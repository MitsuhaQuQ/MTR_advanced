package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.planning.OperationPlan.Duty;
import transitsim.planning.OperationPlan.Issue;
import transitsim.planning.OperationPlan.IssueType;
import transitsim.planning.OperationPlan.Phase;
import transitsim.planning.OperationPlan.PhaseType;
import transitsim.planning.OperationPlan.Trip;
import transitsim.planning.OperationPlan.TripStop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the weekly train assignment from the timetable.
 * <p>
 * Trips are served in time order. Each takes the train that became ready most recently at a reachable platform,
 * otherwise a train from the nearest depot. A train with nothing to do within {@code maxLayoverMillis} returns to
 * its own depot. The week is then replayed from its own end state until the end state stops changing, which joins
 * Sunday night to Monday morning. Every choice has a fixed tie-break and input order does not matter.
 */
public final class DutyPlanner {

	private static final int MAX_WEEKS = 16;

	private final OperationNetwork network;
	private final long maxLayoverMillis;
	private final List<Issue> issues = new ArrayList<>();
	private final List<Trip> trips = new ArrayList<>();
	/**
	 * The trips with orders set on the running diagram, and the service they are of.
	 */
	private final Map<Trip, TimetableService> ordered = new IdentityHashMap<>();
	private final Map<Long, List<TripStop>> platformStops = new TreeMap<>();
	/**
	 * By pool, the trains done with their duties and not yet sent to a siding: the runtime sends each, when it reaches its
	 * last stop, to the siding of its pool whose next duty of the day leaves soonest (DepotDispatcher.chooseReturnSiding),
	 * which here is the siding the pool next sends a train out of. Filled as the week runs.
	 */
	private final Map<String, List<Returning>> returning = new TreeMap<>();

	private DutyPlanner(OperationNetwork network, long maxLayoverMillis) {
		this.network = network;
		this.maxLayoverMillis = maxLayoverMillis;
	}

	public static OperationPlan plan(List<TimetableService> services, List<TimetableService.Through> throughs, OperationNetwork network, long maxLayoverMillis) {
		final DutyPlanner planner = new DutyPlanner(network, maxLayoverMillis);
		planner.expandTrips(services, throughs);
		return planner.solve();
	}

	private void expandTrips(List<TimetableService> services, List<TimetableService.Through> throughs) {
		final Map<Long, TimetableService> serviceMap = new TreeMap<>();
		services.forEach(service -> serviceMap.put(service.id, service));
		final Map<Long, TimetableService.Through> throughMap = new TreeMap<>();
		throughs.forEach(through -> throughMap.put(through.id, through));
		final Set<String> takenByThrough = new HashSet<>();

		throughMap.values().forEach(through -> {
			for (int day = 0; day < 7; day++) {
				if ((through.daysMask >> day & 1) != 0) {
					expandThrough(through, day, serviceMap, takenByThrough);
				}
			}
		});

		serviceMap.values().forEach(service -> {
			for (int day = 0; day < 7; day++) {
				if (!service.runsOn(day) || takenByThrough.contains(service.id + "/" + day)) {
					continue;
				}
				if (service.blank) {
					issues.add(new Issue(IssueType.UNUSED_BLANK, service.id + " day " + day));
				} else {
					final List<TripStop> stops = new ArrayList<>();
					appendStops(stops, service, day * OperationTime.DAY_MILLIS + service.firstArrivalMillis);
					final Trip trip = new Trip(service.id, stops);
					trips.add(trip);
					if (service.startDepots != null || service.endsDuty || service.returnDepots != null) {
						ordered.put(trip, service);
					}
				}
			}
		});

		trips.sort(Comparator.comparingLong(Trip::startMillis).thenComparingLong(trip -> trip.sourceId));
		// Filled as trips get trains (occupy), so a service no train runs doesn't keep a train from waiting at its platform
	}

