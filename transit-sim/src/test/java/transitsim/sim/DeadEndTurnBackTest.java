package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.OperatingDay;
import transitsim.plan.StopTimes;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Item A of MTR-待验证.md (line 8 of the six-line test map, 2026-11-14): two trains swap ends through one dead-end turn
 * back rail T beside a station with an east platform E and a west platform W.
 * <ul>
 * <li>Train 2 (siding in the west) comes east into E, goes on into T, waits there for its planned time and comes out to
 * W, then west home.</li>
 * <li>Train 1 (siding in the east) comes west into W, stops, turns on the platform, runs east into T, turns again and goes
 * east home.</li>
 * </ul>
 * When train 1 stops at W while train 2 waits in T, each needs what the other holds. A train going into a rail it can only
 * leave by turning back must be sure of its way out before it goes in.
 */
class DeadEndTurnBackTest {

	private static final long SECOND = 1000;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final long EAST_SIDING = 100;
	private static final long WEST_SIDING = 200;
	private static final int TRAIN_CARS = 4;
	private static final float SIDING_LENGTH = TRAIN_CARS * 10 + 10;
	private static final long E = 1;
	private static final long W = 2;
	private static final UUID T = new UUID(30, 31);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void twoTrainsSwapEndsThroughOneDeadEndTurnBack() {
		// Train 2 leaves E at :00 and W at :06, so it waits in T from about :01 to :05; train 1 comes into W at about :02
		final Run run = new Run(0, 6, 1, 4);
		run.runner.runUntil(MONDAY + 8 * HOUR);
		final List<String> returns = new ArrayList<>();
		for (final MotionEvent event : run.runner.getEvents()) {
			if (event.type == MotionEvent.Type.RETURNED) {
				returns.add(event.trainId + " " + ScenarioLine.clock(event.millis));
			}
		}
		assertEquals(Collections.emptyList(), run.deadlocks);
		// Each train runs every 10 minutes from 06:00 to 08:00, so each comes home 12 times
		assertTrue(returns.size() >= 22, "only " + returns.size() + " returns: " + returns);
	}

	@Test
	void withoutTheRuleTheyDeadlock() {
		// The scenario is the one of line 8: without the way out asked for with the dead end, the two trains lock each other
		TickRunner.DEAD_END_EXITS = false;
		try {
			final Run run = new Run(0, 6, 1, 4);
			run.runner.runUntil(MONDAY + 8 * HOUR);
			assertFalse(run.deadlocks.isEmpty(), "no deadlock without the rule: the scenario no longer shows it");
		} finally {
			TickRunner.DEAD_END_EXITS = true;
		}
	}

	private static final class Run {

		final TickRunner<ScenarioTrack> runner;
		final List<String> deadlocks = new ArrayList<>();

		/**
		 * @param in2  minute of train 2's services east to E
		 * @param out2 minute of train 2's services from W west
		 * @param in1  minute of train 1's services west to W
		 * @param out1 minute of train 1's services from W east
		 */
		Run(int in2, int out2, int in1, int out1) {
			final TestRoute routeIn2 = ScenarioLine.makeRoute(21, in2, 5, E);
			final TestRoute routeOut2 = ScenarioLine.makeRoute(22, out2, W, 6);
			final TestRoute routeIn1 = ScenarioLine.makeRoute(11, in1, 8, W);
			final TestRoute routeOut1 = ScenarioLine.makeRoute(12, out1, W, 9);
			final Map<Long, TestRoute> routes = new HashMap<>();
			for (final TestRoute route : Arrays.asList(routeIn2, routeOut2, routeIn1, routeOut1)) {
				routes.put(route.id, route);
			}
			runner = new TickRunner<>(MONDAY + 5 * HOUR + 50 * MINUTE, routes::get);

			final List<ScenarioTrack> path2 = trainTwoPath();
			final int stopE = 4;
			final int stopW = 9;
			final TickRunner.Siding<ScenarioTrack> siding2 = siding(path2);
			final List<UUID> way = new ArrayList<>();
			for (int i = stopE + 1; i < stopW; i++) {
				way.add(path2.get(i).getTrackKey());
			}
			siding2.turnBacks = TurnBacks.of(new int[]{stopE}, new TurnBackPoint(Collections.singletonList(Collections.singletonList(T)), new int[]{stopW - stopE}, Collections.singletonList(way), 0));
			runner.addSiding(WEST_SIDING, new DepotBoard(Arrays.asList(routeIn2, routeOut2)), siding2);

			final List<ScenarioTrack> path1 = trainOnePath();
			runner.addSiding(EAST_SIDING, new DepotBoard(Arrays.asList(routeIn1, routeOut1)), siding(path1));
			runner.setAfterTick(() -> {
				for (final Set<Long> deadlock : runner.getReservations().takeDeadlocks()) {
					final StringBuilder where = new StringBuilder();
					for (final TickRunner<ScenarioTrack>.HeadlessTrain train : runner.getTrains()) {
						where.append(" train ").append(train.id).append(" on path rail ").append(train.getHeadRail());
					}
					deadlocks.add(ScenarioLine.clock(runner.getMillis()) + " " + deadlock + where);
				}
			});
		}
	}

