package dev.glow.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.glow.GlowAutotest;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void glow$lightHeldHand(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, CallbackInfo ci) {
		GlowRenderer.lightHeldHand(player);
	}

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void glow$handDepth(CameraRenderState camera, PlayerRenderState player, OptionsRenderState options, boolean consistentDepthRequired, CallbackInfo ci) {
		GlowRenderer.handInMainDepth = !consistentDepthRequired;
	}

	@ModifyArg(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
			ordinal = 0
		)
	)
	private Matrix4f glow$captureProjection(Matrix4f projection) {
		GlowRenderer.PROJECTION.set(projection);
		return GlowRenderer.jitteredProjection(projection);
	}

	/** The world, hand and post effects are done and the interface is next, so this is the frame Frame Generation needs without its interface. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void glow$tagHudless(CallbackInfo ci) {
		GlowRenderer.tagFrameGeneration();
		GlowAutotest.afterWorld();
	}
}
