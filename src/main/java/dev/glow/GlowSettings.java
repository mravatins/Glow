package dev.glow;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.FogType;

import static org.lwjgl.system.MemoryUtil.memPutFloat;

/** Every ray tracing setting the player can change, what each level costs, and which vanilla options have to give way to them. */
public final class GlowSettings {
	/** Which renderer draws the world. Each mode has its own settings menu. */
	public enum RenderMode {
		OFF("Off", "Minecraft's own lighting and options."),
		RAY_TRACING("Ray Tracing", "Traced shadows, light and reflections, each with its own quality setting. Faster, and approximates some light."),
		PATH_TRACING("Path Tracing", "All light follows full paths through the scene, with many bounces. The most accurate, and the slowest.");

		public final String label;
		public final String tooltip;

		RenderMode(String label, String tooltip) {
			this.label = label;
			this.tooltip = tooltip;
		}
	}

	/** Path Tracing's own settings. Each value is an index into its labels and numbers. */
	public enum PathSetting {
		SAMPLES("Samples per Pixel", "Paths traced for each pixel, each frame. Cost grows in step with this.", 0, new String[] {"1", "2", "4", "8"}, new float[] {1.0F, 2.0F, 4.0F, 8.0F}),
		BOUNCES("Max Bounces", "Surfaces a path can land on before it stops. More lets light reach deeper into rooms and caves. Dim paths stop early at random, so high values cost less than they sound.", 2, new String[] {"2", "3", "4", "6", "8", "12"}, new float[] {2.0F, 3.0F, 4.0F, 6.0F, 8.0F, 12.0F}),
		FIREFLY_LIMIT("Firefly Limit", "Caps how bright one path can be, which removes white specks. Low values dim small bright reflections and lights.", 2, new String[] {"Off", "High", "Medium", "Low"}, new float[] {0.0F, 64.0F, 16.0F, 4.0F}),
		ACCUMULATION("Accumulation", "While the view stays still, frames average together until the image is clean, up to this many frames. Moving the camera starts over. Doesn't run with DLSS, which does its own accumulation.", 3, new String[] {"Off", "64", "256", "1024", "4096"}, new float[] {1.0F, 64.0F, 256.0F, 1024.0F, 4096.0F});

		public final String label;
		public final String tooltip;
		public final String[] labels;
		private final float[] numbers;
		private final int defaultValue;
		public int value;

		PathSetting(String label, String tooltip, int defaultValue, String[] labels, float[] numbers) {
			this.label = label;
			this.tooltip = tooltip;
			this.defaultValue = defaultValue;
			this.labels = labels;
			this.numbers = numbers;
			this.value = defaultValue;
		}

		public float number() {
			return this.numbers[this.value];
		}

		public Component component(int index) {
			return Component.literal(this.labels[index]).withStyle(ChatFormatting.GREEN);
		}
	}

	// Path Tracing's presets as indexes into Samples per Pixel and Max Bounces, in the order of Preset.
	private static final int[][] PATH_PRESETS = {{0, 0}, {0, 2}, {1, 3}, {2, 5}};
	private static final float PATH_LIGHT_CANDIDATES = 8.0F;

	/** Whether Path Tracing is the chosen renderer. GlowRenderer.enabled still says whether any renderer runs. */
	public static boolean pathTracing = false;

	public enum Quality {
		OFF("Off", ChatFormatting.GRAY),
		LOW("Low", ChatFormatting.YELLOW),
		MEDIUM("Medium", ChatFormatting.GOLD),
		HIGH("High", ChatFormatting.GREEN),
		ULTRA("Ultra", ChatFormatting.AQUA);

		public final String label;
		private final ChatFormatting color;

		Quality(String label, ChatFormatting color) {
			this.label = label;
			this.color = color;
		}

		public Component component() {
			return Component.literal(this.label).withStyle(this.color);
		}
	}

	public enum Preset {
		LOW("Low", Quality.LOW),
		MEDIUM("Medium", Quality.MEDIUM),
		HIGH("High", Quality.HIGH),
		ULTRA("Ultra", Quality.ULTRA),
		CUSTOM("Custom", null);

		public final Quality quality;
		private final String label;

		Preset(String label, Quality quality) {
			this.label = label;
			this.quality = quality;
		}

		public Component component() {
			return this.quality == null ? Component.literal(this.label).withStyle(ChatFormatting.LIGHT_PURPLE) : this.quality.component();
		}
	}

