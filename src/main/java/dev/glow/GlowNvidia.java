package dev.glow;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Starts NVIDIA Streamline before the game touches Vulkan, routes LWJGL's Vulkan calls through its interposer, and drives DLSS, Reflex, PC Latency and Frame Generation. */
public final class GlowNvidia implements PreLaunchEntrypoint {
	private static final Logger LOGGER = LoggerFactory.getLogger("glow");
	private static final String STREAMLINE = "streamline-2.14.16/bin/x64";
	private static final int FEATURE_DLSS = 0;
	private static final int FEATURE_REFLEX = 3;
	private static final int FEATURE_PCL = 4;
	private static final int FEATURE_DLSS_G = 1000;
	private static final int FEATURE_DLSS_RR = 1001;
	// sl::PCLMarker values.
	public static final int SIMULATION_START = 0;
	public static final int SIMULATION_END = 1;
	public static final int RENDER_SUBMIT_START = 2;
	public static final int RENDER_SUBMIT_END = 3;
	public static final int PRESENT_START = 4;
	public static final int PRESENT_END = 5;
	/** Each image is six words: VkImage, VkImageView, VkFormat, VkImageUsageFlags, width, height. */
	public static final int IMAGE_WORDS = 6;
	public static final int CONSTANT_FLOATS = 114;
	private static final int MAX_IMAGES = 9;
	// Vanilla's Max Framerate slider reads this as unlimited.
	private static final int UNLIMITED_FPS = 260;

	private static boolean active;
	private static boolean dlssSupported, rayReconstructionSupported, frameGenerationSupported, reflexSupported, pclSupported;
	private static boolean dlssFailed, rayReconstructionFailed, frameGenerationFailed, reflexFailed, pclFailed;
	private static boolean frameStarted;
	private static int appliedReflex = -1;
	private static int frameLimitUs, appliedFrameLimitUs = -1;
	// Whether Frame Generation was last set on by the setting, as opposed to paused for a frame Glow didn't trace.
	private static boolean frameGenerationOn;
	private static String appliedFrameGeneration = "";
	private static int frameGenerationMax = 1;
	private static int framesPresented = -1;
	private static long frameGenerationStateCheck;
	private static MethodHandle featureSupported, shutdown, beginFrame, marker, reflexOptions, optimal, setConstants, evaluate, tagScene, tagHudless, frameGenerationOptions, frameGenerationState;
	private static final MemorySegment IMAGE_TABLE = Arena.global().allocate(JAVA_LONG, IMAGE_WORDS * MAX_IMAGES);
	private static final MemorySegment CONSTANTS = Arena.global().allocate(ValueLayout.JAVA_FLOAT, CONSTANT_FLOATS);
	private static final MemorySegment SIZES = Arena.global().allocate(JAVA_INT, 6);
	private static final MemorySegment STATE = Arena.global().allocate(JAVA_INT, 4);
	// Optimal render sizes, keyed by Ray Reconstruction, mode and output size.
	private static long optimalKey = -1L;
	private static int optimalWidth, optimalHeight;

