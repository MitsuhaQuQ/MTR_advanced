package mtr.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where trains stop at each platform set to stop them other than at its exit end (optimisation round 4, stop points;
 * {@link transitsim.plan.StopPositions}), by platform ID, for paths to look up. Each data cache (each dimension on a
 * server, the client's) gives its platforms whenever it syncs, and a lookup sees them all. A train takes them when it is
 * given a path, so a change reaches running trains as a changed stop time does: when their depot finds paths again.
 */
public final class PlatformStops {

	private static final Map<Object, Map<Long, Integer>> SOURCES = new ConcurrentHashMap<>();
	private static volatile Map<Long, Integer> merged = Collections.emptyMap();

	private PlatformStops() {
	}

	/**
	 * @param source the data cache the platforms are of
	 */
	public static void update(Object source, Collection<Platform> platforms) {
		final Map<Long, Integer> settings = new HashMap<>();
		platforms.forEach(platform -> {
			if (platform.stopPosition != transitsim.plan.StopPositions.EXIT_END) {
				settings.put(platform.id, platform.stopPosition);
			}
		});
		final Map<Long, Integer> old = SOURCES.get(source);
		if (settings.equals(old) || old == null && settings.isEmpty()) {
			return;
		}
		SOURCES.put(source, settings);
		final Map<Long, Integer> newMerged = new HashMap<>();
		SOURCES.values().forEach(newMerged::putAll);
		merged = newMerged;
	}

	/**
	 * @return a hash of the settings, which changes when any does
	 */
	public static long fingerprint() {
		return merged.hashCode();
	}

	/**
	 * @return where trains stop at the platform, {@link transitsim.plan.StopPositions#EXIT_END} if it isn't set
	 */
	public static int get(long platformId) {
		final Map<Long, Integer> current = merged;
		return current.isEmpty() ? transitsim.plan.StopPositions.EXIT_END : current.getOrDefault(platformId, transitsim.plan.StopPositions.EXIT_END);
	}
}
