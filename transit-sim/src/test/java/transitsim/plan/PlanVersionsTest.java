package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanVersionsTest {

	@Test
	void aFixedRouteDoesNotChangeWithTheRoute() {
		final Route route = new Route(1, 2, 3);
		route.timetable.services.add(8 * 60 * 60 * 1000);
		final ServiceExceptions.StopException stopException = new ServiceExceptions.StopException();
		stopException.pass = 1;
		route.timetable.exceptions.computeIfAbsent(8 * 60 * 60 * 1000, service -> new HashMap<>()).put(1, stopException);
		final RouteTimetable otherTable = new RouteTimetable();
		otherTable.services.add(9 * 60 * 60 * 1000);
		route.timetable.addTable(otherTable);
		route.stops.get(1).arrivals[1] = 90;

		final PlanRoute fixedRoute = PlanVersions.fix(route);
		route.timetable.services.clear();
		route.timetable.exceptions.clear();
		otherTable.services.clear();
		stopException.pass = 0;
		route.stops.get(1).arrivals[1] = 120;
		route.stops.get(1).pass = true;
		route.timetable.revision++;

		assertEquals(1, fixedRoute.getTimetable().services.size());
		assertEquals(1, fixedRoute.getTimetable().exceptions.get(8 * 60 * 60 * 1000).get(1).pass);
		assertEquals(1, fixedRoute.getTimetable().getTable(1).services.size());
		assertEquals(90, fixedRoute.getStops().get(1).getArrival(1));
		assertFalse(fixedRoute.getStops().get(1).isPass());
		assertEquals(3, fixedRoute.getStops().size());
	}

	@Test
	void trainsOnTheSameRevisionShareACopy() {
		final Route route = new Route(1, 2, 3);
		final PlanRoute first = PlanVersions.fix(route);
		assertSame(first, PlanVersions.fix(route));
		assertSame(first, PlanVersions.fix(first));

		route.timetable.revision++;
		final PlanRoute second = PlanVersions.fix(route);
		assertNotSame(first, second);
		assertEquals(route.timetable.revision, second.getTimetable().revision);
		assertTrue(first.getTimetable().revision < second.getTimetable().revision);
	}

	private static class Route implements PlanRoute {

		final RouteTimetable timetable = new RouteTimetable();
		final List<Stop> stops = new ArrayList<>();

		Route(long... platformIds) {
			for (final long platformId : platformIds) {
				stops.add(new Stop(platformId));
			}
		}

		@Override
		public long getRouteId() {
			return 1;
		}

		@Override
		public RouteTimetable getTimetable() {
			return timetable;
		}

		@Override
		public List<Stop> getStops() {
			return stops;
		}

		@Override
		public boolean isLoop() {
			return false;
		}

		@Override
		public int getTransportModeId() {
			return 0;
		}
	}

	private static class Stop implements PlanStop {

		final long platformId;
		final int[] arrivals = {-1, -1};
		boolean pass;

		Stop(long platformId) {
			this.platformId = platformId;
		}

		@Override
		public long getPlatformId() {
			return platformId;
		}

		@Override
		public boolean isPass() {
			return pass;
		}

		@Override
		public int getArrival(int table) {
			return arrivals[table];
		}

		@Override
		public int getDeparture(int table) {
			return -1;
		}

		@Override
		public boolean getOvertake(int table) {
			return false;
		}
	}
}
