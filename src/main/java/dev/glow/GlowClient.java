package dev.glow;

import net.fabricmc.api.ClientModInitializer;

public final class GlowClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		GlowSettings.load();
		GlowRenderer.LOGGER.info("Glow loaded");
	}
}
