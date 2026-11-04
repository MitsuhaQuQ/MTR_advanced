package mtr.client;

import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.data.*;
import mtr.packet.EditGuard;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * The client's side of keeping players from writing over each other's edits (multiplayer edit conflicts, see
 * {@link EditGuard}). Each change goes with the revision its part was at when the player opened the screen it comes
 * from, or as the client knows it now; the server turns down a change from an older copy and sends the data back as it
 * is. The client also says what it is editing, and keeps who else is editing what.
 */
public final class ClientEditing implements IPacket {

	private static final Map<String, Integer> REVISIONS = new HashMap<>();
	/**
	 * For each object open in an edit screen, as {@code kind:id}, the revision of each part when the screen was opened,
	 * moved on with the player's own changes, and when it was opened.
	 */
	private static final Map<String, Map<String, Integer>> SNAPSHOTS = new HashMap<>();
	private static final Map<String, Long> SNAPSHOT_MILLIS = new HashMap<>();
	/**
	 * The other players editing each object, as {@code kind:id}.
	 */
	private static final Map<String, List<String>> EDITORS = new HashMap<>();
	/**
	 * The web editor's changes being collected to send as one batch, or null.
	 */
	private static List<Object[]> batch;
	/**
	 * In the batch being collected, the revisions of each part the page made its changes from, by {@code kind:id}.
	 */
	private static final Map<String, Map<String, Integer>> BATCH_BASES = new HashMap<>();
	/**
	 * The batches sent and not yet answered, by number.
	 */
	private static final Map<Integer, CompletableFuture<BatchResult>> PENDING = new HashMap<>();
	private static int nextBatchId;

	/**
	 * How long a screen's revisions are used for its changes, as a screen left open longer is taken as stale anyway.
	 */
	private static final long SNAPSHOT_MILLIS_MAX = 30 * 60 * 1000;

	private ClientEditing() {
	}

	/**
	 * Sends a change of railway data with the revision it was made from, or adds it to the batch being collected.
	 *
	 * @return false if it isn't a change whose edits are checked, which is then sent as it is
	 */
	public static synchronized boolean send(ResourceLocation packetId, FriendlyByteBuf packet) {
		final String kind = EditGuard.getKind(packetId);
		if (kind == null) {
			return false;
		}
		final long id = packet.getLong(packet.readerIndex());
		final String part = EditGuard.getPart(packetId, packet);
		final String objectKey = kind + ":" + id;
		final Map<String, Integer> batchBases = batch == null ? null : BATCH_BASES.get(objectKey);
		final Map<String, Integer> snapshot = batchBases == null ? getSnapshot(objectKey) : null;
		final int base = EditGuard.isDelete(packetId) ? EditRevisions.UNCHECKED : batchBases != null ? batchBases.getOrDefault(part, 0) : snapshot == null ? REVISIONS.getOrDefault(EditRevisions.getKey(kind, id, part), 0) : snapshot.getOrDefault(part, 0);
		if (snapshot != null && base != EditRevisions.UNCHECKED) {
			// The screen's next change of the same part goes on from this one
			snapshot.put(part, base + 1);
		}
		final byte[] bytes = new byte[packet.readableBytes()];
		packet.getBytes(packet.readerIndex(), bytes);
		if (batch != null) {
			batch.add(new Object[]{packetId, base, bytes});
			return true;
		}
		final FriendlyByteBuf editPacket = new FriendlyByteBuf(Unpooled.buffer());
		editPacket.writeUtf(packetId.toString());
		editPacket.writeInt(base);
		editPacket.writeBytes(bytes);
		RegistryClient.sendToServer(PACKET_EDIT, editPacket);
		return true;
	}

	/**
	 * A screen editing this object opened: its changes are checked from the revisions the client knows now.
	 */
	public static synchronized void beginEdit(NameColorDataBase data) {
		final String kind = EditGuard.getKind(data);
		if (kind == null) {
			return;
		}
		final String objectKey = kind + ":" + data.id;
		final Map<String, Integer> snapshot = new HashMap<>();
		final String prefix = objectKey + ":";
		REVISIONS.forEach((key, revision) -> {
			if (key.startsWith(prefix)) {
				snapshot.put(key.substring(prefix.length()), revision);
			}
		});
		SNAPSHOTS.put(objectKey, snapshot);
		SNAPSHOT_MILLIS.put(objectKey, System.currentTimeMillis());
		sendEditing(kind, data.id, true);
	}

