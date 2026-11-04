package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.StopTimes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The fastest times of a route's stops, as the timetable page shows them.
 */
class FastestTimesTest {

	private static final int TICKS_PER_MINUTE = 1200;
	private static final long MILLIS_PER_TICK = 50;

	@Test
	void aRoutesOwnPathCountsFromLeavingItsFirstStop() {
		// The route's own path starts standing at platform 1, which isn't one of its stops; platform 2 is reached 2 minutes
		// later and left after 30 seconds, platform 3 at 4 minutes 5 seconds
		final StopTimes stopTimes = StopTimes.of(
				new long[]{3, 2, 2 * TICKS_PER_MINUTE, 2 * TICKS_PER_MINUTE + 600},
				new long[]{6, 3, 4 * TICKS_PER_MINUTE + 100, 4 * TICKS_PER_MINUTE + 100}
		);
		final TestRoute route = new TestRoute(1, 1, 2, 3);
		final long[][] times = stopTimes.getFastestTimesFromFirstStop(route);
		assertArrayEquals(new long[]{0, 0}, times[0]);
		assertArrayEquals(new long[]{2 * TICKS_PER_MINUTE * MILLIS_PER_TICK, (2 * TICKS_PER_MINUTE + 600) * MILLIS_PER_TICK}, times[1]);
		assertArrayEquals(new long[]{(4 * TICKS_PER_MINUTE + 100) * MILLIS_PER_TICK, (4 * TICKS_PER_MINUTE + 100) * MILLIS_PER_TICK}, times[2]);

		// Counted from the first stop the path has, the run to the second stop was lost
		final long[][] fromFound = stopTimes.getFastestTimes(route, Collections.singletonList(route));
		assertArrayEquals(new long[]{0, 600 * MILLIS_PER_TICK}, fromFound[1]);
	}

	@Test
	void aPathThatStopsAtTheFirstStopCountsAsBefore() {
		// A depot's path arrives at platform 1 from the siding
		final StopTimes stopTimes = StopTimes.of(
				new long[]{2, 1, TICKS_PER_MINUTE, TICKS_PER_MINUTE + 400},
				new long[]{5, 2, 3 * TICKS_PER_MINUTE, 3 * TICKS_PER_MINUTE + 400}
		);
		final TestRoute route = new TestRoute(1, 1, 2);
		final long[][] times = stopTimes.getFastestTimesFromFirstStop(route);
		assertArrayEquals(new long[]{0, 400 * MILLIS_PER_TICK}, times[0]);
		assertArrayEquals(new long[]{2 * TICKS_PER_MINUTE * MILLIS_PER_TICK, (2 * TICKS_PER_MINUTE + 400) * MILLIS_PER_TICK}, times[1]);
	}
}
