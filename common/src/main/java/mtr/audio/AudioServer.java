package mtr.audio;

import io.netty.buffer.Unpooled;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import mtr.MTR;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.data.TranslatedMessage;
import mtr.mappings.Text;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import mtr.packet.IPacket;
import mtr.packet.PacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The announcement audio a server keeps, for all its dimensions, in one database in the save's {@code mtr} folder
 * ({@link AudioDatabase}). Each name a player gave a file ({@link AnnouncementSound}) points to audio kept once under its
 * hash, so two players can add the same recording under their own names. Players with the permission level in
 * {@link SecurityConfig} send files in pieces, which are only kept once whole and checked ({@link AudioFiles}). Clients ask for the files they
 * don't have, and those go out a few pieces a tick, so a big list doesn't hold up the connection. Everything here runs
 * on the server thread. Nothing here plays the audio or ties it to trains.
 */
public final class AudioServer implements IPacket {

	private static final String FOLDER = "mtr";
	private static final int MAX_WANTED = 256;
	private static final int CHUNKS_PER_TICK = 2;
	private static final long HOUR_MILLIS = 60 * 60 * 1000;
	private static final int MAX_KEY_LENGTH = 1024;

	// By name key ({@link AnnouncementSound#getNameKey})
	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
	private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
	private static final Map<UUID, Deque<Long>> UPLOAD_TIMES = new HashMap<>();
	private static final Map<UUID, Deque<Sending>> SENDING = new HashMap<>();
	private static final Set<String> MOD_NAMESPACES = new HashSet<>();
	private static AudioDatabase database;

	private AudioServer() {
	}