	private void expandThrough(TimetableService.Through through, int day, Map<Long, TimetableService> serviceMap, Set<String> takenByThrough) {
		final List<TripStop> stops = new ArrayList<>();
		final List<String> taken = new ArrayList<>();
		int segmentDay = day;

		for (final long serviceId : through.serviceIds) {
			final TimetableService service = serviceMap.get(serviceId);
			if (service == null) {
				issues.add(new Issue(IssueType.THROUGH_CONFLICT, through.id + " day " + day + ": missing service " + serviceId));
				return;
			}

			long startMillis = segmentDay * OperationTime.DAY_MILLIS + service.firstArrivalMillis;
			if (!stops.isEmpty()) {
				final TripStop last = stops.get(stops.size() - 1);
				// A later segment that reads as earlier in the day has crossed midnight
				if (startMillis < last.departureMillis) {
					segmentDay++;
					startMillis += OperationTime.DAY_MILLIS;
				}
				final long connectionMillis = network.connectionMillis(last.platformId, service.stops.get(0).platformId);
				if (connectionMillis == OperationNetwork.UNREACHABLE || last.departureMillis + connectionMillis > startMillis) {
					issues.add(new Issue(IssueType.THROUGH_CONFLICT, through.id + " day " + day + ": cannot reach service " + serviceId));
					return;
				}
			}
			if (!service.runsOn(segmentDay % 7)) {
				issues.add(new Issue(IssueType.THROUGH_CONFLICT, through.id + " day " + day + ": service " + serviceId + " does not run"));
				return;
			}

			appendStops(stops, service, startMillis);
			taken.add(serviceId + "/" + segmentDay % 7);
		}

		takenByThrough.addAll(taken);
		trips.add(new Trip(through.id, stops));
	}

	private static void appendStops(List<TripStop> stops, TimetableService service, long startMillis) {
		service.stops.forEach(stop -> stops.add(new TripStop(service.id, stop.platformId, startMillis + stop.arrivalOffsetMillis, startMillis + stop.departureOffsetMillis, stop.pass)));
	}

	private OperationPlan solve() {
		WeekResult week = runWeek(new WeekStart());
		for (int i = 0; i < MAX_WEEKS; i++) {
			final WeekResult nextWeek = runWeek(week.nextStart());
			final boolean settled = nextWeek.nextStart().signature().equals(week.nextStart().signature());
			week = nextWeek;
			if (settled) {
				return buildPlan(week, runWeek(week.nextStart()), true);
			}
		}
		// No week repeats the one before, as when a train that never goes back to its depot ends each week at the other end of
		// the line: the week is planned on its own, from every train in its depot, and each train still out at its end goes
		// back then, so every day is planned and every week is the same
		final WeekResult alone = runWeek(new WeekStart());
		return buildPlan(alone, runWeek(new WeekStart()), false);
	}

	private WeekResult runWeek(WeekStart start) {
		final WeekResult result = new WeekResult();
		platformStops.clear();
		returning.clear();
		final Map<Long, List<Long>> depotReady = new TreeMap<>();
		network.depots().forEach(depot -> {
			// The trains in the siding as the week before left them, which may have gone back to other sidings of the pool
			final List<Long> ready = new ArrayList<>();
			final List<Long> stocked = start.stock.get(depot.id);
			if (stocked == null) {
				for (int i = 0; i < depot.trainCount; i++) {
					ready.add(Long.MIN_VALUE);
				}
			} else {
				ready.addAll(stocked);
			}
			ready.sort(Comparator.naturalOrder());
			depotReady.put(depot.id, ready);
		});
		final List<OpenDuty> open = new ArrayList<>();
		start.carried.forEach(carried -> open.add(carried.shiftedToNextWeek()));

		for (final Trip trip : trips) {
			retireIdleTrains(open, depotReady, trip.startMillis(), result);
			landBeforeDay(trip.startMillis(), depotReady);
			// Set on the running diagram: a trip a train has to come out of a depot for, and one after which it goes back
			final TimetableService order = ordered.get(trip);
			final Set<Long> startDepots = order == null ? null : order.startDepots;
			// Going back to a depot with no siding planned here: no train runs it, rather than one going back elsewhere (P1-B)
			if (order != null && order.returnDepots != null && order.returnDepots.isEmpty()) {
				result.uncovered.add(trip);
				continue;
			}
			OpenDuty duty = startDepots == null ? findTrainInService(open, trip) : null;
			if (duty != null) {
				duty.add(trip, network);
				occupy(trip);
			} else {
				duty = takeFromDepot(depotReady, trip, startDepots);
				if (duty == null) {
					result.uncovered.add(trip);
				} else {
					open.add(duty);
					duty.add(trip, network);
					occupy(trip);
				}
			}
			if (duty != null && order != null && order.endsDuty) {
				retire(open, duty, result, order.returnDepots);
			}
			updatePeak(result, depotReady, trip.startMillis());
		}

		retireIdleTrains(open, depotReady, OperationTime.WEEK_MILLIS, result);
		landBeforeDay(Long.MAX_VALUE, depotReady);
		result.carried.addAll(open);
		depotReady.forEach((depotId, ready) -> {
			final List<Long> stock = new ArrayList<>();
			ready.forEach(time -> stock.add(time > OperationTime.WEEK_MILLIS ? time - OperationTime.WEEK_MILLIS : Long.MIN_VALUE));
			result.stock.put(depotId, stock);
		});
		return result;
	}

