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
	public static final String KEY_TRIP_SEGMENT_CUTS = "trip_segment_cuts";
	public static final String KEY_TRIP_SEGMENT_ENTRIES = "trip_segment_entries";
	public static final String KEY_TRIP_SEGMENT_ENTRY_TIMES = "trip_segment_entry_times";
	public static final String KEY_TRIP_SEGMENTS_KNOWN = "trip_segments_known";
	/**
	 * How many values {@link #write} writes. The host saves the path itself after them. Checked by a test.
	 */
	public static final int WRITTEN_VALUES = 20;

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
	/**
	 * For a duty's path, where each segment is cut and joined, by segment (D4), and for a segment joined part of the way
	 * along where its route's lead isn't known by route, the times from the route's first stop.
	 */
	private final Map<Integer, Integer> segmentCuts = new HashMap<>();
	private final Map<Integer, Integer> segmentEntries = new HashMap<>();
	private final Map<Integer, long[][]> segmentEntryTimes = new HashMap<>();
	private boolean segmentsKnown;
	/**
	 * For a path turned back early (D4): the stops of the usual path its segment joined part of the way along is timed by.
	 */
	private final Map<Integer, StopTimes> segmentLeadSources = new HashMap<>();
	/**
	 * For a path turned back early (D4), the rail of the stop where it turns back, where riders get off, or -1; and the
	 * rail where it goes on running its duty, or -1 when it goes back to the siding empty from there.
	 */
	public final int turnRail;
	public final int turnResumeRail;
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
		segmentCuts.putAll(dutyPlan.segmentCuts);
		segmentEntries.putAll(dutyPlan.segmentEntries);
		segmentsKnown = dutyPlan.segmentsKnown;
		joinIndex = 0;
		lapEnd = 0;
		this.path.addAll(path);
		repeatIndex1 = 0;
		repeatIndex2 = 0;
		lapStop = 0;
		entryRail = -1;
		this.dutyStarts = dutyStarts;
		staysOnLine = dutyPlan.staysOnLine;
		turnRail = -1;
		turnResumeRail = -1;
	}

	public TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, TripTail<T, ?> tripTail, int joinIndex, int lapEnd, int tailStart, int repeatEnd, List<T> path) {
		dutyStarts = null;
		staysOnLine = false;
		turnRail = -1;
		turnResumeRail = -1;
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
		turnRail = -1;
		turnResumeRail = -1;
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
	 * The same path as another under another key, along other tracks, as for passing some of its stops (D3).
	 */
	private TripPath(TripPath<T> base, String key, List<T> path) {
		this.key = key;
		tripKey = base.tripKey;
		turnBackStops = base.turnBackStops;
		turnBackPoint = base.turnBackPoint;
		turnBackWay = base.turnBackWay;
		changeStop = base.changeStop;
		routeIds.addAll(base.routeIds);
		cutStops.putAll(base.cutStops);
		entryStops.putAll(base.entryStops);
		entryTimes.putAll(base.entryTimes);
		entryLeads.putAll(base.entryLeads);
		segmentCuts.putAll(base.segmentCuts);
		segmentEntries.putAll(base.segmentEntries);
		segmentEntryTimes.putAll(base.segmentEntryTimes);
		segmentsKnown = base.segmentsKnown;
		segmentLeadSources.putAll(base.segmentLeadSources);
		joinIndex = base.joinIndex;
		lapEnd = base.lapEnd;
		this.path.addAll(path);
		repeatIndex1 = base.repeatIndex1;
		repeatIndex2 = base.repeatIndex2;
		lapStop = base.lapStop;
		entryRail = base.entryRail;
		dutyStarts = base.dutyStarts;
		staysOnLine = base.staysOnLine;
		turnRail = base.turnRail;
		turnResumeRail = base.turnResumeRail;
	}

	/**
	 * The path with these stops of it passed without stopping (D3), timed again, for a train told to skip some stations.
	 *
	 * @param rails the rails of the stops passed, in order
	 */
	public static <T extends PlanTrack> TripPath<T> withSkips(TripPath<T> base, int[] rails, PathVariants<T> variants) {
		final List<T> newPath = new ArrayList<>(base.path);
		for (final int rail : rails) {
			if (rail > 0 && rail < newPath.size()) {
				newPath.set(rail, variants.pass(newPath.get(rail)));
			}
		}
		final TripPath<T> tripPath = new TripPath<>(base, getSkipKey(base.key, rails), newPath);
		tripPath.generateTimes(variants.getTimeGenerator());
		return tripPath;
	}

	/**
	 * @param baseKey the key of the path as it usually runs
	 * @param rails   the rails of the stops passed, in order
	 * @return the key of the same path passing those stops, or the base key if none (D3)
	 */
	public static String getSkipKey(String baseKey, int[] rails) {
		if (rails.length == 0) {
			return baseKey;
		}
		final StringBuilder builder = new StringBuilder(baseKey).append(SKIP_MARK);
		for (int i = 0; i < rails.length; i++) {
			builder.append(i == 0 ? "" : ",").append(rails[i]);
		}
		return builder.toString();
	}

	public static boolean isSkipKey(String key) {
		return key.contains(SKIP_MARK);
	}

	/**
	 * @return the key of the path as it usually runs, without the stops passed
	 */
	public static String getSkipBase(String key) {
		final int index = key.indexOf(SKIP_MARK);
		return index < 0 ? key : key.substring(0, index);
	}

	/**
	 * @return the rails of the stops passed, or none if the key isn't of such a path
	 */
	public static int[] getSkipRails(String key) {
		final int index = key.indexOf(SKIP_MARK);
		if (index < 0) {
			return new int[0];
		}
		try {
			final String[] parts = key.substring(index + SKIP_MARK.length()).split(",");
			final int[] rails = new int[parts.length];
			for (int i = 0; i < parts.length; i++) {
				rails[i] = Integer.parseInt(parts[i]);
			}
			return rails;
		} catch (NumberFormatException e) {
			return new int[0];
		}
	}

	/**
	 * A duty's path turned back early (D4): the same up to the stop where it turns back, then round to the other platform
	 * of the station and on with the rest of the duty from there, or back to the siding.
	 */
	private TripPath(TripPath<T> base, String key, List<T> path, int[] dutyStarts, int segmentCount, int[] turnBackStops, int turnRail, int turnResumeRail, boolean staysOnLine) {
		this.key = key;
		tripKey = base.tripKey;
		this.turnBackStops = turnBackStops;
		turnBackPoint = -1;
		turnBackWay = -1;
		changeStop = -1;
		routeIds.addAll(base.routeIds.subList(0, Math.min(segmentCount, base.routeIds.size())));
		cutStops.putAll(base.cutStops);
		entryStops.putAll(base.entryStops);
		entryTimes.putAll(base.entryTimes);
		entryLeads.putAll(base.entryLeads);
		base.segmentCuts.forEach((segment, stop) -> {
			if (segment < segmentCount) {
				segmentCuts.put(segment, stop);
			}
		});
		base.segmentEntries.forEach((segment, stop) -> {
			if (segment < segmentCount) {
				segmentEntries.put(segment, stop);
			}
		});
		base.segmentEntryTimes.forEach((segment, times) -> {
			if (segment < segmentCount) {
				segmentEntryTimes.put(segment, times);
			}
		});
		base.segmentLeadSources.forEach((segment, source) -> {
			if (segment < segmentCount) {
				segmentLeadSources.put(segment, source);
			}
		});
		segmentsKnown = true;
		joinIndex = 0;
		lapEnd = 0;
		this.path.addAll(path);
		repeatIndex1 = 0;
		repeatIndex2 = 0;
		lapStop = 0;
		entryRail = -1;
		this.dutyStarts = dutyStarts;
		this.staysOnLine = staysOnLine;
		this.turnRail = turnRail;
		this.turnResumeRail = turnResumeRail;
	}

	/**
	 * @param segment     the segment of the duty it turns back on
	 * @param turnRail    the rail of the stop where it turns back
	 * @param cutStop     that stop's number on the segment's route
	 * @param target      for going on with the duty, the rail of the stop of the next segment it goes on from; otherwise the
	 *                    platform it turns back to
	 * @param entryStop   for going on with the duty, that stop's number on the next segment's route
	 * @param resume      whether it goes on with the duty, or back to the siding empty
	 * @return the key of the duty's path turned back early there (D4)
	 */
	public static String getTurnKey(String baseKey, int segment, int turnRail, int cutStop, long target, int entryStop, boolean resume) {
		return baseKey + TURN_MARK + segment + ":" + turnRail + ":" + cutStop + ":" + target + ":" + entryStop + ":" + (resume ? "r" : "d");
	}

	public static boolean isTurnKey(String key) {
		return key.contains(TURN_MARK);
	}

	/**
	 * @return the key of the path as it usually runs, without turning back early, starting or ending part of the way through
	 * the duty, or passing stops
	 */
	public static String getTurnBase(String key) {
		int index = -1;
		for (final String mark : new String[]{TURN_MARK, FROM_MARK, END_MARK}) {
			final int markIndex = key.indexOf(mark);
			if (markIndex >= 0 && (index < 0 || markIndex < index)) {
				index = markIndex;
			}
		}
		return index < 0 ? getSkipBase(key) : key.substring(0, index);
	}

	/**
	 * @return whether the key is of a duty's path changed from its usual one, turned back early or starting or ending part
	 * of the way through (D4, D5), with or without stops passed
	 */
	public static boolean isChangedKey(String key) {
		return key.contains(TURN_MARK) || key.contains(FROM_MARK) || key.contains(END_MARK);
	}

	/**
	 * @return the key of the duty's path for a train starting it at a later segment, out of the siding (D5)
	 */
	public static String getFromKey(String baseKey, int segment) {
		return baseKey + FROM_MARK + segment;
	}

	public static boolean isFromKey(String key) {
		return key.contains(FROM_MARK);
	}

	/**
	 * @return the segment a path starting part of the way through the duty starts at, or 0
	 */
	public static int getFromSegment(String key) {
		final String fromKey = getSkipBase(key);
		final int index = fromKey.indexOf(FROM_MARK);
		try {
			return index < 0 ? 0 : Integer.parseInt(fromKey.substring(index + FROM_MARK.length()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * @param lastStopRail the rail of the segment's last stop
	 * @return the key of the duty's path for a train handing the rest over after a segment and going back to its siding
	 * empty from that segment's last stop (D5)
	 */
	public static String getEndKey(String baseKey, int segment, int lastStopRail) {
		return baseKey + END_MARK + segment + ":" + lastStopRail;
	}

	public static boolean isEndKey(String key) {
		return key.contains(END_MARK);
	}

	/**
	 * A duty's path starting at a later segment (D5): out of the siding the way the host found to that segment's first stop,
	 * then the rest of the duty as usual. The segments before it run no stops.
	 */
	public static <T extends PlanTrack> TripPath<T> withFrom(TripPath<T> base, String key, PathVariants<T> variants) {
		if (base.dutyStarts == null || !base.segmentsKnown) {
			return null;
		}
		final int segment = getFromSegment(key);
		final int segmentCount = base.dutyStarts.length;
		if (segment <= 0 || segment >= segmentCount) {
			return null;
		}
		final int startRail = base.dutyStarts[segment];
		// A segment starting where the one before turned back on the platform has its stop on the arrival there, and the
		// platform the other way only as the way out, with no stop or platform of its own
		final T firstStop = base.path.get(startRail);
		final T arrival = startRail > 0 ? base.path.get(startRail - 1) : null;
		final boolean turnedOnPlatform = firstStop.getDwellTime() == 0 && arrival != null && arrival.getDwellTime() > 0 && arrival.isOppositeOf(firstStop);
		final long platformId = turnedOnPlatform && firstStop.getStopId() == 0 ? arrival.getStopId() : firstStop.getStopId();
		final int dwellTime = turnedOnPlatform ? arrival.getDwellTime() : firstStop.getDwellTime();
		final List<T> wayOut = variants.getWayOut(platformId);
		if (wayOut == null || wayOut.size() < 2) {
			return null;
		}
		final T wayOutEnd = wayOut.get(wayOut.size() - 1);
		final List<T> newPath = new ArrayList<>(wayOut.subList(0, wayOut.size() - 1));
		final int newStart = newPath.size();
		final int shift;
		if (turnedOnPlatform && wayOutEnd.isOppositeOf(firstStop)) {
			// Out of the siding onto the platform the way the duty arrives there: it stops, then turns back as the duty does
			final T stopped = variants.withStop(wayOutEnd, dwellTime, platformId);
			if (stopped == null) {
				return null;
			}
			newPath.add(stopped);
			shift = newStart + 1 - startRail;
			newPath.addAll(base.path.subList(startRail, base.path.size()));
		} else {
			// Onto the platform the way the segment leaves it: it stops there
			final T stopped = turnedOnPlatform ? variants.withStop(firstStop, dwellTime, platformId) : firstStop;
			if (stopped == null) {
				return null;
			}
			newPath.add(stopped);
			shift = newStart - startRail;
			newPath.addAll(base.path.subList(startRail + 1, base.path.size()));
		}
		final int[] newStarts = new int[segmentCount];
		for (int i = 0; i < segmentCount; i++) {
			newStarts[i] = i <= segment ? newStart : base.dutyStarts[i] + shift;
		}
		final int[] newTurnBackStops = base.turnBackStops.clone();
		for (int i = 0; i < newTurnBackStops.length; i++) {
			newTurnBackStops[i] = newTurnBackStops[i] < startRail ? -1 : newTurnBackStops[i] + shift;
		}
		final TripPath<T> tripPath = new TripPath<>(base, getSkipBase(key), newPath, newStarts, segmentCount, newTurnBackStops, -1, -1, base.staysOnLine);
		// The segments before run none of their stops
		for (int i = 0; i < segment; i++) {
			tripPath.segmentCuts.put(i, -1);
			tripPath.segmentEntries.remove(i);
		}
		tripPath.generateTimes(variants.getTimeGenerator());
		return tripPath;
	}

	/**
	 * A duty's path handed over after a segment (D5): the same up to that segment's last stop, where everyone gets off,
	 * then back to the siding empty the way the host found.
	 */
	public static <T extends PlanTrack> TripPath<T> withEnd(TripPath<T> base, String key, PathVariants<T> variants) {
		final String endKey = getSkipBase(key);
		final int index = endKey.indexOf(END_MARK);
		if (index < 0 || base.dutyStarts == null || !base.segmentsKnown) {
			return null;
		}
		final String[] parts = endKey.substring(index + END_MARK.length()).split(":");
		final int segment;
		final int lastStopRail;
		try {
			segment = Integer.parseInt(parts[0]);
			lastStopRail = Integer.parseInt(parts[1]);
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			return null;
		}
		if (segment < 0 || segment + 1 >= base.dutyStarts.length || lastStopRail < base.dutyStarts[segment] || lastStopRail >= base.path.size()) {
			return null;
		}
		final List<T> back = variants.getTurnReturn(base.path.get(lastStopRail).getStopId());
		if (back == null || back.size() < 2) {
			return null;
		}
		final List<T> newPath = new ArrayList<>(base.path.subList(0, lastStopRail + 1));
		for (int i = 1; i < back.size(); i++) {
			newPath.add(passing(back.get(i), variants));
		}
		final int[] newTurnBackStops = base.turnBackStops.clone();
		for (int i = 0; i < newTurnBackStops.length; i++) {
			newTurnBackStops[i] = newTurnBackStops[i] < lastStopRail ? newTurnBackStops[i] : -1;
		}
		final TripPath<T> tripPath = new TripPath<>(base, endKey, newPath, java.util.Arrays.copyOf(base.dutyStarts, segment + 1), segment + 1, newTurnBackStops, lastStopRail, -1, false);
		tripPath.generateTimes(variants.getTimeGenerator());
		return tripPath;
	}

	/**
	 * The duty's path turned back early, as its key says (D4), with the ways the host found, timed again; or null if the
	 * key doesn't fit the path or the host has no such way.
	 */
	public static <T extends PlanTrack> TripPath<T> withTurn(TripPath<T> base, String key, PathVariants<T> variants) {
		final String turnKey = getSkipBase(key);
		final int index = turnKey.indexOf(TURN_MARK);
		if (index < 0 || base.dutyStarts == null || !base.segmentsKnown) {
			return null;
		}
		final String[] parts = turnKey.substring(index + TURN_MARK.length()).split(":");
		if (parts.length != 6) {
			return null;
		}
		final int segment;
		final int turnRail;
		final int cutStop;
		final long target;
		final int entryStop;
		try {
			segment = Integer.parseInt(parts[0]);
			turnRail = Integer.parseInt(parts[1]);
			cutStop = Integer.parseInt(parts[2]);
			target = Long.parseLong(parts[3]);
			entryStop = Integer.parseInt(parts[4]);
		} catch (NumberFormatException e) {
			return null;
		}
		final boolean resume = "r".equals(parts[5]);
		final int segmentCount = base.dutyStarts.length;
		if (segment < 0 || segment >= segmentCount || turnRail <= base.dutyStarts[segment] || turnRail >= base.path.size()) {
			return null;
		}
		final long platformId = base.path.get(turnRail).getStopId();
		final List<T> newPath = new ArrayList<>(base.path.subList(0, turnRail + 1));
		final int[] newStarts;
		final int[] newTurnBackStops = base.turnBackStops.clone();
		final int resumeRail;
		final TripPath<T> tripPath;
		if (resume) {
			final int targetRail = (int) target;
			if (segment + 1 >= segmentCount || targetRail < base.dutyStarts[segment + 1] || targetRail >= base.path.size() || segment + 2 < segmentCount && targetRail >= base.dutyStarts[segment + 2]) {
				return null;
			}
			final List<T> link = variants.getTurnLink(platformId, base.path.get(targetRail).getStopId());
			if (link == null || link.size() < 2) {
				return null;
			}
			for (int i = 1; i < link.size() - 1; i++) {
				newPath.add(passing(link.get(i), variants));
			}
			resumeRail = newPath.size();
			final int shift = resumeRail - targetRail;
			newPath.addAll(base.path.subList(targetRail, base.path.size()));
			newStarts = base.dutyStarts.clone();
			newStarts[segment + 1] = resumeRail;
			for (int i = segment + 2; i < segmentCount; i++) {
				newStarts[i] += shift;
			}
			for (int i = 0; i < newTurnBackStops.length; i++) {
				final int stop = newTurnBackStops[i];
				newTurnBackStops[i] = stop <= turnRail ? stop : stop < targetRail ? -1 : stop + shift;
			}
			tripPath = new TripPath<>(base, key, newPath, newStarts, segmentCount, newTurnBackStops, turnRail, resumeRail, base.staysOnLine);
			tripPath.segmentCuts.put(segment, cutStop);
			tripPath.segmentEntries.put(segment + 1, entryStop);
			tripPath.segmentEntryTimes.remove(segment + 1);
			// Timed from its route's first stop by the usual path, whose same segment runs from further back
			tripPath.segmentLeadSources.put(segment + 1, base.stopTimes);
		} else {
			final List<T> link = variants.getTurnLink(platformId, target);
			final List<T> back = variants.getTurnReturn(target);
			if (link == null || link.size() < 2 || back == null || back.size() < 2) {
				return null;
			}
			for (int i = 1; i < link.size(); i++) {
				newPath.add(passing(link.get(i), variants));
			}
			for (int i = 1; i < back.size(); i++) {
				newPath.add(passing(back.get(i), variants));
			}
			newStarts = java.util.Arrays.copyOf(base.dutyStarts, segment + 1);
			for (int i = 0; i < newTurnBackStops.length; i++) {
				newTurnBackStops[i] = newTurnBackStops[i] <= turnRail ? newTurnBackStops[i] : -1;
			}
			tripPath = new TripPath<>(base, key, newPath, newStarts, segment + 1, newTurnBackStops, turnRail, -1, false);
			tripPath.segmentCuts.put(segment, cutStop);
		}
		tripPath.generateTimes(variants.getTimeGenerator());
		return tripPath;
	}

	/**
	 * @return the track a train turned back early runs along out of service, stopping nowhere but at a siding at the end
	 */
	private static <T extends PlanTrack> T passing(T track, PathVariants<T> variants) {
		return track.getDwellTime() > 0 && track.isPlatformTrack() ? variants.pass(track) : track;
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
		final long[] segmentCutValues = reader.getLongs(KEY_TRIP_SEGMENT_CUTS);
		for (int i = 0; i + 1 < segmentCutValues.length; i += 2) {
			segmentCuts.put((int) segmentCutValues[i], (int) segmentCutValues[i + 1]);
		}
		final long[] segmentEntryValues = reader.getLongs(KEY_TRIP_SEGMENT_ENTRIES);
		for (int i = 0; i + 1 < segmentEntryValues.length; i += 2) {
			segmentEntries.put((int) segmentEntryValues[i], (int) segmentEntryValues[i + 1]);
		}
		readTimes(reader.getLongs(KEY_TRIP_SEGMENT_ENTRY_TIMES), (segment, times) -> segmentEntryTimes.put((int) (long) segment, times));
		segmentsKnown = reader.getBoolean(KEY_TRIP_SEGMENTS_KNOWN, false);
		final int[] starts = toInts(reader.getLongs(KEY_TRIP_DUTY_STARTS));
		dutyStarts = key.startsWith(DutyShapes.KEY_PREFIX) ? starts : null;
		staysOnLine = reader.getBoolean(KEY_TRIP_DUTY_STAYS, false);
		turnRail = -1;
		turnResumeRail = -1;
	}

	/**
	 * Reads what {@link #writeTimes} wrote: for each, its key, how many stops, then the arrival and departure at each.
	 */
	private static void readTimes(long[] values, java.util.function.BiConsumer<Long, long[][]> consumer) {
		for (int i = 0; i + 1 < values.length; ) {
			final int count = (int) values[i + 1];
			if (count < 0 || i + 2 + count * 2 > values.length) {
				break;
			}
			final long[][] times = new long[count][];
			for (int j = 0; j < count; j++) {
				times[j] = new long[]{values[i + 2 + j * 2], values[i + 3 + j * 2]};
			}
			consumer.accept(values[i], times);
			i += 2 + count * 2;
		}
	}

	private static long[] writeTimes(Map<Integer, long[][]> timesByKey) {
		final List<Long> values = new ArrayList<>();
		new java.util.TreeMap<>(timesByKey).forEach((key, times) -> {
			values.add((long) key);
			values.add((long) times.length);
			for (final long[] time : times) {
				values.add(time[0]);
				values.add(time[1]);
			}
		});
		return values.stream().mapToLong(Long::longValue).toArray();
	}

	private static long[] writePairs(Map<Integer, Integer> pairs) {
		final List<Long> values = new ArrayList<>();
		new java.util.TreeMap<>(pairs).forEach((key, value) -> {
			values.add((long) key);
			values.add((long) value);
		});
		return values.stream().mapToLong(Long::longValue).toArray();
	}

	/**
	 * Gives the stop times of the path what the plans need to know of where its routes are cut and joined.
	 */
	private void applyShape(StopTimes times) {
		times.cutStops.clear();
		times.cutStops.putAll(cutStops);
		times.entryStops.clear();
		times.entryStops.putAll(entryStops);
		times.entryTimes.clear();
		times.entryTimes.putAll(entryTimes);
		times.segmentsKnown = segmentsKnown;
		times.segmentCuts.clear();
		times.segmentCuts.putAll(segmentCuts);
		times.segmentEntries.clear();
		times.segmentEntries.putAll(segmentEntries);
		times.segmentLeadSources.clear();
		times.segmentLeadSources.putAll(segmentLeadSources);
		times.segmentEntryTimes.clear();
		// A segment joined part of the way along takes its route's lead, unless the path has its own for it
		segmentEntries.forEach((segment, entry) -> {
			final long[][] own = segmentEntryTimes.get(segment);
			final long[][] byRoute = segment < routeIds.size() && entryStops.getOrDefault(routeIds.get(segment), -1).equals(entry) ? entryTimes.get(routeIds.get(segment)) : null;
			if (own != null || byRoute != null) {
				times.segmentEntryTimes.put(segment, own != null ? own : byRoute);
			}
		});
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
		// The times worked out elsewhere keep their own leads of routes joined part of the way along
		tripPath.entryTimes.putAll(newStopTimes.entryTimes);
		tripPath.applyShape(tripPath.stopTimes);
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
	public static long getReturnSidingId(String fullKey) {
		final String key = getSkipBase(fullKey);
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
	/**
	 * After the key of a path, the stops of it passed without stopping (D3); no other key has it.
	 */
	private static final String SKIP_MARK = "~skip:";
	/**
	 * After the key of a duty's path, where it turns back early (D4); before {@link #SKIP_MARK} when both.
	 */
	private static final String TURN_MARK = "~turn:";
	/**
	 * After the key of a duty's path, the segment a train starts it at out of the siding, or the segment after which it
	 * hands the rest over (D5); before {@link #SKIP_MARK} when both.
	 */
	private static final String FROM_MARK = "~from:";
	private static final String END_MARK = "~end:";

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
		applyShape(stopTimes);
		stopTimes.firstRouteJoined = isDuty();
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
		writer.writeLongs(KEY_TRIP_SEGMENT_CUTS, writePairs(segmentCuts));
		writer.writeLongs(KEY_TRIP_SEGMENT_ENTRIES, writePairs(segmentEntries));
		writer.writeLongs(KEY_TRIP_SEGMENT_ENTRY_TIMES, writeTimes(segmentEntryTimes));
		writer.writeBoolean(KEY_TRIP_SEGMENTS_KNOWN, segmentsKnown);
	}
}
