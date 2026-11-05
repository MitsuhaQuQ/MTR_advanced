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
