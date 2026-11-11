package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.planning.DutyGenerator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimetableTimesTest {
	@Test
	void ordinaryServicesComputeOneTemplateAndKeepIndependentArrays() {
		RouteTimetable timetable = new RouteTimetable();
		timetable.marginPercent = 0;
		for (int i = 0; i < 100; i++) timetable.services.add(i * 600000);
		ServiceExceptions.StopException exception = new ServiceExceptions.StopException();
		exception.runSeconds = 90;
		timetable.exceptions.put(600000, Collections.singletonMap(1, exception));
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		PlanStop measured = new PlanStop() {
			public long getPlatformId() { return 2; }
			public boolean isPass() { return false; }
			public int getArrival(int table) { reads.incrementAndGet(); return -1; }
			public int getDeparture(int table) { return -1; }
			public boolean getOvertake(int table) { return false; }
		};
		PlanRoute route = new PlanRoute() {
			public long getRouteId() { return 1; }
			public RouteTimetable getTimetable() { return timetable; }
			public List<PlanStop> getStops() { return Arrays.asList(stop(1), measured); }
			public boolean isLoop() { return false; }
			public int getTransportModeId() { return 0; }
		};
		List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(Collections.singletonList(route), ignored -> new long[][]{{0,0},{60000,60000}}, 0, ignored -> fail("Missing times"));
		assertEquals(2, reads.get());
		assertEquals(90000, services.get(1).plans[1][0]);
		services.get(0).plans[1][0] = 7;
		assertEquals(60000, services.get(2).plans[1][0]);
	}

	@Test
	void plannerAndDisplaySelectTheSameTableIncludingExceptions() {
		final RouteTimetable timetable = new RouteTimetable();
		timetable.marginPercent = 0;
		timetable.weekdays = 1;
		timetable.services.add(0);
		final RouteTimetable second = new RouteTimetable();
		second.marginPercent = 0;
		second.weekdays = 2;
		second.services.add(0);
		timetable.addTable(second);
		final List<PlanStop> stops = Arrays.asList(stop(1), stop(2));
		final PlanRoute route = new PlanRoute() {
			public long getRouteId() { return 1; }
			public RouteTimetable getTimetable() { return timetable; }
			public List<PlanStop> getStops() { return stops; }
			public boolean isLoop() { return false; }
			public int getTransportModeId() { return 0; }
		};
		long[][] fast = {{0, 0}, {60000, 60000}};
		long[][] slow = {{0, 0}, {120000, 120000}};
		long[][] times = TimetableTimes.pack(Arrays.asList(fast, slow));
		List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(Collections.singletonList(route), ignored -> times, 0, ignored -> fail("Missing times"));
		assertEquals(60000, services.get(0).plans[1][0]);
		assertEquals(120000, services.get(1).plans[1][0]);
		assertEquals(120000, StopRules.times(stops, times, 0, 1, null).get(1).arrivalMillis);
		ServiceExceptions.StopException exception = new ServiceExceptions.StopException();
		exception.runSeconds = 90;
		assertTrue(StopRules.times(stops, times, 0, 1, Collections.singletonMap(1, exception)).get(1).tooFast);
		assertEquals(60000, StopRules.times(stops, fast, 0, 1, null).get(1).arrivalMillis);
	}

	private static PlanStop stop(long id) {
		return new PlanStop() {
			public long getPlatformId() { return id; }
			public boolean isPass() { return false; }
			public int getArrival(int table) { return -1; }
			public int getDeparture(int table) { return -1; }
			public boolean getOvertake(int table) { return false; }
		};
	}
}
