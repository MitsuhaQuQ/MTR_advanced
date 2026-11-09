package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.compat.Compat;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.RoutePaths;
import mtr.data.Siding;
import mtr.path.PathData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A way out of a siding to a platform, or back from one, as a depot's rule would have it (运用补完 step 2): by a station it
 * goes out or back by, or through the nodes of a named deadhead path; found by the server the way path generation finds
 * it, for the advanced editor to draw as it is chosen on the whole network map. The answer also gives the stations the
 * siding reaches the line at. Asks with {@link #request}; the server answers the player who asked.
 */
public final class DepotWayPreview implements IPacket {

	private static final int MAX_WAYPOINTS = transitsim.plan.DepotRules.MAX_WAYPOINTS;
	private static final int MAX_NODES = 8192;
	private static final int MAX_ENTRANCES = 64;

	private static final AtomicInteger NEXT_ID = new AtomicInteger();
	private static final Map<Integer, CompletableFuture<Answer>> PENDING = new HashMap<>();

	private DepotWayPreview() {
	}

	/**
	 * The stations the siding reaches the line at, the way's nodes (none if there is no way) and how long it takes.
	 */
	public static final class Answer {

		public final List<Long> entrances;
		public final List<Long> nodes;
		public final long millis;

		public Answer(List<Long> entrances, List<Long> nodes, long millis) {
			this.entrances = entrances;
			this.nodes = nodes;
			this.millis = millis;
		}
	}

	/**
	 * @param platformId the platform out to, or back from; 0 for the siding's stations alone
	 * @param entrance   the station gone out or back by, or 0
	 * @param waypoints  the nodes of a named path, or none
	 */
	public static CompletableFuture<Answer> request(long depotId, long sidingId, long platformId, boolean out, long entrance, List<Long> waypoints) {
		final int id = NEXT_ID.incrementAndGet();
		final CompletableFuture<Answer> future = new CompletableFuture<>();
		synchronized (PENDING) {
			PENDING.put(id, future);
		}
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(id);
		packet.writeLong(depotId);
		packet.writeLong(sidingId);
		packet.writeLong(platformId);
		packet.writeBoolean(out);
		packet.writeLong(entrance);
		final int count = Math.min(waypoints.size(), MAX_WAYPOINTS);
		packet.writeInt(count);
		for (int i = 0; i < count; i++) {
			packet.writeLong(waypoints.get(i));
		}
		RegistryClient.sendToServer(PACKET_DEPOT_WAY_PREVIEW, packet);
		return future;
	}

	public static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int id = packet.readInt();
		final long depotId = packet.readLong();
		final long sidingId = packet.readLong();
		final long platformId = packet.readLong();
		final boolean out = packet.readBoolean();
		final long entrance = packet.readLong();
		final int count = packet.readInt();
		if (count < 0 || count > MAX_WAYPOINTS) {
			return;
		}
		final long[] waypoints = new long[count];
		for (int i = 0; i < count; i++) {
			waypoints[i] = packet.readLong();
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				Registry.sendToPlayer(player, PACKET_DEPOT_WAY_PREVIEW, answer(id, find(railwayData, depotId, sidingId, platformId, out, entrance, waypoints)));
			}
		});
	}

	private static Answer find(RailwayData railwayData, long depotId, long sidingId, long platformId, boolean out, long entrance, long[] waypoints) {
		final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
		final Siding siding = railwayData.dataCache.sidingIdMap.get(sidingId);
		if (depot == null || siding == null) {
			return new Answer(Collections.emptyList(), Collections.emptyList(), -1);
		}
		final RoutePaths.DepotLine depotLine = new RoutePaths.DepotLine(depot.getRoutes(railwayData.dataCache), railwayData.dataCache, depot.rules).withWay(entrance, waypoints);
		final List<Long> entrances = new ArrayList<>(railwayData.routePaths.findEntrances(siding, depotLine));
		final Platform platform = railwayData.dataCache.platformIdMap.get(platformId);
		if (platform == null) {
			return new Answer(entrances, Collections.emptyList(), -1);
		}
		final List<PathData> way = railwayData.routePaths.previewDepotWay(out ? siding : platform, out ? platform : siding, railwayData.getRails(), out, depotLine);
		if (way == null || way.isEmpty()) {
			return new Answer(entrances, Collections.emptyList(), -1);
		}
		final List<Long> nodes = new ArrayList<>();
		nodes.add(way.get(0).startingPos.asLong());
		way.forEach(pathData -> {
			if (nodes.size() < MAX_NODES) {
				nodes.add(pathData.getLastPos().asLong());
			}
		});
		return new Answer(entrances, nodes, RoutePaths.timeDepotWay(way, siding.getTimingVehicle(), out));
	}

	private static FriendlyByteBuf answer(int id, Answer answer) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(id);
		packet.writeInt(answer.entrances.size());
		answer.entrances.forEach(packet::writeLong);
		packet.writeInt(answer.nodes.size());
		answer.nodes.forEach(packet::writeLong);
		packet.writeLong(answer.millis);
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final int id = packet.readInt();
		final int entranceCount = packet.readInt();
		if (entranceCount < 0 || entranceCount > MAX_ENTRANCES) {
			return;
		}
		final List<Long> entrances = new ArrayList<>();
		for (int i = 0; i < entranceCount; i++) {
			entrances.add(packet.readLong());
		}
		final int nodeCount = packet.readInt();
		if (nodeCount < 0 || nodeCount > MAX_NODES) {
			return;
		}
		final List<Long> nodes = new ArrayList<>();
		for (int i = 0; i < nodeCount; i++) {
			nodes.add(packet.readLong());
		}
		final long millis = packet.readLong();
		final CompletableFuture<Answer> future;
		synchronized (PENDING) {
			future = PENDING.remove(id);
		}
		if (future != null) {
			future.complete(new Answer(entrances, nodes, millis));
		}
	}
}
