package transitsim.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * When a train reaches and leaves each stop of a path, at the fastest it can run.
 */
public class StopTimes {

	private static final int MILLIS_PER_TICK = 50;

	private int firstIndex = -1;
	/**
	 * The path is a duty's, whose first route can be joined part of the way along, unlike the first route of a depot's path.
	 */
	boolean firstRouteJoined;
	private float firstArrival;
	private long plannedKey;
	/**
	 * The first rail of the path after the way round a loop that trains join part of the way round, or 0.
	 */
	private int joinIndex;
	private float joinArrival;
	/**
	 * The routes this path leaves part of the way along, and the last stop it runs of each one.
	 */
	final Map<Long, Integer> cutStops = new HashMap<>();
	/**
	 * Where a train joins a loop at the end of a trip, from the stop where it joins to the rail of the first stop of the loop.
	 */
	int tailJoinStart = -1;
	int tailJoinEnd;
	int tailLapEnd;
	final List<Stop> stops = new ArrayList<>();
	/**
	 * The stops on the way round a loop to its first stop, which are planned like the same stops of the lap.
	 */
	private final List<Stop> joinStops = new ArrayList<>();
	private final List<Stop> tailJoinStops = new ArrayList<>();
	private final Map<Integer, Integer> joinStopLaps = new HashMap<>();
	private final Map<Long, Map<Integer, RouteTimetable.StopPlan>> plans = new HashMap<>();
	/**
	 * Routes joined part of the way along: the stop where the train joins, and the fastest times from the first stop.
	 */
	final Map<Long, Integer> entryStops = new HashMap<>();
	final Map<Long, long[][]> entryTimes = new HashMap<>();

	/**
	 * Stop times of a path worked out elsewhere, for running services without a world.
	 *
	 * @param stops for each stop in order, its rail on the path, its platform, and its arrival and departure in ticks from leaving the siding
	 */
	public static StopTimes of(long[]... stops) {
		final StopTimes stopTimes = new StopTimes();
		for (int i = 0; i < stops.length; i++) {
			stopTimes.add((int) stops[i][0], stops[i][1], null, stops[i][2], stops[i][3], i == 0);
		}
		return stopTimes;
	}

	/**
	 * A route the path joins part of the way along, for running services without a world.
	 *
	 * @param times the fastest arrival and departure at each stop of the route up to that stop, from its first stop
	 */
	public void setEntry(long routeId, int stop, long[][] times) {
		entryStops.put(routeId, stop);
		entryTimes.put(routeId, times);
		plans.clear();
	}

	public void clear() {
		firstIndex = -1;
		firstArrival = 0;
		plannedKey = 0;
		joinIndex = 0;
		joinArrival = 0;
		stops.clear();
		joinStops.clear();
		tailJoinStops.clear();
		joinStopLaps.clear();
		plans.clear();
	}

	public void copyFrom(StopTimes stopTimes) {
		clear();
		firstIndex = stopTimes.firstIndex;
		firstArrival = stopTimes.firstArrival;
		firstRouteJoined = stopTimes.firstRouteJoined;
		joinIndex = stopTimes.joinIndex;
		joinArrival = stopTimes.joinArrival;
		cutStops.clear();
		cutStops.putAll(stopTimes.cutStops);
		entryStops.clear();
		entryStops.putAll(stopTimes.entryStops);
		entryTimes.clear();
		entryTimes.putAll(stopTimes.entryTimes);
		tailJoinStart = stopTimes.tailJoinStart;
		tailJoinEnd = stopTimes.tailJoinEnd;
		tailLapEnd = stopTimes.tailLapEnd;
		stops.addAll(stopTimes.stops);
		tailJoinStops.addAll(stopTimes.tailJoinStops);
		joinStops.addAll(stopTimes.joinStops);
		joinStopLaps.putAll(stopTimes.joinStopLaps);
	}

	/**
	 * See {@link #joinIndex}. Set before the stops are added.
	 */
	public void setJoinIndex(int joinIndex) {
		this.joinIndex = joinIndex;
	}

	/**
	 * Adds the next stop of the path, while the host works out its times.
	 *
	 * @param platformId   the platform, or 0 for a turn back rail
	 * @param turnBackRail the turn back rail, or null
	 * @param arrival      in ticks from leaving the siding
	 * @param departure    in ticks from leaving the siding
	 * @param isFirstStop  true if the train can start its service here
	 */
	public void add(int index, long platformId, UUID turnBackRail, float arrival, float departure, boolean isFirstStop) {
		if (index < joinIndex) {
			if (joinStops.isEmpty()) {
				joinArrival = arrival;
			}
			joinStops.add(new Stop(index, platformId, null, 0, 0));
			return;
		}
		if (index >= tailJoinStart && tailJoinStart >= 0 && index < tailJoinEnd) {
			tailJoinStops.add(new Stop(index, platformId, null, 0, 0));
			return;
		}
		if (firstIndex < 0 && isFirstStop) {
			firstIndex = index;
			firstArrival = arrival;
		}
		if (firstIndex >= 0) {
			stops.add(new Stop(index, platformId, turnBackRail, (long) ((arrival - firstArrival) * MILLIS_PER_TICK), (long) ((departure - firstArrival) * MILLIS_PER_TICK)));
		}
	}

