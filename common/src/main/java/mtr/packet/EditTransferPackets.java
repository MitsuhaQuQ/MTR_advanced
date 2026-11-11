package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.data.EditTransferAssembler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicLong;

/** Transport for EDIT / EDIT_BATCH only; reuses their permission, revision and atomic group validation. */
public final class EditTransferPackets implements IPacket {
	private static final AtomicLong NEXT = new AtomicLong();
	private static final Map<UUID, EditTransferAssembler> RECEIVING = new HashMap<>();
	private static final Map<UUID, ResourceLocation> DIMENSIONS = new HashMap<>();
	private EditTransferPackets() { }
	public static void send(ResourceLocation target, FriendlyByteBuf packet, BiConsumer<ResourceLocation, FriendlyByteBuf> sender) {
		if (!target.equals(PACKET_EDIT) && !target.equals(PACKET_EDIT_BATCH)) throw new IllegalArgumentException("Unsupported edit packet");
		int size = packet.readableBytes();
		if (size > EditTransferAssembler.MAX_BYTES) throw new IllegalArgumentException("Edit exceeds 1 MiB; apply fewer routes at once");
		if (size <= EditTransferAssembler.CHUNK_BYTES) { sender.accept(target, packet); return; }
		long transfer = NEXT.incrementAndGet();
		for (int offset = 0; offset < size; offset += EditTransferAssembler.CHUNK_BYTES) {
			int length = Math.min(EditTransferAssembler.CHUNK_BYTES, size - offset);
			FriendlyByteBuf part = new FriendlyByteBuf(Unpooled.buffer());
			part.writeLong(transfer); part.writeBoolean(target.equals(PACKET_EDIT_BATCH));
			part.writeVarInt(size); part.writeVarInt(offset);
			byte[] bytes = new byte[length]; packet.getBytes(packet.readerIndex() + offset, bytes); part.writeByteArray(bytes);
			sender.accept(PACKET_EDIT_FRAGMENT, part);
		}
	}
	public static void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet, BiConsumer<Boolean, FriendlyByteBuf> complete) {
		long id = packet.readLong();
		boolean batch = packet.readBoolean();
		int total = packet.readVarInt(), offset = packet.readVarInt();
		byte[] bytes = packet.readByteArray(EditTransferAssembler.CHUNK_BYTES);
		if (packet.isReadable() || total <= 0 || total > EditTransferAssembler.MAX_BYTES) throw new IllegalArgumentException("Invalid edit transfer");
		PacketGuard.execute(server, () -> {
			long now = System.currentTimeMillis();
			RECEIVING.entrySet().removeIf(e -> e.getValue().expired(now));
			DIMENSIONS.keySet().retainAll(RECEIVING.keySet());
			if (server.getPlayerList().getPlayer(player.getUUID()) == player && !mtr.data.RailwayData.hasNoPermission(player)) {
				UUID key = player.getUUID();
				if (!RECEIVING.containsKey(key) && RECEIVING.size() >= 64) return;
				ResourceLocation dimension = player.serverLevel().dimension().location();
				if (offset == 0) DIMENSIONS.put(key, dimension);
				if (!dimension.equals(DIMENSIONS.get(key))) { forget(player); return; }
				EditTransferAssembler assembler = RECEIVING.computeIfAbsent(key, k -> new EditTransferAssembler());
				byte[] joined = assembler.accept(id, batch ? 1 : 0, total, offset, bytes, now);
				if (joined != null) {
					RECEIVING.remove(key);
					DIMENSIONS.remove(key);
					FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(joined));
					try { complete.accept(batch, data); } finally { data.release(); }
				}
			}
		});
	}
	public static void forget(ServerPlayer player) { RECEIVING.remove(player.getUUID()); DIMENSIONS.remove(player.getUUID()); }
}
