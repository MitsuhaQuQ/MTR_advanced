package transitsim.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fixed copies of routes as they were at one revision of their timetable, so a train keeps running a service by the plan it
 * started it with while the timetable, its exceptions or the route's stops are edited, and changes over at the next service
 * (S3d, fix R04). Trains running the same revision share one copy; a copy no train holds any more is let go.
 */
public final class PlanVersions {

	/**
	 * The newest copy of each route, by the host's route object.
	 */
	private static final Map<PlanRoute, FixedRoute> ROUTES = new WeakHashMap<>();
	private static final Map<RouteTimetable, RouteTimetable> TIMETABLES = new WeakHashMap<>();

	private PlanVersions() {
	}

	/**
	 * @return a copy of the route at its current revision, which edits to it don't change
	 */
	public static synchronized PlanRoute fix(PlanRoute route) {
		if (route instanceof FixedRoute) {
			return route;
		}
		final FixedRoute fixedRoute = ROUTES.get(route);
		if (fixedRoute != null && fixedRoute.revision == route.getTimetable().revision) {
			return fixedRoute;
		}
		final FixedRoute newFixedRoute = new FixedRoute(route);
		ROUTES.put(route, newFixedRoute);
		return newFixedRoute;
	}

	/**
	 * @return a copy of the timetable at its current revision, which edits to it don't change
	 */
	public static synchronized RouteTimetable fix(RouteTimetable timetable) {
		final RouteTimetable fixedTimetable = TIMETABLES.get(timetable);
		if (fixedTimetable != null && fixedTimetable.revision == timetable.revision) {
			return fixedTimetable;
		}
		final RouteTimetable newFixedTimetable = timetable.copy();
		TIMETABLES.put(timetable, newFixedTimetable);
		return newFixedTimetable;
	}

	public static List<PlanRoute> fix(List<? extends PlanRoute> routes) {
		final List<PlanRoute> fixedRoutes = new ArrayList<>();
		routes.forEach(route -> fixedRoutes.add(fix(route)));
		return fixedRoutes;
	}

	private static class FixedRoute implements PlanRoute {

		private final long routeId;
		private final int revision;
		private final RouteTimetable timetable;
		private final List<FixedStop> stops = new ArrayList<>();
		private final boolean isLoop;
		private final int transportModeId;
		private final List<Long> terminalPlatforms;
		private final List<Long> startPlatforms;

		private FixedRoute(PlanRoute route) {
			terminalPlatforms = new ArrayList<>(route.getTerminalPlatforms());
			startPlatforms = new ArrayList<>(route.getStartPlatforms());
			routeId = route.getRouteId();
			timetable = route.getTimetable().copy();
			revision = timetable.revision;
			final int tableCount = timetable.getTableCount();
			route.getStops().forEach(stop -> stops.add(new FixedStop(stop, tableCount)));
			isLoop = route.isLoop();
			transportModeId = route.getTransportModeId();
		}

		@Override
		public long getRouteId() {
			return routeId;
		}

		@Override
		public RouteTimetable getTimetable() {
			return timetable;
		}

		@Override
		public List<? extends PlanStop> getStops() {
			return Collections.unmodifiableList(stops);
		}

		@Override
		public boolean isLoop() {
			return isLoop;
		}

		@Override
		public int getTransportModeId() {
			return transportModeId;
		}

		@Override
		public List<Long> getTerminalPlatforms() {
			return terminalPlatforms;
		}

		@Override
		public List<Long> getStartPlatforms() {
			return startPlatforms;
		}
	}

	private static class FixedStop implements PlanStop {

		private final long platformId;
		private final boolean pass;
		private final int[] arrivals;
		private final int[] departures;
		private final boolean[] overtakes;

		private FixedStop(PlanStop stop, int tableCount) {
			platformId = stop.getPlatformId();
			pass = stop.isPass();
			arrivals = new int[tableCount];
			departures = new int[tableCount];
			overtakes = new boolean[tableCount];
			for (int i = 0; i < tableCount; i++) {
				arrivals[i] = stop.getArrival(i);
				departures[i] = stop.getDeparture(i);
				overtakes[i] = stop.getOvertake(i);
			}
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
			return table >= 0 && table < arrivals.length ? arrivals[table] : -1;
		}

		@Override
		public int getDeparture(int table) {
			return table >= 0 && table < departures.length ? departures[table] : -1;
		}

		@Override
		public boolean getOvertake(int table) {
			return table >= 0 && table < overtakes.length && overtakes[table];
		}
	}
}