	public enum Option {
		SUN_SHADOWS("Sun Shadows", "Shadows cast by the sun and moon.", "Falls back to vanilla's light levels, with no real shadows.", "Slightly soft shadows, which stay clean at low render sizes, across your render distance.", "Soft shadows, across your render distance.", "Soft shadows, across your render distance.", "Cleanest soft shadows with two rays per pixel, across your render distance."),
		GLOBAL_ILLUMINATION("Global Illumination", "Sky light and light bouncing off nearby surfaces.", "Sky light comes from vanilla's light levels, with no bounced light.", "Sky, shade and bounces. Sunlight on what bounces hit is estimated from how open to the sky it is, not traced.", "Sunlit bounces, traced.", "Sunlit bounces, with more reuse.", "Two rays per pixel. Every level reaches your render distance."),
		BLOCK_LIGHTS("Block Lights", "Traced light and shadows from torches, lava and other light blocks.", "Uses vanilla's smoothed light levels, with no shadows from torches.", "Weighs up to 4 of the lights in reach for each pixel.", "Up to 8, which covers nearly every torch-lit room.", "Up to 16, for dense lighting.", "Up to 32, for lava lakes and walls of lamps. Every level reaches your render distance."),
		LIGHT_SHAFTS("Light Shafts", "Sunlight scattering through the air, traced at half the render size because shafts are soft.", "No light shafts.", "One ray per traced pixel, reaching your render distance.", "One ray per traced pixel, reaching your render distance.", "One ray per traced pixel, reaching your render distance.", "Two rays per traced pixel, reaching your render distance."),
		REFLECTIONS("Reflections", "Traced reflections on water, glass, metal and shiny blocks.", "Water and glass use vanilla's blending, and nothing else reflects.", "Only the shiniest surfaces.", "Shiny surfaces.", "Shiny and semi-rough surfaces.", "Almost every reflective surface. Every level reflects out to your render distance."),
		MATERIALS("Material Detail", "How much of your resource pack's labPBR maps is used.", "Ignores the pack's labPBR maps.", "Normal maps.", "Adds reflectivity, roughness and glowing surfaces.", "Adds porous surfaces, which let sunlight a little way in.", "The same as High. Every level reaches your render distance."),
		PARALLAX("Parallax", "Real depth from your resource pack's height maps. Packs without height data show little change.", "Flat textures.", "Close range only, within 10 blocks.", "Within 16 blocks, with self-shadowing.", "Within 24 blocks, with finer self-shadowing.", "Within 48 blocks, with the finest detail."),
		FOLIAGE("Foliage Light", "Light passing through leaves and plants.", "Leaves block light completely, which is the fastest in forests.", "A little light gets through. No glow on the far side.", "Light gets through, with a soft glow on the far side.", "Canopy edges stay bright, with a full glow on the far side.", "The same as High. Nothing more to gain here yet."),
		WATER("Enhanced Water", "Realistic clear water: a moving 3D surface the rays meet, wind waves that calm in shallow and sheltered water, flowing rivers, white-water waterfalls, rain rings, light absorbed and scattered by depth, glittering sun, and caustics below. Replaces the water texture. Needs Reflections.", "Simple traced water with small ripples.", "Moving waves with physical colour, refraction, sun glitter, flow and white water.", "Adds finer ripples and clouds in reflections.", "Adds sunlight scattering inside the water and the finest ripples near you.", "The most wave detail at every distance."),
		CLOUD_SHADOWS("Cloud Shadows", "Shadows from vanilla's clouds moving across the ground.", "No cloud shadows.", "Hard, blocky edges.", "Blended edges.", "Soft edges.", "Softest edges."),
		DENOISER("Denoiser", "Cleans up the grain from tracing few rays per pixel. Higher levels are smoother but can lose fine detail.", "Shows the raw ray traced image. Very grainy, and useful for checking what the rays really do.", "Fastest. Some grain stays.", "Balanced.", "Clean image.", "Cleanest image, using the widest filter.");

		public final String label;
		private final String summary;
		private final String[] levels;
		public Quality value = Quality.MEDIUM;

		Option(String label, String summary, String... levels) {
			this.label = label;
			this.summary = summary;
			this.levels = levels;
		}

		public String tooltip(Quality quality) {
			return this.summary + "\n\n" + quality.label + ": " + this.levels[quality.ordinal()];
		}
	}

	/** Limits that override what the quality settings pick. Steps are absolute values, and a distance of 0 means Auto: follow the quality setting. */
	public enum Limit {
		SHADOW_DISTANCE("Shadow Distance", "How far sun shadow rays reach. Anything further can't cast a shadow, so low values let light through distant blocks. Rays can't hit past your render distance.", distances()),
		GI_DISTANCE("GI Ray Distance", "How far bounce light rays reach. A ray that finds nothing counts as open sky, so low values make enclosed spaces too bright.", distances()),
		REFLECTION_DISTANCE("Reflection Distance", "How far reflected rays reach. Beyond it, reflections show the sky.", distances()),
		GI_BOUNCES("GI Bounces", "Surfaces a bounce light ray traces before it stops. The last one reads the Radiance Cache if that's on. Each extra hit costs more rays per pixel and adds noise.", bounces()),
		REFLECTION_BOUNCES("Reflection Bounces", "Mirrors a reflection can follow, as in a mirror seen in a mirror. Costs extra only where a reflection lands on something shiny.", bounces()),
		CACHED_GI_DISTANCE("Cached GI Distance", "Distance where bounce light switches from traced rays to the Radiance Cache. Closer is faster, further keeps more fine detail in contact shadows.", new int[] {0, 8, 16, 24, 32, 48, 64, 96, 128});

		public final String label;
		public final String tooltip;
		public final int[] steps;
		public int value;

		Limit(String label, String tooltip, int[] steps) {
			this.label = label;
			this.tooltip = tooltip;
			this.steps = steps;
			this.value = steps[0];
		}

		private static int[] distances() {
			return new int[] {0, 16, 32, 64, 128, 256, 512, 1024, 2048};
		}

		private static int[] bounces() {
			return new int[] {1, 2, 3, 4, 6, 8};
		}

		public String display(int step) {
			if (this == GI_BOUNCES || this == REFLECTION_BOUNCES) return Integer.toString(step);
			return step == 0 ? "Auto" : step + " blocks";
		}

		/** The quality setting's own value, unless it's off (0) or this limit overrides it. */
		float over(float auto) {
			return auto <= 0.0F || this.value == 0 ? auto : this.value;
		}
	}

	public enum Tuning {
		EXPOSURE("Exposure", "Overall brightness of the ray traced image. This replaces vanilla's Brightness option.", 0.5, 2.0),
		SUN_SOFTNESS("Sun Softness", "How blurred shadow edges get. Larger values look like a bigger sun.", 0.0, 4.0),
		BLOCK_LIGHT_STRENGTH("Block Light Strength", "How bright torches and other light blocks are.", 0.25, 3.0),
		SHAFT_DENSITY("Light Shaft Density", "How thick the air looks in sunlight.", 0.25, 4.0),
		PARALLAX_DEPTH("Parallax Depth", "How deep height maps appear.", 0.25, 2.0),
		WAVE_HEIGHT("Wave Height", "How rough Enhanced Water's surface is. Low values give a calm, glassy surface.", 0.0, 3.0),
		FOG_DENSITY("Fog Density", "How thick the ground mist and far haze are. The default is light; 10 is the old, heavy mist. At the lowest, terrain still fades out at the render distance, with no mist.", 0.0, 10.0);

		public final String label;
		public final String tooltip;
		public final double min, max;
		public double value = 1.0;

