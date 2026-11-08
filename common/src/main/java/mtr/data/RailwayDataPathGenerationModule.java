package mtr.data;

import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.DeterministicPathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import transitsim.network.RailGraph;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Starts the depots' path generations, each on a thread of its own, as many at once as the machine has cores to spare.
 * Depots started in the same tick share one copy of the rails and data and one network to search, made on the server
 * thread once for them all ({@link Snapshot}).
 */
public class RailwayDataPathGenerationModule extends RailwayDataModuleBase {

	private final Map<Long, Long> lastRequestMillis = new HashMap<>();

	private static final int MIN_REQUEST_INTERVAL_MILLIS = 1000;
	/**
	 * How many path generations run at once: as many as the threads shared by the whole server
	 * ({@link transitsim.sim.WorkPool}, the cores but one, at most 8, or {@code -Dmtr.workThreads}), which they run on.
	 */
	public static final int MAX_THREADS = transitsim.sim.WorkPool.THREADS;

	private final Map<Long, Future<?>> generatingPathThreads = new HashMap<>();
	/**
	 * Depots waiting for their paths to be found, with the tick they were queued in (optimisation round 5, step 3).
	 */
	private final Map<Long, Long> queued = new LinkedHashMap<>();
	/**
	 * While a queued depot is started, the tick it was queued in: a copy of the rails made since then serves it as well.
	 */
	private long startingQueuedSince = -1;
	private Snapshot snapshot;

	public RailwayDataPathGenerationModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		super(railwayData, world, rails);
	}

	/**
	 * @return whether a new path generation can start now, as only a few run at once
	 */
	public boolean canGenerate() {
		generatingPathThreads.keySet().removeIf(id -> generatingPathThreads.get(id).isDone());
		return generatingPathThreads.size() < MAX_THREADS;
	}

	/**
	 * Has the depot's paths found as soon as a thread is free ({@link #startQueued}); queued again, it keeps its place.
	 */
	public void queue(long depotId) {
		queued.putIfAbsent(depotId, world.getGameTime());
	}

	/**
	 * Starts the depots queued, as many as threads are free, each with a copy of the rails made no earlier than it was
	 * queued; called each tick.
	 */
	public void startQueued(MinecraftServer minecraftServer) {
		final Iterator<Map.Entry<Long, Long>> iterator = queued.entrySet().iterator();
		while (iterator.hasNext() && canGenerate()) {
			final Map.Entry<Long, Long> entry = iterator.next();
			iterator.remove();
			startingQueuedSince = entry.getValue();
			try {
				generatePath(minecraftServer, entry.getKey());
			} finally {
				startingQueuedSince = -1;
			}
		}
	}

	public void generatePath(MinecraftServer minecraftServer, long depotId) {
		generatingPathThreads.keySet().removeIf(id -> generatingPathThreads.get(id).isDone());
		final long millis = System.currentTimeMillis();
		if (millis - lastRequestMillis.getOrDefault(depotId, 0L) < MIN_REQUEST_INTERVAL_MILLIS || !generatingPathThreads.containsKey(depotId) && generatingPathThreads.size() >= MAX_THREADS) {
			PacketTrainDataGuiServer.generatePathS2C(world, depotId, 0);
			return;
		}
		lastRequestMillis.put(depotId, millis);

		final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
		if (depot != null) {
			if (generatingPathThreads.containsKey(depotId)) {
				// Interrupted, as the thread was before: the search stops with a CancellationException and says so
				generatingPathThreads.get(depotId).cancel(true);
				System.out.println("Restarting path generation" + (depot.name.isEmpty() ? "" : " for " + depot.name));
			} else {
				System.out.println("Starting path generation" + (depot.name.isEmpty() ? "" : " for " + depot.name));
			}
			depot.generateMainRoute(minecraftServer, world, railwayData.dataCache, getSnapshot(), railwayData.sidings, task -> generatingPathThreads.put(depotId, task));
			railwayData.resetTrainDelays(depot);
		} else {
			PacketTrainDataGuiServer.generatePathS2C(world, depotId, 0);
			System.out.println("Failed to generate path, depot is null");
		}
	}

	/**
	 * @return the copy of the rails and data the path generations started in this tick work on
	 */
	private Snapshot getSnapshot() {
		final long tick = world.getGameTime();
		// Rails or data changed within the tick, as a player's edit applied before a request, make a new one
		final int version = railwayData.dataCache.getVersion();
		// A depot queued in an earlier tick takes a copy made since, as it would have had starting then
		final boolean recentEnough = snapshot != null && (snapshot.tick == tick || startingQueuedSince >= 0 && snapshot.tick >= startingQueuedSince);
		if (!recentEnough || snapshot.version != version || snapshot.liveRailCount != rails.size()) {
			snapshot = new Snapshot(tick, version, rails.size(), railwayData.dataCache.copyIdMaps(), DataCache.copyRails(rails), RoutePaths.getVehicles(railwayData), railwayData.pathBoundaries);
		}
		return snapshot;
	}

	/**
	 * The rails and data as they were when path generations were started, which threads read and nothing writes. The
	 * network to search is made the first time a depot needs it, on the server thread, as the pathfinding boundaries it
	 * keeps searches to are the live ones.
	 */
	public static final class Snapshot {

		public final DataCache dataCache;
		public final Map<BlockPos, Map<BlockPos, Rail>> rails;
		public final Map<Long, Set<PathTiming.Vehicle>> vehicles;
		private final long tick;
		private final int version;
		private final int liveRailCount;
		private final mtr.advanced.PathBoundaries boundaries;
		private RailGraph<Rail> graph;

		private Snapshot(long tick, int version, int liveRailCount, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Map<Long, Set<PathTiming.Vehicle>> vehicles, mtr.advanced.PathBoundaries boundaries) {
			this.tick = tick;
			this.version = version;
			this.liveRailCount = liveRailCount;
			this.dataCache = dataCache;
			this.rails = rails;
			this.vehicles = vehicles;
			this.boundaries = boundaries;
		}

		/**
		 * Called on the server thread only.
		 */
		public RailGraph<Rail> getGraph() {
			if (graph == null) {
				graph = DeterministicPathFinder.compile(rails, boundaries);
			}
			return graph;
		}
	}
}