	/**
	 * Goes back from the end of the first lap, as the stops of a joined loop are the last ones of the lap.
	 */
	public void linkJoinStops(int lapEndIndex) {
		linkJoinStops(joinStops, lapEndIndex);
		linkJoinStops(tailJoinStops, tailLapEnd);
	}

	private void linkJoinStops(List<Stop> newJoinStops, int lapEndIndex) {
		int lapStop = stops.size() - 1;
		while (lapStop >= 0 && stops.get(lapStop).index >= lapEndIndex) {
			lapStop--;
		}
		for (int i = newJoinStops.size() - 1; i >= 0; i--) {
			final Stop joinStop = newJoinStops.get(i);
			while (lapStop >= 0 && stops.get(lapStop).platformId != joinStop.platformId) {
				lapStop--;
			}
			if (lapStop >= 0) {
				joinStopLaps.put(joinStop.index, stops.get(lapStop).index);
				lapStop--;
			}
		}
	}

	public boolean isEmpty() {
		return firstIndex < 0;
	}

	/**
	 * @return true if trains join the loop part of the way round
	 */
	public boolean hasJoinStops() {
		return !joinStops.isEmpty() && joinStopLaps.containsKey(joinStops.get(0).index);
	}

	/**
	 * @return where in a lap the first stop a train reaches after joining the loop is, for the time of that stop
	 */
	public int getFirstJoinStopLapIndex() {
		return joinStops.isEmpty() ? firstIndex : joinStopLaps.getOrDefault(joinStops.get(0).index, firstIndex);
	}

	public int getFirstIndex() {
		return firstIndex;
	}

	/**
	 * @return the time needed to reach the first platform from the siding
	 */
	public long getLeadMillis() {
		return (long) ((hasJoinStops() ? joinArrival : firstArrival) * MILLIS_PER_TICK);
	}

	/**
	 * @return the time from the first stop to the last one
	 */
	public long getDurationMillis() {
		return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalMillis;
	}

	/**
	 * @param routes  the routes the path runs through, in order
	 * @param routeId the route the train is on
	 * @param table   the table of that route the train is running to. Other routes use their first table until the train gets to them.
	 * @param service the service of that route, for the stops where it runs differently from its table, or -1
	 * @return the planned times of a stop on the path, or null if the path has no such stop
	 */
	public RouteTimetable.StopPlan getPlan(List<? extends PlanRoute> routes, RouteTimetable timetable, int index, long routeId, int table, int service) {
		long newKey = 1;
		for (final PlanRoute route : routes) {
			newKey = newKey * 31 + route.getRouteId() + route.getTimetable().revision;
		}
		if (newKey != plannedKey) {
			plannedKey = newKey;
			plans.clear();
		}
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = getStopExceptions(routes, routeId, table, service);
		// Services with exceptions have their own plans; the rest of the table shares one
		final long planKey = (routeId * 31 + table) * 31 + (stopExceptions == null ? -1 : service);
		return plans.computeIfAbsent(planKey, key -> makePlans(routes, timetable, routeId, table, stopExceptions)).get(joinStopLaps.getOrDefault(index, index));
	}

	private static Map<Integer, ServiceExceptions.StopException> getStopExceptions(List<? extends PlanRoute> routes, long routeId, int table, int service) {
		if (service < 0) {
			return null;
		}
		for (final PlanRoute route : routes) {
			if (route.getRouteId() == routeId) {
				return route.getTimetable().getTable(table).exceptions.get(service);
			}
		}
		return null;
	}

