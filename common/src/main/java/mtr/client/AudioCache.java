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
	/**
	 * What became of the latest uploads and deletions, newest last, for the page to show.
	 */
	public static final Map<Integer, Request> REQUESTS = new LinkedHashMap<>();

	private static final String CACHE_DIRECTORY = "mtr_audio_cache";
	private static final int MAX_WANTED_PER_PACKET = 256;
	private static final int MAX_REQUESTS = 20;
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
	 * @param namespace who added the file, in lowercase, as the server made it ({@link AnnouncementSound#getNamespace})
	 * @return the hash of the server's file with this name, ignoring case as the server does, or null if it has none
	 */
	public static String getHash(String namespace, String name) {
		return ENTRIES.stream().filter(entry -> entry.namespace.equals(namespace) && entry.name.equalsIgnoreCase(name)).map(entry -> entry.hash).findFirst().orElse(null);
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
	public static int upload(String name, byte[] bytes) throws AudioFiles.InvalidAudioException {
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

		final String fileName = AudioFiles.withExtension(AudioFiles.cleanName(name));
		// Only the server knows which names belong to mods, so this player's own files are told by the server's list
		final String hash = AudioFiles.hash(bytes);
		if (ENTRIES.stream().anyMatch(entry -> entry.mine && entry.name.equalsIgnoreCase(fileName) && !entry.hash.equals(hash))) {
			throw new AudioFiles.InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_same_name_local", fileName));
		}

		final int requestId = addRequest(fileName);
		for (int offset = 0; offset < bytes.length; offset += AudioFiles.CHUNK_BYTES) {
			final int length = Math.min(AudioFiles.CHUNK_BYTES, bytes.length - offset);
			final byte[] chunk = new byte[length];
			System.arraycopy(bytes, offset, chunk, 0, length);
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(requestId);
			packet.writeInt(bytes.length);
			packet.writeInt(offset);
			packet.writeUtf(fileName);
			packet.writeByteArray(chunk);
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

	public static void receiveListS2C(Minecraft minecraftClient, FriendlyByteBuf packet) {
		final boolean newCanUpload = packet.readBoolean();
		final boolean newCanManage = packet.readBoolean();
		final int newMaxFileBytes = packet.readInt();
		final int newMaxSeconds = packet.readInt();
		final long newMaxTotalBytes = packet.readLong();
		final long newTotalBytes = packet.readLong();
		final int count = packet.readInt();
		final List<Entry> entries = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final String hash = packet.readUtf();
			final String namespace = packet.readUtf();
			final String name = packet.readUtf();
			final int size = packet.readInt();
			final long durationMillis = packet.readLong();
			final String uploaderName = packet.readUtf();
			final long uploadedAt = packet.readLong();
			final boolean mine = packet.readBoolean();
			if (AudioFiles.isHash(hash) && !namespace.isEmpty() && size > 0 && size <= AudioFiles.MAX_FILE_BYTES) {
				entries.add(new Entry(hash, namespace.toLowerCase(Locale.ENGLISH), name, size, durationMillis, uploaderName, uploadedAt, mine));
			}
		}
		minecraftClient.execute(() -> {
			canUpload = newCanUpload;
			canManage = newCanManage;
			maxFileBytes = Math.min(AudioFiles.MAX_FILE_BYTES, newMaxFileBytes);
			maxSeconds = newMaxSeconds;
			maxTotalBytes = newMaxTotalBytes;
			totalBytes = newTotalBytes;
			ENTRIES.clear();
			ENTRIES.addAll(entries);
			// Pieces of files the server no longer has won't come
			DOWNLOADS.keySet().removeIf(hash -> entries.stream().noneMatch(entry -> entry.hash.equals(hash)));
			final List<String> missing = new ArrayList<>();
			entries.forEach(entry -> {
				if (!isCached(entry.hash)) {
					missing.add(entry.hash);
				}
			});
			request(missing);
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

		private Entry(String hash, String namespace, String name, int size, long durationMillis, String uploaderName, long uploadedAt, boolean mine) {
			this.hash = hash;
			this.namespace = namespace;
			this.name = name;
			this.size = size;
			this.durationMillis = durationMillis;
			this.uploaderName = uploaderName;
			this.uploadedAt = uploadedAt;
			this.mine = mine;
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
