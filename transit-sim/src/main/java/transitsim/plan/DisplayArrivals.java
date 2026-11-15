package transitsim.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The services passenger displays tell after each train's current one (0.83.16): by the timetable, as each duty runs them,
 * so they come at the timetable's intervals, and only those some train will run. A duty a train is on gives its later
 * segments, each no earlier than planned and later only by the train's known lateness, carried forward stop by stop, the
 * stop times beyond the least a train needs there taking it up. A duty not yet run gives its services when its depot will
 * send a train for it: a train of its pool is in the depot, or back from an earlier duty, in time. A duty a train staying
 * on the line overnight runs is given only when a train will be there for it. A duty without a path gives nothing.
 * <p>
 * Worked out in two steps, so the slow part needn't be done often: {@link #dutyStops} lists a duty's planned stops from
 * its path and timetable, which only changes with them; {@link #compute} puts the duties together with the trains as they
 * are, which is quick.
 */
public final class DisplayArrivals {

	/**
	 * How many arrivals are kept for each platform: more than any display shows of one platform.
	 */
	public static final int PER_PLATFORM = 16;

	private DisplayArrivals() {
	}

	/**
	 * A stop or pass of a planned service, with its planned times as full timestamps.
	 */
	public static final class Stop {
		public final long platformId;
		public final long routeId;
		public final int stopIndex;
		public final long arrivalMillis;
		public final long departureMillis;
		public final boolean pass;
		/**
		 * The least time a train stands here, which is all of the planned stop time a late train can't make up; never more
		 * than the planned stop time, so a train on time stays on time.
		 */
		public final long minDwellMillis;

		public Stop(long platformId, long routeId, int stopIndex, long arrivalMillis, long departureMillis, boolean pass, long minDwellMillis) {
			this.platformId = platformId;
			this.routeId = routeId;
			this.stopIndex = stopIndex;
			this.arrivalMillis = arrivalMillis;
			this.departureMillis = Math.max(arrivalMillis, departureMillis);
			this.pass = pass;
			this.minDwellMillis = pass ? 0 : Math.max(0, Math.min(minDwellMillis, this.departureMillis - arrivalMillis));
		}
	}

	/**
	 * A duty on one operating day.
	 */
	public static final class Duty {
		public final String key;
		/**
		 * The trains that can run it: those of the sidings of a depot with the same vehicle.
		 */
		public final Object pool;
		public final int cars;
		/**
		 * It starts where a train staying on the line overnight from another duty stands, so only that train runs it.
		 */
		public final boolean overnightStart;
		/**
		 * The key of the duty its train goes on with the next day when it stays on the line overnight, or null.
		 */
		public final String nextKey;
		/**
		 * When the train leaves the siding, and when it is back there, as planned.
		 */
		public final long leaveMillis;
		public final long backMillis;
		/**
		 * The planned stops of each segment, empty when the duty has no path.
		 */
		public final List<List<Stop>> segments;

		public Duty(String key, Object pool, int cars, boolean overnightStart, String nextKey, long leaveMillis, long backMillis, List<List<Stop>> segments) {
			this.key = key;
			this.pool = pool;
			this.cars = cars;
			this.overnightStart = overnightStart;
			this.nextKey = nextKey;
			this.leaveMillis = leaveMillis;
			this.backMillis = backMillis;
			this.segments = segments;
		}

		public boolean hasPath() {
			return segments.stream().anyMatch(stops -> !stops.isEmpty());
		}

		private boolean staysOnLine() {
			return nextKey != null;
		}
	}

	/**
	 * A train running a duty.
	 */
	public static final class Running {
		public final String dutyKey;
		public final long trainId;
		/**
		 * The segment it is on.
		 */
		public final int segment;
		/**
		 * The segment from which the plan tells its services: the next, out on the line, where the train tells its current
		 * one itself; its current one while it stands in its siding, hidden from displays.
		 */
		public final int fromSegment;
		/**
		 * The planned departure it has been late since, or 0 when it isn't late.
		 */
		public final long lateSinceMillis;

		public Running(String dutyKey, long trainId, int segment, int fromSegment, long lateSinceMillis) {
			this.dutyKey = dutyKey;
			this.trainId = trainId;
			this.segment = segment;
			this.fromSegment = fromSegment;
			this.lateSinceMillis = lateSinceMillis;
		}
	}

	/**
	 * An arrival for a display: a planned stop and when the train is expected there.
	 */
	public static final class Arrival {
		public final Stop stop;
		public final long estimateMillis;
		public final Duty duty;
		/**
		 * The train running the duty, or 0 for a duty not run yet.
		 */
		public final long trainId;

		private Arrival(Stop stop, long estimateMillis, Duty duty, long trainId) {
			this.stop = stop;
			this.estimateMillis = estimateMillis;
			this.duty = duty;
			this.trainId = trainId;
		}
	}

	/**
	 * What {@link #compute} gives: the next arrivals of each platform, earliest first, and how many duties were left out.
	 */
	public static final class Result {
		public final Map<Long, List<Arrival>> arrivals;
		/**
		 * Duties not run yet that no train will run: without a path, staying for an overnight train that isn't there, or with
		 * no train of their pool in time.
		 */
		public final int noPath;
		public final int noOvernightTrain;
		public final int noTrain;

		private Result(Map<Long, List<Arrival>> arrivals, int noPath, int noOvernightTrain, int noTrain) {
			this.arrivals = arrivals;
			this.noPath = noPath;
			this.noOvernightTrain = noOvernightTrain;
			this.noTrain = noTrain;
		}

		public List<Arrival> get(long platformId) {
			return arrivals.getOrDefault(platformId, Collections.emptyList());
		}
	}

	/**
	 * The planned stops of a duty on a day, by segment, as its path and its routes' timetables have them: the services the
	 * timetable runs that day and doesn't suspend, the stops the path has a rail for.
	 *
	 * @param routes the routes of the path's segments, in order
	 * @return the stops of each segment; empty when the path doesn't fit the duty
	 */
	public static <T extends PlanTrack> List<List<Stop>> dutyStops(DepotDuties.DayDuty duty, long dayStart, TripPath<T> path, List<? extends PlanRoute> routes) {
		if (path == null || !path.isDuty() || path.stopTimes.isEmpty() || path.dutyStarts.length != duty.segments.size() || routes.size() != duty.segments.size() || routes.contains(null)) {
			return Collections.emptyList();
		}
		final int weekday = OperatingDay.getWeekday(dayStart + OperatingDay.MILLIS_PER_DAY / 2);
		final List<List<Stop>> segments = new ArrayList<>();
		for (int index = 0; index < duty.segments.size(); index++) {
			final List<Stop> stops = new ArrayList<>();
			segments.add(stops);
			final DepotDuties.Segment segment = duty.segments.get(index);
			final PlanRoute route = routes.get(index);
			final RouteTimetable timetable = route.getTimetable();
			final long service = DutyShapes.getServiceMillis(dayStart, segment.service);
			final int table = timetable.getTableIndex(service);
			final RouteTimetable serviceTable = timetable.getTable(table);
			if (route.getRouteId() != segment.routeId || route.getStops().size() < 2 || !serviceTable.runsOn(weekday) || !serviceTable.services.contains(segment.service) || serviceTable.suspended.contains(segment.service)) {
				continue;
			}

			final int first = DutyShapes.getFirstStop(route, segment);
			final int last = DutyShapes.getLastStop(route, segment);
			final boolean[] passes = DutyShapes.getPassFlags(route, segment, weekday);
			final long[][] times = path.stopTimes.getSegmentTimes(index, routes);
			final long routeStart;
			if (index == 0) {
				routeStart = first == 0 ? 0 : -path.stopTimes.getEntryOffsetMillis(route, first, table, segment.service);
			} else {
				final RouteTimetable.StopPlan plan = path.stopTimes.getPlan(routes, timetable, path.dutyStarts[index], route.getRouteId(), table, segment.service);
				final RouteTimetable.StopPlan start = plan == null || plan.startsRoute ? plan : plan.nextRouteStart;
				if (start == null) {
					continue;
				}
				routeStart = start.routeStartMillis;
			}

			for (int stop = first; stop <= last && stop < times.length; stop++) {
				if (times[stop][2] < 0 || times[stop][2] >= path.path.size()) {
					continue;
				}
				final int rail = (int) times[stop][2];
				final RouteTimetable.StopPlan plan = path.stopTimes.getPlan(routes, timetable, rail, route.getRouteId(), table, segment.service);
				if (plan == null) {
					continue;
				}
				final T track = path.path.get(rail);
				final boolean pass = passes[stop - first];
				final long arrival = service + plan.arrivalMillis - routeStart;
				final long departure = pass ? arrival : service + plan.departureMillis - routeStart;
				// Dwell times of tracks are in half seconds
				stops.add(new Stop(track.getStopId(), route.getRouteId(), stop, arrival, departure, pass, track.getDwellTime() * 500L));
			}
		}
		return segments;
	}

	/**
	 * @return when a duty's train leaves the siding: its lead before the first stop, from the path
	 */
	public static long leaveMillis(List<List<Stop>> segments, long leadMillis) {
		final Stop first = firstStop(segments);
		return first == null ? 0 : first.arrivalMillis - Math.max(0, leadMillis);
	}

	/**
	 * @param returnMillis the time from leaving the last stop to being back in the siding
	 * @return when a duty's train is back in the siding
	 */
	public static long backMillis(List<List<Stop>> segments, long returnMillis) {
		Stop last = null;
		for (final List<Stop> stops : segments) {
			if (!stops.isEmpty()) {
				last = stops.get(stops.size() - 1);
			}
		}
		return last == null ? 0 : last.departureMillis + Math.max(0, returnMillis);
	}

	/**
	 * Puts the duties together with the trains as they are now.
	 *
	 * @param duties     the duties of the days the displays look at, and of the days before for trains still on them
	 * @param running    the trains on duties
	 * @param idleTrains the trains in the depots, free for a duty now, by pool
	 * @param hasRun     the duties the depots have sent a train for already, or that a train staying overnight took
	 * @param now        the time now
	 * @param untilMillis the latest arrival told
	 */
	public static Result compute(Collection<Duty> duties, Collection<Running> running, Map<Object, Integer> idleTrains, Predicate<Duty> hasRun, long now, long untilMillis) {
		final Map<String, Duty> byKey = new HashMap<>();
		duties.forEach(duty -> byKey.put(duty.key, duty));
		final Map<Object, PriorityQueue<Long>> free = new HashMap<>();
		idleTrains.forEach((pool, count) -> {
			final PriorityQueue<Long> queue = free.computeIfAbsent(pool, key -> new PriorityQueue<>());
			for (int i = 0; i < count; i++) {
				queue.add(now);
			}
		});
		final Set<String> handedOver = new HashSet<>();
		final Set<String> taken = new HashSet<>();
		final List<Arrival> arrivals = new ArrayList<>();

		// The trains on duties: their later services, late by as much as they are
		running.stream().sorted(Comparator.comparingLong(train -> train.trainId)).forEach(train -> {
			final Duty duty = byKey.get(train.dutyKey);
			if (duty == null || !taken.add(duty.key)) {
				return;
			}
			final List<Stop> flat = new ArrayList<>();
			final List<Integer> segmentOf = new ArrayList<>();
			flatten(duty, flat, segmentOf);
			int start = -1;
			for (int i = 0; i < flat.size(); i++) {
				if (segmentOf.get(i) >= train.segment && (train.lateSinceMillis == 0 || flat.get(i).departureMillis >= train.lateSinceMillis)) {
					start = i;
					break;
				}
			}
			final long endDelay = start < 0 ? 0 : carry(flat, start, train.lateSinceMillis == 0 ? 0 : now - flat.get(start).departureMillis, (stop, estimate, index) -> {
				if (segmentOf.get(index) >= train.fromSegment) {
					arrivals.add(new Arrival(stop, estimate, duty, train.trainId));
				}
			});
			// A train coming back to its depot runs another duty after this one; one staying on the line runs the next day's
			if (duty.staysOnLine()) {
				handedOver.add(duty.nextKey);
			} else {
				free.computeIfAbsent(duty.pool, key -> new PriorityQueue<>()).add(duty.backMillis + endDelay);
			}
		});

		// The duties not run yet, in the order the depots send trains for them
		final List<Duty> pending = new ArrayList<>();
		duties.forEach(duty -> {
			if (!taken.contains(duty.key) && !hasRun.test(duty)) {
				pending.add(duty);
			}
		});
		pending.sort(Comparator.comparingLong((Duty duty) -> duty.leaveMillis).thenComparing(duty -> duty.key));
		int noPath = 0;
		int noOvernightTrain = 0;
		int noTrain = 0;
		for (final Duty duty : pending) {
			if (!duty.hasPath()) {
				noPath++;
				continue;
			}
			final List<Stop> flat = new ArrayList<>();
			flatten(duty, flat, new ArrayList<>());
			// All over already, as a duty of the day before no train ran: nothing to tell, and not counted as left out
			if (flat.get(flat.size() - 1).departureMillis < now) {
				continue;
			}
			if (duty.overnightStart) {
				// Only the train staying on the line overnight for it runs it
				if (!handedOver.contains(duty.key)) {
					noOvernightTrain++;
					continue;
				}
				final long endDelay = carry(flat, 0, 0, (stop, estimate, index) -> arrivals.add(new Arrival(stop, estimate, duty, 0)));
				handOn(duty, endDelay, handedOver, free);
				continue;
			}
			final PriorityQueue<Long> queue = free.get(duty.pool);
			final Long freeMillis = queue == null ? null : queue.peek();
			if (freeMillis == null) {
				noTrain++;
				continue;
			}
			final long leave = Math.max(duty.leaveMillis, Math.max(now, freeMillis));
			final long late = leave - duty.leaveMillis;
			int start = 0;
			long delay = late;
			if (late > RouteTimetable.MAX_DEPLOY_DELAY_MILLIS) {
				// Sent out much later, the train joins its duty at the first segment it can still get to in time, by plan
				final long lead = flat.get(0).arrivalMillis - duty.leaveMillis;
				start = -1;
				for (int i = 0; i < flat.size(); i++) {
					if (flat.get(i).arrivalMillis >= leave + lead && (i == 0 || isSegmentStart(duty, flat.get(i)))) {
						start = i;
						break;
					}
				}
				delay = 0;
			}
			if (start < 0) {
				noTrain++;
				continue;
			}
			queue.poll();
			final long endDelay = carryFrom(flat, start, delay, duty, arrivals);
			handOn(duty, endDelay, handedOver, free);
		}

		final Map<Long, List<Arrival>> byPlatform = new HashMap<>();
		arrivals.forEach(arrival -> {
			if (arrival.estimateMillis > now && arrival.estimateMillis <= untilMillis) {
				byPlatform.computeIfAbsent(arrival.stop.platformId, key -> new ArrayList<>()).add(arrival);
			}
		});
		byPlatform.replaceAll((platformId, list) -> {
			list.sort(Comparator.comparingLong((Arrival arrival) -> arrival.estimateMillis).thenComparingLong(arrival -> arrival.stop.routeId).thenComparing(arrival -> arrival.duty.key));
			return Collections.unmodifiableList(new ArrayList<>(list.subList(0, Math.min(PER_PLATFORM, list.size()))));
		});
		return new Result(Collections.unmodifiableMap(byPlatform), noPath, noOvernightTrain, noTrain);
	}

	/**
	 * A duty a train is sent out for: late from the start by as much as it leaves late.
	 */
	private static long carryFrom(List<Stop> flat, int start, long delay, Duty duty, List<Arrival> arrivals) {
		return carry(flat, start, 0, (stop, estimate, index) -> arrivals.add(new Arrival(stop, estimate, duty, 0)), delay);
	}

	private static void handOn(Duty duty, long endDelay, Set<String> handedOver, Map<Object, PriorityQueue<Long>> free) {
		if (duty.staysOnLine()) {
			handedOver.add(duty.nextKey);
		} else {
			free.computeIfAbsent(duty.pool, key -> new PriorityQueue<>()).add(duty.backMillis + endDelay);
		}
	}

	private static boolean isSegmentStart(Duty duty, Stop stop) {
		for (final List<Stop> stops : duty.segments) {
			if (!stops.isEmpty() && stops.get(0) == stop) {
				return true;
			}
		}
		return false;
	}

	private static void flatten(Duty duty, List<Stop> flat, List<Integer> segmentOf) {
		for (int segment = 0; segment < duty.segments.size(); segment++) {
			for (final Stop stop : duty.segments.get(segment)) {
				flat.add(stop);
				segmentOf.add(segment);
			}
		}
	}

	private interface Told {
		void accept(Stop stop, long estimateMillis, int index);
	}

	private static long carry(List<Stop> flat, int start, long departureDelay, Told told) {
		return carry(flat, start, departureDelay, told, -1);
	}

	/**
	 * Carries a delay on from a stop: each later stop reached no earlier than planned, and no sooner after leaving the one
	 * before than planned; left no earlier than planned, and no sooner than the least stop time after arriving.
	 *
	 * @param departureDelay how late the train leaves the first stop
	 * @param arrivalDelay   how late it arrives at the first stop, or -1 when only its departure is known
	 * @return how late it leaves the last stop
	 */
	private static long carry(List<Stop> flat, int start, long departureDelay, Told told, long arrivalDelay) {
		if (start < 0 || start >= flat.size()) {
			return 0;
		}
		final Stop first = flat.get(start);
		long departure;
		if (arrivalDelay >= 0) {
			final long arrival = first.arrivalMillis + arrivalDelay;
			told.accept(first, arrival, start);
			departure = Math.max(first.departureMillis, arrival + first.minDwellMillis);
		} else {
			departure = first.departureMillis + Math.max(0, departureDelay);
			told.accept(first, first.pass ? departure : Math.max(first.arrivalMillis, departure - (first.departureMillis - first.arrivalMillis)), start);
		}
		Stop previous = first;
		for (int i = start + 1; i < flat.size(); i++) {
			final Stop stop = flat.get(i);
			final long arrival = Math.max(stop.arrivalMillis, departure + stop.arrivalMillis - previous.departureMillis);
			told.accept(stop, arrival, i);
			departure = Math.max(stop.departureMillis, arrival + stop.minDwellMillis);
			previous = stop;
		}
		return departure - previous.departureMillis;
	}

	private static Stop firstStop(List<List<Stop>> segments) {
		for (final List<Stop> stops : segments) {
			if (!stops.isEmpty()) {
				return stops.get(0);
			}
		}
		return null;
	}
}
