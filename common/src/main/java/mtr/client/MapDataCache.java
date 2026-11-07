package mtr.client;

import mtr.data.MapSyncData;
import mtr.data.RailShapes;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Keeps the network map on disk. The server lists a hash for each map entry, and only entries whose hash differs are
 * transferred. Hashes are compared, not trusted to a version number, so a wrong or stale file only costs a transfer.
 * The file also holds the terrain of {@link MapTerrainCache}. It is only touched from one thread, away from the game.
 */
public final class MapDataCache {

	private static final int MAX_WANTED_PER_PACKET = 3000;
	private static final String CACHE_DIRECTORY = "mtr_map_cache";
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR Map Cache");
		thread.setDaemon(true);
		return thread;
	});

	private static final List<Map<Long, Entry>> ENTRIES = new ArrayList<>();
	private static final List<Map<Long, Long>> DIGESTS = new ArrayList<>();
	private static final int[] PENDING = new int[MapSyncData.KIND_COUNT];
	private static String cacheName;
	private static int version;
	private static MapCacheDatabase database;

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
		final Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			MapTerrainCache.startScan(client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4);
		}
		PacketTrainDataGuiClient.requestMapRailsC2S();
	}

	public static void writeTiles(Map<Long, int[]> tiles) {
		runOnDatabase(() -> {
			if (database != null) {
				database.writeTiles(tiles);
				database.commit();
			}
		});
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
		final List<Long> removedKeys = new ArrayList<>();
		entries.keySet().forEach(key -> {
			if (!digest.containsKey(key)) {
				removedKeys.add(key);
			}
		});
		entries.keySet().removeAll(removedKeys);
		if (!removedKeys.isEmpty()) {
			runOnDatabase(() -> {
				if (database != null) {
					database.deleteEntries(kind, removedKeys);
					database.commit();
				}
			});
		}
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
		final Entry entry = new Entry(hash, payload);
		ENTRIES.get(kind).put(key, entry);
		runOnDatabase(() -> {
			if (database != null) {
				database.writeEntry(kind, key, hash, entry.payload);
			}
		});
		if (PENDING[kind] > 0) {
			PENDING[kind]--;
			if (PENDING[kind] == 0) {
				finish(kind);
			}
		} else {
			finish(kind);
		}
	}

	private static void finish(int kind) {
		publish(kind);
		runOnDatabase(() -> {
			if (database != null) {
				database.commit();
			}
		});
	}

	/**
	 * @return a number that changes each time map data is published, so the editor's network view only reloads on a change
	 */
	public static int getVersion() {
		return version;
	}

	private static void publish(int kind) {
		version++;
		if (kind == MapSyncData.KIND_RAILS) {
			ClientData.MAP_RAILS.clear();
			ENTRIES.get(kind).values().forEach(entry -> {
				for (int i = 0; i + 2 < entry.payload.length; i += MapSyncData.RAIL_LONGS) {
					ClientData.MAP_RAILS.add(new ClientData.MapRail(BlockPos.of(entry.payload[i]), BlockPos.of(entry.payload[i + 1]), TransportMode.values()[(int) (entry.payload[i + 2] & 0xFFFF)], (int) (entry.payload[i + 2] >>> 16)));
				}
			});
		} else if (kind == MapSyncData.KIND_RAIL_SHAPES) {
			ClientData.MAP_RAIL_SHAPES.clear();
			ENTRIES.get(kind).values().forEach(entry -> {
				int i = 0;
				while (i + 2 < entry.payload.length) {
					final long node1 = entry.payload[i];
					final long node2 = entry.payload[i + 1];
					final int count = (int) Math.max(0, Math.min(entry.payload[i + 2], (entry.payload.length - i - 3)));
					final float[] shape = new float[count * 2];
					for (int j = 0; j < count; j++) {
						shape[j * 2] = RailShapes.unpackX(entry.payload[i + 3 + j]);
						shape[j * 2 + 1] = RailShapes.unpackZ(entry.payload[i + 3 + j]);
					}
					ClientData.MAP_RAIL_SHAPES.put(transitsim.network.RailKeys.of(node1, node2), shape);
					i += 3 + count;
				}
			});
		} else {
			final Map<Long, long[]> published = kind == MapSyncData.KIND_ROUTES ? ClientData.MAP_ROUTES : ClientData.MAP_SIDINGS;
			published.clear();
			ENTRIES.get(kind).forEach((key, entry) -> published.put(key, entry.payload));
		}
	}

	/**
	 * Each world has its own folder with a file for each dimension. A world on this computer goes by its save folder, as
	 * different saves can have the same name, and a server goes by its address.
	 */
	private static String getCacheName() {
		final Minecraft client = Minecraft.getInstance();
		final Path savePath = client.getSingleplayerServer() == null ? null : client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		final String world = savePath != null ? "saves-" + savePath : client.getCurrentServer() != null ? "server-" + client.getCurrentServer().ip : "unknown";
		final String dimension = client.level == null ? "unknown" : client.level.dimension().location().toString();
		return MapCacheDatabase.getSafeName(world) + "/" + MapCacheDatabase.getSafeName(dimension);
	}

	private static File getCacheDirectory() {
		return new File(Minecraft.getInstance().gameDirectory, CACHE_DIRECTORY);
	}

	/**
	 * Opens the file of this world and shows what it has straight away. The entries are read before the server is asked for
	 * its list, so nothing already on disk is sent again. There can be a lot of terrain, so that comes in behind them.
	 */
	private static void load() {
		ENTRIES.forEach(Map::clear);
		MapTerrainCache.clear();
		final Path directory = getCacheDirectory().toPath();
		final Path path = directory.resolve(cacheName + ".db");
		final List<Object[]> savedEntries = new ArrayList<>();
		try {
			EXECUTOR.submit(() -> {
				closeDatabase();
				try {
					deleteOldFiles(directory);
					database = new MapCacheDatabase(path);
					database.readEntries((kind, key, hash, payload) -> savedEntries.add(new Object[]{kind, key, hash, payload}));
				} catch (Exception e) {
					e.printStackTrace();
					savedEntries.clear();
					replaceDatabase(path);
				}
			}).get();
		} catch (Exception e) {
			e.printStackTrace();
		}

		savedEntries.forEach(entry -> ENTRIES.get((int) entry[0]).put((long) entry[1], new Entry((long) entry[2], (long[]) entry[3])));
		for (int kind = 0; kind < MapSyncData.KIND_COUNT; kind++) {
			publish(kind);
		}

		runOnDatabase(() -> {
			if (database != null) {
				database.readTiles(MapTerrainCache::addSavedTile);
			}
		});
	}

	/**
	 * A file that can't be read is unusable, and without SQLite the map still works, just without keeping anything.
	 */
	private static void replaceDatabase(Path path) {
		closeDatabase();
		try {
			Files.deleteIfExists(path);
			database = new MapCacheDatabase(path);
		} catch (Exception | LinkageError e) {
			e.printStackTrace();
		}
	}

	/**
	 * Files used to sit directly in this folder, named by a hash that same-named worlds shared. They hold nothing that can't
	 * be sent or scanned again.
	 */
	private static void deleteOldFiles(Path directory) throws IOException {
		if (Files.isDirectory(directory)) {
			final List<Path> oldFiles = new ArrayList<>();
			try (final Stream<Path> files = Files.list(directory)) {
				files.filter(Files::isRegularFile).forEach(oldFiles::add);
			}
			for (final Path oldFile : oldFiles) {
				Files.deleteIfExists(oldFile);
			}
		}
	}

	private static void closeDatabase() {
		if (database != null) {
			try {
				database.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			database = null;
		}
	}

	private static void runOnDatabase(DatabaseTask task) {
		EXECUTOR.execute(() -> {
			try {
				task.run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	@FunctionalInterface
	private interface DatabaseTask {
		void run() throws Exception;
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
