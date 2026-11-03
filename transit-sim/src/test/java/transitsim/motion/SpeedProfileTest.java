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

	@Test
	void startingFromStandingIsTheSameRun() {
		final double[] breaks = {0, 300, 400, 700};
		final double[] limits = {1, 0.3, 1};
		final SpeedProfile before = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, 0.8);
		final SpeedProfile after = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, 0.8, 0);
		assertEquals(before.getDuration(), after.getDuration());
		for (double position = 0; position <= 700; position += 7.5) {
			assertEquals(before.getSpeed(position), after.getSpeed(position));
		}
	}

	@Test
	void setsOffAtTheSpeedItHas() {
		final double[] breaks = {100, 300, 400, 700};
		final double[] limits = {1, 0.3, 1};
		final SpeedProfile profile = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY, 0.2);
		assertEquals(0.2, profile.getSpeed(100), TOLERANCE);
		assertEquals(0, profile.getSpeed(700), TOLERANCE);
		assertSmooth(profile, breaks, limits);
		// Already moving, it gets there sooner than from standing
		assertTrue(profile.getDuration() < SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY).getDuration());
	}

	@Test
	void brakesDownToTheLimitAtItsOwnRate() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 1000}, new double[]{0.5}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY, 0.8);
		assertEquals(0.8, profile.getSpeed(0), TOLERANCE);
		for (double position = 0; position < 50; position += 2.5) {
			assertEquals(Math.sqrt(Math.max(0.25, 0.64 - 2 * DECELERATION * position)), profile.getSpeed(position), TOLERANCE);
		}
		assertEquals(0.5, profile.getSpeed(500), TOLERANCE);
		// A cap below its speed works the same way
		final SpeedProfile capped = SpeedProfile.build(new double[]{0, 1000}, new double[]{1}, ACCELERATION, DECELERATION, 0.5, 0.8);
		assertEquals(0.8, capped.getSpeed(0), TOLERANCE);
		assertEquals(0.5, capped.getSpeed(500), TOLERANCE);
	}

	@Test
	void tooFastToStopStartsAsFastAsItCouldStillStop() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 10}, new double[]{1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY, 0.9);
		assertEquals(Math.sqrt(2 * DECELERATION * 10), profile.getSpeed(0), TOLERANCE);
		assertEquals(0, profile.getSpeed(10), TOLERANCE);
	}

	@Test
	void findsACapFromTheSpeedItHas() {
		final double[] breaks = {200, 1000};
		final double[] limits = {1};
		final double fastest = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY, 0.3).getDuration();
		final double cap = SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest + 150, 0.1, 0.3);
		assertTrue(cap < 1);
		assertEquals(fastest + 150, SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, cap, 0.3).getDuration(), 1);
		// Starting at 0 finds the same cap as before
		assertEquals(SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, 900, 0.1), SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, 900, 0.1, 0));
	}

	/**
	 * Never over the limit, and the speed only changes as fast as the train can speed up or brake.
	 */
	private static void assertSmooth(SpeedProfile profile, double[] breaks, double[] limits) {
		double lastPosition = profile.startPosition;
		double lastSquared = Math.pow(profile.getSpeed(lastPosition), 2);
		for (double position = profile.startPosition + 0.5; position <= profile.endPosition; position += 0.5) {
			final double speed = profile.getSpeed(position);
			for (int i = 0; i < limits.length; i++) {
				if (position > breaks[i] && position < breaks[i + 1]) {
					assertTrue(speed <= limits[i] + TOLERANCE, "over the limit at " + position);
				}
			}
			final double squared = speed * speed;
			assertTrue(squared - lastSquared <= 2 * ACCELERATION * (position - lastPosition) + TOLERANCE, "speeds up too fast at " + position);
			assertTrue(lastSquared - squared <= 2 * DECELERATION * (position - lastPosition) + TOLERANCE, "brakes too hard at " + position);
			lastPosition = position;
			lastSquared = squared;
		}
	}
}
