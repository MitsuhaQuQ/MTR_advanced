package transitsim.network;

import java.util.*;

/**
 * Track run on by trains of more than one planning unit (user, 2026-11-16: two systems planned apart must not share
 * track; every shared area has to be one running diagram, the part one of them runs there joined into the other's as
 * through running). A planning unit is what is planned together: the depots planning by line with the lines they run
 * (the game's LineDuties), or a depot on its own. The track is that of the units' paths, their sidings' paths, the paths
 * of their services and the other ways round turn back points; a rail is the same rail whichever way it is run.
 * <p>
 * Rails used by two units or more make areas, joined where they meet at a node; an area lists every unit on any of its
 * rails. Both hosts work it out the same way, from the paths they have. Found the same whatever order paths come in.
 */
public final class SharedAreas {

	/**
	 * The units on each rail, a rail being its two nodes, the lower first.
	 */
	private final Map<Rail, Set<String>> railUnits = new HashMap<>();

	/**
	 * The nodes a path of a unit's trains passes, in order.
	 */
	public void addPath(String unit, long[] nodes) {
		for (int i = 1; i < nodes.length; i++) {
			if (nodes[i - 1] != nodes[i]) {
				railUnits.computeIfAbsent(new Rail(nodes[i - 1], nodes[i]), key -> new TreeSet<>()).add(unit);
			}
		}
	}

	/**
	 * @return the shared areas, in a fixed order: by their units, then by their lowest rail
	 */
	public List<Area> result() {
		// Rails of two units or more, joined where they share a node
		final List<Rail> shared = new ArrayList<>();
		railUnits.forEach((rail, units) -> {
			if (units.size() > 1) {
				shared.add(rail);
			}
		});
		Collections.sort(shared);
		final Map<Long, List<Rail>> byNode = new HashMap<>();
		shared.forEach(rail -> {
			byNode.computeIfAbsent(rail.node1, key -> new ArrayList<>()).add(rail);
			byNode.computeIfAbsent(rail.node2, key -> new ArrayList<>()).add(rail);
		});
		final Set<Rail> done = new HashSet<>();
		final List<Area> areas = new ArrayList<>();
		for (final Rail first : shared) {
			if (!done.add(first)) {
				continue;
			}
			final Area area = new Area();
			final ArrayDeque<Rail> queue = new ArrayDeque<>();
			queue.add(first);
			while (!queue.isEmpty()) {
				final Rail rail = queue.poll();
				area.rails.add(rail);
				area.units.addAll(railUnits.get(rail));
				for (final long node : new long[]{rail.node1, rail.node2}) {
					for (final Rail next : byNode.get(node)) {
						if (done.add(next)) {
							queue.add(next);
						}
					}
				}
			}
			Collections.sort(area.rails);
			areas.add(area);
		}
		areas.sort(Comparator.comparing((Area area) -> String.join(",", area.units)).thenComparing(area -> area.rails.get(0)));
		return areas;
	}

	/**
	 * @return the units running on a rail, whichever way its nodes are given, or none
	 */
	public Set<String> getUnits(long node1, long node2) {
		final Set<String> units = railUnits.get(new Rail(node1, node2));
		return units == null ? Collections.emptySet() : Collections.unmodifiableSet(units);
	}

	/**
	 * Rails of more than one unit, joined where they meet.
	 */
	public static final class Area {

		/**
		 * Every unit running on any of its rails, in order.
		 */
		public final Set<String> units = new TreeSet<>();
		/**
		 * Its rails, in order.
		 */
		public final List<Rail> rails = new ArrayList<>();

		/**
		 * @return the same area, to tell when areas change
		 */
		public String describe() {
			return units + "@" + rails.get(0) + "+" + rails.size();
		}
	}

	/**
	 * A rail by its two nodes, the lower first, the same whichever way it is run.
	 */
	public static final class Rail implements Comparable<Rail> {

		public final long node1;
		public final long node2;

		public Rail(long node1, long node2) {
			this.node1 = Math.min(node1, node2);
			this.node2 = Math.max(node1, node2);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Rail && ((Rail) object).node1 == node1 && ((Rail) object).node2 == node2;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(node1) * 31 + Long.hashCode(node2);
		}

		@Override
		public int compareTo(Rail other) {
			final int byFirst = Long.compare(node1, other.node1);
			return byFirst != 0 ? byFirst : Long.compare(node2, other.node2);
		}

		@Override
		public String toString() {
			return node1 + "-" + node2;
		}
	}
}
