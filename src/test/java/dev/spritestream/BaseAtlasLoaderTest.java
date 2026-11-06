package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BaseAtlasLoaderTest {
	private static final Set<String> PACK = Set.of("block/stone", "block/stone_n", "block/stone_s", "block/oak_log", "block/stairs_s");

	@Test
	void pbrMapsNextToTheirTextureAreRecognised() {
		assertTrue(BaseAtlasLoader.isPbrCompanion("block/stone_n", PACK::contains));
		assertTrue(BaseAtlasLoader.isPbrCompanion("block/stone_s", PACK::contains));
	}

	@Test
	void ordinaryTexturesAreNot() {
		assertFalse(BaseAtlasLoader.isPbrCompanion("block/stone", PACK::contains));
		assertFalse(BaseAtlasLoader.isPbrCompanion("block/oak_log", PACK::contains));
		// Ends in _s but there is no "block/stairs" texture, so it is a real texture.
		assertFalse(BaseAtlasLoader.isPbrCompanion("block/stairs_s", PACK::contains));
		assertFalse(BaseAtlasLoader.isPbrCompanion("block/_n", path -> true));
		assertFalse(BaseAtlasLoader.isPbrCompanion("_s", path -> true));
	}
}
