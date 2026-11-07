package mtr.data;

import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;
import transitsim.network.RailKeys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route's fastest times come from its own path, for the slowest vehicle running it (decoupling document 5.4, L1).
 */
class RoutePathsTest {

	private static final PathTiming.Vehicle FAST = new PathTiming.Vehicle(160, 8, 20, 0.01F, 0.01F, false, TransportMode.TRAIN);
	private static final PathTiming.Vehicle SLOW = new PathTiming.Vehicle(160, 8, 20, 0.002F, 0.002F, false, TransportMode.TRAIN);

	@Test
	void theSlowerTimeOfEachStopIsKept() {
		final long[][] slowest = RoutePaths.slowest(new long[][]{{0, 0}, {30, 50}, {90, 90}}, new long[][]{{0, 0}, {40, 45}, {-1, -1}});
		assertArrayEquals(new long[][]{{0, 0}, {40, 50}, {-1, -1}}, slowest);
	}

	@Test
	void theRouteIsTimedForItsSlowestVehicle() {
		final Route route = route();
		final RoutePaths.RoutePath routePath = new RoutePaths.RoutePath(route.id, "", path(), PathFailure.NONE);
		final long[][] fast = routePath.getFastestTimes(route, Collections.singleton(FAST));
		final long[][] slow = routePath.getFastestTimes(route, Collections.singleton(SLOW));
		final long[][] both = routePath.getFastestTimes(route, Arrays.asList(FAST, SLOW));

		assertEquals(3, both.length);
		assertTrue(slow[2][0] > fast[2][0], slow[2][0] + " " + fast[2][0]);
		assertArrayEquals(slow, both);
		assertArrayEquals(both, routePath.getFastestTimes(route, Arrays.asList(SLOW, FAST)));
	}

	@Test
	void theRouteStopsChangeItsTimes() {
		final Route route = route();
		final RoutePaths.RoutePath routePath = new RoutePaths.RoutePath(route.id, "", path(), PathFailure.NONE);
		final long[][] before = routePath.getFastestTimes(route, Collections.singleton(SLOW));
		route.platformIds.get(1).dwellOverride = 200;
		final long[][] after = routePath.getFastestTimes(route, Collections.singleton(SLOW));
		assertTrue(after[1][1] - after[1][0] > before[1][1] - before[1][0]);
	}

	@Test
	void aTimetableIsTimedForItsLeastPerformanceWhateverRunsIt() {
		final Route route = route();
		route.timetable.enabled = true;
		route.timetable.services.add(8 * 3600 * 1000);
		final Map<Long, java.util.Set<PathTiming.Vehicle>> vehicles = new HashMap<>();
		vehicles.put(route.id, new HashSet<>(Arrays.asList(FAST, SLOW)));
		// Set from the slowest vehicle running it the first time
		assertEquals(Collections.singletonList(route), RoutePaths.fillMinPerformance(Collections.singletonList(route), vehicles));
		assertEquals(SLOW.acceleration, route.timetable.minAcceleration);
		assertTrue(RoutePaths.fillMinPerformance(Collections.singletonList(route), vehicles).isEmpty());

		// Then the times are the same whatever runs it
		final RoutePaths.RoutePath routePath = new RoutePaths.RoutePath(route.id, "", path(), PathFailure.NONE);
		assertArrayEquals(routePath.getFastestTimes(route, Collections.singleton(SLOW)), routePath.getFastestTimes(route, Collections.singleton(FAST)));
		assertTrue(RoutePaths.getSlowerVehicles(route, vehicles).isEmpty());

		// A vehicle slower than it asks for is pointed out
		final PathTiming.Vehicle slower = new PathTiming.Vehicle(160, 8, 20, 0.001F, 0.001F, false, TransportMode.TRAIN);
		vehicles.get(route.id).add(slower);
		assertEquals(Collections.singletonList(slower), RoutePaths.getSlowerVehicles(route, vehicles));
	}

