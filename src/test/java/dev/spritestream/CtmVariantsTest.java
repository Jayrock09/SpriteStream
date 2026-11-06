package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** OptiFine CTM {@code tiles} / {@code matchTiles} syntax as found in Optimum Realism and Patrix. */
class CtmVariantsTest {
	private static final String DIR = "optifine/ctm/bricks";

	private static Identifier id(final String path) {
		return Identifier.withDefaultNamespace(path);
	}

	@Test
	void numbersAndRangesLiveNextToTheProperties() {
		assertEquals(
			List.of(id(DIR + "/1"), id(DIR + "/2"), id(DIR + "/3"), id(DIR + "/4"), id(DIR + "/7")),
			CtmVariants.parseTiles("minecraft", DIR, "1 2-4 7")
		);
	}

	@Test
	void texturesPathPointsAtTheNormalTexture() {
		// Optimum: tiles=textures/block/bricks 2-4
		assertEquals(
			List.of(id("block/bricks"), id(DIR + "/2"), id(DIR + "/3"), id(DIR + "/4")),
			CtmVariants.parseTiles("minecraft", DIR, "textures/block/bricks 2-4")
		);
	}

	@Test
	void pngSuffixAndCommasAreAccepted() {
		assertEquals(
			List.of(id(DIR + "/top"), id("block/stone")),
			CtmVariants.parseTiles("minecraft", DIR, "top.png, textures/block/stone.png")
		);
	}

	@Test
	void dotSlashIsRelativeToTheFolder() {
		// Patrix: tiles=./extra/6.png
		assertEquals(
			List.of(id("optifine/ctm/patrix/fern/large/extra/6")),
			CtmVariants.parseTiles("minecraft", "optifine/ctm/patrix/fern/large", "./extra/6.png")
		);
	}

	@Test
	void specialTokensAreSkipped() {
		assertEquals(List.of(id(DIR + "/1")), CtmVariants.parseTiles("minecraft", DIR, "<default> 1 <skip>"));
	}

	@Test
	void namespacedTilesKeepTheirNamespace() {
		assertEquals(
			List.of(Identifier.fromNamespaceAndPath("mymod", "block/thing")),
			CtmVariants.parseTiles("minecraft", DIR, "mymod:textures/block/thing.png")
		);
	}

	@Test
	void matchTilesFormsResolveToAtlasSprites() {
		assertEquals(id("block/bricks"), CtmVariants.textureId("minecraft", "bricks"));
		assertEquals(id("block/bricks"), CtmVariants.textureId("minecraft", "block/bricks"));
		assertEquals(id("block/bricks"), CtmVariants.textureId("minecraft", "minecraft:block/bricks"));
		// Patrix: matchTiles=textures/block/extra/anvil_side.png
		assertEquals(id("block/extra/anvil_side"), CtmVariants.textureId("minecraft", "textures/block/extra/anvil_side.png"));
	}
}
