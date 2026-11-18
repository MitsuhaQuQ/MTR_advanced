package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.data.RailwayData;
import mtr.data.TrainUsage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dimension's train types by use, most used first ({@link TrainUsage}, 0.101.0): by the trains of each type in the
 * world now, then by the use over time. Sent to a player when they join, counted afresh then, and to everyone in the
 * dimension when the order changes. The client builds and keeps those models first.
 */
public final class TrainUsagePackets implements IPacket {

	private TrainUsagePackets() {
	}

	public static void sendAllS2C(Level world, RailwayData railwayData) {
		world.players().forEach(player -> sendS2C((ServerPlayer) player, railwayData));
	}

	public static void sendS2C(ServerPlayer player, RailwayData railwayData) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		final List<Map.Entry<String, Double>> ranking = railwayData.trainUsage.ranking();
		final int count = Math.min(TrainUsage.SENT_TYPES, ranking.size());
		packet.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			packet.writeUtf(ranking.get(i).getKey());
			packet.writeVarInt(railwayData.trainUsage.currentTrains(ranking.get(i).getKey()));
			packet.writeDouble(ranking.get(i).getValue());
		}
		Registry.sendToPlayer(player, PACKET_TRAIN_USAGE, packet);
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final int count = Math.max(0, Math.min(TrainUsage.SENT_TYPES, packet.readVarInt()));
		final List<String> trainIds = new ArrayList<>(count);
		final List<Integer> trainsNow = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			trainIds.add(packet.readUtf());
			trainsNow.add(packet.readVarInt());
			packet.readDouble();
		}
		net.minecraft.client.Minecraft.getInstance().execute(() -> mtr.client.TrainModelsOnDemand.setUsage(trainIds, trainsNow));
	}
}