	@Override
	public void onPreLaunch() {
		Path root = Path.of(System.getProperty("glow.nvidia.dir", "../vendor/nvidia")).toAbsolutePath().normalize();
		Path bridge = root.resolve("glow_nvidia.dll"), plugins = root.resolve(STREAMLINE);
		if ("0".equals(System.getenv("GLOW_STREAMLINE"))) {
			LOGGER.info("NVIDIA Streamline off by GLOW_STREAMLINE");
			return;
		}
		if (!Files.exists(bridge) || !Files.exists(plugins.resolve("sl.interposer.dll"))) {
			LOGGER.warn("NVIDIA Streamline not found at {}", root);
			return;
		}
		try {
			Linker linker = Linker.nativeLinker();
			SymbolLookup symbols = SymbolLookup.libraryLookup(bridge, Arena.global());
			MethodHandle init = linker.downcallHandle(symbols.findOrThrow("glow_sl_init"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
			featureSupported = linker.downcallHandle(symbols.findOrThrow("glow_sl_feature_supported"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
			shutdown = linker.downcallHandle(symbols.findOrThrow("glow_sl_shutdown"), FunctionDescriptor.of(JAVA_INT));
			beginFrame = linker.downcallHandle(symbols.findOrThrow("glow_sl_begin_frame"), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
			marker = linker.downcallHandle(symbols.findOrThrow("glow_sl_marker"), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
			reflexOptions = linker.downcallHandle(symbols.findOrThrow("glow_reflex_set_options"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
			optimal = linker.downcallHandle(symbols.findOrThrow("glow_dlss_optimal"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
			setConstants = linker.downcallHandle(symbols.findOrThrow("glow_sl_set_constants"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
			evaluate = linker.downcallHandle(symbols.findOrThrow("glow_dlss_evaluate"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
			tagScene = linker.downcallHandle(symbols.findOrThrow("glow_dlssg_tag_scene"), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
			tagHudless = linker.downcallHandle(symbols.findOrThrow("glow_dlssg_tag_hudless"), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
			frameGenerationOptions = linker.downcallHandle(symbols.findOrThrow("glow_dlssg_set_options"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
			frameGenerationState = linker.downcallHandle(symbols.findOrThrow("glow_dlssg_state"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
			Path logs = Path.of("logs").toAbsolutePath();
			Files.createDirectories(logs);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment pluginPath = arena.allocateFrom(plugins.toString()), logPath = arena.allocateFrom(logs.toString());
				int result = (int) init.invokeExact(pluginPath, logPath);
				if (result != 0) {
					LOGGER.error("Streamline failed to start: {}", result);
					return;
				}
			}
			Configuration.VULKAN_LIBRARY_NAME.set(plugins.resolve("sl.interposer.dll").toString());
			active = true;
			LOGGER.info("Streamline started from {}", plugins);
		} catch (Throwable e) {
			LOGGER.error("Streamline failed to start", e);
		}
	}

	/** Streamline has to stop before the Vulkan device is destroyed. */
	public static void shutdown() {
		if (!active) return;
		active = false;
		try {
			int result = (int) shutdown.invokeExact();
			if (result != 0) LOGGER.error("Streamline failed to shut down: {}", result);
		} catch (Throwable e) {
			LOGGER.error("Streamline failed to shut down", e);
		}
	}

	public static boolean active() {
		return active;
	}

	public static boolean rayReconstructionAvailable() {
		return active && rayReconstructionSupported && !rayReconstructionFailed;
	}

	public static boolean superResolutionAvailable() {
		return active && dlssSupported && !dlssFailed;
	}

	public static boolean reflexAvailable() {
		return active && reflexSupported && !reflexFailed;
	}

	public static boolean frameGenerationAvailable() {
		return active && frameGenerationSupported && !frameGenerationFailed && reflexAvailable();
	}

	/** The most frames Frame Generation can add per rendered frame on this GPU. */
	public static int frameGenerationMax() {
		return frameGenerationMax;
	}

	/** Starts a frame: a new frame token for every feature, Reflex's sleep, then the simulation start marker. Runs at the top of the game loop. */
	public static void beginFrame() {
		if (!active) return;
		frameLimitUs = reflexFrameLimit();
		applyReflex();
		try {
			int result = (int) beginFrame.invokeExact(reflexMode() > 0 ? 1 : 0);
			frameStarted = result == 0;
			if (result != 0) {
				reflexFailed = true;
				LOGGER.error("Streamline could not start a frame: {}", result);
			}
		} catch (Throwable e) {
			frameStarted = false;
			LOGGER.error("Streamline could not start a frame", e);
		}
		marker(SIMULATION_START);
	}

	/** Marks a point in the frame for PC Latency and Reflex. */
	public static void marker(int which) {
		if (!active || !frameStarted || !pclSupported || pclFailed) return;
		try {
			int result = (int) marker.invokeExact(which);
			if (result != 0) {
				pclFailed = true;
				LOGGER.error("PC Latency marker {} failed: {}. Markers are off for this session", which, result);
			}
		} catch (Throwable e) {
			pclFailed = true;
			LOGGER.error("PC Latency marker failed", e);
		}
	}

	/** 0 off, 1 on, 2 on with boost. Frame Generation needs Reflex, so it turns Reflex on. */
	private static int reflexMode() {
		if (!reflexAvailable()) return 0;
		int mode = GlowSettings.Nvidia.REFLEX.value;
		return mode == 0 && frameGenerationWanted() ? 1 : mode;
	}

	private static void applyReflex() {
		int mode = reflexAvailable() ? reflexMode() : 0;
		int limit = mode > 0 ? frameLimitUs : 0;
		if (mode == appliedReflex && limit == appliedFrameLimitUs || !reflexAvailable()) return;
		appliedFrameLimitUs = limit;
		try {
			int result = (int) reflexOptions.invokeExact(mode, limit);
			if (result != 0) {
				reflexFailed = true;
				LOGGER.error("Reflex failed to change mode: {}", result);
				return;
			}
			appliedReflex = mode;
			LOGGER.info("Reflex mode {}", mode);
		} catch (Throwable e) {
			reflexFailed = true;
			LOGGER.error("Reflex failed to change mode", e);
		}
	}

	private static boolean frameGenerationWanted() {
		return GlowSettings.Nvidia.FRAME_GENERATION.value > 0 && active && frameGenerationSupported && !frameGenerationFailed && reflexSupported && !reflexFailed;
	}

	/**
	 * Takes over vanilla's frame limiter while Reflex runs. A sleep after present, outside Reflex, throws off Reflex's latency estimate
	 * and Frame Generation's pacing, so Reflex limits the frame rate instead. Returns false when vanilla should limit as usual.
	 * framerateLimit counts frames on screen, so with Frame Generation the rendered rate is limited to that over the frames each one shows as.
	 */
	public static boolean limitFrameRate(int framerateLimit) {
		return active && reflexMode() > 0;
	}

	/** Reflex's frame limit for vanilla's current one, in microseconds per rendered frame, or 0 for none. */
	private static int reflexFrameLimit() {
		int framerateLimit = Minecraft.getInstance().getFramerateLimitTracker().getFramerateLimit();
		if (framerateLimit <= 0 || framerateLimit >= UNLIMITED_FPS) return 0;
		int shown = frameGenerationActive() ? Math.min(GlowSettings.Nvidia.FRAME_GENERATION.value, frameGenerationMax) + 1 : 1;
		return (int) Math.min(1_000_000L * shown / framerateLimit, Integer.MAX_VALUE);
	}

	/** Frame Generation on Vulkan doesn't support VSync (Streamline's guide, section 22.5), so the swapchain presents without it while generation is on. */
	public static boolean vsyncAllowed() {
		return !frameGenerationWanted();
	}

	/** The render size DLSS wants for this output, as {width, height}. Falls back to the output size if Streamline can't say. */
	public static int[] optimalRenderSize(boolean rayReconstruction, int mode, int outputWidth, int outputHeight) {
		long key = ((long) outputWidth << 32) | ((long) outputHeight << 8) | (mode << 1) | (rayReconstruction ? 1 : 0);
		if (key != optimalKey) {
			optimalKey = key;
			optimalWidth = outputWidth;
			optimalHeight = outputHeight;
			try {
				int result = (int) optimal.invokeExact(rayReconstruction ? 1 : 0, mode, outputWidth, outputHeight, SIZES);
				int width = SIZES.getAtIndex(JAVA_INT, 0), height = SIZES.getAtIndex(JAVA_INT, 1);
				if (result == 0 && width > 0 && height > 0 && width <= outputWidth && height <= outputHeight) {
					optimalWidth = width;
					optimalHeight = height;
				} else {
					LOGGER.warn("DLSS gave no render size for mode {} at {}x{}: {}", mode, outputWidth, outputHeight, result);
				}
			} catch (Throwable e) {
				LOGGER.error("DLSS render size query failed", e);
			}
			LOGGER.info("DLSS {} mode {}: {}x{} to {}x{}", rayReconstruction ? "Ray Reconstruction" : "Super Resolution", mode, optimalWidth, optimalHeight, outputWidth, outputHeight);
		}
		return new int[] {optimalWidth, optimalHeight};
	}

	/** Sets this frame's camera for DLSS and Frame Generation. Returns false if Streamline refused it. */
	public static boolean setConstants(float[] constants, boolean reset) {
		if (!active || !frameStarted) return false;
		MemorySegment.copy(constants, 0, CONSTANTS, ValueLayout.JAVA_FLOAT, 0L, constants.length);
		try {
			int result = (int) setConstants.invokeExact(CONSTANTS, reset ? 1 : 0);
			if (result != 0) LOGGER.warn("Streamline refused this frame's constants: {}", result);
			return result == 0;
		} catch (Throwable e) {
			LOGGER.error("Streamline constants failed", e);
			return false;
		}
	}

	/** Records DLSS into the command buffer. A failure turns that feature off for the session, so Glow's own path takes over. */
	public static void evaluate(boolean rayReconstruction, int mode, long commandBuffer, long[] images, int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		MemorySegment.copy(images, 0, IMAGE_TABLE, JAVA_LONG, 0L, images.length);
		String name = rayReconstruction ? "DLSS Ray Reconstruction" : "DLSS Super Resolution";
		try {
			int result = (int) evaluate.invokeExact(rayReconstruction ? 1 : 0, mode, commandBuffer, IMAGE_TABLE, renderWidth, renderHeight, outputWidth, outputHeight);
			if (result == 0) return;
			LOGGER.error("{} failed: {}. See run/logs/sl.log", name, result);
		} catch (Throwable e) {
			LOGGER.error("{} failed", name, e);
		}
		if (rayReconstruction) rayReconstructionFailed = true; else dlssFailed = true;
	}

	/** Tags depth and motion for Frame Generation. */
	public static void tagScene(long commandBuffer, long[] images, int renderWidth, int renderHeight) {
		if (!frameGenerationActive()) return;
		MemorySegment.copy(images, 0, IMAGE_TABLE, JAVA_LONG, 0L, images.length);
		try {
			int result = (int) tagScene.invokeExact(commandBuffer, IMAGE_TABLE, renderWidth, renderHeight);
			if (result != 0) failFrameGeneration("could not tag depth and motion: " + result, null);
		} catch (Throwable e) {
			failFrameGeneration("could not tag depth and motion", e);
		}
	}

	/** Tags the frame before the interface draws, so Frame Generation can tell the interface apart. */
	public static void tagHudless(long commandBuffer, long[] image) {
		if (!frameGenerationActive()) return;
		MemorySegment.copy(image, 0, IMAGE_TABLE, JAVA_LONG, 0L, image.length);
		try {
			int result = (int) tagHudless.invokeExact(commandBuffer, IMAGE_TABLE);
			if (result != 0) failFrameGeneration("could not tag the frame without its interface: " + result, null);
		} catch (Throwable e) {
			failFrameGeneration("could not tag the frame without its interface", e);
		}
	}

	public static boolean frameGenerationActive() {
		return frameStarted && !appliedFrameGeneration.isEmpty() && !appliedFrameGeneration.startsWith("0 ");
	}

	/** Turns Frame Generation on or off to match the setting, once per change. Call each traced frame with this frame's sizes and formats. */
	public static void updateFrameGeneration(int renderWidth, int renderHeight, int outputWidth, int outputHeight, int colorFormat, int depthFormat, int motionFormat) {
		if (!active || !frameGenerationSupported) return;
		int frames = frameGenerationWanted() ? Math.min(GlowSettings.Nvidia.FRAME_GENERATION.value, frameGenerationMax) : 0;
		String wanted = frames + " " + renderWidth + "x" + renderHeight + " " + outputWidth + "x" + outputHeight;
		if (!wanted.equals(appliedFrameGeneration)) {
			try {
				int result = (int) frameGenerationOptions.invokeExact(frames, renderWidth, renderHeight, outputWidth, outputHeight, colorFormat, depthFormat, motionFormat);
				if (result != 0) {
					failFrameGeneration("failed to change mode: " + result, null);
					return;
				}
				appliedFrameGeneration = wanted;
				LOGGER.info("DLSS Frame Generation {}", frames > 0 ? frames + " extra frame" + (frames > 1 ? "s" : "") : "off");
				// Streamline's guide asks for a new swapchain when generation turns on or off. Reconfiguring also applies the present mode vsyncAllowed picks.
				if (frameGenerationOn != frames > 0) {
					frameGenerationOn = frames > 0;
					Minecraft.getInstance().invalidateSurfaceConfiguration();
				}
			} catch (Throwable e) {
				failFrameGeneration("failed to change mode", e);
				return;
			}
		}
		// The state can say why generation isn't happening, such as a window too small. Check it about once a second.
		long now = System.nanoTime();
		if (frames > 0 && now - frameGenerationStateCheck > 1_000_000_000L) {
			frameGenerationStateCheck = now;
			try {
				int result = (int) frameGenerationState.invokeExact(STATE);
				int status = STATE.getAtIndex(JAVA_INT, 0), presented = STATE.getAtIndex(JAVA_INT, 1);
				if (result == 0 && status != 0) LOGGER.warn("DLSS Frame Generation status {}, presented {} frames", status, presented);
				else if (result == 0 && presented != framesPresented) LOGGER.info("DLSS Frame Generation presents {} frames per rendered frame", presented);
				framesPresented = presented;
			} catch (Throwable e) {
				LOGGER.error("DLSS Frame Generation state failed", e);
			}
		}
	}

	/** Turns generation off for a frame with no camera, depth or motion, such as one Glow didn't trace. The next traced frame turns it back on. */
	public static void pauseFrameGeneration() {
		if (!frameGenerationActive()) return;
		try {
			int result = (int) frameGenerationOptions.invokeExact(0, 0, 0, 0, 0, 0, 0, 0);
			if (result != 0) {
				failFrameGeneration("failed to pause: " + result, null);
				return;
			}
			appliedFrameGeneration = "0 paused";
		} catch (Throwable e) {
			failFrameGeneration("failed to pause", e);
		}
	}

	private static void failFrameGeneration(String message, Throwable cause) {
		frameGenerationFailed = true;
		if (cause != null) LOGGER.error("DLSS Frame Generation {}", message, cause);
		else LOGGER.error("DLSS Frame Generation {}. See run/logs/sl.log", message);
		try {
			int ignored = (int) frameGenerationOptions.invokeExact(0, 0, 0, 0, 0, 0, 0, 0);
		} catch (Throwable ignored) {
		}
		appliedFrameGeneration = "";
	}

	/** Logs what this GPU and driver can use. Call once the Vulkan device exists. */
	public static void logSupport(long physicalDevice) {
		if (!active) return;
		dlssSupported = logFeature("DLSS Super Resolution", FEATURE_DLSS, physicalDevice);
		rayReconstructionSupported = logFeature("DLSS Ray Reconstruction", FEATURE_DLSS_RR, physicalDevice);
		frameGenerationSupported = logFeature("DLSS Frame Generation", FEATURE_DLSS_G, physicalDevice);
		reflexSupported = logFeature("Reflex", FEATURE_REFLEX, physicalDevice);
		pclSupported = logFeature("PC Latency", FEATURE_PCL, physicalDevice);
		if (frameGenerationSupported) {
			try {
				int result = (int) frameGenerationState.invokeExact(STATE);
				if (result == 0 && STATE.getAtIndex(JAVA_INT, 2) > 0) frameGenerationMax = STATE.getAtIndex(JAVA_INT, 2);
				LOGGER.info("DLSS Frame Generation can add up to {} frames", frameGenerationMax);
			} catch (Throwable e) {
				LOGGER.error("DLSS Frame Generation state failed", e);
			}
		}
	}

	private static boolean logFeature(String name, int feature, long physicalDevice) {
		try {
			int result = (int) featureSupported.invokeExact(feature, physicalDevice);
			LOGGER.info("Streamline {}: {}", name, result == 0 ? "supported" : "unavailable, result " + result);
			return result == 0;
		} catch (Throwable e) {
			LOGGER.error("Streamline support check failed for {}", name, e);
			return false;
		}
	}
}