	private void updatePeak(WeekResult result, Map<Long, List<Long>> depotReady, long timeMillis) {
		int trainsOut = 0;
		for (final OperationNetwork.Depot depot : network.depots()) {
			trainsOut += depot.trainCount - (int) depotReady.get(depot.id).stream().filter(time -> time <= timeMillis).count();
		}
		result.peakTrains = Math.max(result.peakTrains, trainsOut);
	}

	private void retireIdleTrains(List<OpenDuty> open, Map<Long, List<Long>> depotReady, long nowMillis, WeekResult result) {
		for (int i = 0; i < open.size(); ) {
			final OpenDuty duty = open.get(i);
			if (duty.readyMillis + maxLayoverMillis < nowMillis) {
				retire(open, duty, result, null);
			} else {
				i++;
			}
		}
	}

	/**
	 * Ends a train's duty: it goes back to a siding of its pool, which one decided as the runtime decides it (P1-B), when
	 * the pool next sends a train out ({@link #takeFromDepot}) or, if none does that day, to its own.
	 *
	 * @param allowed the sidings it is set to go back to, or null for any of its pool
	 */
	private void retire(List<OpenDuty> open, OpenDuty duty, WeekResult result, Set<Long> allowed) {
		open.remove(duty);
		result.finished.add(duty);
		// Of the depot set, only the sidings of the same pool take the train, as at runtime; with none it goes back as usual
		final String poolKey = network.depot(duty.depotId).poolKey;
		Set<Long> sameVehicle = null;
		if (allowed != null) {
			sameVehicle = new java.util.TreeSet<>();
			for (final long depotId : allowed) {
				if (depotId == duty.depotId || network.depot(depotId).poolKey.equals(poolKey)) {
					sameVehicle.add(depotId);
				}
			}
			if (sameVehicle.isEmpty()) {
				sameVehicle = null;
			}
		}
		final long fallback = sameVehicle == null || sameVehicle.contains(duty.depotId) ? duty.depotId : sameVehicle.iterator().next();
		returning.computeIfAbsent(poolKey, key -> new ArrayList<>()).add(new Returning(duty, sameVehicle, fallback));
	}

	/**
	 * The train is back in the siding once it has run there from its last stop.
	 */
	private void land(Returning train, long depotId, Map<Long, List<Long>> depotReady) {
		final OpenDuty duty = train.duty;
		final long backMillis = Math.max(network.depot(depotId).backMillis(duty.platformId), 0);
		final List<Long> ready = depotReady.get(depotId);
		ready.add(duty.readyMillis + backMillis);
		ready.sort(Comparator.naturalOrder());
		duty.returnDepotAtMillis = duty.readyMillis + backMillis;
		duty.returnDepotId = depotId;
	}

	/**
	 * Trains that reached their last stop on an earlier day than this, with no siding of their pool sending a train out
	 * after them that day, went back to their own (or the first of the depot set) then.
	 */
	private void landBeforeDay(long nowMillis, Map<Long, List<Long>> depotReady) {
		final long day = nowMillis == Long.MAX_VALUE ? Long.MAX_VALUE : Math.floorDiv(nowMillis, OperationTime.DAY_MILLIS);
		returning.values().forEach(trains -> trains.removeIf(train -> {
			if (Math.floorDiv(train.duty.endMillis, OperationTime.DAY_MILLIS) < day) {
				land(train, train.fallback, depotReady);
				return true;
			}
			return false;
		}));
	}

