#extension GL_EXT_ray_query : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#ifdef GLOW_RAYGEN
#define GLOW_PIXEL ivec2(gl_LaunchIDEXT.xy)
#else
layout(local_size_x = 8, local_size_y = 8) in;
#define GLOW_PIXEL ivec2(gl_GlobalInvocationID.xy)
#define REORDER(hint, bits)
#endif

layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
layout(set = 0, binding = 1) uniform sampler2D depthTex;
layout(set = 0, binding = 2) uniform sampler2D colorTex;
layout(set = 0, binding = 3) uniform sampler2D textures[64];
layout(set = 0, binding = 4, rgba8) uniform writeonly image2D outImage;
// Bindings 5 and 8 to 13 belong to the signal this descriptor set denoises: shadow, GI or reflection.
layout(set = 0, binding = 5, rgba16f) uniform image2D noisyImage;
layout(set = 0, binding = 6, rgba16f) uniform image2D gCurrent;
layout(set = 0, binding = 7, rgba16f) uniform image2D gPrevious;
layout(set = 0, binding = 8, rgba16f) uniform image2D historyCurrent;
layout(set = 0, binding = 9, rgba16f) uniform image2D historyPrevious;
layout(set = 0, binding = 10, rgba16f) uniform image2D momentsCurrent;
layout(set = 0, binding = 11, rgba16f) uniform image2D momentsPrevious;
layout(set = 0, binding = 12, rgba16f) uniform image2D filterA;
layout(set = 0, binding = 13, rgba16f) uniform image2D filterB;
layout(set = 0, binding = 14, rgba16f) uniform image2D shadowNoisy;
layout(set = 0, binding = 15, rgba16f) uniform image2D giNoisy;
layout(set = 0, binding = 16, rgba16f) uniform image2D reflectionNoisy;
layout(set = 0, binding = 17, rgba16f) uniform image2D albedoImage; // rgb: albedo, a: reflection amount.
layout(set = 0, binding = 18, rgba16f) uniform image2D shadowFiltered;
layout(set = 0, binding = 19, rgba16f) uniform image2D giFiltered;
layout(set = 0, binding = 20, rgba16f) uniform image2D reflectionFiltered;
layout(set = 0, binding = 21, rgba16f) uniform image2D volumetricNoisy;
layout(set = 0, binding = 22, rgba16f) uniform image2D volumetricFiltered;
layout(set = 0, binding = 23, rgba16f) uniform image2D detailImage; // x: sun change from normal maps, y: ambient occlusion, z: sun strength, w: emission.
layout(set = 0, binding = 24, rgba16f) uniform image2D parallaxImage; // rgb: how parallax changes the surface colour.
layout(set = 0, binding = 25, rgba16f) uniform image2D motionImage; // xyz: previous minus current vertex position, w: 1 moved, 0 static, -1 no history.
// GI reservoirs. A: sample position (or direction, for sky) as float bits, then octahedral normal 12 + 12 bits, M 7 bits, sky 1 bit. B: radiance as float bits, then W.
layout(set = 0, binding = 26, rgba32ui) uniform uimage2D reservoirACurrent;
layout(set = 0, binding = 27, rgba32ui) uniform uimage2D reservoirAPrevious;
layout(set = 0, binding = 28, rgba32ui) uniform uimage2D reservoirBCurrent;
layout(set = 0, binding = 29, rgba32ui) uniform uimage2D reservoirBPrevious;
layout(set = 0, binding = 30, rgba16f) uniform image2D shadingNormalImage; // xyz: the normal GI is gathered around, with normal maps applied. w: linear roughness.
layout(set = 0, binding = 31, rgba16f) uniform image2D rayReconstructionInput; // The noisy lit image, before exposure.
layout(set = 0, binding = 32, rgba16f) uniform image2D rayReconstructionOutput;
layout(set = 0, binding = 33, rgba16f) uniform image2D screenMotionImage; // xy: previous minus current position, as a fraction of the screen.
layout(set = 0, binding = 34, rgba16f) uniform image2D specularGuideImage; // rgb: how strongly the surface reflects, for Ray Reconstruction.
layout(set = 0, binding = 35, rgba16f) uniform image2D diffuseGuideImage; // rgb: the colour diffuse light is multiplied by, for Ray Reconstruction.
layout(set = 0, binding = 36, r32f) uniform image2D renderDepthImage; // The raster depth each render pixel sampled, for DLSS.
layout(set = 0, binding = 37, rgba16f) uniform image2D specularMotionImage; // xy: how the reflected or refracted image moved, as a fraction of the screen.
layout(set = 0, binding = 38, r32f) uniform image2D specularHitImage; // How far the specular ray went before it hit, 0 for no specular.
// ReSTIR DI reservoirs for block lights: the light's camera-relative position as float bits, then its packed value 11 bits, M 4 bits, foliage 1 bit, W as a half float.
layout(set = 0, binding = 39, rgba32ui) uniform uimage2D lightReservoirCurrent;
layout(set = 0, binding = 40, rgba32ui) uniform uimage2D lightReservoirPrevious;
layout(set = 0, binding = 41, r32f) uniform image2D linearDepthImage; // Distance along the view direction, for Ray Reconstruction.
layout(set = 0, binding = 42, rgba32f) uniform image2D accumulationImage; // Path Tracing: rgb is the running mean of the unfiltered light, a is how many frames it holds.

