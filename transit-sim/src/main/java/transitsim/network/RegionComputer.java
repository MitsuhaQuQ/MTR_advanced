package transitsim.network;


import java.util.*;

/**
 * Works out the {@link RegionData} from a snapshot of the network, away from the server thread.
 */
public final class RegionComputer {

	/**
	 * How far a search for the platforms a stop could use may go, so a huge region can't hold up the others.
	 */
	public static final int MAX_SEARCH_CONNECTIONS = 200000;

	private RegionComputer() {
	}

	public static <R> RegionData compute(RailGraph<R> graph, Boundaries boundaries, List<SavedRail> platforms, List<SavedRail> sidings, List<RouteStops> routes) {
		final PathRegions regions = PathRegions.compute(graph, boundaries);

		final Map<Long, int[]> platformRegions = new HashMap<>();
		final Map<Long, SavedRail> platformMap = new HashMap<>();
		final Map<Long, List<SavedRail>> stationPlatforms = new HashMap<>();
		platforms.forEach(platform -> {
			platformRegions.put(platform.id, regions.railRegions(graph, platform.pos1, platform.pos2));
			platformMap.put(platform.id, platform);
			if (platform.stationId != 0) {
				stationPlatforms.computeIfAbsent(platform.stationId, key -> new ArrayList<>()).add(platform);
			}
		});
		final Map<Long, int[]> sidingRegions = new HashMap<>();
		sidings.forEach(siding -> sidingRegions.put(siding.id, regions.railRegions(graph, siding.pos1, siding.pos2)));

		final Map<Long, Map<Integer, long[]>> routeCandidates = new HashMap<>();
		routes.forEach(route -> {
			final Map<Integer, long[]> stops = new HashMap<>();
			for (int i = 0; i < route.platformIds.length; i++) {
				final long[] candidates = getCandidates(graph, regions, platformMap, stationPlatforms, platformRegions, route.platformIds, i);
				if (candidates.length > 1) {
					stops.put(i, candidates);
				}
			}
			if (!stops.isEmpty()) {
				routeCandidates.put(route.id, stops);
			}
		});

		return new RegionData(boundaries.copy(), regions.count, regions.uncutNodes, regions.uncutSegments, regions.missingNodes, regions.missingSegments, platformRegions, sidingRegions, routeCandidates);
	}

	/**
	 * The station areas aren't exact and can take in platforms of other lines, so only platforms in the same region
	 * which the train can get to from the stop before, and get from to the stop after, are offered.
	 */
	private static <R> long[] getCandidates(RailGraph<R> graph, PathRegions regions, Map<Long, SavedRail> platformMap, Map<Long, List<SavedRail>> stationPlatforms, Map<Long, int[]> platformRegions, long[] routePlatformIds, int stopIndex) {
		final SavedRail platform = platformMap.get(routePlatformIds[stopIndex]);
		if (platform == null || platform.stationId == 0) {
			return new long[0];
		}
		final int[] stopRegions = platformRegions.get(platform.id);
		final List<SavedRail> others = new ArrayList<>();
		stationPlatforms.getOrDefault(platform.stationId, Collections.emptyList()).forEach(other -> {
			if (other.id != platform.id && other.transportMode == platform.transportMode && PathRegions.shares(stopRegions, platformRegions.get(other.id))) {
				others.add(other);
			}
		});
		if (others.isEmpty()) {
			return new long[0];
		}

		final SavedRail previous = stopIndex > 0 ? platformMap.get(routePlatformIds[stopIndex - 1]) : null;
		if (previous != null && previous.id != platform.id) {
			final Set<UUID> reached = reachableRails(graph, regions, previous, PathRegions.legRegions(platformRegions.get(previous.id), stopRegions));
			others.removeIf(other -> !reached.contains(RailKeys.of(other.pos1, other.pos2)));
		}
		final SavedRail next = stopIndex + 1 < routePlatformIds.length ? platformMap.get(routePlatformIds[stopIndex + 1]) : null;
		if (next != null && next.id != platform.id) {
			final UUID nextKey = RailKeys.of(next.pos1, next.pos2);
			others.removeIf(other -> !reachableRails(graph, regions, other, PathRegions.legRegions(platformRegions.get(other.id), platformRegions.get(next.id))).contains(nextKey));
		}
		if (others.isEmpty()) {
			return new long[0];
		}

		final long[] candidates = new long[others.size() + 1];
		candidates[0] = platform.id;
		for (int i = 0; i < others.size(); i++) {
			candidates[i + 1] = others.get(i).id;
		}
		return candidates;
	}

	/**
	 * Follows the rails from a platform in both directions, the way a train can run without reversing.
	 *
	 * @return every rail reached, keyed like {@link Boundaries#segmentKey}
	 */
	private static <R> Set<UUID> reachableRails(RailGraph<R> graph, PathRegions regions, SavedRail start, BitSet allowed) {
		final Set<UUID> reached = new HashSet<>();
		final Set<Integer> visited = new HashSet<>();
		final ArrayDeque<RailGraph.Connection<R>> queue = new ArrayDeque<>();
		for (final RailGraph.Connection<R> connection : Arrays.asList(graph.find(start.pos1, start.pos2), graph.find(start.pos2, start.pos1))) {
			if (connection != null && connection.kind != RailGraph.Kind.DISABLED && visited.add(connection.index())) {
				queue.add(connection);
			}
		}
		while (!queue.isEmpty() && visited.size() < MAX_SEARCH_CONNECTIONS) {
			final RailGraph.Connection<R> connection = queue.poll();
			reached.add(RailKeys.of(connection.fromPos, connection.toPos));
			for (final RailGraph.Connection<R> next : graph.outgoing(connection.toPos)) {
				if (next.toPos != connection.fromPos && next.continuesFrom(connection) && regions.allows(next, allowed) && visited.add(next.index())) {
					queue.add(next);
				}
			}
		}
		return reached;
	}

	/**
	 * A platform or siding, copied from the server's data.
	 */
	public static final class SavedRail {

		public final long id;
		public final long pos1;
		public final long pos2;
		public final long stationId;
		public final int transportMode;

		public SavedRail(long id, long pos1, long pos2, long stationId, int transportMode) {
			this.id = id;
			this.pos1 = pos1;
			this.pos2 = pos2;
			this.stationId = stationId;
			this.transportMode = transportMode;
		}
	}

	public static final class RouteStops {

		public final long id;
		public final long[] platformIds;

		public RouteStops(long id, long[] platformIds) {
			this.id = id;
			this.platformIds = platformIds;
		}
	}
}
