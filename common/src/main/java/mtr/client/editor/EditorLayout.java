package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.advanced.RouteLayouts;
import mtr.advanced.StationLayouts;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.TransportMode;
import mtr.data.Station;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * The rails around a station, for the track diagram of the advanced editor (design document 6.12, block D). The box is
 * made around the tracks of the station the picker offers, which are on the same rails, so platforms of another line in
 * the station's area don't stretch it. The server sends the rails in it as their real shape ({@link StationLayouts}),
 * since the client only has full rails near the player; the page asks again until they have come.
 */
public final class EditorLayout {

	private static final int DEFAULT_MARGIN = 48;
	private static final int MIN_MARGIN = 16;
	private static final int MAX_MARGIN = 256;
	private static final long REQUEST_AGAIN_MILLIS = 3000;

	private static StationLayouts.Layout latest;
	private static String requested = "";
	private static long requestedMillis;
	private static RouteLayouts.Layout latestRoute;
	private static long latestRouteMillis;
	private static String requestedRoute = "";
	private static long requestedRouteMillis;

	private EditorLayout() {
	}

	/**
	 * Keeps what the server sent for the last box asked for.
	 */
	public static void receive(StationLayouts.Layout layout) {
		latest = layout;
	}

	public static void receiveRoute(RouteLayouts.Layout layout) {
		latestRoute = layout;
		latestRouteMillis = System.currentTimeMillis();
	}

	/**
	 * The straightened track diagram of a route ({@link RouteLayouts}), asked for again each time the page opens it, so it
	 * shows the rails as they are now.
	 *
	 * @param fresh true when the page has just opened it, which asks the server again
	 * @return {@code {ready, found, length, truncated, stops: [distance], rails: [[distance, offset, ...]], turnouts:
	 * [[distance, offset]], platforms: [{id, name, station, stationName, passingTrack, line: [d, o, d, o]}]}}
	 */
	public static JsonObject buildRoute(long routeId, int requestedHalfWidth, boolean fresh) {
		final int halfWidth = Math.max(RouteLayouts.MIN_HALF_WIDTH, Math.min(RouteLayouts.MAX_HALF_WIDTH, requestedHalfWidth <= 0 ? RouteLayouts.DEFAULT_HALF_WIDTH : requestedHalfWidth));
		final String key = routeId + "|" + halfWidth;
		final long millis = System.currentTimeMillis();
		if (fresh || !key.equals(requestedRoute) || latestRoute == null && millis - requestedRouteMillis > REQUEST_AGAIN_MILLIS) {
			requestedRoute = key;
			requestedRouteMillis = millis;
			PacketTrainDataGuiClient.requestRouteLayoutC2S(routeId, halfWidth);
		}
		final JsonObject layout = new JsonObject();
		final RouteLayouts.Layout received = latestRoute;
		if (received == null || received.routeId != routeId || received.halfWidth != halfWidth || latestRouteMillis < requestedRouteMillis) {
			layout.addProperty("ready", false);
			return layout;
		}
		layout.addProperty("ready", true);
		layout.addProperty("found", received.found);
		layout.addProperty("halfWidth", received.halfWidth);
		layout.addProperty("length", received.length);
		layout.addProperty("truncated", received.truncated);
		final JsonArray stops = new JsonArray();
		for (final float stop : received.stops) {
			stops.add(Math.round(stop * 10) / 10.0);
		}
		layout.add("stops", stops);
		final JsonArray rails = new JsonArray();
		received.pieces.forEach(piece -> rails.add(round(piece)));
		layout.add("rails", rails);
		final JsonArray turnouts = new JsonArray();
		received.turnouts.forEach(turnout -> turnouts.add(round(turnout)));
		layout.add("turnouts", turnouts);
		final JsonArray platformsArray = new JsonArray();
		received.platforms.forEach((id, line) -> {
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(id);
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(id);
			final JsonObject platformObject = new JsonObject();
			platformObject.addProperty("id", String.valueOf(id));
			platformObject.addProperty("name", platform == null ? "" : platform.name);
			platformObject.addProperty("station", station == null ? "" : String.valueOf(station.id));
			platformObject.addProperty("stationName", station == null ? "" : station.name);
			platformObject.addProperty("passingTrack", platform != null && platform.passingTrack);
			platformObject.add("line", round(line));
			platformsArray.add(platformObject);
		});
		layout.add("platforms", platformsArray);
		return layout;
	}

	private static JsonArray round(float[] values) {
		final JsonArray array = new JsonArray();
		for (final float value : values) {
			array.add(Math.round(value * 10) / 10.0);
		}
		return array;
	}

