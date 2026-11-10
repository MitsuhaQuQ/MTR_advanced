package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.path.DeterministicPathFinder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.network.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RailNetworksTest {

	private static BlockPos pos(int x, int z) {
		return new BlockPos(x, 64, z);
	}

	/**
	 * Both directions of a straight rail along x.
	 */
	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, int x1, int x2, RailType railType) {
		final BlockPos start = pos(x1, 0);
		final BlockPos end = pos(x2, 0);
		addRail(rails, start, RailAngle.E, end, RailAngle.W, railType);
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, RailAngle facingStart, BlockPos end, RailAngle facingEnd, RailType railType) {
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, facingStart, end, facingEnd, railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, facingEnd, start, facingStart, railType, TransportMode.TRAIN));
	}

	private static RailRouter.Target target(BlockPos start, BlockPos end) {
		return RailRouter.Target.rail(start.asLong(), start.getX(), start.getZ(), end.asLong(), end.getX(), end.getZ());
	}

	private static RailRouter.Result<Rail> route(RailGraph<Rail> graph, BlockPos start1, BlockPos start2, BlockPos end1, BlockPos end2) {
		return RailRouter.route(graph, Arrays.asList(target(start1, start2), target(end1, end2)));
	}

	private static Network compute(Map<BlockPos, Map<BlockPos, Rail>> rails, Map<UUID, Long> savedRails) {
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		return RailNetworks.compress(graph, PathRegions.compute(graph, new PathBoundaries()), new PathBoundaries(), savedRails, 1);
	}

	@Test
	void joinsAChainOfRailsIntoOneSection() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, RailType.IRON);
		addRail(rails, 20, 40, RailType.IRON);
		addRail(rails, 40, 60, RailType.DIAMOND);
		final Network network = compute(rails, new HashMap<>());
		assertEquals(1, network.sections.size());
		final Network.Section section = network.sections.values().iterator().next();
		assertEquals(60, section.getLength(), 1E-6);
		// Lengths by speed limit, in order along the section
		assertEquals(2, section.getLimitCount());
		assertEquals(40, section.getLimitLength(0), 1E-6);
		assertEquals(RailType.IRON.maxBlocksPerTick, section.getLimit(0), 1E-6);
		assertEquals(20, section.getLimitLength(1), 1E-6);
		assertTrue(section.forwards && section.backwards);
	}

	@Test
	void keepsPlatformsAsSectionsOfTheirOwn() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, RailType.IRON);
		addRail(rails, 20, 40, RailType.PLATFORM);
		addRail(rails, 40, 60, RailType.IRON);
		final Map<UUID, Long> savedRails = new HashMap<>();
		savedRails.put(PathBoundaries.segmentKey(pos(20, 0).asLong(), pos(40, 0).asLong()), 7L);
		final Network network = compute(rails, savedRails);
		assertEquals(3, network.sections.size());
		assertEquals(1, network.sections.values().stream().filter(section -> section.savedRailId == 7 && section.type == SectionType.PLATFORM).count());
		// The platform keeps the limit of the rail before it, which depends on the way a train runs
		network.sections.values().stream().filter(section -> section.savedRailId == 7).forEach(section -> assertEquals(-1, section.getLimit(0), 1E-6));
		// The platform is a berth, and the whole line is one single track, as trains can't pass anywhere
		network.sections.values().stream().filter(section -> section.savedRailId == 7).forEach(section -> assertTrue(network.getResources(section.id).stream().anyMatch(resource -> resource.kind == Resource.Kind.BERTH)));
		assertEquals(1, network.resources.values().stream().filter(resource -> resource.kind == Resource.Kind.SINGLE_TRACK).count());
	}

	@Test
	void keepsTheSameIdsForTheSameNetwork() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, RailType.IRON);
		addRail(rails, 20, 40, RailType.PLATFORM);
		addRail(rails, 40, 60, RailType.IRON);
		assertEquals(compute(rails, new HashMap<>()).sections.keySet(), compute(rails, new HashMap<>()).sections.keySet());
		assertEquals(compute(rails, new HashMap<>()).getFingerprint(), compute(rails, new HashMap<>()).getFingerprint());
		addRail(rails, 60, 80, RailType.IRON);
		assertNotEquals(compute(new HashMap<>(), new HashMap<>()).getFingerprint(), compute(rails, new HashMap<>()).getFingerprint());
	}

	@Test
	void turnsAPathIntoSections() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, RailType.IRON);
		addRail(rails, 20, 40, RailType.IRON);
		addRail(rails, 40, 60, RailType.PLATFORM);
		final Map<UUID, Long> savedRails = new HashMap<>();
		savedRails.put(PathBoundaries.segmentKey(pos(40, 0).asLong(), pos(60, 0).asLong()), 7L);
		final Network network = compute(rails, savedRails);
		final List<long[]> moves = Arrays.asList(
				new long[]{pos(0, 0).asLong(), pos(20, 0).asLong(), 0, 0},
				new long[]{pos(20, 0).asLong(), pos(40, 0).asLong(), 0, 0},
				new long[]{pos(40, 0).asLong(), pos(60, 0).asLong(), 40, 7},
				new long[]{pos(60, 0).asLong(), pos(40, 0).asLong(), 0, 0}
		);
		final Network.SectionPath sectionPath = network.toSectionPath(moves);
		assertEquals(3, sectionPath.steps.size());
		assertEquals(sectionPath.steps.get(1)[0], sectionPath.steps.get(2)[0]);
		assertNotEquals(sectionPath.steps.get(1)[1], sectionPath.steps.get(2)[1]);
		assertEquals(Collections.singletonList(2), sectionPath.reversals);
		assertEquals(1, sectionPath.stops.size());
		assertEquals(7, sectionPath.stops.get(0)[1]);
		assertEquals(0, sectionPath.missingRails);
	}

	@Test
	void savesAndReadsSectionsAndPaths() throws Exception {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, RailType.IRON);
		addRail(rails, 20, 40, RailType.DIAMOND);
		final Network network = compute(rails, new HashMap<>());
		final Network.Section section = network.sections.values().iterator().next();
		final Network.Section read = Network.Section.fromBytes(section.id, section.toBytes());
		assertArrayEquals(section.getNodes(), read.getNodes());
		assertEquals(section.getLimitCount(), read.getLimitCount());
		assertEquals(section.getLength(), read.getLength(), 1E-9);
		assertEquals(section.type, read.type);

		final Network.SectionPath sectionPath = network.toSectionPath(Collections.singletonList(new long[]{pos(0, 0).asLong(), pos(20, 0).asLong(), 0, 0}));
		final Network.SectionPath readPath = Network.SectionPath.fromBytes(sectionPath.toBytes());
		assertEquals(sectionPath.steps.size(), readPath.steps.size());
		assertArrayEquals(sectionPath.steps.get(0), readPath.steps.get(0));
	}

	@Test
	void routesBetweenEveryPairOfArmsAtAYJunction() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final BlockPos junction = new BlockPos(0, 64, 0);
		final BlockPos westInner = new BlockPos(-20, 64, 0);
		final BlockPos westOuter = new BlockPos(-40, 64, 0);
		final BlockPos northEastInner = new BlockPos(20, 64, -20);
		final BlockPos northEastOuter = new BlockPos(40, 64, -40);
		final BlockPos southEastInner = new BlockPos(20, 64, 20);
		final BlockPos southEastOuter = new BlockPos(40, 64, 40);

		addRail(rails, westOuter, RailAngle.E, westInner, RailAngle.W, RailType.IRON);
		addRail(rails, westInner, RailAngle.E, junction, RailAngle.W, RailType.IRON);
		addRail(rails, junction, RailAngle.NE, northEastInner, RailAngle.SW, RailType.IRON);
		addRail(rails, northEastInner, RailAngle.NE, northEastOuter, RailAngle.SW, RailType.IRON);
		addRail(rails, junction, RailAngle.SE, southEastInner, RailAngle.NW, RailType.IRON);
		addRail(rails, southEastInner, RailAngle.SE, southEastOuter, RailAngle.NW, RailType.IRON);

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		assertEquals(3, graph.outgoing(junction.asLong()).size());
		assertTrue(route(graph, westOuter, westInner, northEastInner, northEastOuter).isSuccess());
		assertTrue(route(graph, westOuter, westInner, southEastInner, southEastOuter).isSuccess());
		assertTrue(route(graph, northEastOuter, northEastInner, southEastInner, southEastOuter).isSuccess());
	}

	@Test
	void stackedTracksAtTheSameXZStayInSeparateNetworks() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final BlockPos lowerWest = new BlockPos(-20, 64, 0);
		final BlockPos lowerCentre = new BlockPos(0, 64, 0);
		final BlockPos lowerEast = new BlockPos(20, 64, 0);
		final BlockPos upperNorth = new BlockPos(0, 80, -20);
		final BlockPos upperCentre = new BlockPos(0, 80, 0);
		final BlockPos upperSouth = new BlockPos(0, 80, 20);

		addRail(rails, lowerWest, RailAngle.E, lowerCentre, RailAngle.W, RailType.IRON);
		addRail(rails, lowerCentre, RailAngle.E, lowerEast, RailAngle.W, RailType.IRON);
		addRail(rails, upperNorth, RailAngle.S, upperCentre, RailAngle.N, RailType.IRON);
		addRail(rails, upperCentre, RailAngle.S, upperSouth, RailAngle.N, RailType.IRON);

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		assertNotEquals(lowerCentre.asLong(), upperCentre.asLong());
		assertFalse(graph.isConnected(lowerCentre.asLong(), upperCentre.asLong()));
		assertFalse(route(graph, lowerWest, lowerCentre, upperCentre, upperSouth).isSuccess());
	}

	@Test
	void anExplicitRampConnectsStackedLevels() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final BlockPos lowerWest = new BlockPos(-20, 64, 0);
		final BlockPos lowerEast = new BlockPos(20, 64, 0);
		final BlockPos upperSouth = new BlockPos(20, 80, 20);
		final BlockPos upperCentre = new BlockPos(0, 80, 0);
		final BlockPos upperNorth = new BlockPos(0, 80, -20);

		addRail(rails, lowerWest, RailAngle.E, lowerEast, RailAngle.W, RailType.IRON);
		addRail(rails, lowerEast, RailAngle.S, upperSouth, RailAngle.N, RailType.IRON);
		addRail(rails, upperSouth, RailAngle.NW, upperCentre, RailAngle.SE, RailType.IRON);
		addRail(rails, upperCentre, RailAngle.N, upperNorth, RailAngle.S, RailType.IRON);

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		assertTrue(graph.isConnected(lowerWest.asLong(), upperNorth.asLong()));
		final RailRouter.Result<Rail> result = route(graph, lowerWest, lowerEast, upperCentre, upperNorth);
		assertTrue(result.isSuccess());
		assertTrue(result.steps.stream().anyMatch(step -> step.connection.fromPos == lowerEast.asLong() && step.connection.toPos == upperSouth.asLong()));
	}
}
