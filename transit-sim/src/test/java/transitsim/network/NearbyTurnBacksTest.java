package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where trains turn back near a platform when a route lists nowhere: dead ends of turn back rails, nearest first, then
 * one node per stabling block (step 2a of splitting the depot, 2026-11-13).
 */
class NearbyTurnBacksTest {

	private static long p(int x, int y, int z) {
		return NearbyTurnBacks.pack(x, y, z);
	}

	/**
	 * Rails between nodes, both ways, to count how many leave each node.
	 */
	private static final class Track {
		private final Map<Long, Set<Long>> links = new HashMap<>();
		private final List<long[]> turnBacks = new ArrayList<>();
		private final Map<String, Long> blocks = new HashMap<>();

		Track rail(long a, long b) {
			links.computeIfAbsent(a, k -> new HashSet<>()).add(b);
			links.computeIfAbsent(b, k -> new HashSet<>()).add(a);
			return this;
		}

		Track turnBack(long a, long b, long block) {
			rail(a, b);
			turnBacks.add(new long[]{a, b});
			if (block != NearbyTurnBacks.NO_BLOCK) {
				blocks.put(a + ":" + b, block);
			}
			return this;
		}

		NearbyTurnBacks nearby() {
			return new NearbyTurnBacks(turnBacks, node -> links.getOrDefault(node, Collections.emptySet()).size(), (a, b) -> blocks.getOrDefault(a + ":" + b, NearbyTurnBacks.NO_BLOCK));
		}
	}

	@Test
	void packedPositionsRoundTripAsMinecraftPacksThem() {
		for (final int[] xyz : new int[][]{{0, 0, 0}, {-1, -64, -1}, {33554431, 2047, -33554432}, {-1234, 70, 5678}}) {
			final long packed = p(xyz[0], xyz[1], xyz[2]);
			assertEquals(xyz[0], NearbyTurnBacks.x(packed));
			assertEquals(xyz[1], NearbyTurnBacks.y(packed));
			assertEquals(xyz[2], NearbyTurnBacks.z(packed));
		}
		assertEquals(25.0, NearbyTurnBacks.distSqr(p(3, 0, 4), p(0, 0, 0)));
	}

	@Test
	void deadEndsWithinReachComeNearestFirstUpToTheMost() {
		final long center = p(0, 64, 0);
		final Track track = new Track()
				// Dead ends at 30, 10 and 20 blocks, each reached from a node joined to the line
				.rail(p(-100, 64, 0), p(31, 64, 0)).turnBack(p(31, 64, 0), p(30, 64, 0), NearbyTurnBacks.NO_BLOCK)
				.rail(p(-100, 64, 1), p(11, 64, 0)).turnBack(p(11, 64, 0), p(10, 64, 0), NearbyTurnBacks.NO_BLOCK)
				.rail(p(-100, 64, 2), p(21, 64, 0)).turnBack(p(21, 64, 0), p(20, 64, 0), NearbyTurnBacks.NO_BLOCK)
				// Out of reach
				.rail(p(-100, 64, 3), p(501, 64, 0)).turnBack(p(501, 64, 0), p(500, 64, 0), NearbyTurnBacks.NO_BLOCK);
		final NearbyTurnBacks.Result result = track.nearby().near(1, center, 200, 2);
		assertEquals(Arrays.asList(p(10, 64, 0), p(20, 64, 0)), result.nodes);
		assertTrue(result.stabling.isEmpty());
	}

	@Test
	void aStablingBlockGivesItsNearestDeadEndOrElseItsFurthestEnd() {
		final long center = p(0, 64, 0);
		final Track track = new Track()
				// Block 7: a turn back rail with a dead end at 40, and one through at 30..35
				.rail(p(-100, 64, 0), p(41, 64, 0)).turnBack(p(41, 64, 0), p(40, 64, 0), 7)
				.rail(p(-100, 64, 1), p(30, 64, 0)).turnBack(p(30, 64, 0), p(35, 64, 0), 7).rail(p(35, 64, 0), p(100, 64, 1))
				// Block 3: no dead end, so the end furthest from the platform
				.rail(p(-100, 64, 2), p(0, 64, 50)).turnBack(p(0, 64, 50), p(0, 64, 60), 3).rail(p(0, 64, 60), p(100, 64, 2))
				// A plain dead end nearer than both
				.rail(p(-100, 64, 3), p(6, 64, 0)).turnBack(p(6, 64, 0), p(5, 64, 0), NearbyTurnBacks.NO_BLOCK);
		final NearbyTurnBacks nearby = track.nearby();
		final NearbyTurnBacks.Result result = nearby.near(1, center, 200, 6);
		// The plain dead end first, then the blocks' nodes, nearest first
		assertEquals(Arrays.asList(p(5, 64, 0), p(40, 64, 0), p(0, 64, 60)), result.nodes);
		assertEquals(Arrays.asList(p(40, 64, 0), p(0, 64, 60)), result.stabling);
		// Kept by platform: asked again, the same answer, whatever the centre given
		assertSame(result, nearby.near(1, p(1000, 64, 1000), 200, 6));
	}

	@Test
	void equallyFarNodesGoByPackedPosition() {
		final long center = p(0, 64, 0);
		final Track track = new Track()
				.rail(p(-100, 64, 0), p(11, 64, 0)).turnBack(p(11, 64, 0), p(10, 64, 0), NearbyTurnBacks.NO_BLOCK)
				.rail(p(-100, 64, 1), p(-11, 64, 0)).turnBack(p(-11, 64, 0), p(-10, 64, 0), NearbyTurnBacks.NO_BLOCK);
		final List<Long> nodes = track.nearby().near(1, center, 200, 6).nodes;
		final List<Long> expected = Arrays.asList(p(10, 64, 0), p(-10, 64, 0));
		expected.sort(Comparator.naturalOrder());
		assertEquals(expected, nodes);
	}
}
