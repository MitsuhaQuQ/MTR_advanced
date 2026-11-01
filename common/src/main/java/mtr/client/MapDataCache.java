package mtr.client;

import mtr.data.MapSyncData;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps the network map on disk. The server lists a hash for each map entry, and only entries whose hash differs are
 * transferred. Hashes are compared, not trusted to a version number, so a wrong or stale file only costs a transfer.
 */
public final class MapDataCache {

	private static final int FILE_VERSION = 2;
	private static final int MAX_WANTED_PER_PACKET = 3000;
	private static final String CACHE_DIRECTORY = "mtr_map_cache";

	private static final List<Map<Long, Entry>> ENTRIES = new ArrayList<>();
	private static final List<Map<Long, Long>> DIGESTS = new ArrayList<>();
	private static final int[] PENDING = new int[MapSyncData.KIND_COUNT];
	private static String cacheName;

	static {
		for (int i = 0; i < MapSyncData.KIND_COUNT; i++) {
			ENTRIES.add(new HashMap<>());
			DIGESTS.add(new HashMap<>());
		}
	}

	private MapDataCache() {
	}

	public static void open() {
		final String newCacheName = getCacheName();
		if (!newCacheName.equals(cacheName)) {
			cacheName = newCacheName;
			load();
		}
		PacketTrainDataGuiClient.requestMapRailsC2S();
	}

	public static void receiveDigest(int kind, boolean isLast, long[] keys, long[] hashes) {
		final Map<Long, Long> digest = DIGESTS.get(kind);
		for (int i = 0; i < keys.length; i++) {
			digest.put(keys[i], hashes[i]);
		}
		if (!isLast) {
			return;
		}

		final Map<Long, Entry> entries = ENTRIES.get(kind);
		entries.keySet().removeIf(key -> !digest.containsKey(key));
		final List<Long> wanted = new ArrayList<>();
		digest.forEach((key, hash) -> {
			final Entry entry = entries.get(key);
			if (entry == null || entry.hash != hash) {
				wanted.add(key);
			}
		});
		digest.clear();

		PENDING[kind] = wanted.size();
		int start = 0;
		do {
			final int end = Math.min(start + MAX_WANTED_PER_PACKET, wanted.size());
			PacketTrainDataGuiClient.sendMapWantedC2S(kind, end == wanted.size(), wanted.subList(start, end));
			start = end;
		} while (start < wanted.size());

		if (wanted.isEmpty()) {
			finish(kind);
		}
	}

	public static void receiveData(int kind, long key, long hash, long[] payload) {
		ENTRIES.get(kind).put(key, new Entry(hash, payload));
		if (PENDING[kind] > 0) {
			PENDING[kind]--;
			if (PENDING[kind] == 0) {
				finish(kind);
			}
		} else {
			publish(kind);
		}
	}

	private static void finish(int kind) {
		publish(kind);
		save();
	}

	private static void publish(int kind) {
		if (kind == MapSyncData.KIND_RAILS) {
			ClientData.MAP_RAILS.clear();
			ENTRIES.get(kind).values().forEach(entry -> {
				for (int i = 0; i + 2 < entry.payload.length; i += MapSyncData.RAIL_LONGS) {
					ClientData.MAP_RAILS.add(new ClientData.MapRail(BlockPos.of(entry.payload[i]), BlockPos.of(entry.payload[i + 1]), TransportMode.values()[(int) entry.payload[i + 2]]));
				}
			});
		} else {
			final Map<Long, long[]> published = kind == MapSyncData.KIND_ROUTES ? ClientData.MAP_ROUTES : ClientData.MAP_SIDINGS;
			published.clear();
			ENTRIES.get(kind).forEach((key, entry) -> published.put(key, entry.payload));
		}
	}

	private static String getCacheName() {
		final Minecraft client = Minecraft.getInstance();
		final String world = client.getSingleplayerServer() != null ? client.getSingleplayerServer().getMotd() : client.getCurrentServer() != null ? client.getCurrentServer().ip : "unknown";
		final String dimension = client.level == null ? "" : client.level.dimension().location().toString();
		return Integer.toHexString((world + "|" + dimension).hashCode());
	}

	private static File getCacheFile() {
		return new File(new File(Minecraft.getInstance().gameDirectory, CACHE_DIRECTORY), cacheName + ".bin");
	}

	private static void load() {
		ENTRIES.forEach(Map::clear);
		final File file = getCacheFile();
		if (file.isFile()) {
			try (final DataInputStream stream = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
				if (stream.readInt() == FILE_VERSION) {
					for (int kind = 0; kind < MapSyncData.KIND_COUNT; kind++) {
						final int entryCount = stream.readInt();
						for (int i = 0; i < entryCount; i++) {
							final long key = stream.readLong();
							final long hash = stream.readLong();
							final long[] payload = new long[stream.readInt()];
							for (int j = 0; j < payload.length; j++) {
								payload[j] = stream.readLong();
							}
							ENTRIES.get(kind).put(key, new Entry(hash, payload));
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				ENTRIES.forEach(Map::clear);
			}
		}
		for (int kind = 0; kind < MapSyncData.KIND_COUNT; kind++) {
			publish(kind);
		}
	}

	private static void save() {
		final File file = getCacheFile();
		final List<Map<Long, Entry>> snapshot = new ArrayList<>();
		ENTRIES.forEach(entries -> snapshot.add(new HashMap<>(entries)));
		final Thread thread = new Thread(() -> {
			try {
				file.getParentFile().mkdirs();
				try (final DataOutputStream stream = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(file)))) {
					stream.writeInt(FILE_VERSION);
					for (final Map<Long, Entry> entries : snapshot) {
						stream.writeInt(entries.size());
						for (final Map.Entry<Long, Entry> entry : entries.entrySet()) {
							stream.writeLong(entry.getKey());
							stream.writeLong(entry.getValue().hash);
							stream.writeInt(entry.getValue().payload.length);
							for (final long value : entry.getValue().payload) {
								stream.writeLong(value);
							}
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		thread.setDaemon(true);
		thread.start();
	}

	private static final class Entry {

		private final long hash;
		private final long[] payload;

		private Entry(long hash, long[] payload) {
			this.hash = hash;
			this.payload = Arrays.copyOf(payload, payload.length);
		}
	}
}
