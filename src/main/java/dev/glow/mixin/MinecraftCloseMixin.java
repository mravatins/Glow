package dev.glow.mixin;

import dev.glow.GlowNvidia;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftCloseMixin {
	@Inject(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;shutdownRenderer()V"))
	private void glow$stopStreamline(CallbackInfo ci) {
		GlowNvidia.shutdown();
	}
}
