package dev.glow.mixin;

import dev.glow.GlowVideoSettingsScreen;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Sends the Video Settings button to Glow's screen. */
@Mixin(OptionsScreen.class)
public class OptionsScreenMixin {
	@Shadow
	@Final
	private static Component VIDEO;

	@Shadow
	@Final
	private Options options;

	@ModifyVariable(method = "openScreenButton", at = @At("HEAD"), argsOnly = true)
	private Supplier<Screen> glow$videoScreen(Supplier<Screen> original, Component message) {
		if (message != VIDEO) return original;
		return () -> new GlowVideoSettingsScreen((Screen) (Object) this, Minecraft.getInstance(), this.options);
	}
}
