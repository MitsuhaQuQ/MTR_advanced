package mtr.advanced;

import net.minecraft.network.FriendlyByteBuf;
import transitsim.network.RegionData;

import java.util.*;

/**
 * Sends the {@link RegionData} worked out by the server to every player.
 */
public final class RegionDataPackets {

	private static final int MAX_ENTRIES = 1 << 20;

	private RegionDataPackets() {
	}

	public static void write(RegionData regionData, FriendlyByteBuf packet) {
		PathBoundaries.write(regionData.boundaries, packet);
		packet.writeInt(regionData.regionCount);
		writeLongs(packet, regionData.uncutNodes);
		writeUuids(packet, regionData.uncutSegments);
		writeLongs(packet, regionData.missingNodes);
		writeUuids(packet, regionData.missingSegments);
		writeRegions(packet, regionData.platformRegions);
		writeRegions(packet, regionData.sidingRegions);
		packet.writeInt(regionData.routeCandidates.size());
		regionData.routeCandidates.forEach((routeId, stops) -> {
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
