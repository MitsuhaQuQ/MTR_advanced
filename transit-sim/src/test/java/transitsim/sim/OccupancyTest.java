package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rails taken by trains, kept over two ticks (S2d).
 */
class OccupancyTest {

	private static final UUID RAIL = new UUID(1, 2);
	private static final UUID OTHER_RAIL = new UUID(3, 4);

	@Test
	void seesOtherTrainsButNotItself() {
		final Occupancy occupancy = new Occupancy();
		occupancy.occupy(RAIL, 10);
		assertTrue(occupancy.isOccupiedNow(RAIL));
		assertTrue(occupancy.isTakenByOther(RAIL, 20));
		assertFalse(occupancy.isTakenByOther(RAIL, 10));
		assertFalse(occupancy.isTakenByOther(OTHER_RAIL, 20));
	}

	@Test
	void theFirstToTakeARailKeepsIt() {
		final Occupancy occupancy = new Occupancy();
		occupancy.occupy(RAIL, 10);
		occupancy.occupy(RAIL, 20);
		assertTrue(occupancy.isTakenByOther(RAIL, 20));
		assertFalse(occupancy.isTakenByOther(RAIL, 10));
	}

	@Test
	void remembersTheTickBefore() {
		final Occupancy occupancy = new Occupancy();
		occupancy.occupy(RAIL, 10);
		occupancy.nextTick();
		// Taken in the tick before, though not yet this tick
		assertFalse(occupancy.isOccupiedNow(RAIL));
		assertTrue(occupancy.isTakenByOther(RAIL, 20));
		occupancy.nextTick();
		assertFalse(occupancy.isTakenByOther(RAIL, 20));
	}

	@Test
	void theHostsListIsTheSameData() {
		final Occupancy occupancy = new Occupancy();
		occupancy.getTicks().get(1).put(RAIL, 10L);
		assertTrue(occupancy.isTakenByOther(RAIL, 20));
		final java.util.List<java.util.Map<UUID, Long>> ticks = occupancy.getTicks();
		occupancy.nextTick();
		assertSame(ticks, occupancy.getTicks());
		assertEquals(2, ticks.size());
		assertTrue(ticks.get(0).containsKey(RAIL));
	}
}
