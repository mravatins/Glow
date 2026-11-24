package dev.glow.mixin;

import dev.glow.GlowRenderer;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class FeatureGroupMixin {
	@Shadow
	@Final
	private List<StagedVertexBuffer.Draw> draws;
	@Shadow
	@Final
	private List<PreparedRenderType> drawRenderTypes;

	@Inject(method = "getOrAddDraw", at = @At("RETURN"))
	private void glow$tagDraw(RenderType renderType, CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
		StagedVertexBuffer.Draw draw = cir.getReturnValue();
		GlowRenderer.tagDraw(renderType, draw, this.drawRenderTypes.get(this.draws.indexOf(draw)));
	}
}
