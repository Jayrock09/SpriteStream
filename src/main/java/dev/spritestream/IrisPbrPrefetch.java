package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Keeps Iris's normal/specular atlas build cheap. Iris decodes every {@code _n}/{@code _s} PNG at full size
 * on the render thread, one after another, then shrinks each to its sprite's size; on a 2048x pack that
 * froze the game for minutes when shaders were turned on.
 *
 * <p>Instead, the maps are shrunk to the base size while the resource pack loads (on Minecraft's worker
 * threads, next to the colour textures) and kept for the whole reload. Iris is handed a copy whenever it
 * builds its atlases, so turning shaders on or off later costs almost nothing. Anything not cached by then
 * is decoded in parallel right before Iris's build.
 */
public final class IrisPbrPrefetch {
	public static final String[] SUFFIXES = {"_n", "_s"};

	/** One map to decode: where it lives and the size the atlas sprite has. */
	public record Request(Identifier location, Resource resource, int width, int height) {
	}

	/** Shrunk maps for the current reload, by PBR image location. Never handed out directly. */
	private static final Map<Identifier, NativeImage> CACHE = new ConcurrentHashMap<>();

	private IrisPbrPrefetch() {
	}

	/** The Iris location of a sprite's PBR map, e.g. {@code minecraft:textures/block/stone_n.png}. */
	public static Identifier location(final Identifier sprite, final String suffix) {
		return sprite.withPath(path -> "textures/" + path + suffix + ".png");
	}

	/** Called while the block atlas loads: shrink this sprite's PBR maps to the base sprite size. */
	public static void cacheDuringLoad(final Identifier sprite, final Resource colourResource, final int width, final int height,
		final net.minecraft.server.packs.resources.ResourceManager resources) {
		for (String suffix : SUFFIXES) {
			Identifier location = location(sprite, suffix);
			resources.getResource(location).ifPresent(resource -> {
				NativeImage small = shrink(new Request(location, resource, width, height));
				if (small != null) {
					NativeImage previous = CACHE.put(location, small);
					if (previous != null) {
						previous.close();
					}
				}
			});
		}
	}

	/** Decodes and shrinks the requests that are not cached yet, in parallel; blocks until done. */
	public static void prefetchMissing(final List<Request> requests) {
		List<Request> missing = new ArrayList<>();
		for (Request request : requests) {
			if (!CACHE.containsKey(request.location())) {
				missing.add(request);
			}
		}
		if (missing.isEmpty()) {
			SpriteStreamMod.LOGGER.info("[SpriteStream] All {} PBR maps were pre-shrunk during load", requests.size());
			return;
		}
		long start = System.nanoTime();
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
		AtomicInteger id = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
			Thread thread = new Thread(r, "SpriteStream PBR prefetch " + id.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<Callable<Void>> tasks = new ArrayList<>(missing.size());
			for (Request request : missing) {
				tasks.add(() -> {
					NativeImage small = shrink(request);
					if (small != null) {
						CACHE.put(request.location(), small);
					}
					return null;
				});
			}
			pool.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			pool.shutdownNow();
		}
		SpriteStreamMod.LOGGER.info(
			"[SpriteStream] Shrunk {} uncached PBR maps on {} threads in {} ms ({} were cached during load)",
			missing.size(), threads, (System.nanoTime() - start) / 1_000_000L, requests.size() - missing.size()
		);
	}

	private static @Nullable NativeImage shrink(final Request request) {
		try {
			// Animated maps keep Iris's own path, which also rewrites their frame metadata.
			if (request.resource().metadata().getSection(AnimationMetadataSection.TYPE).isPresent()) {
				return null;
			}
			NativeImage image;
			try (InputStream in = request.resource().open()) {
				image = NativeImage.read(in);
			}
			if (image.getWidth() <= request.width() && image.getHeight() <= request.height()) {
				image.close();
				return null;
			}
			NativeImage small = new NativeImage(request.width(), request.height(), false);
			if (request.location().getPath().endsWith("_s.png")) {
				ImageOps.downsampleSpecular(image.getPointer(), image.getWidth(), image.getHeight(), small.getPointer(), request.width(), request.height());
			} else {
				ImageOps.downsamplePlain(image.getPointer(), image.getWidth(), image.getHeight(), small.getPointer(), request.width(), request.height());
			}
			image.close();
			return small;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * A copy of the cached map for {@code location} at exactly {@code width}x{@code height} (Iris owns and
	 * closes what it gets), or null to let Iris decode it itself.
	 */
	public static @Nullable NativeImage copyOf(final Identifier location, final int width, final int height) {
		NativeImage cached = CACHE.get(location);
		if (cached == null || cached.getWidth() != width || cached.getHeight() != height) {
			return null;
		}
		NativeImage copy = new NativeImage(width, height, false);
		copy.copyFrom(cached);
		return copy;
	}

	/** Drops the cache; called when a new reload starts. */
	public static void clear() {
		CACHE.values().forEach(NativeImage::close);
		CACHE.clear();
	}
}