	/**
	 * Whether a train coming back to the pool may be sent to this siding, as the runtime sends it: the siding needs a train
	 * before it reached its last stop, on the same day, is one it may go back to, and has room (its own always has, and so
	 * does one with no limit, as one whose fleet is -1 at runtime).
	 */
	private boolean maySendTo(Returning train, OperationNetwork.Depot depot, long leaveMillis, Map<Long, List<Long>> depotReady) {
		final OpenDuty duty = train.duty;
		return duty.endMillis <= leaveMillis && Math.floorDiv(duty.endMillis, OperationTime.DAY_MILLIS) == Math.floorDiv(leaveMillis, OperationTime.DAY_MILLIS)
				&& (train.allowed == null || train.allowed.contains(depot.id))
				&& (depot.id == duty.depotId || depot.trainCount >= DepotDuties.MAX_SLOTS || depotReady.get(depot.id).size() < depot.trainCount);
	}

	private OpenDuty findTrainInService(List<OpenDuty> open, Trip trip) {
		OpenDuty best = null;
		OpenDuty missed = null;
		for (final OpenDuty duty : open) {
			final long connectionMillis = network.connectionMillis(duty.platformId, trip.firstPlatform());
			// A train staying on the line overnight goes on the next day from the platform it stopped at: the next day's
			// duty starts on the same track, so a change of platform is only within one operating day
			final boolean overnightMove = duty.platformId != trip.firstPlatform() && Math.floorDiv(duty.endMillis, OperationTime.DAY_MILLIS) != Math.floorDiv(trip.startMillis(), OperationTime.DAY_MILLIS);
			final boolean reachable = !overnightMove && connectionMillis != OperationNetwork.UNREACHABLE && duty.readyMillis + connectionMillis <= trip.startMillis();
			final boolean samePlatform = duty.platformId == trip.firstPlatform();
			if (reachable && (!samePlatform || isPlatformFree(duty.platformId, duty.endMillis, trip.startMillis())) && (best == null || duty.readyMillis > best.readyMillis)) {
				best = duty;
			}
			// Arrived at the platform before the trip leaves, but too late to turn round for it
			if (samePlatform && connectionMillis != OperationNetwork.UNREACHABLE && duty.endMillis <= trip.startMillis() && duty.readyMillis + connectionMillis > trip.startMillis() && (missed == null || duty.endMillis > missed.endMillis)) {
				missed = duty;
			}
		}
		if (best == null && missed != null) {
			final long given = trip.startMillis() - missed.endMillis;
			final long needed = missed.readyMillis + network.connectionMillis(missed.platformId, trip.firstPlatform()) - missed.endMillis;
			final long[] worst = shortTurnarounds.get(trip.firstPlatform());
			if (worst == null || given < worst[0]) {
				shortTurnarounds.put(trip.firstPlatform(), new long[]{given, needed});
			}
		}
		return best;
	}

	/**
	 * Platform → the least time a train arriving there had before a trip it couldn't turn round for, and the time it
	 * needed, in milliseconds.
	 */
	private final Map<Long, long[]> shortTurnarounds = new TreeMap<>();

	/**
	 * @param allowed the depots the train may come from, or null for any
	 */
	private OpenDuty takeFromDepot(Map<Long, List<Long>> depotReady, Trip trip, Set<Long> allowed) {
		OperationNetwork.Depot bestDepot = null;
		long bestLinkMillis = Long.MAX_VALUE;
		for (final OperationNetwork.Depot depot : network.depots()) {
			if (allowed != null && !allowed.contains(depot.id)) {
				continue;
			}
			final long linkMillis = depot.linkMillis(trip.firstPlatform());
			if (linkMillis == OperationNetwork.UNREACHABLE || linkMillis >= bestLinkMillis) {
				continue;
			}
			final long leaveMillis = trip.startMillis() - linkMillis;
			final List<Long> ready = depotReady.get(depot.id);
			// A train in the siding, or one of the pool coming back that the runtime sends here, being where a train is needed
			// next, and that is back in time
			final boolean hasTrain = !ready.isEmpty() && ready.get(0) <= leaveMillis || returning.getOrDefault(depot.poolKey, Collections.emptyList()).stream()
					.anyMatch(train -> maySendTo(train, depot, leaveMillis, depotReady) && train.duty.readyMillis + Math.max(depot.backMillis(train.duty.platformId), 0) <= leaveMillis);
			if (hasTrain) {
				bestDepot = depot;
				bestLinkMillis = linkMillis;
			}
		}
		if (bestDepot == null) {
			return null;
		}
		// Every train of the pool coming back before this one leaves goes to this siding, needing a train soonest
		final OperationNetwork.Depot chosen = bestDepot;
		final long leaveMillis = trip.startMillis() - bestLinkMillis;
		final List<Returning> coming = returning.getOrDefault(chosen.poolKey, Collections.emptyList());
		coming.sort(Comparator.comparingLong(train -> train.duty.endMillis));
		coming.removeIf(train -> {
			if (maySendTo(train, chosen, leaveMillis, depotReady)) {
				land(train, chosen.id, depotReady);
				return true;
			}
			return false;
		});
		depotReady.get(chosen.id).remove(0);
		return new OpenDuty(chosen.id, bestLinkMillis);
	}

