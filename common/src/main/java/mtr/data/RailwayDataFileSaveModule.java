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
	private int filesWritten;
	private int filesDeleted;
	private long autoSaveStartMillis;
	/**
	 * How long taking everything but the rails at once took in the last save.
	 */
	private long snapshotMillis;
	/**
	 * What was last written for the day tool, so an unchanged world isn't written again.
	 */
	private int lastSimulationHash;
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
	/**
	 * The entries the save going on writes, worked out a little each tick and held until it is done, then written together
	 * with its deletions in one transaction: the saved data goes from one whole save to the next, and a save cut off part
	 * of the way, as by the server being killed, leaves the one before.
	 */
	private final Map<String, PendingWrite> pendingWrites = new LinkedHashMap<>();
	/**
	 * Rails changed since the end of the last tick, saved at once at the end of this one.
	 */
	private final Set<BlockPos> railsToSaveNow = new HashSet<>();
	/**
	 * Objects players edited since the end of the last tick, as {@code type:id}, saved at once at the end of this one.
	 */
	private final Set<String> objectsToSaveNow = new LinkedHashSet<>();

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
		try {
			dataStore.forEachEditRevision(railwayData.editRevisions::restore);
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Minecraft Transit Railway data successfully loaded for " + world.dimension().location());
		if (skippedEntries > 0) {
			System.out.println("Minecraft Transit Railway: " + skippedEntries + " data entries of " + world.dimension().location() + " couldn't be read and are kept as they are; nothing is deleted from the saved data until the server is started again with them readable");
		}
		canAutoSave = true;
		dataLoaded = true;
	}

	public void fullSave() {
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
		pendingWrites.clear();
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
			filesWritten = 0;
			filesDeleted = 0;
			if (skippedEntries == 0) {
				checkFilesToDelete.addAll(existingFiles.keySet());
			}
			// Everything but the rails is taken as it is at this moment, so the save holds them as they were together; the
			// rails, saved at once as they change anyway, are worked out a little each tick
			final long snapshotStartMillis = System.currentTimeMillis();
			railwayData.dataCache.stationIdMap.forEach((id, data) -> stage(data, id, TYPE_STATIONS));
			railwayData.dataCache.platformIdMap.forEach((id, data) -> stage(data, id, TYPE_PLATFORMS));
			railwayData.dataCache.sidingIdMap.forEach((id, data) -> stage(data, id, TYPE_SIDINGS));
			railwayData.dataCache.routeIdMap.forEach((id, data) -> stage(data, id, TYPE_ROUTES));
			railwayData.dataCache.depotIdMap.forEach((id, data) -> stage(data, id, TYPE_DEPOTS));
			railwayData.dataCache.liftsServerIdMap.forEach((id, data) -> stage(data, id, TYPE_LIFTS));
			signalBlocks.signalBlocks.forEach(signalBlock -> stage(signalBlock, signalBlock.id, TYPE_SIGNAL_BLOCKS));
			railwayData.keyNodes.forEach(pos -> stage(new KeyNode(pos), pos.asLong(), TYPE_KEY_NODES));
			railwayData.pathBoundaries.nodes.forEach(node -> stage(new PathBoundaries.Entry(node), node, TYPE_BOUNDARY_NODES));
			railwayData.pathBoundaries.segments.forEach(segment -> stage(new PathBoundaries.Entry(segment), new PathBoundaries.Entry(segment).getId(), TYPE_BOUNDARY_SEGMENTS));
			snapshotMillis = System.currentTimeMillis() - snapshotStartMillis;
			dirtyRailPositions.addAll(rails.keySet());
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

			if (saveInProgress && doneWriting) {
				saveInProgress = false;
				finishSave();
				final Map<Long, Map<BlockPos, TrainDelay>> trainDelays = railwayData.getTrainDelays();
				final List<Long> routeIdsToRemove = new ArrayList<>();
				final List<BlockPos> posToRemove = new ArrayList<>();
				trainDelays.forEach((routeId, trainDelaysForRouteId) -> trainDelaysForRouteId.forEach((pos, trainDelay) -> {
					if (trainDelay.isExpired()) {
						routeIdsToRemove.add(routeId);
						posToRemove.add(pos);
					}
				}));

				if (filesWritten > 0 || filesDeleted > 0) {
					System.out.println("Minecraft Transit Railway save complete for " + world.dimension().location() + " in " + (System.currentTimeMillis() - autoSaveStartMillis) / 1000 + " second(s), taking everything but the rails in " + snapshotMillis + " ms");
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

			return doneWriting;
		} else {
			return true;
		}
	}

	/**
	 * Writes what the save worked out and deletes what is gone, in one transaction (see {@link #pendingWrites}). If it
	 * fails, nothing of it is kept, and the next save writes it all again.
	 */
	private void finishSave() {
		try {
			for (final PendingWrite pendingWrite : pendingWrites.values()) {
				dataStore.write(pendingWrite.type, pendingWrite.id, pendingWrite.data);
			}
			for (final String key : checkFilesToDelete) {
				final int separatorIndex = key.lastIndexOf(KEY_SEPARATOR);
				dataStore.delete(key.substring(0, separatorIndex), Long.parseLong(key.substring(separatorIndex + 1)));
			}
			// What the component's day tool simulates, written again only when it changed
			final byte[] simulationData = SimExport.build(railwayData);
			final int simulationHash = Arrays.hashCode(simulationData);
			if (simulationHash != lastSimulationHash) {
				dataStore.writeSimulationData(simulationData);
			}
			dataStore.commit();
			lastSimulationHash = simulationHash;
			pendingWrites.forEach((key, pendingWrite) -> existingFiles.put(key, pendingWrite.hash));
			checkFilesToDelete.forEach(existingFiles::remove);
			filesWritten = pendingWrites.size();
			filesDeleted = checkFilesToDelete.size();
		} catch (Exception e) {
			System.out.println("Minecraft Transit Railway could not save " + world.dimension().location() + "; the saved data stays as it was after the last save, and the next save tries again");
			e.printStackTrace();
			try {
				dataStore.rollback();
			} catch (Exception e2) {
				e2.printStackTrace();
			}
		}
		pendingWrites.clear();
		checkFilesToDelete.clear();
	}

	/**
	 * Works out an entry of the save going on now, which is then written with the rest of the save.
	 */
	private void stage(SerializedDataBase data, long id, String type) {
		if (data == null) {
			return;
		}
		final String key = writeMessagePackToFile(data, id, type);
		if (key != null) {
			checkFilesToDelete.remove(key);
		}
	}

	/**
	 * Has an object a player edited saved at the end of the tick, rather than with the next save (see {@link #saveNow}).
	 *
	 * @param kind as {@link mtr.packet.EditGuard} names it
	 */
	public void markObjectChanged(String kind, long id) {
		final String type = getType(kind);
		if (canAutoSave && type != null) {
			objectsToSaveNow.add(getKey(type, id));
		}
	}

	private static String getType(String kind) {
		switch (kind) {
			case "station":
				return TYPE_STATIONS;
			case "platform":
				return TYPE_PLATFORMS;
			case "siding":
				return TYPE_SIDINGS;
			case "route":
				return TYPE_ROUTES;
			case "depot":
				return TYPE_DEPOTS;
			default:
				return null;
		}
	}

	private SerializedDataBase getObject(String type, long id) {
		switch (type) {
			case TYPE_STATIONS:
				return railwayData.dataCache.stationIdMap.get(id);
			case TYPE_PLATFORMS:
				return railwayData.dataCache.platformIdMap.get(id);
			case TYPE_SIDINGS:
				return railwayData.dataCache.sidingIdMap.get(id);
			case TYPE_ROUTES:
				return railwayData.dataCache.routeIdMap.get(id);
			case TYPE_DEPOTS:
				return railwayData.dataCache.depotIdMap.get(id);
			default:
				return null;
		}
	}

	/**
	 * Has rails changed by a player saved at the end of the tick, rather than with the next save (see
	 * {@link #saveNow}).
	 */
	public void markRailsChanged(Collection<BlockPos> positions) {
		if (canAutoSave) {
			railsToSaveNow.addAll(positions);
		}
	}

	/**
	 * Saves what changed in this tick at once, in a transaction of its own: the rails laid or taken away, with the
	 * platforms and sidings that came or went with them, and the objects players edited, with the revisions of their
	 * edits, so neither is lost if the server stops before the next save. Called at the end of the tick.
	 */
	public void saveNow() {
		final List<Object[]> revisions = railwayData.editRevisions.takeUnsaved();
		if (railsToSaveNow.isEmpty() && objectsToSaveNow.isEmpty() && revisions.isEmpty() || !canAutoSave) {
			railsToSaveNow.clear();
			objectsToSaveNow.clear();
			return;
		}
		final Map<String, PendingWrite> writes = new LinkedHashMap<>();
		final Set<String> deletes = new LinkedHashSet<>();
		try {
			for (final String key : objectsToSaveNow) {
				final int separatorIndex = key.lastIndexOf(KEY_SEPARATOR);
				final String type = key.substring(0, separatorIndex);
				final long id = Long.parseLong(key.substring(separatorIndex + 1));
				final SerializedDataBase data = getObject(type, id);
				if (data != null) {
					addWrite(writes, data, id, type);
				} else if (existingFiles.containsKey(key)) {
					deletes.add(key);
				}
			}
			for (final BlockPos pos : railsToSaveNow) {
				final Map<BlockPos, Rail> connections = rails.get(pos);
				final String key = getKey(TYPE_RAILS, pos.asLong());
				if (connections != null && !connections.isEmpty()) {
					addWrite(writes, new RailEntry(pos, connections), pos.asLong(), TYPE_RAILS);
				} else if (existingFiles.containsKey(key)) {
					deletes.add(key);
				}
			}
			// A platform or siding is made or taken away with its rail
			railwayData.platforms.forEach(platform -> {
				if (!existingFiles.containsKey(getKey(TYPE_PLATFORMS, platform.id))) {
					addWrite(writes, platform, platform.id, TYPE_PLATFORMS);
				}
			});
			railwayData.sidings.forEach(siding -> {
				if (!existingFiles.containsKey(getKey(TYPE_SIDINGS, siding.id))) {
					addWrite(writes, siding, siding.id, TYPE_SIDINGS);
				}
			});
			existingFiles.keySet().forEach(key -> {
				final int separatorIndex = key.lastIndexOf(KEY_SEPARATOR);
				final String type = key.substring(0, separatorIndex);
				final long id = Long.parseLong(key.substring(separatorIndex + 1));
				if (type.equals(TYPE_PLATFORMS) && !railwayData.dataCache.platformIdMap.containsKey(id) && railwayData.platforms.stream().noneMatch(platform -> platform.id == id) || type.equals(TYPE_SIDINGS) && !railwayData.dataCache.sidingIdMap.containsKey(id) && railwayData.sidings.stream().noneMatch(siding -> siding.id == id)) {
					deletes.add(key);
				}
			});
			if (skippedEntries > 0) {
				// Nothing is deleted while entries that couldn't be read are kept
				deletes.clear();
			}
			for (final PendingWrite write : writes.values()) {
				dataStore.write(write.type, write.id, write.data);
			}
			for (final String key : deletes) {
				final int separatorIndex = key.lastIndexOf(KEY_SEPARATOR);
				dataStore.delete(key.substring(0, separatorIndex), Long.parseLong(key.substring(separatorIndex + 1)));
			}
			for (final Object[] revision : revisions) {
				dataStore.writeEditRevision((String) revision[0], (Integer) revision[1], (String) revision[2], (Boolean) revision[3]);
			}
			dataStore.commit();
			writes.forEach((key, write) -> existingFiles.put(key, write.hash));
			deletes.forEach(existingFiles::remove);
			// What the save going on worked out earlier for these is out of date now
			writes.keySet().forEach(key -> {
				pendingWrites.remove(key);
				checkFilesToDelete.remove(key);
			});
			deletes.forEach(pendingWrites::remove);
		} catch (Exception e) {
			System.out.println("Minecraft Transit Railway could not save what was just changed in " + world.dimension().location() + "; it is saved with the next save");
			e.printStackTrace();
			railwayData.editRevisions.keepUnsaved(revisions);
			try {
				dataStore.rollback();
			} catch (Exception e2) {
				e2.printStackTrace();
			}
		}
		railsToSaveNow.clear();
		objectsToSaveNow.clear();
	}

	private void addWrite(Map<String, PendingWrite> writes, SerializedDataBase data, long id, String type) {
		final byte[] bytes = toBytes(data);
		if (bytes != null) {
			writes.put(getKey(type, id), new PendingWrite(type, id, bytes));
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

					existingFiles.put(getKey(type, id), Arrays.hashCode(bytes));
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
		final byte[] bytes = toBytes(data);
		if (bytes == null) {
			return null;
		}
		final String key = getKey(type, id);
		final PendingWrite pendingWrite = new PendingWrite(type, id, bytes);
		// Everything an entry saves counts, so a train's position or a depot's last service sent out are saved each time
		if (!existingFiles.containsKey(key) || pendingWrite.hash != existingFiles.get(key)) {
			pendingWrites.put(key, pendingWrite);
		}
		return key;
	}

	/**
	 * @return the data as it is saved, or null if it can't be written
	 */
	public static byte[] toBytes(SerializedDataBase data) {
		try {
			final MessageBufferPacker messageBufferPacker = MessagePack.newDefaultBufferPacker();
			messageBufferPacker.packMapHeader(data.messagePackLength());
			data.toMessagePack(messageBufferPacker);
			messageBufferPacker.close();
			return messageBufferPacker.toByteArray();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static final class PendingWrite {

		private final String type;
		private final long id;
		private final byte[] data;
		private final int hash;

		private PendingWrite(String type, long id, byte[] data) {
			this.type = type;
			this.id = id;
			this.data = data;
			hash = Arrays.hashCode(data);
		}
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
