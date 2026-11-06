package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Development-only benchmark, enabled with {@code -Dspritestream.bench=<label>} (or {@code survey}). It flies a
 * spectator camera to fixed spots in a fixed-seed world, waits for chunks and streaming to settle, then records
 * every frame time for a fixed period, plus the CPU time SpriteStream spends per frame. Results go to
 * {@code spritestream-bench/<label>/} in the game directory.
 * <ul>
 * <li>{@code survey}: finds the spots (spawn close-up wall, a high landscape view, a village, a forest) and
 * writes them to {@code spritestream-bench/spots.txt}, so later runs use identical coordinates.</li>
 * </ul>
 */
public final class Benchmark {
	static final @Nullable String LABEL = System.getProperty("spritestream.bench");
	public static final boolean ENABLED = LABEL != null && !LABEL.isBlank();
	private static final float WARMUP_SECONDS = Float.parseFloat(System.getProperty("spritestream.benchWarmup", "25"));
	/** {@code -Dspritestream.benchScenes=materials}: emissive and metal blocks near and far, by day and night. */
	private static final String SCENES = System.getProperty("spritestream.benchScenes", "spots");
	private static final float MEASURE_SECONDS = Float.parseFloat(System.getProperty("spritestream.benchMeasure", "20"));

	/** CPU time inside StreamManager#frame, split up, accumulated while measuring. */
	public static long streamNanos;
	public static long probeNanos;
	public static long assignNanos;
	public static long uploadNanos;
	public static long rays;

	private record Spot(String name, double x, double y, double z, float yaw, float pitch) {
	}

	private static long startNanos = -1;
	private static int phase;
	private static int spotIndex;
	private static long phaseStart;
	private static long lastFrame;
	private static final List<Spot> spots = new ArrayList<>();
	private static long[] frameTimes = new long[1 << 16];
	private static int frameCount;
	/** Longest frame while arriving at a spot (reloads, shader toggles and chunk loading happen then). */
	private static long warmupMax;
	private static final List<String> results = new ArrayList<>();

	private Benchmark() {
	}

	private static File dir(final Minecraft minecraft) {
		return new File(minecraft.gameDirectory, "spritestream-bench");
	}

