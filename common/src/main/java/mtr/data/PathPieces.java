package mtr.data;

import mtr.path.PathData;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/**
 * Paths saved as references to shared pieces (optimisation round 5, step 2). A path is cut after each stop into pieces;
 * each piece is stored once in the database, keyed by a hash of its content, and a path saves only the keys of its pieces
 * with the stop number each starts from. The ~20 paths of a siding, and the sidings of a depot, run along the same
 * stretches between stops, so most of their pieces are shared. A piece numbers its stops from 0, so the same stretch
 * is the same piece wherever along a path it comes.
 * <p>
 * Who refers to which piece is kept in a table of its own ({@code path_piece_refs}): pieces no longer referred to are
 * swept in the same transaction that drops their last reference.
 */
public final class PathPieces {

	/**
	 * Written in place of the path itself: the key of each piece and the stop number it starts from, in turn.
	 */
	public static final String KEY_PATH_PIECES = "path_pieces";
	private static final String KEY_VERSION = "v";
	private static final String KEY_RAILS = "rails";
	private static final int VERSION = 1;
	/**
	 * A piece holds at most this many rails, so a long run without stops is cut too.
	 */
	public static final int MAX_PIECE_RAILS = 256;

	private static final ThreadLocal<Writer> WRITING = new ThreadLocal<>();
	private static final ThreadLocal<Reader> READING = new ThreadLocal<>();
	/**
	 * Paths cut where they were made, as on a depot's path generation thread, until a save writes them. A list's hash and
	 * equality go by its elements, which don't override them, so this goes by the path itself; a path let go is forgotten.
	 */
	private static final Map<List<PathData>, Cut> PRECUT = Collections.synchronizedMap(new WeakHashMap<>());

	private PathPieces() {
	}

	private static String signature(List<PathData> rails) {
		try { return Base64.getEncoder().encodeToString(encode(rails, 0, rails.size(), 0)); }
		catch (IOException e) { throw new IllegalStateException(e); }
	}

	private static PathData withStopOffset(PathData rail, int offset) {
		final PathData copy = rail.copy(rail.savedRailBaseId, rail.dwellTime);
		copy.stopIndex = rail.stopIndex + offset;
		return copy;
	}

	/** Segment boundaries identify single trips and connections; repeated geometry is stored once. */
	public static List<PathData> indexDuty(List<PathData> path, int[] starts) {
		return indexDuty(path, starts, -1);
	}

	public static List<PathData> indexDuty(List<PathData> path, int[] starts, int firstServiceRail) {
		if (path instanceof transitsim.plan.IndexedPath || path.isEmpty()) return path;
		final SortedSet<Integer> boundaries = new TreeSet<>();
		boundaries.add(0); boundaries.add(path.size());
		if (starts != null) for (int start : starts) if (start > 0 && start < path.size()) boundaries.add(start);
		final List<Integer> cuts = new ArrayList<>(boundaries);
		final Map<String, List<PathData>> templates = new HashMap<>();
		final List<transitsim.plan.IndexedPath.Segment<PathData>> parts = new ArrayList<>();
		try {
			int numbered = 0; long previousPlatform = 0;
			for (int i = 1; i < cuts.size(); i++) {
				final int from = cuts.get(i - 1), to = cuts.get(i);
				final int stopBase = firstServiceRail < 0 ? path.get(from).stopIndex : numbered;
				final List<PathData> local = new ArrayList<>();
				for (int j = from; j < to; j++) {
					PathData rail = path.get(j);
					PathData copy = rail.copy(rail.savedRailBaseId, rail.dwellTime);
					if (firstServiceRail >= 0) {
						long platform = j >= firstServiceRail && rail.isPlatformTrack() ? rail.savedRailBaseId : 0;
						if (platform != 0 && platform != previousPlatform) numbered++;
						copy.stopIndex = numbered;
						previousPlatform = platform;
					}
					local.add(copy);
				}
				final byte[] bytes = encode(local, 0, local.size(), stopBase);
				final String key = Base64.getEncoder().encodeToString(bytes);
				List<PathData> template = templates.get(key);
				if (template == null) { template = decode(bytes); templates.put(key, template); }
				parts.add(new transitsim.plan.IndexedPath.Segment<>(template, stopBase));
			}
			return new transitsim.plan.IndexedPath<>(parts, PathPieces::withStopOffset, PathPieces::signature);
		} catch (IOException e) { throw new IllegalStateException("Cannot index duty path", e); }
	}

