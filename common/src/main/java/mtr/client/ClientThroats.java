package mtr.client;

import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.Station;
import net.minecraft.core.BlockPos;
import transitsim.network.ThroatRoutes;

import java.util.*;

/**
 * The turnouts of the stations' throats each route passes ({@link ThroatRoutes}, L2), from what the client has for the
 * dashboard map: each route's path on the map, and the rails meeting at each node. Used by the advanced editor to show a
 * stop's throat and the routes it crosses there; the timetable check works them out on the server from the routes' own
 * paths.
 */
public final class ClientThroats {

	private ClientThroats() {
	}

	/**
	 * @return each route's stops' throats, by route ID, for routes with a path on the map
	 */
	public static Map<Long, List<ThroatRoutes.StopThroat>> compute() {
		final Map<Long, Set<Long>> neighbours = new HashMap<>();
		ClientData.MAP_RAILS.forEach(mapRail -> {
			neighbours.computeIfAbsent(mapRail.node1, key -> new HashSet<>()).add(mapRail.node2);
			neighbours.computeIfAbsent(mapRail.node2, key -> new HashSet<>()).add(mapRail.node1);
		});
		final Map<Long, List<ThroatRoutes.StopThroat>> throats = new HashMap<>();
		final Map<java.util.UUID, Long> zones = ClientData.SIGNAL_BLOCKS.getZones(ClientData.interlockColor);
		ClientData.ROUTES.forEach(route -> {
			final long[] nodes = ClientData.MAP_ROUTES.get(route.id);
			if (route.isHidden || nodes == null || nodes.length < 2) {
				return;
			}
			final int[] stopRails = new int[route.platformIds.size()];
			int searchFrom = 0;
			for (int i = 0; i < stopRails.length; i++) {
				stopRails[i] = -1;
				final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(route.platformIds.get(i).platformId);
				if (platform == null) {
					continue;
				}
				final List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
				final long first = ends.get(0).asLong();
				final long second = ends.get(1).asLong();
				for (int k = searchFrom; k + 1 < nodes.length; k++) {
					if (nodes[k] == first && nodes[k + 1] == second || nodes[k] == second && nodes[k + 1] == first) {
						stopRails[i] = k;
						searchFrom = k + 1;
						break;
					}
				}
			}
			throats.put(route.id, ThroatRoutes.of(nodes, node -> {
				final BlockPos pos = BlockPos.of(node);
				return new double[]{pos.getX(), pos.getZ()};
			}, stopRails, node -> neighbours.getOrDefault(node, Collections.emptySet()).size(), rail -> zones.getOrDefault(rail, 0L)));
		});
		return throats;
	}

	/**
	 * A route of another stop at the same station crossing this stop's entry or exit at a turnout.
	 */
	public static final class Crossing {

		public final long otherRouteId;
		public final int otherStop;
		public final boolean entry;
		public final boolean otherEntry;
		public final long node;
		/**
		 * The two use the same interlocking zone, entered at {@link #node}, rather than a turnout.
		 */
		public final boolean zone;

		private Crossing(long otherRouteId, int otherStop, boolean entry, boolean otherEntry, long node, boolean zone) {
			this.zone = zone;
			this.otherRouteId = otherRouteId;
			this.otherStop = otherStop;
			this.entry = entry;
			this.otherEntry = otherEntry;
			this.node = node;
		}
	}

	/**
	 * @return the routes crossing a stop's throat at its station, at most {@code limit}
	 */
	public static List<Crossing> crossings(Map<Long, List<ThroatRoutes.StopThroat>> throats, Route route, int stop, int limit) {
		final List<Crossing> crossings = new ArrayList<>();
		final List<ThroatRoutes.StopThroat> own = throats.get(route.id);
		if (own == null || stop >= own.size()) {
			return crossings;
		}
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(stop).platformId);
		if (station == null) {
			return crossings;
		}
		for (final Route other : ClientData.ROUTES) {
			final List<ThroatRoutes.StopThroat> otherThroats = throats.get(other.id);
			if (other.id == route.id || otherThroats == null) {
				continue;
			}
			for (int j = 0; j < other.platformIds.size() && j < otherThroats.size() && crossings.size() < limit; j++) {
				if (ClientData.DATA_CACHE.platformIdToStation.get(other.platformIds.get(j).platformId) != station) {
					continue;
				}
				for (final boolean entry : new boolean[]{true, false}) {
					for (final boolean otherEntry : new boolean[]{true, false}) {
						final ThroatRoutes.Item item = ThroatRoutes.findConflict(entry ? own.get(stop).entry : own.get(stop).exit, otherEntry ? otherThroats.get(j).entry : otherThroats.get(j).exit);
						if (item != null && crossings.size() < limit) {
							crossings.add(new Crossing(other.id, j, entry, otherEntry, item.getPlace(), item.isZone()));
						}
					}
				}
			}
		}
		return crossings;
	}
}
