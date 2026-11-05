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
			for (int i = 1; i < nodes.length; i++) {
				final UUID key = RailKeys.of(nodes[i - 1], nodes[i]);
				if (!platformRails.contains(key)) {
					statuses.merge(key, released.contains(key) ? RoutePaths.RAIL_RELEASED : RoutePaths.RAIL_TAKEN, Math::min);
				}
			}
		});
		ClientData.PATH_REGIONS.boundaries.segments.forEach(segment -> statuses.put(segment, RoutePaths.RAIL_BOUNDARY));
		return statuses;
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
