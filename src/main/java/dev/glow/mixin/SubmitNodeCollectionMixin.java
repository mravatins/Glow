package dev.glow.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.glow.GlowRenderer;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
	/** Entities cast real shadows, so the vanilla blob would be a second, fake one. */
	@Inject(method = "submitShadow", at = @At("HEAD"), cancellable = true)
	private void glow$replaceBlobShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces, CallbackInfo ci) {
		if (GlowRenderer.tracesEntities()) ci.cancel();
	}
}
