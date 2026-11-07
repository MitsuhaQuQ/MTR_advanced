package transitsim.network;

import java.util.*;

/**
 * The ownership layer laid over the track sections (stages document round 2): which operating lines each track section
 * belongs to. Pathfinding boundaries cut the network into regions, and a region belongs to the lines with a platform in
 * it; a boundary rail belongs to the regions on both sides. A connecting line's own sections belong to it alone. A route
 * runs only on sections its line owns, or no line owns, or its own platforms (a hard rule, the user's decision of
 * 2026-11-08); a connecting line runs anywhere.
 * <p>
 * Kept by section hash, so a change of boundaries or routes changes only the sections whose owners change, and a search
 * that touched them is found again.
 */
public final class TrackOwnership {

	/**
	 * The owner of the sections of a connecting line's own part, as {@code "connecting:" + its key}.
	 */
	public static final String CONNECTING_PREFIX = "connecting:";

	private TrackOwnership() {
	}

	/**
	 * @param boundaryNodes      the pathfinding boundary nodes
	 * @param boundaryRails      the pathfinding boundary rails, by rail key
	 * @param platformLines      the lines with a platform on each saved rail, by the saved rail's ID
	 * @param connectingSections the connecting line each section of a connecting line's own part belongs to
	 * @return the owners of each section with any, by hash; a section with none can be run by every line
	 */
	public static Map<Long, Set<String>> compute(TrackSections.Snapshot graph, Set<Long> boundaryNodes, Set<UUID> boundaryRails, Map<Long, ? extends Collection<String>> platformLines, Map<Long, String> connectingSections) {
		// Regions: sections meeting at a node are in one unless it is a boundary node; a boundary rail joins none
		final List<Long> hashes = new ArrayList<>(new TreeSet<>(graph.sections.keySet()));
		final Map<Long, Long> parents = new HashMap<>();
		hashes.forEach(hash -> parents.put(hash, hash));
		final Set<Long> boundarySections = new HashSet<>();
		graph.sections.values().forEach(section -> {
			if (section.getRailCount() == 1 && boundaryRails.contains(section.getRail(0).getKey())) {
				boundarySections.add(section.hash);
			}
		});
		final Set<Long> nodes = new TreeSet<>();
		graph.sections.values().forEach(section -> {
			nodes.add(section.getStart());
			nodes.add(section.getEnd());
		});
		for (final long node : nodes) {
			if (boundaryNodes.contains(node)) {
				continue;
			}
			Long first = null;
			for (final long hash : graph.getSectionsAt(node)) {
				if (boundarySections.contains(hash)) {
					continue;
				}
				if (first == null) {
					first = hash;
				} else {
					union(parents, first, hash);
				}
			}
		}

		// Each region's lines, from its platforms
		final Map<Long, TreeSet<String>> regionLines = new HashMap<>();
		graph.sections.values().forEach(section -> {
			final Collection<String> lines = platformLines.get(section.getSavedRailId());
			if (section.getSavedRailId() != 0 && lines != null && !boundarySections.contains(section.hash)) {
				regionLines.computeIfAbsent(find(parents, section.hash), key -> new TreeSet<>()).addAll(lines);
			}
		});

		final Map<Long, Set<String>> owners = new HashMap<>();
		graph.sections.values().forEach(section -> {
			final String connecting = connectingSections.get(section.hash);
			if (connecting != null) {
				owners.put(section.hash, Collections.singleton(CONNECTING_PREFIX + connecting));
				return;
			}
			final TreeSet<String> lines = new TreeSet<>();
			if (boundarySections.contains(section.hash)) {
				// A boundary rail belongs to the regions it touches
				for (final long node : new long[]{section.getStart(), section.getEnd()}) {
					for (final long other : graph.getSectionsAt(node)) {
						if (!boundarySections.contains(other)) {
							lines.addAll(regionLines.getOrDefault(find(parents, other), new TreeSet<>()));
						}
					}
				}
			} else {
				lines.addAll(regionLines.getOrDefault(find(parents, section.hash), new TreeSet<>()));
			}
			if (!lines.isEmpty()) {
				owners.put(section.hash, Collections.unmodifiableSet(lines));
			}
		});
		return owners;
	}

	/**
	 * @return the sections whose owners differ between two versions of the layer
	 */
	public static Set<Long> changed(Map<Long, Set<String>> before, Map<Long, Set<String>> after) {
		final Set<Long> changed = new TreeSet<>();
		after.forEach((hash, owners) -> {
			if (!owners.equals(before.get(hash))) {
				changed.add(hash);
			}
		});
		before.keySet().forEach(hash -> {
			if (!after.containsKey(hash)) {
				changed.add(hash);
			}
		});
		return changed;
	}

	/**
	 * @param line the route's line, or null for a connecting line, which runs anywhere
	 * @return whether a route of the line may run on a section with these owners
	 */
	public static boolean mayRun(Set<String> owners, String line) {
		return line == null || owners == null || owners.contains(line);
	}

	public static byte[] toBytes(Set<String> owners) {
		final StringBuilder builder = new StringBuilder();
		owners.forEach(owner -> builder.append(owner).append('\n'));
		return builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	public static Set<String> fromBytes(byte[] data) {
		final Set<String> owners = new TreeSet<>();
		for (final String owner : new String(data, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
			if (!owner.isEmpty()) {
				owners.add(owner);
			}
		}
		return Collections.unmodifiableSet(owners);
	}

	private static long find(Map<Long, Long> parents, long id) {
		long root = id;
		while (parents.get(root) != root) {
			root = parents.get(root);
		}
		long current = id;
		while (current != root) {
			final long next = parents.get(current);
			parents.put(current, root);
			current = next;
		}
		return root;
	}

	private static void union(Map<Long, Long> parents, long id1, long id2) {
		final long root1 = find(parents, id1);
		final long root2 = find(parents, id2);
		if (root1 != root2) {
			parents.put(Math.max(root1, root2), Math.min(root1, root2));
		}
	}
}
