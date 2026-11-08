package mtr.data;

import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;
import transitsim.plan.DepotDuties;
import transitsim.plan.TripPath;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A terminal like the VH line's station 12: two platforms side by side between two turnouts, a turn back rail beyond
 * the far turnout, and no crossover. A train arriving at one platform and leaving from the other for the way back goes
 * round the turn back rail, stops at the other platform for its departure, and doesn't run away down the line first.
 */
class DutyTurnBackTest {

	private static final long DOWN = 10, UP = 11;
	private static final long SOUTH = 1, PLATFORM_A = 2, PLATFORM_B = 3;
	private static final int SERVICE = 8 * 3600 * 1000;

	@Test
	void theTrainTurnsBackBeyondTheStationAndStopsAtTheOtherPlatform() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());

		// The depot's siding, then the platform at the south end, then the line north to the station's turnout
		addRail(rails, pos(0, -60), pos(0, -40), RailType.SIDING);
		addRail(rails, pos(0, -40), pos(0, 0), RailType.IRON);
		final Platform south = platform(dataCache, rails, SOUTH, pos(0, 0), pos(0, 20));
		addRail(rails, pos(0, 20), pos(0, 200), RailType.IRON);
		// Platforms A and B side by side, joined at both ends
		addRail(rails, pos(0, 200), pos(-4, 240), RailType.IRON);
		addRail(rails, pos(0, 200), pos(4, 240), RailType.IRON);
		final Platform a = platform(dataCache, rails, PLATFORM_A, pos(-4, 240), pos(-4, 260));
		final Platform b = platform(dataCache, rails, PLATFORM_B, pos(4, 240), pos(4, 260));
		addRail(rails, pos(-4, 260), pos(0, 300), RailType.IRON);
		addRail(rails, pos(4, 260), pos(0, 300), RailType.IRON);
		// The turn back rail beyond the station, a dead end, long enough for a whole train
		addRail(rails, pos(0, 300), pos(0, 500), RailType.TURN_BACK);

		final Route down = route(dataCache, DOWN, south, a);
		final Route up = route(dataCache, UP, b, south);
		final Siding siding = new Siding(100, TransportMode.TRAIN, pos(0, -60), pos(0, -40), 20);
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.corner1 = new Tuple<>(-50, -100);
		depot.corner2 = new Tuple<>(50, -20);
		depot.routeIds.add(DOWN);
		depot.routeIds.add(UP);
		dataCache.depotIdMap.put(depot.id, depot);

		// One train: down to A, then up from B ten minutes later
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.segments.add(new DepotDuties.Segment(DOWN, SERVICE, 0, -1));
		day.segments.add(new DepotDuties.Segment(UP, SERVICE + 10 * 60000, 0, -1));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		duties.sidings.put(siding.id, Collections.singletonList(slot));

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		final List<String> failed = new ArrayList<>();
		final Map<Long, Map<String, TripPath<PathData>>> paths = depot.findDutyPaths(duties, dataCache, rails, graph, Collections.singleton(siding), false, failed::add);
		assertTrue(failed.isEmpty(), failed.toString());
		final Collection<TripPath<PathData>> sidingPaths = paths.getOrDefault(siding.id, Collections.emptyMap()).values();
		assertEquals(1, sidingPaths.size(), "one duty, one path");
		final List<PathData> path = sidingPaths.iterator().next().path;

		final int atA = indexOf(path, PLATFORM_A);
		final int atB = indexOf(path, PLATFORM_B);
		assertTrue(atA >= 0 && atB > atA, "A, then B: " + atA + " " + atB);
		// Between them: round the turn back rail, and nowhere south of the station's turnout
		boolean turnedBack = false;
		for (int i = atA; i < atB; i++) {
			turnedBack |= path.get(i).rail.railType == RailType.TURN_BACK;
			assertTrue(path.get(i).startingPos.getZ() >= 200 && path.get(i).getLastPos().getZ() >= 200, "kept to the station: " + path.get(i).startingPos + " → " + path.get(i).getLastPos());
		}
		assertTrue(turnedBack, "turned back on the turn back rail");
		// It stops at B for its departure, rather than running straight through
		assertTrue(path.get(atB).dwellTime > 0, "stops at B");

		// The planner is told how long A to B takes the way the duty runs it: the legs in to the turn back rail and back out,
		// over 600 blocks, without standing there to change ends, which the plan adds as the network's least time (round 4)
		final Station station = new Station(50);
		dataCache.platformIdToStation.put(PLATFORM_A, station);
		dataCache.platformIdToStation.put(PLATFORM_B, station);
		final List<Route> routes = Arrays.asList(down, up);
		final Map<Long, Map<Long, Long>> turnTimes = depot.measureTurnTimes(routes, dataCache, rails, graph, false);
		final Long aToB = turnTimes.getOrDefault(PLATFORM_A, Collections.emptyMap()).get(PLATFORM_B);
		assertNotNull(aToB, "A to B measured: " + turnTimes);
		assertTrue(aToB > 30000 && aToB < 270000, "the run round the turn back rail: " + aToB);
		// Round the turn back rail and back to A, where a train may wait: its in leg kept apart, shorter than the round
		final Map<Long, Long> fromA = turnTimes.getOrDefault(PLATFORM_A, Collections.emptyMap());
		if (fromA.containsKey(PLATFORM_A)) {
			final Long in = fromA.get(transitsim.planning.DutyGenerator.HOLD_IN_KEY);
			assertNotNull(in, "the in leg is measured: " + fromA);
			assertTrue(in > 0 && in < fromA.get(PLATFORM_A), "in " + in + " of " + fromA.get(PLATFORM_A));
		}

		// Without the turn back rail there's no way from A to B, and the planner is told so
		rails.get(pos(0, 300)).remove(pos(0, 500));
		rails.remove(pos(0, 500));
		final Map<Long, Map<Long, Long>> noTurnBack = depot.measureTurnTimes(routes, dataCache, rails, DeterministicPathFinder.compile(rails), false);
		assertNull(noTurnBack.getOrDefault(PLATFORM_A, Collections.emptyMap()).get(PLATFORM_B), "no way from A to B: " + noTurnBack);
	}

	private static int indexOf(List<PathData> path, long platformId) {
		for (int i = 0; i < path.size(); i++) {
			if (path.get(i).savedRailBaseId == platformId) {
				return i;
			}
		}
		return -1;
	}

	private static BlockPos pos(int x, int z) {
		return new BlockPos(x, 64, z);
	}

	private static Platform platform(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, long id, BlockPos start, BlockPos end) {
		addRail(rails, start, end, RailType.PLATFORM);
		final Platform platform = new Platform(id, TransportMode.TRAIN, start, end);
		dataCache.platformIdMap.put(id, platform);
		return platform;
	}

	private static Route route(DataCache dataCache, long id, Platform... platforms) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final Platform platform : platforms) {
			route.platformIds.add(new Route.RoutePlatform(platform.id));
		}
		dataCache.routeIdMap.put(id, route);
		return route;
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, RailType railType) {
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}
}
