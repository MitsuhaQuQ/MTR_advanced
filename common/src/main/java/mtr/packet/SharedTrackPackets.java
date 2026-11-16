package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.advanced.SharedTrack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The track run on by depots planned apart, as the server worked it out ({@link SharedTrack}, 2026-11-16), for the web
 * editor's check list: the client has its sidings' own rails only, not the paths of their services, so it can't work it
 * out itself. Sent to every player when it changes, and to a player asking for the map.
 */
public final class SharedTrackPackets implements IPacket {

	/**
	 * The areas as the client has them, replaced whole. Read on the editor's threads.
	 */
	public static volatile List<SharedTrack.Area> areas = Collections.emptyList();
	/**
	 * Goes up with each set of areas received.
	 */
	public static volatile int version;

	private static final int MAX_AREAS = 4096;
	private static final int MAX_ITEMS = 4096;
	private static final int MAX_LINE_KEY_LENGTH = 256;

	private SharedTrackPackets() {
	}

	public static void sendAll(Level world, List<SharedTrack.Area> areas) {
		world.players().forEach(player -> send((ServerPlayer) player, areas));
	}

	public static void send(ServerPlayer player, List<SharedTrack.Area> areas) {
		Registry.sendToPlayer(player, PACKET_SHARED_TRACK, write(areas));
	}

	static FriendlyByteBuf write(List<SharedTrack.Area> areas) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		final int count = Math.min(areas.size(), MAX_AREAS);
		packet.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			final SharedTrack.Area area = areas.get(i);
			packet.writeUtf(area.key);
			packet.writeVarInt(area.units.size());
			area.units.forEach(unit -> {
				packet.writeVarInt(unit.depotIds.size());
				unit.depotIds.forEach(packet::writeLong);
				packet.writeVarInt(unit.lines.size());
				unit.lines.forEach(line -> packet.writeUtf(line, MAX_LINE_KEY_LENGTH));
			});
			packet.writeVarInt(area.stationIds.size());
			area.stationIds.forEach(packet::writeLong);
			packet.writeDouble(area.length);
			packet.writeLong(area.start.asLong());
		}
		return packet;
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final List<SharedTrack.Area> received = read(packet);
		if (received != null) {
			areas = Collections.unmodifiableList(received);
			version++;
		}
	}

	/**
	 * @return the areas, or null if the packet is malformed
	 */
	static List<SharedTrack.Area> read(FriendlyByteBuf packet) {
		try {
			final int count = packet.readVarInt();
			if (count < 0 || count > MAX_AREAS) {
				return null;
			}
			final List<SharedTrack.Area> received = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				final SharedTrack.Area area = new SharedTrack.Area();
				area.key = packet.readUtf();
				final int unitCount = checked(packet.readVarInt());
				for (int j = 0; j < unitCount; j++) {
					final SharedTrack.Unit unit = new SharedTrack.Unit();
					final int depotCount = checked(packet.readVarInt());
					for (int k = 0; k < depotCount; k++) {
						unit.depotIds.add(packet.readLong());
					}
					final int lineCount = checked(packet.readVarInt());
					for (int k = 0; k < lineCount; k++) {
						unit.lines.add(packet.readUtf(MAX_LINE_KEY_LENGTH));
					}
					area.units.add(unit);
				}
				final int stationCount = checked(packet.readVarInt());
				for (int j = 0; j < stationCount; j++) {
					area.stationIds.add(packet.readLong());
				}
				area.length = packet.readDouble();
				area.start = BlockPos.of(packet.readLong());
				received.add(area);
			}
			return received;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static int checked(int count) {
		if (count < 0 || count > MAX_ITEMS) {
			throw new IllegalArgumentException("count " + count);
		}
		return count;
	}
}
