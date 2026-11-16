package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlopedTopsTest {

	@Test
	void levelTrackGivesWholeBlocksUnderItAndNoneAtItsHeight() {
		final double[] level = {10, 10, 10, 10};
		assertEquals(SlopedTops.FLAT, SlopedTops.of(level, 9));
		assertEquals(SlopedTops.FLAT, SlopedTops.of(level, 5));
		assertEquals(SlopedTops.NONE, SlopedTops.of(level, 10));
		// A hair off still rounds to whole blocks
		assertEquals(SlopedTops.FLAT, SlopedTops.of(new double[]{10.01, 9.99, 10, 10}, 9));
		assertEquals(SlopedTops.NONE, SlopedTops.of(new double[]{10.01, 9.99, 10, 10}, 10));
	}

	@Test
	void aSlopeAlongXRisesAcrossTheBlock() {
		// One in four along x: 10.25 at x = 0, 10.5 at x = 1
		final int top = SlopedTops.of(new double[]{10.25, 10.5, 10.5, 10.25}, 10);
		assertEquals(4, SlopedTops.corner(top, 0));
		assertEquals(8, SlopedTops.corner(top, 1));
		assertEquals(8, SlopedTops.corner(top, 2));
		assertEquals(4, SlopedTops.corner(top, 3));
		assertEquals(0.375, SlopedTops.heightAt(top, 0.5, 0.5), 1E-9);
		assertEquals(0.375, SlopedTops.heightAt(top, 0.5, 0.9), 1E-9);
		final int[] columns = SlopedTops.columnHeights(top);
		for (int x = 1; x < 16; x++) {
			assertTrue(columns[x * 16 + 3] >= columns[(x - 1) * 16 + 3]);
		}
		assertEquals(4, columns[3]);
		assertEquals(8, columns[15 * 16 + 3]);
	}

	@Test
	void aTopCrossingTheBlockAboveIsCutAtBothBlocks() {
		// The rail from 10.9 to 11.1 over the block: block 10 is nearly whole, block 11 a thin wedge
		final double[] heights = {10.9, 11.1, 11.1, 10.9};
		final int lower = SlopedTops.of(heights, 10);
		final int upper = SlopedTops.of(heights, 11);
		assertEquals(14, SlopedTops.corner(lower, 0));
		assertEquals(16, SlopedTops.corner(lower, 1));
		assertEquals(0, SlopedTops.corner(upper, 0));
		assertEquals(2, SlopedTops.corner(upper, 1));
		assertFalse(SlopedTops.isFlat(lower) || SlopedTops.isNone(lower) || SlopedTops.isNone(upper));
	}
}
