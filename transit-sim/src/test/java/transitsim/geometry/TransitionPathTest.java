package transitsim.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionPathTest {

	private static final double QUARTER = Math.PI / 2;

	@Test
	void aClothoidLandsWhereTheSeriesSays() {
		// Curvature from 0 to 1/50 over 20 blocks: A^2 = R L = 1000
		final double length = 20;
		final double a2 = 1000;
		final TransitionPath path = new TransitionPath.Builder(0, 0, 0).transition(0, 1D / 50, length).build();
		final double[] end = path.getPoint(length);
		final double expectedX = length - Math.pow(length, 5) / (40 * a2 * a2) + Math.pow(length, 9) / (3456 * Math.pow(a2, 4));
		final double expectedZ = Math.pow(length, 3) / (6 * a2) - Math.pow(length, 7) / (336 * Math.pow(a2, 3)) + Math.pow(length, 11) / (42240 * Math.pow(a2, 5));
		assertEquals(expectedX, end[0], 1E-6);
		assertEquals(expectedZ, end[1], 1E-6);
		assertEquals(length * length / (2 * 50 * length), end[2], 1E-12);
	}

	@Test
	void aTurnKeepsItsEndsAndHasNoCurvatureJumps() {
		final TransitionPath.Builder builder = new TransitionPath.Builder(0, 0, 0);
		assertTrue(builder.turn(50, 60, QUARTER, 20, 20));
		final TransitionPath path = builder.build();
		final double[] end = path.getPoint(path.getLength());
		assertEquals(50, end[0], 1E-4);
		assertEquals(60, end[1], 1E-4);
		assertEquals(QUARTER, end[2], 1E-4);
		assertTrue(path.describe().contains("TAT"), path.describe());
		assertEquals(0, path.getCurvature(0), 1E-12);
		assertEquals(0, path.getCurvature(path.getLength()), 1E-12);
		assertSmooth(path);
		// Not sharper than the smallest radius
		for (double s = 0; s <= path.getLength(); s += 0.25) {
			assertTrue(Math.abs(path.getCurvature(s)) <= 1 / TransitionPath.MIN_RADIUS + 1E-9);
		}
	}

	@Test
	void aTurnTheOtherWayMirrors() {
		final TransitionPath.Builder left = new TransitionPath.Builder(0, 0, 0);
		final TransitionPath.Builder right = new TransitionPath.Builder(0, 0, 0);
		assertTrue(left.turn(40, 30, QUARTER, 15, 10));
		assertTrue(right.turn(40, -30, -QUARTER, 15, 10));
		final TransitionPath pathLeft = left.build();
		final TransitionPath pathRight = right.build();
		assertEquals(pathLeft.getLength(), pathRight.getLength(), 1E-9);
		for (double s = 0; s <= pathLeft.getLength(); s += 1) {
			assertEquals(pathLeft.getPoint(s)[0], pathRight.getPoint(s)[0], 1E-9);
			assertEquals(pathLeft.getPoint(s)[1], -pathRight.getPoint(s)[1], 1E-9);
		}
	}

	@Test
	void theRailBackIsTheSameShape() {
		final TransitionPath.Builder forwards = new TransitionPath.Builder(0, 0, 0);
		assertTrue(forwards.turn(70, 25, Math.toRadians(45), 30, 12));
		final TransitionPath.Builder backwards = new TransitionPath.Builder(70, 25, Math.toRadians(45) + Math.PI);
		assertTrue(backwards.turn(0, 0, Math.PI, 12, 30));
		final TransitionPath pathForwards = forwards.build();
		final TransitionPath pathBackwards = backwards.build();
		assertEquals(pathForwards.getLength(), pathBackwards.getLength(), 1E-6);
		for (double s = 0; s <= pathForwards.getLength(); s += 0.5) {
			final double[] a = pathForwards.getPoint(s);
			final double[] b = pathBackwards.getPoint(pathBackwards.getLength() - s);
			assertEquals(a[0], b[0], 1E-5);
			assertEquals(a[1], b[1], 1E-5);
		}
	}

	@Test
	void transitionsAreShortenedToFit() {
		final TransitionPath.Builder builder = new TransitionPath.Builder(0, 0, 0);
		assertTrue(builder.turn(8, 8, QUARTER, 50, 50));
		final TransitionPath path = builder.build();
		final double[] end = path.getPoint(path.getLength());
		assertEquals(8, end[0], 1E-4);
		assertEquals(8, end[1], 1E-4);
		assertSmooth(path);
		for (double s = 0; s <= path.getLength(); s += 0.1) {
			assertTrue(Math.abs(path.getCurvature(s)) <= 1 / TransitionPath.MIN_RADIUS + 1E-9);
		}
	}

	@Test
	void oneEndOnlyHasATransition() {
		final TransitionPath.Builder builder = new TransitionPath.Builder(0, 0, 0);
		assertTrue(builder.turn(60, 40, QUARTER, 25, 0));
		final TransitionPath path = builder.build();
		assertEquals(0, path.getCurvature(0), 1E-12);
		assertTrue(Math.abs(path.getCurvature(path.getLength())) > 0.01);
	}

	@Test
	void whatIsNotOneTurnIsLeftAlone() {
		// No transition asked for
		assertFalse(new TransitionPath.Builder(0, 0, 0).turn(50, 50, QUARTER, 0, 0));
		// Almost turning round
		assertFalse(new TransitionPath.Builder(0, 0, 0).turn(0, 20, Math.toRadians(179), 10, 10));
		// The end is behind the start
		assertFalse(new TransitionPath.Builder(0, 0, 0).turn(-30, 30, QUARTER, 10, 10));
		// Straight on
		assertFalse(new TransitionPath.Builder(0, 0, 0).turn(30, 0, 0, 10, 10));
	}

	@Test
	void aLongerTransitionGivesAGentlerStart() {
		final TransitionPath.Builder shortOne = new TransitionPath.Builder(0, 0, 0);
		final TransitionPath.Builder longOne = new TransitionPath.Builder(0, 0, 0);
		assertTrue(shortOne.turn(100, 100, QUARTER, 5, 5));
		assertTrue(longOne.turn(100, 100, QUARTER, 40, 40));
		// Without transitions this turn is a quarter circle of radius 100; with them the arc moves in and gets tighter
		final TransitionPath path = longOne.build();
		double maxCurvature = 0;
		for (double s = 0; s <= path.getLength(); s += 0.5) {
			maxCurvature = Math.max(maxCurvature, path.getCurvature(s));
		}
		assertTrue(maxCurvature > 1D / 100);
		assertTrue(path.getCurvature(10) < shortOne.build().getCurvature(10) + 1E-9);
	}

	private static void assertSmooth(TransitionPath path) {
		double[] previous = path.getPoint(0);
		double previousCurvature = path.getCurvature(0);
		for (double s = 0.05; s <= path.getLength(); s += 0.05) {
			final double[] point = path.getPoint(s);
			// Neighbouring points are 0.05 apart and the heading turns no more than the curvature allows
			assertEquals(0.05, Math.hypot(point[0] - previous[0], point[1] - previous[1]), 1E-4);
			final double curvature = path.getCurvature(s);
			assertTrue(Math.abs(curvature - previousCurvature) < 0.02, "curvature jumps at " + s);
			previous = point;
			previousCurvature = curvature;
		}
	}
}
