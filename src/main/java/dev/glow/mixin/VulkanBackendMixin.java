package dev.glow.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {
	@ModifyArg(
		method = "createVma",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I")
	)
	private static VmaAllocatorCreateInfo glow$enableDeviceAddress(VmaAllocatorCreateInfo info) {
		return info.flags(info.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
	}
}
