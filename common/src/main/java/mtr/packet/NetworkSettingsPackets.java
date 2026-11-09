package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.client.ClientData;
import mtr.compat.Compat;
import mtr.data.NetworkSettings;
import mtr.data.RailwayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The network's settings (optimisation round 4): sent to the players when they join and whenever they change, and a
 * player's change sent to the server, which checks the player may edit.
 */
public final class NetworkSettingsPackets implements IPacket {

	private NetworkSettingsPackets() {
	}

	public static void sendAllS2C(Level world, RailwayData railwayData) {
		world.players().forEach(player -> sendS2C((ServerPlayer) player, railwayData));
	}

	public static void sendS2C(ServerPlayer player, RailwayData railwayData) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		railwayData.dataCache.networkSettings.writePacket(packet);
		Registry.sendToPlayer(player, PACKET_NETWORK_SETTINGS, packet);
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		net.minecraft.client.Minecraft.getInstance().execute(() -> ClientData.DATA_CACHE.networkSettings.copyFrom(read));
	}

	public static void sendC2S(NetworkSettings settings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		RegistryClient.sendToServer(PACKET_NETWORK_SETTINGS, packet);
	}

	public static void receiveC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player) || railwayData.dataCache.networkSettings.equals(read)) {
				return;
			}
			railwayData.dataCache.networkSettings.copyFrom(read);
			System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " set the network's least time to change ends to " + read.minReverseMillis + " ms, the early arrival threshold to " + read.earlyThresholdMillis + " ms and the owners of " + read.ownerOverrides.size() + " track sections by hand");
			railwayData.networkSettingsChanged = true;
		});
	}
}