	/**
	 * Writes a path under {@code key}, or as references to its pieces while a {@link Writer} is set for this thread.
	 */
	public static void writePath(MessagePacker messagePacker, List<PathData> path, String key) throws IOException {
		final Writer writer = WRITING.get();
		if (writer == null) {
			RailwayData.writeMessagePackDataset(messagePacker, path, key);
		} else {
			final long[] references = writer.add(path);
			messagePacker.packString(KEY_PATH_PIECES).packArrayHeader(references.length);
			for (final long value : references) {
				messagePacker.packLong(value);
			}
		}
	}

	/**
	 * @return the path saved under {@code key}, or put together from its pieces; null if a piece it refers to isn't there
	 */
	public static List<PathData> readPath(Map<String, Value> map, String key) {
		final Value references = map.get(KEY_PATH_PIECES);
		final List<PathData> path = new ArrayList<>();
		if (references == null) {
			new MessagePackHelper(map).iterateArrayValue(key, value -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
			return path;
		}
		final Reader reader = READING.get();
		if (reader == null) {
			return null;
		}
		final List<Value> values = references.asArrayValue().list();
		final long[] read = new long[values.size() - values.size() % 2];
		final List<transitsim.plan.IndexedPath.Segment<PathData>> segments = new ArrayList<>();
		for (int i = 0; i + 1 < values.size(); i += 2) {
			read[i] = values.get(i).asIntegerValue().toLong();
			read[i + 1] = values.get(i + 1).asIntegerValue().toLong();
			final List<PathData> piece = reader.get(read[i]);
			if (piece == null) return null;
			if (!piece.isEmpty()) segments.add(new transitsim.plan.IndexedPath.Segment<>(piece, (int) read[i + 1]));
		}
		final List<PathData> indexed = new transitsim.plan.IndexedPath<>(segments, PathPieces::withStopOffset, PathPieces::signature);
		reader.read.put(indexed, read);
		return indexed;
	}

	/**
	 * Has the path a host keeps (as a trip path's own copy) written with the pieces {@code read} was put together from,
	 * so the first save after loading doesn't cut it again; nothing for a path read in full or with no reader set.
	 */
	public static void remember(List<PathData> read, List<PathData> kept) {
		final Reader reader = READING.get();
		final long[] references = reader == null ? null : reader.read.remove(read);
		if (references != null && read.size() == kept.size()) {
			reader.remembered.put(kept, references);
		}
	}

	/**
	 * Cuts the paths of these trip paths into pieces now, on this thread, for the next save to take.
	 */
	public static void precut(Collection<? extends transitsim.plan.TripPath<PathData>> tripPaths) {
		for (final transitsim.plan.TripPath<PathData> tripPath : tripPaths) {
			try {
				if (!(tripPath.path instanceof transitsim.plan.IndexedPath)) PRECUT.put(tripPath.path, new Cut(tripPath.path));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Has paths written by this thread written as references, collected by {@code writer}, until {@link #stopWriting}.
	 */
	public static void startWriting(Writer writer) {
		WRITING.set(writer);
	}

	public static void stopWriting() {
		WRITING.remove();
	}

	public static void startReading(Reader reader) {
		READING.set(reader);
	}

	public static void stopReading() {
		READING.remove();
	}

	/**
	 * Cuts a path into pieces: after each stop, and after {@link #MAX_PIECE_RAILS} rails.
	 *
	 * @return the first rail of each piece, and the size of the path at the end
	 */
	static List<Integer> cuts(List<PathData> path) {
		final List<Integer> cuts = new ArrayList<>();
		cuts.add(0);
		int start = 0;
		for (int i = 0; i < path.size(); i++) {
			if (i + 1 < path.size() && (path.get(i).dwellTime > 0 || i + 1 - start >= MAX_PIECE_RAILS)) {
				start = i + 1;
				cuts.add(start);
			}
		}
		if (!path.isEmpty()) {
			cuts.add(path.size());
		}
		return cuts;
	}

	static byte[] encode(List<PathData> path, int from, int to, int stopBase) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(2);
		packer.packString(KEY_VERSION).packInt(VERSION);
		packer.packString(KEY_RAILS).packArrayHeader(to - from);
		for (int i = from; i < to; i++) {
			final PathData pathData = path.get(i);
			final PathData relative = pathData.copy(pathData.savedRailBaseId, pathData.dwellTime);
			relative.stopIndex = pathData.stopIndex - stopBase;
			packer.packMapHeader(relative.messagePackLength());
			relative.toMessagePack(packer);
		}
		packer.close();
		return packer.toByteArray();
	}

	static List<PathData> decode(byte[] bytes) throws IOException {
		try (final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
			final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue());
			if (new MessagePackHelper(map).getInt(KEY_VERSION, 0) != VERSION) {
				return null;
			}
			final List<PathData> piece = new ArrayList<>();
			new MessagePackHelper(map).iterateArrayValue(KEY_RAILS, value -> piece.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
			return piece;
		}
	}

	/**
	 * @return the key of a piece: the first 8 bytes of the SHA-256 of its content
	 */
	static long keyOf(byte[] bytes) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			long key = 0;
			for (int i = 0; i < 8; i++) {
				key = key << 8 | digest[i] & 0xFF;
			}
			return key;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The pieces stored, read once each while the data is loaded.
	 */
	public static final class Reader {

		private final Map<Long, byte[]> stored;
		private final Map<Long, List<PathData>> decoded = new HashMap<>();
		private final Map<List<PathData>, long[]> read = new IdentityHashMap<>();
		private final Map<List<PathData>, long[]> remembered = new IdentityHashMap<>();

		public Reader(Map<Long, byte[]> stored) {
			this.stored = stored;
		}

		/**
		 * Gives the writer the pieces of the paths read, by the path kept ({@link #remember}).
		 */
		public void seed(Writer writer) {
			remembered.forEach((path, references) -> writer.cutPaths.put(path, new Cut(path.size(), references)));
			remembered.clear();
			read.clear();
		}

		private List<PathData> get(long key) {
			return decoded.computeIfAbsent(key, k -> {
				final byte[] bytes = stored.get(k);
				try {
					return bytes == null ? null : decode(bytes);
				} catch (Exception e) {
					e.printStackTrace();
					return null;
				}
			});
		}
	}

	/**
	 * Cuts the paths written into pieces, remembering those of each path so a path written again with each save is cut
	 * only once, and collects the pieces not stored yet and those each owner refers to.
	 */
	public static final class Writer {

		/**
		 * The references of the paths of the last write, by the path itself (not its content).
		 */
		private Map<List<PathData>, Cut> cutPaths = new IdentityHashMap<>();
		private Map<List<PathData>, Cut> cutPathsNow = new IdentityHashMap<>();
		private Set<Long> ownerPieces;
		private Map<Long, byte[]> newPieces;
		private final Set<Long> stored;

		/**
		 * @param stored the keys of the pieces in the database, as {@link PathPieces.Store} keeps them
		 */
		public Writer(Set<Long> stored) {
			this.stored = stored;
		}

		/**
		 * Starts collecting what an owner's paths refer to.
		 */
		public void startOwner() {
			ownerPieces = new HashSet<>();
			newPieces = new HashMap<>();
		}

		/**
		 * @return the pieces the owner's paths refer to, and those of them not stored yet
		 */
		public Owner endOwner() {
			final Owner owner = new Owner(ownerPieces, newPieces);
			ownerPieces = null;
			newPieces = null;
			return owner;
		}

		/**
		 * Forgets the paths not written since the last call, so their pieces aren't held on to.
		 */
		public void endRound() {
			cutPaths = cutPathsNow;
			cutPathsNow = new IdentityHashMap<>();
		}

		private long[] add(List<PathData> path) throws IOException {
			Cut cut = cutPathsNow.get(path);
			if (cut == null) {
				cut = cutPaths.get(path);
			}
			if (cut == null) {
				if (!(path instanceof transitsim.plan.IndexedPath)) cut = PRECUT.remove(path);
			}
			if (cut == null || cut.size != path.size()) {
				cut = new Cut(path);
			}
			cutPathsNow.put(path, cut);
			for (int i = 0; i < cut.keys.length; i++) {
				final long key = cut.keys[i];
				if (ownerPieces != null) {
					ownerPieces.add(key);
				}
				if (!stored.contains(key)) {
					if (cut.bytes[i] == null) {
						// Stored before and swept since, as by another owner dropping it: made again
						cut = new Cut(path);
						cutPathsNow.put(path, cut);
					}
					if (newPieces != null) {
						newPieces.put(key, cut.bytes[i]);
					}
				} else {
					// Kept in the database now, so not held in memory
					cut.bytes[i] = null;
				}
			}
			return cut.references;
		}
	}

	public static final class Owner {

		public final Set<Long> pieces;
		public final Map<Long, byte[]> newPieces;

		private Owner(Set<Long> pieces, Map<Long, byte[]> newPieces) {
			this.pieces = pieces;
			this.newPieces = newPieces;
		}
	}

	private static final class Cut {

		private final int size;
		private final long[] keys;
		private final byte[][] bytes;
		private final long[] references;

		/**
		 * A path read from pieces stored: their bytes are in the database.
		 */
		private Cut(int size, long[] references) {
			this.size = size;
			this.references = references;
			keys = new long[references.length / 2];
			bytes = new byte[keys.length][];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = references[i * 2];
			}
		}

		private Cut(List<PathData> path) throws IOException {
			size = path.size();
			if (path instanceof transitsim.plan.IndexedPath) {
				final List<transitsim.plan.IndexedPath.Segment<PathData>> parts = ((transitsim.plan.IndexedPath<PathData>) path).segments();
				keys = new long[parts.size()]; bytes = new byte[parts.size()][]; references = new long[parts.size() * 2];
				final Map<List<PathData>, byte[]> encoded = new HashMap<>();
				for (int i = 0; i < parts.size(); i++) {
					final transitsim.plan.IndexedPath.Segment<PathData> part = parts.get(i);
					byte[] data = encoded.get(part.template);
					if (data == null) { data = encode(part.template, 0, part.template.size(), 0); encoded.put(part.template, data); }
					bytes[i] = data; keys[i] = keyOf(data);
					references[i * 2] = keys[i]; references[i * 2 + 1] = part.stopOffset;
				}
				return;
			}
			final List<Integer> cuts = cuts(path);
			final int count = Math.max(0, cuts.size() - 1);
			keys = new long[count];
			bytes = new byte[count][];
			references = new long[count * 2];
			for (int i = 0; i < count; i++) {
				final int from = cuts.get(i);
				final int stopBase = path.get(from).stopIndex;
				bytes[i] = encode(path, from, cuts.get(i + 1), stopBase);
				keys[i] = keyOf(bytes[i]);
				references[i * 2] = keys[i];
				references[i * 2 + 1] = stopBase;
			}
		}
	}

	/**
	 * What the database holds: the keys of the pieces stored and the pieces each owner refers to. Changes are worked out
	 * for a transaction and kept only once it is committed.
	 */
	public static final class Store {

		public final Set<Long> stored = new HashSet<>();
		private final Map<Long, Set<Long>> owners = new HashMap<>();
		private final Map<Long, Set<Long>> ownersPending = new HashMap<>();
		private final Set<Long> addedPending = new HashSet<>();
		private final Set<Long> sweptPending = new HashSet<>();

		/**
		 * Reads what is stored, for {@link Reader}.
		 */
		public Map<Long, byte[]> load(RailwayDataStore dataStore) throws Exception {
			final Map<Long, byte[]> pieces = new HashMap<>();
			dataStore.loadPathPieces(pieces::put, (owner, piece) -> owners.computeIfAbsent(owner, k -> new HashSet<>()).add(piece));
			stored.addAll(pieces.keySet());
			return pieces;
		}

		/**
		 * Writes, in the transaction going on, the pieces and references of the owners written and drops those of the
		 * owners deleted, then sweeps the pieces no one refers to any more.
		 *
		 * @param written by owner, or null for an owner written with its paths in full
		 * @param kept    pieces not swept even if no one written refers to them, as those of a save worked out but not
		 *                written yet
		 */
		public void write(RailwayDataStore dataStore, Map<Long, Owner> written, Collection<Long> deleted, Set<Long> kept) throws Exception {
			ownersPending.clear();
			addedPending.clear();
			sweptPending.clear();
			if (written.isEmpty() && deleted.isEmpty()) {
				return;
			}
			final Map<Long, byte[]> newPieces = new HashMap<>();
			for (final Map.Entry<Long, Owner> entry : written.entrySet()) {
				final Set<Long> pieces = entry.getValue() == null ? Collections.emptySet() : entry.getValue().pieces;
				if (entry.getValue() != null) {
					entry.getValue().newPieces.forEach((key, bytes) -> {
						if (bytes != null && !stored.contains(key)) {
							newPieces.put(key, bytes);
						}
					});
				}
				if (!pieces.equals(owners.getOrDefault(entry.getKey(), Collections.emptySet()))) {
					ownersPending.put(entry.getKey(), pieces);
				}
			}
			for (final long owner : deleted) {
				if (owners.containsKey(owner)) {
					ownersPending.put(owner, Collections.emptySet());
				}
			}
			dataStore.writePathPieces(newPieces);
			for (final Map.Entry<Long, Set<Long>> entry : ownersPending.entrySet()) {
				dataStore.writePathPieceRefs(entry.getKey(), entry.getValue());
			}
			addedPending.addAll(newPieces.keySet());
			if (!ownersPending.isEmpty()) {
				final Set<Long> referred = new HashSet<>();
				owners.forEach((owner, pieces) -> {
					if (!ownersPending.containsKey(owner)) {
						referred.addAll(pieces);
					}
				});
				ownersPending.values().forEach(referred::addAll);
				referred.addAll(kept);
				stored.forEach(key -> {
					if (!referred.contains(key)) {
						sweptPending.add(key);
					}
				});
				addedPending.forEach(key -> {
					if (!referred.contains(key)) {
						sweptPending.add(key);
					}
				});
				dataStore.deletePathPieces(sweptPending);
			}
		}

		public void committed() {
			stored.addAll(addedPending);
			stored.removeAll(sweptPending);
			ownersPending.forEach((owner, pieces) -> {
				if (pieces.isEmpty()) {
					owners.remove(owner);
				} else {
					owners.put(owner, pieces);
				}
			});
			ownersPending.clear();
			addedPending.clear();
			sweptPending.clear();
		}

		public void rolledBack() {
			ownersPending.clear();
			addedPending.clear();
			sweptPending.clear();
		}

		public int getStoredCount() {
			return stored.size();
		}
	}
}
