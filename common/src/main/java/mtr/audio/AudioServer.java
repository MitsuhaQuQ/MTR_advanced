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
import net.minecraft.resources.ResourceLocation;
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
	/** The kept folders (0.74.0) */
	private static final List<mtr.media.MediaFolders.Folder> FOLDERS = new ArrayList<>();

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
			final List<Entry> withoutSoundId = new ArrayList<>();
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId, folder) -> {
				if (AudioFiles.isHash(hash) && !namespace.isEmpty()) {
					final Entry entry = new Entry(hash, namespace.toLowerCase(Locale.ENGLISH), AudioFiles.cleanName(fileName), size, durationMillis, uploader, uploaderName, uploadedAt, AnnouncementSound.isValidSoundId(soundId) ? soundId : "");
					entry.folder = folder == null ? "" : folder;
					ENTRIES.put(entry.getKey(), entry);
					if (entry.soundId.isEmpty()) {
						withoutSoundId.add(entry);
					}
				} else {
					System.out.println("Skipping invalid announcement audio entry " + hash);
				}
			});
			// Files added before sound IDs get one from their file name or hash, kept so it stays the same
			for (final Entry entry : withoutSoundId) {
				entry.soundId = AnnouncementSound.getDefaultSoundId(entry.name, entry.hash, soundId -> isSoundIdTaken(entry.namespace, soundId, entry.getKey()));
				database.setSoundId(entry.getKey(), entry.soundId);
			}
			FOLDERS.addAll(database.folders.folders());
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
		// After every entry, so a client from before sound IDs reads the list as it always did
		ENTRIES.values().forEach(entry -> packet.writeUtf(entry.soundId));
		// Then each file's uploader and folder, and the kept folders (0.74.0)
		ENTRIES.values().forEach(entry -> {
			packet.writeUtf(entry.uploader);
			packet.writeUtf(entry.folder);
		});
		packet.writeInt(FOLDERS.size());
		FOLDERS.forEach(folder -> {
			packet.writeUtf(folder.owner);
			packet.writeUtf(folder.ownerName);
			packet.writeUtf(folder.path);
		});
		Registry.sendToPlayer(player, PACKET_AUDIO_LIST, packet);
	}

	public static void receiveUploadC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final int size = packet.readInt();
		final int offset = packet.readInt();
		final String name = packet.readUtf(AudioFiles.MAX_NAME_LENGTH * 4);
		final byte[] chunk = packet.readByteArray(AudioFiles.CHUNK_BYTES);
		// Last, and missing from a client from before sound IDs, which leaves it to the server
		final String soundId = packet.isReadable() ? packet.readUtf(AnnouncementSound.MAX_SOUND_ID_LENGTH * 4) : "";
		// The folder to put it in (0.74.0), or none to keep a replaced file where it is and put a new one at the top
		final String folder = packet.isReadable() ? packet.readUtf(mtr.media.MediaFolders.MAX_PATH_LENGTH * 4) : null;
		PacketGuard.execute(minecraftServer, () -> {
			if (database == null) {
				// Answered, so the page doesn't wait for nothing
				if (offset == 0) {
					sendResult(player, requestId, false, TranslatedMessage.encode("gui.mtr.audio_save_failed"));
				}
				return;
			}
			final UUID playerId = player.getUUID();
			Upload upload = UPLOADS.get(playerId);
			if (offset == 0) {
				String cleanFolder = null;
				String error = soundId.isEmpty() || AnnouncementSound.isValidSoundId(soundId) ? checkUploadStart(player, size) : TranslatedMessage.encode("gui.mtr.audio_bad_sound_id", AnnouncementSound.MAX_SOUND_ID_LENGTH);
				if (error == null && folder != null) {
					try {
						cleanFolder = mtr.media.MediaFolders.normalize(folder);
					} catch (IllegalArgumentException e) {
						error = TranslatedMessage.encode(mtr.media.MediaFolders.INVALID);
					}
				}
				if (error != null) {
					UPLOADS.remove(playerId);
					sendResult(player, requestId, false, error);
					return;
				}
				upload = new Upload(requestId, size, AudioFiles.cleanName(name), soundId);
				upload.folder = cleanFolder;
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
	 * Gives a file another sound ID, which {@code /playsound} and announcers can name it by. Whoever added it and managers
	 * can, as with deleting.
	 */
	public static void receiveSoundIdC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final String nameKey = packet.readUtf(MAX_KEY_LENGTH);
		final String soundId = packet.readUtf(AnnouncementSound.MAX_SOUND_ID_LENGTH * 4);
		PacketGuard.execute(minecraftServer, () -> {
			final Entry entry = ENTRIES.get(nameKey);
			final String error;
			if (database == null || entry == null) {
				error = TranslatedMessage.encode("gui.mtr.audio_missing");
			} else if (!canManage(player) && !entry.uploader.equals(player.getUUID().toString())) {
				error = TranslatedMessage.encode("gui.mtr.audio_no_delete_permission");
			} else if (!AnnouncementSound.isValidSoundId(soundId)) {
				error = TranslatedMessage.encode("gui.mtr.audio_bad_sound_id", AnnouncementSound.MAX_SOUND_ID_LENGTH);
			} else if (isSoundIdTaken(entry.namespace, soundId, nameKey)) {
				error = TranslatedMessage.encode("gui.mtr.audio_same_sound_id", soundId);
			} else {
				String saveError = null;
				try {
					database.setSoundId(nameKey, soundId);
				} catch (Exception e) {
					e.printStackTrace();
					saveError = TranslatedMessage.encode("gui.mtr.audio_save_failed");
				}
				error = saveError;
				if (error == null) {
					entry.soundId = soundId;
					System.out.printf("%s set the sound ID of announcement audio \"%s\" to %s%n", player.getName().getString(), entry.getReference(), entry.getSoundLocation());
					sendListToAll(minecraftServer);
				}
			}
			sendResult(player, requestId, error == null, error == null ? TranslatedMessage.encode("gui.mtr.audio_sound_id_set", entry.getReference(), String.valueOf(entry.getSoundLocation())) : error);
		});
	}

	/**
	 * A folder action (0.74.0, {@link mtr.media.MediaFolders#apply}): {@code int request id, byte action, String owner (UUID),
	 * String path, String argument, int count, String name key...}. Owners organise their own files; managers anyone's.
	 */
	public static void receiveFolderC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final int action = packet.readUnsignedByte();
		final String owner = packet.readUtf(64);
		final String path = packet.readUtf(mtr.media.MediaFolders.MAX_PATH_LENGTH * 4);
		final String argument = packet.readUtf(mtr.media.MediaFolders.MAX_PATH_LENGTH * 4);
		final int count = mtr.packet.PacketGuard.readCount(packet, 4, 1024);
		final List<String> keys = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			keys.add(packet.readUtf(MAX_KEY_LENGTH));
		}
		PacketGuard.execute(minecraftServer, () -> {
			final String key;
			if (database == null) {
				key = mtr.media.MediaFolders.FAILED;
			} else if (!mtr.media.MediaPermissions.canDelete(owner, player.getUUID().toString(), canManage(player))) {
				key = mtr.media.MediaFolders.NO_PERMISSION;
			} else {
				final List<String> ownerPaths = new ArrayList<>();
				ENTRIES.values().stream().filter(entry -> entry.uploader.equals(owner) && !entry.folder.isEmpty()).forEach(entry -> ownerPaths.add(entry.folder));
				FOLDERS.stream().filter(folder -> folder.owner.equals(owner)).forEach(folder -> ownerPaths.add(folder.path));
				key = mtr.media.MediaFolders.apply(database.folders, action, owner, ownerName(owner, player), path, argument, keys, nameKey -> {
					final Entry entry = ENTRIES.get(nameKey);
					return entry == null ? null : entry.uploader;
				}, mtr.media.MediaFolders.withParents(ownerPaths));
				if (key.equals(mtr.media.MediaFolders.DONE)) {
					reloadFolders();
					System.out.printf("%s organised the folders of announcement audio (%s %s %s)%n", player.getName().getString(), action, path, argument);
					sendListToAll(minecraftServer);
				}
			}
			sendResult(player, requestId, key.equals(mtr.media.MediaFolders.DONE), TranslatedMessage.encode(key));
		});
	}

	private static String ownerName(String owner, ServerPlayer player) {
		if (owner.equals(player.getUUID().toString())) {
			return player.getName().getString();
		}
		return ENTRIES.values().stream().filter(entry -> entry.uploader.equals(owner)).map(entry -> entry.uploaderName).findFirst().orElse(FOLDERS.stream().filter(folder -> folder.owner.equals(owner)).map(folder -> folder.ownerName).findFirst().orElse(owner));
	}

	private static void reloadFolders() {
		try {
			FOLDERS.clear();
			FOLDERS.addAll(database.folders.folders());
			final Map<String, String> fileFolders = database.folders.fileFolders();
			ENTRIES.forEach((nameKey, entry) -> entry.folder = fileFolders.getOrDefault(nameKey, ""));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param exceptKey the name key of the file asking, which doesn't count
	 */
	private static boolean isSoundIdTaken(String namespace, String soundId, String exceptKey) {
		return ENTRIES.values().stream().anyMatch(entry -> entry.namespace.equals(namespace) && entry.soundId.equals(soundId) && !entry.getKey().equals(exceptKey));
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
		if (!mtr.media.MediaPermissions.canDelete(entry.uploader, deleterId, canManage)) {
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

	/**
	 * Always answers, so the page never waits for an upload that went wrong here.
	 */
	private static void finishUpload(MinecraftServer minecraftServer, ServerPlayer player, Upload upload) {
		try {
			finishUploadChecked(minecraftServer, player, upload);
		} catch (RuntimeException e) {
			e.printStackTrace();
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_save_failed"));
		}
	}

	private static void finishUploadChecked(MinecraftServer minecraftServer, ServerPlayer player, Upload upload) {
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
		if (existing != null && existing.hash.equals(hash)) {
			sendResult(player, upload.requestId, true, TranslatedMessage.encode("gui.mtr.audio_already_uploaded", existing.name));
			return;
		}
		// A file of the same name in this player's own files is an update of it: it keeps its sound ID unless another is given, so
		// announcers and commands naming it play the new recording. Only the one who added it updates it.
		if (existing != null && !existing.uploader.equals(player.getUUID().toString())) {
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_same_name", existing.name));
			return;
		}
		// The sound ID given, or one from the file name or hash; one given has to be free among this player's files
		if (!upload.soundId.isEmpty() && isSoundIdTaken(namespace, upload.soundId, nameKey)) {
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_same_sound_id", upload.soundId));
			return;
		}
		final String soundId = !upload.soundId.isEmpty() ? upload.soundId : existing != null ? existing.soundId : AnnouncementSound.getDefaultSoundId(name, hash, checkSoundId -> isSoundIdTaken(namespace, checkSoundId, nameKey));
		final Entry entry = new Entry(hash, namespace, existing == null ? name : existing.name, upload.bytes.length, info.durationMillis, player.getUUID().toString(), player.getName().getString(), System.currentTimeMillis(), soundId);
		// The folder chosen, or where a replaced file was
		entry.folder = upload.folder != null ? upload.folder : existing != null ? existing.folder : "";
		try {
			database.replace(nameKey, entry.namespace, entry.name, entry.hash, entry.size, entry.durationMillis, entry.uploader, entry.uploaderName, entry.uploadedAt, entry.soundId, entry.folder, upload.bytes, existing == null ? null : existing.hash);
			ENTRIES.put(nameKey, entry);
			FOLDERS.clear();
			FOLDERS.addAll(database.folders.folders());
		} catch (Exception e) {
			e.printStackTrace();
			sendResult(player, upload.requestId, false, TranslatedMessage.encode("gui.mtr.audio_save_failed"));
			return;
		}
		// The old recording isn't sent any more once nothing names it
		if (existing != null && !hasHash(existing.hash)) {
			SENDING.values().forEach(queue -> queue.removeIf(sending -> sending.hash.equals(existing.hash)));
		}
		System.out.printf("%s %s announcement audio \"%s\" as %s (%s, %s bytes)%n", player.getName().getString(), existing == null ? "uploaded" : "updated", entry.name, entry.getSoundLocation(), hash, upload.bytes.length);
		sendResult(player, upload.requestId, true, TranslatedMessage.encode(existing == null ? "gui.mtr.audio_uploaded" : "gui.mtr.audio_updated", entry.name));
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

	public static String getUploadedHash(String reference) {
		final String[] name = AnnouncementSound.splitUploaded(reference);
		final Entry entry = name == null ? null : ENTRIES.get(AnnouncementSound.getNameKey(name[0], name[1]));
		return entry == null || entry.getSoundLocation() == null ? null : entry.hash;
	}

	public static long getUploadedDurationTicks(String reference) {
		final String[] name = AnnouncementSound.splitUploaded(reference);
		final Entry entry = name == null ? null : ENTRIES.get(AnnouncementSound.getNameKey(name[0], name[1]));
		return entry == null ? 0 : (entry.durationMillis + 49) / 50;
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
		/**
		 * The name of its sound in the game under {@link AnnouncementSound#SOUND_NAMESPACE}, unique among the files of whoever
		 * added it.
		 */
		private String soundId;
		/** The folder it is in under its uploader ({@link mtr.media.MediaFolders}), empty at the top */
		private String folder = "";

		private Entry(String hash, String namespace, String name, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt, String soundId) {
			this.hash = hash;
			this.namespace = namespace;
			this.name = name;
			this.size = size;
			this.durationMillis = durationMillis;
			this.uploader = uploader;
			this.uploaderName = uploaderName;
			this.uploadedAt = uploadedAt;
			this.soundId = soundId;
		}

		private ResourceLocation getSoundLocation() {
			return AnnouncementSound.getSoundLocation(namespace, soundId);
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
		private final String soundId;
		private final byte[] bytes;
		private int received;
		/** The folder chosen, or null to keep a replaced file where it is */
		private String folder;

		private Upload(int requestId, int size, String name, String soundId) {
			this.requestId = requestId;
			this.name = name;
			this.soundId = soundId;
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
