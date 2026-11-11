package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.RouteTimetable;
import transitsim.plan.ServiceExceptions;
import transitsim.plan.StopRules;
import transitsim.plan.StopTimes;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains, the duty planner and the timetable page take their times from the same rules (S3e, A09).
 */
class StopRulesTest {

	private static final int SERVICE = 8 * 60 * 60 * 1000;
	private static final int TICKS_PER_MINUTE = 1200;

	@Test
	void passingOverrideDoesNotReuseAStopsManualArrival() {
		final RouteTimetable table = new RouteTimetable();
		table.marginPercent = 0;
		final RouteTimetable.StopPlan first = new RouteTimetable.StopPlan(0, 0, -1, -1, false);
		final RouteTimetable.StopPlan pass = new RouteTimetable.StopPlan(60000, 90000, 0, 120, false);
		pass.exceptionPass = 1;
		final RouteTimetable.StopPlan last = new RouteTimetable.StopPlan(150000, 150000, -1, -1, false);
		table.applyPlan(java.util.Arrays.asList(first, pass, last));
		assertEquals(60000, pass.arrivalMillis);
		assertEquals(pass.arrivalMillis, pass.departureMillis);
		pass.exceptionRunMillis = 75000;
		table.applyPlan(java.util.Arrays.asList(first, pass, last));
		assertEquals(75000, pass.arrivalMillis);
	}

	@Test
	void trainsAndThePageGetTheSameTimes() {
		final TestRoute route = makeRoute();
		final StopTimes stopTimes = makeStopTimes();
		final List<TestRoute> routes = Collections.singletonList(route);
		final long[][] fastest = stopTimes.getFastestTimes(route, routes);

		assertSameTimes(route, stopTimes, routes, fastest, -1, null);

		// One service runs slower to the second stop, stops longer at the third and passes the fourth
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(150, -1, -1));
		stopExceptions.put(2, exception(-1, 60, -1));
		stopExceptions.put(3, exception(-1, -1, 1));
		route.timetable.exceptions.put(SERVICE, stopExceptions);
		route.timetable.revision++;
		assertSameTimes(route, stopTimes, routes, fastest, SERVICE, stopExceptions);
	}

	@Test
	void theRulesSayWhatEachStopMeans() {
		final TestRoute route = makeRoute();
		final long[][] fastest = makeStopTimes().getFastestTimes(route, Collections.singletonList(route));
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		// Planned to reach the second stop a minute after leaving the first, which takes two minutes at the fastest
		stopExceptions.put(1, exception(60, -1, -1));
		stopExceptions.put(3, exception(-1, -1, 1));
		final ServiceExceptions.StopException overtake = new ServiceExceptions.StopException();
		overtake.overtake = 1;
		stopExceptions.put(2, overtake);

		final List<StopRules.StopTime> times = StopRules.times(route.getStops(), fastest, 0, 0, stopExceptions);
		assertTrue(times.get(1).tooFast);
		assertFalse(times.get(2).tooFast);
		assertTrue(times.get(2).overtake);
		assertTrue(times.get(3).passes);
		assertEquals(times.get(3).arrivalMillis, times.get(3).departureMillis);
		// The last stop is never passed
		stopExceptions.put(4, exception(-1, -1, 1));
		assertFalse(StopRules.times(route.getStops(), fastest, 0, 0, stopExceptions).get(4).passes);
	}

	private static void assertSameTimes(TestRoute route, StopTimes stopTimes, List<TestRoute> routes, long[][] fastest, int service, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final List<RouteTimetable.StopPlan> plans = StopRules.plan(route.getStops(), fastest, route.timetable.marginPercent, 0, stopExceptions);
		for (int i = 0; i < plans.size(); i++) {
			// The stops of the path are at rails 1 to 5
			final RouteTimetable.StopPlan trainPlan = stopTimes.getPlan(routes, route.timetable, i + 1, route.id, 0, service);
			assertEquals(plans.get(i).arrivalMillis, trainPlan.arrivalMillis, "arrival at stop " + i);
			assertEquals(plans.get(i).departureMillis, trainPlan.departureMillis, "departure at stop " + i);
		}
	}

	private static TestRoute makeRoute() {
		final TestRoute route = new TestRoute(1, 1, 2, 3, 4, 5);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 10;
		route.timetable.services.add(SERVICE);
		route.timetable.sanitize();
		return route;
	}

	/**
	 * Five stops two minutes apart, stopping 20 seconds at each.
	 */
	private static StopTimes makeStopTimes() {
		final long[][] stops = new long[5][];
		for (int i = 0; i < stops.length; i++) {
			final long arrival = (long) i * 2 * TICKS_PER_MINUTE;
			stops[i] = new long[]{i + 1, i + 1, arrival, arrival + 400};
		}
		return StopTimes.of(stops);
	}

	private static ServiceExceptions.StopException exception(int runSeconds, int dwellSeconds, int pass) {
		final ServiceExceptions.StopException stopException = new ServiceExceptions.StopException();
		stopException.runSeconds = runSeconds;
		stopException.dwellSeconds = dwellSeconds;
		stopException.pass = pass;
		return stopException;
	}
}
