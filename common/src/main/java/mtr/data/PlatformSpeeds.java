package mtr.data;

import transitsim.network.RailKeys;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pass speed of each platform set to one other than the default (optimisation round 3b, step 2), by its rail, for
 * paths to look up as they run: a platform's rail runs by it rather than the platform rail type's label. Each data cache
 * (each dimension on a server, the client's) gives its platforms whenever it syncs, and a lookup sees them all, so a
 * change takes effect at once without finding paths again.
 */
public final class PlatformSpeeds {

	/**
	 * A platform's pass speed when none is set, in km/h: the platform rail type's label.
	 */
	public static final int DEFAULT_KMH = 80;

	private static final Map<Object, Map<UUID, Integer>> SOURCES = new ConcurrentHashMap<>();
	private static volatile Map<UUID, Integer> merged = Collections.emptyMap();

	private PlatformSpeeds() {
	}

	/**
	 * @param source the data cache the platforms are of
	 */
	public static void update(Object source, Collection<Platform> platforms) {
		final Map<UUID, Integer> speeds = new HashMap<>();
		platforms.forEach(platform -> {
			if (platform.passSpeed > 0 && platform.passSpeed != DEFAULT_KMH) {
				final java.util.List<net.minecraft.core.BlockPos> positions = platform.getOrderedPositions(net.minecraft.core.BlockPos.ZERO, false);
				speeds.put(RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong()), platform.passSpeed);
			}
		});
		final Map<UUID, Integer> old = SOURCES.get(source);
		if (speeds.equals(old) || old == null && speeds.isEmpty()) {
			return;
		}
		SOURCES.put(source, speeds);
		final Map<UUID, Integer> newMerged = new HashMap<>();
		SOURCES.values().forEach(newMerged::putAll);
		merged = newMerged;
	}

	/**
	 * @return a hash of the pass speeds set, which changes when any does
	 */
	public static long fingerprint() {
		return merged.hashCode();
	}

	/**
	 * @return the pass speed of the platform on a rail, in km/h, or the default if none is set or it isn't a platform's
	 */
	public static int getKmh(long node1, long node2) {
		final Map<UUID, Integer> current = merged;
		return current.isEmpty() ? DEFAULT_KMH : current.getOrDefault(RailKeys.of(node1, node2), DEFAULT_KMH);
	}
}
