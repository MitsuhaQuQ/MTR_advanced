package mtr.data;

import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;
import transitsim.plan.DepotDuties;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Path generation finds where a duty can turn back early (D4): from a platform of a station on the way out round to the
 * platform of the same station the way back stops at, and not where that way is too long.
 */
class EarlyTurnLinksTest {

	private static final long DOWN = 10, UP = 11;
	private static final long SOUTH = 1, PLATFORM_A = 2, PLATFORM_B = 3, NORTH = 4;
	private static final int SERVICE = 8 * 3600 * 1000;

	/**
	 * The siding, the south platform, a station of two platforms side by side (A north, B south), the line on to the north
	 * terminal and a turn back rail beyond it. Down runs south, A, north; up north, B, south.
	 *
	 * @param northZ where the north terminal starts
	 */
	private static Map<String, List<PathData>> turnLinks(int northZ) {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		addRail(rails, pos(0, -60), pos(0, -40), RailType.SIDING);
		addRail(rails, pos(0, -40), pos(0, 0), RailType.IRON);
		final Platform south = platform(dataCache, rails, SOUTH, pos(0, 0), pos(0, 20));
		addRail(rails, pos(0, 20), pos(0, 200), RailType.IRON);
		addRail(rails, pos(0, 200), pos(-4, 240), RailType.IRON);
		addRail(rails, pos(0, 200), pos(4, 240), RailType.IRON);
		final Platform a = platform(dataCache, rails, PLATFORM_A, pos(-4, 240), pos(-4, 260));
		final Platform b = platform(dataCache, rails, PLATFORM_B, pos(4, 240), pos(4, 260));
		addRail(rails, pos(-4, 260), pos(0, 300), RailType.IRON);
		addRail(rails, pos(4, 260), pos(0, 300), RailType.IRON);
		addRail(rails, pos(0, 300), pos(0, northZ), RailType.IRON);
		final Platform north = platform(dataCache, rails, NORTH, pos(0, northZ), pos(0, northZ + 20));
		addRail(rails, pos(0, northZ + 20), pos(0, northZ + 220), RailType.TURN_BACK);
		final Station station = new Station(50);
		dataCache.platformIdToStation.put(PLATFORM_A, station);
		dataCache.platformIdToStation.put(PLATFORM_B, station);
		dataCache.platformIdToStation.put(SOUTH, new Station(51));
		dataCache.platformIdToStation.put(NORTH, new Station(52));

		route(dataCache, DOWN, south, a, north);
		route(dataCache, UP, north, b, south);
		final Siding siding = new Siding(100, TransportMode.TRAIN, pos(0, -60), pos(0, -40), 20);
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.corner1 = new Tuple<>(-50, -100);
		depot.corner2 = new Tuple<>(50, -20);
		depot.routeIds.add(DOWN);
		depot.routeIds.add(UP);
		dataCache.depotIdMap.put(depot.id, depot);

		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.segments.add(new DepotDuties.Segment(DOWN, SERVICE, 0, -1));
		day.segments.add(new DepotDuties.Segment(UP, SERVICE + 20 * 60000, 0, -1));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		duties.sidings.put(siding.id, Collections.singletonList(slot));

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		return depot.findTurnLinks(duties, dataCache, rails, graph, siding, false);
	}

	@Test
	void aStationOnTheWayOutCanTurnTrainsBackToItsOtherPlatform() {
		final Map<String, List<PathData>> links = turnLinks(320);
		assertEquals(Collections.singleton(PLATFORM_A + ">" + PLATFORM_B), links.keySet(), links.keySet().toString());
		final List<PathData> link = links.get(PLATFORM_A + ">" + PLATFORM_B);
		assertEquals(PLATFORM_A, link.get(0).savedRailBaseId);
		assertEquals(PLATFORM_B, link.get(link.size() - 1).savedRailBaseId);
	}

	@Test
	void aWayRoundTooLongIsntTaken() {
		assertTrue(turnLinks(1600).isEmpty());
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

	private static void route(DataCache dataCache, long id, Platform... platforms) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final Platform platform : platforms) {
			route.platformIds.add(new Route.RoutePlatform(platform.id));
		}
		dataCache.routeIdMap.put(id, route);
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, RailType railType) {
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}
}
