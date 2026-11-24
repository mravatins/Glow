package dev.glow.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.vulkan.VK14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanInstance.class)
public class VulkanInstanceMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;")
	)
	private int glow$useVulkan14(int original) {
		return VK14.VK_API_VERSION_1_4;
	}
}
