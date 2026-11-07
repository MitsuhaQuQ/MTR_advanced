package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayDataStore;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import transitsim.network.RailKeys;
import transitsim.network.SectionType;
import transitsim.network.SignalOverlay;
import transitsim.network.TrackSections;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The track section layer of the save (stages document round 1) and the sections each found path runs through, in the
 * server's own SQLite tables.
 * <p>
 * The layer ({@link TrackSections}) is kept on the server thread: every few seconds a cheap fingerprint of the rails,
 * key nodes, pathfinding boundary nodes, platforms and sidings is checked, and when it changed, what differs is handed to
 * the layer, which cuts again only the sections the changes are in or meet. The signal layer ({@link SignalOverlay}) is
 * worked out again when the sections or signal blocks change, and only the entries that differ are written.
 * <p>
 * The save loop writes what changed as a new version: the rows added, the rows removed kept until the version after is
 * written, and the root record last, in one transaction. On loading, the published version is read and checked against
 * the rails, and what doesn't match is cut again and written.
 * <p>
 * Paths can be found on any thread, so they wait in a queue and are written with the next version.
 */
public final class GraphRecords {

	private final TrackSections trackSections = new TrackSections();
	private Map<Long, SignalOverlay.Entry> overlay = new HashMap<>();
	private final ConcurrentLinkedQueue<PendingPath> pendingPaths = new ConcurrentLinkedQueue<>();

	/**
	 * What the save read, taken back on the first check, when the rails are there to check it against.
	 */
	private List<TrackSections.Section> loadedSections;
	private Map<Long, SignalOverlay.Entry> loadedOverlay = new HashMap<>();
	private long[] loadedRoot;
	private boolean restored;

	private long railsFingerprint;
	private long overlayVersion = -1;
	/**
	 * The last version written, and the one before whose removed rows can go once it is.
	 */
	private long version;
	private long sweepVersion;
	private final Set<Long> pendingAdded = new HashSet<>();
	private final Set<Long> pendingRemoved = new HashSet<>();
	private final Set<Long> pendingOverlay = new HashSet<>();

	/**
	 * What the last flush wrote, kept until it is committed, so a failed commit writes it again with the next.
	 */
	private final Set<Long> flushedAdded = new HashSet<>();
	private final Set<Long> flushedRemoved = new HashSet<>();
	private final Set<Long> flushedOverlay = new HashSet<>();
	private final List<PendingPath> flushedPaths = new ArrayList<>();
	private long flushedVersion;

	private static final int MAX_PENDING_PATHS = 65536;

	/**
	 * Brings the layer up to date if anything it is made from changed, and the signal layer if the signal blocks did.
	 * Called on the server thread now and then.
	 *
	 * @param savedRails  the platform or siding on each rail, keyed by {@link RailKeys#of}
	 * @param blocks      the rails of each signal block by its ID
	 * @param blocksVersion a number that changes when the signal blocks do
	 */
	public void update(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> keyNodes, Set<Long> boundaryNodes, Map<UUID, Long> savedRails, Map<Long, ? extends Collection<UUID>> blocks, long blocksVersion) {
		final long newFingerprint = fingerprint(rails, keyNodes, boundaryNodes, savedRails);
		final boolean sectionsChanged;
		if (!restored || newFingerprint != railsFingerprint) {
			final long start = System.currentTimeMillis();
			final Map<UUID, TrackSections.Rail> trackRails = toTrackRails(rails, savedRails);
			final Map<Long, Integer> marks = toMarks(keyNodes, boundaryNodes);
			final TrackSections.Change change;
			if (restored) {
				change = trackSections.sync(trackRails, marks);
			} else {
				restored = true;
				change = restore(trackRails, marks);
				System.out.println("Minecraft Transit Railway: track sections checked against " + trackRails.size() + " rails in " + (System.currentTimeMillis() - start) + " ms");
			}
			railsFingerprint = newFingerprint;
			change.removed.forEach(hash -> {
				// A section added since the last flush and gone again was never written
				if (!pendingAdded.remove(hash)) {
					pendingRemoved.add(hash);
				}
			});
			// Written whatever was there, as a hash shared by sections with the same content hash can change hands
			change.added.forEach(hash -> {
				pendingRemoved.remove(hash);
				pendingAdded.add(hash);
			});
			sectionsChanged = !change.isEmpty();
			if (sectionsChanged) {
				System.out.println("Minecraft Transit Railway: track sections " + change + " in " + (System.currentTimeMillis() - start) + " ms, " + trackSections.getSections().size() + " in all");
			}
		} else {
			sectionsChanged = false;
		}
		if (sectionsChanged || blocksVersion != overlayVersion) {
			overlayVersion = blocksVersion;
			final Map<Long, SignalOverlay.Entry> newOverlay = SignalOverlay.build(trackSections.getSections(), blocks);
			newOverlay.forEach((hash, entry) -> {
				if (!entry.equals(overlay.get(hash))) {
					pendingOverlay.add(hash);
				}
			});
			overlay.keySet().forEach(hash -> {
				if (!newOverlay.containsKey(hash)) {
					pendingOverlay.add(hash);
				}
			});
			overlay = newOverlay;
		}
	}

