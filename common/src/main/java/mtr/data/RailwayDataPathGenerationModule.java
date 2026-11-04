package mtr.data;

import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

public class RailwayDataPathGenerationModule extends RailwayDataModuleBase {

	private final Map<Long, Long> lastRequestMillis = new HashMap<>();

	private static final int MIN_REQUEST_INTERVAL_MILLIS = 1000;
	private static final int MAX_THREADS = 4;

	private final Map<Long, Thread> generatingPathThreads = new HashMap<>();

	public RailwayDataPathGenerationModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		super(railwayData, world, rails);
	}

	/**
	 * @return whether a new path generation can start now, as only a few run at once
	 */
	public boolean canGenerate() {
		generatingPathThreads.keySet().removeIf(id -> !generatingPathThreads.get(id).isAlive());
		return generatingPathThreads.size() < MAX_THREADS;
	}

	public void generatePath(MinecraftServer minecraftServer, long depotId) {
		generatingPathThreads.keySet().removeIf(id -> !generatingPathThreads.get(id).isAlive());
		final long millis = System.currentTimeMillis();
		if (millis - lastRequestMillis.getOrDefault(depotId, 0L) < MIN_REQUEST_INTERVAL_MILLIS || !generatingPathThreads.containsKey(depotId) && generatingPathThreads.size() >= MAX_THREADS) {
			PacketTrainDataGuiServer.generatePathS2C(world, depotId, 0);
			return;
		}
		lastRequestMillis.put(depotId, millis);

		final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
		if (depot != null) {
			if (generatingPathThreads.containsKey(depotId)) {
				generatingPathThreads.get(depotId).interrupt();
				System.out.println("Restarting path generation" + (depot.name.isEmpty() ? "" : " for " + depot.name));
			} else {
				System.out.println("Starting path generation" + (depot.name.isEmpty() ? "" : " for " + depot.name));
			}
			depot.generateMainRoute(minecraftServer, world, railwayData.dataCache, rails, railwayData.sidings, thread -> generatingPathThreads.put(depotId, thread));
			railwayData.resetTrainDelays(depot);
		} else {
			PacketTrainDataGuiServer.generatePathS2C(world, depotId, 0);
			System.out.println("Failed to generate path, depot is null");
		}
	}
}
