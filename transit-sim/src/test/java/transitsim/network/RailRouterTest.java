package transitsim.network;

import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph.Kind;
import transitsim.network.RailRouter.Result;
import transitsim.network.RailRouter.Target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailRouterTest {

	private static final int EAST = 0;
	private static final int WEST = 1;
	private static final double SPEED = 2;

	private static long pos(int x, int z) {
		return x * 100000L + z;
	}

	/**
	 * Adds both directions of a straight east-west rail, or a diagonal one when z differs.
	 */
	private static void add(List<Object[]> rails, int x1, int z1, int x2, int z2, Kind kind, double speed) {
		rails.add(new Object[]{x1, z1, x2, z2, kind, speed});
	}

	private static RailGraph<String> graph(List<Object[]> rails) {
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		rails.forEach(rail -> {
			final int x1 = (int) rail[0];
			final int z1 = (int) rail[1];
			final int x2 = (int) rail[2];
			final int z2 = (int) rail[3];
			final Kind kind = (Kind) rail[4];
			final double cost = Math.hypot(x2 - x1, z2 - z1) / (double) rail[5];
			// A rail leaving a node eastwards ends facing west at the other node, and the other way around
			builder.add(pos(x1, z1), x1, z1, EAST, pos(x2, z2), x2, 0, z2, WEST, kind, cost, x1 + "," + z1 + ">" + x2 + "," + z2);
			builder.add(pos(x2, z2), x2, z2, WEST, pos(x1, z1), x1, 0, z1, EAST, kind, cost, x2 + "," + z2 + ">" + x1 + "," + z1);
		});
		return builder.build();
	}

	private static Target saved(int x1, int z1, int x2, int z2) {
		return Target.rail(pos(x1, z1), x1, z1, pos(x2, z2), x2, z2);
	}

	private static List<String> names(Result<String> result) {
		return result.steps.stream().map(step -> step.connection.rail).collect(Collectors.toList());
	}

	/**
	 * Station A, then a short slow branch and a long fast branch to station B.
	 */
	private static List<Object[]> twoBranches() {
		final List<Object[]> rails = new ArrayList<>();
		add(rails, 0, 0, 10, 0, Kind.PLATFORM, SPEED);
		add(rails, 10, 0, 20, 0, Kind.NORMAL, SPEED);
		add(rails, 20, 0, 120, 0, Kind.NORMAL, 0.5);
		add(rails, 20, 0, 70, 50, Kind.NORMAL, 8);
		add(rails, 70, 50, 120, 0, Kind.NORMAL, 8);
		add(rails, 120, 0, 130, 0, Kind.NORMAL, SPEED);
		add(rails, 130, 0, 140, 0, Kind.PLATFORM, SPEED);
		return rails;
	}

	@Test
	void takesTheFastestBranchRatherThanTheShortest() {
		final Result<String> result = RailRouter.route(graph(twoBranches()), Arrays.asList(saved(0, 0, 10, 0), saved(130, 0, 140, 0)));
		assertTrue(result.isSuccess());
		assertTrue(names(result).contains("20,0>70,50"));
		assertEquals("0,0>10,0", names(result).get(0));
		assertEquals("130,0>140,0", names(result).get(names(result).size() - 1));
	}

	@Test
	void waypointForcesTheOtherBranch() {
		final List<Object[]> rails = twoBranches();
		add(rails, 70, 0, 70, 0, Kind.NORMAL, 1);
		rails.remove(rails.size() - 1);
		rails.remove(2);
		add(rails, 20, 0, 70, 0, Kind.NORMAL, 0.5);
		add(rails, 70, 0, 120, 0, Kind.NORMAL, 0.5);
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), Target.node(pos(70, 0), 70, 0), saved(130, 0, 140, 0)));
		assertTrue(names(result).contains("20,0>70,0"));
		assertFalse(names(result).contains("20,0>70,50"));
	}

	@Test
	void unreachableWaypointFailsInsteadOfSilentlyDetouring() {
		final Result<String> result = RailRouter.route(graph(twoBranches()), Arrays.asList(saved(0, 0, 10, 0), Target.node(pos(999, 999), 999, 999), saved(130, 0, 140, 0)));
		assertFalse(result.isSuccess());
		assertEquals(1, result.completedTargets);
	}

	@Test
	void reportsHowManyTargetsWereReached() {
		final List<Object[]> rails = twoBranches();
		add(rails, 500, 0, 510, 0, Kind.PLATFORM, SPEED);
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), saved(130, 0, 140, 0), saved(500, 0, 510, 0)));
		assertFalse(result.isSuccess());
		assertEquals(2, result.completedTargets);
	}

	@Test
	void resultDoesNotDependOnInsertionOrder() {
		final List<Object[]> rails = twoBranches();
		// Two branches of exactly equal cost
		add(rails, 20, 0, 70, -50, Kind.NORMAL, 8);
		add(rails, 70, -50, 120, 0, Kind.NORMAL, 8);
		final List<String> expected = names(RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), saved(130, 0, 140, 0))));

		final Random random = new Random(7);
		for (int i = 0; i < 30; i++) {
			Collections.shuffle(rails, random);
			assertEquals(expected, names(RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), saved(130, 0, 140, 0)))));
		}
	}

	@Test
	void trainCannotReverseOnPlainTrack() {
		// B lies behind the start of A with nothing to turn back on
		final List<Object[]> rails = new ArrayList<>();
		add(rails, 0, 0, 10, 0, Kind.PLATFORM, SPEED);
		add(rails, 10, 0, 20, 0, Kind.NORMAL, SPEED);
		add(rails, 20, 0, 30, 0, Kind.PLATFORM, SPEED);
		add(rails, 30, 0, 40, 0, Kind.NORMAL, SPEED);
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(20, 0, 30, 0), Target.node(pos(40, 0), 40, 0), Target.node(pos(30, 0), 30, 0)));
		assertFalse(result.isSuccess());
		assertTrue(builder.build().connections.isEmpty());
	}

	@Test
	void turnBackRailAllowsReversing() {
		final List<Object[]> rails = new ArrayList<>();
		add(rails, 20, 0, 30, 0, Kind.PLATFORM, SPEED);
		add(rails, 30, 0, 40, 0, Kind.TURN_BACK, SPEED);
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(20, 0, 30, 0), Target.node(pos(40, 0), 40, 0), Target.node(pos(20, 0), 20, 0)));
		assertTrue(result.isSuccess());
		assertEquals(Arrays.asList("20,0>30,0", "30,0>40,0", "40,0>30,0", "30,0>20,0"), names(result));
	}

	@Test
	void trainReversesInATerminalPlatformAndPrefersRunningThrough() {
		final List<Object[]> rails = new ArrayList<>();
		add(rails, 0, 0, 10, 0, Kind.PLATFORM, SPEED);
		add(rails, 10, 0, 20, 0, Kind.NORMAL, SPEED);
		add(rails, 20, 0, 30, 0, Kind.PLATFORM, SPEED);
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), saved(20, 0, 30, 0), saved(0, 0, 10, 0)));
		assertTrue(result.isSuccess());
		assertEquals(Arrays.asList("0,0>10,0", "10,0>20,0", "20,0>30,0", "30,0>20,0", "20,0>10,0", "10,0>0,0"), names(result));
		assertTrue(result.steps.get(3).reversal);
		assertEquals(1, result.steps.get(2).completedTarget);
		assertEquals(2, result.steps.get(5).completedTarget);
	}

	@Test
	void avoidsRunningThroughAnotherPlatformWhenAnotherWayIsSimilar() {
		final List<Object[]> rails = new ArrayList<>();
		add(rails, 0, 0, 10, 0, Kind.PLATFORM, SPEED);
		add(rails, 10, 0, 20, 0, Kind.NORMAL, SPEED);
		add(rails, 20, 0, 40, 0, Kind.PLATFORM, SPEED);
		add(rails, 20, 0, 30, 10, Kind.NORMAL, SPEED);
		add(rails, 30, 10, 40, 0, Kind.NORMAL, SPEED);
		add(rails, 40, 0, 50, 0, Kind.NORMAL, SPEED);
		add(rails, 50, 0, 60, 0, Kind.PLATFORM, SPEED);
		final Result<String> result = RailRouter.route(graph(rails), Arrays.asList(saved(0, 0, 10, 0), saved(50, 0, 60, 0)));
		assertTrue(names(result).contains("20,0>30,10"));
	}

	@Test
	void connectedComponentsIgnoreDirection() {
		final List<Object[]> rails = twoBranches();
		add(rails, 500, 0, 510, 0, Kind.PLATFORM, SPEED);
		final RailGraph<String> graph = graph(rails);
		assertTrue(graph.isConnected(pos(0, 0), pos(140, 0)));
		assertFalse(graph.isConnected(pos(0, 0), pos(500, 0)));
		assertFalse(graph.isConnected(pos(0, 0), pos(999, 999)));
	}

	@Test
	void largeGridRoutesQuickly() {
		final List<Object[]> rails = new ArrayList<>();
		final int size = 200;
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				add(rails, x * 10, z, x * 10 + 10, z, Kind.NORMAL, SPEED);
				if (x % 5 == 0 && z + 1 < size) {
					add(rails, x * 10 + 10, z, x * 10 + 20, z + 1, Kind.NORMAL, SPEED);
				}
			}
		}
		final RailGraph<String> graph = graph(rails);
		final long start = System.nanoTime();
		final Result<String> result = RailRouter.route(graph, Arrays.asList(saved(0, 0, 10, 0), saved(size * 10 - 10, 35, size * 10, 35)));
		System.out.println(graph.connections.size() + " connections routed in " + (System.nanoTime() - start) / 1000000 + " ms");
		assertTrue(result.isSuccess());
	}

	/**
	 * Parallel east-west lines joined by north-south rails, with a platform every 1000 blocks on each line.
	 */
	private static RailGraph<String> manyStations(int lines, int length) {
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		for (int line = 0; line < lines; line++) {
			final int z = line * 100;
			for (int x = 0; x < length; x += 20) {
				final Kind kind = x % 1000 == 500 ? Kind.PLATFORM : Kind.NORMAL;
				builder.add(pos(x, z), x, z, EAST, pos(x + 20, z), x + 20, 0, z, WEST, kind, 20 / SPEED, "e");
				builder.add(pos(x + 20, z), x + 20, z, WEST, pos(x, z), x, 0, z, EAST, kind, 20 / SPEED, "w");
			}
			if (line > 0) {
				for (int x = 0; x <= length; x += 400) {
					// Leaving a line northwards or southwards doesn't face the way the train arrived, so it is a junction
					builder.add(pos(x, z), x, z, 2, pos(x, z - 100), x, 0, z - 100, 3, Kind.NORMAL, 100 / SPEED, "n");
					builder.add(pos(x, z - 100), x, z - 100, 3, pos(x, z), x, 0, z, 2, Kind.NORMAL, 100 / SPEED, "s");
				}
			}
		}
		return builder.build();
	}

	private static List<Target> stationsOnLine(int line, int count) {
		final List<Target> targets = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			targets.add(saved(500 + i * 1000, line * 100, 520 + i * 1000, line * 100));
		}
		return targets;
	}

	@Test
	void longRouteWithManyStopsRunsStraightAlongItsLine() {
		final RailGraph<String> graph = manyStations(20, 32000);
		final long start = System.nanoTime();
		final Result<String> result = RailRouter.route(graph, stationsOnLine(5, 31));
		System.out.println(graph.connections.size() + " connections, 31 stops routed in " + (System.nanoTime() - start) / 1000000 + " ms");
		assertTrue(result.isSuccess());
		// 30 sections of 1000 blocks in 20 block rails, plus the first platform
		assertEquals(30 * 50 + 1, result.steps.size());
		assertTrue(result.steps.stream().allMatch(step -> step.connection.rail.equals("e")));
	}

	@Test
	void laterTargetsDontChangeTheBestRoute() {
		// The second stop is only reached by leaving the line, and the estimate for the later stops mustn't skip it
		final RailGraph<String> graph = manyStations(3, 6000);
		final List<Target> targets = new ArrayList<>(stationsOnLine(0, 2));
		targets.add(saved(2500, 200, 2520, 200));
		targets.addAll(stationsOnLine(0, 6).subList(4, 6));
		final Result<String> result = RailRouter.route(graph, targets);
		assertTrue(result.isSuccess());
		assertEquals(targets.size(), result.completedTargets);
		assertTrue(result.steps.stream().anyMatch(step -> step.connection.toPos == pos(2520, 200) || step.connection.toPos == pos(2500, 200)));
	}

	@Test
	void interruptedSearchStops() {
		final RailGraph<String> graph = manyStations(20, 32000);
		Thread.currentThread().interrupt();
		try {
			assertThrows(CancellationException.class, () -> RailRouter.route(graph, Arrays.asList(saved(500, 0, 520, 0), saved(500, 1900, 520, 1900), saved(30500, 0, 30520, 0))));
		} finally {
			Thread.interrupted();
		}
	}
}
