package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Development-only visual check, enabled with {@code -Dspritestream.test=true}. Once the world renders it
 * builds a brick wall and a jungle-log wall in front of the player, then saves each view twice: streamed and
 * forced to the base atlas. Brick uses connected/random variants in many packs; the log wall is seen at a
 * grazing angle, where block-edge artifacts show up.
 */
final class TestHarness {
	static final boolean ENABLED = Boolean.getBoolean("spritestream.test");
	private static long startNanos = -1;
	private static int step;
	private static BlockPos origin;
	private static float yaw;
	private static float pitch;

	private TestHarness() {
	}

	static void frame(final Minecraft minecraft) {
		if (!ENABLED || minecraft.player == null || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		if (startNanos < 0) {
			if (minecraft.gui.screen() != null) {
				return; // still on a loading screen
			}
			startNanos = now;
			origin = minecraft.player.blockPosition();
		}
		float seconds = (now - startNanos) / 1e9F;
		File dir = new File(minecraft.gameDirectory, "spritestream-test");
		if (step >= 2) {
			minecraft.player.setYRot(yaw);
			minecraft.player.setXRot(pitch);
		}

		switch (step) {
			case 0 -> {
				if (seconds > 3.0F) {
					buildScene(minecraft);
					step++;
				}
			}
			case 1 -> {
				if (seconds > 6.0F) {
					look(-4.0, 1.0, 3.0); // bricks, nearly head-on
					step++;
				}
			}
			case 2 -> {
				if (seconds > 20.0F) {
					StreamManager.get().logUsage("bricks");
					save(minecraft, new File(dir, "bricks-streamed.png"));
					if (SpriteStreamMod.DEBUG.contains("dump")) {
						StreamManager.get().debugDump("brick", new File(dir, "pool-dump"));
					}
					StreamManager.forceBase = true;
					step++;
				}
			}
			case 3 -> {
				if (seconds > 22.0F) {
					save(minecraft, new File(dir, "bricks-base.png"));
					StreamManager.forceBase = false;
					look(9.0, 1.5, 3.0); // jungle logs at a grazing angle
					step++;
				}
			}
			case 4 -> {
				if (seconds > 36.0F) {
					StreamManager.get().logUsage("logs");
					save(minecraft, new File(dir, "logs-streamed.png"));
					StreamManager.forceBase = true;
					step++;
				}
			}
			case 5 -> {
				if (seconds > 38.0F) {
					save(minecraft, new File(dir, "logs-base.png"));
					StreamManager.forceBase = false;
					step++;
				}
			}
			case 6 -> {
				if (seconds > 40.0F) {
					SpriteStreamMod.LOGGER.info("[SpriteStream] Test screenshots written to {}", dir.getAbsolutePath());
					step++;
					minecraft.stop();
				}
			}
			default -> {
			}
		}
	}

	/** Bricks on the left, jungle logs on the right, three blocks ahead (south), with clear air in between. */
	private static void buildScene(final Minecraft minecraft) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		BlockPos base = origin;
		server.execute(() -> {
			ServerLevel level = server.getLevel(minecraft.level.dimension());
			if (level == null) {
				return;
			}
			// Noon, frozen, so runs are comparable.
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time pause");
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int x = -9; x <= 11; x++) {
				for (int y = 0; y <= 4; y++) {
					for (int z = 1; z <= 2; z++) {
						level.setBlock(base.offset(x, y, z), air, 3);
					}
					BlockState wall = x < 0 ? Blocks.BRICKS.defaultBlockState() : Blocks.JUNGLE_LOG.defaultBlockState();
					level.setBlock(base.offset(x, y - 1, 3), wall, 3);
				}
			}
		});
	}

	/** Points the camera at an offset from the player's feet (x east, y up, z south). */
	private static void look(final double dx, final double dy, final double dz) {
		double eye = 1.62;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		pitch = (float) -Math.toDegrees(Math.atan2(dy - eye, horizontal));
	}

	private static void save(final Minecraft minecraft, final File file) {
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
