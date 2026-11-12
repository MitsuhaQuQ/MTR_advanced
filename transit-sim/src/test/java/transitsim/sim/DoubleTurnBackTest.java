package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.OperatingDay;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 站后双折返: a terminus with two turn back rails behind it, side by side, each reached from the arrival line and leading
 * to the departure line over the throat's crossovers. Consecutive trains take the two rails in turn, never two on one
 * rail, never two in the throat against each other, and leave on the services they are planned to.
 * <p>
 * Three stations up (platforms 1, 2, 3), the turn back rails A (nodes 21-22) and B (23-24) behind platform 3, three
 * stations down (4, 5, 6) back to the siding. Services every 3 minutes each way.
 */
class DoubleTurnBackTest {

	private static final long SECOND = 1000;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final long SIDING = 100;
	private static final int HEADWAY_MINUTES = 3;
	private static final int TRAIN_CARS = 4;
	private static final float SIDING_LENGTH = TRAIN_CARS * 10 + 10;
	private static final int TERMINUS_STOP = 6;
	private static final int DEPARTURE_STOP = 11;
	private static final UUID TAIL_A = key(21, 22);
	private static final UUID TAIL_B = key(23, 24);
	private static final long FIRST_UP = 6 * HOUR;
	private static final long LAST_UP = 7 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void consecutiveTrainsTakeTheTwoTurnBackRailsInTurn() {
		// Down services leave platform 4 two minutes after the up ones leave platform 1: a train is through the turn back
		// and gone before the next one comes in, so taking the first free rail would take rail A every time
		final Run run = new Run(2);
		run.runUntil(MONDAY + 8 * HOUR);
		run.assertSafeAndOnTime();
		assertFalse(run.bothTaken, "with this short a turn, one train is through before the next comes");
		run.assertAlternating();
	}

	@Test
	void trainsWaitOnBothTurnBackRailsAtOnceWhenTheTurnIsLong() {
		// Down services leave seven minutes after: each train keeps its rail until it leaves platform 4, so the next train
		// is on the other rail meanwhile, and a third waits at platform 3 for one to come free
		final Run run = new Run(7);
		run.runUntil(MONDAY + 8 * HOUR);
		run.assertSafeAndOnTime();
		assertTrue(run.bothTaken, "the two rails were never in use together");
		run.assertAlternating();
	}

	private static final class Run {

		final TestRoute up = route(1, 0, 1, 2, 3);
		final TestRoute down;
		final TickRunner<ScenarioTrack> runner;
		final RunInvariants<ScenarioTrack> invariants;
		/**
		 * Which rail each turn back took, in order, as {@code hh:mm:ss.t train rail}.
		 */
		final List<String> tails = new ArrayList<>();
		final Map<Long, Long> departures = new TreeMap<>();
		final List<String> sharedTail = new ArrayList<>();
		boolean bothTaken;
		private final Map<Long, UUID> lastTail = new HashMap<>();

