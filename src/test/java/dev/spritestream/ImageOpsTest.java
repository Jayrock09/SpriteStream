package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/** Downsampling of colour and LabPBR data maps. */
class ImageOpsTest {
	/** Downsamples a 2x2 image (texels as {r, g, b, a}) to 1x1 with the given filter. */
	private interface Filter {
		void apply(long src, int sw, int sh, long dst, int dw, int dh);
	}

	private static int[] reduce(final Filter filter, final int[]... texels) {
		ByteBuffer src = MemoryUtil.memAlloc(texels.length * 4);
		ByteBuffer dst = MemoryUtil.memAlloc(4);
		try {
			for (int[] texel : texels) {
				for (int channel : texel) {
					src.put((byte) channel);
				}
			}
			src.flip();
			filter.apply(MemoryUtil.memAddress(src), 2, texels.length / 2, MemoryUtil.memAddress(dst), 1, 1);
			return new int[] {dst.get(0) & 0xFF, dst.get(1) & 0xFF, dst.get(2) & 0xFF, dst.get(3) & 0xFF};
		} finally {
			MemoryUtil.memFree(src);
			MemoryUtil.memFree(dst);
		}
	}

	@Test
	void noEmissionStaysNoEmission() {
		int[] out = reduce(ImageOps::downsampleSpecular, new int[] {100, 10, 0, 255}, new int[] {120, 30, 0, 255}, new int[] {140, 10, 0, 255}, new int[] {160, 30, 0, 255});
		assertArrayEquals(new int[] {130, 20, 0, 255}, out);
	}

	@Test
	void emissionIsAveragedAgainstZeroNotAgainst255() {
		// One fully emissive texel (254) among three "none" (255): a quarter of the glow, not almost full glow.
		int[] out = reduce(ImageOps::downsampleSpecular, new int[] {0, 0, 0, 254}, new int[] {0, 0, 0, 255}, new int[] {0, 0, 0, 255}, new int[] {0, 0, 0, 255});
		assertArrayEquals(new int[] {0, 0, 0, 63}, out);
	}

	@Test
	void mostlyMetalKeepsItsMetalId() {
		// Three gold (231) and one iron (230) texel next to nothing else: gold wins, no in-between value.
		int[] out = reduce(ImageOps::downsampleSpecular, new int[] {200, 231, 0, 255}, new int[] {200, 231, 0, 255}, new int[] {200, 230, 0, 255}, new int[] {200, 231, 0, 255});
		assertArrayEquals(new int[] {200, 231, 0, 255}, out);
	}

	@Test
	void mostlyDielectricAveragesOnlyTheF0Values() {
		int[] out = reduce(ImageOps::downsampleSpecular, new int[] {0, 10, 0, 255}, new int[] {0, 20, 0, 255}, new int[] {0, 30, 0, 255}, new int[] {0, 240, 0, 255});
		assertArrayEquals(new int[] {0, 20, 0, 255}, out);
	}

	@Test
	void plainFilterAveragesEveryChannel() {
		int[] out = reduce(ImageOps::downsamplePlain, new int[] {0, 0, 0, 255}, new int[] {100, 200, 40, 255}, new int[] {0, 0, 0, 1}, new int[] {100, 200, 40, 1});
		assertArrayEquals(new int[] {50, 100, 20, 128}, out);
	}

	@Test
	void colourFilterIgnoresTransparentTexels() {
		// Fully transparent black texels must not darken the visible ones.
		int[] out = reduce(ImageOps::downsample, new int[] {200, 100, 50, 255}, new int[] {0, 0, 0, 0}, new int[] {200, 100, 50, 255}, new int[] {0, 0, 0, 0});
		assertArrayEquals(new int[] {200, 100, 50, 127}, out);
	}
}
