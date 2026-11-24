package dev.glow.mixin;

import dev.glow.GlowRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla bakes a fixed brightness per block face into its meshes, which ray traced sunlight and block lights replace. */
@Mixin(CardinalLighting.class)
public class CardinalLightingMixin {
	@Inject(method = "byFace", at = @At("HEAD"), cancellable = true)
	private void glow$flatFaces(Direction face, CallbackInfoReturnable<Float> result) {
		if (GlowRenderer.replacesVanillaLighting()) result.setReturnValue(1.0F);
	}
}
