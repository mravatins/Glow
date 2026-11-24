package dev.glow.mixin;

import com.mojang.blaze3d.platform.Monitor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VideoSettingsScreen.class)
public interface VideoSettingsScreenAccessor {
	@Accessor("header")
	LinearLayout glow$header();

	@Accessor("restartWarning")
	void glow$setRestartWarning(StringWidget widget);

	@Accessor("fullscreenModeMonitor")
	void glow$setFullscreenModeMonitor(Monitor monitor);
}
