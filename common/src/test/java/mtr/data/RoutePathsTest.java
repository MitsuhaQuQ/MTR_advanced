package mtr.data;

import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;

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
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		final Platform a = platform(dataCache, rails, 1, 0);
		connect(rails, 20, 100);
		final Platform p = platform(dataCache, rails, 2, 100);
		connect(rails, 120, 200);
		final Platform q = platform(dataCache, rails, 3, 200);
		connect(rails, 220, 300);
		final Platform c = platform(dataCache, rails, 4, 300);
		final Route connectingLine = route(dataCache, 10, p, q);
		connectingLine.connectingLine = true;
		final Route toP = route(dataCache, 11, a, p);
		final Route through = route(dataCache, 12, a, c);
		final RoutePaths routePaths = new RoutePaths();
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);

		assertTrue(routePaths.get(connectingLine, dataCache, rails, () -> graph).isFound());
		assertTrue(routePaths.get(toP, dataCache, rails, () -> graph).isFound());
		final RoutePaths.RoutePath throughPath = routePaths.get(through, dataCache, rails, () -> graph);
		assertFalse(throughPath.isFound());
		assertEquals(PathFailure.Type.CONNECTING_LINE, throughPath.failure.type);
		assertEquals(connectingLine.id, throughPath.failure.pos);

		// Once it is an ordinary route again, the route runs over its rails
		connectingLine.connectingLine = false;
		assertEquals(null, routePaths.getFound(through, dataCache, rails));
		assertTrue(routePaths.get(through, dataCache, rails, () -> graph).isFound());
		assertTrue(routePaths.getFound(through, dataCache, rails) != null);
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