	@Test
	void aTrainTimedWithoutSpeedCurvesSetsOffFromStandingAfterAStop() {
		// The same run after a stop, reached once at full speed and once slowly: the time from the stop on is the same, as
		// the train sets off from standing either way (it used to keep the speed braking had left it at the stop)
		final long[] runs = new long[2];
		for (int i = 0; i < 2; i++) {
			final List<PathData> path = new ArrayList<>();
			path.add(rail(0, 200, RailType.PLATFORM, 1, 40, 1));
			path.add(rail(200, 800, i == 0 ? RailType.DIAMOND : RailType.STONE, 0, 0, 2));
			path.add(rail(800, 1000, RailType.PLATFORM, 2, 40, 2));
			path.add(rail(1000, 1600, RailType.DIAMOND, 0, 0, 3));
			path.add(rail(1600, 1800, RailType.PLATFORM, 3, 40, 3));
			runs[i] = PathTiming.calculateStopTimes(FAST, path).getRunMillis(2, 4);
		}
		assertTrue(runs[0] > 0);
		assertEquals(runs[0], runs[1]);
	}

	@Test
	void aRouteNoDepotRunsIsTimedWithTheDefaultVehicle() {
		final Route route = route();
		final PathTiming.Vehicle vehicle = RoutePaths.getDefaultVehicle(TransportMode.TRAIN);
		// 0.8 m/s², as the siding screen shows it
		assertEquals(0.8F, vehicle.acceleration * 400, 0.0001F);
		assertEquals(0.8F, vehicle.deceleration * 400, 0.0001F);
		assertEquals(8, vehicle.trainCars);
		assertEquals(Collections.singleton(vehicle), RoutePaths.getVehicles(Collections.emptyMap(), route));
	}

	@Test
	void otherRoutesKeepOffAConnectingLine() {
		// One straight track with platforms A, P, Q and C in a row; the connecting line runs from P to Q
		final World world = new World();
		final Platform a = world.platform(1, 0);
		world.connect(20, 100);
		final Platform p = world.platform(2, 100);
		world.connect(120, 200);
		final Platform q = world.platform(3, 200);
		world.connect(220, 300);
		final Platform c = world.platform(4, 300);
		final Route connectingLine = world.route(10, p, q);
		connectingLine.connectingLine = true;
		final Route toP = world.route(11, a, p);
		final Route through = world.route(12, a, c);
		world.refresh();

		assertTrue(world.get(connectingLine).isFound());
		assertTrue(world.get(toP).isFound());
		final RoutePaths.RoutePath throughPath = world.get(through);
		assertFalse(throughPath.isFound());
		assertEquals(PathFailure.Type.CONNECTING_LINE, throughPath.failure.type);
		assertEquals(connectingLine.id, throughPath.failure.pos);
		// It names the track in the way, between P and Q
		assertEquals(RailKeys.of(new BlockPos(0, 64, 120).asLong(), new BlockPos(0, 64, 200).asLong()), RailKeys.of(throughPath.failure.node1, throughPath.failure.node2));

		// Released, that rail can be run over by the others again
		connectingLine.connectingReleased.add(new long[]{new BlockPos(0, 64, 200).asLong(), new BlockPos(0, 64, 120).asLong()});
		world.refresh();
		assertEquals(null, world.routePaths.getFound(through, world.dataCache, world.rails));
		assertTrue(world.get(through).isFound());
		connectingLine.connectingReleased.clear();
		world.refresh();
		assertFalse(world.get(through).isFound());

		// Once it is an ordinary route again, the route runs over its rails
		connectingLine.connectingLine = false;
		world.refresh();
		assertEquals(null, world.routePaths.getFound(through, world.dataCache, world.rails));
		assertTrue(world.get(through).isFound());
		assertTrue(world.routePaths.getFound(through, world.dataCache, world.rails) != null);
	}

