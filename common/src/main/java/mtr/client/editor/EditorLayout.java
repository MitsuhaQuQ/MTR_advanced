package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rails around a station, for the track diagram of the advanced editor (design document 6.12, block D): the rail
 * lines the dashboard map draws, which every client keeps, so nothing is asked of the server. Curved rails are drawn as
 * the straight line between their ends, which is enough to see which track joins which.
 */
public final class EditorLayout {

	private static final int DEFAULT_MARGIN = 48;
	private static final int MIN_MARGIN = 16;
	private static final int MAX_MARGIN = 256;
	private static final int MAX_RAILS = 4000;

	private EditorLayout() {
	}

	/**
	 * @return {@code {ready, box: [minX, minZ, maxX, maxZ], rails: [[x1, z1, x2, z2]], truncated, platforms: [...], turnouts:
	 * [[x, z]]}}, or null if there is no such station or it has no platform
	 */
	public static JsonObject build(long stationId, int requestedMargin) {
		final Station station = ClientData.DATA_CACHE.stationIdMap.get(stationId);
		final Map<Long, Platform> stationPlatforms = station == null ? null : ClientData.DATA_CACHE.requestStationIdToPlatforms(stationId);
		if (stationPlatforms == null || stationPlatforms.isEmpty()) {
			return null;
		}
		final JsonObject layout = new JsonObject();
		if (ClientData.MAP_RAILS.isEmpty()) {
			PacketTrainDataGuiClient.requestMapRailsC2S();
			layout.addProperty("ready", false);
			return layout;
		}
		layout.addProperty("ready", true);

		final int margin = Math.max(MIN_MARGIN, Math.min(MAX_MARGIN, requestedMargin <= 0 ? DEFAULT_MARGIN : requestedMargin));
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		TransportMode transportMode = null;
		for (final Platform platform : stationPlatforms.values()) {
			for (final BlockPos pos : ends(platform)) {
				minX = Math.min(minX, pos.getX());
				minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX());
				maxZ = Math.max(maxZ, pos.getZ());
			}
			transportMode = platform.transportMode;
		}
		minX -= margin;
		minZ -= margin;
		maxX += margin;
		maxZ += margin;
		final JsonArray box = new JsonArray();
		box.add(minX);
		box.add(minZ);
		box.add(maxX);
		box.add(maxZ);
		layout.add("box", box);

		final JsonArray rails = new JsonArray();
		final Map<Long, Integer> ends = new HashMap<>();
		boolean truncated = false;
		for (final ClientData.MapRail rail : ClientData.MAP_RAILS) {
			if (rail.transportMode != transportMode || Math.max(rail.x1, rail.x2) < minX || Math.min(rail.x1, rail.x2) > maxX || Math.max(rail.z1, rail.z2) < minZ || Math.min(rail.z1, rail.z2) > maxZ) {
				continue;
			}
			if (rails.size() >= MAX_RAILS) {
				truncated = true;
				break;
			}
			final JsonArray railArray = new JsonArray();
			railArray.add(rail.x1);
			railArray.add(rail.z1);
			railArray.add(rail.x2);
			railArray.add(rail.z2);
			rails.add(railArray);
			ends.merge(key(rail.x1, rail.z1), 1, Integer::sum);
			ends.merge(key(rail.x2, rail.z2), 1, Integer::sum);
		}
		layout.add("rails", rails);
		layout.addProperty("truncated", truncated);

		// Where three or more rails meet, rails branch
		final JsonArray turnouts = new JsonArray();
		ends.forEach((end, count) -> {
			if (count >= 3) {
				final JsonArray point = new JsonArray();
				point.add((int) (end >> 32));
				point.add((int) (long) end);
				turnouts.add(point);
			}
		});
		layout.add("turnouts", turnouts);

		final JsonArray platformsArray = new JsonArray();
		for (final Platform platform : ClientData.PLATFORMS) {
			if (platform.transportMode != transportMode) {
				continue;
			}
			final List<BlockPos> platformEnds = ends(platform);
			final BlockPos pos1 = platformEnds.get(0), pos2 = platformEnds.get(1);
			if (Math.max(pos1.getX(), pos2.getX()) < minX || Math.min(pos1.getX(), pos2.getX()) > maxX || Math.max(pos1.getZ(), pos2.getZ()) < minZ || Math.min(pos1.getZ(), pos2.getZ()) > maxZ) {
				continue;
			}
			final Station platformStation = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
			final JsonObject platformObject = new JsonObject();
			platformObject.addProperty("id", String.valueOf(platform.id));
			platformObject.addProperty("name", platform.name);
			platformObject.addProperty("station", platformStation == null ? "" : String.valueOf(platformStation.id));
			platformObject.addProperty("stationName", platformStation == null ? "" : platformStation.name);
			platformObject.addProperty("passingTrack", platform.passingTrack);
			final JsonArray line = new JsonArray();
			line.add(pos1.getX());
			line.add(pos1.getZ());
			line.add(pos2.getX());
			line.add(pos2.getZ());
			platformObject.add("line", line);
			platformsArray.add(platformObject);
		}
		layout.add("platforms", platformsArray);
		return layout;
	}

	private static List<BlockPos> ends(Platform platform) {
		return platform.getOrderedPositions(BlockPos.ZERO, false);
	}

	private static long key(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}
}
