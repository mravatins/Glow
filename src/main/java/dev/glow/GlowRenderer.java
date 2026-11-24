package dev.glow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTOpacityMicromap.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK13.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.glow.mixin.FrontendGpuDeviceAccessor;
import dev.glow.mixin.StagedDrawAccessor;
import dev.glow.mixin.TextureAtlasAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.Options;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.Optional;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.Resource;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ray-traced sun shadows: captures chunk meshes, keeps a TLAS current and darkens occluded pixels after the main pass. */
public final class GlowRenderer {
	public static final Logger LOGGER = LoggerFactory.getLogger("glow");
	public static final Matrix4f PROJECTION = new Matrix4f();
	public static boolean enabled = true;
	public static int lastInstances;

	private static final int SOURCE_STRIDE = 28;
	private static final int STRIDE = 28;
	private static final int MAX_LIGHTS = 256;
	private static final float FOG_FULL_SKY_LIGHT = 8.0F;
	// Fog Density 1 is a tenth of the original mist and haze: a light hint of air, not a wall.
	private static final float FOG_DENSITY_SCALE = 0.1F;
	private static final float FOG_SKY_EASING = 0.05F; // Per frame.
	private float fogSkyVisibility = 1.0F;
	private static final int LIGHTS_PER_SECTION = 64;
	private static final int LIGHT_COLOR_STEP = 100;
	private static final String[] FOLIAGE_NAMES = {
		"leaves", "short_grass", "tall_grass", "dry_grass", "seagrass", "fern", "vine", "sapling", "azalea", "dandelion", "poppy", "orchid", "allium", "bluet", "tulip",
		"daisy", "cornflower", "lily", "rose", "peony", "lilac", "sunflower", "pitcher", "torchflower", "wheat_stage", "carrots_stage", "potatoes_stage", "beetroots_stage",
		"sugar_cane", "kelp", "bamboo", "mushroom", "sprouts", "roots", "lichen", "moss_carpet", "dripleaf", "pink_petals", "leaf_litter", "wildflowers", "bush", "spore_blossom"
	};
	private static final int LIGHT_COLOR_LAVA = 2;
	private static final int LIGHT_FULL_BLOCK = 1000;
	// Lights are gathered out to the render distance. The scan covers that whole area in about this many frames, at least this many sections a frame.
	private static final int LIGHT_SCAN_FRAMES = 60, LIGHT_SCAN_MIN = 64;
	// Each scanned light is x, y, z, the packed value, then where its flame or glow sits relative to the block's centre (see lightOffset).
	private static final int LIGHT_STRIDE = 5;
	private static final float LIGHT_OFFSET_UNIT = 1.0F / 64.0F;
	// Light grid: for each 16 block cell of the world, the lights that can reach it, so a pixel only draws candidates that can light it.
	// A hash table of buckets after the frame vectors in the light buffer: key, how many lights reach the cell, then up to LIGHT_GRID_STORED light indices, a byte each.
	// shadow.comp's lightCell reads it, and its constants must match these.
	private static final int LIGHT_GRID_BUCKETS = 8192, LIGHT_GRID_BUCKET_WORDS = 16, LIGHT_GRID_STORED = 56, LIGHT_GRID_PROBES = 8;
	private static final float LIGHT_GRID_CELL = 16.0F;
	// A light reaches as far as its level, measured from its glowing body, which is up to 0.7 wide each way. The rest covers rounding between here and the shader.
	private static final float LIGHT_GRID_MARGIN = 1.75F;
	private static final long LIGHT_GRID_OFFSET = (MAX_LIGHTS + 3 + GlowSettings.PARAM_VECTORS + 2 + 4) * 16L; // After UPSCALE_VECTORS and FRAME_VECTORS.
	private final int[] lightGrid = new int[LIGHT_GRID_BUCKETS * LIGHT_GRID_BUCKET_WORDS];
	private final float[] lightMirror = new float[MAX_LIGHTS * 4]; // What the light buffer holds, kept here because reading mapped memory back is slow.
	private int lightGridSeed = 1;
	private float lightListReach = Float.MAX_VALUE;
	private boolean mergedBuilt = GlowSettings.Toggle.MERGED_GEOMETRY.value;
	// Where a held light sits relative to the eyes, in blocks: about where the item shows on screen.
	private static final double HELD_LIGHT_FORWARD = 0.5, HELD_LIGHT_SIDE = 0.35, HELD_LIGHT_DOWN = 0.3, HELD_LIGHT_WALL_GAP = 0.15;
	private static final int LIGHT_SECTION_RADIUS = 3;
	private static final int PUSH_SIZE = 256;
	private static final int SHADER_CACHE_DAYS = 30;
	private static final int IMAGE_COUNT = 51;
	/** Blocks dimmer than this light their surroundings but don't look lit themselves. */
	private static final int VISIBLE_GLOW_EMISSION = 5;
	private static final int WORLD_RAY_MASK = 1, PARTICLE_RAY_MASK = 2, WEATHER_RAY_MASK = 4, TRANSLUCENT_RAY_MASK = 8;
	// Entities get their own bit so the shadow and bounce rays that use merged geometry still see them. A section's exact BLAS drops MERGED when a merged BLAS stands in for it.
	private static final int DYNAMIC_RAY_MASK = 16, MERGED_RAY_MASK = 32;
	private static final int ACCUMULATION_IMAGE = 50;
	private static final int SIGNALS = 4;
	private static final int SHAFT_SIGNAL = 3; // Light shafts' images are half the render size; see ensureOutput.
	private static final int SIGNAL_IMAGES = 7;
	private static final int STORAGE_BINDINGS = 39;
	private static final int RR_INPUT_IMAGE = 39;
	private static final int RR_OUTPUT_IMAGE = 40;
	private static final int SCREEN_MOTION_IMAGE = 41;
	private static final int SPECULAR_GUIDE_IMAGE = 42;
	private static final int DIFFUSE_GUIDE_IMAGE = 43;
	private static final int RENDER_DEPTH_IMAGE = 44;
	private static final int SPECULAR_MOTION_IMAGE = 45;
	private static final int SPECULAR_HIT_IMAGE = 46;
	// ReSTIR DI reservoirs for block lights, current and previous by frame parity like the GI reservoirs.
	private static final int LIGHT_RESERVOIR_IMAGE = 47;
	// Distance along the view direction, for Ray Reconstruction, which reads it in place of the raster depth.
	private static final int LINEAR_DEPTH_IMAGE = 49;
	// Composite iterations. 0 finishes the image with Glow's own denoiser, at full size.
	private static final int RR_ASSEMBLE_ITERATION = 1;
	private static final int RR_FINISH_ITERATION = 2;
	private static final int SR_ASSEMBLE_ITERATION = 3;
	private static final int SR_FINISH_ITERATION = 4;
	private static final int HAND_ITERATION = 5;
	private static final int MIN_JITTER_PHASES = 32;
	// After the world, cloud, labPBR and quality vectors: upscale ratio and render jitter, then raster jitter.
	private static final int UPSCALE_VECTORS = 2;
	// Then the radiance cache's address, the sun and the moon for the sky, and the sun's colour after the air.
	private static final int FRAME_VECTORS = 4;
	// Radiance cache cells. common.glsl's CACHE_CAPACITY must match, and the resolve pass runs as rows of CACHE_ROW threads.
	private static final int CACHE_CAPACITY = 1 << 21;
	private static final int CACHE_ROW = 1024;
	private static final int SKY_LUT_TEXELS = 2 * 64 * 64;
	// Keys, sums, means, ages, then sun visibility sums and means, then the sky tables, in bytes per cell as the shader lays them out.
	// Then the auto exposure meter and state.
	private static final long CACHE_BYTES = 108L * CACHE_CAPACITY + SKY_LUT_TEXELS * 16L + 16L;
	private static final int RESOLVE_ITERATION = 2;
	// The sky's brightness under the moon, relative to under the sun. Real moonlight is far dimmer still; with the eye's partial adaptation
	// this leaves a night that is dark and colourless but walkable, and a moonless corner or a cave close to black.
	private static final float MOON_SKY = 0.01F;
	// Direct moonlight, relative to the sun, and its colour: night vision sees it bluish.
	private static final float MOON_LIGHT = 0.02F;
	private static final float[] MOON_TINT = {0.82F, 0.93F, 1.15F};
	private static final float CAMERA_NEAR = 0.05F;
	private static final int RR_COLOR_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
	private static int jitterPhase;
	// This frame's DLSS plan, made when the projection is jittered, before the world draws.
	private static boolean frameRayReconstruction;
	private static int frameDlssMode = -1; // The bridge's DLSS mode: 0 DLAA to 4 Ultra Performance, or -1 for none.

	/** Whether terrain textures should sample one mip sharper: DLSS builds the image from jittered frames, so it can use the extra detail. */
	public static boolean sharpenTerrainMips() {
		return GlowSettings.Toggle.DLSS_LOD_BIAS.value && (frameRayReconstruction || frameDlssMode >= 0);
	}
	private static int planOutWidth, planOutHeight, planRenderWidth, planRenderHeight;
	// Render jitter is where each render pixel samples, in render pixels. Raster jitter moves the full size world so those samples land on its pixels.
	private static float renderJitterX, renderJitterY, rasterJitterX, rasterJitterY;
	// Diagnostic: zero jitter and a history reset every frame, so DLSS output should match its input.
	// Diagnostics: GLOW_DLSS_PROBE = 1 (no jitter + reset), nojitter, zeromv (clear motion vectors before DLSS).
	private static final String DLSS_PROBE = String.valueOf(System.getenv("GLOW_DLSS_PROBE"));
	// Diagnostic: GLOW_DLSS_JSIGN = two of + or -, where - flips the jitter DLSS is told, x then y.
	private static final String DLSS_JSIGN = System.getenv("GLOW_DLSS_JSIGN") == null ? "++" : System.getenv("GLOW_DLSS_JSIGN");
	private static boolean tracedThisFrame;
	private static final int MOTION_IMAGE = 33;
	private static final int RESERVOIR_A_IMAGE = 34;
	private static final int RESERVOIR_B_IMAGE = 36;
	private static final int SHADING_NORMAL_IMAGE = 38;
	private static final int SPATIAL_REUSE_ITERATION = 1;
	private static final int NO_PREVIOUS_POSITION = 0x7FC00000;
	private static final long PREVIOUS_POSITION_SIZE = 12L;
	private static final int PARALLAX_IMAGE = 32;
	private static final int DETAIL_IMAGE = 31;
	private static final int MATERIAL_LABPBR = 8;
	private static final int MATERIAL_LABPBR_EMISSIVE = 9;
	private static final int MATERIAL_WATER = 7;
	private static final int MATERIAL_FOLIAGE = 16;
	private static final int G_BUFFER_IMAGE = 28;
	private static final int ALBEDO_IMAGE = 30;
	private static final double WRAP = 3072.0;
	private static final Identifier CLOUD_TEXTURE = Identifier.withDefaultNamespace("textures/environment/clouds.png");
	private static final int MATERIAL_CELL = 16;
	private static final int TIMESTAMPS = 8;
	private static final int MAX_TEXTURES = 64;
	private static final int MAX_QUADS = 128 * 1024;
	private static final int RING = 4;
	private static final int BUILDS_PER_FRAME = 64;
	// A BLAS is built into a buffer sized for the worst case. Once built, the driver knows its real size, usually about half, and each section's
	// solid and translucent BLAS is copied into a buffer of that size a few frames later. The merged copy is left as built.
	private static final int COMPACT_SLOTS = BUILDS_PER_FRAME * 2;
	private static final float COMPACT_WORTH = 0.9F; // Only copy when it frees at least a tenth.
	private long compactPool;
	private final Mesh[][] compactMeshes = new Mesh[RING][COMPACT_SLOTS];
	private final long[][] compactHandles = new long[RING][COMPACT_SLOTS];
	private final int[] compactCounts = new int[RING];
	private static long compactedBefore, compactedAfter, compactedCount;
	private static final float SUN_STRENGTH = 0.55F;
	private static final float MOON_STRENGTH = 0.25F;
	private static final float SUN_TILT = 0.3F;

	private static final int USAGE_ADDRESS = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
	private static final int USAGE_AS_INPUT = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

	// Mesh builds keep up to BUILDS_PER_FRAME sets of geometry and micromap structs alive until one build call, more than the default stack holds.
	private static final MemoryStack BUILD_STACK = MemoryStack.create(2 << 20);
	private static final int[] LAYER_FLAGS = {VK_GEOMETRY_OPAQUE_BIT_KHR, VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR, VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR};
	private static final Map<CompiledSectionMesh, Mesh> MESHES = new ConcurrentHashMap<>();
	private static final Queue<Mesh> RELEASED = new ConcurrentLinkedQueue<>();
	// A rebuilt section's old BLAS stays in the scene until the new one is built, so the section never drops out and lets light through.
	private static final Map<SectionRenderDispatcher.RenderSection, Mesh> STANDINS = new ConcurrentHashMap<>();
	private static final int STANDIN_GRACE_FRAMES = 2, STANDIN_MAX_FRAMES = 240;
	private static GlowRenderer instance;
	private static boolean unavailable;
	private static final Map<StagedVertexBuffer.Draw, Long> DRAW_TEXTURES = new IdentityHashMap<>();
	private static final long[] DYNAMIC_TEXTURES = new long[MAX_TEXTURES];
	private static boolean capturingFeatures;
	private static ByteBuffer dynamicData;
	private static int dynamicQuads;
	// Last frame's captured vertices, and the {texture view, first quad, quad count} of each batch in both frames, which pair them up.
	private static ByteBuffer lastDynamicData;
	private static int lastDynamicQuads;
	private static List<long[]> dynamicSlices = new ArrayList<>();
	private static List<long[]> lastDynamicSlices = new ArrayList<>();
	public static boolean motionVectors = true;
	private static int dynamicTextureCount = 1;
	private static final SubmitNodeStorage PLAYER_STORAGE = new SubmitNodeStorage();
	private static final GlowEffects PARTICLES = new GlowEffects(STRIDE), WEATHER = new GlowEffects(STRIDE);
	private static final Identifier RAIN_TEXTURE = Identifier.withDefaultNamespace("textures/environment/rain.png");
	private static final Identifier SNOW_TEXTURE = Identifier.withDefaultNamespace("textures/environment/snow.png");
	private static final Set<String> GLOW_EXCLUDED_BLOCKS = Set.of("light", "fire", "soul_fire", "lava", "campfire", "soul_campfire", "end_portal");
	private static Set<String> glowingBlocks;
	private static final ByteBuffer LAST_PUSH = memAlloc(256);
	private static final float[] TIMINGS = new float[TIMESTAMPS - 1];
	private static float cpuMillis;

	private static final class Mesh {
		final SectionRenderDispatcher.RenderSection section;
		final CompiledSectionMesh key;
		final int x, y, z;
		final ByteBuffer[] layers = new ByteBuffer[3];
		final long[] geometry = new long[3];
		Allocation vertices, storage, micromapStorage;
		long handle, reference, micromap;
		// Water and glass have their own BLAS and instance, so rays that pass through them can skip them with the instance mask.
		Allocation translucentStorage;
		long translucentHandle, translucentReference, translucentGeometry;
		// Filled in on the micromap thread, because encoding takes too long for the render thread.
		GlowMicromaps.Encoded encoded;
		// Merged copy of the solid layer for bounce and shadow rays, built on the same thread.
		ByteBuffer proxy;
		Allocation proxyStorage;
		long proxyHandle, proxyReference, proxyGeometry;
		boolean encodeStarted;
		volatile boolean encodeDone;
		boolean processed;
		boolean destroyed;
		int standinFrames;

		Mesh(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh key) {
			this.section = section;
			this.key = key;
			this.x = section.getRenderOrigin().getX();
			this.y = section.getRenderOrigin().getY();
			this.z = section.getRenderOrigin().getZ();
		}

		synchronized void freeCpu() {
			for (int i = 0; i < layers.length; i++) {
				if (layers[i] != null) memFree(layers[i]);
				layers[i] = null;
			}
			if (proxy != null) memFree(proxy);
			proxy = null;
		}
	}

	private record Allocation(long buffer, long allocation, long mapped, long address, long size) {}

	private record DynamicBlas(Allocation vertices, Allocation storage, Allocation scratch, long handle, long reference, int capacity) {}

	public static void capture(SectionRenderDispatcher.RenderSection section, ChunkSectionLayer layer, CompiledSectionMesh key, ByteBuffer source) {
		if (source == null) return;
		int index = layer == ChunkSectionLayer.SOLID ? 0 : layer == ChunkSectionLayer.TRANSLUCENT ? 2 : 1;
		Mesh mesh = MESHES.computeIfAbsent(key, k -> new Mesh(section, k));
		synchronized (mesh) {
			if (mesh.layers[index] != null) return; // Upload retry.
			int count = source.remaining() / SOURCE_STRIDE;
			ByteBuffer out = memAlloc(count * STRIDE);
			long from = memAddress(source), to = memAddress(out);
			int flags = index == 2 ? 1 << 8 : 0;
			for (int i = 0; i < count; i++) {
				copyVertex(from + i * (long) SOURCE_STRIDE, 12, 16, 24, to + i * (long) STRIDE, flags);
			}
			mesh.layers[index] = out;
		}
	}

	/** The material word packs the texture index with the translucent flag above it. */
	private static void copyVertex(long from, int colorOffset, int uvOffset, int lightOffset, long to, int material) {
		memCopy(from, to, 12);
		memCopy(from + uvOffset, to + 12, 8);
		int block = Math.clamp(memGetShort(from + lightOffset), 0, 255), sky = Math.clamp(memGetShort(from + lightOffset + 2), 0, 255);
		memPutInt(to + 20, block | sky << 8 | material << 16);
		memPutInt(to + 24, colorOffset < 0 ? -1 : memGetInt(from + colorOffset));
	}

