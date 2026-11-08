package transitsim.plan;

import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One service running differently from its table at some stops, set in the advanced editor: its own running time to a
 * stop, its own stop time, passing a stop its route stops at or the other way round, or waiting to be overtaken.
 * <p>
 * They are kept in each table by service, then by the index of the stop in the route, see {@link RouteTimetable#exceptions}.
 * Everything that works out planned times goes through {@link #applyTo(RouteTimetable.StopPlan, StopException, StopException)},
 * so the train, the PIDS and the conflict check all agree.
 * <p>
 * In a depot running by its duties each duty has a path of its own (see the host's duty shapes), so passing or stopping for one
 * service is where the train really stops. Elsewhere it only changes the times, and the train stops where its path does.
 */
public interface ServiceExceptions {

	/**
	 * The most exceptions a table keeps, counting each stop of each service.
	 */
	int MAX_EXCEPTIONS = 16384;
	/**
	 * How long a service stops at a stop its route passes, unless it has its own stop time there.
	 */
	long ADDED_STOP_MILLIS = 30000;
	int MAX_SECONDS = RouteTimetable.MAX_STOP_SECONDS;
	/**
	 * Each exception saves as the service, the stop and the four values.
	 */
	int SAVED_INTS = 6;

	/**
	 * What one service does differently at one stop. Each value is -1 to follow the table.
	 */
	class StopException {

		/**
		 * Seconds from leaving the stop before to reaching this one.
		 */
		public int runSeconds = -1;
		/**
		 * Seconds from reaching this stop to leaving it.
		 */
		public int dwellSeconds = -1;
		/**
		 * 1 to pass this stop, 0 to stop here.
		 */
		public int pass = -1;
		/**
		 * 1 to wait here to be overtaken, 0 not to.
		 */
		public int overtake = -1;

		public boolean isEmpty() {
			return runSeconds < 0 && dwellSeconds < 0 && pass < 0 && overtake < 0;
		}

		public StopException copy() {
			final StopException stopException = new StopException();
			stopException.runSeconds = runSeconds;
			stopException.dwellSeconds = dwellSeconds;
			stopException.pass = pass;
			stopException.overtake = overtake;
			return stopException;
		}

		private void clamp() {
			runSeconds = Math.max(-1, Math.min(runSeconds, MAX_SECONDS));
			dwellSeconds = Math.max(-1, Math.min(dwellSeconds, MAX_SECONDS));
			pass = Math.max(-1, Math.min(pass, 1));
			overtake = Math.max(-1, Math.min(overtake, 1));
		}
	}

	/**
	 * Applies the exceptions of the route a plan's stop belongs to. A stop can belong to two routes where one ends and the
	 * next starts, so the arrival and the departure each take theirs from their own route.
	 *
	 * @param arrivalException   the exception for reaching this stop, or null
	 * @param departureException the exception for stopping at and leaving this stop, or null
	 */
	static void applyTo(RouteTimetable.StopPlan stopPlan, StopException arrivalException, StopException departureException) {
		if (arrivalException != null && arrivalException.runSeconds >= 0) {
			stopPlan.exceptionRunMillis = arrivalException.runSeconds * 1000L;
		}
		if (departureException != null) {
			if (departureException.dwellSeconds >= 0) {
				stopPlan.exceptionDwellMillis = departureException.dwellSeconds * 1000L;
			}
			stopPlan.exceptionPass = departureException.pass;
			if (departureException.overtake >= 0) {
				stopPlan.overtake = departureException.overtake == 1;
			}
		}
	}

	/**
	 * @param stopExceptions the exceptions of one service by stop, or null
	 */
	static void applyTo(List<RouteTimetable.StopPlan> stopPlans, Map<Integer, StopException> stopExceptions) {
		if (stopExceptions != null) {
			for (int i = 0; i < stopPlans.size(); i++) {
				final StopException stopException = stopExceptions.get(i);
				applyTo(stopPlans.get(i), stopException, stopException);
			}
		}
	}

	/**
	 * Drops exceptions of services the table no longer has and empty ones, and keeps the number within the limit.
	 */
	static void sanitize(RouteTimetable table) {
		table.exceptions.keySet().retainAll(table.services);
		int count = 0;
		for (final Map<Integer, StopException> stopExceptions : table.exceptions.values()) {
			stopExceptions.values().forEach(StopException::clamp);
			stopExceptions.values().removeIf(StopException::isEmpty);
			stopExceptions.keySet().removeIf(stop -> stop < 0);
			count += stopExceptions.size();
		}
		table.exceptions.values().removeIf(Map::isEmpty);
		if (count > MAX_EXCEPTIONS) {
			// Keeps the earlier services, which a table is most likely to be edited from
			int kept = 0;
			for (final int service : new ArrayList<>(table.exceptions.keySet()).stream().sorted().toArray(Integer[]::new)) {
				final int size = table.exceptions.get(service).size();
				if (kept + size > MAX_EXCEPTIONS) {
					table.exceptions.remove(service);
				} else {
					kept += size;
				}
			}
		}
	}

	/**
	 * Keeps exceptions with the same stops when the route's stops change, matching them by platform.
	 */
	static void remapStops(RouteTimetable timetable, List<? extends PlanStop> newStops, List<? extends PlanStop> oldStops) {
		final Map<Integer, Integer> newIndices = new HashMap<>();
		for (int i = 0; i < oldStops.size(); i++) {
			for (int j = 0; j < newStops.size(); j++) {
				if (newStops.get(j).getPlatformId() == oldStops.get(i).getPlatformId() && !newIndices.containsValue(j)) {
					newIndices.put(i, j);
					break;
				}
			}
		}
		timetable.getTables().forEach(table -> table.exceptions.values().forEach(stopExceptions -> {
			final Map<Integer, StopException> remapped = new HashMap<>();
			stopExceptions.forEach((stop, stopException) -> {
				final Integer newStop = newIndices.get(stop);
				if (newStop != null) {
					remapped.put(newStop, stopException);
				}
			});
			stopExceptions.clear();
			stopExceptions.putAll(remapped);
		}));
	}

	static void limitStops(RouteTimetable table, int stopCount) {
		table.exceptions.values().forEach(stopExceptions -> stopExceptions.keySet().removeIf(stop -> stop >= stopCount));
		table.exceptions.values().removeIf(Map::isEmpty);
	}

	static void copy(RouteTimetable from, RouteTimetable to) {
		to.exceptions.clear();
		from.exceptions.forEach((service, stopExceptions) -> {
			final Map<Integer, StopException> copied = new HashMap<>();
			stopExceptions.forEach((stop, stopException) -> copied.put(stop, stopException.copy()));
			to.exceptions.put(service, copied);
		});
	}

	/**
	 * @return the exceptions as a flat list for saving, {@link #SAVED_INTS} for each, by service and stop: the same
	 * exceptions are always written the same, which the keys of what duties are planned from rely on (round 4, step 4)
	 */
	static int[] toInts(RouteTimetable table) {
		final List<Integer> values = new ArrayList<>();
		new java.util.TreeMap<>(table.exceptions).forEach((service, stopExceptions) -> new java.util.TreeMap<>(stopExceptions).forEach((stop, stopException) -> {
			values.add(service);
			values.add(stop);
			values.add(stopException.runSeconds);
			values.add(stopException.dwellSeconds);
			values.add(stopException.pass);
			values.add(stopException.overtake);
		}));
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	static void fromInts(List<Integer> values, RouteTimetable table) {
		table.exceptions.clear();
		for (int i = 0; i + SAVED_INTS - 1 < values.size() && i < MAX_EXCEPTIONS * SAVED_INTS; i += SAVED_INTS) {
			final StopException stopException = new StopException();
			stopException.runSeconds = values.get(i + 2);
			stopException.dwellSeconds = values.get(i + 3);
			stopException.pass = values.get(i + 4);
			stopException.overtake = values.get(i + 5);
			table.exceptions.computeIfAbsent(values.get(i), key -> new HashMap<>()).put(values.get(i + 1), stopException);
		}
	}

	static void write(BinaryWriter packet, RouteTimetable table) {
		final int[] values = toInts(table);
		packet.writeInt(values.length);
		for (final int value : values) {
			packet.writeInt(value);
		}
	}

	/**
	 * Reads every value even past the limit, so the rest of the packet still lines up.
	 */
	static void read(BinaryReader packet, RouteTimetable table) {
		final int count = packet.readInt();
		final List<Integer> values = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final int value = packet.readInt();
			if (i < MAX_EXCEPTIONS * SAVED_INTS) {
				values.add(value);
			}
		}
		fromInts(values, table);
	}
}
