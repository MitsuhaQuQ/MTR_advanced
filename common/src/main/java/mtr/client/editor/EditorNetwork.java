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
	 * boundaryNodes: [node], stations: [{name, x, z}], routes: [{id, color, lineKey, name, nodes: [node], stopNodes: [index in nodes of each stop, or -1]}],
	 * owners: {ready, names: [owner], rails: [[rail, manual, section, logical line or "", owner, ...]]}}}: the owners of the rails whose section has any,
	 * as the server worked them out (web layers step 2), each an index in {@code names}; manual 1 where set by hand
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
		final int version = (((MapDataCache.getVersion() * 31 + (mode == null ? 0 : mode.ordinal())) * 31 + ClientData.SIGNAL_BLOCKS.getVersion() * 17 + ClientData.stablingColor.ordinal()) * 31 + mtr.packet.TrackOwnersPackets.version) * 31 + mtr.data.Depot.clientTrackUsesVersion;
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
		final Map<UUID, mtr.packet.TrackOwnersPackets.Owners> railOwners = mtr.packet.TrackOwnersPackets.railOwners;
		final JsonArray ownedArray = new JsonArray();
		// Each section a number, so the page can show a change to its owners on all its rails
		final Map<mtr.packet.TrackOwnersPackets.Owners, Integer> sectionNumbers = new IdentityHashMap<>();
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
			final mtr.packet.TrackOwnersPackets.Owners owners = railOwners.get(key);
			if (owners != null) {
				final JsonArray ownedRail = new JsonArray();
				ownedRail.add(railsArray.size());
				ownedRail.add(owners.manual ? 1 : 0);
				ownedRail.add(sectionNumbers.computeIfAbsent(owners, entry -> sectionNumbers.size()));
				// The section's logical line (2026-11-09), or empty
				ownedRail.add(owners.logicalLine == 0 ? "" : String.valueOf(owners.logicalLine));
				for (final int owner : owners.owners) {
					ownedRail.add(owner);
				}
				ownedArray.add(ownedRail);
			}
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
		final JsonObject ownersObject = new JsonObject();
		ownersObject.addProperty("ready", mtr.packet.TrackOwnersPackets.version > 0);
		final JsonArray namesArray = new JsonArray();
		mtr.packet.TrackOwnersPackets.ownerNames.forEach(namesArray::add);
		ownersObject.add("names", namesArray);
		ownersObject.add("rails", ownedArray);
		network.add("owners", ownersObject);

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
			// Where each stop is along the path: the index of the node ending its platform's rail, or -1 (web layers step 3,
			// so the page knows which rails a route runs between two stops)
			final JsonArray stopNodes = new JsonArray();
			int from = 1;
			for (final mtr.data.Route.RoutePlatform routePlatform : route.platformIds) {
				final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
				int found = -1;
				if (platform != null) {
					final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
					final UUID platformKey = RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong());
					for (int i = from; i < nodes.length; i++) {
						if (RailKeys.of(nodes[i - 1], nodes[i]).equals(platformKey)) {
							found = i;
							break;
						}
					}
				}
				stopNodes.add(found);
				if (found >= 0) {
					from = found + 1;
				}
			}
			routeObject.add("stopNodes", stopNodes);
			routesArray.add(routeObject);
		});
		network.add("routes", routesArray);
		// The rails the depots' duty paths run, each way, with a route (0.80.0): for the station track use, so the moves
		// between services (turning back, changing platforms, out and back) show beside the routes' own paths
		final JsonArray dutyUses = new JsonArray();
		for (final mtr.data.Depot depot : ClientData.DEPOTS) {
			if (depot.transportMode != shownMode) {
				continue;
			}
			final long[] uses = depot.clientTrackUses;
			for (int i = 0; i + 2 < uses.length; i += 3) {
				final JsonArray use = new JsonArray();
				use.add(nodeIndex(uses[i], nodeIndices, nodesArray));
				use.add(nodeIndex(uses[i + 1], nodeIndices, nodesArray));
				use.add(String.valueOf(uses[i + 2]));
				use.add(String.valueOf(depot.id));
				dutyUses.add(use);
			}
		}
		network.add("dutyUses", dutyUses);
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
