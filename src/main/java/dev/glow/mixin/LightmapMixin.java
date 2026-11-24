package dev.glow.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.glow.GlowLightmap;
import dev.glow.GlowRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A white lightmap removes vanilla's sky and block light from everything drawn with it, whatever the smooth lighting setting is. */
@Mixin(Lightmap.class)
public class LightmapMixin {
	@Unique
	private static final Vector4fc GLOW$WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

	@Shadow
	@Final
	private GpuTexture texture;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void glow$white(LightmapRenderState state, CallbackInfo ci) {
		if (!GlowRenderer.replacesVanillaLighting() || GlowLightmap.isRestoring()) return;
		GlowLightmap.remember((Lightmap) (Object) this, state);
		RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, GLOW$WHITE);
		ci.cancel();
	}
}
