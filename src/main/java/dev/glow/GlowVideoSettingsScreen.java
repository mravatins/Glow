package dev.glow;

import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import dev.glow.mixin.VideoSettingsScreenAccessor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Vanilla's video settings sorted into tabs, led by Glow's own ray tracing settings. */
public class GlowVideoSettingsScreen extends VideoSettingsScreen {
	public enum Tab {
		RAY_TRACING("Ray Tracing"), DISPLAY("Display"), WORLD("World"), INTERFACE("Interface");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int HEADER_HEIGHT = 82;
	private static final int TAB_WIDTH = 78;
	private static final int MODE_WIDTH = 104;
	private static final int STATS_COLOR = 0xFFA0A0A0;
	private static final String REPLACED = "The renderer replaces this. Set the mode to Off to change it.";
	private static final String UNAVAILABLE = "This graphics card or driver doesn't support Vulkan ray queries.";

	public static Tab tab = Tab.RAY_TRACING;

	private final Map<GlowSettings.Option, CycleButton<GlowSettings.Quality>> optionButtons = new EnumMap<>(GlowSettings.Option.class);
	private final Map<GlowSettings.Nvidia, CycleButton<Integer>> nvidiaButtons = new EnumMap<>(GlowSettings.Nvidia.class);
	private final Map<GlowSettings.Tuning, TuningSlider> tuningSliders = new EnumMap<>(GlowSettings.Tuning.class);
	private final Map<GlowSettings.Toggle, CycleButton<Boolean>> toggleButtons = new EnumMap<>(GlowSettings.Toggle.class);
	private final Map<GlowSettings.Limit, LimitSlider> limitSliders = new EnumMap<>(GlowSettings.Limit.class);
	private final List<OptionInstance<?>> replacedOptions = new ArrayList<>();
	private final Map<GlowSettings.PathSetting, CycleButton<Integer>> pathButtons = new EnumMap<>(GlowSettings.PathSetting.class);
	private CycleButton<GlowSettings.Preset> presetButton;

	public GlowVideoSettingsScreen(Screen lastScreen, Minecraft minecraft, Options options) {
		super(lastScreen, minecraft, options);
		this.layout.setHeaderHeight(HEADER_HEIGHT);
	}

	private static Component header(String text) {
		return Component.literal(text).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD);
	}

	@Override
	protected void addTitle() {
		super.addTitle();
		GlowSettings.RenderMode mode = GlowSettings.mode();
		// Off has no renderer to set up, so its menu is only vanilla's options.
		if (mode == GlowSettings.RenderMode.OFF && tab == Tab.RAY_TRACING) tab = Tab.DISPLAY;
		LinearLayout modes = LinearLayout.horizontal().spacing(4);
		for (GlowSettings.RenderMode each : GlowSettings.RenderMode.values()) {
			Button button = Button.builder(Component.literal(each.label), b -> this.switchMode(each)).width(MODE_WIDTH).build();
			boolean supported = each == GlowSettings.RenderMode.OFF || !GlowRenderer.isUnavailable();
			button.active = each != mode && supported;
			button.setTooltip(Tooltip.create(Component.literal(supported ? each.tooltip : UNAVAILABLE)));
			modes.addChild(button);
		}
		LinearLayout tabs = LinearLayout.horizontal().spacing(4);
		for (Tab each : Tab.values()) {
			if (each == Tab.RAY_TRACING && mode == GlowSettings.RenderMode.OFF) continue;
			String label = each == Tab.RAY_TRACING ? mode.label : each.label;
			Button button = Button.builder(Component.literal(label), b -> this.open(each)).width(TAB_WIDTH).build();
			button.active = each != tab;
			tabs.addChild(button);
		}
		LinearLayout header = ((VideoSettingsScreenAccessor) this).glow$header();
		header.addChild(modes);
		header.addChild(tabs);
	}

	private void open(Tab next) {
		tab = next;
		if (this.list != null) this.list.applyUnsavedChanges();
		this.rebuild();
	}