	@Test
	void aConnectingLineBranchingOffTheMainLineKeepsOnlyItsBranch() {
		// Main line A, P, C in a row; at X (z 200) a branch leaves for platform Q beside the line. The connecting line runs
		// from P along the main line to X and off along the branch to Q
		final World world = new World();
		final Platform a = world.platform(1, 0);
		world.connect(20, 100);
		final Platform p = world.platform(2, 100);
		world.connect(120, 200);
		world.connect(200, 300);
		final Platform c = world.platform(4, 300);
		final BlockPos fork = new BlockPos(0, 64, 200);
		final BlockPos branchEnd = new BlockPos(10, 64, 260);
		addRail(world.rails, fork, branchEnd, RailType.DIAMOND);
		final BlockPos qEnd = new BlockPos(10, 64, 280);
		addRail(world.rails, branchEnd, qEnd, RailType.PLATFORM);
		final Platform q = new Platform(3, TransportMode.TRAIN, branchEnd, qEnd);
		world.dataCache.platformIdMap.put(3L, q);
		final Route connectingLine = world.route(10, p, q);
		connectingLine.connectingLine = true;
		final Route through = world.route(12, a, c);
		final Route toQ = world.route(13, a, q);
		world.refresh();

		assertTrue(world.get(connectingLine).isFound());
		// The main line it starts along stays the others', the branch is its own
		assertTrue(world.get(through).isFound());
		final RoutePaths.RoutePath toQPath = world.get(toQ);
		assertFalse(toQPath.isFound());
		assertEquals(PathFailure.Type.CONNECTING_LINE, toQPath.failure.type);

		// Another fork before X, a siding off the main line at z 160: without key nodes the connecting line's own part
		// starts there, taking the main line from there to X
		world.rails.get(new BlockPos(0, 64, 120)).remove(fork);
		world.rails.get(fork).remove(new BlockPos(0, 64, 120));
		world.connect(120, 160);
		world.connect(160, 200);
		addRail(world.rails, new BlockPos(0, 64, 160), new BlockPos(6, 64, 190), RailType.DIAMOND);
		world.refresh();
		assertTrue(world.get(connectingLine).isFound());
		assertFalse(world.get(through).isFound());
		// A key node at X says where it leaves the main line
		connectingLine.platformIds.get(0).waypoints.add(fork);
		world.refresh();
		final RoutePaths.RoutePath withKey = world.get(connectingLine);
		assertTrue(withKey.isFound(), withKey.failure.type + " " + withKey.failure.pos + " " + withKey.failure.node1 + " " + withKey.failure.node2);
		assertTrue(world.get(through).isFound());
		assertFalse(world.get(toQ).isFound());
		// Key nodes at both ends of the branch, one rail apart: that rail alone is its own
		connectingLine.platformIds.get(0).waypoints.add(branchEnd);
		world.refresh();
		assertTrue(world.get(connectingLine).isFound());
		assertTrue(world.get(through).isFound());
		assertFalse(world.get(toQ).isFound());
	}

	@Test
	void aRouteKeepsToItsLinesTrackAndANewShortcutIsTaken() {
		// Line L from A round by a loop to C
		final World world = new World();
		final Platform a = world.platform(1, 0);
		world.connect(20, 100);
		addRail(world.rails, new BlockPos(0, 64, 100), new BlockPos(40, 64, 200), RailType.DIAMOND);
		addRail(world.rails, new BlockPos(40, 64, 200), new BlockPos(0, 64, 300), RailType.DIAMOND);
		final Platform c = world.platform(4, 300);
		final Route l = world.route(20, a, c);
		l.name = "L";
		world.refresh();
		final RoutePaths.RoutePath round = world.get(l);
		assertTrue(round.isFound());

		// A straight way from z 100 to z 300 laid later: the leg is found again and takes it
		world.connect(100, 300);
		world.refresh();
		assertEquals(null, world.routePaths.getFound(l, world.dataCache, world.rails));
		final RoutePaths.RoutePath straight = world.get(l);
		assertTrue(straight.isFound());
		assertFalse(Arrays.equals(straight.getNodes(), round.getNodes()), Arrays.toString(straight.getNodes()));

		// The straight way is line M's, with platforms on it and cut off by boundaries at both ends: line L keeps to its loop
		world.rails.get(new BlockPos(0, 64, 100)).remove(new BlockPos(0, 64, 300));
		world.rails.get(new BlockPos(0, 64, 300)).remove(new BlockPos(0, 64, 100));
		world.connect(100, 140);
		final Platform m1 = world.platform(5, 140);
		world.connect(160, 240);
		final Platform m2 = world.platform(6, 240);
		world.connect(260, 300);
		final Route m = world.route(21, m1, m2);
		m.name = "M";
		world.boundaries.nodes.add(new BlockPos(0, 64, 100).asLong());
		world.boundaries.nodes.add(new BlockPos(0, 64, 300).asLong());
		world.refresh();
		assertTrue(world.get(m).isFound());
		final RoutePaths.RoutePath kept = world.get(l);
		assertTrue(kept.isFound());
		assertArrayEquals(round.getNodes(), kept.getNodes());
		// Without its loop it can't borrow line M's track
		world.rails.get(new BlockPos(40, 64, 200)).remove(new BlockPos(0, 64, 300));
		world.rails.get(new BlockPos(0, 64, 300)).remove(new BlockPos(40, 64, 200));
		world.refresh();
		final RoutePaths.RoutePath blocked = world.get(l);
		assertFalse(blocked.isFound());
		assertEquals(PathFailure.Type.OTHER_LINE, blocked.failure.type);
	}

