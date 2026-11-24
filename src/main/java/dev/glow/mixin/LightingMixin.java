package dev.glow.mixin;

import com.mojang.blaze3d.platform.Lighting;
import dev.glow.GlowRenderer;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Vanilla shades entities with two fixed lights in its shaders, clamped to full brightness. Two huge opposite lights
 * saturate that clamp for almost any normal, which removes the shading without editing vanilla's shaders.
 */
@Mixin(Lighting.class)
public class LightingMixin {
	@Unique
	private static final Vector3fc GLOW$UP = new Vector3f(577.0F, 577.0F, 577.0F);
	@Unique
	private static final Vector3fc GLOW$DOWN = new Vector3f(-577.0F, -577.0F, -577.0F);

	@ModifyArgs(method = "updateLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Lighting;updateBuffer(Lcom/mojang/blaze3d/platform/Lighting$Entry;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;)V"))
	private void glow$flat(Args args) {
		if (!GlowRenderer.replacesVanillaLighting()) return;
		args.set(1, GLOW$UP);
		args.set(2, GLOW$DOWN);
	}
}