	/**
	 * Records the stops of a trip a train runs, which keep other trains from waiting at those platforms then.
	 */
	private void occupy(Trip trip) {
		trip.stops.forEach(stop -> {
			final List<TripStop> stops = platformStops.computeIfAbsent(stop.platformId, key -> new ArrayList<>());
			int at = stops.size();
			while (at > 0 && stops.get(at - 1).arrivalMillis > stop.arrivalMillis) {
				at--;
			}
			stops.add(at, stop);
		});
	}

	/**
	 * True if no trip a train runs occupies the platform strictly between the two times, including across the week
	 * boundary. Only trips with a train count: a service no train runs doesn't stand at the platform.
	 */
	private boolean isPlatformFree(long platformId, long fromMillis, long toMillis) {
		final List<TripStop> stops = platformStops.getOrDefault(platformId, Collections.emptyList());
		for (int week = -1; week <= 1; week++) {
			final long shift = week * OperationTime.WEEK_MILLIS;
			int low = 0;
			int high = stops.size();
			while (low < high) {
				final int middle = (low + high) >>> 1;
				if (stops.get(middle).arrivalMillis + shift <= fromMillis) {
					low = middle + 1;
				} else {
					high = middle;
				}
			}
			if (low < stops.size() && stops.get(low).arrivalMillis + shift < toMillis) {
				return false;
			}
		}
		return true;
	}

	private OperationPlan buildPlan(WeekResult week, WeekResult following, boolean settled) {
		final List<Issue> planIssues = new ArrayList<>(issues);
		if (!settled) {
			planIssues.add(new Issue(IssueType.NOT_PERIODIC, "No repeating week within " + MAX_WEEKS + " weeks"));
		}
		week.uncovered.forEach(trip -> planIssues.add(new Issue(IssueType.NO_TRAIN, trip.key())));
		// Detail: the platform, the time trains had there to turn round and the time they needed, in milliseconds
		shortTurnarounds.forEach((platformId, times) -> planIssues.add(new Issue(IssueType.SHORT_TURNAROUND, platformId + " " + times[0] + " " + times[1])));

		final List<OpenDuty> openDuties = new ArrayList<>(week.finished);
		openDuties.addAll(week.carried);
		openDuties.removeIf(duty -> duty.trips.isEmpty());
		openDuties.sort(Comparator.comparingLong((OpenDuty duty) -> duty.trips.get(0).startMillis()).thenComparingLong(duty -> duty.trips.get(0).sourceId));
		final Map<OpenDuty, Integer> indices = new IdentityHashMap<>();
		final Map<String, Integer> indexByFirstTrip = new TreeMap<>();
		for (int i = 0; i < openDuties.size(); i++) {
			indices.put(openDuties.get(i), i);
			indexByFirstTrip.put(openDuties.get(i).trips.get(0).key(), i);
		}

		// The following week repeats this one, so a duty carried into it continues as the duty with the same first trip
		final Map<OpenDuty, Integer> nextDuties = new IdentityHashMap<>();
		final Map<OpenDuty, Integer> previousDuties = new IdentityHashMap<>();
		final List<OpenDuty> followingDuties = new ArrayList<>(following.finished);
		followingDuties.addAll(following.carried);
		followingDuties.stream().filter(duty -> duty.carriedFrom != null).forEach(duty -> {
			if (duty.trips.isEmpty()) {
				duty.carriedFrom.returnDepotAtMillis = duty.returnDepotAtMillis + OperationTime.WEEK_MILLIS;
				duty.carriedFrom.returnDepotId = duty.returnDepotId;
			} else {
				// A duty carried into the following week whose first trip or whose duty it came from isn't one of this week's
				// is left as one that doesn't carry on
				final Integer nextIndex = indexByFirstTrip.get(duty.trips.get(0).key());
				final Integer fromIndex = indices.get(duty.carriedFrom);
				if (nextIndex != null && fromIndex != null) {
					nextDuties.put(duty.carriedFrom, nextIndex);
					previousDuties.put(openDuties.get(nextIndex), fromIndex);
				}
			}
		});

		final List<Duty> duties = new ArrayList<>();
		openDuties.forEach(duty -> {
			final int nextIndex = nextDuties.getOrDefault(duty, OperationPlan.NONE);
			final long nextStartMillis = nextIndex == OperationPlan.NONE ? Long.MIN_VALUE : openDuties.get(nextIndex).trips.get(0).startMillis() + OperationTime.WEEK_MILLIS;
			final long nextPlatform = nextIndex == OperationPlan.NONE ? 0 : openDuties.get(nextIndex).trips.get(0).firstPlatform();
			duties.add(new Duty(indices.get(duty), duty.depotId, duty.trips, previousDuties.getOrDefault(duty, OperationPlan.NONE), nextIndex, buildPhases(duty, previousDuties.containsKey(duty), nextIndex != OperationPlan.NONE, nextStartMillis, nextPlatform), duty.returnDepotId == 0 ? duty.depotId : duty.returnDepotId));
		});

		checkPlatformOccupancy(duties, planIssues);
		return new OperationPlan(duties, planIssues, week.peakTrains);
	}

