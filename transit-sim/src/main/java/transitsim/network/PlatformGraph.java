package transitsim.network;

import java.util.*;

/**
 * How trains can run between platforms without turning back, which is what picking a route's platforms and making a new
 * service type go by (design document 6.12, block D).
 * <p>
 * A network is rails physically joined together, one connected part of the {@link RailGraph}. Platforms with no rail
 * joining them are in different networks by themselves, so they are never offered for each other, whatever station they
 * belong to; pathfinding boundaries only cut a network further.
 * <p>
 * Each platform is two states, one for each way along it: 0 from its first end to its second, 1 the other way. From a state
 * a train runs on without turning back, and each branch ends at the first platform it meets, which is an edge to that
 * platform in the way it was run along. A train passing stations runs along several edges in a row. Turning back at a
 * platform isn't an edge: a host may allow it, with a warning. Waypoints of routes aren't looked at.
 */
public final class PlatformGraph {

	private final long[] platformIds;
	private final long[] networks;
	/**
	 * For each state, whether a train can run along the platform that way.
	 */
	private final boolean[] usable;
	private final int[][] targets;
	private final double[][] costs;
	private final Map<Long, Integer> indices = new HashMap<>();
	private final int networkCount;

	public static final PlatformGraph EMPTY = new PlatformGraph(new long[0], new long[0], new boolean[0], new int[0][], new double[0][]);
	/**
	 * How many rails a search from one state may look at, so a huge network can't hold up the others.
	 */
	public static final int MAX_SEARCH_CONNECTIONS = 50000;

	/**
	 * @param targets for each state, the states its edges lead to
	 * @param costs   for each state, the cost of each edge
	 */
	public PlatformGraph(long[] platformIds, long[] networks, boolean[] usable, int[][] targets, double[][] costs) {
		if (networks.length != platformIds.length || usable.length != platformIds.length * 2 || targets.length != usable.length || costs.length != usable.length) {
			throw new IllegalArgumentException("Every platform needs a network and two states");
		}
		this.platformIds = platformIds;
		this.networks = networks;
		this.usable = usable;
		this.targets = targets;
		this.costs = costs;
		for (int i = 0; i < platformIds.length; i++) {
			indices.put(platformIds[i], i);
		}
		networkCount = (int) Arrays.stream(networks).distinct().count();
	}

	/**
	 * @param regions the regions of the pathfinding boundaries, or null for none
	 */
	public static <R> PlatformGraph compute(RailGraph<R> graph, PathRegions regions, List<RegionComputer.SavedRail> platforms) {
		final List<RegionComputer.SavedRail> sorted = new ArrayList<>(platforms);
		sorted.sort(Comparator.comparingLong(platform -> platform.id));
		final int count = sorted.size();
		final long[] platformIds = new long[count];
		final long[] networks = new long[count];
		final boolean[] usable = new boolean[count * 2];
		final RailGraph.Connection<?>[] stateConnections = new RailGraph.Connection<?>[count * 2];
		final Map<Integer, Integer> connectionStates = new HashMap<>();
		final Map<Long, Long> networkOfNode = findNetworks(graph);

		for (int i = 0; i < count; i++) {
			final RegionComputer.SavedRail platform = sorted.get(i);
			platformIds[i] = platform.id;
			networks[i] = networkOfNode.getOrDefault(platform.pos1, networkOfNode.getOrDefault(platform.pos2, 0L));
			for (int direction = 0; direction < 2; direction++) {
				final RailGraph.Connection<R> connection = direction == 0 ? graph.find(platform.pos1, platform.pos2) : graph.find(platform.pos2, platform.pos1);
				if (connection != null && connection.kind != RailGraph.Kind.DISABLED) {
					final int state = i * 2 + direction;
					usable[state] = true;
					stateConnections[state] = connection;
					connectionStates.put(connection.index(), state);
				}
			}
		}

		final int[][] targets = new int[count * 2][];
		final double[][] costs = new double[count * 2][];
		for (int state = 0; state < count * 2; state++) {
			final Map<Integer, Double> found = usable[state] ? search(graph, regions, sorted, stateConnections, connectionStates, state) : Collections.emptyMap();
			targets[state] = found.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
			costs[state] = Arrays.stream(targets[state]).mapToDouble(found::get).toArray();
		}
		return new PlatformGraph(platformIds, networks, usable, targets, costs);
	}