	/**
	 * Without the database, nothing can be added or sent, and the list stays empty.
	 */
	public static void open(MinecraftServer minecraftServer) {
		close();
		// Every mod has its own namespace in the data the server loads; sounds that only a client's resource pack adds can't be known here
		MOD_NAMESPACES.add("minecraft");
		MOD_NAMESPACES.add(MTR.MOD_ID);
		try {
			minecraftServer.getResourceManager().getNamespaces().forEach(namespace -> MOD_NAMESPACES.add(namespace.toLowerCase(Locale.ENGLISH)));
		} catch (Exception | LinkageError e) {
			e.printStackTrace();
		}
		final Path path = minecraftServer.getWorldPath(LevelResource.ROOT).resolve(FOLDER).resolve(AudioDatabase.FILE_NAME);
		try {
			database = new AudioDatabase(path);
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt) -> {
				if (AudioFiles.isHash(hash) && !namespace.isEmpty()) {
					final Entry entry = new Entry(hash, namespace.toLowerCase(Locale.ENGLISH), AudioFiles.cleanName(fileName), size, durationMillis, uploader, uploaderName, uploadedAt);
					ENTRIES.put(entry.getKey(), entry);
				} else {
					System.out.println("Skipping invalid announcement audio entry " + hash);
				}
			});
		} catch (Exception | LinkageError e) {
			System.out.println("Failed to open the announcement audio database " + path);
			e.printStackTrace();
			close();
		}
	}

	public static void close() {
		MOD_NAMESPACES.clear();
		ENTRIES.clear();
		UPLOADS.clear();
		UPLOAD_TIMES.clear();
		SENDING.clear();
		if (database != null) {
			try {
				database.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			database = null;
		}
	}

	public static void forget(ServerPlayer player) {
		UPLOADS.remove(player.getUUID());
		SENDING.remove(player.getUUID());
	}

	public static void tick(MinecraftServer minecraftServer) {
		SENDING.entrySet().removeIf(sendingEntry -> {
			// Players are looked up each time, as respawning makes a new player object
			final ServerPlayer player = minecraftServer.getPlayerList().getPlayer(sendingEntry.getKey());
			if (player == null) {
				return true;
			}
			final Deque<Sending> queue = sendingEntry.getValue();
			for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
				final Sending sending = queue.peekFirst();
				if (sending.bytes == null) {
					sending.bytes = readFile(sending.hash);
					if (sending.bytes == null) {
						queue.removeFirst();
						continue;
					}
				}
				final int length = Math.min(AudioFiles.CHUNK_BYTES, sending.bytes.length - sending.offset);
				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
				packet.writeUtf(sending.hash);
				packet.writeInt(sending.bytes.length);
				packet.writeInt(sending.offset);
				final byte[] chunk = new byte[length];
				System.arraycopy(sending.bytes, sending.offset, chunk, 0, length);
				packet.writeByteArray(chunk);
				Registry.sendToPlayer(player, PACKET_AUDIO_DATA, packet);
				sending.offset += length;
				if (sending.offset >= sending.bytes.length) {
					queue.removeFirst();
				}
			}
			return queue.isEmpty();
		});
	}

	/**
	 * The list for one player, with what that player may do, as the buttons on the client depend on it.
	 */
	public static void sendList(ServerPlayer player) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBoolean(canUpload(player));
		packet.writeBoolean(canManage(player));
		packet.writeInt(getMaxFileBytes());
		packet.writeInt(SecurityConfig.audioMaxSeconds);
		packet.writeLong(getMaxTotalBytes());
		packet.writeLong(getTotalBytes());
		packet.writeInt(ENTRIES.size());
		final String playerId = player.getUUID().toString();
		ENTRIES.values().forEach(entry -> {
			packet.writeUtf(entry.hash);
			packet.writeUtf(entry.namespace);
			packet.writeUtf(entry.name);
			packet.writeInt(entry.size);
			packet.writeLong(entry.durationMillis);
			packet.writeUtf(entry.uploaderName);
			packet.writeLong(entry.uploadedAt);
			packet.writeBoolean(entry.uploader.equals(playerId));
		});
		Registry.sendToPlayer(player, PACKET_AUDIO_LIST, packet);
	}

	public static void receiveUploadC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final int size = packet.readInt();
		final int offset = packet.readInt();
		final String name = packet.readUtf(AudioFiles.MAX_NAME_LENGTH * 4);
		final byte[] chunk = packet.readByteArray(AudioFiles.CHUNK_BYTES);
		PacketGuard.execute(minecraftServer, () -> {
			if (database == null) {
				return;
			}
			final UUID playerId = player.getUUID();
			Upload upload = UPLOADS.get(playerId);
			if (offset == 0) {
				final String error = checkUploadStart(player, size);
				if (error != null) {
					UPLOADS.remove(playerId);
					sendResult(player, requestId, false, error);
					return;
				}
				upload = new Upload(requestId, size, AudioFiles.cleanName(name));
				UPLOADS.put(playerId, upload);
			} else if (upload == null || upload.requestId != requestId || upload.received != offset) {
				// Pieces of an upload that was turned down or replaced
				return;
			}

			if (chunk.length == 0 || upload.received + chunk.length > upload.bytes.length) {
				UPLOADS.remove(playerId);
				sendResult(player, requestId, false, TranslatedMessage.encode("gui.mtr.audio_size_mismatch"));
				return;
			}
			System.arraycopy(chunk, 0, upload.bytes, upload.received, chunk.length);
			upload.received += chunk.length;
			if (upload.received == upload.bytes.length) {
				UPLOADS.remove(playerId);
				finishUpload(minecraftServer, player, upload);
			}
		});
	}

	public static void receiveDeleteC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final String nameKey = packet.readUtf(MAX_KEY_LENGTH);
		PacketGuard.execute(minecraftServer, () -> {
			final Entry entry = ENTRIES.get(nameKey);
			final String reference = entry == null ? nameKey : entry.getReference();
			final String error = delete(minecraftServer, player.getName().getString(), canManage(player), player.getUUID().toString(), nameKey);
			sendResult(player, requestId, error == null, error == null ? TranslatedMessage.encode("gui.mtr.audio_deleted", reference) : error);
		});
	}

	/**
	 * {@code /mtr deleteaudio <namespace>:<file name>}, with the same rules as deleting on the advanced editor's page. The
	 * server console can delete anything.
	 */
	public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mtr").then(Commands.literal("deleteaudio").then(Commands.argument("name", StringArgumentType.greedyString())
				.suggests((context, builder) -> {
					final ServerPlayer player = getPlayer(context.getSource());
					final boolean canManage = canManage(context.getSource(), player);
					final List<String> names = new ArrayList<>();
					ENTRIES.values().forEach(entry -> {
						if (canManage || player != null && entry.uploader.equals(player.getUUID().toString())) {
							names.add(entry.getReference());
						}
					});
					return SharedSuggestionProvider.suggest(names, builder);
				})
				.executes(context -> {
					final CommandSourceStack source = context.getSource();
					final ServerPlayer player = getPlayer(source);
					final String reference = StringArgumentType.getString(context, "name").trim();
					final String[] parts = AnnouncementSound.splitUploaded(reference);
					final String error = parts == null ? TranslatedMessage.encode("gui.mtr.audio_bad_reference") : delete(source.getServer(), source.getTextName(), canManage(source, player), player == null ? "" : player.getUUID().toString(), AnnouncementSound.getNameKey(parts[0], parts[1]));
					final String message = error == null ? TranslatedMessage.encode("gui.mtr.audio_deleted", reference) : error;
					if (player == null) {
						System.out.println(TranslatedMessage.decode(message));
					} else {
						player.displayClientMessage(TranslatedMessage.toComponent(message), false);
					}
					return error == null ? 1 : 0;
				}))));
	}

	/**
	 * Anyone can take back what they added, even after losing the right to add more; only managers delete others'.
	 *
	 * @return why nothing was deleted, or null if it was
	 */
	private static String delete(MinecraftServer minecraftServer, String deleterName, boolean canManage, String deleterId, String nameKey) {
		final Entry entry = ENTRIES.get(nameKey);
		if (database == null || entry == null) {
			return TranslatedMessage.encode("gui.mtr.audio_missing");
		}
		if (!canManage && !entry.uploader.equals(deleterId)) {
			return TranslatedMessage.encode("gui.mtr.audio_no_delete_permission");
		}
		try {
			database.delete(nameKey, entry.hash);
		} catch (Exception e) {
			e.printStackTrace();
			return TranslatedMessage.encode("gui.mtr.audio_delete_failed");
		}
		ENTRIES.remove(nameKey);
		if (!hasHash(entry.hash)) {
			SENDING.values().forEach(queue -> queue.removeIf(sending -> sending.hash.equals(entry.hash)));
		}
		System.out.printf("%s deleted announcement audio \"%s\" (%s)%n", deleterName, entry.getReference(), entry.hash);
		sendListToAll(minecraftServer);
		return null;
	}

	/**
	 * The namespace a player's new files get ({@link AnnouncementSound#getNamespace}).
	 */
	private static String getPlayerNamespace(ServerPlayer player) {
		return AnnouncementSound.getNamespace(player.getName().getString(), MOD_NAMESPACES);
	}

	private static ServerPlayer getPlayer(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
	}

	/**
	 * A command from the console or a command block goes by the permission level it runs with.
	 */
	private static boolean canManage(CommandSourceStack source, ServerPlayer player) {
		return player == null ? source.hasPermission(SecurityConfig.audioManagePermissionLevel) : canManage(player);
	}

	public static void receiveWantedC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int count = PacketGuard.readCount(packet, AudioFiles.HASH_LENGTH, MAX_WANTED);
		final List<String> hashes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			hashes.add(packet.readUtf(AudioFiles.HASH_LENGTH));
		}
		PacketGuard.execute(minecraftServer, () -> {
			final Deque<Sending> queue = SENDING.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>());
			hashes.forEach(hash -> {
				if (hasHash(hash) && queue.stream().noneMatch(sending -> sending.hash.equals(hash))) {
					queue.addLast(new Sending(hash));
				}
			});
			if (queue.isEmpty()) {
				SENDING.remove(player.getUUID());
			}
		});
	}

	public static boolean canUpload(ServerPlayer player) {
		return getMaxFileBytes() > 0 && (player.hasPermissions(SecurityConfig.audioUploadPermissionLevel) || isOwner(player));
	}

	public static boolean canManage(ServerPlayer player) {
		return player.hasPermissions(SecurityConfig.audioManagePermissionLevel) || isOwner(player);
	}

	/**
	 * Whoever plays a world on their own computer can always add audio to it, even without cheats.
	 */
	private static boolean isOwner(ServerPlayer player) {
		final MinecraftServer minecraftServer = player.getServer();
		return minecraftServer != null && !minecraftServer.isDedicatedServer() && minecraftServer.isSingleplayerOwner(player.getGameProfile());
	}

	/**
	 * @return why an upload can't start, or null if it can
	 */
	private static String checkUploadStart(ServerPlayer player, int size) {
		if (!canUpload(player)) {
			return TranslatedMessage.encode("gui.mtr.audio_no_permission");
		}
		if (size <= 0 || size > getMaxFileBytes()) {
			return TranslatedMessage.encode("gui.mtr.audio_file_too_big_kb", getMaxFileBytes() / 1024);
		}
		if (getTotalBytes() + size > getMaxTotalBytes()) {
			return TranslatedMessage.encode("gui.mtr.audio_server_full");
		}
		final long millis = System.currentTimeMillis();
		final Deque<Long> times = UPLOAD_TIMES.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>());
		while (!times.isEmpty() && millis - times.peekFirst() >= HOUR_MILLIS) {
			times.removeFirst();
		}
		if (times.size() >= SecurityConfig.audioUploadsPerHour && !isOwner(player)) {
			return TranslatedMessage.encode("gui.mtr.audio_too_frequent");
		}
		// Every attempt counts, so sending broken files over and over is limited too
		times.addLast(millis);
		return null;
	}

	private static void finishUpload(MinecraftServer minecraftServer, ServerPlayer player, Upload upload) {
		final AudioFiles.Info info;
		try {
			info = AudioFiles.check(upload.bytes);
		} catch (AudioFiles.InvalidAudioException e) {
			sendResult(player, upload.requestId, false, e.getMessage());
			return;
		}
		if (info.durationMillis > SecurityConfig.audioMaxSeconds * 1000L) {
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_too_long_seconds", SecurityConfig.audioMaxSeconds));
			return;
		}
		final String hash = AudioFiles.hash(upload.bytes);
		// Announcers name a file by who added it and its file name, so one player can't have two of the same name, whatever the case
		final String name = AudioFiles.withExtension(upload.name.isEmpty() ? hash.substring(0, 8) : upload.name);
		final String namespace = getPlayerNamespace(player);
		final String nameKey = AnnouncementSound.getNameKey(namespace, name);
		final Entry existing = ENTRIES.get(nameKey);
		if (existing != null) {
			if (existing.hash.equals(hash)) {
				sendResult(player, upload.requestId, true, TranslatedMessage.encode("gui.mtr.audio_already_uploaded", existing.name));
			} else {
				sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_same_name", existing.name));
			}
			return;
		}
		final Entry entry = new Entry(hash, namespace, name, upload.bytes.length, info.durationMillis, player.getUUID().toString(), player.getName().getString(), System.currentTimeMillis());
		try {
			database.write(nameKey, entry.namespace, entry.name, entry.hash, entry.size, entry.durationMillis, entry.uploader, entry.uploaderName, entry.uploadedAt, upload.bytes);
			ENTRIES.put(nameKey, entry);
		} catch (Exception e) {
			e.printStackTrace();
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_save_failed"));
			return;
		}
		System.out.printf("%s uploaded announcement audio \"%s\" (%s, %s bytes)%n", player.getName().getString(), name, hash, upload.bytes.length);
		sendResult(player, upload.requestId, true, TranslatedMessage.encode("gui.mtr.audio_uploaded", name));
		sendListToAll(minecraftServer);
	}

	private static void sendListToAll(MinecraftServer minecraftServer) {
		minecraftServer.getPlayerList().getPlayers().forEach(AudioServer::sendList);
	}

	private static void sendResult(ServerPlayer player, int requestId, boolean ok, String message) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeBoolean(ok);
		packet.writeUtf(message);
		Registry.sendToPlayer(player, PACKET_AUDIO_RESULT, packet);
	}

	private static byte[] readFile(String hash) {
		try {
			return database == null ? null : database.readData(hash);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static int getMaxFileBytes() {
		return Math.min(AudioFiles.MAX_FILE_BYTES, SecurityConfig.audioMaxFileKilobytes * 1024);
	}

	private static long getMaxTotalBytes() {
		return SecurityConfig.audioMaxTotalMegabytes * 1024L * 1024;
	}

	/**
	 * Audio under several names takes its room once.
	 */
	private static long getTotalBytes() {
		final Map<String, Integer> sizes = new HashMap<>();
		ENTRIES.values().forEach(entry -> sizes.put(entry.hash, entry.size));
		return sizes.values().stream().mapToLong(size -> size).sum();
	}

	private static boolean hasHash(String hash) {
		return ENTRIES.values().stream().anyMatch(entry -> entry.hash.equals(hash));
	}

	private static final class Entry {

		private final String hash;
		private final String namespace;
		private final String name;
		private final int size;
		private final long durationMillis;
		private final String uploader;
		private final String uploaderName;
		private final long uploadedAt;

		private Entry(String hash, String namespace, String name, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt) {
			this.hash = hash;
			this.namespace = namespace;
			this.name = name;
			this.size = size;
			this.durationMillis = durationMillis;
			this.uploader = uploader;
			this.uploaderName = uploaderName;
			this.uploadedAt = uploadedAt;
		}

		private String getKey() {
			return AnnouncementSound.getNameKey(namespace, name);
		}

		private String getReference() {
			return AnnouncementSound.getUploadedString(namespace, name);
		}
	}

	private static final class Upload {

		private final int requestId;
		private final String name;
		private final byte[] bytes;
		private int received;

		private Upload(int requestId, int size, String name) {
			this.requestId = requestId;
			this.name = name;
			bytes = new byte[size];
		}
	}

	private static final class Sending {

		private final String hash;
		private byte[] bytes;
		private int offset;

		private Sending(String hash) {
			this.hash = hash;
		}
	}
}
