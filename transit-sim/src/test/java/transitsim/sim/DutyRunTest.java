package transitsim.sim;

import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.DutyPlan;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.RouteTimetable;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trains of a depot running by its duties (design document 6.12, block E2), without a world: a fake train on hand-made paths.
 */
class DutyRunTest {

	private static final long SECOND = 1000;
	private static final int MILLIS_PER_TICK = 50;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final int TICKS_PER_MINUTE = (int) (MINUTE / MILLIS_PER_TICK);
	private static final long SIDING_ID = 100;
	/**
	 * Another siding of the same pool (decoupling document, step D, depots sending trains out 4 and 5).
	 */
	private static final long OTHER_SIDING_ID = 200;

	private long now;
	private final Map<Long, TestRoute> routes = new HashMap<>();
	private final DutyDepot depot = new DutyDepot();
	private final Map<String, TripPath<TestTrack>> tripPaths = new HashMap<>();

	@BeforeEach
	void setUp() {
		OperatingDay.set("UTC", 0);
		routes.put(1L, makeRoute(1, 1, 2, 3));
		routes.put(2L, makeRoute(2, 4, 5));
		routes.put(3L, makeRoute(3, 3, 2, 1));
		routes.put(4L, makeRoute(4, 6, 7, 8, 9));
	}

