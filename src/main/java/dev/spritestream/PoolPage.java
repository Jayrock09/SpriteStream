package dev.spritestream;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * The high-res pool: one large texture per plane (colour, and normal + specular when a PBR shader pack
 * is active), all sharing one layout. Space is handed out in power-of-two squares aligned to their own
 * size, so a 2048 texture and sixteen 512 textures can share the same area depending on what is on screen.
 * Every block keeps its own mip chain down to the base resolution, which is why the shader never samples
 * a level coarser than that.
 *
 * <p>Occupancy is tracked on a grid of {@link #CELL}-texel cells, so allocation is a plain scan; the grid
 * is at most 128x128 cells, which keeps that cheap.
 */
public final class PoolPage {
	public static final int CELL = 128;
	public static final int PLANE_COLOR = 0;
	public static final int PLANE_NORMAL = 1;
	public static final int PLANE_SPECULAR = 2;

	public final int width;
	public final int height;
	public final int maxResolution;
	public final int baseResolution;
	public final int mipLevels;
	public final int planeCount;
	public final GpuTexture[] textures;
	public final GpuTextureView[] views;
	private final int cols;
	private final int rows;
	/** Sprite id owning each cell, 0 when free. */
	private final int[] owners;

	private PoolPage(final int width, final int height, final int maxResolution, final int baseResolution, final int planeCount) {
		this.width = width;
		this.height = height;
		this.maxResolution = maxResolution;
		this.baseResolution = baseResolution;
		this.mipLevels = Integer.numberOfTrailingZeros(maxResolution) - Integer.numberOfTrailingZeros(baseResolution) + 1;
		this.planeCount = planeCount;
		this.cols = width / CELL;
		this.rows = height / CELL;
		this.owners = new int[this.cols * this.rows];
		this.textures = new GpuTexture[planeCount];
		this.views = new GpuTextureView[planeCount];

		GpuDevice device = RenderSystem.getDevice();
		String[] names = {"colour", "normal", "specular"};
		for (int plane = 0; plane < planeCount; plane++) {
			this.textures[plane] = device.createTexture(
				"spritestream pool " + names[plane],
				// COPY_SRC lets the debug dump read blocks back.
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM,
				width,
				height,
				1,
				this.mipLevels
			);
			this.views[plane] = device.createTextureView(this.textures[plane]);
		}
	}

	/**
	 * Builds the largest page that fits {@code budgetBytes} across {@code planeCount} planes, or returns null
	 * when not even one block of the smallest tier fits.
	 */
	public static @Nullable PoolPage create(final long budgetBytes, final int planeCount, final int wantedMax, final int baseResolution) {
		int maxTexture = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
		int maxResolution = Math.min(wantedMax, Integer.highestOneBit(maxTexture));
		// Mips of a page add a third on top of level 0.
		long texelsPerPlane = budgetBytes * 3L / (planeCount * 4L * 4L);
		while (maxResolution >= CELL && (long) maxResolution * maxResolution > texelsPerPlane) {
			maxResolution >>= 1;
		}
		if (maxResolution < CELL || maxResolution <= baseResolution) {
			return null;
		}
		int side = (int) Math.min(maxTexture, Math.sqrt((double) texelsPerPlane));
		int width = Math.max(maxResolution, side / maxResolution * maxResolution);
		int height = (int) Math.min(maxTexture, texelsPerPlane / width);
		height = Math.max(maxResolution, height / maxResolution * maxResolution);
		return new PoolPage(width, height, maxResolution, baseResolution, planeCount);
	}

	public long allocatedBytes() {
		long bytes = 0;
		for (int level = 0; level < this.mipLevels; level++) {
			bytes += (long) this.textures[0].getWidth(level) * this.textures[0].getHeight(level) * 4L;
		}
		return bytes * this.planeCount;
	}

	public int totalCells() {
		return this.owners.length;
	}

	public static int cellsFor(final int resolution) {
		int side = resolution / CELL;
		return side * side;
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

	/** Packs a block position as {@code x | y << 16} (texel coordinates), or -1 when nothing is free. */
	public int findFree(final int resolution) {
		int side = resolution / CELL;
		for (int cy = 0; cy + side <= this.rows; cy += side) {
			for (int cx = 0; cx + side <= this.cols; cx += side) {
				if (this.isFree(cx, cy, side)) {
					return (cx * CELL) | (cy * CELL) << 16;
				}
			}
		}
		return -1;
	}

	/**
	 * Finds the aligned block of {@code resolution} whose current owners are all evictable and cost the
	 * least to evict. Returns the packed position, or -1 when no such block exists.
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
				best = (cx * CELL) | (cy * CELL) << 16;
				bestCost = total;
			}
		}
		return best;
	}

	/** Owners overlapping the block at {@code packed} of size {@code resolution} (distinct ids). */
	public int[] ownersIn(final int packed, final int resolution) {
		int cx0 = (packed & 0xFFFF) / CELL;
		int cy0 = (packed >>> 16) / CELL;
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
		return java.util.Arrays.copyOf(found, count);
	}

	public void mark(final int packed, final int resolution, final int owner) {
		int cx0 = (packed & 0xFFFF) / CELL;
		int cy0 = (packed >>> 16) / CELL;
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

	public static int x(final int packed) {
		return packed & 0xFFFF;
	}

	public static int y(final int packed) {
		return packed >>> 16;
	}

	public void close() {
		for (int plane = 0; plane < this.planeCount; plane++) {
			this.views[plane].close();
			this.textures[plane].close();
		}
	}
}
