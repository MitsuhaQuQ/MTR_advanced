package transitsim.sim;

import org.junit.jupiter.api.AfterEach;
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
 * Line 11 of the six-line test map (2026-11-15): one dead-end turn back rail T beside a station with an east platform E
 * and a west platform W, used both ways.
 * <ul>
 * <li>Train A (siding in the west) comes east into E, goes on into T, waits there and comes out to W, then west home.</li>
 * <li>Train B (siding in the east) comes west into W, turns on the platform, runs into T, turns again and comes out to E
 * the other way, then on west to P7 and home.</li>
 * </ul>
 * A standing at E for T has its way out over W, B standing at W for T has its way out over E: each holds what the other
 * needs. A train goes onto such a platform only together with the dead end after it and the dead end's way out.
 */
class DeadEndBothWaysTest {

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

	@AfterEach
	void restore() {
		TickRunner.DEAD_END_PLATFORMS = true;
	}

	@Test
	void trainsForOneDeadEndFromBothPlatformsDoNotDeadlock() {
		final List<String> deadlocks = new ArrayList<>();
		for (int in1 = 0; in1 < 10; in1++) {
			final Run run = new Run(0, 6, in1, (in1 + 4) % 10);
			run.runner.runUntil(MONDAY + 8 * HOUR);
			deadlocks.addAll(run.deadlocks);
			// Each train runs every 10 minutes from 06:00 to 08:00 and comes home 12 times; at minute 2 both are timed into T
			// at once, so one waits for the other and fewer come home by 08:00, but they keep running
			assertTrue(run.returns() >= 12, "B at minute " + in1 + ": only " + run.returns() + " returns");
		}
		assertEquals(Collections.emptyList(), deadlocks);
	}

	@Test
	void withoutTheRuleTheyDeadlock() {
		TickRunner.DEAD_END_PLATFORMS = false;
		int deadlocked = 0;
		for (int in1 = 0; in1 < 10; in1++) {
			final Run run = new Run(0, 6, in1, (in1 + 4) % 10);
			run.runner.runUntil(MONDAY + 8 * HOUR);
			if (!run.deadlocks.isEmpty()) {
				deadlocked++;
			}
		}
		assertTrue(deadlocked > 0, "no deadlock without the rule: the scenario no longer shows it");
	}

	private static final class Run {

		final TickRunner<ScenarioTrack> runner;
		final List<String> deadlocks = new ArrayList<>();

		int returns() {
			int count = 0;
			for (final MotionEvent event : runner.getEvents()) {
				if (event.type == MotionEvent.Type.RETURNED) {
					count++;
				}
			}
			return count;
		}

		Run(int inA, int outA, int inB, int outB) {
			final TestRoute routeInA = ScenarioLine.makeRoute(21, inA, 5, E);
			final TestRoute routeOutA = ScenarioLine.makeRoute(22, outA, W, 6);
			final TestRoute routeInB = ScenarioLine.makeRoute(11, inB, 8, W);
			final TestRoute routeOutB = ScenarioLine.makeRoute(12, outB, E, 7);
			final Map<Long, TestRoute> routes = new HashMap<>();
			for (final TestRoute route : Arrays.asList(routeInA, routeOutA, routeInB, routeOutB)) {
				routes.put(route.id, route);
			}
			runner = new TickRunner<>(MONDAY + 5 * HOUR + 50 * MINUTE, routes::get);

			final List<ScenarioTrack> pathA = trainAPath();
			final int stopE = 4;
			final int stopW = 9;
			final TickRunner.Siding<ScenarioTrack> sidingA = siding(pathA);
			final List<UUID> way = new ArrayList<>();
			for (int i = stopE + 1; i < stopW; i++) {
				way.add(pathA.get(i).getTrackKey());
			}
			sidingA.turnBacks = TurnBacks.of(new int[]{stopE}, new TurnBackPoint(Collections.singletonList(Collections.singletonList(T)), new int[]{stopW - stopE}, Collections.singletonList(way), 0));
			runner.addSiding(WEST_SIDING, new DepotBoard(Arrays.asList(routeInA, routeOutA)), sidingA);
			runner.addSiding(EAST_SIDING, new DepotBoard(Arrays.asList(routeInB, routeOutB)), siding(trainBPath()));
			runner.setAfterTick(() -> {
				for (final Set<Long> deadlock : runner.getReservations().takeDeadlocks()) {
					deadlocks.add(ScenarioLine.clock(runner.getMillis()) + " " + deadlock);
				}
			});
		}
	}

	private static final ScenarioTrack PLATFORM_E = ScenarioTrack.platform(10, 11, E, 20, true);
	private static final ScenarioTrack PLATFORM_W = ScenarioTrack.platform(21, 20, W, 20, true);
	// A turn is a stop of half a second on the game's paths ("turn, dwell 1")
	private static final ScenarioTrack TAIL = new ScenarioTrack(30, 31, 50, ScenarioTrack.Kind.PLAIN, 0, 1, 1, true);

	/**
	 * Train A: west siding, P5, E, into T, out to W, P6, home.
	 */
	static List<ScenarioTrack> trainAPath() {
		return Arrays.asList(
				ScenarioTrack.siding(70, 71, WEST_SIDING, SIDING_LENGTH),
				ScenarioTrack.plain(71, 72, 60, false),
				ScenarioTrack.platform(72, 73, 5, 20, false),
				ScenarioTrack.plain(73, 10, 200, false),
				PLATFORM_E,
				ScenarioTrack.plain(11, 30, 30, true),
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
	 * Train B: east siding, P8, W, turn on W, into T, turn, out over E the other way, P7, home.
	 */
	static List<ScenarioTrack> trainBPath() {
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
				ScenarioTrack.plain(30, 11, 30, true),
				PLATFORM_E.reversed(20),
				ScenarioTrack.plain(10, 90, 60, false),
				ScenarioTrack.platform(90, 91, 7, 20, false),
				ScenarioTrack.plain(91, 61, 400, false),
				ScenarioTrack.siding(60, 61, EAST_SIDING, SIDING_LENGTH).reversed(0)
		);
	}

	private static TickRunner.Siding<ScenarioTrack> siding(List<ScenarioTrack> path) {
		final TickRunner.Siding<ScenarioTrack> unTimed = new TickRunner.Siding<>(path, 0, 0, null, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 1);
		final StopTimes stopTimes = ScenarioLine.curveStopTimes(unTimed);
		return new TickRunner.Siding<>(path, 0, 0, stopTimes, "train", TRAIN_CARS, 10, SIDING_LENGTH, 0.01F, 0.02F, 1);
	}
}
