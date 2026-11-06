package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.Mth;

/**
 * Replaces the block atlas sprite loader. Each sprite larger than the base resolution is shrunk before it
 * reaches the stitcher, and its original resource is remembered so the streamer can reload it at higher
 * resolution later. This is what keeps the always-resident atlas small no matter how large the pack is.
 */
public final class BaseAtlasLoader {
	/** A full-resolution texture available for streaming. */
	public record Source(Resource resource, int width, int height) {
	}

	private static volatile Map<Identifier, Source> sources = new ConcurrentHashMap<>();
	private static volatile boolean activeForCurrentLoad;
	/** Whether to shrink Iris's PBR maps during this load (Iris with our hooks, able to run shader packs here). */
	private static volatile boolean preparePbrForCurrentLoad;

	private BaseAtlasLoader() {
	}

	/** Called when the block atlas starts loading; clears the previous reload's sources. */
	public static void beginLoad() {
		sources = new ConcurrentHashMap<>();
		IrisPbrPrefetch.clear();
		activeForCurrentLoad = SpriteStreamMod.shouldStream();
		preparePbrForCurrentLoad = activeForCurrentLoad
			&& SpriteStreamMod.config.preparePbrMapsAtLoad
			&& IrisCompat.shaderPacksPossible();
		if (!activeForCurrentLoad) {
			SpriteStreamMod.LOGGER.info("[SpriteStream] Streaming disabled for this reload; textures load unchanged");
		}
	}

	public static boolean isActive() {
		return activeForCurrentLoad;
	}

	public static Map<Identifier, Source> sources() {
		return sources;
	}

	/** Loader for the block atlas: shrinks to the base resolution and records sources for streaming. */
	public static SpriteResourceLoader createForBlocks(final Set<MetadataSectionType<?>> additionalMetadataSections) {
		SpriteResourceLoader vanilla = SpriteResourceLoader.create(additionalMetadataSections);
		return (spriteLocation, resource) -> {
			if (!activeForCurrentLoad) {
				return vanilla.loadSprite(spriteLocation, resource);
			}
			if (SpriteStreamMod.config.skipPbrMapsInBlockAtlas && isPbrCompanion(spriteLocation.getPath(), colour -> textureExists(spriteLocation.withPath(colour)))) {
				SpriteContents placeholder = pbrPlaceholder(spriteLocation, resource);
				if (placeholder != null) {
					return placeholder;
				}
			}
			if (spriteLocation.getPath().startsWith("entity/")) {
				// Entity textures some packs put in the block atlas are drawn by entity renderers, not the
				// patched terrain shader, so they could never stream back up: cap them like the other atlases.
				int cap = SpriteStreamMod.config.otherAtlasResolution;
				return cap > 0
					? load(spriteLocation, resource, additionalMetadataSections, vanilla, cap, false)
					: vanilla.loadSprite(spriteLocation, resource);
			}
			return load(spriteLocation, resource, additionalMetadataSections, vanilla, SpriteStreamMod.config.baseResolution, true);
		};
	}

	/**
	 * True for a LabPBR normal or specular map ({@code foo_n} / {@code foo_s}) that sits next to its colour
	 * texture {@code foo}. Vanilla's directory source stitches every PNG under {@code textures/block}, these
	 * included, although no model uses them; shader packs get them from Iris's own PBR atlases instead.
	 */
	static boolean isPbrCompanion(final String spritePath, final Predicate<String> textureExists) {
		if (!spritePath.endsWith("_n") && !spritePath.endsWith("_s")) {
			return false;
		}
		String colour = spritePath.substring(0, spritePath.length() - 2);
		return !colour.isEmpty() && !colour.endsWith("/") && textureExists.test(colour);
	}

	private static boolean textureExists(final Identifier sprite) {
		return net.minecraft.client.Minecraft.getInstance().getResourceManager()
			.getResource(sprite.withPath(path -> "textures/" + path + ".png")).isPresent();
	}

	/**
	 * A neutral base-size stand-in for a PBR map in the block atlas, so the full-size map is not decoded just to
	 * be shrunk into a slot nothing draws. Animated maps (and anything unreadable) keep the normal path.
	 */
	private static @org.jspecify.annotations.Nullable SpriteContents pbrPlaceholder(final Identifier spriteLocation, final Resource resource) {
		try {
			if (resource.metadata().getSection(AnimationMetadataSection.TYPE).isPresent()) {
				return null;
			}
		} catch (Exception e) {
			return null;
		}
		int size = SpriteStreamMod.config.baseResolution;
		NativeImage image = new NativeImage(size, size, false);
		// Flat normal / no specular, in case something does draw it.
		image.fillRect(0, 0, size, size, spriteLocation.getPath().endsWith("_n") ? 0xFF8080FF : 0xFF000000);
		return new SpriteContents(spriteLocation, new FrameSize(size, size), image, Optional.empty(), List.of(), Optional.empty());
	}