	/**
	 * @param trackIds the station's tracks the picker offers, or none for all the station's platforms
	 * @return {@code {ready, box: [minX, minZ, maxX, maxZ], rails: [[x, z, x, z, ...]], truncated, platforms: [...],
	 * turnouts: [[x, z]]}}, or null if there is no such station or it has no platform
	 */
	public static JsonObject build(long stationId, int requestedMargin, List<Long> trackIds) {
		final List<Platform> tracks = new ArrayList<>();
		trackIds.forEach(id -> {
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(id);
			if (platform != null) {
				tracks.add(platform);
			}
		});
		if (tracks.isEmpty()) {
			final Map<Long, Platform> stationPlatforms = ClientData.DATA_CACHE.stationIdMap.containsKey(stationId) ? ClientData.DATA_CACHE.requestStationIdToPlatforms(stationId) : null;
			if (stationPlatforms != null) {
				tracks.addAll(stationPlatforms.values());
			}
		}
		if (tracks.isEmpty()) {
			return null;
		}

		final TransportMode transportMode = tracks.get(0).transportMode;
		final int margin = Math.max(MIN_MARGIN, Math.min(MAX_MARGIN, requestedMargin <= 0 ? DEFAULT_MARGIN : requestedMargin));
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (final Platform platform : tracks) {
			for (final BlockPos pos : ends(platform)) {
				minX = Math.min(minX, pos.getX());
				minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX());
				maxZ = Math.max(maxZ, pos.getZ());
			}
		}
		final int[] box = StationLayouts.clampBox(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
		// The diagram shows only rails joined by track to these platforms
		final long[] seeds = tracks.stream().flatMap(platform -> ends(platform).stream()).mapToLong(BlockPos::asLong).distinct().sorted().limit(StationLayouts.MAX_SEEDS).toArray();

		final JsonObject layout = new JsonObject();
		final StationLayouts.Layout received = latest;
		if (received == null || !Arrays.equals(received.box, box) || received.mode != transportMode.ordinal() || !Arrays.equals(received.seeds, seeds)) {
			final String key = Arrays.toString(box) + transportMode + Arrays.toString(seeds);
			final long millis = System.currentTimeMillis();
			if (!key.equals(requested) || millis - requestedMillis > REQUEST_AGAIN_MILLIS) {
				requested = key;
				requestedMillis = millis;
				PacketTrainDataGuiClient.requestStationLayoutC2S(box, transportMode, seeds);
			}
			layout.addProperty("ready", false);
			return layout;
		}
		layout.addProperty("ready", true);
		final JsonArray boxArray = new JsonArray();
		for (final int value : box) {
			boxArray.add(value);
		}
		layout.add("box", boxArray);
		layout.addProperty("truncated", received.truncated);

		final JsonArray rails = new JsonArray();
		final Map<Long, Integer> ends = new HashMap<>();
		received.lines.forEach(points -> {
			final JsonArray line = new JsonArray();
			for (final float value : points) {
				line.add(Math.round(value * 10) / 10.0);
			}
			rails.add(line);
			ends.merge(key(points[0], points[1]), 1, Integer::sum);
			ends.merge(key(points[points.length - 2], points[points.length - 1]), 1, Integer::sum);
		});
		layout.add("rails", rails);

		// Where three or more rails meet, rails branch
		final JsonArray turnouts = new JsonArray();
		ends.forEach((end, count) -> {
			if (count >= 3) {
				final JsonArray point = new JsonArray();
				point.add((int) (end >> 32) / 2.0);
				point.add((int) (long) end / 2.0);
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
			if (Math.max(pos1.getX(), pos2.getX()) < box[0] || Math.min(pos1.getX(), pos2.getX()) > box[2] || Math.max(pos1.getZ(), pos2.getZ()) < box[1] || Math.min(pos1.getZ(), pos2.getZ()) > box[3]) {
				continue;
			}
			final Station platformStation = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
			final JsonObject platformObject = new JsonObject();
			platformObject.addProperty("id", String.valueOf(platform.id));
			platformObject.addProperty("name", platform.name);
			platformObject.addProperty("station", platformStation == null ? "" : String.valueOf(platformStation.id));
			platformObject.addProperty("stationName", platformStation == null ? "" : platformStation.name);
			platformObject.addProperty("passingTrack", platform.passingTrack);
			// Rails run through the middle of their end blocks
			final JsonArray line = new JsonArray();
			line.add(pos1.getX() + 0.5);
			line.add(pos1.getZ() + 0.5);
			line.add(pos2.getX() + 0.5);
			line.add(pos2.getZ() + 0.5);
			platformObject.add("line", line);
			platformsArray.add(platformObject);
		}
		layout.add("platforms", platformsArray);
		return layout;
	}

	private static List<BlockPos> ends(Platform platform) {
		return platform.getOrderedPositions(BlockPos.ZERO, false);
	}

	/**
	 * Rail ends to half a block, which is how close two ends of rails meeting at a node are.
	 */
	private static long key(float x, float z) {
		return (long) Math.round(x * 2) << 32 | Math.round(z * 2) & 0xFFFFFFFFL;
	}
}
