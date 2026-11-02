package mtr.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapCacheDatabaseTest {

	private static final int KIND = 1;

	@TempDir
	Path directory;

	private Path path() {
		return directory.resolve("cache").resolve("world.db");
	}

	private static int[] tile(int color) {
		final int[] colors = new int[MapCacheDatabase.TILE_COLORS];
		Arrays.fill(colors, color);
		return colors;
	}

	private Map<Long, int[]> readTiles() throws Exception {
		final Map<Long, int[]> tiles = new HashMap<>();
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.readTiles(tiles::put);
		}
		return tiles;
	}

	@Test
	void entriesComeBackAfterReopening() throws Exception {
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.writeEntry(KIND, 5, 99, new long[]{1, -2, Long.MAX_VALUE});
			database.commit();
		}

		final List<long[]> payloads = new ArrayList<>();
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.readEntries((kind, key, hash, payload) -> {
				assertEquals(KIND, kind);
				assertEquals(5, key);
				assertEquals(99, hash);
				payloads.add(payload);
			});
		}

		assertEquals(1, payloads.size());
		assertArrayEquals(new long[]{1, -2, Long.MAX_VALUE}, payloads.get(0));
	}

	@Test
	void aNewHashReplacesAnEntry() throws Exception {
		final List<Long> hashes = new ArrayList<>();
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.writeEntry(KIND, 5, 1, new long[]{1});
			database.writeEntry(KIND, 5, 2, new long[]{2});
			database.readEntries((kind, key, hash, payload) -> hashes.add(hash));
		}
		assertEquals(Collections.singletonList(2L), hashes);
	}

	@Test
	void deletedEntriesAreGone() throws Exception {
		final List<Long> keys = new ArrayList<>();
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.writeEntry(KIND, 5, 1, new long[]{1});
			database.writeEntry(KIND, 6, 1, new long[]{1});
			database.writeEntry(KIND + 1, 5, 1, new long[]{1});
			database.deleteEntries(KIND, Collections.singletonList(5L));
			database.readEntries((kind, key, hash, payload) -> keys.add(kind * 100L + key));
		}
		Collections.sort(keys);
		assertEquals(Arrays.asList(106L, 205L), keys);
	}

	@Test
	void newTilesReplaceOldOnesAndOthersStay() throws Exception {
		final long kept = MapCacheDatabase.getTileKey(0, 0);
		final long changed = MapCacheDatabase.getTileKey(1, 0);
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			final Map<Long, int[]> tiles = new HashMap<>();
			tiles.put(kept, tile(0x123456));
			tiles.put(changed, tile(0x111111));
			database.writeTiles(tiles);
			database.commit();
		}
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.writeTiles(Collections.singletonMap(changed, tile(0xFFEEDD)));
			database.commit();
		}

		final Map<Long, int[]> tiles = readTiles();
		assertEquals(2, tiles.size());
		assertArrayEquals(tile(0x123456), tiles.get(kept));
		assertArrayEquals(tile(0xFFEEDD), tiles.get(changed));
	}

	@Test
	void uncommittedTilesAreKeptOnClose() throws Exception {
		try (final MapCacheDatabase database = new MapCacheDatabase(path())) {
			database.writeTiles(Collections.singletonMap(MapCacheDatabase.getTileKey(2, 3), tile(1)));
		}
		assertTrue(readTiles().containsKey(MapCacheDatabase.getTileKey(2, 3)));
	}

	@Test
	void namesAreKeptWhenTheyAreSafe() {
		assertEquals("saves-新的世界 2", MapCacheDatabase.getSafeName("saves-新的世界 2"));
	}

	@Test
	void unsafeNamesStayDifferent() {
		final String name1 = MapCacheDatabase.getSafeName("minecraft:overworld");
		final String name2 = MapCacheDatabase.getSafeName("minecraft_overworld");

		assertTrue(name1.matches("[^\\\\/:*?\"<>|]+"));
		assertNotEquals(name1, name2);
		assertTrue(MapCacheDatabase.getSafeName("server-mc.example.com:25565").startsWith("server-mc.example.com_25565"));
		assertNotEquals('.', MapCacheDatabase.getSafeName("world.").charAt(MapCacheDatabase.getSafeName("world.").length() - 1));
	}

	@Test
	void tileKeysWorkWithNegativeChunks() {
		for (final int[] chunk : new int[][]{{0, 0}, {-1, -1}, {-1875000, 1875000}, {1875000, -1875000}}) {
			final long key = MapCacheDatabase.getTileKey(chunk[0], chunk[1]);
			assertEquals(chunk[0], MapCacheDatabase.getTileX(key));
			assertEquals(chunk[1], MapCacheDatabase.getTileZ(key));
		}
	}
}
