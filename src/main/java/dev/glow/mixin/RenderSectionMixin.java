package dev.glow.mixin;

import dev.glow.GlowRenderer;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin {
	@Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
	private void glow$capture(
		ChunkSectionLayer layer, CompiledSectionMesh key, ByteBuffer vertices, ByteBuffer indices, CallbackInfoReturnable<Boolean> cir
	) {
		GlowRenderer.capture((SectionRenderDispatcher.RenderSection)(Object)this, layer, key, vertices);
	}

	@Inject(method = "releaseSectionMesh", at = @At("HEAD"))
	private void glow$release(SectionMesh mesh, CallbackInfo ci) {
		GlowRenderer.release(mesh);
	}
}
