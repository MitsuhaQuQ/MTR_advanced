package mtr.advanced;

import net.minecraft.network.FriendlyByteBuf;
import transitsim.network.PlatformGraph;
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
		writePlatformGraph(packet, regionData.platformGraph);
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
		return new RegionData(boundaries, regionCount, uncutNodes, uncutSegments, missingNodes, missingSegments, platformRegions, sidingRegions, readPlatformGraph(packet));
	}

	/**
	 * Each platform with its network and, for each of its two states, whether it can be used and the edges from it.
	 */
	private static void writePlatformGraph(FriendlyByteBuf packet, PlatformGraph platformGraph) {
		packet.writeInt(platformGraph.size());
		for (int i = 0; i < platformGraph.size(); i++) {
			packet.writeLong(platformGraph.getPlatformId(i));
			packet.writeLong(platformGraph.getNetwork(i));
			for (int direction = 0; direction < 2; direction++) {
				final int state = i * 2 + direction;
				final int[] targets = platformGraph.getTargets(state);
				final double[] costs = platformGraph.getCosts(state);
				packet.writeBoolean(platformGraph.isUsable(state));
				packet.writeInt(targets.length);
				for (int j = 0; j < targets.length; j++) {
					packet.writeInt(targets[j]);
					packet.writeFloat((float) costs[j]);
				}
			}
		}
	}

	private static PlatformGraph readPlatformGraph(FriendlyByteBuf packet) {
		final int count = limit(packet.readInt());
		final long[] platformIds = new long[count];
		final long[] networks = new long[count];
		final boolean[] usable = new boolean[count * 2];
		final int[][] targets = new int[count * 2][];
		final double[][] costs = new double[count * 2][];
		for (int i = 0; i < count; i++) {
			platformIds[i] = packet.readLong();
			networks[i] = packet.readLong();
			for (int direction = 0; direction < 2; direction++) {
				final int state = i * 2 + direction;
				usable[state] = packet.readBoolean();
				final int edgeCount = limit(packet.readInt());
				targets[state] = new int[edgeCount];
				costs[state] = new double[edgeCount];
				for (int j = 0; j < edgeCount; j++) {
					final int target = packet.readInt();
					if (target < 0 || target >= count * 2) {
						throw new IllegalArgumentException("Bad platform state " + target);
					}
					targets[state][j] = target;
					costs[state][j] = packet.readFloat();
				}
			}
		}
		return new PlatformGraph(platformIds, networks, usable, targets, costs);
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
