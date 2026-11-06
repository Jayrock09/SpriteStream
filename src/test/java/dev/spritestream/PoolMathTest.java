package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pool sizing and the resolution each texture gets. */
class PoolMathTest {
	private static final long MIB = 1L << 20;

	@Test
	void baseAtlasIsEnoughUpToTheBaseResolution() {
		assertEquals(0, ResolutionMath.target(64.0F, 64, 2048, 2048));
		assertEquals(0, ResolutionMath.target(10.0F, 64, 2048, 2048));
	}

	@Test
	void targetIsTheNextPowerOfTwoAtLeastOneBlock() {
		assertEquals(128, ResolutionMath.target(65.0F, 64, 2048, 2048));
		assertEquals(256, ResolutionMath.target(129.0F, 64, 2048, 2048));
		assertEquals(256, ResolutionMath.target(256.0F, 64, 2048, 2048));
		assertEquals(512, ResolutionMath.target(300.5F, 64, 2048, 2048));
	}

	@Test
	void targetNeverExceedsTheSourceOrThePool() {
		// A 128x pack never asks for more than its own texture, however close the camera is.
		assertEquals(128, ResolutionMath.target(5000.0F, 64, 128, 2048));
		assertEquals(1024, ResolutionMath.target(5000.0F, 64, 2048, 1024));
	}

	@Test
	void fitKeepsTargetsThatFit() {
		assertArrayEquals(new int[] {512, 128, 0}, ResolutionMath.fit(new int[] {512, 128, 0}, 1000));
	}

	@Test
	void fitStepsDownWhenThePoolIsFull() {
		// 20 cells: first sprite takes 16 (512), the next can only get 4 cells (256), then nothing is left.
		assertArrayEquals(new int[] {512, 256, 0}, ResolutionMath.fit(new int[] {512, 512, 512}, 20));
	}

	@Test
	void fitServesPriorityOrder() {
		int[] fitted = ResolutionMath.fit(new int[] {2048, 128}, 256);
		assertArrayEquals(new int[] {2048, 0}, fitted);
	}

	@Test
	void layoutFitsTheBudget() {
		for (int planes : new int[] {1, 3}) {
			for (long budget : new long[] {512 * MIB, 1536 * MIB, 2560 * MIB, 6144 * MIB}) {
				PoolLayout layout = PoolLayout.compute(budget, planes, 2048, 64, 16384);
				assertNotNull(layout);
				assertTrue(layout.bytesPerPlane(64) * planes <= budget, "over budget: " + layout + " planes " + planes);
				assertEquals(0, layout.width() % layout.maxResolution());
				assertEquals(0, layout.height() % layout.maxResolution());
				assertTrue(layout.width() <= 16384 && layout.height() <= 16384);
			}
		}
	}

	@Test
	void smallBudgetsLowerTheLargestBlock() {
		PoolLayout layout = PoolLayout.compute(16 * MIB, 3, 2048, 64, 16384);
		assertNotNull(layout);
		assertTrue(layout.maxResolution() < 2048);
	}

	@Test
	void tooSmallABudgetGivesNoPool() {
		assertNull(PoolLayout.compute(MIB / 16, 3, 2048, 64, 16384));
	}

	@Test
	void layoutRespectsTheTextureSizeLimit() {
		PoolLayout layout = PoolLayout.compute(64L * 1024 * MIB, 1, 4096, 64, 8192);
		assertNotNull(layout);
		assertTrue(layout.width() <= 8192 && layout.height() <= 8192);
		assertTrue(layout.maxResolution() <= 4096);
	}

	@Test
	void mipChainRunsDownToTheBase() {
		assertEquals(6, new PoolLayout(16384, 16384, 2048).mipLevels(64));
		assertEquals(2, new PoolLayout(4096, 4096, 128).mipLevels(64));
	}
}
