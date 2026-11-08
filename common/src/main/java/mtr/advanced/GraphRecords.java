package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayDataStore;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import transitsim.network.*;

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
	 * The leg layer (stages document round 2): the sections as last cut, the owners of each, and the legs found on them.
	 * The snapshot and owners are replaced on the server thread before the cache is told what changed, so a leg found
	 * on another thread is never taken in for a network it wasn't found on.
	 */
	private volatile TrackSections.Snapshot snapshot;
	private volatile Map<Long, Set<String>> owners = Collections.emptyMap();
	private volatile double maxChordPerCost;
	private final LegCache legCache = new LegCache();
	private Map<Long, byte[]> loadedLegs = new HashMap<>();
	private Map<Long, Set<String>> loadedOwners = new HashMap<>();
	/**
	 * What was written of the leg layer, to write only what differs.
	 */
	private final Map<Long, LegRouter.Leg> writtenLegs = new HashMap<>();
	private final Map<Long, Set<String>> writtenOwners = new HashMap<>();
	private final Map<Long, Map<Long, LegRouter.Leg>> flushedLegs = new HashMap<>();
	private final Map<Long, Set<String>> flushedOwners = new HashMap<>();
	private final Set<Long> flushedLegRemovals = new HashSet<>();
	private final Set<Long> flushedOwnerRemovals = new HashSet<>();

	/**
	 * Where nodes are: a node is a block position.
	 */
	public static final LegRouter.Coordinates COORDINATES = new LegRouter.Coordinates() {
		@Override
		public int getX(long node) {
			return BlockPos.getX(node);
		}

		@Override
		public int getZ(long node) {
			return BlockPos.getZ(node);
		}
	};

	/**
	 * Brings the layer up to date if anything it is made from changed, and the signal layer if the signal blocks did.
	 * Called on the server thread now and then.
	 *
	 * @param savedRails  the platform or siding on each rail, keyed by {@link RailKeys#of}
	 * @param blocks      the rails of each signal block by its ID
	 * @param blocksVersion a number that changes when the signal blocks do
	 */
	public void update(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> keyNodes, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Set<UUID> releasedRails, Map<UUID, Long> savedRails, Map<Long, ? extends Collection<UUID>> blocks, long blocksVersion) {
		final long newFingerprint = fingerprint(rails, keyNodes, boundaryNodes, savedRails) + 31 * hashRails(boundaryRails, 5) + 37 * hashRails(releasedRails, 6) + 41 * mtr.data.PlatformSpeeds.fingerprint();
		final boolean sectionsChanged;
		if (!restored || newFingerprint != railsFingerprint) {
			final long start = System.currentTimeMillis();
			final Map<UUID, TrackSections.Rail> trackRails = toTrackRails(rails, savedRails);
			final Map<Long, Integer> marks = toMarks(keyNodes, boundaryNodes, boundaryRails, releasedRails);
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
			if (sectionsChanged || snapshot == null) {
				updateLegs(change);
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

	/**
	 * A new snapshot for the legs, then the legs the change touches dropped: those that touched a section added or
	 * removed, or went on from a node a new section joins.
	 */
	private void updateLegs(TrackSections.Change change) {
		final TrackSections.Snapshot newSnapshot = trackSections.snapshot();
		maxChordPerCost = LegRouter.getMaxChordPerCost(newSnapshot, COORDINATES);
		final boolean first = snapshot == null;
		snapshot = newSnapshot;
		if (first) {
			owners = loadedOwners;
			writtenOwners.putAll(loadedOwners);
			// The legs as saved, where every section they touched is still there; the change drops the rest
			int taken = 0;
			for (final Map.Entry<Long, byte[]> entry : loadedLegs.entrySet()) {
				boolean kept = false;
				try {
					final LegRouter.Leg leg = LegRouter.Leg.fromBytes(entry.getValue(), legCache.getVersion());
					if (leg != null && Arrays.stream(leg.domainSections).allMatch(newSnapshot.sections::containsKey) && legCache.put(leg)) {
						writtenLegs.put(leg.request.key, leg);
						kept = leg.request.key == entry.getKey();
						taken++;
					}
				} catch (Exception e) {
					System.out.println("Minecraft Transit Railway: a saved leg couldn't be read: " + e.getMessage());
				}
				// A row not taken back, as one found by other rules (round 4), is removed with the next write: written as
				// nothing, it isn't among the legs kept
				if (!kept) {
					writtenLegs.putIfAbsent(entry.getKey(), null);
				}
			}
			if (!loadedLegs.isEmpty()) {
				System.out.println("Minecraft Transit Railway: " + taken + " of " + loadedLegs.size() + " saved legs taken back");
			}
			loadedLegs = new HashMap<>();
			loadedOwners = new HashMap<>();
		}
		final Set<Long> changed = new HashSet<>(change.added);
		changed.addAll(change.removed);
		final Set<Long> joined = new HashSet<>();
		change.added.forEach(hash -> {
			final TrackSections.Section section = newSnapshot.sections.get(hash);
			if (section != null) {
				joined.add(section.getStart());
				joined.add(section.getEnd());
			}
		});
		final Set<Long> dropped = legCache.change(changed, joined);
		if (!dropped.isEmpty()) {
			System.out.println("Minecraft Transit Railway: " + dropped.size() + " legs to find again");
		}
	}

	/**
	 * Replaces the owners of the sections, then drops the legs that touched a section whose owners changed. Called on the
	 * server thread.
	 *
	 * @return the sections whose owners changed
	 */
	public Set<Long> setOwners(Map<Long, Set<String>> newOwners) {
		final Set<Long> changed = TrackOwnership.changed(owners, newOwners);
		owners = Collections.unmodifiableMap(new HashMap<>(newOwners));
		if (!changed.isEmpty()) {
			final Set<Long> dropped = legCache.changeOwners(changed);
			System.out.println("Minecraft Transit Railway: the owners of " + changed.size() + " track sections changed, " + dropped.size() + " legs to find again");
		}
		return changed;
	}

	/**
	 * @return the legs as they can be found now, or null before the first check
	 */
	public Legs getLegs() {
		// The version first: a snapshot replaced after it makes the legs found older than a change, never newer
		final long legVersion = legCache.getVersion();
		final TrackSections.Snapshot currentSnapshot = snapshot;
		return currentSnapshot == null ? null : new Legs(legVersion, currentSnapshot, owners, maxChordPerCost, legCache);
	}

	/**
	 * The legs of one moment: kept ones, or found on the snapshot of that moment and kept. Used from any thread.
	 */
	public static final class Legs implements RouteLegs.LegSource {

		public final TrackSections.Snapshot snapshot;
		public final Map<Long, Set<String>> owners;
		private final long version;
		private final double maxChordPerCost;
		private final LegCache legCache;

		private Legs(long version, TrackSections.Snapshot snapshot, Map<Long, Set<String>> owners, double maxChordPerCost, LegCache legCache) {
			this.version = version;
			this.snapshot = snapshot;
			this.owners = owners;
			this.maxChordPerCost = maxChordPerCost;
			this.legCache = legCache;
		}

		@Override
		public LegRouter.Leg get(LegRouter.Request request) {
			final LegRouter.Leg kept = legCache.get(request);
			if (kept != null) {
				return kept;
			}
			final LegRouter.Leg leg = LegRouter.find(snapshot, version, owners, request, COORDINATES, maxChordPerCost);
			// Another thread may have found it at the same time: what is kept is used, so the routes agree on it
			final LegRouter.Leg taken = legCache.keep(leg);
			return taken == null ? leg : taken;
		}

		/**
		 * @return the same legs, found without keeping what is found: for measuring ways the cache isn't for, so their
		 * search domains don't weigh on it or on the save
		 */
		public RouteLegs.LegSource withoutKeeping() {
			return request -> {
				final LegRouter.Leg kept = legCache.get(request);
				return kept != null ? kept : LegRouter.find(snapshot, version, owners, request, COORDINATES, maxChordPerCost);
			};
		}

		/**
		 * @return whether the legs are still the ones kept
		 */
		public boolean holds(Collection<LegRouter.Leg> legs) {
			return legCache.holds(legs);
		}
	}

	/**
	 * @return the ID of a set of owners, from its content
	 */
	private static long getOwnerSetId(Set<String> sectionOwners) {
		long hash = 17;
		for (final String owner : new TreeSet<>(sectionOwners)) {
			hash = mix(hash, owner.hashCode(), owner.length());
		}
		return hash;
	}

	public LegCache getLegCache() {
		return legCache;
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
		// The leg layer: what differs from what was written
		flushedLegs.clear();
		flushedOwners.clear();
		flushedLegRemovals.clear();
		flushedOwnerRemovals.clear();
		final Map<Long, LegRouter.Leg> currentLegs = new HashMap<>();
		legCache.getLegs().forEach(leg -> currentLegs.put(leg.request.key, leg));
		final Map<Long, byte[]> legsPut = new TreeMap<>();
		currentLegs.forEach((key, leg) -> {
			if (writtenLegs.get(key) != leg) {
				legsPut.put(key, leg.toBytes());
				flushedLegs.computeIfAbsent(0L, k -> new HashMap<>()).put(key, leg);
			}
		});
		writtenLegs.keySet().forEach(key -> {
			if (!currentLegs.containsKey(key)) {
				flushedLegRemovals.add(key);
			}
		});
		final Map<Long, Set<String>> currentOwners = owners;
		// Each different set of owners is written once, and each section names its set
		final Map<Long, byte[]> ownersPut = new TreeMap<>();
		final Map<Long, byte[]> ownerSetsPut = new TreeMap<>();
		currentOwners.forEach((hash, sectionOwners) -> {
			if (!sectionOwners.equals(writtenOwners.get(hash))) {
				final long setId = getOwnerSetId(sectionOwners);
				ownersPut.put(hash, java.nio.ByteBuffer.allocate(8).putLong(setId).array());
				ownerSetsPut.putIfAbsent(setId, TrackOwnership.toBytes(sectionOwners));
				flushedOwners.put(hash, sectionOwners);
			}
		});
		writtenOwners.keySet().forEach(hash -> {
			if (!currentOwners.containsKey(hash)) {
				flushedOwnerRemovals.add(hash);
			}
		});
		if (!legsPut.isEmpty() || !flushedLegRemovals.isEmpty() || !ownersPut.isEmpty() || !flushedOwnerRemovals.isEmpty()) {
			store.writeLegLayer(legsPut, flushedLegRemovals, ownersPut, flushedOwnerRemovals, ownerSetsPut);
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
		flushedLegs.values().forEach(writtenLegs::putAll);
		flushedLegRemovals.forEach(writtenLegs::remove);
		writtenOwners.putAll(flushedOwners);
		flushedOwnerRemovals.forEach(writtenOwners::remove);
		flushedLegs.clear();
		flushedOwners.clear();
		flushedLegRemovals.clear();
		flushedOwnerRemovals.clear();
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
		// What differs is worked out again from what was written, so nothing of the leg layer needs keeping
		flushedLegs.clear();
		flushedOwners.clear();
		flushedLegRemovals.clear();
		flushedOwnerRemovals.clear();
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
			final Map<Long, Set<String>> ownerSets = new HashMap<>();
			final Map<Long, Long> sectionSets = new HashMap<>();
			store.loadLegLayer(loadedLegs::put, (id, data) -> sectionSets.put(id, java.nio.ByteBuffer.wrap(data).getLong()), (id, data) -> ownerSets.put(id, TrackOwnership.fromBytes(data)));
			sectionSets.forEach((hash, setId) -> {
				final Set<String> sectionOwners = ownerSets.get(setId);
				if (sectionOwners != null) {
					loadedOwners.put(hash, sectionOwners);
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
			trackRails.put(key, new TrackSections.Rail(start, end, rail.getLength(), lowerShape, mtr.data.SpeedRules.labelKmh(railType, rail.transportMode, start, end), railType.canAccelerate, getType(railType), forwards, backwards, savedRails.getOrDefault(key, 0L)));
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

	private static Map<Long, Integer> toMarks(Set<BlockPos> keyNodes, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Set<UUID> releasedRails) {
		final Map<Long, Integer> marks = new HashMap<>();
		keyNodes.forEach(pos -> marks.merge(pos.asLong(), TrackSections.MARK_KEY, (mark1, mark2) -> mark1 | mark2));
		boundaryNodes.forEach(node -> marks.merge(node, TrackSections.MARK_BOUNDARY, (mark1, mark2) -> mark1 | mark2));
		// A boundary rail, and a rail released from a connecting line, make sections of their own
		boundaryRails.forEach(rail -> {
			marks.merge(rail.getMostSignificantBits(), TrackSections.MARK_BOUNDARY_RAIL, (mark1, mark2) -> mark1 | mark2);
			marks.merge(rail.getLeastSignificantBits(), TrackSections.MARK_BOUNDARY_RAIL, (mark1, mark2) -> mark1 | mark2);
		});
		releasedRails.forEach(rail -> {
			marks.merge(rail.getMostSignificantBits(), TrackSections.MARK_RELEASED, (mark1, mark2) -> mark1 | mark2);
			marks.merge(rail.getLeastSignificantBits(), TrackSections.MARK_RELEASED, (mark1, mark2) -> mark1 | mark2);
		});
		return marks;
	}

	private static long hashRails(Set<UUID> rails, long salt) {
		long sum = 0;
		for (final UUID rail : rails) {
			sum += mix(rail.getMostSignificantBits() * 31 + rail.getLeastSignificantBits(), salt, 0);
		}
		return sum;
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