	/**
	 * Runs on from a platform state the way a train can without turning back, and stops each branch at the first platform.
	 *
	 * @return each platform state reached, with the lowest cost to it
	 */
	@SuppressWarnings("unchecked")
	private static <R> Map<Integer, Double> search(RailGraph<R> graph, PathRegions regions, List<RegionComputer.SavedRail> platforms, RailGraph.Connection<?>[] stateConnections, Map<Integer, Integer> connectionStates, int startState) {
		final RailGraph.Connection<R> start = (RailGraph.Connection<R>) stateConnections[startState];
		final RegionComputer.SavedRail startPlatform = platforms.get(startState / 2);
		final Map<Integer, Double> distances = new HashMap<>();
		final Map<Integer, RailGraph.Connection<R>> previous = new HashMap<>();
		final Set<Integer> done = new HashSet<>();
		final PriorityQueue<Object[]> queue = new PriorityQueue<>(Comparator.<Object[]>comparingDouble(entry -> (double) entry[0]).thenComparingInt(entry -> ((RailGraph.Connection<?>) entry[1]).index()));
		final Map<Integer, Double> found = new TreeMap<>();

		distances.put(start.index(), 0D);
		queue.add(new Object[]{0D, start});
		while (!queue.isEmpty() && done.size() < MAX_SEARCH_CONNECTIONS) {
			final Object[] entry = queue.poll();
			final double distance = (double) entry[0];
			final RailGraph.Connection<R> connection = (RailGraph.Connection<R>) entry[1];
			if (!done.add(connection.index())) {
				continue;
			}
			final Integer reachedState = connection == start ? null : connectionStates.get(connection.index());
			if (reachedState != null) {
				// The first platform on this branch, unless the way to it crosses a pathfinding boundary it can't
				if (!found.containsKey(reachedState) && isAllowed(graph, regions, startPlatform, platforms.get(reachedState / 2), connection, previous, start)) {
					found.put(reachedState, distance);
				}
				continue;
			}
			for (final RailGraph.Connection<R> next : graph.outgoing(connection.toPos)) {
				if (next.toPos != connection.fromPos && next.continuesFrom(connection)) {
					final double nextDistance = distance + next.cost;
					if (nextDistance < distances.getOrDefault(next.index(), Double.POSITIVE_INFINITY)) {
						distances.put(next.index(), nextDistance);
						previous.put(next.index(), connection);
						queue.add(new Object[]{nextDistance, next});
					}
				}
			}
		}
		return found;
	}

	/**
	 * A way between two platforms may only run where a route between them could, by {@link PathRegions#legRegions}. Ways
	 * passing other platforms are checked an edge at a time, which is a little looser than checking the whole way at once.
	 */
	private static <R> boolean isAllowed(RailGraph<R> graph, PathRegions regions, RegionComputer.SavedRail from, RegionComputer.SavedRail to, RailGraph.Connection<R> end, Map<Integer, RailGraph.Connection<R>> previous, RailGraph.Connection<R> start) {
		if (regions == null) {
			return true;
		}
		final BitSet allowed = PathRegions.legRegions(regions.railRegions(graph, from.pos1, from.pos2), regions.railRegions(graph, to.pos1, to.pos2));
		RailGraph.Connection<R> connection = end;
		while (connection != null && connection != start) {
			if (!regions.allows(connection, allowed)) {
				return false;
			}
			connection = previous.get(connection.index());
		}
		return true;
	}

	/**
	 * @return each node's network, numbered by its lowest node, so the same rails always get the same numbers
	 */
	private static <R> Map<Long, Long> findNetworks(RailGraph<R> graph) {
		final Map<Long, Long> parents = new HashMap<>();
		graph.connections.forEach(connection -> union(parents, connection.fromPos, connection.toPos));
		final Map<Long, Long> lowest = new HashMap<>();
		parents.keySet().forEach(node -> lowest.merge(find(parents, node), node, Math::min));
		final Map<Long, Long> networkOfNode = new HashMap<>();
		parents.keySet().forEach(node -> networkOfNode.put(node, lowest.get(find(parents, node))));
		return networkOfNode;
	}

	private static long find(Map<Long, Long> parents, long node) {
		parents.putIfAbsent(node, node);
		long root = node;
		while (parents.get(root) != root) {
			root = parents.get(root);
		}
		long current = node;
		while (current != root) {
			final long next = parents.get(current);
			parents.put(current, root);
			current = next;
		}
		return root;
	}

	private static void union(Map<Long, Long> parents, long node1, long node2) {
		final long root1 = find(parents, node1);
		final long root2 = find(parents, node2);
		if (root1 != root2) {
			parents.put(root1, root2);
		}
	}

	public int size() {
		return platformIds.length;
	}

	public long getPlatformId(int index) {
		return platformIds[index];
	}

	/**
	 * @return the index of a platform, or -1 if it isn't in the graph
	 */
	public int indexOf(long platformId) {
		return indices.getOrDefault(platformId, -1);
	}

	public long getNetwork(int index) {
		return networks[index];
	}

	/**
	 * @return the network of a platform, or 0 for a platform with no rails
	 */
	public long getNetworkOf(long platformId) {
		final int index = indexOf(platformId);
		return index < 0 ? 0 : networks[index];
	}

	public int getNetworkCount() {
		return networkCount;
	}

	public boolean isUsable(int state) {
		return usable[state];
	}

	public int[] getTargets(int state) {
		return targets[state].clone();
	}

	public double[] getCosts(int state) {
		return costs[state].clone();
	}

	/**
	 * Whether a train at one platform can get to another, passing other platforms on the way. It may leave either way, so
	 * turning back at the first platform counts, but not turning back anywhere after.
	 */
	public boolean canReach(long fromPlatformId, long toPlatformId) {
		final int from = indexOf(fromPlatformId);
		final int to = indexOf(toPlatformId);
		if (from < 0 || to < 0 || networks[from] != networks[to]) {
			return false;
		}
		final Set<Integer> visited = new HashSet<>();
		final ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int direction = 0; direction < 2; direction++) {
			if (usable[from * 2 + direction] && visited.add(from * 2 + direction)) {
				queue.add(from * 2 + direction);
			}
		}
		while (!queue.isEmpty()) {
			final int state = queue.poll();
			for (final int target : targets[state]) {
				if (target / 2 == to) {
					return true;
				}
				if (visited.add(target)) {
					queue.add(target);
				}
			}
		}
		return false;
	}
}
