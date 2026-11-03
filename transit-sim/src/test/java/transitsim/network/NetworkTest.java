package transitsim.network;

import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph.Kind;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network snapshot of a host-independent rail graph: compression, resources, versions and saving (design document
 * 5.11, contracts document 4.1 and 5.1).
 */
class NetworkTest {

	private static final int EAST = 0;
	private static final int WEST = 1;
	private static final double FAST = 1;
	private static final double SLOW = 0.5;

	private static long pos(int x, int z) {
		return x * 100000L + z;
	}

	private static final RailAttributes<TestRail> ATTRIBUTES = new RailAttributes<TestRail>() {
		@Override
		public SectionType getType(TestRail rail) {
			return rail.type;
		}

		@Override
		public boolean canAccelerate(TestRail rail) {
			return rail.type == SectionType.NORMAL;
		}

		@Override
		public double getSpeedLimit(TestRail rail) {
			return rail.limit;
		}

		@Override
		public double getLength(TestRail rail) {
			return rail.length;
		}
	};

	/**
	 * A rail from the first node to the second, both ways unless it is one way.
	 */
	private static void add(RailGraph.Builder<TestRail> builder, int x1, int z1, int x2, int z2, SectionType type, double limit, boolean oneWay) {
		final TestRail rail = new TestRail(type, limit, Math.hypot(x2 - x1, z2 - z1));
		final Kind kind = type == SectionType.PLATFORM ? Kind.PLATFORM : Kind.NORMAL;
		builder.add(pos(x1, z1), x1, z1, EAST, pos(x2, z2), x2, 0, z2, WEST, kind, rail.length, rail);
		if (!oneWay) {
			builder.add(pos(x2, z2), x2, z2, WEST, pos(x1, z1), x1, 0, z1, EAST, kind, rail.length, rail);
		}
	}

	private static void add(RailGraph.Builder<TestRail> builder, int x1, int z1, int x2, int z2, SectionType type) {
		add(builder, x1, z1, x2, z2, type, FAST, false);
	}

	private static Network build(RailGraph.Builder<TestRail> builder, long version) {
		return NetworkBuilder.build(builder.build(), null, new Boundaries(), new HashMap<>(), ATTRIBUTES, version);
	}

	private static List<Resource> resources(Network network, Resource.Kind kind) {
		return network.resources.values().stream().filter(resource -> resource.kind == kind).collect(Collectors.toList());
	}

	private static Network.Section sectionAt(Network network, int x, int z) {
		return network.sections.values().stream().filter(section -> Arrays.stream(section.getNodes()).anyMatch(node -> node == pos(x, z))).findFirst().orElse(null);
	}

