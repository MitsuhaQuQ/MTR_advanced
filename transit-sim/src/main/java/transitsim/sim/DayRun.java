package transitsim.sim;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;
import transitsim.io.ByteArrayBinary;
import transitsim.motion.MotionEvent;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * One operating day simulated from a world's data, as the editor shows it next to the plan (S5): where and when each
 * service actually stopped, and what went wrong on the way. It runs in a world of its own, so nothing in the game changes.
 * <p>
 * Run from the start of the day, every train starts in its depot, so a train that stays on the line overnight leaves its
 * depot instead. Run from later in the day, it carries on from the trains as the data has them (G0), and reports only
 * what happens from then on.
 */
public final class DayRun {

	/**
	 * The services of the routes reported on, in the order they first stopped.
	 */
	public final List<Trip> trips;
	/**
	 * What went wrong, in the order it happened, with services that didn't run at the end.
	 */
	public final List<Problem> problems;
	public final long dayStart;
	/**
	 * When the simulation started: the start of the day, or later if it carried on from the trains as they were.
	 */
	public final long fromMillis;
	/**
	 * Whether it was stopped before the end of the day.
	 */
	public final boolean cancelled;

	/**
	 * How late a train has to be, or how much longer than planned it has to stand or wait, to be reported.
	 */
	public static final long REPORT_MILLIS = 30000;
	/**
	 * How long past the end of the day the simulation goes on, so the last services of the day get to their stops.
	 */
	private static final long OVERRUN_MILLIS = 60 * 60 * 1000;
	/**
	 * How much it simulates between asking whether it should stop.
	 */
	private static final long STEP_MILLIS = 10 * 60 * 1000;
	private static final int VERSION = 2;

	private DayRun(List<Trip> trips, List<Problem> problems, long dayStart, long fromMillis, boolean cancelled) {
		this.trips = Collections.unmodifiableList(trips);
		this.problems = Collections.unmodifiableList(problems);
		this.dayStart = dayStart;
		this.fromMillis = fromMillis;
		this.cancelled = cancelled;
	}

	/**
	 * @param dataset      the world, with any drafts already put in ({@link SimDataset#replaceRoute}, {@link SimDataset#replaceDuties})
	 * @param dayStart     when the operating day starts
	 * @param reportRoutes the routes to report on, or empty for all of them
	 * @param inputs       anything the host puts in on the way, such as rails taken from outside
	 * @param cancelled    asked every ten simulated minutes; true stops the run
	 */
	/**
	 * @return a runner of the day from its start, with the trains that stayed on the line overnight standing where their
	 * duties start (2026-11-09), as built again the same way
	 */
	public static java.util.function.Supplier<TickRunner<SimDataset.DataTrack>> startOfDay(byte[] bytes, long dayStart) {
		final java.util.function.Supplier<TickRunner<SimDataset.DataTrack>> start = SimDataset.start(bytes, dayStart);
		return () -> {
			final TickRunner<SimDataset.DataTrack> runner = start.get();
			runner.placeOvernightTrains();
			return runner;
		};
	}

	public static DayRun run(SimDataset dataset, long dayStart, Set<Long> reportRoutes, List<HostInput> inputs, BooleanSupplier cancelled) {
		return run(dataset, dayStart, dayStart, reportRoutes, inputs, cancelled);
	}