		Tuning(String label, String tooltip, double min, double max) {
			this.label = label;
			this.tooltip = tooltip;
			this.min = min;
			this.max = max;
		}
	}

	/** Individual techniques that can be turned on or off, separate from the quality levels and presets. */
	public enum Toggle {
		RESTIR_GI("ReSTIR GI", "Reuses good bounce-light samples between pixels and frames, for cleaner light from the same number of rays. Off, each pixel uses only its own rays.", true),
		RESTIR_CORNER_LIMITS("ReSTIR Corner Limits", "Stops ReSTIR GI sharing samples across inner corners, and limits how much one sample can add, for fewer white specks. Off is plain ReSTIR.", true),
		RESTIR_DI("ReSTIR Block Lights", "Reuses the choice of light between pixels and frames, so many torches stay clean. Off, each pixel picks one light with no reuse, which is noisier.", true),
		RADIANCE_CACHE("Radiance Cache", "Stores light in the world so bounces can carry more bounces. Off uses vanilla's light levels where a bounce ray stops, which can make rooms much too bright unless GI Bounces is raised.", true),
		AUTO_EXPOSURE("Auto Exposure", "Adapts brightness to the view, part of the way, as eyes do. Off keeps a fixed exposure.", true),
		DLSS_LOD_BIAS("DLSS LOD Bias", "Terrain textures sample one mip level sharper (-1) while DLSS or Ray Reconstruction is on. Off uses the same level as without DLSS.", true),
		WATER_TRACED_BOUNCE("Water Traced Bounce", "Whatever is reflected in water traces its own bounce light. Accurate, but grainy without Ray Reconstruction. Off reads the Radiance Cache, which is smooth but can show flickering, block-shaped patches.", true),
		CAUSTIC_LIMIT("Caustic Limit", "Limits how bright and dark caustics get, which softens the thin bright rings at their focus. Off leaves them unlimited.", false),
		DISTANCE_FOG("Distance Fog", "Ground mist over water and in valleys, and far haze that follows your render distance, so terrain and water fade into the sky instead of ending at a hard edge.", true),
		TRACED_EFFECTS("Traced Particles and Rain", "Shows particles and weather in reflections, and lets particles cast shadows. Off is slightly faster when many are on screen.", true),
		CACHED_SUN("Cached Sun Shadows", "Remembers where the world is fully lit or fully shaded, so most pixels skip their sun rays. Shadow edges and moving things are still traced each frame. Off traces every pixel. Measured no faster so far, so it is off by default.", false),
		CACHED_GI("Cached Far GI", "Past the Cached GI Distance, bounce light comes from the Radiance Cache instead of rays from every pixel. Much faster on open views. Off traces bounce light at every distance.", false),
		MERGED_GEOMETRY("Merged Bounce Geometry", "Bounce and shadow rays use a simplified copy of each chunk, with flat runs of the same block merged into large faces, and slightly averaged colour in bounce light. Measured slightly slower so far, because every chunk section is then in the scene twice, so it is off by default. Changing it rebuilds the world's meshes.", false);

		public final String label;
		public final String tooltip;
		private final boolean defaultValue;
		public boolean value;

		Toggle(String label, String tooltip, boolean defaultValue) {
			this.label = label;
			this.tooltip = tooltip;
			this.defaultValue = defaultValue;
			this.value = defaultValue;
		}
	}

	/** NVIDIA's own features. Each value is an index into its labels, and 0 is always off. */
	public enum Nvidia {
		DLSS("DLSS", "NVIDIA's AI upscaling. Glow traces fewer pixels, and DLSS rebuilds the full image from them.",
			new String[] {"Off", "DLAA", "Quality", "Balanced", "Performance", "Ultra Performance", "Auto"},
			new String[] {"Every pixel is traced, and Glow's own denoiser finishes the image.", "Every pixel is traced, with DLSS antialiasing. The sharpest, at the highest cost.", "Traces about 44% of the pixels.", "Traces about 34% of the pixels.", "Traces a quarter of the pixels.", "Traces a ninth of the pixels. Meant for 4K and above.", "Picks by resolution: Quality up to 1080p, Balanced up to 1440p, Performance up to 4K, Ultra Performance above that."}),
		RAY_RECONSTRUCTION("Ray Reconstruction", "NVIDIA's AI denoiser. It replaces Glow's own denoiser, and does the upscaling too, in the DLSS setting's mode. Light shafts keep Glow's filter, because they're added afterwards.",
			new String[] {"Off", "On"},
			new String[] {"Glow's own denoiser cleans the image, at the Denoiser setting's level.", "Ray Reconstruction cleans the image, and the Denoiser setting is locked."}),
		REFLEX("NVIDIA Reflex", "Cuts the delay between your input and the screen by keeping the CPU from running ahead of the GPU.",
			new String[] {"Off", "On", "On + Boost"},
			new String[] {"No latency reduction.", "Lower latency.", "Lower latency, and the GPU clocks stay high even when the CPU holds it up."}),
		FRAME_GENERATION("Frame Generation", "DLSS makes extra frames between the ones Glow renders. Motion looks smoother, at a little extra latency. Turns Reflex on.",
			new String[] {"Off", "2x", "3x", "4x"},
			new String[] {"Only rendered frames are shown.", "One generated frame per rendered frame.", "Two generated frames per rendered frame. Needs an RTX 50 series card.", "Three generated frames per rendered frame. Needs an RTX 50 series card."});

		public final String label;
		private final String summary;
		public final String[] values;
		private final String[] descriptions;
		public int value;

		Nvidia(String label, String summary, String[] values, String[] descriptions) {
			this.label = label;
			this.summary = summary;
			this.values = values;
			this.descriptions = descriptions;
		}

		public String tooltip(int index) {
			String description = this == DLSS && index == 0 && rayReconstruction() ? "Every pixel is traced, and Ray Reconstruction cleans them. The same as DLAA while it's on." : this.descriptions[index];
			return this.summary + "\n\n" + this.values[index] + ": " + description;
		}

		public Component component(int index) {
			return Component.literal(this.values[index]).withStyle(index == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN);
		}
	}

