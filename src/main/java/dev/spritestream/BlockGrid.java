package dev.spritestream;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;

/**
 * Occupancy of the pool page: a grid of {@link #CELL}-texel cells, each owned by a sprite id (0 = free).
 * Blocks are power-of-two squares aligned to their own size, so a block either contains another block or
 * does not overlap it at all. Positions are packed as {@code x | y << 16} in texels.
 */
public final class BlockGrid {
	public static final int CELL = 128;

	private final int cols;
	private final int rows;
	private final int[] owners;

	public BlockGrid(final int width, final int height) {
		this.cols = width / CELL;
		this.rows = height / CELL;
		this.owners = new int[this.cols * this.rows];
	}

	public static int cellsFor(final int resolution) {
		int side = resolution / CELL;
		return side * side;
	}

	public static int pack(final int x, final int y) {
		return x | y << 16;
	}

	public static int x(final int packed) {
		return packed & 0xFFFF;
	}

	public static int y(final int packed) {
		return packed >>> 16;
	}

	public int totalCells() {
		return this.owners.length;
	}

	public int usedCells() {
		int used = 0;
		for (int owner : this.owners) {
			if (owner != 0) {
				used++;
			}
		}
		return used;
	}

	/** The first free aligned block of {@code resolution}, or -1 when none is free. */
	public int findFree(final int resolution) {
		int side = resolution / CELL;
		for (int cy = 0; cy + side <= this.rows; cy += side) {
			for (int cx = 0; cx + side <= this.cols; cx += side) {
				if (this.isFree(cx, cy, side)) {
					return pack(cx * CELL, cy * CELL);
				}
			}
		}
		return -1;
	}

	/**
	 * The aligned block of {@code resolution} whose current owners are all evictable and cost the least to
	 * evict in total, or -1 when every block has an owner that must stay.
	 */
	public int findVictimBlock(final int resolution, final IntPredicate evictable, final IntToLongFunction cost) {
		int side = resolution / CELL;
		int best = -1;
		long bestCost = Long.MAX_VALUE;
		int[] seen = new int[side * side];
		for (int cy = 0; cy + side <= this.rows; cy += side) {
			search:
			for (int cx = 0; cx + side <= this.cols; cx += side) {
				int seenCount = 0;
				long total = 0;
				for (int y = cy; y < cy + side; y++) {
					for (int x = cx; x < cx + side; x++) {
						int owner = this.owners[y * this.cols + x];
						if (owner == 0) {
							continue;
						}
						boolean counted = false;
						for (int i = 0; i < seenCount; i++) {
							if (seen[i] == owner) {
								counted = true;
								break;
							}
						}
						if (counted) {
							continue;
						}
						if (!evictable.test(owner)) {
							continue search;
						}
						seen[seenCount++] = owner;
						total += cost.applyAsLong(owner);
						if (total >= bestCost) {
							continue search;
						}
					}
				}
				best = pack(cx * CELL, cy * CELL);
				bestCost = total;
			}
		}
		return best;
	}

	/** Distinct owners overlapping the block at {@code packed} of size {@code resolution}. */
	public int[] ownersIn(final int packed, final int resolution) {
		int cx0 = x(packed) / CELL;
		int cy0 = y(packed) / CELL;
		int side = resolution / CELL;
		int[] found = new int[side * side];
		int count = 0;
		for (int y = cy0; y < cy0 + side; y++) {
			for (int x = cx0; x < cx0 + side; x++) {
				int owner = this.owners[y * this.cols + x];
				if (owner == 0) {
					continue;
				}
				boolean dup = false;
				for (int i = 0; i < count; i++) {
					if (found[i] == owner) {
						dup = true;
						break;
					}
				}
				if (!dup) {
					found[count++] = owner;
				}
			}
		}
		return Arrays.copyOf(found, count);
	}

	/** Sets every cell of the block to {@code owner} (0 frees it). */
	public void mark(final int packed, final int resolution, final int owner) {
		int cx0 = x(packed) / CELL;
		int cy0 = y(packed) / CELL;
		int side = resolution / CELL;
		for (int y = cy0; y < cy0 + side; y++) {
			for (int x = cx0; x < cx0 + side; x++) {
				this.owners[y * this.cols + x] = owner;
			}
		}
	}

	private boolean isFree(final int cx, final int cy, final int side) {
		for (int y = cy; y < cy + side; y++) {
			for (int x = cx; x < cx + side; x++) {
				if (this.owners[y * this.cols + x] != 0) {
					return false;
				}
			}
		}
		return true;
	}
}
