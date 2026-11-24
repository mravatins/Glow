// Flat C bridge from Java to NVIDIA Streamline, whose API is C++ structs. Built by native/build.bat.
#include <windows.h>
#include <string>

#include "sl.h"
#include "sl_dlss.h"
#include "sl_dlss_d.h"
#include "sl_dlss_g.h"
#include "sl_pcl.h"
#include "sl_reflex.h"
#include "sl_security.h"

namespace {
const sl::Feature FEATURES[] = {sl::kFeatureDLSS, sl::kFeatureDLSS_RR, sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL};
// Identifies Glow to NVIDIA's driver. Keep it stable across versions.
const char* const PROJECT_ID = "6f1c2a94-3d7e-4b58-9a10-c7e4d2b86f35";
const int ERROR_NOT_LOADED = -1;
const int ERROR_BAD_SIGNATURE = -2;
const int ERROR_NO_ENTRY_POINT = -3;
const int ERROR_NO_FRAME = -4;
const uint32_t VK_LAYOUT_GENERAL = 1;

HMODULE interposer = nullptr;
// One token per frame, shared by every feature, so Reflex, PC Latency, DLSS and Frame Generation agree on which frame is which.
sl::FrameToken* currentFrame = nullptr;
sl::Constants frameConstants{};
// World to view and view to world, for Ray Reconstruction's normals and hit distances.
sl::float4x4 worldToView, viewToWorld;
bool constantsValid = false;

std::wstring wide(const char* text) {
	int length = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
	std::wstring result(length, L'\0');
	MultiByteToWideChar(CP_UTF8, 0, text, -1, result.data(), length);
	result.resize(length - 1);
	return result;
}

template <typename F>
F* entry(const char* name) {
	return reinterpret_cast<F*>(GetProcAddress(interposer, name));
}

template <typename F>
F* featureFunction(sl::Feature feature, const char* name) {
	static auto getFeatureFunction = entry<PFun_slGetFeatureFunction>("slGetFeatureFunction");
	if (getFeatureFunction == nullptr) return nullptr;
	void* function = nullptr;
	if (getFeatureFunction(feature, name, function) != sl::Result::eOk) return nullptr;
	return reinterpret_cast<F*>(function);
}

// Caches a feature function once Streamline can hand it out. Before the device exists it can't, so a miss is retried next time.
template <typename F>
F* cached(F*& slot, sl::Feature feature, const char* name) {
	if (slot == nullptr) slot = featureFunction<F>(feature, name);
	return slot;
}

void copyMatrix(sl::float4x4& target, const float* source) {
	memcpy(&target, source, sizeof(float) * 16);
}

const float IDENTITY[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

// Each image is six words in the table: VkImage, VkImageView, VkFormat, VkImageUsageFlags, width, height.
sl::Resource describe(const uint64_t* word) {
	sl::Resource resource(sl::ResourceType::eTex2d, reinterpret_cast<void*>(word[0]), nullptr, reinterpret_cast<void*>(word[1]), VK_LAYOUT_GENERAL);
	resource.nativeFormat = static_cast<uint32_t>(word[2]);
	resource.usage = static_cast<uint32_t>(word[3]);
	resource.width = static_cast<uint32_t>(word[4]);
	resource.height = static_cast<uint32_t>(word[5]);
	resource.mipLevels = 1;
	resource.arrayLayers = 1;
	resource.flags = 0;
	return resource;
}

const int IMAGE_WORDS = 6;

int tag(const uint64_t* images, const sl::BufferType* types, const sl::Extent* extents, int count, sl::ResourceLifecycle lifecycle, void* commandBuffer) {
	static auto setTags = entry<PFun_slSetTagForFrame>("slSetTagForFrame");
	if (setTags == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::Resource resources[9];
	sl::ResourceTag tags[9];
	for (int i = 0; i < count; i++) {
		resources[i] = describe(images + i * IMAGE_WORDS);
		tags[i] = sl::ResourceTag(&resources[i], types[i], lifecycle, &extents[i]);
	}
	sl::ViewportHandle viewport(0);
	return static_cast<int>(setTags(*currentFrame, viewport, tags, count, reinterpret_cast<sl::CommandBuffer*>(commandBuffer)));
}

sl::DLSSMode dlssMode(int mode) {
	switch (mode) {
	case 1: return sl::DLSSMode::eMaxQuality;
	case 2: return sl::DLSSMode::eBalanced;
	case 3: return sl::DLSSMode::eMaxPerformance;
	case 4: return sl::DLSSMode::eUltraPerformance;
	default: return sl::DLSSMode::eDLAA;
	}
}
}

// Must run before any Vulkan call. Returns sl::Result, where 0 is success, or a negative ERROR_ value.
extern "C" __declspec(dllexport) int glow_sl_init(const char* pluginDir, const char* logDir) {
	std::wstring plugins = wide(pluginDir), logs = wide(logDir);
	std::wstring path = plugins + L"\\sl.interposer.dll";
	if (!sl::security::verifyEmbeddedSignature(path.c_str())) return ERROR_BAD_SIGNATURE;
	interposer = LoadLibraryExW(path.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	auto init = entry<PFun_slInit>("slInit");
	if (init == nullptr) return ERROR_NO_ENTRY_POINT;

	const wchar_t* paths[] = {plugins.c_str()};
	sl::Preferences preferences{};
	preferences.pathsToPlugins = paths;
	preferences.numPathsToPlugins = 1;
	preferences.pathToLogsAndData = logs.c_str();
	preferences.logLevel = sl::LogLevel::eDefault;
	// Over-the-air updates are off, so the signed DLLs we ship are exactly what runs.
	preferences.flags = sl::PreferenceFlags::eDisableCLStateTracking | sl::PreferenceFlags::eUseFrameBasedResourceTagging;
	preferences.featuresToLoad = FEATURES;
	preferences.numFeaturesToLoad = static_cast<uint32_t>(sizeof(FEATURES) / sizeof(FEATURES[0]));
	preferences.engine = sl::EngineType::eCustom;
	preferences.engineVersion = "1.0";
	preferences.projectId = PROJECT_ID;
	preferences.renderAPI = sl::RenderAPI::eVulkan;
	return static_cast<int>(init(preferences, sl::kSDKVersion));
}

// Must run before the Vulkan device is destroyed. Returns sl::Result, where 0 is success.
extern "C" __declspec(dllexport) int glow_sl_shutdown() {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	auto shutdown = entry<PFun_slShutdown>("slShutdown");
	if (shutdown == nullptr) return ERROR_NO_ENTRY_POINT;
	currentFrame = nullptr;
	return static_cast<int>(shutdown());
}

// Returns sl::Result, where 0 means this GPU, driver and OS can use the feature.
extern "C" __declspec(dllexport) int glow_sl_feature_supported(unsigned int feature, void* physicalDevice) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	auto supported = entry<PFun_slIsFeatureSupported>("slIsFeatureSupported");
	if (supported == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::AdapterInfo adapter{};
	adapter.vkPhysicalDevice = physicalDevice;
	return static_cast<int>(supported(feature, adapter));
}

// Starts a frame: takes a new frame token, then lets Reflex sleep if it's on. Call once at the very start of each frame, before input is read.
extern "C" __declspec(dllexport) int glow_sl_begin_frame(int reflex) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	static auto newFrameToken = entry<PFun_slGetNewFrameToken>("slGetNewFrameToken");
	if (newFrameToken == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::Result result = newFrameToken(currentFrame, nullptr);
	if (result != sl::Result::eOk) return static_cast<int>(result);
	constantsValid = false;
	if (reflex == 0) return 0;
	static PFun_slReflexSleep* sleep = nullptr;
	if (cached(sleep, sl::kFeatureReflex, "slReflexSleep") == nullptr) return ERROR_NO_ENTRY_POINT;
	return static_cast<int>(sleep(*currentFrame));
}

// Marks a point in the frame for PC Latency stats and for Reflex, as a sl::PCLMarker value.
extern "C" __declspec(dllexport) int glow_sl_marker(int marker) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	if (currentFrame == nullptr) return ERROR_NO_FRAME;
	static PFun_slPCLSetMarker* setMarker = nullptr;
	if (cached(setMarker, sl::kFeaturePCL, "slPCLSetMarker") == nullptr) return ERROR_NO_ENTRY_POINT;
	return static_cast<int>(setMarker(static_cast<sl::PCLMarker>(marker), *currentFrame));
}

// Mode 0 off, 1 low latency, 2 low latency with boost. frameLimitUs 0 means no limit.
extern "C" __declspec(dllexport) int glow_reflex_set_options(int mode, unsigned int frameLimitUs) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	static PFun_slReflexSetOptions* setOptions = nullptr;
	if (cached(setOptions, sl::kFeatureReflex, "slReflexSetOptions") == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::ReflexOptions options{};
	options.mode = mode == 2 ? sl::ReflexMode::eLowLatencyWithBoost : mode == 1 ? sl::ReflexMode::eLowLatency : sl::ReflexMode::eOff;
	options.frameLimitUs = frameLimitUs;
	options.useMarkersToOptimize = true;
	return static_cast<int>(setOptions(options));
}

// Writes the render size DLSS wants for an output size into size[0..1], and the smallest and largest it accepts into size[2..5].
// rayReconstruction picks DLSS Ray Reconstruction over Super Resolution. Mode 0 DLAA, 1 Quality, 2 Balanced, 3 Performance, 4 Ultra Performance.
extern "C" __declspec(dllexport) int glow_dlss_optimal(int rayReconstruction, int mode, uint32_t outputWidth, uint32_t outputHeight, uint32_t* size) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	sl::Result result;
	if (rayReconstruction != 0) {
		static PFun_slDLSSDGetOptimalSettings* optimal = nullptr;
		if (cached(optimal, sl::kFeatureDLSS_RR, "slDLSSDGetOptimalSettings") == nullptr) return ERROR_NO_ENTRY_POINT;
		sl::DLSSDOptions options{};
		options.mode = dlssMode(mode);
		options.outputWidth = outputWidth;
		options.outputHeight = outputHeight;
		sl::DLSSDOptimalSettings settings{};
		result = optimal(options, settings);
		size[0] = settings.optimalRenderWidth, size[1] = settings.optimalRenderHeight;
		size[2] = settings.renderWidthMin, size[3] = settings.renderHeightMin, size[4] = settings.renderWidthMax, size[5] = settings.renderHeightMax;
	} else {
		static PFun_slDLSSGetOptimalSettings* optimal = nullptr;
		if (cached(optimal, sl::kFeatureDLSS, "slDLSSGetOptimalSettings") == nullptr) return ERROR_NO_ENTRY_POINT;
		sl::DLSSOptions options{};
		options.mode = dlssMode(mode);
		options.outputWidth = outputWidth;
		options.outputHeight = outputHeight;
		sl::DLSSOptimalSettings settings{};
		result = optimal(options, settings);
		size[0] = settings.optimalRenderWidth, size[1] = settings.optimalRenderHeight;
		size[2] = settings.renderWidthMin, size[3] = settings.renderHeightMin, size[4] = settings.renderWidthMax, size[5] = settings.renderHeightMax;
	}
	return static_cast<int>(result);
}

// Sets this frame's camera for every feature. constants holds view to clip, clip to view, clip to previous clip and previous clip to clip as 16 floats each,
// then camera position, up, right and forward, then near, far, vertical FOV, aspect, jitter x and y in render pixels, then world to view and view to world.
extern "C" __declspec(dllexport) int glow_sl_set_constants(const float* constants, int reset) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	if (currentFrame == nullptr) return ERROR_NO_FRAME;
	static auto setConstants = entry<PFun_slSetConstants>("slSetConstants");
	if (setConstants == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::Constants& values = frameConstants;
	values = sl::Constants{};
	copyMatrix(values.cameraViewToClip, constants);
	copyMatrix(values.clipToCameraView, constants + 16);
	copyMatrix(values.clipToPrevClip, constants + 32);
	copyMatrix(values.prevClipToClip, constants + 48);
	copyMatrix(values.clipToLensClip, IDENTITY);
	values.cameraPos = {constants[64], constants[65], constants[66]};
	values.cameraUp = {constants[67], constants[68], constants[69]};
	values.cameraRight = {constants[70], constants[71], constants[72]};
	values.cameraFwd = {constants[73], constants[74], constants[75]};
	values.cameraNear = constants[76];
	values.cameraFar = constants[77];
	values.cameraFOV = constants[78];
	values.cameraAspectRatio = constants[79];
	values.jitterOffset = {constants[80], constants[81]};
	copyMatrix(worldToView, constants + 82);
	copyMatrix(viewToWorld, constants + 98);
	values.mvecScale = {1.0f, 1.0f}; // Motion is stored as a fraction of the screen.
	values.cameraPinholeOffset = {0.0f, 0.0f};
	values.depthInverted = sl::Boolean::eTrue;
	values.cameraMotionIncluded = sl::Boolean::eTrue;
	values.motionVectors3D = sl::Boolean::eFalse;
	values.motionVectorsDilated = sl::Boolean::eFalse;
	values.orthographicProjection = sl::Boolean::eFalse;
	values.reset = reset != 0 ? sl::Boolean::eTrue : sl::Boolean::eFalse;
	sl::ViewportHandle viewport(0);
	sl::Result result = setConstants(values, *currentFrame, viewport);
	constantsValid = result == sl::Result::eOk;
	return static_cast<int>(result);
}

// Upscales, and for Ray Reconstruction also denoises, one frame. Call glow_sl_set_constants first in the same frame.
// images holds colour input, output, depth, motion, then for Ray Reconstruction diffuse albedo, specular albedo, packed normal and roughness,
// specular motion and specular hit distance.
// The output is the output size, and every other image is the render size. Returns sl::Result, where 0 is success.
extern "C" __declspec(dllexport) int glow_dlss_evaluate(int rayReconstruction, int mode, void* commandBuffer, const uint64_t* images, uint32_t renderWidth, uint32_t renderHeight, uint32_t outputWidth, uint32_t outputHeight) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	if (currentFrame == nullptr || !constantsValid) return ERROR_NO_FRAME;
	static auto evaluate = entry<PFun_slEvaluateFeature>("slEvaluateFeature");
	if (evaluate == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::ViewportHandle viewport(0);
	sl::Result result;
	if (rayReconstruction != 0) {
		static PFun_slDLSSDSetOptions* setOptions = nullptr;
		if (cached(setOptions, sl::kFeatureDLSS_RR, "slDLSSDSetOptions") == nullptr) return ERROR_NO_ENTRY_POINT;
		sl::DLSSDOptions options{};
		options.mode = dlssMode(mode);
		options.outputWidth = outputWidth;
		options.outputHeight = outputHeight;
		options.colorBuffersHDR = sl::Boolean::eTrue;
		options.normalRoughnessMode = sl::DLSSDNormalRoughnessMode::ePacked;
		options.worldToCameraView = worldToView;
		options.cameraViewToWorld = viewToWorld;
		result = setOptions(viewport, options);
	} else {
		static PFun_slDLSSSetOptions* setOptions = nullptr;
		if (cached(setOptions, sl::kFeatureDLSS, "slDLSSSetOptions") == nullptr) return ERROR_NO_ENTRY_POINT;
		sl::DLSSOptions options{};
		options.mode = dlssMode(mode);
		options.outputWidth = outputWidth;
		options.outputHeight = outputHeight;
		// Super Resolution runs after Glow's own tone curve, so its colour is display referred.
		options.colorBuffersHDR = sl::Boolean::eFalse;
		result = setOptions(viewport, options);
	}
	if (result != sl::Result::eOk) return static_cast<int>(result);

	sl::BufferType types[] = {sl::kBufferTypeScalingInputColor, sl::kBufferTypeScalingOutputColor, sl::kBufferTypeDepth, sl::kBufferTypeMotionVectors, sl::kBufferTypeAlbedo, sl::kBufferTypeSpecularAlbedo, sl::kBufferTypeNormalRoughness,
		sl::kBufferTypeSpecularMotionVectors, sl::kBufferTypeSpecularHitDistance};
	const int count = rayReconstruction != 0 ? 9 : 4;
	// Ray Reconstruction is given linear depth.
	if (rayReconstruction != 0) types[2] = sl::kBufferTypeLinearDepth;
	sl::Extent render{0, 0, renderWidth, renderHeight}, output{0, 0, outputWidth, outputHeight};
	sl::Extent extents[9] = {render, output, render, render, render, render, render, render, render};
	int tagged = tag(images, types, extents, count, sl::ResourceLifecycle::eOnlyValidNow, commandBuffer);
	if (tagged != 0) return tagged;
	const sl::BaseStructure* inputs[] = {&viewport};
	return static_cast<int>(evaluate(rayReconstruction != 0 ? sl::kFeatureDLSS_RR : sl::kFeatureDLSS, *currentFrame, inputs, 1, reinterpret_cast<sl::CommandBuffer*>(commandBuffer)));
}

// Tags this frame's depth and motion for Frame Generation, which reads them at present. Both are the render size. Streamline copies them now.
extern "C" __declspec(dllexport) int glow_dlssg_tag_scene(void* commandBuffer, const uint64_t* images, uint32_t renderWidth, uint32_t renderHeight) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	if (currentFrame == nullptr) return ERROR_NO_FRAME;
	const sl::BufferType types[] = {sl::kBufferTypeDepth, sl::kBufferTypeMotionVectors};
	sl::Extent render{0, 0, renderWidth, renderHeight};
	sl::Extent extents[2] = {render, render};
	return tag(images, types, extents, 2, sl::ResourceLifecycle::eOnlyValidNow, commandBuffer);
}

