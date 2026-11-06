package dev.spritestream;

import org.jspecify.annotations.Nullable;

/**
 * Size of the pool page for a VRAM budget: the largest page (and largest block resolution) whose planes,
 * with their mip chains, fit the budget and the GPU's maximum texture size.
 */
public record PoolLayout(int width, int height, int maxResolution) {
	/**
	 * @return the layout, or null when not even one block of the smallest pooled size ({@link BlockGrid#CELL})
	 *     above the base resolution fits
	 */
	public static @Nullable PoolLayout compute(
		final long budgetBytes, final int planeCount, final int wantedMax, final int baseResolution, final int maxTexture
	) {
		int maxResolution = Math.min(wantedMax, Integer.highestOneBit(maxTexture));
		// Mips of a page add a third on top of level 0.
		long texelsPerPlane = budgetBytes * 3L / (planeCount * 4L * 4L);
		while (maxResolution >= BlockGrid.CELL && (long) maxResolution * maxResolution > texelsPerPlane) {
			maxResolution >>= 1;
		}
		if (maxResolution < BlockGrid.CELL || maxResolution <= baseResolution) {
			return null;
		}
		int side = (int) Math.min(maxTexture, Math.sqrt((double) texelsPerPlane));
		int width = Math.max(maxResolution, side / maxResolution * maxResolution);
		int height = (int) Math.min(maxTexture, texelsPerPlane / width);
		height = Math.max(maxResolution, height / maxResolution * maxResolution);
		return new PoolLayout(width, height, maxResolution);
	}

	/** Mip levels of the page: from the largest block down to the base resolution. */
	public int mipLevels(final int baseResolution) {
		return Integer.numberOfTrailingZeros(this.maxResolution) - Integer.numberOfTrailingZeros(baseResolution) + 1;
	}

	/** Bytes the page takes per plane, mips included. */
	public long bytesPerPlane(final int baseResolution) {
		long bytes = 0;
		for (int level = 0; level < this.mipLevels(baseResolution); level++) {
			bytes += (long) Math.max(1, this.width >> level) * Math.max(1, this.height >> level) * 4L;
		}
		return bytes;
	}
}
