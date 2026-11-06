package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpriteStreamConfigTest {
	@Test
	void defaultsAreSane() {
		SpriteStreamConfig config = SpriteStreamConfig.defaults();
		assertEquals(64, config.baseResolution);
		assertEquals(256, config.animatedResolution);
		assertEquals(2048, config.maxResolution);
		assertEquals(2, config.configVersion);
	}

	@Test
	void fileFromBeforeVersioningGetsTheNewAnimatedDefault() {
		SpriteStreamConfig config = SpriteStreamConfig.fromJson("{\"animatedResolution\": 128, \"vramBudgetMiB\": 1024}");
		assertEquals(256, config.animatedResolution);
		assertEquals(1024, config.vramBudgetMiB);
		assertEquals(2, config.configVersion);
	}

	@Test
	void customAnimatedValueIsKept() {
		assertEquals(512, SpriteStreamConfig.fromJson("{\"animatedResolution\": 512}").animatedResolution);
		assertEquals(64, SpriteStreamConfig.fromJson("{\"animatedResolution\": 64}").animatedResolution);
	}

	@Test
	void currentVersionIsNotMigratedAgain() {
		// A user who sets 128 after upgrading keeps it.
		assertEquals(128, SpriteStreamConfig.fromJson("{\"configVersion\": 2, \"animatedResolution\": 128}").animatedResolution);
	}

	@Test
	void valuesAreClampedAndRoundedToPowersOfTwo() {
		SpriteStreamConfig config = SpriteStreamConfig.fromJson(
			"{\"baseResolution\": 100, \"maxResolution\": 99999, \"vramBudgetMiB\": 1, \"qualityBias\": 50, \"decodeThreads\": 0, \"otherAtlasResolution\": -5}"
		);
		assertEquals(64, config.baseResolution);
		assertEquals(4096, config.maxResolution);
		assertEquals(128, config.vramBudgetMiB);
		assertEquals(4.0F, config.qualityBias);
		assertEquals(1, config.decodeThreads);
		assertEquals(0, config.otherAtlasResolution);
	}

	@Test
	void emptyFileGivesDefaults() {
		assertEquals(64, SpriteStreamConfig.fromJson("").baseResolution);
	}
}