	private static final ScenarioTrack PLATFORM_W = ScenarioTrack.platform(21, 20, W, 20, true);
	// A turn is a stop of half a second on the game's paths ("turn, dwell 1")
	private static final ScenarioTrack TAIL = new ScenarioTrack(30, 31, 50, ScenarioTrack.Kind.PLAIN, 0, 1, 1, true);

	/**
	 * Train 2: west siding, P5, E, into T, out to W, P6, home.
	 */
	static List<ScenarioTrack> trainTwoPath() {
		return Arrays.asList(
				ScenarioTrack.siding(70, 71, WEST_SIDING, SIDING_LENGTH),
				ScenarioTrack.plain(71, 72, 60, false),
				ScenarioTrack.platform(72, 73, 5, 20, false),
				ScenarioTrack.plain(73, 10, 200, false),
				ScenarioTrack.platform(10, 11, E, 20, false),
				ScenarioTrack.plain(11, 30, 30, false),
				TAIL,
				TAIL.reversed(0),
				ScenarioTrack.plain(30, 21, 30, true),
				PLATFORM_W,
				ScenarioTrack.plain(20, 74, 200, false),
				ScenarioTrack.platform(74, 75, 6, 20, false),
				ScenarioTrack.plain(75, 71, 60, false),
				ScenarioTrack.siding(70, 71, WEST_SIDING, SIDING_LENGTH).reversed(0)
		);
	}

	/**
	 * Train 1: east siding, P8, W, turn on W, into T, turn, P9, home.
	 */
	static List<ScenarioTrack> trainOnePath() {
		return Arrays.asList(
				ScenarioTrack.siding(60, 61, EAST_SIDING, SIDING_LENGTH),
				ScenarioTrack.plain(61, 80, 60, false),
				ScenarioTrack.platform(80, 81, 8, 20, false),
				ScenarioTrack.plain(81, 21, 200, false),
				PLATFORM_W,
				PLATFORM_W.reversed(0),
				ScenarioTrack.plain(21, 30, 30, true),
				TAIL,
				TAIL.reversed(0),
				ScenarioTrack.plain(30, 82, 30, false),
				ScenarioTrack.platform(82, 83, 9, 20, false),
				ScenarioTrack.plain(83, 61, 200, false),
				ScenarioTrack.siding(60, 61, EAST_SIDING, SIDING_LENGTH).reversed(0)
		);
	}

	private static TickRunner.Siding<ScenarioTrack> siding(List<ScenarioTrack> path) {
		final TickRunner.Siding<ScenarioTrack> unTimed = new TickRunner.Siding<>(path, 0, 0, null, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 1);
		final StopTimes stopTimes = ScenarioLine.curveStopTimes(unTimed);
		return new TickRunner.Siding<>(path, 0, 0, stopTimes, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 1);
	}
}
