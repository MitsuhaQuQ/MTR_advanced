package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Straightening rails along a route's path for the track diagram of a whole line.
 */
class StraightenerTest {

	private static final double TOLERANCE = 1E-3;
	// A path east from (0, 0) to (100, 0), then south to (100, 100)
	private static final float[] L_PATH = {0, 0, 50, 0, 100, 0, 100, 50, 100, 100};

	@Test
	void putsARailBesideThePathAtItsSide() {
		final Straightener straightener = new Straightener(new float[]{0, 0, 100, 0}, 24);
		assertEquals(100, straightener.length, TOLERANCE);
		// North of a path running east is on its left, which is positive
		final List<float[]> pieces = straightener.straighten(new float[]{10, -4, 90, -4}, 24);
		assertEquals(1, pieces.size());
		assertArrayEquals(new float[]{10, 4, 90, 4}, pieces.get(0), (float) TOLERANCE);
		final float[] south = straightener.straighten(new float[]{10, 4, 90, 4}, 24).get(0);
		assertEquals(-4, south[1], TOLERANCE);
	}

	@Test
	void followsThePathRoundACorner() {
		final Straightener straightener = new Straightener(L_PATH, 24);
		assertEquals(200, straightener.length, TOLERANCE);
		// A rail west of the second, southbound part: running south, west is on the right
		final float[] piece = straightener.straighten(new float[]{96, 20, 96, 80}, 24).get(0);
		assertEquals(120, piece[0], TOLERANCE);
		assertEquals(-4, piece[1], TOLERANCE);
		assertEquals(180, piece[2], TOLERANCE);
	}

	@Test
	void leavesOutRailsOutsideTheCorridor() {
		final Straightener straightener = new Straightener(L_PATH, 24);
		assertTrue(straightener.straighten(new float[]{0, 60, 50, 60}, 24).isEmpty());
	}

	@Test
	void cutsARailWhereItLeavesTheCorridor() {
		final Straightener straightener = new Straightener(new float[]{0, 0, 200, 0}, 24);
		// Beside the path, away from it, and back
		final List<float[]> pieces = straightener.straighten(new float[]{0, 10, 40, 10, 80, 60, 120, 60, 160, 10, 200, 10}, 24);
		assertEquals(2, pieces.size());
		assertEquals(0, pieces.get(0)[0], TOLERANCE);
		assertEquals(200, pieces.get(1)[pieces.get(1).length - 2], TOLERANCE);
	}

	@Test
	void putsThePathItselfOnTheMiddleLine() {
		final Straightener straightener = new Straightener(L_PATH, 24);
		final float[] piece = straightener.straighten(L_PATH, 24).get(0);
		for (int i = 1; i < piece.length; i += 2) {
			assertEquals(0, piece[i], TOLERANCE);
		}
		assertEquals(200, piece[piece.length - 2], TOLERANCE);
	}

	@Test
	void keepsAlongTheSamePartWhereThePathRunsTwice() {
		// Out east and back west along the same rails, as a route turning back at the end
		final Straightener straightener = new Straightener(new float[]{0, 0, 100, 0, 0, 0}, 24);
		final float[] out = straightener.straighten(new float[]{10, -4, 50, -4}, 24).get(0);
		assertTrue(out[2] - out[0] > 0);
		assertEquals(4, out[1], TOLERANCE);
		// A rail starting near the far end follows the way back, where the side is the other way round
		final double[] back = straightener.project(60, -4, 24, 160);
		assertEquals(140, back[0], TOLERANCE);
		assertEquals(-4, back[1], TOLERANCE);
	}

	@Test
	void givesTheSameAnswerEachTime() {
		final float[] rail = {96, 20, 96, 80};
		assertArrayEquals(new Straightener(L_PATH, 24).straighten(rail, 24).get(0), new Straightener(L_PATH, 24).straighten(rail, 24).get(0));
	}
}