	public static void frame(final Minecraft minecraft) {
		if (!ENABLED || minecraft.player == null || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		if (startNanos < 0) {
			if (minecraft.gui.screen() != null) {
				return;
			}
			startNanos = now;
			phaseStart = now;
		}
		float inPhase = (now - phaseStart) / 1e9F;
		switch (phase) {
			case 0 -> {
				if (inPhase > 3.0F) {
					setUp(minecraft);
					next(now);
				}
			}
			case 1 -> {
				if (inPhase > 2.0F) {
					if ("survey".equals(LABEL)) {
						survey(minecraft);
						phase = 99;
					} else {
						if ("materials".equals(SCENES)) {
							materialSpots(minecraft);
						} else if ("extras".equals(SCENES)) {
							loadSpots(minecraft);
							Spot c = spots.stream().filter(spot -> spot.name().equals("closeup")).findFirst().orElseThrow();
							spots.clear();
							buildExtras(minecraft, BlockPos.containing(c.x(), c.y(), c.z()));
							spots.add(new Spot("ctm", c.x() + 19.0, c.y(), c.z(), 0.0F, 10.0F));
							// 48 blocks from the wall and 20 up, looking at it: too far for more than the base texture
							// unless the spyglass zooms in.
							float pitch = (float) Math.toDegrees(Math.atan2(18.5, 48.0));
							spots.add(new Spot("zoom-off", c.x() - 2.0, c.y() + 20.0, c.z() - 45.0, 0.0F, pitch));
							spots.add(new Spot("zoom-on", c.x() - 2.0, c.y() + 20.0, c.z() - 45.0, 0.0F, pitch));
							spots.add(new Spot("hand", c.x(), c.y(), c.z(), 0.0F, 15.0F));
							spots.add(new Spot("modmenu", c.x(), c.y(), c.z(), 0.0F, 15.0F));
						} else if ("toggles".equals(SCENES)) {
							loadSpots(minecraft);
							rebuildWall(minecraft);
							Spot village = spots.stream().filter(spot -> spot.name().equals("village")).findFirst().orElseThrow();
							spots.removeIf(spot -> !spot.name().equals("closeup"));
							Spot c = spots.getFirst();
							for (String action : new String[] {"after-reload", "shaders-off", "shaders-on"}) {
								spots.add(new Spot(action, c.x(), c.y(), c.z(), c.yaw(), c.pitch()));
							}
							// Turning on the spot at 90 degrees a second, over the village.
							spots.add(new Spot("spin", village.x(), village.y(), village.z(), village.yaw(), village.pitch()));
						} else {
							loadSpots(minecraft);
							rebuildWall(minecraft);
						}
						spotIndex = 0;
						goTo(minecraft, spots.get(0));
						next(now);
					}
				}
			}
			case 2 -> { // warm-up at the current spot
				hold(minecraft);
				if (lastFrame > 0) {
					warmupMax = Math.max(warmupMax, now - lastFrame);
				}
				lastFrame = now;
				if (inPhase > WARMUP_SECONDS) {
					frameCount = 0;
					streamNanos = probeNanos = assignNanos = uploadNanos = rays = 0;
					lastFrame = now;
					next(now);
				}
			}
			case 3 -> { // measuring
				hold(minecraft);
				if (frameCount < frameTimes.length) {
					frameTimes[frameCount++] = now - lastFrame;
				}
				lastFrame = now;
				if (inPhase > MEASURE_SECONDS) {
					Spot spot = spots.get(spotIndex);
					record(minecraft, spot);
					screenshot(minecraft, new File(dir(minecraft), LABEL + "/" + spot.name() + ".png"));
					next(now);
				}
			}
			case 4 -> { // let the screenshot finish, then move on
				hold(minecraft);
				if (inPhase > 1.0F) {
					spotIndex++;
					if (spotIndex < spots.size()) {
						goTo(minecraft, spots.get(spotIndex));
						phase = 2;
						phaseStart = now;
					} else {
						writeResults(minecraft);
						phase = 99;
					}
				}
			}
			case 99 -> {
				phase = 100;
				minecraft.stop();
			}
			default -> {
			}
		}
	}

	private static void next(final long now) {
		phase++;
		phaseStart = now;
	}

	private static void hold(final Minecraft minecraft) {
		Spot spot = spots.get(spotIndex);
		if (spot.name().equals("zoom-on")) {
			minecraft.options.keyUse.setDown(true);
		}
		float yaw = spot.name().equals("spin") ? spot.yaw() + (System.nanoTime() - phaseStart) / 1e9F * 90.0F : spot.yaw();
		minecraft.player.setYRot(yaw);
		minecraft.player.setYHeadRot(yaw);
		minecraft.player.setXRot(spot.pitch());
	}

	private static void command(final MinecraftServer server, final String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}

	private static void setUp(final Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			command(server, "gamemode spectator @p");
			command(server, "time set 6000");
			command(server, "time pause");
			command(server, "weather clear 1000000");
			command(server, "difficulty peaceful");
		});
	}

	private static void goTo(final Minecraft minecraft, final Spot spot) {
		warmupMax = 0;
		lastFrame = 0;
		minecraft.options.keyUse.setDown(spot.name().equals("zoom-on"));
		if (spot.name().equals("zoom-on")) {
			// Start using the spyglass; holding the key keeps it raised.
			net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
		}
		switch (spot.name()) {
			case "zoom-off", "hand" -> holdItem(minecraft, spot.name().equals("hand") ? "diamond_sword" : "spyglass");
			case "modmenu" -> openModMenu(minecraft);
			case "after-reload" -> minecraft.reloadResourcePacks();
			case "shaders-off" -> toggleShaders(minecraft, false);
			case "shaders-on" -> toggleShaders(minecraft, true);
			default -> {
			}
		}
		MinecraftServer server = minecraft.getSingleplayerServer();
		server.execute(() -> {
			command(server, spot.name().startsWith("night") ? "time set 18000" : "time set 6000");
			command(server, String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
		});
	}

	/** Creative and flying, holding {@code item}, so the hand and spyglass work (spectators have neither). */
	private static void holdItem(final Minecraft minecraft, final String item) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		server.execute(() -> {
			command(server, "gamemode creative @p");
			command(server, "item replace entity @p weapon.mainhand with minecraft:" + item);
			var player = server.getPlayerList().getPlayers().getFirst();
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		});
	}

	/** Mod Menu's list, filtered to SpriteStream and with its entry selected, when Mod Menu is installed. */
	private static void openModMenu(final Minecraft minecraft) {
		try {
			Class<?> screenClass = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
			Object screen = screenClass.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null);
			minecraft.gui.setScreen((net.minecraft.client.gui.screens.Screen) screen);
			java.lang.reflect.Field search = screenClass.getDeclaredField("searchBox");
			search.setAccessible(true);
			((net.minecraft.client.gui.components.EditBox) search.get(screen)).setValue("spritestream");
			java.lang.reflect.Field listField = screenClass.getDeclaredField("modList");
			listField.setAccessible(true);
			Object list = listField.get(screen);
			java.util.List<?> entries = (java.util.List<?>) list.getClass().getMethod("children").invoke(list);
			if (!entries.isEmpty()) {
				for (java.lang.reflect.Method m : list.getClass().getMethods()) {
					if (m.getName().equals("setSelected") && m.getParameterCount() == 1) {
						m.invoke(list, entries.getFirst());
						break;
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			SpriteStreamMod.LOGGER.warn("[SpriteStream] Bench could not open Mod Menu", e);
		}
	}

	/** Glass, bookshelves and sandstone (connected textures in Optimum Realism) next to the close-up wall. */
	private static void buildExtras(final Minecraft minecraft, final BlockPos base) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		server.execute(() -> {
			ServerLevel level = server.overworld();
			buildWall(level, base);
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int x = 12; x <= 27; x++) {
				for (int y = 0; y <= 5; y++) {
					for (int z = -1; z <= 2; z++) {
						level.setBlock(base.offset(x, y, z), air, 3);
					}
					BlockState wall = x < 17 ? Blocks.GLASS.defaultBlockState()
						: x < 22 ? Blocks.BOOKSHELF.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState();
					level.setBlock(base.offset(x, y - 1, 3), wall, 3);
				}
			}
		});
	}

	private static void toggleShaders(final Minecraft minecraft, final boolean on) {
		try {
			Class.forName("net.irisshaders.iris.Iris").getMethod("toggleShaders", Minecraft.class, boolean.class).invoke(null, minecraft, on);
		} catch (ReflectiveOperationException | LinkageError e) {
			SpriteStreamMod.LOGGER.warn("[SpriteStream] Bench could not toggle shaders", e);
		}
	}

	/** A platform high above spawn with a row of emissive and metal blocks (LabPBR emission and metal IDs). */
	private static void materialSpots(final Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		BlockPos base = new BlockPos(minecraft.player.blockPosition().getX(), 200, minecraft.player.blockPosition().getZ());
		BlockState[] row = {
			Blocks.GLOWSTONE.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(),
			Blocks.LANTERN.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(),
			Blocks.COPPER_BLOCK.asList().getFirst().defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState(), Blocks.REDSTONE_BLOCK.defaultBlockState(),
			Blocks.STONE_BRICKS.defaultBlockState()
		};
		server.execute(() -> {
			ServerLevel level = server.overworld();
			for (int x = -2; x <= row.length * 2; x++) {
				for (int z = -1; z <= 4; z++) {
					level.setBlock(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
				}
			}
			for (int i = 0; i < row.length; i++) {
				level.setBlock(base.offset(i * 2, 1, 2), row[i], 2);
			}
		});
		double cx = base.getX() + row.length + 0.5;
		for (String time : new String[] {"day", "night"}) {
			spots.add(new Spot(time + "-near", cx, 200.6, base.getZ() - 6.0, 0.0F, 12.0F));
			spots.add(new Spot(time + "-far", cx, 200.6, base.getZ() - 38.0, 0.0F, 2.5F));
		}
	}

	// ---------------------------------------------------------------- survey

	private static void survey(final Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		BlockPos start = minecraft.player.blockPosition();
		server.execute(() -> {
			ServerLevel level = server.overworld();
			List<String> lines = new ArrayList<>();
			int groundY = surface(level, start.getX(), start.getZ());
			BlockPos ground = new BlockPos(start.getX(), groundY, start.getZ());
			buildWall(level, ground);
			// Close-up: a wall of stone bricks, bricks and logs 2.5 blocks ahead (south), filling most of the view.
			lines.add(spot("closeup", ground.getX() + 0.5, groundY + 0.5, ground.getZ() + 0.5, 0.0F, 15.0F));
			// Landscape: 40 blocks above spawn, looking out slightly downwards.
			lines.add(spot("landscape", ground.getX() + 0.5, groundY + 40, ground.getZ() + 0.5, 30.0F, 18.0F));
			BlockPos village = level.findNearestMapStructure(StructureTags.VILLAGE, start, 100, false);
			if (village != null) {
				int vy = surface(level, village.getX(), village.getZ());
				lines.add(spot("village", village.getX() - 20.5, vy + 10, village.getZ() + 0.5, -90.0F, 20.0F));
			}
			var forest = level.findClosestBiome3d(biome -> biome.is(BiomeTags.IS_FOREST), start, 6400, 32, 64);
			if (forest != null) {
				BlockPos f = forest.getFirst();
				int fy = surfaceNoLeaves(level, f.getX(), f.getZ());
				lines.add(spot("forest", f.getX() + 0.5, fy + 1.0, f.getZ() + 0.5, 45.0F, 0.0F));
			}
			File file = new File(dir(minecraft), "spots.txt");
			file.getParentFile().mkdirs();
			try {
				Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
				SpriteStreamMod.LOGGER.info("[SpriteStream] Bench spots written to {}: {}", file, lines);
			} catch (IOException e) {
				SpriteStreamMod.LOGGER.error("[SpriteStream] Could not write {}", file, e);
			}
		});
	}

	private static String spot(final String name, final double x, final double y, final double z, final float yaw, final float pitch) {
		return String.format(Locale.ROOT, "%s %.2f %.2f %.2f %.1f %.1f", name, x, y, z, yaw, pitch);
	}

	private static int surface(final ServerLevel level, final int x, final int z) {
		level.getChunk(x >> 4, z >> 4);
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}

	private static int surfaceNoLeaves(final ServerLevel level, final int x, final int z) {
		level.getChunk(x >> 4, z >> 4);
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/** Same wall as the visual test: stone bricks, bricks and jungle logs, 3 blocks south of the camera. */
	private static void buildWall(final ServerLevel level, final BlockPos base) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = -9; x <= 11; x++) {
			for (int y = 0; y <= 5; y++) {
				for (int z = -1; z <= 2; z++) {
					level.setBlock(base.offset(x, y, z), air, 3);
				}
				BlockState wall = x < -4 ? Blocks.STONE_BRICKS.defaultBlockState()
					: x < 0 ? Blocks.BRICKS.defaultBlockState() : Blocks.JUNGLE_LOG.defaultBlockState();
				level.setBlock(base.offset(x, y - 1, 3), wall, 3);
			}
		}
	}

	/** Each run starts from the pristine world, so the close-up wall is built again where the survey put it. */
	private static void rebuildWall(final Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		for (Spot spot : spots) {
			if (spot.name().equals("closeup")) {
				BlockPos ground = BlockPos.containing(spot.x(), spot.y(), spot.z());
				server.execute(() -> buildWall(server.overworld(), ground));
			}
		}
	}

	private static void loadSpots(final Minecraft minecraft) {
		File file = new File(dir(minecraft), "spots.txt");
		try {
			for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
				String[] p = line.trim().split("\\s+");
				if (p.length == 6) {
					spots.add(new Spot(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
						Float.parseFloat(p[4]), Float.parseFloat(p[5])));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Run the survey first (-Pbench=survey): " + file, e);
		}
		String only = System.getProperty("spritestream.benchSpots");
		if (only != null) {
			List<String> keep = Arrays.asList(only.split(","));
			spots.removeIf(s -> !keep.contains(s.name()));
		}
	}

	// ---------------------------------------------------------------- results

	private static void record(final Minecraft minecraft, final Spot spot) {
		int n = frameCount;
		if (n < 2) {
			return;
		}
		long[] times = Arrays.copyOf(frameTimes, n);
		long total = 0;
		for (long t : times) {
			total += t;
		}
		long[] sorted = times.clone();
		Arrays.sort(sorted);
		int worst = Math.max(1, n / 100);
		long worstTotal = 0;
		for (int i = n - worst; i < n; i++) {
			worstTotal += sorted[i];
		}
		double avgMs = total / 1e6 / n;
		double lowFps = 1e9 / ((double) worstTotal / worst);
		double p50 = sorted[n / 2] / 1e6;
		double p99 = sorted[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
		String line = String.format(
			Locale.ROOT, "%s,%s,%d,%.1f,%.1f,%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%.0f,%s,%.1f",
			LABEL, spot.name(), n, 1000.0 / avgMs, lowFps, avgMs, p50, p99,
			streamNanos / 1e6 / n, probeNanos / 1e6 / n, assignNanos / 1e6 / n, uploadNanos / 1e6 / n, (double) rays / n,
			StreamManager.get().benchUsage(), warmupMax / 1e6
		);
		results.add(line);
		if ("extras".equals(SCENES)) {
			SpriteStreamMod.LOGGER.info("[SpriteStream] BENCH-RESIDENT {} {}", spot.name(),
				StreamManager.get().residentOf("glass", "bookshelf", "sandstone", "stone_bricks", "jungle_log", "block/bricks"));
		}
		SpriteStreamMod.LOGGER.info("[SpriteStream] BENCH {}", line);
		File dir = new File(dir(minecraft), LABEL);
		dir.mkdirs();
		try (PrintWriter out = new PrintWriter(new File(dir, "frametimes-" + spot.name() + ".csv"), StandardCharsets.UTF_8)) {
			out.println("frame,ms");
			for (int i = 0; i < n; i++) {
				out.printf(Locale.ROOT, "%d,%.3f%n", i, times[i] / 1e6);
			}
		} catch (IOException e) {
			SpriteStreamMod.LOGGER.error("[SpriteStream] Could not write frame times", e);
		}
	}

	private static void writeResults(final Minecraft minecraft) {
		File file = new File(dir(minecraft), LABEL + "/results.csv");
		file.getParentFile().mkdirs();
		List<String> lines = new ArrayList<>();
		lines.add("label,scene,frames,avg_fps,low1_fps,avg_ms,p50_ms,p99_ms,ss_cpu_ms,probe_ms,assign_ms,upload_ms,rays_per_frame,pool,warmup_max_ms");
		lines.addAll(results);
		try {
			Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
			SpriteStreamMod.LOGGER.info("[SpriteStream] Bench results written to {}", file);
		} catch (IOException e) {
			SpriteStreamMod.LOGGER.error("[SpriteStream] Could not write {}", file, e);
		}
	}

	private static void screenshot(final Minecraft minecraft, final File file) {
		file.getParentFile().mkdirs();
		Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
			try (image) {
				image.writeToFile(file);
			} catch (IOException e) {
				SpriteStreamMod.LOGGER.error("[SpriteStream] Could not save {}", file, e);
			}
		});
	}
}