	// Indexed by Quality.
	// A sharp shadow costs the same ray as a soft one. Low's is half as wide, so its edges need fewer samples to come out clean.
	private static final float[] SUN_RADIUS = {0.0F, 0.006F, 0.012F, 0.012F, 0.012F};
	private static final float[] SUN_SAMPLES = {1.0F, 1.0F, 1.0F, 1.0F, 2.0F};
	private static final float[] GI_RAYS = {1.0F, 1.0F, 1.0F, 1.0F, 2.0F};
	private static final float[] GI_SUNLIT_BOUNCE = {0.0F, 0.0F, 1.0F, 1.0F, 1.0F};
	private static final float[] RESTIR_SPATIAL_SAMPLES = {0.0F, 1.0F, 2.0F, 3.0F, 5.0F};
	private static final float[] RESTIR_MAX_M = {1.0F, 10.0F, 20.0F, 20.0F, 30.0F};
	private static final float[] REFLECTION_ROUGHNESS = {0.0F, 0.2F, 0.4F, 0.6F, 0.8F};
	private static final float[] LIGHT_CANDIDATES = {1.0F, 4.0F, 8.0F, 16.0F, 32.0F};
	private static final float[] SHAFT_SAMPLES = {1.0F, 1.0F, 1.0F, 1.0F, 2.0F};
	private static final float[] FOLIAGE_THROUGH = {0.0F, 0.7F, 0.85F, 1.0F, 1.0F};
	private static final float[] FOLIAGE_GLOW = {0.0F, 0.0F, 0.6F, 1.0F, 1.0F};
	private static final float[] PARALLAX_MIN_STEPS = {8.0F, 8.0F, 12.0F, 16.0F, 32.0F};
	private static final float[] PARALLAX_MAX_STEPS = {16.0F, 16.0F, 32.0F, 48.0F, 96.0F};
	private static final float[] PARALLAX_FADE_START = {6.0F, 6.0F, 12.0F, 16.0F, 32.0F};
	private static final float[] PARALLAX_FADE_END = {10.0F, 10.0F, 16.0F, 24.0F, 48.0F};
	private static final float[] PARALLAX_SHADOW_STEPS = {0.0F, 0.0F, 4.0F, 8.0F, 16.0F};
	private static final float[] WATER_OCTAVES = {0.0F, 8.0F, 11.0F, 14.0F, 18.0F};
	private static final float LIGHT_INTENSITY = 0.6F; // A torch lights a small pool around it, not a room as bright as day.
	// A-trous passes per signal: shadow, GI, reflection, light shafts. Each count must stay odd, because the composite pass reads the first filter image.
	private static final int[][] DENOISE_PASSES = {{1, 1, 1, 1}, {1, 3, 1, 1}, {3, 3, 3, 3}, {3, 5, 3, 3}, {5, 5, 5, 5}};
	public static final int PARAM_VECTORS = 9;

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("glow.properties");
	private static final boolean TEMPORARY = System.getenv("GLOW_AUTOTEST") != null;
	private static final Properties VANILLA_BACKUP = new Properties();

	private GlowSettings() {}

	/** The level in effect, which is Off when something this setting depends on is off. */
	public static Quality level(Option option) {
		// A path traces all of these at every bounce, so they have no levels of their own in this mode.
		if (pathTraced() && (option == Option.SUN_SHADOWS || option == Option.GLOBAL_ILLUMINATION || option == Option.BLOCK_LIGHTS || option == Option.REFLECTIONS || option == Option.FOLIAGE)) return Quality.ULTRA;
		return blockedReason(option) != null ? Quality.OFF : option.value;
	}

	public static boolean on(Option option) {
		return level(option) != Quality.OFF;
	}

	/** Why a setting can't be used right now, or null when it can. */
	public static String blockedReason(Option option) {
		if (!GlowRenderer.enabled) return "Turn on Ray Tracing to use this.";
		return switch (option) {
			case WATER -> level(Option.REFLECTIONS) == Quality.OFF ? "Enhanced Water needs Reflections, which trace what the water shows." : null;
			case PARALLAX -> Option.MATERIALS.value == Quality.OFF ? "Parallax needs Material Detail, which supplies the height maps." : null;
			case LIGHT_SHAFTS -> level(Option.SUN_SHADOWS) == Quality.OFF ? "Light Shafts need Sun Shadows, which block the light that forms them." : null;
			case CLOUD_SHADOWS -> {
				if (level(Option.SUN_SHADOWS) == Quality.OFF) yield "Cloud Shadows need Sun Shadows.";
				yield Minecraft.getInstance().options.cloudStatus().get() == CloudStatus.OFF ? "Clouds are off. Turn them on in the World tab." : null;
			}
			case DENOISER -> rayReconstruction() ? "Ray Reconstruction is cleaning the image. Turn it off in NVIDIA RTX to use Glow's own denoiser." : null;
			default -> null;
		};
	}

	public static String blockedReason(Nvidia setting) {
		if (!GlowRenderer.enabled) return "Turn on Ray Tracing to use this.";
		if (!GlowNvidia.active()) return "Needs an NVIDIA RTX card, with Streamline installed next to Glow.";
		return switch (setting) {
			case DLSS -> GlowNvidia.superResolutionAvailable() || GlowNvidia.rayReconstructionAvailable() ? null : "This card or driver doesn't support DLSS.";
			case RAY_RECONSTRUCTION -> GlowNvidia.rayReconstructionAvailable() ? null : "This card or driver doesn't support Ray Reconstruction.";
			case REFLEX -> {
				if (!GlowNvidia.reflexAvailable()) yield "This card or driver doesn't support Reflex.";
				yield Nvidia.FRAME_GENERATION.value > 0 && Nvidia.REFLEX.value == 0 && GlowNvidia.frameGenerationAvailable() ? "Frame Generation keeps Reflex on." : null;
			}
			case FRAME_GENERATION -> GlowNvidia.frameGenerationAvailable() ? null : "This card or driver doesn't support DLSS Frame Generation.";
		};
	}

