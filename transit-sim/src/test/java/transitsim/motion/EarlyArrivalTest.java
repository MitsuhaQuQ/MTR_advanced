package transitsim.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A train with time in hand runs at full speed and spends it standing at the stop, unless it would be more than the
 * network's early threshold early; then it cruises to arrive that much early rather than on time (optimisation round 4,
 * step 5). A threshold of 0 arrives on time, as before.
 */
class EarlyArrivalTest {

	private static final int TICKS = 6000;
	private static final long THRESHOLD = 30000;
	private static final int TICKS_PER_SECOND = 20;

	@Test
	void twentySecondsInHandIsRunAtFullSpeed() {
		final int free = run(line(-1, 0));
		// 20 s to spare, under the 30 s threshold: at full speed, there 20 s early
		assertEquals(free, run(line(free + 20 * TICKS_PER_SECOND, THRESHOLD)), 2);
	}

	@Test
	void ninetySecondsInHandArrivesThirtySecondsEarly() {
		final int free = run(line(-1, 0));
		final int planned = free + 90 * TICKS_PER_SECOND;
		final int arrival = run(line(planned, THRESHOLD));
		assertEquals(planned - 30 * TICKS_PER_SECOND, arrival, 3, "the threshold early");
		assertTrue(arrival > free + 50 * TICKS_PER_SECOND, "slower than at full speed: " + arrival + " against " + free);
	}

	@Test
	void aThresholdOfNothingArrivesOnTime() {
		final int free = run(line(-1, 0));
		final int planned = free + 90 * TICKS_PER_SECOND;
		assertEquals(planned, run(line(planned, 0)), 3);
	}

	/**
	 * A siding, a station, a long stretch in two rails and a second station, as {@link TrainMotionRestartTest} has it.
	 */
	private static MotionFixture line(int plannedArrivalTick, long earlyThresholdMillis) {
		final MotionFixture fixture = new MotionFixture()
				.rail(30, 0, 0.3F)
				.rail(300, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(300, 0, 0.6F)
				.rail(300, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(200, 0, 0.4F);
		fixture.speedCurve = true;
		fixture.plannedArrivalTick = plannedArrivalTick;
		fixture.plannedStopRail = 5;
		fixture.earlyThresholdMillis = earlyThresholdMillis;
		fixture.blockedAt = (tick, rail) -> false;
		return fixture;
	}

	/**
	 * @return the tick the train reaches the second station, or -1
	 */
	private static int run(MotionFixture fixture) {
		for (int i = 0; i < TICKS; i++) {
			final int stops = fixture.stopsReached;
			fixture.step(false, 1);
			if (stops < 2 && fixture.stopsReached == 2) {
				return fixture.tick - 1;
			}
		}
		return -1;
	}
}
