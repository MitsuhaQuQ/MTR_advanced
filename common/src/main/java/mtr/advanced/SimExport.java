package mtr.advanced;

import mtr.data.DataCache;
import mtr.data.PathTiming;
import mtr.data.Depot;
import mtr.data.LineDuties;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.RoutePaths;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import transitsim.sim.RunRecord;
import transitsim.sim.SimDataset;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the component's simulator needs of the world, written into the database when the world saves (S4e), so the day
 * tool can run it without the game. With it go the trains as they are (G0), to simulate on from them.
 */
public final class SimExport {

	private SimExport() {
	}

	/**
	 * @return the world's routes, depots and sidings in the component's format
	 */
	public static byte[] build(RailwayData railwayData) {
		return toDataset(railwayData).toBytes();
	}

	/**
	 * @return the world's routes, depots, sidings, signal blocks and trains, read on the server thread
	 */
	public static SimDataset toDataset(RailwayData railwayData) {
		final SimDataset dataset = new SimDataset();
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		final Map<Long, Set<PathTiming.Vehicle>> vehicles = RoutePaths.getVehicles(railwayData);
		railwayData.routes.stream().sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			final SimDataset.RouteData routeData = new SimDataset.RouteData(route);
			routeData.lineKey = LineDuties.getLineKey(route);
			// The route's own fastest times, so the component plans with the same times as the game (L1)
			final RoutePaths.RoutePath routePath = RoutePaths.timesByPath(route) ? railwayData.routePaths.getFound(route, railwayData.dataCache, rails) : null;
			if (routePath != null) {
				routeData.fastestTimes = routePath.getFastestTimes(route, RoutePaths.getVehicles(vehicles, route));
			}
			dataset.routes.add(routeData);
		});
		railwayData.depots.stream().sorted(Comparator.comparingLong(depot -> depot.id)).forEach(depot -> {
			final SimDataset.DepotData depotData = new SimDataset.DepotData(depot.id, toArray(depot.routeIds), depot.cycleRoutes, depot.getRunDuties() != null ? depot.getRunDuties().toBytes() : new byte[0]);
			// Sending trains out by frequency, the depot takes its sidings in turn in their order by name, as the game's does
			depotData.departures = depot.getDeployTimes(railwayData.getWorld());
			depotData.sidingOrder = railwayData.sidings.stream().filter(siding -> railwayData.dataCache.sidingIdToDepot.get(siding.id) == depot).sorted().mapToLong(siding -> siding.id).toArray();
			depotData.releaseInterval = depot.releaseInterval;
			dataset.depots.add(depotData);
		});
		final RunRecord trains = new RunRecord(System.currentTimeMillis());
		final Set<Long> recordedDepots = new HashSet<>();
		railwayData.sidings.stream().sorted(Comparator.comparingLong(siding -> siding.id)).forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null) {
				final SimDataset.SidingData sidingData = siding.toSimulation(depot.id, toArray(depot.getRouteIds(siding.getTrainId(), railwayData.dataCache)), path -> toTracks(path, rails));
				if (sidingData != null) {
					dataset.sidings.add(sidingData);
					if (recordedDepots.add(depot.id)) {
						depot.recordDispatcher(trains, siding.id);
					}
					siding.recordTrains(trains);
				}
			}
		});
		dataset.trains = trains;
		dataset.signalBlocks.addAll(railwayData.getSignalBlocks().getBlockRails());
		return dataset;
	}

	public static List<SimDataset.DataTrack> toTracks(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		return path.stream().map(pathData -> {
			final RailType railType = pathData.rail.railType;
			final Rail backwards = DataCache.tryGet(rails, pathData.getLastPos(), pathData.startingPos);
			return new SimDataset.DataTrack(
					pathData.startingPos.asLong(), pathData.getLastPos().asLong(), pathData.rail.getLength(),
					railType == RailType.PLATFORM, railType == RailType.SIDING, pathData.savedRailBaseId, pathData.dwellTime, pathData.stopIndex,
					railType.maxBlocksPerTick, railType.canAccelerate, railType.hasSignal, backwards != null && backwards.railType != RailType.NONE
			);
		}).collect(Collectors.toList());
	}

	private static long[] toArray(List<Long> values) {
		return values.stream().mapToLong(Long::longValue).toArray();
	}
}
