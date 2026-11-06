package transitsim.plan;

import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A path of a service that isn't the usual path of the depot. Trains use it from the moment they start that service.
 */
public class TripPath<T extends PlanTrack> {

	public static final String KEY_TRIP_KEY = "trip_key";
	public static final String KEY_TRIP_ROUTE_IDS = "trip_route_ids";
	public static final String KEY_TRIP_CUT_ROUTE = "trip_cut_route";
	public static final String KEY_TRIP_CUT_STOP = "trip_cut_stop";
	public static final String KEY_TRIP_LAP_END = "trip_lap_end";
	public static final String KEY_TRIP_CUTS = "trip_cuts";
	public static final String KEY_TRIP_REPEAT_1 = "trip_repeat_1";
	public static final String KEY_TRIP_REPEAT_2 = "trip_repeat_2";
	public static final String KEY_TRIP_LAP_STOP = "trip_lap_stop";
	public static final String KEY_TRIP_ENTRY_RAIL = "trip_entry_rail";
	public static final String KEY_TRIP_ENTRIES = "trip_entries";
	public static final String KEY_TRIP_BASE = "trip_base";
	public static final String KEY_TRIP_TURN_BACK = "trip_turn_back";
	public static final String KEY_TRIP_TURN_BACK_STOPS = "trip_turn_back_stops";
	public static final String KEY_TRIP_CHANGE_STOP = "trip_change_stop";
	public static final String KEY_TRIP_DUTY_STARTS = "trip_duty_starts";
	public static final String KEY_TRIP_DUTY_STAYS = "trip_duty_stays";
	public static final String KEY_LOOP_JOIN_INDEX = "loop_join_index";
	/**
	 * How many values {@link #write} writes. The host saves the path itself after them.
	 */
	public static final int WRITTEN_VALUES = 16;

	public final String key;
	public final List<Long> routeIds = new ArrayList<>();
	public final List<T> path = new ArrayList<>();
	public final List<Double> distances = new ArrayList<>();
	public final List<TimeSegment> timeSegments = new ArrayList<>();
	public final StopTimes stopTimes = new StopTimes();
	/**
	 * The rails a train laps on when the trip ends with a loop, or 0.
	 */
	public final int repeatIndex1;
	public final int repeatIndex2;
	/**
	 * The rail of the first stop of that loop.
	 */
	public final int lapStop;
	/**
	 * The rail where the train joins that loop part of the way round, or -1 if it joins at the first stop.
	 */
	public final int entryRail;
	/**
	 * The key of the service running through to another route, or empty for the usual path of the depot.
	 */
	public final String tripKey;
	/**
	 * The rail of the stop before each {@link TurnBacks} point, or -1 for a point this path doesn't pass.
	 */
	public final int[] turnBackStops;
	/**
	 * The point where this path takes another way round than the usual one, and which way, or -1.
	 */
	public final int turnBackPoint;
	public final int turnBackWay;
	/**
	 * For a way back to the depot, the rail of the stop where trains change onto it, or -1.
	 */
	public final int changeStop;
	private final Map<Long, Integer> cutStops = new HashMap<>();
	/**
	 * Routes the trip joins part of the way along, by the stop where it joins, with the fastest times of each from its
	 * first stop to there. See {@link TripTail#entryLeads}.
	 */
	private final Map<Long, Integer> entryStops = new HashMap<>();
	private final Map<Long, long[][]> entryTimes = new HashMap<>();
	private final Map<Long, List<T>> entryLeads = new HashMap<>();
	private final int joinIndex;
	private final int lapEnd;
	/**
	 * For the path of a duty, the rail of the first stop of each segment, or null for other paths.
	 */
	public final int[] dutyStarts;
	/**
	 * The path of a duty ends at its last stop, where the train stays overnight.
	 */
	public final boolean staysOnLine;

