package transitsim.plan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service leaving the usual path of a depot for another route. The path up to there is the same as the usual path, so a
 * train can change over while it runs the usual path.
 */
public class TripTail<T extends PlanTrack, P> {

	public static final int NO_RAIL = Integer.MIN_VALUE;

	public final String key;
	public final List<Long> routeIds;
	public final Map<Long, Integer> cutStops;
	public final int leaveRail;
	public final List<T> path;
	public final P lastPlatform;
	public final int lapStart;
	public final boolean isLapReversed;
	public final int entryRail;
	public final Map<Long, Integer> entryStops;
	public final Map<Long, List<T>> entryLeads;

	public TripTail(String key, List<Long> routeIds, Map<Long, Integer> cutStops, int leaveRail, List<T> path, P lastPlatform, int lapStart, boolean isLapReversed, int entryRail) {
		this(key, routeIds, cutStops, leaveRail, path, lastPlatform, lapStart, isLapReversed, entryRail, new HashMap<>(), new HashMap<>());
	}

	/**
	 * @param key           the key of this way through, see {@link #getKey(List)}
	 * @param routeIds      the routes of the whole trip in order
	 * @param cutStops      for routes left part of the way along, the stop where the train leaves them
	 * @param leaveRail     the rail of the main path where the train leaves it
	 * @param path          the rest of the way to the end of the last route
	 * @param lastPlatform  the last stop of the last route
	 * @param lapStart      for a loop at the end, the rail of {@code path} where its first stop is, or {@link #NO_RAIL}
	 * @param isLapReversed true if a train at the end of a lap goes back along the rail it came in on
	 * @param entryRail     the rail of {@code path} where a train joins that loop part of the way round, or {@link #NO_RAIL}
	 * @param entryStops    for routes other than such a loop joined part of the way along, the stop where the train joins them
	 * @param entryLeads    for those routes, the path from their first stop to that stop, which the train doesn't run but
	 *                      plans by, as its service counts from the first stop
	 */
	public TripTail(String key, List<Long> routeIds, Map<Long, Integer> cutStops, int leaveRail, List<T> path, P lastPlatform, int lapStart, boolean isLapReversed, int entryRail, Map<Long, Integer> entryStops, Map<Long, List<T>> entryLeads) {
		this.key = key;
		this.routeIds = routeIds;
		this.cutStops = cutStops;
		this.leaveRail = leaveRail;
		this.path = path;
		this.lastPlatform = lastPlatform;
		this.lapStart = lapStart;
		this.isLapReversed = isLapReversed;
		this.entryRail = entryRail;
		this.entryStops = entryStops;
		this.entryLeads = entryLeads;
	}

	/**
	 * @return a key for the way a train goes with these links, which doesn't depend on the times of the services
	 */
	public static String getKey(List<RouteTimetable.ServiceLink> links) {
		final StringBuilder key = new StringBuilder();
		for (final RouteTimetable.ServiceLink link : links) {
			key.append(key.length() == 0 ? "" : ">").append(link.routeId).append("@").append(link.fromStop);
			if (link.toStop > 0) {
				key.append("+").append(link.toStop);
			}
		}
		return key.toString();
	}
}
