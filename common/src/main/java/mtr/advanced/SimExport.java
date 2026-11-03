package mtr.advanced;

import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
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
		final SimDataset dataset = new SimDataset();
		railwayData.routes.stream().sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> dataset.routes.add(new SimDataset.RouteData(route)));
		railwayData.depots.stream().sorted(Comparator.comparingLong(depot -> depot.id)).forEach(depot -> dataset.depots.add(new SimDataset.DepotData(depot.id, toArray(depot.routeIds), depot.cycleRoutes, depot.duties.diagramMode ? depot.duties.toBytes() : new byte[0])));
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
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
		return dataset.toBytes();
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
