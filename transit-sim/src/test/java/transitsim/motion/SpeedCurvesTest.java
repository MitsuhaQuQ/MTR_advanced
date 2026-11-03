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
	void needsEveryValueForEveryRail() {
		assertThrows(IllegalArgumentException.class, () -> new TrackView(new double[]{1, 2}, new double[]{1}, new boolean[2], new boolean[2], 0.1));
	}
}
