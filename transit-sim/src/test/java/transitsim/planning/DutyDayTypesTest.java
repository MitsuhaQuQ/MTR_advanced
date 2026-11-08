package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Planning each timetable's days on their own (duty groups, step 2, option B) against planning the week at once: the
 * same services run, no train stays out from one day type into another, and with one day type nothing changes. Each
 * scenario prints both plans' counts, to compare before choosing.
 */
class DutyDayTypesTest {

	private static final long MINUTE = 60000;
	private static final long ROUTE_UP = 1;
	private static final long ROUTE_DOWN = 2;
	private static final int WEEKDAYS = 0x1F;
	private static final int WEEKEND = 0x60;

	private static DutyGenerator.ServiceInput shuttle(long routeId, long startMinutes, int weekdays) {
		final long[] platforms = routeId == ROUTE_UP ? new long[]{11, 21} : new long[]{22, 12};
		return new DutyGenerator.ServiceInput(routeId, (int) (startMinutes * MINUTE), weekdays, startMinutes * MINUTE, platforms, new long[][]{{0, 0}, {20 * MINUTE, 20 * MINUTE}}, new boolean[2], 0, 0, -1, 0);
	}

	private static Map<Long, Long> shuttleStations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(11L, 1L);
		stationOf.put(12L, 1L);
		stationOf.put(21L, 2L);
		stationOf.put(22L, 2L);
		return stationOf;
	}

	/**
	 * Every {@code weekdayEvery} minutes on weekdays from 06:00 to 22:00, every {@code weekendEvery} at the weekend from
	 * 07:00 to 21:00, both ways.
	 */
	private static List<DutyGenerator.ServiceInput> weekdaysAndWeekend(int weekdayEvery, int weekendEvery) {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (long t = 6 * 60; t <= 22 * 60; t += weekdayEvery) {
			services.add(shuttle(ROUTE_UP, t, WEEKDAYS));
			services.add(shuttle(ROUTE_DOWN, t + 25, WEEKDAYS));
		}
		for (long t = 7 * 60; t <= 21 * 60; t += weekendEvery) {
			services.add(shuttle(ROUTE_UP, t, WEEKEND));
			services.add(shuttle(ROUTE_DOWN, t + 25, WEEKEND));
		}
		return services;
	}

	/**
	 * A line run through the night with trains staying out, as {@code aTrainNeverGoingBackStillHasADutyEveryDay}: every
	 * 5 minutes on weekdays, every 10 at the weekend.
	 */
	private static List<DutyGenerator.ServiceInput> allDay() {
		final long[][] plans = {{0, 0}, {51000, 61000}, {84000, 84000}};
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (long t = 2 * 60; t <= 22 * 60 + 50; t += 5) {
			final int weekdays = t % 10 == 0 ? DepotDuties.ALL_WEEKDAYS : WEEKDAYS;
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (t * MINUTE), weekdays, t * MINUTE, new long[]{100, 101, 102}, plans, new boolean[3], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) (t * MINUTE), weekdays, t * MINUTE, new long[]{102, 103, 100}, plans, new boolean[3], 0, 0, -1, 0));
		}
		return services;
	}

	private static Map<Long, Long> allDayStations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(100L, 1L);
		stationOf.put(101L, 2L);
		stationOf.put(102L, 3L);
		stationOf.put(103L, 2L);
		return stationOf;
	}

	@Test
	void theDaysRunningTheSameServicesAreOneType() {
		assertEquals(Arrays.asList(WEEKDAYS, WEEKEND), DutyGenerator.dayTypes(weekdaysAndWeekend(15, 30)));
		assertEquals(Collections.singletonList(DepotDuties.ALL_WEEKDAYS), DutyGenerator.dayTypes(Arrays.asList(shuttle(ROUTE_UP, 360, DepotDuties.ALL_WEEKDAYS))));
		// Friday has an extra service: a type of its own
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>(weekdaysAndWeekend(15, 30));
		services.add(shuttle(ROUTE_UP, 23 * 60, 0x10));
		assertEquals(Arrays.asList(0x0F, 0x10, WEEKEND), DutyGenerator.dayTypes(services));
	}

	@Test
	void withOneDayTypeThePlanIsTheSame() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (long t = 6 * 60; t <= 22 * 60; t += 15) {
			services.add(shuttle(ROUTE_UP, t, DepotDuties.ALL_WEEKDAYS));
			services.add(shuttle(ROUTE_DOWN, t + 25, DepotDuties.ALL_WEEKDAYS));
		}
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 3));
		final DutyGenerator.Result week = DutyGenerator.generateSpread(services, sidings, shuttleStations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE);
		final DutyGenerator.Result byType = DutyGenerator.generateByDayType(services, sidings, shuttleStations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE);
		assertArrayEquals(bytes(week), bytes(byType));
		assertEquals(week.trains, byType.trains);
		assertEquals(week.uncovered, byType.uncovered);
	}

	@Test
	void weekdaysAndWeekendPlannedApartRunTheSameServices() {
		for (final int fleet : new int[]{-1, 4, 2}) {
			final List<DutyGenerator.ServiceInput> services = weekdaysAndWeekend(15, 30);
			final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, fleet));
			final DutyGenerator.Result week = DutyGenerator.generateSpread(services, sidings, shuttleStations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE);
			final DutyGenerator.Result byType = DutyGenerator.generateByDayType(services, sidings, shuttleStations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE);
			report("shuttle, fleet " + fleet, week, byType);
			assertNoStayIntoAnotherType(byType, DutyGenerator.dayTypes(services));
			if (week.uncovered == 0) {
				assertEquals(0, byType.uncovered, "fleet " + fleet);
				assertRunsEachServiceOnceADay(services, byType);
			}
		}
	}

	@Test
	void aLineRunThroughTheNightKeepsItsTrainsOutOnlyWithinADayType() {
		final List<DutyGenerator.ServiceInput> services = allDay();
		final List<Integer> types = DutyGenerator.dayTypes(services);
		assertEquals(Arrays.asList(WEEKDAYS, WEEKEND), types);
		for (final int fleet : new int[]{-1, 3}) {
			final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, fleet));
			final DutyGenerator.Result week = DutyGenerator.generateSpread(services, sidings, allDayStations(), RouteTimetable.MAX_STAY_MILLIS, DutyGenerator.PlanInputs.NONE);
			final DutyGenerator.Result byType = DutyGenerator.generateByDayType(services, sidings, allDayStations(), RouteTimetable.MAX_STAY_MILLIS, DutyGenerator.PlanInputs.NONE);
			report("all day, fleet " + fleet, week, byType);
			assertNoStayIntoAnotherType(byType, types);
			if (week.uncovered == 0) {
				assertEquals(0, byType.uncovered, "fleet " + fleet);
				assertRunsEachServiceOnceADay(services, byType);
			}
		}
	}

	private static byte[] bytes(DutyGenerator.Result result) {
		final DepotDuties duties = new DepotDuties();
		duties.sidings.putAll(result.sidings);
		return duties.toBytes();
	}

	private static void report(String name, DutyGenerator.Result week, DutyGenerator.Result byType) {
		System.out.println("Day types, " + name + ": week at once " + counts(week) + "; by day type " + counts(byType));
	}

	private static String counts(DutyGenerator.Result result) {
		int rows = 0;
		int stays = 0;
		int slots = 0;
		for (final List<DepotDuties.Slot> list : result.sidings.values()) {
			slots += list.size();
			for (final DepotDuties.Slot slot : list) {
				rows += slot.days.size();
				for (final DepotDuties.DayDuty day : slot.days) {
					if (day.stayOnLine) {
						stays += Integer.bitCount(day.weekdays);
					}
				}
			}
		}
		return "trains " + result.trains + ", slots " + slots + ", uncovered " + result.uncovered + ", duty rows " + rows + ", nights out " + stays;
	}

	/**
	 * A duty staying out overnight goes on the next day within its own type.
	 */
	private static void assertNoStayIntoAnotherType(DutyGenerator.Result result, List<Integer> types) {
		result.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> {
			if (!day.stayOnLine) {
				return;
			}
			for (int d = 0; d < 7; d++) {
				final int today = d;
				if ((day.weekdays >> today & 1) != 0) {
					final int next = (today + 1) % 7;
					final int type = types.stream().filter(mask -> (mask >> today & 1) != 0).findFirst().orElse(0);
					assertTrue((type >> next & 1) != 0, "a train staying out on day " + today + " goes on into day " + next + " of another type");
				}
			}
		})));
	}

	private static void assertRunsEachServiceOnceADay(List<DutyGenerator.ServiceInput> services, DutyGenerator.Result result) {
		for (int d = 0; d < 7; d++) {
			final int day = d;
			final List<String> expected = new ArrayList<>();
			services.forEach(service -> {
				if ((service.weekdays >> day & 1) != 0) {
					expected.add(service.routeId + "@" + service.service);
				}
			});
			final List<String> run = new ArrayList<>();
			result.sidings.values().forEach(slots -> slots.forEach(slot -> {
				final DepotDuties.DayDuty duty = slot.getDuty(day);
				if (duty != null) {
					duty.segments.forEach(segment -> run.add(segment.routeId + "@" + segment.service));
				}
			}));
			Collections.sort(expected);
			Collections.sort(run);
			assertEquals(expected, run, "day " + d);
		}
	}
}
