package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.io.ByteArrayBinary;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DutyGeneratorTest {

	private static final long MINUTE = 60000;
	private static final long ROUTE_UP = 1;
	private static final long ROUTE_DOWN = 2;

	/**
	 * A two station shuttle: up from platform 11 to 21, down from 22 to 12, with stations 1 and 2 having one platform for
	 * each direction. Each run takes 20 minutes.
	 */
	private static DutyGenerator.ServiceInput service(long routeId, long startMinutes, int weekdays, long linkRoute, long linkStartMinutes) {
		final long[] platforms = routeId == ROUTE_UP ? new long[]{11, 21} : new long[]{22, 12};
		return new DutyGenerator.ServiceInput(routeId, (int) (startMinutes * MINUTE), weekdays, startMinutes * MINUTE, platforms, new long[][]{{0, 0}, {20 * MINUTE, 20 * MINUTE}}, new boolean[2], linkRoute, (int) (linkStartMinutes * MINUTE), -1, 0);
	}

	private static Map<Long, Long> stations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(11L, 1L);
		stationOf.put(12L, 1L);
		stationOf.put(21L, 2L);
		stationOf.put(22L, 2L);
		return stationOf;
	}

	@Test
	void runsEveryServiceOnceWithTheFewestTrains() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			services.add(service(ROUTE_UP, 360 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
			services.add(service(ROUTE_DOWN, 385 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
		}
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 4)), stations(), 30 * MINUTE);

		// A round trip is an hour and a train leaves every half hour, so two trains run it
		assertEquals(2, result.trains);
		assertEquals(0, result.uncovered);
		final List<DepotDuties.Slot> slots = result.sidings.get(100L);
		assertEquals(2, slots.size());
		final Set<DepotDuties.Segment> runs = new HashSet<>();
		slots.forEach(slot -> {
			// Every day is the same, so each train has one duty for the whole week
			assertEquals(1, slot.days.size());
			assertEquals(DepotDuties.ALL_WEEKDAYS, slot.days.get(0).weekdays);
			assertFalse(slot.days.get(0).stayOnLine);
			slot.days.get(0).segments.forEach(segment -> assertTrue(runs.add(segment)));
		});
		assertEquals(12, runs.size());
	}

	@Test
	void runsLinkedServicesAsOneTrain() {
		final List<DutyGenerator.ServiceInput> services = Arrays.asList(
				service(ROUTE_UP, 360, DepotDuties.ALL_WEEKDAYS, ROUTE_DOWN, 385),
				service(ROUTE_DOWN, 385, DepotDuties.ALL_WEEKDAYS, 0, 0)
		);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, -1)), stations(), 30 * MINUTE);
		final List<DepotDuties.Slot> slots = result.sidings.get(100L);
		assertEquals(1, slots.size());
		final List<DepotDuties.Segment> segments = slots.get(0).days.get(0).segments;
		assertEquals(2, segments.size());
		assertEquals(ROUTE_UP, segments.get(0).routeId);
		assertEquals(ROUTE_DOWN, segments.get(1).routeId);
	}

	@Test
	void keepsWeekendsApartWhenTheyDiffer() {
		final List<DutyGenerator.ServiceInput> services = Arrays.asList(
				service(ROUTE_UP, 360, 0x1F, 0, 0),
				service(ROUTE_UP, 420, 0x60, 0, 0)
		);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, -1)), stations(), 30 * MINUTE);
		final DepotDuties.Slot slot = result.sidings.get(100L).get(0);
		assertEquals(2, slot.days.size());
		assertEquals(360 * MINUTE, slot.getDuty(0).segments.get(0).service);
		assertEquals(420 * MINUTE, slot.getDuty(6).segments.get(0).service);
	}

	@Test
	void savesAndReadsDuties() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = 0x1F;
		day.stayOnLine = true;
		day.segments.add(new DepotDuties.Segment(ROUTE_UP, 21600000, 0, -1));
		day.segments.add(new DepotDuties.Segment(ROUTE_DOWN, 23100000, 1, 3));
		slot.days.add(day);
		duties.setSlot(100, 2, 1, slot);

		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties.toBytes());
		assertTrue(read.diagramMode);
		assertEquals(2, read.getSlots(100).size());
		assertTrue(read.getSlots(100).get(0).days.isEmpty());
		final DepotDuties.DayDuty readDay = read.getSlots(100).get(1).getDuty(2);
		assertNotNull(readDay);
		assertTrue(readDay.stayOnLine);
		assertEquals(day.segments, readDay.segments);
		assertNull(read.getSlots(100).get(1).getDuty(5));

		// One slot at a time, as the game sends them
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		slot.write(writer);
		read.setSlot(100, 1, 0, DepotDuties.Slot.read(new ByteArrayBinary.Reader(writer.toBytes())));
		assertEquals(1, read.getSlots(100).size());
		assertEquals(day.segments, read.getSlots(100).get(0).getDuty(0).segments);
		read.setSlot(100, 0, -1, null);
		assertTrue(read.isEmpty());
	}
}
