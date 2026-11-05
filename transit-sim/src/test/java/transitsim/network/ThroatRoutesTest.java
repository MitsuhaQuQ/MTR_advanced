package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The turnouts a route passes coming in to and going out of its stops, and which routes cross (L2).
 */
class ThroatRoutesTest {

	/*
	 * A station with two tracks, 1 north (z -5) and 2 south (z 5), joined by a crossover west of it:
	 *
	 *   10 --- 11(turnout) ------ 12 ===== 13   track 1
	 *                \
	 *   20 --- 21(turnout) ------ 22 ===== 23   track 2
	 *
	 * Node 11 has rails to 10, 12 and 21; node 21 to 20, 22 and 11.
	 */
	private static final Map<Long, double[]> POSITIONS = new HashMap<>();
	private static final Map<Long, Integer> RAILS = new HashMap<>();

	static {
		POSITIONS.put(10L, new double[]{0, -5});
		POSITIONS.put(11L, new double[]{20, -5});
		POSITIONS.put(12L, new double[]{40, -5});
		POSITIONS.put(13L, new double[]{60, -5});
		POSITIONS.put(20L, new double[]{0, 5});
		POSITIONS.put(21L, new double[]{20, 5});
		POSITIONS.put(22L, new double[]{40, 5});
		POSITIONS.put(23L, new double[]{60, 5});
		RAILS.put(11L, 3);
		RAILS.put(21L, 3);
	}

	private static List<ThroatRoutes.StopThroat> throats(long[] nodes, int... stopRails) {
		return ThroatRoutes.of(nodes, POSITIONS::get, stopRails, node -> RAILS.getOrDefault(node, 2));
	}

	@Test
	void routesThroughTheSameInterlockingZoneConflictWithoutAShareNode() {
		// Tracks 1 and 2 coming in straight share no node, but the rails 11-12 and 21-22 are painted as one zone, as where
		// they cross without a node
		final Map<java.util.UUID, Long> zones = new HashMap<>();
		zones.put(RailKeys.of(11, 12), 7L);
		zones.put(RailKeys.of(21, 22), 7L);
		final List<ThroatRoutes.StopThroat> one = ThroatRoutes.of(new long[]{10, 11, 12, 13}, POSITIONS::get, new int[]{2}, node -> RAILS.getOrDefault(node, 2), rail -> zones.getOrDefault(rail, 0L));
		final List<ThroatRoutes.StopThroat> two = ThroatRoutes.of(new long[]{20, 21, 22, 23}, POSITIONS::get, new int[]{2}, node -> RAILS.getOrDefault(node, 2), rail -> zones.getOrDefault(rail, 0L));
		final ThroatRoutes.Item conflict = ThroatRoutes.findConflict(one.get(0).entry, two.get(0).entry);
		assertNotNull(conflict);
		assertTrue(conflict.isZone());
		assertEquals(7, conflict.node);
		// The path enters it at rail 1, from node 11, and the tail leaves it after rail 1
		assertEquals(1, conflict.index);
		assertEquals(2, conflict.endIndex);
		assertEquals(11, conflict.getPlace());
		// Unpainted, the two straight ways don't cross
		assertNull(ThroatRoutes.findConflict(throats(new long[]{10, 11, 12, 13}, 2).get(0).entry, throats(new long[]{20, 21, 22, 23}, 2).get(0).entry));
	}

	@Test
	void aZoneFarFromTheThroatIsntPartOfIt() {
		// A straight line with no turnouts, every 20 blocks, the stop on rail 6; a zone on rail 4 is just before it, one on
		// rail 0 is 60 blocks of plain track further back, more than the 64 counted from rail 4's end
		final Map<Long, double[]> positions = new HashMap<>();
		final long[] nodes = new long[9];
		for (int i = 0; i < nodes.length; i++) {
			nodes[i] = 100 + i;
			positions.put(nodes[i], new double[]{i * 20, 0});
		}
		final Map<java.util.UUID, Long> zones = new HashMap<>();
		zones.put(RailKeys.of(nodes[4], nodes[5]), 1L);
		zones.put(RailKeys.of(nodes[0], nodes[1]), 2L);
		final List<ThroatRoutes.Item> entry = ThroatRoutes.of(nodes, positions::get, new int[]{6}, node -> 2, rail -> zones.getOrDefault(rail, 0L)).get(0).entry;
		assertEquals(1, entry.size());
		assertEquals(1, entry.get(0).node);
	}

	@Test
	void comingInOverTheCrossover() {
		// From the north line's west end, over the crossover, into track 2 (rail 22-23)
		final List<ThroatRoutes.StopThroat> throats = throats(new long[]{10, 11, 21, 22, 23}, 3);
		final List<ThroatRoutes.Item> entry = throats.get(0).entry;
		assertEquals(2, entry.size());
		assertEquals(new ThroatRoutes.Item(21, 11, 22), entry.get(0));
		assertEquals(new ThroatRoutes.Item(11, 10, 21), entry.get(1));
		assertTrue(throats.get(0).exit.isEmpty());
	}

	@Test
	void goingOutStraightDoesntCrossGoingInStraight() {
		// Track 1 straight both ways uses node 11 by 10-12; over the crossover it is 10-21
		final ThroatRoutes.StopThroat straightIn = throats(new long[]{10, 11, 12, 13}, 2).get(0);
		final ThroatRoutes.StopThroat straightOut = throats(new long[]{13, 12, 11, 10}, 0).get(0);
		final ThroatRoutes.StopThroat crossing = throats(new long[]{10, 11, 21, 22, 23}, 3).get(0);
		assertNull(ThroatRoutes.findConflict(straightIn.entry, straightOut.exit), "the same way, opposite directions");
		final ThroatRoutes.Item conflict = ThroatRoutes.findConflict(straightIn.entry, crossing.entry);
		assertNotNull(conflict);
		assertEquals(11, conflict.node);
	}

	@Test
	void theThroatEndsAfterPlainTrack() {
		// A long way of plain track before the crossover: the walk stops before reaching it
		POSITIONS.put(30L, new double[]{-200, -5});
		POSITIONS.put(31L, new double[]{-100, -5});
		final Map<Long, Integer> rails = new HashMap<>(RAILS);
		rails.put(31L, 3);
		final List<ThroatRoutes.StopThroat> throats = ThroatRoutes.of(new long[]{30, 31, 10, 11, 12, 13}, POSITIONS::get, new int[]{4}, node -> rails.getOrDefault(node, 2));
		// Node 11 is in the throat; node 31 is past 110 blocks of plain track from it
		assertEquals(1, throats.get(0).entry.size());
		assertEquals(11, throats.get(0).entry.get(0).node);
	}

	@Test
	void theThroatEndsAtTheNeighbouringStop() {
		// Two stops: track 1's rail 11-12 then 12-13; the second stop's entry doesn't reach back past the first platform
		final List<ThroatRoutes.StopThroat> throats = throats(new long[]{10, 11, 12, 13}, 1, 2);
		assertTrue(throats.get(1).entry.isEmpty());
		assertEquals(0, throats.get(0).exit.size());
	}
}
