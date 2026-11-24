package dev.glow;

import dev.glow.GlowSettings.Nvidia;
import dev.glow.GlowSettings.Option;
import dev.glow.GlowSettings.Quality;
import dev.glow.GlowSettings.Toggle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev harness, enabled by the GLOW_AUTOTEST environment variable: builds a test scene, takes screenshots and exits.
 * GLOW_AUTOTEST=1 runs every scene; a comma list of groups (e.g. {@code water,dlss}) runs only those.
 * Each scene starts from default settings, then each shot applies its change, waits and takes a screenshot.
 */
public final class GlowAutotest {
	private static final String FILTER = System.getenv("GLOW_AUTOTEST");
	public static final boolean ENABLED = FILTER != null;
	private static final int SETUP_FRAME = 60, SETTLE = 580, GAP = 5;
	private static final String HOME = "tp @s 4 140 30 180 35";
	private static final String[] SETUP = {
		"gamerule advance_time false",
		"fill -16 120 -16 16 120 16 minecraft:smooth_quartz",
		"fill -1 121 -1 1 132 1 minecraft:stone_bricks",
		"fill 6 121 -10 6 128 -6 minecraft:oak_leaves",
		"fill -10 121 8 -8 121 10 minecraft:torch",
		"setblock 8 121 8 minecraft:oak_fence",
		"setblock 10 121 8 minecraft:poppy",
		"setblock 10 121 10 minecraft:tall_grass",
		"fill -11 124 7 -7 124 11 minecraft:smooth_quartz",
		"summon minecraft:villager 3 121 10 {NoAI:1b,Rotation:[180f,0f]}",
		"summon minecraft:pig 0 121 8 {NoAI:1b}",
		"summon minecraft:armor_stand -3 121 10 {ShowArms:1b}",
		"setblock 6 121 12 minecraft:chest",
		"setblock 0 121 11 minecraft:glowstone",
		"fill -14 121 4 -14 124 8 minecraft:cobblestone",
		"fill -13 121 4 -13 124 8 minecraft:bricks",
		"fill 7 121 -2 7 123 -2 minecraft:glass",
		"fill 12 121 4 16 125 12 minecraft:stone hollow",
		"fill 13 121 12 15 124 12 minecraft:air",
		"setblock 14 121 6 minecraft:torch",
		"fill 0 121 -6 0 125 -3 minecraft:red_stained_glass",
		"fill -6 121 9 -6 123 9 minecraft:stone_bricks",
		"fill -4 120 12 -1 120 15 minecraft:iron_block",
		"fill 1 120 12 4 120 15 minecraft:gold_block",
		"fill -8 116 -12 -2 116 -2 minecraft:sandstone",
		"fill -8 117 -12 -2 120 -2 minecraft:water",
		"setblock -5 117 -7 minecraft:gold_block",
		"fill -9 121 -13 -9 126 -13 minecraft:red_concrete",
		"fill 13 121 7 15 121 8 minecraft:lava",
		"fill 12 121 -8 12 126 -2 minecraft:red_concrete",
		HOME,
	};

	/** Apply {@code changes} (commands or Runnables), wait {@code delay} frames, then screenshot {@code name}.png. {@code motion} sends a command every 9 frames while waiting,
	 * and {@code everyFrame} runs on every frame while waiting, with the frame number since the shot began. */
	private record Shot(String name, int delay, IntFunction<String> motion, IntConsumer everyFrame, Object... changes) {}
	private record Scene(String group, Shot... shots) {}

	private static Runnable ommIgnored(boolean ignored) {
		return () -> GlowRenderer.micromapsIgnored = ignored;
	}

	private static Runnable pathTraced(GlowSettings.Preset preset) {
		return () -> {
			GlowSettings.pathTracing = true;
			GlowSettings.applyPathPreset(preset);
		};
	}

