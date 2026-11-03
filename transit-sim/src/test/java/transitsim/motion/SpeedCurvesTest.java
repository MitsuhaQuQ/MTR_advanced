package transitsim.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Speed curves read from a {@link TrackView}, without a host's paths.
 */
class SpeedCurvesTest {

	private static final double ACCELERATION = 0.01;
	private static final double DECELERATION = 0.02;
	private static final double TOLERANCE = 1E-6;

	@Test
	void startsAgainATrainLengthOnAfterReversing() {
		// A siding, a line, a platform where the train turns back, the same platform the other way, and a line on
		final TrackView track = new TrackView(
				new double[]{20, 100, 40, 40, 100},
				new double[]{-1, 0.5, -1, -1, 0.5},
				new boolean[]{false, false, true, false, false},
				new boolean[]{false, false, true, false, false},
				0.1
		);
		final SpeedCurves curves = SpeedCurves.of(track, 15, 30, ACCELERATION, DECELERATION);
		assertEquals(2, curves.runs.size());
		assertEquals(160, curves.runs.get(0).fastest.endPosition, TOLERANCE);
		assertEquals(190, curves.runs.get(1).fastest.startPosition, TOLERANCE);
		assertSame(curves.runs.get(1), curves.getRun(160));
	}

	@Test
	void keepsTheLimitBeforeRailsATrainCantSpeedUpOn() {
		// The platform keeps the slow limit of the rail before it, and the siding the lowest speed
		final TrackView track = new TrackView(
				new double[]{20, 100, 40},
				new double[]{-1, 0.2, -1},
				new boolean[]{false, false, true},
				new boolean[]{false, false, false},
				0.1
		);
		final SpeedProfile profile = SpeedCurves.of(track, 0, 10, ACCELERATION, DECELERATION).runs.get(0).fastest;
		assertTrue(profile.getSpeed(10) <= 0.1 + TOLERANCE);
		assertTrue(profile.getSpeed(130) <= 0.2 + TOLERANCE);
		assertEquals(0, profile.getSpeed(160), TOLERANCE);
	}

	@Test
	void givesTheRestOfARunFromPartOfTheWayAlong() {
		final SpeedCurves curves = SpeedCurves.of(line(), 10, 20, ACCELERATION, DECELERATION);
		final SpeedCurves.Run run = curves.runs.get(0);
		// From the start at standing it is the same run
		assertEquals(run.fastest.getDuration(), run.from(run.fastest.startPosition, 0, Double.POSITIVE_INFINITY).getDuration(), TOLERANCE);
		// From part of the way along, it starts there at that speed, keeps the limits and stops at the same place
		final SpeedProfile rest = run.from(250, 0.1, Double.POSITIVE_INFINITY);
		assertEquals(250, rest.startPosition, TOLERANCE);
		assertEquals(run.fastest.endPosition, rest.endPosition, TOLERANCE);
		assertEquals(0.1, rest.getSpeed(250), TOLERANCE);
		for (double position = 260; position < rest.endPosition; position += 10) {
			assertTrue(rest.getSpeed(position) <= run.fastest.getSpeed(position) + 0.1 + TOLERANCE, "faster than the limits allow at " + position);
		}
		// Nothing is left past the end
		assertEquals(0, run.from(run.fastest.endPosition, 0.1, Double.POSITIVE_INFINITY).getDuration(), TOLERANCE);
	}

	@Test
	void findsACruisingCapForATrainSettingOffAgainOnTheWay() {
		final SpeedCurves curves = SpeedCurves.of(line(), 10, 20, ACCELERATION, DECELERATION);
		final SpeedCurves.Run run = curves.runs.get(0);
		final double restTicks = run.from(250, 0.01, Double.POSITIVE_INFINITY).getDuration();
		final double cap = curves.findCruiseCap(250, 0.01, run.stopRailIndex, restTicks + 100);
		assertTrue(cap < 0.5);
		assertEquals(restTicks + 100, run.from(250, 0.01, cap).getDuration(), 1);
		// Late, it runs as fast as it can; at the start of the run it is the same cap as before
		assertTrue(Double.isInfinite(curves.findCruiseCap(250, 0.01, run.stopRailIndex, restTicks - 10)));
		assertEquals(run.findCap(800, curves.minSpeed), curves.findCruiseCap(run.fastest.startPosition, 0.01, run.stopRailIndex, 800));
		// A train heading somewhere else has no cap
		assertTrue(Double.isInfinite(curves.findCruiseCap(250, 0.01, run.stopRailIndex + 1, restTicks + 100)));
	}

	/**
	 * A siding, a fast stretch, a slower one and a platform.
	 */
	private static TrackView line() {
		return new TrackView(
				new double[]{20, 300, 200, 60},
				new double[]{-1, 0.5, 0.3, -1},
				new boolean[]{false, false, false, true},
				new boolean[]{false, false, false, false},
				0.1
		);
	}

	@Test
	void needsEveryValueForEveryRail() {
		assertThrows(IllegalArgumentException.class, () -> new TrackView(new double[]{1, 2}, new double[]{1}, new boolean[2], new boolean[2], 0.1));
	}
}
