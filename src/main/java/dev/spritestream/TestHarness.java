package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development-only visual check, enabled with {@code -Dspritestream.test=true}. Once in a world it looks
 * at the ground, saves one screenshot with streaming and one forced to the base atlas, then quits.
 */
final class TestHarness {
	static final boolean ENABLED = Boolean.getBoolean("spritestream.test");
	private static long startNanos = -1;
	private static int step;
	private static float lockedYaw = Float.NaN;

	private TestHarness() {
	}

	static void frame(final Minecraft minecraft) {
		if (!ENABLED || minecraft.player == null || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		if (startNanos < 0) {
			startNanos = now;
		}
		float seconds = (now - startNanos) / 1e9F;
		File dir = new File(minecraft.gameDirectory, "spritestream-test");
		if (Float.isNaN(lockedYaw)) {
			lockedYaw = minecraft.player.getYRot();
		}
		// Hold the view still every frame so the A/B screenshots show the same pixels.
		minecraft.player.setYRot(lockedYaw);
		minecraft.player.setXRot(step <= 2 ? 55.0F : 8.0F);

		if (step == 0 && seconds > 5.0F) {
			step++;
		} else if (step == 1 && seconds > 25.0F) {
			save(minecraft, new File(dir, "streamed.png"));
			StreamManager.forceBase = true;
			step++;
		} else if (step == 2 && seconds > 27.0F) {
			save(minecraft, new File(dir, "base.png"));
			StreamManager.forceBase = false;
			step++;
		} else if (step == 3 && seconds > 28.0F) {
			// Zoom check: look toward the horizon at normal FOV, then narrow the FOV like a zoom key would.
			step++;
		} else if (step == 4 && seconds > 40.0F) {
			StreamManager.get().logUsage("normal FOV");
			save(minecraft, new File(dir, "far-normal.png"));
			minecraft.options.fov().set(30);
			step++;
		} else if (step == 5 && seconds > 55.0F) {
			StreamManager.get().logUsage("FOV 30");
			save(minecraft, new File(dir, "far-zoomed.png"));
			StreamManager.forceBase = true;
			step++;
		} else if (step == 6 && seconds > 57.0F) {
			save(minecraft, new File(dir, "far-zoomed-base.png"));
			StreamManager.forceBase = false;
			minecraft.options.fov().set(70);
			step++;
		} else if (step == 7 && seconds > 59.0F) {
			SpriteStreamMod.LOGGER.info("[SpriteStream] Test screenshots written to {}", dir.getAbsolutePath());
			step++;
			minecraft.stop();
		}
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