	/** Whether Ray Reconstruction replaces Glow's denoiser. A saved On is ignored on a card without it. */
	public static boolean rayReconstruction() {
		return Nvidia.RAY_RECONSTRUCTION.value > 0 && GlowNvidia.rayReconstructionAvailable();
	}

	/** The denoiser level in effect. Under Ray Reconstruction only light shafts use Glow's filter, with one pass; see denoisePasses. */
	public static Quality denoiserLevel() {
		return rayReconstruction() ? Quality.ULTRA : Option.DENOISER.value;
	}

	public static void resetNvidia() {
		for (Nvidia setting : Nvidia.values()) setting.value = 0;
	}

	private static final int DLSS_QUALITY = 2, DLSS_PERFORMANCE = 4, DLSS_AUTO = 6;
	private static final int SETTINGS_VERSION = 2;

	/** What ray tracing looks and runs best with: tracing fewer pixels leaves time for the rays that matter, and Ray Reconstruction rebuilds the image cleanly. */
	public static void applyRecommendedNvidia() {
		resetNvidia();
		Nvidia.RAY_RECONSTRUCTION.value = 1;
		Nvidia.DLSS.value = DLSS_AUTO;
		Nvidia.REFLEX.value = 1;
	}

	/**
	 * A preset covers the upscaler as well: the render size sets the cost of every ray and of Ray Reconstruction, so it moves frame rate more than
	 * any ray setting does. Medium is Auto. Low traces one step fewer pixels than Auto would at this window size, High one step more, and Ultra
	 * asks for image quality first.
	 */
	public static void applyPresetWithUpscaling(Preset preset) {
		applyPreset(preset);
		if (preset.quality == null) return;
		applyRecommendedNvidia();
		int auto = dlssMode(Minecraft.getInstance().getWindow().getHeight());
		switch (preset) {
			case LOW -> Nvidia.DLSS.value = Math.max(auto, Math.min(auto + 1, DLSS_PERFORMANCE));
			case HIGH -> Nvidia.DLSS.value = Math.max(auto - 1, DLSS_QUALITY);
			case ULTRA -> Nvidia.DLSS.value = DLSS_QUALITY;
			default -> {}
		}
	}

	/** The DLSS setting as a fixed mode for this output height. Auto keeps the render height near 720p to 1080p, where a frame stays affordable. */
	public static int dlssMode(int outputHeight) {
		if (Nvidia.DLSS.value != DLSS_AUTO) return Nvidia.DLSS.value;
		return outputHeight <= 1080 ? DLSS_QUALITY : outputHeight <= 1600 ? 3 : outputHeight <= 2160 ? 4 : 5;
	}

	public static String blockedReason(Tuning tuning) {
		if (!GlowRenderer.enabled) return "Turn on Ray Tracing to use this.";
		return switch (tuning) {
			case EXPOSURE -> null;
			case SUN_SOFTNESS -> on(Option.SUN_SHADOWS) ? null : "Turn on Sun Shadows to use this.";
			case BLOCK_LIGHT_STRENGTH -> null;
			case SHAFT_DENSITY -> on(Option.LIGHT_SHAFTS) ? null : "Turn on Light Shafts to use this.";
			case FOG_DENSITY -> Toggle.DISTANCE_FOG.value ? null : "Turn on Distance Fog to use this.";
			case PARALLAX_DEPTH -> on(Option.PARALLAX) ? null : "Turn on Parallax to use this.";
			case WAVE_HEIGHT -> on(Option.WATER) ? null : "Turn on Enhanced Water to use this.";
		};
	}

	public static Preset currentPreset() {
		for (Preset preset : Preset.values()) {
			if (preset.quality == null) continue;
			boolean matches = true;
			for (Option option : Option.values()) matches &= option.value == preset.quality;
			if (matches) return preset;
		}
		return Preset.CUSTOM;
	}

	public static void applyPreset(Preset preset) {
		if (preset.quality == null) return;
		for (Option option : Option.values()) option.value = preset.quality;
	}

	public static RenderMode mode() {
		return !GlowRenderer.enabled ? RenderMode.OFF : pathTracing ? RenderMode.PATH_TRACING : RenderMode.RAY_TRACING;
	}

	public static boolean pathTraced() {
		return GlowRenderer.enabled && pathTracing;
	}

	/** Moving between the two traced modes keeps vanilla's options and meshes. Only Off hands them back. */
	public static void setMode(RenderMode mode) {
		if (mode != RenderMode.OFF) pathTracing = mode == RenderMode.PATH_TRACING;
		setEnabled(mode != RenderMode.OFF);
		save();
	}

	public static Preset currentPathPreset() {
		for (Preset preset : Preset.values()) {
			if (preset.quality == null) continue;
			int[] values = PATH_PRESETS[preset.ordinal()];
			if (PathSetting.SAMPLES.value == values[0] && PathSetting.BOUNCES.value == values[1]) return preset;
		}
		return Preset.CUSTOM;
	}

	public static void applyPathPreset(Preset preset) {
		if (preset.quality == null) return;
		PathSetting.SAMPLES.value = PATH_PRESETS[preset.ordinal()][0];
		PathSetting.BOUNCES.value = PATH_PRESETS[preset.ordinal()][1];
	}

	public static void resetPathTracing() {
		for (PathSetting setting : PathSetting.values()) setting.value = setting.defaultValue;
		for (Option option : new Option[] {Option.MATERIALS, Option.PARALLAX, Option.WATER, Option.LIGHT_SHAFTS, Option.CLOUD_SHADOWS, Option.DENOISER}) option.value = Quality.MEDIUM;
		for (Tuning tuning : Tuning.values()) tuning.value = 1.0;
		for (Toggle toggle : Toggle.values()) toggle.value = toggle.defaultValue;
	}

