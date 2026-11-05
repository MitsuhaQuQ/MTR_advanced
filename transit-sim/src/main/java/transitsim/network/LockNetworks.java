package transitsim.network;

import java.util.*;

/**
 * Keeps the new running logic apart from the old (decoupling document 5.4, L2): trains running by duties lock their way
 * through station throats, trains sent out by frequency don't, and the two can't share track, as the locks only work
 * when every train on the track takes part. The track is what the depots' trains run along, their sidings' paths and
 * the paths of their duties, joined wherever they share a node; path boundaries keep paths apart, so they split it too.
 * A network with a depot on the old logic has the locks off for the depots on it running by duties.
 * <p>
 * Both hosts work it out the same way, from the paths they have.
 */
public final class LockNetworks {

	private final Map<Long, Long> parent = new HashMap<>();
	/**
	 * A node of each depot's paths, by depot, and whether it runs by the new logic.
	 */
	private final Map<Long, Long> depotNodes = new TreeMap<>();
	private final Map<Long, Boolean> newLogic = new TreeMap<>();

	/**
	 * A depot, whether its trains run by duties (the new logic) or not.
	 */
	public void addDepot(long depotId, boolean runsByNewLogic) {
		newLogic.merge(depotId, runsByNewLogic, Boolean::logicalAnd);
	}

	/**
	 * The nodes a path of one of the depot's trains passes, in order.
	 */
	public void addPath(long depotId, long[] nodes) {
		if (nodes.length == 0) {
			return;
		}
		final Long first = depotNodes.get(depotId);
		if (first == null) {
			depotNodes.put(depotId, nodes[0]);
			find(nodes[0]);
		} else {
			union(first, nodes[0]);
		}
		for (int i = 1; i < nodes.length; i++) {
			union(nodes[i - 1], nodes[i]);
		}
	}

	/**
	 * @return the depots whose network has a depot on the old logic, with those old depots, by network
	 */
	public Result result() {
		final Map<Long, Network> networks = new TreeMap<>();
		depotNodes.forEach((depotId, node) -> {
			final Network network = networks.computeIfAbsent(find(node), key -> new Network());
			(newLogic.getOrDefault(depotId, true) ? network.newDepots : network.oldDepots).add(depotId);
		});
		final Result result = new Result();
		networks.values().forEach(network -> {
			// Only depots running by duties lock throats, so only theirs are turned off
			if (!network.oldDepots.isEmpty() && !network.newDepots.isEmpty()) {
				result.mixed.add(network);
				result.offDepots.addAll(network.newDepots);
			}
		});
		result.networks = networks.size();
		return result;
	}

	private long find(long node) {
		if (!parent.containsKey(node)) {
			parent.put(node, node);
			return node;
		}
		long root = node;
		while (parent.get(root) != root) {
			root = parent.get(root);
		}
		// Shorten the way for next time
		long walk = node;
		while (walk != root) {
			final long next = parent.get(walk);
			parent.put(walk, root);
			walk = next;
		}
		return root;
	}

	private void union(long node1, long node2) {
		final long root1 = find(node1), root2 = find(node2);
		if (root1 != root2) {
			parent.put(Math.max(root1, root2), Math.min(root1, root2));
		}
	}

	public static final class Result {

		/**
		 * The depots running by duties on a network with a depot on the old logic, whose trains don't lock throats.
		 */
		public final Set<Long> offDepots = new TreeSet<>();
		/**
		 * The networks with depots on both the old logic and the new.
		 */
		public final List<Network> mixed = new ArrayList<>();
		/**
		 * How many networks there are in all.
		 */
		public int networks;

		/**
		 * @return the same networks mixed, to tell when it changes
		 */
		public String describe() {
			final StringBuilder builder = new StringBuilder();
			mixed.forEach(network -> builder.append(network.oldDepots).append(network.newDepots).append(';'));
			return builder.toString();
		}
	}

	public static final class Network {

		public final Set<Long> oldDepots = new TreeSet<>();
		public final Set<Long> newDepots = new TreeSet<>();
	}
}