	/**
	 * Rails, data, the track sections and the route paths of a test.
	 */
	private static final class World {

		private final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		private final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		private final mtr.advanced.GraphRecords graphRecords = new mtr.advanced.GraphRecords();
		private final RoutePaths routePaths = new RoutePaths(graphRecords);
		private final mtr.advanced.PathBoundaries boundaries = new mtr.advanced.PathBoundaries();

		private Platform platform(long id, int startZ) {
			return RoutePathsTest.platform(dataCache, rails, id, startZ);
		}

		private void connect(int startZ, int endZ) {
			RoutePathsTest.connect(rails, startZ, endZ);
		}

		private Route route(long id, Platform... platforms) {
			return RoutePathsTest.route(dataCache, id, platforms);
		}

		/**
		 * The sections and their owners brought up to date, as the server does every few seconds.
		 */
		private void refresh() {
			final java.util.Set<BlockPos> keyNodes = new HashSet<>();
			dataCache.routeIdMap.values().forEach(route -> route.platformIds.forEach(routePlatform -> keyNodes.addAll(routePlatform.waypoints)));
			final Map<java.util.UUID, Long> savedRails = new HashMap<>();
			dataCache.platformIdMap.values().forEach(platform -> {
				final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
				savedRails.put(RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong()), platform.id);
			});
			graphRecords.update(rails, keyNodes, boundaries.nodes, boundaries.segments, RoutePaths.getReleasedRails(dataCache), savedRails, Collections.emptyMap(), 0);
			routePaths.updateOwners(dataCache, rails, boundaries, this::graph);
		}

		private RailGraph<Rail> graph() {
			return DeterministicPathFinder.compile(rails);
		}

		private RoutePaths.RoutePath get(Route route) {
			return routePaths.get(route, dataCache, rails, this::graph);
		}
	}

	private static Route route() {
		final Route route = new Route(1, TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		route.platformIds.add(new Route.RoutePlatform(3));
		return route;
	}

	/**
	 * As a route's path is found: standing at the first platform, then each next platform at its own dwell time.
	 */
	private static List<PathData> path() {
		final List<PathData> path = new ArrayList<>();
		path.add(rail(0, 40, RailType.PLATFORM, 1, 0, 1));
		path.add(rail(40, 440, RailType.DIAMOND, 0, 0, 2));
		path.add(rail(440, 480, RailType.PLATFORM, 2, 40, 2));
		path.add(rail(480, 880, RailType.DIAMOND, 0, 0, 3));
		path.add(rail(880, 920, RailType.PLATFORM, 3, 40, 3));
		return path;
	}

	private static Platform platform(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, long id, int startZ) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, startZ + 20);
		addRail(rails, start, end, RailType.PLATFORM);
		final Platform platform = new Platform(id, TransportMode.TRAIN, start, end);
		dataCache.platformIdMap.put(id, platform);
		return platform;
	}

	private static void connect(Map<BlockPos, Map<BlockPos, Rail>> rails, int startZ, int endZ) {
		addRail(rails, new BlockPos(0, 64, startZ), new BlockPos(0, 64, endZ), RailType.DIAMOND);
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, RailType railType) {
		rails.computeIfAbsent(start, pos -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, pos -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}

	private static Route route(DataCache dataCache, long id, Platform... platforms) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final Platform platform : platforms) {
			route.platformIds.add(new Route.RoutePlatform(platform.id));
		}
		dataCache.routeIdMap.put(id, route);
		return route;
	}

	private static PathData rail(int startZ, int endZ, RailType railType, long savedRailBaseId, int dwellTime, int stopIndex) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, stopIndex);
	}
}
