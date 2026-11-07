package transitsim.network;

import java.io.*;
import java.util.*;

/**
 * The track section layer of the save (optimisation document 5, stages document round 1): the rails cut into sections at
 * the nodes where something happens, each section named by a hash of its content, kept up to date piece by piece as rails
 * change and saved row by row.
 * <p>
 * A node cuts the rails where they branch or end (any number of rails but two), where it is marked (a key node or a
 * pathfinding boundary node), and at both ends of a platform, siding or turn back rail, which make sections of their own.
 * The rails between two such nodes make one section. A ring with no such node at all starts and ends at its lowest node.
 * <p>
 * A section's hash depends only on its content: its nodes in order, each rail's length, shape, speed label, type, ways
 * it can be run and saved rail, and the marks on its two ends. So the same rails give the same hashes whatever order they
 * are added in, and changing rails changes only the sections they are in, or that meet them. Two different sections with
 * the same hash, which shouldn't ever happen, are told apart by content: the sections sharing a hash are put in the order
 * of their content, the first keeps the hash and the others are given hashes made from it and their place. So the hashes
 * depend only on which sections there are, never on the order they came in, and it is counted ({@link #getCollisions}).
 * <p>
 * Not thread safe: one thread changes it; {@link #getSections} gives a copy to read elsewhere.
 */
public final class TrackSections {

	/**
	 * A node marked as a key node.
	 */
	public static final int MARK_KEY = 1;
	/**
	 * A node marked as a pathfinding boundary.
	 */
	public static final int MARK_BOUNDARY = 2;

	private static final int FORMAT = 1;

	private final Map<UUID, Rail> rails = new HashMap<>();
	private final Map<Long, List<UUID>> nodeRails = new HashMap<>();
	private final Map<Long, Integer> marks = new HashMap<>();
	private final Map<UUID, Long> railSections = new HashMap<>();
	private final Map<Long, Section> sections = new HashMap<>();
	/**
	 * The sections by the hash of their content, before any is told apart from another with the same.
	 */
	private final Map<Long, List<Section>> groups = new HashMap<>();
	private int collisions;
	/**
	 * Hashes a section's content; tests give one that collides.
	 */
	static volatile java.util.function.ToLongFunction<byte[]> hasher = TrackSections::hash;

	/**
	 * A rail, both ways at once, as the host knows it.
	 */
	public static final class Rail {

		/**
		 * The lower node.
		 */
		public final long node1;
		/**
		 * The higher node.
		 */
		public final long node2;
		public final double length;
		/**
		 * A hash of the rail's shape between its nodes, as the facings at both ends, the same seen from either end.
		 */
		public final long shape;
		/**
		 * The speed written on the rail, in km/h.
		 */
		public final int speedKmh;
		/**
		 * False for a rail a train can't speed up on, as a platform or siding, which keeps the speed of the rail before.
		 */
		public final boolean canAccelerate;
		public final SectionType type;
		/**
		 * Whether a train can run from the lower node to the higher.
		 */
		public final boolean forwards;
		/**
		 * Whether a train can run from the higher node to the lower.
		 */
		public final boolean backwards;
		/**
		 * The platform or siding on the rail, or 0.
		 */
		public final long savedRailId;

		/**
		 * @param fromAToB whether a train can run from node A to node B
		 * @param fromBToA whether a train can run from node B to node A
		 */
		public Rail(long nodeA, long nodeB, double length, long shape, int speedKmh, boolean canAccelerate, SectionType type, boolean fromAToB, boolean fromBToA, long savedRailId) {
			final boolean aLower = nodeA <= nodeB;
			node1 = aLower ? nodeA : nodeB;
			node2 = aLower ? nodeB : nodeA;
			this.length = length;
			this.shape = shape;
			this.speedKmh = speedKmh;
			this.canAccelerate = canAccelerate;
			this.type = type;
			forwards = aLower ? fromAToB : fromBToA;
			backwards = aLower ? fromBToA : fromAToB;
			this.savedRailId = savedRailId;
		}

