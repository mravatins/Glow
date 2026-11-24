package dev.glow.mixin;

import dev.glow.GlowAutotest;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void glow$autotest(CallbackInfo ci) {
		GlowAutotest.onTitleScreen((TitleScreen)(Object)this);
	}
}