	private Map<Integer, RouteTimetable.StopPlan> makePlans(List<? extends PlanRoute> routes, RouteTimetable timetable, long routeId, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final Map<Integer, RouteTimetable.StopPlan> newPlans = new HashMap<>();
		final List<RouteTimetable.StopPlan> stopPlans = new ArrayList<>();
		final List<Boolean> isTurnBacks = new ArrayList<>();
		matchRouteStops(routes, (stop, routeStop) -> {
			final PlanStop arrivalStop = routeStop == null ? null : routeStop.arrivalStop;
			final PlanStop departureStop = routeStop == null ? null : routeStop.departureStop;
			final int arrivalTable = routeStop != null && routeStop.arrivalRoute.getRouteId() == routeId ? table : 0;
			final int departureTable = routeStop != null && routeStop.departureRoute.getRouteId() == routeId ? table : 0;
			final RouteTimetable.StopPlan stopPlan = new RouteTimetable.StopPlan(stop.arrivalMillis, stop.departureMillis, arrivalStop == null ? -1 : arrivalStop.getArrival(arrivalTable), departureStop == null ? -1 : departureStop.getDeparture(departureTable), departureStop != null && departureStop.getOvertake(departureTable));
			if (stopExceptions != null && routeStop != null) {
				ServiceExceptions.applyTo(stopPlan, routeStop.arrivalRoute.getRouteId() == routeId ? stopExceptions.get(routeStop.arrivalIndex) : null, routeStop.departureRoute.getRouteId() == routeId ? stopExceptions.get(routeStop.departureIndex) : null);
			}
			stopPlan.startsRoute = routeStop != null && routeStop.startsRoute;
			if (stopPlan.startsRoute && routeStop.departureIndex > 0) {
				stopPlan.routeStartOffsetMillis = getEntryOffset(routeStop.departureRoute, routeStop.departureIndex, departureTable, routeStop.departureRoute.getRouteId() == routeId ? stopExceptions : null);
			}
			stopPlan.route = stopPlan.startsRoute ? routeStop.departureRoute : null;
			stopPlan.waitsForService = stopPlan.startsRoute && routeStop.departureRoute.getTimetable().isActive();
			stopPlans.add(stopPlan);
			isTurnBacks.add(stop.platformId == 0);
			newPlans.put(stop.index, stopPlan);
		});
		final PlanRoute currentRoute = routes.stream().filter(route -> route.getRouteId() == routeId).findFirst().orElse(null);
		// The extra time of the route the train is on, which is what the running sheet of that route shows
		(currentRoute == null ? timetable : currentRoute.getTimetable().getTable(table)).applyPlan(stopPlans);
		RouteTimetable.linkTurnBacks(stopPlans, isTurnBacks);
		return newPlans;
	}

	/**
	 * @return for a duty whose first segment joins its route part of the way along, the planned time from that route's first
	 * stop to where the train joins it
	 */
	public long getEntryOffsetMillis(PlanRoute route, int entryStop, int table, int service) {
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = service < 0 || table >= route.getTimetable().getTableCount() ? null : route.getTimetable().getTable(table).exceptions.get(service);
		return getEntryOffset(route, entryStop, table, stopExceptions);
	}

	/**
	 * A route joined part of the way along still runs to the times of its services, which count from its first stop.
	 *
	 * @return the planned time from the first stop of the route to the stop where the train joins it
	 */
	private long getEntryOffset(PlanRoute route, int entryStop, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final long[][] leadTimes = entryTimes.get(route.getRouteId());
		if (leadTimes == null || entryStop >= route.getStops().size()) {
			return 0;
		}
		final long[][] times = new long[route.getStops().size()][];
		for (int i = 0; i < times.length; i++) {
			times[i] = i < leadTimes.length ? leadTimes[i] : new long[]{-1, -1};
		}
		return RouteTimetable.getStopPlans(route.getStops(), times, route.getTimetable().getTable(table).marginPercent, table, stopExceptions).get(entryStop).arrivalMillis;
	}

	/**
	 * @param routes the routes this path runs through, in order
	 * @return the fastest arrival and departure at each stop of the route counted from reaching its first stop, or -1 for stops not found
	 */
	public long[][] getFastestTimes(PlanRoute route, List<? extends PlanRoute> routes) {
		final int stopCount = route.getStops().size();
		final long[][] times = new long[stopCount][];
		for (int i = 0; i < stopCount; i++) {
			times[i] = new long[]{-1, -1};
		}
		matchRouteStops(routes, (stop, routeStop) -> {
			if (routeStop != null && routeStop.departureRoute == route && times[routeStop.departureIndex][0] < 0) {
				times[routeStop.departureIndex] = new long[]{stop.arrivalMillis, stop.departureMillis};
			} else if (routeStop != null && routeStop.arrivalRoute == route && times[routeStop.arrivalIndex][0] < 0) {
				times[routeStop.arrivalIndex] = new long[]{stop.arrivalMillis, stop.arrivalMillis};
			}
		});

		long start = -1;
		for (final long[] stopTime : times) {
			if (stopTime[0] >= 0) {
				start = start < 0 ? stopTime[0] : start;
				stopTime[0] -= start;
				stopTime[1] -= start;
			}
		}
		return times;
	}

