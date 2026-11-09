package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a train waits between services, when it leaves its siding and the stations it goes out and back by, kept with the
 * duty (运用补完 step 1): read and written the same in every form, and duties without them as before.
 */
class DutyItemsTest {

	private static DepotDuties.DayDuty planned() {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = 0b11111;
		day.returnSidingId = 7;
		day.segments.addAll(Arrays.asList(new DepotDuties.Segment(1, 21600000, 0, -1), new DepotDuties.Segment(2, 23400000, 0, -1), new DepotDuties.Segment(1, 25200000, 0, -1)));
		day.waits.addAll(Arrays.asList(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL, DepotDuties.DayDuty.WAIT_CHANGE_PLATFORM));
		day.outLeadMillis = 95000;
		day.outEntrance = 12;
		day.backEntrance = 13;
		return day;
	}

	private static DepotDuties dutiesOf(DepotDuties.DayDuty day) {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.id = 5;
		slot.days.add(day);
		duties.sidings.put(7L, Collections.singletonList(slot));
		return duties;
	}

	@Test
	void plannedItemsReadBackInEveryForm() {
		final DepotDuties.DayDuty day = planned();
		// Saved and sent
		final DepotDuties read = new DepotDuties();
		read.fromBytes(dutiesOf(day).toBytes());
		final DepotDuties.DayDuty readDay = read.getSlots(7).get(0).days.get(0);
		assertTrue(readDay.sameAs(day));
		assertEquals(7, readDay.returnSidingId);
		// Kept by a train
		final DepotDuties.DayDuty fromText = DepotDuties.DayDuty.fromText(day.toText());
		assertNotNull(fromText);
		assertTrue(fromText.sameAs(day));
		// As layers: the waits in the body, the rest in the assignment
		final DutyLayers layers = DutyLayers.of(dutiesOf(day), siding -> "pool");
		assertEquals(day.waits, layers.bodies.values().iterator().next().waits);
		assertEquals(95000, layers.assignments.get(0).outLeadMillis);
		final DutyLayers readLayers = DutyLayers.fromBytes(layers.toBytes());
		assertNotNull(readLayers);
		assertArrayEquals(dutiesOf(day).toBytes(), readLayers.toDuties().toBytes());
	}

	@Test
	void dutiesWithoutPlannedItemsAreAsBefore() {
		final DepotDuties.DayDuty day = planned();
		day.waits.clear();
		day.outLeadMillis = -1;
		day.outEntrance = 0;
		day.backEntrance = 0;
		assertFalse(day.hasItems());
		// Written as text as before (six fields first), so a train's saved duty reads in an older version
		assertEquals(6, day.toText().split(";")[0].split(",").length);
		// The same body as one made without waits, keyed as before
		final DutyLayers.Body body = new DutyLayers.Body(false, day.segments);
		assertEquals(body.key, new DutyLayers.Body(false, day.segments, Collections.emptyList()).key);
		assertNotEquals(body.key, new DutyLayers.Body(false, day.segments, planned().waits).key);
		assertEquals(DepotDuties.DayDuty.WAIT_ANY, day.getWait(0));
	}

	@Test
	void theRuntimeLeavesAsPlannedButNeverSoonerThanTheWayOutTakes() {
		final DepotDuties.DayDuty day = planned();
		assertEquals(95000, transitsim.sim.DepotDispatcher.outLeadMillis(day, 60000));
		assertEquals(120000, transitsim.sim.DepotDispatcher.outLeadMillis(day, 120000));
		day.outLeadMillis = -1;
		assertEquals(75000, transitsim.sim.DepotDispatcher.outLeadMillis(day, 60000), "as before: the way out and 15 seconds");
	}
}