	/** Changes whenever a setting that alters the image does, which restarts accumulation. */
	public static int fingerprint() {
		int hash = 1;
		for (Option option : Option.values()) hash = 31 * hash + option.value.ordinal();
		for (Tuning tuning : Tuning.values()) hash = 31 * hash + Double.hashCode(tuning.value);
		for (Toggle toggle : Toggle.values()) if (toggle != Toggle.RESTIR_GI) hash = 31 * hash + (toggle.value ? 1 : 0);
		for (Limit limit : Limit.values()) hash = 31 * hash + limit.value;
		for (Nvidia setting : Nvidia.values()) hash = 31 * hash + setting.value;
		for (PathSetting setting : PathSetting.values()) hash = 31 * hash + setting.value;
		return hash;
	}

	public static void resetToDefaults() {
		applyPreset(Preset.MEDIUM);
		for (Tuning tuning : Tuning.values()) tuning.value = 1.0;
		for (Toggle toggle : Toggle.values()) toggle.value = toggle.defaultValue;
		for (Limit limit : Limit.values()) limit.value = limit.steps[0];
	}

	public static String blockedReason(Limit limit) {
		if (!GlowRenderer.enabled || GlowRenderer.isUnavailable()) return "Needs Ray Tracing on.";
		return switch (limit) {
			case SHADOW_DISTANCE -> on(Option.SUN_SHADOWS) ? null : "Turn on Sun Shadows to use this.";
			case GI_DISTANCE, GI_BOUNCES -> on(Option.GLOBAL_ILLUMINATION) ? null : "Turn on Global Illumination to use this.";
			case REFLECTION_DISTANCE, REFLECTION_BOUNCES -> on(Option.REFLECTIONS) ? null : "Turn on Reflections to use this.";
			case CACHED_GI_DISTANCE -> !on(Option.GLOBAL_ILLUMINATION) ? "Turn on Global Illumination to use this." : !Toggle.RADIANCE_CACHE.value ? "Needs the Radiance Cache." : pathTraced() ? "Not used in Path Tracing." : Toggle.CACHED_GI.value ? null : "Turn on Cached Far GI to use this.";
		};
	}

	public static String blockedReason(Toggle toggle) {
		if (!GlowRenderer.enabled || GlowRenderer.isUnavailable()) return "Needs Ray Tracing on.";
		return switch (toggle) {
			case RESTIR_GI -> !on(Option.GLOBAL_ILLUMINATION) ? "Needs Global Illumination." : rayReconstruction() ? "Not used with Ray Reconstruction, which is cleaner without it." : null;
			case RADIANCE_CACHE -> on(Option.GLOBAL_ILLUMINATION) ? null : "Needs Global Illumination.";
			case RESTIR_CORNER_LIMITS -> !on(Option.GLOBAL_ILLUMINATION) ? "Needs Global Illumination." : rayReconstruction() ? "Not used with Ray Reconstruction." : Toggle.RESTIR_GI.value ? null : "Needs ReSTIR GI.";
			case RESTIR_DI -> !on(Option.BLOCK_LIGHTS) ? "Needs Block Lights." : rayReconstruction() ? "Not used with Ray Reconstruction, which is cleaner without it." : null;
			case DLSS_LOD_BIAS -> GlowNvidia.active() ? null : "Needs an NVIDIA RTX card, with Streamline installed next to Glow.";
			case WATER_TRACED_BOUNCE -> !on(Option.WATER) ? "Needs Enhanced Water." : on(Option.GLOBAL_ILLUMINATION) ? null : "Needs Global Illumination.";
			case CAUSTIC_LIMIT -> on(Option.WATER) ? null : "Needs Enhanced Water.";
			case CACHED_SUN -> on(Option.SUN_SHADOWS) ? null : "Needs Sun Shadows.";
			case CACHED_GI -> !on(Option.GLOBAL_ILLUMINATION) ? "Needs Global Illumination." : !Toggle.RADIANCE_CACHE.value ? "Needs the Radiance Cache." : pathTraced() ? "Not used in Path Tracing." : null;
			case AUTO_EXPOSURE, DISTANCE_FOG, TRACED_EFFECTS, MERGED_GEOMETRY -> null;
		};
	}

	public static int denoisePasses(int signal) {
		// Under Ray Reconstruction only light shafts come here. They are soft already, and temporal reuse does most of the work.
		if (rayReconstruction()) return 1;
		return DENOISE_PASSES[denoiserLevel().ordinal()][signal];
	}

	public static float exposure() {
		return (float) Tuning.EXPOSURE.value;
	}

