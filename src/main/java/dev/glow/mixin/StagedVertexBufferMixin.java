package dev.glow.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.glow.GlowRenderer;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMixin {
	@Inject(method = "uploadDrawsToBuffers", at = @At("HEAD"))
	private void glow$captureDraws(GpuDevice device, List<StagedVertexBuffer.Draw> draws, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int vertexSize, int indexSize, CallbackInfo ci) {
		GlowRenderer.captureDraws(draws);
	}
}
