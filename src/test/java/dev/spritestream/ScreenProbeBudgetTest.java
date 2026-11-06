package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The probe's ray budget: unchanged at 60 fps or less, the same rays per second above that. */
class ScreenProbeBudgetTest {
	private static final int ROWS = 36;
	private static final int COLS = 64;
	private static final long MS = 1_000_000L;

	private static int totalRows(final double fps, final double seconds) {
		ScreenProbe probe = new ScreenProbe();
		long frame = (long) (1e9 / fps);
		long now = 1;
		probe.rowsToCast(ROWS, COLS, now);
		int total = 0;
		for (long t = 0; t < seconds * 1e9; t += frame) {
			now += frame;
			total += probe.rowsToCast(ROWS, COLS, now);
		}
		return total;
	}

	@Test
	void firstFrameCastsTheWholeGrid() {
		assertEquals(ROWS, new ScreenProbe().rowsToCast(ROWS, COLS, 123));
	}

	@Test
	void atOrBelow60FpsEveryFrameCastsTheWholeGrid() {
		for (double fps : new double[] {20, 30, 45, 59}) {
			ScreenProbe probe = new ScreenProbe();
			long now = 1;
			probe.rowsToCast(ROWS, COLS, now);
			for (int i = 0; i < 100; i++) {
				now += (long) (1e9 / fps);
				assertEquals(ROWS, probe.rowsToCast(ROWS, COLS, now), "fps " + fps);
			}
		}
	}

	@Test
	void aboveThatTheRaysPerSecondStayAt60Grids() {
		for (double fps : new double[] {120, 240, 500, 2000}) {
			int rows = totalRows(fps, 10.0);
			// 60 grids per second for 10 s, within one grid.
			assertTrue(Math.abs(rows - 600 * ROWS) <= ROWS, "fps " + fps + ": " + rows);
		}
	}

	@Test
	void neverMoreThanOneGridPerFrameAfterAStall() {
		ScreenProbe probe = new ScreenProbe();
		probe.rowsToCast(ROWS, COLS, 1);
		assertEquals(ROWS, probe.rowsToCast(ROWS, COLS, 1 + 5_000 * MS));
		// The stall must not leave extra credit behind.
		assertTrue(probe.rowsToCast(ROWS, COLS, 1 + 5_000 * MS + 2 * MS) < ROWS);
	}
}