	private List<Phase> buildPhases(OpenDuty duty, boolean hasPrevious, boolean hasNext, long nextStartMillis, long nextPlatform) {
		final List<Phase> phases = new ArrayList<>();
		final Trip firstTrip = duty.trips.get(0);
		if (!hasPrevious && duty.leaveMillis > 0) {
			phases.add(new Phase(PhaseType.LEAVE_DEPOT, firstTrip.startMillis() - duty.leaveMillis, firstTrip.startMillis(), firstTrip.firstPlatform(), firstTrip.firstPlatform()));
		}

		for (int i = 0; i < duty.trips.size(); i++) {
			final Trip trip = duty.trips.get(i);
			for (int j = 0; j < trip.stops.size(); j++) {
				final TripStop stop = trip.stops.get(j);
				phases.add(new Phase(PhaseType.STOP, stop.arrivalMillis, stop.departureMillis, stop.platformId, stop.platformId));
				if (j + 1 < trip.stops.size()) {
					final TripStop nextStop = trip.stops.get(j + 1);
					phases.add(new Phase(PhaseType.RUN, stop.departureMillis, nextStop.arrivalMillis, stop.platformId, nextStop.platformId));
				}
			}
			if (i + 1 < duty.trips.size()) {
				addLayover(phases, trip, duty.trips.get(i + 1).startMillis(), duty.trips.get(i + 1).firstPlatform());
			}
		}

		final Trip lastTrip = duty.trips.get(duty.trips.size() - 1);
		if (hasNext) {
			addLayover(phases, lastTrip, nextStartMillis, nextPlatform);
		} else {
			final long readyMillis = lastTrip.endMillis() + network.turnaroundMillis(lastTrip.lastPlatform());
			phases.add(new Phase(PhaseType.LAYOVER, lastTrip.endMillis(), readyMillis, lastTrip.lastPlatform(), lastTrip.lastPlatform()));
			final long returnedMillis = duty.returnDepotAtMillis > 0 ? duty.returnDepotAtMillis : readyMillis;
			phases.add(new Phase(PhaseType.RETURN_TO_DEPOT, readyMillis, returnedMillis, lastTrip.lastPlatform(), lastTrip.lastPlatform()));
		}
		return phases;
	}

	private void addLayover(List<Phase> phases, Trip trip, long nextStartMillis, long nextPlatform) {
		final long platform = trip.lastPlatform();
		final long connectionMillis = network.connectionMillis(platform, nextPlatform);
		final long leaveMillis = nextStartMillis - connectionMillis;
		phases.add(new Phase(PhaseType.LAYOVER, trip.endMillis(), leaveMillis, platform, platform));
		if (connectionMillis > 0) {
			phases.add(new Phase(PhaseType.CONNECT, leaveMillis, nextStartMillis, platform, nextPlatform));
		}
	}

