package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

	private BaseAtlasLoader() {
	}

	/** Called when the block atlas starts loading; clears the previous reload's sources. */
	public static void beginLoad() {
		sources = new ConcurrentHashMap<>();
		activeForCurrentLoad = SpriteStreamMod.shouldStream();
		if (!activeForCurrentLoad) {
			SpriteStreamMod.LOGGER.info("[SpriteStream] Streaming disabled for this reload (needs Sodium)");
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
			return load(spriteLocation, resource, additionalMetadataSections, vanilla, SpriteStreamMod.config.baseResolution, true);
		};
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
			int frameCap = Math.min(cap, config.animatedResolution);
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
		return new SpriteContents(
			spriteLocation, new FrameSize(base.getWidth(), base.getHeight()), base, animationInfo, additionalMetadata, textureInfo
		);
	}
}
