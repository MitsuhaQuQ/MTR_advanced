package transitsim.network;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Where the rail network is cut for pathfinding (design document 5.13): at a node, or along the rail between two
 * connected nodes. Routes don't search across a boundary, and the regions are what is left between them.
 */
public class Boundaries {

	/**
	 * Node IDs.
	 */
	public final Set<Long> nodes = new HashSet<>();
	/**
	 * Rails between two nodes, both directions together, keyed by {@link RailKeys#of}.
	 */
	public final Set<UUID> segments = new HashSet<>();

	public boolean isEmpty() {
		return nodes.isEmpty() && segments.isEmpty();
	}

	public boolean isSegment(long node1, long node2) {
		return !segments.isEmpty() && segments.contains(RailKeys.of(node1, node2));
	}

	public Boundaries copy() {
		final Boundaries copy = new Boundaries();
		copy.nodes.addAll(nodes);
		copy.segments.addAll(segments);
		return copy;
	}
}
