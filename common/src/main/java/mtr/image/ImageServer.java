package mtr.image;

import io.netty.buffer.Unpooled;
import mtr.Items;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.audio.AudioFiles;
import mtr.compat.Compat;
import mtr.packet.IPacket;
import mtr.packet.PacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

/** World-wide imported images; all mutation runs on the server thread. */
public final class ImageServer implements IPacket {
	private static ImageDatabase database;
	private static final List<ImageDatabase.Entry> ENTRIES = new ArrayList<>();
	private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
	private static final Map<UUID, Deque<Download>> DOWNLOADS = new HashMap<>();
	private static final Map<UUID, Deque<Long>> ATTEMPTS = new HashMap<>();
	private static String session = "";
	private static final Map<UUID, Integer> PERMISSIONS = new HashMap<>();
	private static int ticks;
	private ImageServer() { }
	public static void open(MinecraftServer server) {
		close();
		try { database = new ImageDatabase(server.getWorldPath(LevelResource.ROOT).resolve("mtr/pictures.db")); ENTRIES.addAll(database.entries()); session = UUID.randomUUID().toString(); }
		catch (Exception e) { e.printStackTrace(); close(); }
	}
	public static void close() {
		if (database != null) try { database.close(); } catch (Exception e) { e.printStackTrace(); }
		database = null; ENTRIES.clear(); UPLOADS.clear(); DOWNLOADS.clear(); ATTEMPTS.clear(); PERMISSIONS.clear(); session = "";
	}
	public static void forget(ServerPlayer player) { UPLOADS.remove(player.getUUID()); DOWNLOADS.remove(player.getUUID()); PERMISSIONS.remove(player.getUUID()); }
	public static boolean canEdit(ServerPlayer player) { return !player.isSpectator() && player.hasPermissions(SecurityConfig.editPermissionLevel); }
	private static boolean owner(ServerPlayer player) { return player.getServer() != null && !player.getServer().isDedicatedServer() && player.getServer().isSingleplayerOwner(player.getGameProfile()); }
	private static boolean canUpload(ServerPlayer player) { return !player.isSpectator() && (owner(player) || player.hasPermissions(SecurityConfig.imageUploadPermissionLevel)); }
	private static boolean canDelete(ServerPlayer player, ImageDatabase.Entry entry) { return mtr.media.MediaPermissions.canDelete(entry.uploader, player.getUUID().toString(), owner(player) || player.hasPermissions(SecurityConfig.imageManagePermissionLevel)); }
	private static String namespace(ServerPlayer player) {
		final Set<String> namespaces = new HashSet<>(); namespaces.add("minecraft"); namespaces.add("mtr");
		if (player.getServer() != null) namespaces.addAll(player.getServer().getResourceManager().getNamespaces());
		return mtr.audio.AnnouncementSound.getNamespace(player.getName().getString(), namespaces);
	}
	public static void sendList(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeByte(0); packet.writeUtf(session); packet.writeBoolean(database != null && canUpload(player)); packet.writeInt(ENTRIES.size());
		for (ImageDatabase.Entry entry : ENTRIES) { packet.writeUtf(entry.hash); packet.writeUtf(entry.name); packet.writeInt(entry.width); packet.writeInt(entry.height); packet.writeInt(entry.size); packet.writeBoolean(canDelete(player, entry)); packet.writeUtf(entry.uploader); packet.writeUtf(entry.uploaderName); packet.writeLong(entry.uploadedAt); }
		packet.writeLong(SecurityConfig.imageMaxTotalMegabytes * 1024L * 1024);
		Registry.sendToPlayer(player, PACKET_IMAGE_DATA, packet);
	}
	private static void result(ServerPlayer player, boolean ok, String key) {
		result(player, ok, key, true);
	}
	private static void result(ServerPlayer player, boolean ok, String key, boolean transfer) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(2); packet.writeBoolean(ok); packet.writeBoolean(transfer); packet.writeUtf(key);
		Registry.sendToPlayer(player, PACKET_IMAGE_DATA, packet);
	}
	public static void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
		final int action = packet.readUnsignedByte();
		if (action == 0) {
			final String name = AudioFiles.cleanName(packet.readUtf(64));
			final int size = packet.readInt(), offset = packet.readInt();
			final byte[] chunk = packet.readByteArray(ImageFiles.CHUNK);
			PacketGuard.execute(server, () -> upload(server, player, name, size, offset, chunk));
		} else if (action == 1) {
			final String hash = packet.readUtf(64);
			PacketGuard.execute(server, () -> {
				if (database == null || !ImageFiles.isHash(hash) || ENTRIES.stream().noneMatch(entry -> entry.hash.equals(hash))) return;
				final Deque<Download> queue = DOWNLOADS.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
				if (queue.size() >= 16 || queue.stream().anyMatch(download -> download.hash.equals(hash))) return;
				try { final byte[] bytes = database.read(hash); if (bytes != null) queue.add(new Download(hash, bytes)); } catch (Exception e) { e.printStackTrace(); }
			});
		} else if (action == 2) {
			final int id = packet.readInt(); final String json = packet.readUtf(512);
			PacketGuard.execute(server, () -> {
				final Entity entity = Compat.getLevel(player).getEntity(id);
				if (!(entity instanceof Painting) || !(entity instanceof PaintingImage) || !canEdit(player) || player.distanceToSqr(entity) > 64 || (!player.getMainHandItem().is(Items.BRUSH.get()) && !player.getOffhandItem().is(Items.BRUSH.get()))) { result(player, false, "gui.mtr.image_no_permission", false); return; }
				try {
					final PaintingSettings settings = PaintingSettings.parse(json);
					if (!settings.image.isEmpty()) settings.image = ENTRIES.stream().filter(entry -> entry.name.equalsIgnoreCase(settings.image)).findFirst().orElseThrow(IllegalArgumentException::new).name;
					((PaintingImage) entity).mtr$setImageSettings(settings.json());
					result(player, true, "gui.mtr.image_saved", false);
				} catch (RuntimeException e) { result(player, false, "gui.mtr.image_invalid_settings", false); }
			});
		} else if (action == 3) {
			final String reference = packet.readUtf(129);
			PacketGuard.execute(server, () -> {
				final ImageDatabase.Entry existing = ENTRIES.stream().filter(entry -> entry.name.equalsIgnoreCase(reference)).findFirst().orElse(null);
				if (database == null || !ImageFiles.isReference(reference) || existing == null || !canDelete(player, existing)) { result(player, false, "gui.mtr.image_no_permission", false); return; }
				try { database.delete(reference); ENTRIES.clear(); ENTRIES.addAll(database.entries()); result(player, true, "gui.mtr.image_deleted", false); server.getPlayerList().getPlayers().forEach(ImageServer::sendList); }
				catch (Exception e) { e.printStackTrace(); result(player, false, "gui.mtr.image_invalid", false); }
			});
		} else throw new IllegalArgumentException("Unknown image action");
	}
	private static void upload(MinecraftServer server, ServerPlayer player, String name, int size, int offset, byte[] chunk) {
		final UUID id = player.getUUID();
		if (database == null || !canUpload(player)) { UPLOADS.remove(id); result(player, false, "gui.mtr.image_no_permission"); return; }
		if (size < 1 || size > ImageFiles.MAX_BYTES || offset < 0 || chunk.length < 1 || (long) offset + chunk.length > size) { UPLOADS.remove(id); result(player, false, "gui.mtr.image_invalid"); return; }
		if (offset == 0) {
			final Deque<Long> times = ATTEMPTS.computeIfAbsent(id, key -> new ArrayDeque<>());
			final long now = System.currentTimeMillis();
			while (!times.isEmpty() && now - times.peekFirst() > 3600000) times.removeFirst();
			if (times.size() >= 30 || UPLOADS.containsKey(id)) { result(player, false, "gui.mtr.image_busy"); return; }
			times.addLast(now); UPLOADS.put(id, new Upload(name, size));
		}
		final Upload upload = UPLOADS.get(id);
		if (upload == null) return;
		if (upload.bytes.length != size || upload.offset != offset || !upload.name.equals(name)) { UPLOADS.remove(id); result(player, false, "gui.mtr.image_invalid"); return; }
		System.arraycopy(chunk, 0, upload.bytes, offset, chunk.length); upload.offset += chunk.length; upload.updated = System.currentTimeMillis();
		if (upload.offset != size) return;
		UPLOADS.remove(id);
		try {
			final String extension = ImageFiles.isGif(upload.bytes) ? ".gif" : ".png";
			final String fileName = upload.name.replaceAll("(?i)\\.(png|jpe?g|gif)$", "") + extension;
			final String namespace = namespace(player), reference = namespace + ":" + fileName;
			final ImageDatabase.Entry existing = ENTRIES.stream().filter(entry -> entry.name.equalsIgnoreCase(reference)).findFirst().orElse(null);
			if (existing != null && !existing.uploader.equals(id.toString())) { result(player, false, "gui.mtr.image_no_permission"); return; }
			final Map<String, Integer> sizes = new HashMap<>();
			ENTRIES.stream().filter(entry -> entry != existing).forEach(entry -> sizes.put(entry.hash, entry.size)); sizes.put(ImageFiles.hash(upload.bytes), size);
			if ((existing == null && ENTRIES.size() >= 256) || sizes.values().stream().mapToLong(Integer::longValue).sum() > SecurityConfig.imageMaxTotalMegabytes * 1024L * 1024) { result(player, false, "gui.mtr.image_full"); return; }
			database.add(namespace, existing == null ? fileName : existing.fileName, upload.bytes, id.toString(), player.getName().getString()); ENTRIES.clear(); ENTRIES.addAll(database.entries());
			result(player, true, "gui.mtr.image_uploaded"); server.getPlayerList().getPlayers().forEach(ImageServer::sendList);
		} catch (Exception e) {
			final String key = e.getMessage();
			result(player, false, e instanceof IllegalArgumentException && key != null && key.startsWith("gui.mtr.image_") ? key : "gui.mtr.image_invalid");
		}
	}
	public static void tick(MinecraftServer server) {
		if (++ticks % 20 == 0) server.getPlayerList().getPlayers().forEach(player -> {
			final int permissions = (canUpload(player) ? 1 : 0) | (owner(player) || player.hasPermissions(SecurityConfig.imageManagePermissionLevel) ? 2 : 0) | (mtr.audio.AudioServer.canUpload(player) ? 4 : 0) | (mtr.audio.AudioServer.canManage(player) ? 8 : 0);
			final Integer previous = PERMISSIONS.put(player.getUUID(), permissions);
			if (previous == null || previous != permissions) { sendList(player); mtr.audio.AudioServer.sendList(player); }
		});
		UPLOADS.entrySet().removeIf(entry -> {
			if (System.currentTimeMillis() - entry.getValue().updated < 30000) return false;
			final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey()); if (player != null) result(player, false, "gui.mtr.image_timeout"); return true;
		});
		DOWNLOADS.entrySet().removeIf(entry -> {
			final ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey()); if (player == null) return true;
			for (int i = 0; i < 2 && !entry.getValue().isEmpty(); i++) {
				final Download download = entry.getValue().peekFirst();
				final int length = Math.min(ImageFiles.CHUNK, download.bytes.length - download.offset);
				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer()); packet.writeByte(1); packet.writeUtf(download.hash); packet.writeInt(download.bytes.length); packet.writeInt(download.offset); packet.writeByteArray(Arrays.copyOfRange(download.bytes, download.offset, download.offset + length));
				Registry.sendToPlayer(player, PACKET_IMAGE_DATA, packet); download.offset += length;
				if (download.offset == download.bytes.length) entry.getValue().removeFirst();
			}
			return entry.getValue().isEmpty();
		});
	}
	private static final class Upload {
		final String name; final byte[] bytes; int offset; long updated = System.currentTimeMillis();
		Upload(String name, int size) { this.name = name; bytes = new byte[size]; }
	}
	private static final class Download {
		final String hash; final byte[] bytes; int offset;
		Download(String hash, byte[] bytes) { this.hash = hash; this.bytes = bytes; }
	}
}