	/** Opens the settings screen on one tab of one mode and saves the window, since the frame-based shots can't show menus. Frames stop counting until the screen closes. */
	private static Runnable menu(GlowSettings.RenderMode mode, GlowVideoSettingsScreen.Tab tab, String name) {
		return () -> {
			Minecraft minecraft = Minecraft.getInstance();
			GlowSettings.setMode(mode);
			GlowVideoSettingsScreen.tab = tab;
			minecraft.gui.setScreen(new GlowVideoSettingsScreen(null, minecraft, minecraft.options));
			Thread.ofPlatform().start(() -> {
				try {
					Thread.sleep(3000L);
					new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "../tools/capture-window.ps1", Long.toString(ProcessHandle.current().pid()), "screenshots/" + name + ".png").inheritIO().start().waitFor();
				} catch (Exception e) {
					GlowRenderer.LOGGER.warn("Autotest could not capture {}", name, e);
				}
				minecraft.execute(() -> minecraft.gui.setScreen(null));
			});
		};
	}

	private static Runnable pathNoDenoiser(int accumulation) {
		return () -> {
			Option.DENOISER.value = Quality.OFF;
			GlowSettings.PathSetting.ACCUMULATION.value = accumulation;
		};
	}

	private static final List<Scene> SCENES = List.of(
		// Each Path Tracing shot compares with its _rt twin from the same view. glow_pt_room_1 and _600 show a still view converging with the denoiser off.
		// A held light in a sealed box at night, so everything visible comes from it, then the same box with a placed block. Creative, because spectators have no hand.
		new Scene("hand",
			shot("glow_hand_day", 110, "gamemode creative", "item replace entity @s weapon.mainhand with minecraft:glowstone", HOME),
			shot("glow_hand_dark_empty", 400, "time set 18000", "fill 30 130 30 38 136 38 minecraft:stone hollow", "item replace entity @s weapon.mainhand with minecraft:air", "tp @s 34 131 31 0 15"),
			shot("glow_hand_dark_torch", 45, "item replace entity @s weapon.mainhand with minecraft:torch"),
			shot("glow_hand_dark_glowstone", 45, "item replace entity @s weapon.mainhand with minecraft:glowstone"),
			shot("glow_hand_dark_offhand", 45, "item replace entity @s weapon.mainhand with minecraft:iron_sword", "item replace entity @s weapon.offhand with minecraft:torch"),
			shot("glow_hand_placed", 200, "item replace entity @s weapon.mainhand with minecraft:air", "item replace entity @s weapon.offhand with minecraft:air", "setblock 34 132 36 minecraft:glowstone"),
			shot("glow_hand_wall", 45, "setblock 34 132 36 minecraft:air", "item replace entity @s weapon.mainhand with minecraft:torch", "tp @s 34 131 37.3 0 0"),
			shot("glow_hand_day_rr", 220, "time set 2500", "item replace entity @s weapon.mainhand with minecraft:glowstone", HOME, (Runnable) () -> { GlowSettings.Nvidia.RAY_RECONSTRUCTION.value = 1; GlowSettings.Nvidia.DLSS.value = 2; }),
			shot("glow_hand_day_pt", 120, (Runnable) () -> { GlowSettings.resetNvidia(); GlowSettings.pathTracing = true; }),
			shot("glow_hand_noon", 45, (Runnable) () -> GlowSettings.pathTracing = false, "gamemode spectator", "clear @s", "time set 2500", HOME)),
		// A sealed box in daylight, then a block placed and broken inside it: the _place and _break shots, a few frames after the change, should stay as dark as _before.
		new Scene("blockchange",
			shot("glow_block_home", 110, "gamemode creative", HOME),
			shot("glow_block_before", 400, "fill 30 130 30 38 136 38 minecraft:stone hollow", "tp @s 34 131 31 0 15"),
			shot("glow_block_place", 3, "setblock 34 132 36 minecraft:stone"),
			shot("glow_block_settled", 60),
			shot("glow_block_break", 3, "setblock 34 132 36 minecraft:air"),
			shot("glow_block_end", 45, "gamemode spectator", HOME)),
		new Scene("path",
			shot("glow_pt_home_rt", 70, HOME),
			shot("glow_pt_home", 70, pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("glow_pt_room_rt", 70, "tp @s 14 122 11 180 10", (Runnable) () -> GlowSettings.pathTracing = false),
			shot("glow_pt_room", 70, pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("glow_pt_room_ultra", 40, pathTraced(GlowSettings.Preset.ULTRA)),
			shot("glow_pt_water", 70, "tp @s -4 125 3 180 25", (Runnable) () -> Option.DENOISER.value = Quality.HIGH),
			shot("glow_pt_metal", 70, "tp @s 0 123 22 180 25"),
			shot("glow_pt_foliage", 70, "tp @s 9 126 -14 45 30"),
			shot("glow_pt_night", 70, "time set 18000", "tp @s -6 127 17 180 35"),
			shot("glow_pt_rr", 70, "time set 2500", HOME, rayReconstruction()),
			shot("glow_pt_dlss", 40, (Runnable) () -> GlowSettings.Nvidia.DLSS.value = 2)),
		new Scene("pathstill",
			shot("glow_pt_still_1", 45, "tp @s 14 122 11 180 10", pathTraced(GlowSettings.Preset.MEDIUM), pathNoDenoiser(0)),
			shot("glow_pt_still_600", 600, pathNoDenoiser(3))),
		// Water from near to the edge of the render distance. Glow's traced water must reach all the way out, with no vanilla colour or black showing through.
		new Scene("farwater",
			shot("glow_farwater_rt", 90, "tp @s 4 150 30 180 12"),
			shot("glow_farwater_low_rt", 70, "tp @s 4 90 30 180 4"),
			shot("glow_farwater_pool_rt", 70, "tp @s -5 122.5 0 180 25"),
			shot("glow_farwater_pt", 90, "tp @s 4 150 30 180 12", pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("glow_farwater_low_pt", 70, "tp @s 4 90 30 180 4"),
			shot("glow_farwater_pool_pt", 70, "tp @s -5 122.5 0 180 25")),
		// Terrain and water ending in mist, from high and low, and at other times of day.
		new Scene("fog",
			shot("glow_fog_high_on", 90, "tp @s 4 150 30 180 12"),
			shot("glow_fog_high_off", 20, toggle(() -> Toggle.DISTANCE_FOG.value = false)),
			shot("glow_fog_low_on", 60, "tp @s 4 90 30 180 4", toggle(() -> Toggle.DISTANCE_FOG.value = true)),
			shot("glow_fog_low_off", 20, toggle(() -> Toggle.DISTANCE_FOG.value = false)),
			shot("glow_fog_sunset", 70, "time set 12300", "tp @s 4 110 30 90 2", toggle(() -> Toggle.DISTANCE_FOG.value = true)),
			shot("glow_fog_night", 70, "time set 18000"),
			shot("glow_fog_morning", 70, "time set 500", "tp @s 4 110 30 180 8"),
			shot("glow_fog_inside", 60, "tp @s 4 76 30 180 2"),
			shot("glow_fog_rr", 120, "time set 6000", "tp @s 4 150 30 180 12", rayReconstruction()),
			shot("glow_fog_dlss", 90, (Runnable) () -> { Nvidia.RAY_RECONSTRUCTION.value = 0; Nvidia.DLSS.value = 2; })),
		// Smoke and flames in front of an iron wall, then rain, each with Traced Particles and Rain on and off.
		new Scene("effects",
			shot("glow_effects_smoke_on", 110, "time set 6000", "weather clear", "fill 58 124 58 72 136 80 minecraft:air", "fill 60 124 60 70 125 78 minecraft:stone",
				"fill 60 126 60 70 132 60 minecraft:iron_block", "tp @s 65 128 66 180 0", "particle minecraft:campfire_signal_smoke 65 128 72 2 1 2 0 300 force"),
			shot("glow_effects_smoke_off", 8, toggle(() -> GlowSettings.Toggle.TRACED_EFFECTS.value = false)),
			shot("glow_effects_flame_on", 40, "time set 18000", "particle minecraft:flame 65 128 72 2 1 2 0.01 600 force", toggle(() -> GlowSettings.Toggle.TRACED_EFFECTS.value = true)),
			shot("glow_effects_rain_on", 900, "time set 6000", "weather rain"),
			shot("glow_effects_rain_off", 40, toggle(() -> GlowSettings.Toggle.TRACED_EFFECTS.value = false)),
			shot("glow_effects_rain_pt", 90, toggle(() -> GlowSettings.Toggle.TRACED_EFFECTS.value = true), pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("glow_effects_clear", 10, "weather clear", (Runnable) () -> GlowSettings.pathTracing = false)),
		// Light shafts under Ray Reconstruction, still and just after a cut.
		new Scene("rrshafts",
			shot("glow_rrshafts_still", 260, "time set 23400", "weather clear", "tp @s 4 92 30 90 -8", (Runnable) () -> { GlowSettings.Nvidia.RAY_RECONSTRUCTION.value = 1; GlowSettings.Nvidia.DLSS.value = 2; }),
			shot("glow_rrshafts_cut", 4, "tp @s 10 92 30 90 -8"),
			shot("glow_rrshafts_cave", 200, "time set 6000", "tp @s 4 150 30 180 25"),
			shot("glow_rrshafts_done", 5, (Runnable) GlowSettings::resetNvidia)),
		// An iron wall facing a pool, red glass, a dropped item and a villager: each must show in the mirror and cast a shadow.
		new Scene("sees",
			shot("glow_sees_rt", 110, "time set 6000", "fill 58 124 58 72 136 76 minecraft:air", "fill 60 124 60 70 124 72 minecraft:stone", "fill 60 125 60 70 125 72 minecraft:stone", "fill 61 125 61 69 125 65 minecraft:water",
				"fill 60 126 60 70 132 60 minecraft:iron_block", "fill 62 126 66 63 130 66 minecraft:red_stained_glass", "summon item 67 126 67 {Item:{id:\"minecraft:diamond_block\",count:1},PickupDelay:32767,Age:-32768,NoGravity:1b}",
				"summon villager 65 126 67 {NoAI:1b}", "tp @s 65 129 74 180 12"),
			shot("glow_sees_pt", 90, pathTraced(GlowSettings.Preset.MEDIUM))),
		// A closed room with a water floor, lit only through one opening and by one light block. Vanilla's light levels must not show on the water.
		new Scene("cave",
			shot("glow_cave_open_rt", 90, "fill 50 125 50 66 133 70 minecraft:stone hollow", "fill 51 126 51 65 127 69 minecraft:water", "fill 56 130 50 60 132 50 minecraft:air", "setblock 64 132 68 minecraft:glowstone", "tp @s 58 129.5 67 180 22"),
			shot("glow_cave_lamp_rt", 70, "tp @s 58 129.5 54 0 22"),
			shot("glow_cave_open_pt", 90, "tp @s 58 129.5 67 180 22", pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("glow_cave_lamp_pt", 70, "tp @s 58 129.5 54 0 22"),
			shot("glow_cave_dark_pt", 90, "fill 56 130 50 60 132 50 minecraft:stone", "setblock 64 132 68 minecraft:air"),
			shot("glow_cave_dark_rt", 90, (Runnable) () -> GlowSettings.pathTracing = false)),
		new Scene("menus",
			shot("glow_menu_after_off", 5, menu(GlowSettings.RenderMode.OFF, GlowVideoSettingsScreen.Tab.WORLD, "menu_off_world")),
			shot("glow_menu_after_rt", 5, menu(GlowSettings.RenderMode.RAY_TRACING, GlowVideoSettingsScreen.Tab.RAY_TRACING, "menu_rt")),
			shot("glow_menu_after_pt", 5, menu(GlowSettings.RenderMode.PATH_TRACING, GlowVideoSettingsScreen.Tab.RAY_TRACING, "menu_pt"))),
		new Scene("modes",
			shot("glow_mode_rt", 60, HOME),
			shot("glow_mode_pt", 60, (Runnable) () -> GlowSettings.setMode(GlowSettings.RenderMode.PATH_TRACING)),
			shot("glow_mode_off", 60, (Runnable) () -> GlowSettings.setMode(GlowSettings.RenderMode.OFF)),
			shot("glow_mode_pt2", 90, (Runnable) () -> GlowSettings.setMode(GlowSettings.RenderMode.PATH_TRACING))),
		new Scene("basic", shot("glow_on", 60, HOME)),
		new Scene("basic", shot("glow_close", 55, "tp @s -1 125 22 180 20"), shot("glow_close_off", 10, (Runnable) () -> GlowRenderer.enabled = false)),
		new Scene("gi", shot("glow_room", 70, "tp @s 14 122 11 180 10"), shot("glow_room_nogi", 45, set(Option.GLOBAL_ILLUMINATION, Quality.OFF))),
		new Scene("reflections", shot("glow_water", 75, "tp @s -4 125 3 180 25"), shot("glow_water_norefl", 15, set(Option.REFLECTIONS, Quality.OFF))),
		new Scene("basic", shot("glow_metal", 75, "tp @s 0 123 22 180 25")),
		new Scene("gi", shot("glow_bleed", 75, "tp @s 16 124 -5 90 30"), shot("glow_bleed_nogi", 45, set(Option.GLOBAL_ILLUMINATION, Quality.OFF))),
		new Scene("sky", shot("glow_night", 115, "time set 18000", "tp @s -6 127 17 180 35"), shot("glow_noon", 565, "time set 6000", HOME),
			shot("glow_sky_up", 45, "tp @s -6 127 17 180 -40"), shot("glow_sunset", 75, "time set 12300", "tp @s -6 127 17 90 -5"), shot("glow_sunrise", 75, "time set 23300", "tp @s -6 127 17 -90 -5")),
		new Scene("shafts", shot("glow_shafts", 75, "time set 1000", "tp @s -14 124 0 -90 -5"), shot("glow_shafts_off", 15, set(Option.LIGHT_SHAFTS, Quality.OFF))),
		new Scene("clouds", shot("glow_clouds", 75, "time set 6000", HOME), shot("glow_clouds_off", 35, set(Option.CLOUD_SHADOWS, Quality.OFF))),
		new Scene("pbr", shot("glow_pbr", 65, "tp @s 0 123 22 180 25"), shot("glow_pbr_wall", 65, "tp @s 5 123 5 135 10"), shot("glow_pbr_wall_off", 35, set(Option.MATERIALS, Quality.OFF))),
		new Scene("foliage", shot("glow_foliage", 75, "tp @s 9 126 -14 45 30"), shot("glow_foliage_back", 75, "tp @s -1 124 -8 -90 -10")),
		new Scene("parallax", shot("glow_parallax", 65, "tp @s -11 123 3 40 15"), shot("glow_parallax_off", 35, set(Option.PARALLAX, Quality.OFF))),
		// Real frame times. Run at the display's size: GLOW_AUTOTEST=bench tools/autotest.sh 2560 1440
		new Scene("bench",
			shot("bench_old_settings", 200, rayReconstruction(), dlss(1), (Runnable) () -> { GlowSettings.Limit.GI_BOUNCES.value = 2; GlowSettings.Limit.REFLECTION_BOUNCES.value = 2; }, "tp @s 4 95 30 180 8"),
			shot("bench_land", 200, dlss(6), (Runnable) () -> { GlowSettings.Limit.GI_BOUNCES.value = 1; GlowSettings.Limit.REFLECTION_BOUNCES.value = 1; }),
			shot("bench_home", 150, HOME),
			moving("bench_moving", 300, step -> "tp @s " + (4 + step / 9) + " 95 " + (30 - step / 9) + " 180 8"),
			shot("bench_leaves", 200, "fill -16 96 -16 24 110 24 minecraft:oak_leaves replace air", "tp @s 4 114 30 180 25"),
			shot("bench_hand", 150, "gamemode creative", "item replace entity @s weapon.mainhand with minecraft:torch", "tp @s 4 95 30 180 8"),
			shot("bench_hand_empty", 150, "item replace entity @s weapon.mainhand with minecraft:air"),
			shot("bench_hand_spectator", 150, "gamemode spectator", "clear @s", "tp @s 4 95 30 180 8"),
			shot("bench_torches_night", 200, "time set 18000", "tp @s -3 123 16 200 15")),
		// Opacity micromaps on and off in the same world. Run alone: the leaves stay in the world.
		new Scene("benchomm",
			shot("omm_land_rt_on", 200, rayReconstruction(), dlss(6), "tp @s 4 95 30 180 8"),
			shot("omm_land_rt_off", 200, ommIgnored(true)),
			shot("omm_land_ptm_off", 200, pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("omm_land_ptm_on", 200, ommIgnored(false)),
			shot("omm_land_pth_on", 200, pathTraced(GlowSettings.Preset.HIGH)),
			shot("omm_land_pth_off", 200, ommIgnored(true)),
			shot("omm_canopy_rt_off", 200, (Runnable) () -> GlowSettings.pathTracing = false, "fill -16 96 -16 24 110 24 minecraft:oak_leaves replace air"),
			shot("omm_canopy_rt_on", 200, ommIgnored(false)),
			shot("omm_canopy_ptm_on", 200, pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("omm_canopy_ptm_off", 200, ommIgnored(true)),
			shot("omm_solid_ptm_on", 200, ommIgnored(false), (Runnable) () -> GlowRenderer.solidLeafShadows = true),
			shot("omm_solid_ptm_off", 200, (Runnable) () -> GlowRenderer.solidLeafShadows = false),
			shot("omm_solid_rt_off", 200, (Runnable) () -> GlowSettings.pathTracing = false),
			shot("omm_solid_rt_on", 200, (Runnable) () -> GlowRenderer.solidLeafShadows = true),
			shot("omm_solidland_rt_on", 200, "fill -16 96 -16 24 110 24 minecraft:air replace minecraft:oak_leaves"),
			shot("omm_solidland_rt_off", 200, (Runnable) () -> GlowRenderer.solidLeafShadows = false)),
		// Cached Sun Shadows, Cached Far GI and Merged Bounce Geometry, each off alone and all off, at fixed exposure so brightness compares. Run alone: the canopy stays.
		new Scene("benchfast",
			shot("fast_land_on", 200, rayReconstruction(), dlss(6), toggle(() -> Toggle.AUTO_EXPOSURE.value = false), "tp @s 4 95 30 180 8"),
			shot("fast_land_nosun", 150, toggle(() -> Toggle.CACHED_SUN.value = false)),
			shot("fast_land_nogi", 150, toggle(() -> { Toggle.CACHED_SUN.value = true; Toggle.CACHED_GI.value = false; })),
			shot("fast_land_nomerge", 150, toggle(() -> { Toggle.CACHED_GI.value = true; Toggle.MERGED_GEOMETRY.value = false; })),
			shot("fast_land_off", 150, toggle(() -> { Toggle.CACHED_SUN.value = false; Toggle.CACHED_GI.value = false; })),
			shot("fast_far_off", 200, "tp @s 4 110 30 90 2"),
			shot("fast_far_on", 200, toggle(() -> { Toggle.CACHED_SUN.value = true; Toggle.CACHED_GI.value = true; Toggle.MERGED_GEOMETRY.value = true; })),
			shot("fast_far_gi16", 150, toggle(() -> GlowSettings.Limit.CACHED_GI_DISTANCE.value = 16)),
			moving("fast_far_moving", 145, frame -> "tp @s ~-0.1 ~ ~", toggle(() -> GlowSettings.Limit.CACHED_GI_DISTANCE.value = 0)),
			shot("fast_torch_on", 200, "time set 18000", "tp @s -3 123 16 200 15"),
			shot("fast_torch_off", 150, toggle(() -> { Toggle.CACHED_SUN.value = false; Toggle.CACHED_GI.value = false; Toggle.MERGED_GEOMETRY.value = false; })),
			shot("fast_canopy_off", 200, "time set 2500", "fill -16 96 -16 24 110 24 minecraft:oak_leaves replace air", "tp @s 4 95 30 180 8"),
			shot("fast_canopy_on", 200, toggle(() -> { Toggle.CACHED_SUN.value = true; Toggle.CACHED_GI.value = true; Toggle.MERGED_GEOMETRY.value = true; })),
			shot("fast_end", 20, "fill -16 96 -16 24 110 24 minecraft:air replace minecraft:oak_leaves")),
		new Scene("benchcost",
			shot("cost_base", 110, rayReconstruction(), dlss(3), "tp @s 4 95 30 180 8"),
			shot("cost_noshafts", 110, set(Option.LIGHT_SHAFTS, Quality.OFF)),
			shot("cost_noreflect", 110, set(Option.LIGHT_SHAFTS, Quality.HIGH), set(Option.REFLECTIONS, Quality.OFF)),
			shot("cost_nogi", 110, set(Option.REFLECTIONS, Quality.HIGH), set(Option.GLOBAL_ILLUMINATION, Quality.OFF)),
			shot("cost_nosun", 110, set(Option.GLOBAL_ILLUMINATION, Quality.HIGH), set(Option.SUN_SHADOWS, Quality.OFF)),
			shot("cost_nolights", 110, set(Option.SUN_SHADOWS, Quality.HIGH), set(Option.BLOCK_LIGHTS, Quality.OFF)),
			shot("cost_nomaterials", 110, set(Option.BLOCK_LIGHTS, Quality.HIGH), set(Option.MATERIALS, Quality.OFF)),
			shot("cost_nocloud", 110, set(Option.MATERIALS, Quality.HIGH), set(Option.CLOUD_SHADOWS, Quality.OFF)),
			shot("cost_norestirgi", 110, set(Option.CLOUD_SHADOWS, Quality.HIGH), restir(false)),
			shot("cost_norestirdi", 110, restir(true), (Runnable) () -> Toggle.RESTIR_DI.value = false),
			shot("cost_nocache", 110, (Runnable) () -> { Toggle.RESTIR_DI.value = true; Toggle.RADIANCE_CACHE.value = false; }),
			shot("cost_nofog", 110, (Runnable) () -> { Toggle.RADIANCE_CACHE.value = true; Toggle.DISTANCE_FOG.value = false; }),
			shot("cost_allon", 110, (Runnable) () -> Toggle.DISTANCE_FOG.value = true)),
		// The default look against an accumulated path traced reference, from the same views. Compare with imgstat.
		new Scene("ref",
			shot("ref_land_rt", 150, rayReconstruction(), (Runnable) () -> Nvidia.DLSS.value = 6, "tp @s 4 95 30 180 8"),
			shot("ref_land_pt", 420, (Runnable) GlowSettings::resetNvidia, pathTraced(GlowSettings.Preset.HIGH)),
			shot("ref_room_rt", 150, (Runnable) () -> { GlowSettings.pathTracing = false; Nvidia.RAY_RECONSTRUCTION.value = 1; Nvidia.DLSS.value = 6; }, "time set 6000", "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:air", "tp @s 14 123 11 180 10"),
			shot("ref_room_pt", 420, (Runnable) GlowSettings::resetNvidia, pathTraced(GlowSettings.Preset.HIGH)),
			shot("ref_torch_rt", 150, (Runnable) () -> { GlowSettings.pathTracing = false; Nvidia.RAY_RECONSTRUCTION.value = 1; Nvidia.DLSS.value = 6; }, "time set 18000", "tp @s -3 123 16 200 15"),
			shot("ref_torch_pt", 420, (Runnable) GlowSettings::resetNvidia, pathTraced(GlowSettings.Preset.HIGH))),
		new Scene("torch",
			shot("torch_rt", 150, rayReconstruction(), dlss(3), (Runnable) () -> Toggle.AUTO_EXPOSURE.value = false, "time set 18000", "tp @s -3 123 16 200 15"),
			shot("torch_rt_nodi", 120, (Runnable) () -> Toggle.RESTIR_DI.value = false),
			shot("torch_native", 120, (Runnable) () -> { Toggle.RESTIR_DI.value = true; Nvidia.RAY_RECONSTRUCTION.value = 0; Nvidia.DLSS.value = 0; }),
			shot("torch_pt", 420, pathTraced(GlowSettings.Preset.HIGH))),
		// A wall torch and a standing lantern at night, against a stone wall: the light should come from the flame and the glass, not the block centre.
		new Scene("walltorch",
			shot("walltorch_rt", 150, (Runnable) () -> Toggle.AUTO_EXPOSURE.value = false, "time set 18000", "fill 40 140 40 48 140 48 minecraft:stone",
				"fill 40 141 40 48 145 40 minecraft:stone", "setblock 42 142 41 minecraft:wall_torch[facing=south]", "setblock 46 141 41 minecraft:lantern", "tp @s 44 142 45 180 15"),
			shot("walltorch_pt", 420, pathTraced(GlowSettings.Preset.HIGH))),
		// A sealed room at fixed exposure, in both modes under Ray Reconstruction, against converged Path Tracing. reuse_pt should match reuse_ref.
		new Scene("reuse",
			shot("reuse_rt", 200, rayReconstruction(), dlss(6), (Runnable) () -> Toggle.AUTO_EXPOSURE.value = false, "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:air", "tp @s 14 123 11 180 10"),
			shot("reuse_pt", 200, pathTraced(GlowSettings.Preset.MEDIUM)),
			shot("reuse_ref", 800, (Runnable) () -> { Nvidia.RAY_RECONSTRUCTION.value = 0; Nvidia.DLSS.value = 0; })),
		new Scene("presets",
			shot("glow_q_low", 65, HOME, preset(GlowSettings.Preset.LOW)),
			shot("glow_q_medium", 45, preset(GlowSettings.Preset.MEDIUM)),
			shot("glow_q_high", 45, preset(GlowSettings.Preset.HIGH)),
			shot("glow_q_ultra", 45, preset(GlowSettings.Preset.ULTRA)),
			shot("glow_q_off", 45, preset(null))),
		new Scene("motion",
			moving("glow_motion_on", 355, GlowAutotest::villagerStep, "time set 6000", "tp @s -18 124 -32 0 10", "summon minecraft:villager -20 122 -22 {NoAI:1b,NoGravity:1b,Tags:[\"moving\"]}"),
			moving("glow_motion_off", 355, GlowAutotest::villagerStep, (Runnable) () -> GlowRenderer.motionVectors = false)),
		// A sealed room with one roof hole and no lights, so its walls are lit only by bounced sun. This is the hard case for GI.
		new Scene("restir",
			shot("glow_restir_raw_on", 95, set(Option.DENOISER, Quality.OFF), "time set 6000", "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:air", "tp @s 14 123 11 180 10"),
			shot("glow_restir_raw_off", 35, restir(false)),
			shot("glow_restir_on", 65, restir(true), glowDenoiser()),
			shot("glow_restir_off", 55, restir(false))),
		// The same room: the radiance cache adds every bounce after the first, so the walls the floor lights should light the rest.
		new Scene("cache",
			shot("glow_cache_on", 400, "time set 6000", "fill 13 121 5 15 121 11 minecraft:stone", "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:air", "tp @s 14 123 11 180 10"),
			shot("glow_cache_off", 70, toggle(() -> Toggle.RADIANCE_CACHE.value = false)),
			shot("glow_cache_raw_on", 90, set(Option.DENOISER, Quality.OFF), toggle(() -> Toggle.RADIANCE_CACHE.value = true)),
			shot("glow_cache_raw_off", 70, toggle(() -> Toggle.RADIANCE_CACHE.value = false)),
			shot("glow_cache_fixed_exposure", 70, glowDenoiser(), toggle(() -> { Toggle.RADIANCE_CACHE.value = true; Toggle.AUTO_EXPOSURE.value = false; }))),
		// The room sealed at night with a few small lights, for ReSTIR DI. The raw shots show the noise it removes.
		new Scene("di",
			shot("glow_di_raw_on", 95, set(Option.DENOISER, Quality.OFF), "time set 18000", "fill 13 121 5 15 121 11 minecraft:stone", "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:stone",
				"setblock 13 122 6 minecraft:torch", "setblock 15 122 8 minecraft:soul_torch", "setblock 14 122 5 minecraft:lantern", "setblock 15 123 6 minecraft:redstone_torch", "tp @s 14 123 11 180 10"),
			shot("glow_di_raw_off", 45, toggle(() -> Toggle.RESTIR_DI.value = false)),
			shot("glow_di_on", 75, glowDenoiser(), toggle(() -> Toggle.RESTIR_DI.value = true)),
			shot("glow_di_off", 55, toggle(() -> Toggle.RESTIR_DI.value = false))),
		new Scene("sharp",
			shot("glow_sharp_off", 95, glowDenoiser(), dlss(0), "tp @s 4 123 14 180 70"),
			shot("glow_sharp_dlaa", 95, dlss(1)),
			shot("glow_sharp_rr", 95, rayReconstruction()),
			shot("glow_sharp_rr2", 150)),
		new Scene("rr",
			shot("glow_rr_on", 95, rayReconstruction(), HOME),
			shot("glow_rr_off", 45, glowDenoiser()),
			shot("glow_rr_close_on", 85, rayReconstruction(), "tp @s -1 125 22 180 20"),
			shot("glow_rr_close_off", 45, glowDenoiser()),
			moving("glow_rr_moving_on", 145, frame -> "tp @s ~0.1 ~ ~", rayReconstruction()),
			moving("glow_rr_moving_off", 150, frame -> "tp @s ~-0.1 ~ ~", glowDenoiser())),
		// DLSS paths from the Ray Reconstruction view: RR upscaling, Super Resolution after Glow's denoiser, DLAA, then Reflex and Frame Generation.
		new Scene("dlss",
			shot("glow_dlss_rr_quality", 100, HOME, rayReconstruction(), dlss(2)),
			shot("glow_dlss_rr_performance", 95, dlss(4)),
			shot("glow_dlss_sr_quality", 95, glowDenoiser(), dlss(2)),
			shot("glow_dlss_dlaa", 95, dlss(1)),
			shot("glow_dlss_off", 95, dlss(0))),
		new Scene("fg", shot("glow_fg_on", 195, HOME, (Runnable) () -> { Nvidia.REFLEX.value = 1; Nvidia.FRAME_GENERATION.value = 1; })),
		// Ghosting: Ray Reconstruction mid-turn against a still shot of the same final view. The closer the pair, the less the image trails while the camera moves.
		// 1 degree a frame for 90 frames turns from yaw 135 to 225, across the test scene. Balanced is the default at 1440p and has a fractional upscale ratio; Performance is a whole 2.
		new Scene("ghost",
			panning("ghost_pan_balanced", 92, 135.0F, 25.0F, 1.0F, "tp @s 4 128 28 135 25", rayReconstruction(), dlss(3)),
			shot("ghost_still_balanced", 150, looking(225.0F, 25.0F)),
			panning("ghost_pan_performance", 92, 135.0F, 25.0F, 1.0F, looking(135.0F, 25.0F), dlss(4)),
			shot("ghost_still_performance", 150, looking(225.0F, 25.0F)),
			panning("ghost_pan_dlaa", 92, 135.0F, 25.0F, 1.0F, looking(135.0F, 25.0F), dlss(1)),
			shot("ghost_still_dlaa", 150, looking(225.0F, 25.0F)),
			// Fixed exposure, then also without the radiance cache, to tell lighting lag from denoiser lag.
			panning("ghost_pan_fixedexp", 92, 135.0F, 25.0F, 1.0F, looking(135.0F, 25.0F), dlss(3), toggle(() -> Toggle.AUTO_EXPOSURE.value = false)),
			shot("ghost_still_fixedexp", 150, looking(225.0F, 25.0F)),
			panning("ghost_pan_nocache", 92, 135.0F, 25.0F, 1.0F, looking(135.0F, 25.0F), toggle(() -> Toggle.RADIANCE_CACHE.value = false)),
			shot("ghost_still_nocache", 150, looking(225.0F, 25.0F))),
		// A larger pool of its own, with the low morning sun across it.
		// The same sky seen from just above the pool and from under its surface, which should show the same clouds, light and mist.
		new Scene("undersky",
			shot("glow_undersky_above", 90, "fill 30 121 -16 60 135 4 minecraft:air", "fill 29 107 -17 61 120 5 minecraft:stone", "fill 30 109 -16 60 120 4 minecraft:water",
				"time set 1000", "tp @s 45 122 -6 -90 -35"),
			shot("glow_undersky_below", 90, "tp @s 45 119.2 -6 -90 -35"),
			shot("glow_undersky_up", 60, "tp @s 45 119.2 -6 -90 -80"),
			shot("glow_undersky_rr", 120, "tp @s 45 119.2 -6 -90 -35", rayReconstruction()),
			shot("glow_undersky_pt", 90, (Runnable) GlowSettings::resetNvidia, pathTraced(GlowSettings.Preset.MEDIUM))),
		new Scene("water",
			shot("glow_water_glint_on", 80, "fill 30 121 -16 60 135 4 minecraft:air", "fill 29 107 -17 61 120 5 minecraft:stone", "fill 30 108 -16 60 108 4 minecraft:sand",
				"fill 30 109 -16 60 120 4 minecraft:water", "fill 38 109 -8 39 118 -7 minecraft:gold_block", "fill 45 109 -12 46 119 -11 minecraft:mossy_cobblestone",
				"fill 50 109 -2 52 114 0 minecraft:prismarine",
				"time set 1000", "tp @s 26 124 -6 -90 12"),
			shot("glow_water_glint_off", 35, set(Option.WATER, Quality.OFF)),
			shot("glow_water_under_on", 75, set(Option.WATER, Quality.HIGH), "time set 6000", "tp @s 36 118.5 -6 -90 -40"),
			shot("glow_water_under_off", 35, set(Option.WATER, Quality.OFF)),
			// Looking along the pool from inside it, where vanilla's water fog used to cover everything.
			shot("glow_water_inside_on", 75, set(Option.WATER, Quality.HIGH), "tp @s 31 114 -6 -90 5"),
			shot("glow_water_inside_off", 35, set(Option.WATER, Quality.OFF)),
			// Just under the surface, looking slightly up across it toward the far edge, as a swimmer near a shore does.
			shot("glow_water_shore_on", 75, set(Option.WATER, Quality.HIGH), "tp @s 32 118.3 -6 -90 -12"),
			shot("glow_water_shore_off", 35, set(Option.WATER, Quality.OFF)),
			shot("glow_water_off", 65, "tp @s -4 125 3 180 25")),
		// Shadows of two beams across a pool, shallow on one side and deep on the other, with seagrass on the shallow floor, at noon.
		// The floor and seagrass seen through the surface must be as sharp as on land, and the beams' shadows must show on the water.
		new Scene("watershade",
			shot("glow_watershade", 90, "fill 30 121 -16 60 135 4 minecraft:air", "fill 29 107 -17 61 120 5 minecraft:stone",
				"fill 30 116 -16 45 116 4 minecraft:sand", "fill 46 108 -16 60 108 4 minecraft:sand", "fill 30 117 -16 45 120 4 minecraft:water",
				"fill 46 109 -16 60 120 4 minecraft:water", "fill 32 117 -14 38 117 -10 minecraft:seagrass", "fill 40 124 -16 42 124 4 minecraft:stone",
				"fill 52 124 -16 54 124 4 minecraft:stone", "time set 5000", "tp @s 34 129 -6 -90 50"),
			shot("glow_watershade_deep", 75, "tp @s 47 131 -6 -90 70"),
			shot("glow_watershade_under", 75, "tp @s 31 119.5 -6 -90 35"),
			shot("glow_watershade_nomerge", 75, "tp @s 34 129 -6 -90 50", toggle(() -> Toggle.MERGED_GEOMETRY.value = false))),
		// Sand steps rising out of water, for bright block corners and for what's seen through the surface. The sun is high, so inner corners get no direct light of their own.
		new Scene("shore",
			shot("glow_shore_on", 110, "fill 30 121 -16 60 135 4 minecraft:air", "fill 29 107 -17 61 120 5 minecraft:stone", "fill 30 108 -16 60 120 4 minecraft:water",
				"fill 44 108 -16 60 116 4 minecraft:sand", "fill 47 117 -16 60 118 4 minecraft:sand", "fill 50 119 -16 60 120 4 minecraft:sand", "fill 53 121 -16 60 122 4 minecraft:sand", "fill 56 123 -16 60 124 4 minecraft:sand",
				"fill 53 121 -6 55 122 -4 minecraft:air", "fill 56 123 -8 60 124 -6 minecraft:air", "time set 3000", "tp @s 42 124 -6 -90 22"),
			shot("glow_shore_next", 1),
			shot("glow_shore_restir_off", 70, restir(false)),
			shot("glow_shore_cache_off", 70, restir(true), toggle(() -> Toggle.RADIANCE_CACHE.value = false)),
			shot("glow_shore_gi_off", 45, toggle(() -> Toggle.RADIANCE_CACHE.value = true), set(Option.GLOBAL_ILLUMINATION, Quality.OFF)),
			shot("glow_shore_rr", 110, set(Option.GLOBAL_ILLUMINATION, Quality.HIGH), rayReconstruction()),
			shot("glow_shore_rr_next", 1),
			// Water and inner corners the way the game is played: Ray Reconstruction at DLSS Quality. Each corner shot turns off one thing that could be brightening them.
			shot("glow_shore_rr_quality", 110, dlss(2)),
			shot("glow_corner_rr", 110, toggle(() -> Toggle.AUTO_EXPOSURE.value = false), "tp @s 45 123 -2 -100 15"),
			shot("glow_corner_rr2", 7),
			shot("glow_corner_rr_restir_off", 80, restir(false)),
			shot("glow_corner_rr_cache_off", 80, restir(true), toggle(() -> Toggle.RADIANCE_CACHE.value = false)),
			shot("glow_corner_rr_gi_off", 80, toggle(() -> Toggle.RADIANCE_CACHE.value = true), set(Option.GLOBAL_ILLUMINATION, Quality.OFF)),
			shot("glow_corner_rr_limits_off", 80, set(Option.GLOBAL_ILLUMINATION, Quality.HIGH), toggle(() -> Toggle.RESTIR_CORNER_LIMITS.value = false)),
			shot("glow_shore_rr_bounce_off", 80, toggle(() -> { Toggle.RESTIR_CORNER_LIMITS.value = true; Toggle.WATER_TRACED_BOUNCE.value = false; }), "tp @s 42 124 -6 -90 22"),
			shot("glow_shore_rr_bounce_on", 80, toggle(() -> Toggle.WATER_TRACED_BOUNCE.value = true)),
			shot("glow_shore_rr_caustic_limit", 80, toggle(() -> Toggle.CAUSTIC_LIMIT.value = true)),
			shot("glow_corner_high", 100, toggle(() -> Toggle.CAUSTIC_LIMIT.value = false), "tp @s 45 123 -2 -100 15", set(Option.GLOBAL_ILLUMINATION, Quality.HIGH), glowDenoiser(), dlss(0)),
			shot("glow_corner_dlaa", 100, dlss(1)),
			moving("glow_shore_rr_moving", 145, frame -> "tp @s ~-0.05 ~ ~", toggle(() -> Toggle.AUTO_EXPOSURE.value = true), rayReconstruction(), dlss(2), "tp @s 42 124 -6 -90 22")),
		// ReSTIR Corner Limits on and off, repeated, because one shot varies a lot. Exposure is fixed so brightness can be compared.
		new Scene("limits",
			shot("glow_limits_on_0", 120, "fill 30 121 -16 60 135 4 minecraft:air", "fill 29 107 -17 61 120 5 minecraft:stone", "fill 30 108 -16 60 120 4 minecraft:water",
				"fill 44 108 -16 60 116 4 minecraft:sand", "fill 47 117 -16 60 118 4 minecraft:sand", "fill 50 119 -16 60 120 4 minecraft:sand", "fill 53 121 -16 60 122 4 minecraft:sand", "fill 56 123 -16 60 124 4 minecraft:sand",
				"fill 53 121 -6 55 122 -4 minecraft:air", "fill 56 123 -8 60 124 -6 minecraft:air", "time set 3000", toggle(() -> Toggle.AUTO_EXPOSURE.value = false), rayReconstruction(), dlss(2), "tp @s 45 123 -2 -100 15"),
			shot("glow_limits_off_0", 60, toggle(() -> Toggle.RESTIR_CORNER_LIMITS.value = false)),
			shot("glow_limits_none_0", 60, toggle(() -> Toggle.RESTIR_GI.value = false)),
			shot("glow_limits_on_1", 60, toggle(() -> { Toggle.RESTIR_GI.value = true; Toggle.RESTIR_CORNER_LIMITS.value = true; })),
			shot("glow_limits_off_1", 60, toggle(() -> Toggle.RESTIR_CORNER_LIMITS.value = false)),
			shot("glow_limits_none_1", 60, toggle(() -> Toggle.RESTIR_GI.value = false)),
			shot("glow_limits_on_2", 60, toggle(() -> { Toggle.RESTIR_GI.value = true; Toggle.RESTIR_CORNER_LIMITS.value = true; })),
			shot("glow_limits_off_2", 60, toggle(() -> Toggle.RESTIR_CORNER_LIMITS.value = false)),
			shot("glow_limits_none_2", 60, toggle(() -> Toggle.RESTIR_GI.value = false))),
		// The same textures with and without the -1 LOD bias, from far enough that mipmaps are in use.
		// Ray Limits: shadow and GI distance from the open view, GI bounces in the roof-hole room, reflection bounces between facing iron walls.
		new Scene("reach",
			shot("glow_reach_auto", 120, HOME, "time set 6000", toggle(() -> Toggle.AUTO_EXPOSURE.value = false)),
			shot("glow_reach_shadow16", 50, toggle(() -> GlowSettings.Limit.SHADOW_DISTANCE.value = 16)),
			shot("glow_reach_gi16", 50, toggle(() -> { GlowSettings.Limit.SHADOW_DISTANCE.value = 0; GlowSettings.Limit.GI_DISTANCE.value = 16; })),
			shot("glow_reach_refl16", 50, toggle(() -> { GlowSettings.Limit.GI_DISTANCE.value = 0; GlowSettings.Limit.REFLECTION_DISTANCE.value = 16; })),
			shot("glow_reach_room_b1", 200, toggle(() -> GlowSettings.Limit.REFLECTION_DISTANCE.value = 0), "fill 13 121 5 15 121 11 minecraft:stone", "fill 13 121 12 15 124 12 minecraft:stone", "fill 13 122 5 15 124 11 minecraft:air", "setblock 14 125 7 minecraft:air", "tp @s 14 123 11 180 10"),
			shot("glow_reach_room_b4", 120, toggle(() -> GlowSettings.Limit.GI_BOUNCES.value = 4)),
			shot("glow_reach_room_b8", 120, toggle(() -> GlowSettings.Limit.GI_BOUNCES.value = 8)),
			shot("glow_reach_room_b1again", 120, toggle(() -> GlowSettings.Limit.GI_BOUNCES.value = 1)),
			shot("glow_reach_nocache_b1", 120, toggle(() -> { Toggle.RADIANCE_CACHE.value = false; GlowSettings.Limit.GI_BOUNCES.value = 1; })),
			shot("glow_reach_nocache_b4", 120, toggle(() -> GlowSettings.Limit.GI_BOUNCES.value = 4)),
			shot("glow_reach_nocache_b8", 120, toggle(() -> GlowSettings.Limit.GI_BOUNCES.value = 8)),
			shot("glow_reach_mirror_r1", 90, toggle(() -> { Toggle.RADIANCE_CACHE.value = true; GlowSettings.Limit.GI_BOUNCES.value = 1; }), "fill 10 121 2 18 130 2 minecraft:iron_block", "fill 10 121 14 18 130 14 minecraft:iron_block", "fill 11 121 3 17 130 13 minecraft:air", "fill 10 120 2 18 120 14 minecraft:stone", "tp @s 14 124 11 180 5"),
			shot("glow_reach_mirror_r4", 120, toggle(() -> GlowSettings.Limit.REFLECTION_BOUNCES.value = 4))),
		new Scene("mip",
			shot("glow_mip_on", 100, toggle(() -> Toggle.AUTO_EXPOSURE.value = false), glowDenoiser(), dlss(1), "tp @s 4 175 14 180 50"),
			shot("glow_mip_off", 100, toggle(() -> Toggle.DLSS_LOD_BIAS.value = false)),
			shot("glow_mip_nodlss", 60, toggle(() -> { Toggle.DLSS_LOD_BIAS.value = true; Toggle.AUTO_EXPOSURE.value = true; }), dlss(0))),
		// Last, because it paves over the test scene.
		new Scene("parallax", shot("glow_ring", 75, "fill -40 121 -40 40 121 40 minecraft:stone", "tp @s 0 140 0 0 60"), shot("glow_ring_off", 45, set(Option.PARALLAX, Quality.OFF)),
			// Early morning across the same floor, low over it, where a ring at the parallax distance showed.
			shot("glow_ring_dawn", 90, set(Option.PARALLAX, Quality.HIGH), "time set 200", "tp @s 0 123 0 0 12"),
			shot("glow_ring_dawn_off", 60, set(Option.PARALLAX, Quality.OFF)))
	);

	private static final Map<Integer, Runnable> SCHEDULE = new HashMap<>();
	private static boolean started;
	private static int frames;
	private static Runnable pendingShot;

	static {
		if (ENABLED) schedule();
	}

	private GlowAutotest() {}

	public static void onTitleScreen(TitleScreen screen) {
		if (!ENABLED || started) return;
		started = true;
		Minecraft minecraft = Minecraft.getInstance();
		// The test never sends input, so vanilla's AFK limit would hold it at 30 fps and hide real frame times.
		minecraft.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		minecraft.execute(() -> minecraft.createWorldOpenFlows().createFreshLevel(
			"glow_autotest_" + System.currentTimeMillis(),
			new LevelSettings("Glow Autotest", GameType.SPECTATOR, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
			new WorldOptions(20261105L, true, false),
			WorldPresets::createNormalWorldDimensions,
			screen
		));
	}

	public static void onFrame() {
		if (!ENABLED) return;
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.nanoTime();
		if (lastFrameNanos != 0L) FRAME_NANOS[frameNanosCursor++ % FRAME_NANOS.length] = now - lastFrameNanos;
		lastFrameNanos = now;
		if (minecraft.player == null || minecraft.gui.screen() != null) return;
		Runnable action = SCHEDULE.get(++frames);
		if (action != null) action.run();
	}

	/** Turns the scene list into absolute frame numbers once, so onFrame is a lookup. */
	private static void schedule() {
		Set<String> groups = FILTER.isBlank() || FILTER.equals("1") || FILTER.equalsIgnoreCase("all") ? null : Set.of(FILTER.toLowerCase().split("\\s*,\\s*"));
		at(SETUP_FRAME, () -> Arrays.stream(SETUP).forEach(GlowAutotest::command));
		int frame = SETUP_FRAME + SETTLE;
		for (Scene scene : SCENES) {
			if (groups != null && !groups.contains(scene.group())) continue;
			at(frame, GlowAutotest::reset);
			for (Shot shot : scene.shots()) {
				at(frame, () -> {
					for (Object change : shot.changes()) {
						if (change instanceof Runnable runnable) runnable.run();
						else command((String) change);
					}
				});
				for (int f = frame + 1; shot.everyFrame() != null && f <= frame + shot.delay(); f++) {
					int step = f - frame;
					at(f, () -> shot.everyFrame().accept(step));
				}
				for (int f = frame + 1; shot.motion() != null && f < frame + shot.delay(); f++) {
					if (f % 9 != 0) continue;
					int step = f - frame;
					at(f, () -> command(shot.motion().apply(step)));
				}
				frame += shot.delay();
				at(frame, () -> shoot(shot.name() + ".png"));
				frame += GAP;
			}
		}
		at(frame, () -> {
			GlowRenderer.LOGGER.info("Autotest finished");
			Minecraft.getInstance().stop();
		});
	}

	private static void at(int frame, Runnable action) {
		SCHEDULE.merge(frame, action, (first, second) -> () -> { first.run(); second.run(); });
	}

	private static void reset() {
		GlowSettings.resetToDefaults();
		GlowSettings.resetNvidia();
		GlowRenderer.enabled = GlowRenderer.motionVectors = true;
		GlowSettings.pathTracing = false;
		GlowSettings.resetPathTracing();
		GlowSettings.resetToDefaults();
		command("time set 2500");
	}

	private static Shot shot(String name, int delay, Object... changes) {
		return new Shot(name, delay, null, null, changes);
	}

	private static Shot moving(String name, int delay, IntFunction<String> motion, Object... changes) {
		return new Shot(name, delay, motion, null, changes);
	}

	private static final int PAN_HOLD = 2;

	/** Turns the camera smoothly, the same angle every frame whatever the frame rate, so motion shots can be compared with a still shot at the same final view. */
	private static Shot panning(String name, int delay, float startYaw, float pitch, float degreesPerFrame, Object... changes) {
		// The last few frames hold the final view, because the screenshot can come from the frame before the last turn.
		int turning = delay - PAN_HOLD;
		return new Shot(name, delay, null, frame -> look(startYaw + degreesPerFrame * Math.min(frame, turning), pitch), changes);
	}

	/** Points the camera exactly, with no interpolation from the last tick. */
	private static Runnable looking(float yaw, float pitch) {
		return () -> look(yaw, pitch);
	}

	private static void look(float yaw, float pitch) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
	}

	private static String villagerStep(int frame) {
		return "execute as @e[tag=moving] at @s run tp @s ~" + (frame / 180 % 2 == 0 ? "0.4" : "-0.4") + " ~ ~";
	}

	private static Runnable set(Option option, Quality quality) {
		return () -> option.value = quality;
	}

	/** A preset, or every option off for null. */
	private static Runnable preset(GlowSettings.Preset preset) {
		return () -> {
			if (preset != null) GlowSettings.applyPreset(preset);
			else for (Option option : Option.values()) option.value = Quality.OFF;
		};
	}

	private static Runnable toggle(Runnable change) {
		return change;
	}

	private static Runnable restir(boolean on) {
		return () -> Toggle.RESTIR_GI.value = on;
	}

	private static Runnable rayReconstruction() {
		return () -> Nvidia.RAY_RECONSTRUCTION.value = 1;
	}

	private static Runnable glowDenoiser() {
		return () -> {
			Nvidia.RAY_RECONSTRUCTION.value = 0;
			Option.DENOISER.value = Quality.HIGH;
		};
	}

	private static Runnable dlss(int mode) {
		return () -> Nvidia.DLSS.value = mode;
	}

	private static void command(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
	}

	/** Runs once the world and hand have drawn, and before the interface, so a shot shows everything. Frame callbacks fire mid-world, before the hand. */
	public static void afterWorld() {
		if (pendingShot == null) return;
		Runnable shot = pendingShot;
		pendingShot = null;
		shot.run();
	}

	private static final long[] FRAME_NANOS = new long[90];
	private static int frameNanosCursor;
	private static long lastFrameNanos;

	/** Wall clock time per frame over the last 90 frames, which the GPU timers alone can't show. */
	private static String frameTimes() {
		long[] sorted = FRAME_NANOS.clone();
		Arrays.sort(sorted);
		return String.format(java.util.Locale.ROOT, "frame=%.2fms p99=%.2fms", Arrays.stream(sorted).average().orElse(0.0) / 1.0E6, sorted[sorted.length - 2] / 1.0E6);
	}

	private static void shoot(String name) {
		Minecraft minecraft = Minecraft.getInstance();
		pendingShot = (() -> {
			GlowRenderer.LOGGER.info("Autotest shot {}: fps={} meshes={} instances={} {}", name, minecraft.getFps(), GlowRenderer.meshCount(), GlowRenderer.lastInstances, GlowRenderer.timings() + " " + frameTimes()); GlowRenderer.LOGGER.info("Autotest player at {} mode {}", minecraft.player.position(), minecraft.gameMode.getPlayerMode());
			Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1, message -> {});
		});
	}
}
