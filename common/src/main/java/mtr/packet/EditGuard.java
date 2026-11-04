package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.compat.Compat;
import mtr.data.*;
import mtr.mappings.NetworkUtilities;
import mtr.mappings.Text;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Keeps players editing the same railway data at once from writing over each other (multiplayer edit conflicts). A
 * player's change comes with the revision of the part it changes that the player's copy was at when they started
 * editing; the server makes it only if the part hasn't changed since, and otherwise tells the player who changed it and
 * sends them the data as it is now. Changes from the web editor come as one batch, made all or not at all. The server
 * also tells everyone who is editing what.
 */
public final class EditGuard implements IPacket {

	/**
	 * The change packets of each kind of data, by packet, with whether they delete.
	 */
	private static final Map<ResourceLocation, String> KINDS = new HashMap<>();
	private static final Set<ResourceLocation> DELETES = new HashSet<>();
	private static final Map<ResourceLocation, NetworkUtilities.PacketCallback> HANDLERS = new HashMap<>();
	/**
	 * What each player is editing, as {@code kind:id}.
	 */
	private static final Map<UUID, String> EDITING = new HashMap<>();
	private static final Map<UUID, String> EDITING_NAMES = new HashMap<>();

	public static final String STATION = "station";
	public static final String PLATFORM = "platform";
	public static final String SIDING = "siding";
	public static final String ROUTE = "route";
	public static final String DEPOT = "depot";
	private static final int MAX_BATCH = 4096;

	static {
		addKind(PACKET_UPDATE_STATION, STATION, false);
		addKind(PACKET_DELETE_STATION, STATION, true);
		addKind(PACKET_UPDATE_PLATFORM, PLATFORM, false);
		addKind(PACKET_DELETE_PLATFORM, PLATFORM, true);
		addKind(PACKET_UPDATE_SIDING, SIDING, false);
		addKind(PACKET_DELETE_SIDING, SIDING, true);
		addKind(PACKET_UPDATE_ROUTE, ROUTE, false);
		addKind(PACKET_DELETE_ROUTE, ROUTE, true);
		addKind(PACKET_UPDATE_DEPOT, DEPOT, false);
		addKind(PACKET_DELETE_DEPOT, DEPOT, true);
	}

	private EditGuard() {
	}

	private static void addKind(ResourceLocation packetId, String kind, boolean isDelete) {
		KINDS.put(packetId, kind);
		if (isDelete) {
			DELETES.add(packetId);
		}
	}

	/**
	 * @return the kind of data this packet changes, or null if it isn't one whose edits are checked
	 */
	public static String getKind(ResourceLocation packetId) {
		return KINDS.get(packetId);
	}

	public static boolean isDelete(ResourceLocation packetId) {
		return DELETES.contains(packetId);
	}

	/**
	 * @return the kind of a piece of data a screen edits, or null
	 */
	public static String getKind(NameColorDataBase data) {
		if (data instanceof Station) {
			return STATION;
		} else if (data instanceof Platform) {
			return PLATFORM;
		} else if (data instanceof Siding) {
			return SIDING;
		} else if (data instanceof Route) {
			return ROUTE;
		} else if (data instanceof Depot) {
			return DEPOT;
		} else {
			return null;
		}
	}

	/**
	 * The part of an object a change packet changes, as the key its update starts with, read without moving the packet on.
	 */
	public static String getPart(ResourceLocation packetId, FriendlyByteBuf packet) {
		if (isDelete(packetId)) {
			return EditRevisions.WHOLE;
		}
		final FriendlyByteBuf copy = new FriendlyByteBuf(packet.copy());
		copy.readLong();
		copy.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		return copy.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
	}

