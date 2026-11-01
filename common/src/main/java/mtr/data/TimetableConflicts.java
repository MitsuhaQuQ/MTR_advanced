package mtr.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds services that need the same platform at the same time, so a timetable that can't work shows up before trains run it.
 * Times repeat every day, so the check runs around midnight too. Depots with departures instead of a timetable are not part of it.
 */
public final class TimetableConflicts {

	/**
	 * The time between one train leaving a platform and the next one arriving, as trains have a length and need to clear it.
	 */
	public static final long MIN_PLATFORM_GAP_MILLIS = 30 * 1000;
	public static final int MAX_CONFLICTS = 256;

	private TimetableConflicts() {
	}

	/**
	 * @param routeId the route to look for conflicts with
	 * @param routes  the times of this route and every route that shares a platform with it
	 * @return the conflicts of this route, as many as {@link #MAX_CONFLICTS}
	 */
	public static List<Conflict> check(long routeId, List<RouteTimes> routes) {
		final Set<Long> platformIds = new HashSet<>();
		final Map<Long, RouteTimes> routeMap = new HashMap<>();
		routes.forEach(route -> {
			routeMap.put(route.routeId, route);
			if (route.routeId == routeId) {
				platformIds.addAll(route.platformIds);
			}
		});

		final Map<Long, List<Occupation>> occupations = new HashMap<>();
		final Set<ServiceStop> linkedStops = new HashSet<>();
		routes.forEach(route -> linkTurnBacks(route, routeMap.get(route.nextRouteId), platformIds, occupations, linkedStops));
		routes.forEach(route -> route.forEachStop((service, stopIndex, arrival, departure) -> {
			final ServiceStop stop = new ServiceStop(route.routeId, service, stopIndex);
			final long platformId = route.platformIds.get(stopIndex);
			if (platformIds.contains(platformId) && !linkedStops.contains(stop)) {
				occupations.computeIfAbsent(platformId, key -> new ArrayList<>()).add(new Occupation(arrival, departure, Collections.singletonList(stop)));
			}
		}));

		final List<Conflict> conflicts = new ArrayList<>();
		occupations.forEach((platformId, platformOccupations) -> findConflicts(routeId, platformId, platformOccupations, conflicts));
		conflicts.sort(Comparator.<Conflict>comparingInt(conflict -> conflict.service).thenComparingInt(conflict -> conflict.stopIndex));
		return conflicts.size() > MAX_CONFLICTS ? new ArrayList<>(conflicts.subList(0, MAX_CONFLICTS)) : conflicts;
	}

	/**
	 * A train turning back at the platform it arrived on stays there until its next service leaves, so whether the next
	 * train fits depends on when these two line up, not just on how often they run. Each train takes the first service
	 * nobody has taken, like {@link Depot#findNextService}.
	 */
	private static void linkTurnBacks(RouteTimes route, RouteTimes nextRoute, Set<Long> platformIds, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops) {
		if (nextRoute == null || route.platformIds.isEmpty() || nextRoute.platformIds.isEmpty()) {
			return;
		}
		final int lastIndex = route.platformIds.size() - 1;
		final long platformId = route.platformIds.get(lastIndex);
		if (platformId != nextRoute.platformIds.get(0) || !platformIds.contains(platformId) || route.arrivals[lastIndex] < 0 || nextRoute.departures[0] < 0) {
			return;
		}

		final List<Integer> nextServices = new ArrayList<>(nextRoute.services);
		Collections.sort(nextServices);
		final List<long[]> arrivals = new ArrayList<>();
		route.services.forEach(service -> arrivals.add(new long[]{Math.floorMod(service + route.arrivals[lastIndex], (long) Depot.MILLISECONDS_PER_DAY), service}));
		arrivals.sort(Comparator.comparingLong(arrival -> arrival[0]));

		for (final long[] arrival : arrivals) {
			final ServiceStop stop = new ServiceStop(route.routeId, (int) arrival[1], lastIndex);
			long bestWait = Long.MAX_VALUE;
			ServiceStop bestStop = null;
			for (final int nextService : nextServices) {
				final ServiceStop nextStop = new ServiceStop(nextRoute.routeId, nextService, 0);
				final long wait = Math.floorMod(nextService - arrival[0], (long) Depot.MILLISECONDS_PER_DAY);
				if (wait < bestWait && wait <= RouteTimetable.MAX_LAYOVER_MILLIS && !linkedStops.contains(nextStop) && !nextStop.equals(stop)) {
					bestWait = wait;
					bestStop = nextStop;
				}
			}
			if (bestStop != null) {
				linkedStops.add(stop);
				linkedStops.add(bestStop);
				final long leaving = arrival[0] + bestWait + nextRoute.departures[0] - nextRoute.arrivals[0];
				final List<ServiceStop> stops = new ArrayList<>();
				stops.add(stop);
				stops.add(bestStop);
				occupations.computeIfAbsent(platformId, key -> new ArrayList<>()).add(new Occupation(arrival[0], leaving, stops));
			}
		}
	}

