package transitsim.plan;

import java.util.ArrayList;
import java.util.Collections;
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
	 * For a duty's path, the same by segment (D4): a route can be in several segments, each cut and joined at its own stops.
	 * Used instead of the ones by route when {@link #segmentsKnown}.
	 */
	final Map<Integer, Integer> segmentCuts = new HashMap<>();
	final Map<Integer, Integer> segmentEntries = new HashMap<>();
	final Map<Integer, long[][]> segmentEntryTimes = new HashMap<>();
	/**
	 * For a segment joined part of the way along on a path turned back early (D4), the stop times of the usual path, whose
	 * same segment runs from further back: the times to where it joins are worked out from them when the plan is made.
	 */
	final Map<Integer, StopTimes> segmentLeadSources = new HashMap<>();
	boolean segmentsKnown;

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
		segmentCuts.clear();
		segmentCuts.putAll(stopTimes.segmentCuts);
		segmentEntries.clear();
		segmentEntries.putAll(stopTimes.segmentEntries);
		segmentEntryTimes.clear();
		segmentEntryTimes.putAll(stopTimes.segmentEntryTimes);
		segmentLeadSources.clear();
		segmentLeadSources.putAll(stopTimes.segmentLeadSources);
		segmentsKnown = stopTimes.segmentsKnown;
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
	 * @return the fastest time from leaving one stop of the path to reaching a later one, by their rails, or -1 if either
	 * isn't a stop of it
	 */
	public long getRunMillis(int fromIndex, int toIndex) {
		Stop from = null;
		Stop to = null;
		for (final Stop stop : stops) {
			if (stop.index == fromIndex) {
				from = stop;
			} else if (stop.index == toIndex) {
				to = stop;
			}
		}
		return from == null || to == null ? -1 : Math.max(0, to.arrivalMillis - from.departureMillis);
	}

	/**
	 * @return the fastest time running from leaving one stop of the path to reaching a later one, leaving out the time it
	 * stands at the stops between, such as a turn back rail (D1); -1 if either isn't a stop of it
	 */
	public long getMovingMillis(int fromIndex, int toIndex) {
		Stop from = null;
		Stop to = null;
		long standing = 0;
		for (final Stop stop : stops) {
			if (stop.index == fromIndex) {
				from = stop;
			} else if (stop.index == toIndex) {
				to = stop;
			} else if (stop.index > fromIndex && stop.index < toIndex) {
				standing += stop.departureMillis - stop.arrivalMillis;
			}
		}
		return from == null || to == null ? -1 : Math.max(0, to.arrivalMillis - from.departureMillis - standing);
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
				stopPlan.routeStartOffsetMillis = getEntryOffset(routes, routeStop.departureRoute, routeStop.departureSegment, routeStop.departureIndex, departureTable, routeStop.departureRoute.getRouteId() == routeId ? stopExceptions : null);
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
		return getEntryOffset(null, route, 0, entryStop, table, stopExceptions);
	}

	/**
	 * A route joined part of the way along still runs to the times of its services, which count from its first stop.
	 *
	 * @return the planned time from the first stop of the route to the stop where the train joins it
	 */
	private long getEntryOffset(List<? extends PlanRoute> routes, PlanRoute route, int segment, int entryStop, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final long[][] leadTimes = segmentsKnown ? getSegmentLead(segment, routes) : entryTimes.get(route.getRouteId());
		if (leadTimes == null || entryStop >= route.getStops().size()) {
			return 0;
		}
		final long[][] times = new long[route.getStops().size()][];
		for (int i = 0; i < times.length; i++) {
			times[i] = i < leadTimes.length ? leadTimes[i] : new long[]{-1, -1};
		}
		return StopRules.plan(route.getStops(), times, route.getTimetable().getTable(table).marginPercent, table, stopExceptions).get(entryStop).arrivalMillis;
	}

	/**
	 * @param routes the routes this path runs through, in order
	 * @return the fastest arrival and departure at each stop of the route counted from reaching its first stop, or -1 for stops not found
	 */
	public long[][] getFastestTimes(PlanRoute route, List<? extends PlanRoute> routes) {
		final long[][] times = matchFastestTimes(route, routes);
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
	 * Like {@link #getFastestTimes}, for a path that starts standing at the route's first stop, as the route's own path
	 * does when no depot runs it on its path. That stop isn't one of the path's stops, as the train doesn't arrive there,
	 * so it is left at the start of the path and the times count from there. Counted from the first stop the path has
	 * instead, the run to the second stop would be lost and every stop after the first would come too early.
	 */
	public long[][] getFastestTimesFromFirstStop(PlanRoute route) {
		final long[][] times = matchFastestTimes(route, Collections.singletonList(route));
		if (times.length == 0 || times[0][0] >= 0) {
			return getFastestTimes(route, Collections.singletonList(route));
		}
		// The stops count from reaching the first one the path has, which is this long after the start of the path
		final long reachedMillis = firstIndex < 0 ? 0 : (long) (firstArrival * MILLIS_PER_TICK);
		for (final long[] stopTime : times) {
			if (stopTime[0] >= 0) {
				stopTime[0] += reachedMillis;
				stopTime[1] += reachedMillis;
			}
		}
		times[0] = new long[]{0, 0};
		return times;
	}

	/**
	 * Like {@link #getFastestTimes}, for one segment of a duty's path (D4), whose route may be in other segments as well:
	 * the arrival and departure from the start of the path, and the rail, of each stop of that segment's route, or -1 for
	 * the stops it doesn't run.
	 */
	public long[][] getSegmentTimes(int segment, List<? extends PlanRoute> routes) {
		if (segment < 0 || segment >= routes.size()) {
			return new long[0][];
		}
		final int stopCount = routes.get(segment).getStops().size();
		final long[][] times = new long[stopCount][];
		for (int i = 0; i < stopCount; i++) {
			times[i] = new long[]{-1, -1, -1};
		}
		matchRouteStops(routes, (stop, routeStop) -> {
			if (routeStop != null && routeStop.departureSegment == segment && times[routeStop.departureIndex][0] < 0) {
				times[routeStop.departureIndex] = new long[]{stop.arrivalMillis, stop.departureMillis, stop.index};
			} else if (routeStop != null && routeStop.arrivalSegment == segment && times[routeStop.arrivalIndex][0] < 0) {
				times[routeStop.arrivalIndex] = new long[]{stop.arrivalMillis, stop.arrivalMillis, stop.index};
			}
		});
		return times;
	}

	/**
	 * @return the fastest times from a segment's route's first stop to each stop up to where the segment joins it: the
	 * path's own, or worked out from the usual path a path turned back early took it from (D4); or null
	 */
	private long[][] getSegmentLead(int segment, List<? extends PlanRoute> routes) {
		final long[][] own = segmentEntryTimes.get(segment);
		final StopTimes source = segmentLeadSources.get(segment);
		if (own != null || source == null || routes == null) {
			return own;
		}
		final long[][] times = source.getSegmentTimes(segment, routes);
		final int from = Math.max(0, source.getSegmentEntry(segment));
		final long[][] sourceLead = from > 0 ? source.getSegmentLead(segment, routes) : null;
		if (from >= times.length || times[from][0] < 0 || from > 0 && (sourceLead == null || sourceLead.length <= from)) {
			return null;
		}
		final long start = from > 0 ? sourceLead[from][0] : 0;
		final long[][] lead = new long[times.length][];
		for (int i = 0; i < times.length; i++) {
			if (i < from) {
				lead[i] = sourceLead[i];
			} else if (times[i][0] >= 0) {
				lead[i] = new long[]{start + times[i][0] - times[from][0], start + times[i][1] - times[from][0]};
			} else {
				lead[i] = new long[]{-1, -1};
			}
		}
		return lead;
	}

	/**
	 * @return for a duty's path, the stop of the segment's route where it joins it, or -1 if it isn't known by segment
	 */
	public int getSegmentEntry(int segment) {
		return segmentsKnown ? segmentEntries.getOrDefault(segment, 0) : -1;
	}

	/**
	 * @return for a duty's path, the times from the route's first stop to the stop where the segment joins it, or null
	 */
	public long[][] getSegmentEntryTimes(int segment) {
		return segmentsKnown ? segmentEntryTimes.get(segment) : null;
	}

	private long[][] matchFastestTimes(PlanRoute route, List<? extends PlanRoute> routes) {
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
		for (int segment = 0; segment < routes.size(); segment++) {
			final PlanRoute route = routes.get(segment);
			// A duty's path says where each segment is cut and joined (D4), as one route can be in several segments
			final Integer cut = segmentsKnown ? segmentCuts.get(segment) : cutStops.get(route.getRouteId());
			final int stopCount = cut != null ? Math.min(cut + 1, route.getStops().size()) : route.getStops().size();
			// A route joined part of the way along starts at the stop where the train joins it
			// A duty's first segment can join its route part of the way along too
			final int entry = segmentsKnown ? segmentEntries.getOrDefault(segment, 0) : entryStops.getOrDefault(route.getRouteId(), 0);
			final int firstStop = lastRoute == null && !firstRouteJoined ? 0 : Math.min(entry, Math.max(0, stopCount - 1));
			for (int i = firstStop; i < stopCount; i++) {
				final PlanStop routePlatform = route.getStops().get(i);
				final RouteStop lastStop = routeStops.isEmpty() ? null : routeStops.get(routeStops.size() - 1);
				if (i == firstStop && lastStop != null && lastStop.arrivalRoute == lastRoute && lastStop.platformId == routePlatform.getPlatformId()) {
					routeStops.set(routeStops.size() - 1, new RouteStop(lastStop.arrivalRoute, lastStop.arrivalSegment, lastStop.arrivalIndex, route, segment, firstStop, true));
				} else {
					routeStops.add(new RouteStop(route, segment, i, route, segment, i, i == firstStop && lastRoute != null));
				}
			}
			lastRoute = route;
		}

		int nextRouteStop = 0;
		for (final Stop stop : stops) {
			RouteStop matchedStop = null;
			// A train turning back at another platform of the last stop's station (站前折返) is at the last stop of the route
			// there, and at the first stop of the next route too when that starts in the same station
			if (stop.platformId != 0 && nextRouteStop < routeStops.size()) {
				final RouteStop expected = routeStops.get(nextRouteStop);
				final List<? extends PlanStop> arrivalStops = expected.arrivalRoute.getStops();
				if (expected.platformId != stop.platformId && expected.arrivalIndex == arrivalStops.size() - 1 && expected.arrivalRoute.getTerminalPlatforms().contains(stop.platformId)) {
					final RouteStop next = nextRouteStop + 1 < routeStops.size() ? routeStops.get(nextRouteStop + 1) : null;
					if (expected.departureRoute == expected.arrivalRoute && next != null && next.startsRoute) {
						matchedStop = new RouteStop(expected.arrivalRoute, expected.arrivalSegment, expected.arrivalIndex, next.departureRoute, next.departureSegment, next.departureIndex, true);
						nextRouteStop += 2;
					} else {
						matchedStop = expected;
						nextRouteStop++;
					}
				}
			}
			for (int i = nextRouteStop; matchedStop == null && stop.platformId != 0 && i < routeStops.size(); i++) {
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
		/**
		 * Which of the routes of the path, in order, it is a stop of: for a duty, its segment.
		 */
		private final int arrivalSegment;
		private final int arrivalIndex;
		private final PlanRoute departureRoute;
		private final int departureSegment;
		private final int departureIndex;
		private final PlanStop arrivalStop;
		private final PlanStop departureStop;
		private final boolean startsRoute;

		private RouteStop(PlanRoute arrivalRoute, int arrivalSegment, int arrivalIndex, PlanRoute departureRoute, int departureSegment, int departureIndex, boolean startsRoute) {
			this.arrivalRoute = arrivalRoute;
			this.arrivalSegment = arrivalSegment;
			this.arrivalIndex = arrivalIndex;
			this.departureRoute = departureRoute;
			this.departureSegment = departureSegment;
			this.departureIndex = departureIndex;
			this.startsRoute = startsRoute;
			arrivalStop = arrivalRoute.getStops().get(arrivalIndex);
			departureStop = departureRoute.getStops().get(departureIndex);
			platformId = arrivalStop.getPlatformId();
		}
	}
}