	private TrackSections.Change restore(Map<UUID, TrackSections.Rail> trackRails, Map<Long, Integer> marks) {
		final List<TrackSections.Section> saved = loadedSections == null ? Collections.emptyList() : loadedSections;
		final TrackSections.Change change = trackSections.restore(saved, trackRails, marks);
		if (loadedRoot == null) {
			System.out.println("Minecraft Transit Railway: no track sections saved, " + trackSections.getSections().size() + " made from the rails");
		} else {
			version = loadedRoot[0];
			sweepVersion = version;
			if (saved.size() != loadedRoot[2] || !change.isEmpty()) {
				System.out.println("Minecraft Transit Railway: the saved track sections didn't match the rails (" + saved.size() + " read of " + loadedRoot[2] + "), so " + change + " were cut again");
			}
		}
		// The signal layer as saved, so only what differs is written
		overlay = loadedOverlay;
		loadedSections = null;
		loadedOverlay = new HashMap<>();
		return change;
	}

	/**
	 * @param owner what the path belongs to, such as {@code route:<id>} or {@code siding:<id>}
	 */
	public void addPath(String owner, List<long[]> moves) {
		if (pendingPaths.size() < MAX_PENDING_PATHS && !moves.isEmpty()) {
			pendingPaths.add(new PendingPath(owner, moves));
		}
	}

	/**
	 * Writes what changed as a new version, then the paths found since. Paths found before the first check wait for it.
	 */
	public void flush(RailwayDataStore store) throws Exception {
		flushedAdded.clear();
		flushedRemoved.clear();
		flushedOverlay.clear();
		flushedPaths.clear();
		if (!restored) {
			return;
		}
		// The rows removed by the version before, written and committed, can go
		if (sweepVersion > 0) {
			store.sweepTrackSections(sweepVersion);
			sweepVersion = 0;
		}
		if (!pendingAdded.isEmpty() || !pendingRemoved.isEmpty() || !pendingOverlay.isEmpty()) {
			final Map<Long, byte[]> added = new TreeMap<>();
			pendingAdded.forEach(hash -> {
				final TrackSections.Section section = trackSections.getSection(hash);
				if (section != null) {
					added.put(hash, section.toBytes());
				}
			});
			final Map<Long, byte[]> overlayPut = new TreeMap<>();
			final List<Long> overlayRemoved = new ArrayList<>();
			pendingOverlay.forEach(hash -> {
				final SignalOverlay.Entry entry = overlay.get(hash);
				if (entry == null) {
					overlayRemoved.add(hash);
				} else {
					overlayPut.put(hash, entry.toBytes());
				}
			});
			flushedVersion = version + 1;
			store.publishTrackSections(flushedVersion, added, pendingRemoved, overlayPut, overlayRemoved, trackSections.getFingerprint(), trackSections.getSections().size());
			flushedAdded.addAll(pendingAdded);
			flushedRemoved.addAll(pendingRemoved);
			flushedOverlay.addAll(pendingOverlay);
			pendingAdded.clear();
			pendingRemoved.clear();
			pendingOverlay.clear();
		}
		final long fingerprint = trackSections.getFingerprint();
		PendingPath pendingPath;
		while ((pendingPath = pendingPaths.poll()) != null) {
			flushedPaths.add(pendingPath);
			store.writeGraphPath(pendingPath.owner, fingerprint, trackSections.toSectionPath(pendingPath.moves).toBytes());
		}
	}