	/**
	 * Services every 10 minutes from 06:00.
	 */
	private static TestRoute makeRoute(long id, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 0;
		for (long millis = 6 * HOUR; millis < 24 * HOUR; millis += 10 * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}

	/**
	 * @param platformIds each rail's platform, with 0 for a siding at either end
	 */
	private static List<TestTrack> makePath(long... platformIds) {
		final List<TestTrack> path = new ArrayList<>();
		for (int i = 0; i < platformIds.length; i++) {
			path.add(new TestTrack(i * 20, i * 20 + 20, platformIds[i] == 0, platformIds[i], i == 0 ? 0 : 10, i));
		}
		return path;
	}

	private static long[] stop(int rail, long platformId, int minutes) {
		final long arrival = (long) minutes * TICKS_PER_MINUTE;
		return new long[]{rail, platformId, arrival, arrival + 10 * SECOND / MILLIS_PER_TICK};
	}

	private static DepotDuties.DayDuty day(boolean stay, int nextSlot, DepotDuties.Segment... segments) {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.stayOnLine = stay;
		day.nextSlot = nextSlot;
		day.segments.addAll(Arrays.asList(segments));
		return day;
	}

	private void setSlots(DepotDuties.DayDuty... days) {
		final List<DepotDuties.Slot> slots = new ArrayList<>();
		for (final DepotDuties.DayDuty day : days) {
			final DepotDuties.Slot slot = new DepotDuties.Slot();
			slot.days.add(day);
			slots.add(slot);
		}
		depot.slots = slots;
	}

	private TripPath<TestTrack> addPath(int slot, int weekday, int[] starts, List<TestTrack> path, long[]... stops) {
		final String key = DutyShapes.getSlotKey(depot.slots, slot, weekday, routes::get);
		final DepotDuties.DayDuty day = depot.slots.get(slot).getDuty(weekday);
		final List<Long> routeIds = new ArrayList<>();
		day.segments.forEach(segment -> routeIds.add(segment.routeId));
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(key, routeIds, new HashMap<>(), new HashMap<>(), new HashMap<>(), path, starts, null, null, false, day.stayOnLine);
		// Every route in full, so by segment as by route
		dutyPlan.segmentsKnown = true;
		final TripPath<TestTrack> tripPath = TripPath.ofDuty(dutyPlan, starts, path, StopTimes.of(stops));
		tripPaths.put(key, tripPath);
		return tripPath;
	}

	private FakeTrain newTrain(List<TestTrack> path) {
		return newTrain(path, TurnBacks.NONE);
	}

	private FakeTrain newTrain(List<TestTrack> path, TurnBacks turnBacks) {
		final FakeTrain train = new FakeTrain(path);
		train.service.setSiding(SIDING_ID);
		train.service.setNetwork(depot, routes::get, null);
		train.service.setStopTimes(new StopTimes(), tripPaths, turnBacks);
		return train;
	}

	private static final List<UUID> FIRST_TURN_BACK = Collections.singletonList(new UUID(0, 1));
	private static final List<UUID> SECOND_TURN_BACK = Collections.singletonList(new UUID(0, 2));

	/**
	 * Route 1 from 08:00 to platform 3, turning back after it on one of two rails, then route 2 from 08:20 at platform 4. The
	 * second turn back rail is a rail further on, and the train reaches platform 4 four minutes later that way.
	 *
	 * @return the duty's usual path and its path the other way round
	 */
	private List<TripPath<TestTrack>> turnBackDuty(FakeTrain[] train) {
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(2, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final String key = DutyShapes.getSlotKey(depot.slots, 0, 0, routes::get);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(key, Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), makePath(0, 1, 2, 3, 0, 4, 5, 0), new int[]{1, 5}, null, null, false, false);
		final List<TestTrack> path = makePath(0, 1, 2, 3, 0, 4, 5, 0);
		final TripPath<TestTrack> usual = TripPath.ofDuty(dutyPlan, new int[]{1, 5}, path, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 0, 6), stop(5, 4, 8), stop(6, 5, 10)), -1, -1, new int[]{3});
		final TripPath<TestTrack> other = TripPath.ofDuty(dutyPlan, new int[]{1, 6}, makePath(0, 1, 2, 3, 0, 0, 4, 5, 0), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(5, 0, 10), stop(6, 4, 12), stop(7, 5, 14)), 0, 1, new int[]{3});
		tripPaths.put(usual.key, usual);
		tripPaths.put(other.key, other);
		train[0] = newTrain(path, TurnBacks.of(new int[]{3}, new TurnBackPoint(Arrays.asList(FIRST_TURN_BACK, SECOND_TURN_BACK), new int[]{2, 3}, 0)));
		return Arrays.asList(usual, other);
	}

	private FakeTrain atTurnBackStop(List<TripPath<TestTrack>> paths, FakeTrain train, long millis) {
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, paths.get(0));
		now = millis;
		train.nextStoppingIndex = 3;
		return train;
	}

	@Test
	void aDutyTurningBackAtAnotherPlatformOfTheLastStationRunsTheNextRouteFromThere() {
		// Route 1 ends at platform 3, and its trains can turn back at platform 9 of that station too, then run route 3 back
		routes.get(1L).terminalPlatforms.add(9L);
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(3, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final List<TestTrack> path = makePath(0, 1, 2, 9, 0, 2, 1, 0);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1, 3}, path, stop(1, 1, 0), stop(2, 2, 2), stop(3, 9, 4), stop(5, 2, 6), stop(6, 1, 8));
		final FakeTrain train = newTrain(path);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);

		// At platform 9 it is at the last stop of route 1 and the first of route 3, which it leaves on time
		train.nextStoppingIndex = 3;
		now = MONDAY + 8 * HOUR + 4 * MINUTE;
		train.service.onStopReached();
		assertEquals(3, train.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, train.service.getTripMillis());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE + 10 * SECOND, train.service.getDutyDepartureMillis());
		// The stops after it are route 3's
		assertEquals(MONDAY + 8 * HOUR + 22 * MINUTE, train.service.getPlannedArrivalMillis(5));
	}

	/**
	 * Route 1 to platform 3, then route 2 from 08:20 at platform 4, turning back either at platform 9 of the station (the
	 * usual way, 站前) or on a turn back rail where the train stops (站后): both two rails from platform 3 to platform 4.
	 */
	private List<TripPath<TestTrack>> mixedTurnBackDuty(FakeTrain[] train) {
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(2, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final String key = DutyShapes.getSlotKey(depot.slots, 0, 0, routes::get);
		final List<TestTrack> platformPath = makePath(0, 1, 2, 3, 9, 4, 5, 0);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(key, Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), platformPath, new int[]{1, 5}, null, null, false, false);
		final TripPath<TestTrack> usual = TripPath.ofDuty(dutyPlan, new int[]{1, 5}, platformPath, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 9, 6), stop(5, 4, 8), stop(6, 5, 10)), -1, -1, new int[]{3});
		final TripPath<TestTrack> rail = TripPath.ofDuty(dutyPlan, new int[]{1, 5}, makePath(0, 1, 2, 3, 0, 4, 5, 0), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 0, 6), stop(5, 4, 8), stop(6, 5, 10)), 0, 1, new int[]{3});
		tripPaths.put(usual.key, usual);
		tripPaths.put(rail.key, rail);
		train[0] = newTrain(platformPath, TurnBacks.of(new int[]{3}, new TurnBackPoint(Arrays.asList(FIRST_TURN_BACK, SECOND_TURN_BACK), new int[]{2, 2}, 0)));
		return Arrays.asList(usual, rail);
	}

	@Test
	void aTrainWithALongWaitTakesTheTurnBackRailOverThePlatform() {
		// Leaving platform 3 at 08:04, ready at platform 4 by 08:08 for 08:20: a long wait, so on the rail (混合折返)
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = mixedTurnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		assertTrue(train[0].service.chooseTurnBack());
		assertSame(paths.get(1), train[0].followed);
		assertEquals(SECOND_TURN_BACK, train[0].reserved);
	}

	@Test
	void aTrainWithAShortWaitTurnsBackTheUsualWay() {
		// Leaving at 08:15, ready by 08:19: a short wait, the usual way at the platform
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = mixedTurnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 15 * MINUTE);
		assertFalse(train[0].service.chooseTurnBack());
		assertEquals(FIRST_TURN_BACK, train[0].reserved);
	}

	@Test
	void aTrainWithALongWaitTurnsBackAtThePlatformWhenTheRailIsTaken() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = mixedTurnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		train[0].takenRails.addAll(SECOND_TURN_BACK);
		assertFalse(train[0].service.chooseTurnBack());
		assertEquals(FIRST_TURN_BACK, train[0].reserved);
	}

	@Test
	void aDutyTurnsBackTheUsualWayWhenItMakesTheNextService() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		assertTrue(train[0].service.mayChooseWayAtStop());
		assertFalse(train[0].service.chooseTurnBack());
		assertSame(paths.get(0), train[0].followed);
		assertEquals(FIRST_TURN_BACK, train[0].reserved);
	}

	@Test
	void aDutyTakesTheOtherWayWhenAnotherTrainHoldsTheUsualOneAndItStillMakesTheNextService() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		train[0].heldRails.addAll(FIRST_TURN_BACK);
		assertTrue(train[0].service.chooseTurnBack());
		assertSame(paths.get(1), train[0].followed);
		assertEquals(3, train[0].nextStoppingIndex);
		assertEquals(SECOND_TURN_BACK, train[0].reserved);
		// It chooses once
		assertFalse(train[0].service.chooseTurnBack());
	}

	@Test
	void aDutyThatMakesTheNextServiceNeitherWayTakesTheSoonestClearOne() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		// At 08:18 it reaches platform 4 at 08:22 the usual way and 08:26 the other, after the service at 08:20
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 18 * MINUTE);
		assertFalse(train[0].service.chooseTurnBack());
		assertSame(paths.get(0), train[0].followed);

		// With the usual way held, the clear way goes first even though it is later
		final FakeTrain[] other = new FakeTrain[1];
		tripPaths.clear();
		final List<TripPath<TestTrack>> otherPaths = turnBackDuty(other);
		atTurnBackStop(otherPaths, other[0], MONDAY + 8 * HOUR + 18 * MINUTE);
		other[0].heldRails.addAll(FIRST_TURN_BACK);
		assertTrue(other[0].service.chooseTurnBack());
		assertSame(otherPaths.get(1), other[0].followed);
	}

	@Test
	void aTrainWaitsOnTheTurnBackRailUntilItsTimeToRunToThePlatform() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, paths.get(0));
		train[0].service.update(true);
		// On the platform it isn't a turn back rail
		train[0].nextStoppingIndex = 3;
		assertEquals(0, train[0].service.getTurnBackLeaveMillis());
		// On the turn back rail: route 2 is due at 08:20 at the platform two minutes on, less ten seconds' stop
		train[0].nextStoppingIndex = 4;
		now = MONDAY + 8 * HOUR + 7 * MINUTE;
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE - 2 * MINUTE + 10 * SECOND, train[0].service.getTurnBackLeaveMillis());
	}

	@Test
	void aDutyTakesTheOtherWayWhenTheUsualTurnBackRailIsTakenAndWaitsWhenBothAre() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		train[0].takenRails.addAll(FIRST_TURN_BACK);
		train[0].takenRails.addAll(SECOND_TURN_BACK);
		assertTrue(train[0].service.chooseTurnBack());
		assertNull(train[0].reserved);
		train[0].takenRails.removeAll(SECOND_TURN_BACK);
		assertTrue(train[0].service.chooseTurnBack());
		assertSame(paths.get(1), train[0].followed);
	}

	@Test
	void aDutyThatWentTheOtherWayGoesBackToAnotherSidingAlongItsUsualPathsWayBack() {
		final FakeTrain[] train = new FakeTrain[1];
		final List<TripPath<TestTrack>> paths = turnBackDuty(train);
		final TripPath<TestTrack> usual = paths.get(0);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(usual.key, Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), usual.path, usual.dutyStarts, null, null, false, false);
		final TripPath<TestTrack> returnPath = new TripPath<>(dutyPlan, usual.dutyStarts, makePath(0, 1, 2, 3, 0, 4, 5, 0, 0), TripPath.getReturnKey(usual.key, OTHER_SIDING_ID));
		tripPaths.put(returnPath.key, returnPath);
		depot.returnTo = OTHER_SIDING_ID;
		atTurnBackStop(paths, train[0], MONDAY + 8 * HOUR + 4 * MINUTE);
		train[0].heldRails.addAll(FIRST_TURN_BACK);
		train[0].service.chooseTurnBack();
		assertSame(paths.get(1), train[0].followed);

		// The last stop is on rail 7 the other way round, and on rail 6 of the usual path's way back
		train[0].nextStoppingIndex = 7;
		assertTrue(train[0].service.chooseDutyReturn());
		assertSame(returnPath, train[0].followed);
		assertEquals(6, train[0].nextStoppingIndex);
	}

	/**
	 * TestRoute 1 from 08:00, then on along route 2 from 08:20.
	 */
	private TripPath<TestTrack> twoRoutes(FakeTrain[] train) {
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(2, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final List<TestTrack> path = makePath(0, 1, 2, 3, 4, 5, 0);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1, 4}, path, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 4, 20), stop(5, 5, 22));
		train[0] = newTrain(path);
		return tripPath;
	}

	@Test
	void runsTheServicesOfItsDuty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		assertSame(tripPath, train[0].followed);
		assertEquals(MONDAY + 8 * HOUR, train[0].service.getTripMillis());
		assertEquals(1, train[0].service.getTripRouteId());

		// Nothing is reserved, as the duties say which train runs what
		train[0].service.update(true);
		assertTrue(depot.reserved.isEmpty());

		train[0].nextStoppingIndex = 4;
		now = MONDAY + 8 * HOUR + 18 * MINUTE;
		train[0].service.onStopReached();
		assertEquals(2, train[0].service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, train[0].service.getTripMillis());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE + 10 * SECOND, train[0].service.getDutyDepartureMillis());

		// Back at the depot the duty is over
		train[0].service.update(false);
		assertFalse(train[0].service.isOnDuty());
	}

	@Test
	void keepsItsDutyWhileWaitingToLeave() {
		// The run out of the siding is granted a tick later at the earliest, and the train keeps its duty meanwhile
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		for (int tick = 0; tick < 100; tick++) {
			train[0].service.update(false);
		}
		assertTrue(train[0].service.isOnDuty());
		assertEquals(MONDAY + 8 * HOUR, train[0].service.getTripMillis());

		// Once it has left, coming back to the depot ends the duty
		train[0].service.update(true);
		train[0].service.update(false);
		assertFalse(train[0].service.isOnDuty());
	}

	@Test
	void keepsItsDutyWhenTheDutiesAreEdited() {
		// F2, R04 of the architecture review: a duty under way runs as it was when the train took it on
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(2, (int) (8 * HOUR + 40 * MINUTE), 0, -1)));

		train[0].nextStoppingIndex = 4;
		now = MONDAY + 8 * HOUR + 18 * MINUTE;
		train[0].service.onStopReached();
		assertEquals(2, train[0].service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, train[0].service.getTripMillis());

		// Removing the duty altogether doesn't stop it either
		setSlots();
		now = MONDAY + 8 * HOUR + 20 * MINUTE + 30 * SECOND;
		train[0].service.alignToTimetable(true);
		assertEquals(4, train[0].movedTo);
		assertFalse(train[0].returned);
	}

	@Test
	void takesTheEditedDutyTheNextTime() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].service.update(false);
		assertFalse(train[0].service.isOnDuty());
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(2, (int) (8 * HOUR + 40 * MINUTE), 0, -1)));

		now = MONDAY + OperatingDay.MILLIS_PER_DAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY + OperatingDay.MILLIS_PER_DAY, tripPath);
		train[0].nextStoppingIndex = 4;
		now = MONDAY + OperatingDay.MILLIS_PER_DAY + 8 * HOUR + 38 * MINUTE;
		train[0].service.onStopReached();
		assertEquals(MONDAY + OperatingDay.MILLIS_PER_DAY + 8 * HOUR + 40 * MINUTE, train[0].service.getTripMillis());
	}

	@Test
	void takesTheNextSlotChosenSinceItLeft() {
		// Which slot a train staying overnight takes over is decided when the new day starts
		setSlots(
				day(true, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)),
				day(false, -1, new DepotDuties.Segment(3, (int) (7 * HOUR), 0, -1))
		);
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;

		depot.slots.get(0).days.get(0).nextSlot = 1;
		final TripPath<TestTrack> nextPath = addPath(1, 1, new int[]{0}, makePath(3, 2, 1, 0), stop(0, 3, 0), stop(1, 2, 2), stop(2, 1, 4));
		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		assertSame(nextPath, train.followed);
		assertEquals(1, train.service.getDutySlot());
	}

	@Test
	void changesSegmentEvenOnTheSameRoute() {
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(1, (int) (8 * HOUR + 30 * MINUTE), 0, -1)));
		final List<TestTrack> path = makePath(0, 1, 2, 3, 1, 2, 3, 0);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1, 4}, path, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 1, 20), stop(5, 2, 22), stop(6, 3, 24));
		final FakeTrain train = newTrain(path);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 4;
		train.service.onStopReached();
		assertEquals(1, train.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 30 * MINUTE, train.service.getTripMillis());
	}

	@Test
	void linesUpWithItsDutyAfterARestart() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		now = MONDAY + 8 * HOUR + 20 * MINUTE + 30 * SECOND;
		train[0].service.alignToTimetable(true);
		assertEquals(4, train[0].movedTo);
		assertEquals(2, train[0].service.getTripRouteId());

		now = MONDAY + 12 * HOUR;
		train[0].service.alignToTimetable(true);
		assertTrue(train[0].returned);
	}

	@Test
	void keepsItsOwnDutyThroughARestartWhateverTheDepotHasNow() throws Exception {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		train[0].service.write(values);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);

		// The depot's duties are gone meanwhile, as when they were made again or edited
		setSlots();
		final FakeTrain restored = newTrain(tripPath.path);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertTrue(restored.service.restoreTripPath(tripPaths));
		now = MONDAY + 8 * HOUR + 20 * MINUTE + 30 * SECOND;
		restored.service.alignToTimetable(true);
		assertEquals(4, restored.movedTo);
		assertEquals(2, restored.service.getTripRouteId());
	}

	@Test
	void sentOutLateItJoinsAtTheFirstStopItCanKeepTo() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		// Its duty starts at 08:00; the depot only sends it out at 08:20:30
		now = MONDAY + 8 * HOUR + 20 * MINUTE + 30 * SECOND;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.joinDutyLate();
		// Route 1 is over by then, so it joins route 2 at its first stop
		assertEquals(4, train[0].movedTo);
		assertEquals(2, train[0].service.getTripRouteId());
	}

	@Test
	void runsTheSlotOfAnotherSidingAndKeepsItThroughARestart() throws Exception {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		// The slot is the other siding's; this siding has none
		depot.otherSlots = depot.slots;
		depot.slots = new ArrayList<>();
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, OTHER_SIDING_ID, MONDAY, tripPath);
		assertEquals(OTHER_SIDING_ID, train[0].service.getDutySidingId());
		assertEquals(1, train[0].service.getTripRouteId());

		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		train[0].service.write(values);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);
		final FakeTrain restored = newTrain(tripPath.path);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertTrue(restored.service.restoreTripPath(tripPaths));
		assertEquals(OTHER_SIDING_ID, restored.service.getDutySidingId());
		now = MONDAY + 8 * HOUR + 20 * MINUTE + 30 * SECOND;
		restored.service.alignToTimetable(true);
		assertEquals(4, restored.movedTo);
		assertEquals(2, restored.service.getTripRouteId());

		// Back in the depot it is the siding's own train again
		restored.service.update(true);
		restored.service.update(false);
		assertEquals(SIDING_ID, restored.service.getDutySidingId());
	}

	@Test
	void goesBackToTheSidingOfItsPoolThatNeedsATrain() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		final List<TestTrack> otherPath = new ArrayList<>(tripPath.path);
		final DepotDuties.DayDuty day = depot.slots.get(0).getDuty(0);
		final List<Long> routeIds = new ArrayList<>();
		day.segments.forEach(segment -> routeIds.add(segment.routeId));
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(tripPath.key, routeIds, new HashMap<>(), new HashMap<>(), new HashMap<>(), otherPath, tripPath.dutyStarts, null, null, false, false);
		final TripPath<TestTrack> returnPath = new TripPath<>(dutyPlan, tripPath.dutyStarts, otherPath, TripPath.getReturnKey(tripPath.key, OTHER_SIDING_ID));
		tripPaths.put(returnPath.key, returnPath);
		depot.returnTo = OTHER_SIDING_ID;

		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		// Not yet at its last stop
		train[0].nextStoppingIndex = 4;
		assertFalse(train[0].service.chooseDutyReturn());
		train[0].nextStoppingIndex = 5;
		assertTrue(train[0].service.mayChooseWayAtStop());
		assertTrue(train[0].service.chooseDutyReturn());
		assertSame(returnPath, train[0].followed);
		assertTrue(train[0].service.isOnDuty());
		// It chooses once
		assertFalse(train[0].service.chooseDutyReturn());

		assertFalse(train[0].service.takeReturnedElsewhere());
		train[0].service.update(false);
		// The host learns which siding it came back to, once
		assertEquals(OTHER_SIDING_ID, train[0].service.takeReturnedElsewhereSiding());
		assertEquals(0, train[0].service.takeReturnedElsewhereSiding());
		assertFalse(train[0].service.takeReturnedElsewhere());
	}

	@Test
	void aTrainStayingOvernightRunsTheDutyOfAnotherSidingByItsNumber() {
		// Monday: route 1 to platform 3, staying on the line; Tuesday it runs duty 77 of the other siding
		setSlots(day(true, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)));
		depot.slots.get(0).id = 11;
		depot.slots.get(0).days.get(0).weekdays = 1;
		depot.slots.get(0).days.get(0).nextDutyId = 77;
		final DepotDuties.Slot other = new DepotDuties.Slot();
		other.id = 77;
		final DepotDuties.DayDuty tuesday = day(false, -1, new DepotDuties.Segment(3, (int) (7 * HOUR), 0, -1));
		tuesday.weekdays = 1 << 1;
		tuesday.fromOvernight = true;
		other.days.add(tuesday);
		depot.otherSlots = new ArrayList<>(Collections.singletonList(other));
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final String otherKey = DutyShapes.getSlotKey(depot.otherSlots, 0, 1, routes::get);
		final DutyPlan<TestTrack, Object> otherPlan = new DutyPlan<>(otherKey, Collections.singletonList(3L), new HashMap<>(), new HashMap<>(), new HashMap<>(), makePath(3, 2, 1, 0), new int[]{0}, null, null, true, false);
		final TripPath<TestTrack> nextPath = TripPath.ofDuty(otherPlan, new int[]{0}, makePath(3, 2, 1, 0), StopTimes.of(stop(0, 3, 0), stop(1, 2, 2), stop(2, 1, 4)));
		tripPaths.put(otherKey, nextPath);
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;

		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		assertNull(train.service.getOvernightHold());
		assertSame(nextPath, train.followed);
		assertEquals(OTHER_SIDING_ID, train.service.getDutySidingId());
		// The other siding doesn't send a train for it: taken over overnight
		assertTrue(DutyShapes.getOvernightSlot(depot.otherSlots, 0, 1) >= 0);
	}

	@Test
	void aTrainStayingOvernightWithItsNextDutyGoneWaits() {
		setSlots(day(true, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)));
		depot.slots.get(0).days.get(0).weekdays = 1;
		depot.slots.get(0).days.get(0).nextDutyId = 99;
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;
		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		assertEquals(TrainService.HoldReason.NO_DUTY, train.service.getOvernightHold());
	}

	@Test
	void aDutySetToGoBackToASidingGoesThereIfItCan() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		depot.slots.get(0).getDuty(0).returnSidingId = OTHER_SIDING_ID;
		final List<TestTrack> otherPath = new ArrayList<>(tripPath.path);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(tripPath.key, Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), otherPath, tripPath.dutyStarts, null, null, false, false);
		final TripPath<TestTrack> returnPath = new TripPath<>(dutyPlan, tripPath.dutyStarts, otherPath, TripPath.getReturnKey(tripPath.key, OTHER_SIDING_ID));
		tripPaths.put(returnPath.key, returnPath);
		// The pool would keep it at its own siding
		depot.returnTo = SIDING_ID;

		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].nextStoppingIndex = 5;
		assertTrue(train[0].service.chooseDutyReturn());
		assertSame(returnPath, train[0].followed);
		assertTrue(depot.missed.isEmpty());
	}

	@Test
	void aDutySetToGoBackToASidingWithNoWayThereIsTold() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		depot.slots.get(0).getDuty(0).returnSidingId = OTHER_SIDING_ID;
		depot.returnTo = SIDING_ID;
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].nextStoppingIndex = 5;
		assertFalse(train[0].service.chooseDutyReturn());
		assertEquals(Collections.singletonList(SIDING_ID + ">" + OTHER_SIDING_ID), depot.missed);
	}

	@Test
	void aSidingTheDutyHasNoWayBackToIsntChosen() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		// The pool would send it to the other siding, but no way back there was found
		depot.returnTo = OTHER_SIDING_ID;

		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].nextStoppingIndex = 5;
		assertFalse(train[0].service.chooseDutyReturn(), "keeps to its own way back");
		assertSame(tripPath, train[0].followed);
		assertEquals(Arrays.asList(OTHER_SIDING_ID), depot.refused, "the other siding was refused for having no way back");
	}

	@Test
	void aDutyWhoseLastServiceIsSetToGoBackToADepotAsksForItsSidings() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		final List<TestTrack> otherPath = new ArrayList<>(tripPath.path);
		final DepotDuties.DayDuty day = depot.slots.get(0).getDuty(0);
		final List<Long> routeIds = new ArrayList<>();
		day.segments.forEach(segment -> routeIds.add(segment.routeId));
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(tripPath.key, routeIds, new HashMap<>(), new HashMap<>(), new HashMap<>(), otherPath, tripPath.dutyStarts, null, null, false, false);
		final TripPath<TestTrack> returnPath = new TripPath<>(dutyPlan, tripPath.dutyStarts, otherPath, TripPath.getReturnKey(tripPath.key, OTHER_SIDING_ID));
		tripPaths.put(returnPath.key, returnPath);
		depot.returnTo = OTHER_SIDING_ID;
		// The duty's last service, route 2 at 08:20, goes back to depot 77
		routes.get(2L).timetable.returnOrders.put((int) (8 * HOUR + 20 * MINUTE), new RouteTimetable.ServiceOrder(1, 77));

		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].nextStoppingIndex = 5;
		assertTrue(train[0].service.chooseDutyReturn());
		assertEquals(77, depot.orderedDepot);
		assertSame(returnPath, train[0].followed);
	}

	@Test
	void theSidingOfAReturnPathIsReadFromItsKey() {
		assertEquals(OTHER_SIDING_ID, TripPath.getReturnSidingId(TripPath.getReturnKey("duty|1", OTHER_SIDING_ID)));
		assertEquals(-5, TripPath.getReturnSidingId(TripPath.getReturnKey("duty|1", -5)));
		assertEquals(0, TripPath.getReturnSidingId("duty|1"));
	}

	@Test
	void aTrainGoesBackToASidingOfAnotherDepotOfItsLineThatNeedsATrainSooner() {
		// The sidings are of two depots, each with its own duties and dispatcher
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (10 * HOUR), 0, -1)));
		final DepotDuties ownDuties = new DepotDuties();
		ownDuties.sidings.put(SIDING_ID, depot.slots);
		setSlots(day(false, -1, new DepotDuties.Segment(2, (int) (9 * HOUR), 0, -1)));
		final DepotDuties otherDuties = new DepotDuties();
		otherDuties.sidings.put(OTHER_SIDING_ID, depot.slots);
		final DepotDispatcher ownDispatcher = new DepotDispatcher();
		final DepotDispatcher otherDispatcher = new DepotDispatcher();
		final java.util.function.LongFunction<List<DepotDuties.Slot>> slotsOf = sidingId -> sidingId == SIDING_ID ? ownDuties.getSlots(sidingId) : otherDuties.getSlots(sidingId);
		final DepotDispatcher.DutyRuns runs = (sidingId, slot, day) -> (sidingId == SIDING_ID ? ownDispatcher : otherDispatcher).hasDutyRun(sidingId, slot, day);
		final List<Long> pool = Arrays.asList(SIDING_ID, OTHER_SIDING_ID);
		final long millis = MONDAY + 8 * HOUR + 30 * MINUTE;

		assertEquals(OTHER_SIDING_ID, DepotDispatcher.chooseReturnSiding(SIDING_ID, pool, slotsOf, runs, millis, sidingId -> true));
		// Full there, it comes home
		assertEquals(SIDING_ID, DepotDispatcher.chooseReturnSiding(SIDING_ID, pool, slotsOf, runs, millis, sidingId -> sidingId == SIDING_ID));
		// Its depot has sent that duty out already, which the other depot's dispatcher knows, not its own
		otherDispatcher.markDutyRun(OTHER_SIDING_ID, 0, DutyShapes.getDayNumber(MONDAY));
		assertEquals(SIDING_ID, DepotDispatcher.chooseReturnSiding(SIDING_ID, pool, slotsOf, runs, millis, sidingId -> true));
		// The one depot's way gives the same as before for its own pool
		assertEquals(SIDING_ID, ownDispatcher.chooseReturnSiding(SIDING_ID, pool, ownDuties, millis, sidingId -> true));
	}

	@Test
	void goesBackToItsOwnSidingWhenNoOtherNeedsATrain() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].service.update(true);
		train[0].nextStoppingIndex = 5;
		assertFalse(train[0].service.chooseDutyReturn());
		assertSame(tripPath, train[0].followed);
		train[0].service.update(false);
		assertFalse(train[0].service.takeReturnedElsewhere());
	}

	@Test
	void aPoolTrainLeavesInPlaceOfASidingWithNoneReady() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		final DepotDuties duties = new DepotDuties();
		duties.sidings.put(SIDING_ID, depot.slots);
		final Map<Long, Map<String, TripPath<TestTrack>>> sidingPaths = new HashMap<>();
		sidingPaths.put(SIDING_ID, tripPaths);
		sidingPaths.put(OTHER_SIDING_ID, tripPaths);
		final long due = MONDAY + 8 * HOUR;

		// Only the other siding has a train ready: of the same pool it goes, of another it doesn't
		final DepotDispatcher apart = new DepotDispatcher();
		assertNull(apart.findDutyDeploy(due, Collections.singletonList(OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> sidingId));
		final DepotDispatcher pooled = new DepotDispatcher();
		final DepotDispatcher.DutyDeploy<TestTrack> deploy = pooled.findDutyDeploy(due, Collections.singletonList(OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool");
		assertNotNull(deploy);
		assertEquals(OTHER_SIDING_ID, deploy.sidingId);
		assertEquals(SIDING_ID, deploy.dutySidingId);
		assertTrue(pooled.hasDutyRun(SIDING_ID, 0, DutyShapes.getDayNumber(MONDAY)));
		assertNull(pooled.findDutyDeploy(due + SECOND, Collections.singletonList(OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool"));

		// With its own siding ready too, that one goes
		final DepotDispatcher both = new DepotDispatcher();
		assertEquals(SIDING_ID, both.findDutyDeploy(due, Arrays.asList(SIDING_ID, OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool").sidingId);
	}

	@Test
	void aDutySetToLeaveFromItsSidingAloneWaitsForATrainThere() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		final DepotDuties duties = new DepotDuties();
		duties.sidings.put(SIDING_ID, depot.slots);
		depot.slots.get(0).start = DepotDuties.Slot.START_ONLY;
		final Map<Long, Map<String, TripPath<TestTrack>>> sidingPaths = new HashMap<>();
		sidingPaths.put(SIDING_ID, tripPaths);
		sidingPaths.put(OTHER_SIDING_ID, tripPaths);
		final long due = MONDAY + 8 * HOUR;
		// Only the other siding of the pool has a train ready: none goes in its place (S8)
		assertNull(new DepotDispatcher().findDutyDeploy(due, Collections.singletonList(OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool"));
		assertEquals(SIDING_ID, new DepotDispatcher().findDutyDeploy(due, Arrays.asList(SIDING_ID, OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool").sidingId);
	}

	@Test
	void aDutyLeavingFromAnySidingTakesTheQuickestThenTheLowestId() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		final DepotDuties duties = new DepotDuties();
		duties.sidings.put(SIDING_ID, depot.slots);
		depot.slots.get(0).start = DepotDuties.Slot.START_ANY;
		final Map<Long, Map<String, TripPath<TestTrack>>> sidingPaths = new HashMap<>();
		sidingPaths.put(SIDING_ID, tripPaths);
		sidingPaths.put(OTHER_SIDING_ID, tripPaths);
		final long due = MONDAY + 8 * HOUR;
		// As quick from either: the lower ID, whichever is the duty's own (S8)
		assertEquals(Math.min(SIDING_ID, OTHER_SIDING_ID), new DepotDispatcher().findDutyDeploy(due, Arrays.asList(SIDING_ID, OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool").sidingId);
		assertEquals(OTHER_SIDING_ID, new DepotDispatcher().findDutyDeploy(due, Collections.singletonList(OTHER_SIDING_ID), duties, sidingPaths::get, routes::get, sidingId -> "pool").sidingId);
	}

	@Test
	void trainsLeaveAtLeastTheReleaseIntervalApart() {
		setSlots(
				day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)),
				day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR + 30 * SECOND), 0, -1))
		);
		final List<TestTrack> path = makePath(0, 1, 2, 3, 0);
		addPath(0, 0, new int[]{1}, path, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		addPath(1, 0, new int[]{1}, path, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final DepotDuties duties = new DepotDuties();
		duties.sidings.put(SIDING_ID, depot.slots);
		final DepotDispatcher dispatcher = new DepotDispatcher();
		dispatcher.releaseIntervalMillis = 120 * (int) SECOND;
		final List<Long> ready = Collections.singletonList(SIDING_ID);

		final DepotDispatcher.DutyDeploy<TestTrack> first = dispatcher.findDutyDeploy(MONDAY + 8 * HOUR, ready, duties, sidingId -> tripPaths, routes::get);
		assertEquals(0, first.slot);
		assertNull(dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 40 * SECOND, ready, duties, sidingId -> tripPaths, routes::get));
		final DepotDispatcher.DutyDeploy<TestTrack> second = dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 2 * MINUTE, ready, duties, sidingId -> tripPaths, routes::get);
		assertEquals(1, second.slot);
		assertFalse(second.late);
	}

	@Test
	void aTrainGoesBackToTheSidingWithRoomThatNeedsATrainSoonest() {
		final DepotDuties duties = new DepotDuties();
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (10 * HOUR), 0, -1)));
		duties.sidings.put(SIDING_ID, depot.slots);
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (9 * HOUR), 0, -1)));
		duties.sidings.put(OTHER_SIDING_ID, depot.slots);
		final DepotDispatcher dispatcher = new DepotDispatcher();
		final List<Long> pool = Arrays.asList(SIDING_ID, OTHER_SIDING_ID);
		final long millis = MONDAY + 8 * HOUR + 30 * MINUTE;

		assertEquals(OTHER_SIDING_ID, dispatcher.chooseReturnSiding(SIDING_ID, pool, duties, millis, sidingId -> true));
		// Full, or its duty already gone, it doesn't need this train
		assertEquals(SIDING_ID, dispatcher.chooseReturnSiding(SIDING_ID, pool, duties, millis, sidingId -> sidingId == SIDING_ID));
		dispatcher.markDutyRun(OTHER_SIDING_ID, 0, DutyShapes.getDayNumber(MONDAY));
		assertEquals(SIDING_ID, dispatcher.chooseReturnSiding(SIDING_ID, pool, duties, millis, sidingId -> true));
	}

	@Test
	void takesOverTheChosenSlotAfterStayingOvernight() {
		setSlots(
				day(true, 1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)),
				day(false, -1, new DepotDuties.Segment(3, (int) (7 * HOUR), 0, -1))
		);
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final TripPath<TestTrack> nextPath = addPath(1, 1, new int[]{0}, makePath(3, 2, 1, 0), stop(0, 3, 0), stop(1, 2, 2), stop(2, 1, 4));
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;
		assertTrue(train.service.isStayingOnLine());

		// Still the same operating day
		now = MONDAY + 23 * HOUR;
		train.service.updateOvernight();
		assertEquals(0, train.service.getDutySlot());

		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		assertSame(nextPath, train.followed);
		assertEquals(0, train.nextStoppingIndex);
		assertEquals(1, train.service.getDutySlot());
		assertEquals(MONDAY + OperatingDay.MILLIS_PER_DAY + 7 * HOUR, train.service.getTripMillis());
		assertTrue(depot.hasDutyRun(SIDING_ID, 1, DutyShapes.getDayNumber(MONDAY + OperatingDay.MILLIS_PER_DAY)));
	}

	@Test
	void waitsWhereItIsWhenTheNextDutyStartsElsewhere() {
		setSlots(
				day(true, 1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)),
				day(false, -1, new DepotDuties.Segment(2, (int) (7 * HOUR), 0, -1))
		);
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		addPath(1, 1, new int[]{1}, makePath(0, 4, 5, 0), stop(1, 4, 0), stop(2, 5, 2));
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;
		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		// It stays at its platform and says why, rather than vanishing into its siding
		assertFalse(train.returned);
		assertEquals(TrainService.HoldReason.OTHER_PLATFORM, train.service.getOvernightHold());
		assertEquals(3, train.service.getHeldStopId());
		train.service.updateOvernight();
		assertFalse(train.returned);

		// Taken straight back by the player's command
		assertTrue(train.service.recallHeld());
		assertTrue(train.returned);
		assertNull(train.service.getOvernightHold());
		assertFalse(train.service.recallHeld());
	}

	@Test
	void aTrainWaitingWithNoDutyGoesOnOnceOneStartsWhereItIs() {
		setSlots(day(true, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1)));
		// Monday only
		depot.slots.get(0).days.get(0).weekdays = 1;
		final List<TestTrack> stayPath = makePath(0, 1, 2, 3);
		final TripPath<TestTrack> tripPath = addPath(0, 0, new int[]{1}, stayPath, stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4));
		final FakeTrain train = newTrain(stayPath);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 3;

		// Its slot has no duty on Tuesday
		now = MONDAY + OperatingDay.MILLIS_PER_DAY + HOUR;
		train.service.updateOvernight();
		assertEquals(TrainService.HoldReason.NO_DUTY, train.service.getOvernightHold());
		assertFalse(train.returned);

		// A Tuesday duty from where it stands is set: it goes on with it
		final DepotDuties.DayDuty tuesday = day(false, -1, new DepotDuties.Segment(3, (int) (7 * HOUR), 0, -1));
		tuesday.weekdays = 1 << 1;
		depot.slots.get(0).days.add(tuesday);
		final TripPath<TestTrack> nextPath = addPath(0, 1, new int[]{0}, makePath(3, 2, 1, 0), stop(0, 3, 0), stop(1, 2, 2), stop(2, 1, 4));
		train.service.updateOvernight();
		assertNull(train.service.getOvernightHold());
		assertSame(nextPath, train.followed);
	}

	/**
	 * Passing a stop with the test tracks, timed by the stops left (D3).
	 */
	private static final transitsim.plan.PathVariants<TestTrack> SKIP_VARIANTS = new transitsim.plan.PathVariants<TestTrack>() {
		@Override
		public TestTrack pass(TestTrack track) {
			return track.passed();
		}

		@Override
		public transitsim.plan.TimeGenerator<TestTrack> getTimeGenerator() {
			return new transitsim.plan.TimeGenerator<TestTrack>() {
				@Override
				public StopTimes calculateStopTimes(List<TestTrack> path) {
					final StopTimes stopTimes = new StopTimes();
					generateTimeSegments(path, new ArrayList<>(), stopTimes, 0, 0);
					return stopTimes;
				}

				@Override
				public void generateTimeSegments(List<TestTrack> path, List<transitsim.plan.TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
					final List<long[]> stops = new ArrayList<>();
					for (int i = 0; i < path.size(); i++) {
						if (path.get(i).getDwellTime() > 0 && !path.get(i).isSidingTrack()) {
							stops.add(stop(i, path.get(i).getStopId(), i * 2));
						}
					}
					stopTimes.copyFrom(StopTimes.of(stops.toArray(new long[0][])));
				}
			};
		}
	};

	/**
	 * One segment on route 4, stopping at platforms 6 to 9 on rails 1 to 4.
	 */
	private FakeTrain fourStops(TripPath<TestTrack>[] tripPath) {
		setSlots(day(false, -1, new DepotDuties.Segment(4, (int) (8 * HOUR), 0, -1)));
		final List<TestTrack> path = makePath(0, 6, 7, 8, 9, 0);
		tripPath[0] = addPath(0, 0, new int[]{1}, path, stop(1, 6, 0), stop(2, 7, 2), stop(3, 8, 4), stop(4, 9, 6));
		final FakeTrain train = newTrain(path);
		train.service.setPathVariants(SKIP_VARIANTS);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath[0]);
		train.nextStoppingIndex = 1;
		return train;
	}

	@Test
	void passesTheStationsItIsToldToFromItsNextStopOn() {
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		// Nothing told, nothing changes
		assertFalse(train.service.chooseSkip());
		assertSame(tripPath[0], train.followed);

		train.service.setSkipPlatforms(new HashSet<>(Arrays.asList(7L, 8L)));
		assertTrue(train.service.chooseSkip());
		assertEquals(TripPath.getSkipKey(tripPath[0].key, new int[]{2, 3}), train.service.getTripPathKey());
		assertTrue(train.service.isSkipping());
		// The same rails, the same stop, only the stops passed
		assertEquals(1, train.nextStoppingIndex);
		assertEquals(tripPath[0].path.size(), train.followed.path.size());
		assertEquals(0, train.followed.path.get(2).getDwellTime());
		assertEquals(0, train.followed.path.get(3).getDwellTime());
		assertEquals(10, train.followed.path.get(4).getDwellTime());
		assertTrue(transitsim.plan.PathTimes.isPassingPlatform(train.followed.path, 2));
		// Its siding's paths are left as they were
		assertEquals(10, tripPath[0].path.get(2).getDwellTime());
		assertNull(tripPaths.get(train.service.getTripPathKey()));
		// It chooses once at a stop
		assertFalse(train.service.chooseSkip());
	}

	@Test
	void theFirstAndLastStopsOfEachSegmentAreAlwaysStops() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = twoRoutes(train);
		train[0].service.setPathVariants(SKIP_VARIANTS);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].nextStoppingIndex = 1;
		// Route 1 on rails 1 to 3, route 2 on rails 4 and 5: only platform 2 is between the two ends of a segment
		train[0].service.setSkipPlatforms(new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L)));
		assertTrue(train[0].service.chooseSkip());
		assertEquals(TripPath.getSkipKey(tripPath.key, new int[]{2}), train[0].service.getTripPathKey());
	}

	@Test
	void toldToStopAgainItTakesItsUsualPathAtTheNextStop() {
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		train.service.setSkipPlatforms(Collections.singleton(8L));
		assertTrue(train.service.chooseSkip());
		assertTrue(train.service.isSkipping());

		// At platform 7 it is told to stop again, so it stops at 8 after all
		train.nextStoppingIndex = 2;
		train.service.setSkipPlatforms(Collections.emptySet());
		assertTrue(train.service.chooseSkip());
		assertSame(tripPath[0], train.followed);
		assertEquals(tripPath[0].key, train.service.getTripPathKey());
		assertEquals(2, train.nextStoppingIndex);
		assertFalse(train.service.isSkipping());
	}

	@Test
	void stopsPassedStayPassedWhileItKeepsPassing() {
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		train.service.setSkipPlatforms(Collections.singleton(7L));
		assertTrue(train.service.chooseSkip());
		final TripPath<TestTrack> skipping = train.followed;
		// Past platform 7 and at 8, told the same, it keeps the path rather than making it again
		train.nextStoppingIndex = 3;
		train.service.setSkipPlatforms(new HashSet<>(Collections.singleton(7L)));
		assertFalse(train.service.chooseSkip());
		assertSame(skipping, train.followed);
	}

	@Test
	void aTrainPassingStopsIsPutBackOnItsPathFromASave() throws Exception {
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		train.service.setSkipPlatforms(new HashSet<>(Arrays.asList(7L, 8L)));
		train.service.chooseSkip();
		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		train.service.write(values);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);

		final FakeTrain restored = newTrain(tripPath[0].path);
		restored.service.setPathVariants(SKIP_VARIANTS);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertTrue(restored.service.restoreTripPath(tripPaths));
		assertEquals(train.service.getTripPathKey(), restored.service.getTripPathKey());
		assertEquals(0, restored.followed.path.get(2).getDwellTime());
		assertEquals(new TreeSet<>(Arrays.asList(7L, 8L)), restored.service.getSkipPlatforms());

		// Without a way to make it, the path is gone as any other path that is
		final FakeTrain noVariants = newTrain(tripPath[0].path);
		noVariants.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertFalse(noVariants.service.restoreTripPath(tripPaths));
	}

	@Test
	void aRouteCutShortInOneSegmentRunsInFullInALaterOne() {
		// D4: route 1 to platform 2 only, back on route 3 from platform 2, then route 1 in full; cut and joined part of the
		// way along by segment, so the third segment still has platform 3
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, 1), new DepotDuties.Segment(3, (int) (8 * HOUR + 10 * MINUTE), 1, -1), new DepotDuties.Segment(1, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final List<TestTrack> path = makePath(0, 1, 2, 1, 2, 3, 0);
		final String key = DutyShapes.getSlotKey(depot.slots, 0, 0, routes::get);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(key, Arrays.asList(1L, 3L, 1L), new HashMap<>(Collections.singletonMap(1L, 1)), new HashMap<>(Collections.singletonMap(3L, 1)), new HashMap<>(), path, new int[]{1, 2, 3}, null, null, false, false);
		dutyPlan.segmentCuts.put(0, 1);
		dutyPlan.segmentEntries.put(1, 1);
		dutyPlan.segmentsKnown = true;
		final TripPath<TestTrack> tripPath = TripPath.ofDuty(dutyPlan, new int[]{1, 2, 3}, path, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 1, 4), stop(4, 2, 6), stop(5, 3, 8)));
		tripPaths.put(key, tripPath);
		final FakeTrain train = newTrain(path);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train.service.deployDuty(0, MONDAY, tripPath);
		train.nextStoppingIndex = 4;
		now = MONDAY + 8 * HOUR + 21 * MINUTE;
		train.service.onStopReached();
		assertEquals(1, train.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, train.service.getTripMillis());
		final List<PlanRoute> dutyRoutes = Arrays.asList(routes.get(1L), routes.get(3L), routes.get(1L));
		// The third segment runs route 1 to its end, the first only to platform 2
		final long[][] third = tripPath.stopTimes.getSegmentTimes(2, dutyRoutes);
		assertTrue(third[2][0] >= 0, Arrays.deepToString(third));
		final long[][] first = tripPath.stopTimes.getSegmentTimes(0, dutyRoutes);
		assertTrue(first[1][0] >= 0);
		assertEquals(-1, first[2][0]);
		// The second joins route 3 at its second stop
		assertEquals(-1, tripPath.stopTimes.getSegmentTimes(1, dutyRoutes)[0][0]);
		assertEquals(1, tripPath.stopTimes.getSegmentEntry(1));
		// By route, as before, the cut of the first segment would cut the third short too
		dutyPlan.segmentsKnown = false;
		final TripPath<TestTrack> byRoute = TripPath.ofDuty(dutyPlan, new int[]{1, 2, 3}, path, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 1, 4), stop(4, 2, 6), stop(5, 3, 8)));
		assertEquals(-1, byRoute.stopTimes.getSegmentTimes(2, dutyRoutes)[2][0]);
	}

	/**
	 * Turning back early with the test tracks (D4): from platform 2 round a turn back rail to platform 2 the other way, and
	 * from there back to the siding.
	 */
	private static final transitsim.plan.PathVariants<TestTrack> TURN_VARIANTS = new transitsim.plan.PathVariants<TestTrack>() {
		@Override
		public TestTrack pass(TestTrack track) {
			return SKIP_VARIANTS.pass(track);
		}

		@Override
		public transitsim.plan.TimeGenerator<TestTrack> getTimeGenerator() {
			return SKIP_VARIANTS.getTimeGenerator();
		}

		@Override
		public Collection<Long> getTurnTargets(long platformId) {
			return platformId == 2 ? Collections.singletonList(2L) : Collections.emptyList();
		}

		@Override
		public List<TestTrack> getTurnLink(long fromPlatformId, long toPlatformId) {
			return fromPlatformId == 2 && toPlatformId == 2 ? Arrays.asList(new TestTrack(20, 40, false, 2, 10, 2), new TestTrack(40, 50, false, 0, 0, 2), new TestTrack(50, 40, false, 0, 0, 2), new TestTrack(40, 20, false, 2, 0, 2)) : null;
		}

		@Override
		public List<TestTrack> getTurnReturn(long platformId) {
			return platformId == 2 ? Arrays.asList(new TestTrack(40, 20, false, 2, 0, 2), new TestTrack(20, 0, true, 0, 0, 2)) : platformId == 3 ? Arrays.asList(new TestTrack(60, 80, false, 3, 10, 3), new TestTrack(80, 200, false, 0, 0, 3), new TestTrack(200, 220, true, 0, 0, 3)) : null;
		}

		@Override
		public List<TestTrack> getWayOut(long platformId) {
			return platformId == 3 ? Arrays.asList(WAY_OUT_SIDING, WAY_OUT_LINE, new TestTrack(60, 80, false, 3, 10, 3)) : null;
		}
	};
	private static final TestTrack WAY_OUT_SIDING = new TestTrack(300, 320, true, 0, 0, 0);
	private static final TestTrack WAY_OUT_LINE = new TestTrack(320, 360, false, 0, 0, 0);

	/**
	 * Route 1 out on rails 1 to 3, route 3 back on rails 3 to 5, platform 2 on rails 2 and 4.
	 */
	private TripPath<TestTrack> outAndBack(FakeTrain[] train) {
		setSlots(day(false, -1, new DepotDuties.Segment(1, (int) (8 * HOUR), 0, -1), new DepotDuties.Segment(3, (int) (8 * HOUR + 20 * MINUTE), 0, -1)));
		final List<TestTrack> path = makePath(0, 1, 2, 3, 2, 1, 0);
		final String key = DutyShapes.getSlotKey(depot.slots, 0, 0, routes::get);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>(key, Arrays.asList(1L, 3L), new HashMap<>(), new HashMap<>(), new HashMap<>(), path, new int[]{1, 3}, null, null, false, false);
		dutyPlan.segmentsKnown = true;
		final TripPath<TestTrack> tripPath = TripPath.ofDuty(dutyPlan, new int[]{1, 3}, path, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 2, 22), stop(5, 1, 24)));
		tripPaths.put(key, tripPath);
		train[0] = newTrain(path);
		train[0].service.setPathVariants(TURN_VARIANTS);
		now = MONDAY + 7 * HOUR + 58 * MINUTE;
		train[0].service.deployDuty(0, MONDAY, tripPath);
		train[0].nextStoppingIndex = 1;
		return tripPath;
	}

	@Test
	void turnsBackEarlyAtTheNearestStopItCanAndGoesOnWithItsDuty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		assertFalse(train[0].service.chooseEarlyTurn());
		train[0].service.setTurnBack(transitsim.plan.Contingency.TURN_NEAREST, Collections.emptySet(), transitsim.plan.Contingency.AFTER_RESUME);
		assertEquals(1, train[0].service.getTurnState());
		// Platform 1 has no way round, platform 2 has, and platform 3 is where the duty turns back anyway
		assertTrue(train[0].service.chooseEarlyTurn());
		final TripPath<TestTrack> turned = train[0].followed;
		assertEquals(TripPath.getTurnKey(tripPath.key, 0, 2, 1, 4, 1, true), train[0].service.getTripPathKey());
		assertEquals(2, train[0].service.getTurnState());
		assertEquals(1, train[0].nextStoppingIndex);
		// Out to platform 2, round the turn back rail, then on with route 3 from platform 2
		assertEquals(Arrays.asList(0L, 1L, 2L, 0L, 0L, 2L, 1L, 0L), turned.path.stream().map(TestTrack::getStopId).collect(java.util.stream.Collectors.toList()));
		assertArrayEquals(new int[]{1, 5}, turned.dutyStarts);
		assertEquals(2, turned.turnRail);
		assertEquals(5, turned.turnResumeRail);
		assertFalse(train[0].service.isClearingOut());

		// Riders get off at platform 2, it runs empty round, and takes riders again from platform 2 the other way
		train[0].nextStoppingIndex = 2;
		assertTrue(train[0].service.isClearingOut());
		assertFalse(train[0].service.isOutOfService());
		train[0].nextStoppingIndex = 4;
		assertTrue(train[0].service.isOutOfService());
		// On its way to platform 2 the other way, still empty, until it gets there
		train[0].nextStoppingIndex = 5;
		assertTrue(train[0].service.isOutOfService());
		now = MONDAY + 8 * HOUR + 5 * MINUTE;
		train[0].service.onStopReached();
		assertFalse(train[0].service.isOutOfService());
		assertEquals(3, train[0].service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, train[0].service.getTripMillis());
		// Its service on route 3 left platform 3 at 08:20, so it is planned at platform 2 later than that, and waits there
		assertTrue(train[0].service.getPlannedArrivalMillis(5) > MONDAY + 8 * HOUR + 20 * MINUTE, String.valueOf(train[0].service.getPlannedArrivalMillis(5) - MONDAY - 8 * HOUR));
		// Turned back once, it doesn't again
		train[0].nextStoppingIndex = 5;
		assertFalse(train[0].service.chooseEarlyTurn());
	}

	@Test
	void turnsBackAtAGivenStationAndGoesBackEmpty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		train[0].service.setTurnBack(transitsim.plan.Contingency.TURN_STATION, Collections.singleton(2L), transitsim.plan.Contingency.AFTER_DEADHEAD);
		assertTrue(train[0].service.chooseEarlyTurn());
		final TripPath<TestTrack> turned = train[0].followed;
		assertEquals(Arrays.asList(0L, 1L, 2L, 0L, 0L, 2L, 0L), turned.path.stream().map(TestTrack::getStopId).collect(java.util.stream.Collectors.toList()));
		assertArrayEquals(new int[]{1}, turned.dutyStarts);
		assertEquals(Collections.singletonList(1L), turned.routeIds);
		assertEquals(-1, turned.turnResumeRail);
		// Nothing after platform 2 is a stop until the siding
		for (int i = 3; i < turned.path.size() - 1; i++) {
			assertEquals(0, turned.path.get(i).getDwellTime());
		}
		train[0].nextStoppingIndex = 6;
		assertTrue(train[0].service.isOutOfService());
		assertFalse(train[0].service.chooseDutyReturn());
	}

	@Test
	void toldAStationItCantTurnBackAtItSaysSo() {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		train[0].service.setTurnBack(transitsim.plan.Contingency.TURN_STATION, Collections.singleton(1L), transitsim.plan.Contingency.AFTER_RESUME);
		assertFalse(train[0].service.chooseEarlyTurn());
		assertEquals(3, train[0].service.getTurnState());
		// Told none, it is told nothing
		train[0].service.setTurnBack(0, Collections.emptySet(), 0);
		assertEquals(0, train[0].service.getTurnState());
	}

	@Test
	void aTrainTurnedBackIsPutBackOnItsPathFromASaveAndCanPassStopsToo() throws Exception {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		train[0].service.setTurnBack(transitsim.plan.Contingency.TURN_NEAREST, Collections.emptySet(), transitsim.plan.Contingency.AFTER_RESUME);
		train[0].service.chooseEarlyTurn();
		final String key = train[0].service.getTripPathKey();
		// Passing stops of the turned path keeps it turned
		train[0].service.setSkipPlatforms(Collections.singleton(1L));
		train[0].nextStoppingIndex = 2;
		train[0].service.chooseSkip();
		assertEquals(TripPath.getSkipKey(key, new int[0]), TripPath.getSkipBase(train[0].service.getTripPathKey()));
		assertEquals(key, TripPath.getSkipBase(train[0].service.getTripPathKey()));

		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		train[0].service.write(values);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);
		final FakeTrain restored = newTrain(train[0].path);
		restored.service.setPathVariants(TURN_VARIANTS);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertTrue(restored.service.restoreTripPath(tripPaths));
		assertEquals(train[0].service.getTripPathKey(), restored.service.getTripPathKey());
		assertEquals(train[0].followed.path.size(), restored.followed.path.size());
		assertEquals(transitsim.plan.Contingency.TURN_NEAREST, restored.service.getTurnMode());
	}

	private DepotDuties dutiesOfSlots() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.put(SIDING_ID, depot.slots);
		return duties;
	}

	@Test
	void aSpareStartsTheDutyAtALaterSegmentOutOfTheSiding() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		final TripPath<TestTrack> from = TripPath.withFrom(tripPath, TripPath.getFromKey(tripPath.key, 1), TURN_VARIANTS);
		// Out of the siding by its own way to platform 3, then route 3 as the duty runs it
		assertEquals(Arrays.asList(0L, 0L, 3L, 2L, 1L, 0L), from.path.stream().map(TestTrack::getStopId).collect(java.util.stream.Collectors.toList()));
		assertSame(WAY_OUT_SIDING, from.path.get(0));
		assertArrayEquals(new int[]{2, 2}, from.dutyStarts);

		final FakeTrain spare = newTrain(from.path);
		spare.service.setPathVariants(TURN_VARIANTS);
		now = MONDAY + 8 * HOUR + 10 * MINUTE;
		spare.service.deployDuty(0, 0, MONDAY, from, 1);
		assertEquals(3, spare.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 20 * MINUTE, spare.service.getTripMillis());
		spare.nextStoppingIndex = 3;
		spare.service.onStopReached();
		assertEquals(3, spare.service.getTripRouteId());
		assertTrue(spare.service.getPlannedArrivalMillis(3) > MONDAY + 8 * HOUR + 20 * MINUTE);

		// Put back from a save by its key
		final FakeTrain restored = newTrain(tripPath.path);
		restored.service.setPathVariants(TURN_VARIANTS);
		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		try {
			spare.service.write(values);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertTrue(restored.service.restoreTripPath(tripPaths));
		assertEquals(from.path.size(), restored.followed.path.size());
	}

	@Test
	void aLateTrainHandsItsDutyOverAndGoesBackEmpty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		depot.dispatcher.findDutyDeploy(MONDAY + 7 * HOUR, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS);
		now = MONDAY + 8 * HOUR + 1 * MINUTE;
		assertFalse(train[0].service.chooseHandOver());
		train[0].service.setTakeover(true);
		assertEquals(1, train[0].service.getTakeoverState());
		assertTrue(train[0].service.chooseHandOver());
		// It runs route 1 to platform 3, where everyone gets off, then back to the siding empty
		assertEquals(TripPath.getEndKey(tripPath.key, 0, 3), train[0].service.getTripPathKey());
		assertEquals(Arrays.asList(0L, 1L, 2L, 3L, 0L, 0L), train[0].followed.path.stream().map(TestTrack::getStopId).collect(java.util.stream.Collectors.toList()));
		assertEquals(2, train[0].service.getTakeoverState());
		train[0].nextStoppingIndex = 5;
		assertTrue(train[0].service.isOutOfService());
		assertEquals(0, train[0].service.getTurnState());
		// The depot sends a spare out of the siding for route 3 in time for its 08:20 service, 4 minutes out, at 08:15:45
		assertEquals(Collections.singletonList(SIDING_ID + " 0 " + MONDAY + " 1"), depot.dispatcher.takeovers);
		assertNull(depot.dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 10 * MINUTE, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS));
		final DepotDispatcher.DutyDeploy<TestTrack> deploy = depot.dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 16 * MINUTE, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS);
		assertNotNull(deploy);
		assertEquals(1, deploy.startSegment);
		assertEquals(TripPath.getFromKey(tripPath.key, 1), deploy.path.key);
		assertTrue(depot.dispatcher.takeovers.isEmpty());
	}

	@Test
	void noSpareIsSentWhoseWayOutRunsWhereTheLateTrainIs() {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		depot.dispatcher.findDutyDeploy(MONDAY + 7 * HOUR, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS);
		now = MONDAY + 8 * HOUR + 1 * MINUTE;
		// The late train is held up by one holding the rail the spare's way out runs along
		train[0].affected.add(WAY_OUT_LINE.getTrackKey());
		train[0].service.setTakeover(true);
		assertFalse(train[0].service.chooseHandOver());
		assertEquals(TrainService.TAKEOVER_CROSSES, train[0].service.getTakeoverState());
		assertTrue(depot.dispatcher.takeovers.isEmpty());
	}

	@Test
	void withNoSpareInTheDepotTheTrainRunsOn() {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		// No train ready in any siding
		depot.dispatcher.findDutyDeploy(MONDAY + 7 * HOUR, Collections.emptySet(), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS);
		now = MONDAY + 8 * HOUR + 1 * MINUTE;
		train[0].service.setTakeover(true);
		assertFalse(train[0].service.chooseHandOver());
		assertEquals(TrainService.TAKEOVER_NO_SPARE, train[0].service.getTakeoverState());
	}

	@Test
	void aTrainSentOutLateLeavesForTheFirstSegmentItCanGetTo() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		final DepotDispatcher dispatcher = new DepotDispatcher();
		// Route 1 at 08:00 is long missed at 08:12; route 3 at 08:20 can still be got to, 4 minutes out of the siding
		assertNull(dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 12 * MINUTE, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS));
		final DepotDispatcher.DutyDeploy<TestTrack> deploy = dispatcher.findDutyDeploy(MONDAY + 8 * HOUR + 16 * MINUTE, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS);
		assertNotNull(deploy);
		assertFalse(deploy.late);
		assertEquals(1, deploy.startSegment);
		assertEquals(TripPath.getFromKey(tripPath.key, 1), deploy.path.key);
		// Too late for route 3 as well while the duty still runs, it isn't sent at all, nor put on the line
		final DepotDispatcher tooLate = new DepotDispatcher();
		assertNull(tooLate.findDutyDeploy(MONDAY + 8 * HOUR + 20 * MINUTE, Collections.singleton(SIDING_ID), dutiesOfSlots(), sidingId -> sidingId == SIDING_ID ? tripPaths : null, routes::get, sidingId -> sidingId, sidingId -> TURN_VARIANTS));
		assertTrue(tooLate.hasDutyRun(SIDING_ID, 0, transitsim.plan.DutyShapes.getDayNumber(MONDAY)));
	}

	@Test
	void aDispatcherHoldsATrainAndLetsItGo() {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		assertFalse(train[0].service.isDispatchHeld());
		train[0].service.dispatch(TrainService.COMMAND_HOLD, 0, null);
		assertTrue(train[0].service.isDispatchHeld());
		assertEquals(TrainService.DISPATCH_HOLD, train[0].service.getDispatchState());
		train[0].service.dispatch(TrainService.COMMAND_RELEASE, 0, null);
		assertFalse(train[0].service.isDispatchHeld());
		assertFalse(train[0].service.hasDispatch());
	}

	@Test
	void aDispatcherHasATrainStandLongerAtItsStop() {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		train[0].service.dispatch(TrainService.COMMAND_EXTEND_DWELL, MINUTE, null);
		// Counted from when it would leave the stop
		now = MONDAY + 8 * HOUR;
		assertTrue(train[0].service.isDispatchHeld());
		now = MONDAY + 8 * HOUR + 59 * SECOND;
		assertTrue(train[0].service.isDispatchHeld());
		now = MONDAY + 8 * HOUR + MINUTE;
		assertFalse(train[0].service.isDispatchHeld());
		assertFalse(train[0].service.hasDispatch());
	}

	@Test
	void clearedOutAtItsNextStopItTurnsBackThereEmptyIfItCan() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		train[0].service.dispatch(TrainService.COMMAND_CLEAR_OUT, 0, null);
		assertTrue(train[0].service.chooseDispatch());
		// At platform 1, its next stop platform 2 has a way round: back from there empty
		assertEquals(TripPath.getTurnKey(tripPath.key, 0, 2, 1, 2, 0, false), train[0].service.getTripPathKey());
		train[0].nextStoppingIndex = 3;
		assertTrue(train[0].service.isOutOfService());
		assertEquals(0, train[0].service.getDispatchState());
	}

	@Test
	void clearedOutWhereItCantTurnBackItRunsTheSegmentEmptyAndGoesBack() {
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		train.service.setPathVariants(TURN_VARIANTS);
		// At platform 6, the next stop platform 7 has no way round: everyone gets off at 7, then it runs empty past 8 and 9
		train.service.dispatch(TrainService.COMMAND_CLEAR_OUT, 0, null);
		assertTrue(train.service.chooseDispatch());
		assertEquals(TripPath.getEndKey(tripPath[0].key, 0, 4, 2), train.service.getTripPathKey());
		assertEquals(0, train.followed.path.get(3).getDwellTime());
		assertEquals(0, train.followed.path.get(4).getDwellTime());
		assertEquals(tripPath[0].path.size(), train.followed.path.size());
		train.nextStoppingIndex = 2;
		assertTrue(train.service.isClearingOut());
		train.nextStoppingIndex = 5;
		assertTrue(train.service.isOutOfService());
	}

	@Test
	void sentBackItRunsThisSegmentAndGoesBackEmpty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		train[0].service.dispatch(TrainService.COMMAND_RETURN, 0, null);
		assertTrue(train[0].service.chooseDispatch());
		assertEquals(TripPath.getEndKey(tripPath.key, 0, 3), train[0].service.getTripPathKey());
		// No spare is asked for
		assertTrue(depot.dispatcher.takeovers.isEmpty());
	}

	@Test
	void turnedBackByADispatcherAndClearedOutItGoesBackEmpty() {
		final FakeTrain[] train = new FakeTrain[1];
		final TripPath<TestTrack> tripPath = outAndBack(train);
		train[0].service.dispatch(TrainService.COMMAND_TURN_AT, 0, Collections.singleton(2L));
		final FakeTrain[] other = new FakeTrain[1];
		// Turned back alone, it goes on with its duty
		assertTrue(train[0].service.chooseEarlyTurn());
		assertEquals(TripPath.getTurnKey(tripPath.key, 0, 2, 1, 4, 1, true), train[0].service.getTripPathKey());

		final TripPath<TestTrack> again = outAndBack(other);
		other[0].service.dispatch(TrainService.COMMAND_TURN_AT, 0, Collections.singleton(2L));
		other[0].service.dispatch(TrainService.COMMAND_CLEAR_OUT, 0, null);
		// Clearing out leaves the turning back to it, which then goes back empty
		assertFalse(other[0].service.chooseDispatch());
		assertTrue(other[0].service.chooseEarlyTurn());
		assertEquals(TripPath.getTurnKey(again.key, 0, 2, 1, 2, 0, false), other[0].service.getTripPathKey());
		assertEquals(0, other[0].service.getDispatchState());
	}

	@Test
	void whatADispatcherOrderedIsKeptThroughASave() throws Exception {
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train);
		train[0].service.dispatch(TrainService.COMMAND_HOLD, 0, null);
		train[0].service.dispatch(TrainService.COMMAND_TURN_AT, 0, Collections.singleton(2L));
		final transitsim.io.BinaryKeyed.Writer values = new transitsim.io.BinaryKeyed.Writer();
		train[0].service.write(values);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		values.writeTo(writer);
		final FakeTrain restored = newTrain(train[0].path);
		restored.service.read(new transitsim.io.BinaryKeyed.Reader(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes())));
		assertEquals(train[0].service.getDispatchState(), restored.service.getDispatchState());
		assertEquals(Collections.singleton(2L), restored.service.getDispatchTurnPlatforms());
		// Cancelled, nothing is left
		restored.service.dispatch(TrainService.COMMAND_CANCEL, 0, null);
		assertFalse(restored.service.hasDispatch());
	}

	@Test
	void aTripPathWritesAsManyValuesAsItSays() throws Exception {
		final int[] count = new int[1];
		final transitsim.io.KeyedWriter counter = (transitsim.io.KeyedWriter) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{transitsim.io.KeyedWriter.class}, (proxy, method, args) -> {
			count[0]++;
			return null;
		});
		final FakeTrain[] train = new FakeTrain[1];
		outAndBack(train)
				.write(counter);
		assertEquals(TripPath.WRITTEN_VALUES, count[0]);
	}

	@Test
	void aServiceWritesAsManyValuesAsItSays() throws Exception {
		// The hosts write the size of a saved train before its values, so one too few breaks what comes after it
		final int[] count = new int[1];
		final transitsim.io.KeyedWriter counter = (transitsim.io.KeyedWriter) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{transitsim.io.KeyedWriter.class}, (proxy, method, args) -> {
			count[0]++;
			return null;
		});
		@SuppressWarnings("unchecked") final TripPath<TestTrack>[] tripPath = new TripPath[1];
		final FakeTrain train = fourStops(tripPath);
		train.service.setSkipPlatforms(Collections.singleton(7L));
		train.service.write(counter);
		assertEquals(TrainService.SAVED_KEYS, count[0]);
	}

	@Test
	void theKeysOfPathsPassingStopsKeepWhatTheyWere() {
		final String returnKey = TripPath.getReturnKey("duty|1", OTHER_SIDING_ID);
		final String skipKey = TripPath.getSkipKey(returnKey, new int[]{3, 5});
		assertTrue(TripPath.isSkipKey(skipKey));
		assertEquals(returnKey, TripPath.getSkipBase(skipKey));
		assertArrayEquals(new int[]{3, 5}, TripPath.getSkipRails(skipKey));
		assertEquals(OTHER_SIDING_ID, TripPath.getReturnSidingId(skipKey));
		assertTrue(TripPath.isReturnKey(skipKey));
		assertEquals(returnKey, TripPath.getSkipKey(returnKey, new int[0]));
		assertFalse(TripPath.isSkipKey(returnKey));
	}

	private class FakeTrain implements TrainService.Vehicle<TestTrack> {

		final TrainService<TestTrack> service;
		final List<TestTrack> path;
		int nextStoppingIndex;
		int movedTo = -1;
		boolean returned;
		TripPath<TestTrack> followed;
		/**
		 * Rails another train is on, and rails another train holds.
		 */
		final Set<UUID> takenRails = new HashSet<>();
		final Set<UUID> heldRails = new HashSet<>();
		List<UUID> reserved;
		/**
		 * Rails where trains holding up this one are (D5).
		 */
		final Set<UUID> affected = new HashSet<>();

		FakeTrain(List<TestTrack> path) {
			this.path = path;
			service = new TrainService<>("train", this, () -> now);
		}

		@Override
		public List<TestTrack> getPath() {
			return followed == null ? path : followed.path;
		}

		@Override
		public int getNextStoppingIndex() {
			return nextStoppingIndex;
		}

		@Override
		public boolean isRepeat() {
			return false;
		}

		@Override
		public int getRepeatIndex2() {
			return 0;
		}

		@Override
		public int getPathRepeatIndex1() {
			return 0;
		}

		@Override
		public int getPathRepeatIndex2() {
			return 0;
		}

		@Override
		public void setLastLap(boolean isLastLap) {
		}

		@Override
		public void followPath(TripPath<TestTrack> tripPath) {
			followed = tripPath;
		}

		@Override
		public Collection<UUID> getAffectedRails(List<UUID> remaining) {
			final Set<UUID> rails = new HashSet<>(remaining);
			rails.addAll(affected);
			return rails;
		}

		@Override
		public void changePath(TripPath<TestTrack> tripPath, int newStopIndex) {
			followed = tripPath;
			nextStoppingIndex = newStopIndex;
		}

		@Override
		public boolean isTurnBackFree(List<UUID> rails) {
			return rails.stream().noneMatch(takenRails::contains);
		}

		@Override
		public void reserveTurnBack(List<UUID> rails, int untilIndex) {
			reserved = rails;
		}

		@Override
		public boolean isWayClear(List<UUID> rails) {
			return isTurnBackFree(rails) && rails.stream().noneMatch(heldRails::contains);
		}

		@Override
		public boolean isStopFree(int stopIndex) {
			return true;
		}

		@Override
		public void moveToStop(int stopIndex) {
			movedTo = stopIndex;
			nextStoppingIndex = stopIndex;
		}

		@Override
		public void returnToDepot() {
			returned = true;
		}
	}

	private class DutyDepot implements TrainService.Board {

		final DepotDispatcher dispatcher = new DepotDispatcher();

		@Override
		public int requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected) {
			return dispatcher.requestTakeover(now, dutySidingId, slot, dayStart, fromSegment, fromKey, serviceMillis, affected, sidingId -> sidingId == SIDING_ID ? tripPaths : null, sidingId -> TURN_VARIANTS, sidingId -> sidingId);
		}

		List<DepotDuties.Slot> slots = new ArrayList<>();
		List<DepotDuties.Slot> otherSlots = new ArrayList<>();
		long returnTo = SIDING_ID;
		/**
		 * The depot a duty was last set to go back to, or 0.
		 */
		long orderedDepot;
		final List<Long> refused = new ArrayList<>();
		final List<String> missed = new ArrayList<>();
		final Set<String> reserved = new HashSet<>();

		@Override
		public long[] findDuty(long dutyId) {
			for (int i = 0; i < slots.size(); i++) {
				if (slots.get(i).id == dutyId) {
					return new long[]{SIDING_ID, i};
				}
			}
			for (int i = 0; i < otherSlots.size(); i++) {
				if (otherSlots.get(i).id == dutyId) {
					return new long[]{OTHER_SIDING_ID, i};
				}
			}
			return null;
		}

		@Override
		public void noteReturnMissed(long sidingId, long targetSidingId, String reason) {
			missed.add(sidingId + ">" + targetSidingId);
		}
		final Map<String, Long> runDays = new HashMap<>();

		@Override
		public RouteTimetable getTimetable(String trainId) {
			return routes.get(1L).timetable;
		}

		@Override
		public List<TestRoute> getTrainRoutes(String trainId) {
			return Collections.singletonList(routes.get(1L));
		}

		@Override
		public long findNextTrip(String trainId, long readyMillis) {
			return 0;
		}

		@Override
		public long findNextService(PlanRoute planRoute, long readyMillis) {
			final TestRoute route = (TestRoute) planRoute;
			return 0;
		}

		@Override
		public void reserveTrip(long routeId, long tripMillis) {
			reserved.add(routeId + "@" + tripMillis);
		}

		@Override
		public boolean isTripReserved(long routeId, long tripMillis) {
			return reserved.contains(routeId + "@" + tripMillis);
		}

		@Override
		public List<DepotDuties.Slot> getDutySlots(long sidingId) {
			return sidingId == SIDING_ID ? slots : sidingId == OTHER_SIDING_ID ? otherSlots : Collections.emptyList();
		}

		@Override
		public long chooseReturnSiding(long sidingId, long depotId, java.util.function.LongPredicate canGoBack) {
			if (depotId != 0) {
				orderedDepot = depotId;
			}
			if (canGoBack.test(returnTo)) {
				return returnTo;
			}
			refused.add(returnTo);
			return sidingId;
		}

		@Override
		public boolean hasDutyRun(long sidingId, int slot, long day) {
			return Objects.equals(runDays.get(sidingId + ":" + slot), day);
		}

		@Override
		public void markDutyRun(long sidingId, int slot, long day) {
			runDays.put(sidingId + ":" + slot, day);
		}
	}
}
