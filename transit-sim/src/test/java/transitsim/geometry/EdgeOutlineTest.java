package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EdgeOutlineTest {

	@Test
	void pastTheEnds() {
		// An edge along x from 0 to 2, then turning to go along z: past its first point before x = 0, past its last
		// beyond z = 3 along the last piece, alongside it in between (0.100.0, marking stops square at a platform's end)
		final double[] chain = {0, 0.5, 1, 0.5, 2, 0.5, 2.5, 1, 2.5, 3};
		assertTrue(EdgeOutline.pastEnds(chain, -0.3, 0.8) > 0.29);
		assertEquals(0.3, EdgeOutline.pastEnds(chain, -0.3, 0.8), 1E-9);
		assertTrue(EdgeOutline.pastEnds(chain, 1, 0.9) <= 0);
		assertTrue(EdgeOutline.pastEnds(chain, 2.3, 2) <= 0);
		assertEquals(0.5, EdgeOutline.pastEnds(chain, 2.2, 3.5), 1E-9);
		assertEquals(0, EdgeOutline.pastEnds(new double[]{1, 1}, 5, 5));
	}


	@Test
	void aStraightLineAcrossCutsTheBlock() {
		// Along x at z = 0.3: left of +x is -z
		final double[] chain = {-1, 0.3, 0, 0.3, 1, 0.3, 2, 0.3};
		final double[] left = EdgeOutline.cut(chain, true);
		final double[] right = EdgeOutline.cut(chain, false);
		assertEquals(0.3, EdgeOutline.area(left), 1E-9);
		assertEquals(0.7, EdgeOutline.area(right), 1E-9);
		for (int i = 0; i < left.length; i += 2) {
			assertTrue(left[i + 1] <= 0.3 + 1E-9);
		}
	}

	@Test
	void bothSidesMakeTheWholeBlock() {
		final double[] chain = arc(3, -2.2, 3, 0.6);
		final double[] left = EdgeOutline.cut(chain, true);
		final double[] right = EdgeOutline.cut(chain, false);
		assertNotNull(left);
		assertNotNull(right);
		assertTrue(EdgeOutline.area(left) > 0);
		assertTrue(EdgeOutline.area(right) > 0);
		assertEquals(1, EdgeOutline.area(left) + EdgeOutline.area(right), 1E-6);
	}

	@Test
	void neighbouringBlocksMeetOnTheCurve() {
		// The same arc through two blocks side by side: where it crosses their shared side, both outlines have that point
		final double[] chain = arc(3, -2.2, 3, 0.6);
		final double[] shifted = new double[chain.length];
		for (int i = 0; i < chain.length; i += 2) {
			shifted[i] = chain[i] - 1;
			shifted[i + 1] = chain[i + 1];
		}
		final double[] here = EdgeOutline.cut(chain, true);
		final double[] next = EdgeOutline.cut(shifted, true);
		assertNotNull(here);
		assertNotNull(next);
		final double zHere = pointOnSide(here, 1);
		final double zNext = pointOnSide(next, 0);
		assertFalse(Double.isNaN(zHere));
		assertEquals(zHere, zNext, 1E-9);
	}

	@Test
	void aCurveMissingTheBlockLeavesItWholeOrEmpty() {
		// Along x at z = 2: its left, -z, is where the block is
		final double[] chain = {-1, 2, 2, 2};
		assertArrayEquals(EdgeOutline.FULL, EdgeOutline.cut(chain, true), 0);
		assertEquals(0, EdgeOutline.cut(chain, false).length);
		assertEquals(1.5, EdgeOutline.depth(chain, true, 0.5, 0.5), 1E-9);
		assertEquals(-1.5, EdgeOutline.depth(chain, false, 0.5, 0.5), 1E-9);
	}

	@Test
	void aCurveEndingInsideIsLeftToTheStraightCut() {
		assertNull(EdgeOutline.cut(new double[]{-1, 0.5, 0.5, 0.5}, true));
	}

	@Test
	void outlinesGoRoundTheWayTheDrawingWants() {
		final double[] chain = {-1, -1, 2, 2};
		assertTrue(EdgeOutline.area(EdgeOutline.cut(chain, true)) > 0);
		assertTrue(EdgeOutline.area(EdgeOutline.cut(chain, false)) > 0);
		assertEquals(0.5, EdgeOutline.area(EdgeOutline.cut(chain, true)), 1E-9);
	}

	/**
	 * @return points every tenth of a block along a circle around (centreX, centreZ), crossing the block from x = -1 to 2
	 */
	private static double[] arc(double radius, double centreZ, double ignored, double centreX) {
		final int count = 400;
		final double[] chain = new double[count * 2];
		for (int i = 0; i < count; i++) {
			final double x = -1 + 3.0 * i / (count - 1);
			chain[i * 2] = x;
			chain[i * 2 + 1] = centreZ + Math.sqrt(radius * radius - (x - centreX) * (x - centreX));
		}
		return chain;
	}

	private static double pointOnSide(double[] polygon, double x) {
		for (int i = 0; i < polygon.length; i += 2) {
			if (Math.abs(polygon[i] - x) < 1E-9 && polygon[i + 1] > 1E-9 && polygon[i + 1] < 1 - 1E-9) {
				return polygon[i + 1];
			}
		}
		return Double.NaN;
	}
}
