package mtr.data;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The dashboard map is split into entries, each with a hash, so a client only downloads entries that changed.
 * Rails are grouped into square regions. Each route is one entry, and so is the generated path of each siding.
 */
public final class MapSyncData {

	public static final int KIND_RAILS = 0;
	public static final int KIND_ROUTES = 1;
	public static final int KIND_SIDINGS = 2;
	/**
	 * A few points along each curved rail, by the same regions as the rails: node, node, count, then that many points
	 * packed by {@link RailShapes#pack}, so the maps draw a long curve along it rather than straight across.
	 */
	public static final int KIND_RAIL_SHAPES = 3;
	public static final int KIND_COUNT = 4;
	public static final int RAIL_LONGS = 3;
	private static final int REGION_BITS = 7;
	private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

	private MapSyncData() {
	}

	public static long regionKey(int x, int z) {
		return (long) (x >> REGION_BITS) << 32 | (z >> REGION_BITS) & 0xFFFFFFFFL;
	}

	/**
	 * The rails of a region arrive in no particular order, so each rail is hashed alone and the results added.
	 */
	public static long hashRails(long[] payload) {
		long hash = payload.length;
		for (int i = 0; i + 2 < payload.length; i += RAIL_LONGS) {
			hash += mix(mix(mix(0, payload[i]), payload[i + 1]), payload[i + 2]);
		}
		return hash;
	}

	public static long hashRoute(long[] payload) {
		long hash = payload.length;
		for (final long value : payload) {
			hash = mix(hash, value);
		}
		return hash;
	}

	public static Map<Long, long[]> toPayloads(Map<Long, ? extends List<Long>> regions) {
		final Map<Long, long[]> payloads = new TreeMap<>();
		regions.forEach((key, values) -> payloads.put(key, values.stream().mapToLong(Long::longValue).toArray()));
		return payloads;
	}

	private static long mix(long hash, long value) {
		final long mixed = (hash ^ value) * HASH_MULTIPLIER;
		return mixed ^ mixed >>> 29;
	}
}
