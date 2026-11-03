package transitsim.sim;

import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanTrack;
import transitsim.plan.RouteTimetable;
import transitsim.plan.TripPath;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * What a depot decides when it sends trains out: which services its trains have taken, which service the next train
 * takes, and which slot's duty leaves next. The host keeps the depot's paths and saves, and tells the trains to go.
 */
public class DepotDispatcher {

	/**
	 * The last service sent out on each first route, so the next train takes a later one. The host saves it.
	 */
	public final Map<Long, Long> lastDeployedTrips = new HashMap<>();
	/**
	 * The operating day each slot's duty last ran on, by siding and slot as {@code "siding:slot"}. The host saves it.
	 */
	public final Map<String, Long> dutyRunDays = new HashMap<>();
	/**
	 * Services taken in the last tick and in this one. Trains taking a service reserve it again every tick.
	 */
	private Set<Long> reservedTrips = new HashSet<>();
	private Set<Long> newReservedTrips = new HashSet<>();

	private static final int DUTY_DEPLOY_MARGIN_MILLIS = 15000;

	/**
	 * Called once a tick before trains reserve their services, which forgets the ones no train reserved last tick.
	 */
	public void startTick() {
		final Set<Long> oldReservedTrips = reservedTrips;
		reservedTrips = newReservedTrips;
		newReservedTrips = oldReservedTrips;
		newReservedTrips.clear();
	}

	public void reserveTrip(long routeId, long tripMillis) {
		if (tripMillis != 0) {
			newReservedTrips.add(getTripKey(routeId, tripMillis));
		}
	}

	public boolean isTripReserved(long routeId, long tripMillis) {
		final long tripKey = getTripKey(routeId, tripMillis);
		return reservedTrips.contains(tripKey) || newReservedTrips.contains(tripKey);
	}

	/**
	 * @param staysOnLine true if trains wait on the line for their next service however long that is
	 * @return the first service of this route from this time on that no train has taken, which is then taken, or 0 if a
	 * train would wait too long for it
	 */
	public long findNextService(PlanRoute route, long readyMillis, boolean staysOnLine) {
		final long tripMillis = route.getTimetable().getNextService(readyMillis, readyMillis + (staysOnLine ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS), millis -> isTripReserved(route.getRouteId(), millis));
		reserveTrip(route.getRouteId(), tripMillis);
		return tripMillis;
	}

	/**
	 * @param leadMillis the time a train needs from the siding to the first stop
	 * @param count      which of the services no train has taken, counting from 1 for the next one
	 * @return the time until a train has to leave for that service, or -1 if there is none within a day
	 */
	public int getNextDepartureMillis(RouteTimetable timetable, long firstRouteId, long currentMillis, long leadMillis, int count) {
		long fromMillis = currentMillis + leadMillis;
		long tripMillis = 0;
		for (int i = 0; i < count; i++) {
			tripMillis = timetable.getNextService(fromMillis, currentMillis + OperatingDay.MILLIS_PER_DAY, millis -> isTripReserved(firstRouteId, millis));
			if (tripMillis == 0) {
				return -1;
			}
			fromMillis = tripMillis + 1;
		}
		return (int) (tripMillis - leadMillis - currentMillis);
	}

	public boolean hasDutyRun(long sidingId, int slot, long day) {
		final Long runDay = dutyRunDays.get(sidingId + ":" + slot);
		return runDay != null && runDay == day;
	}

	public void markDutyRun(long sidingId, int slot, long day) {
		dutyRunDays.put(sidingId + ":" + slot, day);
		// Days long gone don't need remembering
		dutyRunDays.values().removeIf(runDay -> runDay < day - 2);
	}

	/**
	 * A depot running by its duties sends out a train for each slot whose duty starts from the siding today, when its first
	 * service is due, once a day (design document 6.12, block E2). Slots whose duty a train staying overnight takes over are
	 * left to that train. One train leaves a tick, the one due first; the duty is marked as run.
	 *
	 * @param sidingIds the sidings with a train ready to leave, in order
	 * @param tripPaths the paths of a siding by key, or null if the siding doesn't exist
	 * @return the duty to send out, or null if none is due
	 */
	public <T extends PlanTrack> DutyDeploy<T> findDutyDeploy(long currentMillis, Collection<Long> sidingIds, DepotDuties duties, LongFunction<Map<String, TripPath<T>>> tripPaths, LongFunction<? extends PlanRoute> routes) {
		final long dayStart = DutyShapes.getDayStart(currentMillis);
		final long day = DutyShapes.getDayNumber(dayStart);
		final int weekday = OperatingDay.getWeekday(currentMillis);
		DutyDeploy<T> best = null;
		for (final long sidingId : sidingIds) {
			final Map<String, TripPath<T>> sidingPaths = tripPaths.apply(sidingId);
			final List<DepotDuties.Slot> slots = duties.getSlots(sidingId);
			if (sidingPaths == null) {
				continue;
			}
			for (int i = 0; i < slots.size(); i++) {
				final DepotDuties.DayDuty duty = slots.get(i).getDuty(weekday);
				if (duty == null || duty.segments.isEmpty() || hasDutyRun(sidingId, i, day) || DutyShapes.getOvernightSlot(slots, i, weekday) >= 0) {
					continue;
				}
				final String key = DutyShapes.getSlotKey(slots, i, weekday, routes);
				final TripPath<T> dutyPath = key == null ? null : sidingPaths.get(key);
				if (dutyPath == null) {
					continue;
				}
				final long departureMillis = DutyShapes.getServiceMillis(dayStart, duty.segments.get(0).service) - dutyPath.stopTimes.getLeadMillis() - DUTY_DEPLOY_MARGIN_MILLIS;
				if (currentMillis >= departureMillis && currentMillis <= departureMillis + RouteTimetable.MAX_DEPLOY_DELAY_MILLIS && (best == null || departureMillis < best.departureMillis)) {
					best = new DutyDeploy<>(sidingId, i, dayStart, dutyPath, departureMillis);
				}
			}
		}
		if (best != null) {
			markDutyRun(best.sidingId, best.slot, day);
		}
		return best;
	}

	private static long getTripKey(long routeId, long tripMillis) {
		return routeId * 31 + tripMillis;
	}

	/**
	 * A slot's duty to send out from a siding.
	 */
	public static class DutyDeploy<T extends PlanTrack> {

		public final long sidingId;
		public final int slot;
		/**
		 * When the operating day of the duty started, as a full timestamp.
		 */
		public final long dayStart;
		public final TripPath<T> path;
		private final long departureMillis;

		private DutyDeploy(long sidingId, int slot, long dayStart, TripPath<T> path, long departureMillis) {
			this.sidingId = sidingId;
			this.slot = slot;
			this.dayStart = dayStart;
			this.path = path;
			this.departureMillis = departureMillis;
		}
	}
}