	/** Each mode has its own menu, so changing mode opens that menu's first tab. */
	private void switchMode(GlowSettings.RenderMode next) {
		if (this.list != null) this.list.applyUnsavedChanges();
		GlowSettings.setMode(next);
		tab = next == GlowSettings.RenderMode.OFF ? Tab.DISPLAY : Tab.RAY_TRACING;
		this.rebuild();
	}

	private void rebuild() {
		this.layout.removeChildren();
		VideoSettingsScreenAccessor self = (VideoSettingsScreenAccessor) this;
		self.glow$header().removeChildren();
		self.glow$setRestartWarning(null);
		this.rebuildWidgets();
	}

	@Override
	protected void addOptions() {
		this.optionButtons.clear();
		this.nvidiaButtons.clear();
		this.tuningSliders.clear();
		this.limitSliders.clear();
		this.toggleButtons.clear();
		this.replacedOptions.clear();
		this.pathButtons.clear();
		this.presetButton = null;
		// Vanilla rebuilds the screen whenever this differs from the window's monitor, so every tab has to set it.
		((VideoSettingsScreenAccessor) this).glow$setFullscreenModeMonitor(this.minecraft.getWindow().findBestMonitor());
		switch (tab) {
			case RAY_TRACING -> {
				if (GlowSettings.mode() == GlowSettings.RenderMode.PATH_TRACING) this.addPathTracing();
				else this.addRayTracing();
			}
			case DISPLAY -> this.addDisplay();
			case WORLD -> this.addWorld();
			case INTERFACE -> this.addInterface();
		}
		this.refresh();
	}

