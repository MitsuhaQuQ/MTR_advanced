package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties are their pool's (运用补完 step 5): a siding gone or changed to another vehicle hands its slots on to another
 * siding of the pool, or keeps them unplaced until the pool has a siding again, rather than losing them.
 */
class DutyRehomeTest {

	private static final String EMU = "emu/8";
	private static final String DMU = "dmu/4";

	@Test
	void aGoneSidingsSlotsGoToTheEmptiestSidingOfItsPool() {
		final DepotDuties duties = duties(1, 2, 2, 1, 3, 0);
		assertFalse(duties.rehome(pools(1, EMU, 2, EMU, 3, EMU)), "nothing gone: the pools are only recorded");
		assertEquals(EMU, duties.sidingPools.get(1L));
		assertTrue(duties.moves.isEmpty());

		// Siding 1 is taken away: its two slots go to 3, which has none, then 2, each to the one with fewer
		final long[] ids = {duties.getSlots(1).get(0).id, duties.getSlots(1).get(1).id};
		assertTrue(duties.rehome(pools(2, EMU, 3, EMU)));
		assertFalse(duties.sidings.containsKey(1L));
		assertEquals(1, duties.getSlots(3).size());
		assertEquals(2, duties.getSlots(2).size());
		assertEquals(ids[0], duties.getSlots(3).get(0).id, "the slot keeps its number");
		assertEquals(ids[1], duties.getSlots(2).get(1).id);
		assertEquals(2, duties.moves.size());
		assertEquals(1, duties.moves.get(0).fromSidingId);
		assertEquals(3, duties.moves.get(0).toSidingId);
		assertNotNull(duties.findSlot(ids[0]));
	}

	@Test
	void aSidingChangedToAnotherVehicleHandsItsSlotsOnAndThoseWithNoSidingWait() {
		final DepotDuties duties = duties(1, 2, 2, 1);
		duties.rehome(pools(1, EMU, 2, DMU));
		// Siding 2 now holds EMUs too; the DMU pool has no siding left, so its slot waits unplaced and isn't run
		assertTrue(duties.rehome(pools(1, EMU, 2, EMU)));
		assertEquals(2, duties.getSlots(1).size());
		assertFalse(duties.sidings.containsKey(2L));
		assertEquals(1, duties.unplaced.get(DMU).size());
		assertEquals(0, duties.moves.get(0).toSidingId);

		// Saved and read again, all of it is kept
		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties.toBytes());
		assertArrayEquals(duties.toBytes(), read.toBytes());
		assertEquals(1, read.unplaced.get(DMU).size());
		assertEquals(EMU, read.sidingPools.get(1L));
		assertEquals(1, read.moves.size());

		// A DMU siding again: the slot goes back to it
		assertTrue(read.rehome(pools(1, EMU, 2, EMU, 5, DMU)));
		assertTrue(read.unplaced.isEmpty());
		assertEquals(1, read.getSlots(5).size());
		assertEquals(DMU, read.sidingPools.get(5L));
		assertEquals(0, read.moves.get(read.moves.size() - 1).fromSidingId);

		// A player changing the duties has seen the moves
		read.setSlot(5, 1, -1, null);
		assertTrue(read.moves.isEmpty());
	}

	@Test
	void slotsOfASidingWithNoPoolRecordedStayAsTheyAre() {
		// Data saved before pools were recorded: the siding's pool now is taken as its own
		final DepotDuties duties = duties(1, 1);
		duties.sidingPools.clear();
		assertFalse(duties.rehome(pools(1, DMU)));
		assertEquals(DMU, duties.sidingPools.get(1L));
		// Gone with no pool recorded: kept unplaced under no pool
		final DepotDuties gone = duties(4, 1);
		assertTrue(gone.rehome(Collections.emptyMap()));
		assertEquals(1, gone.unplaced.get("").size());
	}

	/**
	 * @param counts siding, number of slots, in pairs
	 */
	private static DepotDuties duties(long... counts) {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		for (int i = 0; i < counts.length; i += 2) {
			if (counts[i + 1] > 0) {
				duties.setSlot(counts[i], (int) counts[i + 1], -1, null);
			}
		}
		return duties;
	}

	private static Map<Long, String> pools(Object... pairs) {
		final Map<Long, String> pools = new TreeMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			pools.put(((Number) pairs[i]).longValue(), (String) pairs[i + 1]);
		}
		return pools;
	}
}
