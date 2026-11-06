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
		// The turn back rail beyond the station, a dead end
		addRail(rails, pos(0, 300), pos(0, 340), RailType.TURN_BACK);

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
