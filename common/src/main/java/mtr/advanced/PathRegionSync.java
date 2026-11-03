package mtr.advanced;

import io.netty.buffer.Unpooled;
import mtr.MTR;
import mtr.Registry;
import mtr.data.*;
import mtr.packet.IPacket;
import mtr.path.DeterministicPathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import transitsim.network.Network;
import transitsim.network.PathRegions;
import transitsim.network.RailGraph;
import transitsim.network.RegionComputer;
import transitsim.network.RegionData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the players' {@link RegionData} up to date. A cheap fingerprint of the rails, boundaries, platforms and routes
 * is checked every few seconds, and only a change starts the work, which runs away from the server thread.
 */
public final class PathRegionSync {

	private final PathBoundaries boundaries;
	private long fingerprint;
	private boolean computed;
	private boolean computing;
	private RegionData latest = RegionData.EMPTY;
	/**
	 * The order networks are published in (contracts document 5.1).
	 */
	private long networkVersion;

	private static final int CHECK_INTERVAL_TICKS = 40;

	public PathRegionSync(PathBoundaries boundaries) {
		this.boundaries = boundaries;
	}

	public void tick(Level world, RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		if (computing || !MTR.isGameTickInterval(CHECK_INTERVAL_TICKS)) {
			return;
		}
		final long newFingerprint = getFingerprint(railwayData, rails);
		if (computed && newFingerprint == fingerprint) {
			return;
		}
		final MinecraftServer minecraftServer = world.getServer();
		if (minecraftServer == null) {
			return;
		}

		fingerprint = newFingerprint;
		computing = true;
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		final long version = ++networkVersion;
		final PathBoundaries boundariesCopy = boundaries.copy();
		final List<RegionComputer.SavedRail> platforms = new ArrayList<>();
		railwayData.platforms.forEach(platform -> {
			final Station station = railwayData.dataCache.platformIdToStation.get(platform.id);
			platforms.add(toSavedRail(platform, station == null ? 0 : station.id));
		});
		final List<RegionComputer.SavedRail> sidings = new ArrayList<>();
		railwayData.sidings.forEach(siding -> sidings.add(toSavedRail(siding, 0)));

		final Thread thread = new Thread(() -> {
			RegionData data = null;
			Network network = null;
			try {
				data = RegionComputer.compute(graph, boundariesCopy, platforms, sidings);
				final Map<UUID, Long> savedRails = new HashMap<>();
				platforms.forEach(platform -> savedRails.put(PathBoundaries.segmentKey(platform.pos1, platform.pos2), platform.id));
				sidings.forEach(siding -> savedRails.put(PathBoundaries.segmentKey(siding.pos1, siding.pos2), siding.id));
				network = RailNetworks.compress(graph, PathRegions.compute(graph, boundariesCopy), boundariesCopy, savedRails, version);
			} catch (Exception e) {
				e.printStackTrace();
			}
			final RegionData finalData = data;
			final Network finalNetwork = network;
			minecraftServer.execute(() -> {
				computing = false;
				if (finalNetwork != null) {
					railwayData.graphRecords.setGraph(finalNetwork);
				}
				if (finalData != null) {
					latest = finalData;
					computed = true;
					world.players().forEach(player -> send((ServerPlayer) player));
				}
			});
		});
		thread.setDaemon(true);
		thread.start();
	}

	public void send(ServerPlayer player) {
		if (computed) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			RegionDataPackets.write(latest, packet);
			Registry.sendToPlayer(player, IPacket.PACKET_PATH_REGIONS, packet);
		}
	}

	private static RegionComputer.SavedRail toSavedRail(SavedRailBase savedRail, long stationId) {
		final List<BlockPos> positions = savedRail.getOrderedPositions(BlockPos.ZERO, false);
		return new RegionComputer.SavedRail(savedRail.id, positions.get(0).asLong(), positions.get(1).asLong(), stationId, savedRail.transportMode.ordinal());
	}

	/**
	 * Adds up a hash of each item, so the order the sets are kept in doesn't matter.
	 */
	private long getFingerprint(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final long[] sum = {0};
		rails.forEach((startPos, connections) -> connections.forEach((endPos, rail) -> sum[0] += mix(startPos.asLong() * 31 + endPos.asLong(), rail.railType.ordinal(), Float.floatToIntBits(rail.facingStart.angleDegrees) * 31L + Float.floatToIntBits(rail.facingEnd.angleDegrees))));
		boundaries.nodes.forEach(pos -> sum[0] += mix(pos, 1, 0));
		boundaries.segments.forEach(segment -> sum[0] += mix(segment.getMostSignificantBits(), 2, segment.getLeastSignificantBits()));
		railwayData.platforms.forEach(platform -> {
			final Station station = railwayData.dataCache.platformIdToStation.get(platform.id);
			sum[0] += mix(platform.id, platform.getMidPos().asLong(), station == null ? 0 : station.id);
		});
		railwayData.sidings.forEach(siding -> sum[0] += mix(siding.id, siding.getMidPos().asLong(), 3));
		railwayData.routes.forEach(route -> sum[0] += mix(route.id, route.platformIds.stream().mapToLong(routePlatform -> routePlatform.platformId).reduce(17, (hash, id) -> hash * 31 + id), 4));
		return sum[0];
	}

	private static long mix(long value1, long value2, long value3) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L + value3;
		return hash ^ hash >>> 29;
	}
}
