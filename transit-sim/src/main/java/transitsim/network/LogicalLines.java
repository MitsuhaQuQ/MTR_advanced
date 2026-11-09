package transitsim.network;

import java.util.*;

/**
 * The logical lines (逻辑线路) laid over the track sections (web layers, 2026-11-09): lines of the track itself, apart from
 * the operating lines that run on them. A player puts each section in at most one logical line, by a rail of it; an
 * operating line may run on the logical lines chosen for it, or, with none chosen, on those its platforms are on. A
 * connecting logical line may be run by every operating line, and so may a section in no logical line.
 * <p>
 * The result is in the form {@link TrackOwnership} gives, the operating lines that may run on each section, so pathfinding
 * goes by it the same way ({@link TrackOwnership#mayRun}).
 */
public final class LogicalLines {

	private LogicalLines() {
	}

	public static final class Result {

		/**
		 * The operating lines that may run on each section of a logical line that isn't a connecting line, by hash; none for a
		 * section of a logical line no operating line may run on.
		 */
		public final Map<Long, Set<String>> owners;
		/**
		 * The logical line of each section in one, by hash.
		 */
		public final Map<Long, Long> sectionLines;
		/**
		 * The logical lines each operating line may run on.
		 */
		public final Map<String, Set<Long>> allowed;

		private Result(Map<Long, Set<String>> owners, Map<Long, Long> sectionLines, Map<String, Set<Long>> allowed) {
			this.owners = owners;
			this.sectionLines = sectionLines;
			this.allowed = allowed;
		}
	}

	/**
	 * @param railLines     the logical line of a section, by the key of one of its rails; of several rails of one section, the
	 *                      one with the lowest key counts
	 * @param connecting    the logical lines that are connecting lines
	 * @param chosen        the logical lines chosen for each operating line; a line with none chosen gets those of its platforms
	 * @param platformLines the operating lines with a platform on each saved rail, by the saved rail's ID
	 */
	public static Result compute(TrackSections.Snapshot graph, Map<UUID, Long> railLines, Set<Long> connecting, Map<String, ? extends Collection<Long>> chosen, Map<Long, ? extends Collection<String>> platformLines) {
		final Map<Long, Long> sectionLines = new TreeMap<>();
		new TreeMap<>(railLines).forEach((railKey, line) -> {
			final TrackSections.Section section = graph.getSectionOf(railKey.getMostSignificantBits(), railKey.getLeastSignificantBits());
			if (section != null) {
				sectionLines.putIfAbsent(section.hash, line);
			}
		});

		// The logical lines of each operating line's platforms
		final Map<String, Set<Long>> ofPlatforms = new TreeMap<>();
		graph.sections.values().forEach(section -> {
			final Collection<String> lines = section.getSavedRailId() == 0 ? null : platformLines.get(section.getSavedRailId());
			final Long logical = sectionLines.get(section.hash);
			if (lines != null) {
				lines.forEach(line -> {
					final Set<Long> set = ofPlatforms.computeIfAbsent(line, key -> new TreeSet<>());
					if (logical != null) {
						set.add(logical);
					}
				});
			}
		});
		final Map<String, Set<Long>> allowed = new TreeMap<>();
		ofPlatforms.forEach((line, logical) -> allowed.put(line, Collections.unmodifiableSet(logical)));
		chosen.forEach((line, logical) -> {
			if (!logical.isEmpty()) {
				allowed.put(line, Collections.unmodifiableSet(new TreeSet<>(logical)));
			}
		});

		final Map<Long, Set<String>> owners = new HashMap<>();
		sectionLines.forEach((hash, logical) -> {
			if (connecting.contains(logical)) {
				return;
			}
			final TreeSet<String> lines = new TreeSet<>();
			allowed.forEach((line, set) -> {
				if (set.contains(logical)) {
					lines.add(line);
				}
			});
			owners.put(hash, Collections.unmodifiableSet(lines));
		});
		return new Result(owners, Collections.unmodifiableMap(sectionLines), Collections.unmodifiableMap(allowed));
	}
}
