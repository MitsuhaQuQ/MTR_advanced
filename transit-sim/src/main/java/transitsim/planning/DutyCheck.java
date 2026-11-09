package transitsim.planning;

import transitsim.plan.DepotDuties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether duties already planned still hold with new times (optimisation round 4, step 4): the run times, the ways out and
 * back, the changes of platform and the least time to change ends, as the planner takes them ({@link DutyGenerator}).
 * Checked by the planner's own rules ({@link DutyPlanner}): each train turns round in time for its next trip, waits on
 * the platform only while no other train of its group stops there and otherwise on the turn back rail beyond it, one
 * train of the group at a time there (each group is planned on its own and knows only its own trains), doesn't wait longer than the line lets it, and has a way out of its siding and back. Duties that hold are kept,
 * so a small change in the times leaves them as they are; one that breaks a rule, by however little, has them planned
 * again. Only the times are checked: what the duties are made of (the timetables, the sidings and their trains) is the
 * caller's to compare.
 */
public final class DutyCheck {

	public enum Kind {
		/**
		 * A duty runs a service, or part of one, that the timetables no longer have.
		 */
		NO_SERVICE,
		NO_WAY_OUT,
		NO_WAY_BACK,
		/**
		 * The train's next trip leaves from a platform it has no way to.
		 */
		NO_CONNECTION,
		/**
		 * Too little time between arriving and the next trip to change ends or platform.
		 */
		SHORT_TURNAROUND,
		/**
		 * Another train stops at the platform while the train waits there, and it can't wait on a turn back rail instead.
		 */
		PLATFORM_TAKEN,
		/**
		 * Two trains wait on the same turn back rail at once.
		 */
		TURN_BACK_RAIL_TAKEN,
		/**
		 * The train waits longer on the line than the line lets it.
		 */
		LONG_LAYOVER,
		/**
		 * A train staying on the line overnight has no duty the next morning from where it stopped.
		 */
		OVERNIGHT
	}

	/**
	 * A rule a duty breaks: on which siding, slot and day (0 Monday), where, and the time it had and needed, in
	 * milliseconds (0 where there is none).
	 */
	public static final class Break {

		public final long sidingId;
		public final long slotId;
		public final int day;
		public final Kind kind;
		public final long platformId;
		public final long givenMillis;
		public final long neededMillis;

		private Break(long sidingId, long slotId, int day, Kind kind, long platformId, long givenMillis, long neededMillis) {
			this.sidingId = sidingId;
			this.slotId = slotId;
			this.day = day;
			this.kind = kind;
			this.platformId = platformId;
			this.givenMillis = givenMillis;
			this.neededMillis = neededMillis;
		}

		@Override
		public String toString() {
			return kind + " siding " + sidingId + " slot " + slotId + " day " + day + (platformId == 0 ? "" : " platform " + platformId) + (givenMillis == 0 && neededMillis == 0 ? "" : " had " + givenMillis + " ms, needs " + neededMillis + " ms");
		}
	}

	/**
	 * Sidings planned together, as one call of the planner has them: the services they run, the sidings, and their duties.
	 */
	public static final class Group {

		public final List<DutyGenerator.ServiceInput> services;
		public final List<DutyGenerator.SidingInput> sidings;
		/**
		 * The duties of each siding, as {@link DutyGenerator.Result#sidings} has them.
		 */
		public final Map<Long, List<DepotDuties.Slot>> slots;

		public Group(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, Map<Long, List<DepotDuties.Slot>> slots) {
			this.services = services;
			this.sidings = sidings;
			this.slots = slots;
		}
	}

	private DutyCheck() {
	}

	/**
	 * @param groups           the groups, which share the platforms and turn back rails
	 * @param maxLayoverMillis how long a train may wait on the line for its next trip
	 * @return the rules broken, none if the duties hold
	 */
	public static List<Break> check(List<Group> groups, Map<Long, Long> stationOf, long maxLayoverMillis, DutyGenerator.PlanInputs inputs) {
		final List<Break> breaks = new ArrayList<>();
		final List<Piece> pieces = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			readGroup(groups.get(i), i, stationOf, inputs, pieces, breaks);
		}

