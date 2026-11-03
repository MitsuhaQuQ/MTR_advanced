package transitsim.network;

import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph.Kind;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How trains run between platforms without turning back, by network (design document 6.12, block D).
 */
class PlatformGraphTest {

	private static final int EAST = 0;
	private static final int WEST = 1;

	private static long pos(int x, int z) {
		return x * 100000L + z;
	}

	/**
	 * Both directions of a rail running east from the first node, unless it is one way.
	 */
	private static void add(RailGraph.Builder<String> builder, int x1, int z1, int x2, int z2, Kind kind, boolean oneWay) {
		final double cost = Math.hypot(x2 - x1, z2 - z1);
		builder.add(pos(x1, z1), x1, z1, EAST, pos(x2, z2), x2, 0, z2, WEST, kind, cost, x1 + "," + z1 + ">" + x2 + "," + z2);
		if (!oneWay) {
			builder.add(pos(x2, z2), x2, z2, WEST, pos(x1, z1), x1, 0, z1, EAST, kind, cost, x2 + "," + z2 + ">" + x1 + "," + z1);
		}
	}

	private static void add(RailGraph.Builder<String> builder, int x1, int z1, int x2, int z2, Kind kind) {
		add(builder, x1, z1, x2, z2, kind, false);
	}

	private static RegionComputer.SavedRail platform(long id, int x1, int z1, int x2, int z2, long station) {
		return new RegionComputer.SavedRail(id, pos(x1, z1), pos(x2, z2), station, 0);
	}

