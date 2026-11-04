package mtr.packet;

import mtr.Registry;
import mtr.mappings.NetworkUtilities;
import mtr.mappings.Text;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the packets players send to the server, turning away packets that are too large or come too often
 * before they are read.
 */
public final class PacketGuard {

	private static final int MAX_PACKET_BYTES = 1 << 20;
	private static final int MAX_PACKETS_PER_SECOND = 400;
	private static final long MILLIS_PER_SECOND = 1000;
	private static final Map<UUID, Map<ResourceLocation, long[]>> PACKET_COUNTS = new ConcurrentHashMap<>();

	private PacketGuard() {
	}

	public static void register(ResourceLocation id, NetworkUtilities.PacketCallback packetCallback) {
		Registry.registerNetworkReceiver(id, (minecraftServer, player, packet) -> {
			if (packet.readableBytes() > MAX_PACKET_BYTES) {
				reject(minecraftServer, player, id, "packet too large");
			} else if (withinRateLimit(player, id)) {
				try {
					packetCallback.packetCallback(minecraftServer, player, packet);
				} catch (Exception e) {
					e.printStackTrace();
					reject(minecraftServer, player, id, "malformed packet");
				}
			}
		});
	}

	public static void execute(MinecraftServer minecraftServer, Runnable runnable) {
		minecraftServer.execute(() -> {
			try {
				runnable.run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	public static int readCount(FriendlyByteBuf packet, int minimumElementBytes, int maximum) {
		final int count = packet.readInt();
		if (count < 0 || count > maximum || (long) count * minimumElementBytes > packet.readableBytes()) {
			throw new IllegalArgumentException("Invalid element count " + count);
		}
		return count;
	}

	public static void forget(ServerPlayer player) {
		PACKET_COUNTS.remove(player.getUUID());
	}

	private static boolean withinRateLimit(ServerPlayer player, ResourceLocation id) {
		final long[] counter = PACKET_COUNTS.computeIfAbsent(player.getUUID(), key -> new ConcurrentHashMap<>()).computeIfAbsent(id, key -> new long[2]);
		synchronized (counter) {
			final long millis = System.currentTimeMillis();
			if (millis - counter[0] >= MILLIS_PER_SECOND) {
				counter[0] = millis;
				counter[1] = 0;
			}
			counter[1]++;
			return counter[1] <= MAX_PACKETS_PER_SECOND;
		}
	}

	private static void reject(MinecraftServer minecraftServer, ServerPlayer player, ResourceLocation id, String reason) {
		System.out.printf("Rejected packet %s from %s: %s%n", id, player.getName().getString(), reason);
		minecraftServer.execute(() -> player.connection.disconnect(Text.literal("Invalid Minecraft Transit Railway packet")));
	}
}
