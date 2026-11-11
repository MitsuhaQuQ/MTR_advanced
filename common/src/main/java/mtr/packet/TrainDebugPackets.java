package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.compat.Compat;
import mtr.data.RailwayData;
import mtr.data.TrainServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The debug display of a train a player holding the brush looks at (0.65.31): the client asks for one train about once a
 * second, the server answers with that train's lines ({@link TrainServer#getDebugLines}).
 */
public final class TrainDebugPackets implements IPacket {

	public static final int MAX_LINES = 32;
	private static final int MAX_TEXT = 256;
	private static long lastRequestMillis;
	private static long lastRequestedTrain;

	/**
	 * The last answer: the train and its lines, or none.
	 */
	public static volatile long clientTrainId;
	public static volatile List<String[]> clientLines = Collections.emptyList();
	public static volatile long clientReceivedMillis;

	private TrainDebugPackets() {
	}

	/**
	 * Asks for a train's lines, at once for another train and otherwise at most once a second.
	 */
	public static void sendRequestC2S(long trainId) {
		final long millis = System.currentTimeMillis();
		if (trainId == lastRequestedTrain && millis - lastRequestMillis < 1000) {
			return;
		}
		lastRequestMillis = millis;
		lastRequestedTrain = trainId;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(trainId);
		RegistryClient.sendToServer(PACKET_TRAIN_DEBUG, packet);
	}

	public static void receiveRequestC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final long trainId = packet.readLong();
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null) {
				return;
			}
			final TrainServer[] found = {null};
			railwayData.sidings.forEach(siding -> siding.forEachTrain(train -> {
				if (train.id == trainId) {
					found[0] = train;
				}
			}));
			Registry.sendToPlayer(player, PACKET_TRAIN_DEBUG, write(trainId, found[0] == null ? Collections.emptyList() : found[0].getDebugLines(railwayData.dataCache)));
		});
	}

	static FriendlyByteBuf write(long trainId, List<String[]> lines) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(trainId);
		final int count = Math.min(lines.size(), MAX_LINES);
		packet.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			packet.writeUtf(limit(lines.get(i)[0]));
			packet.writeUtf(limit(lines.get(i)[1]));
		}
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final long trainId = packet.readLong();
		final List<String[]> lines = read(packet);
		clientTrainId = trainId;
		clientLines = lines;
		clientReceivedMillis = System.currentTimeMillis();
	}

	static List<String[]> read(FriendlyByteBuf packet) {
		final int count = packet.readVarInt();
		if (count < 0 || count > MAX_LINES) {
			throw new IllegalArgumentException("Too many debug lines: " + count);
		}
		final List<String[]> lines = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			lines.add(new String[]{packet.readUtf(MAX_TEXT), packet.readUtf(MAX_TEXT)});
		}
		return lines;
	}

	private static String limit(String text) {
		return text == null ? "" : text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
	}
}
