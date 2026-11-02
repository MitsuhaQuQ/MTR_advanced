package mtr.advanced;

import mtr.path.RailGraph;

import java.util.*;

/**
 * The regions that pathfinding boundaries cut the rail network into (design document 5.13). Rails meeting at a node
 * are in the same region unless the node is a boundary, and a boundary rail joins no region of its own. Boundaries
 * belong to every region they touch. Without boundaries the regions are the connected parts of the network.
 */
public final class PathRegions {

	public final int count;
	/**
	 * Boundaries that don't separate anything, because the rails on both sides are still connected another way.
	 */
	public final List<Long> uncutNodes = new ArrayList<>();
	public final List<UUID> uncutSegments = new ArrayList<>();
	/**
	 * Boundaries on nodes or rails that no longer exist.
	 */
	public final List<Long> missingNodes = new ArrayList<>();
	public final List<UUID> missingSegments = new ArrayList<>();

	private final int[][] connectionRegions;
	private final Map<Long, int[]> nodeRegions = new HashMap<>();

	private static final int[] NONE = new int[0];

	private PathRegions(int count, int[][] connectionRegions) {
		this.count = count;
		this.connectionRegions = connectionRegions;
	}

	public static <R> PathRegions compute(RailGraph<R> graph, PathBoundaries boundaries) {
		final List<RailGraph.Connection<R>> connections = graph.connections;

		// Each rail is stored once in each direction, and both make one edge
		final Map<UUID, Integer> edgeIndices = new HashMap<>();
		final List<UUID> edges = new ArrayList<>();
		final int[] connectionEdges = new int[connections.size()];
		final Map<Long, List<Integer>> nodeEdges = new LinkedHashMap<>();
		for (int i = 0; i < connections.size(); i++) {
			final RailGraph.Connection<R> connection = connections.get(i);
			final UUID key = PathBoundaries.segmentKey(connection.fromPos, connection.toPos);
			Integer edge = edgeIndices.get(key);
			if (edge == null) {
				edge = edges.size();
				edgeIndices.put(key, edge);
				edges.add(key);
				nodeEdges.computeIfAbsent(connection.fromPos, pos -> new ArrayList<>()).add(edge);
				if (connection.toPos != connection.fromPos) {
					nodeEdges.computeIfAbsent(connection.toPos, pos -> new ArrayList<>()).add(edge);
				}
			}
			connectionEdges[i] = edge;
		}

		final boolean[] boundaryEdges = new boolean[edges.size()];
		for (int i = 0; i < edges.size(); i++) {
			boundaryEdges[i] = boundaries.segments.contains(edges.get(i));
		}

		final int[] parents = new int[edges.size()];
		for (int i = 0; i < parents.length; i++) {
			parents[i] = i;
		}
		nodeEdges.forEach((pos, incident) -> {
			if (!boundaries.nodes.contains(pos)) {
				int first = -1;
				for (final int edge : incident) {
					if (!boundaryEdges[edge]) {
						if (first < 0) {
							first = edge;
						} else {
							union(parents, first, edge);
						}
					}
				}
			}
		});

		// Numbered in the order of the sorted connections, so the same network always gets the same numbers
		final int[] edgeRegion = new int[edges.size()];
		final Map<Integer, Integer> rootRegions = new HashMap<>();
		for (int i = 0; i < edges.size(); i++) {
			edgeRegion[i] = boundaryEdges[i] ? -1 : rootRegions.computeIfAbsent(find(parents, i), root -> rootRegions.size());
		}
		int count = rootRegions.size();

		final Map<Long, int[]> innerNodeRegions = new HashMap<>();
		nodeEdges.forEach((pos, incident) -> innerNodeRegions.put(pos, incident.stream().mapToInt(edge -> edgeRegion[edge]).filter(region -> region >= 0).distinct().sorted().toArray()));

		final int[][] edgeRegions = new int[edges.size()][];
		for (int i = 0; i < edges.size(); i++) {
			if (boundaryEdges[i]) {
				final UUID key = edges.get(i);
				final int[] regions1 = innerNodeRegions.getOrDefault(key.getMostSignificantBits(), NONE);
				final int[] regions2 = innerNodeRegions.getOrDefault(key.getLeastSignificantBits(), NONE);
				final int[] regions = union(regions1, regions2);
				edgeRegions[i] = regions.length == 0 ? new int[]{count++} : regions;
			} else {
				edgeRegions[i] = new int[]{edgeRegion[i]};
			}
		}

		final int[][] connectionRegions = new int[connections.size()][];
		for (int i = 0; i < connections.size(); i++) {
			connectionRegions[i] = edgeRegions[connectionEdges[i]];
		}

		final PathRegions pathRegions = new PathRegions(count, connectionRegions);
		nodeEdges.forEach((pos, incident) -> {
			int[] regions = NONE;
			for (final int edge : incident) {
				regions = union(regions, edgeRegions[edge]);
			}
			pathRegions.nodeRegions.put(pos, regions);
		});

		boundaries.nodes.stream().sorted().forEach(pos -> {
			final List<Integer> incident = nodeEdges.get(pos);
			if (incident == null) {
				pathRegions.missingNodes.add(pos);
			} else if (incident.stream().filter(edge -> !boundaryEdges[edge]).count() >= 2 && innerNodeRegions.get(pos).length == 1) {
				pathRegions.uncutNodes.add(pos);
			}
		});
		boundaries.segments.stream().sorted().forEach(segment -> {
			if (!edgeIndices.containsKey(segment)) {
				pathRegions.missingSegments.add(segment);
			} else {
				final int[] regions1 = innerNodeRegions.getOrDefault(segment.getMostSignificantBits(), NONE);
				final int[] regions2 = innerNodeRegions.getOrDefault(segment.getLeastSignificantBits(), NONE);
				if (regions1.length + regions2.length > union(regions1, regions2).length) {
					pathRegions.uncutSegments.add(segment);
				}
			}
		});

		return pathRegions;
	}

