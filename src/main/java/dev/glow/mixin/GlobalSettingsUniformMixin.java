package dev.glow.mixin;

import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GlobalSettingsUniform.class)
public class GlobalSettingsUniformMixin {
	/** Bit 1 of UseRgss tells Glow's terrain.fsh that DLSS is on. The second int written is UseRgss; the first is the menu blur radius. */
	@ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putInt(I)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1))
	private int glow$markDlss(int useRgss) {
		return GlowRenderer.sharpenTerrainMips() ? useRgss | 2 : useRgss;
	}
}