	/**
	 * @return the fastest time from leaving the last stop the path stops at to its end, back in a siding, or 0 for a path
	 * that stays on the line or has no times (as {@code DepotLegs} measures the way back)
	 */
	public long getBackMillis() {
		if (staysOnLine || timeSegments.isEmpty()) {
			return 0;
		}
		TimeSegment lastStop = null;
		for (final TimeSegment segment : timeSegments) {
			if (segment.savedRailBaseId != 0 && !segment.pass) {
				lastStop = segment;
			}
		}
		return lastStop == null ? 0 : Math.max(0, (long) ((timeSegments.get(timeSegments.size() - 1).endTime - lastStop.endTime) * 50));
	}

	/**
	 * The path of a duty (design document 6.12, block E2).
	 */
	public TripPath(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path) {
		this(dutyPlan, dutyStarts, path, dutyPlan.key);
	}

	/**
	 * The path of a duty under its own key, as for the same duty going back to another siding of its pool.
	 */
	public TripPath(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path, String key) {
		this(dutyPlan, dutyStarts, path, key, -1, -1, new int[0]);
	}

	/**
	 * The path of a duty that passes turn back points, or takes another way round one of them than the usual one.
	 *
	 * @param turnBackPoint the point where this path takes another way round, or -1 for the duty's usual path
	 * @param turnBackStops the rail of the stop before each point of the siding's {@link TurnBacks}, or -1 for one this path
	 *                      doesn't pass
	 */
	public TripPath(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path, String key, int turnBackPoint, int turnBackWay, int[] turnBackStops) {
		this.key = key;
		tripKey = dutyPlan.key;
		this.turnBackStops = turnBackStops;
		this.turnBackPoint = turnBackPoint;
		this.turnBackWay = turnBackWay;
		changeStop = -1;
		routeIds.addAll(dutyPlan.routeIds);
		cutStops.putAll(dutyPlan.cutStops);
		entryStops.putAll(dutyPlan.entryStops);
		entryLeads.putAll(dutyPlan.entryLeads);
		joinIndex = 0;
		lapEnd = 0;
		this.path.addAll(path);
		repeatIndex1 = 0;
		repeatIndex2 = 0;
		lapStop = 0;
		entryRail = -1;
		this.dutyStarts = dutyStarts;
		staysOnLine = dutyPlan.staysOnLine;
	}

