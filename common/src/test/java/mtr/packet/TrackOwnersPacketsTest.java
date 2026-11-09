package mtr.packet;

import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import transitsim.network.RailKeys;
import transitsim.network.SectionType;
import transitsim.network.TrackOwnership;
import transitsim.network.TrackSections;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owners of the track sections as the server sends them to players for the editor's map (web layers step 2): every
 * rail of a section with owners, or set by hand, gets them; the rest none; a large network goes in parts, read back whole.
 */
class TrackOwnersPacketsTest {

	@Test
	void theOwnersOfEachRailComeAsTheServerWorkedThemOut() {
		// A line of track: a platform of line X at the start, a boundary rail, then track with no platform
		final TrackSections.Rail platform = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail approach = rail(20, 0, 40, 0, SectionType.NORMAL, 0);
		final TrackSections.Rail boundary = rail(40, 0, 60, 0, SectionType.NORMAL, 0);
		final TrackSections.Rail beyond = rail(60, 0, 100, 0, SectionType.NORMAL, 0);
		final TrackSections.Rail end = rail(100, 0, 140, 0, SectionType.NORMAL, 0);
		final Map<UUID, TrackSections.Rail> rails = new HashMap<>();
		for (final TrackSections.Rail rail : new TrackSections.Rail[]{platform, approach, boundary, beyond, end}) {
			rails.put(rail.getKey(), rail);
		}
		final Map<Long, Integer> marks = new HashMap<>();
		marks.put(boundary.node1, TrackSections.MARK_BOUNDARY_RAIL);
		marks.put(boundary.node2, TrackSections.MARK_BOUNDARY_RAIL);
		final TrackSections sections = new TrackSections();
		sections.sync(rails, marks);
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final Map<Long, List<String>> platformLines = new HashMap<>();
		platformLines.put(1L, Collections.singletonList("TRAIN|X"));
		// The track beyond the boundary given to line Y by hand
		final Map<UUID, List<String>> overrides = new HashMap<>();
		overrides.put(beyond.getKey(), Collections.singletonList("TRAIN|Y"));
		final Map<Long, Set<String>> owners = TrackOwnership.compute(snapshot, Collections.emptySet(), Collections.singleton(boundary.getKey()), platformLines, Collections.emptyMap(), overrides);
		final Set<Long> manual = TrackOwnership.getOverrides(snapshot, overrides).keySet();

		receive(TrackOwnersPackets.write(snapshot, owners, manual, 7, 1 << 20));
		final Map<UUID, TrackOwnersPackets.Owners> received = TrackOwnersPackets.railOwners;
		assertEquals(Collections.singletonList("TRAIN|X"), names(received.get(platform.getKey())));
		assertEquals(Collections.singletonList("TRAIN|X"), names(received.get(approach.getKey())));
		assertFalse(received.get(approach.getKey()).manual);
		assertEquals(Collections.singletonList("TRAIN|Y"), names(received.get(beyond.getKey())));
		assertTrue(received.get(beyond.getKey()).manual);
		// The rest of the section beyond the boundary goes with it
		assertSame(received.get(beyond.getKey()), received.get(end.getKey()));
		// The boundary rail belongs to the regions on both sides
		assertNotNull(received.get(boundary.getKey()));

		// Opened to every line by hand: sent with no owners, as set by hand
		overrides.put(beyond.getKey(), Collections.emptyList());
		final Map<Long, Set<String>> open = TrackOwnership.compute(snapshot, Collections.emptySet(), Collections.singleton(boundary.getKey()), platformLines, Collections.emptyMap(), overrides);
		final int version = TrackOwnersPackets.version;
		receive(TrackOwnersPackets.write(snapshot, open, TrackOwnership.getOverrides(snapshot, overrides).keySet(), 8, 1 << 20));
		assertEquals(version + 1, TrackOwnersPackets.version);
		assertEquals(0, TrackOwnersPackets.railOwners.get(beyond.getKey()).owners.length);
		assertTrue(TrackOwnersPackets.railOwners.get(beyond.getKey()).manual);
	}

