package dev.glow.mixin;

import dev.glow.GlowSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {
	/** Under enhanced water the shaders absorb and scatter light along each view ray, so vanilla's flat water fog would only hide the world. */
	private static final float OUT_OF_REACH = 1.0E6F;

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void glow$clearWaterFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkness, ClientLevel level, boolean bossFog, CallbackInfoReturnable<FogData> cir) {
		FogData data = cir.getReturnValue();
		// Vanilla's render distance fog would tint the surface colours that the traced light multiplies. Composite draws the mist instead.
		if (GlowSettings.distanceFogActive()) {
			data.renderDistanceStart = OUT_OF_REACH;
			data.renderDistanceEnd = OUT_OF_REACH + 1.0F;
		}
		if (GlowSettings.underwaterColor() < 0) return;
		data.environmentalStart = Math.max(data.environmentalStart, data.renderDistanceStart);
		data.environmentalEnd = Math.max(data.environmentalEnd, data.renderDistanceEnd);
		data.skyEnd = Math.max(data.skyEnd, data.renderDistanceEnd);
		data.cloudEnd = Math.max(data.cloudEnd, data.renderDistanceEnd);
	}
}