	/**
	 * What the last flush wrote was committed: its version is the published one, and the rows it removed go with the next.
	 */
	public void flushCommitted() {
		if (!flushedAdded.isEmpty() || !flushedRemoved.isEmpty() || !flushedOverlay.isEmpty()) {
			version = flushedVersion;
			sweepVersion = version;
		}
		flushedAdded.clear();
		flushedRemoved.clear();
		flushedOverlay.clear();
		flushedPaths.clear();
	}

	/**
	 * What the last flush wrote wasn't committed, so it is written again with the next.
	 */
	public void flushFailed() {
		flushedAdded.forEach(hash -> {
			if (!pendingRemoved.remove(hash)) {
				pendingAdded.add(hash);
			}
		});
		flushedRemoved.forEach(hash -> {
			if (!pendingAdded.remove(hash)) {
				pendingRemoved.add(hash);
			}
		});
		pendingOverlay.addAll(flushedOverlay);
		pendingPaths.addAll(flushedPaths);
		flushedAdded.clear();
		flushedRemoved.clear();
		flushedOverlay.clear();
		flushedPaths.clear();
	}

	/**
	 * Reads back the published version, to be checked against the rails on the first update.
	 */
	public void load(RailwayDataStore store) {
		loadedSections = new ArrayList<>();
		loadedOverlay = new HashMap<>();
		try {
			loadedRoot = store.loadTrackSections((id, data) -> {
				try {
					loadedSections.add(TrackSections.Section.fromBytes(id, data));
				} catch (Exception e) {
					// Cut again from the rails when checked
					System.out.println("Minecraft Transit Railway: a saved track section couldn't be read: " + e.getMessage());
				}
			}, (id, data) -> {
				try {
					loadedOverlay.put(id, SignalOverlay.Entry.fromBytes(data));
				} catch (Exception e) {
					System.out.println("Minecraft Transit Railway: a saved signal layer entry couldn't be read: " + e.getMessage());
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public TrackSections getTrackSections() {
		return trackSections;
	}

	/**
	 * The rails as the layer takes them: both ways of a rail at once, typed by the way a train can run it.
	 */
	public static Map<UUID, TrackSections.Rail> toTrackRails(Map<BlockPos, Map<BlockPos, Rail>> rails, Map<UUID, Long> savedRails) {
		final Map<UUID, TrackSections.Rail> trackRails = new HashMap<>();
		rails.forEach((startPos, connections) -> connections.forEach((endPos, rail) -> {
			final long start = startPos.asLong();
			final long end = endPos.asLong();
			if (start == end || start > end && hasRail(rails, endPos, startPos)) {
				// Each rail once, from its lower node when both ways are there
				return;
			}
			final UUID key = RailKeys.of(start, end);
			final Rail reverse = getRail(rails, endPos, startPos);
			final boolean forwards = rail.railType != RailType.NONE;
			final boolean backwards = reverse != null && reverse.railType != RailType.NONE;
			// The type is that of a way a train can run
			final RailType railType = forwards || reverse == null ? rail.railType : reverse.railType;
			final long shape = (long) Float.floatToIntBits(rail.facingStart.angleDegrees) << 32 | Float.floatToIntBits(rail.facingEnd.angleDegrees) & 0xFFFFFFFFL;
			// Facings as seen from the lower node, the same whichever way the rail is kept
			final long lowerShape = start < end ? shape : (long) Float.floatToIntBits(rail.facingEnd.angleDegrees) << 32 | Float.floatToIntBits(rail.facingStart.angleDegrees) & 0xFFFFFFFFL;
			trackRails.put(key, new TrackSections.Rail(start, end, rail.getLength(), lowerShape, railType.speedLimit, railType.canAccelerate, getType(railType), forwards, backwards, savedRails.getOrDefault(key, 0L)));
		}));
		return trackRails;
	}

	private static Rail getRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos from, BlockPos to) {
		final Map<BlockPos, Rail> connections = rails.get(from);
		return connections == null ? null : connections.get(to);
	}

	private static boolean hasRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos from, BlockPos to) {
		return getRail(rails, from, to) != null;
	}

	private static SectionType getType(RailType railType) {
		switch (railType) {
			case PLATFORM:
				return SectionType.PLATFORM;
			case SIDING:
				return SectionType.SIDING;
			case TURN_BACK:
				return SectionType.TURN_BACK;
			default:
				return SectionType.NORMAL;
		}
	}

	private static Map<Long, Integer> toMarks(Set<BlockPos> keyNodes, Set<Long> boundaryNodes) {
		final Map<Long, Integer> marks = new HashMap<>();
		keyNodes.forEach(pos -> marks.merge(pos.asLong(), TrackSections.MARK_KEY, (mark1, mark2) -> mark1 | mark2));
		boundaryNodes.forEach(node -> marks.merge(node, TrackSections.MARK_BOUNDARY, (mark1, mark2) -> mark1 | mark2));
		return marks;
	}

	/**
	 * Adds up a hash of each item, so the order they are kept in doesn't matter.
	 */
	private static long fingerprint(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> keyNodes, Set<Long> boundaryNodes, Map<UUID, Long> savedRails) {
		final long[] sum = {0};
		rails.forEach((startPos, connections) -> connections.forEach((endPos, rail) -> sum[0] += mix(startPos.asLong() * 31 + endPos.asLong(), rail.railType.ordinal(), Float.floatToIntBits(rail.facingStart.angleDegrees) * 31L + Float.floatToIntBits(rail.facingEnd.angleDegrees))));
		keyNodes.forEach(pos -> sum[0] += mix(pos.asLong(), 1, 0));
		boundaryNodes.forEach(node -> sum[0] += mix(node, 2, 0));
		savedRails.forEach((key, id) -> sum[0] += mix(key.getMostSignificantBits() * 31 + key.getLeastSignificantBits(), 3, id));
		return sum[0];
	}

	private static long mix(long value1, long value2, long value3) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L + value3;
		return hash ^ hash >>> 29;
	}

	/**
	 * @return each rail of a path as {from node, to node, stop time, platform or 0}
	 */
	public static List<long[]> getMoves(List<PathData> path) {
		final List<long[]> moves = new ArrayList<>();
		path.forEach(pathData -> moves.add(new long[]{pathData.startingPos.asLong(), pathData.getLastPos().asLong(), pathData.dwellTime, pathData.savedRailBaseId}));
		return moves;
	}

	/**
	 * @return the rails between nodes passed in order, without stops
	 */
	public static List<long[]> getMoves(long[] nodes) {
		final List<long[]> moves = new ArrayList<>();
		for (int i = 0; i + 1 < nodes.length; i++) {
			moves.add(new long[]{nodes[i], nodes[i + 1], 0, 0});
		}
		return moves;
	}

	private static final class PendingPath {

		private final String owner;
		private final List<long[]> moves;

		private PendingPath(String owner, List<long[]> moves) {
			this.owner = owner;
			this.moves = moves;
		}
	}
}
