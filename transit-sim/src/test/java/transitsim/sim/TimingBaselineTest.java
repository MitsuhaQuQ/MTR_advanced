package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimingBaselineTest {
	private static StopTimes actual() {
		return StopTimes.of(new long[]{0, 1, 0, 200}, new long[]{1, 2, 1000, 1200}, new long[]{2, 3, 2000, 2200}, new long[]{3, 4, 3000, 3200});
	}

	@Test
	void runtimeUsesTheSameTableBaselineAsTheDiagramInsteadOfVehicleTimes() {
		TestRoute route = new TestRoute(1, 1, 2, 3);
		route.timetable.marginPercent = 0;
		RouteTimetable second = new RouteTimetable();
		second.marginPercent = 0;
		route.timetable.addTable(second);
		route.timingBaseline = new long[][]{{0,0,0,0},{80000,110000,100000,130000},{170000,180000,210000,220000}};
		StopTimes actual = actual();
		for (int table = 0; table < 2; table++) {
			List<StopRules.StopTime> shown = StopRules.times(route.getStops(), route.timingBaseline, 0, table, null);
			for (int stop = 0; stop < 3; stop++) {
				RouteTimetable.StopPlan plan = actual.getPlan(Collections.singletonList(route), route.timetable, stop, route.id, table, -1);
				assertEquals(shown.get(stop).arrivalMillis, plan.arrivalMillis);
				assertEquals(shown.get(stop).departureMillis, plan.departureMillis);
			}
		}
	}

	@Test
	void sharedTerminalAnchorsTheNextServiceWithoutAddingAnotherDoorDwell() {
		TestRoute first = new TestRoute(1, 1, 2, 3), next = new TestRoute(2, 3, 4);
		first.timetable.marginPercent = next.timetable.marginPercent = 0;
		first.timingBaseline = new long[][]{{0,0},{80000,110000},{170000,180000}};
		next.timingBaseline = new long[][]{{0,0},{120000,140000}};
		StopTimes times = actual();
		List<TestRoute> routes = Arrays.asList(first, next);
		RouteTimetable.StopPlan start = times.getPlan(routes, next.timetable, 2, next.id, 0, -1);
		RouteTimetable.StopPlan end = times.getPlan(routes, next.timetable, 3, next.id, 0, -1);
		assertEquals(170000, start.routeStartMillis);
		assertEquals(0, start.departureMillis - start.routeStartMillis);
		assertEquals(120000, end.arrivalMillis - start.routeStartMillis);
		assertEquals(140000, end.departureMillis - start.routeStartMillis);
	}

	@Test
	void replacingTheBaselineInvalidatesCachedRuntimePlans() {
		TestRoute route = new TestRoute(1, 1, 2, 3);
		route.timetable.marginPercent = 0;
		route.timingBaseline = new long[][]{{0,0},{80000,110000},{170000,180000}};
		StopTimes times = actual();
		assertEquals(80000, times.getPlan(Collections.singletonList(route), route.timetable, 1, route.id, 0, -1).arrivalMillis);
		route.timingBaseline = new long[][]{{0,0},{100000,130000},{210000,220000}};
		assertEquals(100000, times.getPlan(Collections.singletonList(route), route.timetable, 1, route.id, 0, -1).arrivalMillis);
	}

	@Test
	void joiningMidRouteUsesTheSameEntryOffsetAsTheDiagram() {
		TestRoute route = new TestRoute(1, 1, 2, 3);
		route.timetable.marginPercent = 0;
		route.timingBaseline = new long[][]{{0,0},{80000,110000},{170000,180000}};
		StopTimes times = actual();
		times.setEntry(route.id, 1, new long[][]{{0,10000},{50000,60000},{100000,110000}});
		assertEquals(80000, times.getEntryOffsetMillis(route, 1, 0, -1));
	}

	@Test
	void aFixedPlanKeepsItsOwnBaselineCopy() {
		TestRoute route = new TestRoute(1, 1, 2);
		route.timingBaseline = new long[][]{{0,0},{60000,60000}};
		PlanRoute fixed = PlanVersions.fix(route);
		route.timingBaseline[1][0] = 90000;
		assertEquals(60000, fixed.getTimingBaseline()[1][0]);
		route.timetable.revision++;
		assertEquals(90000, PlanVersions.fix(route).getTimingBaseline()[1][0]);
	}
}
