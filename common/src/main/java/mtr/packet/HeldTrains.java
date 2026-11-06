package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.client.ClientData;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.compat.Compat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Trains a depot's duties left waiting on the line (P1-C) and services whose order to go back to a depot couldn't be kept
 * (P1-B): sent to the clients for the advanced editor's sync panel, where a waiting train can be taken back to its siding
 * at once, by the player's own command.
 */
public final class HeldTrains implements IPacket {

	private static final int MAX_ENTRIES = 64;
	private static final int MAX_LENGTH = 256;

	private HeldTrains() {
	}

	/**
	 * @return the depot's trains staying on the line with no duty to go on with, as {@code <siding> <slot> <platform> <reason>}
	 */
	public static List<String> collect(Depot depot, Collection<Siding> sidings, DataCache dataCache) {
		final List<String> held = new ArrayList<>();
		sidings.forEach(siding -> {
			if (dataCache.sidingIdToDepot.get(siding.id) == depot) {
				siding.forEachHeldTrain((slot, stopId, reason) -> held.add(siding.id + " " + slot + " " + stopId + " " + reason));
			}
		});
		held.sort(String::compareTo);
		return held;
	}

	public static void sendS2C(Level world, long depotId, List<String> held, List<String> returnMisses) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		writeList(packet, held);
		writeList(packet, returnMisses);
		world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_HELD_TRAINS, packet));
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		final List<String> held = readList(packet);
		final List<String> returnMisses = readList(packet);
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(depotId);
			if (depot != null) {
				depot.clientHeldTrains = held;
				synchronized (depot.returnOrderMisses) {
					depot.returnOrderMisses.clear();
					depot.returnOrderMisses.addAll(returnMisses);
				}
			}
		});
	}

	/**
	 * Asks the server to take a waiting train straight back to its siding.
	 */
	public static void sendRecallC2S(long depotId, long sidingId, int slot) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		packet.writeLong(sidingId);
		packet.writeInt(slot);
		RegistryClient.sendToServer(PACKET_RECALL_TRAIN, packet);
	}

	public static void receiveRecallC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		final long sidingId = packet.readLong();
		final int slot = packet.readInt();
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player)) {
				return;
			}
			final Siding siding = railwayData.dataCache.sidingIdMap.get(sidingId);
			final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
			if (siding != null && depot != null && railwayData.dataCache.sidingIdToDepot.get(sidingId) == depot && siding.recallHeldTrain(slot)) {
				System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " took train " + (slot + 1) + " of siding " + sidingId + " straight back from the line");
				depot.runProblemsChanged = true;
			}
		});
	}

	private static void writeList(FriendlyByteBuf packet, List<String> list) {
		final int count = Math.min(list.size(), MAX_ENTRIES);
		packet.writeInt(count);
		list.stream().limit(count).forEach(entry -> packet.writeUtf(entry.length() > MAX_LENGTH ? entry.substring(0, MAX_LENGTH) : entry));
	}

	private static List<String> readList(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		if (count < 0 || count > MAX_ENTRIES) {
			throw new IllegalArgumentException("Too many entries: " + count);
		}
		final List<String> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(packet.readUtf(MAX_LENGTH));
		}
		return list;
	}
}
