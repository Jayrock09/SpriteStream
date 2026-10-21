package dev.spritestream;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/** Streaming state for one block atlas sprite that has a higher-resolution source. */
public final class StreamedSprite {
	public final int id;
	public final Identifier name;
	public final Resource resource;
	/** LabPBR normal ({@code _n}) and specular ({@code _s}) sources, when the pack has them. */
	public final @Nullable Resource normalResource;
	public final @Nullable Resource specularResource;
	public final int sourceWidth;
	public final int sourceHeight;
	/** Largest pool resolution this sprite can use (its source is at least that large). */
	public final int maxResolution;
	/** Atlas UV rectangle of the base sprite. */
	public final float u0, v0, u1, v1;

	/** Pool block shown now ({@link PoolPage} packed position) and its resolution; 0 resolution = base only. */
	public int residentResolution;
	public int residentBlock = -1;
	/** Block being decoded or uploaded; 0 resolution when nothing is in flight. */
	public int pendingResolution;
	public int pendingBlock = -1;
	/** Resolution the last assignment pass gave this sprite (0 = base). */
	public int effectiveResolution;

	/** Largest texels-per-block any probe ray asked for, in the current and previous hold window. */
	public float wantCurrent;
	public float wantPrevious;
	/** Probe rays that hit this sprite, same windows; used to rank sprites competing for space. */
	public int hitsCurrent;
	public int hitsPrevious;

	public long fadeStartNanos;
	public float fade = 1.0F;

	public StreamedSprite(
		final int id, final Identifier name, final Resource resource, final @Nullable Resource normalResource,
		final @Nullable Resource specularResource, final int sourceWidth, final int sourceHeight, final int maxResolution,
		final float u0, final float v0, final float u1, final float v1
	) {
		this.id = id;
		this.name = name;
		this.resource = resource;
		this.normalResource = normalResource;
		this.specularResource = specularResource;
		this.sourceWidth = sourceWidth;
		this.sourceHeight = sourceHeight;
		this.maxResolution = maxResolution;
		this.u0 = u0;
		this.v0 = v0;
		this.u1 = u1;
		this.v1 = v1;
	}

	public float wanted() {
		return Math.max(this.wantCurrent, this.wantPrevious);
	}

	public int hits() {
		return this.hitsCurrent + this.hitsPrevious;
	}
}
