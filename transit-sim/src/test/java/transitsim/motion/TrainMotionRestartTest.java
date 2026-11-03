package transitsim.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A train held back on the way to its next stop, then let go (S2b, review A04): with the new rules it sets a cruising
 * cap from where it is and still arrives on time if it can; before, it got none and arrived early.
 */
class TrainMotionRestartTest {

	private static final int TICKS = 6000;
	private static final int SPARE_TICKS = 1500;
	private static final int HELD_TICKS = 300;

	@Test
	void arrivesOnTimeAfterBeingHeldBack() {
		// Where the train stops when nothing is in the way, and when it would arrive at the second station
		final MotionFixture free = line(-1, -1);
		final int freeArrival = run(free);
		assertTrue(freeArrival > 0);
		final int planned = freeArrival + SPARE_TICKS;

		// Held back on the way, for a while
		final MotionFixture probe = line(planned, -1);
		final int heldFrom = tickNearlyThere(probe);
		final MotionFixture held = line(planned, heldFrom);
		final int arrival = run(held);
		assertEquals(planned, arrival, 3, "arrives on time");

		final MotionFixture before = line(planned, heldFrom);
		before.capFromState = false;
		final int arrivalBefore = run(before);
		assertTrue(arrivalBefore < planned - 50, "with no cap after being held back, it arrived early: " + arrivalBefore + " for " + planned);
	}

	@Test
	void runsAsFastAsItCanWhenLate() {
		final MotionFixture free = line(-1, -1);
		final int freeArrival = run(free);
		// Planned too soon to make: no cap after setting off again, so it is no later than it has to be
		final MotionFixture probe = line(freeArrival, -1);
		final int heldFrom = tickNearlyThere(probe);
		final MotionFixture held = line(freeArrival, heldFrom);
		held.capFromState = true;
		final MotionFixture heldBefore = line(freeArrival, heldFrom);
		heldBefore.capFromState = false;
		assertEquals(run(heldBefore), run(held), 1);
	}

	/**
	 * A siding, a station, a long stretch in two rails and a second station.
	 */
	private static MotionFixture line(int plannedArrivalTick, int heldFrom) {
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
		fixture.blockedAt = (tick, rail) -> heldFrom >= 0 && rail == 4 && tick >= heldFrom && tick < heldFrom + HELD_TICKS;
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

	/**
	 * @return the tick the train is nearly at the end of the first rail of the long stretch, so the rail after it is taken
	 * just in front of it
	 */
	private static int tickNearlyThere(MotionFixture fixture) {
		final double nearlyThere = fixture.getDistance(3) - 30;
		for (int i = 0; i < TICKS; i++) {
			fixture.step(false, 1);
			if (fixture.railProgress > nearlyThere && fixture.stopsReached == 1) {
				return fixture.tick;
			}
		}
		fail("never got there");
		return -1;
	}
}
