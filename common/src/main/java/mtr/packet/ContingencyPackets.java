package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.advanced.PacketBinary;
import mtr.client.ClientData;
import mtr.compat.Compat;
import mtr.data.RailwayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import transitsim.plan.Contingencies;

/**
 * The contingency plans of the rail systems (D2): all of them sent to the players when they join and whenever they
 * change, and a player's change of a system's plans, or of which are on and what is set for single trains, sent to the
 * server, which checks the player may edit.
 */
public final class ContingencyPackets implements IPacket {

	private static final int KIND_PLANS = 0;
	private static final int KIND_STATE = 1;

	private ContingencyPackets() {
	}

	public static void sendAllS2C(Level world, RailwayData railwayData) {
		world.players().forEach(player -> sendS2C((ServerPlayer) player, railwayData));
	}

	public static void sendS2C(ServerPlayer player, RailwayData railwayData) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		railwayData.contingencySystems.write(packet);
		Registry.sendToPlayer(player, PACKET_CONTINGENCIES, packet);
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final mtr.data.ContingencySystems read = new mtr.data.ContingencySystems();
		read.read(packet);
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			ClientData.CONTINGENCY_SYSTEMS.systems.clear();
			ClientData.CONTINGENCY_SYSTEMS.systems.putAll(read.systems);
		});
	}

	/**
	 * Sends a system's plans, in place of the ones it has.
	 */
	public static void sendPlansC2S(long anchor, Contingencies contingencies) {
		send(anchor, KIND_PLANS, contingencies);
	}

	/**
	 * Sends which of a system's plans are on and what is set for single trains.
	 */
	public static void sendStateC2S(long anchor, Contingencies contingencies) {
		send(anchor, KIND_STATE, contingencies);
	}

	private static void send(long anchor, int kind, Contingencies contingencies) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(anchor);
		packet.writeVarInt(kind);
		if (kind == KIND_PLANS) {
			contingencies.writePlans(PacketBinary.of(packet));
		} else {
			contingencies.writeState(PacketBinary.of(packet));
		}
		RegistryClient.sendToServer(PACKET_CONTINGENCIES, packet);
	}

	public static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long anchor = packet.readLong();
		final int kind = packet.readVarInt();
		final Contingencies read = new Contingencies();
		if (kind == KIND_PLANS) {
			read.readPlans(PacketBinary.of(packet));
		} else {
			read.readState(PacketBinary.of(packet));
		}
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player) || railwayData.dataCache.platformIdMap.get(anchor) == null) {
				return;
			}
			if (!railwayData.contingencySystems.systems.containsKey(anchor) && railwayData.contingencySystems.systems.size() >= mtr.data.ContingencySystems.MAX_SYSTEMS) {
				return;
			}
			final Contingencies system = railwayData.contingencySystems.getOrCreate(anchor);
			if (kind == KIND_PLANS) {
				system.plans.clear();
				system.plans.putAll(read.plans);
				system.active.retainAll(system.plans.keySet());
				System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " set the contingency plans of the rail system of platform " + anchor + ": " + system.plans.size());
			} else {
				system.active.clear();
				read.active.stream().filter(system.plans::containsKey).forEach(system.active::add);
				system.adhoc.clear();
				system.adhoc.putAll(read.adhoc);
				System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " set the plans on of the rail system of platform " + anchor + " " + system.active + " and for trains " + system.adhoc.keySet());
			}
			if (system.isEmpty()) {
				railwayData.contingencySystems.systems.remove(anchor);
			}
			railwayData.contingenciesChanged = true;
		});
	}
}
