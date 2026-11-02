package mtr.client;

import mtr.compat.Compat;
import mtr.data.RailwayData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colours of chunks seen so far, so the dashboard map isn't limited to the chunks the client has loaded. Opening the
 * dashboard reads the loaded chunks a few at a time, and any that changed replace what is stored.
 */
public final class MapTerrainCache {

	private static final Map<Long, int[]> TILES = new ConcurrentHashMap<>();
	private static final Deque<Long> SCAN_QUEUE = new ArrayDeque<>();
	private static final int SCAN_RADIUS = 32;
	private static final int CHUNKS_PER_FRAME = 16;

	private MapTerrainCache() {
	}

	public static void clear() {
		TILES.clear();
		SCAN_QUEUE.clear();
	}

	/**
	 * A tile from the file. A chunk scanned while the file was being read is newer, so it stays.
	 */
	public static void addSavedTile(long key, int[] colors) {
		TILES.putIfAbsent(key, colors);
	}

	public static void startScan(int centerChunkX, int centerChunkZ) {
		SCAN_QUEUE.clear();
		SCAN_QUEUE.add(MapCacheDatabase.getTileKey(centerChunkX, centerChunkZ));
		for (int radius = 1; radius <= SCAN_RADIUS; radius++) {
			for (int i = -radius; i < radius; i++) {
				SCAN_QUEUE.add(MapCacheDatabase.getTileKey(centerChunkX + i, centerChunkZ - radius));
				SCAN_QUEUE.add(MapCacheDatabase.getTileKey(centerChunkX + radius, centerChunkZ + i));
				SCAN_QUEUE.add(MapCacheDatabase.getTileKey(centerChunkX - i, centerChunkZ + radius));
				SCAN_QUEUE.add(MapCacheDatabase.getTileKey(centerChunkX - radius, centerChunkZ - i));
			}
		}
	}

	/**
	 * Called each frame the map is drawn. Chunks that aren't loaded are skipped and cost next to nothing.
	 */
	public static void scan(Level world) {
		final Map<Long, int[]> changedTiles = new HashMap<>();
		int chunksRead = 0;
		while (!SCAN_QUEUE.isEmpty() && chunksRead < CHUNKS_PER_FRAME) {
			final long key = SCAN_QUEUE.poll();
			final int chunkX = MapCacheDatabase.getTileX(key);
			final int chunkZ = MapCacheDatabase.getTileZ(key);
			if (isLoaded(world, chunkX, chunkZ)) {
				chunksRead++;
				final int[] colors = new int[MapCacheDatabase.TILE_COLORS];
				for (int z = 0; z < MapCacheDatabase.TILE_SIZE; z++) {
					for (int x = 0; x < MapCacheDatabase.TILE_SIZE; x++) {
						colors[z * MapCacheDatabase.TILE_SIZE + x] = readColor(world, chunkX * MapCacheDatabase.TILE_SIZE + x, chunkZ * MapCacheDatabase.TILE_SIZE + z);
					}
				}
				if (!Arrays.equals(colors, TILES.get(key))) {
					TILES.put(key, colors);
					changedTiles.put(key, colors);
				}
			}
		}
		if (!changedTiles.isEmpty()) {
			MapDataCache.writeTiles(changedTiles);
		}
	}

	/**
	 * @return the colour of a block column, from the world if its chunk is loaded and otherwise as last seen, or 0 if never seen
	 */
	public static int getColor(Level world, int x, int z) {
		final int chunkX = x >> 4;
		final int chunkZ = z >> 4;
		if (isLoaded(world, chunkX, chunkZ)) {
			return readColor(world, x, z);
		}
		final int[] colors = TILES.get(MapCacheDatabase.getTileKey(chunkX, chunkZ));
		return colors == null ? 0 : colors[(z & (MapCacheDatabase.TILE_SIZE - 1)) * MapCacheDatabase.TILE_SIZE + (x & (MapCacheDatabase.TILE_SIZE - 1))];
	}

	/**
	 * {@link Level#hasChunk(int, int)} is always true on the client, so it can't be used here.
	 */
	private static boolean isLoaded(Level world, int chunkX, int chunkZ) {
		return world.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
	}

	private static int readColor(Level world, int x, int z) {
		return Compat.getColor(world.getBlockState(RailwayData.newBlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z)).getBlock());
	}
}
