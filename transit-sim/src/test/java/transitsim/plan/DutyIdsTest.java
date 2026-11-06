package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.io.ByteArrayBinary;
import transitsim.sim.DepotDispatcher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties have numbers of their own (P2-D): kept as they are saved and sent, given the same way to data saved before, and
 * found again wherever a duty is moved.
 */
class DutyIdsTest {

	private static DepotDuties duties() {
		final DepotDuties duties = new DepotDuties();
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.id = 42;
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.stayOnLine = true;
		day.nextDutyId = 43;
		day.returnSidingId = 200;
		day.fromOvernight = true;
		day.segments.add(new DepotDuties.Segment(1, 25200000, 0, -1));
		slot.days.add(day);
		duties.sidings.put(100L, new ArrayList<>(Collections.singletonList(slot)));
		return duties;
	}

	@Test
	void numbersAndTheNewPartsOfADayAreSavedAndSent() {
		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties().toBytes());
		final DepotDuties.Slot slot = read.getSlots(100).get(0);
		assertEquals(42, slot.id);
		final DepotDuties.DayDuty day = slot.getDuty(0);
		assertEquals(43, day.nextDutyId);
		assertEquals(200, day.returnSidingId);
		assertTrue(day.fromOvernight);
	}

	@Test
	void dataSavedBeforeNumbersGetsTheSameNumbersEveryTime() {
		// Version 2: the mode, then a siding with one slot of one day, without the numbers or the new parts of a day
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writer.writeByte(2);
		writer.writeBoolean(true);
		writer.writeVarInt(1);
		writer.writeLong(100);
		writer.writeVarInt(1);
		writer.writeVarInt(1);
		writer.writeByte(DepotDuties.ALL_WEEKDAYS);
		writer.writeBoolean(false);
		writer.writeVarInt(0);
		writer.writeVarInt(1);
		writer.writeLong(1);
		writer.writeVarInt(25200000);
		writer.writeVarInt(0);
		writer.writeVarInt(0);
		final byte[] bytes = writer.toBytes();
		final DepotDuties first = new DepotDuties();
		first.fromBytes(bytes);
		final DepotDuties second = new DepotDuties();
		second.fromBytes(bytes);
		final long id = first.getSlots(100).get(0).id;
		assertNotEquals(0, id);
		assertEquals(id, second.getSlots(100).get(0).id);
		assertEquals(DepotDuties.defaultId(100, 0), id);
		assertEquals(0, first.getSlots(100).get(0).getDuty(0).nextDutyId);
		assertEquals(1, first.getSlots(100).get(0).getDuty(0).segments.get(0).routeId);
	}

	@Test
	void aDutyMovedToAnotherSidingIsFoundAndHasStillRunToday() {
		final DepotDuties duties = duties();
		final DepotDispatcher dispatcher = new DepotDispatcher();
		dispatcher.slotsOf = duties::getSlots;
		dispatcher.markDutyRun(100, 0, 5);
		// Moved to siding 200, after a slot already there
		final DepotDuties.Slot moved = duties.sidings.remove(100L).get(0);
		final DepotDuties.Slot before = new DepotDuties.Slot();
		before.id = 7;
		duties.sidings.put(200L, new ArrayList<>(Arrays.asList(before, moved)));
		assertArrayEquals(new long[]{200, 1}, duties.findSlot(42));
		assertTrue(dispatcher.hasDutyRun(200, 1, 5));
		assertFalse(dispatcher.hasDutyRun(200, 0, 5));
		assertNull(duties.findSlot(99));
	}

	@Test
	void aDutyAsTextKeepsTheNewPartsAndOlderTextStillReads() {
		final DepotDuties.DayDuty day = duties().getSlots(100).get(0).getDuty(0);
		final DepotDuties.DayDuty read = DepotDuties.DayDuty.fromText(day.toText());
		assertEquals(43, read.nextDutyId);
		assertEquals(200, read.returnSidingId);
		assertTrue(read.fromOvernight);
		final DepotDuties.DayDuty older = DepotDuties.DayDuty.fromText("127,1,-1;1,25200000,0,-1");
		assertNotNull(older);
		assertEquals(0, older.nextDutyId);
		assertEquals(1, older.segments.size());
	}
}
