package mtr.data;

import transitsim.network.ThroatRoutes;
import transitsim.plan.TimetableConflicts;
import transitsim.plan.RouteTimetable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

	/**
	 * Route A running into the turn back rail a minute after leaving its last stop, then a minute out to route B.
	 */
	private static TimetableConflicts.TurnBack turnBack(UUID rail, long routeId, long nextRouteId) {
		return new TimetableConflicts.TurnBack(routeId, rail, 1, nextRouteId, MINUTE, 0, MINUTE);
	}

	private static TimetableConflicts.RouteTimes routeA(UUID rail, int... serviceMinutes) {
		final TimetableConflicts.RouteTimes route = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), serviceMinutes);
		route.addTurnBack(turnBack(rail, ROUTE_A, ROUTE_B));
		return route;
	}

	private static TimetableConflicts.RouteTimes routeB(int... serviceMinutes) {
		return route(ROUTE_B, new long[]{PLATFORM_3}, new long[][]{{0, 0}}, serviceMinutes);
	}

	/**
	 * Route A arrives at its second stop over turnout 100 by way 1-2; route B leaves the same station from another track
	 * over turnout 100 by way 1-3. Route A's service at 10 arrives there at 13.
	 */
	private static List<TimetableConflicts.Conflict> throatCheck(int bDepartureMillis, boolean sameWay) {
		final TimetableConflicts.RouteTimes a = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 10);
		a.setThroats(Arrays.asList(new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.emptyList()), new ThroatRoutes.StopThroat(Collections.singletonList(new ThroatRoutes.Item(100, 1, 2)), Collections.emptyList())));
		final List<Route.RoutePlatform> stops = Arrays.asList(new Route.RoutePlatform(PLATFORM_3), new Route.RoutePlatform(14));
		final TimetableConflicts.RouteTimes b = new TimetableConflicts.RouteTimes(ROUTE_B, stops, Collections.singletonList(bDepartureMillis), 0, new long[][]{{0, 0}, {3L * MINUTE, 3L * MINUTE}});
		b.setThroats(Arrays.asList(new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.singletonList(new ThroatRoutes.Item(100, 1, sameWay ? 2 : 3))), new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.emptyList())));
		return check(a, b);
	}

	@Test
	void crossingTheThroatWithinHalfAMinuteConflicts() {
		// A uses the turnout from 12:30 to 13:00, B from 12:45 to 13:15
		final List<TimetableConflicts.Conflict> conflicts = throatCheck(12 * MINUTE + 45000, false);
		assertEquals(1, conflicts.size());
		assertEquals(100, conflicts.get(0).turnoutNode);
		assertEquals(0, conflicts.get(0).platformId);
		assertEquals(1, conflicts.get(0).stopIndex);
		assertEquals(ROUTE_B, conflicts.get(0).otherRouteId);
	}

	@Test
	void crossingTheThroatMinutesApartDoesnt() {
		assertTrue(throatCheck(15 * MINUTE, false).isEmpty());
	}

	@Test
	void followingTheSameWayIsntAThroatConflict() {
		assertTrue(throatCheck(12 * MINUTE + 45000, true).isEmpty());
	}

	@Test
	void throatConflictsGoRoundMidnight() {
		// A's service at 23:57 arrives at midnight, on the turnout from 23:59:30; B leaves at 23:59:45
		final TimetableConflicts.RouteTimes a = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 23 * 60 + 57);
		a.setThroats(Arrays.asList(new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.emptyList()), new ThroatRoutes.StopThroat(Collections.singletonList(new ThroatRoutes.Item(100, 1, 2)), Collections.emptyList())));
		final TimetableConflicts.RouteTimes b = new TimetableConflicts.RouteTimes(ROUTE_B, Arrays.asList(new Route.RoutePlatform(PLATFORM_3), new Route.RoutePlatform(14)), Collections.singletonList((23 * 60 + 59) * MINUTE + 45000), 0, new long[][]{{0, 0}, {3L * MINUTE, 3L * MINUTE}});
		b.setThroats(Arrays.asList(new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.singletonList(new ThroatRoutes.Item(100, 1, 3))), new ThroatRoutes.StopThroat(Collections.emptyList(), Collections.emptyList())));
		assertEquals(1, check(a, b).size());
	}

	@Test
	void trainsOnOneTurnBackRailConflict() {
		// Both trains are in at 15 and 25, and the first one only leaves for its service at 29
		final List<TimetableConflicts.Conflict> conflicts = check(routeA(new UUID(1, 1), 10, 20), routeB(30, 40));

		assertEquals(1, conflicts.size());
		assertEquals(0, conflicts.get(0).platformId);
		assertEquals(1, conflicts.get(0).stopIndex);
		assertEquals(10 * MINUTE, conflicts.get(0).service);
	}

	@Test
	void trainsLeavingTheTurnBackRailInTimeHaveNoConflict() {
		assertTrue(check(routeA(new UUID(1, 1), 10, 20), routeB(17, 27)).isEmpty());
	}

	@Test
	void theRouteAfterTheTurnBackRailSeesTheConflict() {
		final List<TimetableConflicts.Conflict> conflicts = TimetableConflicts.check(ROUTE_B, Arrays.asList(routeA(new UUID(1, 1), 10, 20), routeB(30, 40)));

		assertEquals(1, conflicts.size());
		assertEquals(0, conflicts.get(0).stopIndex);
		assertEquals(30 * MINUTE, conflicts.get(0).service);
		assertEquals(ROUTE_A, conflicts.get(0).otherRouteId);
	}

	@Test
	void routesOnDifferentTurnBackRailsHaveNoConflict() {
		final TimetableConflicts.RouteTimes other = route(3, new long[]{PLATFORM_3, PLATFORM_1}, times(), 20);
		other.addTurnBack(turnBack(new UUID(2, 2), 3, 0));
		assertTrue(check(routeA(new UUID(1, 1), 10), other, routeB(30)).isEmpty());
	}

	@Test
	void anotherRouteOnTheTurnBackRailConflicts() {
		final TimetableConflicts.RouteTimes other = route(3, new long[]{PLATFORM_3, 14}, times(), 20);
		other.addTurnBack(turnBack(new UUID(1, 1), 3, 0));

		final List<TimetableConflicts.Conflict> conflicts = check(routeA(new UUID(1, 1), 10), other, routeB(40));

		assertEquals(1, conflicts.size());
		assertEquals(3, conflicts.get(0).otherRouteId);
	}

	@Test
	void aTrainUsingATurnBackRailLeavesItsPlatform() {
		// Route A ends where route B starts, but the train goes by way of the rail in between, so another service fits in between
		final TimetableConflicts.RouteTimes routeA = route(ROUTE_A, new long[]{PLATFORM_1, PLATFORM_2}, times(), 10);
		routeA.nextRouteId = ROUTE_B;
		routeA.addTurnBack(turnBack(new UUID(1, 1), ROUTE_A, ROUTE_B));
		final TimetableConflicts.RouteTimes routeB = route(ROUTE_B, new long[]{PLATFORM_2, PLATFORM_3}, times(), 30);
		final TimetableConflicts.RouteTimes other = route(3, new long[]{PLATFORM_2}, new long[][]{{0, MINUTE}}, 20);

		assertTrue(check(routeA, routeB, other).isEmpty());
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
	void tablesOnDifferentDaysDoNotConflict() {
		final long[][] oneStop = {{0, 2L * MINUTE}};
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));
		final TimetableConflicts.RouteTimes weekdays = new TimetableConflicts.RouteTimes(ROUTE_A, stops);
		weekdays.addTable(0, 0b0011111, Collections.singletonList(10 * MINUTE), 0, oneStop);
		final TimetableConflicts.RouteTimes weekends = new TimetableConflicts.RouteTimes(ROUTE_B, stops);
		weekends.addTable(0, 0b1100000, Collections.singletonList(10 * MINUTE), 0, oneStop);

		assertTrue(check(weekdays, weekends).isEmpty());
	}

	@Test
	void tablesSharingADayConflict() {
		final long[][] oneStop = {{0, 2L * MINUTE}};
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));
		final TimetableConflicts.RouteTimes route = new TimetableConflicts.RouteTimes(ROUTE_A, stops);
		route.addTable(0, 0b0011111, Collections.singletonList(30 * MINUTE), 0, oneStop);
		route.addTable(1, 0b1100000, Collections.singletonList(10 * MINUTE), 0, oneStop);
		final TimetableConflicts.RouteTimes other = new TimetableConflicts.RouteTimes(ROUTE_B, stops);
		other.addTable(0, 0b0100000, Collections.singletonList(10 * MINUTE), 0, oneStop);

		final List<TimetableConflicts.Conflict> conflicts = check(route, other);
		assertEquals(1, conflicts.size());
		assertEquals(1, conflicts.get(0).table);
	}

	@Test
	void aConflictOnSeveralDaysCountsOnce() {
		final long[][] oneStop = {{0, 2L * MINUTE}};
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));
		final TimetableConflicts.RouteTimes route = new TimetableConflicts.RouteTimes(ROUTE_A, stops);
		route.addTable(0, RouteTimetable.ALL_WEEKDAYS, Collections.singletonList(10 * MINUTE), 0, oneStop);
		final TimetableConflicts.RouteTimes other = new TimetableConflicts.RouteTimes(ROUTE_B, stops);
		other.addTable(0, 0b0011111, Collections.singletonList(10 * MINUTE), 0, oneStop);
		other.addTable(1, 0b1100000, Collections.singletonList(50 * MINUTE), 0, oneStop);

		assertEquals(1, check(route, other).size());
	}

	@Test
	void aServiceInTwoTablesOnTheSameDayRunsFromTheFirst() {
		final List<Route.RoutePlatform> stops = Collections.singletonList(new Route.RoutePlatform(PLATFORM_1));
		final TimetableConflicts.RouteTimes route = new TimetableConflicts.RouteTimes(ROUTE_A, stops);
		route.addTable(0, RouteTimetable.ALL_WEEKDAYS, Collections.singletonList(10 * MINUTE), 0, new long[][]{{0, MINUTE}});
		route.addTable(1, RouteTimetable.ALL_WEEKDAYS, Collections.singletonList(10 * MINUTE), 0, new long[][]{{0, MINUTE}});

		assertTrue(check(route).isEmpty());
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
