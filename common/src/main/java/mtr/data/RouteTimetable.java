package mtr.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * The services of a route, each being the planned arrival at the first stop in UTC milliseconds of the day, like
 * {@link Depot#departures}. The times at later stops come from the generated path.
 * <p>
 * This is a route's first table, which also holds its other tables, as a route can run differently on different days. Each
 * table has its own services and times at each stop.
 */
public class RouteTimetable {

	public boolean enabled;
	public int marginPercent = DEFAULT_MARGIN_PERCENT;
	public final List<Integer> services = new ArrayList<>();
	public String name = "";
	/**
	 * On a loop, the platform where trains from the depot join it instead of its first stop, or 0. See {@link Route#getLoopEntryStop()}.
	 */
	public long entryPlatformId;
	/**
	 * The days this table runs on, with the lowest bit for Monday. See {@link TimetableConfig#getWeekday(long)}.
	 */
	public int weekdays = ALL_WEEKDAYS;
	/**
	 * Changes whenever the times of this timetable change, so plans made from it can be rebuilt.
	 */
	public int revision;
	/**
	 * Services of this table that carry on onto a service of another route, by the service they continue from.
	 */
	public final Map<Integer, ServiceLink> links = new HashMap<>();
	/**
	 * Services of this table after which the train goes back to the depot, for depots whose trains otherwise stay on the line.
	 */
	public final Set<Integer> returnServices = new HashSet<>();
	private final List<RouteTimetable> otherTables = new ArrayList<>();
	private final List<Integer> allServices = new ArrayList<>();
	/**
	 * Services that only run as the continuation of a service of another route, so no depot sends a train for them.
	 */
	private final Set<Integer> linkedServices = new HashSet<>();

	public static final int MAX_TABLES = 16;
	public static final int MAX_NAME_LENGTH = 32;
	public static final int ALL_WEEKDAYS = 0b1111111;
	public static final int MAX_SERVICES = 4096;
	/**
	 * The largest time of a stop in seconds from reaching the first stop.
	 */
	public static final int MAX_STOP_SECONDS = 2 * 24 * 60 * 60;
	/**
	 * How long a train waits past its departure time for a train that should overtake it, so a very late train can't hold it forever.
	 */
	public static final long MAX_OVERTAKE_WAIT_MILLIS = 5 * 60 * 1000;
	public static final int DEFAULT_MARGIN_PERCENT = 5;
	public static final int MAX_MARGIN_PERCENT = 100;
	/**
	 * How far ahead a train that stays on the line looks for its next service, which is the first train of the next day at most.
	 */
	public static final long MAX_STAY_MILLIS = 8L * Depot.MILLISECONDS_PER_DAY;
	/**
	 * A train with a longer wait until its next service returns to the depot instead.
	 */
	public static final long MAX_LAYOVER_MILLIS = 30 * 60 * 1000;
	/**
	 * A service still waiting for a train after this long is dropped, so a train never runs far behind on purpose.
	 */
	public static final long MAX_DEPLOY_DELAY_MILLIS = 10 * 60 * 1000;
	/**
	 * A train this close to its time after a restart stays where it is, as a train running a little late can catch up.
	 */
	public static final long ALIGN_TOLERANCE_MILLIS = 60 * 1000;
	/**
	 * How many more routes one train runs through after the routes of its depot. This also ends services linked in a circle.
	 */
	public static final int MAX_TRIP_LINKS = 7;

	public boolean isActive() {
		return enabled && getTables().stream().anyMatch(table -> !table.services.isEmpty());
	}

	/**
	 * Sorts the services and removes anything a client should not have sent.
	 */
	public void sanitize() {
		final List<Integer> newServices = services.stream().map(service -> Math.floorMod(service, Depot.MILLISECONDS_PER_DAY)).distinct().sorted().limit(MAX_SERVICES).collect(Collectors.toList());
		services.clear();
		services.addAll(newServices);
		marginPercent = Math.max(0, Math.min(marginPercent, MAX_MARGIN_PERCENT));
		weekdays &= ALL_WEEKDAYS;
		name = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
		links.keySet().retainAll(services);
		returnServices.retainAll(services);

		otherTables.forEach(RouteTimetable::sanitize);
		allServices.clear();
		allServices.addAll(getTables().stream().flatMap(table -> table.services.stream()).distinct().sorted().collect(Collectors.toList()));
	}

	/**
	 * @return every table of the route, starting with this one
	 */
	public List<RouteTimetable> getTables() {
		final List<RouteTimetable> tables = new ArrayList<>();
		tables.add(this);
		tables.addAll(otherTables);
		return tables;
	}

	public int getTableCount() {
		return otherTables.size() + 1;
	}

	/**
	 * @return the table with this index, or this one if there is no such table
	 */
	public RouteTimetable getTable(int index) {
		return index >= 1 && index <= otherTables.size() ? otherTables.get(index - 1) : this;
	}

	public void addTable(RouteTimetable table) {
		if (getTableCount() < MAX_TABLES) {
			otherTables.add(table);
		}
	}

	/**
	 * Other than the first table, which holds the rest. Use {@link Route#removeTable(int)} to remove the table's stop times too.
	 */
	public void removeTable(int index) {
		if (index >= 1 && index <= otherTables.size()) {
			otherTables.remove(index - 1);
		}
	}

	public void clearOtherTables() {
		otherTables.clear();
	}

	public void clearLinkedServices() {
		linkedServices.clear();
	}

	public void addLinkedService(int service) {
		linkedServices.add(service);
	}

	/**
	 * @return the service this one carries on onto, or null if the train doesn't leave this route's path
	 */
	public ServiceLink getLink(long serviceMillis) {
		return getTable(getTableIndex(serviceMillis)).links.get((int) Math.floorMod(serviceMillis, (long) Depot.MILLISECONDS_PER_DAY));
	}

	/**
	 * @param serviceMillis a service as a full timestamp
	 * @return true if the train goes back to the depot after this service
	 */
	public boolean isReturnService(long serviceMillis) {
		return getTable(getTableIndex(serviceMillis)).returnServices.contains((int) Math.floorMod(serviceMillis, (long) Depot.MILLISECONDS_PER_DAY));
	}

	/**
	 * @return the service a service of this route carries on onto on any day, or null. Unlike {@link #getLink(long)} this
	 * doesn't need the day, as a path is made for a service before it runs.
	 */
	public ServiceLink getLinkOfService(int service) {
		return getTables().stream().map(table -> table.links.get(service)).filter(Objects::nonNull).findFirst().orElse(null);
	}

	/**
	 * @return this link and the ones following it, as far as a train can run in one go
	 */
	public static List<ServiceLink> getLinkChain(ServiceLink firstLink, LongFunction<Route> getRoute) {
		final List<ServiceLink> links = new ArrayList<>();
		links.add(firstLink);
		while (links.size() < MAX_TRIP_LINKS) {
			final ServiceLink link = links.get(links.size() - 1);
			final Route route = getRoute.apply(link.routeId);
			final ServiceLink nextLink = route == null ? null : route.timetable.getLinkOfService(link.service);
			if (nextLink == null) {
				break;
			}
			links.add(nextLink);
		}
		return links;
	}

	public boolean runsOn(int weekday) {
		return (weekdays >> weekday & 1) != 0;
	}

	/**
	 * @param serviceMillis a service as a full timestamp
	 * @return the table this service belongs to on its day, which is the first one if several have it
	 */
	public int getTableIndex(long serviceMillis) {
		return Math.max(0, findTableIndex(serviceMillis));
	}

	private int findTableIndex(long serviceMillis) {
		if (otherTables.isEmpty() && weekdays == ALL_WEEKDAYS) {
			return 0;
		}
		final int weekday = TimetableConfig.getWeekday(serviceMillis);
		final int service = (int) Math.floorMod(serviceMillis, (long) Depot.MILLISECONDS_PER_DAY);
		final List<RouteTimetable> tables = getTables();
		for (int i = 0; i < tables.size(); i++) {
			if (tables.get(i).runsOn(weekday) && Collections.binarySearch(tables.get(i).services, service) >= 0) {
				return i;
			}
		}
		return -1;
	}

	private List<Integer> getAllServices() {
		return otherTables.isEmpty() ? services : allServices;
	}

	/**
	 * Applies the planned extra time to a calculated running time, which is what lets a late train catch up.
	 */
	public long addMargin(long millis) {
		return millis * (100 + marginPercent) / 100;
	}

	/**
	 * Works out the times of a whole trip. A stop without a set time follows the stop before it, so changing a time also
	 * moves every automatic time after it. Set times count from where their own route starts, because a route after a turn
	 * back runs by its own services, and doesn't know when the trip started.
	 *
	 * @param stops the stops of the trip in order, starting at the first stop
	 */
	public void applyPlan(List<StopPlan> stops) {
		long lastFastestDeparture = 0;
		long lastDeparture = 0;
		long routeStart = 0;
		for (int i = 0; i < stops.size(); i++) {
			final StopPlan stop = stops.get(i);
			final long automaticArrival = i == 0 ? 0 : lastDeparture + addMargin(stop.fastestArrivalMillis - lastFastestDeparture);
			stop.arrivalMillis = i > 0 && stop.setArrivalSeconds >= 0 ? routeStart + stop.setArrivalSeconds * 1000L : automaticArrival;
			if (stop.startsRoute) {
				routeStart = stop.arrivalMillis;
			}
			stop.departureMillis = stop.setDepartureSeconds >= 0 ? Math.max(routeStart + stop.setDepartureSeconds * 1000L, stop.arrivalMillis) : stop.arrivalMillis + stop.fastestDepartureMillis - stop.fastestArrivalMillis;
			stop.routeStartMillis = routeStart;
			stop.isTimed = i == 0 || stop.overtake || stop.setDepartureSeconds >= 0 || stop.waitsForService;
			lastFastestDeparture = stop.fastestDepartureMillis;
			lastDeparture = stop.departureMillis;
		}
	}

	/**
	 * Works out the times of one route on its own. A passing train doesn't stop, even if the times from the server were
	 * worked out before the stop was changed to passing, as they only change when the path is refreshed.
	 *
	 * @param stops         the stops of the route
	 * @param times         the fastest arrival and departure at each stop, with -1 for stops not found, or null if there are none yet
	 * @param marginPercent the extra time to plan for
	 * @param table         the index of the table to take the times at each stop from
	 */
	public static List<StopPlan> getStopPlans(List<Route.RoutePlatform> stops, long[][] times, int marginPercent, int table) {
		final List<StopPlan> plans = new ArrayList<>();
		long lastTime = 0;
		for (int i = 0; i < stops.size(); i++) {
			final Route.RoutePlatform stop = stops.get(i);
			final boolean hasTime = times != null && times[i][0] >= 0;
			final boolean isPass = stop.pass && i > 0 && i < stops.size() - 1;
			final long arrival = hasTime ? times[i][0] : lastTime;
			final long departure = !hasTime ? lastTime : isPass ? arrival : times[i][1];
			lastTime = departure;
			plans.add(new StopPlan(arrival, departure, stop.getArrival(table), stop.getDeparture(table), stop.getOvertake(table)));
		}
		final RouteTimetable timetable = new RouteTimetable();
		timetable.marginPercent = marginPercent;
		timetable.applyPlan(plans);
		return plans;
	}

	/**
	 * Marks turn back rails just before a route with services of its own, where a train waits so it reaches that route on time.
	 *
	 * @param stops       the stops of the trip in order, after {@link #applyPlan(List)}
	 * @param isTurnBacks for each stop, true if it is not at a platform
	 */
	public static void linkTurnBacks(List<StopPlan> stops, List<Boolean> isTurnBacks) {
		for (int i = 0; i < stops.size() - 1; i++) {
			final StopPlan nextStop = stops.get(i + 1);
			if (isTurnBacks.get(i) && nextStop.startsRoute && nextStop.waitsForService) {
				stops.get(i).nextRouteStart = nextStop;
				stops.get(i).isTimed = true;
			}
		}
	}

	/**
	 * @return the first free service from {@code fromMillis} until before {@code beforeMillis} as a full timestamp, or 0
	 */
	public long getNextService(long fromMillis, long beforeMillis, LongPredicate isTaken) {
		final long lastDay = Math.floorDiv(beforeMillis, (long) Depot.MILLISECONDS_PER_DAY);
		final List<Integer> runningServices = getAllServices();
		for (long day = Math.floorDiv(fromMillis, (long) Depot.MILLISECONDS_PER_DAY); day <= lastDay; day++) {
			for (final int service : runningServices) {
				final long serviceMillis = day * Depot.MILLISECONDS_PER_DAY + service;
				if (serviceMillis >= beforeMillis) {
					return 0;
				}
				if (serviceMillis >= fromMillis && !linkedServices.contains(service) && findTableIndex(serviceMillis) >= 0 && !isTaken.test(serviceMillis)) {
					return serviceMillis;
				}
			}
		}
		return 0;
	}

	/**
	 * @return the last free service after {@code afterMillis} and up to {@code latestMillis} as a full timestamp, or 0
	 */
	public long getLatestService(long afterMillis, long latestMillis, LongPredicate isTaken) {
		final long firstDay = Math.floorDiv(afterMillis, (long) Depot.MILLISECONDS_PER_DAY);
		final List<Integer> runningServices = getAllServices();
		for (long day = Math.floorDiv(latestMillis, (long) Depot.MILLISECONDS_PER_DAY); day >= firstDay; day--) {
			for (int i = runningServices.size() - 1; i >= 0; i--) {
				final long serviceMillis = day * Depot.MILLISECONDS_PER_DAY + runningServices.get(i);
				if (serviceMillis <= afterMillis) {
					return 0;
				}
				if (serviceMillis <= latestMillis && !linkedServices.contains(runningServices.get(i)) && findTableIndex(serviceMillis) >= 0 && !isTaken.test(serviceMillis)) {
					return serviceMillis;
				}
			}
		}
		return 0;
	}

	/**
	 * @return the time of this service closest to a planned time as a full timestamp
	 */
	public static long getClosestService(long plannedMillis, int service) {
		final long dayMillis = plannedMillis - Math.floorMod(plannedMillis, (long) Depot.MILLISECONDS_PER_DAY);
		long closest = dayMillis + service;
		for (final long day : new long[]{-Depot.MILLISECONDS_PER_DAY, Depot.MILLISECONDS_PER_DAY}) {
			if (Math.abs(dayMillis + day + service - plannedMillis) < Math.abs(closest - plannedMillis)) {
				closest = dayMillis + day + service;
			}
		}
		return closest;
	}

	/**
	 * A service carrying on onto a service of another route, such as a loop's 07:00 running through to another line at 07:32.
	 */
	public static class ServiceLink {

		public final long routeId;
		public final int service;
		/**
		 * The stop of this route where the train leaves it, or -1 for the last stop.
		 */
		public final int fromStop;
		/**
		 * The stop of a loop at the end of the trip where the train joins it, or 0 for the first stop.
		 */
		public final int toStop;

		public ServiceLink(long routeId, int service, int fromStop) {
			this(routeId, service, fromStop, 0);
		}

		public ServiceLink(long routeId, int service, int fromStop, int toStop) {
			this.routeId = routeId;
			this.service = service;
			this.fromStop = fromStop;
			this.toStop = toStop;
		}
	}

	/**
	 * The times of one stop, in milliseconds from reaching the first stop.
	 */
	public static class StopPlan {

		public final long fastestArrivalMillis;
		public final long fastestDepartureMillis;
		public final int setArrivalSeconds;
		public final int setDepartureSeconds;
		public final boolean overtake;
		/**
		 * The next route of the trip starts here, so times from here on count from this stop.
		 */
		public boolean startsRoute;
		/**
		 * The route starting here, which is only known on the server.
		 */
		public Route route;
		/**
		 * The route starting here has services of its own, which the train waits for here.
		 */
		public boolean waitsForService;
		public long routeStartMillis;
		/**
		 * This is a turn back rail, and the next stop starts a route with services of its own.
		 */
		public StopPlan nextRouteStart;
		public long arrivalMillis;
		public long departureMillis;
		/**
		 * The train keeps to this stop's departure time. Elsewhere it leaves when ready, so its stop time can vary.
		 */
		public boolean isTimed;

		public StopPlan(long fastestArrivalMillis, long fastestDepartureMillis, int setArrivalSeconds, int setDepartureSeconds, boolean overtake) {
			this.fastestArrivalMillis = fastestArrivalMillis;
			this.fastestDepartureMillis = fastestDepartureMillis;
			this.setArrivalSeconds = setArrivalSeconds;
			this.setDepartureSeconds = setDepartureSeconds;
			this.overtake = overtake;
		}
	}
}