	/**
	 * Loader for the other atlases (items, particles, chests, ...). They are not streamed, only capped, so a
	 * 2048x pack cannot blow past the maximum texture size or eat VRAM through them.
	 */
	public static SpriteResourceLoader createCapped(final Set<MetadataSectionType<?>> additionalMetadataSections, final int cap) {
		SpriteResourceLoader vanilla = SpriteResourceLoader.create(additionalMetadataSections);
		return (spriteLocation, resource) -> load(spriteLocation, resource, additionalMetadataSections, vanilla, cap, false);
	}

	private static SpriteContents load(
		final Identifier spriteLocation,
		final Resource resource,
		final Set<MetadataSectionType<?>> additionalMetadataSections,
		final SpriteResourceLoader vanilla,
		final int cap,
		final boolean recordSource
	) {
		Optional<AnimationMetadataSection> animationInfo;
		Optional<TextureMetadataSection> textureInfo;
		List<MetadataSectionType.WithValue<?>> additionalMetadata;
		try {
			ResourceMetadata metadata = resource.metadata();
			animationInfo = metadata.getSection(AnimationMetadataSection.TYPE);
			textureInfo = metadata.getSection(TextureMetadataSection.TYPE);
			additionalMetadata = metadata.getTypedSections(additionalMetadataSections);
		} catch (Exception e) {
			return vanilla.loadSprite(spriteLocation, resource);
		}

		NativeImage image;
		try (InputStream is = resource.open()) {
			image = NativeImage.read(is);
		} catch (IOException e) {
			SpriteStreamMod.LOGGER.error("[SpriteStream] Using missing texture, unable to load {}", spriteLocation, e);
			return null;
		}

		SpriteStreamConfig config = SpriteStreamMod.config;
		if (animationInfo.isPresent()) {
			AnimationMetadataSection animation = animationInfo.get();
			FrameSize frame = animation.calculateFrameSize(image.getWidth(), image.getHeight());
			boolean explicitSize = animation.frameWidth().isPresent() || animation.frameHeight().isPresent();
			// Animated block textures (water, lava, fire, ...) are not streamed, so they keep their own cap
			// instead of dropping to the base resolution.
			int frameCap = recordSource ? config.animatedResolution : Math.min(cap, config.animatedResolution);
			boolean evenFrames = Mth.isMultipleOf(image.getWidth(), frame.width()) && Mth.isMultipleOf(image.getHeight(), frame.height());
			if (!explicitSize && evenFrames && Math.max(frame.width(), frame.height()) > frameCap) {
				int largest = Math.max(frame.width(), frame.height());
				int fw = Math.max(1, frame.width() * frameCap / largest);
				int fh = Math.max(1, frame.height() * frameCap / largest);
				NativeImage scaled = ImageOps.shrinkFrames(image, frame.width(), frame.height(), fw, fh);
				image.close();
				return new SpriteContents(spriteLocation, new FrameSize(fw, fh), scaled, animationInfo, additionalMetadata, textureInfo);
			}
			if (!evenFrames) {
				image.close();
				return vanilla.loadSprite(spriteLocation, resource);
			}
			return new SpriteContents(spriteLocation, frame, image, animationInfo, additionalMetadata, textureInfo);
		}

		int width = image.getWidth();
		int height = image.getHeight();
		if (Math.max(width, height) <= cap) {
			return new SpriteContents(spriteLocation, new FrameSize(width, height), image, animationInfo, additionalMetadata, textureInfo);
		}

		if (recordSource) {
			sources.put(spriteLocation, new Source(resource, width, height));
		}
		NativeImage base = ImageOps.shrinkTo(image, cap);
		image.close();
		if (recordSource && preparePbrForCurrentLoad) {
			IrisPbrPrefetch.cacheDuringLoad(
				spriteLocation, resource, base.getWidth(), base.getHeight(), net.minecraft.client.Minecraft.getInstance().getResourceManager()
			);
		}
		return new SpriteContents(
			spriteLocation, new FrameSize(base.getWidth(), base.getHeight()), base, animationInfo, additionalMetadata, textureInfo
		);
	}
}
