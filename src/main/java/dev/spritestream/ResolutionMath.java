package dev.spritestream;

/** Pure resolution decisions used by the streamer. */
public final class ResolutionMath {
	private ResolutionMath() {
	}

	/**
	 * The pool resolution a sprite should get for a measured need of {@code texelsPerBlock}: the next power of
	 * two at or above the need, at least one pool block, at most what the sprite's source and the pool hold.
	 * Returns 0 when the base atlas already has enough detail.
	 */
	public static int target(final float texelsPerBlock, final int baseResolution, final int spriteMax, final int poolMax) {
		if (texelsPerBlock <= baseResolution) {
			return 0;
		}
		int needed = (int) Math.ceil(texelsPerBlock);
		int resolution = needed <= 1 ? 1 : Integer.highestOneBit(needed - 1) << 1;
		resolution = Math.max(BlockGrid.CELL, resolution);
		return Math.min(resolution, Math.min(spriteMax, poolMax));
	}

	/**
	 * Fits targets into the pool in priority order: each sprite gets its target, or the largest smaller
	 * resolution that still fits once the higher-priority sprites are placed, or 0 (base) when nothing fits.
	 *
	 * @param targets resolutions in priority order (0 entries are skipped)
	 * @param capacityCells pool cells available
	 * @return the resolution each sprite gets, in the same order
	 */
	public static int[] fit(final int[] targets, final long capacityCells) {
		int[] result = new int[targets.length];
		long used = 0;
		for (int i = 0; i < targets.length; i++) {
			int resolution = targets[i];
			if (resolution <= 0) {
				continue;
			}
			while (resolution >= BlockGrid.CELL && used + BlockGrid.cellsFor(resolution) > capacityCells) {
				resolution >>= 1;
			}
			if (resolution < BlockGrid.CELL) {
				continue;
			}
			result[i] = resolution;
			used += BlockGrid.cellsFor(resolution);
		}
		return result;
	}
}
