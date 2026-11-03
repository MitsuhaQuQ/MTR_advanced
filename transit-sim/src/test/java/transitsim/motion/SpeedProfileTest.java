package transitsim.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeedProfileTest {

	private static final double ACCELERATION = 0.01;
	private static final double DECELERATION = 0.02;
	private static final double TOLERANCE = 1E-6;

	@Test
	void speedsUpCruisesAndBrakes() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 200}, new double[]{1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		// 50 blocks and 100 ticks to reach 1, 25 blocks and 50 ticks to stop, 125 blocks at 1 in between
		assertEquals(275, profile.getDuration(), TOLERANCE);
		assertEquals(1, profile.getSpeed(100), TOLERANCE);
		assertEquals(0, profile.getSpeed(200), TOLERANCE);
		assertEquals(100, profile.getTime(50), TOLERANCE);
		assertEquals(50, profile.getPosition(100), TOLERANCE);
	}

	@Test
	void shortRunsNeverReachTheLimit() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 30}, new double[]{1}, ACCELERATION, ACCELERATION, Double.POSITIVE_INFINITY);
		assertEquals(2 * Math.sqrt(2 * 15 / ACCELERATION), profile.getDuration(), TOLERANCE);
		assertEquals(Math.sqrt(2 * ACCELERATION * 15), profile.getSpeed(15), TOLERANCE);
	}

	@Test
	void brakesBeforeALowerLimitAhead() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 300, 400, 700}, new double[]{1, 0.3, 1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		assertTrue(profile.getSpeed(300) <= 0.3 + TOLERANCE);
		assertTrue(profile.getSpeed(290) < 1);
		assertEquals(0.3, profile.getSpeed(350), TOLERANCE);
		// Only speeds up again past the slow part
		assertEquals(0.3, profile.getSpeed(400), TOLERANCE);
		assertTrue(profile.getSpeed(410) > 0.3);
	}

	@Test
	void cruisesSlowerToArriveOnTime() {
		final double[] breaks = {0, 1000};
		final double[] limits = {1};
		final double fastest = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY).getDuration();
		final double cap = SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest + 200, 0.1);
		assertTrue(cap < 1);
		assertEquals(fastest + 200, SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, cap).getDuration(), 1);
		// A late train runs as fast as it can
		assertTrue(Double.isInfinite(SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest - 10, 0.1)));
		// It can't crawl, so a lot of spare time is spent waiting at the stop instead
		assertEquals(0.1, SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest * 100, 0.1), TOLERANCE);
	}

	@Test
	void movingTickByTickArrivesWhenWorkedOut() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 300, 400, 700}, new double[]{1, 0.3, 1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		double position = profile.startPosition;
		int ticks = 0;
		while (position < profile.endPosition - TOLERANCE) {
			position = profile.advance(position, 1);
			ticks++;
		}
		assertEquals(Math.ceil(profile.getDuration() - TOLERANCE), ticks);
	}
}