	/**
	 * @return the regions of a connection of the graph these regions were computed from
	 */
	public int[] regionsOf(RailGraph.Connection<?> connection) {
		final int index = connection.index();
		return index < connectionRegions.length ? connectionRegions[index] : NONE;
	}

	/**
	 * @return the regions a node is in, more than one for a boundary, or none for a node without rails
	 */
	public int[] nodeRegions(long pos) {
		return nodeRegions.getOrDefault(pos, NONE);
	}

	/**
	 * @return the regions of a rail between two nodes
	 */
	public int[] railRegions(RailGraph<?> graph, long pos1, long pos2) {
		final RailGraph.Connection<?> connection = graph.find(pos1, pos2);
		final RailGraph.Connection<?> backwards = connection == null ? graph.find(pos2, pos1) : connection;
		return backwards == null ? NONE : regionsOf(backwards);
	}

	/**
	 * Where a search between two places may go: the regions both are in, or when they share none, the regions of
	 * either, so a route can still cross straight into the next region over a boundary.
	 *
	 * @return the regions, or null for anywhere when either place has none
	 */
	public static BitSet legRegions(int[] regions1, int[] regions2) {
		if (regions1.length == 0 || regions2.length == 0) {
			return null;
		}
		final BitSet bitSet1 = toBitSet(regions1);
		final BitSet bitSet2 = toBitSet(regions2);
		if (bitSet1.intersects(bitSet2)) {
			bitSet1.and(bitSet2);
		} else {
			bitSet1.or(bitSet2);
		}
		return bitSet1;
	}

	public boolean allows(RailGraph.Connection<?> connection, BitSet allowed) {
		if (allowed == null) {
			return true;
		}
		for (final int region : regionsOf(connection)) {
			if (allowed.get(region)) {
				return true;
			}
		}
		return false;
	}

	public static boolean shares(int[] regions1, int[] regions2) {
		for (final int region1 : regions1) {
			for (final int region2 : regions2) {
				if (region1 == region2) {
					return true;
				}
			}
		}
		return false;
	}

	private static BitSet toBitSet(int[] regions) {
		final BitSet bitSet = new BitSet();
		for (final int region : regions) {
			bitSet.set(region);
		}
		return bitSet;
	}

	private static int[] union(int[] regions1, int[] regions2) {
		if (regions2.length == 0) {
			return regions1;
		}
		if (regions1.length == 0) {
			return regions2;
		}
		final int[] all = Arrays.copyOf(regions1, regions1.length + regions2.length);
		System.arraycopy(regions2, 0, all, regions1.length, regions2.length);
		return Arrays.stream(all).distinct().sorted().toArray();
	}

	private static int find(int[] parents, int index) {
		int root = index;
		while (parents[root] != root) {
			root = parents[root];
		}
		while (parents[index] != root) {
			final int next = parents[index];
			parents[index] = root;
			index = next;
		}
		return root;
	}

	private static void union(int[] parents, int index1, int index2) {
		final int root1 = find(parents, index1);
		final int root2 = find(parents, index2);
		if (root1 != root2) {
			parents[Math.max(root1, root2)] = Math.min(root1, root2);
		}
	}
}