	public TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, TripTail<T, ?> tripTail, int joinIndex, int lapEnd, int tailStart, int repeatEnd, List<T> path) {
		dutyStarts = null;
		staysOnLine = false;
		this.key = key;
		tripKey = tripTail.key;
		this.turnBackStops = turnBackStops;
		this.turnBackPoint = turnBackPoint;
		this.turnBackWay = turnBackWay;
		changeStop = -1;
		routeIds.addAll(tripTail.routeIds);
		cutStops.putAll(tripTail.cutStops);
		entryStops.putAll(tripTail.entryStops);
		entryLeads.putAll(tripTail.entryLeads);
		this.joinIndex = joinIndex;
		this.lapEnd = lapEnd;
		this.path.addAll(path);
		final boolean hasLap = tripTail.lapStart != TripTail.NO_RAIL;
		lapStop = hasLap ? tripTail.lapStart + tailStart : 0;
		repeatIndex1 = hasLap ? lapStop + (tripTail.isLapReversed ? 1 : 0) : 0;
		repeatIndex2 = hasLap ? repeatEnd : 0;
		entryRail = tripTail.entryRail == TripTail.NO_RAIL ? -1 : tripTail.entryRail + tailStart;
	}

	/**
	 * The usual path of the depot with another way round a turn back point.
	 */
	public TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, int joinIndex, int repeatIndex1, int repeatIndex2, int changeStop, List<T> path) {
		dutyStarts = null;
		staysOnLine = false;
		this.key = key;
		tripKey = "";
		this.turnBackStops = turnBackStops;
		this.turnBackPoint = turnBackPoint;
		this.turnBackWay = turnBackWay;
		this.changeStop = changeStop;
		this.joinIndex = joinIndex;
		lapEnd = repeatIndex2;
		this.path.addAll(path);
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		lapStop = 0;
		entryRail = -1;
	}

	/**
	 * A path read back as {@link #write} wrote it, with the path the host saved.
	 */
	public TripPath(KeyedReader reader, List<T> path) {
		key = reader.getString(KEY_TRIP_KEY, "");
		tripKey = reader.getString(KEY_TRIP_BASE, key);
		turnBackStops = toInts(reader.getLongs(KEY_TRIP_TURN_BACK_STOPS));
		final long[] turnBack = reader.getLongs(KEY_TRIP_TURN_BACK);
		turnBackPoint = turnBack.length == 2 ? (int) turnBack[0] : -1;
		turnBackWay = turnBack.length == 2 ? (int) turnBack[1] : -1;
		changeStop = reader.getInt(KEY_TRIP_CHANGE_STOP, -1);
		for (final long routeId : reader.getLongs(KEY_TRIP_ROUTE_IDS)) {
			routeIds.add(routeId);
		}
		final long oldCutRouteId = reader.getLong(KEY_TRIP_CUT_ROUTE, 0);
		if (oldCutRouteId != 0) {
			cutStops.put(oldCutRouteId, reader.getInt(KEY_TRIP_CUT_STOP, 0));
		}
		final long[] cutValues = reader.getLongs(KEY_TRIP_CUTS);
		for (int i = 0; i + 1 < cutValues.length; i += 2) {
			cutStops.put(cutValues[i], (int) cutValues[i + 1]);
		}
		joinIndex = reader.getInt(KEY_LOOP_JOIN_INDEX, 0);
		lapEnd = reader.getInt(KEY_TRIP_LAP_END, 0);
		repeatIndex1 = reader.getInt(KEY_TRIP_REPEAT_1, 0);
		repeatIndex2 = reader.getInt(KEY_TRIP_REPEAT_2, 0);
		lapStop = reader.getInt(KEY_TRIP_LAP_STOP, 0);
		entryRail = reader.getInt(KEY_TRIP_ENTRY_RAIL, -1);
		// Each route as its id, the stop, how many stops, then the arrival and departure at each
		final long[] entryValues = reader.getLongs(KEY_TRIP_ENTRIES);
		for (int i = 0; i + 2 < entryValues.length; ) {
			final long routeId = entryValues[i];
			final int count = (int) entryValues[i + 2];
			if (count < 0 || i + 3 + count * 2 > entryValues.length) {
				break;
			}
			entryStops.put(routeId, (int) entryValues[i + 1]);
			final long[][] times = new long[count][];
			for (int j = 0; j < count; j++) {
				times[j] = new long[]{entryValues[i + 3 + j * 2], entryValues[i + 4 + j * 2]};
			}
			entryTimes.put(routeId, times);
			i += 3 + count * 2;
		}
		this.path.addAll(path);
		final int[] starts = toInts(reader.getLongs(KEY_TRIP_DUTY_STARTS));
		dutyStarts = key.startsWith(DutyShapes.KEY_PREFIX) ? starts : null;
		staysOnLine = reader.getBoolean(KEY_TRIP_DUTY_STAYS, false);
	}

	private static int[] toInts(long[] values) {
		final int[] ints = new int[values.length];
		for (int i = 0; i < values.length; i++) {
			ints[i] = (int) values[i];
		}
		return ints;
	}

	/**
	 * The path of a duty worked out elsewhere, for running services without a world.
	 */
	public static <T extends PlanTrack> TripPath<T> ofDuty(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path, StopTimes newStopTimes) {
		return ofDuty(new TripPath<>(dutyPlan, dutyStarts, path), newStopTimes);
	}

	/**
	 * The path of a duty passing turn back points, or taking another way round one of them, worked out elsewhere.
	 */
	public static <T extends PlanTrack> TripPath<T> ofDuty(DutyPlan<T, ?> dutyPlan, int[] dutyStarts, List<T> path, StopTimes newStopTimes, int turnBackPoint, int turnBackWay, int[] turnBackStops) {
		final String key = turnBackPoint < 0 ? dutyPlan.key : getTurnBackKey(dutyPlan.key, turnBackPoint, turnBackWay);
		return ofDuty(new TripPath<>(dutyPlan, dutyStarts, path, key, turnBackPoint, turnBackWay, turnBackStops), newStopTimes);
	}

	private static <T extends PlanTrack> TripPath<T> ofDuty(TripPath<T> tripPath, StopTimes newStopTimes) {
		tripPath.stopTimes.copyFrom(newStopTimes);
		tripPath.stopTimes.cutStops.clear();
		tripPath.stopTimes.cutStops.putAll(tripPath.cutStops);
		tripPath.stopTimes.entryStops.clear();
		tripPath.stopTimes.entryStops.putAll(tripPath.entryStops);
		tripPath.stopTimes.firstRouteJoined = true;
		return tripPath;
	}

	public boolean isDuty() {
		return dutyStarts != null;
	}

	/**
	 * A path worked out elsewhere, for running services without a world.
	 *
	 * @param tailStart the rail of {@code path} where the tail of the trip starts
	 * @param repeatEnd for a loop at the end, the rail where a lap ends, or 0
	 */
	public static <T extends PlanTrack> TripPath<T> of(TripTail<T, ?> tripTail, List<T> path, int tailStart, int repeatEnd, StopTimes newStopTimes) {
		final TripPath<T> tripPath = new TripPath<>(tripTail.key, -1, -1, new int[0], tripTail, 0, 0, tailStart, repeatEnd, path);
		tripPath.stopTimes.copyFrom(newStopTimes);
		tripPath.stopTimes.cutStops.clear();
		tripPath.stopTimes.cutStops.putAll(tripPath.cutStops);
		tripPath.stopTimes.entryStops.clear();
		tripPath.stopTimes.entryStops.putAll(tripPath.entryStops);
		return tripPath;
	}

	/**
	 * The usual path with another way round a turn back point, worked out elsewhere, for running services without a world.
	 */
	public static <T extends PlanTrack> TripPath<T> ofTurnBack(int point, int way, int[] turnBackStops, List<T> path, StopTimes newStopTimes) {
		final TripPath<T> tripPath = new TripPath<>(getTurnBackKey("", point, way), point, way, turnBackStops, 0, 0, 0, -1, path);
		tripPath.stopTimes.copyFrom(newStopTimes);
		return tripPath;
	}

	/**
	 * A way back to the depot after a route, worked out elsewhere, for running services without a world.
	 */
	public static <T extends PlanTrack> TripPath<T> ofDepotReturn(int routeIndex, int changeStop, List<T> path, StopTimes newStopTimes) {
		final TripPath<T> tripPath = new TripPath<>(getDepotReturnKey(routeIndex), -1, -1, new int[0], 0, 0, 0, changeStop, path);
		tripPath.stopTimes.copyFrom(newStopTimes);
		return tripPath;
	}

	/**
	 * @param tripKey the key of the service running through to another route, or empty for the usual path of the depot
	 * @return the key of the path that takes this way round this turn back point
	 */
	public static String getTurnBackKey(String tripKey, int point, int way) {
		return tripKey + "|" + point + ":" + way;
	}

	/**
	 * @param routeIndex the route of the depot after which the train goes back to it
	 * @return the key of the path back to the depot after that route
	 */
	public static String getDepotReturnKey(int routeIndex) {
		return "depot|" + routeIndex;
	}

	/**
	 * @param dutyKey  the key of a duty's path from a siding back to it
	 * @param sidingId another siding of its pool
	 * @return the key of the same duty's path going back to that siding instead (decoupling document, step D, depots sending
	 * trains out 5)
	 */
	public static String getReturnKey(String dutyKey, long sidingId) {
		return dutyKey + RETURN_MARK + sidingId;
	}

	/**
	 * @return the siding a path going back to another siding goes to, or 0 if the key isn't of such a path
	 */
	public static long getReturnSidingId(String key) {
		final int index = key.lastIndexOf(RETURN_MARK);
		if (index < 0) {
			return 0;
		}
		try {
			return Long.parseLong(key.substring(index + 1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * @return whether the key is of a path going back to a siding other than the one it left
	 */
	public static boolean isReturnKey(String key) {
		return key.indexOf(RETURN_MARK) >= 0;
	}

	private static final char RETURN_MARK = '#';

	public void generateTimes(TimeGenerator<T> generator) {
		stopTimes.cutStops.clear();
		stopTimes.cutStops.putAll(cutStops);
		// The way from the first stop of a route joined part of the way along is only run in thought, for its plan
		entryLeads.forEach((routeId, lead) -> {
			final StopTimes leadTimes = generator.calculateStopTimes(lead);
			if (leadTimes.stops.size() == entryStops.getOrDefault(routeId, -1) + 1) {
				entryTimes.put(routeId, leadTimes.stops.stream().map(stop -> new long[]{stop.arrivalMillis, stop.departureMillis}).toArray(long[][]::new));
			}
		});
		stopTimes.entryStops.clear();
		stopTimes.entryStops.putAll(entryStops);
		stopTimes.firstRouteJoined = isDuty();
		stopTimes.entryTimes.clear();
		stopTimes.entryTimes.putAll(entryTimes);
		stopTimes.tailJoinStart = entryRail;
		stopTimes.tailJoinEnd = entryRail < 0 ? 0 : lapStop;
		stopTimes.tailLapEnd = repeatIndex2;
		generator.generateTimeSegments(path, timeSegments, stopTimes, joinIndex, lapEnd);
		double distanceSum = 0;
		distances.clear();
		for (final T pathData : path) {
			distanceSum += pathData.getTrackLength();
			distances.add(distanceSum);
		}
	}

	/**
	 * Writes {@link #WRITTEN_VALUES} values. The host saves {@link #path} after them.
	 */
	public void write(KeyedWriter writer) throws IOException {
		writer.writeString(KEY_TRIP_KEY, key);
		writer.writeInt(KEY_TRIP_CHANGE_STOP, changeStop);
		writer.writeString(KEY_TRIP_BASE, tripKey);
		writer.writeInts(KEY_TRIP_TURN_BACK, new int[]{turnBackPoint, turnBackWay});
		writer.writeInts(KEY_TRIP_TURN_BACK_STOPS, turnBackStops);
		writer.writeLongs(KEY_TRIP_ROUTE_IDS, routeIds.stream().mapToLong(Long::longValue).toArray());
		final List<Long> cutValues = new ArrayList<>();
		for (final Map.Entry<Long, Integer> entry : cutStops.entrySet()) {
			cutValues.add(entry.getKey());
			cutValues.add((long) entry.getValue());
		}
		writer.writeLongs(KEY_TRIP_CUTS, cutValues.stream().mapToLong(Long::longValue).toArray());
		writer.writeInt(KEY_LOOP_JOIN_INDEX, joinIndex);
		writer.writeInt(KEY_TRIP_LAP_END, lapEnd);
		writer.writeInt(KEY_TRIP_REPEAT_1, repeatIndex1);
		writer.writeInt(KEY_TRIP_REPEAT_2, repeatIndex2);
		writer.writeInt(KEY_TRIP_LAP_STOP, lapStop);
		writer.writeInt(KEY_TRIP_ENTRY_RAIL, entryRail);
		final List<Long> entryValues = new ArrayList<>();
		entryTimes.forEach((routeId, times) -> {
			entryValues.add(routeId);
			entryValues.add((long) entryStops.getOrDefault(routeId, 0));
			entryValues.add((long) times.length);
			for (final long[] time : times) {
				entryValues.add(time[0]);
				entryValues.add(time[1]);
			}
		});
		writer.writeLongs(KEY_TRIP_ENTRIES, entryValues.stream().mapToLong(Long::longValue).toArray());
		writer.writeInts(KEY_TRIP_DUTY_STARTS, dutyStarts == null ? new int[0] : dutyStarts);
		writer.writeBoolean(KEY_TRIP_DUTY_STAYS, staysOnLine);
	}
}