	/**
	 * The screen closed. Its revisions stay for the changes it sends as it closes.
	 */
	public static void endEdit(NameColorDataBase data) {
		final String kind = EditGuard.getKind(data);
		if (kind != null) {
			sendEditing(kind, data.id, false);
		}
	}

	/**
	 * Collects the changes sent from now on into one batch, for the web editor.
	 */
	public static synchronized void startBatch() {
		batch = new ArrayList<>();
		BATCH_BASES.clear();
	}

	/**
	 * In the batch being collected, this object's changes are checked from these revisions of its parts, as the page
	 * had them when it made its draft.
	 */
	public static synchronized void setBatchBases(NameColorDataBase data, Map<String, Integer> parts) {
		final String kind = EditGuard.getKind(data);
		if (kind != null && batch != null) {
			BATCH_BASES.putIfAbsent(kind + ":" + data.id, new HashMap<>(parts));
		}
	}

	/**
	 * @return the revision of each part of this object as the client knows it now
	 */
	public static synchronized Map<String, Integer> getRevisions(NameColorDataBase data) {
		final String kind = EditGuard.getKind(data);
		final Map<String, Integer> parts = new TreeMap<>();
		if (kind != null) {
			final String prefix = kind + ":" + data.id + ":";
			REVISIONS.forEach((key, revision) -> {
				if (key.startsWith(prefix)) {
					parts.put(key.substring(prefix.length()), revision);
				}
			});
		}
		return parts;
	}

	/**
	 * Sends the changes collected as one batch, made all or not at all.
	 *
	 * @param routeBases the revision ({@link Route#getEditorRevision}) the page based its changes of each route on
	 */
	public static synchronized CompletableFuture<BatchResult> sendBatch(Map<Long, String> routeBases) {
		final List<Object[]> changes = batch;
		batch = null;
		BATCH_BASES.clear();
		if (changes == null || changes.isEmpty()) {
			return CompletableFuture.completedFuture(new BatchResult(true, false, ""));
		}
		final int batchId = nextBatchId++;
		final CompletableFuture<BatchResult> future = new CompletableFuture<>();
		PENDING.put(batchId, future);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(batchId);
		packet.writeInt(changes.size());
		for (final Object[] change : changes) {
			final byte[] bytes = (byte[]) change[2];
			packet.writeUtf(change[0].toString());
			packet.writeInt((Integer) change[1]);
			packet.writeInt(bytes.length);
			packet.writeBytes(bytes);
		}
		packet.writeInt(routeBases.size());
		routeBases.forEach((routeId, base) -> {
			packet.writeLong(routeId);
			packet.writeUtf(base);
		});
		RegistryClient.sendToServer(PACKET_EDIT_BATCH, packet);
		return future;
	}

	/**
	 * Whether a batch was made, as the server answers it.
	 */
	public static void receiveResultS2C(FriendlyByteBuf packet) {
		final int batchId = packet.readInt();
		final BatchResult result = new BatchResult(packet.readBoolean(), packet.readBoolean(), packet.readUtf());
		final CompletableFuture<BatchResult> future;
		synchronized (ClientEditing.class) {
			future = PENDING.remove(batchId);
		}
		if (future != null) {
			future.complete(result);
		}
	}

	public static final class BatchResult {

		public final boolean ok;
		/**
		 * If it wasn't made, whether because something in it was deleted, and who changed or deleted it.
		 */
		public final boolean deleted;
		public final String playerName;

		private BatchResult(boolean ok, boolean deleted, String playerName) {
			this.ok = ok;
			this.deleted = deleted;
			this.playerName = playerName;
		}
	}

	/**
	 * @return the other players editing this object
	 */
	public static synchronized List<String> getEditors(NameColorDataBase data) {
		final String kind = EditGuard.getKind(data);
		return kind == null ? Collections.emptyList() : EDITORS.getOrDefault(kind + ":" + data.id, Collections.emptyList());
	}