	@Test
	void aLargeNetworkComesInPartsAndIsReadWhole() {
		// Two hundred sections, each a platform of its own line, cut apart by boundary rails
		final Map<UUID, TrackSections.Rail> rails = new HashMap<>();
		final Map<Long, Integer> marks = new HashMap<>();
		final Map<Long, List<String>> platformLines = new HashMap<>();
		final List<TrackSections.Rail> platforms = new ArrayList<>();
		final Set<UUID> boundaries = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			final TrackSections.Rail platform = rail(i * 100, 0, i * 100 + 40, 0, SectionType.PLATFORM, i + 1);
			final TrackSections.Rail boundary = rail(i * 100 + 40, 0, i * 100 + 100, 0, SectionType.NORMAL, 0);
			rails.put(platform.getKey(), platform);
			rails.put(boundary.getKey(), boundary);
			boundaries.add(boundary.getKey());
			marks.put(boundary.node1, TrackSections.MARK_BOUNDARY_RAIL);
			marks.put(boundary.node2, TrackSections.MARK_BOUNDARY_RAIL);
			platformLines.put(i + 1L, Collections.singletonList("TRAIN|L" + i));
			platforms.add(platform);
		}
		final TrackSections sections = new TrackSections();
		sections.sync(rails, marks);
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final Map<Long, Set<String>> owners = TrackOwnership.compute(snapshot, Collections.emptySet(), boundaries, platformLines, Collections.emptyMap());
		final List<FriendlyByteBuf> packets = TrackOwnersPackets.write(snapshot, owners, Collections.emptySet(), 9, 200);
		assertTrue(packets.size() > 5, packets.size() + " parts");

		// A part missed at the start: nothing changes until a whole set has come
		final Map<UUID, TrackOwnersPackets.Owners> before = TrackOwnersPackets.railOwners;
		final int version = TrackOwnersPackets.version;
		receive(packets.subList(1, packets.size()));
		assertSame(before, TrackOwnersPackets.railOwners);
		assertEquals(version, TrackOwnersPackets.version);

		receive(packets);
		for (int i = 0; i < 200; i++) {
			assertEquals(Collections.singletonList("TRAIN|L" + i), names(TrackOwnersPackets.railOwners.get(platforms.get(i).getKey())));
		}
	}

	private static void receive(List<FriendlyByteBuf> packets) {
		packets.forEach(packet -> TrackOwnersPackets.receiveS2C(new FriendlyByteBuf(packet.copy())));
	}

	private static List<String> names(TrackOwnersPackets.Owners owners) {
		assertNotNull(owners);
		final List<String> names = new ArrayList<>();
		for (final int owner : owners.owners) {
			names.add(TrackOwnersPackets.ownerNames.get(owner));
		}
		return names;
	}

	private static long pos(int x, int z) {
		return (x + 100000L) * 1000000L + z + 100000L;
	}

	private static TrackSections.Rail rail(int x1, int z1, int x2, int z2, SectionType type, long savedRailId) {
		final int facing1 = (int) Math.round(Math.toDegrees(Math.atan2(z2 - z1, x2 - x1)) * 10);
		final int facing2 = (int) Math.round(Math.toDegrees(Math.atan2(z1 - z2, x1 - x2)) * 10);
		final long node1 = pos(x1, z1);
		final long node2 = pos(x2, z2);
		final long shape = node1 < node2 ? (long) facing1 << 32 | facing2 & 0xFFFFFFFFL : (long) facing2 << 32 | facing1 & 0xFFFFFFFFL;
		return new TrackSections.Rail(node1, node2, Math.hypot(x2 - x1, z2 - z1), shape, type == SectionType.PLATFORM ? 80 : 120, type == SectionType.NORMAL, type, true, true, savedRailId);
	}

	static {
		// RailKeys is what both sides key rails by
		assertEquals(RailKeys.of(1, 2), RailKeys.of(2, 1));
	}
}
