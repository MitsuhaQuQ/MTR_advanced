package mtr.data;

import transitsim.plan.RouteTimetable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trip joining a route part of the way along keeps to that route's services, which count from its first stop.
 */
class RouteEntryTest {

	private static Route makeRoute(long id, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final long platformId : platformIds) {
			route.platformIds.add(new Route.RoutePlatform(platformId));
		}
		route.timetable.marginPercent = 0;
		return route;
	}

	@Test
	void theJoinedRouteStartsBeforeTheStopWhereTheTrainJoins() {
		final Route first = makeRoute(1, 1, 2);
		final Route joined = makeRoute(2, 10, 11, 12, 13);
		final List<Route> routes = Arrays.asList(first, joined);

		// Rail, platform, and arrival and departure in ticks: the first route, then the joined route from its third stop
		final Siding.StopTimes stopTimes = Siding.StopTimes.of(new long[]{0, 1, 0, 20}, new long[]{10, 2, 100, 120}, new long[]{30, 12, 300, 320}, new long[]{50, 13, 500, 500});
		// The joined route takes three minutes from its first stop to the third
		stopTimes.setEntry(2, 2, new long[][]{{0, 0}, {60000, 90000}, {180000, 210000}});

		final RouteTimetable.StopPlan joinPlan = stopTimes.getPlan(routes, first.timetable, 30, 1, 0, -1);
		assertTrue(joinPlan.startsRoute);
		assertEquals(15000, joinPlan.arrivalMillis);
		assertEquals(15000 - 180000, joinPlan.routeStartMillis);

		// A time set in the joined route's table counts from its first stop too
		joined.platformIds.get(3).setArrival(300);
		joined.timetable.revision++;
		final RouteTimetable.StopPlan lastPlan = stopTimes.getPlan(routes, first.timetable, 50, 1, 0, -1);
		assertEquals(15000 - 180000 + 300000, lastPlan.arrivalMillis);
	}
}
