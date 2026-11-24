package dev.glow.mixin;

import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ShaderManager.class)
public class ShaderManagerMixin {
	private static final String AFTER_HEADER = "#extension GL_ARB_separate_shader_objects : require\n";

	/**
	 * Gives terrain a -1 LOD bias while DLSS is on. Vanilla's terrain shader picks each mip level itself, so a sampler bias alone isn't what's wanted:
	 * these macros wrap the two lookups its sampling functions use. {@link GlobalSettingsUniformMixin} sets bit 1 of UseRgss when the bias applies.
	 */
	@ModifyArg(method = "loadShader", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableMap$Builder;put(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;"), index = 1)
	private static Object glow$biasTerrainMips(Object source) {
		String text = (String) source;
		if (!text.contains("UseRgss == 1") || !text.contains(AFTER_HEADER)) return source;
		return text.replace("UseRgss == 1", "(UseRgss & 1) == 1").replace(AFTER_HEADER, AFTER_HEADER + """
			#define GLOW_LOD_BIAS float((UseRgss >> 1) & 1)
			#define textureLod(s, uv, lod) textureLod(s, uv, (lod) - GLOW_LOD_BIAS)
			#define textureGrad(s, uv, dx, dy) textureGrad(s, uv, (dx) * (1.0 - 0.5 * GLOW_LOD_BIAS), (dy) * (1.0 - 0.5 * GLOW_LOD_BIAS))
			""");
	}
}