	public static void beginFeatureCapture(LevelRenderState level) {
		CameraRenderState camera = level.cameraRenderState;
		capturingFeatures = tracesEntities();
		ByteBuffer spareData = lastDynamicData;
		lastDynamicData = dynamicData;
		dynamicData = spareData;
		lastDynamicQuads = dynamicQuads;
		List<long[]> spareSlices = lastDynamicSlices;
		lastDynamicSlices = dynamicSlices;
		dynamicSlices = spareSlices;
		dynamicSlices.clear();
		dynamicQuads = 0;
		dynamicTextureCount = 1;
		if (capturingFeatures) capturePlayer(camera);
	}

	/** The light a held item gives, packed like a block light's value, or 0 when it gives none. */
	private static int heldLightValue(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		Item item = stack.getItem();
		BlockState state = item instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
		int emission = state != null ? state.getLightEmission() : item == Items.LAVA_BUCKET ? 15 : 0;
		if (emission <= 0) return 0;
		int color = lightColor(BuiltInRegistries.ITEM.getKey(item).getPath());
		return emission + color * LIGHT_COLOR_STEP + (state != null && state.isSolidRender() || color == LIGHT_COLOR_LAVA ? LIGHT_FULL_BLOCK : 0);
	}

	/** The hand draws with vanilla's lightmap, which knows nothing of the light it holds. A held torch shouldn't be dark. */
	public static void lightHeldHand(PlayerRenderState player) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!enabled || player.avatarRenderState == null || minecraft.player == null) return;
		int held = Math.max(heldLightValue(minecraft.player.getMainHandItem()) % LIGHT_COLOR_STEP, heldLightValue(minecraft.player.getOffhandItem()) % LIGHT_COLOR_STEP);
		int light = player.avatarRenderState.lightCoords;
		player.avatarRenderState.lightCoords = Math.max(light & 0xFFFF, held * 16) | light & 0xFFFF0000;
	}

	/** Writes the lights the player carries, which move too smoothly and too often for the block scan. Returns how many. */
	private static int writeHeldLights(Minecraft minecraft, Vec3 camera, long out) {
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator()) return 0;
		boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
		int count = writeHeldLight(minecraft, player, player.getMainHandItem(), rightMain ? 1.0 : -1.0, camera, out);
		return count + writeHeldLight(minecraft, player, player.getOffhandItem(), rightMain ? -1.0 : 1.0, camera, out + count * 16L);
	}

	private static int writeHeldLight(Minecraft minecraft, LocalPlayer player, ItemStack stack, double side, Vec3 camera, long out) {
		int value = heldLightValue(stack);
		if (value == 0) return 0;
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 eye = player.getEyePosition(partial), look = player.getViewVector(partial);
		// From the yaw alone, because looking straight up or down leaves look with no sideways part.
		Vec3 right = Vec3.directionFromRotation(0.0F, player.getViewYRot(partial)).cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 up = right.cross(look);
		Vec3 target = eye.add(look.scale(HELD_LIGHT_FORWARD)).add(right.scale(side * HELD_LIGHT_SIDE)).add(up.scale(-HELD_LIGHT_DOWN));
		// A light inside a wall would light nothing, so it stays on the player's side of whatever the hand is clipping into.
		BlockHitResult hit = minecraft.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.MISS) target = hit.getLocation().add(target.subtract(eye).normalize().scale(-HELD_LIGHT_WALL_GAP));
		memPutFloat(out, (float) (target.x - camera.x));
		memPutFloat(out + 4, (float) (target.y - camera.y));
		memPutFloat(out + 8, (float) (target.z - camera.z));
		memPutFloat(out + 12, value);
		return 1;
	}

	/** The game skips the local player in first person, so submit it into a private storage that only reaches the capture. */
	private static void capturePlayer(CameraRenderState camera) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator() || player.isInvisible() || minecraft.getCameraEntity() != player || !minecraft.options.getCameraType().isFirstPerson()) return;
		EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
		EntityRenderState state = dispatcher.extractEntity(player, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		PLAYER_STORAGE.setUseImprovedTransparency(minecraft.gameRenderer.useImprovedTransparency());
		Vec3 pos = camera.pos;
		dispatcher.submit(state, camera, state.x - pos.x, state.y - pos.y, state.z - pos.z, new PoseStack(), PLAYER_STORAGE);
		try (FeatureRenderDispatcher.PreparedFrame frame = minecraft.gameRenderer.featureRenderDispatcher().prepareFrame(PLAYER_STORAGE)) {
			// Preparing is enough: the vertices were captured at upload and nothing here is drawn.
		}
	}

	public static void endFeatureCapture() {
		capturingFeatures = false;
		DRAW_TEXTURES.clear();
	}

	public static boolean tracesEntities() {
		Minecraft minecraft = Minecraft.getInstance();
		return enabled && instance != null && minecraft.level != null && minecraft.level.dimensionType().hasSkyLight();
	}

	/** Remembers the texture of an opaque or cutout draw so its vertices can be captured at upload. */
	public static void tagDraw(RenderType type, StagedVertexBuffer.Draw draw, PreparedRenderType prepared) {
		// Player models, dropped items and held items mostly draw with blending, though their pixels are solid or empty, so the alpha test handles them.
		String name = prepared.name();
		boolean solidEnough = name.startsWith("item_") || name.startsWith("entity_translucent");
		if (!capturingFeatures || type.hasBlending() && !solidEnough || type.isOutline() || type.primitiveTopology() != PrimitiveTopology.QUADS) return;
		VertexFormat format = type.format();
		if (!format.contains("UV0") || !format.contains("UV2")) return;
		for (PreparedRenderType.Texture texture : prepared.textures()) {
			if (texture.name().equals("Sampler0") && texture.textureView() instanceof VulkanGpuTextureView view) {
				DRAW_TEXTURES.put(draw, view.vkImageView());
				return;
			}
		}
	}

	/** Copies this frame's entity, block entity and item quads. They are already camera-relative. */
	public static void captureDraws(List<StagedVertexBuffer.Draw> draws) {
		if (!capturingFeatures) return;
		for (StagedVertexBuffer.Draw draw : draws) {
			Long view = DRAW_TEXTURES.get(draw);
			if (view == null) continue;
			int texture = dynamicTexture(view);
			if (texture < 0) continue;
			StagedDrawAccessor accessor = (StagedDrawAccessor) draw;
			VertexFormat format = accessor.glow$format();
			int size = format.getVertexSize(), uv = format.getElement("UV0").offset(), light = format.getElement("UV2").offset();
			int color = format.contains("Color") ? format.getElement("Color").offset() : -1;
			for (ByteBufferBuilder.Result slice : accessor.glow$slices()) {
				ByteBuffer bytes = slice.byteBuffer();
				int quads = Math.min(bytes.remaining() / size / 4, MAX_QUADS - dynamicQuads);
				if (quads <= 0) continue;
				long needed = (dynamicQuads + quads) * 4L * STRIDE;
				if (dynamicData == null || dynamicData.capacity() < needed) dynamicData = memRealloc(dynamicData, (int) Math.max(needed * 2, 1L << 20));
				long from = memAddress(bytes), to = memAddress0(dynamicData) + dynamicQuads * 4L * STRIDE;
				for (int i = 0; i < quads * 4; i++) copyVertex(from + i * (long) size, color, uv, light, to + i * (long) STRIDE, texture);
				dynamicSlices.add(new long[] {view, dynamicQuads, quads});
				dynamicQuads += quads;
			}
		}
	}

	/** True while rays can see particles and weather. */
	public static boolean tracesEffects() {
		return GlowSettings.Toggle.TRACED_EFFECTS.value && tracesEntities();
	}

	/** Vanilla keeps these in the level's render state. They draw after the trace, but they are already extracted when it runs. */
	private static void captureEffects(LevelRenderState level) {
		PARTICLES.clear();
		WEATHER.clear();
		if (!tracesEffects()) return;
		for (ParticleGroupRenderState group : level.particlesRenderState.particles) {
			if (!(group instanceof QuadParticleRenderState quads) || quads.isEmpty()) continue;
			for (SingleQuadParticle.Layer layer : quads.layers()) {
				int texture = effectTexture(layer.textureAtlasLocation());
				if (texture < 0) continue;
				PARTICLES.useTexture(texture);
				quads.buildLayer(layer, PARTICLES);
			}
		}
		WeatherRenderState weather = level.weatherRenderState;
		Vec3 camera = level.cameraRenderState.pos;
		if (!weather.rainColumns.isEmpty()) {
			int texture = effectTexture(RAIN_TEXTURE);
			if (texture >= 0) {
				WEATHER.useTexture(texture);
				WEATHER.addRain(weather.rainColumns, camera, weather.radius, weather.intensity);
			}
		}
		if (!weather.snowColumns.isEmpty()) {
			int texture = effectTexture(SNOW_TEXTURE);
			if (texture >= 0) {
				WEATHER.useTexture(texture);
				WEATHER.addSnow(weather.snowColumns, camera, weather.radius, weather.intensity);
			}
		}
	}

	private static int effectTexture(Identifier location) {
		return Minecraft.getInstance().getTextureManager().getTexture(location).textureView() instanceof VulkanGpuTextureView view ? dynamicTexture(view.vkImageView()) : -1;
	}

	private static int dynamicTexture(long view) {
		for (int i = 1; i < dynamicTextureCount; i++) {
			if (DYNAMIC_TEXTURES[i] == view) return i;
		}
		if (dynamicTextureCount == MAX_TEXTURES) return -1;
		DYNAMIC_TEXTURES[dynamicTextureCount] = view;
		return dynamicTextureCount++;
	}

	public static void release(SectionMesh key) {
		if (key instanceof CompiledSectionMesh compiled) {
			Mesh mesh = MESHES.remove(compiled);
			if (mesh == null) return;
			if (mesh.handle == 0L && mesh.translucentHandle == 0L) {
				RELEASED.add(mesh);
				return;
			}
			Mesh previous = STANDINS.put(mesh.section, mesh);
			if (previous != null) RELEASED.add(previous);
		}
	}

	public static void releaseAll() {
		for (CompiledSectionMesh key : MESHES.keySet()) release(key);
		for (SectionRenderDispatcher.RenderSection section : STANDINS.keySet()) {
			Mesh standin = STANDINS.remove(section);
			if (standin != null) RELEASED.add(standin);
		}
	}

	/** True while ray tracing supplies all of the scene's light, so vanilla's own lighting has to stay out of the image. */
	public static boolean replacesVanillaLighting() {
		return enabled && !unavailable;
	}

	public static void render(LevelRenderState state) {
		if (unavailable) return;
		if (enabled) GlowSettings.enforceVanillaOverrides(Minecraft.getInstance());
		if (instance == null) {
			if (!(((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).glow$backend() instanceof VulkanDevice device)
				|| !device.vkDevice().getCapabilities().VK_KHR_ray_query) {
				LOGGER.warn("Glow disabled: requires the Vulkan backend with ray query support");
				unavailable = true;
				return;
			}
			instance = new GlowRenderer(device);
			LOGGER.info("Glow ray tracing active");
		}
		long cpuStart = System.nanoTime();
		instance.renderFrame(state);
		cpuMillis += ((System.nanoTime() - cpuStart) / 1.0E6F - cpuMillis) * 0.05F;
		GlowAutotest.onFrame();
	}

	/** Smoothed GPU milliseconds per stage. */
	public static boolean isUnavailable() {
		return unavailable;
	}

	/** Recent milliseconds per stage: 0 build, 1 trace, 2 denoise, 3 DLSS, 4 composite. */
	/** Frames averaged into the current still view, 0 when not accumulating. */
	public static int accumulatedFrames() {
		return accumulatedFrames;
	}

	public static float timing(int stage) {
		return stage == 1 ? TIMINGS[1] + TIMINGS[2] + TIMINGS[3] : TIMINGS[stage == 0 ? 0 : stage + 2];
	}

	public static String timings() {
		return String.format(Locale.ROOT, "build=%.2fms trace=%.2fms denoise=%.2fms dlss=%.2fms composite=%.2fms cpu=%.2fms render=%dx%d", TIMINGS[0], TIMINGS[1] + TIMINGS[2] + TIMINGS[3], TIMINGS[4], TIMINGS[5], TIMINGS[6], cpuMillis, planRenderWidth, planRenderHeight)
			+ String.format(Locale.ROOT, " resolve=%.2fms primary=%.2fms spatial=%.2fms", TIMINGS[1], TIMINGS[2], TIMINGS[3]);
	}

	public static int meshCount() {
		return MESHES.size();
	}

	private final VkDevice vk;
	private final long vma;
	private final Allocation indices;
	private final long descriptorLayout, descriptorPool, pipelineLayout, temporalPipeline, atrousPipeline, compositePipeline, sampler, queryPool;
	// The trace pass runs as a ray generation shader when the driver has ray tracing pipelines, so Shader Execution Reordering can regroup its threads.
	// GLOW_RT_PIPELINE=0 keeps it in compute. Reordering is opt-in with GLOW_SER=1: with ray queries it only regroups by a hint, and compiling it in made the pass about 30% slower.
	private long tracePipeline, rayGenPipeline;
	// Ray Tracing and Path Tracing each have their own trace shader. Path Tracing's is only built once it's used, since the first compile takes the better part of a minute.
	private static final String[] TRACE_HEADERS = {"#define GLOW_RAY_CODE\n", "#define GLOW_PATH_CODE\n"};
	private record TraceVariant(long compute, long rayGen, long tableAddress) {}
	private final TraceVariant[] traceVariants = new TraceVariant[TRACE_HEADERS.length];
	private Allocation shaderTable;
	private long shaderTableAddress, shaderRecordSize;
	private static final int PIPELINE_STAGES = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_RAYGEN_BIT_KHR;
	private final float timestampPeriod;
	private final boolean[] timestampsWritten = new boolean[RING];
	private final Allocation[] lightBuffers = new Allocation[RING];
	private final Map<Long, int[]> sectionLights = new HashMap<>();
	private final long[] lightOrder = new long[MAX_LIGHTS * 64];
	private int lightCursor;
	private final Matrix4f previousViewProj = new Matrix4f();
	private double previousX, previousY, previousZ;
	private boolean historyValid;
	// Path Tracing's accumulation of a still view.
	private static final float ACCUMULATION_SUN_DRIFT = 0.0087F; // Half a degree, about two seconds of the day's cycle.
	private static final float ACCUMULATION_RAIN_DRIFT = 0.01F;
	private static int accumulatedFrames;
	private int accumulationFingerprint, accumulationLights;
	private float accumulationSun, accumulationRain;
	private boolean rayReconstructionLastFrame, dlssLastFrame;
	private int dlssModeLastFrame = -1;
	private int frameIndex;
	private final long[] normalAtlas = new long[3], specularAtlas = new long[3];
	private final long[] descriptorSets = new long[RING * SIGNALS];
	private Allocation materialMap;
	private Map<Identifier, TextureAtlasSprite> materialSprites;
	private int materialWidth, materialHeight;
	private final Allocation[] instanceBuffers = new Allocation[RING];
	private final Allocation[] tableBuffers = new Allocation[RING];
	private final Allocation[] previousPositions = new Allocation[RING];
	private final DynamicBlas[] dynamics = new DynamicBlas[RING];
	private Allocation blasScratch, tlasScratch, tlasStorage, micromapScratch;
	// The radiance cache and the sky tables, in one buffer that is cleared once and then kept.
	private final Allocation radianceCacheBuffer;
	private boolean radianceCacheCleared;
	// Opacity micromaps on cutout geometry, so the hardware skips the alpha test where it can. GLOW_OMM=0 turns them off.
	private final boolean micromaps;
	// Test only: ignores micromaps per instance, so one run can compare both in the same world.
	public static boolean micromapsIgnored;
	public static boolean solidLeafShadows;
	// Nearest sections first, so a block changed beside the player isn't queued behind a whole world load.
	private static final java.util.concurrent.ThreadPoolExecutor MICROMAP_ENCODER = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
		new java.util.concurrent.PriorityBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "Glow micromap encoder");
			thread.setDaemon(true);
			return thread;
		});
	private static double encodeCameraX, encodeCameraY, encodeCameraZ;

	private record EncodeTask(double distance, long order, Runnable work) implements Runnable, Comparable<EncodeTask> {
		private static final java.util.concurrent.atomic.AtomicLong ORDER = new java.util.concurrent.atomic.AtomicLong();

		EncodeTask(double distance, Runnable work) {
			this(distance, ORDER.getAndIncrement(), work);
		}

		@Override
		public void run() {
			work.run();
		}

		@Override
		public int compareTo(EncodeTask other) {
			int byDistance = Double.compare(distance, other.distance);
			return byDistance != 0 ? byDistance : Long.compare(order, other.order);
		}
	}
	private static final boolean MICROMAP_TOGGLE = System.getenv("GLOW_AUTOTEST") != null;
	private long tlas;
	private int capacity;
	private long outImage, outAllocation, outView;
	private final DynamicBlas[] particleBlases = new DynamicBlas[RING], weatherBlases = new DynamicBlas[RING];
	private final long[] images = new long[IMAGE_COUNT], imageAllocations = new long[IMAGE_COUNT], imageViews = new long[IMAGE_COUNT];
	private int outWidth, outHeight, renderWidth, renderHeight;
	private boolean outNeedsLayout;
	private int frame;
	private int lightCount;

	private GlowRenderer(VulkanDevice device) {
		this.vk = device.vkDevice();
		this.vma = device.vma();
		GlowNvidia.logSupport(vk.getPhysicalDevice().address());
		this.micromaps = vk.getCapabilities().VK_EXT_opacity_micromap && !"0".equals(System.getenv("GLOW_OMM"));
		if (micromaps) LOGGER.info("Glow opacity micromaps on");
		this.indices = createBuffer(6L * MAX_QUADS * 4, USAGE_ADDRESS | USAGE_AS_INPUT, true);
		writeQuadIndices(indices.mapped, MAX_QUADS);
		this.radianceCacheBuffer = createBuffer(CACHE_BYTES, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
		try (MemoryStack stack = stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4 + STORAGE_BINDINGS, stack);
			int[] types = {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
			for (int i = 0; i < 4 + STORAGE_BINDINGS; i++) {
				bindings.get(i).binding(i).descriptorType(i < 4 ? types[i] : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(i == 3 ? MAX_TEXTURES : 1).stageFlags(PIPELINE_STAGES);
			}
			LongBuffer out = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, out), "descriptor layout");
			this.descriptorLayout = out.get(0);

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			// One set per signal per frame.
			int setCount = RING * SIGNALS;
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(setCount);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(setCount * (2 + MAX_TEXTURES));
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(setCount * STORAGE_BINDINGS);
			check(vkCreateDescriptorPool(vk, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(setCount).pPoolSizes(sizes), null, out), "descriptor pool");
			this.descriptorPool = out.get(0);

			LongBuffer layouts = stack.mallocLong(setCount);
			for (int i = 0; i < setCount; i++) layouts.put(i, descriptorLayout);
			LongBuffer sets = stack.mallocLong(setCount);
			check(vkAllocateDescriptorSets(vk, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts), sets), "descriptor sets");
			sets.get(descriptorSets);

			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(PIPELINE_STAGES).offset(0).size(PUSH_SIZE);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, out), "pipeline layout");
			this.pipelineLayout = out.get(0);

			long traceStart = System.nanoTime();
			useTraceVariant(GlowSettings.pathTracing ? 1 : 0, stack);
			LOGGER.info("Glow trace pass took {} ms to prepare", (System.nanoTime() - traceStart) / 1000000L);
			// Its own shader, because another call site of the bounce shading pushes the trace shader past the optimizer's ID limit.
			this.temporalPipeline = createPipeline("temporal.comp", stack);
			this.atrousPipeline = createPipeline("atrous.comp", stack);
			this.compositePipeline = createPipeline("composite.comp", stack);

			check(vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(TIMESTAMPS * RING), null, out), "query pool");
			this.queryPool = out.get(0);
			check(vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(COMPACT_SLOTS * RING), null, out), "compaction query pool");
			this.compactPool = out.get(0);
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), properties);
			this.timestampPeriod = properties.limits().timestampPeriod();

			VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
				// Every lookup asks for level 0.
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0.0f)
				.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
			check(vkCreateSampler(vk, samplerInfo, null, out), "sampler");
			this.sampler = out.get(0);
		}
	}

	private static void writeQuadIndices(long address, int quads) {
		for (int q = 0; q < quads; q++) {
			int v = q * 4;
			long p = address + q * 24L;
			memPutInt(p, v);
			memPutInt(p + 4, v + 1);
			memPutInt(p + 8, v + 2);
			memPutInt(p + 12, v + 2);
			memPutInt(p + 16, v + 3);
			memPutInt(p + 20, v);
		}
	}

	/** Points the trace dispatches at one mode's shader, building it the first time. */
	private void useTraceVariant(int index, MemoryStack stack) {
		if (traceVariants[index] == null) {
			rayGenPipeline = 0L;
			createRayGenPipeline(TRACE_HEADERS[index], stack);
			// The trace pass only needs its compute form where a ray tracing pipeline isn't available.
			long compute = rayGenPipeline != 0L ? VK_NULL_HANDLE : createPipeline("shadow.comp", TRACE_HEADERS[index], stack);
			traceVariants[index] = new TraceVariant(compute, rayGenPipeline, shaderTableAddress);
		}
		tracePipeline = traceVariants[index].compute();
		rayGenPipeline = traceVariants[index].rayGen();
		shaderTableAddress = traceVariants[index].tableAddress();
	}

	/** Builds the trace pass as a ray generation shader with a one-record shader table, adding thread reordering where the driver and compiler support it. */
	private void createRayGenPipeline(String variantHeader, MemoryStack stack) {
		var caps = vk.getCapabilities();
		if (!caps.VK_KHR_ray_tracing_pipeline || "0".equals(System.getenv("GLOW_RT_PIPELINE"))) {
			LOGGER.info("Glow trace pass: compute ({})", caps.VK_KHR_ray_tracing_pipeline ? "off by GLOW_RT_PIPELINE" : "no ray tracing pipelines");
			return;
		}
		boolean allowReorder = "1".equals(System.getenv("GLOW_SER"));
		String[] headers = {
			allowReorder && caps.VK_EXT_ray_tracing_invocation_reorder ? "#extension GL_EXT_shader_invocation_reorder : require\n#define REORDER(hint, bits) reorderThreadEXT(hint, bits)\n" : null,
			allowReorder && caps.VK_NV_ray_tracing_invocation_reorder ? "#extension GL_NV_shader_invocation_reorder : require\n#define REORDER(hint, bits) reorderThreadNV(hint, bits)\n" : null,
			"#define REORDER(hint, bits)\n"
		};
		String[] names = {"EXT reordering", "NV reordering", "no reordering"};
		ByteBuffer spirv = null;
		String chosen = null;
		for (int i = 0; i < headers.length && spirv == null; i++) {
			if (headers[i] == null) continue;
			try {
				spirv = compileShader("shadow.comp", variantHeader + (micromaps ? "#extension GL_EXT_opacity_micromap : require\n#define GLOW_OMM\n" : "") + "#define GLOW_RAYGEN\n#extension GL_EXT_ray_tracing : require\n" + headers[i], Shaderc.shaderc_raygen_shader);
				chosen = names[i];
			} catch (IllegalStateException e) {
				LOGGER.warn("Glow ray generation shader with {} failed to compile: {}", names[i], e.getMessage());
			}
		}
		if (spirv == null) return;
		LongBuffer out = stack.mallocLong(1);
		check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, out), "shader module");
		long module = out.get(0);
		memFree(spirv);
		VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(1, stack);
		stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(module).pName(stack.UTF8("main"));
		VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(1, stack);
		groups.get(0).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0)
			.closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
		VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack).sType$Default()
			.flags(micromaps ? VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT : 0).pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(pipelineLayout);
		int result = vkCreateRayTracingPipelinesKHR(vk, VK_NULL_HANDLE, VK_NULL_HANDLE, info, null, out);
		vkDestroyShaderModule(vk, module, null);
		if (result != VK_SUCCESS) {
			LOGGER.warn("Glow ray tracing pipeline failed: VkResult {}, tracing in compute", result);
			return;
		}
		rayGenPipeline = out.get(0);

		VkPhysicalDeviceRayTracingPipelinePropertiesKHR rt = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
		vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rt.address()));
		int handleSize = rt.shaderGroupHandleSize();
		long baseAlignment = rt.shaderGroupBaseAlignment();
		shaderRecordSize = (handleSize + rt.shaderGroupHandleAlignment() - 1) / rt.shaderGroupHandleAlignment() * (long) rt.shaderGroupHandleAlignment();
		ByteBuffer handles = stack.malloc(handleSize);
		check(vkGetRayTracingShaderGroupHandlesKHR(vk, rayGenPipeline, 0, 1, handles), "shader group handles");
		shaderTable = createBuffer(shaderRecordSize + baseAlignment, USAGE_ADDRESS | VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true);
		shaderTableAddress = (shaderTable.address + baseAlignment - 1) / baseAlignment * baseAlignment;
		memCopy(memAddress(handles), shaderTable.mapped + (shaderTableAddress - shaderTable.address), handleSize);
		LOGGER.info("Glow trace pass: ray tracing pipeline with {}", chosen);
	}

	private long createPipeline(String name, MemoryStack stack) {
		return createPipeline(name, "", stack);
	}

	private long createPipeline(String name, String header, MemoryStack stack) {
		LongBuffer out = stack.mallocLong(1);
		ByteBuffer spirv = compileShader(name, header, Shaderc.shaderc_compute_shader);
		check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, out), "shader module");
		long module = out.get(0);
		memFree(spirv);
		VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
		info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
		check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, info, null, out), "compute pipeline");
		vkDestroyShaderModule(vk, module, null);
		return out.get(0);
	}

	private static Path shaderCacheFile(String source, int kind) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest((kind + "\n" + source).getBytes(StandardCharsets.UTF_8));
			return FabricLoader.getInstance().getGameDir().resolve("glow-shader-cache").resolve(HexFormat.of().formatHex(hash) + ".spv");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ByteBuffer readCachedShader(Path file) {
		try {
			if (!Files.exists(file)) return null;
			byte[] bytes = Files.readAllBytes(file);
			return memAlloc(bytes.length).put(bytes).flip();
		} catch (IOException e) {
			LOGGER.warn("Glow could not read a cached shader", e);
			return null;
		}
	}

	private static void writeCachedShader(Path file, ByteBuffer spirv) {
		try {
			Files.createDirectories(file.getParent());
			try (Stream<Path> old = Files.list(file.getParent())) {
				Instant expired = Instant.now().minus(Duration.ofDays(SHADER_CACHE_DAYS));
				for (Path each : (Iterable<Path>) old::iterator) {
					if (Files.getLastModifiedTime(each).toInstant().isBefore(expired)) Files.deleteIfExists(each);
				}
			}
			byte[] bytes = new byte[spirv.remaining()];
			spirv.duplicate().get(bytes);
			// Written beside it and moved into place, so a crash can't leave half a shader.
			Path partial = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(partial, bytes);
			Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Glow could not cache a shader", e);
		}
	}

	private static String readShader(String name) {
		try (InputStream in = GlowRenderer.class.getResourceAsStream("/assets/glow/shaders/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Missing Glow shader " + name, e);
		}
	}

	private static ByteBuffer compileShader(String name, String header, int kind) {
		String source = "#version 460\n" + header + readShader("common.glsl") + readShader(name);
		// The trace shader takes the better part of a minute to compile, so each result is kept on disk until its source changes.
		Path cached = shaderCacheFile(source, kind);
		ByteBuffer stored = readCachedShader(cached);
		if (stored != null) return stored;
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
		Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);
		Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
		// The source goes on the heap, because the ray tracing shader is larger than LWJGL's 64 KB thread stack.
		ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
		ByteBuffer nameBytes = org.lwjgl.system.MemoryUtil.memUTF8(name);
		ByteBuffer entryBytes = org.lwjgl.system.MemoryUtil.memUTF8("main");
		long result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, kind, nameBytes, entryBytes, options);
		org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
		org.lwjgl.system.MemoryUtil.memFree(nameBytes);
		org.lwjgl.system.MemoryUtil.memFree(entryBytes);
		try {
			if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
				throw new IllegalStateException("Glow shader " + name + " failed: " + Shaderc.shaderc_result_get_error_message(result));
			}
			ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
			ByteBuffer copy = memAlloc(bytes.remaining());
			memCopy(bytes, copy);
			writeCachedShader(cached, copy);
			return copy;
		} finally {
			Shaderc.shaderc_result_release(result);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	private Allocation createBuffer(long size, int usage, boolean hostVisible) {
		try (MemoryStack stack = stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
			if (hostVisible) alloc.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
			LongBuffer buffer = stack.mallocLong(1);
			PointerBuffer allocation = stack.mallocPointer(1);
			VmaAllocationInfo result = VmaAllocationInfo.calloc(stack);
			check(Vma.vmaCreateBuffer(vma, info, alloc, buffer, allocation, result), "buffer");
			long address = vkGetBufferDeviceAddress(vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer.get(0)));
			return new Allocation(buffer.get(0), allocation.get(0), result.pMappedData(), address, size);
		}
	}

	private void destroyLater(VulkanCommandEncoder encoder, Allocation allocation) {
		if (allocation != null) encoder.queueForDestroy(() -> Vma.vmaDestroyBuffer(vma, allocation.buffer, allocation.allocation));
	}

	private void destroyMesh(VulkanCommandEncoder encoder, Mesh mesh) {
		mesh.freeCpu();
		mesh.destroyed = true;
		long handle = mesh.handle;
		if (handle != 0L) encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, handle, null));
		destroyLater(encoder, mesh.vertices);
		destroyLater(encoder, mesh.storage);
		long translucentHandle = mesh.translucentHandle;
		if (translucentHandle != 0L) encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, translucentHandle, null));
		destroyLater(encoder, mesh.translucentStorage);
		long proxyHandle = mesh.proxyHandle;
		if (proxyHandle != 0L) encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, proxyHandle, null));
		destroyLater(encoder, mesh.proxyStorage);
		long micromap = mesh.micromap;
		if (micromap != 0L) encoder.queueForDestroy(() -> vkDestroyMicromapEXT(vk, micromap, null));
		destroyLater(encoder, mesh.micromapStorage);
	}

	private void renderFrame(LevelRenderState state) {
		VulkanCommandEncoder encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
		Mesh released;
		while ((released = RELEASED.poll()) != null) destroyMesh(encoder, released);

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || !minecraft.level.dimensionType().hasSkyLight()) return;

		VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
		frame = (frame + 1) % RING;
		readTimestamps();
		try (MemoryStack stack = stackPush()) {
			vkCmdResetQueryPool(cb, queryPool, frame * TIMESTAMPS, TIMESTAMPS);
			timestamp(cb, 0);
			try (MemoryStack buildFrame = BUILD_STACK.push()) {
				encodeCameraX = state.cameraRenderState.pos.x;
				encodeCameraY = state.cameraRenderState.pos.y;
				encodeCameraZ = state.cameraRenderState.pos.z;
				compactBuilt(cb, encoder, buildFrame);
				buildPending(cb, encoder, buildFrame);
			}
			retireStandins(encoder);
			buildDynamic(cb, encoder, stack);
			captureEffects(state);
			buildEffects(cb, encoder, stack);
			int count = writeInstances(state.cameraRenderState.pos, encoder);
			lastInstances = count;
			boolean traced = false;
			if (count > 0) {
				buildTlas(cb, encoder, count, stack);
				timestamp(cb, 1);
				if (enabled) {
					updateLights(minecraft, state.cameraRenderState.pos, encoder);
					shadowPass(cb, encoder, state, stack);
					traced = true;
				}
			}
			timestampsWritten[frame] = traced;
			historyValid = traced;
			check(vkEndCommandBuffer(cb), "end command buffer");
		}
		encoder.execute(cb);
	}

	private void timestamp(VkCommandBuffer cb, int index) {
		vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, queryPool, frame * TIMESTAMPS + index);
	}

	private void readTimestamps() {
		if (!timestampsWritten[frame]) return;
		try (MemoryStack stack = stackPush()) {
			LongBuffer values = stack.mallocLong(TIMESTAMPS);
			if (vkGetQueryPoolResults(vk, queryPool, frame * TIMESTAMPS, TIMESTAMPS, values, 8L, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return;
			for (int i = 0; i < TIMESTAMPS - 1; i++) {
				float ms = (values.get(i + 1) - values.get(i)) * timestampPeriod / 1.0E6F;
				TIMINGS[i] += (ms - TIMINGS[i]) * 0.05F;
			}
		}
	}

	/** Rescans a few sections each frame for emissive blocks, then uploads the nearest lights camera-relative. */
	private void updateLights(Minecraft minecraft, Vec3 camera, VulkanCommandEncoder encoder) {
		// Merged copies are made when a section's mesh is built, so turning the setting on or off rebuilds the meshes, between frames.
		if (mergedBuilt != GlowSettings.Toggle.MERGED_GEOMETRY.value) {
			mergedBuilt = GlowSettings.Toggle.MERGED_GEOMETRY.value;
			minecraft.execute(minecraft.levelExtractor::allChanged);
		}
		int chunkRadius = Math.max(minecraft.options.getEffectiveRenderDistance(), 1);
		int cx = SectionPos.blockToSectionCoord(camera.x), cy = SectionPos.blockToSectionCoord(camera.y), cz = SectionPos.blockToSectionCoord(camera.z);
		int width = chunkRadius * 2 + 1, height = LIGHT_SECTION_RADIUS * 2 + 1, total = width * width * height;
		int budget = Math.min(total, Math.max(LIGHT_SCAN_MIN, total / LIGHT_SCAN_FRAMES));
		for (int i = 0; i < budget; i++) {
			int index = lightCursor++ % total;
			int sx = cx - chunkRadius + index % width, sz = cz - chunkRadius + index / width % width, sy = cy - LIGHT_SECTION_RADIUS + index / (width * width);
			long key = SectionPos.asLong(sx, sy, sz);
			int[] found = scanSection(minecraft, sx, sy, sz);
			if (found == null) sectionLights.remove(key); else sectionLights.put(key, found);
		}
		sectionLights.keySet().removeIf(key -> Math.abs(SectionPos.x(key) - cx) > chunkRadius || Math.abs(SectionPos.z(key) - cz) > chunkRadius || Math.abs(SectionPos.y(key) - cy) > LIGHT_SECTION_RADIUS);

		if (lightBuffers[frame] == null) lightBuffers[frame] = createBuffer(LIGHT_GRID_OFFSET + lightGrid.length * 4L, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		int candidates = 0;
		int[][] sources = new int[sectionLights.size()][];
		int source = 0;
		for (int[] lights : sectionLights.values()) {
			sources[source] = lights;
			for (int i = 0; i < lights.length && candidates < lightOrder.length; i += LIGHT_STRIDE) {
				double dx = lights[i] + 0.5 - camera.x, dy = lights[i + 1] + 0.5 - camera.y, dz = lights[i + 2] + 0.5 - camera.z;
				lightOrder[candidates++] = (long) (dx * dx + dy * dy + dz * dz) << 32 | (long) source << 16 | i;
			}
			source++;
		}
		long out = lightBuffers[frame].mapped;
		int held = writeHeldLights(minecraft, camera, out);
		out += held * 16L;
		int scanned = Math.min(candidates, MAX_LIGHTS - held);
		if (candidates > scanned) Arrays.sort(lightOrder, 0, candidates);
		// With more lights around than the list holds, it keeps the nearest, and Path Tracing counts the glow of light blocks past them itself.
		lightListReach = candidates > scanned && scanned > 0 ? (float) Math.sqrt(lightOrder[scanned - 1] >>> 32) : candidates > scanned ? 0.0F : Float.MAX_VALUE;
		lightCount = scanned + held;
		// The few held lights are written elsewhere, so they are read back.
		for (int i = 0; i < held * 4; i++) lightMirror[i] = memGetFloat(lightBuffers[frame].mapped + i * 4L);
		for (int i = 0; i < scanned; i++) {
			int[] lights = sources[(int) (lightOrder[i] >> 16 & 0xFFFFL)];
			int at = (int) (lightOrder[i] & 0xFFFFL);
			int offset = lights[at + 4];
			int mirror = (held + i) * 4;
			lightMirror[mirror] = (float) (lights[at] + 0.5 + offsetPart(offset, 0) - camera.x);
			lightMirror[mirror + 1] = (float) (lights[at + 1] + 0.5 + offsetPart(offset, 8) - camera.y);
			lightMirror[mirror + 2] = (float) (lights[at + 2] + 0.5 + offsetPart(offset, 16) - camera.z);
			lightMirror[mirror + 3] = lights[at + 3];
			for (int part = 0; part < 4; part++) memPutFloat(out + part * 4L, lightMirror[mirror + part]);
			out += 16L;
		}
		writeLightGrid(camera, lightBuffers[frame].mapped);
	}

	/** Sorts this frame's lights into the grid's cells. A cell that more than LIGHT_GRID_STORED lights reach keeps a random set of them, new each frame, and the true count. */
	private void writeLightGrid(Vec3 camera, long mapped) {
		Arrays.fill(lightGrid, 0);
		// The same world position the shaders add to a camera-relative point: PARAMS_WORLD.
		float wx = (float) wrap(camera.x), wy = (float) camera.y, wz = (float) wrap(camera.z);
		for (int i = 0; i < lightCount; i++) {
			float x = lightMirror[i * 4] + wx, y = lightMirror[i * 4 + 1] + wy, z = lightMirror[i * 4 + 2] + wz;
			float reach = lightMirror[i * 4 + 3] % LIGHT_FULL_BLOCK % LIGHT_COLOR_STEP + LIGHT_GRID_MARGIN;
			int x0 = (int) Math.floor((x - reach) / LIGHT_GRID_CELL), x1 = (int) Math.floor((x + reach) / LIGHT_GRID_CELL);
			int y0 = (int) Math.floor((y - reach) / LIGHT_GRID_CELL), y1 = (int) Math.floor((y + reach) / LIGHT_GRID_CELL);
			int z0 = (int) Math.floor((z - reach) / LIGHT_GRID_CELL), z1 = (int) Math.floor((z + reach) / LIGHT_GRID_CELL);
			for (int cz = z0; cz <= z1; cz++) {
				for (int cy = y0; cy <= y1; cy++) {
					for (int cx = x0; cx <= x1; cx++) {
						float dx = Math.clamp(x, cx * LIGHT_GRID_CELL, (cx + 1) * LIGHT_GRID_CELL) - x, dy = Math.clamp(y, cy * LIGHT_GRID_CELL, (cy + 1) * LIGHT_GRID_CELL) - y;
						float dz = Math.clamp(z, cz * LIGHT_GRID_CELL, (cz + 1) * LIGHT_GRID_CELL) - z;
						if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
						int key = cx & 0x3FF | (cy & 0x3FF) << 10 | (cz & 0x3FF) << 20 | 0x80000000;
						int slot = key * 0x9E3779B1 >>> 19;
						for (int probe = 0; probe < LIGHT_GRID_PROBES; probe++) {
							int base = (slot + probe & LIGHT_GRID_BUCKETS - 1) * LIGHT_GRID_BUCKET_WORDS;
							if (lightGrid[base] == 0) lightGrid[base] = key;
							if (lightGrid[base] != key) continue;
							int entry = lightGrid[base + 1]++;
							if (entry >= LIGHT_GRID_STORED) {
								lightGridSeed = lightGridSeed * 1664525 + 1013904223;
								entry = (lightGridSeed >>> 8) % (entry + 1);
							}
							if (entry < LIGHT_GRID_STORED) {
								int word = base + 2 + entry / 4, shift = entry % 4 * 8;
								lightGrid[word] = lightGrid[word] & ~(0xFF << shift) | i << shift;
							}
							break;
						}
					}
				}
			}
		}
		memIntBuffer(mapped + LIGHT_GRID_OFFSET, lightGrid.length).put(lightGrid);
	}

	/** Index into the shader's light colours. */
	private static int lightColor(String block) {
		if (block.contains("soul")) return 1;
		if (block.contains("lava") || block.contains("magma")) return 2;
		if (block.contains("redstone") || block.contains("repeater") || block.contains("comparator")) return 3;
		if (block.contains("sea_lantern") || block.contains("beacon") || block.contains("end_rod") || block.contains("conduit") || block.contains("froglight")) return 4;
		if (block.contains("glowstone") || block.contains("lamp") || block.contains("shroomlight")) return 5;
		return 0;
	}

	/**
	 * Where a light block's flame or glow sits relative to the block's centre, packed as three signed bytes in 64ths of a block, x lowest.
	 * Shadows and light then come from the flame: a torch's tip, a wall torch's tip leaning out from the wall, a lantern's glass.
	 */
	private static int lightOffset(BlockState state) {
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		float x = 0.0F, y = 0.0F, z = 0.0F;
		if (name.endsWith("wall_torch") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			// The model leans 22.5 degrees out from the wall, so the flame is about 0.21 from the wall and 0.75 up. Facing points away from the wall.
			Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			x = facing.getStepX() * -0.29F;
			z = facing.getStepZ() * -0.29F;
			y = 0.25F;
		} else if (name.endsWith("torch")) {
			y = 0.1F; // A standing torch is 10 pixels tall with its flame at the top.
		} else if (name.endsWith("lantern") && !name.contains("sea_lantern") && !name.contains("jack_o")) {
			y = -0.25F; // The glass sits low in the block, standing or hanging.
		}
		return packOffset(x) | packOffset(y) << 8 | packOffset(z) << 16;
	}

	private static int packOffset(float blocks) {
		return Math.clamp(Math.round(blocks / LIGHT_OFFSET_UNIT), -127, 127) & 0xFF;
	}

	private static double offsetPart(int packed, int shift) {
		return (byte) (packed >> shift & 0xFF) * LIGHT_OFFSET_UNIT;
	}

	private static int[] scanSection(Minecraft minecraft, int sx, int sy, int sz) {
		ClientLevel level = minecraft.level;
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		if (chunk == null) return null;
		int index = level.getSectionIndexFromSectionY(sy);
		if (index < 0 || index >= chunk.getSections().length) return null;
		LevelChunkSection section = chunk.getSection(index);
		if (section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) return null;
		int[] found = new int[LIGHTS_PER_SECTION * LIGHT_STRIDE];
		int count = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16 && count < found.length; x++) {
					BlockState state = section.getBlockState(x, y, z);
					int emission = state.getLightEmission();
					if (emission > 0) {
						found[count++] = SectionPos.sectionToBlockCoord(sx) + x;
						found[count++] = SectionPos.sectionToBlockCoord(sy) + y;
						found[count++] = SectionPos.sectionToBlockCoord(sz) + z;
						int color = lightColor(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
						found[count++] = emission + color * LIGHT_COLOR_STEP + (state.isSolidRender() || color == LIGHT_COLOR_LAVA ? LIGHT_FULL_BLOCK : 0);
						found[count++] = lightOffset(state);
					}
				}
			}
		}
		return count == 0 ? null : Arrays.copyOf(found, count);
	}

	private void encodeLater(Mesh mesh) {
		if (mesh.encodeStarted) return;
		mesh.encodeStarted = true;
		synchronized (mesh) {
			ByteBuffer solid = mesh.layers[0], cutout = mesh.layers[1];
			boolean hasCutout = micromaps && cutout != null && cutout.remaining() / STRIDE / 4 > 0;
			if (!hasCutout && (solid == null || solid.remaining() / STRIDE / 4 == 0)) {
				mesh.encodeDone = true; // Nothing to encode, so build without waiting for the queue.
				return;
			}
		}
		double dx = mesh.x + 8.0 - encodeCameraX, dy = mesh.y + 8.0 - encodeCameraY, dz = mesh.z + 8.0 - encodeCameraZ;
		MICROMAP_ENCODER.execute(new EncodeTask(dx * dx + dy * dy + dz * dz, () -> {
			synchronized (mesh) {
				ByteBuffer cutout = mesh.layers[1];
				if (micromaps && cutout != null && cutout.remaining() / STRIDE / 4 > 0) mesh.encoded = GlowMicromaps.encode(cutout, STRIDE, cutout.remaining() / STRIDE / 4);
				// Only built while the setting is on: a merged copy is a second BLAS and a second instance for the section.
				if (mesh.layers[0] != null && GlowSettings.Toggle.MERGED_GEOMETRY.value) mesh.proxy = mergeSolid(mesh.layers[0]);
			}
			mesh.encodeDone = true;
		}));
	}

	private void buildPending(VkCommandBuffer cb, VulkanCommandEncoder encoder, MemoryStack stack) {
		if (micromaps) {
			TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
			TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlas;
			GlowMicromaps.update(accessor.glow$sprites(), accessor.glow$width(), accessor.glow$height(), GlowRenderer::isFoliage);
		}
		Mesh[] batch = new Mesh[BUILDS_PER_FRAME];
		int size = 0;
		for (Mesh mesh : MESHES.values()) {
			if (!mesh.processed && mesh.section.getSectionMesh() == mesh.key) {
				if (!mesh.encodeDone) {
					encodeLater(mesh);
					if (!mesh.encodeDone) continue;
				}
				batch[size++] = mesh;
				if (size == BUILDS_PER_FRAME) break;
			}
		}
		if (size == 0) return;

		VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(size * 3, stack);
		VkMicromapBuildInfoEXT.Buffer micromapInfos = VkMicromapBuildInfoEXT.calloc(size, stack);
		long[] micromapScratchSizes = new long[size];
		long micromapScratchTotal = 0L;
		int micromapCount = 0;
		PointerBuffer ranges = stack.mallocPointer(size * 3);
		long[] scratchSizes = new long[size * 3];
		long scratchTotal = 0L;
		int built = 0, compactCount = 0;
		for (int i = 0; i < size; i++) {
			Mesh mesh = batch[i];
			mesh.processed = true;
			int[] verts = new int[3];
			int geometryCount = 0, totalVerts = 0;
			boolean oversized = false;
			synchronized (mesh) {
				for (int layer = 0; layer < 3; layer++) {
					verts[layer] = mesh.layers[layer] == null ? 0 : mesh.layers[layer].remaining() / STRIDE;
					if (verts[layer] / 4 > 0) geometryCount++;
					if (verts[layer] / 4 > MAX_QUADS) oversized = true;
					totalVerts += verts[layer];
				}
			}
			if (geometryCount == 0 || oversized) {
				mesh.freeCpu();
				continue;
			}

			// The merged copy goes after the real layers in the same allocation, and needs a solid layer so geometry 0 lines up in both BLASes.
			ByteBuffer proxy;
			synchronized (mesh) {
				proxy = verts[0] / 4 > 0 ? mesh.proxy : null;
			}
			int proxyVerts = proxy == null ? 0 : proxy.remaining() / STRIDE;
			int proxyCount = proxyVerts > 0 ? 1 + (verts[1] / 4 > 0 ? 1 : 0) : 0;
			Allocation vertices = createBuffer((totalVerts + proxyVerts) * (long) STRIDE, USAGE_ADDRESS | USAGE_AS_INPUT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
			int solidCount = (verts[0] / 4 > 0 ? 1 : 0) + (verts[1] / 4 > 0 ? 1 : 0);
			VkAccelerationStructureGeometryKHR.Buffer geometries = VkAccelerationStructureGeometryKHR.calloc(Math.max(solidCount, 1), stack);
			VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(Math.max(solidCount, 1), stack);
			IntBuffer primitives = stack.mallocInt(Math.max(solidCount, 1));
			VkAccelerationStructureGeometryKHR.Buffer translucentGeometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			VkAccelerationStructureBuildRangeInfoKHR.Buffer translucentRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
			VkAccelerationStructureGeometryKHR.Buffer proxyGeometries = VkAccelerationStructureGeometryKHR.calloc(Math.max(proxyCount, 1), stack);
			VkAccelerationStructureBuildRangeInfoKHR.Buffer proxyRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(Math.max(proxyCount, 1), stack);
			IntBuffer proxyPrimitives = stack.mallocInt(Math.max(proxyCount, 1));
			long offset = 0L;
			int g = 0;
			synchronized (mesh) {
				for (int layer = 0; layer < 3; layer++) {
					int quads = verts[layer] / 4;
					if (quads == 0) continue;
					long address = vertices.address + offset;
					memCopy(memAddress(mesh.layers[layer]), vertices.mapped + offset, verts[layer] * (long) STRIDE);
					if (layer == 2) {
						triangles(translucentGeometry.get(0), address, verts[layer], LAYER_FLAGS[layer]);
						translucentRange.get(0).primitiveCount(quads * 2);
						mesh.translucentGeometry = address;
						offset += verts[layer] * (long) STRIDE;
						continue;
					}
					triangles(geometries.get(g), address, verts[layer], LAYER_FLAGS[layer]);
					if (proxyCount > 0 && layer == 1) {
						triangles(proxyGeometries.get(1), address, verts[layer], LAYER_FLAGS[layer]);
						proxyRange.get(1).primitiveCount(quads * 2);
						proxyPrimitives.put(1, quads * 2);
				}
						if (micromaps && layer == 1) {
						GlowMicromaps.Encoded encoded = mesh.encoded;
						mesh.encoded = null;
						if (encoded != null) {
							long scratchSize = buildMicromap(mesh, encoded, micromapInfos.get(micromapCount), geometries.get(g), proxyCount > 0 ? proxyGeometries.get(1) : null, encoder, stack);
							micromapScratchSizes[micromapCount++] = scratchSize;
							micromapScratchTotal += scratchSize;
						}
					}
					range.get(g).primitiveCount(quads * 2);
					primitives.put(g, quads * 2);
					mesh.geometry[g++] = address;
					offset += verts[layer] * (long) STRIDE;
				}
				if (proxyCount > 0) {
					long address = vertices.address + totalVerts * (long) STRIDE;
					memCopy(memAddress(proxy), vertices.mapped + totalVerts * (long) STRIDE, proxyVerts * (long) STRIDE);
					triangles(proxyGeometries.get(0), address, proxyVerts, LAYER_FLAGS[0]);
					proxyRange.get(0).primitiveCount(proxyVerts / 2);
					proxyPrimitives.put(0, proxyVerts / 2);
					mesh.proxyGeometry = address;
				}
			}
			recordMerge(verts[0] / 4, proxyVerts / 4);
			mesh.freeCpu();
			mesh.vertices = vertices;

			if (solidCount > 0) {
				VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(built);
				info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR | (micromaps && MICROMAP_TOGGLE ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DISABLE_OPACITY_MICROMAPS_EXT : 0)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(solidCount).pGeometries(geometries);
				VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
				vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, primitives, sizes);
				mesh.storage = createBuffer(sizes.accelerationStructureSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
				mesh.handle = createAs(mesh.storage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack);
				mesh.reference = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(mesh.handle));
				info.dstAccelerationStructure(mesh.handle);
				compactMeshes[frame][compactCount] = mesh;
				compactHandles[frame][compactCount++] = mesh.handle;
				scratchSizes[built] = align(sizes.buildScratchSize());
				scratchTotal += scratchSizes[built];
				ranges.put(built, range.address());
				built++;
			}
			if (proxyCount > 0) {
				VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(built);
				info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | (micromaps && MICROMAP_TOGGLE ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DISABLE_OPACITY_MICROMAPS_EXT : 0)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(proxyCount).pGeometries(proxyGeometries);
				VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
				vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, proxyPrimitives, sizes);
				mesh.proxyStorage = createBuffer(sizes.accelerationStructureSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
				mesh.proxyHandle = createAs(mesh.proxyStorage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack);
				mesh.proxyReference = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(mesh.proxyHandle));
				info.dstAccelerationStructure(mesh.proxyHandle);
				scratchSizes[built] = align(sizes.buildScratchSize());
				scratchTotal += scratchSizes[built];
				ranges.put(built, proxyRange.address());
				built++;
			}
			if (verts[2] / 4 > 0) {
				VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(built);
				info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(translucentGeometry);
				VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
				vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints(verts[2] / 4 * 2), sizes);
				mesh.translucentStorage = createBuffer(sizes.accelerationStructureSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
				mesh.translucentHandle = createAs(mesh.translucentStorage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack);
				mesh.translucentReference = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(mesh.translucentHandle));
				info.dstAccelerationStructure(mesh.translucentHandle);
				compactMeshes[frame][compactCount] = mesh;
				compactHandles[frame][compactCount++] = mesh.translucentHandle;
				scratchSizes[built] = align(sizes.buildScratchSize());
				scratchTotal += scratchSizes[built];
				ranges.put(built, translucentRange.address());
				built++;
			}
		}
		if (micromapCount > 0) {
			if (micromapScratch == null || micromapScratch.size < micromapScratchTotal + 256L) {
				destroyLater(encoder, micromapScratch);
				micromapScratch = createBuffer(Math.max(micromapScratchTotal * 2, 4L << 20), USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			}
			long at = align(micromapScratch.address);
			for (int i = 0; i < micromapCount; i++) {
				micromapInfos.get(i).scratchData().deviceAddress(at);
				at += micromapScratchSizes[i];
			}
			micromapInfos.limit(micromapCount);
			vkCmdBuildMicromapsEXT(cb, micromapInfos);
			// Micromap builds are a synchronization2 stage, which the legacy all-commands barrier doesn't order against the acceleration structure builds.
			VkMemoryBarrier2.Buffer built2 = VkMemoryBarrier2.calloc(1, stack).sType$Default()
				.srcStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT).srcAccessMask(VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR).dstAccessMask(VK_ACCESS_2_MICROMAP_READ_BIT_EXT | VK_ACCESS_2_SHADER_READ_BIT);
			vkCmdPipelineBarrier2(cb, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(built2));
		}
		if (built == 0) return;

		if (blasScratch == null || blasScratch.size < scratchTotal + 256L) {
			destroyLater(encoder, blasScratch);
			blasScratch = createBuffer(Math.max(scratchTotal * 2, 32L << 20), USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
		}
		long scratch = align(blasScratch.address);
		for (int i = 0; i < built; i++) {
			infos.get(i).scratchData().deviceAddress(scratch);
			scratch += scratchSizes[i];
		}
		infos.limit(built);
		ranges.limit(built);
		vkCmdBuildAccelerationStructuresKHR(cb, infos, ranges);
		barrier(cb, stack);
		// Asks how small each new BLAS can be. compactBuilt reads the answers when this ring slot comes round again.
		if (compactCount > 0) {
			vkCmdResetQueryPool(cb, compactPool, frame * COMPACT_SLOTS, compactCount);
			LongBuffer handles = stack.mallocLong(compactCount);
			handles.put(0, compactHandles[frame], 0, compactCount);
			vkCmdWriteAccelerationStructuresPropertiesKHR(cb, handles, VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, compactPool, frame * COMPACT_SLOTS);
			compactCounts[frame] = compactCount;
		}
	}

	/** Copies the BLASes built when this ring slot was last used into buffers of the size they really need, and frees the originals. */
	private void compactBuilt(VkCommandBuffer cb, VulkanCommandEncoder encoder, MemoryStack stack) {
		int count = compactCounts[frame];
		if (count == 0) return;
		compactCounts[frame] = 0;
		LongBuffer sizes = stack.mallocLong(count);
		boolean ready = vkGetQueryPoolResults(vk, compactPool, frame * COMPACT_SLOTS, count, sizes, 8L, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS;
		boolean copied = false;
		for (int i = 0; i < count; i++) {
			Mesh mesh = compactMeshes[frame][i];
			long handle = compactHandles[frame][i];
			compactMeshes[frame][i] = null;
			if (!ready || mesh.destroyed) continue;
			boolean solid = mesh.handle == handle;
			if (!solid && mesh.translucentHandle != handle) continue;
			Allocation old = solid ? mesh.storage : mesh.translucentStorage;
			long size = sizes.get(i);
			if (size <= 0L || size > old.size * COMPACT_WORTH) continue;
			Allocation storage = createBuffer(size, USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			long compact = createAs(storage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack);
			vkCmdCopyAccelerationStructureKHR(cb, VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default().src(handle).dst(compact).mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));
			long reference = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(compact));
			if (solid) {
				mesh.handle = compact;
				mesh.storage = storage;
				mesh.reference = reference;
			} else {
				mesh.translucentHandle = compact;
				mesh.translucentStorage = storage;
				mesh.translucentReference = reference;
			}
			encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, handle, null));
			destroyLater(encoder, old);
			copied = true;
			compactedBefore += old.size;
			compactedAfter += size;
			if (++compactedCount % 1024 == 0) LOGGER.info("Glow compacted {} BLASes: {} MB -> {} MB", compactedCount, compactedBefore >> 20, compactedAfter >> 20);
		}
		if (copied) barrier(cb, stack);
	}

	/** Pairs each batch with last frame's batch of the same texture, position in the frame and size. A batch with no such match gets no motion. */
	private void writePreviousPositions(VulkanCommandEncoder encoder, int capacity) {
		long needed = capacity * 4L * PREVIOUS_POSITION_SIZE;
		if (previousPositions[frame] == null || previousPositions[frame].size < needed) {
			destroyLater(encoder, previousPositions[frame]);
			previousPositions[frame] = createBuffer(needed, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		long out = previousPositions[frame].mapped;
		for (int i = 0; i < dynamicSlices.size(); i++) {
			long[] slice = dynamicSlices.get(i);
			long[] match = matchingLastSlice(i);
			long to = out + slice[1] * 4L * PREVIOUS_POSITION_SIZE;
			int vertices = (int) slice[2] * 4;
			if (match == null) {
				for (int v = 0; v < vertices; v++) memPutInt(to + v * PREVIOUS_POSITION_SIZE, NO_PREVIOUS_POSITION);
				continue;
			}
			long from = memAddress0(lastDynamicData) + match[1] * 4L * STRIDE;
			for (int v = 0; v < vertices; v++) memCopy(from + v * (long) STRIDE, to + v * PREVIOUS_POSITION_SIZE, PREVIOUS_POSITION_SIZE);
		}
	}

	private static long[] matchingLastSlice(int index) {
		if (lastDynamicData == null || lastDynamicQuads == 0) return null;
		long[] slice = dynamicSlices.get(index);
		int ordinal = 0;
		for (int i = 0; i < index; i++) {
			if (dynamicSlices.get(i)[0] == slice[0]) ordinal++;
		}
		for (long[] last : lastDynamicSlices) {
			if (last[0] != slice[0]) continue;
			if (ordinal-- == 0) return last[2] == slice[2] ? last : null;
		}
		return null;
	}

	private void buildEffects(VkCommandBuffer cb, VulkanCommandEncoder encoder, MemoryStack stack) {
		if (PARTICLES.quads() > 0) particleBlases[frame] = buildQuadBlas(particleBlases[frame], PARTICLES.data(), PARTICLES.quads(), cb, encoder, stack);
		if (WEATHER.quads() > 0) weatherBlases[frame] = buildQuadBlas(weatherBlases[frame], WEATHER.data(), WEATHER.quads(), cb, encoder, stack);
	}

	private void buildDynamic(VkCommandBuffer cb, VulkanCommandEncoder encoder, MemoryStack stack) {
		if (dynamicQuads == 0) return;
		dynamics[frame] = buildQuadBlas(dynamics[frame], dynamicData, dynamicQuads, cb, encoder, stack);
		writePreviousPositions(encoder, dynamics[frame].capacity);
	}

	/** Rebuilds a per-frame BLAS from quads, growing it if needed. */
	private DynamicBlas buildQuadBlas(DynamicBlas dynamic, ByteBuffer data, int quads, VkCommandBuffer cb, VulkanCommandEncoder encoder, MemoryStack stack) {
		VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
		info.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
			.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geometry);
		if (dynamic == null || dynamic.capacity < quads) {
			if (dynamic != null) {
				long old = dynamic.handle;
				encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, old, null));
				destroyLater(encoder, dynamic.vertices);
				destroyLater(encoder, dynamic.storage);
				destroyLater(encoder, dynamic.scratch);
			}
			int capacity = Math.min(Math.max(quads * 2, 4096), MAX_QUADS);
			Allocation vertices = createBuffer(capacity * 4L * STRIDE, USAGE_ADDRESS | USAGE_AS_INPUT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
			triangles(geometry.get(0), vertices.address, capacity * 4, 0);
			VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), stack.ints(capacity * 2), sizes);
			Allocation storage = createBuffer(sizes.accelerationStructureSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			Allocation scratch = createBuffer(sizes.buildScratchSize() + 256L, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			long handle = createAs(storage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, stack);
			long reference = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
			dynamic = new DynamicBlas(vertices, storage, scratch, handle, reference, capacity);
		} else {
			triangles(geometry.get(0), dynamic.vertices.address, dynamic.capacity * 4, 0);
		}
		memCopy(memAddress0(data), dynamic.vertices.mapped, quads * 4L * STRIDE);
		info.get(0).dstAccelerationStructure(dynamic.handle);
		info.get(0).scratchData().deviceAddress(align(dynamic.scratch.address));
		VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(quads * 2);
		vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range.address()));
		barrier(cb, stack);
		return dynamic;
	}

	/** Creates a mesh's micromap from encoded cutout states, fills its build info, and chains it into the cutout geometry, and into the merged BLAS's copy of it when there is one. Returns the build's scratch size. */
	private long buildMicromap(Mesh mesh, GlowMicromaps.Encoded encoded, VkMicromapBuildInfoEXT info, VkAccelerationStructureGeometryKHR geometry, VkAccelerationStructureGeometryKHR proxyGeometry, VulkanCommandEncoder encoder, MemoryStack stack) {
		long dataSize = align(encoded.data().capacity()), triangleSize = align(encoded.triangles().capacity());
		// Micromap data and triangle arrays must start on 256-byte boundaries.
		Allocation input = createBuffer(256L + dataSize + triangleSize + encoded.indices().capacity(), USAGE_ADDRESS | USAGE_AS_INPUT | VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT, true);
		long base = align(input.address), mapped = input.mapped + (base - input.address);
		memCopy(memAddress0(encoded.data()), mapped, encoded.data().capacity());
		memCopy(memAddress0(encoded.triangles()), mapped + dataSize, encoded.triangles().capacity());
		memCopy(memAddress0(encoded.indices()), mapped + dataSize + triangleSize, encoded.indices().capacity());
		destroyLater(encoder, input);

		VkMicromapUsageEXT.Buffer entries = usages(encoded.entriesByLevel(), stack);
		VkMicromapUsageEXT.Buffer references = usages(encoded.referencesByLevel(), stack);
		info.sType$Default().type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT).flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT).mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
			.usageCountsCount(entries.remaining()).pUsageCounts(entries).triangleArrayStride(8);
		info.data().deviceAddress(base);
		info.triangleArray().deviceAddress(base + dataSize);
		VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
		vkGetMicromapBuildSizesEXT(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, sizes);
		mesh.micromapStorage = createBuffer(sizes.micromapSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false);
		LongBuffer handle = stack.mallocLong(1);
		check(vkCreateMicromapEXT(vk, VkMicromapCreateInfoEXT.calloc(stack).sType$Default().buffer(mesh.micromapStorage.buffer).size(sizes.micromapSize())
			.type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT), null, handle), "micromap");
		mesh.micromap = handle.get(0);
		info.dstMicromap(mesh.micromap);

		VkAccelerationStructureTrianglesOpacityMicromapEXT link = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack).sType$Default()
			.indexType(VK_INDEX_TYPE_UINT32).indexStride(4).usageCountsCount(references.remaining()).pUsageCounts(references).micromap(mesh.micromap);
		link.indexBuffer().deviceAddress(base + dataSize + triangleSize);
		geometry.geometry().triangles().pNext(link.address());
		if (proxyGeometry != null) {
			VkAccelerationStructureTrianglesOpacityMicromapEXT proxyLink = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack).sType$Default()
				.indexType(VK_INDEX_TYPE_UINT32).indexStride(4).usageCountsCount(references.remaining()).pUsageCounts(references).micromap(mesh.micromap);
			proxyLink.indexBuffer().deviceAddress(base + dataSize + triangleSize);
			proxyGeometry.geometry().triangles().pNext(proxyLink.address());
		}
		return align(sizes.buildScratchSize());
	}

	private static VkMicromapUsageEXT.Buffer usages(int[] byLevel, MemoryStack stack) {
		int count = 0;
		for (int n : byLevel) if (n > 0) count++;
		VkMicromapUsageEXT.Buffer usages = VkMicromapUsageEXT.calloc(Math.max(count, 1), stack);
		int i = 0;
		for (int level = 0; level < byLevel.length; level++) {
			if (byLevel[level] > 0) usages.get(i++).count(byLevel[level]).subdivisionLevel(level).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
		}
		return usages;
	}

	private static final java.util.concurrent.atomic.AtomicLong MERGE_IN = new java.util.concurrent.atomic.AtomicLong(), MERGE_OUT = new java.util.concurrent.atomic.AtomicLong(), MERGE_MESHES = new java.util.concurrent.atomic.AtomicLong();
	private static final int MERGE_LOG_EVERY = 1024;
	private static final int COLOR_TOLERANCE = 12, LIGHT_TOLERANCE = 8;
	private static final float MERGE_EPSILON = 1.0E-4F;

	private record MergeKey(int axis, int sign, int plane, int material, int u0, int v0, int u1, int v1, int corners) {}

	/** A plane's 16x16 cells, each holding the index of the unit quad that fills it, or -1. */
	private static final class MergeGrid {
		final int[] cells = new int[256];

		MergeGrid() {
			Arrays.fill(cells, -1);
		}
	}

	private static void recordMerge(int solidQuads, int proxyQuads) {
		if (solidQuads == 0) return;
		MERGE_IN.addAndGet(solidQuads);
		MERGE_OUT.addAndGet(proxyQuads > 0 ? proxyQuads : solidQuads);
		long meshes = MERGE_MESHES.incrementAndGet();
		if (meshes % MERGE_LOG_EVERY == 0) LOGGER.info("Glow merged geometry: {} solid quads -> {} ({} sections)", MERGE_IN.get(), MERGE_OUT.get(), meshes);
	}

	/** Seed holds the seed's vertex 0 as {colour, packed}. Candidate holds a quad's four vertices as {colour, packed} pairs. */
	private static boolean mergeable(int[] seed, int[] candidate) {
		for (int i = 0; i < 4; i++) {
			int color = candidate[i * 2], packed = candidate[i * 2 + 1];
			for (int shift = 0; shift < 32; shift += 8) {
				if (Math.abs((color >>> shift & 255) - (seed[0] >>> shift & 255)) > COLOR_TOLERANCE) return false;
			}
			if (Math.abs((packed & 255) - (seed[1] & 255)) > LIGHT_TOLERANCE || Math.abs((packed >>> 8 & 255) - (seed[1] >>> 8 & 255)) > LIGHT_TOLERANCE) return false;
		}
		return true;
	}

	private static boolean cellMatches(MergeGrid grid, boolean[] used, int[][] vertexData, int[] seedData, int x, int y) {
		int cell = grid.cells[x + y * 16];
		return cell >= 0 && !used[x + y * 16] && mergeable(seedData, vertexData[cell]);
	}

	/**
	 * Merges flat runs of unit block faces in the solid layer into large rectangles, for rays that only need coarse colour.
	 * Returns the merged copy, or null when it wouldn't be at most 75% of the original.
	 */
	private static ByteBuffer mergeSolid(ByteBuffer solid) {
		int quads = solid.remaining() / STRIDE / 4;
		if (quads == 0) return null;
		int base = solid.position();
		long source = memAddress(solid);
		ByteBuffer out = memAlloc(quads * 4 * STRIDE);
		long target = memAddress(out);
		int outQuads = 0;
		Map<MergeKey, MergeGrid> groups = new HashMap<>();
		int[][] layout = new int[quads][]; // Per merge candidate: axis, corner pattern, plane, then the uv centre.
		int[][] vertexData = new int[quads][];
		for (int q = 0; q < quads; q++) {
			int at = base + q * 4 * STRIDE;
			float[][] p = new float[4][3];
			float[] u = new float[4], v = new float[4];
			int[] data = new int[8];
			for (int i = 0; i < 4; i++) {
				int o = at + i * STRIDE;
				for (int c = 0; c < 3; c++) p[i][c] = solid.getFloat(o + c * 4);
				u[i] = solid.getFloat(o + 12);
				v[i] = solid.getFloat(o + 16);
				data[i * 2 + 1] = solid.getInt(o + 20);
				data[i * 2] = solid.getInt(o + 24);
			}
			int axis = -1;
			for (int a = 0; a < 3 && axis < 0; a++) {
				boolean flat = true;
				for (int i = 1; i < 4; i++) flat &= Math.abs(p[i][a] - p[0][a]) <= MERGE_EPSILON;
				if (flat) axis = a;
			}
			boolean ok = axis >= 0;
			int t1 = (axis + 1) % 3, t2 = (axis + 2) % 3;
			int[] low = new int[2];
			int pattern = 0;
			if (ok) {
				int[] tangents = {t1, t2};
				for (int k = 0; k < 2 && ok; k++) {
					int t = tangents[k];
					float min = Math.min(Math.min(p[0][t], p[1][t]), Math.min(p[2][t], p[3][t]));
					float max = Math.max(Math.max(p[0][t], p[1][t]), Math.max(p[2][t], p[3][t]));
					low[k] = Math.round(min);
					ok = Math.abs(max - min - 1.0F) <= MERGE_EPSILON && Math.abs(min - low[k]) <= MERGE_EPSILON && low[k] >= 0 && low[k] <= 15;
					for (int i = 0; i < 4 && ok; i++) {
						boolean atMax = Math.abs(p[i][t] - max) <= MERGE_EPSILON;
						ok = atMax || Math.abs(p[i][t] - min) <= MERGE_EPSILON;
						pattern |= (atMax ? 1 : 0) << (i * 2 + k);
					}
				}
			}
			int sign = 0;
			if (ok) {
				float e1a = p[1][t1] - p[0][t1], e1b = p[1][t2] - p[0][t2], e2a = p[2][t1] - p[0][t1], e2b = p[2][t2] - p[0][t2];
				float normal = e1a * e2b - e1b * e2a;
				ok = Math.abs(normal) > 1.0E-6F;
				sign = normal > 0.0F ? 1 : 0;
				// All four corners must be distinct, or the rectangle would be degenerate.
				int seen = 0;
				for (int i = 0; i < 4; i++) seen |= 1 << (pattern >> (i * 2) & 3);
				ok &= seen == 15;
			}
			MergeGrid grid = null;
			int cell = 0;
			float centerU = 0.0F, centerV = 0.0F;
			if (ok) {
				float minU = Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3])), maxU = Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3]));
				float minV = Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])), maxV = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));
				centerU = (minU + maxU) * 0.5F;
				centerV = (minV + maxV) * 0.5F;
				MergeKey key = new MergeKey(axis, sign, Math.round(p[0][axis] * 4096.0F), data[1] >>> 16, Float.floatToIntBits(minU), Float.floatToIntBits(minV), Float.floatToIntBits(maxU), Float.floatToIntBits(maxV), pattern);
				grid = groups.computeIfAbsent(key, k -> new MergeGrid());
				cell = low[0] + low[1] * 16;
				if (grid.cells[cell] >= 0) ok = false; // Overlapping duplicate: leave it as it is.
			}
			if (!ok) {
				memCopy(source + at, target + (long) outQuads * 4 * STRIDE, 4L * STRIDE);
				outQuads++;
				continue;
			}
			grid.cells[cell] = q;
			layout[q] = new int[] {axis, pattern, Float.floatToIntBits(p[0][axis]), Float.floatToIntBits(centerU), Float.floatToIntBits(centerV)};
			vertexData[q] = data;
		}
		for (MergeGrid grid : groups.values()) {
			boolean[] used = new boolean[256];
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					int seed = grid.cells[x + y * 16];
					if (seed < 0 || used[x + y * 16]) continue;
					int[] seedData = vertexData[seed];
					int w = 1;
					while (x + w < 16 && cellMatches(grid, used, vertexData, seedData, x + w, y)) w++;
					int h = 1;
					rows:
					while (y + h < 16) {
						for (int i = 0; i < w; i++) if (!cellMatches(grid, used, vertexData, seedData, x + i, y + h)) break rows;
						h++;
					}
					for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) used[x + i + (y + j) * 16] = true;
					long to = target + (long) outQuads * 4 * STRIDE;
					outQuads++;
					if (w * h == 1) {
						memCopy(source + base + (long) seed * 4 * STRIDE, to, 4L * STRIDE);
						continue;
					}
					int[] info = layout[seed];
					int axis = info[0], pattern = info[1];
					float plane = Float.intBitsToFloat(info[2]), centerU = Float.intBitsToFloat(info[3]), centerV = Float.intBitsToFloat(info[4]);
					for (int i = 0; i < 4; i++) {
						long vertex = to + i * (long) STRIDE;
						float[] position = new float[3];
						position[axis] = plane;
						position[(axis + 1) % 3] = (pattern >> (i * 2) & 1) != 0 ? x + w : x;
						position[(axis + 2) % 3] = (pattern >> (i * 2 + 1) & 1) != 0 ? y + h : y;
						memPutFloat(vertex, position[0]);
						memPutFloat(vertex + 4, position[1]);
						memPutFloat(vertex + 8, position[2]);
						memPutFloat(vertex + 12, centerU);
						memPutFloat(vertex + 16, centerV);
						memPutInt(vertex + 20, seedData[1] | 1 << 27); // The shader reads this bit as merged, and samples a high mip for the colour.
						memPutInt(vertex + 24, seedData[0]);
					}
				}
			}
		}
		if (outQuads * 4 > quads * 3) {
			memFree(out);
			return null;
		}
		out.limit(outQuads * 4 * STRIDE);
		return out;
	}

	private static long align(long value) {
		return value + 255L & ~255L;
	}

	private void triangles(VkAccelerationStructureGeometryKHR geometry, long address, int vertices, int flags) {
		geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(flags);
		VkAccelerationStructureGeometryTrianglesDataKHR triangles = geometry.geometry().triangles();
		triangles.sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(STRIDE).maxVertex(vertices - 1).indexType(VK_INDEX_TYPE_UINT32);
		triangles.vertexData().deviceAddress(address);
		triangles.indexData().deviceAddress(indices.address);
	}

	private long createAs(Allocation storage, int type, MemoryStack stack) {
		LongBuffer handle = stack.mallocLong(1);
		check(vkCreateAccelerationStructureKHR(vk, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(storage.buffer).size(storage.size).type(type), null, handle), "acceleration structure");
		return handle.get(0);
	}

	/** Drops each stand-in once its section's new mesh is built, or once the section has no new mesh coming. */
	private void retireStandins(VulkanCommandEncoder encoder) {
		for (Map.Entry<SectionRenderDispatcher.RenderSection, Mesh> entry : STANDINS.entrySet()) {
			Mesh standin = entry.getValue();
			SectionRenderDispatcher.RenderSection section = entry.getKey();
			Mesh current = section.getSectionMesh() instanceof CompiledSectionMesh compiled ? MESHES.get(compiled) : null;
			boolean moved = section.getRenderOrigin().getX() != standin.x || section.getRenderOrigin().getY() != standin.y || section.getRenderOrigin().getZ() != standin.z;
			int age = ++standin.standinFrames;
			boolean replaced = current != null ? current.processed : age > STANDIN_GRACE_FRAMES;
			if ((replaced || moved || age > STANDIN_MAX_FRAMES) && STANDINS.remove(section, standin)) destroyMesh(encoder, standin);
		}
	}

	private int writeInstances(Vec3 camera, VulkanCommandEncoder encoder) {
		int needed = (MESHES.size() + STANDINS.size()) * 3 + 3;
		if (instanceBuffers[frame] == null || instanceBuffers[frame].size < needed * 64L) {
			int slots = Math.max(needed * 2, 4096);
			destroyLater(encoder, instanceBuffers[frame]);
			destroyLater(encoder, tableBuffers[frame]);
			instanceBuffers[frame] = createBuffer(slots * 64L, USAGE_ADDRESS | USAGE_AS_INPUT, true);
			tableBuffers[frame] = createBuffer(slots * 24L, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		long slotsAvailable = instanceBuffers[frame].size / 64L;
		int count = 0;
		for (Mesh mesh : MESHES.values()) {
			if (mesh.section.getSectionMesh() != mesh.key) continue;
			count = writeMeshInstances(mesh, camera, count, slotsAvailable);
		}
		for (Mesh mesh : STANDINS.values()) {
			count = writeMeshInstances(mesh, camera, count, slotsAvailable);
		}
		if (dynamicQuads > 0) {
			DynamicBlas dynamic = dynamics[frame];
			writeInstance(count++, 0.0F, 0.0F, 0.0F, dynamic.reference, dynamic.vertices.address, motionVectors ? previousPositions[frame].address : 0L, 0L, DYNAMIC_RAY_MASK);
		}
		if (PARTICLES.quads() > 0) writeInstance(count++, 0.0F, 0.0F, 0.0F, particleBlases[frame].reference, particleBlases[frame].vertices.address, 0L, 0L, PARTICLE_RAY_MASK);
		if (WEATHER.quads() > 0) writeInstance(count++, 0.0F, 0.0F, 0.0F, weatherBlases[frame].reference, weatherBlases[frame].vertices.address, 0L, 0L, WEATHER_RAY_MASK);
		return count;
	}

	/** A section's exact BLAS, its merged BLAS when it has one, and its water and glass. */
	private int writeMeshInstances(Mesh mesh, Vec3 camera, int count, long slotsAvailable) {
		if (count + 6 >= slotsAvailable) return count;
		float x = (float) (mesh.x - camera.x), y = (float) (mesh.y - camera.y), z = (float) (mesh.z - camera.z);
		if (mesh.handle != 0L) {
			boolean merged = mesh.proxyHandle != 0L;
			writeInstance(count++, x, y, z, mesh.reference, mesh.geometry[0], mesh.geometry[1], 0L, merged ? WORLD_RAY_MASK : WORLD_RAY_MASK | MERGED_RAY_MASK);
			if (merged) writeInstance(count++, x, y, z, mesh.proxyReference, mesh.proxyGeometry, mesh.geometry[1], 0L, MERGED_RAY_MASK);
		}
		if (mesh.translucentHandle != 0L) writeInstance(count++, x, y, z, mesh.translucentReference, mesh.translucentGeometry, 0L, 0L, TRANSLUCENT_RAY_MASK);
		return count;
	}

	private void writeInstance(int index, float x, float y, float z, long reference, long first, long second, long third) {
		writeInstance(index, x, y, z, reference, first, second, third, WORLD_RAY_MASK);
	}

	private void writeInstance(int index, float x, float y, float z, long reference, long first, long second, long third, int mask) {
		long out = instanceBuffers[frame].mapped + index * 64L;
		memSet(out, 0, 48);
		memPutFloat(out, 1.0F);
		memPutFloat(out + 12, x);
		memPutFloat(out + 20, 1.0F);
		memPutFloat(out + 28, y);
		memPutFloat(out + 40, 1.0F);
		memPutFloat(out + 44, z);
		memPutInt(out + 48, index & 0xFFFFFF | mask << 24);
		memPutInt(out + 52, micromapsIgnored ? VK_GEOMETRY_INSTANCE_DISABLE_OPACITY_MICROMAPS_EXT << 24 : 0);
		memPutLong(out + 56, reference);
		long table = tableBuffers[frame].mapped + index * 24L;
		memPutLong(table, first);
		memPutLong(table + 8, second);
		memPutLong(table + 16, third);
	}

	private void buildTlas(VkCommandBuffer cb, VulkanCommandEncoder encoder, int count, MemoryStack stack) {
		VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
		geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
		VkAccelerationStructureGeometryInstancesDataKHR instances = geometry.get(0).geometry().instances();
		instances.sType$Default().arrayOfPointers(false);
		instances.data().deviceAddress(instanceBuffers[frame].address);

		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
		info.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
			.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geometry);

		if (count > capacity) {
			capacity = Math.max(count * 2, 4096);
			VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), stack.ints(capacity), sizes);
			long old = tlas;
			if (old != 0L) encoder.queueForDestroy(() -> vkDestroyAccelerationStructureKHR(vk, old, null));
			destroyLater(encoder, tlasStorage);
			destroyLater(encoder, tlasScratch);
			tlasStorage = createBuffer(sizes.accelerationStructureSize(), USAGE_ADDRESS | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			tlasScratch = createBuffer(sizes.buildScratchSize() + 256L, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			tlas = createAs(tlasStorage, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, stack);
		}
		info.get(0).dstAccelerationStructure(tlas);
		info.get(0).scratchData().deviceAddress(align(tlasScratch.address));
		VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(count);
		vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range.address()));
		barrier(cb, stack);
	}

	private long[] createImage(int format, int usage, int width, int height, MemoryStack stack) {
		VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
			.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
		info.extent().set(width, height, 1);
		LongBuffer image = stack.mallocLong(1);
		PointerBuffer allocation = stack.mallocPointer(1);
		check(Vma.vmaCreateImage(vma, info, VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO), image, allocation, null), "image");
		VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.get(0)).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
		view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		LongBuffer viewOut = stack.mallocLong(1);
		check(vkCreateImageView(vk, view, null, viewOut), "image view");
		return new long[] {image.get(0), allocation.get(0), viewOut.get(0)};
	}

	private void destroyImageLater(VulkanCommandEncoder encoder, long image, long allocation, long view) {
		encoder.queueForDestroy(() -> {
			vkDestroyImageView(vk, view, null);
			Vma.vmaDestroyImage(vma, image, allocation);
		});
	}

	private void ensureOutput(VulkanCommandEncoder encoder, VulkanGpuTexture color, int format, int traceWidth, int traceHeight, MemoryStack stack) {
		int width = color.getWidth(0), height = color.getHeight(0);
		if (outImage != 0L && outWidth == width && outHeight == height && renderWidth == traceWidth && renderHeight == traceHeight) return;
		if (outImage != 0L) {
			destroyImageLater(encoder, outImage, outAllocation, outView);
			for (int i = 0; i < IMAGE_COUNT; i++) destroyImageLater(encoder, images[i], imageAllocations[i], imageViews[i]);
		}
		long[] created = createImage(format, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, width, height, stack);
		outImage = created[0];
		outAllocation = created[1];
		outView = created[2];
		for (int i = 0; i < IMAGE_COUNT; i++) {
			// Reservoirs hold exact float bits, because 16 bit positions would drift from the surfaces they sit on.
			boolean reservoir = i >= RESERVOIR_A_IMAGE && i < SHADING_NORMAL_IMAGE || i == LIGHT_RESERVOIR_IMAGE || i == LIGHT_RESERVOIR_IMAGE + 1;
			int imageFormat = reservoir ? VK_FORMAT_R32G32B32A32_UINT : i == ACCUMULATION_IMAGE ? VK_FORMAT_R32G32B32A32_SFLOAT : i == RENDER_DEPTH_IMAGE || i == SPECULAR_HIT_IMAGE || i == LINEAR_DEPTH_IMAGE ? VK_FORMAT_R32_SFLOAT : VK_FORMAT_R16G16B16A16_SFLOAT;
			// Everything traces at the render size. Only DLSS's output is the full size, and light shafts, the last signal, are half the render size.
			boolean full = i == RR_OUTPUT_IMAGE, half = i >= SHAFT_SIGNAL * SIGNAL_IMAGES && i < SIGNALS * SIGNAL_IMAGES;
			created = createImage(imageFormat, i >= SHADING_NORMAL_IMAGE && i < LIGHT_RESERVOIR_IMAGE || i == LINEAR_DEPTH_IMAGE ? RR_COLOR_USAGE : VK_IMAGE_USAGE_STORAGE_BIT,
				full ? width : half ? (traceWidth + 1) / 2 : traceWidth, full ? height : half ? (traceHeight + 1) / 2 : traceHeight, stack);
			images[i] = created[0];
			imageAllocations[i] = created[1];
			imageViews[i] = created[2];
		}
		outWidth = width;
		outHeight = height;
		renderWidth = traceWidth;
		renderHeight = traceHeight;
		outNeedsLayout = true;
		historyValid = false;
	}

	private void transitionImages(VkCommandBuffer cb, MemoryStack stack) {
		VkImageMemoryBarrier.Buffer transitions = VkImageMemoryBarrier.calloc(IMAGE_COUNT + 1, stack);
		for (int i = 0; i <= IMAGE_COUNT; i++) {
			VkImageMemoryBarrier transition = transitions.get(i).sType$Default()
				.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(i == IMAGE_COUNT ? outImage : images[i]).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			transition.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		}
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, transitions);
	}

	private void writeDescriptors(long set, int signal, long depthView, long colorView, long atlasView, MemoryStack stack) {
		VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(tlas));
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4 + STORAGE_BINDINGS, stack);
		writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
		long[] views = {depthView, colorView};
		for (int i = 0; i < 2; i++) {
			VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
			writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(image);
		}
		// Slot 0 is the block atlas; unused slots repeat it so every descriptor stays valid.
		VkDescriptorImageInfo.Buffer textures = VkDescriptorImageInfo.calloc(MAX_TEXTURES, stack);
		for (int i = 0; i < MAX_TEXTURES; i++) {
			long view = i > 0 && i < dynamicTextureCount ? DYNAMIC_TEXTURES[i] : atlasView;
			textures.get(i).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
		}
		writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES).pImageInfo(textures);

		// The ring is even, so odd and even frames swap which history images count as current and previous.
		int even = frame & 1, odd = even ^ 1;
		int base = signal * SIGNAL_IMAGES;
		long[] storage = {
			outView, imageViews[base], imageViews[G_BUFFER_IMAGE + even], imageViews[G_BUFFER_IMAGE + odd],
			imageViews[base + 1 + even], imageViews[base + 1 + odd], imageViews[base + 3 + even], imageViews[base + 3 + odd], imageViews[base + 5], imageViews[base + 6],
			imageViews[0], imageViews[SIGNAL_IMAGES], imageViews[SIGNAL_IMAGES * 2], imageViews[ALBEDO_IMAGE],
			imageViews[5], imageViews[SIGNAL_IMAGES + 5], imageViews[SIGNAL_IMAGES * 2 + 5],
			imageViews[SIGNAL_IMAGES * 3], imageViews[SIGNAL_IMAGES * 3 + 5], imageViews[DETAIL_IMAGE], imageViews[PARALLAX_IMAGE], imageViews[MOTION_IMAGE],
			imageViews[RESERVOIR_A_IMAGE + even], imageViews[RESERVOIR_A_IMAGE + odd], imageViews[RESERVOIR_B_IMAGE + even], imageViews[RESERVOIR_B_IMAGE + odd], imageViews[SHADING_NORMAL_IMAGE],
			imageViews[RR_INPUT_IMAGE], imageViews[RR_OUTPUT_IMAGE], imageViews[SCREEN_MOTION_IMAGE], imageViews[SPECULAR_GUIDE_IMAGE], imageViews[DIFFUSE_GUIDE_IMAGE],
			imageViews[RENDER_DEPTH_IMAGE], imageViews[SPECULAR_MOTION_IMAGE], imageViews[SPECULAR_HIT_IMAGE],
			imageViews[LIGHT_RESERVOIR_IMAGE + even], imageViews[LIGHT_RESERVOIR_IMAGE + odd], imageViews[LINEAR_DEPTH_IMAGE], imageViews[ACCUMULATION_IMAGE]
		};
		for (int i = 0; i < storage.length; i++) {
			VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack).imageView(storage[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
			writes.get(4 + i).sType$Default().dstSet(set).dstBinding(4 + i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(image);
		}
		vkUpdateDescriptorSets(vk, writes, null);
	}

	private void bindSignal(VkCommandBuffer cb, int signal, MemoryStack stack) {
		vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSets[frame * SIGNALS + signal]), null);
		if (rayGenPipeline != 0L) vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(descriptorSets[frame * SIGNALS + signal]), null);
	}

	/** Maps the block atlas to materials, and copies each sprite's labPBR textures to the same place in matching atlases. Sprites without them fall back to their name. */
	private void updateMaterials(Minecraft minecraft, VulkanCommandEncoder encoder, VkCommandBuffer cb, MemoryStack stack) {
		TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		TextureAtlasAccessor accessor = (TextureAtlasAccessor) atlas;
		Map<Identifier, TextureAtlasSprite> sprites = accessor.glow$sprites();
		if (sprites == materialSprites) return;
		materialSprites = sprites;
		destroyLater(encoder, materialMap);
		materialWidth = accessor.glow$width() / MATERIAL_CELL;
		materialHeight = accessor.glow$height() / MATERIAL_CELL;
		materialMap = createBuffer(materialWidth * (long) materialHeight * 4L, USAGE_ADDRESS | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		memSet(materialMap.mapped, 0, materialMap.size);
		long stagingSize = 0L;
		for (TextureAtlasSprite sprite : sprites.values()) stagingSize += sprite.contents().width() * (long) sprite.contents().height() * 8L;
		Allocation staging = createBuffer(stagingSize, USAGE_ADDRESS | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
		destroyLater(encoder, staging);
		VkBufferImageCopy.Buffer normalCopies = VkBufferImageCopy.calloc(sprites.size());
		VkBufferImageCopy.Buffer specularCopies = VkBufferImageCopy.calloc(sprites.size());
		ResourceManager resources = minecraft.getResourceManager();
		long stagingAt = 0L;
		int normals = 0, speculars = 0;
		for (Map.Entry<Identifier, TextureAtlasSprite> entry : sprites.entrySet()) {
			int material = materialFor(entry.getKey().getPath());
			TextureAtlasSprite sprite = entry.getValue();
			int width = sprite.contents().width(), height = sprite.contents().height();
			int atlasX = Math.round(sprite.getU0() * accessor.glow$width()), atlasY = Math.round(sprite.getV0() * accessor.glow$height());
			if (copyLabPbr(resources, entry.getKey(), "_n", width, height, staging.mapped + stagingAt)) {
				normalCopies.get(normals++).bufferOffset(stagingAt).imageSubresource(layer -> layer.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
					.imageOffset(offset -> offset.set(atlasX, atlasY, 0)).imageExtent(extent -> extent.set(width, height, 1));
				stagingAt += width * (long) height * 4L;
			}
			if (copyLabPbr(resources, entry.getKey(), "_s", width, height, staging.mapped + stagingAt)) {
				specularCopies.get(speculars++).bufferOffset(stagingAt).imageSubresource(layer -> layer.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
					.imageOffset(offset -> offset.set(atlasX, atlasY, 0)).imageExtent(extent -> extent.set(width, height, 1));
				stagingAt += width * (long) height * 4L;
				if (material != MATERIAL_WATER) material = isEmissive(entry.getKey().getPath()) ? MATERIAL_LABPBR_EMISSIVE : MATERIAL_LABPBR;
			}
			if (isFoliage(entry.getKey().getPath())) material |= MATERIAL_FOLIAGE;
			if (material == 0) continue;
			// Sprite x and y include atlas padding, so the cells come from the UVs that vertices actually use.
			int x0 = (int) (sprite.getU0() * materialWidth), y0 = (int) (sprite.getV0() * materialHeight);
			int x1 = (int) Math.ceil(sprite.getU1() * materialWidth) - 1, y1 = (int) Math.ceil(sprite.getV1() * materialHeight) - 1;
			for (int y = y0; y <= y1 && y < materialHeight; y++) {
				for (int x = x0; x <= x1 && x < materialWidth; x++) {
					memPutInt(materialMap.mapped + (y * (long) materialWidth + x) * 4L, material);
				}
			}
		}
		normalCopies.limit(normals);
		specularCopies.limit(speculars);
		uploadLabPbr(encoder, cb, normalAtlas, normals > 0, new float[] {0.5F, 0.5F, 1.0F, 1.0F}, staging, normalCopies, accessor, stack);
		uploadLabPbr(encoder, cb, specularAtlas, speculars > 0, new float[] {0.0F, 0.0F, 0.0F, 0.0F}, staging, specularCopies, accessor, stack);
		normalCopies.free();
		specularCopies.free();
		LOGGER.info("Glow labPBR: {} normal and {} specular sprites of {} in a {}x{} atlas", normals, speculars, sprites.size(), accessor.glow$width(), accessor.glow$height());
	}

	/** Copies the first frame of a sprite's labPBR texture into staging memory, resized if it doesn't match the sprite. */
	private static boolean copyLabPbr(ResourceManager resources, Identifier sprite, String suffix, int width, int height, long out) {
		Optional<Resource> resource = resources.getResource(sprite.withPath(path -> "textures/" + path + suffix + ".png"));
		if (resource.isEmpty()) return false;
		try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
			int sourceWidth = image.getWidth();
			int frameHeight = Math.min(sourceWidth * height / width, image.getHeight());
			int[] pixels = image.getPixelsABGR();
			for (int y = 0; y < height; y++) {
				int row = (y * frameHeight / height) * sourceWidth;
				for (int x = 0; x < width; x++) memPutInt(out + (y * (long) width + x) * 4L, pixels[row + x * sourceWidth / width]);
			}
			return true;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Glow can't read {}{}: {}", sprite, suffix, e.toString());
			return false;
		}
	}

	private void uploadLabPbr(VulkanCommandEncoder encoder, VkCommandBuffer cb, long[] atlas, boolean used, float[] clear, Allocation staging, VkBufferImageCopy.Buffer copies, TextureAtlasAccessor accessor, MemoryStack stack) {
		if (atlas[0] != 0L) destroyImageLater(encoder, atlas[0], atlas[1], atlas[2]);
		atlas[0] = 0L;
		if (!used) return;
		long[] created = createImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, accessor.glow$width(), accessor.glow$height(), stack);
		System.arraycopy(created, 0, atlas, 0, 3);
		VkImageMemoryBarrier.Buffer transition = VkImageMemoryBarrier.calloc(1, stack).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(atlas[0])
			.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
		transition.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, transition);
		VkClearColorValue color = VkClearColorValue.calloc(stack);
		for (int i = 0; i < 4; i++) color.float32(i, clear[i]);
		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		vkCmdClearColorImage(cb, atlas[0], VK_IMAGE_LAYOUT_GENERAL, color, range);
		barrier(cb, stack);
		vkCmdCopyBufferToImage(cb, staging.buffer, atlas[0], VK_IMAGE_LAYOUT_GENERAL, copies);
		barrier(cb, stack);
	}

	private static double wrap(double value) {
		return value - Math.floor(value / WRAP) * WRAP;
	}

	private static boolean isFoliage(String path) {
		if (path.contains("_block") || path.contains("planks") || path.contains("door") || path.contains("_log") || path.contains("_stem")) return false;
		for (String name : FOLIAGE_NAMES) {
			if (path.contains(name)) return true;
		}
		return false;
	}

	private static boolean isEmissive(String path) {
		return path.contains("lava") || path.contains("glowstone") || path.contains("sea_lantern") || path.contains("shroomlight") || path.contains("magma")
			|| (path.contains("torch") && !path.contains("torchflower")) || path.contains("froglight") || path.contains("redstone_lamp_on") || path.contains("crying_obsidian") || path.contains("end_rod")
			|| path.contains("beacon") || path.contains("jack_o_lantern") || path.endsWith("/lantern") || path.endsWith("/soul_lantern") || path.startsWith("block/fire_")
			|| path.startsWith("block/soul_fire_") || path.contains("campfire_fire") || path.contains("campfire_log_lit") || isGlowingBlockSprite(path);
	}

	/** Catches every block the game says glows, including modded ones, by matching its sprites to its name: block/amethyst_cluster, block/sculk_catalyst_top. */
	private static boolean isGlowingBlockSprite(String path) {
		if (!path.startsWith("block/")) return false;
		if (glowingBlocks == null) {
			glowingBlocks = new HashSet<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
				// Excluded names are handled above, or are prefixes of blocks that don't glow, such as light_blue_wool and fire_coral.
				if (block.defaultBlockState().getLightEmission() >= VISIBLE_GLOW_EMISSION && !GLOW_EXCLUDED_BLOCKS.contains(name)) glowingBlocks.add(name);
			}
		}
		for (String name = path.substring(6); ; name = name.substring(0, name.lastIndexOf('_'))) {
			if (glowingBlocks.contains(name)) return true;
			if (name.indexOf('_') < 0) return false;
		}
	}

	private static int materialFor(String path) {
		if (path.equals("block/water_still") || path.equals("block/water_flow") || path.equals("block/water_overlay")) return 7;
		if (isEmissive(path)) return 6;
		if (path.contains("iron_block") || path.contains("gold_block") || path.contains("netherite_block") || path.equals("block/copper_block") || path.contains("cut_copper")) return 1;
		if (path.contains("diamond_block") || path.contains("emerald_block") || path.contains("amethyst_block")) return 2;
		if (path.contains("polished_") || path.contains("obsidian") || path.contains("quartz") || path.contains("prismarine")) return 3;
		if (path.endsWith("/ice") || path.contains("packed_ice") || path.contains("blue_ice")) return 4;
		if (path.contains("glazed_terracotta") || path.contains("_concrete") && !path.contains("powder")) return 5;
		return 0;
	}

	/** Runs a pass over every render pixel. */
	private void dispatch(VkCommandBuffer cb, long pipeline, ByteBuffer push, int iteration, MemoryStack stack) {
		dispatch(cb, pipeline, push, iteration, renderWidth, renderHeight, stack);
	}

	private void dispatch(VkCommandBuffer cb, long pipeline, ByteBuffer push, int iteration, int width, int height, MemoryStack stack) {
		push.putInt(200, iteration);
		vkCmdPushConstants(cb, pipelineLayout, PIPELINE_STAGES, 0, push);
		if (pipeline == tracePipeline && rayGenPipeline != 0L) {
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, rayGenPipeline);
			VkStridedDeviceAddressRegionKHR rayGen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(shaderTableAddress).stride(shaderRecordSize).size(shaderRecordSize);
			VkStridedDeviceAddressRegionKHR none = VkStridedDeviceAddressRegionKHR.calloc(stack);
			vkCmdTraceRaysKHR(cb, rayGen, none, none, none, width, height, 1);
		} else {
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
		}
		barrier(cb, stack);
	}

	private void shadowPass(VkCommandBuffer cb, VulkanCommandEncoder encoder, LevelRenderState state, MemoryStack stack) {
		Minecraft minecraft = Minecraft.getInstance();
		useTraceVariant(GlowSettings.pathTraced() ? 1 : 0, stack);
		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		VulkanGpuTextureView colorView = (VulkanGpuTextureView) target.getColorTextureView();
		VulkanGpuTextureView depthView = (VulkanGpuTextureView) target.getDepthTextureView();
		VulkanGpuTextureView atlasView = (VulkanGpuTextureView) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).textureView();
		VulkanGpuTexture color = colorView.texture();
		// A resize between planning and tracing leaves the plan stale, so this frame runs without DLSS.
		if (planOutWidth != color.getWidth(0) || planOutHeight != color.getHeight(0)) {
			frameDlssMode = -1;
			frameRayReconstruction = false;
			planRenderWidth = color.getWidth(0);
			planRenderHeight = color.getHeight(0);
			renderJitterX = renderJitterY = rasterJitterX = rasterJitterY = 0.0F;
		}
		ensureOutput(encoder, color, VulkanConst.toVk(color.getFormat()), planRenderWidth, planRenderHeight, stack);
		if (outNeedsLayout) {
			transitionImages(cb, stack);
			outNeedsLayout = false;
		}

		updateMaterials(minecraft, encoder, cb, stack);
		// Texture slots are handed out again every frame, so these have to ask each time too.
		int normalSlot = normalAtlas[0] != 0L ? dynamicTexture(normalAtlas[2]) : -1;
		int specularSlot = specularAtlas[0] != 0L ? dynamicTexture(specularAtlas[2]) : -1;
		int cloudSlot = -1;
		// Reflections and enhanced water trace vanilla's cloud boxes for rays that reach the sky, so they need the cloud map even without cloud shadows.
		boolean cloudsVisible = state.cloudColor != 0 && !Float.isNaN(state.cloudHeight) && minecraft.options.cloudStatus().get() != CloudStatus.OFF;
		if (cloudsVisible && (GlowSettings.on(GlowSettings.Option.CLOUD_SHADOWS) || GlowSettings.on(GlowSettings.Option.WATER) || GlowSettings.on(GlowSettings.Option.REFLECTIONS))) {
			cloudSlot = dynamicTexture(((VulkanGpuTextureView) minecraft.getTextureManager().getTexture(CLOUD_TEXTURE).textureView()).vkImageView());
		}
		// Each signal's set is bound only for the denoiser, which Ray Reconstruction runs for light shafts alone. The first set also serves every other pass.
		for (int signal = 0; signal < SIGNALS; signal++) {
			if (frameRayReconstruction && signal != 0 && signal != 3) continue;
			writeDescriptors(descriptorSets[frame * SIGNALS + signal], signal, depthView.vkImageView(), colorView.vkImageView(), atlasView.vkImageView(), stack);
		}

		CameraRenderState camera = state.cameraRenderState;
		Matrix4f viewProj = new Matrix4f(PROJECTION).mul(camera.viewRotationMatrix);
		Matrix4f inverse = new Matrix4f(viewProj).invert();
		SkyRenderState sky = state.skyRenderState;
		boolean sun = Math.cos(sky.sunAngle) > 0.0;
		float angle = sun ? sky.sunAngle : sky.moonAngle;
		// The vanilla sun moves exactly along the X axis; a small tilt stops Z-facing walls sitting at N.L = 0.
		float lightLength = (float) Math.sqrt(1.0 + SUN_TILT * SUN_TILT);
		float lightX = (float) -Math.sin(angle) / lightLength, lightY = (float) Math.cos(angle) / lightLength, lightZ = SUN_TILT / lightLength;
		float horizon = Math.clamp(Math.abs(lightY) / 0.12F, 0.0F, 1.0F);
		float fade = horizon * horizon * (3.0F - 2.0F * horizon) * sky.rainBrightness;
		float strength = (sun ? SUN_STRENGTH : MOON_STRENGTH) * fade;
		float range = minecraft.options.getEffectiveRenderDistance() * 16.0F;

		ByteBuffer push = stack.calloc(PUSH_SIZE);
		inverse.get(0, push);
		previousViewProj.get(64, push);
		Vec3 pos = camera.pos;
		push.putFloat(128, (float) (pos.x - previousX)).putFloat(132, (float) (pos.y - previousY)).putFloat(136, (float) (pos.z - previousZ)).putFloat(140, historyValid && GlowSettings.denoiserLevel() != GlowSettings.Quality.OFF ? 1.0F : 0.0F);
		push.putFloat(144, lightX).putFloat(148, lightY).putFloat(152, lightZ).putFloat(156, strength);
		push.putFloat(160, range * 0.8F).putFloat(164, range).putFloat(168, sun ? fade : 0.0F).putFloat(172, GlowSettings.exposure());
		push.putLong(176, tableBuffers[frame].address);
		push.putLong(184, lightBuffers[frame].address);
		push.putInt(192, lightCount);
		push.putInt(196, frameIndex++);
		boolean rayReconstruction = frameRayReconstruction;
		boolean superResolution = !rayReconstruction && frameDlssMode >= 0;
		boolean pathTraced = GlowSettings.pathTraced();
		boolean accumulates = pathTraced && !rayReconstruction && !superResolution && GlowSettings.PathSetting.ACCUMULATION.value > 0;
		int fingerprint = GlowSettings.fingerprint();
		boolean still = historyValid && viewProj.equals(previousViewProj, 1.0E-6F) && pos.x == previousX && pos.y == previousY && pos.z == previousZ && fingerprint == accumulationFingerprint
			&& lightCount == accumulationLights && Math.abs(sky.sunAngle - accumulationSun) < ACCUMULATION_SUN_DRIFT && Math.abs(sky.rainBrightness - accumulationRain) < ACCUMULATION_RAIN_DRIFT;
		boolean accumulationReset = !accumulates || !still || accumulatedFrames == 0; // The first frame back in Path Tracing may be still, but the history is from an older view.
		if (accumulationReset) {
			accumulatedFrames = 0;
			accumulationSun = sky.sunAngle;
			accumulationRain = sky.rainBrightness;
		}
		accumulationFingerprint = fingerprint;
		accumulationLights = lightCount;
		if (accumulates) accumulatedFrames = Math.min(accumulatedFrames + 1, (int) GlowSettings.PathSetting.ACCUMULATION.number());
		// Reused samples repeat from frame to frame, so a still view that averages frames takes fresh ones instead.
		// Ray Reconstruction expects independent noise: samples shared between pixels reach it as blotches it keeps, and it is cleaner and faster without them.
		boolean reuse = accumulationReset && !rayReconstruction;
		boolean restirGi = GlowSettings.Toggle.RESTIR_GI.value && reuse;
		boolean restirDi = GlowSettings.Toggle.RESTIR_DI.value && reuse;
		boolean globalIllumination = GlowSettings.on(GlowSettings.Option.GLOBAL_ILLUMINATION);
		boolean reflections = GlowSettings.on(GlowSettings.Option.REFLECTIONS);
		// Under enhanced water the scattering pass carries the water's own haze and light shafts, so it runs even with Light Shafts off.
		boolean volumetrics = GlowSettings.on(GlowSettings.Option.LIGHT_SHAFTS) || GlowSettings.underwaterColor() >= 0;
		push.putInt(204, (globalIllumination ? 1 : 0) | (reflections ? 2 : 0) | (volumetrics ? 4 : 0) | (GlowSettings.on(GlowSettings.Option.MATERIALS) ? 8 : 0)
			| (GlowSettings.on(GlowSettings.Option.PARALLAX) ? 16 : 0) | (GlowSettings.denoiserLevel() == GlowSettings.Quality.OFF ? 32 : 0)
			| (restirGi ? 64 : 0) | (historyValid ? 128 : 0) | (rayReconstruction ? 256 : 0) | (restirDi ? 512 : 0) | (GlowSettings.Toggle.RADIANCE_CACHE.value && !pathTraced ? 1024 : 0) | (GlowSettings.Toggle.AUTO_EXPOSURE.value ? 2048 : 0)
			| (GlowSettings.Toggle.RESTIR_CORNER_LIMITS.value ? 4096 : 0) | (GlowSettings.Toggle.WATER_TRACED_BOUNCE.value ? 8192 : 0) | (GlowSettings.Toggle.CAUSTIC_LIMIT.value ? 16384 : 0)
			| (solidLeafShadows || !GlowSettings.on(GlowSettings.Option.FOLIAGE) ? 1048576 : 0) | (pathTraced ? 32768 : 0) | (accumulates ? 65536 : 0) | (accumulationReset ? 131072 : 0) | (GlowSettings.distanceFogActive() ? 262144 : 0) | (GlowSettings.distanceFogOutdoors() ? 524288 : 0)
			| (GlowSettings.Toggle.CACHED_SUN.value && GlowSettings.on(GlowSettings.Option.SUN_SHADOWS) && !accumulates ? 2097152 : 0) | (GlowSettings.Toggle.MERGED_GEOMETRY.value ? 4194304 : 0));
		// Clouds drift with world time, matching the offset the vanilla cloud renderer uses.
		double ticks = minecraft.level.getGameTime() + state.worldPartialTicks;
		long params = lightBuffers[frame].mapped + MAX_LIGHTS * 16L;
		memPutFloat(params, (float) wrap(pos.x));
		memPutFloat(params + 4L, (float) pos.y);
		memPutFloat(params + 8L, (float) wrap(pos.z));
		memPutFloat(params + 12L, (float) (System.nanoTime() / 1.0E9 % 3600.0));
		memPutFloat(params + 16L, (float) wrap(pos.x + ticks * 0.03));
		memPutFloat(params + 20L, (float) (state.cloudHeight - pos.y));
		memPutFloat(params + 24L, (float) wrap(pos.z + 3.96));
		memPutFloat(params + 28L, cloudSlot);
		memPutFloat(params + 32L, normalSlot);
		memPutFloat(params + 36L, specularSlot);
		// Vanilla's cloud colour as raw ARGB bits, then the distance its clouds fade out over.
		memPutInt(params + 40L, cloudSlot >= 0 ? state.cloudColor : 0);
		memPutFloat(params + 44L, minecraft.options.cloudRange().get() * 16.0F);
		GlowSettings.writeParams(params + 48L);
		long upscale = params + 48L + GlowSettings.PARAM_VECTORS * 16L;
		memPutFloat(upscale, (float) outWidth / renderWidth);
		memPutFloat(upscale + 4L, (float) outHeight / renderHeight);
		memPutFloat(upscale + 8L, renderJitterX);
		memPutFloat(upscale + 12L, renderJitterY);
		memPutFloat(upscale + 16L, rasterJitterX);
		memPutFloat(upscale + 20L, rasterJitterY);
		memPutFloat(upscale + 24L, lightListReach);
		// The Nether and the End have no sun or sky, so they get a dim, coloured ambient instead.
		boolean hasSky = minecraft.level.dimensionType().hasSkyLight();
		if (hasSky) {
			push.putFloat(208, sky.skyColor.x()).putFloat(212, sky.skyColor.y()).putFloat(216, sky.skyColor.z());
		} else if (minecraft.level.dimensionType().hasCeiling()) {
			push.putFloat(208, 0.3F).putFloat(212, 0.16F).putFloat(216, 0.12F);
		} else {
			push.putFloat(208, 0.18F).putFloat(212, 0.14F).putFloat(216, 0.25F);
		}
		push.putFloat(220, (float) GlowSettings.Tuning.FOG_DENSITY.value * FOG_DENSITY_SCALE);
		float sunLight = hasSky ? fade * (sun ? 1.0F : MOON_LIGHT) : 0.0F;
		// Sunlight takes the colour of the air it crosses: white at noon, orange then red toward the horizon.
		float sunX = (float) -Math.sin(sky.sunAngle) / lightLength, sunY = (float) Math.cos(sky.sunAngle) / lightLength;
		float moonX = (float) -Math.sin(sky.moonAngle) / lightLength, moonY = (float) Math.cos(sky.moonAngle) / lightLength;
		float[] sunTint = Atmosphere.sunColor(sunY);
		if (sun) push.putFloat(224, sunLight * sunTint[0]).putFloat(228, sunLight * sunTint[1]).putFloat(232, sunLight * sunTint[2]).putFloat(236, sunLight);
		else push.putFloat(224, sunLight * MOON_TINT[0]).putFloat(228, sunLight * MOON_TINT[1]).putFloat(232, sunLight * MOON_TINT[2]).putFloat(236, sunLight);
		long frameParams = upscale + UPSCALE_VECTORS * 16L;
		memPutLong(frameParams, radianceCacheBuffer.address);
		memPutFloat(frameParams + 16L, sunX);
		memPutFloat(frameParams + 20L, sunY);
		memPutFloat(frameParams + 24L, lightZ);
		memPutFloat(frameParams + 28L, sky.rainBrightness);
		memPutFloat(frameParams + 32L, moonX);
		memPutFloat(frameParams + 36L, moonY);
		memPutFloat(frameParams + 40L, lightZ);
		memPutFloat(frameParams + 44L, MOON_SKY);
		memPutFloat(frameParams + 48L, sunTint[0]);
		memPutFloat(frameParams + 52L, sunTint[1]);
		memPutFloat(frameParams + 56L, sunTint[2]);
		// Sky light where the camera stands, eased so walking into a cave doesn't pop. Deep underground the mist goes dark instead of glowing like the sky.
		float openness = Math.min(minecraft.level.getBrightness(LightLayer.SKY, minecraft.gameRenderer.mainCamera().blockPosition()) / FOG_FULL_SKY_LIGHT, 1.0F);
		fogSkyVisibility += (openness - fogSkyVisibility) * FOG_SKY_EASING;
		memPutFloat(frameParams + 60L, fogSkyVisibility);
		push.putLong(240, materialMap == null ? 0L : materialMap.address);
		push.putInt(248, materialWidth);
		push.putInt(252, materialHeight);

		bindSignal(cb, 0, stack);
		if (!radianceCacheCleared) {
			vkCmdFillBuffer(cb, radianceCacheBuffer.buffer, 0L, VK_WHOLE_SIZE, 0);
			barrier(cb, stack);
			radianceCacheCleared = true;
		}
		// Folds last frame's cache samples in and fills the sky tables, both of which tracing reads.
		dispatch(cb, tracePipeline, push, RESOLVE_ITERATION, CACHE_ROW, CACHE_CAPACITY / CACHE_ROW, stack);
		timestamp(cb, 2);
		dispatch(cb, tracePipeline, push, 0, stack);
		timestamp(cb, 3);
		if (globalIllumination && restirGi || restirDi) dispatch(cb, tracePipeline, push, SPATIAL_REUSE_ITERATION, stack);
		timestamp(cb, 4);
		for (int signal = 0; signal < SIGNALS; signal++) {
			if (signal == 1 && !globalIllumination || signal == 2 && !reflections || signal == 3 && !volumetrics) continue;
			// Ray Reconstruction takes the noisy image, so only light shafts, which are added after it, still use the custom denoiser. Feeding them through it dims them and loses the sun's disc.
			if (rayReconstruction && signal != 3) continue;
			bindSignal(cb, signal, stack);
			int width = signal == SHAFT_SIGNAL ? (renderWidth + 1) / 2 : renderWidth, height = signal == SHAFT_SIGNAL ? (renderHeight + 1) / 2 : renderHeight;
			dispatch(cb, temporalPipeline, push, signal, width, height, stack);
			for (int i = 0; i < GlowSettings.denoisePasses(signal); i++) dispatch(cb, atrousPipeline, push, i, width, height, stack);
		}
		timestamp(cb, 5);
		bindSignal(cb, 0, stack);
		boolean dlss = rayReconstruction || superResolution;
		boolean reset = !historyValid || dlss != dlssLastFrame || rayReconstruction != rayReconstructionLastFrame || frameDlssMode != dlssModeLastFrame;
		boolean constants = GlowNvidia.active() && setStreamlineConstants(camera, inverse, (float) (pos.x - previousX), (float) (pos.y - previousY), (float) (pos.z - previousZ), range, pos, reset);
		// DLSS can't run without this frame's constants. Glow's own finish only covers a frame traced at full size.
		if (dlss && !constants && renderWidth == outWidth && renderHeight == outHeight) dlss = false;
		if (dlss) {
			dispatch(cb, compositePipeline, push, rayReconstruction ? RR_ASSEMBLE_ITERATION : SR_ASSEMBLE_ITERATION, stack);
			if (DLSS_PROBE.equals("zeromv")) {
				barrier(cb, stack);
				vkCmdClearColorImage(cb, images[SCREEN_MOTION_IMAGE], VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack),
					VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
				barrier(cb, stack);
			}
			evaluateDlss(cb, rayReconstruction);
			barrier(cb, stack);
		}
		rayReconstructionLastFrame = rayReconstruction;
		dlssLastFrame = dlss;
		dlssModeLastFrame = frameDlssMode;
		timestamp(cb, 6);
		bindSignal(cb, 0, stack);
		if (dlss) dispatch(cb, compositePipeline, push, rayReconstruction ? RR_FINISH_ITERATION : SR_FINISH_ITERATION, outWidth, outHeight, stack);
		else dispatch(cb, compositePipeline, push, 0, stack);
		// Depth and motion are tagged for Frame Generation later, once the hand has drawn and been written into them.
		if (constants) GlowNvidia.updateFrameGeneration(renderWidth, renderHeight, outWidth, outHeight, VulkanConst.toVk(color.getFormat()), VK_FORMAT_R32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT);
		tracedThisFrame = constants;
		memCopy(memAddress(push), memAddress(LAST_PUSH), PUSH_SIZE);

		VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
		copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		copy.extent().set(outWidth, outHeight, 1);
		vkCmdCopyImage(cb, outImage, VK_IMAGE_LAYOUT_GENERAL, color.vkImage(), VK_IMAGE_LAYOUT_GENERAL, copy);
		barrier(cb, stack);
		timestamp(cb, 7);

		previousViewProj.set(viewProj);
		previousX = pos.x;
		previousY = pos.y;
		previousZ = pos.z;
	}

	/** Picks this frame's DLSS feature, mode, render size and jitter. Runs once per frame, when the world's projection is made. */
	private static void planFrame(int outWidth, int outHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean traces = enabled && instance != null && minecraft.level != null && minecraft.level.dimensionType().hasSkyLight();
		int setting = GlowSettings.dlssMode(outHeight);
		boolean rayReconstruction = traces && GlowSettings.rayReconstruction();
		int mode = rayReconstruction ? Math.max(setting - 1, 0) : traces && setting > 0 && GlowNvidia.superResolutionAvailable() ? setting - 1 : -1;
		frameRayReconstruction = rayReconstruction;
		frameDlssMode = mode;
		planOutWidth = outWidth;
		planOutHeight = outHeight;
		planRenderWidth = outWidth;
		planRenderHeight = outHeight;
		renderJitterX = renderJitterY = rasterJitterX = rasterJitterY = 0.0F;
		if (mode < 0) return;
		if (mode > 0) {
			int[] size = GlowNvidia.optimalRenderSize(rayReconstruction, mode, outWidth, outHeight);
			planRenderWidth = size[0];
			planRenderHeight = size[1];
		}
		float ratioX = (float) outWidth / planRenderWidth, ratioY = (float) outHeight / planRenderHeight;
		// DLSS wants at least 8 jitter phases per output pixel each render pixel covers.
		int phases = Math.max(MIN_JITTER_PHASES, (int) Math.ceil(8.0F * ratioX * ratioY));
		jitterPhase = jitterPhase % phases + 1;
		renderJitterX = halton(jitterPhase, 2) - 0.5F;
		renderJitterY = halton(jitterPhase, 3) - 0.5F;
		// Render pixel 0 samples (0.5 + jitter) * ratio - 0.5 output pixels in. Shifting the world by the rest of a pixel puts that exactly on a raster pixel,
		// and every render pixel after it too when the ratio is whole. Otherwise the others land within half an output pixel.
		float firstX = (0.5F + renderJitterX) * ratioX - 0.5F, firstY = (0.5F + renderJitterY) * ratioY - 0.5F;
		rasterJitterX = Math.round(firstX) - firstX;
		rasterJitterY = Math.round(firstY) - firstY;
		if (DLSS_PROBE.equals("1") || DLSS_PROBE.equals("nojitter")) renderJitterX = renderJitterY = rasterJitterX = rasterJitterY = 0.0F;
	}

	/** Offsets the world's projection a fraction of a pixel each frame, so DLSS can antialias. Glow's own passes keep the unjittered matrix. */
	public static Matrix4f jitteredProjection(Matrix4f projection) {
		tracedThisFrame = false;
		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		planFrame(target.width, target.height);
		if (frameDlssMode < 0) return projection;
		return new Matrix4f().translation(2.0F * rasterJitterX / target.width, 2.0F * rasterJitterY / target.height, 0.0F).mul(projection);
	}

	private static float halton(int index, int base) {
		float fraction = 1.0F, result = 0.0F;
		while (index > 0) {
			fraction /= base;
			result += fraction * (index % base);
			index /= base;
		}
		return result;
	}

	private static long[] concat(long[]... parts) {
		int length = 0;
		for (long[] part : parts) length += part.length;
		long[] result = new long[length];
		int at = 0;
		for (long[] part : parts) {
			System.arraycopy(part, 0, result, at, part.length);
			at += part.length;
		}
		return result;
	}

	/** One image as the bridge describes it. Every image but DLSS's output is the render size. */
	private long[] imageEntry(int index, int format) {
		boolean full = index == RR_OUTPUT_IMAGE;
		return new long[] {images[index], imageViews[index], format, RR_COLOR_USAGE, full ? outWidth : renderWidth, full ? outHeight : renderHeight};
	}

	/** Hands Streamline this frame's camera. Every NVIDIA feature reads it, Frame Generation included. */
	private boolean setStreamlineConstants(CameraRenderState camera, Matrix4f inverse, float deltaX, float deltaY, float deltaZ, float range, Vec3 pos, boolean reset) {
		// JOML's column-major arrays are exactly Streamline's row-major layout, because Streamline multiplies vectors from the left.
		float[] constants = new float[GlowNvidia.CONSTANT_FLOATS];
		PROJECTION.get(constants, 0);
		new Matrix4f(PROJECTION).invert().get(constants, 16);
		Matrix4f toPrevious = new Matrix4f(previousViewProj).translate(deltaX, deltaY, deltaZ).mul(inverse);
		toPrevious.get(constants, 32);
		new Matrix4f(toPrevious).invert().get(constants, 48);
		Matrix4f viewToWorld = new Matrix4f(camera.viewRotationMatrix).invert();
		Vector3f up = viewToWorld.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
		Vector3f right = viewToWorld.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
		Vector3f forward = viewToWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F));
		float[] rest = {(float) wrap(pos.x), (float) pos.y, (float) wrap(pos.z), up.x, up.y, up.z, right.x, right.y, right.z, forward.x, forward.y, forward.z,
			CAMERA_NEAR, range, 2.0F * (float) Math.atan(1.0F / PROJECTION.m11()), Math.abs(PROJECTION.m11() / PROJECTION.m00()),
			// DLSS takes the offset back to the pixel centre, the negative of where the sample sits, as Radiance's renderer passes it.
			// Measured on the sharp test: DLAA keeps about 15% more detail this way than with the sample's own offset.
			DLSS_JSIGN.charAt(0) == '-' ? renderJitterX : -renderJitterX, DLSS_JSIGN.charAt(1) == '-' ? renderJitterY : -renderJitterY};
		System.arraycopy(rest, 0, constants, 64, rest.length);
		// Positions are camera-relative, so world to view is the rotation alone. Ray Reconstruction uses it for normals and hit distances.
		new Matrix4f(camera.viewRotationMatrix).get(constants, 82);
		viewToWorld.get(constants, 98);
		return GlowNvidia.setConstants(constants, reset || DLSS_PROBE.equals("1"));
	}

	private void evaluateDlss(VkCommandBuffer cb, boolean rayReconstruction) {
		int color = VK_FORMAT_R16G16B16A16_SFLOAT;
		// Ray Reconstruction gets linear depth, as Radiance's renderer gives it; Super Resolution keeps the raster depth.
		long[] table = concat(imageEntry(RR_INPUT_IMAGE, color), imageEntry(RR_OUTPUT_IMAGE, color),
			rayReconstruction ? imageEntry(LINEAR_DEPTH_IMAGE, VK_FORMAT_R32_SFLOAT) : imageEntry(RENDER_DEPTH_IMAGE, VK_FORMAT_R32_SFLOAT), imageEntry(SCREEN_MOTION_IMAGE, color));
		if (rayReconstruction) {
			table = concat(table, imageEntry(DIFFUSE_GUIDE_IMAGE, color), imageEntry(SPECULAR_GUIDE_IMAGE, color), imageEntry(SHADING_NORMAL_IMAGE, color),
				imageEntry(SPECULAR_MOTION_IMAGE, color), imageEntry(SPECULAR_HIT_IMAGE, VK_FORMAT_R32_SFLOAT));
		}
		GlowNvidia.evaluate(rayReconstruction, frameDlssMode, cb.address(), table, renderWidth, renderHeight, outWidth, outHeight);
	}

	/** Whether the hand drew into the main depth buffer this frame. With post effects on, vanilla draws it into a target of its own. */
	public static boolean handInMainDepth = true;

	/**
	 * Hands Frame Generation this frame's depth, motion and finished world, once the world and hand are done and before the interface draws.
	 * A frame Glow didn't trace has no camera or motion for it, so generation pauses until tracing resumes.
	 */
	public static void tagFrameGeneration() {
		if (!GlowNvidia.frameGenerationActive()) return;
		if (instance == null || !tracedThisFrame) {
			GlowNvidia.pauseFrameGeneration();
			return;
		}
		instance.tagForFrameGeneration();
	}

	private void tagForFrameGeneration() {
		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		VulkanGpuTexture color = ((VulkanGpuTextureView) target.getColorTextureView()).texture();
		VulkanGpuTextureView view = (VulkanGpuTextureView) target.getColorTextureView();
		VulkanCommandEncoder encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
		VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();
		try (MemoryStack stack = stackPush()) {
			if (handInMainDepth) {
				ByteBuffer push = stack.malloc(PUSH_SIZE);
				memCopy(memAddress(LAST_PUSH), memAddress(push), PUSH_SIZE);
				barrier(cb, stack);
				bindSignal(cb, 0, stack);
				dispatch(cb, compositePipeline, push, HAND_ITERATION, stack);
			}
		}
		GlowNvidia.tagScene(cb.address(), concat(imageEntry(RENDER_DEPTH_IMAGE, VK_FORMAT_R32_SFLOAT), imageEntry(SCREEN_MOTION_IMAGE, VK_FORMAT_R16G16B16A16_SFLOAT)), renderWidth, renderHeight);
		int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		GlowNvidia.tagHudless(cb.address(), new long[] {color.vkImage(), view.vkImageView(), VulkanConst.toVk(color.getFormat()), usage, color.getWidth(0), color.getHeight(0)});
		check(vkEndCommandBuffer(cb), "end command buffer");
		encoder.execute(cb);
	}

	private static void barrier(VkCommandBuffer cb, MemoryStack stack) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
			.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException("Glow failed to create " + what + ": VkResult " + result);
	}
}
