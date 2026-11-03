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
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.E, end, RailAngle.W, railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.W, start, RailAngle.E, railType, TransportMode.TRAIN));
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
}
