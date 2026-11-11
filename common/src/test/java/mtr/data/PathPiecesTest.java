package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paths saved as references to pieces shared by content (optimisation round 5, step 2): read back the same, shared
 * between paths along the same stretch, and swept once no one refers to them.
 */
class PathPiecesTest {

	private static PathData rail(int startZ, int endZ, long savedRailBaseId, int dwellTime, int stopIndex) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), dwellTime > 0 ? RailType.PLATFORM : RailType.IRON, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, stopIndex);
	}

	/**
	 * Out of a siding, two stops, a stretch and a third stop, numbered from {@code firstStop}.
	 */
	private static List<PathData> path(int firstStop, long sidingId) {
		final List<PathData> path = new ArrayList<>();
		path.add(rail(0, 10, sidingId, 0, firstStop - 1));
		path.add(rail(10, 20, 0, 0, firstStop - 1));
		path.add(rail(20, 30, 2, 40, firstStop));
		path.add(rail(30, 40, 0, 0, firstStop));
		path.add(rail(40, 50, 3, 40, firstStop + 1));
		path.add(rail(50, 60, 0, 0, firstStop + 1));
		path.add(rail(60, 70, 0, 0, firstStop + 1));
		path.add(rail(70, 80, 4, 40, firstStop + 2));
		path.get(3).speedCap = 1.5F;
		return path;
	}

	private static byte[] bytes(PathData pathData) {
		final List<PathData> one = Collections.singletonList(pathData);
		return RailwayDataFileSaveModule.toBytes(new SerializedDataBase() {
			@Override
			public void toMessagePack(org.msgpack.core.MessagePacker messagePacker) throws IOException {
				RailwayData.writeMessagePackDataset(messagePacker, one, "path");
			}

			@Override
			public int messagePackLength() {
				return 1;
			}

			@Override
			public void writePacket(net.minecraft.network.FriendlyByteBuf packet) {
			}
		});
	}

	private static void assertSamePath(List<PathData> expected, List<PathData> actual) {
		assertNotNull(actual);
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertArrayEquals(bytes(expected.get(i)), bytes(actual.get(i)), "rail " + i);
		}
	}

	private static Map<String, Value> written(List<PathData> path, PathPieces.Writer writer) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(1);
		if (writer != null) {
			PathPieces.startWriting(writer);
		}
		try {
			PathPieces.writePath(packer, path, "path");
		} finally {
			PathPieces.stopWriting();
		}
		packer.close();
		return RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
	}

	@Test
	void repeatedTripsAreStoredAsTemplateReferencesWithCorrectAnnouncementIndices() throws IOException {
		List<PathData> joined = new transitsim.plan.JoinedPath<>();
		int[] starts = new int[100];
		for (int i = 0; i < starts.length; i++) { starts[i] = joined.size(); joined.addAll(path(100 + i * 3, 9)); }
		List<PathData> expected = new ArrayList<>();
		joined.forEach(rail -> expected.add(rail.copy(rail.savedRailBaseId, rail.dwellTime)));
		transitsim.plan.AnnouncementStops.numberDuty(expected, 2);
		List<PathData> indexed = PathPieces.indexDuty(joined, starts, 2);
		assertSamePath(expected, indexed);
		assertEquals(99, joined.get(0).stopIndex, "The template source is not renumbered in place");
		PathPieces.Writer writer = new PathPieces.Writer(new HashSet<>()); writer.startOwner();
		Map<String, Value> saved = written(indexed, writer); PathPieces.Owner owner = writer.endOwner();
		assertEquals(1, owner.newPieces.size());
		PathPieces.startReading(new PathPieces.Reader(owner.newPieces));
		try {
			List<PathData> restored = PathPieces.readPath(saved, "path");
			assertTrue(restored instanceof transitsim.plan.IndexedPath);
			assertSamePath(expected, restored);
		} finally { PathPieces.stopReading(); }
	}

	@Test
	void aPathIsCutAfterEachStopAndAfterManyRails() {
		assertEquals(Arrays.asList(0, 3, 5, 8), PathPieces.cuts(path(1, 9)));
		final List<PathData> longRun = new ArrayList<>();
		for (int i = 0; i < PathPieces.MAX_PIECE_RAILS + 10; i++) {
			longRun.add(rail(i, i + 1, 0, 0, 0));
		}
		assertEquals(Arrays.asList(0, PathPieces.MAX_PIECE_RAILS, PathPieces.MAX_PIECE_RAILS + 10), PathPieces.cuts(longRun));
		assertEquals(Collections.singletonList(0), PathPieces.cuts(Collections.emptyList()));
	}

	@Test
	void aPathWrittenAsPiecesReadsBackTheSame() throws IOException {
		final PathPieces.Writer writer = new PathPieces.Writer(new HashSet<>());
		writer.startOwner();
		final List<PathData> path = path(1, 9);
		final Map<String, Value> map = written(path, writer);
		final PathPieces.Owner owner = writer.endOwner();
		assertTrue(map.containsKey(PathPieces.KEY_PATH_PIECES));
		assertFalse(map.containsKey("path"));
		assertEquals(3, owner.pieces.size());
		assertEquals(owner.pieces, owner.newPieces.keySet());

		// Without the pieces it can't be put together, and is made again
		assertNull(PathPieces.readPath(map, "path"));
		PathPieces.startReading(new PathPieces.Reader(owner.newPieces));
		try {
			assertSamePath(path, PathPieces.readPath(map, "path"));
		} finally {
			PathPieces.stopReading();
		}
	}

	@Test
	void aPathWrittenInFullReadsBackWithoutPieces() throws IOException {
		final List<PathData> path = path(1, 9);
		final Map<String, Value> map = written(path, null);
		assertTrue(map.containsKey("path"));
		assertSamePath(path, PathPieces.readPath(map, "path"));
	}

	@Test
	void pathsAlongTheSameStretchShareItsPiecesWhateverTheirStopNumbers() throws IOException {
		final PathPieces.Writer writer = new PathPieces.Writer(new HashSet<>());
		writer.startOwner();
		written(path(1, 9), writer);
		final PathPieces.Owner first = writer.endOwner();
		writer.startOwner();
		// From another siding, with its stops numbered on from 5
		final List<PathData> other = path(5, 8);
		final Map<String, Value> map = written(other, writer);
		final PathPieces.Owner second = writer.endOwner();

		final Set<Long> shared = new HashSet<>(first.pieces);
		shared.retainAll(second.pieces);
		assertEquals(2, shared.size(), "only the way out of the siding differs");
		final Map<Long, byte[]> all = new HashMap<>(first.newPieces);
		all.putAll(second.newPieces);
		PathPieces.startReading(new PathPieces.Reader(all));
		try {
			assertSamePath(other, PathPieces.readPath(map, "path"));
		} finally {
			PathPieces.stopReading();
		}
	}

	@Test
	void aPathWrittenAgainIsNotCutAgainAndPiecesStoredAreNotWrittenAgain() throws IOException {
		final Set<Long> stored = new HashSet<>();
		final PathPieces.Writer writer = new PathPieces.Writer(stored);
		final List<PathData> path = path(1, 9);
		writer.startOwner();
		written(path, writer);
		final PathPieces.Owner first = writer.endOwner();
		stored.addAll(first.newPieces.keySet());
		writer.endRound();
		writer.startOwner();
		written(path, writer);
		final PathPieces.Owner second = writer.endOwner();
		assertEquals(first.pieces, second.pieces);
		assertTrue(second.newPieces.isEmpty());

		// Swept since, as by another owner dropping it: written again
		stored.clear();
		writer.startOwner();
		written(path, writer);
		assertEquals(first.pieces, writer.endOwner().newPieces.keySet());
	}

	@Test
	void aPathReadFromPiecesIsWrittenAsThemWithoutCuttingItAgain() throws IOException {
		final PathPieces.Writer first = new PathPieces.Writer(new HashSet<>());
		first.startOwner();
		final Map<String, Value> map = written(path(1, 9), first);
		final PathPieces.Owner owner = first.endOwner();

		final Set<Long> stored = new HashSet<>(owner.pieces);
		final PathPieces.Writer writer = new PathPieces.Writer(stored);
		final PathPieces.Reader reader = new PathPieces.Reader(owner.newPieces);
		final List<PathData> kept;
		PathPieces.startReading(reader);
		try {
			final List<PathData> read = PathPieces.readPath(map, "path");
			kept = new ArrayList<>(read);
			PathPieces.remember(read, kept);
		} finally {
			PathPieces.stopReading();
		}
		reader.seed(writer);

		writer.startOwner();
		final Map<String, Value> again = written(kept, writer);
		final PathPieces.Owner second = writer.endOwner();
		assertEquals(owner.pieces, second.pieces);
		assertTrue(second.newPieces.isEmpty());
		assertEquals(map.get(PathPieces.KEY_PATH_PIECES), again.get(PathPieces.KEY_PATH_PIECES));
	}

	@Test
	void piecesNoOneRefersToAreSweptInTheSameTransaction() throws Exception {
		final MemoryStore dataStore = new MemoryStore();
		final PathPieces.Store store = new PathPieces.Store();
		final PathPieces.Writer writer = new PathPieces.Writer(store.stored);
		writer.startOwner();
		written(path(1, 9), writer);
		final PathPieces.Owner sidingA = writer.endOwner();
		writer.startOwner();
		written(path(5, 8), writer);
		final PathPieces.Owner sidingB = writer.endOwner();
		final Map<Long, PathPieces.Owner> written = new HashMap<>();
		written.put(1L, sidingA);
		written.put(2L, sidingB);
		store.write(dataStore, written, Collections.emptyList(), Collections.emptySet());
		store.committed();
		assertEquals(4, dataStore.pieces.size());
		assertEquals(4, store.getStoredCount());

		// A rolled back write leaves what was known (written to a copy, as the database drops it)
		store.write(dataStore.copy(), Collections.emptyMap(), Collections.singletonList(1L), Collections.emptySet());
		store.rolledBack();
		assertEquals(4, store.getStoredCount());

		// Siding 1 deleted, while a save worked out but not written yet refers to its way out: kept
		final MemoryStore copy = dataStore.copy();
		final Set<Long> wayOutOfA = new HashSet<>(sidingA.pieces);
		wayOutOfA.removeAll(sidingB.pieces);
		store.write(copy, Collections.emptyMap(), Collections.singletonList(1L), wayOutOfA);
		assertEquals(4, copy.pieces.size());
		store.rolledBack();

		store.write(dataStore, Collections.emptyMap(), Collections.singletonList(1L), Collections.emptySet());
		store.committed();
		assertEquals(sidingB.pieces, dataStore.pieces.keySet());
		assertEquals(sidingB.pieces, store.stored);
		assertFalse(dataStore.refs.containsKey(1L));

		// Read back as it was left
		final PathPieces.Store loaded = new PathPieces.Store();
		assertEquals(sidingB.pieces, loaded.load(dataStore).keySet());
		loaded.write(dataStore, Collections.emptyMap(), Collections.singletonList(2L), Collections.emptySet());
		loaded.committed();
		assertTrue(dataStore.pieces.isEmpty());
	}

	private static final class MemoryStore implements RailwayDataStore {

		private final Map<Long, byte[]> pieces = new HashMap<>();
		private final Map<Long, Set<Long>> refs = new HashMap<>();

		private MemoryStore copy() {
			final MemoryStore copy = new MemoryStore();
			copy.pieces.putAll(pieces);
			refs.forEach((owner, set) -> copy.refs.put(owner, new HashSet<>(set)));
			return copy;
		}

		@Override
		public boolean storesPathPieces() {
			return true;
		}

		@Override
		public void loadPathPieces(BiConsumer<Long, byte[]> pieceConsumer, BiConsumer<Long, Long> refConsumer) {
			pieces.forEach(pieceConsumer);
			refs.forEach((owner, set) -> set.forEach(piece -> refConsumer.accept(owner, piece)));
		}

		@Override
		public void writePathPieces(Map<Long, byte[]> newPieces) {
			newPieces.forEach(pieces::putIfAbsent);
		}

		@Override
		public void writePathPieceRefs(long owner, Collection<Long> ownerPieces) {
			if (ownerPieces.isEmpty()) {
				refs.remove(owner);
			} else {
				refs.put(owner, new HashSet<>(ownerPieces));
			}
		}

		@Override
		public void deletePathPieces(Collection<Long> deleted) {
			deleted.forEach(piece -> {
				if (refs.values().stream().noneMatch(set -> set.contains(piece))) {
					pieces.remove(piece);
				}
			});
		}

		@Override
		public void forEach(String type, BiConsumer<Long, byte[]> consumer) {
		}

		@Override
		public void write(String type, long id, byte[] data) {
		}

		@Override
		public void delete(String type, long id) {
		}

		@Override
		public void commit() {
		}

		@Override
		public void backup() {
		}

		@Override
		public void close() {
		}
	}
}
