package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.ClientData;
import mtr.client.MapDataCache;
import mtr.client.MapIsolation;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.core.BlockPos;
import transitsim.network.RailKeys;

import java.util.*;

/**
 * The whole network's track layout for the advanced editor's "全网配线" view, from what the client already has for the
 * dashboard map: every rail end to end through the few points along its curve ({@code ClientData.MAP_RAIL_SHAPES}),
 * which platforms and sidings they are, their isolation ({@link MapIsolation}), the stations and the routes' paths.
 */
public final class EditorNetwork {

	public static final int KIND_PLAIN = 0;
	public static final int KIND_PLATFORM = 1;
	public static final int KIND_SIDING = 2;
	/**
	 * A rail in a stabling block (运用补完 step 4), a place for one train to wait near a terminus; marked in the game only.
	 */
	public static final int KIND_STABLING = 3;

	private EditorNetwork() {
	}

	/**
	 * @param modeName    the transport mode to show, or empty for the first one with rails
	 * @param fresh       true when the page has just opened the view, which asks the server for the map again
	 * @param seenVersion the version of the map the page has, which is answered with only the version if nothing changed
	 * @return {@code {version, mode, modes: [name], nodes: [[x, z, id]], rails: [[node, node, kind, isolation, x, z, ...]],
	 * boundaryNodes: [node], stations: [{name, x, z}], routes: [{id, color, lineKey, name, nodes: [node]}]}}
	 */
	public static JsonObject build(String modeName, boolean fresh, int seenVersion) {
		if (fresh) {
			PacketTrainDataGuiClient.requestMapRailsC2S();
		}
		final JsonObject network = new JsonObject();
		final Set<TransportMode> modes = new TreeSet<>();
		ClientData.MAP_RAILS.forEach(mapRail -> modes.add(mapRail.transportMode));
		TransportMode mode = null;
		for (final TransportMode testMode : modes) {
			if (mode == null || testMode.toString().equals(modeName)) {
				mode = testMode;
			}
		}
		// The stabling blocks are drawn too, so a change to the signal blocks or their colour is a new map
		final Map<UUID, Long> stablingRails = ClientData.SIGNAL_BLOCKS.getZones(ClientData.stablingColor);
		final int version = (MapDataCache.getVersion() * 31 + (mode == null ? 0 : mode.ordinal())) * 31 + ClientData.SIGNAL_BLOCKS.getVersion() * 17 + ClientData.stablingColor.ordinal();
		network.addProperty("version", version);
		if (version == seenVersion && !fresh) {
			network.addProperty("same", true);
			return network;
		}
		final JsonArray modesArray = new JsonArray();
		modes.forEach(testMode -> modesArray.add(testMode.toString()));
		network.add("modes", modesArray);
		network.addProperty("mode", mode == null ? "" : mode.toString());
		if (mode == null) {
			return network;
		}
		final TransportMode shownMode = mode;

		final Map<Long, Integer> nodeIndices = new HashMap<>();
		final JsonArray nodesArray = new JsonArray();
		final Set<UUID> platformRails = MapIsolation.getPlatformRails();
		final Set<UUID> sidingRails = new HashSet<>();
		ClientData.DATA_CACHE.sidingIdMap.values().forEach(siding -> {
			final List<BlockPos> positions = siding.getOrderedPositions(BlockPos.ZERO, false);
			sidingRails.add(RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong()));
		});
		final Map<UUID, Integer> statuses = MapIsolation.getStatuses(mode);

