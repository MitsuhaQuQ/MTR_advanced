package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties as bodies, groups and assignments (duty groups, 运用组): the layers turn back into the duties exactly, and what
 * is the same is kept once.
 */
class DutyLayersTest {

	private static final int WEEKDAYS = 0x1F;
	private static final int WEEKEND = 0x60;

	private static DepotDuties.DayDuty day(int weekdays, boolean stayOnLine, int... services) {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = weekdays;
		day.stayOnLine = stayOnLine;
		for (final int service : services) {
			day.segments.add(new DepotDuties.Segment(service % 2 == 0 ? 10 : 11, service * 60000, 0, -1));
		}
		return day;
	}

	private static DepotDuties.Slot slot(long id, DepotDuties.DayDuty... days) {
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.id = id;
		slot.days.addAll(Arrays.asList(days));
		return slot;
	}

	/**
	 * Two sidings of one pool: a train running all week, one staying out on weekdays and going on with the other siding's
	 * duty, an empty slot, and different duties at the weekend.
	 */
	private static DepotDuties duties() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.DayDuty out = day(WEEKDAYS, true, 1, 2, 3);
		out.nextDutyId = 22;
		out.nextSlot = 0;
		final DepotDuties.DayDuty back = day(WEEKDAYS, false, 4, 5);
		back.returnSidingId = 200;
		back.fromOvernight = true;
		final DepotDuties.Slot first = slot(11, out, day(WEEKEND, false, 7, 8));
		first.start = DepotDuties.Slot.START_ANY;
		duties.sidings.put(100L, Arrays.asList(first, slot(12)));
		duties.sidings.put(200L, Arrays.asList(slot(22, back, day(WEEKEND, false, 9))));
		return duties;
	}

	@Test
	void theLayersTurnBackIntoTheDutiesExactly() {
		final DepotDuties duties = duties();
		final DutyLayers layers = DutyLayers.of(duties, sidingId -> "pm178");
		assertArrayEquals(duties.toBytes(), layers.toDuties().toBytes());
		final DutyLayers read = DutyLayers.fromBytes(layers.toBytes());
		assertNotNull(read);
		assertArrayEquals(duties.toBytes(), read.toDuties().toBytes());
		assertArrayEquals(layers.toBytes(), read.toBytes());
		// Bodies and groups: 4 duties, 2 groups (weekdays, weekend) of one pool
		assertEquals(4, layers.bodies.size());
		assertEquals(2, layers.groups.size());
		assertEquals(5, layers.assignments.size(), "4 rows of weekdays and the empty slot");
		assertNull(DutyLayers.fromBytes(new byte[]{9, 9}));
	}

	@Test
	void theSameDutiesOnOtherWeekdaysAreOneGroup() {
		final DepotDuties duties = new DepotDuties();
		// Monday to Thursday and Friday split, as by two timetables with the same services
		duties.sidings.put(100L, Arrays.asList(slot(1, day(0x0F, false, 1, 2), day(0x10, false, 1, 2)), slot(2, day(0x0F, false, 3), day(0x10, false, 3))));
		final DutyLayers layers = DutyLayers.of(duties, sidingId -> "pool");
		assertEquals(2, layers.bodies.size());
		assertEquals(1, layers.groups.size(), "the two timetables' duties are kept once");
		assertArrayEquals(duties.toBytes(), layers.toDuties().toBytes());
	}

	@Test
	void anotherDepotRunningTheSameDutiesRefersToTheSameGroupsAndBodies() {
		final DepotDuties first = duties();
		final DepotDuties second = new DepotDuties();
		// Its own sidings and numbers, the same duties
		first.sidings.forEach((sidingId, slots) -> second.sidings.put(sidingId + 1000, slots));
		final DutyLayers layersFirst = DutyLayers.of(first, sidingId -> "pm178");
		final DutyLayers layersSecond = DutyLayers.of(second, sidingId -> "pm178");
		assertEquals(layersFirst.groups.keySet(), layersSecond.groups.keySet());
		assertEquals(layersFirst.bodies.keySet(), layersSecond.bodies.keySet());
		assertNotEquals(layersFirst.getGroupKeys(first.sidings.keySet()), layersSecond.getGroupKeys(first.sidings.keySet()), "only the assignments differ");
		assertEquals(layersFirst.getGroupKeys(first.sidings.keySet()), layersSecond.getGroupKeys(second.sidings.keySet()));
	}

	@Test
	void anotherPoolIsAnotherGroupWithTheSameBodies() {
		final DepotDuties duties = duties();
		final DutyLayers layers = DutyLayers.of(duties, sidingId -> sidingId == 100 ? "pm178" : "sh03b01");
		final Set<String> pools = new HashSet<>();
		layers.groups.values().forEach(group -> pools.add(group.pool));
		assertEquals(new HashSet<>(Arrays.asList("pm178", "sh03b01")), pools);
		assertEquals(4, layers.groups.size());
		assertArrayEquals(duties.toBytes(), layers.toDuties().toBytes());
	}

	@Test
	void aMissingBodyIsToldRatherThanLeftOut() {
		final DutyLayers layers = DutyLayers.of(duties(), sidingId -> "pm178");
		layers.bodies.clear();
		assertNull(layers.toDuties());
	}
}