	/**
	 * A single track line from (0,0) to (100,0) with a station of one platform at (40,0)-(50,0).
	 */
	private static RailGraph.Builder<TestRail> singleTrack() {
		final RailGraph.Builder<TestRail> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 20, 0, SectionType.NORMAL);
		add(builder, 20, 0, 40, 0, SectionType.NORMAL, SLOW, false);
		add(builder, 40, 0, 50, 0, SectionType.PLATFORM);
		add(builder, 50, 0, 100, 0, SectionType.NORMAL);
		return builder;
	}

	@Test
	void joinsAChainOfRailsIntoOneSectionWithItsLimits() {
		final RailGraph.Builder<TestRail> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 20, 0, SectionType.NORMAL);
		add(builder, 20, 0, 40, 0, SectionType.NORMAL);
		add(builder, 40, 0, 60, 0, SectionType.NORMAL, SLOW, false);
		final Network network = build(builder, 1);
		assertEquals(1, network.sections.size());
		final Network.Section section = network.sections.values().iterator().next();
		assertEquals(60, section.getLength(), 1E-9);
		assertEquals(2, section.getLimitCount());
		assertEquals(FAST, section.getLimit(0), 1E-9);
		assertEquals(40, section.getLimitLength(0), 1E-9);
		assertEquals(SLOW, section.getLimit(1), 1E-9);
		assertTrue(section.forwards && section.backwards);
	}

	@Test
	void makesASingleTrackOfALineWithNowhereToPass() {
		final Network network = build(singleTrack(), 1);
		// Two rails before the platform make one section, the platform and the rest one each
		assertEquals(3, network.sections.size());
		assertEquals(3, resources(network, Resource.Kind.TRACK).size());
		assertEquals(1, resources(network, Resource.Kind.BERTH).size());
		assertEquals(0, resources(network, Resource.Kind.TURNOUT).size());
		// A station with one platform is no place to pass, so the whole line is one single track
		final List<Resource> singleTracks = resources(network, Resource.Kind.SINGLE_TRACK);
		assertEquals(1, singleTracks.size());
		assertEquals(3, singleTracks.get(0).getSections().length);
		// The platform section uses its track, its berth and the single track
		final Network.Section platform = network.sections.values().stream().filter(section -> section.type == SectionType.PLATFORM).findFirst().orElse(null);
		assertNotNull(platform);
		assertEquals(3, network.getResources(platform.id).size());
	}

	@Test
	void splitsTheSingleTrackAtAPassingLoop() {
		final RailGraph.Builder<TestRail> builder = singleTrack();
		// A loop round the platform, from (40,0) by (45,10) to (50,0)
		add(builder, 40, 0, 45, 10, SectionType.NORMAL);
		add(builder, 45, 10, 50, 0, SectionType.NORMAL);
		final Network network = build(builder, 1);
		// Both ends of the loop are turnouts, each where three sections meet
		final List<Resource> turnouts = resources(network, Resource.Kind.TURNOUT);
		assertEquals(2, turnouts.size());
		turnouts.forEach(turnout -> assertEquals(3, turnout.getSections().length));
		// Trains can pass in the loop, so each stretch is a single track of its own
		final List<Resource> singleTracks = resources(network, Resource.Kind.SINGLE_TRACK);
		assertEquals(4, singleTracks.size());
		final Network.Section west = sectionAt(network, 0, 0);
		final Network.Section east = sectionAt(network, 100, 0);
		assertNotEquals(singleTrackOf(network, west), singleTrackOf(network, east));
	}

	private static Resource singleTrackOf(Network network, Network.Section section) {
		return network.getResources(section.id).stream().filter(resource -> resource.kind == Resource.Kind.SINGLE_TRACK).findFirst().orElse(null);
	}

	@Test
	void leavesOneWayRailsOutOfSingleTracks() {
		final RailGraph.Builder<TestRail> builder = new RailGraph.Builder<>();
		add(builder, 0, 0, 20, 0, SectionType.NORMAL, FAST, true);
		final Network network = build(builder, 1);
		assertEquals(1, network.sections.size());
		assertTrue(resources(network, Resource.Kind.SINGLE_TRACK).isEmpty());
	}

	@Test
	void keepsTheSameIdsAndPublishesVersions() {
		final Network first = build(singleTrack(), 1);
		final Network second = build(singleTrack(), 2);
		assertEquals(first.sections.keySet(), second.sections.keySet());
		assertEquals(first.resources.keySet(), second.resources.keySet());
		assertEquals(first.getFingerprint(), second.getFingerprint());
		assertEquals(1, first.version);
		assertEquals(2, second.version);
		// A snapshot never changes
		assertThrows(UnsupportedOperationException.class, () -> first.sections.clear());
		assertThrows(UnsupportedOperationException.class, () -> first.resources.clear());
		final Network.Section section = first.sections.values().iterator().next();
		section.getNodes()[0] = -1;
		assertNotEquals(-1, section.getNodes()[0]);
	}

	@Test
	void turnsAPathIntoSections() {
		final Network network = build(singleTrack(), 1);
		final List<long[]> moves = Arrays.asList(
				new long[]{pos(0, 0), pos(20, 0), 0, 0},
				new long[]{pos(20, 0), pos(40, 0), 0, 0},
				new long[]{pos(40, 0), pos(50, 0), 40, 7},
				new long[]{pos(50, 0), pos(40, 0), 0, 0}
		);
		final Network.SectionPath sectionPath = network.toSectionPath(moves);
		assertEquals(3, sectionPath.steps.size());
		assertEquals(Collections.singletonList(2), sectionPath.reversals);
		assertEquals(1, sectionPath.stops.size());
		assertEquals(0, sectionPath.missingRails);
	}

	@Test
	void readsSectionsSavedWithTheHostsRailTypes() throws Exception {
		final Network network = build(singleTrack(), 1);
		final Network.Section platform = network.sections.values().stream().filter(section -> section.type == SectionType.PLATFORM).findFirst().orElse(null);
		assertNotNull(platform);
		final Network.Section read = Network.Section.fromBytes(platform.id, platform.toBytes());
		assertArrayEquals(platform.getNodes(), read.getNodes());
		assertEquals(platform.getLength(), read.getLength(), 1E-9);
		assertEquals(SectionType.PLATFORM, read.type);

		// Before the network moved into the component, the type was the name of the host's rail type
		assertEquals(SectionType.PLATFORM, Network.Section.fromBytes(1, oldSection("PLATFORM")).type);
		assertEquals(SectionType.NORMAL, Network.Section.fromBytes(1, oldSection("IRON")).type);
		assertEquals(SectionType.TURN_BACK, Network.Section.fromBytes(1, oldSection("TURN_BACK")).type);
	}

	private static byte[] oldSection(String railType) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream output = new DataOutputStream(bytes);
		output.writeInt(2);
		output.writeLong(1);
		output.writeLong(2);
		output.writeInt(1);
		output.writeDouble(1);
		output.writeDouble(20);
		output.writeLong(0);
		output.writeUTF(railType);
		output.writeInt(0);
		output.writeBoolean(true);
		output.writeBoolean(true);
		return bytes.toByteArray();
	}

	private static final class TestRail {

		private final SectionType type;
		private final double limit;
		private final double length;

		private TestRail(SectionType type, double limit, double length) {
			this.type = type;
			this.limit = limit;
			this.length = length;
		}
	}
}
