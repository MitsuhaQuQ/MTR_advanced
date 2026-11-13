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
		// The way each double track runs by its line's running side, for the paths below (user, 2026-11-12)
		RunningSides.get(railwayData);
		final Map<Long, Set<PathTiming.Vehicle>> vehicles = RoutePaths.getVehicles(railwayData);
		railwayData.routes.stream().sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			final SimDataset.RouteData routeData = new SimDataset.RouteData(route);
			routeData.lineKey = LineDuties.getLineKey(route);
			// The route's own fastest times, so the component plans with the same times as the game (L1)
			final RoutePaths.RoutePath routePath = RoutePaths.timesByPath(route) ? railwayData.routePaths.getFound(route, railwayData.dataCache, rails) : null;
			if (routePath != null) {
				routeData.fastestTimes = routePath.getTimetableTimes(route, RoutePaths.getVehicles(vehicles, route));
			}
			dataset.routes.add(routeData);
		});
		railwayData.depots.stream().sorted(Comparator.comparingLong(depot -> depot.id)).forEach(depot -> {
			final SimDataset.DepotData depotData = new SimDataset.DepotData(depot.id, toArray(depot.routeIds), depot.cycleRoutes, depot.getRunDuties() != null ? depot.getRunDuties().toBytes() : new byte[0]);
			// Sending trains out by frequency, the depot takes its sidings in turn in their order by name, as the game's does
			depotData.departures = Depot.supportsTimetable(depot.transportMode) ? new int[0] : depot.getDeployTimes(railwayData.getWorld());
			// A depot of trains sends them out by duties only (R2), as the game's does
			depotData.dutiesOnly = Depot.supportsTimetable(depot.transportMode);
			depotData.sidingOrder = railwayData.sidings.stream().filter(siding -> railwayData.dataCache.sidingIdToDepot.get(siding.id) == depot).sorted().mapToLong(siding -> siding.id).toArray();
			depotData.releaseInterval = depot.releaseInterval;
			depotData.addedBuffers = depot.getAddedBuffers();
			dataset.depots.add(depotData);
		});
		final RunRecord trains = new RunRecord(System.currentTimeMillis());
		final Set<Long> recordedDepots = new HashSet<>();
		railwayData.sidings.stream().sorted(Comparator.comparingLong(siding -> siding.id)).forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null) {
				final SimDataset.SidingData sidingData = siding.toSimulation(depot.id, toArray(depot.getSidingRouteIds(siding, railwayData.dataCache)), path -> toTracks(path, rails), depot.getRunDuties() != null);
				if (sidingData != null) {
					dataset.sidings.add(sidingData);
					if (recordedDepots.add(depot.id)) {
						depot.recordDispatcher(trains, siding.id);
					}
					siding.recordTrains(trains);
				}
			}
		});
		// Where trains stop on each platform set to stop them other than at the exit end (round 4)
		railwayData.platforms.forEach(platform -> {
			if (platform.stopPosition != transitsim.plan.StopPositions.EXIT_END) {
				dataset.stopPositions.put(platform.id, platform.stopPosition);
			}
		});
		dataset.applyStopPositions();
		// How early trains may run at full speed before cruising slower (round 4, step 5)
		dataset.earlyThresholdMillis = railwayData.dataCache.networkSettings.earlyThresholdMillis;
		dataset.trains = trains;
		dataset.signalBlocks.addAll(railwayData.getSignalBlocks().getBlockRails());
		dataset.interlockBlocks.addAll(railwayData.getSignalBlocks().getInterlockFlags(mtr.SecurityConfig.interlockColor));
		return dataset;
	}

	public static List<SimDataset.DataTrack> toTracks(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final Map<java.util.UUID, Integer> directions = RunningSides.get(rails);
		final java.util.function.Function<PathData, SimDataset.DataTrack> convert = pathData -> {
			final RailType railType = pathData.rail.railType;
			final Rail backwards = DataCache.tryGet(rails, pathData.getLastPos(), pathData.startingPos);
			return new SimDataset.DataTrack(
					pathData.startingPos.asLong(), pathData.getLastPos().asLong(), pathData.rail.getLength(),
					railType == RailType.PLATFORM, railType == RailType.SIDING, pathData.savedRailBaseId, pathData.dwellTime, pathData.stopIndex,
					pathData.getMaxSpeed(), railType.canAccelerate, railType.hasSignal, backwards != null && backwards.railType != RailType.NONE,
					RunningSides.getConvention(directions, pathData.startingPos, pathData.getLastPos())
			);
		};
		if (path instanceof transitsim.plan.IndexedPath) return ((transitsim.plan.IndexedPath<PathData>) path).map(convert, SimDataset.DataTrack::withStopIndexOffset, SimDataset::trackSignature);
		return path.stream().map(convert).collect(Collectors.toList());
	}

	private static long[] toArray(List<Long> values) {
		return values.stream().mapToLong(Long::longValue).toArray();
	}
}
