package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.compat.Compat;
import mtr.data.FareData;
import mtr.data.NetworkSettings;
import mtr.data.RailwayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import transitsim.fare.FareNetwork;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fares by distance (design document 6.13, 0.68.0), set from the dashboard: the fare rules (0.73.0) with their fares and
 * reference lines, the rule of each line's routes and the rules of stations. Only players who may edit change them; the server keeps them in the
 * network's settings, which go to every player.
 */
public final class FarePackets implements IPacket {

	private static final int MAX_GROUP_LENGTH = 256;

	private FarePackets() {
	}

	/**
	 * Asks for every group with the longest distance between two of its stations, to make tiers up to.
	 */
	public static void requestGroupsC2S() {
		RegistryClient.sendToServer(PACKET_REQUEST_FARE_GROUPS, new FriendlyByteBuf(Unpooled.buffer()));
	}

	public static void receiveRequestGroupsC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
		PacketGuard.execute(server, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null) {
				return;
			}
			final FareNetwork network = railwayData.getFareNetwork();
			final Map<String, String> groups = FareData.getAllGroups(railwayData.dataCache);
			final FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
			response.writeVarInt(groups.size());
			groups.forEach((group, name) -> {
				response.writeUtf(group, MAX_GROUP_LENGTH);
				response.writeUtf(name);
				response.writeVarLong(network.getLongestBlocks(group));
			});
			Registry.sendToPlayer(player, PACKET_FARE_GROUPS, response);
		});
	}

	/**
	 * Sends the fares and the groups of routes and stations as edited, which replace the server's.
	 */
	public static void setC2S(NetworkSettings edited) {
		final NetworkSettings settings = new NetworkSettings();
		settings.fareRules.clear();
		settings.fareRules.putAll(edited.fareRules);
		settings.fareLevels.putAll(edited.fareLevels);
		settings.fareRouteGroups.putAll(edited.fareRouteGroups);
		settings.fareStationGroups.putAll(edited.fareStationGroups);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		RegistryClient.sendToServer(PACKET_SET_FARES, packet);
	}

	public static void receiveSetC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		PacketGuard.execute(server, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player)) {
				return;
			}
			final NetworkSettings settings = railwayData.dataCache.networkSettings;
			// Routes and stations that are gone are left out, and so are fares and assignments of rules there are none of
			read.fareRouteGroups.keySet().retainAll(railwayData.dataCache.routeIdMap.keySet());
			read.fareStationGroups.keySet().retainAll(railwayData.dataCache.stationIdMap.keySet());
			read.keepFareRulesKnown();
			if (new java.util.ArrayList<>(settings.fareRules.entrySet()).equals(new java.util.ArrayList<>(read.fareRules.entrySet())) && settings.fareLevels.equals(read.fareLevels) && settings.fareRouteGroups.equals(read.fareRouteGroups) && settings.fareStationGroups.equals(read.fareStationGroups)) {
				return;
			}
			settings.fareRules.clear();
			settings.fareRules.putAll(read.fareRules);
			settings.fareLevels.clear();
			settings.fareLevels.putAll(read.fareLevels);
			settings.fareRouteGroups.clear();
			settings.fareRouteGroups.putAll(read.fareRouteGroups);
			settings.fareStationGroups.clear();
			settings.fareStationGroups.putAll(read.fareStationGroups);
			System.out.println("Minecraft Transit Railway: " + player.getGameProfile().getName() + " set " + read.fareRules.size() + " fare rules, the fares of " + read.fareLevels.size() + ", the rules of " + read.fareRouteGroups.size() + " routes and the rules of " + read.fareStationGroups.size() + " stations");
			railwayData.networkSettingsChanged = true;
		});
	}

	/**
	 * @return group key to {name, longest distance in blocks}
	 */
	public static void receiveGroupsS2C(FriendlyByteBuf packet) {
		final Map<String, Object[]> groups = new LinkedHashMap<>();
		final int count = packet.readVarInt();
		for (int i = 0; i < count; i++) {
			final String group = packet.readUtf(MAX_GROUP_LENGTH);
			final Object[] value = {packet.readUtf(), packet.readVarLong()};
			if (i < NetworkSettings.MAX_FARE_GROUPS) {
				groups.put(group, value);
			}
		}
		final net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		client.execute(() -> {
			if (client.screen instanceof mtr.screen.FareScreen) {
				((mtr.screen.FareScreen) client.screen).setGroups(groups);
			}
		});
	}
}
