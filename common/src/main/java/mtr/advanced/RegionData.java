package mtr.advanced;

import net.minecraft.network.FriendlyByteBuf;

import java.util.*;

/**
 * What the server works out from the pathfinding boundaries and sends to every player: the boundaries themselves, which
 * regions each platform and siding is in, and the platforms each stop of a route could use instead (design document
 * 5.13 and 6.12, block P).
 */
public final class RegionData {

	public final PathBoundaries boundaries;
	public final int regionCount;
	public final List<Long> uncutNodes;
	public final List<UUID> uncutSegments;
	public final List<Long> missingNodes;
	public final List<UUID> missingSegments;
	public final Map<Long, int[]> platformRegions;
	public final Map<Long, int[]> sidingRegions;
	/**
	 * Route ID → stop index → platform IDs a train on this route could stop at instead: platforms of the same station,
	 * in the same region, which can be reached from the stop before and lead on to the stop after. Stops with no other
	 * platform are left out.
	 */
	public final Map<Long, Map<Integer, long[]>> routeCandidates;

	public static final RegionData EMPTY = new RegionData(new PathBoundaries(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

	private static final int MAX_ENTRIES = 1 << 20;

	public RegionData(PathBoundaries boundaries, int regionCount, List<Long> uncutNodes, List<UUID> uncutSegments, List<Long> missingNodes, List<UUID> missingSegments, Map<Long, int[]> platformRegions, Map<Long, int[]> sidingRegions, Map<Long, Map<Integer, long[]>> routeCandidates) {
		this.boundaries = boundaries;
		this.regionCount = regionCount;
		this.uncutNodes = uncutNodes;
		this.uncutSegments = uncutSegments;
		this.missingNodes = missingNodes;
		this.missingSegments = missingSegments;
		this.platformRegions = platformRegions;
		this.sidingRegions = sidingRegions;
		this.routeCandidates = routeCandidates;
	}

	/**
	 * @return the platforms a stop could use, always including the one the route uses now
	 */
	public long[] getCandidates(long routeId, int stopIndex, long platformId) {
		final Map<Integer, long[]> stops = routeCandidates.get(routeId);
		final long[] candidates = stops == null ? null : stops.get(stopIndex);
		return candidates == null ? new long[]{platformId} : candidates;
	}

	public boolean isBoundaryNode(long pos) {
		return boundaries.nodes.contains(pos);
	}

	public void write(FriendlyByteBuf packet) {
		boundaries.write(packet);
		packet.writeInt(regionCount);
		writeLongs(packet, uncutNodes);
		writeUuids(packet, uncutSegments);
		writeLongs(packet, missingNodes);
		writeUuids(packet, missingSegments);
		writeRegions(packet, platformRegions);
		writeRegions(packet, sidingRegions);
		packet.writeInt(routeCandidates.size());
		routeCandidates.forEach((routeId, stops) -> {
			packet.writeLong(routeId);
			packet.writeInt(stops.size());
			stops.forEach((stopIndex, platformIds) -> {
				packet.writeInt(stopIndex);
				packet.writeInt(platformIds.length);
				for (final long platformId : platformIds) {
					packet.writeLong(platformId);
				}
			});
		});
	}

	public static RegionData read(FriendlyByteBuf packet) {
		final PathBoundaries boundaries = PathBoundaries.read(packet);
		final int regionCount = packet.readInt();
		final List<Long> uncutNodes = readLongs(packet);
		final List<UUID> uncutSegments = readUuids(packet);
		final List<Long> missingNodes = readLongs(packet);
		final List<UUID> missingSegments = readUuids(packet);
		final Map<Long, int[]> platformRegions = readRegions(packet);
		final Map<Long, int[]> sidingRegions = readRegions(packet);
		final Map<Long, Map<Integer, long[]>> routeCandidates = new HashMap<>();
		final int routeCount = limit(packet.readInt());
		for (int i = 0; i < routeCount; i++) {
			final Map<Integer, long[]> stops = new HashMap<>();
			routeCandidates.put(packet.readLong(), stops);
			final int stopCount = limit(packet.readInt());
			for (int j = 0; j < stopCount; j++) {
				final int stopIndex = packet.readInt();
				final long[] platformIds = new long[limit(packet.readInt())];
				for (int k = 0; k < platformIds.length; k++) {
					platformIds[k] = packet.readLong();
				}
				stops.put(stopIndex, platformIds);
			}
		}
		return new RegionData(boundaries, regionCount, uncutNodes, uncutSegments, missingNodes, missingSegments, platformRegions, sidingRegions, routeCandidates);
	}

	private static void writeLongs(FriendlyByteBuf packet, List<Long> values) {
		packet.writeInt(values.size());
		values.forEach(packet::writeLong);
	}

	private static List<Long> readLongs(FriendlyByteBuf packet) {
		final List<Long> values = new ArrayList<>();
		final int count = limit(packet.readInt());
		for (int i = 0; i < count; i++) {
			values.add(packet.readLong());
		}
		return values;
	}

	private static void writeUuids(FriendlyByteBuf packet, List<UUID> values) {
		packet.writeInt(values.size());
		values.forEach(packet::writeUUID);
	}

	private static List<UUID> readUuids(FriendlyByteBuf packet) {
		final List<UUID> values = new ArrayList<>();
		final int count = limit(packet.readInt());
		for (int i = 0; i < count; i++) {
			values.add(packet.readUUID());
		}
		return values;
	}

	private static void writeRegions(FriendlyByteBuf packet, Map<Long, int[]> regions) {
		packet.writeInt(regions.size());
		regions.forEach((id, idRegions) -> {
			packet.writeLong(id);
			packet.writeInt(idRegions.length);
			for (final int region : idRegions) {
				packet.writeInt(region);
			}
		});
	}

	private static Map<Long, int[]> readRegions(FriendlyByteBuf packet) {
		final Map<Long, int[]> regions = new HashMap<>();
		final int count = limit(packet.readInt());
		for (int i = 0; i < count; i++) {
			final long id = packet.readLong();
			final int[] idRegions = new int[limit(packet.readInt())];
			for (int j = 0; j < idRegions.length; j++) {
				idRegions[j] = packet.readInt();
			}
			regions.put(id, idRegions);
		}
		return regions;
	}

	private static int limit(int count) {
		if (count < 0 || count > MAX_ENTRIES) {
			throw new IllegalArgumentException("Too many region entries: " + count);
		}
		return count;
	}
}
