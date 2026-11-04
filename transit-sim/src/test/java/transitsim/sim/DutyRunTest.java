package transitsim.sim;

import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.DutyPlan;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.RouteTimetable;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
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
		final TripPath<TestTrack> tripPath = TripPath.ofDuty(dutyPlan, starts, path, StopTimes.of(stops));
		tripPaths.put(key, tripPath);
		return tripPath;
	}

	private FakeTrain newTrain(List<TestTrack> path) {
		final FakeTrain train = new FakeTrain(path);
		train.service.setSiding(SIDING_ID);
		train.service.setNetwork(depot, routes::get, null);
		train.service.setStopTimes(new StopTimes(), tripPaths);
		return train;
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
		assertTrue(train[0].service.takeReturnedElsewhere());
		assertFalse(train[0].service.takeReturnedElsewhere());
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
	void goesBackWhenTheNextDutyStartsElsewhere() {
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
		assertTrue(train.returned);
	}

	private class FakeTrain implements TrainService.Vehicle<TestTrack> {

		final TrainService<TestTrack> service;
		final List<TestTrack> path;
		int nextStoppingIndex;
		int movedTo = -1;
		boolean returned;
		TripPath<TestTrack> followed;

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
		public void changePath(TripPath<TestTrack> tripPath, int newStopIndex) {
			followed = tripPath;
			nextStoppingIndex = newStopIndex;
		}

		@Override
		public boolean isTurnBackFree(List<UUID> rails) {
			return true;
		}

		@Override
		public void reserveTurnBack(List<UUID> rails, int untilIndex) {
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

		List<DepotDuties.Slot> slots = new ArrayList<>();
		List<DepotDuties.Slot> otherSlots = new ArrayList<>();
		long returnTo = SIDING_ID;
		final Set<String> reserved = new HashSet<>();
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
		public long chooseReturnSiding(long sidingId) {
			return returnTo;
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
