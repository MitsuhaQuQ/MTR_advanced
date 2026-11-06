package transitsim.sim;

import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanTrack;
import transitsim.plan.RouteTimetable;
import transitsim.plan.TripPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

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
	/**
	 * Services reserved by trains the simulator doesn't move every tick, which would reserve them again in each one (S4f),
	 * counted by how many trains reserve them.
	 */
	private final Map<Long, Integer> standingTrips = new HashMap<>();
	/**
	 * The depots sharing a route with this one, whose trains' services count as taken here too, so a service of a route
	 * two depots run is run by one train, whichever depot sends it.
	 */
	private final List<DepotDispatcher> peers = new ArrayList<>();
	/**
	 * The shortest time between two trains the depot sends out by its duties, or 0 for none, set by the host (decoupling
	 * document, step D, depots sending trains out 2).
	 */
	public int releaseIntervalMillis;
	private long lastReleaseMillis = Long.MIN_VALUE / 2;

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
		if (isReservedHere(tripKey)) {
			return true;
		}
		for (final DepotDispatcher peer : peers) {
			if (peer.isReservedHere(tripKey)) {
				return true;
			}
		}
		return false;
	}

	private boolean isReservedHere(long tripKey) {
		return reservedTrips.contains(tripKey) || newReservedTrips.contains(tripKey) || standingTrips.containsKey(tripKey);
	}

	/**
	 * @return the latest service of this first route this depot or one sharing its routes sent a train out for, so a
	 * service a train of either has run already isn't sent again once that train has finished it
	 */
	public long getLastDeployedTrip(long routeId) {
		long last = lastDeployedTrips.getOrDefault(routeId, 0L);
		for (final DepotDispatcher peer : peers) {
			last = Math.max(last, peer.lastDeployedTrips.getOrDefault(routeId, 0L));
		}
		return last;
	}

	/**
	 * The two depots run a route in common, so each takes the services the other's trains have taken as taken.
	 */
	public void shareServicesWith(DepotDispatcher other) {
		if (other != this && !peers.contains(other)) {
			peers.add(other);
			other.peers.add(this);
		}
	}

	/**
	 * Forgets the depots this one shared services with, before they are worked out again.
	 */
	public void clearPeers() {
		peers.clear();
	}

	/**
	 * Keeps services reserved every tick until {@link #removeStandingTrips} for a train that isn't moved every tick.
	 *
	 * @param tripKeys what {@link #getTripKey} gives for each service
	 */
	public void addStandingTrips(Collection<Long> tripKeys) {
		tripKeys.forEach(tripKey -> standingTrips.merge(tripKey, 1, Integer::sum));
	}

	public void removeStandingTrips(Collection<Long> tripKeys) {
		tripKeys.forEach(tripKey -> standingTrips.computeIfPresent(tripKey, (key, count) -> count > 1 ? count - 1 : null));
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

	/**
	 * A siding's slots, to keep what has run by the duty's number rather than its place (P2-D), or null to go by place.
	 */
	public java.util.function.LongFunction<List<DepotDuties.Slot>> slotsOf;

	private String runKey(long sidingId, int slot) {
		final List<DepotDuties.Slot> slots = slotsOf == null ? null : slotsOf.apply(sidingId);
		return slots != null && slot >= 0 && slot < slots.size() && slots.get(slot).id != 0 ? "#" + slots.get(slot).id : sidingId + ":" + slot;
	}

	public boolean hasDutyRun(long sidingId, int slot, long day) {
		final Long runDay = dutyRunDays.get(runKey(sidingId, slot));
		return runDay != null && runDay == day;
	}

	public void markDutyRun(long sidingId, int slot, long day) {
		dutyRunDays.put(runKey(sidingId, slot), day);
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
		return findDutyDeploy(currentMillis, sidingIds, duties, tripPaths, routes, sidingId -> sidingId);
	}

	/**
	 * As {@link #findDutyDeploy(long, Collection, DepotDuties, LongFunction, LongFunction)}, with the depot's sidings of the
	 * same vehicle as a pool: a slot whose siding has no train ready is run by a train ready in another siding of its pool
	 * that has the path of the duty (decoupling document, step D, depots sending trains out 4). Trains leave at least the
	 * depot's release interval apart (2).
	 *
	 * @param pools the pool of a siding, the same for the sidings whose trains can run each other's duties
	 */
	public <T extends PlanTrack> DutyDeploy<T> findDutyDeploy(long currentMillis, Collection<Long> sidingIds, DepotDuties duties, LongFunction<Map<String, TripPath<T>>> tripPaths, LongFunction<? extends PlanRoute> routes, LongFunction<Object> pools) {
		if (releaseIntervalMillis > 0 && currentMillis < lastReleaseMillis + releaseIntervalMillis) {
			return null;
		}
		final long dayStart = DutyShapes.getDayStart(currentMillis);
		final long day = DutyShapes.getDayNumber(dayStart);
		final int weekday = OperatingDay.getWeekday(currentMillis);
		final Set<Long> ready = new TreeSet<>(sidingIds);
		DutyDeploy<T> best = null;
		for (final Map.Entry<Long, List<DepotDuties.Slot>> entry : duties.sidings.entrySet()) {
			final long dutySidingId = entry.getKey();
			final List<DepotDuties.Slot> slots = entry.getValue();
			for (int i = 0; i < slots.size(); i++) {
				final DepotDuties.DayDuty duty = slots.get(i).getDuty(weekday);
				if (duty == null || duty.segments.isEmpty() || hasDutyRun(dutySidingId, i, day) || DutyShapes.getOvernightSlot(slots, i, weekday) >= 0) {
					continue;
				}
				final String key = DutyShapes.getSlotKey(slots, i, weekday, routes);
				if (key == null) {
					continue;
				}
				// Where the train comes from, as the duty says (S8): its own siding first, else the first siding of its pool that
				// has a train ready and the path of the duty; its own siding alone; or whichever of the pool has the quickest way
				// out to the first stop, by ID when as quick
				final int start = slots.get(i).start;
				long sidingId = 0;
				TripPath<T> dutyPath = null;
				if (start != DepotDuties.Slot.START_ANY && ready.contains(dutySidingId)) {
					dutyPath = getPath(tripPaths, dutySidingId, key);
					sidingId = dutySidingId;
				}
				if (dutyPath == null && start != DepotDuties.Slot.START_ONLY) {
					final Object pool = pools.apply(dutySidingId);
					for (final long otherSidingId : ready) {
						if ((start == DepotDuties.Slot.START_ANY || otherSidingId != dutySidingId) && Objects.equals(pool, pools.apply(otherSidingId))) {
							final TripPath<T> otherPath = getPath(tripPaths, otherSidingId, key);
							if (otherPath != null && (dutyPath == null || start == DepotDuties.Slot.START_ANY && otherPath.stopTimes.getLeadMillis() < dutyPath.stopTimes.getLeadMillis())) {
								dutyPath = otherPath;
								sidingId = otherSidingId;
								if (start != DepotDuties.Slot.START_ANY) {
									break;
								}
							}
						}
					}
				}
				if (dutyPath == null) {
					continue;
				}
				final long departureMillis = DutyShapes.getServiceMillis(dayStart, duty.segments.get(0).service) - dutyPath.stopTimes.getLeadMillis() - DUTY_DEPLOY_MARGIN_MILLIS;
				// Missed by much, as when the depot had no train or the server stopped, the train joins its duty where it can
				// still keep to it instead of the duty being left out, while the duty runs
				final boolean late = currentMillis > departureMillis + RouteTimetable.MAX_DEPLOY_DELAY_MILLIS;
				final boolean running = duty.stayOnLine || currentMillis < departureMillis + dutyPath.stopTimes.getDurationMillis();
				if (currentMillis >= departureMillis && (!late || running) && (best == null || best.late && !late || best.late == late && departureMillis < best.departureMillis)) {
					best = new DutyDeploy<>(sidingId, dutySidingId, i, dayStart, dutyPath, departureMillis, late);
				}
			}
		}
		if (best != null) {
			markDutyRun(best.dutySidingId, best.slot, day);
			lastReleaseMillis = currentMillis;
		}
		return best;
	}

	private static <T extends PlanTrack> TripPath<T> getPath(LongFunction<Map<String, TripPath<T>>> tripPaths, long sidingId, String key) {
		final Map<String, TripPath<T>> sidingPaths = tripPaths.apply(sidingId);
		return sidingPaths == null ? null : sidingPaths.get(key);
	}

	/**
	 * Where a train of a pool goes back to after its duty (decoupling document, step D, depots sending trains out 5): of the
	 * pool's sidings with room, no train standing in it or heading to it, the one whose next duty today that hasn't left
	 * starts soonest; its own siding if none of them needs a train.
	 *
	 * @param poolSidingIds the sidings of the train's pool, its own among them
	 * @param hasRoom       whether a siding has room for one more train
	 * @return the siding to go back to
	 */
	public long chooseReturnSiding(long ownSidingId, Collection<Long> poolSidingIds, DepotDuties duties, long currentMillis, LongPredicate hasRoom) {
		return chooseReturnSiding(ownSidingId, poolSidingIds, duties::getSlots, this::hasDutyRun, currentMillis, hasRoom);
	}

	/**
	 * Whether a siding's slot has run its duty on a day, as the dispatcher of the siding's own depot knows.
	 */
	@FunctionalInterface
	public interface DutyRuns {
		boolean hasDutyRun(long sidingId, int slot, long day);
	}

	/**
	 * As {@link #chooseReturnSiding(long, Collection, DepotDuties, long, LongPredicate)}, for a pool whose sidings may be of
	 * different depots of an operating line, each with its own duties and dispatcher.
	 *
	 * @param slotsOf a siding's duty slots, from its own depot
	 * @param runs    whether a siding's slot has run today, from its own depot
	 */
	public static long chooseReturnSiding(long ownSidingId, Collection<Long> poolSidingIds, java.util.function.LongFunction<List<DepotDuties.Slot>> slotsOf, DutyRuns runs, long currentMillis, LongPredicate hasRoom) {
		final long dayStart = DutyShapes.getDayStart(currentMillis);
		final long day = DutyShapes.getDayNumber(dayStart);
		final int weekday = OperatingDay.getWeekday(currentMillis);
		long bestSidingId = ownSidingId;
		long bestMillis = Long.MAX_VALUE;
		for (final long sidingId : new TreeSet<>(poolSidingIds)) {
			if (!hasRoom.test(sidingId)) {
				continue;
			}
			final List<DepotDuties.Slot> slots = slotsOf.apply(sidingId);
			for (int i = 0; i < slots.size(); i++) {
				final DepotDuties.DayDuty duty = slots.get(i).getDuty(weekday);
				if (duty == null || duty.segments.isEmpty() || runs.hasDutyRun(sidingId, i, day) || DutyShapes.getOvernightSlot(slots, i, weekday) >= 0) {
					continue;
				}
				final long serviceMillis = DutyShapes.getServiceMillis(dayStart, duty.segments.get(0).service);
				// Its own siding wins a tie, so a train only goes elsewhere for a duty that needs it sooner
				if (serviceMillis < bestMillis || serviceMillis == bestMillis && sidingId == ownSidingId) {
					bestMillis = serviceMillis;
					bestSidingId = sidingId;
				}
			}
		}
		return bestSidingId;
	}

	/**
	 * A depot sending trains out by frequency rather than by a timetable sends one out when the time of the day has passed
	 * one of its departures and the last train it sent left before that departure.
	 *
	 * @param departures         the departures as times of the day, in order
	 * @param lastDeployedMillis when the depot last sent a train out
	 * @param offset             which departure, counting from 1 for the one now due or next
	 * @return for the first departure, 0 if a train is due now, else the time until the next departure; for a later one,
	 * the time until it; -1 if there is no such departure
	 */
	public static int getMillisUntilDeploy(List<Integer> departures, long lastDeployedMillis, long currentMillis, int offset) {
		final long millis = currentMillis % OperatingDay.MILLIS_PER_DAY;
		for (int i = 0; i < departures.size(); i++) {
			final long thisDeparture = departures.get(i);
			final long nextDeparture = wrapTime(departures.get((i + 1) % departures.size()), thisDeparture);
			final long newMillis = wrapTime(millis, thisDeparture);
			if (newMillis > thisDeparture && newMillis <= nextDeparture) {
				if (offset > 1) {
					if (offset <= departures.size()) {
						return (int) (wrapTime(departures.get((i + offset) % departures.size()), millis) - millis);
					}
				} else {
					return wrapTime(lastDeployedMillis, newMillis) - OperatingDay.MILLIS_PER_DAY >= thisDeparture ? (int) (nextDeparture - newMillis) : 0;
				}
			}
		}
		return -1;
	}

	/**
	 * @return the time of the day moved on by whole days until it is after the other time
	 */
	private static long wrapTime(long time, long mustBeGreaterThan) {
		long newTime = time % OperatingDay.MILLIS_PER_DAY;
		while (newTime <= mustBeGreaterThan) {
			newTime += OperatingDay.MILLIS_PER_DAY;
		}
		return newTime;
	}

	public static long getTripKey(long routeId, long tripMillis) {
		return routeId * 31 + tripMillis;
	}

	/**
	 * A slot's duty to send out from a siding.
	 */
	public static class DutyDeploy<T extends PlanTrack> {

		/**
		 * The siding the train leaves from.
		 */
		public final long sidingId;
		/**
		 * The siding whose slot it runs, another one of the pool when the train is sent in place of that siding's.
		 */
		public final long dutySidingId;
		public final int slot;
		/**
		 * When the operating day of the duty started, as a full timestamp.
		 */
		public final long dayStart;
		public final TripPath<T> path;
		private final long departureMillis;
		/**
		 * Sent out much later than its duty needed: the train is put at the first stop of its duty it can still keep to
		 * (see {@link TrainService#joinDutyLate}).
		 */
		public final boolean late;

		private DutyDeploy(long sidingId, long dutySidingId, int slot, long dayStart, TripPath<T> path, long departureMillis, boolean late) {
			this.sidingId = sidingId;
			this.dutySidingId = dutySidingId;
			this.slot = slot;
			this.dayStart = dayStart;
			this.path = path;
			this.departureMillis = departureMillis;
			this.late = late;
		}
	}
}
