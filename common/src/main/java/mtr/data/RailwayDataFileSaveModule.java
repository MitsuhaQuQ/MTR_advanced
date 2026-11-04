package mtr.data;

import mtr.advanced.PathBoundaries;
import mtr.advanced.SimExport;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class RailwayDataFileSaveModule extends RailwayDataModuleBase {

	private boolean canAutoSave = false;
	private boolean dataLoaded = false;
	/**
	 * Whether loading started; if it didn't finish, nothing is saved, so a failed load can't write over the data.
	 */
	private boolean loadStarted = false;
	/**
	 * Entries that couldn't be read. With any, nothing is deleted while the server runs: what can't be read stays in the
	 * database, and what depended on it (such as a siding on a rail that couldn't be read) isn't deleted with it either.
	 */
	private int skippedEntries;
	private boolean useReducedHash = true;
	private int filesWritten;
	private int filesDeleted;
	private long autoSaveStartMillis;
	/**
	 * What was last written for the day tool, so an unchanged world isn't written again.
	 */
	private int lastSimulationHash;
	/**
	 * Whether the save going on has written it.
	 */
	private boolean simulationDataWritten = true;
	/**
	 * Whether a save is going on, writing and then deleting a little each tick.
	 */
	private boolean saveInProgress;

	private final SignalBlocks signalBlocks;

	private final List<Long> dirtyStationIds = new ArrayList<>();
	private final List<Long> dirtyPlatformIds = new ArrayList<>();
	private final List<Long> dirtySidingIds = new ArrayList<>();
	private final List<Long> dirtyRouteIds = new ArrayList<>();
	private final List<Long> dirtyDepotIds = new ArrayList<>();
	private final List<Long> dirtyLiftIds = new ArrayList<>();
	private final List<BlockPos> dirtyRailPositions = new ArrayList<>();
	private final List<SignalBlocks.SignalBlock> dirtySignalBlocks = new ArrayList<>();
	private final List<BlockPos> dirtyKeyNodes = new ArrayList<>();
	private final List<Long> dirtyBoundaryNodes = new ArrayList<>();
	private final List<UUID> dirtyBoundarySegments = new ArrayList<>();

	private final Map<String, Integer> existingFiles = new HashMap<>();
	private final Set<String> checkFilesToDelete = new LinkedHashSet<>();

	private final RailwayDataStore dataStore;

	private static final char KEY_SEPARATOR = ':';
	private static final String TYPE_STATIONS = "stations";
	private static final String TYPE_PLATFORMS = "platforms";
	private static final String TYPE_SIDINGS = "sidings";
	private static final String TYPE_ROUTES = "routes";
	private static final String TYPE_DEPOTS = "depots";
	private static final String TYPE_LIFTS = "lifts";
	private static final String TYPE_RAILS = "rails";
	private static final String TYPE_SIGNAL_BLOCKS = "signal-blocks";
	private static final String TYPE_KEY_NODES = "key-nodes";
	private static final String TYPE_BOUNDARY_NODES = "boundary-nodes";
	private static final String TYPE_BOUNDARY_SEGMENTS = "boundary-segments";
	private static final String[] TYPES = {TYPE_STATIONS, TYPE_PLATFORMS, TYPE_SIDINGS, TYPE_ROUTES, TYPE_DEPOTS, TYPE_LIFTS, TYPE_RAILS, TYPE_SIGNAL_BLOCKS, TYPE_KEY_NODES, TYPE_BOUNDARY_NODES, TYPE_BOUNDARY_SEGMENTS};

	public RailwayDataFileSaveModule(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, Path savePath, SignalBlocks signalBlocks) {
		super(railwayData, world, rails);
		this.signalBlocks = signalBlocks;

		try {
			Files.createDirectories(savePath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		dataStore = RailwayDataFileStore.open(savePath, TYPES);
	}

	public void load() {
		loadStarted = true;
		skippedEntries = 0;
		existingFiles.clear();
		readMessagePackFromFile(TYPE_STATIONS, Station::new, railwayData.stations::add, false);
		readMessagePackFromFile(TYPE_PLATFORMS, Platform::new, railwayData.platforms::add, true);
		readMessagePackFromFile(TYPE_SIDINGS, Siding::new, railwayData.sidings::add, true);
		readMessagePackFromFile(TYPE_ROUTES, Route::new, railwayData.routes::add, false);
		readMessagePackFromFile(TYPE_DEPOTS, Depot::new, railwayData.depots::add, false);
		readMessagePackFromFile(TYPE_LIFTS, LiftServer::new, railwayData.lifts::add, true);
		readMessagePackFromFile(TYPE_RAILS, RailEntry::new, railEntry -> rails.put(railEntry.pos, railEntry.connections), true);
		readMessagePackFromFile(TYPE_SIGNAL_BLOCKS, SignalBlocks.SignalBlock::new, signalBlocks.signalBlocks::add, true);
		readMessagePackFromFile(TYPE_KEY_NODES, KeyNode::new, keyNode -> railwayData.keyNodes.add(keyNode.pos), true);
		readMessagePackFromFile(TYPE_BOUNDARY_NODES, PathBoundaries.Entry::new, entry -> railwayData.pathBoundaries.nodes.add(entry.node), true);
		readMessagePackFromFile(TYPE_BOUNDARY_SEGMENTS, PathBoundaries.Entry::new, entry -> railwayData.pathBoundaries.segments.add(entry.segment), true);

		railwayData.graphRecords.load(dataStore);

		System.out.println("Minecraft Transit Railway data successfully loaded for " + world.dimension().location());
		if (skippedEntries > 0) {
			System.out.println("Minecraft Transit Railway: " + skippedEntries + " data entries of " + world.dimension().location() + " couldn't be read and are kept as they are; nothing is deleted from the saved data until the server is started again with them readable");
		}
		canAutoSave = true;
		dataLoaded = true;
	}

	public void fullSave() {
		useReducedHash = false;
		dirtyStationIds.clear();
		dirtyPlatformIds.clear();
		dirtySidingIds.clear();
		dirtyRouteIds.clear();
		dirtyDepotIds.clear();
		dirtyLiftIds.clear();
		dirtyRailPositions.clear();
		dirtySignalBlocks.clear();
		dirtyKeyNodes.clear();
		dirtyBoundaryNodes.clear();
		dirtyBoundarySegments.clear();
		checkFilesToDelete.clear();
		saveInProgress = false;
		autoSave();
		while (true) {
			if (autoSaveTick()) {
				break;
			}
		}
		canAutoSave = false;
		try {
			dataStore.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void autoSave() {
		if (!dataLoaded) {
			if (loadStarted) {
				// Loading failed part of the way, so what is in memory isn't the saved data
				System.out.println("Minecraft Transit Railway data of " + world.dimension().location() + " failed to load; it isn't saved, so the saved data stays as it was");
				return;
			}
			// A world with no saved data yet
			dataLoaded = true;
			canAutoSave = true;
		}

		if (canAutoSave && !saveInProgress) {
			saveInProgress = true;
			autoSaveStartMillis = System.currentTimeMillis();
			simulationDataWritten = false;
			filesWritten = 0;
			filesDeleted = 0;
			dirtyStationIds.addAll(railwayData.dataCache.stationIdMap.keySet());
			dirtyPlatformIds.addAll(railwayData.dataCache.platformIdMap.keySet());
			dirtySidingIds.addAll(railwayData.dataCache.sidingIdMap.keySet());
			dirtyRouteIds.addAll(railwayData.dataCache.routeIdMap.keySet());
			dirtyDepotIds.addAll(railwayData.dataCache.depotIdMap.keySet());
			dirtyLiftIds.addAll(railwayData.dataCache.liftsServerIdMap.keySet());
			dirtyRailPositions.addAll(rails.keySet());
			dirtySignalBlocks.addAll(signalBlocks.signalBlocks);
			dirtyKeyNodes.addAll(railwayData.keyNodes);
			dirtyBoundaryNodes.addAll(railwayData.pathBoundaries.nodes);
			dirtyBoundarySegments.addAll(railwayData.pathBoundaries.segments);
			if (skippedEntries == 0) {
				checkFilesToDelete.addAll(existingFiles.keySet());
			}
			dataStore.backup();
		}
	}

	public boolean autoSaveTick() {
		if (canAutoSave) {

			boolean hasSpareTime = writeDirtyDataToFile(dirtyStationIds, railwayData.dataCache.stationIdMap::get, id -> id, TYPE_STATIONS);
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyPlatformIds, railwayData.dataCache.platformIdMap::get, id -> id, TYPE_PLATFORMS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtySidingIds, railwayData.dataCache.sidingIdMap::get, id -> id, TYPE_SIDINGS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyRouteIds, railwayData.dataCache.routeIdMap::get, id -> id, TYPE_ROUTES);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyDepotIds, railwayData.dataCache.depotIdMap::get, id -> id, TYPE_DEPOTS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyLiftIds, railwayData.dataCache.liftsServerIdMap::get, id -> id, TYPE_LIFTS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyRailPositions, pos -> rails.containsKey(pos) ? new RailEntry(pos, rails.get(pos)) : null, BlockPos::asLong, TYPE_RAILS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtySignalBlocks, signalBlock -> signalBlock, signalBlock -> signalBlock.id, TYPE_SIGNAL_BLOCKS);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyKeyNodes, pos -> railwayData.keyNodes.contains(pos) ? new KeyNode(pos) : null, BlockPos::asLong, TYPE_KEY_NODES);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyBoundaryNodes, pos -> railwayData.pathBoundaries.nodes.contains(pos) ? new PathBoundaries.Entry(pos) : null, pos -> pos, TYPE_BOUNDARY_NODES);
			}
			if (hasSpareTime) {
				hasSpareTime = writeDirtyDataToFile(dirtyBoundarySegments, segment -> railwayData.pathBoundaries.segments.contains(segment) ? new PathBoundaries.Entry(segment) : null, segment -> new PathBoundaries.Entry(segment).getId(), TYPE_BOUNDARY_SEGMENTS);
			}

			final boolean doneWriting = dirtyStationIds.isEmpty() && dirtyPlatformIds.isEmpty() && dirtySidingIds.isEmpty() && dirtyRouteIds.isEmpty() && dirtyDepotIds.isEmpty() && dirtyLiftIds.isEmpty() && dirtyRailPositions.isEmpty() && dirtySignalBlocks.isEmpty() && dirtyKeyNodes.isEmpty() && dirtyBoundaryNodes.isEmpty() && dirtyBoundarySegments.isEmpty();
			if (hasSpareTime && !checkFilesToDelete.isEmpty() && doneWriting) {
				final String key = checkFilesToDelete.iterator().next();
				checkFilesToDelete.remove(key);
				try {
					final int separatorIndex = key.lastIndexOf(KEY_SEPARATOR);
					dataStore.delete(key.substring(0, separatorIndex), Long.parseLong(key.substring(separatorIndex + 1)));
				} catch (Exception e) {
					e.printStackTrace();
				}
				existingFiles.remove(key);
				filesDeleted++;
			}

			if (doneWriting && checkFilesToDelete.isEmpty() && !simulationDataWritten) {
				// What the component's day tool simulates, once a save, written again only when it changed
				simulationDataWritten = true;
				try {
					final byte[] simulationData = SimExport.build(railwayData);
					final int simulationHash = Arrays.hashCode(simulationData);
					if (simulationHash != lastSimulationHash) {
						dataStore.writeSimulationData(simulationData);
						lastSimulationHash = simulationHash;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (saveInProgress && doneWriting && checkFilesToDelete.isEmpty()) {
				saveInProgress = false;
				final Map<Long, Map<BlockPos, TrainDelay>> trainDelays = railwayData.getTrainDelays();
				final List<Long> routeIdsToRemove = new ArrayList<>();
				final List<BlockPos> posToRemove = new ArrayList<>();
				trainDelays.forEach((routeId, trainDelaysForRouteId) -> trainDelaysForRouteId.forEach((pos, trainDelay) -> {
					if (trainDelay.isExpired()) {
						routeIdsToRemove.add(routeId);
						posToRemove.add(pos);
					}
				}));

				if (!useReducedHash || filesWritten > 0 || filesDeleted > 0) {
					System.out.println("Minecraft Transit Railway save complete for " + world.dimension().location() + " in " + (System.currentTimeMillis() - autoSaveStartMillis) / 1000 + " second(s)");
					if (filesWritten > 0) {
						System.out.println("- Changed: " + filesWritten);
					}
					if (filesDeleted > 0) {
						System.out.println("- Deleted: " + filesDeleted);
					}
					if (!routeIdsToRemove.isEmpty()) {
						System.out.println("- Delays Cleared: " + routeIdsToRemove.size());
					}
				}

				for (int i = 0; i < routeIdsToRemove.size(); i++) {
					final long routeId = routeIdsToRemove.get(i);
					trainDelays.get(routeId).remove(posToRemove.get(i));
					if (trainDelays.get(routeId).isEmpty()) {
						trainDelays.remove(routeId);
					}
				}
			}

			try {
				railwayData.graphRecords.flush(dataStore);
			} catch (Exception e) {
				e.printStackTrace();
			}

			try {
				dataStore.commit();
			} catch (Exception e) {
				e.printStackTrace();
			}

			return doneWriting && checkFilesToDelete.isEmpty();
		} else {
			return true;
		}
	}

	private <T extends SerializedDataBase> void readMessagePackFromFile(String type, Function<Map<String, Value>, T> getData, Consumer<T> callback, boolean skipVerify) {
		try {
			dataStore.forEach(type, (id, bytes) -> {
				try (final MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(bytes)) {
					final int size = messageUnpacker.unpackMapHeader();
					final HashMap<String, Value> result = new HashMap<>(size);

					for (int i = 0; i < size; i++) {
						result.put(messageUnpacker.unpackString(), messageUnpacker.unpackValue());
					}

					final T data = getData.apply(result);
					if (skipVerify || !(data instanceof NameColorDataBase) || !((NameColorDataBase) data).name.isEmpty()) {
						callback.accept(data);
					}

					existingFiles.put(getKey(type, id), getHash(data, true));
				} catch (Exception e) {
					System.out.println("Skipping invalid Minecraft Transit Railway data " + getKey(type, id));
					e.printStackTrace();
					skippedEntries++;
				}
			});
		} catch (Exception e) {
			throw new IllegalStateException("Could not read Minecraft Transit Railway data: " + type, e);
		}
	}

	private String writeMessagePackToFile(SerializedDataBase data, long id, String type) {
		try {
			final String key = getKey(type, id);
			final int hash = getHash(data, useReducedHash);

			if (!existingFiles.containsKey(key) || hash != existingFiles.get(key)) {
				final MessageBufferPacker messageBufferPacker = MessagePack.newDefaultBufferPacker();
				messageBufferPacker.packMapHeader(data.messagePackLength());
				data.toMessagePack(messageBufferPacker);
				messageBufferPacker.close();
				dataStore.write(type, id, messageBufferPacker.toByteArray());

				existingFiles.put(key, hash);
				filesWritten++;
			}

			return key;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private <T extends SerializedDataBase, U> boolean writeDirtyDataToFile(List<U> dirtyData, Function<U, T> getId, Function<U, Long> idToLong, String type) {
		final long millis = System.currentTimeMillis();
		while (!dirtyData.isEmpty()) {
			final U id = dirtyData.remove(dirtyData.size() - 1);
			final T data = getId.apply(id);
			if (data != null) {
				final String key = writeMessagePackToFile(data, idToLong.apply(id), type);
				if (key != null) {
					checkFilesToDelete.remove(key);
				}
			}
			if (System.currentTimeMillis() - millis >= 2) {
				return false;
			}
		}
		return true;
	}

	private static String getKey(String type, long id) {
		return type + KEY_SEPARATOR + id;
	}

	private static int getHash(SerializedDataBase data, boolean useReducedHash) {
		try {
			final MessageBufferPacker messageBufferPacker = MessagePack.newDefaultBufferPacker();

			if (useReducedHash && data instanceof IReducedSaveData) {
				messageBufferPacker.packMapHeader(((IReducedSaveData) data).reducedMessagePackLength());
				((IReducedSaveData) data).toReducedMessagePack(messageBufferPacker);
			} else {
				messageBufferPacker.packMapHeader(data.messagePackLength());
				data.toMessagePack(messageBufferPacker);
			}

			final int hash = Arrays.hashCode(messageBufferPacker.toByteArray());
			messageBufferPacker.close();

			return hash;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}

	private static class RailEntry extends SerializedDataBase {

		public final BlockPos pos;
		public final Map<BlockPos, Rail> connections;

		private static final String KEY_NODE_POS = "node_pos";
		private static final String KEY_RAIL_CONNECTIONS = "rail_connections";

		public RailEntry(BlockPos pos, Map<BlockPos, Rail> connections) {
			this.pos = pos;
			this.connections = connections;
		}

		public RailEntry(Map<String, Value> map) {
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			pos = BlockPos.of(messagePackHelper.getLong(KEY_NODE_POS));
			connections = new HashMap<>();
			messagePackHelper.iterateArrayValue(KEY_RAIL_CONNECTIONS, value -> {
				final Map<String, Value> mapSK = RailwayData.castMessagePackValueToSKMap(value);
				connections.put(BlockPos.of(new MessagePackHelper(mapSK).getLong(KEY_NODE_POS)), new Rail(mapSK));
			});
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			messagePacker.packString(KEY_NODE_POS).packLong(pos.asLong());

			messagePacker.packString(KEY_RAIL_CONNECTIONS).packArrayHeader(connections.size());
			for (final Map.Entry<BlockPos, Rail> entry : connections.entrySet()) {
				final BlockPos endNodePos = entry.getKey();
				messagePacker.packMapHeader(entry.getValue().messagePackLength() + 1);
				messagePacker.packString(KEY_NODE_POS).packLong(endNodePos.asLong());
				entry.getValue().toMessagePack(messagePacker);
			}
		}

		@Override
		public int messagePackLength() {
			return 2;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
		}
	}
}
