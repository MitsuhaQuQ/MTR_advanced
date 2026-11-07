package mtr.client;

import mtr.data.Route;
import mtr.data.RoutePaths;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import transitsim.network.RailKeys;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The isolation of rails as the client knows it, for the dashboard map and the editor's whole network view: pathfinding
 * boundaries ({@code ClientData.PATH_REGIONS}) and the rails connecting lines take from other routes or have released,
 * along their paths on the map ({@code ClientData.MAP_ROUTES}) other than platform rails. The same statuses as the
 * track diagrams ({@code RoutePaths.RAIL_*}).
 */
public final class MapIsolation {

	private MapIsolation() {
	}

	/**
	 * @return each isolated rail's status by rail key; a boundary wins over a connecting line's
	 */
	public static Map<UUID, Integer> getStatuses(TransportMode transportMode) {
		final Map<UUID, Integer> statuses = new HashMap<>();
		final Set<UUID> platformRails = getPlatformRails();
		ClientData.ROUTES.forEach(route -> {
			final long[] nodes = route.connectingLine && route.transportMode == transportMode ? ClientData.MAP_ROUTES.get(route.id) : null;
			if (nodes == null) {
				return;
			}
			final Set<UUID> released = getReleased(route);
			final int[] range = getOwnRange(route, nodes);
			for (int i = range[0] + 1; i <= range[1]; i++) {
				final UUID key = RailKeys.of(nodes[i - 1], nodes[i]);
				if (!platformRails.contains(key)) {
					statuses.merge(key, released.contains(key) ? RoutePaths.RAIL_RELEASED : RoutePaths.RAIL_TAKEN, Math::min);
				}
			}
		});
		ClientData.PATH_REGIONS.boundaries.segments.forEach(segment -> statuses.put(segment, RoutePaths.RAIL_BOUNDARY));
		return statuses;
	}

	/**
	 * A connecting line's own part as {@link RoutePaths} takes it: from its first waypoint, or else the first fork after its
	 * first stop, to its last waypoint after that, or else the last fork before its last stop.
	 *
	 * @return the first and last index of its nodes
	 */
	private static int[] getOwnRange(Route route, long[] nodes) {
		final List<Long> keyNodes = new java.util.ArrayList<>();
		route.platformIds.forEach(routePlatform -> routePlatform.waypoints.forEach(waypoint -> keyNodes.add(waypoint.asLong())));
		if (!keyNodes.isEmpty()) {
			int from = -1;
			int to = -1;
			for (int i = 0; i < nodes.length; i++) {
				if (from < 0 && nodes[i] == keyNodes.get(0)) {
					from = i;
				}
				if (from >= 0 && i > from && nodes[i] == keyNodes.get(keyNodes.size() - 1)) {
					to = i;
				}
			}
			if (from >= 0) {
				return new int[]{from, to > from ? to : lastFork(nodes, from)};
			}
		}
		int from = 0;
		for (int i = 1; i < nodes.length - 1; i++) {
			if (isFork(nodes[i])) {
				from = i;
				break;
			}
		}
		return new int[]{from, lastFork(nodes, from)};
	}

	private static int lastFork(long[] nodes, int from) {
		for (int i = nodes.length - 2; i > from; i--) {
			if (isFork(nodes[i])) {
				return i;
			}
		}
		return nodes.length - 1;
	}

	private static boolean isFork(long node) {
		final Map<BlockPos, ?> connections = ClientData.RAILS.get(BlockPos.of(node));
		return connections != null && connections.size() > 2;
	}

	public static Set<UUID> getPlatformRails() {
		final Set<UUID> platformRails = new HashSet<>();
		ClientData.DATA_CACHE.platformIdMap.values().forEach(platform -> {
			final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
			platformRails.add(RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong()));
		});
		return platformRails;
	}

	private static Set<UUID> getReleased(Route route) {
		final Set<UUID> released = new HashSet<>();
		route.connectingReleased.forEach(rail -> released.add(RailKeys.of(rail[0], rail[1])));
		return released;
	}
}