	/**
	 * @param fromMillis when to start: the start of the day, or later to carry on from {@link SimDataset#trains}, which
	 *                   then have to be there (see {@link EventSimulator#resume} for how long they may be out of date)
	 */
	public static DayRun run(SimDataset dataset, long dayStart, long fromMillis, Set<Long> reportRoutes, List<HostInput> inputs, BooleanSupplier cancelled) {
		final Recorder recorder = new Recorder(dataset, reportRoutes);
		final byte[] bytes = dataset.toBytes();
		final boolean fromTrains = fromMillis > dayStart && dataset.trains != null;
		final EventSimulator<SimDataset.DataTrack> simulator = fromTrains ? EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), dataset.trains, fromMillis) : new EventSimulator<>(startOfDay(bytes, dayStart));
		final long start = simulator.getTime();
		simulator.getRunner().setTrainListener(recorder::onEvent);
		inputs.forEach(simulator::submit);
		final long end = dayStart + OperatingDay.MILLIS_PER_DAY + OVERRUN_MILLIS;
		boolean wasCancelled = false;
		for (long time = start; time < end; time = Math.min(end, time + STEP_MILLIS)) {
			if (cancelled.getAsBoolean()) {
				wasCancelled = true;
				break;
			}
			simulator.advanceTo(Math.min(end, time + STEP_MILLIS));
		}
		final long lastTime = simulator.getTime();
		recorder.endHolds(lastTime);
		simulator.events(0).forEach(event -> {
			if (event.kind == SimEvent.Kind.DEADLOCK) {
				recorder.problems.add(new Problem(ProblemKind.DEADLOCK, event.time, 0, 0, 0, event.trainId, -1, 0, event.reason));
			}
		});
		recorder.addLateness();
		if (!wasCancelled) {
			recorder.addNotRun(simulator.getRunner().getRoutes(), start, dayStart + OperatingDay.MILLIS_PER_DAY);
		}
		recorder.problems.sort((problem1, problem2) -> Long.compare(problem1.time, problem2.time));
		return new DayRun(new ArrayList<>(recorder.trips.values()), recorder.problems, dayStart, start, wasCancelled);
	}

	/**
	 * @return the report as the server sends it to the editor, with times counted from the start of the day
	 */
	public byte[] toBytes() {
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		writer.writeInt(VERSION);
		writer.writeLong(dayStart);
		writer.writeLong(fromMillis);
		writer.writeBoolean(cancelled);
		writer.writeVarInt(trips.size());
		for (final Trip trip : trips) {
			writer.writeLong(trip.routeId);
			writer.writeLong(trip.serviceMillis);
			writer.writeLong(trip.trainId);
			writer.writeVarInt(trip.stops.size());
			for (final Stop stop : trip.stops) {
				writer.writeInt(stop.stopIndex);
				writer.writeLong(stop.platformId);
				writer.writeBoolean(stop.passed);
				writeTime(writer, stop.arrivalMillis);
				writeTime(writer, stop.departureMillis);
				writeTime(writer, stop.plannedArrivalMillis);
				writeTime(writer, stop.plannedDepartureMillis);
			}
		}
		writer.writeVarInt(problems.size());
		for (final Problem problem : problems) {
			writer.writeVarInt(problem.kind.ordinal());
			writeTime(writer, problem.time);
			writer.writeLong(problem.durationMillis);
			writer.writeLong(problem.routeId);
			writer.writeLong(problem.serviceMillis);
			writer.writeLong(problem.trainId);
			writer.writeInt(problem.stopIndex);
			writer.writeLong(problem.platformId);
			final byte[] detail = problem.detail.getBytes(StandardCharsets.UTF_8);
			writer.writeVarInt(detail.length);
			for (final byte value : detail) {
				writer.writeByte(value);
			}
		}
		return writer.toBytes();
	}

	public static DayRun fromBytes(byte[] bytes) {
		final BinaryReader reader = new ByteArrayBinary.Reader(bytes);
		final int version = reader.readInt();
		if (version != VERSION) {
			throw new IllegalArgumentException("Unknown day report version " + version);
		}
		final long dayStart = reader.readLong();
		final long fromMillis = reader.readLong();
		final boolean cancelled = reader.readBoolean();
		final List<Trip> trips = new ArrayList<>();
		for (int i = reader.readVarInt(); i > 0; i--) {
			final Trip trip = new Trip(reader.readLong(), reader.readLong(), reader.readLong());
			for (int j = reader.readVarInt(); j > 0; j--) {
				final int stopIndex = reader.readInt();
				final long platformId = reader.readLong();
				final boolean passed = reader.readBoolean();
				final long arrivalMillis = readTime(reader, dayStart);
				final long departureMillis = readTime(reader, dayStart);
				final Stop stop = new Stop(stopIndex, platformId, passed, arrivalMillis, readTime(reader, dayStart), readTime(reader, dayStart));
				stop.departureMillis = departureMillis;
				trip.stops.add(stop);
			}
			trips.add(trip);
		}
		final List<Problem> problems = new ArrayList<>();
		final ProblemKind[] kinds = ProblemKind.values();
		for (int i = reader.readVarInt(); i > 0; i--) {
			final ProblemKind kind = kinds[Math.min(reader.readVarInt(), kinds.length - 1)];
			final long time = readTime(reader, dayStart);
			final long durationMillis = reader.readLong();
			final long routeId = reader.readLong();
			final long serviceMillis = reader.readLong();
			final long trainId = reader.readLong();
			final int stopIndex = reader.readInt();
			final long platformId = reader.readLong();
			final byte[] detail = new byte[reader.readVarInt()];
			for (int j = 0; j < detail.length; j++) {
				detail[j] = reader.readByte();
			}
			problems.add(new Problem(kind, time, durationMillis, routeId, serviceMillis, trainId, stopIndex, platformId, new String(detail, StandardCharsets.UTF_8)));
		}
		return new DayRun(trips, problems, dayStart, fromMillis, cancelled);
	}

	/**
	 * A time, or -1 for none, as a count from the start of the day plus one, which fits in an int.
	 */
	private void writeTime(BinaryWriter writer, long millis) {
		writer.writeInt(millis < 0 ? 0 : (int) (millis - dayStart) + 1);
	}

	private static long readTime(BinaryReader reader, long dayStart) {
		final int value = reader.readInt();
		return value == 0 ? -1 : dayStart + value - 1;
	}

	public enum ProblemKind {
		/**
		 * Later than planned at a stop by more than {@link #REPORT_MILLIS}; once for each service, at the first such stop,
		 * with the most it was late as its duration.
		 */
		LATE,
		/**
		 * Stopped short of a platform another train has.
		 */
		WAITING_FOR_PLATFORM,
		/**
		 * Stopped on the line short of a taken rail.
		 */
		HELD_ON_LINE,
		/**
		 * Stood at a stop longer than planned, as the way on was taken or it was held.
		 */
		LONG_DWELL,
		/**
		 * Trains waiting for each other in a ring, such as two facing each other on a single track.
		 */
		DEADLOCK,
		/**
		 * A service of the plan that no train ran.
		 */
		NOT_RUN
	}

	/**
	 * A service as it ran.
	 */
	public static final class Trip {

		public final long routeId;
		/**
		 * The service, as the timetable has it, as a full timestamp.
		 */
		public final long serviceMillis;
		public final long trainId;
		public final List<Stop> stops = new ArrayList<>();
		private int nextStopIndex;

		private Trip(long routeId, long serviceMillis, long trainId) {
			this.routeId = routeId;
			this.serviceMillis = serviceMillis;
			this.trainId = trainId;
		}
	}

	/**
	 * A stop of a service as it ran, with what the plan had for it. Times are full timestamps, or -1 if there are none.
	 */
	public static final class Stop {

		/**
		 * Which stop of its route this is, or -1 if it isn't one of the route's.
		 */
		public final int stopIndex;
		public final long platformId;
		public final boolean passed;
		public long arrivalMillis;
		public long departureMillis = -1;
		public final long plannedArrivalMillis;
		public long plannedDepartureMillis;

		private Stop(int stopIndex, long platformId, boolean passed, long arrivalMillis, long plannedArrivalMillis, long plannedDepartureMillis) {
			this.stopIndex = stopIndex;
			this.platformId = platformId;
			this.passed = passed;
			this.arrivalMillis = arrivalMillis;
			this.plannedArrivalMillis = plannedArrivalMillis;
			this.plannedDepartureMillis = plannedDepartureMillis;
		}
	}

	public static final class Problem {

		public final ProblemKind kind;
		public final long time;
		/**
		 * How long it went on, or for {@link ProblemKind#LATE} the most the service was late.
		 */
		public final long durationMillis;
		/**
		 * The service it happened to, or 0 if there is none.
		 */
		public final long routeId;
		public final long serviceMillis;
		public final long trainId;
		/**
		 * Which stop of the route it was at or before, or -1.
		 */
		public final int stopIndex;
		public final long platformId;
		/**
		 * For a deadlock, the trains in it.
		 */
		public final String detail;

		private Problem(ProblemKind kind, long time, long durationMillis, long routeId, long serviceMillis, long trainId, int stopIndex, long platformId, String detail) {
			this.kind = kind;
			this.time = time;
			this.durationMillis = durationMillis;
			this.routeId = routeId;
			this.serviceMillis = serviceMillis;
			this.trainId = trainId;
			this.stopIndex = stopIndex;
			this.platformId = platformId;
			this.detail = detail;
		}
	}

	/**
	 * Writes down what trains do as they do it, reading each train only.
	 */
	private static final class Recorder {

		private final Set<Long> reportRoutes;
		private final Map<Long, long[]> routePlatforms = new HashMap<>();
		private final Map<String, Trip> trips = new LinkedHashMap<>();
		private final List<Problem> problems = new ArrayList<>();
		/**
		 * The stop each train stands at, and where each train stopped short on the way.
		 */
		private final Map<Long, Stop> standing = new HashMap<>();
		private final Map<Long, Trip> standingTrips = new HashMap<>();
		private final Map<Long, Hold> holds = new HashMap<>();

		private Recorder(SimDataset dataset, Set<Long> reportRoutes) {
			this.reportRoutes = reportRoutes;
			dataset.routes.forEach(route -> routePlatforms.put(route.id, route.platformIds));
		}

		private void onEvent(MotionEvent event, TickRunner<SimDataset.DataTrack>.HeadlessTrain train) {
			final List<SimDataset.DataTrack> path = train.getPath();
			final int rail = event.rail;
			if (rail < 0 || rail >= path.size()) {
				return;
			}
			switch (event.type) {
				case STOPPED:
					if (path.get(rail).getDwellTime() > 0) {
						arrive(event, train, path.get(rail).getStopId(), false);
					} else {
						// Short of the rail after, which is taken; a platform there is another train's
						final boolean platformAhead = rail + 1 < path.size() && path.get(rail + 1).isPlatformTrack();
						holds.put(train.id, new Hold(event.millis, platformAhead, getTrip(train), train.getNextPlatformIndex() < path.size() ? path.get(train.getNextPlatformIndex()).getStopId() : 0));
					}
					break;
				case PASSED:
					if (path.get(rail).getStopId() != 0 && path.get(rail).isPlatformTrack()) {
						arrive(event, train, path.get(rail).getStopId(), true);
						depart(event, train);
					}
					break;
				case DEPARTED:
					final Hold hold = holds.remove(train.id);
					if (hold != null) {
						endHold(hold, train.id, event.millis);
					} else if (rail > 0) {
						depart(event, train);
					}
					break;
				case RETURNED:
					standing.remove(train.id);
					standingTrips.remove(train.id);
					holds.remove(train.id);
					break;
				default:
					break;
			}
		}

		private void arrive(MotionEvent event, TickRunner<SimDataset.DataTrack>.HeadlessTrain train, long platformId, boolean passed) {
			final Trip trip = getTrip(train);
			if (trip == null) {
				return;
			}
			final RouteTimetable.StopPlan plan = train.service.getPlanAt(event.rail);
			final Stop stop = new Stop(findStopIndex(trip, platformId, true), platformId, passed, event.millis, plan == null ? -1 : train.service.getPlannedMillis(plan.arrivalMillis), plan == null ? -1 : train.service.getPlannedMillis(plan.departureMillis));
			trip.stops.add(stop);
			standing.put(train.id, stop);
			standingTrips.put(train.id, trip);
		}

		private void depart(MotionEvent event, TickRunner<SimDataset.DataTrack>.HeadlessTrain train) {
			final Stop stop = standing.remove(train.id);
			final Trip arrivedTrip = standingTrips.remove(train.id);
			if (stop == null) {
				return;
			}
			final Trip trip = getTrip(train);
			if (trip != null && trip != arrivedTrip) {
				// The stop ended one service and starts the next one, which leaves from it
				final RouteTimetable.StopPlan plan = train.service.getPlanAt(event.rail);
				final Stop firstStop = new Stop(findStopIndex(trip, stop.platformId, true), stop.platformId, stop.passed, stop.arrivalMillis, -1, plan == null ? -1 : train.service.getPlannedMillis(plan.departureMillis));
				firstStop.departureMillis = event.millis;
				trip.stops.add(firstStop);
				return;
			}
			stop.departureMillis = event.millis;
			if (trip != null && !stop.passed && stop.plannedArrivalMillis >= 0 && stop.plannedDepartureMillis >= 0) {
				// Stood longer than planned beyond being late already
				final long extraMillis = event.millis - stop.plannedDepartureMillis - Math.max(0, stop.arrivalMillis - stop.plannedArrivalMillis);
				if (extraMillis > REPORT_MILLIS) {
					problems.add(new Problem(ProblemKind.LONG_DWELL, stop.arrivalMillis, event.millis - stop.arrivalMillis, trip.routeId, trip.serviceMillis, trip.trainId, stop.stopIndex, stop.platformId, ""));
				}
			}
		}

		private void endHold(Hold hold, long trainId, long endMillis) {
			if (hold.trip != null && endMillis - hold.startMillis > REPORT_MILLIS) {
				problems.add(new Problem(hold.platformAhead ? ProblemKind.WAITING_FOR_PLATFORM : ProblemKind.HELD_ON_LINE, hold.startMillis, endMillis - hold.startMillis, hold.trip.routeId, hold.trip.serviceMillis, trainId, findStopIndex(hold.trip, hold.nextPlatformId, false), hold.nextPlatformId, ""));
			}
		}

		/**
		 * Trains still stopped short at the end, which never got on.
		 */
		private void endHolds(long endMillis) {
			holds.forEach((trainId, hold) -> endHold(hold, trainId, endMillis));
			holds.clear();
		}

		private void addLateness() {
			trips.values().forEach(trip -> {
				Stop firstLate = null;
				long mostLate = 0;
				for (final Stop stop : trip.stops) {
					final long late = Math.max(stop.plannedArrivalMillis < 0 ? 0 : stop.arrivalMillis - stop.plannedArrivalMillis, stop.plannedDepartureMillis < 0 || stop.departureMillis < 0 ? 0 : stop.departureMillis - stop.plannedDepartureMillis);
					if (late > REPORT_MILLIS) {
						if (firstLate == null) {
							firstLate = stop;
						}
						mostLate = Math.max(mostLate, late);
					}
				}
				if (firstLate != null) {
					problems.add(new Problem(ProblemKind.LATE, firstLate.arrivalMillis, mostLate, trip.routeId, trip.serviceMillis, trip.trainId, firstLate.stopIndex, firstLate.platformId, ""));
				}
			});
		}

		private void addNotRun(LongFunction<? extends PlanRoute> routes, long fromMillis, long beforeMillis) {
			final Set<String> ran = new HashSet<>(trips.keySet());
			routePlatforms.keySet().stream().sorted().forEach(routeId -> {
				final PlanRoute route = routes.apply(routeId);
				if (route == null || !isReported(routeId) || !route.getTimetable().isActive()) {
					return;
				}
				for (final long serviceMillis : route.getTimetable().getServicesBetween(fromMillis, beforeMillis)) {
					if (!ran.contains(getKey(routeId, serviceMillis))) {
						problems.add(new Problem(ProblemKind.NOT_RUN, serviceMillis, 0, routeId, serviceMillis, 0, -1, 0, ""));
					}
				}
			});
		}

		/**
		 * @return the service the train runs now, or null if it runs none or not one of a route reported on
		 */
		private Trip getTrip(TickRunner<SimDataset.DataTrack>.HeadlessTrain train) {
			final long routeId = train.service.getTripRouteId();
			final long serviceMillis = train.service.getTripMillis();
			if (serviceMillis == 0 || !isReported(routeId)) {
				return null;
			}
			return trips.computeIfAbsent(getKey(routeId, serviceMillis), key -> new Trip(routeId, serviceMillis, train.id));
		}

		private boolean isReported(long routeId) {
			return reportRoutes.isEmpty() || reportRoutes.contains(routeId);
		}

		/**
		 * @param advance whether the service stops there now, so later stops are looked for after it
		 * @return the first stop of the route at this platform after the stops the service has made so far, or -1
		 */
		private int findStopIndex(Trip trip, long platformId, boolean advance) {
			final long[] platformIds = routePlatforms.get(trip.routeId);
			if (platformIds != null) {
				for (int i = trip.nextStopIndex; i < platformIds.length; i++) {
					if (platformIds[i] == platformId) {
						if (advance) {
							trip.nextStopIndex = i + 1;
						}
						return i;
					}
				}
			}
			return -1;
		}

		private static String getKey(long routeId, long serviceMillis) {
			return routeId + "@" + serviceMillis;
		}
	}

	private static final class Hold {

		private final long startMillis;
		private final boolean platformAhead;
		private final Trip trip;
		private final long nextPlatformId;

		private Hold(long startMillis, boolean platformAhead, Trip trip, long nextPlatformId) {
			this.startMillis = startMillis;
			this.platformAhead = platformAhead;
			this.trip = trip;
			this.nextPlatformId = nextPlatformId;
		}
	}
}