layout(push_constant) uniform Push {
	mat4 invViewProj;
	mat4 prevViewProj;
	vec4 cameraDelta; // xyz: current camera minus previous camera, w: history is valid.
	vec4 lightAndStrength;
	vec4 fade; // x, y: distance fade range, z: daylight amount, w: exposure.
	uint64_t table;
	uint64_t lights;
	int lightCount;
	int frameIndex;
	int iteration;
	int flags; // 2048: auto exposure. 1: global illumination, 2: reflections, 4: volumetrics, 8: labPBR, 16: parallax, 32: denoiser off, 64: ReSTIR GI, 128: reservoir history valid, 256: DLSS Ray Reconstruction, 512: ReSTIR DI, 1024: radiance cache. 32768: path tracing. 65536: accumulate a still view. 131072: restart that accumulation. 262144: distance fog.
	vec4 skyColor;
	vec4 sunColor;
	uint64_t materials;
	int materialWidth;
	int materialHeight;
} pc;

layout(buffer_reference, std430) readonly buffer Lights { vec4 l[]; };

// Tracing runs at DLSS's render size while the world is drawn at full size. Each render pixel takes the full size raster pixel nearest its jittered sample.
const int PARAMS_UPSCALE = 268; // PARAMS_QUALITY + GlowSettings.PARAM_VECTORS.
const int PARAMS_PATH = 267; // qI: GI bounces, reflection bounces, path firefly limit, most frames to accumulate.
vec2 upscaleRatio = vec2(1.0); // Output pixels per render pixel.
vec2 renderJitter = vec2(0.0); // Where render pixels sample, in render pixels from their centre.
vec2 rasterJitter = vec2(0.0); // How far the world's projection moved, in output pixels.

void loadUpscale() {
	Lights params = Lights(pc.lights);
	vec4 a = params.l[PARAMS_UPSCALE], b = params.l[PARAMS_UPSCALE + 1];
	upscaleRatio = a.xy;
	renderJitter = a.zw;
	rasterJitter = b.xy;
}

ivec2 rasterSize() {
	return textureSize(depthTex, 0);
}

// The raster pixel a render pixel samples. The ratio and jitters are chosen so this is exact when the ratio is whole.
ivec2 rasterPixel(ivec2 p) {
	vec2 x = (vec2(p) + 0.5 + renderJitter) * upscaleRatio - 0.5 + rasterJitter;
	return clamp(ivec2(floor(x + 0.5)), ivec2(0), rasterSize() - 1);
}