	/**
	 * Platform 1 at station 10; station 20 with a main line platform 2 and a passing track platform 3 beside it; platform 5
	 * at station 30; and platform 4 of another line in station 20's area, with no rail joining it to the rest.
	 */
	private static RailGraph<String> stationWithPassingTrack(boolean passingTrackRejoins) {
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 10, 0, Kind.PLATFORM);
		add(builder, 10, 0, 80, 0, Kind.NORMAL);
		add(builder, 80, 0, 90, 0, Kind.NORMAL);
		add(builder, 90, 0, 100, 0, Kind.PLATFORM);
		add(builder, 100, 0, 110, 0, Kind.NORMAL);
		add(builder, 80, 0, 90, 10, Kind.NORMAL);
		add(builder, 90, 10, 100, 10, Kind.PLATFORM);
		if (passingTrackRejoins) {
			add(builder, 100, 10, 110, 0, Kind.NORMAL);
		}
		add(builder, 110, 0, 120, 0, Kind.NORMAL);
		add(builder, 120, 0, 130, 0, Kind.PLATFORM);
		add(builder, 90, 100, 100, 100, Kind.PLATFORM);
		return builder.build();
	}

	private static final List<RegionComputer.SavedRail> PLATFORMS = Arrays.asList(
			platform(1, 0, 0, 10, 0, 10),
			platform(2, 90, 0, 100, 0, 20),
			platform(3, 90, 10, 100, 10, 20),
			platform(4, 90, 100, 100, 100, 20),
			platform(5, 120, 0, 130, 0, 30)
	);

	private static PlatformGraph compute(RailGraph<String> graph) {
		return PlatformGraph.compute(graph, null, PLATFORMS);
	}

	/**
	 * @return the platforms a state leads to straight away
	 */
	private static Set<Long> next(PlatformGraph platformGraph, long platformId, int direction) {
		final Set<Long> platformIds = new TreeSet<>();
		for (final int target : platformGraph.getTargets(platformGraph.indexOf(platformId) * 2 + direction)) {
			platformIds.add(platformGraph.getPlatformId(target / 2));
		}
		return platformIds;
	}

	@Test
	void leadsToTheFirstPlatformsOnEachBranch() {
		final PlatformGraph platformGraph = compute(stationWithPassingTrack(true));
		// Eastwards from platform 1 the line splits for the two tracks of station 20, and both lead on to platform 5
		assertEquals(new TreeSet<>(Arrays.asList(2L, 3L)), next(platformGraph, 1, 0));
		assertEquals(Collections.singleton(5L), next(platformGraph, 2, 0));
		assertEquals(Collections.singleton(5L), next(platformGraph, 3, 0));
		// Westwards it is the other way round
		assertEquals(new TreeSet<>(Arrays.asList(2L, 3L)), next(platformGraph, 5, 1));
		assertEquals(Collections.singleton(1L), next(platformGraph, 2, 1));
	}

	@Test
	void hasNoWayOnWithoutTurningBack() {
		final PlatformGraph platformGraph = compute(stationWithPassingTrack(true));
		// From platform 1 westwards the line ends, and eastwards from platform 5 too
		assertTrue(next(platformGraph, 1, 1).isEmpty());
		assertTrue(next(platformGraph, 5, 0).isEmpty());
		// Platform 5 is only reached running east, so a train coming from it westwards can't get there without turning back
		final int platform5 = platformGraph.indexOf(5);
		for (final int target : platformGraph.getTargets(platformGraph.indexOf(2) * 2)) {
			assertEquals(platform5 * 2, target);
		}
	}

	@Test
	void endsAPassingTrackThatDoesNotRejoin() {
		final PlatformGraph platformGraph = compute(stationWithPassingTrack(false));
		assertTrue(next(platformGraph, 3, 0).isEmpty());
		assertEquals(Collections.singleton(5L), next(platformGraph, 2, 0));
		// It still works as a terminus
		assertTrue(platformGraph.canReach(1, 3));
		assertFalse(platformGraph.canReach(3, 5));
	}

	@Test
	void runsPastStationsAlongSeveralEdges() {
		final PlatformGraph platformGraph = compute(stationWithPassingTrack(true));
		// An express from platform 1 to platform 5 passes station 20 on one of its tracks
		assertTrue(platformGraph.canReach(1, 5));
		assertFalse(next(platformGraph, 1, 0).contains(5L));
	}

	@Test
	void keepsPlatformsWithNoRailJoiningThemApart() {
		final PlatformGraph platformGraph = compute(stationWithPassingTrack(true));
		// Platform 4 is in station 20's area but on rails of its own, so it is a network by itself
		assertEquals(2, platformGraph.getNetworkCount());
		assertEquals(platformGraph.getNetworkOf(1), platformGraph.getNetworkOf(3));
		assertNotEquals(platformGraph.getNetworkOf(2), platformGraph.getNetworkOf(4));
		assertFalse(platformGraph.canReach(2, 4));
		// Each network is numbered by its lowest node, so the same rails get the same numbers
		assertEquals(pos(0, 0), platformGraph.getNetworkOf(1));
		assertEquals(platformGraph.getNetworkOf(4), compute(stationWithPassingTrack(true)).getNetworkOf(4));
	}

	@Test
	void keepsWaysWithinThePathfindingRegions() {
		final RailGraph<String> graph = stationWithPassingTrack(true);
		final Boundaries boundaries = new Boundaries();
		// Cutting the line east of station 20 leaves platform 5 in a region of its own
		boundaries.nodes.add(pos(110, 0));
		final PlatformGraph platformGraph = PlatformGraph.compute(graph, PathRegions.compute(graph, boundaries), PLATFORMS);
		// A route may still cross straight over a boundary into the next region, as with pathfinding
		assertEquals(Collections.singleton(5L), next(platformGraph, 2, 0));
		// Boundaries cut a network, but don't make it two
		assertEquals(platformGraph.getNetworkOf(1), platformGraph.getNetworkOf(5));
	}

	@Test
	void leavesOneWayPlatformsUnusableTheOtherWay() {
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 10, 0, Kind.PLATFORM, true);
		add(builder, 10, 0, 20, 0, Kind.NORMAL, true);
		add(builder, 20, 0, 30, 0, Kind.PLATFORM, true);
		final PlatformGraph platformGraph = PlatformGraph.compute(builder.build(), null, Arrays.asList(platform(1, 0, 0, 10, 0, 10), platform(2, 20, 0, 30, 0, 20)));
		assertTrue(platformGraph.isUsable(0));
		assertFalse(platformGraph.isUsable(1));
		assertEquals(Collections.singleton(2L), next(platformGraph, 1, 0));
		assertFalse(platformGraph.canReach(2, 1));
	}

	@Test
	void givesTheSameGraphEachTime() {
		final PlatformGraph first = compute(stationWithPassingTrack(true));
		final PlatformGraph second = compute(stationWithPassingTrack(true));
		for (int state = 0; state < first.size() * 2; state++) {
			assertArrayEquals(first.getTargets(state), second.getTargets(state));
			assertArrayEquals(first.getCosts(state), second.getCosts(state), 1E-9);
		}
	}
}
