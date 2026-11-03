package transitsim.planning;

import transitsim.planning.OperationPlan.Duty;
import transitsim.planning.OperationPlan.Issue;
import transitsim.planning.OperationPlan.IssueType;
import transitsim.planning.OperationPlan.Phase;
import transitsim.planning.OperationPlan.PhaseType;
import transitsim.planning.OperationPlan.Trip;
import transitsim.planning.OperationPlan.TripStop;

import java.util.ArrayList;
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
	private final Map<Long, List<TripStop>> platformStops = new TreeMap<>();

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
					trips.add(new Trip(service.id, stops));
				}
			}
		});

		trips.sort(Comparator.comparingLong(Trip::startMillis).thenComparingLong(trip -> trip.sourceId));
		trips.forEach(trip -> trip.stops.forEach(stop -> platformStops.computeIfAbsent(stop.platformId, key -> new ArrayList<>()).add(stop)));
		platformStops.values().forEach(stops -> stops.sort(Comparator.comparingLong(stop -> stop.arrivalMillis)));
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
		return buildPlan(week, week, false);
	}

	private WeekResult runWeek(WeekStart start) {
		final WeekResult result = new WeekResult();
		final Map<Long, List<Long>> depotReady = new TreeMap<>();
		network.depots().forEach(depot -> {
			final List<Long> ready = new ArrayList<>();
			for (int i = 0; i < depot.trainCount - start.trainsOut(depot.id); i++) {
				ready.add(i < start.lateReturns(depot.id).size() ? start.lateReturns(depot.id).get(i) : Long.MIN_VALUE);
			}
			ready.sort(Comparator.naturalOrder());
			depotReady.put(depot.id, ready);
		});
		final List<OpenDuty> open = new ArrayList<>();
		start.carried.forEach(carried -> open.add(carried.shiftedToNextWeek()));

		for (final Trip trip : trips) {
			retireIdleTrains(open, depotReady, trip.startMillis(), result);
			final OpenDuty duty = findTrainInService(open, trip);
			if (duty != null) {
				duty.add(trip, network);
			} else {
				final OpenDuty fromDepot = takeFromDepot(depotReady, trip);
				if (fromDepot == null) {
					result.uncovered.add(trip);
				} else {
					open.add(fromDepot);
					fromDepot.add(trip, network);
				}
			}
			updatePeak(result, depotReady, trip.startMillis());
		}

		retireIdleTrains(open, depotReady, OperationTime.WEEK_MILLIS, result);
		result.carried.addAll(open);
		depotReady.forEach((depotId, ready) -> ready.stream().filter(time -> time > OperationTime.WEEK_MILLIS).forEach(time -> result.lateReturns.add(new long[]{depotId, time - OperationTime.WEEK_MILLIS})));
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
				open.remove(i);
				final long linkMillis = network.depot(duty.depotId).linkMillis(duty.platformId);
				final List<Long> ready = depotReady.get(duty.depotId);
				ready.add(duty.readyMillis + Math.max(linkMillis, 0));
				ready.sort(Comparator.naturalOrder());
				duty.returnDepotAtMillis = duty.readyMillis + Math.max(linkMillis, 0);
				result.finished.add(duty);
			} else {
				i++;
			}
		}
	}

	private OpenDuty findTrainInService(List<OpenDuty> open, Trip trip) {
		OpenDuty best = null;
		for (final OpenDuty duty : open) {
			final long connectionMillis = network.connectionMillis(duty.platformId, trip.firstPlatform());
			final boolean reachable = connectionMillis != OperationNetwork.UNREACHABLE && duty.readyMillis + connectionMillis <= trip.startMillis();
			final boolean samePlatform = duty.platformId == trip.firstPlatform();
			if (reachable && (!samePlatform || isPlatformFree(duty.platformId, duty.endMillis, trip.startMillis())) && (best == null || duty.readyMillis > best.readyMillis)) {
				best = duty;
			}
		}
		return best;
	}

	private OpenDuty takeFromDepot(Map<Long, List<Long>> depotReady, Trip trip) {
		OperationNetwork.Depot bestDepot = null;
		long bestLinkMillis = Long.MAX_VALUE;
		for (final OperationNetwork.Depot depot : network.depots()) {
			final long linkMillis = depot.linkMillis(trip.firstPlatform());
			final List<Long> ready = depotReady.get(depot.id);
			if (linkMillis != OperationNetwork.UNREACHABLE && linkMillis < bestLinkMillis && !ready.isEmpty() && ready.get(0) + linkMillis <= trip.startMillis()) {
				bestDepot = depot;
				bestLinkMillis = linkMillis;
			}
		}
		if (bestDepot == null) {
			return null;
		}
		depotReady.get(bestDepot.id).remove(0);
		return new OpenDuty(bestDepot.id, bestLinkMillis);
	}

	/**
	 * True if nothing occupies the platform strictly between the two times, including across the week boundary.
	 */
	private boolean isPlatformFree(long platformId, long fromMillis, long toMillis) {
		final List<TripStop> stops = platformStops.get(platformId);
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
			} else {
				final int nextIndex = indexByFirstTrip.get(duty.trips.get(0).key());
				nextDuties.put(duty.carriedFrom, nextIndex);
				previousDuties.put(openDuties.get(nextIndex), indices.get(duty.carriedFrom));
			}
		});

		final List<Duty> duties = new ArrayList<>();
		openDuties.forEach(duty -> {
			final int nextIndex = nextDuties.getOrDefault(duty, OperationPlan.NONE);
			final long nextStartMillis = nextIndex == OperationPlan.NONE ? Long.MIN_VALUE : openDuties.get(nextIndex).trips.get(0).startMillis() + OperationTime.WEEK_MILLIS;
			final long nextPlatform = nextIndex == OperationPlan.NONE ? 0 : openDuties.get(nextIndex).trips.get(0).firstPlatform();
			duties.add(new Duty(indices.get(duty), duty.depotId, duty.trips, previousDuties.getOrDefault(duty, OperationPlan.NONE), nextIndex, buildPhases(duty, previousDuties.containsKey(duty), nextIndex != OperationPlan.NONE, nextStartMillis, nextPlatform)));
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

	private static final class WeekResult {

		private final List<OpenDuty> finished = new ArrayList<>();
		private final List<OpenDuty> carried = new ArrayList<>();
		private final List<long[]> lateReturns = new ArrayList<>();
		private final List<Trip> uncovered = new ArrayList<>();
		private int peakTrains;

		private WeekStart nextStart() {
			final WeekStart start = new WeekStart();
			start.carried.addAll(carried);
			lateReturns.forEach(lateReturn -> start.lateReturnsByDepot.computeIfAbsent(lateReturn[0], key -> new ArrayList<>()).add(lateReturn[1]));
			return start;
		}
	}

	private static final class WeekStart {

		private final List<OpenDuty> carried = new ArrayList<>();
		private final Map<Long, List<Long>> lateReturnsByDepot = new TreeMap<>();

		private int trainsOut(long depotId) {
			return (int) carried.stream().filter(duty -> duty.depotId == depotId).count();
		}

		private List<Long> lateReturns(long depotId) {
			return lateReturnsByDepot.getOrDefault(depotId, new ArrayList<>());
		}

		private String signature() {
			final List<String> parts = new ArrayList<>();
			carried.forEach(duty -> parts.add(duty.depotId + ":" + duty.platformId + ":" + duty.readyMillis));
			lateReturnsByDepot.forEach((depotId, times) -> times.forEach(time -> parts.add(depotId + ":" + time)));
			parts.sort(Comparator.naturalOrder());
			return String.join(",", parts);
		}
	}
}