	/**
	 * Registers the server's handling of a change packet, which then also comes checked through {@link #PACKET_EDIT}. A
	 * change sent on its own, unchecked, still can't make again an object deleted while the server runs.
	 */
	public static void register(ResourceLocation packetId, NetworkUtilities.PacketCallback callback) {
		HANDLERS.put(packetId, callback);
		PacketGuard.register(packetId, (minecraftServer, player, packet) -> handle(minecraftServer, player, packetId, EditRevisions.UNCHECKED, packet));
	}

	public static void registerPackets() {
		PacketGuard.register(PACKET_EDIT, EditGuard::receiveEditC2S);
		PacketGuard.register(PACKET_EDIT_BATCH, EditGuard::receiveBatchC2S);
		PacketGuard.register(PACKET_EDITING, EditGuard::receiveEditingC2S);
	}

	/**
	 * A change with the revision it was made from: the packet's ID, the revision and the packet.
	 */
	private static void receiveEditC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final ResourceLocation packetId = new ResourceLocation(packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH));
		final int base = packet.readInt();
		final FriendlyByteBuf inner = new FriendlyByteBuf(packet.readBytes(packet.readableBytes()));
		if (KINDS.containsKey(packetId)) {
			handle(minecraftServer, player, packetId, base, inner);
		}
	}

	private static void handle(MinecraftServer minecraftServer, ServerPlayer player, ResourceLocation packetId, int base, FriendlyByteBuf packet) {
		final String kind = KINDS.get(packetId);
		final long id = packet.getLong(packet.readerIndex());
		final String part = getPart(packetId, packet);
		// On the server's thread, so each change is checked against the ones made before it, in order
		PacketGuard.execute(minecraftServer, () -> {
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player)) {
				return;
			}
			final EditRevisions.Conflict conflict = railwayData.editRevisions.check(kind, id, part, base, isDelete(packetId));
			if (conflict != null) {
				reject(player, railwayData, Collections.singletonList(new long[]{0, id}), Collections.singletonList(kind), conflict);
				return;
			}
			apply(minecraftServer, player, railwayData, packetId, kind, id, part, packet);
		});
	}

	private static void apply(MinecraftServer minecraftServer, ServerPlayer player, RailwayData railwayData, ResourceLocation packetId, String kind, long id, String part, FriendlyByteBuf packet) {
		final int revision = railwayData.editRevisions.record(kind, id, part, player.getGameProfile().getName(), isDelete(packetId));
		sendRevision(Compat.getLevel(player), kind, id, isDelete(packetId) ? EditRevisions.WHOLE : part, revision, player.getUUID());
		try {
			HANDLERS.get(packetId).packetCallback(minecraftServer, player, packet);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * The web editor's changes, in groups each made all or not at all, one group for each route the page changed (or new
	 * route, or depot's duties): a group with a change made from an older copy isn't made, and doesn't keep the others
	 * from being made. The batch's number, how many changes, then each with its group and as {@link #receiveEditC2S} has
	 * it; then how many routes, each with the revision the page based its changes on ({@link Route#getEditorRevision}),
	 * for pages that don't send the revisions of each part. The player is told which groups weren't made, so the page
	 * keeps those in its draft.
	 */
	private static void receiveBatchC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int batchId = packet.readInt();
		final int count = PacketGuard.readCount(packet, 8, MAX_BATCH);
		final Map<String, List<Object[]>> groups = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			final String group = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
			final ResourceLocation packetId = new ResourceLocation(packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH));
			final int base = packet.readInt();
			final int length = packet.readInt();
			final FriendlyByteBuf inner = new FriendlyByteBuf(packet.readBytes(length));
			if (KINDS.containsKey(packetId)) {
				groups.computeIfAbsent(group, key -> new ArrayList<>()).add(new Object[]{packetId, base, inner});
			}
		}
		final Map<Long, String> routeBases = new HashMap<>();
		final int routeCount = PacketGuard.readCount(packet, 8, MAX_BATCH);
		for (int i = 0; i < routeCount; i++) {
			routeBases.put(packet.readLong(), packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH));
		}

		PacketGuard.execute(minecraftServer, () -> {
			final Map<String, EditRevisions.Conflict> rejected = new LinkedHashMap<>();
			final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
			if (railwayData == null || RailwayData.hasNoPermission(player)) {
				groups.keySet().forEach(group -> rejected.put(group, null));
				sendResult(player, batchId, rejected);
				return;
			}
			groups.forEach((group, changes) -> {
				// Every change of the group is checked before any of it is made
				EditRevisions.Conflict groupConflict = null;
				final List<long[]> objects = new ArrayList<>();
				final List<String> kinds = new ArrayList<>();
				for (final Object[] change : changes) {
					final ResourceLocation packetId = (ResourceLocation) change[0];
					final FriendlyByteBuf inner = (FriendlyByteBuf) change[2];
					final long id = inner.getLong(inner.readerIndex());
					objects.add(new long[]{0, id});
					kinds.add(KINDS.get(packetId));
					final EditRevisions.Conflict conflict = railwayData.editRevisions.check(KINDS.get(packetId), id, getPart(packetId, inner), (Integer) change[1], isDelete(packetId));
					if (conflict != null && groupConflict == null) {
						groupConflict = conflict;
					}
				}
				// The page's own revision of the group's route, covering its timetable and stops as the game has them now
				final Long routeId = group.startsWith(ROUTE + ":") ? parseLong(group.substring(ROUTE.length() + 1)) : null;
				final Route route = routeId == null ? null : railwayData.dataCache.routeIdMap.get(routeId);
				if (groupConflict == null && route != null && routeBases.containsKey(routeId) && !route.getEditorRevision().equals(routeBases.get(routeId))) {
					// Changed since the page loaded it; checked from a revision it can't have, which names who changed it last
					groupConflict = railwayData.editRevisions.check(ROUTE, route.id, "timetable", Integer.MIN_VALUE, false);
				}
				if (groupConflict != null) {
					rejected.put(group, groupConflict);
					reject(player, railwayData, objects, kinds, groupConflict);
					return;
				}
				for (final Object[] change : changes) {
					final ResourceLocation packetId = (ResourceLocation) change[0];
					final FriendlyByteBuf inner = (FriendlyByteBuf) change[2];
					apply(minecraftServer, player, railwayData, packetId, KINDS.get(packetId), inner.getLong(inner.readerIndex()), getPart(packetId, inner), inner);
				}
			});
			sendResult(player, batchId, rejected);
		});
	}

	private static Long parseLong(String text) {
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Which groups of a batch weren't made: the batch's number, how many, then each group, whether because something was
	 * deleted, and who changed or deleted it.
	 */
	private static void sendResult(ServerPlayer player, int batchId, Map<String, EditRevisions.Conflict> rejected) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(batchId);
		packet.writeInt(rejected.size());
		rejected.forEach((group, conflict) -> {
			packet.writeUtf(group);
			packet.writeBoolean(conflict != null && conflict.deleted);
			packet.writeUtf(conflict == null ? "" : conflict.playerName);
		});
		Registry.sendToPlayer(player, PACKET_EDIT_RESULT, packet);
	}

	/**
	 * Tells the player why their change wasn't made and sends them the data as it is now, so their copy is right again.
	 */
	private static void reject(ServerPlayer player, RailwayData railwayData, List<long[]> objects, List<String> kinds, EditRevisions.Conflict conflict) {
		final String name = conflict.playerName.isEmpty() ? "?" : conflict.playerName;
		player.displayClientMessage(Text.translatable(conflict.deleted ? "gui.mtr.edit_conflict_deleted" : "gui.mtr.edit_conflict_changed", name), false);
		final Set<String> sent = new HashSet<>();
		for (int i = 0; i < objects.size(); i++) {
			final String kind = kinds.get(i);
			final long id = objects.get(i)[1];
			if (sent.add(kind + ":" + id)) {
				sendData(player, railwayData, kind, id);
			}
		}
	}

	/**
	 * Sends one object as the server has it, or that it isn't there.
	 */
	private static void sendData(ServerPlayer player, RailwayData railwayData, String kind, long id) {
		final NameColorDataBase data = getData(railwayData, kind, id);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(kind);
		packet.writeLong(id);
		packet.writeBoolean(data != null);
		if (data != null) {
			data.writePacket(packet);
		}
		if (packet.readableBytes() <= MAX_PACKET_BYTES) {
			Registry.sendToPlayer(player, PACKET_EDIT_DATA, packet);
		}
	}

	private static NameColorDataBase getData(RailwayData railwayData, String kind, long id) {
		switch (kind) {
			case STATION:
				return railwayData.dataCache.stationIdMap.get(id);
			case PLATFORM:
				return railwayData.dataCache.platformIdMap.get(id);
			case SIDING:
				return railwayData.dataCache.sidingIdMap.get(id);
			case ROUTE:
				return railwayData.dataCache.routeIdMap.get(id);
			case DEPOT:
				return railwayData.dataCache.depotIdMap.get(id);
			default:
				return null;
		}
	}

	private static void sendRevision(Level world, String kind, long id, String part, int revision, UUID editor) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(1);
		writeRevision(packet, EditRevisions.getKey(kind, id, part), revision);
		packet.writeUUID(editor);
		world.players().forEach(worldPlayer -> Registry.sendToPlayer((ServerPlayer) worldPlayer, PACKET_EDIT_REVISIONS, new FriendlyByteBuf(packet.copy())));
	}

	/**
	 * Every revision the server has, for a player joining, so their changes are checked from the right ones.
	 */
	public static void revisionsS2C(ServerPlayer player) {
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		if (railwayData == null) {
			return;
		}
		final Map<String, Integer> revisions = railwayData.editRevisions.getAll();
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(revisions.size());
		revisions.forEach((key, revision) -> writeRevision(packet, key, revision));
		packet.writeUUID(new UUID(0, 0));
		Registry.sendToPlayer(player, PACKET_EDIT_REVISIONS, packet);
		sendEditors(player);
	}

	private static void writeRevision(FriendlyByteBuf packet, String key, int revision) {
		packet.writeUtf(key);
		packet.writeInt(revision);
	}

	/**
	 * A player started or stopped editing something: its kind, ID, and whether they are editing it now.
	 */
	private static void receiveEditingC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final String kind = packet.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		final long id = packet.readLong();
		final boolean editing = packet.readBoolean();
		PacketGuard.execute(minecraftServer, () -> {
			final String target = kind + ":" + id;
			if (editing) {
				EDITING.put(player.getUUID(), target);
				EDITING_NAMES.put(player.getUUID(), player.getGameProfile().getName());
			} else if (target.equals(EDITING.get(player.getUUID()))) {
				EDITING.remove(player.getUUID());
			}
			minecraftServer.getPlayerList().getPlayers().forEach(EditGuard::sendEditors);
		});
	}

	/**
	 * A player left, so they edit nothing any more.
	 */
	public static void onPlayerQuit(MinecraftServer minecraftServer, ServerPlayer player) {
		if (EDITING.remove(player.getUUID()) != null && minecraftServer != null) {
			minecraftServer.getPlayerList().getPlayers().forEach(otherPlayer -> {
				if (otherPlayer != player) {
					sendEditors(otherPlayer);
				}
			});
		}
	}

	/**
	 * Who is editing what: how many players, then each one's name and what they edit as {@code kind:id}.
	 */
	private static void sendEditors(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(EDITING.size());
		EDITING.forEach((uuid, target) -> {
			packet.writeUUID(uuid);
			packet.writeUtf(EDITING_NAMES.getOrDefault(uuid, "?"));
			packet.writeUtf(target);
		});
		Registry.sendToPlayer(player, PACKET_EDITORS, packet);
	}
}
