package dev.glow;

import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;

/** Vanilla's lightmap stays white while the traced world draws, then gets its real values back for what draws afterward. */
public final class GlowLightmap {
	private static Lightmap lightmap;
	private static LightmapRenderState state;
	private static boolean restoring;

	private GlowLightmap() {}

	public static boolean isRestoring() {
		return restoring;
	}

	public static void remember(Lightmap target, LightmapRenderState current) {
		lightmap = target;
		state = current;
	}

	public static void restoreVanilla() {
		if (lightmap == null || state == null) return;
		restoring = true;
		state.needsUpdate = true;
		lightmap.render(state);
		restoring = false;
		state = null;
	}
}