// Where a raster pixel's sample sits on the unjittered screen, from 0 to 1.
vec2 rasterUv(ivec2 k) {
	return (vec2(k) + 0.5 - rasterJitter) / vec2(rasterSize());
}

vec3 unprojectUv(vec2 uv, float depth) {
	vec4 h = pc.invViewProj * vec4(uv * 2.0 - 1.0, depth, 1.0);
	return h.xyz / h.w;
}

float luminance(vec3 c) {
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Per-frame values after the upscale vectors. Frame: the radiance cache's address as two words, then flags. Sun and moon: direction, then sky strength.
const int PARAMS_FRAME = PARAMS_UPSCALE + 2;
const int PARAMS_SUN = PARAMS_UPSCALE + 3;
const int PARAMS_MOON = PARAMS_UPSCALE + 4;
const int PARAMS_SUN_COLOR = PARAMS_UPSCALE + 5; // The sun's colour after the air it crosses, 1 at noon. w: how open to the sky the camera is, for distance fog.
layout(buffer_reference, std430) readonly buffer ParamWords { uvec4 w[]; };

// One buffer holds the radiance cache, then the sky tables. GlowRenderer.CACHE_CAPACITY must match.
const uint CACHE_CAPACITY = 1u << 21;
const uint CACHE_MASK = CACHE_CAPACITY - 1u;
const uint CACHE_ROW = 1024u; // The resolve pass runs as rows of this many threads.
const uint64_t CACHE_ACCUM_OFFSET = 4ul * uint64_t(CACHE_CAPACITY);
const uint64_t CACHE_RESOLVED_OFFSET = 36ul * uint64_t(CACHE_CAPACITY);
const uint64_t CACHE_AGE_OFFSET = 68ul * uint64_t(CACHE_CAPACITY);
const uint64_t CACHE_SUN_SUM_OFFSET = 72ul * uint64_t(CACHE_CAPACITY); // 5 words per cell: sun visibility rgb and count, then a disagreement flag.
const uint64_t CACHE_SUN_OFFSET = 92ul * uint64_t(CACHE_CAPACITY); // A vec4 per cell: mean sun visibility, then agreeing samples in a row.
const uint64_t SKY_LUT_OFFSET = 108ul * uint64_t(CACHE_CAPACITY);

uint64_t cacheBase() {
	uvec4 w = ParamWords(pc.lights).w[PARAMS_FRAME];
	return uint64_t(w.x) | uint64_t(w.y) << 32;
}

// Physical sky: single Rayleigh and Mie scattering with ozone, kept as a sky-view table per light, sun then moon.
// Width runs over the azimuth from the light, 0 to pi. Height runs over elevation, squeezed toward the horizon where the sky changes fastest.
const int SKY_LUT_WIDTH = 64;
const int SKY_LUT_HEIGHT = 64;
const float PI_HALF = 1.5707963;
// The table is in units of a light of strength 1. This scale makes noon's sky dome as bright, on average, as the flat sky it replaced.
const float SKY_LIGHT_SCALE = 22.0;
// Sky seen directly or in a mirror is brighter than the light it sheds on surfaces suggests, as with the old environment.
const float SKY_VIEW_GAIN = 2.0;
layout(buffer_reference, std430) buffer SkyLut { vec4 t[]; };

vec2 skyLutUv(vec3 dir, vec3 light) {
	float elevation = asin(clamp(dir.y, -1.0, 1.0));
	float v = 0.5 + 0.5 * sign(elevation) * sqrt(abs(elevation) / PI_HALF);
	vec2 a = dir.xz, b = light.xz;
	float la = length(a), lb = length(b);
	float cosine = la > 1.0e-4 && lb > 1.0e-4 ? dot(a, b) / (la * lb) : 1.0;
	return vec2(acos(clamp(cosine, -1.0, 1.0)) / 3.14159265, v);
}

vec3 skyLutFetch(SkyLut lut, int table, ivec2 texel) {
	texel = clamp(texel, ivec2(0), ivec2(SKY_LUT_WIDTH - 1, SKY_LUT_HEIGHT - 1));
	return lut.t[table * SKY_LUT_WIDTH * SKY_LUT_HEIGHT + texel.y * SKY_LUT_WIDTH + texel.x].rgb;
}

vec3 skyLutSample(int table, vec3 dir, vec3 light) {
	SkyLut lut = SkyLut(cacheBase() + SKY_LUT_OFFSET);
	vec2 x = skyLutUv(dir, light) * vec2(SKY_LUT_WIDTH, SKY_LUT_HEIGHT) - 0.5;
	ivec2 base = ivec2(floor(x));
	vec2 f = x - vec2(base);
	return mix(mix(skyLutFetch(lut, table, base), skyLutFetch(lut, table, base + ivec2(1, 0)), f.x),
		mix(skyLutFetch(lut, table, base + ivec2(0, 1)), skyLutFetch(lut, table, base + ivec2(1, 1)), f.x), f.y);
}

// Auto exposure, after the sky tables: a log-luminance meter the composite fills from every 8th pixel, and the adapted exposure the
// cache pass works out from it. The eye adapts only part of the way, so caves stay darker than daylight, just not black.
const uint64_t EXPOSURE_OFFSET = SKY_LUT_OFFSET + uint64_t(2 * SKY_LUT_WIDTH * SKY_LUT_HEIGHT) * 16ul;
layout(buffer_reference, std430) buffer ExposureState { uint sum; uint count; float adapted; float lastTime; };
const float EXPOSURE_METER_SCALE = 64.0;

// The exposure the image is finished with: the player's setting, times the adaptation when auto exposure is on.
float exposure() {
	if ((pc.flags & 2048) == 0) return pc.fade.w;
	float adapted = ExposureState(cacheBase() + EXPOSURE_OFFSET).adapted;
	return pc.fade.w * (adapted > 0.0 ? adapted : 1.0);
}

void meterExposure(ivec2 p, vec3 radiance) {
	if ((pc.flags & 2048) == 0 || ((p.x | p.y) & 7) != 0) return;
	float level = clamp(log2(max(luminance(radiance), 1.0e-5)), -16.0, 8.0);
	ExposureState state = ExposureState(cacheBase() + EXPOSURE_OFFSET);
	atomicAdd(state.sum, uint((level + 16.0) * EXPOSURE_METER_SCALE));
	atomicAdd(state.count, 1u);
}

// Scattered light from the atmosphere in a direction, in the units the lighting uses. Below the horizon it holds the horizon's colour, dimmed.
const float RAIN_SKY_LIGHT = 0.3; // Clouds cover the sun in full rain, but the sky keeps this share of its light.

vec3 atmosphere(vec3 dir) {
	Lights params = Lights(pc.lights);
	vec4 sun = params.l[PARAMS_SUN], moon = params.l[PARAMS_MOON];
	vec3 up = dir.y < 0.02 ? normalize(vec3(dir.x, 0.02, dir.z)) : dir;
	vec3 sky = (skyLutSample(0, up, sun.xyz) + skyLutSample(1, up, moon.xyz) * moon.w) * mix(RAIN_SKY_LIGHT, 1.0, sun.w) * SKY_LIGHT_SCALE;
	// Rain greys the sky over. Strength falls with rain, so the shortfall from 1 measures it.
	float overcast = clamp((1.0 - sun.w) * 2.0, 0.0, 1.0);
	return mix(sky, vec3(luminance(sky)), overcast);
}

// The colour of far air: the sky a little above the horizon in the same direction, so it stays blue by day and warm toward a low sun.
const float HAZE_ELEVATION = 0.14;
vec3 hazeColor(vec3 dir) {
	float across = length(dir.xz);
	vec3 flat_ = across > 1.0e-4 ? vec3(dir.x, 0.0, dir.z) / across : vec3(1.0, 0.0, 0.0);
	return atmosphere(normalize(flat_ + vec3(0.0, HAZE_ELEVATION, 0.0))) * SKY_VIEW_GAIN;
}

// The sky as seen: brighter than the light it sheds, plus the discs of the sun and moon and, at night, stars.
vec3 skyView(vec3 dir, bool discs) {
	// Toward the horizon and below it the sky settles on the haze colour, rather than climbing to a clipped white.
	vec3 sky = mix(hazeColor(dir), atmosphere(dir) * SKY_VIEW_GAIN, smoothstep(0.0, HAZE_ELEVATION, dir.y));
	if (!discs) return sky;
	Lights params = Lights(pc.lights);
	vec4 sun = params.l[PARAMS_SUN], moon = params.l[PARAMS_MOON];
	float toSun = dot(dir, sun.xyz), toMoon = dot(dir, moon.xyz);
	// A disc a little larger than the real sun, with a darkened limb, tinted by the air it shines through.
	float sunDisc = smoothstep(0.99965, 0.99975, toSun);
	if (sunDisc > 0.0) sky += params.l[PARAMS_SUN_COLOR].rgb * sun.w * sunDisc * 40.0 * (0.6 + 0.4 * sqrt(clamp((toSun - 0.99965) / 0.00035, 0.0, 1.0)));
	sky += vec3(0.85, 0.9, 1.0) * smoothstep(0.99975, 0.99982, toMoon) * 1.5 * step(0.0, moon.xyz.y + 0.05);
	// Stars: a hashed grid of directions, fading in as the sky darkens.
	float dark = clamp(1.0 - luminance(sky) * 20.0, 0.0, 1.0);
	if (dark > 0.0 && dir.y > 0.0) {
		vec3 cell = floor(dir * 180.0);
		uvec3 h = uvec3(ivec3(cell) + 4096);
		uint seed = h.x * 73856093u ^ h.y * 19349663u ^ h.z * 83492791u;
		seed = (seed ^ (seed >> 16)) * 2246822519u;
		seed ^= seed >> 13;
		float pick = float(seed & 0xFFFFu) / 65535.0;
		if (pick > 0.9965) {
			vec3 centre = (cell + 0.5) / 180.0;
			float twinkle = smoothstep(0.0032, 0.0, length(dir - normalize(centre) * length(dir)));
			sky += vec3(0.9, 0.95, 1.0) * twinkle * (pick - 0.9965) * 300.0 * dark * dir.y * sun.w;
		}
	}
	return sky;
}

// Enhanced water's medium, per block (a metre). Clear water takes its colour from pure water's own absorption, which eats red
// within a few metres and leaves blue-green, so shallows stay clear and depths turn blue. The biome colour only nudges the tint.
// Biome colours that aren't blue (swamps, mangroves) add murk: silt that absorbs and scatters.
const int PARAMS_WATER = 266; // qH: water level, wave octaves, wave height, camera water colour as 0xRRGGBB or -1 above water.
const vec3 WATER_PURE_ABSORPTION = vec3(0.34, 0.045, 0.014);
const float WATER_TINT = 0.04;
const vec3 WATER_SCATTERING = vec3(0.010, 0.014, 0.018);
const vec3 WATER_MURK_ABSORPTION = vec3(0.18, 0.14, 0.26);
const vec3 WATER_MURK_SCATTERING = vec3(0.09, 0.11, 0.06);
// Sky light enters the water from the whole dome above, not from straight up alone.
const float WATER_SKY_GAIN = 2.2;

vec3 waterExtinction(vec3 biome) {
	// Only the biome colour's hue matters. Its brightness varies with how the vertex was shaded.
	biome /= max(max(biome.r, max(biome.g, biome.b)), 0.01);
	float murk = clamp((0.96 - biome.b) * 5.0, 0.0, 1.0);
	return WATER_PURE_ABSORPTION - log(clamp(biome, vec3(0.05), vec3(1.0))) * WATER_TINT + WATER_SCATTERING
		+ murk * (WATER_MURK_ABSORPTION + WATER_MURK_SCATTERING);
}

// The scattering part of an extinction from waterExtinction. Murk shows in the blue channel, which clear water barely absorbs.
vec3 waterScattering(vec3 sigma) {
	float murk = clamp((sigma.b - 0.04) / (WATER_MURK_ABSORPTION.b + WATER_MURK_SCATTERING.b), 0.0, 1.0);
	return WATER_SCATTERING + murk * WATER_MURK_SCATTERING;
}

// True while the camera is under water with Enhanced Water on, with the extinction of the water around it.
bool cameraUnderwater(out vec3 sigma) {
	float packed = Lights(pc.lights).l[PARAMS_WATER].w;
	sigma = vec3(0.0);
	if (packed < 0.0) return false;
	uint rgb = uint(packed);
	sigma = waterExtinction(vec3(float(rgb >> 16 & 0xFFu), float(rgb >> 8 & 0xFFu), float(rgb & 0xFFu)) / 255.0);
	return true;
}

// Distance fog has two parts. Far air fades everything into the haze colour before the loaded world ends. Ground mist is a real layer lying
// over water and in valleys, ray marched along each view ray, so hills rise out of it and the camera can walk into it.
const int FOG_PARAMS_CAMERA = 256; // Camera x, y, z wrapped to the world's 3072 block repeat, then the time, which wraps at 3600 seconds.
const int FOG_PARAMS_CLOUDS = 257; // Cloud x offset, height above the camera, z offset, cloud map slot (below 0 without clouds).
const int FOG_PARAMS_CLOUD_RANGE = 258; // w: how far vanilla's clouds reach, in blocks.
const float FOG_CLOUD_THICKNESS = 4.0; // Vanilla's cloud boxes.
const float FOG_CLOUD_START = 0.25; // Where clouds start melting into the horizon, as a fraction of their reach.
const float FOG_AIR_THICKNESS = 0.3; // The far air's optical depth across the render distance.
const float FOG_CLEAR_RANGE = 0.2; // The fraction of the render distance around the camera with no far air.
const float FOG_EDGE_START = 0.6; // Where surfaces start fading out for good, as a fraction of where they finish.
const float FOG_EDGE_MARGIN = 16.0; // Blocks. Chunks stop up to about one chunk short of the render distance, depending on where the camera stands in its own.
const float MIST_BOTTOM = 56.0; // Just under sea level, so caves below stay clear.
const float MIST_TOP = 92.0; // The tallest the mist gets, where its noise peaks.
const float MIST_DENSITY = 0.04; // Extinction per block at its thickest.
const float MIST_NEAR_START = 4.0, MIST_NEAR_END = 28.0; // Blocks. Mist builds up from the camera, so standing in it doesn't blank the view.
const int MIST_STEPS = 8;
const int MIST_LIGHT_STEPS = 4; // Thin mist, as at the default density, hides too little for its steps to show; half as many save time.
const float MIST_LARGE_CELL = 48.0, MIST_SMALL_CELL = 12.0; // Blocks. Both divide 3072, so the noise repeats with the world's wrap.
const float MIST_DRIFT = 3072.0 / 3600.0; // Blocks per second, which repeats with both the wrap and the time.

float fogFarEnd() {
	return max(pc.fade.y - FOG_EDGE_MARGIN, pc.fade.y * 0.75);
}

// How much far air hides a surface. The edge term reaches 1 before the loaded world ends.
float fogAmount(vec3 dir, float dist) {
	float range = pc.fade.y;
	float across = length(dir.xz) * dist;
	// Clouds aren't part of the loaded world, so they keep their own reach and only melt into the horizon at the end of it.
	vec4 clouds = Lights(pc.lights).l[FOG_PARAMS_CLOUDS];
	float aboveClouds = dir.y * dist - clouds.y;
	if (clouds.w >= 0.0 && aboveClouds > -0.1 && aboveClouds < FOG_CLOUD_THICKNESS + 0.1) {
		float reach = Lights(pc.lights).l[FOG_PARAMS_CLOUD_RANGE].w;
		return smoothstep(reach * FOG_CLOUD_START, reach, across);
	}
	float end = fogFarEnd();
	float edge = smoothstep(end * FOG_EDGE_START, end, across);
	float optical = FOG_AIR_THICKNESS * pc.skyColor.w * max(across / range - FOG_CLEAR_RANGE, 0.0);
	return 1.0 - (1.0 - edge) * exp(-optical);
}

float mistCellHash(ivec2 cell) {
	uvec2 u = uvec2(cell);
	uint h = u.x * 1597334677u ^ u.y * 3812015801u;
	h = (h ^ (h >> 15)) * 2246822519u;
	return float(h >> 16) / 65535.0;
}

float mistNoise(vec2 at, float cellSize) {
	int period = int(3072.0 / cellSize);
	vec2 x = at / cellSize;
	ivec2 base = ivec2(floor(x));
	vec2 f = x - vec2(base);
	f = f * f * (3.0 - 2.0 * f);
	ivec2 a = base & (period - 1), b = (base + 1) & (period - 1);
	return mix(mix(mistCellHash(a), mistCellHash(ivec2(b.x, a.y)), f.x), mix(mistCellHash(ivec2(a.x, b.y)), mistCellHash(b), f.x), f.y);
}

// Light scattered by the mist: the sky's haze from around it, and sunlight, stronger looking toward the sun.
vec3 mistLight(vec3 dir) {
	vec3 sun = Lights(pc.lights).l[PARAMS_SUN].xyz;
	float toSun = max(dot(dir, sun), 0.0);
	return hazeColor(dir) * 0.6 + pc.sunColor.rgb * (0.3 + 0.5 * toSun * toSun * toSun * toSun);
}

// rgb: light the mist adds along the ray up to dist. a: how much of what's behind it gets through.
vec4 groundMist(vec3 dir, float dist) {
	float strength = pc.skyColor.w;
	vec4 camera = Lights(pc.lights).l[FOG_PARAMS_CAMERA];
	// Everything past the end of the far air is hidden, so the sky and a fully faded surface march the same stretch and match.
	float last = min(dist, fogFarEnd() / max(length(dir.xz), 1.0e-3));
	float first = 0.0;
	if (abs(dir.y) > 1.0e-4) {
		float toBottom = (MIST_BOTTOM - camera.y) / dir.y, toTop = (MIST_TOP - camera.y) / dir.y;
		first = max(min(toBottom, toTop), 0.0);
		last = min(last, max(toBottom, toTop));
	} else if (camera.y < MIST_BOTTOM || camera.y > MIST_TOP) return vec4(0.0, 0.0, 0.0, 1.0);
	if (strength <= 0.0 || last <= first) return vec4(0.0, 0.0, 0.0, 1.0);
	vec3 sun = Lights(pc.lights).l[PARAMS_SUN].xyz;
	// Mist burns off as the sun climbs, and hangs on longer under rain.
	float season = mix(1.0, 0.55, smoothstep(0.25, 0.8, sun.y));
	season = max(season, clamp((1.0 - Lights(pc.lights).l[PARAMS_SUN].w) * 2.0, 0.0, 1.0));
	vec2 drift = vec2(MIST_DRIFT * camera.w, 0.0);
	// A ray whose whole stretch couldn't dim the view by even 1% skips the march.
	if ((last - first) * MIST_DENSITY * strength * season < 0.01) return vec4(0.0, 0.0, 0.0, 1.0);
	int steps = strength < 0.5 ? MIST_LIGHT_STEPS : MIST_STEPS;
	float span = (last - first) / float(steps);
	float optical = 0.0;
	for (int i = 0; i < steps; i++) {
		float t = first + (float(i) + 0.5) * span;
		vec3 at = camera.xyz + dir * t;
		// The noise sets how tall the mist stands here, so its top rolls and thin patches let the ground show.
		float n = mistNoise(at.xz + drift, MIST_LARGE_CELL) * 0.7 + mistNoise(at.xz - drift, MIST_SMALL_CELL) * 0.3;
		float top = mix(MIST_BOTTOM + 8.0, MIST_TOP, n);
		float height = 1.0 - smoothstep(MIST_BOTTOM + 4.0, top, at.y);
		optical += height * height * smoothstep(0.3, 0.7, n) * smoothstep(MIST_NEAR_START, MIST_NEAR_END, t);
	}
	float kept = exp(-optical * span * MIST_DENSITY * strength * season);
	return vec4(mistLight(dir) * Lights(pc.lights).l[PARAMS_SUN_COLOR].w * (1.0 - kept), kept);
}

// A surface seen through far air and mist. The far air takes the sky's colour in that direction, so a fully faded surface matches the sky beside it. Caves stay dark.
vec3 mistOver(vec3 light, vec3 dir, float dist) {
	float amount = fogAmount(dir, dist);
	if (amount > 0.0) light = mix(light, skyView(dir, false) * Lights(pc.lights).l[PARAMS_SUN_COLOR].w, amount);
	vec4 mist = groundMist(dir, dist);
	return light * mist.a + mist.rgb;
}

// Light shafts: how strongly the air scatters sunlight toward the camera, by the angle between the view and the sun.
const int PARAMS_QUALITY = 259;
const float VOLUME_ANISOTROPY = 0.6;
const float VOLUME_DENSITY_SCALE = 0.004; // Per block, times Light Shaft Density.
float shaftPhase(float cosine) {
	float g = VOLUME_ANISOTROPY;
	return 0.05 + 0.125 * (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosine, 1.5);
}

// The sky already holds the glow sunlit air makes around the sun. Over the sky, the shaft pass's sunlit air would add that glow again, a white
// haze many times the sun's size, so there it only darkens: by the air it found in shadow, which is what shows as shafts against the sky.
// openAir is what the pass returns for a view with nothing shadowing it. A shadowed sky keeps at least this share of itself.
const float SHADOWED_SKY_FLOOR = 0.35;
vec3 skyWithShafts(vec3 sky, vec3 scattered, vec3 dir) {
	if ((pc.flags & 4) == 0 || pc.sunColor.a <= 0.0) return sky + scattered;
	Lights params = Lights(pc.lights);
	float reach = params.l[PARAMS_QUALITY + 2].w, density = VOLUME_DENSITY_SCALE * params.l[PARAMS_QUALITY + 3].y;
	vec3 openAir = pc.sunColor.rgb * shaftPhase(dot(dir, pc.lightAndStrength.xyz)) * (1.0 - exp(-density * reach));
	return max(sky + scattered - openAir, sky * SHADOWED_SKY_FLOOR);
}

// The mist in front of what the camera sees, while the camera itself is in open air.
vec3 applyFog(vec3 light, vec3 dir, float dist) {
	return (pc.flags & 262144) == 0 ? light : mistOver(light, dir, dist);
}

// The sky seen through the mist, for views from above or inside the layer.
vec3 applySkyMist(vec3 light, vec3 dir) {
	if ((pc.flags & 262144) == 0) return light;
	vec4 mist = groundMist(dir, 1.0e9);
	return light * mist.a + mist.rgb;
}
