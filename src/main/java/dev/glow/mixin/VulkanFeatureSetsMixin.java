package dev.glow.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	@ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
	private static Set<FeatureSet> glow$addRayQuery(Set<FeatureSet> sets) {
		VulkanPNextStruct as = new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
		VulkanPNextStruct rq = new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class);
		sets.add(new FeatureSet(
			"Glow Ray Query",
			Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations"),
			Set.of(
				new VulkanFeature(as, "accelerationStructure"),
				new VulkanFeature(rq, "rayQuery"),
				new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"),
				new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing"),
				new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64")
			)
		));
		sets.add(new FeatureSet(
			"Glow Opacity Micromap",
			Set.of("VK_EXT_opacity_micromap"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceOpacityMicromapFeaturesEXT.class), "micromap"))
		));
		sets.add(new FeatureSet(
			"Glow Ray Tracing Pipeline",
			Set.of("VK_KHR_ray_tracing_pipeline"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class), "rayTracingPipeline"))
		));
		sets.add(new FeatureSet(
			"Glow Invocation Reorder",
			Set.of("VK_EXT_ray_tracing_invocation_reorder"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.class), "rayTracingInvocationReorder"))
		));
		sets.add(new FeatureSet(
			"Glow Invocation Reorder NV",
			Set.of("VK_NV_ray_tracing_invocation_reorder"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.class), "rayTracingInvocationReorder"))
		));
		return sets;
	}
}
