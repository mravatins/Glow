package dev.glow.mixin;

import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherMixin {
	@Inject(method = "dispose", at = @At("HEAD"))
	private void glow$dispose(CallbackInfo ci) {
		GlowRenderer.releaseAll();
	}
}