	/** The biome water colour around the camera as 0xRRGGBB while it is under water with Enhanced Water on, otherwise -1. */
	public static int underwaterColor() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!on(Option.WATER) || minecraft.level == null) return -1;
		Camera camera = minecraft.gameRenderer.mainCamera();
		if (camera.getFogType() != FogType.WATER) return -1;
		return BiomeColors.getAverageWaterColor(minecraft.level, camera.blockPosition()) & 0xFFFFFF;
	}

	/** True while the shaders fade the world into mist: traced, with the camera in open air. Water, lava and powder snow have fogs of their own. */
	public static boolean distanceFogActive() {
		return distanceFogOutdoors() && Minecraft.getInstance().gameRenderer.mainCamera().getFogType() == FogType.NONE;
	}

	/** True while the open air holds mist, wherever the camera is. An underwater camera sees it through the surface. */
	public static boolean distanceFogOutdoors() {
		return Toggle.DISTANCE_FOG.value && GlowRenderer.tracesEntities();
	}

	// Every reach follows the render distance, so quality levels change sample counts and reuse, never how far effects go.
	// Rays may start anywhere in the loaded area and cross it diagonally, so they get a few times the render distance; finding nothing costs little.
	private static final float RAY_REACH_SCALE = 3.0F;

	/** The render distance in blocks. */
	public static float renderBlocks() {
		return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
	}

	/** Fills the quality vectors that follow the world, cloud and labPBR parameters in the light buffer. */
	public static void writeParams(long address) {
		float render = renderBlocks(), rays = render * RAY_REACH_SCALE;
		int sun = level(Option.SUN_SHADOWS).ordinal(), gi = level(Option.GLOBAL_ILLUMINATION).ordinal(), reflection = level(Option.REFLECTIONS).ordinal();
		int light = level(Option.BLOCK_LIGHTS).ordinal(), shafts = level(Option.LIGHT_SHAFTS).ordinal(), foliage = level(Option.FOLIAGE).ordinal();
		int materials = level(Option.MATERIALS).ordinal(), parallax = level(Option.PARALLAX).ordinal(), water = level(Option.WATER).ordinal();
		float[] values = {
			Limit.SHADOW_DISTANCE.over(sun > 0 ? rays : 0.0F), SUN_RADIUS[sun] * (float) Tuning.SUN_SOFTNESS.value, SUN_SAMPLES[sun], Limit.GI_DISTANCE.over(gi > 0 ? rays : 0.0F),
			GI_RAYS[gi], GI_SUNLIT_BOUNCE[gi], Limit.REFLECTION_DISTANCE.over(reflection > 0 ? rays : 0.0F), REFLECTION_ROUGHNESS[reflection],
			light > 0 ? render : 0.0F, LIGHT_CANDIDATES[light], LIGHT_INTENSITY * (float) Tuning.BLOCK_LIGHT_STRENGTH.value, shafts > 0 ? render : 0.0F,
			SHAFT_SAMPLES[shafts], (float) Tuning.SHAFT_DENSITY.value, FOLIAGE_THROUGH[foliage], FOLIAGE_GLOW[foliage],
			level(Option.CLOUD_SHADOWS).ordinal(), materials, materials > 0 ? render : 0.0F, PARALLAX_MIN_STEPS[parallax],
			PARALLAX_MAX_STEPS[parallax], PARALLAX_FADE_START[parallax], PARALLAX_FADE_END[parallax], PARALLAX_SHADOW_STEPS[parallax],
			(float) Tuning.PARALLAX_DEPTH.value, (float) Tuning.BLOCK_LIGHT_STRENGTH.value, RESTIR_SPATIAL_SAMPLES[gi], RESTIR_MAX_M[gi],
			water, WATER_OCTAVES[water], (float) Tuning.WAVE_HEIGHT.value, underwaterColor(),
			Limit.GI_BOUNCES.value, Limit.REFLECTION_BOUNCES.value, Toggle.CACHED_GI.value && gi > 0 && Toggle.RADIANCE_CACHE.value ? (Limit.CACHED_GI_DISTANCE.value == 0 ? 32.0F : Limit.CACHED_GI_DISTANCE.value) : 0.0F, 0.0F
		};
		if (pathTraced()) {
			float samples = PathSetting.SAMPLES.number();
			values[0] = values[3] = values[6] = rays;
			values[2] = values[4] = samples;
			values[7] = 1.0F;
			values[8] = render;
			values[9] = PATH_LIGHT_CANDIDATES;
			values[32] = PathSetting.BOUNCES.number();
			values[34] = PathSetting.FIREFLY_LIMIT.number();
			values[35] = PathSetting.ACCUMULATION.number();
		}
		for (int i = 0; i < values.length; i++) memPutFloat(address + i * 4L, values[i]);
	}

	/** Turns ray tracing on or off, handing vanilla's lighting options over in the matching direction. Meshes hold vanilla's baked shading, so they rebuild. */
	public static void setEnabled(boolean enabled) {
		if (GlowRenderer.enabled == enabled) return;
		GlowRenderer.enabled = enabled;
		Minecraft minecraft = Minecraft.getInstance();
		if (enabled) applyVanillaOverrides(minecraft.options);
		else restoreVanillaOptions(minecraft.options);
		minecraft.levelExtractor.allChanged();
		save();
	}

	/** Holds vanilla's options where ray tracing needs them. It runs between frames, because some of these rebuild the world's meshes. */
	public static void enforceVanillaOverrides(Minecraft minecraft) {
		if (vanillaOverridesApplied(minecraft.options)) return;
		minecraft.execute(() -> applyVanillaOverrides(minecraft.options));
	}

	private static boolean vanillaOverridesApplied(Options options) {
		return options.ambientOcclusion().get() && !options.improvedTransparency().get() && !options.entityShadows().get()
			&& options.chunkSectionFadeInTime().get() == 0.0 && options.cutoutLeaves().get() && !options.vignette().get();
	}

	// Smooth lighting stays on: BlockModelLighterCacheMixin removes its darkening, and the smoothed light levels it stores feed the shaders.
	private static void applyVanillaOverrides(Options options) {
		if (!GlowRenderer.enabled || vanillaOverridesApplied(options)) return;
		GlowRenderer.LOGGER.info("Glow is taking over vanilla's lighting options");
		if (VANILLA_BACKUP.isEmpty()) {
			VANILLA_BACKUP.setProperty("ambientOcclusion", options.ambientOcclusion().get().toString());
			VANILLA_BACKUP.setProperty("improvedTransparency", options.improvedTransparency().get().toString());
			VANILLA_BACKUP.setProperty("entityShadows", options.entityShadows().get().toString());
			VANILLA_BACKUP.setProperty("chunkSectionFadeInTime", options.chunkSectionFadeInTime().get().toString());
			VANILLA_BACKUP.setProperty("cutoutLeaves", options.cutoutLeaves().get().toString());
			VANILLA_BACKUP.setProperty("vignette", options.vignette().get().toString());
			save();
		}
		if (!options.ambientOcclusion().get()) options.ambientOcclusion().set(true);
		if (options.improvedTransparency().get()) options.improvedTransparency().set(false);
		if (options.entityShadows().get()) options.entityShadows().set(false);
		if (options.chunkSectionFadeInTime().get() != 0.0) options.chunkSectionFadeInTime().set(0.0);
		if (!options.cutoutLeaves().get()) options.cutoutLeaves().set(true);
		if (options.vignette().get()) options.vignette().set(false);
	}

	private static void restoreVanillaOptions(Options options) {
		if (VANILLA_BACKUP.isEmpty()) return;
		GlowRenderer.LOGGER.info("Glow is handing vanilla's lighting options back");
		options.ambientOcclusion().set(Boolean.parseBoolean(VANILLA_BACKUP.getProperty("ambientOcclusion")));
		options.improvedTransparency().set(Boolean.parseBoolean(VANILLA_BACKUP.getProperty("improvedTransparency")));
		options.entityShadows().set(Boolean.parseBoolean(VANILLA_BACKUP.getProperty("entityShadows")));
		options.chunkSectionFadeInTime().set(Double.parseDouble(VANILLA_BACKUP.getProperty("chunkSectionFadeInTime")));
		options.cutoutLeaves().set(Boolean.parseBoolean(VANILLA_BACKUP.getProperty("cutoutLeaves")));
		options.vignette().set(Boolean.parseBoolean(VANILLA_BACKUP.getProperty("vignette")));
		VANILLA_BACKUP.clear();
	}

	public static void load() {
		if (TEMPORARY) return;
		if (!Files.exists(FILE)) {
			applyRecommendedNvidia();
			return;
		}
		Properties saved = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			saved.load(reader);
		} catch (IOException e) {
			GlowRenderer.LOGGER.warn("Glow could not read its settings", e);
			return;
		}
		GlowRenderer.enabled = Boolean.parseBoolean(saved.getProperty("enabled", "true"));
		pathTracing = Boolean.parseBoolean(saved.getProperty("pathTracing", "false"));
		for (PathSetting setting : PathSetting.values()) {
			try {
				setting.value = Math.clamp(Integer.parseInt(saved.getProperty("path." + setting.name(), Integer.toString(setting.value))), 0, setting.labels.length - 1);
			} catch (NumberFormatException e) {
				GlowRenderer.LOGGER.warn("Glow ignored an unknown value for {}", setting.name());
			}
		}
		// Denoiser Ultra used to be what turned Ray Reconstruction on, so older saves keep the image they had.
		if (!saved.containsKey(Nvidia.RAY_RECONSTRUCTION.name()) && Option.DENOISER.value == Quality.ULTRA) Nvidia.RAY_RECONSTRUCTION.value = 1;
		for (Option option : Option.values()) {
			try {
				option.value = Quality.valueOf(saved.getProperty(option.name(), option.value.name()));
			} catch (IllegalArgumentException e) {
				GlowRenderer.LOGGER.warn("Glow ignored an unknown level for {}", option.name());
			}
		}
		for (Nvidia setting : Nvidia.values()) {
			try {
				setting.value = Math.clamp(Integer.parseInt(saved.getProperty(setting.name(), "0")), 0, setting.values.length - 1);
			} catch (NumberFormatException e) {
				GlowRenderer.LOGGER.warn("Glow ignored an unknown value for {}", setting.name());
			}
		}
		for (Tuning tuning : Tuning.values()) {
			try {
				tuning.value = Math.clamp(Double.parseDouble(saved.getProperty(tuning.name(), "1.0")), tuning.min, tuning.max);
			} catch (NumberFormatException e) {
				GlowRenderer.LOGGER.warn("Glow ignored an unknown value for {}", tuning.name());
			}
		}
		for (Toggle toggle : Toggle.values()) toggle.value = Boolean.parseBoolean(saved.getProperty(toggle.name(), Boolean.toString(toggle.value)));
		for (Limit limit : Limit.values()) {
			try {
				int value = Integer.parseInt(saved.getProperty(limit.name(), Integer.toString(limit.value)));
				if (java.util.Arrays.stream(limit.steps).anyMatch(step -> step == value)) limit.value = value;
			} catch (NumberFormatException e) {
				GlowRenderer.LOGGER.warn("Glow ignored an unknown value for {}", limit.name());
			}
		}
		for (String key : saved.stringPropertyNames()) {
			if (key.startsWith("vanilla.")) VANILLA_BACKUP.setProperty(key.substring(8), saved.getProperty(key));
		}
		// Saves from before the performance defaults move to them once: native resolution and extra bounces were what made High slow.
		if (!saved.containsKey("settingsVersion")) {
			int frameGeneration = Nvidia.FRAME_GENERATION.value;
			applyRecommendedNvidia();
			Nvidia.FRAME_GENERATION.value = frameGeneration;
			Limit.GI_BOUNCES.value = Limit.GI_BOUNCES.steps[0];
			Limit.REFLECTION_BOUNCES.value = Limit.REFLECTION_BOUNCES.steps[0];
			save();
		} else if (savedVersion(saved) < 2) {
			// Cached Sun Shadows and Merged Bounce Geometry were on by default while they were being measured, and measured no faster.
			Toggle.CACHED_SUN.value = Toggle.CACHED_SUN.defaultValue;
			Toggle.MERGED_GEOMETRY.value = Toggle.MERGED_GEOMETRY.defaultValue;
			save();
		}
	}

	private static int savedVersion(Properties saved) {
		try {
			return Integer.parseInt(saved.getProperty("settingsVersion", "0").trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static void save() {
		if (TEMPORARY) return;
		Properties saved = new Properties();
		saved.setProperty("settingsVersion", Integer.toString(SETTINGS_VERSION));
		saved.setProperty("enabled", Boolean.toString(GlowRenderer.enabled));
		saved.setProperty("pathTracing", Boolean.toString(pathTracing));
		for (PathSetting setting : PathSetting.values()) saved.setProperty("path." + setting.name(), Integer.toString(setting.value));
		for (Option option : Option.values()) saved.setProperty(option.name(), option.value.name());
		for (Tuning tuning : Tuning.values()) saved.setProperty(tuning.name(), Double.toString(tuning.value));
		for (Nvidia setting : Nvidia.values()) saved.setProperty(setting.name(), Integer.toString(setting.value));
		for (Toggle toggle : Toggle.values()) saved.setProperty(toggle.name(), Boolean.toString(toggle.value));
		for (Limit limit : Limit.values()) saved.setProperty(limit.name(), Integer.toString(limit.value));
		for (String key : VANILLA_BACKUP.stringPropertyNames()) saved.setProperty("vanilla." + key, VANILLA_BACKUP.getProperty(key));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			saved.store(writer, "Glow ray tracing settings");
		} catch (IOException e) {
			GlowRenderer.LOGGER.warn("Glow could not save its settings", e);
		}
	}
}
