package dev.glow.mixin;

import dev.glow.GlowRenderer;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Mirrors and shadows show particles behind the camera too, so they have to reach the render state. The rasterizer still clips them. */
@Mixin(QuadParticleGroup.class)
public class QuadParticleGroupMixin {
	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean glow$keepForRays(Frustum frustum, double x, double y, double z) {
		return GlowRenderer.tracesEffects() || frustum.pointInFrustum(x, y, z);
	}
}
