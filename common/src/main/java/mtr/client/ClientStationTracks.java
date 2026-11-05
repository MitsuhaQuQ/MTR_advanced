package mtr.client;

import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.Station;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import transitsim.network.PlatformGraph;
import transitsim.network.StationTracks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tracks of the stations ({@link StationTracks}, L2) from what the client knows: the platforms, the routes' stops and
 * the way each route runs along each platform on its map path. Used by the advanced editor and the platform screen, so
 * both say the same.
 */
public final class ClientStationTracks {

	private ClientStationTracks() {
	}

	public static Map<Long, StationTracks.Track> compute() {
		final PlatformGraph platformGraph = ClientData.PATH_REGIONS.platformGraph;
		final List<StationTracks.PlatformInput> platforms = new ArrayList<>();
		ClientData.DATA_CACHE.platformIdMap.values().forEach(platform -> {
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
			final List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
			final int index = platformGraph.indexOf(platform.id);
			platforms.add(new StationTracks.PlatformInput(platform.id, station == null ? 0 : station.id, index < 0 ? 0 : platformGraph.getNetwork(index), platform.name, platform.passingTrack, ends.get(0).getX() + 0.5, ends.get(0).getZ() + 0.5, ends.get(1).getX() + 0.5, ends.get(1).getZ() + 0.5));
		});
		final List<StationTracks.RouteInput> routes = new ArrayList<>();
		ClientData.ROUTES.forEach(route -> {
			if (route.isHidden || route.platformIds.size() < 2) {
				return;
			}
			final long[] nodes = ClientData.MAP_ROUTES.get(route.id);
			final StationTracks.RouteInput routeInput = new StationTracks.RouteInput();
			int searchFrom = 0;
			for (int i = 0; i < route.platformIds.size(); i++) {
				final Route.RoutePlatform routePlatform = route.platformIds.get(i);
				final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
				int way = -1;
				if (platform != null && nodes != null) {
					// The way along the platform is the order its ends come in on the route's path
					final List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
					final long first = ends.get(0).asLong();
					final long second = ends.get(1).asLong();
					for (int k = searchFrom; k + 1 < nodes.length; k++) {
						if (nodes[k] == first && nodes[k + 1] == second || nodes[k] == second && nodes[k + 1] == first) {
							way = nodes[k] == first ? 0 : 1;
							searchFrom = k + 1;
							break;
						}
					}
				}
				final boolean passes = routePlatform.pass && i > 0 && i < route.platformIds.size() - 1;
				routeInput.stops.add(new StationTracks.StopInput(routePlatform.platformId, way, passes));
			}
			routes.add(routeInput);
		});
		return StationTracks.compute(platforms, routes);
	}

	/**
	 * @return the track in words for the game's screens, as "上行 正线", or empty if nothing is known
	 */
	public static String describe(StationTracks.Track track) {
		if (track == null) {
			return "";
		}
		final List<String> words = new ArrayList<>();
		if (!track.direction.isEmpty()) {
			words.add(Text.translatable("gui.mtr.track_" + track.direction).getString());
		}
		if (!track.kind.isEmpty()) {
			words.add(Text.translatable("gui.mtr.track_" + track.kind).getString());
		}
		return String.join(" ", words);
	}
}
