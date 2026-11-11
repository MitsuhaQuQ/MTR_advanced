package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.packet.IPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.*;

/**
 * Keeps each player's copy of nearby moving objects (trains, lifts) up to date. Each tick, objects that come into
 * range are sent in full, those already in range send only what changed (the state packet, for trains), and those that
 * left range or were removed are deleted on the client.
 */
public class UpdateNearbyMovingObjects<T extends NameColorDataBase> implements IPacket {

	public final Map<Player, Set<T>> newDataSetInPlayerRange = new HashMap<>();
	public final Set<T> dataSetToSync = new HashSet<>();
	private final Map<Player, Set<T>> dataSetInPlayerRange = new HashMap<>();
	private final ResourceLocation deletePacketId;
	private final ResourceLocation updatePacketId;
	/**
	 * For trains, the packet with only what changes while they run (G2), or null.
	 */
	private final ResourceLocation statePacketId;
	/**
	 * For each player, the sync key of each train last sent in full.
	 */
	private final Map<Player, Map<Long, Integer>> sentKeys = new HashMap<>();
	private static final Set<Long> TOLD_TOO_BIG = Collections.synchronizedSet(new HashSet<>());
	private final Set<Player> requestedSnapshots = new HashSet<>();
	private final Map<Player, Long> snapshotTimes = new WeakHashMap<>();
	private final Sender sender;
	interface Sender { void send(ServerPlayer player, ResourceLocation id, FriendlyByteBuf packet); }

	public UpdateNearbyMovingObjects(ResourceLocation deletePacketId, ResourceLocation updatePacketId) {
		this(deletePacketId, updatePacketId, null);
	}

	public UpdateNearbyMovingObjects(ResourceLocation deletePacketId, ResourceLocation updatePacketId, ResourceLocation statePacketId) {
		this(deletePacketId, updatePacketId, statePacketId, Registry::sendToPlayer);
	}

	UpdateNearbyMovingObjects(ResourceLocation deletePacketId, ResourceLocation updatePacketId, ResourceLocation statePacketId, Sender sender) {
		this.deletePacketId = deletePacketId;
		this.updatePacketId = updatePacketId;
		this.statePacketId = statePacketId;
		this.sender = sender;
	}

	public void requestFullSnapshot(Player player) { requestFullSnapshot(player, System.nanoTime() / 1000000); }
	boolean requestFullSnapshot(Player player, long now) {
		Long previous = snapshotTimes.get(player);
		if (previous != null && now - previous < 2000) return false;
		snapshotTimes.put(player, now); requestedSnapshots.add(player); return true;
	}
	public void forget(Player player) { requestedSnapshots.remove(player); snapshotTimes.remove(player); dataSetInPlayerRange.remove(player); sentKeys.remove(player); }

	public void startTick() {
		newDataSetInPlayerRange.clear();
		dataSetToSync.clear();
	}

	public void tick() {
		// Use this tick's complete visibility set for both deletion and full data, even for parked trains.
		for (Player player : requestedSnapshots) {
			Set<T> visible = newDataSetInPlayerRange.getOrDefault(player, Collections.emptySet());
			FriendlyByteBuf keep = new FriendlyByteBuf(Unpooled.buffer());
			keep.writeInt(visible.size()); visible.forEach(data -> keep.writeLong(data.id));
			if (keep.readableBytes() <= MAX_PACKET_BYTES) sender.send((ServerPlayer)player, deletePacketId, keep);
			dataSetInPlayerRange.remove(player); sentKeys.remove(player);
		}
		requestedSnapshots.clear();
		dataSetInPlayerRange.forEach((player, dataSet) -> {
			for (final T data : dataSet) {
				if (!newDataSetInPlayerRange.containsKey(player) || !newDataSetInPlayerRange.get(player).contains(data)) {
					final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());

					if (newDataSetInPlayerRange.containsKey(player)) {
						packet.writeInt(newDataSetInPlayerRange.get(player).size());
						newDataSetInPlayerRange.get(player).forEach(dataToKeep -> packet.writeLong(dataToKeep.id));
					} else {
						packet.writeInt(0);
					}

					if (packet.readableBytes() <= MAX_PACKET_BYTES) {
						sender.send((ServerPlayer) player, deletePacketId, packet);
					}

					break;
				}
			}
		});

		sentKeys.keySet().retainAll(newDataSetInPlayerRange.keySet());
		newDataSetInPlayerRange.forEach((player, dataSet) -> {
			final List<FriendlyByteBuf> dataSetPacketsToUpdate = new ArrayList<>();
			final List<FriendlyByteBuf> statePackets = new ArrayList<>();
			final Map<Long, Integer> playerKeys = sentKeys.computeIfAbsent(player, key -> new HashMap<>());
			final boolean wasInRange = dataSetInPlayerRange.containsKey(player);
			final Set<Long> inRange = new HashSet<>();
			dataSet.forEach(data -> {
				inRange.add(data.id);
				final boolean newInRange = !wasInRange || !dataSetInPlayerRange.get(player).contains(data);
				if (dataSetToSync.contains(data) || newInRange) {
					final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
					// A train the player has with the same path only needs what changed while it runs
					if (statePacketId != null && data instanceof Train && !newInRange && Objects.equals(playerKeys.get(data.id), ((Train) data).getSyncKey())) {
						((Train) data).writeStatePacket(packet);
						statePackets.add(packet);
					} else {
						data.writePacket(packet);
						if (packet.readableBytes() < MAX_PACKET_BYTES) {
							dataSetPacketsToUpdate.add(packet);
							if (data instanceof Train) {
								playerKeys.put(data.id, ((Train) data).getSyncKey());
							}
						} else if (TOLD_TOO_BIG.add(data.id)) {
							// Said once, as the player won't see it at all
							System.out.println("Minecraft Transit Railway: " + data.getClass().getSimpleName() + " " + data.id + " is too big to send to players (" + packet.readableBytes() + " bytes), so they don't see it");
						}
					}
				}
			});
			playerKeys.keySet().retainAll(inRange);
			send((ServerPlayer) player, statePacketId, statePackets);

			while (!dataSetPacketsToUpdate.isEmpty()) {
				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());

				while (!dataSetPacketsToUpdate.isEmpty()) {
					final FriendlyByteBuf dataPacket = dataSetPacketsToUpdate.get(0);
					if (packet.readableBytes() + dataPacket.readableBytes() < MAX_PACKET_BYTES) {
						packet.writeBytes(dataPacket);
						dataSetPacketsToUpdate.remove(0);
					} else {
						break;
					}
				}

				sender.send((ServerPlayer) player, updatePacketId, packet);
			}
		});

		dataSetInPlayerRange.clear();
		dataSetInPlayerRange.putAll(newDataSetInPlayerRange);
	}

	/**
	 * Sends the packets together in as few as fit.
	 */
	private void send(ServerPlayer player, ResourceLocation packetId, List<FriendlyByteBuf> packets) {
		while (!packets.isEmpty()) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			while (!packets.isEmpty() && packet.readableBytes() + packets.get(0).readableBytes() < MAX_PACKET_BYTES) {
				packet.writeBytes(packets.remove(0));
			}
			sender.send(player, packetId, packet);
		}
	}
}
