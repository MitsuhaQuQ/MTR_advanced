package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuperelevationTest {

	private static final double STEP = 0.5;

	/**
	 * Points every STEP along a path: a straight, a transition and an arc
	 */
	private static double[][] points(boolean leftTurn) {
		final double curvature = 1D / 100 * (leftTurn ? -1 : 1);
		final TransitionPath path = new TransitionPath.Builder(0, 0, 0).line(20).transition(0, curvature, 20).arc(curvature, 30).build();
		final int count = (int) Math.floor(path.getLength() / STEP) + 1;
		final double[] xs = new double[count];
		final double[] zs = new double[count];
		for (int i = 0; i < count; i++) {
			final double[] point = path.getPoint(i * STEP);
			xs[i] = point[0];
			zs[i] = point[1];
		}
		return new double[][]{xs, zs};
	}

	@Test
	void cantFollowsCurvatureNothingOnTheStraight() {
		final double[][] points = points(false);
		final float[] rolls = Superelevation.rolls(points[0], points[1], STEP, 120, 0, 0, 0);
		// The straight (the first 20 blocks, keeping off its end): no roll
		for (int i = 0; i < 38; i++) {
			assertEquals(0, rolls[i], 1E-6, "straight at " + i);
		}
		// Along the transition it grows, never falling back
		for (int i = 42; i < 78; i++) {
			assertTrue(Math.abs(rolls[i + 1]) >= Math.abs(rolls[i]) - 1E-4, "growing at " + i);
		}
		// The arc has the whole cant: 120 mm over 1500 mm
		final double whole = Math.asin(120 / 1500D);
		assertEquals(whole, Math.abs(rolls[rolls.length - 5]), 1E-3);
	}

	@Test
	void turningTheOtherWayLeansTheOtherWay() {
		final double[][] right = points(false);
		final double[][] left = points(true);
		final float[] rightRolls = Superelevation.rolls(right[0], right[1], STEP, 100, 0, 100, 0);
		final float[] leftRolls = Superelevation.rolls(left[0], left[1], STEP, 100, 0, 100, 0);
		final int i = rightRolls.length - 5;
		assertTrue(rightRolls[i] > 0, "heading growing: positive");
		assertEquals(-rightRolls[i], leftRolls[i], 1E-5);
	}

	@Test
	void theRailBackHasTheSameLeanSeenTheOtherWay() {
		final double[][] points = points(false);
		final int count = points[0].length;
		final double[] xs = new double[count];
		final double[] zs = new double[count];
		for (int i = 0; i < count; i++) {
			xs[i] = points[0][count - 1 - i];
			zs[i] = points[1][count - 1 - i];
		}
		final float[] forwards = Superelevation.rolls(points[0], points[1], STEP, 80, 0, 150, 0);
		// The way back starts at the far node: its settings swap ends
		final float[] backwards = Superelevation.rolls(xs, zs, STEP, 150, 0, 80, 0);
		for (int i = 1; i < count - 1; i++) {
			assertEquals(-forwards[i], backwards[count - 1 - i], 1E-4, "at " + i);
		}
	}

	@Test
	void designSpeedGivesCantByRadius() {
		// 80 km/h on a radius of 400: 11.8 · 6400 / 400 = 188.8, held to 180
		assertEquals(180, Superelevation.cantOf(0, 80, 1D / 400, 1D / 400), 1E-9);
		// 60 km/h on 400: 106.2
		assertEquals(106.2, Superelevation.cantOf(0, 60, 1D / 400, 1D / 400), 1E-9);
		// A design speed wins over a cant
		assertEquals(106.2, Superelevation.cantOf(50, 60, 1D / 400, 1D / 400), 1E-9);
		// Straight: nothing
		assertEquals(0, Superelevation.cantOf(120, 60, 0, 1D / 400), 1E-9);
		// A cant on the sharpest part, half on a place half as sharp; held to the most
		assertEquals(60, Superelevation.cantOf(120, 0, 1D / 800, 1D / 400), 1E-9);
		assertEquals(180, Superelevation.cantOf(500, 0, 1D / 400, 1D / 400), 1E-9);
	}

	@Test
	void aCantTypedInDegrees() {
		// 4.6° is about 120 mm; back again the same
		assertEquals(120.3, Superelevation.cantOfDegrees(4.6), 0.1);
		assertEquals(4.6, Superelevation.degreesOf(Superelevation.cantOfDegrees(4.6)), 1E-9);
		// Held to the most, about 6.89°
		assertEquals(Superelevation.MAX_CANT, Superelevation.cantOfDegrees(10), 1E-9);
		assertEquals(6.89, Superelevation.degreesOf(Superelevation.MAX_CANT), 0.01);
		assertEquals(0, Superelevation.cantOfDegrees(0));
		assertEquals(0, Superelevation.cantOfDegrees(Double.NaN));
	}

	@Test
	void anEndWithNoSettingTakesTheOther() {
		final double[][] points = points(false);
		final float[] one = Superelevation.rolls(points[0], points[1], STEP, 0, 0, 120, 0);
		final float[] both = Superelevation.rolls(points[0], points[1], STEP, 120, 0, 120, 0);
		assertArrayEquals(both, one, 1E-6F);
		final float[] none = Superelevation.rolls(points[0], points[1], STEP, 0, 0, 0, 0);
		for (final float roll : none) {
			assertEquals(0, roll);
		}
	}
}