	private void addRayTracing() {
		this.presetButton = CycleButton.builder(GlowSettings.Preset::component, GlowSettings.currentPreset())
			.withValues(GlowSettings.Preset.LOW, GlowSettings.Preset.MEDIUM, GlowSettings.Preset.HIGH, GlowSettings.Preset.ULTRA)
			.withTooltip(value -> Tooltip.create(Component.literal("Sets every ray tracing setting below to the same level, and turns on Ray Reconstruction with DLSS. Medium is the recommended balance, with DLSS on Auto. Low traces fewer pixels than Auto, High traces more, and Ultra uses DLSS Quality with twice the rays. Changing a level afterward shows Custom.")))
			.create(Component.literal("Quality Preset"), (button, value) -> {
				GlowSettings.applyPresetWithUpscaling(value);
				this.rebuild();
			});
		this.list.addBig(this.presetButton);

		this.list.addHeader(header("Lighting"));
		this.addOptionRow(GlowSettings.Option.SUN_SHADOWS, GlowSettings.Option.GLOBAL_ILLUMINATION);
		this.addOptionRow(GlowSettings.Option.BLOCK_LIGHTS, GlowSettings.Option.LIGHT_SHAFTS);
		this.list.addHeader(header("Surfaces"));
		this.addOptionRow(GlowSettings.Option.REFLECTIONS, GlowSettings.Option.MATERIALS);
		this.addOptionRow(GlowSettings.Option.PARALLAX, GlowSettings.Option.FOLIAGE);
		this.list.addSmall(this.qualityButton(GlowSettings.Option.WATER), null);
		this.list.addHeader(header("Sky and Image"));
		this.addOptionRow(GlowSettings.Option.CLOUD_SHADOWS, GlowSettings.Option.DENOISER);

		this.list.addHeader(header("NVIDIA RTX"));
		this.list.addSmall(this.nvidiaButton(GlowSettings.Nvidia.DLSS), this.nvidiaButton(GlowSettings.Nvidia.RAY_RECONSTRUCTION));
		this.list.addSmall(this.nvidiaButton(GlowSettings.Nvidia.FRAME_GENERATION), this.nvidiaButton(GlowSettings.Nvidia.REFLEX));

		this.list.addHeader(header("Advanced"));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.RESTIR_GI), this.toggleButton(GlowSettings.Toggle.RESTIR_CORNER_LIMITS));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.RESTIR_DI), this.toggleButton(GlowSettings.Toggle.RADIANCE_CACHE));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.WATER_TRACED_BOUNCE), this.toggleButton(GlowSettings.Toggle.CAUSTIC_LIMIT));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.DLSS_LOD_BIAS), this.toggleButton(GlowSettings.Toggle.AUTO_EXPOSURE));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.DISTANCE_FOG), this.slider(GlowSettings.Tuning.FOG_DENSITY));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.TRACED_EFFECTS), this.toggleButton(GlowSettings.Toggle.CACHED_SUN));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.CACHED_GI), this.toggleButton(GlowSettings.Toggle.MERGED_GEOMETRY));

		this.list.addHeader(header("Ray Limits"));
		this.list.addSmall(this.limitSlider(GlowSettings.Limit.SHADOW_DISTANCE), this.limitSlider(GlowSettings.Limit.GI_DISTANCE));
		this.list.addSmall(this.limitSlider(GlowSettings.Limit.REFLECTION_DISTANCE), this.limitSlider(GlowSettings.Limit.CACHED_GI_DISTANCE));
		this.list.addSmall(this.limitSlider(GlowSettings.Limit.GI_BOUNCES), this.limitSlider(GlowSettings.Limit.REFLECTION_BOUNCES));

		this.list.addHeader(header("Fine Tuning"));
		this.list.addSmall(this.slider(GlowSettings.Tuning.EXPOSURE), this.slider(GlowSettings.Tuning.SUN_SOFTNESS));
		this.list.addSmall(this.slider(GlowSettings.Tuning.BLOCK_LIGHT_STRENGTH), this.slider(GlowSettings.Tuning.SHAFT_DENSITY));
		this.list.addSmall(this.slider(GlowSettings.Tuning.PARALLAX_DEPTH), this.slider(GlowSettings.Tuning.WAVE_HEIGHT));
		this.list.addBig(Button.builder(Component.literal("Reset Ray Tracing to Defaults"), button -> {
			GlowSettings.resetToDefaults();
			GlowSettings.applyRecommendedNvidia();
			this.rebuild();
		}).build());
	}

	/** Path Tracing's menu. Light has no separate quality settings here, because every path traces all of it. */
	private void addPathTracing() {
		this.presetButton = CycleButton.builder(GlowSettings.Preset::component, GlowSettings.currentPathPreset())
			.withValues(GlowSettings.Preset.LOW, GlowSettings.Preset.MEDIUM, GlowSettings.Preset.HIGH, GlowSettings.Preset.ULTRA)
			.withTooltip(value -> Tooltip.create(Component.literal("Sets Samples per Pixel and Max Bounces together. Changing either afterward shows Custom.")))
			.create(Component.literal("Path Quality"), (button, value) -> {
				GlowSettings.applyPathPreset(value);
				this.pathButtons.forEach((setting, widget) -> widget.setValue(setting.value));
				this.refresh();
			});
		this.list.addBig(this.presetButton);

		this.list.addHeader(header("Paths"));
		this.list.addSmall(this.pathButton(GlowSettings.PathSetting.SAMPLES), this.pathButton(GlowSettings.PathSetting.BOUNCES));
		this.list.addSmall(this.pathButton(GlowSettings.PathSetting.FIREFLY_LIMIT), this.pathButton(GlowSettings.PathSetting.ACCUMULATION));
		this.list.addHeader(header("Surfaces"));
		this.addOptionRow(GlowSettings.Option.MATERIALS, GlowSettings.Option.PARALLAX);
		this.list.addSmall(this.qualityButton(GlowSettings.Option.WATER), null);
		this.list.addHeader(header("Sky and Image"));
		this.addOptionRow(GlowSettings.Option.LIGHT_SHAFTS, GlowSettings.Option.CLOUD_SHADOWS);
		this.list.addSmall(this.qualityButton(GlowSettings.Option.DENOISER), null);

		this.list.addHeader(header("NVIDIA RTX"));
		this.list.addSmall(this.nvidiaButton(GlowSettings.Nvidia.DLSS), this.nvidiaButton(GlowSettings.Nvidia.RAY_RECONSTRUCTION));
		this.list.addSmall(this.nvidiaButton(GlowSettings.Nvidia.FRAME_GENERATION), this.nvidiaButton(GlowSettings.Nvidia.REFLEX));

		this.list.addHeader(header("Sampling"));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.RESTIR_GI), this.toggleButton(GlowSettings.Toggle.RESTIR_CORNER_LIMITS));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.RESTIR_DI), this.toggleButton(GlowSettings.Toggle.AUTO_EXPOSURE));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.DLSS_LOD_BIAS), this.toggleButton(GlowSettings.Toggle.CAUSTIC_LIMIT));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.DISTANCE_FOG), this.slider(GlowSettings.Tuning.FOG_DENSITY));
		this.list.addSmall(this.toggleButton(GlowSettings.Toggle.TRACED_EFFECTS), null);

		this.list.addHeader(header("Fine Tuning"));
		this.list.addSmall(this.slider(GlowSettings.Tuning.EXPOSURE), this.slider(GlowSettings.Tuning.SUN_SOFTNESS));
		this.list.addSmall(this.slider(GlowSettings.Tuning.BLOCK_LIGHT_STRENGTH), this.slider(GlowSettings.Tuning.SHAFT_DENSITY));
		this.list.addSmall(this.slider(GlowSettings.Tuning.PARALLAX_DEPTH), this.slider(GlowSettings.Tuning.WAVE_HEIGHT));
		this.list.addBig(Button.builder(Component.literal("Reset Path Tracing to Defaults"), button -> {
			GlowSettings.resetPathTracing();
			GlowSettings.resetNvidia();
			this.rebuild();
		}).build());
	}

	private CycleButton<Integer> pathButton(GlowSettings.PathSetting setting) {
		List<Integer> values = new ArrayList<>();
		for (int i = 0; i < setting.labels.length; i++) values.add(i);
		CycleButton<Integer> button = CycleButton.builder(setting::component, setting.value)
			.withValues(values)
			.withTooltip(value -> Tooltip.create(Component.literal(setting.tooltip)))
			.create(Component.literal(setting.label), (widget, value) -> {
				setting.value = value;
				this.refresh();
			});
		this.pathButtons.put(setting, button);
		return button;
	}

	private void addOptionRow(GlowSettings.Option left, GlowSettings.Option right) {
		this.list.addSmall(this.qualityButton(left), this.qualityButton(right));
	}

	private CycleButton<GlowSettings.Quality> qualityButton(GlowSettings.Option option) {
		CycleButton<GlowSettings.Quality> button = CycleButton.builder(GlowSettings.Quality::component, option.value)
			.withValues(GlowSettings.Quality.values())
			.create(Component.literal(option.label), (widget, value) -> {
				option.value = value;
				this.refresh();
			});
		this.optionButtons.put(option, button);
		return button;
	}

	private CycleButton<Integer> nvidiaButton(GlowSettings.Nvidia setting) {
		List<Integer> values = new ArrayList<>();
		for (int i = 0; i < setting.values.length; i++) values.add(i);
		CycleButton<Integer> button = CycleButton.builder(setting::component, setting.value)
			.withValues(values)
			.create(Component.literal(setting.label), (widget, value) -> {
				setting.value = value;
				this.refresh();
			});
		this.nvidiaButtons.put(setting, button);
		return button;
	}

	private CycleButton<Boolean> toggleButton(GlowSettings.Toggle toggle) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(toggle.value).create(Component.literal(toggle.label), (widget, value) -> {
			toggle.value = value;
			this.refresh();
		});
		this.toggleButtons.put(toggle, button);
		return button;
	}

	private LimitSlider limitSlider(GlowSettings.Limit limit) {
		LimitSlider slider = new LimitSlider(limit);
		this.limitSliders.put(limit, slider);
		return slider;
	}

	private TuningSlider slider(GlowSettings.Tuning tuning) {
		TuningSlider slider = new TuningSlider(tuning);
		this.tuningSliders.put(tuning, slider);
		return slider;
	}

	private void addDisplay() {
		this.list.addHeader(header("Window"));
		this.addExclusiveModeOption();
		this.list.addSmall(this.options.fullscreen(), this.options.exclusiveFullscreen());
		this.list.addHeader(header("Frame Rate"));
		this.list.addSmall(this.options.framerateLimit(), this.options.enableVsync(), this.options.inactivityFpsLimit());
		this.list.addHeader(header("Advanced"));
		List<OptionInstance<?>> advanced = new ArrayList<>(List.of(this.options.gamma(), this.options.preferredGraphicsBackend()));
		if (Util.getPlatform() == Util.OS.WINDOWS) advanced.add(this.options.preferDXGIPresent());
		if (MacosUtil.IS_MACOS) advanced.add(this.options.macFullscreenMenuVisibility());
		this.list.addSmall(advanced.toArray(OptionInstance[]::new));
		this.replacedOptions.add(this.options.gamma());
		this.replacedOptions.add(this.options.preferredGraphicsBackend());
	}

	/** The same control vanilla's screen builds inline, so the monitor it remembers still matches. */
	private void addExclusiveModeOption() {
		Window window = this.minecraft.getWindow();
		Monitor monitor = window.findBestMonitor();
		int initial = monitor == null ? -1 : window.getPreferredFullscreenVideoMode().map(monitor::indexOfMode).orElse(-1);
		this.list.addBig(new OptionInstance<>(
			"options.fullscreen.exclusive.mode",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.fullscreen.exclusive.mode.tooltip")),
			(caption, value) -> {
				if (monitor == null) return Component.translatable("options.fullscreen.unavailable");
				if (value == -1) return Options.genericValueLabel(caption, Component.translatable("options.fullscreen.current"));
				VideoMode mode = monitor.mode(value);
				return Options.genericValueLabel(caption, Component.translatable("options.fullscreen.entry", mode.getWidth(), mode.getHeight(), mode.refreshRateLabel(), mode.getRedBits() + mode.getGreenBits() + mode.getBlueBits()));
			},
			new OptionInstance.IntRange(-1, monitor != null ? monitor.modeCount() - 1 : -1),
			initial,
			value -> {
				if (monitor != null) window.setPreferredFullscreenVideoMode(value == -1 ? Optional.empty() : Optional.of(monitor.mode(value)));
			}
		));
	}

	private void addWorld() {
		this.list.addBig(this.options.graphicsPreset());
		this.list.addHeader(header("Distance"));
		this.list.addSmall(this.options.renderDistance(), this.options.simulationDistance(), this.options.entityDistanceScaling(), this.options.prioritizeChunkUpdates());
		this.list.addHeader(header("World Detail"));
		this.list.addSmall(this.options.cloudStatus(), this.options.cloudRange(), this.options.particles(), this.options.weatherRadius(), this.options.biomeBlendRadius());
		this.list.addHeader(header("Textures"));
		this.list.addSmall(this.options.mipmapLevels(), this.options.textureFiltering(), this.options.maxAnisotropyBit());
		this.list.addHeader(header(GlowRenderer.replacesVanillaLighting() ? "Replaced by the Renderer" : "Lighting"));
		OptionInstance<?>[] replaced = {this.options.ambientOcclusion(), this.options.entityShadows(), this.options.improvedTransparency(), this.options.cutoutLeaves(), this.options.chunkSectionFadeInTime(), this.options.vignette()};
		this.list.addSmall(replaced);
		this.replacedOptions.addAll(List.of(replaced));
	}

	private void addInterface() {
		this.list.addSmall(this.options.guiScale(), this.options.menuBackgroundBlurriness(), this.options.showAutosaveIndicator(), this.options.attackIndicator());
	}

	/** Greys out whatever can't apply right now and says why in its tooltip. */
	private void refresh() {
		boolean available = !GlowRenderer.isUnavailable();
		if (this.presetButton != null) {
			this.presetButton.active = available && GlowRenderer.enabled;
			this.presetButton.setValue(GlowSettings.pathTraced() ? GlowSettings.currentPathPreset() : GlowSettings.currentPreset());
		}
		this.optionButtons.forEach((option, button) -> {
			String reason = GlowSettings.blockedReason(option);
			button.active = reason == null;
			button.setTooltip(Tooltip.create(Component.literal(reason != null ? reason : option.tooltip(option.value))));
		});
		this.nvidiaButtons.forEach((setting, button) -> {
			String reason = GlowSettings.blockedReason(setting);
			button.active = reason == null;
			button.setTooltip(Tooltip.create(Component.literal(reason != null ? reason : setting.tooltip(setting.value))));
		});
		this.tuningSliders.forEach((tuning, slider) -> {
			String reason = GlowSettings.blockedReason(tuning);
			slider.active = reason == null;
			slider.setTooltip(Tooltip.create(Component.literal(reason != null ? reason : tuning.tooltip)));
		});
		this.limitSliders.forEach((limit, slider) -> {
			String reason = GlowSettings.blockedReason(limit);
			slider.active = reason == null;
			slider.setTooltip(Tooltip.create(Component.literal(reason != null ? reason : limit.tooltip)));
		});
		this.toggleButtons.forEach((toggle, button) -> {
			String reason = GlowSettings.blockedReason(toggle);
			button.active = reason == null;
			button.setTooltip(Tooltip.create(Component.literal(reason != null ? reason : toggle.tooltip)));
		});
		for (OptionInstance<?> option : this.replacedOptions) {
			AbstractWidget widget = this.list.findOption(option);
			if (widget == null) continue;
			widget.active = !GlowRenderer.replacesVanillaLighting();
			if (GlowRenderer.replacesVanillaLighting()) widget.setTooltip(Tooltip.create(Component.literal(REPLACED)));
		}
		AbstractWidget cloudRange = this.list.findOption(this.options.cloudRange());
		if (cloudRange != null) cloudRange.active = this.options.cloudStatus().get() != CloudStatus.OFF;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.list != null) this.refresh();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics) {
		super.extractRenderState(graphics);
		if (tab != Tab.RAY_TRACING) return;
		String stats;
		if (!GlowRenderer.replacesVanillaLighting()) stats = "Ray tracing is off";
		else if (this.minecraft.level == null) stats = "Join a world to see how each setting performs";
		else if (GlowSettings.pathTraced()) stats = String.format(Locale.ROOT, "%d FPS   Paths %.1f ms   Denoising %.1f ms   Still frames %d", this.minecraft.getFps(), GlowRenderer.timing(1), GlowRenderer.timing(2), GlowRenderer.accumulatedFrames());
		else stats = String.format(Locale.ROOT, "%d FPS   Tracing %.1f ms   Denoising %.1f ms", this.minecraft.getFps(), GlowRenderer.timing(1), GlowRenderer.timing(2));
		graphics.centeredText(this.font, stats, this.width / 2, HEADER_HEIGHT - this.font.lineHeight - 1, STATS_COLOR);
	}

	@Override
	public void removed() {
		GlowSettings.save();
		super.removed();
	}

	private static class LimitSlider extends AbstractSliderButton {
		private final GlowSettings.Limit limit;

		LimitSlider(GlowSettings.Limit limit) {
			super(0, 0, 150, 20, Component.empty(), position(limit));
			this.limit = limit;
			this.updateMessage();
		}

		private static double position(GlowSettings.Limit limit) {
			for (int i = 0; i < limit.steps.length; i++) {
				if (limit.steps[i] == limit.value) return (double) i / (limit.steps.length - 1);
			}
			return 0.0;
		}

		private int step() {
			return this.limit.steps[(int) Math.round(this.value * (this.limit.steps.length - 1))];
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(this.limit.label + ": " + this.limit.display(this.step())));
		}

		@Override
		protected void applyValue() {
			this.limit.value = this.step();
		}
	}

	private static class TuningSlider extends AbstractSliderButton {
		private static final double STEP = 0.05;
		private final GlowSettings.Tuning tuning;

		TuningSlider(GlowSettings.Tuning tuning) {
			super(0, 0, 150, 20, Component.empty(), (tuning.value - tuning.min) / (tuning.max - tuning.min));
			this.tuning = tuning;
			this.updateMessage();
		}

		private double scaled() {
			return Math.round((this.tuning.min + this.value * (this.tuning.max - this.tuning.min)) / STEP) * STEP;
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(String.format(Locale.ROOT, "%s: %d%%", this.tuning.label, Math.round(this.scaled() * 100.0))));
		}

		@Override
		protected void applyValue() {
			this.tuning.value = this.scaled();
		}
	}
}