		// A train staying on the line overnight goes on the next day from where it stopped, in its slot or, as the planner
		// gives slots, in another of the siding's
		final Set<Piece> continued = new HashSet<>();
		for (final Piece piece : pieces) {
			if (!piece.stayOnLine) {
				continue;
			}
			final Trip last = piece.trips.get(piece.trips.size() - 1);
			final int nextDay = (piece.day + 1) % 7;
			Piece next = null;
			for (final Piece other : pieces) {
				if (other.group == piece.group && other.sidingId == piece.sidingId && other.day == nextDay && !continued.contains(other) && other.trips.get(0).firstPlatform() == last.lastPlatform() && (next == null || other.slotId == piece.slotId && next.slotId != piece.slotId)) {
					next = other;
				}
			}
			if (next == null) {
				breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.OVERNIGHT, last.lastPlatform(), 0, 0));
			} else {
				continued.add(next);
				piece.next = next;
			}
		}

		// Each group as the planner plans it, on its own: only its own trains keep a platform or a turn back rail from it
		final List<Map<Long, List<long[]>>> groupStops = new ArrayList<>();
		final List<Map<Long, List<long[]>>> groupHolds = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			groupStops.add(new HashMap<>());
			groupHolds.add(new TreeMap<>());
		}
		pieces.forEach(piece -> piece.trips.forEach(trip -> trip.stops.forEach(stop -> groupStops.get(piece.group).computeIfAbsent(stop[0], key -> new ArrayList<>()).add(new long[]{stop[1], stop[2]}))));
		groupStops.forEach(platformStops -> platformStops.values().forEach(stops -> stops.sort(Comparator.comparingLong(stop -> stop[0]))));

		for (final Piece piece : pieces) {
			final Map<Long, List<long[]>> platformStops = groupStops.get(piece.group);
			final Map<Long, List<long[]>> holds = groupHolds.get(piece.group);
			final OperationNetwork.Depot siding = piece.network.depot(piece.sidingId);
			final Trip first = piece.trips.get(0);
			if (!continued.contains(piece) && siding.linkMillis(first.firstPlatform()) == OperationNetwork.UNREACHABLE) {
				breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.NO_WAY_OUT, first.firstPlatform(), 0, 0));
			}
			for (int i = 0; i + 1 < piece.trips.size(); i++) {
				checkTurn(piece, piece.trips.get(i), piece.trips.get(i + 1), maxLayoverMillis, platformStops, holds, breaks);
			}
			final Trip last = piece.trips.get(piece.trips.size() - 1);
			if (piece.next != null) {
				// The next morning, a week on from Sunday
				checkTurn(piece, last, piece.next.trips.get(0).shifted(piece.next.day < piece.day ? OperationTime.WEEK_MILLIS : 0), maxLayoverMillis, platformStops, holds, breaks);
			} else if (!piece.stayOnLine && !canGoBack(piece.network, siding, last.lastPlatform())) {
				breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.NO_WAY_BACK, last.lastPlatform(), 0, 0));
			}
		}

		// No more trains at once than there are places to wait beyond the platform, turn back rails and stabling blocks
		// (运用补完 step 4): a train coming in when they are all taken. Two coming in at the same time, the later listed
		// is the one that doesn't fit
		groupHolds.forEach(holds -> holds.forEach((platformId, intervals) -> {
			intervals.sort(Comparator.comparingLong(interval -> interval[0]));
			for (int j = 0; j < intervals.size(); j++) {
				final long[] b = intervals.get(j);
				int before = 0;
				for (int i = 0; i < intervals.size(); i++) {
					if (i == j) {
						continue;
					}
					final long[] a = intervals.get(i);
					for (int week = -1; week <= 1; week++) {
						final long shift = week * OperationTime.WEEK_MILLIS;
						final boolean on = a[0] + shift < b[0] && b[0] < a[1] + shift || a[0] + shift == b[0] && i < j;
						if (on) {
							before++;
						}
					}
				}
				if (before >= b[5]) {
					breaks.add(new Break(b[2], b[3], (int) b[4], Kind.TURN_BACK_RAIL_TAKEN, platformId, 0, 0));
				}
			}
		}));
		return breaks;
	}

	/**
	 * Reads each duty's days into trips, as the planner runs them that day.
	 */
	private static void readGroup(Group group, int groupIndex, Map<Long, Long> stationOf, DutyGenerator.PlanInputs inputs, List<Piece> pieces, List<Break> breaks) {
		final List<DutyGenerator.Chain> chains = DutyGenerator.buildChains(group.services);
		final OperationNetwork network = DutyGenerator.networkOf(DutyGenerator.timetableServicesOf(chains, group.sidings, new ArrayList<>()), group.sidings, stationOf, inputs);
		final Map<String, List<DutyGenerator.Chain>> chainsByFirst = new HashMap<>();
		chains.forEach(chain -> chainsByFirst.computeIfAbsent(segmentKey(chain.segments.get(0)), key -> new ArrayList<>()).add(chain));

		group.slots.forEach((sidingId, slots) -> slots.forEach(slot -> slot.days.forEach(dayDuty -> {
			for (int day = 0; day < 7; day++) {
				if ((dayDuty.weekdays >> day & 1) == 0 || dayDuty.segments.isEmpty()) {
					continue;
				}
				final List<Trip> trips = network.depot(sidingId) == null ? null : readTrips(dayDuty.segments, day, chainsByFirst);
				if (trips == null) {
					breaks.add(new Break(sidingId, slot.id, day, Kind.NO_SERVICE, 0, 0, 0));
				} else {
					pieces.add(new Piece(groupIndex, sidingId, slot.id, day, trips, dayDuty.stayOnLine, network));
				}
			}
		})));
	}

	/**
	 * @return the trips of a day's segments, each a chain the planner made of the services, run on the day the planner
	 * gives it, or null if a segment is of no such chain
	 */
	private static List<Trip> readTrips(List<DepotDuties.Segment> segments, int day, Map<String, List<DutyGenerator.Chain>> chainsByFirst) {
		final List<Trip> trips = new ArrayList<>();
		int position = 0;
		while (position < segments.size()) {
			Trip found = null;
			int length = 0;
			for (final DutyGenerator.Chain chain : chainsByFirst.getOrDefault(segmentKey(segments.get(position)), Collections.emptyList())) {
				final int size = chain.segments.size();
				if (position + size > segments.size() || !segments.subList(position, position + size).equals(chain.segments)) {
					continue;
				}
				// The day the chain runs on that the planner counts as this day: the one its first arrival falls in
				for (int runDay = 0; runDay < 7 && found == null; runDay++) {
					if ((chain.weekdays >> runDay & 1) == 0) {
						continue;
					}
					final long start = runDay * OperationTime.DAY_MILLIS + chain.startMillis;
					if (Math.floorMod(Math.floorDiv(start + chain.stops.get(0).arrivalOffsetMillis, OperationTime.DAY_MILLIS), 7L) == day) {
						found = new Trip(chain, start);
						length = size;
					}
				}
				if (found != null) {
					break;
				}
			}
			if (found == null) {
				return null;
			}
			trips.add(found);
			position += length;
		}
		return trips;
	}

	/**
	 * A train turning round from one trip for the next, by the rules the planner takes trains in service by.
	 */
	private static void checkTurn(Piece piece, Trip from, Trip to, long maxLayoverMillis, Map<Long, List<long[]>> platformStops, Map<Long, List<long[]>> holds, List<Break> breaks) {
		final OperationNetwork network = piece.network;
		final long platformId = from.lastPlatform();
		final long connectionMillis = network.connectionMillis(platformId, to.firstPlatform());
		if (connectionMillis == OperationNetwork.UNREACHABLE) {
			breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.NO_CONNECTION, platformId, 0, 0));
			return;
		}
		final long readyMillis = from.endMillis() + network.turnaroundMillis(platformId);
		if (readyMillis + connectionMillis > to.startMillis()) {
			breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.SHORT_TURNAROUND, platformId, to.startMillis() - from.endMillis(), readyMillis + connectionMillis - from.endMillis()));
			return;
		}
		if (to.startMillis() > readyMillis + maxLayoverMillis) {
			breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.LONG_LAYOVER, platformId, to.startMillis() - readyMillis, maxLayoverMillis));
			return;
		}
		final boolean samePlatform = platformId == to.firstPlatform();
		final boolean platformFree = !samePlatform || isPlatformFree(platformStops, platformId, from.endMillis(), to.startMillis());
		final boolean longWait = samePlatform && to.startMillis() - from.endMillis() >= transitsim.sim.TrainService.LONG_TURN_BACK_WAIT_MILLIS;
		if (platformFree && !longWait) {
			return;
		}
		// On the turn back rail beyond the platform (混合折返), as the planner sends a train there
		final long[] hold = network.turnBackHold(platformId);
		if (hold != null) {
			final long outMillis = samePlatform ? hold[1] : Math.max(connectionMillis, 0);
			final long fromMillis = from.endMillis() + hold[0];
			final long toMillis = to.startMillis() - outMillis;
			if (readyMillis + hold[0] + outMillis <= to.startMillis() && fromMillis <= toMillis && isPlatformFree(platformStops, to.firstPlatform(), toMillis, to.startMillis())) {
				holds.computeIfAbsent(platformId, key -> new ArrayList<>()).add(new long[]{fromMillis, toMillis, piece.sidingId, piece.slotId, piece.day, hold.length > 2 ? hold[2] : 1});
				return;
			}
		}
		if (!platformFree) {
			breaks.add(new Break(piece.sidingId, piece.slotId, piece.day, Kind.PLATFORM_TAKEN, platformId, 0, 0));
		}
	}

	/**
	 * True if no trip of the duties arrives at the platform strictly between the two times, the week round.
	 */
	private static boolean isPlatformFree(Map<Long, List<long[]>> platformStops, long platformId, long fromMillis, long toMillis) {
		final List<long[]> stops = platformStops.getOrDefault(platformId, Collections.emptyList());
		for (int week = -1; week <= 1; week++) {
			final long shift = week * OperationTime.WEEK_MILLIS;
			for (final long[] stop : stops) {
				final long arrival = stop[0] + shift;
				if (arrival > fromMillis && arrival < toMillis) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * As the planner sends a train back: to its own siding, or else to one of its pool it has a way back to.
	 */
	private static boolean canGoBack(OperationNetwork network, OperationNetwork.Depot siding, long platformId) {
		if (siding.canGoBack(platformId)) {
			return true;
		}
		return network.depots().stream().anyMatch(depot -> depot.poolKey.equals(siding.poolKey) && depot.canGoBack(platformId));
	}

	private static String segmentKey(DepotDuties.Segment segment) {
		return segment.routeId + "@" + segment.service + "@" + segment.fromStop;
	}

	/**
	 * One day of one duty: its trips in order.
	 */
	private static final class Piece {

		private final int group;
		private final long sidingId;
		private final long slotId;
		private final int day;
		private final List<Trip> trips;
		private final boolean stayOnLine;
		private final OperationNetwork network;
		private Piece next;

		private Piece(int group, long sidingId, long slotId, int day, List<Trip> trips, boolean stayOnLine, OperationNetwork network) {
			this.group = group;
			this.sidingId = sidingId;
			this.slotId = slotId;
			this.day = day;
			this.trips = trips;
			this.stayOnLine = stayOnLine;
			this.network = network;
		}
	}

	/**
	 * A chain run on one day: each stop as platform, arrival and departure, in milliseconds from the start of the week.
	 */
	private static final class Trip {

		private final List<long[]> stops = new ArrayList<>();

		private Trip(DutyGenerator.Chain chain, long start) {
			chain.stops.forEach(stop -> stops.add(new long[]{stop.platformId, start + stop.arrivalOffsetMillis, start + stop.departureOffsetMillis}));
		}

		private Trip(List<long[]> stops) {
			this.stops.addAll(stops);
		}

		private Trip shifted(long millis) {
			final List<long[]> shifted = new ArrayList<>();
			stops.forEach(stop -> shifted.add(new long[]{stop[0], stop[1] + millis, stop[2] + millis}));
			return new Trip(shifted);
		}

		private long startMillis() {
			return stops.get(0)[1];
		}

		private long endMillis() {
			return stops.get(stops.size() - 1)[2];
		}

		private long firstPlatform() {
			return stops.get(0)[0];
		}

		private long lastPlatform() {
			return stops.get(stops.size() - 1)[0];
		}
	}
}
