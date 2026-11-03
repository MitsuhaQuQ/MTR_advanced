package transitsim.sim;

import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.TripTail;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;
import transitsim.plan.RouteTimetable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs services the way a train on a path does, without a world.
 */
class TrainServiceTest {

	private static final long SECOND = 1000;
	private static final int MILLIS_PER_TICK = 50;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	/**
	 * Monday the 5th of January 2026 at midnight in UTC.
	 */
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final int TICKS_PER_MINUTE = (int) (MINUTE / MILLIS_PER_TICK);
	private static final List<UUID> FIRST_TURN_BACK = Collections.singletonList(new UUID(0, 1));
	private static final List<UUID> SECOND_TURN_BACK = Collections.singletonList(new UUID(0, 2));

	private long now;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTrainStillOnItsServiceGoesToTheStopItShouldBeAt() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);

		now = MONDAY + 8 * HOUR + 3 * MINUTE;
		train.service.alignToTimetable(true);

		// The first stop was left at 08:00:10 and the second is left at 08:02:10, both more than a minute ago at most
		assertEquals(2, train.movedTo);
		assertEquals(MONDAY + 8 * HOUR, train.service.getTripMillis());
	}

	@Test
	void aTrainWhoseServiceHasEndedTakesTheOneRunningNow() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);

		now = MONDAY + 9 * HOUR + 3 * MINUTE;
		train.service.alignToTimetable(true);

		assertEquals(MONDAY + 9 * HOUR, train.service.getTripMillis());
		assertEquals(2, train.movedTo);
		assertTrue(line.depot.isTripReserved(line.route.id, MONDAY + 9 * HOUR));
	}

	@Test
	void aTrainGoesBackToTheLastFreeStop() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		train.takenStops.add(2);

		now = MONDAY + 9 * HOUR + 3 * MINUTE;
		train.service.alignToTimetable(true);

		assertEquals(1, train.movedTo);
	}

	@Test
	void twoTrainsDoNotTakeTheSameService() {
		final Line line = new Line();
		final FakeTrain train1 = line.deploy(MONDAY + 8 * HOUR);
		final FakeTrain train2 = line.deploy(MONDAY + 8 * HOUR + 10 * MINUTE);

		now = MONDAY + 9 * HOUR + 3 * MINUTE;
		train1.service.alignToTimetable(true);
		train2.service.alignToTimetable(true);

		assertEquals(MONDAY + 9 * HOUR, train1.service.getTripMillis());
		// The 09:00 service is taken, so the other train waits at the first stop for the 09:10 one
		assertEquals(MONDAY + 9 * HOUR + 10 * MINUTE, train2.service.getTripMillis());
		assertEquals(1, train2.movedTo);
	}

	@Test
	void aTrainWithNothingToRunGoesBackToTheDepot() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 23 * HOUR + 50 * MINUTE);

		now = MONDAY + OperatingDay.MILLIS_PER_DAY + 2 * HOUR;
		train.service.alignToTimetable(true);

		assertTrue(train.returned);
		assertEquals(-1, train.movedTo);
	}

	@Test
	void aTrainAtTheDepotIsNotMoved() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);

		now = MONDAY + 9 * HOUR + 3 * MINUTE;
		train.service.alignToTimetable(false);

		assertEquals(-1, train.movedTo);
		assertFalse(train.returned);
		assertEquals(MONDAY + 8 * HOUR, train.service.getTripMillis());
	}

	@Test
	void aTrainKeepsThePlanOfItsServiceWhenTheTimetableChanges() {
		final Line line = new Line();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		assertEquals(2 * MINUTE, train.service.getPlanAt(2).arrivalMillis);

		// Half as much time again between stops, from now on
		line.route.timetable.marginPercent = 50;
		line.route.timetable.revision++;
		assertEquals(2 * MINUTE, train.service.getPlanAt(2).arrivalMillis);

		// A train leaving after the change runs by the new plan at once
		final FakeTrain newTrain = line.deploy(MONDAY + 8 * HOUR + 10 * MINUTE);
		// The margin adds to the running time, not to the 10 seconds stopped at the first stop
		assertEquals(2 * MINUTE + (2 * MINUTE - 10 * SECOND) / 2, newTrain.service.getPlanAt(2).arrivalMillis);
		assertEquals(2 * MINUTE, train.service.getPlanAt(2).arrivalMillis);
	}

	@Test
	void aTrainTakesTheNewPlanAtItsNextService() {
		final Loop loop = new Loop(6);
		final FakeTrain train = loop.deploy(MONDAY + 6 * HOUR);
		final long oldArrival = train.service.getPlanAt(2).arrivalMillis;

		loop.depot.routes.get(0).timetable.marginPercent = 50;
		loop.depot.routes.get(0).timetable.revision++;
		assertEquals(oldArrival, train.service.getPlanAt(2).arrivalMillis);

		now = MONDAY + 6 * HOUR + 6 * MINUTE;
		train.service.onLapComplete();
		assertEquals(MONDAY + 6 * HOUR + 10 * MINUTE, train.service.getTripMillis());
		assertEquals(oldArrival + (oldArrival - 10 * SECOND) / 2, train.service.getPlanAt(2).arrivalMillis);
	}

	@Test
	void oneTrainOnALoopRunsEveryServiceOfTheDay() {
		final Loop loop = new Loop(6);
		final List<Long> taken = loop.runDay(Collections.singletonList(loop.deploy(MONDAY + 6 * HOUR)));

		assertEquals(loop.getServices(), taken);
	}

	@Test
	void twoTrainsOnALoopShareTheServicesOfTheDay() {
		final Loop loop = new Loop(16);
		final FakeTrain train1 = loop.deploy(MONDAY + 6 * HOUR);
		final FakeTrain train2 = loop.deploy(MONDAY + 6 * HOUR + 10 * MINUTE);
		final List<Long> taken = loop.runDay(Arrays.asList(train1, train2));

		final List<Long> sorted = new ArrayList<>(taken);
		Collections.sort(sorted);
		assertEquals(loop.getServices(), sorted);
		assertEquals(taken.size(), new HashSet<>(taken).size());
		assertTrue(train1.lastLap);
		assertTrue(train2.lastLap);
	}

	@Test
	void aTrainLeavesForTheLatestServiceItCanStillReach() {
		final Line line = new Line();
		final FakeTrain train = line.newTrain();

		now = MONDAY + 8 * HOUR + 30 * SECOND;
		assertEquals(MONDAY + 8 * HOUR, train.service.findDeployService(0));

		// Once the 08:00 service is taken, the 07:50 one is too long ago to run
		line.depot.reserveTrip(line.route.id, MONDAY + 8 * HOUR);
		assertEquals(0, train.service.findDeployService(0));

		// A train doesn't leave early for the 08:10 service, nor go back to one before the last service sent out
		now = MONDAY + 8 * HOUR + 5 * MINUTE;
		assertEquals(0, train.service.findDeployService(MONDAY + 8 * HOUR));
		now = MONDAY + 8 * HOUR + 9 * MINUTE + 50 * SECOND;
		assertEquals(MONDAY + 8 * HOUR + 10 * MINUTE, train.service.findDeployService(MONDAY + 8 * HOUR));
	}

	@Test
	void aTrainTurningBackTakesTheNextServiceTheOtherWay() {
		final TestRoute up = makeRoute(1, 0, 1, 2, 3);
		final TestRoute down = makeRoute(2, 5, 3, 2, 1);
		final Network network = new Network(Arrays.asList(up, down), makePath(7), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 2, 6), stop(5, 1, 8)), 0, 0);
		final FakeTrain train = network.deploy(MONDAY + 8 * HOUR);
		network.depot.reserveTrip(down.id, MONDAY + 8 * HOUR + 5 * MINUTE);

		now = MONDAY + 8 * HOUR + 4 * MINUTE;
		train.nextStoppingIndex = 3;
		train.service.onStopReached();

		// The 08:05 service the other way has a train already
		assertEquals(down.id, train.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 15 * MINUTE, train.service.getTripMillis());
		assertTrue(network.depot.isTripReserved(down.id, MONDAY + 8 * HOUR + 15 * MINUTE));
		assertEquals(MONDAY + 8 * HOUR + 17 * MINUTE, train.service.getPlannedArrivalMillis(4));
	}

	@Test
	void aServiceRunningThroughTakesItsOwnPathAndTheLinkedService() {
		final TestRoute line1 = makeRoute(1, 0, 1, 2, 3);
		final TestRoute line2 = makeRoute(2, 6, 3, 4);
		final int linkedService = (int) (8 * HOUR + 6 * MINUTE);
		line1.timetable.links.put((int) (8 * HOUR), new RouteTimetable.ServiceLink(line2.id, linkedService, -1));
		line2.timetable.addLinkedService(linkedService);
		final Line line = new Line(line1);
		line.addRoute(line2);
		final String key = TripTail.getKey(Collections.singletonList(line1.timetable.getLink(MONDAY + 8 * HOUR)));
		final List<TestTrack> tripRails = makePath(6);
		final TripTail<TestTrack, Object> tripTail = new TripTail<>(key, Arrays.asList(line1.id, line2.id), new HashMap<>(), 3, tripRails.subList(3, 6), null, TripTail.NO_RAIL, false, TripTail.NO_RAIL);
		final TripPath<TestTrack> tripPath = TripPath.of(tripTail, tripRails, 3, 0, StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 4, 8)));
		line.tripPaths.put(key, tripPath);

		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		assertSame(tripPath, train.followed);

		now = MONDAY + 8 * HOUR + 4 * MINUTE;
		train.nextStoppingIndex = 3;
		train.service.onStopReached();

		assertEquals(line2.id, train.service.getTripRouteId());
		assertEquals(MONDAY + linkedService, train.service.getTripMillis());
		assertEquals(MONDAY + 8 * HOUR + 10 * MINUTE, train.service.getPlannedArrivalMillis(4));

		// The next service has no link, so its train keeps to the usual path
		final FakeTrain other = line.deploy(MONDAY + 8 * HOUR + 10 * MINUTE);
		assertNull(other.followed);
		assertEquals(line1.id, other.service.getTripRouteId());
	}

	@Test
	void aTrainTurnsBackOnTheFirstFreeTurnBackRail() {
		final TurnBackLine line = new TurnBackLine();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		train.nextStoppingIndex = 3;

		assertFalse(train.service.chooseTurnBack());
		assertEquals(0, train.pathChanges);
		assertEquals(FIRST_TURN_BACK, train.reserved);
		assertEquals(5, train.reservedUntil);

		// Once taken, the turn back rail is kept for the train however long it still waits
		train.takenRails.addAll(FIRST_TURN_BACK);
		assertFalse(train.service.chooseTurnBack());
	}

	@Test
	void aTrainTakesTheNextTurnBackRailWhenTheFirstIsTaken() {
		final TurnBackLine line = new TurnBackLine();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		train.nextStoppingIndex = 3;
		train.takenRails.addAll(FIRST_TURN_BACK);

		// The train waits a moment after changing paths, so it leaves along the new one
		assertTrue(train.service.chooseTurnBack());
		assertSame(line.secondWay, train.followed);
		assertEquals(SECOND_TURN_BACK, train.reserved);
		assertEquals(6, train.reservedUntil);
		assertFalse(train.service.chooseTurnBack());

		// The longer way round puts the next route's stops one rail further on, and they still get the next service
		now = MONDAY + 8 * HOUR + 7 * MINUTE;
		train.nextStoppingIndex = 5;
		train.service.onStopReached();
		assertEquals(line.down.id, train.service.getTripRouteId());
		assertEquals(MONDAY + 8 * HOUR + 9 * MINUTE, train.service.getTripMillis());
		assertEquals(MONDAY + 8 * HOUR + 9 * MINUTE, train.service.getPlannedArrivalMillis(6));
	}

	@Test
	void aTrainWaitsWhileEveryTurnBackRailIsTaken() {
		final TurnBackLine line = new TurnBackLine();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		train.nextStoppingIndex = 3;
		train.takenRails.addAll(FIRST_TURN_BACK);
		train.takenRails.addAll(SECOND_TURN_BACK);

		assertTrue(train.service.chooseTurnBack());
		assertEquals(0, train.pathChanges);
		assertNull(train.reserved);

		train.takenRails.removeAll(SECOND_TURN_BACK);
		assertTrue(train.service.chooseTurnBack());
		assertSame(line.secondWay, train.followed);
	}

	@Test
	void aTrainOnTheOtherWayRoundGoesBackToTheUsualPathWhenItsTurnBackRailIsFree() {
		final TurnBackLine line = new TurnBackLine();
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);
		train.nextStoppingIndex = 3;
		train.takenRails.addAll(FIRST_TURN_BACK);
		train.service.chooseTurnBack();

		// Coming round to the same stop again, now with the first turn back rail free
		train.takenRails.clear();
		train.service.onStopReached();
		assertTrue(train.service.chooseTurnBack());
		assertEquals(2, train.pathChanges);
		assertNull(train.followed);
		assertEquals(3, train.nextStoppingIndex);
	}

	@Test
	void aTrainOnTheLineGoesBackToTheDepotAfterAServiceMarkedForIt() {
		final Line line = new Line();
		line.depot.keepsTrainsOnLine = true;
		line.route.timetable.returnServices.add((int) (8 * HOUR));
		final TripPath<TestTrack> wayBack = TripPath.ofDepotReturn(0, 3, makePath(6), line.stopTimes);
		line.tripPaths.put(wayBack.key, wayBack);
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);

		// Only at the last stop of the route
		train.nextStoppingIndex = 2;
		assertFalse(train.service.chooseDepotReturn());

		train.nextStoppingIndex = 3;
		assertTrue(train.service.chooseDepotReturn());
		assertSame(wayBack, train.followed);
		assertEquals(3, train.nextStoppingIndex);
		assertEquals(0, train.service.getNextTripMillis());
		assertFalse(train.service.chooseDepotReturn());
	}

	@Test
	void aTrainOnTheLineStaysOnItAfterAServiceNotMarked() {
		final Line line = new Line();
		line.depot.keepsTrainsOnLine = true;
		line.route.timetable.returnServices.add((int) (8 * HOUR));
		final TripPath<TestTrack> wayBack = TripPath.ofDepotReturn(0, 3, makePath(6), line.stopTimes);
		line.tripPaths.put(wayBack.key, wayBack);
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR + 10 * MINUTE);

		train.nextStoppingIndex = 3;
		assertFalse(train.service.chooseDepotReturn());
		assertNull(train.followed);
	}

	@Test
	void aDepotWhoseTrainsGoBackAnywayIgnoresTheMark() {
		final Line line = new Line();
		line.route.timetable.returnServices.add((int) (8 * HOUR));
		final TripPath<TestTrack> wayBack = TripPath.ofDepotReturn(0, 3, makePath(6), line.stopTimes);
		line.tripPaths.put(wayBack.key, wayBack);
		final FakeTrain train = line.deploy(MONDAY + 8 * HOUR);

		train.nextStoppingIndex = 3;
		assertFalse(train.service.chooseDepotReturn());
	}

	/**
	 * A depot and its siding, with the routes it runs.
	 */
	private class Network {

		final Map<Long, TestRoute> routes = new HashMap<>();
		final FakeDepot depot;
		final List<TestTrack> path;
		final StopTimes stopTimes;
		final Map<String, TripPath<TestTrack>> tripPaths = new HashMap<>();
		TurnBacks turnBacks = TurnBacks.NONE;
		final int repeatIndex1;
		final int repeatIndex2;

		Network(List<TestRoute> depotRoutes, List<TestTrack> path, StopTimes stopTimes, int repeatIndex1, int repeatIndex2) {
			depot = new FakeDepot(depotRoutes);
			depotRoutes.forEach(this::addRoute);
			this.path = path;
			this.stopTimes = stopTimes;
			this.repeatIndex1 = repeatIndex1;
			this.repeatIndex2 = repeatIndex2;
		}

		/**
		 * Adds a route of the network, which may be run from another depot.
		 */
		void addRoute(TestRoute route) {
			routes.put(route.id, route);
		}

		FakeTrain newTrain() {
			final FakeTrain train = new FakeTrain(path, repeatIndex1, repeatIndex2);
			train.service.setNetwork(depot, routes::get, null);
			train.service.setStopTimes(stopTimes, tripPaths, turnBacks);
			return train;
		}

		FakeTrain deploy(long tripMillis) {
			final FakeTrain train = newTrain();
			final long routeId = depot.routes.get(0).id;
			depot.reserveTrip(routeId, tripMillis);
			now = tripMillis;
			train.service.deploy(tripMillis, routeId);
			train.nextStoppingIndex = 1;
			return train;
		}
	}

	/**
	 * Three stops 2 minutes apart, with a service every 10 minutes from 06:00 to 23:50.
	 */
	private class Line extends Network {

		final TestRoute route;

		Line() {
			this(makeRoute(1, 0, 1, 2, 3));
		}

		Line(TestRoute route) {
			super(Collections.singletonList(route), makePath(5), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4)), 0, 0);
			this.route = route;
		}
	}

	/**
	 * Three stops one way and three the other, with two turn back rails between. The second way round has one rail more.
	 */
	private class TurnBackLine extends Network {


		final TestRoute down;
		final TripPath<TestTrack> secondWay;

		TurnBackLine() {
			this(makeRoute(1, 0, 1, 2, 3), makeRoute(2, 9, 4, 5, 6));
		}

		private TurnBackLine(TestRoute up, TestRoute down) {
			super(Arrays.asList(up, down), makePath(9), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(4, 0, 6), stop(5, 4, 8), stop(6, 5, 10), stop(7, 6, 12)), 0, 0);
			this.down = down;
			secondWay = TripPath.ofTurnBack(0, 1, new int[]{3}, makePath(10), StopTimes.of(stop(1, 1, 0), stop(2, 2, 2), stop(3, 3, 4), stop(5, 0, 7), stop(6, 4, 9), stop(7, 5, 11), stop(8, 6, 13)));
			tripPaths.put(secondWay.key, secondWay);
			turnBacks = TurnBacks.of(new int[]{3}, new TurnBackPoint(Arrays.asList(FIRST_TURN_BACK, SECOND_TURN_BACK), new int[]{2, 3}, 0));
		}
	}

	/**
	 * Three stops round a loop, with a service every 10 minutes from 06:00 to 23:50.
	 */
	private class Loop extends Network {

		/**
		 * @param lapMinutes the time from the first stop round to it again
		 */
		Loop(int lapMinutes) {
			super(Collections.singletonList(makeRoute(1, 0, 1, 2, 3, 1)), makePath(6), StopTimes.of(stop(1, 1, 0), stop(2, 2, lapMinutes / 3), stop(3, 3, lapMinutes * 2 / 3), stop(4, 1, lapMinutes)), 1, 4);
		}

		/**
		 * Brings each train round to the end of its lap when it is planned to get there, until every train has run its last lap.
		 *
		 * @return the services the trains ran, in the order they started them
		 */
		List<Long> runDay(List<FakeTrain> trains) {
			final List<Long> taken = new ArrayList<>();
			trains.forEach(train -> taken.add(train.service.getTripMillis()));
			final Set<FakeTrain> running = new HashSet<>(trains);
			while (!running.isEmpty()) {
				FakeTrain next = null;
				long nextMillis = Long.MAX_VALUE;
				for (final FakeTrain train : running) {
					final long lapEndMillis = train.service.getPlannedMillis(train.service.getPlanAt(1).arrivalMillis) + stopTimes.getDurationMillis();
					if (lapEndMillis < nextMillis) {
						next = train;
						nextMillis = lapEndMillis;
					}
				}
				now = nextMillis;
				if (next.lastLap) {
					running.remove(next);
				} else {
					next.service.onLapComplete();
					taken.add(next.service.getTripMillis());
				}
			}
			return taken;
		}

		List<Long> getServices() {
			final List<Long> services = new ArrayList<>();
			for (long millis = 6 * HOUR; millis < 24 * HOUR; millis += 10 * MINUTE) {
				services.add(MONDAY + millis);
			}
			return services;
		}
	}

	private static long[] stop(int rail, long platformId, int minutes) {
		final long arrival = (long) minutes * TICKS_PER_MINUTE;
		return new long[]{rail, platformId, arrival, arrival + 10 * SECOND / MILLIS_PER_TICK};
	}

	/**
	 * @param firstMinute the minute past each hour of the services, which run every 10 minutes from 06:00 until midnight
	 */
	private static TestRoute makeRoute(long id, int firstMinute, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 0;
		for (long millis = 6 * HOUR + firstMinute * MINUTE; millis < 24 * HOUR; millis += 10 * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}

	/**
	 * @return a path from a siding through platforms to another siding, with each platform the same as its rail
	 */
	private static List<TestTrack> makePath(int rails) {
		final List<TestTrack> path = new ArrayList<>();
		for (int i = 0; i < rails; i++) {
			final boolean isSiding = i == 0 || i == rails - 1;
			path.add(new TestTrack(i * 20, i * 20 + 20, isSiding, isSiding ? 0 : i, i == 0 ? 0 : 10, i));
		}
		return path;
	}

	private class FakeTrain implements TrainService.Vehicle<TestTrack> {

		final TrainService<TestTrack> service;
		final List<TestTrack> path;
		final int pathRepeatIndex1;
		final int pathRepeatIndex2;
		final Set<Integer> takenStops = new HashSet<>();
		int nextStoppingIndex;
		boolean lastLap;
		int movedTo = -1;
		boolean returned;
		TripPath<TestTrack> followed;
		int pathChanges;
		final Set<UUID> takenRails = new HashSet<>();
		List<UUID> reserved;
		int reservedUntil;

		FakeTrain(List<TestTrack> path, int pathRepeatIndex1, int pathRepeatIndex2) {
			this.path = path;
			this.pathRepeatIndex1 = pathRepeatIndex1;
			this.pathRepeatIndex2 = pathRepeatIndex2;
			service = new TrainService<>("train", this, () -> now);
		}

		@Override
		public List<TestTrack> getPath() {
			return path;
		}

		@Override
		public int getNextStoppingIndex() {
			return nextStoppingIndex;
		}

		@Override
		public boolean isRepeat() {
			return !lastLap && pathRepeatIndex1 > 0 && pathRepeatIndex2 > 0;
		}

		@Override
		public int getRepeatIndex2() {
			return lastLap ? 0 : pathRepeatIndex2;
		}

		@Override
		public int getPathRepeatIndex1() {
			return pathRepeatIndex1;
		}

		@Override
		public int getPathRepeatIndex2() {
			return pathRepeatIndex2;
		}

		@Override
		public void setLastLap(boolean isLastLap) {
			lastLap = isLastLap;
		}

		@Override
		public void followPath(TripPath<TestTrack> tripPath) {
			followed = tripPath;
		}

		@Override
		public void changePath(TripPath<TestTrack> tripPath, int newStopIndex) {
			followed = tripPath;
			nextStoppingIndex = newStopIndex;
			pathChanges++;
		}

		@Override
		public boolean isTurnBackFree(List<UUID> rails) {
			return rails.stream().noneMatch(takenRails::contains);
		}

		@Override
		public void reserveTurnBack(List<UUID> rails, int untilIndex) {
			reserved = rails;
			reservedUntil = untilIndex;
		}

		@Override
		public boolean isStopFree(int stopIndex) {
			return !takenStops.contains(stopIndex);
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

	/**
	 * Takes services like a depot, except that it never forgets one.
	 */
	private static class FakeDepot implements TrainService.Board {

		private final List<TestRoute> routes;
		private final Set<Long> reservedTrips = new HashSet<>();
		boolean keepsTrainsOnLine;

		private FakeDepot(List<TestRoute> routes) {
			this.routes = routes;
		}

		@Override
		public RouteTimetable getTimetable(String trainId) {
			return routes.get(0).timetable;
		}

		@Override
		public List<TestRoute> getTrainRoutes(String trainId) {
			return routes;
		}

		@Override
		public long findNextTrip(String trainId, long readyMillis) {
			return findNextService(routes.get(0), readyMillis);
		}

		@Override
		public boolean keepsTrainsOnLine() {
			return keepsTrainsOnLine;
		}

		@Override
		public long findNextService(PlanRoute planRoute, long readyMillis) {
			final TestRoute route = (TestRoute) planRoute;
			final long tripMillis = route.timetable.getNextService(readyMillis, readyMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> isTripReserved(route.id, millis));
			reserveTrip(route.id, tripMillis);
			return tripMillis;
		}

		@Override
		public void reserveTrip(long routeId, long tripMillis) {
			if (tripMillis != 0) {
				reservedTrips.add(routeId * 31 + tripMillis);
			}
		}

		@Override
		public boolean isTripReserved(long routeId, long tripMillis) {
			return reservedTrips.contains(routeId * 31 + tripMillis);
		}
	}
}