	/**
	 * @param routes the routes this path runs through, in order
	 * @return the turn back rails on this path, each after the last stop before it
	 */
	public List<TimetableConflicts.TurnBack> getTurnBacks(List<? extends PlanRoute> routes) {
		final List<Stop> pathStops = new ArrayList<>();
		final List<RouteStop> pathRouteStops = new ArrayList<>();
		matchRouteStops(routes, (stop, routeStop) -> {
			pathStops.add(stop);
			pathRouteStops.add(routeStop);
		});

		final List<TimetableConflicts.TurnBack> turnBacks = new ArrayList<>();
		for (int i = 1; i < pathStops.size(); i++) {
			final Stop stop = pathStops.get(i);
			final RouteStop lastRouteStop = pathRouteStops.get(i - 1);
			if (stop.turnBackRail != null && lastRouteStop != null) {
				final boolean startsRoute = i + 1 < pathStops.size() && pathRouteStops.get(i + 1) != null && pathRouteStops.get(i + 1).startsRoute;
				turnBacks.add(new TimetableConflicts.TurnBack(
						lastRouteStop.departureRoute.getRouteId(), stop.turnBackRail, lastRouteStop.departureIndex, startsRoute ? pathRouteStops.get(i + 1).departureRoute.getRouteId() : 0,
						stop.arrivalMillis - pathStops.get(i - 1).departureMillis, stop.departureMillis - stop.arrivalMillis, startsRoute ? pathStops.get(i + 1).arrivalMillis - stop.departureMillis : 0
				));
			}
		}
		return turnBacks;
	}

	/**
	 * Goes through the stops of the path, finding each one in the routes by platform in order. A platform where one
	 * route ends and the next one starts is one stop on the path, and gets its arrival and departure from different routes.
	 */
	private void matchRouteStops(List<? extends PlanRoute> routes, BiConsumer<Stop, RouteStop> consumer) {
		final List<RouteStop> routeStops = new ArrayList<>();
		PlanRoute lastRoute = null;
		for (final PlanRoute route : routes) {
			final int stopCount = cutStops.containsKey(route.getRouteId()) ? Math.min(cutStops.get(route.getRouteId()) + 1, route.getStops().size()) : route.getStops().size();
			// A route joined part of the way along starts at the stop where the train joins it
			// A duty's first segment can join its route part of the way along too
			final int firstStop = lastRoute == null && !firstRouteJoined ? 0 : Math.min(entryStops.getOrDefault(route.getRouteId(), 0), Math.max(0, stopCount - 1));
			for (int i = firstStop; i < stopCount; i++) {
				final PlanStop routePlatform = route.getStops().get(i);
				final RouteStop lastStop = routeStops.isEmpty() ? null : routeStops.get(routeStops.size() - 1);
				if (i == firstStop && lastStop != null && lastStop.arrivalRoute == lastRoute && lastStop.platformId == routePlatform.getPlatformId()) {
					routeStops.set(routeStops.size() - 1, new RouteStop(lastStop.arrivalRoute, lastStop.arrivalIndex, route, firstStop, true));
				} else {
					routeStops.add(new RouteStop(route, i, route, i, i == firstStop && lastRoute != null));
				}
			}
			lastRoute = route;
		}

		int nextRouteStop = 0;
		for (final Stop stop : stops) {
			RouteStop matchedStop = null;
			for (int i = nextRouteStop; stop.platformId != 0 && i < routeStops.size(); i++) {
				if (routeStops.get(i).platformId == stop.platformId) {
					matchedStop = routeStops.get(i);
					nextRouteStop = i + 1;
					break;
				}
			}
			consumer.accept(stop, matchedStop);
		}
	}

	static class Stop {

		private final int index;
		private final long platformId;
		private final UUID turnBackRail;
		final long arrivalMillis;
		final long departureMillis;

		private Stop(int index, long platformId, UUID turnBackRail, long arrivalMillis, long departureMillis) {
			this.index = index;
			this.platformId = platformId;
			this.turnBackRail = turnBackRail;
			this.arrivalMillis = arrivalMillis;
			this.departureMillis = departureMillis;
		}
	}

	private static class RouteStop {

		private final long platformId;
		private final PlanRoute arrivalRoute;
		private final int arrivalIndex;
		private final PlanRoute departureRoute;
		private final int departureIndex;
		private final PlanStop arrivalStop;
		private final PlanStop departureStop;
		private final boolean startsRoute;

		private RouteStop(PlanRoute arrivalRoute, int arrivalIndex, PlanRoute departureRoute, int departureIndex, boolean startsRoute) {
			this.arrivalRoute = arrivalRoute;
			this.arrivalIndex = arrivalIndex;
			this.departureRoute = departureRoute;
			this.departureIndex = departureIndex;
			this.startsRoute = startsRoute;
			arrivalStop = arrivalRoute.getStops().get(arrivalIndex);
			departureStop = departureRoute.getStops().get(departureIndex);
			platformId = arrivalStop.getPlatformId();
		}
	}
}
