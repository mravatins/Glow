package dev.glow.mixin;

import dev.glow.GlowLightmap;
import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	private static final String PREPARE_FRAME = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;";

	@Inject(method = "render", at = @At(value = "INVOKE", target = PREPARE_FRAME))
	private void glow$beginCapture(CallbackInfo ci) {
		GlowRenderer.beginFeatureCapture(this.levelRenderState);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = PREPARE_FRAME, shift = At.Shift.AFTER))
	private void glow$endCapture(CallbackInfo ci) {
		GlowRenderer.endFeatureCapture();
	}

	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void glow$renderShadows(FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo ci) {
		GlowRenderer.render(this.levelRenderState);
		// Particles and weather draw after this point and aren't traced, so they keep vanilla's light, and so does the hand.
		GlowLightmap.restoreVanilla();
	}
}
