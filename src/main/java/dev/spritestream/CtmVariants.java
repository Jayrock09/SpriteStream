package dev.spritestream;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

/**
 * Connected and random textures (OptiFine CTM format, applied by Continuity) swap a block's texture for
 * variants such as {@code optifine/ctm/bricks/2.png} when the chunk is built. The screen probe only sees the
 * block's own model, so without this the variants would never be asked for and would stay at the base
 * resolution next to upgraded neighbours.
 *
 * <p>This reads every {@code optifine/ctm/**.properties} and links its tiles to the textures
 * ({@code matchTiles}) and blocks ({@code matchBlocks}) it replaces, so a measurement for those also counts
 * for the variants.
 */
public final class CtmVariants {
	private final Map<StreamedSprite, StreamedSprite[]> byBaseSprite = new IdentityHashMap<>();
	private final Map<Block, StreamedSprite[]> byBlock = new IdentityHashMap<>();
	/** Overlay tiles (grass/sand edges drawn on top of neighbouring blocks), kept apart so they can rank lower. */
	private final Map<StreamedSprite, StreamedSprite[]> overlaysByBaseSprite = new IdentityHashMap<>();
	private final Map<Block, StreamedSprite[]> overlaysByBlock = new IdentityHashMap<>();
	private int ruleCount;

	private CtmVariants() {
	}

	public static CtmVariants empty() {
		return new CtmVariants();
	}

	public static CtmVariants load(final ResourceManager resources, final Map<Identifier, StreamedSprite> spritesByName) {
		CtmVariants variants = new CtmVariants();
		Map<StreamedSprite, Set<StreamedSprite>> baseLinks = new IdentityHashMap<>();
		Map<Block, Set<StreamedSprite>> blockLinks = new IdentityHashMap<>();
		Map<StreamedSprite, Set<StreamedSprite>> overlayBaseLinks = new IdentityHashMap<>();
		Map<Block, Set<StreamedSprite>> overlayBlockLinks = new IdentityHashMap<>();

		Map<Identifier, Resource> files = resources.listResources("optifine/ctm", id -> id.getPath().endsWith(".properties"));
		for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
			Properties properties = new Properties();
			try (InputStream in = file.getValue().open()) {
				properties.load(in);
			} catch (Exception e) {
				continue;
			}
			Identifier location = file.getKey();
			String directory = location.getPath().substring(0, location.getPath().lastIndexOf('/'));

			List<StreamedSprite> tiles = new ArrayList<>();
			for (Identifier tile : parseTiles(location.getNamespace(), directory, properties.getProperty("tiles", ""))) {
				StreamedSprite sprite = spritesByName.get(tile);
				if (sprite == null && tile.getPath().startsWith("optifine/")) {
					// Continuity registers OptiFine-format tiles under its own prefix in the atlas.
					sprite = spritesByName.get(tile.withPath("continuity_reserved/" + tile.getPath().substring("optifine/".length())));
				}
				if (sprite != null) {
					tiles.add(sprite);
				}
			}
			if (tiles.isEmpty()) {
				continue;
			}
			variants.ruleCount++;
			boolean overlay = properties.getProperty("method", "").trim().startsWith("overlay");
			Map<StreamedSprite, Set<StreamedSprite>> toBase = overlay ? overlayBaseLinks : baseLinks;
			Map<Block, Set<StreamedSprite>> toBlock = overlay ? overlayBlockLinks : blockLinks;

			for (String tile : split(properties.getProperty("matchTiles", ""))) {
				StreamedSprite base = spritesByName.get(textureId(location.getNamespace(), tile));
				if (base != null) {
					toBase.computeIfAbsent(base, k -> new LinkedHashSet<>()).addAll(tiles);
				}
			}
			for (String entry : split(properties.getProperty("matchBlocks", ""))) {
				Block block = blockFor(entry);
				if (block != null) {
					toBlock.computeIfAbsent(block, k -> new LinkedHashSet<>()).addAll(tiles);
				}
			}
		}

