package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformEdgesTest {

	@Test
	void theMiddleOfACarStandsInTowardsAPlatformOnTheInsideOfACurve() {
		// A curve of radius 100 round the origin, the point at (100, 0), half a car (12.5 blocks of arc) each way
		final double radius = 100;
		final double half = 12.5 / radius;
		final double prevX = radius * Math.cos(-half), prevZ = radius * Math.sin(-half);
		final double nextX = radius * Math.cos(half), nextZ = radius * Math.sin(half);
		// Inside: towards the centre
		final double inside = PlatformEdges.signedSagitta(prevX, prevZ, radius, 0, nextX, nextZ, -1, 0);
		final double outside = PlatformEdges.signedSagitta(prevX, prevZ, radius, 0, nextX, nextZ, 1, 0);
		assertEquals(PlatformEdges.sagitta(25, radius), inside, 0.01);
		assertEquals(-inside, outside, 1E-9);
		assertEquals(0, PlatformEdges.signedSagitta(-12.5, 0, 0, 0, 12.5, 0, 0, 1), 1E-12);
	}

	@Test
	void anInsideEdgeMovesOutByTheSagittaAndTheGapGrowsByItEitherWay() {
		assertEquals(1 + PlatformEdges.STANDARD_GAP + 0.3, PlatformEdges.edgeOffset(1, 0.3), 1E-12);
		assertEquals(1 + PlatformEdges.STANDARD_GAP, PlatformEdges.edgeOffset(1, -0.3), 1E-12);
		assertEquals(PlatformEdges.STANDARD_GAP + 0.3, PlatformEdges.largestGap(-0.3), 1E-12);
		// A 25 block car: a gap of a block (half a block more than usual) on a radius of 156.25
		assertEquals(156.25, PlatformEdges.smallestRadius(25), 1E-9);
		assertEquals(PlatformEdges.WARN_EXTRA_GAP, PlatformEdges.sagitta(25, PlatformEdges.smallestRadius(25)), 1E-9);
	}

	@Test
	void aStraightEdgeAlongTheBlockGridCutsHalfTheBlock() {
		// Edge along z at x = 10.5, platform towards +x
		final int cut = PlatformEdges.cutOf(10, 3, 10.5, 3.2, 1, 0);
		assertTrue(cut >= 0);
		final boolean[] mask = PlatformEdges.mask(PlatformEdges.angleOf(cut), PlatformEdges.cutOfPacked(cut));
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				assertEquals(x >= 8, mask[x * 16 + z], x + " " + z);
			}
		}
		// Wholly platform, wholly track
		assertEquals(PlatformEdges.FULL, PlatformEdges.cutOf(11, 3, 10.5, 3, 1, 0));
		assertEquals(PlatformEdges.EMPTY, PlatformEdges.cutOf(9, 3, 10.5, 3, 1, 0));
	}

	@Test
	void aDiagonalEdgeCutsCornerToCorner() {
		// Edge through (0, 0) and (1, 1), platform towards +x -z
		final int cut = PlatformEdges.cutOf(0, 0, 0.5, 0.5, 1, -1);
		final boolean[] mask = PlatformEdges.mask(PlatformEdges.angleOf(cut), PlatformEdges.cutOfPacked(cut));
		int platform = 0;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				if (x != z) {
					assertEquals(x > z, mask[x * 16 + z], x + " " + z);
				}
				platform += mask[x * 16 + z] ? 1 : 0;
			}
		}
		assertTrue(platform >= 120 && platform <= 136, String.valueOf(platform));
	}

	@Test
	void theOutlineEndsWithTheCut() {
		final int cut = PlatformEdges.cutOf(10, 3, 10.5, 3.2, 1, 0);
		final double[] outline = PlatformEdges.outline(PlatformEdges.angleOf(cut), PlatformEdges.cutOfPacked(cut));
		assertEquals(8, outline.length);
		final int n = outline.length;
		// The last two corners are on the cut, x = 0.5
		assertEquals(0.5, outline[n - 4], 1E-9);
		assertEquals(0.5, outline[n - 2], 1E-9);
		for (int i = 0; i < n; i += 2) {
			assertTrue(outline[i] >= 0.5 - 1E-9, String.valueOf(outline[i]));
		}
	}
}
