package mtr.advanced;

import mtr.data.Platform;
import mtr.data.Route;
import transitsim.plan.RouteTimetable;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableBoundariesTest {

	private final Map<Long, Platform> platforms = new HashMap<>();
	private final Map<Long, Route> routes = new HashMap<>();

	private Route makeRoute(long id, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final long platformId : platformIds) {
			route.platformIds.add(new Route.RoutePlatform(platformId));
			platforms.computeIfAbsent(platformId, key -> new Platform(key, TransportMode.TRAIN, new BlockPos((int) (long) key, 0, 0), new BlockPos((int) (long) key + 10, 0, 0)));
		}
		routes.put(id, route);
		return route;
	}

	private String check(Route route, long toRoute, int fromStop, int toStop) {
		return TimetableBoundaries.checkLink(route, new RouteTimetable.ServiceLink(toRoute, 1000, fromStop, toStop), routes::get, platforms::get);
	}

	@Test
	void withoutBoundariesOnlyTheEndsLink() {
		final Route route = makeRoute(1, 1, 2, 3, 4);
		makeRoute(2, 11, 12, 13, 14);
		assertNull(check(route, 2, -1, 0));
		assertNotNull(check(route, 2, 1, 0));
		assertNotNull(check(route, 2, -1, 2));
		assertFalse(TimetableBoundaries.hasBoundaryStops(route, platforms::get));
	}

	@Test
	void boundaryPlatformsCanBeLeftAndJoined() {
		final Route route = makeRoute(1, 1, 2, 3, 4);
		makeRoute(2, 11, 12, 13, 14);
		platforms.get(2L).timetableBoundary = true;
		platforms.get(13L).timetableBoundary = true;
		assertTrue(TimetableBoundaries.hasBoundaryStops(route, platforms::get));
		assertNull(check(route, 2, 1, 2));
		// Only the marked platforms, and never the first or last stop as a middle stop
		assertNotNull(check(route, 2, 2, 0));
		assertNotNull(check(route, 2, -1, 1));
		// A service has to stop at a boundary
		route.platformIds.get(1).pass = true;
		assertNotNull(check(route, 2, 1, 0));
	}

	@Test
	void loopsKeepJoiningAtAnyStop() {
		final Route route = makeRoute(1, 1, 2, 3);
		final Route loop = makeRoute(2, 11, 12, 13, 14, 11);
		assertTrue(loop.isLoop());
		assertNull(check(route, 2, -1, 2));
		loop.platformIds.get(2).pass = true;
		assertNotNull(check(route, 2, -1, 2));
	}

	@Test
	void theServerDropsLinksThatBreakTheRules() {
		final Route route = makeRoute(1, 1, 2, 3, 4);
		makeRoute(2, 11, 12, 13, 14);
		platforms.get(2L).timetableBoundary = true;
		route.timetable.services.add(1000);
		route.timetable.services.add(2000);
		route.timetable.services.add(3000);
		route.timetable.links.put(1000, new RouteTimetable.ServiceLink(2, 5000, 1, 0));
		route.timetable.links.put(2000, new RouteTimetable.ServiceLink(2, 6000, 2, 0));
		route.timetable.links.put(3000, new RouteTimetable.ServiceLink(99, 7000, -1, 0));
		assertTrue(TimetableBoundaries.sanitizeLinks(route, routes::get, platforms::get));
		assertEquals(1, route.timetable.links.size());
		assertTrue(route.timetable.links.containsKey(1000));
		assertFalse(TimetableBoundaries.sanitizeLinks(route, routes::get, platforms::get));
	}
}
