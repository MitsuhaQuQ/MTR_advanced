package mtr.data;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * The services of a route, each being the planned arrival at the first stop in UTC milliseconds of the day, like
 * {@link Depot#departures}. The times at later stops come from the generated path.
 */
public class RouteTimetable {

	public boolean enabled;
	public int marginPercent = DEFAULT_MARGIN_PERCENT;
	public final List<Integer> services = new ArrayList<>();
	/**
	 * Changes whenever the times of this timetable change, so plans made from it can be rebuilt.
	 */
	public int revision;

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
	 * A train with a longer wait until its next service returns to the depot instead.
	 */
	public static final long MAX_LAYOVER_MILLIS = 30 * 60 * 1000;
	/**
	 * A service still waiting for a train after this long is dropped, so a train never runs far behind on purpose.
	 */
	public static final long MAX_DEPLOY_DELAY_MILLIS = 10 * 60 * 1000;

	public boolean isActive() {
		return enabled && !services.isEmpty();
	}

	/**
	 * Sorts the services and removes anything a client should not have sent.
	 */
	public void sanitize() {
		final List<Integer> newServices = services.stream().map(service -> Math.floorMod(service, Depot.MILLISECONDS_PER_DAY)).distinct().sorted().limit(MAX_SERVICES).collect(Collectors.toList());
		services.clear();
		services.addAll(newServices);
		marginPercent = Math.max(0, Math.min(marginPercent, MAX_MARGIN_PERCENT));
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
	 */
	public static List<StopPlan> getStopPlans(List<Route.RoutePlatform> stops, long[][] times, int marginPercent) {
		final List<StopPlan> plans = new ArrayList<>();
		long lastTime = 0;
		for (int i = 0; i < stops.size(); i++) {
			final Route.RoutePlatform stop = stops.get(i);
			final boolean hasTime = times != null && times[i][0] >= 0;
			final boolean isPass = stop.pass && i > 0 && i < stops.size() - 1;
			final long arrival = hasTime ? times[i][0] : lastTime;
			final long departure = !hasTime ? lastTime : isPass ? arrival : times[i][1];
			lastTime = departure;
			plans.add(new StopPlan(arrival, departure, stop.getArrival(), stop.getDeparture(), stop.overtake));
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
		for (long day = Math.floorDiv(fromMillis, (long) Depot.MILLISECONDS_PER_DAY); day <= lastDay; day++) {
			for (final int service : services) {
				final long serviceMillis = day * Depot.MILLISECONDS_PER_DAY + service;
				if (serviceMillis >= beforeMillis) {
					return 0;
				}
				if (serviceMillis >= fromMillis && !isTaken.test(serviceMillis)) {
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
		for (long day = Math.floorDiv(latestMillis, (long) Depot.MILLISECONDS_PER_DAY); day >= firstDay; day--) {
			for (int i = services.size() - 1; i >= 0; i--) {
				final long serviceMillis = day * Depot.MILLISECONDS_PER_DAY + services.get(i);
				if (serviceMillis <= afterMillis) {
					return 0;
				}
				if (serviceMillis <= latestMillis && !isTaken.test(serviceMillis)) {
					return serviceMillis;
				}
			}
		}
		return 0;
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
