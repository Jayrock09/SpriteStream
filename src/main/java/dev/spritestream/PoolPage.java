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
 * size (see {@link BlockGrid}), so a 2048 texture and sixteen 512 textures can share the same area
 * depending on what is on screen. Every block keeps its own mip chain.
 */
public final class PoolPage {
	public static final int CELL = BlockGrid.CELL;
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
	private final BlockGrid grid;

	private PoolPage(final PoolLayout layout, final int baseResolution, final int planeCount) {
		this.width = layout.width();
		this.height = layout.height();
		this.maxResolution = layout.maxResolution();
		this.baseResolution = baseResolution;
		this.mipLevels = layout.mipLevels(baseResolution);
		this.planeCount = planeCount;
		this.grid = new BlockGrid(this.width, this.height);
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
				this.width,
				this.height,
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
		PoolLayout layout = PoolLayout.compute(budgetBytes, planeCount, wantedMax, baseResolution, maxTexture);
		return layout == null ? null : new PoolPage(layout, baseResolution, planeCount);
	}

	public long allocatedBytes() {
		long bytes = 0;
		for (int level = 0; level < this.mipLevels; level++) {
			bytes += (long) this.textures[0].getWidth(level) * this.textures[0].getHeight(level) * 4L;
		}
		return bytes * this.planeCount;
	}

	public int totalCells() {
		return this.grid.totalCells();
	}

	public static int cellsFor(final int resolution) {
		return BlockGrid.cellsFor(resolution);
	}

	public int usedCells() {
		return this.grid.usedCells();
	}

	/** Packs a block position as {@code x | y << 16} (texel coordinates), or -1 when nothing is free. */
	public int findFree(final int resolution) {
		return this.grid.findFree(resolution);
	}

	/** See {@link BlockGrid#findVictimBlock}. */
	public int findVictimBlock(final int resolution, final IntPredicate evictable, final IntToLongFunction cost) {
		return this.grid.findVictimBlock(resolution, evictable, cost);
	}

	/** Owners overlapping the block at {@code packed} of size {@code resolution} (distinct ids). */
	public int[] ownersIn(final int packed, final int resolution) {
		return this.grid.ownersIn(packed, resolution);
	}

	public void mark(final int packed, final int resolution, final int owner) {
		this.grid.mark(packed, resolution, owner);
	}

	public static int x(final int packed) {
		return BlockGrid.x(packed);
	}

	public static int y(final int packed) {
		return BlockGrid.y(packed);
	}

	public void close() {
		for (int plane = 0; plane < this.planeCount; plane++) {
			this.views[plane].close();
			this.textures[plane].close();
		}
	}
}