		freeze(baseLinks, variants.byBaseSprite, true);
		freeze(blockLinks, variants.byBlock, false);
		freeze(overlayBaseLinks, variants.overlaysByBaseSprite, true);
		freeze(overlayBlockLinks, variants.overlaysByBlock, false);
		return variants;
	}

	private static <K> void freeze(final Map<K, Set<StreamedSprite>> links, final Map<K, StreamedSprite[]> into, final boolean dropSelf) {
		links.forEach((key, set) -> {
			if (dropSelf) {
				set.remove(key);
			}
			if (!set.isEmpty()) {
				into.put(key, set.toArray(new StreamedSprite[0]));
			}
		});
	}

	/** Overlay tiles that can be drawn over {@code base}, or an empty array. */
	public StreamedSprite[] overlaysForSprite(final StreamedSprite base) {
		return this.overlaysByBaseSprite.getOrDefault(base, NONE);
	}

	/** Overlay tiles that can be drawn over {@code block}, or an empty array. */
	public StreamedSprite[] overlaysForBlock(final Block block) {
		return this.overlaysByBlock.getOrDefault(block, NONE);
	}

	/** Variants that stand in for {@code base} somewhere in the world, or an empty array. */
	public StreamedSprite[] forSprite(final StreamedSprite base) {
		return this.byBaseSprite.getOrDefault(base, NONE);
	}

	/** Variants used on {@code block}, or an empty array. */
	public StreamedSprite[] forBlock(final Block block) {
		return this.byBlock.getOrDefault(block, NONE);
	}

	public int ruleCount() {
		return this.ruleCount;
	}

	public int linkedSprites() {
		Set<StreamedSprite> all = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		this.byBaseSprite.values().forEach(a -> all.addAll(List.of(a)));
		this.byBlock.values().forEach(a -> all.addAll(List.of(a)));
		this.overlaysByBaseSprite.values().forEach(a -> all.addAll(List.of(a)));
		this.overlaysByBlock.values().forEach(a -> all.addAll(List.of(a)));
		return all.size();
	}

	private static final StreamedSprite[] NONE = new StreamedSprite[0];

	/**
	 * OptiFine tile syntax: numbers and ranges ({@code 0-46}) and bare names live next to the properties
	 * file; paths with a slash are relative to the namespace ({@code textures/block/bricks}).
	 */
	static List<Identifier> parseTiles(final String namespace, final String directory, final String tiles) {
		List<Identifier> result = new ArrayList<>();
		for (String token : split(tiles)) {
			if (token.startsWith("<")) {
				continue; // <default>, <skip>
			}
			int dash = token.indexOf('-');
			if (dash > 0 && token.chars().allMatch(c -> Character.isDigit(c) || c == '-')) {
				try {
					int from = Integer.parseInt(token.substring(0, dash));
					int to = Integer.parseInt(token.substring(dash + 1));
					for (int i = from; i <= Math.min(to, from + 512); i++) {
						result.add(Identifier.fromNamespaceAndPath(namespace, directory + "/" + i));
					}
				} catch (NumberFormatException ignored) {
				}
				continue;
			}
			String path = token.endsWith(".png") ? token.substring(0, token.length() - 4) : token;
			if (path.startsWith("./")) {
				// Relative to the properties file's folder.
				Identifier relative = Identifier.tryBuild(namespace, directory + "/" + path.substring(2));
				if (relative != null) {
					result.add(relative);
				}
				continue;
			}
			String tileNamespace = namespace;
			int colon = path.indexOf(':');
			if (colon > 0) {
				tileNamespace = path.substring(0, colon);
				path = path.substring(colon + 1);
			}
			if (!path.contains("/")) {
				path = directory + "/" + path;
			} else if (path.startsWith("textures/")) {
				path = path.substring("textures/".length());
			}
			Identifier id = Identifier.tryBuild(tileNamespace, path);
			if (id != null) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * {@code bricks}, {@code block/bricks}, {@code minecraft:block/bricks} or
	 * {@code textures/block/extra/anvil_side.png} as an atlas sprite id.
	 */
	static Identifier textureId(final String namespace, final String name) {
		String path = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
		String ns = namespace;
		int colon = path.indexOf(':');
		if (colon > 0) {
			ns = path.substring(0, colon);
			path = path.substring(colon + 1);
		}
		if (path.startsWith("textures/")) {
			path = path.substring("textures/".length());
		}
		if (!path.contains("/")) {
			path = "block/" + path;
		}
		Identifier id = Identifier.tryBuild(ns, path);
		return id != null ? id : Identifier.withDefaultNamespace("missingno");
	}

	/** {@code oak_log}, {@code minecraft:oak_log} or {@code oak_log:axis=y} as a block. */
	private static Block blockFor(final String entry) {
		String[] parts = entry.split(":");
		String id;
		if (parts.length >= 2 && !parts[1].contains("=")) {
			id = parts[0] + ":" + parts[1];
		} else {
			id = "minecraft:" + parts[0];
		}
		Identifier key = Identifier.tryParse(id);
		if (key == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}

	private static List<String> split(final String value) {
		List<String> parts = new ArrayList<>();
		for (String part : value.trim().split("[\\s,]+")) {
			if (!part.isEmpty()) {
				parts.add(part);
			}
		}
		return parts;
	}
}
