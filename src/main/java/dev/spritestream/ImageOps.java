package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/** CPU-side resampling of RGBA8 images held in native memory. */
public final class ImageOps {
	/** {@code -Dspritestream.debug=plainspecular}: the old plain-average specular mips, for before/after comparisons. */
	static final boolean PLAIN_SPECULAR = SpriteStreamMod.DEBUG.contains("plainspecular");

	private ImageOps() {
	}

	/**
	 * Box-filters {@code src} (RGBA8, {@code sw}x{@code sh}) down to {@code dw}x{@code dh} at {@code dst}.
	 * Colour is averaged alpha-weighted so cut-out edges do not pick up dark fringes.
	 */
	public static void downsample(final long src, final int sw, final int sh, final long dst, final int dw, final int dh) {
		for (int y = 0; y < dh; y++) {
			int y0 = (int) ((long) y * sh / dh);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / dh));
			for (int x = 0; x < dw; x++) {
				int x0 = (int) ((long) x * sw / dw);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / dw));
				long r = 0, g = 0, b = 0, a = 0;
				int n = 0;
				for (int sy = y0; sy < y1; sy++) {
					long row = src + ((long) sy * sw + x0) * 4L;
					for (int sx = x0; sx < x1; sx++, row += 4) {
						int pa = MemoryUtil.memGetByte(row + 3) & 0xFF;
						r += (MemoryUtil.memGetByte(row) & 0xFF) * pa;
						g += (MemoryUtil.memGetByte(row + 1) & 0xFF) * pa;
						b += (MemoryUtil.memGetByte(row + 2) & 0xFF) * pa;
						a += pa;
						n++;
					}
				}
				long out = dst + ((long) y * dw + x) * 4L;
				if (a == 0) {
					MemoryUtil.memPutInt(out, 0);
				} else {
					MemoryUtil.memPutByte(out, (byte) (r / a));
					MemoryUtil.memPutByte(out + 1, (byte) (g / a));
					MemoryUtil.memPutByte(out + 2, (byte) (b / a));
					MemoryUtil.memPutByte(out + 3, (byte) (a / n));
				}
			}
		}
	}

	/** Box filter that averages every channel on its own; for data maps whose alpha is not coverage. */
	public static void downsamplePlain(final long src, final int sw, final int sh, final long dst, final int dw, final int dh) {
		for (int y = 0; y < dh; y++) {
			int y0 = (int) ((long) y * sh / dh);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / dh));
			for (int x = 0; x < dw; x++) {
				int x0 = (int) ((long) x * sw / dw);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / dw));
				long r = 0, g = 0, b = 0, a = 0;
				int n = 0;
				for (int sy = y0; sy < y1; sy++) {
					long row = src + ((long) sy * sw + x0) * 4L;
					for (int sx = x0; sx < x1; sx++, row += 4) {
						r += MemoryUtil.memGetByte(row) & 0xFF;
						g += MemoryUtil.memGetByte(row + 1) & 0xFF;
						b += MemoryUtil.memGetByte(row + 2) & 0xFF;
						a += MemoryUtil.memGetByte(row + 3) & 0xFF;
						n++;
					}
				}
				long out = dst + ((long) y * dw + x) * 4L;
				MemoryUtil.memPutByte(out, (byte) (r / n));
				MemoryUtil.memPutByte(out + 1, (byte) (g / n));
				MemoryUtil.memPutByte(out + 2, (byte) (b / n));
				MemoryUtil.memPutByte(out + 3, (byte) (a / n));
			}
		}
	}

	/** Returns a new image scaled so that its larger side is at most {@code maxSide}; the source is not closed. */
	/**
	 * Box filter for LabPBR specular maps, whose channels are not all plain quantities:
	 * <ul>
	 * <li>G below 230 is linear F0, 230-255 are IDs of predefined metals. A block that is mostly metal keeps its
	 * most common metal ID; otherwise only the non-metal texels are averaged, so the result is never a made-up
	 * value between an F0 and an ID.</li>
	 * <li>A is emission (1-254; 255 and 0 mean "none"). See {@link #emission}: sparse emissive patterns keep
	 * glowing at a distance instead of averaging away, and texels with no emission never start to glow.</li>
	 * <li>R (smoothness) and B (porosity / subsurface) are averaged.</li>
	 * </ul>
	 */
	public static void downsampleSpecular(final long src, final int sw, final int sh, final long dst, final int dw, final int dh) {
		int[] metalCounts = new int[26];
		for (int y = 0; y < dh; y++) {
			int y0 = (int) ((long) y * sh / dh);
			int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / dh));
			for (int x = 0; x < dw; x++) {
				int x0 = (int) ((long) x * sw / dw);
				int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / dw));
				long r = 0, b = 0, emission = 0, f0 = 0;
				int n = 0, dielectrics = 0, metals = 0, emissive = 0;
				java.util.Arrays.fill(metalCounts, 0);
				for (int sy = y0; sy < y1; sy++) {
					long row = src + ((long) sy * sw + x0) * 4L;
					for (int sx = x0; sx < x1; sx++, row += 4) {
						r += MemoryUtil.memGetByte(row) & 0xFF;
						int g = MemoryUtil.memGetByte(row + 1) & 0xFF;
						b += MemoryUtil.memGetByte(row + 2) & 0xFF;
						int a = MemoryUtil.memGetByte(row + 3) & 0xFF;
						if (a != 255 && a != 0) {
							emission += a;
							emissive++;
						}
						if (g >= 230) {
							metalCounts[g - 230]++;
							metals++;
						} else {
							f0 += g;
							dielectrics++;
						}
						n++;
					}
				}
				int g;
				if (metals > dielectrics) {
					int best = 0;
					for (int i = 1; i < metalCounts.length; i++) {
						if (metalCounts[i] > metalCounts[best]) {
							best = i;
						}
					}
					g = 230 + best;
				} else {
					g = (int) (f0 / dielectrics);
				}
				int a = emission(emission, emissive, n);
				long out = dst + ((long) y * dw + x) * 4L;
				MemoryUtil.memPutByte(out, (byte) (r / n));
				MemoryUtil.memPutByte(out + 1, (byte) g);
				MemoryUtil.memPutByte(out + 2, (byte) (b / n));
				MemoryUtil.memPutByte(out + 3, (byte) a);
			}
		}
	}

	/**
	 * LabPBR emission of a texel made from {@code total} source texels, {@code emissive} of which glow with
	 * {@code sum} combined strength: the average of the emissive texels only. Averaging the glow with the
	 * surrounding non-emissive texels (ore stone, the dark parts of a redstone block) dims a small pattern so much
	 * that it stops glowing at a distance, where the base texture is used; counting "none" (255) as emission made
	 * the whole block glow. A texel with no emissive source texel stays at "none".
	 */
	public static int emission(final long sum, final int emissive, final int total) {
		if (emissive == 0) {
			return 255;
		}
		return (int) Math.clamp(Math.round((double) sum / emissive), 1, 254);
	}

	public static NativeImage shrinkTo(final NativeImage source, final int maxSide) {
		int w = source.getWidth();
		int h = source.getHeight();
		int largest = Math.max(w, h);
		int dw = Math.max(1, (int) ((long) w * maxSide / largest));
		int dh = Math.max(1, (int) ((long) h * maxSide / largest));
		NativeImage result = new NativeImage(dw, dh, false);
		downsample(source.getPointer(), w, h, result.getPointer(), dw, dh);
		return result;
	}

	/** Same as {@link #shrinkTo} but keeps whole animation frames: each frame is scaled by the same factor. */
	public static NativeImage shrinkFrames(final NativeImage source, final int frameW, final int frameH, final int dFrameW, final int dFrameH) {
		int cols = source.getWidth() / frameW;
		int rows = source.getHeight() / frameH;
		NativeImage result = new NativeImage(cols * dFrameW, rows * dFrameH, false);
		NativeImage frame = new NativeImage(frameW, frameH, false);
		NativeImage small = new NativeImage(dFrameW, dFrameH, false);
		try {
			for (int fy = 0; fy < rows; fy++) {
				for (int fx = 0; fx < cols; fx++) {
					source.copyRect(frame, fx * frameW, fy * frameH, 0, 0, frameW, frameH, false, false);
					downsample(frame.getPointer(), frameW, frameH, small.getPointer(), dFrameW, dFrameH);
					small.copyRect(result, 0, 0, fx * dFrameW, fy * dFrameH, dFrameW, dFrameH, false, false);
				}
			}
		} finally {
			frame.close();
			small.close();
		}
		return result;
	}
}
