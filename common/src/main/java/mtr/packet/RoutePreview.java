package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.data.SavedRailBase;
import mtr.compat.Compat;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import transitsim.network.RailGraph;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The way a route would run between its stops, found by the server the way path generation finds it, for the advanced
 * editor's new line on the whole network map: each leg's nodes, or why it can't be found. Asks with
 * {@link #request}; the server answers the player who asked.
 */
public final class RoutePreview implements IPacket {

	public static final int MAX_STOPS = 32;
	private static final int MAX_WAYPOINTS = 16;
	private static final int MAX_NODES = 8192;

	private static final AtomicInteger NEXT_ID = new AtomicInteger();
	private static final Map<Integer, CompletableFuture<List<Leg>>> PENDING = new HashMap<>();

	private RoutePreview() {
	}

	/**
	 * One leg between two stops: the nodes it runs through, or why it can't be found.
	 */
	public static final class Leg {

		public final List<Long> nodes;
		public final PathFailure failure;

		public Leg(List<Long> nodes, PathFailure failure) {
			this.nodes = nodes;
			this.failure = failure;
		}
	}

	/**
	 * Asks the server for the way through these platforms, each leg through its own waypoints.
	 */
	public static CompletableFuture<List<Leg>> request(List<Long> platformIds, List<List<Long>> waypoints) {
		final int id = NEXT_ID.incrementAndGet();
		final CompletableFuture<List<Leg>> future = new CompletableFuture<>();
		synchronized (PENDING) {
			PENDING.put(id, future);
		}
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(id);
		final int count = Math.min(platformIds.size(), MAX_STOPS);
		packet.writeInt(count);
		for (int i = 0; i < count; i++) {
			packet.writeLong(platformIds.get(i));
			final List<Long> legWaypoints = i < waypoints.size() ? waypoints.get(i) : Collections.emptyList();
			final int waypointCount = Math.min(legWaypoints.size(), MAX_WAYPOINTS);
			packet.writeInt(waypointCount);
			for (int j = 0; j < waypointCount; j++) {
				packet.writeLong(legWaypoints.get(j));
			}
		}
		RegistryClient.sendToServer(PACKET_ROUTE_PREVIEW, packet);
		return future;
	}

	public static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int id = packet.readInt();
		final int count = packet.readInt();
		if (count < 0 || count > MAX_STOPS) {
			return;
		}
		final List<Long> platformIds = new ArrayList<>();
		final List<List<BlockPos>> waypoints = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			platformIds.add(packet.readLong());
			final int waypointCount = packet.readInt();
			if (waypointCount < 0 || waypointCount > MAX_WAYPOINTS) {
				return;
			}
			final List<BlockPos> legWaypoints = new ArrayList<>();
			for (int j = 0; j < waypointCount; j++) {
				legWaypoints.add(BlockPos.of(packet.readLong()));
			}
			waypoints.add(legWaypoints);
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				Registry.sendToPlayer(player, PACKET_ROUTE_PREVIEW, answer(id, find(railwayData, platformIds, waypoints)));
			}
		});
	}

	/**
	 * Finds each leg on the server thread, in the rails and with the pathfinding boundaries as path generation has them.
	 */
	private static List<Leg> find(RailwayData railwayData, List<Long> platformIds, List<List<BlockPos>> waypoints) {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		final RailGraph<Rail> graph = railwayData.getPreviewGraph();
		final List<Leg> legs = new ArrayList<>();
		for (int i = 0; i + 1 < platformIds.size(); i++) {
			final SavedRailBase from = railwayData.dataCache.platformIdMap.get(platformIds.get(i));
			final SavedRailBase to = railwayData.dataCache.platformIdMap.get(platformIds.get(i + 1));
			if (from == null || to == null) {
				legs.add(new Leg(Collections.emptyList(), new PathFailure(PathFailure.Type.NO_ROUTE, 0)));
				continue;
			}
			final List<SavedRailBase> stops = Arrays.asList(from, to);
			final List<List<BlockPos>> sectionWaypoints = Collections.singletonList(waypoints.get(i));
			final List<PathData> path = new ArrayList<>();
			final int reached = PathFinder.findPath(path, rails, graph, stops, sectionWaypoints, 0, 256, false);
			if (path.isEmpty()) {
				legs.add(new Leg(Collections.emptyList(), graph == null ? new PathFailure(PathFailure.Type.NO_ROUTE, 0) : PathFailure.diagnose(graph, stops, sectionWaypoints, reached)));
			} else {
				final List<Long> nodes = new ArrayList<>();
				nodes.add(path.get(0).startingPos.asLong());
				path.forEach(pathData -> {
					if (nodes.size() < MAX_NODES) {
						nodes.add(pathData.getLastPos().asLong());
					}
				});
				legs.add(new Leg(nodes, PathFailure.NONE));
			}
		}
		return legs;
	}

	private static FriendlyByteBuf answer(int id, List<Leg> legs) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(id);
		packet.writeInt(legs.size());
		legs.forEach(leg -> {
			packet.writeInt(leg.nodes.size());
			leg.nodes.forEach(packet::writeLong);
			leg.failure.writePacket(packet);
		});
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final int id = packet.readInt();
		final int count = packet.readInt();
		final List<Leg> legs = new ArrayList<>();
		for (int i = 0; i < Math.min(count, MAX_STOPS); i++) {
			final int nodeCount = packet.readInt();
			if (nodeCount < 0 || nodeCount > MAX_NODES) {
				return;
			}
			final List<Long> nodes = new ArrayList<>();
			for (int j = 0; j < nodeCount; j++) {
				nodes.add(packet.readLong());
			}
			legs.add(new Leg(nodes, PathFailure.readPacket(packet)));
		}
		final CompletableFuture<List<Leg>> future;
		synchronized (PENDING) {
			future = PENDING.remove(id);
		}
		if (future != null) {
			future.complete(legs);
		}
	}
}