	private void checkPlatformOccupancy(List<Duty> duties, List<Issue> planIssues) {
		final Map<Long, List<long[]>> occupancy = new TreeMap<>();
		duties.forEach(duty -> duty.phases.stream().filter(Phase::isAtPlatform).forEach(phase -> {
			final List<long[]> intervals = occupancy.computeIfAbsent(phase.fromPlatform, key -> new ArrayList<>());
			intervals.add(new long[]{phase.startMillis, phase.endMillis, duty.index});
			intervals.add(new long[]{phase.startMillis + OperationTime.WEEK_MILLIS, phase.endMillis + OperationTime.WEEK_MILLIS, duty.index});
		}));

		occupancy.forEach((platformId, intervals) -> {
			intervals.sort(Comparator.<long[]>comparingLong(interval -> interval[0]).thenComparingLong(interval -> interval[1]));
			long[] latest = null;
			for (final long[] interval : intervals) {
				if (latest != null && interval[0] < latest[1] && interval[2] != latest[2]) {
					planIssues.add(new Issue(IssueType.PLATFORM_CONFLICT, "platform " + platformId + " at " + OperationTime.normalize(interval[0]) + " duties " + latest[2] + "," + interval[2]));
				}
				if (latest == null || interval[1] > latest[1]) {
					latest = interval;
				}
			}
		});
	}

	private static final class OpenDuty {

		private final long depotId;
		private final long leaveMillis;
		private final OpenDuty carriedFrom;
		private final List<Trip> trips = new ArrayList<>();
		private long platformId;
		private long endMillis;
		private long readyMillis;
		private long returnDepotAtMillis;
		/**
		 * The depot it went back to, or 0 while it hasn't.
		 */
		private long returnDepotId;

		private OpenDuty(long depotId, long leaveMillis) {
			this(depotId, leaveMillis, null);
		}

		private OpenDuty(long depotId, long leaveMillis, OpenDuty carriedFrom) {
			this.depotId = depotId;
			this.leaveMillis = leaveMillis;
			this.carriedFrom = carriedFrom;
		}

		private void add(Trip trip, OperationNetwork network) {
			trips.add(trip);
			platformId = trip.lastPlatform();
			endMillis = trip.endMillis();
			readyMillis = endMillis + network.turnaroundMillis(platformId);
		}

		private OpenDuty shiftedToNextWeek() {
			final OpenDuty duty = new OpenDuty(depotId, 0, this);
			duty.platformId = platformId;
			duty.endMillis = endMillis - OperationTime.WEEK_MILLIS;
			duty.readyMillis = readyMillis - OperationTime.WEEK_MILLIS;
			return duty;
		}
	}

	/**
	 * A train done with its duty and on its way back, not yet sent to a siding.
	 */
	private static final class Returning {

		private final OpenDuty duty;
		private final Set<Long> allowed;
		private final long fallback;

		private Returning(OpenDuty duty, Set<Long> allowed, long fallback) {
			this.duty = duty;
			this.allowed = allowed;
			this.fallback = fallback;
		}
	}

	private static final class WeekResult {

		private final List<OpenDuty> finished = new ArrayList<>();
		private final List<OpenDuty> carried = new ArrayList<>();
		/**
		 * Each depot's trains at the end of the week, when each is ready the next: in it already, or back during it.
		 */
		private final Map<Long, List<Long>> stock = new TreeMap<>();
		private final List<Trip> uncovered = new ArrayList<>();
		private int peakTrains;

		private WeekStart nextStart() {
			final WeekStart start = new WeekStart();
			start.carried.addAll(carried);
			stock.forEach((depotId, times) -> start.stock.put(depotId, new ArrayList<>(times)));
			return start;
		}
	}

	private static final class WeekStart {

		private final List<OpenDuty> carried = new ArrayList<>();
		/**
		 * Each depot's trains as the week before left them, or none for every depot full.
		 */
		private final Map<Long, List<Long>> stock = new TreeMap<>();

		private String signature() {
			final List<String> parts = new ArrayList<>();
			carried.forEach(duty -> parts.add(duty.depotId + ":" + duty.platformId + ":" + duty.readyMillis));
			stock.forEach((depotId, times) -> {
				parts.add(depotId + "#" + times.size());
				times.stream().filter(time -> time != Long.MIN_VALUE).forEach(time -> parts.add(depotId + ":" + time));
			});
			parts.sort(Comparator.naturalOrder());
			return String.join(",", parts);
		}
	}
}