		public UUID getKey() {
			return RailKeys.of(node1, node2);
		}

		/**
		 * @return whether the rail makes a section of its own
		 */
		public boolean isSpecial() {
			return type != SectionType.NORMAL || savedRailId != 0;
		}

		/**
		 * @return the physical speed limit, in km/h ({@link SpeedLimits})
		 */
		public int getPhysicalKmh() {
			return SpeedLimits.physicalKmh(speedKmh);
		}

		/**
		 * @return the speed planned at, in km/h ({@link SpeedLimits})
		 */
		public int getPlanKmh() {
			return SpeedLimits.planKmh(speedKmh);
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Rail)) {
				return false;
			}
			final Rail other = (Rail) object;
			return node1 == other.node1 && node2 == other.node2 && Double.doubleToLongBits(length) == Double.doubleToLongBits(other.length) && shape == other.shape && speedKmh == other.speedKmh && canAccelerate == other.canAccelerate && type == other.type && forwards == other.forwards && backwards == other.backwards && savedRailId == other.savedRailId;
		}

		@Override
		public int hashCode() {
			return Objects.hash(node1, node2, length, shape, speedKmh, canAccelerate, type, forwards, backwards, savedRailId);
		}

		@Override
		public String toString() {
			return node1 + "-" + node2;
		}
	}

	/**
	 * The rails between two cutting nodes, in order.
	 */
	public static final class Section {

		public final long hash;
		/**
		 * The hash of its content, which is its hash unless another section has the same.
		 */
		private final long baseHash;
		private final long[] nodes;
		private final Rail[] rails;
		public final int startMark;
		public final int endMark;

		private Section(long hash, long baseHash, long[] nodes, Rail[] rails, int startMark, int endMark) {
			this.hash = hash;
			this.baseHash = baseHash;
			this.nodes = nodes;
			this.rails = rails;
			this.startMark = startMark;
			this.endMark = endMark;
		}

		public long[] getNodes() {
			return nodes.clone();
		}

		public long getStart() {
			return nodes[0];
		}

		public long getEnd() {
			return nodes[nodes.length - 1];
		}

		public int getRailCount() {
			return rails.length;
		}

		/**
		 * @return the rail between node {@code index} and the one after it
		 */
		public Rail getRail(int index) {
			return rails[index];
		}

		/**
		 * @return whether the section runs along the rail from its lower node to its higher
		 */
		public boolean isAlong(int index) {
			return nodes[index] == rails[index].node1 && nodes[index + 1] == rails[index].node2;
		}

		public double getLength() {
			double length = 0;
			for (final Rail rail : rails) {
				length += rail.length;
			}
			return length;
		}

		/**
		 * @return whether a train can run all of it in the section's order
		 */
		public boolean isForwards() {
			for (int i = 0; i < rails.length; i++) {
				if (!(isAlong(i) ? rails[i].forwards : rails[i].backwards)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * @return whether a train can run all of it against the section's order
		 */
		public boolean isBackwards() {
			for (int i = 0; i < rails.length; i++) {
				if (!(isAlong(i) ? rails[i].backwards : rails[i].forwards)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * @return everything the hash is made from
		 */
		public byte[] toBytes() {
			return contentBytes(nodes, rails, startMark, endMark);
		}

		/**
		 * @return whether the content is the same, whatever the hash
		 */
		public boolean sameContent(Section other) {
			return Arrays.equals(nodes, other.nodes) && Arrays.equals(rails, other.rails) && startMark == other.startMark && endMark == other.endMark;
		}

		public static Section fromBytes(long hash, byte[] data) throws IOException {
			final DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
			if (input.readInt() != FORMAT) {
				throw new IOException("Unknown track section format");
			}
			final int railCount = input.readInt();
			if (railCount < 1 || railCount > 1 << 20) {
				throw new IOException("Bad rail count " + railCount);
			}
			final long[] nodes = new long[railCount + 1];
			for (int i = 0; i < nodes.length; i++) {
				nodes[i] = input.readLong();
			}
			final Rail[] rails = new Rail[railCount];
			for (int i = 0; i < railCount; i++) {
				final double length = input.readDouble();
				final long shape = input.readLong();
				final int speedKmh = input.readInt();
				final int flags = input.readByte();
				final SectionType type = SectionType.fromName(input.readUTF());
				final long savedRailId = input.readLong();
				// Written as along and against the section; the rail turns them into lower to higher
				rails[i] = new Rail(nodes[i], nodes[i + 1], length, shape, speedKmh, (flags & 1) != 0, type, (flags & 2) != 0, (flags & 4) != 0, savedRailId);
			}
			final int startMark = input.readInt();
			final int endMark = input.readInt();
			return new Section(hash, hasher.applyAsLong(contentBytes(nodes, rails, startMark, endMark)), nodes, rails, startMark, endMark);
		}

		private Section withHash(long newHash) {
			return newHash == hash ? this : new Section(newHash, baseHash, nodes, rails, startMark, endMark);
		}

		@Override
		public String toString() {
			return Long.toHexString(hash) + Arrays.toString(nodes);
		}
	}

	/**
	 * What an update did to the sections.
	 */
	public static final class Change {

		public final Set<Long> added = new TreeSet<>();
		public final Set<Long> removed = new TreeSet<>();

		public boolean isEmpty() {
			return added.isEmpty() && removed.isEmpty();
		}

		@Override
		public String toString() {
			return "+" + added.size() + " -" + removed.size();
		}
	}

	/**
	 * Makes the sections match the rails and marks given, changing only what differs from what it has.
	 *
	 * @param allRails every rail by its key ({@link RailKeys#of})
	 * @param allMarks every marked node with its marks
	 */
	public Change sync(Map<UUID, Rail> allRails, Map<Long, Integer> allMarks) {
		final List<Rail> put = new ArrayList<>();
		final List<UUID> removed = new ArrayList<>();
		allRails.forEach((key, rail) -> {
			if (!rail.equals(rails.get(key))) {
				put.add(rail);
			}
		});
		rails.keySet().forEach(key -> {
			if (!allRails.containsKey(key)) {
				removed.add(key);
			}
		});
		final Map<Long, Integer> markChanges = new HashMap<>();
		allMarks.forEach((node, mark) -> {
			if (!mark.equals(marks.getOrDefault(node, 0))) {
				markChanges.put(node, mark);
			}
		});
		marks.keySet().forEach(node -> {
			if (!allMarks.containsKey(node) || allMarks.get(node) == 0) {
				markChanges.put(node, 0);
			}
		});
		return update(put, removed, markChanges);
	}

	/**
	 * Changes some rails and marks, and cuts again only the sections they are in or meet.
	 *
	 * @param putRails     rails added or changed
	 * @param removedRails the keys of rails removed
	 * @param markChanges  nodes with their new marks, 0 for none
	 */
	public Change update(Collection<Rail> putRails, Collection<UUID> removedRails, Map<Long, Integer> markChanges) {
		final Set<Long> touchedNodes = new HashSet<>();
		putRails.forEach(rail -> {
			touchedNodes.add(rail.node1);
			touchedNodes.add(rail.node2);
		});
		removedRails.forEach(key -> {
			touchedNodes.add(key.getMostSignificantBits());
			touchedNodes.add(key.getLeastSignificantBits());
		});
		touchedNodes.addAll(markChanges.keySet());

		// The sections running through or ending at a touched node, before the change
		final Set<Long> freed = new HashSet<>();
		collectSections(touchedNodes, freed);

		removedRails.forEach(this::removeRail);
		putRails.forEach(rail -> {
			removeRail(rail.getKey());
			addRail(rail);
		});
		markChanges.forEach((node, mark) -> {
			if (mark == 0) {
				marks.remove(node);
			} else {
				marks.put(node, mark);
			}
		});

		// The rails to cut again: those of the freed sections still there, and the new ones
		final Set<UUID> freeRails = new HashSet<>();
		final Set<Long> bases = new HashSet<>();
		freed.forEach(hash -> {
			final Section section = sections.remove(hash);
			removeFromGroup(section);
			bases.add(section.baseHash);
			for (final Rail rail : section.rails) {
				final UUID key = rail.getKey();
				if (railSections.get(key) != null && railSections.get(key) == hash) {
					railSections.remove(key);
				}
				if (rails.containsKey(key)) {
					freeRails.add(key);
				}
			}
		});
		putRails.forEach(rail -> freeRails.add(rail.getKey()));

		final List<Section> made = cut(freeRails);
		made.forEach(section -> bases.add(section.baseHash));
		// The hashes before and after of every section sharing a content hash with one freed or made
		final Set<Long> before = new HashSet<>(freed);
		bases.forEach(base -> groups.getOrDefault(base, Collections.emptyList()).forEach(section -> before.add(section.hash)));
		made.forEach(section -> groups.computeIfAbsent(section.baseHash, key -> new ArrayList<>()).add(section));
		final Set<Long> after = new HashSet<>();
		bases.forEach(base -> after.addAll(assign(base)));
		final Change change = new Change();
		after.forEach(hash -> {
			if (!before.contains(hash)) {
				change.added.add(hash);
			}
		});
		before.forEach(hash -> {
			if (!after.contains(hash)) {
				change.removed.add(hash);
			}
		});
		return change;
	}

	private void removeFromGroup(Section section) {
		final List<Section> group = groups.get(section.baseHash);
		if (group != null) {
			group.removeIf(member -> member.hash == section.hash);
			if (group.isEmpty()) {
				groups.remove(section.baseHash);
			}
		}
	}

	/**
	 * Gives the sections sharing a content hash their hashes by the order of their content, and puts them in.
	 *
	 * @return their hashes
	 */
	private List<Long> assign(long base) {
		final List<Section> group = groups.get(base);
		if (group == null) {
			return Collections.emptyList();
		}
		group.forEach(member -> {
			if (sections.get(member.hash) == member) {
				sections.remove(member.hash);
			}
		});
		if (group.size() > 1) {
			group.sort((section1, section2) -> compareBytes(section1.toBytes(), section2.toBytes()));
			collisions++;
			System.out.println("Track sections: " + group.size() + " sections have the content hash " + Long.toHexString(base) + ", told apart by their content");
		}
		final List<Long> hashes = new ArrayList<>();
		for (int i = 0; i < group.size(); i++) {
			final Section section = group.get(i).withHash(i == 0 ? base : mix(base, i));
			group.set(i, section);
			sections.put(section.hash, section);
			for (final Rail rail : section.rails) {
				railSections.put(rail.getKey(), section.hash);
			}
			hashes.add(section.hash);
		}
		return hashes;
	}

	private static int compareBytes(byte[] bytes1, byte[] bytes2) {
		for (int i = 0; i < Math.min(bytes1.length, bytes2.length); i++) {
			final int difference = (bytes1[i] & 0xFF) - (bytes2[i] & 0xFF);
			if (difference != 0) {
				return difference;
			}
		}
		return bytes1.length - bytes2.length;
	}

	/**
	 * Takes saved sections back, then makes them match the rails and marks given: a saved section that doesn't fit the
	 * rails or the cutting rules any more is cut again with its neighbours, and rails no saved section has are cut.
	 *
	 * @return what had to change, nothing if the save matched
	 */
	public Change restore(Collection<Section> saved, Map<UUID, Rail> allRails, Map<Long, Integer> allMarks) {
		clear();
		final Set<Long> broken = new TreeSet<>();
		for (final Section section : saved) {
			boolean overlaps = false;
			for (final Rail rail : section.rails) {
				overlaps |= rails.containsKey(rail.getKey());
			}
			if (overlaps || sections.containsKey(section.hash)) {
				broken.add(section.hash);
				continue;
			}
			sections.put(section.hash, section);
			groups.computeIfAbsent(section.baseHash, key -> new ArrayList<>()).add(section);
			for (final Rail rail : section.rails) {
				addRail(rail);
				railSections.put(rail.getKey(), section.hash);
			}
			if (section.startMark != 0) {
				marks.put(section.getStart(), section.startMark);
			}
			if (section.endMark != 0) {
				marks.put(section.getEnd(), section.endMark);
			}
		}
		// Hashes given as another version gave them, as one of sections sharing a content hash
		final Change change = new Change();
		new ArrayList<>(groups.keySet()).forEach(base -> {
			final Set<Long> before = new HashSet<>();
			groups.get(base).forEach(section -> before.add(section.hash));
			final List<Long> after = assign(base);
			after.forEach(hash -> {
				if (!before.contains(hash)) {
					change.added.add(hash);
				}
			});
			before.forEach(hash -> {
				if (!after.contains(hash)) {
					change.removed.add(hash);
				}
			});
		});
		merge(change, sync(allRails, allMarks));
		// Sections cut where the rules don't cut, or not cut where they do, as by an older version
		final Set<Long> touchedNodes = new HashSet<>();
		sections.values().forEach(section -> {
			if (!fitsRules(section)) {
				touchedNodes.add(section.getStart());
			}
		});
		if (!touchedNodes.isEmpty()) {
			final Change repair = update(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), touchedNodes);
			merge(change, repair);
		}
		change.removed.addAll(broken);
		return change;
	}

	private Change update(Collection<Rail> putRails, Collection<UUID> removedRails, Map<Long, Integer> markChanges, Set<Long> extraNodes) {
		// The extra nodes are re-cut by giving them their own marks again
		final Map<Long, Integer> same = new HashMap<>(markChanges);
		extraNodes.forEach(node -> same.putIfAbsent(node, marks.getOrDefault(node, 0)));
		return update(putRails, removedRails, same);
	}

	private static void merge(Change into, Change from) {
		from.added.forEach(hash -> {
			if (!into.removed.remove(hash)) {
				into.added.add(hash);
			}
		});
		from.removed.forEach(hash -> {
			if (!into.added.remove(hash)) {
				into.removed.add(hash);
			}
		});
	}

	/**
	 * @return whether the section is cut as the rules cut: at its ends only, and its ends and marks as they are now
	 */
	private boolean fitsRules(Section section) {
		final long[] nodes = section.nodes;
		final boolean ring = nodes[0] == nodes[nodes.length - 1];
		if (!ring && (!isCut(nodes[0]) || !isCut(nodes[nodes.length - 1]))) {
			return false;
		}
		if (ring && isCut(nodes[0]) == false) {
			// A ring of uncut nodes starts at its lowest
			for (final long node : nodes) {
				if (node < nodes[0]) {
					return false;
				}
			}
		}
		for (int i = 1; i < nodes.length - 1; i++) {
			if (isCut(nodes[i])) {
				return false;
			}
		}
		return section.startMark == marks.getOrDefault(nodes[0], 0) && section.endMark == marks.getOrDefault(nodes[nodes.length - 1], 0);
	}

	public void clear() {
		rails.clear();
		nodeRails.clear();
		marks.clear();
		railSections.clear();
		sections.clear();
		groups.clear();
	}

	/**
	 * @return the sections by hash
	 */
	public Map<Long, Section> getSections() {
		return Collections.unmodifiableMap(sections);
	}

	public Section getSection(long hash) {
		return sections.get(hash);
	}

	/**
	 * @return the hash of the section a rail is in, or null
	 */
	public Long getSectionOf(UUID railKey) {
		return railSections.get(railKey);
	}

	public int getRailCount() {
		return rails.size();
	}

	/**
	 * @return a fingerprint of the whole layer, the same for the same sections whatever order they came in
	 */
	public long getFingerprint() {
		long sum = sections.size();
		for (final long hash : sections.keySet()) {
			sum += mix(hash, 0x51ED2701L);
		}
		return sum;
	}

	/**
	 * A path as the track sections it runs through, like {@link Network#toSectionPath}: each step is {section hash, 1 if
	 * along the section's order else 0}, with the stops and reversals.
	 *
	 * @param moves each rail of the path in order as {from node, to node, stop time, platform or 0}
	 */
	public Network.SectionPath toSectionPath(List<long[]> moves) {
		final Network.SectionPath sectionPath = new Network.SectionPath();
		for (final long[] move : moves) {
			final Long hash = railSections.get(RailKeys.of(move[0], move[1]));
			final Section section = hash == null ? null : sections.get(hash);
			if (section == null) {
				sectionPath.missingRails++;
				continue;
			}
			// Along the section if the move runs from the earlier of its two nodes in the section
			long along = 1;
			for (int i = 0; i < section.rails.length; i++) {
				if (section.nodes[i] == move[1] && section.nodes[i + 1] == move[0]) {
					along = 0;
					break;
				}
				if (section.nodes[i] == move[0] && section.nodes[i + 1] == move[1]) {
					break;
				}
			}
			final long[] last = sectionPath.steps.isEmpty() ? null : sectionPath.steps.get(sectionPath.steps.size() - 1);
			if (last == null || last[0] != section.hash || last[1] != along) {
				if (last != null && last[0] == section.hash) {
					sectionPath.reversals.add(sectionPath.steps.size());
				}
				sectionPath.steps.add(new long[]{section.hash, along});
			}
			if (move[2] > 0 || move[3] != 0) {
				sectionPath.stops.add(new long[]{sectionPath.steps.size() - 1, move[3], move[2]});
			}
		}
		return sectionPath;
	}

	/**
	 * @return how many times two sections came out with the same hash, which shouldn't ever happen
	 */
	public int getCollisions() {
		return collisions;
	}

	/**
	 * @return whether the node cuts the rails
	 */
	public boolean isCut(long node) {
		final List<UUID> incident = nodeRails.get(node);
		if (incident == null || incident.size() != 2 || marks.getOrDefault(node, 0) != 0) {
			return true;
		}
		for (final UUID key : incident) {
			if (rails.get(key).isSpecial()) {
				return true;
			}
		}
		return false;
	}

	private void collectSections(Set<Long> nodes, Set<Long> into) {
		nodes.forEach(node -> {
			final List<UUID> incident = nodeRails.get(node);
			if (incident != null) {
				incident.forEach(key -> {
					final Long hash = railSections.get(key);
					if (hash != null) {
						into.add(hash);
					}
				});
			}
		});
	}

	private void addRail(Rail rail) {
		final UUID key = rail.getKey();
		rails.put(key, rail);
		addIncident(rail.node1, key);
		if (rail.node2 != rail.node1) {
			addIncident(rail.node2, key);
		}
	}

	private void addIncident(long node, UUID key) {
		final List<UUID> incident = nodeRails.computeIfAbsent(node, k -> new ArrayList<>(2));
		if (!incident.contains(key)) {
			incident.add(key);
		}
	}

	private void removeRail(UUID key) {
		final Rail rail = rails.remove(key);
		if (rail == null) {
			return;
		}
		removeIncident(rail.node1, key);
		removeIncident(rail.node2, key);
	}

	private void removeIncident(long node, UUID key) {
		final List<UUID> incident = nodeRails.get(node);
		if (incident != null) {
			incident.remove(key);
			if (incident.isEmpty()) {
				nodeRails.remove(node);
			}
		}
	}

	/**
	 * Cuts the free rails into sections. The free rails are whole sections, so a walk never leaves them.
	 */
	private List<Section> cut(Set<UUID> freeRails) {
		final TreeSet<Long> nodes = new TreeSet<>();
		freeRails.forEach(key -> {
			nodes.add(key.getMostSignificantBits());
			nodes.add(key.getLeastSignificantBits());
		});
		final Set<UUID> used = new HashSet<>();
		final List<Section> made = new ArrayList<>();
		for (final long node : nodes) {
			if (isCut(node)) {
				walkAll(node, freeRails, used, made);
			}
		}
		// What is left are rings with no cutting node, each started at its lowest node
		for (final long node : nodes) {
			walkAll(node, freeRails, used, made);
		}
		return made;
	}

	private void walkAll(long node, Set<UUID> freeRails, Set<UUID> used, List<Section> made) {
		final List<UUID> incident = new ArrayList<>(nodeRails.getOrDefault(node, Collections.emptyList()));
		incident.sort(Comparator.naturalOrder());
		for (final UUID first : incident) {
			if (freeRails.contains(first) && !used.contains(first)) {
				made.add(walk(node, first, freeRails, used));
			}
		}
	}

	private Section walk(long start, UUID first, Set<UUID> freeRails, Set<UUID> used) {
		final List<Long> nodes = new ArrayList<>();
		final List<Rail> sectionRails = new ArrayList<>();
		nodes.add(start);
		long current = start;
		UUID edge = first;
		while (edge != null && used.add(edge)) {
			final Rail rail = rails.get(edge);
			sectionRails.add(rail);
			final long next = rail.node1 == current ? rail.node2 : rail.node1;
			nodes.add(next);
			current = next;
			if (next == start || isCut(next)) {
				break;
			}
			UUID following = null;
			for (final UUID candidate : nodeRails.get(next)) {
				if (!candidate.equals(edge)) {
					following = candidate;
				}
			}
			if (following != null && !freeRails.contains(following)) {
				throw new IllegalStateException("A section walk left the rails being cut at " + next);
			}
			edge = following;
		}
		// The same way round every time
		final int last = nodes.size() - 1;
		if (nodes.get(0) > nodes.get(last) || nodes.get(0).equals(nodes.get(last)) && last > 1 && nodes.get(1) > nodes.get(last - 1)) {
			Collections.reverse(nodes);
			Collections.reverse(sectionRails);
		}
		final long[] nodeArray = nodes.stream().mapToLong(Long::longValue).toArray();
		final Rail[] railArray = sectionRails.toArray(new Rail[0]);
		final int startMark = marks.getOrDefault(nodeArray[0], 0);
		final int endMark = marks.getOrDefault(nodeArray[nodeArray.length - 1], 0);
		final long base = hasher.applyAsLong(contentBytes(nodeArray, railArray, startMark, endMark));
		return new Section(base, base, nodeArray, railArray, startMark, endMark);
	}

	private static byte[] contentBytes(long[] nodes, Rail[] rails, int startMark, int endMark) {
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final DataOutputStream output = new DataOutputStream(bytes);
			output.writeInt(FORMAT);
			output.writeInt(rails.length);
			for (final long node : nodes) {
				output.writeLong(node);
			}
			for (int i = 0; i < rails.length; i++) {
				final Rail rail = rails[i];
				final boolean along = nodes[i] < nodes[i + 1];
				output.writeDouble(rail.length);
				output.writeLong(rail.shape);
				output.writeInt(rail.speedKmh);
				// The ways it can be run, along and against the section
				output.writeByte((rail.canAccelerate ? 1 : 0) | ((along ? rail.forwards : rail.backwards) ? 2 : 0) | ((along ? rail.backwards : rail.forwards) ? 4 : 0));
				output.writeUTF(rail.type.name());
				output.writeLong(rail.savedRailId);
			}
			output.writeInt(startMark);
			output.writeInt(endMark);
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * A 64 bit hash of bytes: FNV-1a, finished with the mixing step of SplitMix64.
	 */
	static long hash(byte[] data) {
		long hash = 0xCBF29CE484222325L;
		for (final byte b : data) {
			hash ^= b & 0xFF;
			hash *= 0x100000001B3L;
		}
		return mix(hash, data.length);
	}

	private static long mix(long value1, long value2) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
		hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
		return hash ^ hash >>> 31;
	}
}
