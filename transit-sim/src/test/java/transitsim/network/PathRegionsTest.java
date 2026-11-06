package transitsim.network;

import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph.Kind;
import transitsim.network.RailRouter.Result;
import transitsim.network.RailRouter.Target;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PathRegionsTest {

	private static final int EAST = 0;
	private static final int WEST = 1;

	private static long pos(int x, int z) {
		return x * 100000L + z;
	}

	/**
	 * Both directions of a rail running east from the first node, like {@code RailRouterTest}.
	 */
	private static void add(RailGraph.Builder<String> builder, int x1, int z1, int x2, int z2, Kind kind, double speed) {
		final double cost = Math.hypot(x2 - x1, z2 - z1) / speed;
		builder.add(pos(x1, z1), x1, z1, EAST, pos(x2, z2), x2, 0, z2, WEST, kind, cost, x1 + "," + z1 + ">" + x2 + "," + z2);
		builder.add(pos(x2, z2), x2, z2, WEST, pos(x1, z1), x1, 0, z1, EAST, kind, cost, x2 + "," + z2 + ">" + x1 + "," + z1);
	}

	private static Target saved(int x1, int z1, int x2, int z2) {
		return Target.rail(pos(x1, z1), x1, z1, pos(x2, z2), x2, z2);
	}

	private static List<String> names(Result<String> result) {
		return result.steps.stream().map(step -> step.connection.rail).collect(Collectors.toList());
	}

	/**
	 * Line X runs from platform (0,0)-(10,0) to platform (100,0)-(110,0) on a slow direct rail. A fast detour leaves at
	 * (10,0), runs along line Y from (20,50) to (90,50), and comes back at (100,0).
	 */
	private static RailGraph.Builder<String> twoLines() {
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 10, 0, Kind.PLATFORM, 2);
		add(builder, 10, 0, 100, 0, Kind.NORMAL, 0.5);
		add(builder, 100, 0, 110, 0, Kind.PLATFORM, 2);
		add(builder, 10, 0, 20, 50, Kind.NORMAL, 8);
		add(builder, 20, 50, 90, 50, Kind.NORMAL, 8);
		add(builder, 90, 50, 100, 0, Kind.NORMAL, 8);
		return builder;
	}

	private static Boundaries yCutOff() {
		final Boundaries boundaries = new Boundaries();
		boundaries.nodes.add(pos(20, 50));
		boundaries.nodes.add(pos(90, 50));
		return boundaries;
	}

	@Test
	void withoutBoundariesTheRegionsAreTheConnectedParts() {
		final RailGraph.Builder<String> builder = twoLines();
		add(builder, 500, 500, 510, 500, Kind.PLATFORM, 2);
		final RailGraph<String> graph = builder.build();
		final PathRegions regions = PathRegions.compute(graph, new Boundaries());
		assertEquals(2, regions.count);
		assertTrue(regions.uncutNodes.isEmpty());

		// Searches don't change at all without boundaries
		graph.setRegions(new Boundaries());
		assertNull(graph.getRegions());
		assertTrue(names(RailRouter.route(graph, Arrays.asList(saved(0, 0, 10, 0), saved(100, 0, 110, 0)))).contains("20,50>90,50"));
	}

	@Test
	void boundaryNodesCutOffARegionAndBelongToBothSides() {
		final RailGraph<String> graph = twoLines().build();
		final PathRegions regions = PathRegions.compute(graph, yCutOff());
		assertEquals(2, regions.count);
		assertEquals(2, regions.nodeRegions(pos(20, 50)).length);
		assertEquals(1, regions.nodeRegions(pos(0, 0)).length);
		assertFalse(PathRegions.shares(regions.railRegions(graph, pos(20, 50), pos(90, 50)), regions.railRegions(graph, pos(0, 0), pos(10, 0))));
		assertTrue(regions.uncutNodes.isEmpty());
	}

	@Test
	void searchesStayInTheRegionOfBothEnds() {
		final RailGraph<String> graph = twoLines().build();
		graph.setRegions(yCutOff());
		final List<String> path = names(RailRouter.route(graph, Arrays.asList(saved(0, 0, 10, 0), saved(100, 0, 110, 0))));
		assertFalse(path.isEmpty());
		assertFalse(path.contains("20,50>90,50"));
		assertTrue(path.contains("10,0>100,0"));
	}

	@Test
	void withNoWayWithinTheRegionsTheSearchLooksBeyondThem() {
		// Line Y alone between the two platforms, cut off by boundaries: no way within the regions of the ends, but a way
		final RailGraph.Builder<String> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 10, 0, Kind.PLATFORM, 2);
		add(builder, 100, 0, 110, 0, Kind.PLATFORM, 2);
		add(builder, 10, 0, 20, 50, Kind.NORMAL, 8);
		add(builder, 20, 50, 90, 50, Kind.NORMAL, 8);
		add(builder, 90, 50, 100, 0, Kind.NORMAL, 8);
		final RailGraph<String> graph = builder.build();
		graph.setRegions(yCutOff());
		final Result<String> result = RailRouter.route(graph, Arrays.asList(saved(0, 0, 10, 0), saved(100, 0, 110, 0)));
		// Boundaries only narrow the search: a route with a way finds it (the user's decision)
		assertTrue(result.isSuccess());
		assertTrue(result.crossedRegions);
		assertTrue(names(result).contains("20,50>90,50"));
		// Found within the regions, it says so
		final RailGraph<String> withDirect = twoLines().build();
		withDirect.setRegions(yCutOff());
		assertFalse(RailRouter.route(withDirect, Arrays.asList(saved(0, 0, 10, 0), saved(100, 0, 110, 0))).crossedRegions);
	}

	@Test
	void searchesCanCrossIntoTheNextRegion() {
		final RailGraph<String> graph = twoLines().build();
		graph.setRegions(yCutOff());
		final Result<String> result = RailRouter.route(graph, Arrays.asList(saved(0, 0, 10, 0), saved(20, 50, 90, 50)));
		assertTrue(result.isSuccess());
		assertTrue(names(result).contains("10,0>20,50"));
	}

	@Test
	void boundaryRailsBelongToBothSides() {
		final RailGraph<String> graph = twoLines().build();
		final Boundaries boundaries = new Boundaries();
		// Cutting the slow rail and the detour leaves the two stations apart
		boundaries.segments.add(RailKeys.of(pos(10, 0), pos(100, 0)));
		boundaries.segments.add(RailKeys.of(pos(90, 50), pos(100, 0)));
		final PathRegions regions = PathRegions.compute(graph, boundaries);
		assertEquals(2, regions.count);
		assertEquals(2, regions.railRegions(graph, pos(10, 0), pos(100, 0)).length);
		assertTrue(regions.uncutSegments.isEmpty());
	}

	@Test
	void reportsBoundariesThatDoNotCutAnything() {
		final RailGraph<String> graph = twoLines().build();
		final Boundaries boundaries = new Boundaries();
		// The detour still joins both sides of the slow rail
		boundaries.segments.add(RailKeys.of(pos(10, 0), pos(100, 0)));
		boundaries.nodes.add(pos(5000, 0));
		final PathRegions regions = PathRegions.compute(graph, boundaries);
		assertEquals(1, regions.count);
		assertEquals(Collections.singletonList(RailKeys.of(pos(10, 0), pos(100, 0))), regions.uncutSegments);
		assertEquals(Collections.singletonList(pos(5000, 0)), regions.missingNodes);
	}
}
