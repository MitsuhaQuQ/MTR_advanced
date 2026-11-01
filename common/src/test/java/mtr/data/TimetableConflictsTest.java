package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableConflictsTest {

	private static final int MINUTE = 60 * 1000;
	private static final long ROUTE_A = 1;
	private static final long ROUTE_B = 2;
	private static final long PLATFORM_1 = 11;
	private static final long PLATFORM_2 = 12;
	private static final long PLATFORM_3 = 13;

	private static TimetableConflicts.RouteTimes route(long routeId, long[] platformIds, long[][] times, int... serviceMinutes) {
		final List<Route.RoutePlatform> stops = new ArrayList<>();
		Arrays.stream(platformIds).forEach(platformId -> stops.add(new Route.RoutePlatform(platformId)));
		final List<Integer> services = new ArrayList<>();
		Arrays.stream(serviceMinutes).forEach(minute -> services.add(minute * MINUTE));
		return new TimetableConflicts.RouteTimes(routeId, stops, services, 0, times);
	}

	/**
	 * Two stops two minutes apart, stopping for one minute at each.
	 */
	private static long[][] times() {
		return new long[][]{{0, MINUTE}, {3L * MINUTE, 4L * MINUTE}};
	}

	private static List<TimetableConflicts.Conflict> check(TimetableConflicts.RouteTimes... routes) {
		return TimetableConflicts.check(ROUTE_A, Arrays.asList(routes));
	}

	@Test
	void servicesFarApartHaveNoConflict() {
		assertTrue(check(route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 10, 20)).isEmpty());
	}

	@Test
	void differentPlatformsHaveNoConflict() {
		assertTrue(check(route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 10), route(ROUTE_B, new long[]{PLATFORM_3}, new long[][]{{0, MINUTE}}, 10)).isEmpty());
	}

	@Test
	void twoRoutesOnOnePlatformConflict() {
		final List<TimetableConflicts.Conflict> conflicts = check(route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 10), route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, times(), 13));

		assertEquals(1, conflicts.size());
		final TimetableConflicts.Conflict conflict = conflicts.get(0);
		assertEquals(1, conflict.stopIndex);
		assertEquals(10 * MINUTE, conflict.service);
		assertEquals(ROUTE_B, conflict.otherRouteId);
		assertEquals(13 * MINUTE, conflict.otherService);
		assertEquals(PLATFORM_2, conflict.platformId);
	}

	@Test
	void theGapAfterLeavingCounts() {
		final long[][] oneStop = {{0, MINUTE}};
		final int leaving = 11 * MINUTE;
		final TimetableConflicts.RouteTimes first = route(ROUTE_A, new long[]{PLATFORM_1}, oneStop, 10);

		final List<Integer> tooSoon = Collections.singletonList(leaving + (int) TimetableConflicts.MIN_PLATFORM_GAP_MILLIS - 1);
		final List<Integer> justEnough = Collections.singletonList(leaving + (int) TimetableConflicts.MIN_PLATFORM_GAP_MILLIS);
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));

		assertEquals(1, check(first, new TimetableConflicts.RouteTimes(ROUTE_B, stops, tooSoon, 0, oneStop)).size());
		assertTrue(check(first, new TimetableConflicts.RouteTimes(ROUTE_B, stops, justEnough, 0, oneStop)).isEmpty());
	}

	@Test
	void conflictsAreFoundAcrossMidnight() {
		final long[][] oneStop = {{0, 2L * MINUTE}};
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));
		final TimetableConflicts.RouteTimes beforeMidnight = new TimetableConflicts.RouteTimes(ROUTE_A, stops, Collections.singletonList(Depot.MILLISECONDS_PER_DAY - MINUTE), 0, oneStop);

		assertEquals(1, check(beforeMidnight, route(ROUTE_B, new long[]{PLATFORM_1}, oneStop, 0)).size());
	}

	@Test
	void conflictsBetweenOtherRoutesAreLeftOut() {
		final long[][] oneStop = {{0, 2L * MINUTE}};
		assertTrue(check(route(ROUTE_A, new long[]{PLATFORM_1}, oneStop, 30), route(ROUTE_B, new long[]{PLATFORM_1}, oneStop, 10), route(3, new long[]{PLATFORM_1}, oneStop, 10)).isEmpty());
	}

	@Test
	void shortHeadwayOnOneRouteConflicts() {
		final List<TimetableConflicts.Conflict> conflicts = check(route(ROUTE_A, new long[]{PLATFORM_1}, new long[][]{{0, 2L * MINUTE}}, 10, 11));

		assertEquals(1, conflicts.size());
		assertEquals(ROUTE_A, conflicts.get(0).otherRouteId);
	}

	@Test
	void stopsWithoutTimesAreSkipped() {
		final long[][] missing = {{0, MINUTE}, {-1, -1}};
		assertTrue(check(route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, missing, 10), route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, times(), 10)).isEmpty());
	}

	@Test
	void turningBackAtThePlatformBlocksItUntilTheNextService() {
		// Arrive at 03, leave again at 11, which is past the next arrival at 07
		final TimetableConflicts.RouteTimes down = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 0, 4);
		final TimetableConflicts.RouteTimes up = route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, times(), 10, 14);
		down.nextRouteId = ROUTE_B;

		final List<TimetableConflicts.Conflict> conflicts = check(down, up);
		assertEquals(1, conflicts.size());
		assertEquals(PLATFORM_2, conflicts.get(0).platformId);
	}

	@Test
	void turningBackFitsWhenItIsClearBeforeTheNextArrival() {
		// Arrive at 03 and leave at 06, then arrive at 13 and leave at 16
		final TimetableConflicts.RouteTimes down = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 0, 10);
		final TimetableConflicts.RouteTimes up = route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, times(), 5, 15);
		down.nextRouteId = ROUTE_B;

		assertTrue(check(down, up).isEmpty());
	}

	@Test
	void theSameIntervalCanConflictOrNotDependingOnTheTimes() {
		final long[][] arrive = {{0, MINUTE}, {3L * MINUTE, 3L * MINUTE}};
		final long[][] leave = {{0, MINUTE}, {3L * MINUTE, 4L * MINUTE}};

		final TimetableConflicts.RouteTimes down = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, arrive, 0, 4, 8);
		down.nextRouteId = ROUTE_B;
		assertTrue(check(down, route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, leave, 3, 7, 11)).stream().noneMatch(conflict -> conflict.platformId == PLATFORM_2 && conflict.otherRouteId == ROUTE_B));

		final TimetableConflicts.RouteTimes laterDown = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, arrive, 0, 4, 8);
		laterDown.nextRouteId = ROUTE_B;
		assertTrue(check(laterDown, route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, leave, 6, 10, 14)).stream().anyMatch(conflict -> conflict.platformId == PLATFORM_2));
	}

	@Test
	void aLoopRunsIntoItsOwnNextService() {
		final long[][] loop = {{0, MINUTE}, {5L * MINUTE, 5L * MINUTE}};

		final TimetableConflicts.RouteTimes fits = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_1}, loop, 0, 6);
		fits.nextRouteId = ROUTE_A;
		assertTrue(check(fits).isEmpty());
	}

	@Test
	void theNumberOfConflictsIsLimited() {
		final List<Integer> serviceList = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			serviceList.add(i * 1000);
		}
		final TimetableConflicts.RouteTimes route = new TimetableConflicts.RouteTimes(ROUTE_A, Collections.singletonList(new Route.RoutePlatform(PLATFORM_1)), serviceList, 0, new long[][]{{0, 10L * MINUTE}});

		assertEquals(TimetableConflicts.MAX_CONFLICTS, check(route).size());
	}
}
