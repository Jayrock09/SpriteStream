package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/** CPU-side resampling of RGBA8 images held in native memory. */
public final class ImageOps {
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