	private static void findConflicts(long routeId, long platformId, List<Occupation> occupations, List<Conflict> conflicts) {
		occupations.sort(Comparator.comparingLong(occupation -> occupation.start));
		final int size = occupations.size();
		for (int i = 0; i < size && conflicts.size() < MAX_CONFLICTS; i++) {
			final Occupation occupation = occupations.get(i);
			for (int offset = 1; offset < size && conflicts.size() < MAX_CONFLICTS; offset++) {
				final Occupation otherOccupation = occupations.get((i + offset) % size);
				// Starts only grow from here, going past midnight into the next day
				if (otherOccupation.start + (i + offset < size ? 0 : Depot.MILLISECONDS_PER_DAY) >= occupation.end + MIN_PLATFORM_GAP_MILLIS) {
					break;
				}
				addConflict(routeId, platformId, occupation, otherOccupation, conflicts);
			}
		}
	}

	private static void addConflict(long routeId, long platformId, Occupation occupation1, Occupation occupation2, List<Conflict> conflicts) {
		if (Collections.disjoint(occupation1.stops, occupation2.stops)) {
			final ServiceStop stop1 = occupation1.getStop(routeId);
			final ServiceStop stop2 = occupation2.getStop(routeId);
			if (stop1 != null) {
				conflicts.add(new Conflict(stop1.stopIndex, stop1.service, occupation2.stops.get(0).routeId, occupation2.stops.get(0).service, platformId));
			} else if (stop2 != null) {
				conflicts.add(new Conflict(stop2.stopIndex, stop2.service, occupation1.stops.get(0).routeId, occupation1.stops.get(0).service, platformId));
			}
		}
	}

	@FunctionalInterface
	private interface StopConsumer {
		void accept(int service, int stopIndex, long arrival, long departure);
	}

	/**
	 * The planned times of a route, which are copied so they can be used away from the server thread.
	 */
	public static class RouteTimes {

		public final long routeId;
		/**
		 * The route the same train runs next.
		 */
		public long nextRouteId;
		private final List<Long> platformIds = new ArrayList<>();
		private final List<Integer> services;
		private final long[] arrivals;
		private final long[] departures;

		/**
		 * @param times the fastest arrival and departure at each stop, with -1 for stops not found
		 */
		public RouteTimes(long routeId, List<Route.RoutePlatform> stops, List<Integer> services, int marginPercent, long[][] times) {
			this.routeId = routeId;
			this.services = new ArrayList<>(services);
			stops.forEach(stop -> platformIds.add(stop.platformId));
			final List<RouteTimetable.StopPlan> plans = RouteTimetable.getStopPlans(stops, times, marginPercent);
			arrivals = new long[plans.size()];
			departures = new long[plans.size()];
			for (int i = 0; i < plans.size(); i++) {
				final boolean hasTime = times[i][0] >= 0;
				arrivals[i] = hasTime ? plans.get(i).arrivalMillis : -1;
				departures[i] = hasTime ? plans.get(i).departureMillis : -1;
			}
		}

		private void forEachStop(StopConsumer consumer) {
			services.forEach(service -> {
				for (int i = 0; i < arrivals.length; i++) {
					if (arrivals[i] >= 0) {
						final long arrival = Math.floorMod(service + arrivals[i], (long) Depot.MILLISECONDS_PER_DAY);
						consumer.accept(service, i, arrival, arrival + departures[i] - arrivals[i]);
					}
				}
			});
		}
	}

	public static class Conflict {

		/**
		 * The stop of the checked route where this happens.
		 */
		public final int stopIndex;
		public final int service;
		public final long otherRouteId;
		public final int otherService;
		public final long platformId;

		public Conflict(int stopIndex, int service, long otherRouteId, int otherService, long platformId) {
			this.stopIndex = stopIndex;
			this.service = service;
			this.otherRouteId = otherRouteId;
			this.otherService = otherService;
			this.platformId = platformId;
		}
	}

	/**
	 * What came back from a check.
	 */
	public static class Result {

		public final List<Conflict> conflicts;
		/**
		 * Routes on the same platforms that have no times yet, so they could not be checked.
		 */
		public final int uncheckedRoutes;

		public Result(List<Conflict> conflicts, int uncheckedRoutes) {
			this.conflicts = conflicts;
			this.uncheckedRoutes = uncheckedRoutes;
		}
	}

	private static class Occupation {

		private final long start;
		private final long end;
		private final List<ServiceStop> stops;

		private Occupation(long start, long end, List<ServiceStop> stops) {
			this.start = start;
			this.end = end;
			this.stops = stops;
		}

		private ServiceStop getStop(long routeId) {
			return stops.stream().filter(stop -> stop.routeId == routeId).findFirst().orElse(null);
		}
	}

	private static class ServiceStop {

		private final long routeId;
		private final int service;
		private final int stopIndex;

		private ServiceStop(long routeId, int service, int stopIndex) {
			this.routeId = routeId;
			this.service = service;
			this.stopIndex = stopIndex;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof ServiceStop)) {
				return false;
			}
			final ServiceStop stop = (ServiceStop) object;
			return routeId == stop.routeId && service == stop.service && stopIndex == stop.stopIndex;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, service, stopIndex);
		}
	}
}
