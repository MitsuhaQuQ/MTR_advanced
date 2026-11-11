package mtr.client.editor;

import mtr.data.Route;
import java.util.HashMap;
import java.util.Map;

/** Timing results are valid for their physical inputs, not merely their route ID. */
public final class TimingCache {
	private final Map<Long, String> keys = new HashMap<>();
	private final Map<Long, Request> requests = new HashMap<>();
	private int sequence;

	private static final class Request {
		final int id;
		final String key;
		Request(int id, String key) { this.id = id; this.key = key; }
	}

	public static String key(Route route) {
		StringBuilder key = new StringBuilder().append(route.id).append(':').append(route.transportMode).append(':').append(route.speedLimit);
		route.getPathData().forEach(rail -> key.append(';').append(rail.getRailProduct()).append(':').append(rail.savedRailBaseId).append(':').append(rail.dwellTime).append(':').append(rail.rail.getLength()).append(':').append(rail.getPlanSpeed()));
		route.platformIds.forEach(stop -> key.append('|').append(stop.platformId).append(':').append(stop.pass).append(':').append(stop.dwellOverride).append(':').append(stop.waypoints));
		route.timetable.getTables().forEach(table -> key.append('/').append(table.minAcceleration).append(':').append(table.minDeceleration).append(':').append(table.minMaxSpeed));
		return key.toString();
	}

	public int request(Route route) {
		sequence = sequence == Integer.MIN_VALUE ? -1 : sequence - 1;
		requests.put(route.id, new Request(sequence, key(route)));
		return sequence;
	}

	public boolean accept(Route route, int id) {
		Request request = requests.get(route.id);
		if (request == null || request.id != id || !request.key.equals(key(route))) return false;
		requests.remove(route.id);
		keys.put(route.id, request.key);
		return true;
	}

	public void remember(Route route) { keys.put(route.id, key(route)); }
	public boolean matches(Route route) { return key(route).equals(keys.get(route.id)); }
	public void clear() { keys.clear(); requests.clear(); }
}
