package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one set of rules for when a service of a route reaches and leaves each stop, whether it passes, waits to be
 * overtaken, or is planned faster than a train can run (S3e, A09). Trains running a path, the duty planner and the
 * timetable page all take their times from here; the page asks the game rather than working them out itself.
 * <p>
 * The arithmetic of a whole trip, including routes joined part of the way along, is {@link RouteTimetable#applyPlan}.
 */
public final class StopRules {

	/**
	 * How much earlier than the fastest time a stop can be planned before it counts as too fast, for rounding.
	 */
	private static final long TOO_FAST_TOLERANCE_MILLIS = 500;

	private StopRules() {
	}

	/**
	 * Works out the times of one route on its own. A passing train doesn't stop, even if the fastest times were worked out
	 * before the stop was changed to passing, as they only change when the path is refreshed.
	 *
	 * @param stops          the stops of the route
	 * @param fastest        the fastest pairs at each stop ({@link TimetableTimes}), with -1 for stops not found, or null if unknown
	 * @param marginPercent  the extra time to plan for
	 * @param table          the index of the table to take the times at each stop from
	 * @param stopExceptions where one service runs differently, by stop, or null for the table's own times
	 */
	public static List<RouteTimetable.StopPlan> plan(List<? extends PlanStop> stops, long[][] fastest, int marginPercent, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final List<RouteTimetable.StopPlan> plans = new ArrayList<>();
		long lastTime = 0;
		for (int i = 0; i < stops.size(); i++) {
			final PlanStop stop = stops.get(i);
			final int column = fastest == null ? 0 : TimetableTimes.column(fastest[i], table);
			final boolean hasTime = fastest != null && column >= 0 && column + 1 < fastest[i].length && fastest[i][column] >= 0;
			final boolean isPass = stop.isPass() && i > 0 && i < stops.size() - 1;
			final long arrival = hasTime ? fastest[i][column] : lastTime;
			final long departure = !hasTime ? lastTime : isPass ? arrival : fastest[i][column + 1];
			lastTime = departure;
			plans.add(new RouteTimetable.StopPlan(arrival, departure, stop.getArrival(table), stop.getDeparture(table), stop.getOvertake(table)));
		}
		ServiceExceptions.applyTo(plans, stopExceptions);
		final RouteTimetable timetable = new RouteTimetable();
		timetable.marginPercent = marginPercent;
		timetable.applyPlan(plans);
		return plans;
	}

	/**
	 * @return the times of one route on its own as {@link #plan} works them out, with what each stop means for the service
	 */
	public static List<StopTime> times(List<? extends PlanStop> stops, long[][] fastest, int marginPercent, int table, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		final List<RouteTimetable.StopPlan> plans = plan(stops, fastest, marginPercent, table, stopExceptions);
		final List<StopTime> times = new ArrayList<>();
		for (int i = 0; i < plans.size(); i++) {
			final RouteTimetable.StopPlan plan = plans.get(i);
			final boolean middle = i > 0 && i < plans.size() - 1;
			final RouteTimetable.StopPlan previous = i > 0 ? plans.get(i - 1) : null;
			final boolean tooFast = previous != null && fastest != null && plan.arrivalMillis < previous.departureMillis + plan.fastestArrivalMillis - previous.fastestDepartureMillis - TOO_FAST_TOLERANCE_MILLIS;
			times.add(new StopTime(plan.arrivalMillis, plan.departureMillis, plan.overtake && middle, passes(stops.get(i), plan, middle), tooFast));
		}
		return times;
	}

	/**
	 * A service passes a stop between the first and the last when its exception says so, or when the route passes it and
	 * the service doesn't stop there by exception.
	 */
	public static boolean passes(PlanStop stop, RouteTimetable.StopPlan plan, boolean middle) {
		return middle && (plan.exceptionPass == 1 || plan.exceptionPass != 0 && stop.isPass());
	}

	/**
	 * One stop of one service, in milliseconds from the service's planned arrival at its first stop.
	 */
	public static final class StopTime {

		public final long arrivalMillis;
		public final long departureMillis;
		/**
		 * The train waits here for trains that should overtake it.
		 */
		public final boolean overtake;
		public final boolean passes;
		/**
		 * Planned to reach this stop sooner than a train can run from the stop before.
		 */
		public final boolean tooFast;

		private StopTime(long arrivalMillis, long departureMillis, boolean overtake, boolean passes, boolean tooFast) {
			this.arrivalMillis = arrivalMillis;
			this.departureMillis = departureMillis;
			this.overtake = overtake;
			this.passes = passes;
			this.tooFast = tooFast;
		}
	}
}
