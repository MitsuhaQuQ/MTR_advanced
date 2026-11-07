package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.compat.Compat;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TrainServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import transitsim.sim.TrainService;

import java.util.*;

/**
 * The dispatcher's console (D6): the state of the trains on duties and of the signal blocks, which the web editor asks for
 * every few seconds while the console is open, and the dispatcher's commands to trains.
 */
public final class DispatchConsole implements IPacket {

	/**
	 * At most this many trains in one snapshot.
	 */
	public static final int MAX_TRAINS = 4000;
	public static final int MAX_BLOCKS = 20000;

	/**
	 * The last snapshot the client was sent, or null.
	 */
	public static volatile Snapshot clientSnapshot;
	private static long lastRequestMillis;

	private DispatchConsole() {
	}

	/**
	 * Asks the server for a snapshot, at most once a second.
	 */
	public static void sendRequestC2S() {
		final long millis = System.currentTimeMillis();
		if (millis - lastRequestMillis < 1000) {
			return;
		}
		lastRequestMillis = millis;
		RegistryClient.sendToServer(PACKET_CONSOLE, new FriendlyByteBuf(Unpooled.buffer()));
	}

	public static void receiveRequestC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData != null) {
				Registry.sendToPlayer(player, PACKET_CONSOLE, write(railwayData));
			}
		});
	}

	/**
	 * The trains on duties with where their heads are, and the signal blocks taken.
	 */
	static FriendlyByteBuf write(RailwayData railwayData) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(System.currentTimeMillis());
		final List<Long> occupied = new ArrayList<>();
		railwayData.getSignalBlocks().signalBlocks.forEach(signalBlock -> {
			if (signalBlock.isOccupied() && occupied.size() < MAX_BLOCKS) {
				occupied.add(signalBlock.id);
			}
		});
		packet.writeVarInt(occupied.size());
		occupied.forEach(packet::writeLong);
		final List<TrainServer> trains = new ArrayList<>();
		final Map<TrainServer, Siding> trainSidings = new HashMap<>();
		railwayData.sidings.stream().sorted(Comparator.comparingLong(siding -> siding.id)).forEach(siding -> siding.forEachTrain(train -> {
			if (train.getService().isOnDuty() && train.isOnRouteNow() && trains.size() < MAX_TRAINS) {
				trains.add(train);
				trainSidings.put(train, siding);
			}
		}));
		packet.writeVarInt(trains.size());
		trains.forEach(train -> {
			final TrainService<?> service = train.getService();
			final UUID head = train.getHeadRail();
			packet.writeLong(train.id);
			packet.writeLong(trainSidings.get(train).id);
			packet.writeLong(service.getTripRouteId());
			packet.writeVarInt(service.getDutySlot());
			packet.writeLong(head == null ? 0 : head.getMostSignificantBits());
			packet.writeLong(head == null ? 0 : head.getLeastSignificantBits());
			packet.writeLong(train.getNextStopId());
			packet.writeLong(service.getLateSinceMillis());
			packet.writeLong(train.getWaitingSinceMillis());
			packet.writeVarInt((service.getSkipPlatforms().isEmpty() ? 0 : 1) | (service.isOutOfService() ? 2 : 0));
			packet.writeVarInt(service.getTurnState());
			packet.writeVarInt(service.getTakeoverState());
			packet.writeVarInt(service.getDispatchState());
		});
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		clientSnapshot = read(packet);
	}

	static Snapshot read(FriendlyByteBuf packet) {
		final long serverMillis = packet.readLong();
		final Set<Long> occupied = new HashSet<>();
		final int blockCount = Math.min(packet.readVarInt(), MAX_BLOCKS);
		for (int i = 0; i < blockCount; i++) {
			occupied.add(packet.readLong());
		}
		final List<TrainState> trains = new ArrayList<>();
		final int trainCount = Math.min(packet.readVarInt(), MAX_TRAINS);
		for (int i = 0; i < trainCount; i++) {
			trains.add(new TrainState(packet.readLong(), packet.readLong(), packet.readLong(), packet.readVarInt(), new UUID(packet.readLong(), packet.readLong()), packet.readLong(), packet.readLong(), packet.readLong(), packet.readVarInt(), packet.readVarInt(), packet.readVarInt(), packet.readVarInt()));
		}
		return new Snapshot(serverMillis, System.currentTimeMillis() - serverMillis, occupied, trains);
	}

	/**
	 * Sends a dispatcher's command for a train (D6). See {@link TrainService#dispatch}.
	 */
	public static void sendCommandC2S(long trainId, int command, long value, Collection<Long> platformIds) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(trainId);
		packet.writeVarInt(command);
		packet.writeLong(value);
		packet.writeVarInt(platformIds.size());
		platformIds.forEach(packet::writeLong);
		RegistryClient.sendToServer(PACKET_DISPATCH, packet);
	}

	public static void receiveCommandC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long trainId = packet.readLong();
		final int command = packet.readVarInt();
		final long value = packet.readLong();
		final Set<Long> platformIds = new TreeSet<>();
		final int count = Math.min(packet.readVarInt(), 64);
		for (int i = 0; i < count; i++) {
			platformIds.add(packet.readLong());
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player) || command < TrainService.COMMAND_HOLD || command > TrainService.COMMAND_CANCEL) {
				return;
			}
			if (railwayData.dispatch(trainId, command, value, platformIds)) {
				System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " gave train " + trainId + " command " + command + (value != 0 ? " " + value : "") + (platformIds.isEmpty() ? "" : " " + platformIds));
			}
		});
	}

	/**
	 * What the client was last sent.
	 */
	public static final class Snapshot {

		public final long serverMillis;
		/**
		 * The client's clock less the server's, to work out how late from the moments sent.
		 */
		public final long clockOffset;
		public final Set<Long> occupiedBlocks;
		public final List<TrainState> trains;

		Snapshot(long serverMillis, long clockOffset, Set<Long> occupiedBlocks, List<TrainState> trains) {
			this.serverMillis = serverMillis;
			this.clockOffset = clockOffset;
			this.occupiedBlocks = occupiedBlocks;
			this.trains = trains;
		}
	}

	public static final class TrainState {

		public final long trainId;
		public final long sidingId;
		public final long routeId;
		public final int slot;
		public final UUID headRail;
		public final long nextStopId;
		public final long lateSince;
		public final long waitingSince;
		/**
		 * 1 passing stations, 2 out of service.
		 */
		public final int flags;
		public final int turnState;
		public final int takeoverState;
		public final int dispatchState;

		TrainState(long trainId, long sidingId, long routeId, int slot, UUID headRail, long nextStopId, long lateSince, long waitingSince, int flags, int turnState, int takeoverState, int dispatchState) {
			this.trainId = trainId;
			this.sidingId = sidingId;
			this.routeId = routeId;
			this.slot = slot;
			this.headRail = headRail;
			this.nextStopId = nextStopId;
			this.lateSince = lateSince;
			this.waitingSince = waitingSince;
			this.flags = flags;
			this.turnState = turnState;
			this.takeoverState = takeoverState;
			this.dispatchState = dispatchState;
		}
	}
}
