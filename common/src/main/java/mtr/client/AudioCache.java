package mtr.client;

import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.audio.AnnouncementSound;
import mtr.audio.AudioDatabase;
import mtr.audio.AudioFiles;
import mtr.data.TranslatedMessage;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The announcement audio this client has, in one database in the {@code mtr_audio_cache} folder, each file under its
 * hash ({@link AudioDatabase}). Files are shared by every world and server, as the same hash is the same file. When the server sends its list, the files not here
 * yet are asked for in the background, and each one is only kept if its hash matches. This also sends the files the
 * advanced editor's page adds or deletes ({@link mtr.audio.AudioServer} checks them again). Called on the game thread.
 */
public final class AudioCache implements IPacket {

	public static boolean canUpload;
	public static boolean canManage;
	public static int maxFileBytes;
	public static int maxSeconds;
	public static long maxTotalBytes;
	public static long totalBytes;
	public static final List<Entry> ENTRIES = new ArrayList<>();
	/** The kept folders, of every uploader (0.74.0) */
	public static final List<mtr.media.MediaFolders.Folder> FOLDERS = new ArrayList<>();
	/**
	 * What became of the latest uploads and deletions, newest last, for the page to show.
	 */
	public static final Map<Integer, Request> REQUESTS = new LinkedHashMap<>();