		final JsonArray railsArray = new JsonArray();
		ClientData.MAP_RAILS.forEach(mapRail -> {
			if (mapRail.transportMode != shownMode) {
				return;
			}
			final UUID key = RailKeys.of(mapRail.node1, mapRail.node2);
			final long lower = Math.min(mapRail.node1, mapRail.node2);
			final long upper = Math.max(mapRail.node1, mapRail.node2);
			final JsonArray railArray = new JsonArray();
			railArray.add(nodeIndex(lower, nodeIndices, nodesArray));
			railArray.add(nodeIndex(upper, nodeIndices, nodesArray));
			railArray.add(platformRails.contains(key) ? KIND_PLATFORM : sidingRails.contains(key) ? KIND_SIDING : stablingRails.containsKey(key) ? KIND_STABLING : KIND_PLAIN);
			railArray.add(statuses.getOrDefault(key, 0));
			// The points along a curve run from the lower node, as the server sends them
			final float[] shape = ClientData.MAP_RAIL_SHAPES.get(key);
			if (shape != null) {
				for (final float value : shape) {
					railArray.add(Math.round(value * 4) / 4.0);
				}
			}
			railsArray.add(railArray);
		});
		network.add("rails", railsArray);

		final JsonArray boundaryArray = new JsonArray();
		ClientData.PATH_REGIONS.boundaries.nodes.forEach(node -> {
			final Integer index = nodeIndices.get(node);
			if (index != null) {
				boundaryArray.add(index);
			}
		});
		network.add("boundaryNodes", boundaryArray);

		// The stations with a platform of this mode, named at the middle of their platforms
		final Map<Long, double[]> stationSums = new HashMap<>();
		ClientData.DATA_CACHE.platformIdMap.values().forEach(platform -> {
			final Station station = platform.transportMode == shownMode ? ClientData.DATA_CACHE.platformIdToStation.get(platform.id) : null;
			if (station != null) {
				final BlockPos middle = platform.getMidPos();
				final double[] sum = stationSums.computeIfAbsent(station.id, id -> new double[3]);
				sum[0] += middle.getX() + 0.5;
				sum[1] += middle.getZ() + 0.5;
				sum[2]++;
			}
		});
		final JsonArray stationsArray = new JsonArray();
		stationSums.forEach((stationId, sum) -> {
			final Station station = ClientData.DATA_CACHE.stationIdMap.get(stationId);
			final JsonObject stationObject = new JsonObject();
			stationObject.addProperty("id", String.valueOf(stationId));
			stationObject.addProperty("name", station == null ? "" : station.name);
			stationObject.addProperty("x", Math.round(sum[0] / sum[2] * 10) / 10.0);
			stationObject.addProperty("z", Math.round(sum[1] / sum[2] * 10) / 10.0);
			stationsArray.add(stationObject);
		});
		network.add("stations", stationsArray);

		final JsonArray routesArray = new JsonArray();
		ClientData.ROUTES.stream().filter(route -> route.transportMode == shownMode && !route.isHidden).sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			final long[] nodes = ClientData.MAP_ROUTES.get(route.id);
			if (nodes == null || nodes.length < 2) {
				return;
			}
			final JsonObject routeObject = new JsonObject();
			routeObject.addProperty("id", String.valueOf(route.id));
			routeObject.addProperty("name", route.name);
			routeObject.addProperty("color", route.color);
			routeObject.addProperty("lineKey", route.transportMode + "|" + route.getLineKey());
			final JsonArray routeNodes = new JsonArray();
			for (final long node : nodes) {
				routeNodes.add(nodeIndex(node, nodeIndices, nodesArray));
			}
			routeObject.add("nodes", routeNodes);
			routesArray.add(routeObject);
		});
		network.add("routes", routesArray);
		network.add("nodes", nodesArray);
		return network;
	}

	/**
	 * @return the node's place in the table of nodes, as {x, z, id} with the node drawn at the middle of its block
	 */
	private static int nodeIndex(long node, Map<Long, Integer> nodeIndices, JsonArray nodesArray) {
		return nodeIndices.computeIfAbsent(node, key -> {
			final BlockPos pos = BlockPos.of(node);
			final JsonArray nodeArray = new JsonArray();
			nodeArray.add(pos.getX() + 0.5);
			nodeArray.add(pos.getZ() + 0.5);
			nodeArray.add(String.valueOf(node));
			nodesArray.add(nodeArray);
			return nodesArray.size() - 1;
		});
	}
}