// Tags the finished frame before the interface draws on it, so Frame Generation can keep the interface out of its interpolation.
extern "C" __declspec(dllexport) int glow_dlssg_tag_hudless(void* commandBuffer, const uint64_t* image) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	if (currentFrame == nullptr) return ERROR_NO_FRAME;
	const sl::BufferType types[] = {sl::kBufferTypeHUDLessColor};
	sl::Extent extents[1] = {{0, 0, static_cast<uint32_t>(image[4]), static_cast<uint32_t>(image[5])}};
	return tag(image, types, extents, 1, sl::ResourceLifecycle::eOnlyValidNow, commandBuffer);
}

// Turns Frame Generation on with framesToGenerate extra frames per rendered frame, or off with 0. Reflex must be on while it runs.
extern "C" __declspec(dllexport) int glow_dlssg_set_options(int framesToGenerate, uint32_t renderWidth, uint32_t renderHeight, uint32_t outputWidth, uint32_t outputHeight, uint32_t colorFormat, uint32_t depthFormat, uint32_t mvecFormat) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	static PFun_slDLSSGSetOptions* setOptions = nullptr;
	if (cached(setOptions, sl::kFeatureDLSS_G, "slDLSSGSetOptions") == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::DLSSGOptions options{};
	options.mode = framesToGenerate > 0 ? sl::DLSSGMode::eOn : sl::DLSSGMode::eOff;
	options.numFramesToGenerate = framesToGenerate > 0 ? static_cast<uint32_t>(framesToGenerate) : 1;
	options.flags = sl::DLSSGFlags::eRetainResourcesWhenOff;
	options.mvecDepthWidth = renderWidth;
	options.mvecDepthHeight = renderHeight;
	options.colorWidth = outputWidth;
	options.colorHeight = outputHeight;
	options.colorBufferFormat = colorFormat;
	options.hudLessBufferFormat = colorFormat;
	options.depthBufferFormat = depthFormat;
	options.mvecBufferFormat = mvecFormat;
	sl::ViewportHandle viewport(0);
	return static_cast<int>(setOptions(viewport, options));
}

// Fills state with Frame Generation's status bits, the frames actually presented last time, and the most extra frames this GPU can make.
extern "C" __declspec(dllexport) int glow_dlssg_state(uint32_t* state) {
	if (interposer == nullptr) return ERROR_NOT_LOADED;
	static PFun_slDLSSGGetState* getState = nullptr;
	if (cached(getState, sl::kFeatureDLSS_G, "slDLSSGGetState") == nullptr) return ERROR_NO_ENTRY_POINT;
	sl::DLSSGState result{};
	sl::ViewportHandle viewport(0);
	sl::Result code = getState(viewport, result, nullptr);
	state[0] = static_cast<uint32_t>(result.status);
	state[1] = result.numFramesActuallyPresented;
	state[2] = result.numFramesToGenerateMax;
	state[3] = result.minWidthOrHeight;
	return static_cast<int>(code);
}
