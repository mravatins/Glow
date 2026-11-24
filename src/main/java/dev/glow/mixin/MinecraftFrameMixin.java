package dev.glow.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.glow.GlowNvidia;
import java.util.Collection;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frames the game loop for NVIDIA Reflex and PC Latency: a new frame and Reflex's sleep at the top, then markers around simulation, rendering and present. */
@Mixin(Minecraft.class)
public class MinecraftFrameMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void glow$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		GlowNvidia.beginFrame();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void glow$simulationEnd(boolean advanceGameTime, CallbackInfo ci) {
		GlowNvidia.marker(GlowNvidia.SIMULATION_END);
		GlowNvidia.marker(GlowNvidia.RENDER_SUBMIT_START);
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void glow$presentStart(boolean advanceGameTime, CallbackInfo ci) {
		GlowNvidia.marker(GlowNvidia.RENDER_SUBMIT_END);
		GlowNvidia.marker(GlowNvidia.PRESENT_START);
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void glow$presentEnd(boolean advanceGameTime, CallbackInfo ci) {
		GlowNvidia.marker(GlowNvidia.PRESENT_END);
	}

	/** Streamline hooks the Vulkan swapchain for Frame Generation, which a DXGI surface would bypass. */
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSurface(JLjava/util/function/BooleanSupplier;Z)Lcom/mojang/renderpearl/api/device/GpuSurface;"), index = 2)
	private boolean glow$vulkanSurface(boolean dxgi) {
		return dxgi && !GlowNvidia.active();
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface$PresentMode;getSupportedVsyncMode(Ljava/util/Collection;Z)Lcom/mojang/renderpearl/api/device/GpuSurface$PresentMode;"))
	private GpuSurface.PresentMode glow$noVsyncUnderFrameGeneration(Collection<GpuSurface.PresentMode> modes, boolean vsync, Operation<GpuSurface.PresentMode> original) {
		return original.call(modes, vsync && GlowNvidia.vsyncAllowed());
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
	private void glow$reflexLimitsFrameRate(int framerateLimit, Operation<Void> original) {
		if (!GlowNvidia.limitFrameRate(framerateLimit)) original.call(framerateLimit);
	}
}
