package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockGridTest {
	@Test
	void freeBlocksAreAlignedToTheirSize() {
		BlockGrid grid = new BlockGrid(2048, 2048);
		grid.mark(grid.findFree(128), 128, 1);
		int block = grid.findFree(512);
		assertEquals(0, BlockGrid.x(block) % 512);
		assertEquals(0, BlockGrid.y(block) % 512);
		// The first 512 block holds the 128 one, so the next free 512 block starts after it.
		assertEquals(BlockGrid.pack(512, 0), block);
	}

	@Test
	void fullGridHasNoFreeBlock() {
		BlockGrid grid = new BlockGrid(1024, 1024);
		grid.mark(BlockGrid.pack(0, 0), 1024, 7);
		assertEquals(-1, grid.findFree(128));
		assertEquals(grid.totalCells(), grid.usedCells());
	}

	@Test
	void smallBlocksFillEveryCellWithoutOverlap() {
		BlockGrid grid = new BlockGrid(512, 512);
		Set<Integer> seen = new HashSet<>();
		for (int id = 1; id <= 16; id++) {
			int block = grid.findFree(128);
			assertNotEquals(-1, block);
			assertEquals(true, seen.add(block));
			grid.mark(block, 128, id);
		}
		assertEquals(-1, grid.findFree(128));
	}

	@Test
	void ownersInListsEachOwnerOnce() {
		BlockGrid grid = new BlockGrid(1024, 1024);
		grid.mark(BlockGrid.pack(0, 0), 256, 3);
		grid.mark(BlockGrid.pack(256, 0), 128, 4);
		assertArrayEquals(new int[] {3, 4}, grid.ownersIn(BlockGrid.pack(0, 0), 512));
		assertArrayEquals(new int[0], grid.ownersIn(BlockGrid.pack(512, 512), 512));
	}

	@Test
	void freeingABlockMakesItAvailableAgain() {
		BlockGrid grid = new BlockGrid(512, 512);
		grid.mark(BlockGrid.pack(0, 0), 512, 9);
		grid.mark(BlockGrid.pack(0, 0), 512, 0);
		assertEquals(BlockGrid.pack(0, 0), grid.findFree(512));
		assertEquals(0, grid.usedCells());
	}

	@Test
	void victimIsTheCheapestEvictableBlock() {
		BlockGrid grid = new BlockGrid(1024, 512);
		// Left 512 block: owner 1 (cost 10). Right 512 block: owners 2 and 3 (cost 1 + 1).
		grid.mark(BlockGrid.pack(0, 0), 512, 1);
		grid.mark(BlockGrid.pack(512, 0), 256, 2);
		grid.mark(BlockGrid.pack(768, 0), 256, 3);
		long[] cost = {0, 10, 1, 1};
		assertEquals(BlockGrid.pack(512, 0), grid.findVictimBlock(512, owner -> true, owner -> cost[owner]));
	}

	@Test
	void blocksWithAnOwnerThatMustStayAreSkipped() {
		BlockGrid grid = new BlockGrid(1024, 512);
		grid.mark(BlockGrid.pack(0, 0), 512, 1);
		grid.mark(BlockGrid.pack(512, 0), 128, 2);
		assertEquals(BlockGrid.pack(0, 0), grid.findVictimBlock(512, owner -> owner != 2, owner -> 100));
		assertEquals(-1, grid.findVictimBlock(512, owner -> false, owner -> 1));
	}

	@Test
	void cellsForCountsGridCells() {
		assertEquals(1, BlockGrid.cellsFor(128));
		assertEquals(4, BlockGrid.cellsFor(256));
		assertEquals(256, BlockGrid.cellsFor(2048));
	}
}
