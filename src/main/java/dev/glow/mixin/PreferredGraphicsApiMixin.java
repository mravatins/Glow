package dev.glow.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
	/** Glow only works on Vulkan, so it is always tried first. */
	@ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
	private GpuBackend[] glow$preferVulkan(GpuBackend[] backends) {
		Arrays.sort(backends, Comparator.comparing(backend -> !(backend instanceof VulkanBackend)));
		return backends;
	}
}
