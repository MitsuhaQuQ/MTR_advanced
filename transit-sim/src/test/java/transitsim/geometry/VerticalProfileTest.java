package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerticalProfileTest {

	private static final double DELTA = 1E-6;

	@Test
	void endsAreAtTheirHeightsAndGrades() {
		final double[][] cases = {
				{200, 64, 70, 0, 0, 500, 500},
				{200, 64, 70, 0.02, 0.04, 500, 500},
				{120, 10, 4, 0.01, -0.03, 300, 800},
				{50, 0, 5, 0, 0, 500, 500},
				{30, 0, 0, 0.05, 0.05, 500, 500},
				{400, 0, -12, -0.02, 0, 2000, 100},
		};
		for (final double[] c : cases) {
			final VerticalProfile profile = VerticalProfile.of(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
			assertEquals(c[1], profile.getY(0), DELTA);
			assertEquals(c[2], profile.getY(c[0]), DELTA);
			assertEquals(c[3], profile.getGrade(0), DELTA);
			assertEquals(c[4], profile.getGrade(c[0]), DELTA);
			assertSmooth(profile, c[0], Math.min(c[5], c[6]) * profile.getRadiusScale());
		}
	}

	@Test
	void aLongRampIsAConstantGradeBetweenTwoCurves() {
		// 10 up over 400 from level to level: about 2.5%, with curves of 500 * grade at both ends
		final VerticalProfile profile = VerticalProfile.of(400, 0, 10, 0, 0, 500, 500);
		assertTrue(profile.fitsRadius());
		final double grade = profile.getMiddleGrade();
		assertEquals(500 * grade, profile.getCurveStart(), DELTA);
		assertEquals(500 * grade, profile.getCurveEnd(), DELTA);
		// The curves lose a little height each, so the middle is a bit steeper than the mean grade
		assertTrue(grade > 10 / 400D);
		assertEquals(grade * 400 - 500 * grade * grade, 10, DELTA);
		assertEquals(grade, profile.getGrade(200), DELTA);
		assertEquals(5, profile.getY(200), DELTA);
	}

	@Test
	void aRailOnAConstantGradeStaysStraight() {
		final VerticalProfile profile = VerticalProfile.of(100, 0, 3, 0.03, 0.03, 500, 500);
		assertEquals(0.03, profile.getMiddleGrade(), DELTA);
		assertEquals(0, profile.getCurveStart(), DELTA);
		for (int s = 0; s <= 100; s += 10) {
			assertEquals(0.03 * s, profile.getY(s), DELTA);
		}
	}

	@Test
	void shortRailsUseSmallerRadii() {
		// 5 up over 50 from level to level can't be done with curves of 500
		final VerticalProfile profile = VerticalProfile.of(50, 0, 5, 0, 0, 500, 500);
		assertFalse(profile.fitsRadius());
		assertTrue(profile.getRadiusScale() > 0 && profile.getRadiusScale() < 1);
		assertEquals(50, profile.getCurveStart() + profile.getCurveEnd(), 1E-3);
		assertEquals(2.5, profile.getY(25), 1E-3);
	}

	@Test
	void gradesAndRadiiAreKeptInBounds() {
		assertEquals(VerticalProfile.MIN_RADIUS, VerticalProfile.clampRadius(0));
		assertEquals(VerticalProfile.MAX_RADIUS, VerticalProfile.clampRadius(1E9));
		assertEquals(VerticalProfile.DEFAULT_RADIUS, VerticalProfile.clampRadius(Double.NaN));
		assertEquals(VerticalProfile.MAX_GRADE, VerticalProfile.clampGrade(5));
		assertEquals(0, VerticalProfile.clampGrade(Double.POSITIVE_INFINITY));
		final VerticalProfile empty = VerticalProfile.of(0, 3, 3, 0, 0, 500, 500);
		assertEquals(3, empty.getY(0), DELTA);
	}

	@Test
	void autoNodeGradeAveragesBothSides() {
		// Level on the back side, 4% up on the front side: 2% at the node
		assertEquals(0.02, VerticalProfile.autoNodeGrade(new double[]{0, 0.04}, new boolean[]{false, true}, false), DELTA);
		// Going away on the back side downhill is uphill the way the node faces
		assertEquals(0.03, VerticalProfile.autoNodeGrade(new double[]{-0.03, 0.03}, new boolean[]{false, true}, false), DELTA);
		// A turnout: the branches on one side are averaged first
		assertEquals(0.015, VerticalProfile.autoNodeGrade(new double[]{0.01, 0.03, -0.01}, new boolean[]{true, true, false}, false), DELTA);
		// One side only
		assertEquals(-0.05, VerticalProfile.autoNodeGrade(new double[]{0.05}, new boolean[]{false}, false), DELTA);
		assertEquals(0, VerticalProfile.autoNodeGrade(new double[0], new boolean[0], false), DELTA);
		// A rail of the old shape is level at its end
		assertEquals(0, VerticalProfile.autoNodeGrade(new double[]{0.02, 0.04}, new boolean[]{false, true}, true), DELTA);
	}

	/**
	 * Height and grade have no jumps, and the grade never changes faster than one over the radius.
	 */
	private static void assertSmooth(VerticalProfile profile, double length, double radius) {
		final int steps = 2000;
		final double step = length / steps;
		for (int i = 0; i < steps; i++) {
			final double s = i * step;
			final double gradeChange = Math.abs(profile.getGrade(s + step) - profile.getGrade(s));
			assertTrue(gradeChange <= step / radius + 1E-6, "grade jumps at " + s);
			final double meanGrade = (profile.getY(s + step) - profile.getY(s)) / step;
			// Exact inside one piece; a step across the start or end of a curve is off by at most a quarter of step / radius
			assertEquals((profile.getGrade(s) + profile.getGrade(s + step)) / 2, meanGrade, step / radius / 4 + 1E-9, "height jumps at " + s);
		}
	}
}