	/**
	 * The revisions the server has now: how many, each key and revision, then who made the change, or no one for all of
	 * them on joining.
	 */
	public static void receiveRevisionsS2C(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		final Map<String, Integer> revisions = new HashMap<>();
		for (int i = 0; i < count; i++) {
			revisions.put(packet.readUtf(), packet.readInt());
		}
		final UUID editor = packet.readUUID();
		synchronized (ClientEditing.class) {
			if (editor.getMostSignificantBits() == 0 && editor.getLeastSignificantBits() == 0) {
				REVISIONS.clear();
				SNAPSHOTS.clear();
			}
			final boolean own = Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(editor);
			revisions.forEach((key, revision) -> {
				REVISIONS.put(key, revision);
				final int separator = key.lastIndexOf(':');
				final String objectKey = key.substring(0, separator);
				final String part = key.substring(separator + 1);
				final Map<String, Integer> snapshot = SNAPSHOTS.get(objectKey);
				if (part.equals(EditRevisions.WHOLE)) {
					SNAPSHOTS.remove(objectKey);
				} else if (own && snapshot != null) {
					snapshot.put(part, Math.max(revision, snapshot.getOrDefault(part, 0)));
				}
			});
		}
	}

	/**
	 * Who is editing what: how many players, then each one's ID, name and what they edit.
	 */
	public static void receiveEditorsS2C(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		final Map<String, List<String>> editors = new HashMap<>();
		final UUID self = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
		for (int i = 0; i < count; i++) {
			final UUID uuid = packet.readUUID();
			final String name = packet.readUtf();
			final String target = packet.readUtf();
			if (!uuid.equals(self)) {
				editors.computeIfAbsent(target, key -> new ArrayList<>()).add(name);
			}
		}
		synchronized (ClientEditing.class) {
			EDITORS.clear();
			EDITORS.putAll(editors);
		}
	}

	/**
	 * One object as the server has it, after a change of the player's was turned down: its kind, ID, whether it is there,
	 * and the object.
	 */
	public static void receiveDataS2C(Minecraft minecraft, FriendlyByteBuf packet) {
		final String kind = packet.readUtf();
		final long id = packet.readLong();
		final NameColorDataBase data;
		if (packet.readBoolean()) {
			switch (kind) {
				case EditGuard.STATION:
					data = new Station(packet);
					break;
				case EditGuard.PLATFORM:
					data = new Platform(packet);
					break;
				case EditGuard.SIDING:
					data = new Siding(packet);
					break;
				case EditGuard.ROUTE:
					data = new Route(packet);
					break;
				case EditGuard.DEPOT:
					data = new Depot(packet);
					break;
				default:
					return;
			}
		} else {
			data = null;
		}
		minecraft.execute(() -> {
			replace(kind, id, data);
			ClientData.DATA_CACHE.sync();
			ClientData.DATA_CACHE.refreshDynamicResources();
		});
	}

	@SuppressWarnings("unchecked")
	private static void replace(String kind, long id, NameColorDataBase data) {
		final Set<? extends NameColorDataBase> set;
		switch (kind) {
			case EditGuard.STATION:
				set = ClientData.STATIONS;
				break;
			case EditGuard.PLATFORM:
				set = ClientData.PLATFORMS;
				break;
			case EditGuard.SIDING:
				set = ClientData.SIDINGS;
				break;
			case EditGuard.ROUTE:
				set = ClientData.ROUTES;
				break;
			case EditGuard.DEPOT:
				set = ClientData.DEPOTS;
				break;
			default:
				return;
		}
		set.removeIf(existing -> existing.id == id);
		if (data != null) {
			((Set<NameColorDataBase>) set).add(data);
		}
	}

	private static Map<String, Integer> getSnapshot(String objectKey) {
		final Long millis = SNAPSHOT_MILLIS.get(objectKey);
		if (millis == null || System.currentTimeMillis() - millis > SNAPSHOT_MILLIS_MAX) {
			SNAPSHOTS.remove(objectKey);
			SNAPSHOT_MILLIS.remove(objectKey);
			return null;
		}
		return SNAPSHOTS.get(objectKey);
	}

	private static void sendEditing(String kind, long id, boolean editing) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeUtf(kind);
		packet.writeLong(id);
		packet.writeBoolean(editing);
		RegistryClient.sendToServer(PACKET_EDITING, packet);
	}
}
