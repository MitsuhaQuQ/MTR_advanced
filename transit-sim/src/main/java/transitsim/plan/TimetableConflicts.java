package transitsim.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Finds services that need the same platform at the same time, so a timetable that can't work shows up before trains run it.
 * Each day of the week is checked with the tables running that day, and times run around midnight into the same day's tables.
 * Depots with departures instead of a timetable are not part of it.
 */
public final class TimetableConflicts {

	/**
	 * The time between one train leaving a platform and the next one arriving, as trains have a length and need to clear it.
	 */
	public static final long MIN_PLATFORM_GAP_MILLIS = 30 * 1000;
	/**
	 * How long a train takes through a station's throat, before reaching its platform and after leaving it: an
	 * approximation, as the times of a route are only known at its stops (L2).
	 */
	public static final long THROAT_MILLIS = 30 * 1000;
	public static final int MAX_CONFLICTS = 256;

	private static final int DAYS_PER_WEEK = 7;

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
				platformIds.addAll(route.terminalPlatforms);
			}
		});

		final Set<UUID> turnBackRails = new HashSet<>();
		routes.forEach(route -> route.turnBacks.forEach(turnBack -> {
			if (route.routeId == routeId || turnBack.nextRouteId == routeId) {
				turnBackRails.addAll(turnBack.candidates);
			}
		}));

		final Set<Conflict> conflicts = new LinkedHashSet<>();
		final Set<String> checkedDays = new HashSet<>();
		for (int weekday = 0; weekday < DAYS_PER_WEEK; weekday++) {
			final int day = weekday;
			// Days running the same tables have the same conflicts
			if (checkedDays.add(routes.stream().map(route -> route.getTableKey(day)).collect(Collectors.joining(",")))) {
				checkDay(routeId, routes, routeMap, platformIds, day, conflicts);
				checkTurnBacks(routeId, routes, routeMap, turnBackRails, day, conflicts);
				checkThroats(routeId, routes, day, conflicts);
				checkHeadways(routeMap.get(routeId), day, conflicts);
			}
		}

		final List<Conflict> sortedConflicts = new ArrayList<>(conflicts);
		sortedConflicts.sort(Comparator.<Conflict>comparingInt(conflict -> conflict.table).thenComparingInt(conflict -> conflict.service).thenComparingInt(conflict -> conflict.stopIndex));
		return sortedConflicts;
	}

	/**
	 * Services of the route running closer behind each other than trains can (B, 2026-11-14): each pair one after the other
	 * from its first stop, against the least time between them from each stop to the next ({@link RouteTimes#setHeadways},
	 * worked out by the host with {@code transitsim.run.Headways} for the vehicles running them). One conflict for each
	 * pair, where it is shortest of what is needed; told only, the times stay as they are (user's choice).
	 */
	private static void checkHeadways(RouteTimes route, int weekday, Set<Conflict> conflicts) {
		if (route == null || route.headways.isEmpty()) {
			return;
		}
		final List<ServiceTimes> services = new ArrayList<>();
		for (final ServiceTimes serviceTimes : route.getServices(weekday)) {
			if (firstDeparture(serviceTimes) >= 0) {
				services.add(serviceTimes);
			}
		}
		services.sort(Comparator.comparingLong(serviceTimes -> serviceTimes.service + firstDeparture(serviceTimes)));
		for (int i = 0; i + 1 < services.size(); i++) {
			final ServiceTimes ahead = services.get(i);
			final ServiceTimes behind = services.get(i + 1);
			final long[] needed = route.getHeadways(ahead.service, behind.service);
			if (needed == null) {
				continue;
			}
			int worstStop = -1;
			long worstShort = 0;
			long worstGap = 0;
			for (int stop = 0; stop < needed.length && stop < ahead.departures.length && stop < behind.departures.length; stop++) {
				if (needed[stop] <= 0 || ahead.departures[stop] < 0 || behind.departures[stop] < 0) {
					continue;
				}
				final long gap = behind.service + behind.departures[stop] - ahead.service - ahead.departures[stop];
				if (needed[stop] - gap > worstShort) {
					worstShort = needed[stop] - gap;
					worstStop = stop;
					worstGap = gap;
				}
			}
			if (worstStop >= 0 && conflicts.size() < MAX_CONFLICTS) {
				conflicts.add(new Conflict(behind.table, worstStop, behind.service, route.routeId, ahead.service, 0, 0, worstGap, needed[worstStop]));
			}
		}
	}

	private static long firstDeparture(ServiceTimes serviceTimes) {
		for (final long departure : serviceTimes.departures) {
			if (departure >= 0) {
				return departure;
			}
		}
		return -1;
	}

	private static void checkDay(long routeId, List<RouteTimes> routes, Map<Long, RouteTimes> routeMap, Set<Long> platformIds, int weekday, Set<Conflict> conflicts) {
		final Map<Long, List<Occupation>> occupations = new HashMap<>();
		final Set<ServiceStop> linkedStops = new HashSet<>();
		// Trains turning back at one of several platforms of a station (站前折返), which take one once the others are known
		final List<Occupation> choosingOccupations = new ArrayList<>();
		// Pairs known for sure first, set by the player or one after the other in a duty (TerminalPairs), then the nearest
		routes.forEach(route -> linkFixedTurnBacks(route, routeMap, weekday, platformIds, occupations, linkedStops, choosingOccupations));
		routes.forEach(route -> {
			// A train going by way of a turn back rail leaves the platform in between
			if (route.turnBacks.stream().noneMatch(turnBack -> turnBack.nextRouteId == route.nextRouteId)) {
				linkTurnBacks(route, routeMap.get(route.nextRouteId), weekday, platformIds, occupations, linkedStops, choosingOccupations);
			}
		});
		routes.forEach(route -> route.getServices(weekday).forEach(serviceTimes -> {
			for (int i = 0; i < serviceTimes.arrivals.length; i++) {
				final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, i);
				final long platformId = route.platformIds.get(i);
				if (serviceTimes.arrivals[i] >= 0 && platformIds.contains(platformId) && !linkedStops.contains(stop)) {
					final long arrival = serviceTimes.getArrival(i);
					occupations.computeIfAbsent(platformId, key -> new ArrayList<>()).add(new Occupation(arrival, arrival + serviceTimes.departures[i] - serviceTimes.arrivals[i], Collections.singletonList(stop)));
				}
			}
		}));
		// Each takes the first of its platforms free for as long as it stays, in the order they arrive; with none free, it
		// stays on the first, which then conflicts
		choosingOccupations.sort(Comparator.comparingLong(occupation -> occupation.start));
		for (final Occupation occupation : choosingOccupations) {
			long chosenPlatform = occupation.platforms.get(0);
			for (final long platformId : occupation.platforms) {
				if (findOverlap(occupation, occupations.getOrDefault(platformId, Collections.emptyList())) == null) {
					chosenPlatform = platformId;
					break;
				}
			}
			occupations.computeIfAbsent(chosenPlatform, key -> new ArrayList<>()).add(occupation);
		}
		occupations.forEach((platformId, platformOccupations) -> findConflicts(routeId, platformId, platformOccupations, conflicts));
	}

	/**
	 * A train turning back at the platform it arrived on stays there until its next service leaves, so whether the next
	 * train fits depends on when these two line up, not just on how often they run. Each train takes the first service
	 * nobody has taken, like a depot finding its next service.
	 */
	private static void linkTurnBacks(RouteTimes route, RouteTimes nextRoute, int weekday, Set<Long> platformIds, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops, List<Occupation> choosingOccupations) {
		if (nextRoute == null || route.platformIds.isEmpty() || nextRoute.platformIds.isEmpty()) {
			return;
		}
		final int lastIndex = route.platformIds.size() - 1;
		final long platformId = route.platformIds.get(lastIndex);
		if (platformId != nextRoute.platformIds.get(0) || !platformIds.contains(platformId)) {
			return;
		}

		final List<ServiceTimes> services = route.getServices(weekday).stream().filter(serviceTimes -> serviceTimes.arrivals[lastIndex] >= 0).collect(Collectors.toList());
		services.sort(Comparator.comparingLong(serviceTimes -> serviceTimes.getArrival(lastIndex)));
		final List<ServiceTimes> nextServices = nextRoute.getServices(weekday).stream().filter(serviceTimes -> serviceTimes.departures[0] >= 0).collect(Collectors.toList());

		for (final ServiceTimes serviceTimes : services) {
			final long arrival = serviceTimes.getArrival(lastIndex);
			final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, lastIndex);
			// Already paired for sure
			if (linkedStops.contains(stop)) {
				continue;
			}
			final ServiceTimes nextServiceTimes = findNextService(nextRoute, nextServices, arrival, stop, linkedStops);
			if (nextServiceTimes != null) {
				addTurnBackStay(route, platformId, serviceTimes, nextRoute, nextServiceTimes, occupations, linkedStops, choosingOccupations);
			}
		}
	}

	/**
	 * Trains whose next service is known for sure ({@link RouteTimes#fixedNext}) and leaves from the platform they arrive at
	 * stay there until it leaves, whichever route it is of.
	 */
	private static void linkFixedTurnBacks(RouteTimes route, Map<Long, RouteTimes> routeMap, int weekday, Set<Long> platformIds, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops, List<Occupation> choosingOccupations) {
		if (route.fixedNext.isEmpty() || route.platformIds.isEmpty()) {
			return;
		}
		final int lastIndex = route.platformIds.size() - 1;
		final long platformId = route.platformIds.get(lastIndex);
		if (!platformIds.contains(platformId)) {
			return;
		}
		for (final ServiceTimes serviceTimes : route.getServices(weekday)) {
			final long[] next = route.fixedNext.get(serviceTimes.service);
			final RouteTimes nextRoute = next == null ? null : routeMap.get(next[0]);
			if (serviceTimes.arrivals[lastIndex] < 0 || nextRoute == null || nextRoute.platformIds.isEmpty() || nextRoute.platformIds.get(0) != platformId) {
				continue;
			}
			final ServiceTimes nextServiceTimes = nextRoute.getServices(weekday).stream().filter(other -> other.service == (int) next[1] && other.departures[0] >= 0).findFirst().orElse(null);
			final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, lastIndex);
			if (nextServiceTimes != null && !linkedStops.contains(stop)) {
				addTurnBackStay(route, platformId, serviceTimes, nextRoute, nextServiceTimes, occupations, linkedStops, choosingOccupations);
			}
		}
	}

	/**
	 * A train staying at its last stop's platform from its arrival until its next service leaves; with platforms to turn
	 * back at in turn (站前折返), it takes the first one free once the others are known.
	 */
	private static void addTurnBackStay(RouteTimes route, long platformId, ServiceTimes serviceTimes, RouteTimes nextRoute, ServiceTimes nextServiceTimes, Map<Long, List<Occupation>> occupations, Set<ServiceStop> linkedStops, List<Occupation> choosingOccupations) {
		final int lastIndex = route.platformIds.size() - 1;
		final long arrival = serviceTimes.getArrival(lastIndex);
		final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, lastIndex);
		final ServiceStop nextStop = new ServiceStop(nextRoute.routeId, nextServiceTimes.table, nextServiceTimes.service, 0);
		linkedStops.add(stop);
		linkedStops.add(nextStop);
		final long wait = Math.floorMod(nextServiceTimes.service - arrival, (long) OperatingDay.MILLIS_PER_DAY);
		final long leaving = arrival + wait + nextServiceTimes.departures[0] - nextServiceTimes.arrivals[0];
		final List<ServiceStop> stops = new ArrayList<>();
		stops.add(stop);
		stops.add(nextStop);
		if (route.terminalPlatforms.isEmpty()) {
			occupations.computeIfAbsent(platformId, key -> new ArrayList<>()).add(new Occupation(arrival, leaving, stops));
		} else {
			final List<Long> platforms = new ArrayList<>();
			platforms.add(platformId);
			platforms.addAll(route.terminalPlatforms);
			choosingOccupations.add(new Occupation(arrival, leaving, stops, Collections.emptyList(), platforms));
		}
	}

	/**
	 * A train stays on a turn back rail until it is time to run to the first stop of its next service, so like a platform
	 * where a train turns back, what matters is how the two routes line up. The platforms of these services are used as usual.
	 * A train with more than one turn back rail takes the first one free when it gets there, so it only conflicts when every
	 * one of them is taken.
	 */
	private static void checkTurnBacks(long routeId, List<RouteTimes> routes, Map<Long, RouteTimes> routeMap, Set<UUID> turnBackRails, int weekday, Set<Conflict> conflicts) {
		final Map<UUID, List<Occupation>> occupations = new HashMap<>();
		final List<Occupation> choosingOccupations = new ArrayList<>();
		final Set<ServiceStop> linkedStops = new HashSet<>();
		routes.forEach(route -> route.turnBacks.forEach(turnBack -> {
			if (turnBack.candidates.stream().anyMatch(turnBackRails::contains)) {
				addTurnBack(route, turnBack, routeMap.get(turnBack.nextRouteId), weekday, turnBack.candidates.size() > 1 ? choosingOccupations : occupations.computeIfAbsent(turnBack.rail, key -> new ArrayList<>()), linkedStops);
			}
		}));
		occupations.values().forEach(railOccupations -> findConflicts(routeId, 0, railOccupations, conflicts));

		choosingOccupations.sort(Comparator.comparingLong(occupation -> occupation.start));
		for (final Occupation occupation : choosingOccupations) {
			Occupation firstOverlap = null;
			UUID freeRail = null;
			for (final UUID rail : occupation.candidates) {
				final Occupation overlap = findOverlap(occupation, occupations.getOrDefault(rail, Collections.emptyList()));
				if (overlap == null) {
					freeRail = rail;
					break;
				} else if (firstOverlap == null) {
					firstOverlap = overlap;
				}
			}
			if (freeRail != null) {
				occupations.computeIfAbsent(freeRail, key -> new ArrayList<>()).add(occupation);
			} else if (firstOverlap != null && conflicts.size() < MAX_CONFLICTS) {
				addConflict(routeId, 0, occupation, firstOverlap, conflicts);
			}
		}
	}

	/**
	 * @return an occupation too close to this one, including across midnight, or null if there is none
	 */
	private static Occupation findOverlap(Occupation occupation, List<Occupation> otherOccupations) {
		for (final Occupation otherOccupation : otherOccupations) {
			for (int day = -1; day <= 1; day++) {
				final long offset = day * OperatingDay.MILLIS_PER_DAY;
				if (otherOccupation.start + offset < occupation.end + MIN_PLATFORM_GAP_MILLIS && occupation.start < otherOccupation.end + offset + MIN_PLATFORM_GAP_MILLIS) {
					return otherOccupation;
				}
			}
		}
		return null;
	}

	private static void addTurnBack(RouteTimes route, TurnBack turnBack, RouteTimes nextRoute, int weekday, List<Occupation> occupations, Set<ServiceStop> linkedStops) {
		final List<ServiceTimes> services = route.getServices(weekday).stream().filter(serviceTimes -> serviceTimes.departures[turnBack.stopIndex] >= 0).collect(Collectors.toList());
		services.sort(Comparator.comparingLong(turnBack::getArrival));
		final List<ServiceTimes> nextServices = nextRoute == null ? Collections.emptyList() : nextRoute.getServices(weekday).stream().filter(serviceTimes -> serviceTimes.arrivals[0] >= 0).collect(Collectors.toList());

		for (final ServiceTimes serviceTimes : services) {
			final long arrival = turnBack.getArrival(serviceTimes);
			final long earliestStart = arrival + turnBack.dwellMillis + turnBack.runOutMillis;
			final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, turnBack.stopIndex);
			final List<ServiceStop> stops = new ArrayList<>();
			stops.add(stop);
			final ServiceTimes nextServiceTimes = findNextService(nextRoute, nextServices, earliestStart, stop, linkedStops);
			long leaving = arrival + turnBack.dwellMillis;
			if (nextServiceTimes != null) {
				final ServiceStop nextStop = new ServiceStop(nextRoute.routeId, nextServiceTimes.table, nextServiceTimes.service, 0);
				linkedStops.add(nextStop);
				stops.add(nextStop);
				leaving += Math.floorMod(nextServiceTimes.service - earliestStart, (long) OperatingDay.MILLIS_PER_DAY);
			}
			occupations.add(new Occupation(arrival, leaving, stops, turnBack.candidates));
		}
	}

	/**
	 * Two trains can't use a turnout of a station's throat by different ways at once (L2): each stop's entry route is used
	 * for {@link #THROAT_MILLIS} before its arrival, its exit route for as long after its departure. The checked route's
	 * windows are compared with every other train's at the same turnout. An interlocking zone is used the same way, and any
	 * two trains using it at once conflict.
	 */
	private static void checkThroats(long routeId, List<RouteTimes> routes, int weekday, Set<Conflict> conflicts) {
		// By turnout node, or by zone; a zone's ID is kept apart from the nodes
		final Map<String, List<ThroatUse>> uses = new HashMap<>();
		routes.forEach(route -> {
			if (route.throats.isEmpty()) {
				return;
			}
			route.getServices(weekday).forEach(serviceTimes -> {
				for (int i = 0; i < serviceTimes.arrivals.length && i < route.throats.size(); i++) {
					if (serviceTimes.arrivals[i] < 0) {
						continue;
					}
					final ServiceStop stop = new ServiceStop(route.routeId, serviceTimes.table, serviceTimes.service, i);
					final long arrival = serviceTimes.getArrival(i);
					final long departure = arrival + serviceTimes.departures[i] - serviceTimes.arrivals[i];
					for (final transitsim.network.ThroatRoutes.Item item : route.throats.get(i).entry) {
						uses.computeIfAbsent(useKey(item), key -> new ArrayList<>()).add(new ThroatUse(arrival - THROAT_MILLIS, arrival, item, stop));
					}
					for (final transitsim.network.ThroatRoutes.Item item : route.throats.get(i).exit) {
						uses.computeIfAbsent(useKey(item), key -> new ArrayList<>()).add(new ThroatUse(departure, departure + THROAT_MILLIS, item, stop));
					}
				}
			});
		});
		uses.forEach((key, nodeUses) -> {
			for (final ThroatUse use : nodeUses) {
				if (use.stop.routeId != routeId || conflicts.size() >= MAX_CONFLICTS) {
					continue;
				}
				for (final ThroatUse other : nodeUses) {
					// The same train coming in and going out is one train
					if (other == use || !use.item.conflicts(other.item) || other.stop.routeId == use.stop.routeId && other.stop.table == use.stop.table && other.stop.service == use.stop.service) {
						continue;
					}
					for (int day = -1; day <= 1; day++) {
						final long offset = day * OperatingDay.MILLIS_PER_DAY;
						if (other.start + offset < use.end && use.start < other.end + offset) {
							conflicts.add(new Conflict(use.stop.table, use.stop.stopIndex, use.stop.service, other.stop.routeId, other.stop.service, 0, use.item.getPlace()));
							break;
						}
					}
				}
			}
		});
	}

	private static String useKey(transitsim.network.ThroatRoutes.Item item) {
		return (item.isZone() ? "zone:" : "node:") + item.node;
	}

	/**
	 * @return the first service from {@code earliestMillis} that no train has taken, within the time a train waits for one
	 */
	private static ServiceTimes findNextService(RouteTimes nextRoute, List<ServiceTimes> nextServices, long earliestMillis, ServiceStop stop, Set<ServiceStop> linkedStops) {
		long bestWait = Long.MAX_VALUE;
		ServiceTimes bestService = null;
		for (final ServiceTimes nextServiceTimes : nextServices) {
			final ServiceStop nextStop = new ServiceStop(nextRoute.routeId, nextServiceTimes.table, nextServiceTimes.service, 0);
			final long wait = Math.floorMod(nextServiceTimes.service - earliestMillis, (long) OperatingDay.MILLIS_PER_DAY);
			if (wait < bestWait && wait <= RouteTimetable.MAX_LAYOVER_MILLIS && !linkedStops.contains(nextStop) && !nextStop.equals(stop)) {
				bestWait = wait;
				bestService = nextServiceTimes;
			}
		}
		return bestService;
	}

	private static void findConflicts(long routeId, long platformId, List<Occupation> occupations, Collection<Conflict> conflicts) {
		occupations.sort(Comparator.comparingLong(occupation -> occupation.start));
		final int size = occupations.size();
		for (int i = 0; i < size && conflicts.size() < MAX_CONFLICTS; i++) {
			final Occupation occupation = occupations.get(i);
			for (int offset = 1; offset < size && conflicts.size() < MAX_CONFLICTS; offset++) {
				final Occupation otherOccupation = occupations.get((i + offset) % size);
				// Starts only grow from here, going past midnight into the next day
				if (otherOccupation.start + (i + offset < size ? 0 : OperatingDay.MILLIS_PER_DAY) >= occupation.end + MIN_PLATFORM_GAP_MILLIS) {
					break;
				}
				addConflict(routeId, platformId, occupation, otherOccupation, conflicts);
			}
		}
	}

	private static void addConflict(long routeId, long platformId, Occupation occupation1, Occupation occupation2, Collection<Conflict> conflicts) {
		if (Collections.disjoint(occupation1.stops, occupation2.stops)) {
			final ServiceStop stop1 = occupation1.getStop(routeId);
			final ServiceStop stop2 = occupation2.getStop(routeId);
			if (stop1 != null) {
				conflicts.add(new Conflict(stop1.table, stop1.stopIndex, stop1.service, occupation2.stops.get(0).routeId, occupation2.stops.get(0).service, platformId));
			} else if (stop2 != null) {
				conflicts.add(new Conflict(stop2.table, stop2.stopIndex, stop2.service, occupation1.stops.get(0).routeId, occupation1.stops.get(0).service, platformId));
			}
		}
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
		/**
		 * The other platforms of the last stop's station trains of the route can turn back at (站前折返), in order.
		 */
		public final List<Long> terminalPlatforms = new ArrayList<>();
		/**
		 * By service, the route and service the same train runs next when that is known for sure, as {@code {route,
		 * service}} (see {@link TerminalPairs#fixedNext}); the others are paired with the nearest of {@link #nextRouteId}.
		 */
		public final Map<Integer, long[]> fixedNext = new HashMap<>();
		private final List<PlanStop> stops;
		private final List<Long> platformIds = new ArrayList<>();
		private final List<TableTimes> tables = new ArrayList<>();
		private final List<TurnBack> turnBacks = new ArrayList<>();
		/**
		 * The turnouts each stop's train passes coming in and going out, or empty if not known.
		 */
		private final List<transitsim.network.ThroatRoutes.StopThroat> throats = new ArrayList<>();

		public RouteTimes(long routeId, List<? extends PlanStop> stops) {
			this.routeId = routeId;
			this.stops = new ArrayList<>(stops);
			stops.forEach(stop -> platformIds.add(stop.getPlatformId()));
		}

		/**
		 * A route with one table running every day.
		 */
		public RouteTimes(long routeId, List<? extends PlanStop> stops, List<Integer> services, int marginPercent, long[][] times) {
			this(routeId, stops);
			addTable(0, RouteTimetable.ALL_WEEKDAYS, services, marginPercent, times);
		}

		/**
		 * @param times the fastest arrival and departure at each stop, with -1 for stops not found
		 */
		public void addTable(int table, int weekdays, List<Integer> services, int marginPercent, long[][] times) {
			addTable(table, weekdays, services, marginPercent, times, Collections.emptyMap());
		}

		/**
		 * @param exceptions the stops where services of the table run differently, which get times of their own
		 */
		public void addTable(int table, int weekdays, List<Integer> services, int marginPercent, long[][] times, Map<Integer, Map<Integer, ServiceExceptions.StopException>> exceptions) {
			final long[][] tableTimes = getTimes(times, StopRules.plan(stops, times, marginPercent, table, null));
			final TableTimes tableTimesObject = new TableTimes(table, weekdays, services, tableTimes[0], tableTimes[1]);
			exceptions.forEach((service, stopExceptions) -> tableTimesObject.serviceTimes.put(service, getTimes(times, StopRules.plan(stops, times, marginPercent, table, stopExceptions))));
			tables.add(tableTimesObject);
		}

		/**
		 * As {@link #addTable(int, int, List, int, long[][], Map)}, with the services set on the running diagram to be joined
		 * or left part of the way, which don't use the stops before or after.
		 */
		public void addTable(int table, int weekdays, List<Integer> services, int marginPercent, long[][] times, Map<Integer, Map<Integer, ServiceExceptions.StopException>> exceptions, Map<Integer, RouteTimetable.ServiceOrder> deployOrders, Map<Integer, RouteTimetable.ServiceOrder> returnOrders) {
			addTable(table, weekdays, services, marginPercent, times, exceptions);
			final TableTimes tableTimes = tables.get(tables.size() - 1);
			deployOrders.forEach((service, order) -> tableTimes.servedStops.computeIfAbsent(service, key -> new int[]{0, Integer.MAX_VALUE})[0] = order.stop);
			returnOrders.forEach((service, order) -> tableTimes.servedStops.computeIfAbsent(service, key -> new int[]{0, Integer.MAX_VALUE})[1] = order.stop);
		}

		/**
		 * @return the arrivals and departures, with -1 for stops the path doesn't reach
		 */
		private static long[][] getTimes(long[][] times, List<RouteTimetable.StopPlan> plans) {
			final long[] arrivals = new long[plans.size()];
			final long[] departures = new long[plans.size()];
			for (int i = 0; i < plans.size(); i++) {
				final boolean hasTime = times[i][0] >= 0;
				arrivals[i] = hasTime ? plans.get(i).arrivalMillis : -1;
				departures[i] = hasTime ? plans.get(i).departureMillis : -1;
			}
			return new long[][]{arrivals, departures};
		}

		public void addTurnBack(TurnBack turnBack) {
			turnBacks.add(turnBack);
		}

		/**
		 * For each pair of vehicles, ahead then behind as "pool>pool", the least time between two trains from each stop to the
		 * next (B, 2026-11-14); empty for no check.
		 */
		private final Map<String, long[]> headways = new HashMap<>();
		/**
		 * The vehicle running each service (a time of the day), by its pool; those not here run the default one, "".
		 */
		private final Map<Integer, String> servicePools = new HashMap<>();

		/**
		 * @param headways for each pair of vehicles, ahead then behind as "pool>pool" ("" for the default vehicle), the least
		 *                 time between two trains from each stop to the next
		 * @param pools    the vehicle running each service, by its pool, where known
		 */
		public void setHeadways(Map<String, long[]> headways, Map<Integer, String> pools) {
			this.headways.clear();
			this.headways.putAll(headways);
			servicePools.clear();
			servicePools.putAll(pools);
		}

		private long[] getHeadways(int aheadService, int behindService) {
			final String ahead = servicePools.getOrDefault(aheadService, "");
			final String behind = servicePools.getOrDefault(behindService, "");
			final long[] headway = headways.get(ahead + ">" + behind);
			return headway != null ? headway : headways.get(">");
		}

		/**
		 * @param throats for each stop, the turnouts its train passes coming in and going out ({@code ThroatRoutes})
		 */
		public void setThroats(List<transitsim.network.ThroatRoutes.StopThroat> throats) {
			this.throats.clear();
			this.throats.addAll(throats);
		}

		private String getTableKey(int weekday) {
			return tables.stream().filter(table -> table.runsOn(weekday)).map(table -> String.valueOf(table.table)).collect(Collectors.joining("/"));
		}

		/**
		 * @return the services of a day, each from the first table that has it, like the service a train actually runs
		 */
		private List<ServiceTimes> getServices(int weekday) {
			final List<ServiceTimes> services = new ArrayList<>();
			final Set<Integer> foundServices = new HashSet<>();
			tables.stream().filter(table -> table.runsOn(weekday)).forEach(table -> table.services.forEach(service -> {
				if (foundServices.add(service)) {
					final long[][] serviceTimes = table.serviceTimes.get(service);
					long[] arrivals = serviceTimes == null ? table.arrivals : serviceTimes[0];
					long[] departures = serviceTimes == null ? table.departures : serviceTimes[1];
					// Stops a service joined or left part of the way doesn't use count as not reached
					final int[] served = table.servedStops.get(service);
					if (served != null) {
						arrivals = arrivals.clone();
						departures = departures.clone();
						for (int i = 0; i < arrivals.length; i++) {
							if (i < served[0] || i > served[1]) {
								arrivals[i] = -1;
								departures[i] = -1;
							}
						}
					}
					services.add(new ServiceTimes(table.table, service, arrivals, departures));
				}
			}));
			return services;
		}
	}

	/**
	 * A turn back rail a route uses after one of its stops, and how long it takes to get there and away.
	 */
	public static class TurnBack {

		public final long routeId;
		public final UUID rail;
		/**
		 * The stop of this route before the turn back rail.
		 */
		public final int stopIndex;
		/**
		 * The route the train runs next, or 0 if the train just carries on.
		 */
		public final long nextRouteId;
		public final long runInMillis;
		public final long dwellMillis;
		public final long runOutMillis;
		/**
		 * The turn back rails a train can use here, in the order it tries them, which is only this one unless the route lists more.
		 */
		private List<UUID> candidates;

		public TurnBack(long routeId, UUID rail, int stopIndex, long nextRouteId, long runInMillis, long dwellMillis, long runOutMillis) {
			this.routeId = routeId;
			this.rail = rail;
			this.stopIndex = stopIndex;
			this.nextRouteId = nextRouteId;
			this.runInMillis = runInMillis;
			this.dwellMillis = dwellMillis;
			this.runOutMillis = runOutMillis;
			candidates = Collections.singletonList(rail);
		}

		/**
		 * @param newCandidates the turn back rails a train can use here, in the order it tries them, including this one
		 */
		public void setCandidates(List<UUID> newCandidates) {
			candidates = newCandidates.contains(rail) ? new ArrayList<>(newCandidates) : Collections.singletonList(rail);
		}

		public List<UUID> getCandidates() {
			return candidates;
		}

		private long getArrival(ServiceTimes serviceTimes) {
			return Math.floorMod(serviceTimes.service + serviceTimes.departures[stopIndex] + runInMillis, (long) OperatingDay.MILLIS_PER_DAY);
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof TurnBack)) {
				return false;
			}
			final TurnBack turnBack = (TurnBack) object;
			return routeId == turnBack.routeId && rail.equals(turnBack.rail) && stopIndex == turnBack.stopIndex && nextRouteId == turnBack.nextRouteId;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, rail, stopIndex, nextRouteId);
		}
	}

	public static class Conflict {

		/**
		 * The table of the checked route with this service.
		 */
		public final int table;
		/**
		 * The stop of the checked route where this happens.
		 */
		public final int stopIndex;
		public final int service;
		public final long otherRouteId;
		public final int otherService;
		/**
		 * Where this happens, or 0 for a turn back rail or a throat.
		 */
		public final long platformId;
		/**
		 * The turnout of a station's throat both trains use by different ways, or 0.
		 */
		public final long turnoutNode;
		/**
		 * For a service running closer behind the one before than trains can (B, 2026-11-14), the time between them from the
		 * stop, and the least it needs; both 0 otherwise.
		 */
		public final long headwayMillis;
		public final long neededMillis;

		public Conflict(int table, int stopIndex, int service, long otherRouteId, int otherService, long platformId) {
			this(table, stopIndex, service, otherRouteId, otherService, platformId, 0);
		}

		public Conflict(int table, int stopIndex, int service, long otherRouteId, int otherService, long platformId, long turnoutNode) {
			this(table, stopIndex, service, otherRouteId, otherService, platformId, turnoutNode, 0, 0);
		}

		public Conflict(int table, int stopIndex, int service, long otherRouteId, int otherService, long platformId, long turnoutNode, long headwayMillis, long neededMillis) {
			this.table = table;
			this.stopIndex = stopIndex;
			this.service = service;
			this.otherRouteId = otherRouteId;
			this.otherService = otherService;
			this.platformId = platformId;
			this.turnoutNode = turnoutNode;
			this.headwayMillis = headwayMillis;
			this.neededMillis = neededMillis;
		}

		/**
		 * @return whether the service runs closer behind the one before than trains can
		 */
		public boolean isHeadway() {
			return neededMillis > 0;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Conflict)) {
				return false;
			}
			final Conflict conflict = (Conflict) object;
			return table == conflict.table && stopIndex == conflict.stopIndex && service == conflict.service && otherRouteId == conflict.otherRouteId && otherService == conflict.otherService && platformId == conflict.platformId && turnoutNode == conflict.turnoutNode && headwayMillis == conflict.headwayMillis && neededMillis == conflict.neededMillis;
		}

		@Override
		public int hashCode() {
			return Objects.hash(table, stopIndex, service, otherRouteId, otherService, platformId, turnoutNode, headwayMillis, neededMillis);
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

	private static class TableTimes {

		private final int table;
		private final int weekdays;
		private final List<Integer> services;
		private final long[] arrivals;
		private final long[] departures;
		/**
		 * The arrivals and departures of services with exceptions, which differ from the table's.
		 */
		private final Map<Integer, long[][]> serviceTimes = new HashMap<>();
		/**
		 * The first and last stops of services joined or left part of the way.
		 */
		private final Map<Integer, int[]> servedStops = new HashMap<>();

		private TableTimes(int table, int weekdays, List<Integer> services, long[] arrivals, long[] departures) {
			this.table = table;
			this.weekdays = weekdays;
			this.services = new ArrayList<>(services);
			this.arrivals = arrivals;
			this.departures = departures;
		}

		private boolean runsOn(int weekday) {
			return (weekdays >> weekday & 1) != 0;
		}
	}

	private static class ServiceTimes {

		private final int table;
		private final int service;
		private final long[] arrivals;
		private final long[] departures;

		private ServiceTimes(int table, int service, long[] arrivals, long[] departures) {
			this.table = table;
			this.service = service;
			this.arrivals = arrivals;
			this.departures = departures;
		}

		/**
		 * @return the time of day this service reaches a stop
		 */
		private long getArrival(int stopIndex) {
			return Math.floorMod(service + arrivals[stopIndex], (long) OperatingDay.MILLIS_PER_DAY);
		}
	}

	private static class Occupation {

		private final long start;
		private final long end;
		private final List<ServiceStop> stops;
		/**
		 * For a train that can turn back on more than one rail, those rails in the order it tries them.
		 */
		private final List<UUID> candidates;
		/**
		 * For a train that can turn back at more than one platform, those platforms in the order it tries them.
		 */
		private final List<Long> platforms;

		private Occupation(long start, long end, List<ServiceStop> stops) {
			this(start, end, stops, Collections.emptyList());
		}

		private Occupation(long start, long end, List<ServiceStop> stops, List<UUID> candidates) {
			this(start, end, stops, candidates, Collections.emptyList());
		}

		private Occupation(long start, long end, List<ServiceStop> stops, List<UUID> candidates, List<Long> platforms) {
			this.start = start;
			this.end = end;
			this.stops = stops;
			this.candidates = candidates;
			this.platforms = platforms;
		}

		private ServiceStop getStop(long routeId) {
			return stops.stream().filter(stop -> stop.routeId == routeId).findFirst().orElse(null);
		}
	}

	private static class ThroatUse {

		private final long start;
		private final long end;
		private final transitsim.network.ThroatRoutes.Item item;
		private final ServiceStop stop;

		private ThroatUse(long start, long end, transitsim.network.ThroatRoutes.Item item, ServiceStop stop) {
			this.start = start;
			this.end = end;
			this.item = item;
			this.stop = stop;
		}
	}

	private static class ServiceStop {

		private final long routeId;
		private final int table;
		private final int service;
		private final int stopIndex;

		private ServiceStop(long routeId, int table, int service, int stopIndex) {
			this.routeId = routeId;
			this.table = table;
			this.service = service;
			this.stopIndex = stopIndex;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof ServiceStop)) {
				return false;
			}
			final ServiceStop stop = (ServiceStop) object;
			return routeId == stop.routeId && table == stop.table && service == stop.service && stopIndex == stop.stopIndex;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, table, service, stopIndex);
		}
	}
}
