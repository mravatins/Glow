package dev.glow.mixin;

import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes vanilla's corner darkening from block meshes, since traced GI provides real occlusion. */
@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")
public class BlockModelLighterCacheMixin {
	@Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
	private void glow$noCornerDarkening(BlockState state, BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Float> result) {
		if (GlowRenderer.replacesVanillaLighting()) result.setReturnValue(1.0F);
	}
}