	private static final String CACHE_DIRECTORY = "mtr_audio_cache";
	private static final int MAX_WANTED_PER_PACKET = 256;
	private static final int MAX_REQUESTS = 20;
	/**
	 * How long the page waits for the server to answer an upload or a change before telling the player it got no answer,
	 * as when the server is from before it answered every time, plus time for the size of a file.
	 */
	private static final long REQUEST_TIMEOUT_MILLIS = 30000;
	private static final int TIMEOUT_BYTES_PER_SECOND = 64 * 1024;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR Audio Cache");
		thread.setDaemon(true);
		return thread;
	});

	private static final Map<String, Download> DOWNLOADS = new HashMap<>();
	private static final Set<String> CACHED = Collections.synchronizedSet(new HashSet<>());
	private static boolean opened;
	// Only used on the executor's thread
	private static AudioDatabase database;
	private static int nextRequestId = 1;

	private AudioCache() {
	}

	/**
	 * @return the file if it is here; if not, it is asked for, and this is null until it arrives
	 */
	public static byte[] read(String hash) {
		if (!AudioFiles.isHash(hash)) {
			return null;
		}
		if (!isCached(hash)) {
			request(Collections.singletonList(hash));
			return null;
		}
		try {
			final byte[] bytes = EXECUTOR.submit(() -> database == null ? null : database.readData(hash)).get();
			if (bytes == null) {
				CACHED.remove(hash);
			}
			return bytes;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * The file as kept here, for a thread other than the game's, without asking the server for it.
	 *
	 * @return the file, or null if it isn't here
	 */
	public static byte[] readStored(String hash) {
		if (!AudioFiles.isHash(hash) || !CACHED.contains(hash)) {
			return null;
		}
		try {
			return EXECUTOR.submit(() -> database == null ? null : database.readData(hash)).get();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Asks the server for a file this client doesn't have yet, as when it is played before it was fetched.
	 */
	public static void want(String hash) {
		if (!isCached(hash)) {
			request(Collections.singletonList(hash));
		}
	}

	/**
	 * @param namespace who added the file, in lowercase, as the server made it ({@link AnnouncementSound#getNamespace})
	 * @return the hash of the server's file with this name, ignoring case as the server does, or null if it has none
	 */
	public static String getHash(String namespace, String name) {
		final Entry entry = find(namespace, name);
		return entry == null ? null : entry.hash;
	}

	/**
	 * @return the file with this name, or the one that had it before (0.79.0), or null
	 */
	public static Entry find(String namespace, String name) {
		final Map<String, List<String>> oldNames = new HashMap<>();
		ENTRIES.forEach(entry -> oldNames.put(entry.resourceId, entry.oldNames));
		return mtr.media.MediaIds.resolve(AnnouncementSound.getUploadedString(namespace, name), ENTRIES, Entry::getReference, entry -> entry.resourceId, mtr.media.MediaIds.byOldName(oldNames));
	}

	public static boolean isCached(String hash) {
		open();
		return CACHED.contains(hash);
	}

	public static boolean isDownloading(String hash) {
		return DOWNLOADS.containsKey(hash);
	}

	/**
	 * Checks a file here first, so a wrong file is turned down straight away, then sends it to the server in pieces.
	 *
	 * @return the number to look for in {@link #REQUESTS}
	 * @throws AudioFiles.InvalidAudioException with the reason a file can't be sent
	 */
	public static int upload(String name, String soundId, byte[] bytes) throws AudioFiles.InvalidAudioException {
		return upload(name, soundId, bytes, null);
	}

	/**
	 * @param folder the folder to put it in, or null to leave a replaced file where it is and put a new one at the top
	 */
	public static int upload(String name, String soundId, byte[] bytes, String folder) throws AudioFiles.InvalidAudioException {
		return upload(name, soundId, bytes, folder, null);
	}

	/**
	 * @param replaceId the id of the file to put this in place of (0.79.0), keeping its id, folder and sound ID, or null
	 */
	public static int upload(String name, String soundId, byte[] bytes, String folder, String replaceId) throws AudioFiles.InvalidAudioException {
		final String cleanFolder;
		try {
			cleanFolder = folder == null ? null : mtr.media.MediaFolders.normalize(folder);
		} catch (IllegalArgumentException e) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode(mtr.media.MediaFolders.INVALID));
		}
		if (!canUpload) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_no_permission"));
		}
		if (bytes.length > maxFileBytes) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_file_too_big_kb", maxFileBytes / 1024));
		}
		final AudioFiles.Info info = AudioFiles.check(bytes);
		if (info.durationMillis > maxSeconds * 1000L) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_too_long_seconds", maxSeconds));
		}
		// Empty leaves it to the server, which makes one from the file name or hash
		final String cleanSoundId = soundId == null ? "" : soundId.trim();
		if (!cleanSoundId.isEmpty() && !AnnouncementSound.isValidSoundId(cleanSoundId)) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_bad_sound_id", AnnouncementSound.MAX_SOUND_ID_LENGTH));
		}
		if (!cleanSoundId.isEmpty() && ENTRIES.stream().anyMatch(entry -> entry.mine && entry.soundId.equals(cleanSoundId) && !entry.name.equalsIgnoreCase(AudioFiles.withExtension(AudioFiles.cleanName(name))) && !entry.resourceId.equals(replaceId))) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_same_sound_id", cleanSoundId));
		}

		// A file of the same name in this player's own files is replaced by the server
		final String fileName = AudioFiles.withExtension(AudioFiles.cleanName(name));

		final int requestId = addRequest(fileName);
		REQUESTS.get(requestId).timeoutMillis += (long) bytes.length * 1000 / TIMEOUT_BYTES_PER_SECOND;
		for (int offset = 0; offset < bytes.length; offset += AudioFiles.UPLOAD_CHUNK_BYTES) {
			final int length = Math.min(AudioFiles.UPLOAD_CHUNK_BYTES, bytes.length - offset);
			final byte[] chunk = new byte[length];
			System.arraycopy(bytes, offset, chunk, 0, length);
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(requestId);
			packet.writeInt(bytes.length);
			packet.writeInt(offset);
			packet.writeUtf(fileName);
			packet.writeByteArray(chunk);
			packet.writeUtf(cleanSoundId);
			if (cleanFolder != null || replaceId != null) {
				packet.writeUtf(cleanFolder == null ? "" : cleanFolder);
			}
			if (replaceId != null) {
				packet.writeUtf(replaceId);
			}
			RegistryClient.sendToServer(PACKET_AUDIO_UPLOAD, packet);
		}
		// This client has the file already, so once the server takes it, it isn't sent back
		REQUESTS.get(requestId).bytes = bytes.clone();
		return requestId;
	}

	/**
	 * Deletes one name; the audio stays while another name uses it.
	 */
	public static int delete(Entry entry) {
		final int requestId = addRequest(entry.getReference());
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeUtf(entry.getKey());
		RegistryClient.sendToServer(PACKET_AUDIO_DELETE, packet);
		return requestId;
	}

	/**
	 * Gives a file another sound ID ({@link mtr.audio.AudioServer#receiveSoundIdC2S}).
	 *
	 * @throws AudioFiles.InvalidAudioException if the sound ID isn't valid
	 */
	public static int setSoundId(Entry entry, String soundId) throws AudioFiles.InvalidAudioException {
		final String cleanSoundId = soundId == null ? "" : soundId.trim();
		if (!AnnouncementSound.isValidSoundId(cleanSoundId)) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_bad_sound_id", AnnouncementSound.MAX_SOUND_ID_LENGTH));
		}
		final int requestId = addRequest(entry.getReference());
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeUtf(entry.getKey());
		packet.writeUtf(cleanSoundId);
		RegistryClient.sendToServer(PACKET_AUDIO_SOUND_ID, packet);
		return requestId;
	}

	/**
	 * A folder action ({@link mtr.audio.AudioServer#receiveFolderC2S}).
	 *
	 * @param owner the uploader whose folders these are (UUID)
	 * @param keys  for moving files: their name keys
	 */
	public static int folderAction(int action, String owner, String path, String argument, java.util.Collection<String> keys) {
		final int requestId = addRequest(path.isEmpty() ? argument : path);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeByte(action);
		packet.writeUtf(owner);
		packet.writeUtf(path);
		packet.writeUtf(argument);
		packet.writeInt(keys.size());
		keys.forEach(packet::writeUtf);
		RegistryClient.sendToServer(PACKET_AUDIO_FOLDER, packet);
		return requestId;
	}

	public static void receiveListS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final boolean newCanUpload = packet.readBoolean();
		final boolean newCanManage = packet.readBoolean();
		final int newMaxFileBytes = packet.readInt();
		final int newMaxSeconds = packet.readInt();
		final long newMaxTotalBytes = packet.readLong();
		final long newTotalBytes = packet.readLong();
		final int count = packet.readInt();
		final List<Entry> entries = new ArrayList<>();
		final List<Object[]> rawEntries = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final String hash = packet.readUtf();
			final String namespace = packet.readUtf();
			final String name = packet.readUtf();
			final int size = packet.readInt();
			final long durationMillis = packet.readLong();
			final String uploaderName = packet.readUtf();
			final long uploadedAt = packet.readLong();
			final boolean mine = packet.readBoolean();
			rawEntries.add(new Object[]{hash, namespace, name, size, durationMillis, uploaderName, uploadedAt, mine});
		}
		// The sound IDs come after every entry, and not at all from a server from before them
		final String[] soundIds = new String[count];
		for (int i = 0; i < count; i++) {
			soundIds[i] = packet.isReadable() ? packet.readUtf() : "";
		}
		// Then each file's uploader and folder, and the kept folders (0.74.0)
		final String[] uploaders = new String[count];
		final String[] folders = new String[count];
		for (int i = 0; i < count; i++) {
			uploaders[i] = packet.isReadable() ? packet.readUtf(64) : "";
			folders[i] = packet.isReadable() ? packet.readUtf(mtr.media.MediaFolders.MAX_PATH_LENGTH * 4) : "";
		}
		final List<mtr.media.MediaFolders.Folder> newFolders = new ArrayList<>();
		final int folderCount = packet.isReadable() ? packet.readInt() : 0;
		for (int i = 0; i < folderCount; i++) {
			newFolders.add(new mtr.media.MediaFolders.Folder(packet.readUtf(64), packet.readUtf(), packet.readUtf(mtr.media.MediaFolders.MAX_PATH_LENGTH * 4)));
		}
		// Then each file's id and the references it had before (0.79.0)
		final String[] resourceIds = new String[count];
		final List<List<String>> oldNameLists = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			resourceIds[i] = packet.isReadable() ? packet.readUtf(16) : "";
			final List<String> names = new ArrayList<>();
			final int oldCount = packet.isReadable() ? mtr.packet.PacketGuard.readCount(packet, 1, 4096) : 0;
			for (int j = 0; j < oldCount; j++) {
				names.add(packet.readUtf());
			}
			oldNameLists.add(names);
		}
		for (int i = 0; i < count; i++) {
			final String soundId = soundIds[i];
			final Object[] raw = rawEntries.get(i);
			final String hash = (String) raw[0];
			final String namespace = (String) raw[1];
			final int size = (Integer) raw[3];
			if (AudioFiles.isHash(hash) && !namespace.isEmpty() && size > 0 && size <= AudioFiles.MAX_FILE_BYTES) {
				final Entry entry = new Entry(hash, namespace.toLowerCase(Locale.ENGLISH), (String) raw[2], size, (Long) raw[4], (String) raw[5], (Long) raw[6], (Boolean) raw[7], AnnouncementSound.isValidSoundId(soundId) ? soundId : "");
				entry.uploader = uploaders[i];
				entry.folder = folders[i];
				entry.resourceId = resourceIds[i];
				entry.oldNames = oldNameLists.get(i);
				entries.add(entry);
			}
		}
		minecraftClient.execute(() -> {
			canUpload = newCanUpload;
			canManage = newCanManage;
			maxFileBytes = Math.min(AudioFiles.MAX_FILE_BYTES, newMaxFileBytes);
			maxSeconds = newMaxSeconds;
			maxTotalBytes = newMaxTotalBytes;
			totalBytes = newTotalBytes;
			// A name now with another recording was updated: whoever had the old one gets the new one straight away,
			// whatever the size, so announcers and /playsound play the update the first time
			final Map<String, String> oldHashes = new HashMap<>();
			// By id too, so a file replaced under another name counts as updated (0.79.0)
			ENTRIES.forEach(entry -> {
				oldHashes.put(entry.getKey(), entry.hash);
				if (!entry.resourceId.isEmpty()) {
					oldHashes.put("#" + entry.resourceId, entry.hash);
				}
			});
			final List<String> updated = new ArrayList<>();
			entries.forEach(entry -> {
				final String oldHash = oldHashes.getOrDefault(entry.getKey(), entry.resourceId.isEmpty() ? null : oldHashes.get("#" + entry.resourceId));
				if (oldHash != null && !oldHash.equals(entry.hash) && isCached(oldHash) && !isCached(entry.hash)) {
					updated.add(entry.hash);
				}
			});
			ENTRIES.clear();
			ENTRIES.addAll(entries);
			FOLDERS.clear();
			FOLDERS.addAll(newFolders);
			// Pieces of files the server no longer has won't come
			DOWNLOADS.keySet().removeIf(hash -> entries.stream().noneMatch(entry -> entry.hash.equals(hash)));
			// Every file the server has up to the size the player set, unless the player chose to fetch a file only once
			// something plays it; a bigger one is fetched the first time it plays
			if (Config.audioDownloadAll()) {
				final long maxPrefetchBytes = Config.audioPrefetchMaxKilobytes() * 1024L;
				final List<String> missing = new ArrayList<>();
				entries.forEach(entry -> {
					if (entry.size <= maxPrefetchBytes && !isCached(entry.hash)) {
						missing.add(entry.hash);
					}
				});
				request(missing);
			}
			request(updated);
			UploadedSounds.refresh();
		});
	}

	public static void receiveResultS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final boolean ok = packet.readBoolean();
		final String message = packet.readUtf();
		minecraftClient.execute(() -> {
			final Request request = REQUESTS.get(requestId);
			if (request != null) {
				request.done = true;
				request.ok = ok;
				request.message = message;
				if (ok && request.bytes != null) {
					store(AudioFiles.hash(request.bytes), request.bytes);
				}
				request.bytes = null;
			}
		});
	}

	public static void receiveDataS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final String hash = packet.readUtf(AudioFiles.HASH_LENGTH);
		final int size = packet.readInt();
		final int offset = packet.readInt();
		final byte[] chunk = packet.readByteArray(AudioFiles.CHUNK_BYTES);
		minecraftClient.execute(() -> {
			final Download download = DOWNLOADS.get(hash);
			// Only what was asked for is taken, in order, and no bigger than the server said
			if (download == null || offset != download.received || size <= 0 || size > AudioFiles.MAX_FILE_BYTES || offset + chunk.length > size) {
				return;
			}
			if (download.bytes == null) {
				download.bytes = new byte[size];
			} else if (download.bytes.length != size) {
				DOWNLOADS.remove(hash);
				return;
			}
			System.arraycopy(chunk, 0, download.bytes, offset, chunk.length);
			download.received += chunk.length;
			if (download.received == size) {
				DOWNLOADS.remove(hash);
				if (AudioFiles.hash(download.bytes).equals(hash)) {
					store(hash, download.bytes);
				} else {
					System.out.println("Discarding announcement audio that doesn't match its hash " + hash);
				}
			}
		});
	}

	private static void request(List<String> hashes) {
		final List<String> wanted = new ArrayList<>();
		hashes.forEach(hash -> {
			if (!DOWNLOADS.containsKey(hash) && ENTRIES.stream().anyMatch(entry -> entry.hash.equals(hash))) {
				DOWNLOADS.put(hash, new Download());
				wanted.add(hash);
			}
		});
		for (int start = 0; start < wanted.size(); start += MAX_WANTED_PER_PACKET) {
			final List<String> part = wanted.subList(start, Math.min(start + MAX_WANTED_PER_PACKET, wanted.size()));
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(part.size());
			part.forEach(packet::writeUtf);
			RegistryClient.sendToServer(PACKET_AUDIO_WANTED, packet);
		}
	}

	/**
	 * Gives up on what the server hasn't answered in time, so the page doesn't show it as being sent forever.
	 */
	public static void expireRequests() {
		final long millis = System.currentTimeMillis();
		REQUESTS.values().forEach(request -> {
			if (!request.done && millis - request.startedMillis > request.timeoutMillis) {
				request.done = true;
				request.ok = false;
				request.message = TranslatedMessage.encode("gui.mtr.audio_no_response");
				request.bytes = null;
			}
		});
	}

	private static int addRequest(String name) {
		final int requestId = nextRequestId++;
		REQUESTS.put(requestId, new Request(requestId, name));
		while (REQUESTS.size() > MAX_REQUESTS) {
			REQUESTS.remove(REQUESTS.keySet().iterator().next());
		}
		return requestId;
	}

	private static void store(String hash, byte[] bytes) {
		if (isCached(hash)) {
			return;
		}
		final byte[] copy = bytes.clone();
		EXECUTOR.execute(() -> {
			try {
				if (database != null) {
					database.writeData(hash, copy);
					CACHED.add(hash);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Opens the database once and lists what it has, so checking for a file doesn't touch the disk each time. A file that
	 * can't be opened is replaced, as everything in it can be sent again; without SQLite nothing is kept.
	 */
	private static void open() {
		if (opened) {
			return;
		}
		opened = true;
		final Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_DIRECTORY).resolve(AudioDatabase.FILE_NAME);
		// Waited for, so the files already here aren't asked for again
		final Future<?> future = EXECUTOR.submit(() -> {
			try {
				database = new AudioDatabase(path);
				database.readDataHashes(hash -> {
					if (AudioFiles.isHash(hash)) {
						CACHED.add(hash);
					}
				});
			} catch (Exception | LinkageError e) {
				e.printStackTrace();
				try {
					if (database != null) {
						database.close();
					}
					database = null;
					Files.deleteIfExists(path);
					database = new AudioDatabase(path);
				} catch (Exception | LinkageError e2) {
					e2.printStackTrace();
					database = null;
				}
			}
		});
		try {
			future.get();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static final class Entry {

		public final String hash;
		public final String namespace;
		public final String name;
		public final int size;
		public final long durationMillis;
		public final String uploaderName;
		public final long uploadedAt;
		public final boolean mine;
		/**
		 * The name of its sound in the game under {@link AnnouncementSound#SOUND_NAMESPACE}, or empty from a server that
		 * doesn't give one.
		 */
		public final String soundId;
		/** Who uploaded it (UUID), empty from a server from before folders */
		public String uploader = "";
		/** The folder it is in under its uploader ({@link mtr.media.MediaFolders}), empty at the top */
		public String folder = "";
		/** Its id, which stays when it is replaced, and the references it had before (0.79.0); empty from an older server */
		public String resourceId = "";
		public List<String> oldNames = Collections.emptyList();

		private Entry(String hash, String namespace, String name, int size, long durationMillis, String uploaderName, long uploadedAt, boolean mine, String soundId) {
			this.hash = hash;
			this.namespace = namespace;
			this.name = name;
			this.size = size;
			this.durationMillis = durationMillis;
			this.uploaderName = uploaderName;
			this.uploadedAt = uploadedAt;
			this.mine = mine;
			this.soundId = soundId;
		}

		/**
		 * @return the name {@code /playsound} and announcers can play it by, or null if it has none
		 */
		public ResourceLocation getSoundLocation() {
			return AnnouncementSound.getSoundLocation(namespace, soundId);
		}

		/**
		 * @return what an announcer names this file with
		 */
		public String getReference() {
			return AnnouncementSound.getUploadedString(namespace, name);
		}

		public String getKey() {
			return AnnouncementSound.getNameKey(namespace, name);
		}
	}

	public static final class Request {

		public final int id;
		public final String name;
		private final long startedMillis = System.currentTimeMillis();
		private long timeoutMillis = REQUEST_TIMEOUT_MILLIS;
		public boolean done;
		public boolean ok;
		public String message = "";
		private byte[] bytes;

		private Request(int id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private static final class Download {

		private byte[] bytes;
		private int received;
	}
}