		Run(int downMinutes) {
			down = route(2, downMinutes, 4, 5, 6);
			final List<ScenarioTrack> wayA = path(21, 22);
			final List<ScenarioTrack> wayB = path(23, 24);
			final Map<Long, TestRoute> routes = new HashMap<>();
			routes.put(up.id, up);
			routes.put(down.id, down);
			runner = new TickRunner<>(MONDAY + 5 * HOUR + 50 * MINUTE, routes::get);
			final TickRunner.Siding<ScenarioTrack> unTimedA = new TickRunner.Siding<>(wayA, 0, 0, null, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 30);
			final TickRunner.Siding<ScenarioTrack> unTimedB = new TickRunner.Siding<>(wayB, 0, 0, null, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 30);
			final TickRunner.Siding<ScenarioTrack> siding = new TickRunner.Siding<>(wayA, 0, 0, ScenarioLine.curveStopTimes(unTimedA), "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 30);
			final StopTimes stopTimesB = ScenarioLine.curveStopTimes(unTimedB);
			final TripPath<ScenarioTrack> pathB = TripPath.ofTurnBack(0, 1, new int[]{TERMINUS_STOP}, wayB, stopTimesB);
			((transitsim.plan.PathDistances) pathB.distances).reset(wayB);
			siding.tripPaths.put(pathB.key, pathB);
			final int length = DEPARTURE_STOP - TERMINUS_STOP;
			final List<List<UUID>> tailRails = Arrays.asList(Collections.singletonList(TAIL_A), Collections.singletonList(TAIL_B));
			siding.turnBacks = TurnBacks.of(new int[]{TERMINUS_STOP}, new TurnBackPoint(tailRails, new int[]{length, length}, Arrays.asList(wayRails(wayA), wayRails(wayB)), 0));
			runner.addSiding(SIDING, new DepotBoard(Arrays.asList(up, down)), siding);
			invariants = new RunInvariants<>(runner);
			runner.setAfterTick(() -> {
				invariants.check();
				watch();
			});
		}

		void assertSafeAndOnTime() {
			assertEquals(Collections.emptyList(), invariants.getViolations());
			// Never two trains on or holding one rail
			assertEquals(Collections.emptyList(), sharedTail);
			// Every down service left platform 4 on time: never before the plan, and not more than a few seconds after
			final int services = (int) ((LAST_UP - FIRST_UP) / (HEADWAY_MINUTES * MINUTE));
			assertEquals(services, departures.size(), log.toString());
			departures.forEach((train, millis) -> {
				final long planned = plannedDeparture(millis);
				assertTrue(millis >= planned - 1000 && millis <= planned + 15 * SECOND, "left platform 4 at " + ScenarioLine.clock(millis) + ", planned " + ScenarioLine.clock(planned));
			});
		}

		/**
		 * Every up service turned back, in the order the trains came in on A, B, A, B, ...
		 */
		void assertAlternating() {
			final int services = (int) ((LAST_UP - FIRST_UP) / (HEADWAY_MINUTES * MINUTE));
			assertEquals(services, tails.size(), log.toString());
			for (int i = 0; i < tails.size(); i++) {
				assertEquals(i % 2 == 0 ? "A" : "B", tails.get(i), "turn back " + i + " of " + log);
			}
		}

		void runUntil(long millis) {
			runner.runUntil(millis);
			for (final MotionEvent event : runner.getEvents()) {
				if (event.type == MotionEvent.Type.DEPARTED && event.rail == DEPARTURE_STOP) {
					departures.putIfAbsent(event.trainId * 1000 + departures.size(), event.millis);
				}
			}
		}

		/**
		 * @return the planned departure of the down service nearest this time
		 */
		long plannedDeparture(long millis) {
			final long dayStart = Math.floorDiv(millis, OperatingDay.MILLIS_PER_DAY) * OperatingDay.MILLIS_PER_DAY;
			long best = 0;
			for (final int service : down.timetable.services) {
				final long planned = dayStart + service;
				if (best == 0 || Math.abs(planned - millis) < Math.abs(best - millis)) {
					best = planned;
				}
			}
			return best;
		}

		final List<String> log = new ArrayList<>();

		private void watch() {
			for (final TickRunner<ScenarioTrack>.HeadlessTrain train : runner.getTrains()) {
				if (!train.getTurnBackRails().contains(TAIL_A) && !train.getTurnBackRails().contains(TAIL_B)) {
					lastTail.remove(train.id);
				}
			}
			int taken = 0;
			for (final UUID tail : Arrays.asList(TAIL_A, TAIL_B)) {
				final Set<Long> trains = new HashSet<>();
				final long occupant = runner.getOccupant(tail);
				if (occupant != 0) {
					trains.add(occupant);
				}
				for (final TickRunner<ScenarioTrack>.HeadlessTrain train : runner.getTrains()) {
					if (train.getTurnBackRails().contains(tail)) {
						trains.add(train.id);
						if (!tail.equals(lastTail.put(train.id, tail))) {
							tails.add(tail.equals(TAIL_A) ? "A" : "B");
							log.add(ScenarioLine.clock(runner.getMillis()) + " train " + train.id + " " + (tail.equals(TAIL_A) ? "A" : "B"));
						}
					}
				}
				if (trains.size() > 1) {
					sharedTail.add(ScenarioLine.clock(runner.getMillis()) + " " + trains);
				}
				if (occupant != 0) {
					taken++;
				}
			}
			if (taken == 2) {
				bothTaken = true;
			}
		}
	}

	/**
	 * The siding's path with one of the turn back rails: up to platform 3, along the throat onto the rail, back off it to
	 * platform 4 and down to the siding.
	 */
	private static List<ScenarioTrack> path(long tailStart, long tailEnd) {
		return Arrays.asList(
				ScenarioTrack.siding(0, 1, SIDING, SIDING_LENGTH),
				ScenarioTrack.plain(1, 2, 60, false),
				ScenarioTrack.platform(2, 3, 1, 20, false),
				ScenarioTrack.plain(3, 4, 300, false),
				ScenarioTrack.platform(4, 5, 2, 20, false),
				ScenarioTrack.plain(5, 6, 300, false),
				ScenarioTrack.platform(6, 7, 3, 20, false),
				ScenarioTrack.plain(7, tailStart, 30, false),
				ScenarioTrack.plain(tailStart, tailEnd, 60, true),
				ScenarioTrack.plain(tailEnd, tailStart, 60, true),
				ScenarioTrack.plain(tailStart, 31, 30, false),
				ScenarioTrack.platform(31, 32, 4, 20, false),
				ScenarioTrack.plain(32, 33, 300, false),
				ScenarioTrack.platform(33, 34, 5, 20, false),
				ScenarioTrack.plain(34, 35, 300, false),
				ScenarioTrack.platform(35, 36, 6, 20, false),
				ScenarioTrack.plain(36, 1, 60, false),
				ScenarioTrack.siding(0, 1, SIDING, SIDING_LENGTH).reversed(0)
		);
	}

	/**
	 * @return every rail between platform 3 and platform 4
	 */
	private static List<UUID> wayRails(List<ScenarioTrack> path) {
		final List<UUID> rails = new ArrayList<>();
		for (int i = TERMINUS_STOP + 1; i < DEPARTURE_STOP; i++) {
			rails.add(path.get(i).getTrackKey());
		}
		return rails;
	}

	private static UUID key(long a, long b) {
		return new UUID(Math.min(a, b), Math.max(a, b));
	}

	private static TestRoute route(long id, int firstMinute, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 5;
		for (long millis = FIRST_UP + firstMinute * MINUTE; millis < LAST_UP + firstMinute * MINUTE; millis += HEADWAY_MINUTES * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}
}
