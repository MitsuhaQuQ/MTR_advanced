package mtr.data;

import mtr.advanced.PathBoundaries;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import transitsim.network.RailGraph;
import transitsim.network.RailKeys;
import transitsim.plan.StopTimes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The path of each route, found once and kept, rather than by each depot and siding group that runs it (decoupling
 * document 5.4, L1): the rails from its first platform to its last through its waypoints, with every stop at its
 * platform's own dwell time, so whoever uses it applies the stops of its own services ({@link RoutePath#getPath}). A
 * route's path is found again when its platforms or waypoints change, or when a rail on it changes. Depots running by a
 * timetable build their paths and duties from these; others search their routes as before. Kept for the world while
 * the server runs, not saved: depots running by a timetable work their paths out again once after loading anyway.
 * Paths are found on the path generation threads; a route's path is found by one thread at a time.
 */
public final class RoutePaths {

	public static final int DEFAULT_CARS = 8;
	public static final int DEFAULT_CAR_LENGTH = 20;
	public static final float DEFAULT_ACCELERATION = 0.002F;
	public static final int RAIL_TAKEN = 1;
	public static final int RAIL_RELEASED = 2;
	/**
	 * A pathfinding boundary on the rail, which every search keeps off; shown with the connecting lines' rails as one
	 * layer of isolation.
	 */
	public static final int RAIL_BOUNDARY = 3;

	private final Map<Long, RoutePath> paths = new ConcurrentHashMap<>();
	private final Map<Long, Object> locks = new ConcurrentHashMap<>();

	/**
	 * @param graph the rail network to search, made once a path has to be found
	 * @return the route's path, found now if there is none for its platforms and rails as they are; null if the route
	 * has a path drawn by hand, which is used as it is
	 */
	public RoutePath get(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph) {
		if (!route.getPathData().isEmpty()) {
			return null;
		}
		// The connecting lines get their paths first, as this route's path keeps off them
		final Exclusion exclusion = getExclusion(route, dataCache, rails, graph);
		final String key = getKey(route, dataCache) + exclusion.key;
		synchronized (locks.computeIfAbsent(route.id, id -> new Object())) {
			final RoutePath existing = paths.get(route.id);
			if (existing != null && existing.key.equals(key) && !existing.isStale(rails)) {
				return existing;
			}
			final RoutePath found = find(route, key, dataCache, rails, graph.get(), exclusion);
			paths.put(route.id, found);
			return found;
		}
	}

	/**
	 * @return the route's path if it was found for its platforms, the connecting lines and rails as they are now, without
	 * searching
	 */
	public RoutePath getFound(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final RoutePath existing = paths.get(route.id);
		return existing != null && existing.key.equals(getKey(route, dataCache) + getExclusion(route, dataCache, rails, null).key) && existing.isFound() && !existing.isStale(rails) ? existing : null;
	}

	/**
	 * The rails of the connecting lines of the route's kind (decoupling document 5.4, L1), which its path keeps off: those
	 * of each one's path strictly between its first and last platform, other than platform rails. A connecting line
	 * itself runs over every rail.
	 *
	 * @param graph the network to find the connecting lines' paths in, or null to take only those found already
	 */
	private Exclusion getExclusion(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph) {
		if (route.connectingLine || !timesByPath(route)) {
			return route.connectingLine ? Exclusion.CONNECTING_LINE : Exclusion.NONE;
		}
		final Exclusion exclusion = new Exclusion();
		dataCache.routeIdMap.values().stream().filter(other -> other.connectingLine && other.id != route.id && other.transportMode == route.transportMode && timesByPath(other)).sorted(Comparator.comparingLong(other -> other.id)).forEach(other -> {
			final RoutePath connectingPath = graph == null ? getFound(other, dataCache, rails) : get(other, dataCache, rails, graph);
			exclusion.add(other, connectingPath);
		});
		exclusion.finish();
		return exclusion;
	}

	/**
	 * Lets go of the paths of routes that are gone.
	 */
	public void retain(Set<Long> routeIds) {
		paths.keySet().retainAll(routeIds);
		locks.keySet().retainAll(routeIds);
	}

	/**
	 * The rails of the connecting lines of a transport mode, for the track diagram: those each one takes from other routes
	 * ({@link #RAIL_TAKEN}) and those released by hand ({@link #RAIL_RELEASED}), by rail key.
	 *
	 * @param graph the network to find the connecting lines' paths in, or null to take only those found already
	 */
	public Map<UUID, Integer> getConnectingRails(TransportMode transportMode, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph) {
		final Map<UUID, Integer> statuses = new HashMap<>();
		dataCache.routeIdMap.values().stream().filter(route -> route.connectingLine && route.transportMode == transportMode && timesByPath(route)).forEach(route -> {
			final RoutePath connectingPath = graph == null ? getFound(route, dataCache, rails) : get(route, dataCache, rails, graph);
			final Set<UUID> released = getReleased(route);
			forEachLineRail(connectingPath, (fromPos, toPos) -> {
				final UUID key = RailKeys.of(fromPos, toPos);
				statuses.merge(key, released.contains(key) ? RAIL_RELEASED : RAIL_TAKEN, Math::min);
			});
		});
		return statuses;
	}

	private static Set<UUID> getReleased(Route connectingLine) {
		final Set<UUID> released = new HashSet<>();
		connectingLine.connectingReleased.forEach(rail -> released.add(RailKeys.of(rail[0], rail[1])));
		return released;
	}

	/**
	 * Gives each rail of a connecting line's path strictly between its first and last platform that isn't a platform rail.
	 */
	private static void forEachLineRail(RoutePath connectingPath, java.util.function.BiConsumer<Long, Long> consumer) {
		if (connectingPath == null || !connectingPath.isFound()) {
			return;
		}
		final int lastStop = connectingPath.getStopRail(connectingPath.getStopCount() - 1);
		for (int i = 1; i < lastStop; i++) {
			final PathData pathData = connectingPath.rawPath.get(i);
			if (pathData.rail.railType != RailType.PLATFORM) {
				consumer.accept(pathData.startingPos.asLong(), pathData.getLastPos().asLong());
			}
		}
	}

	/**
	 * @return whether the route's times come from its own path: it has no path drawn by hand, and isn't flown or moving
	 * continuously, which are timed along their depots' paths as before
	 */
	public static boolean timesByPath(Route route) {
		return route.transportMode != TransportMode.AIRPLANE && !route.transportMode.continuousMovement && route.getPathData().isEmpty();
	}

	/**
	 * The kind of vehicle a route is timed with when no depot runs it: 8 cars of 20 m, accelerating and braking at
	 * 0.8 m/s² (0.002 m/tick², shown × 400 on the siding screen), on speed curves as a new depot runs.
	 */
	public static PathTiming.Vehicle getDefaultVehicle(TransportMode transportMode) {
		return new PathTiming.Vehicle(DEFAULT_CARS * DEFAULT_CAR_LENGTH, DEFAULT_CARS, DEFAULT_CAR_LENGTH, DEFAULT_ACCELERATION, DEFAULT_ACCELERATION, true, transportMode);
	}

	/**
	 * Read on the server thread.
	 *
	 * @return for each route, the kinds of vehicle of every siding of a depot running it, each on speed curves or not as
	 * its depot runs
	 */
	public static Map<Long, Set<PathTiming.Vehicle>> getVehicles(RailwayData railwayData) {
		final Map<Long, Set<PathTiming.Vehicle>> vehicles = new HashMap<>();
		railwayData.sidings.forEach(siding -> {
			final Depot depot = railwayData.dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null) {
				final PathTiming.Vehicle vehicle = siding.getTimingVehicle(depot.speedCurves);
				depot.getRoutes(siding.getTrainId(), railwayData.dataCache).forEach(route -> vehicles.computeIfAbsent(route.id, id -> new HashSet<>()).add(vehicle));
			}
		});
		return vehicles;
	}

	/**
	 * @param vehicles from {@link #getVehicles}
	 * @return the vehicles a route is timed with: those running it, or the default one
	 */
	public static Collection<PathTiming.Vehicle> getVehicles(Map<Long, Set<PathTiming.Vehicle>> vehicles, Route route) {
		final Set<PathTiming.Vehicle> routeVehicles = vehicles.get(route.id);
		return routeVehicles == null || routeVehicles.isEmpty() ? Collections.singleton(getDefaultVehicle(route.transportMode)) : routeVehicles;
	}

	private static RoutePath find(Route route, String key, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, Exclusion exclusion) {
		final long startNanos = System.nanoTime();
		final List<SavedRailBase> platforms = new ArrayList<>();
		final List<List<BlockPos>> waypoints = new ArrayList<>();
		for (final Route.RoutePlatform routePlatform : route.platformIds) {
			final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
			if (platform == null) {
				return new RoutePath(route.id, key, Collections.emptyList(), new PathFailure(PathFailure.Type.NO_ROUTE, 0));
			}
			platforms.add(platform);
			waypoints.add(routePlatform.waypoints);
		}
		final List<PathData> path = new ArrayList<>();
		final int reached = platforms.size() < 2 ? 0 : DeterministicPathFinder.findPath(path, graph, platforms, waypoints, 1, exclusion.isEmpty() ? null : exclusion::avoids);
		PathFailure failure = path.isEmpty() && platforms.size() >= 2 ? PathFailure.diagnose(graph, platforms, waypoints, reached) : PathFailure.NONE;
		if (path.isEmpty() && platforms.size() >= 2 && !exclusion.isEmpty()) {
			// Found only over a connecting line, the route says which one it would have to run over
			final List<PathData> unrestricted = new ArrayList<>();
			DeterministicPathFinder.findPath(unrestricted, graph, platforms, waypoints, 1);
			for (final PathData pathData : unrestricted) {
				final long fromPos = pathData.startingPos.asLong();
				final long toPos = pathData.getLastPos().asLong();
				final long connectingLineId = exclusion.getLine(fromPos, toPos);
				if (connectingLineId != 0) {
					failure = new PathFailure(PathFailure.Type.CONNECTING_LINE, connectingLineId, fromPos, toPos);
					break;
				}
			}
		}
		System.out.println("Minecraft Transit Railway: " + (path.isEmpty() ? "found no path for" : "found the path of") + " route " + (route.name.isEmpty() ? String.valueOf(route.id) : route.name) + " (" + path.size() + " rails) in " + (System.nanoTime() - startNanos) / 1000000 + " ms");
		return new RoutePath(route.id, key, path, failure);
	}

	/**
	 * What a route's path is found from: its platforms in order with their own dwell times, and the waypoints after each;
	 * the connecting lines it keeps off are added to it ({@link Exclusion#key}). The route's stop settings don't change
	 * the path, as they are applied by whoever uses it.
	 */
	static String getKey(Route route, DataCache dataCache) {
		final StringBuilder key = new StringBuilder();
		route.platformIds.forEach(routePlatform -> {
			final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
			key.append(routePlatform.platformId).append('/').append(platform == null ? -1 : platform.getDwellTime()).append('[');
			routePlatform.waypoints.forEach(waypoint -> key.append(waypoint.asLong()).append(','));
			key.append(']');
		});
		return key.toString();
	}

	/**
	 * @return whether a rail of the path is gone or isn't the same any more, by the same rules as a siding's path
	 */
	static boolean isStale(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		return path.stream().anyMatch(pathData -> {
			final Rail rail = DataCache.tryGet(rails, pathData.startingPos, pathData.getLastPos());
			return rail == null || rail.railType != pathData.rail.railType || rail.facingStart != pathData.rail.facingStart || rail.facingEnd != pathData.rail.facingEnd || Math.abs(rail.getLength() - pathData.rail.getLength()) > 0.01;
		});
	}

	/**
	 * @return stop by stop, the later arrival and the later departure, or -1 where either doesn't reach the stop
	 */
	static long[][] slowest(long[][] times1, long[][] times2) {
		final long[][] slowest = new long[Math.min(times1.length, times2.length)][];
		for (int i = 0; i < slowest.length; i++) {
			final boolean reached = times1[i][0] >= 0 && times2[i][0] >= 0;
			slowest[i] = reached ? new long[]{Math.max(times1[i][0], times2[i][0]), Math.max(times1[i][1], times2[i][1])} : new long[]{-1, -1};
		}
		return slowest;
	}

	static List<PathData> copy(List<PathData> path) {
		final List<PathData> copy = new ArrayList<>(path.size());
		path.forEach(pathData -> copy.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex)));
		return copy;
	}

	/**
	 * The rails a route's path keeps off, by the connecting line each belongs to, in both directions.
	 */
	static final class Exclusion {

		static final Exclusion NONE = new Exclusion();
		/**
		 * A connecting line runs over every rail, and its key says it is one, so it is found again when that changes.
		 */
		static final Exclusion CONNECTING_LINE = new Exclusion("|connecting");

		private final Map<Long, Map<Long, Long>> lines = new HashMap<>();
		private final StringBuilder keyBuilder = new StringBuilder();
		/**
		 * The connecting lines and their rails, so the route is found again when one changes.
		 */
		String key;

		Exclusion() {
			key = "";
		}

		private Exclusion(String key) {
			this.key = key;
		}

		/**
		 * @param connectingPath the connecting line's path, or null if it has none yet
		 */
		void add(Route connectingLine, RoutePath connectingPath) {
			final long connectingLineId = connectingLine.id;
			final Set<UUID> released = getReleased(connectingLine);
			keyBuilder.append("|x").append(connectingLineId).append(':');
			if (connectingPath == null || !connectingPath.isFound()) {
				keyBuilder.append('-');
				return;
			}
			final long[] hash = {17};
			forEachLineRail(connectingPath, (fromPos, toPos) -> {
				if (!released.contains(RailKeys.of(fromPos, toPos))) {
					lines.computeIfAbsent(fromPos, pos -> new HashMap<>()).put(toPos, connectingLineId);
					lines.computeIfAbsent(toPos, pos -> new HashMap<>()).put(fromPos, connectingLineId);
					hash[0] = hash[0] * 31 + fromPos;
					hash[0] = hash[0] * 31 + toPos;
				}
			});
			keyBuilder.append(Long.toHexString(hash[0]));
		}

		void finish() {
			key = keyBuilder.toString();
		}

		boolean isEmpty() {
			return lines.isEmpty();
		}

		boolean avoids(RailGraph.Connection<Rail> connection) {
			return getLine(connection.fromPos, connection.toPos) != 0;
		}

		/**
		 * @return the connecting line a rail belongs to, or 0
		 */
		long getLine(long fromPos, long toPos) {
			final Map<Long, Long> connections = lines.get(fromPos);
			final Long line = connections == null ? null : connections.get(toPos);
			return line == null ? 0 : line;
		}
	}

	/**
	 * The route paths as one path generation thread sees them: its own copy of the rails, and the network to search made
	 * once, only if a path has to be found.
	 */
	public static final class Search {

		private final RoutePaths routePaths;
		private final Map<BlockPos, Map<BlockPos, Rail>> rails;
		private final PathBoundaries boundaries;
		private final Map<Long, Set<PathTiming.Vehicle>> vehicles;
		private RailGraph<Rail> graph;

		/**
		 * @param graph    the network already made for the depot, or null to make one when needed
		 * @param vehicles the vehicles running each route ({@link RoutePaths#getVehicles(RailwayData)})
		 */
		public Search(RoutePaths routePaths, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, PathBoundaries boundaries, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
			this.routePaths = routePaths;
			this.rails = rails;
			this.graph = graph;
			this.boundaries = boundaries;
			this.vehicles = vehicles;
		}

		/**
		 * See {@link RoutePaths#getConnectingRails}.
		 */
		public Map<UUID, Integer> getConnectingRails(TransportMode transportMode, DataCache dataCache) {
			return routePaths.getConnectingRails(transportMode, dataCache, rails, this::getGraph);
		}

		/**
		 * @return the route's fastest times ({@link RoutePath#getFastestTimes}), finding its path if needed; null if the
		 * route has a path drawn by hand
		 */
		public long[][] getFastestTimes(Route route, DataCache dataCache) {
			final RoutePath routePath = get(route, dataCache);
			return routePath == null ? null : routePath.getFastestTimes(route, RoutePaths.getVehicles(vehicles, route));
		}

		/**
		 * See {@link RoutePaths#get}.
		 */
		public RoutePath get(Route route, DataCache dataCache) {
			return routePaths.get(route, dataCache, rails, this::getGraph);
		}

		private synchronized RailGraph<Rail> getGraph() {
			if (graph == null) {
				graph = DeterministicPathFinder.compile(rails, boundaries);
			}
			return graph;
		}
	}

	/**
	 * A route's path as found. It isn't changed once made, and hands out copies, as users change the rails of theirs.
	 */
	public static final class RoutePath {

		public final long routeId;
		/**
		 * Why no path was found, or {@link PathFailure#NONE}.
		 */
		public final PathFailure failure;
		private final String key;
		/**
		 * Every stop at its platform's own dwell time, stop {@code i} on the rail with stop index {@code i + 1}.
		 */
		private final List<PathData> rawPath;
		private final Map<List<Object>, StopTimes> times = new ConcurrentHashMap<>();

		RoutePath(long routeId, String key, List<PathData> rawPath, PathFailure failure) {
			this.routeId = routeId;
			this.key = key;
			this.rawPath = Collections.unmodifiableList(rawPath);
			this.failure = failure;
		}

		public boolean isFound() {
			return rawPath.size() >= 2;
		}

		/**
		 * @return the nodes the path passes in order, for the map and the track diagram
		 */
		public long[] getNodes() {
			final long[] nodes = new long[rawPath.size() + 1];
			for (int i = 0; i < rawPath.size(); i++) {
				nodes[i] = rawPath.get(i).startingPos.asLong();
			}
			nodes[rawPath.size()] = rawPath.get(rawPath.size() - 1).getLastPos().asLong();
			return nodes;
		}

		/**
		 * @return how many of the route's stops the path numbers, as the last rail has the last stop's number
		 */
		int getStopCount() {
			return rawPath.isEmpty() ? 0 : rawPath.get(rawPath.size() - 1).stopIndex;
		}

		/**
		 * @param stopSettings how the services stop at each of the route's platforms, as {@code route.platformIds}
		 * @return a copy of the path from the first stop to the last with those stops
		 */
		public List<PathData> getPath(List<Route.RoutePlatform> stopSettings) {
			final List<PathData> path = copy(rawPath);
			PathFinder.applyStopSettings(path, stopSettings);
			return path;
		}

		/**
		 * @return a copy of the path from one stop to a later one, every stop at its platform's own dwell time, or empty
		 * if the path doesn't have them; it starts standing at the first stop, as a path found from there would, so the
		 * stops of a service are applied to it the same way
		 */
		public List<PathData> getSlice(int firstStop, int lastStop) {
			final int start = getStopRail(firstStop);
			final int end = getStopRail(lastStop);
			if (start < 0 || end < start) {
				return Collections.emptyList();
			}
			final List<PathData> slice = copy(rawPath.subList(start, end + 1));
			final PathData first = slice.get(0);
			slice.set(0, new PathData(first.rail, first.savedRailBaseId, 0, first.startingPos, first.getLastPos(), first.stopIndex));
			return slice;
		}

		/**
		 * @return the rail of the path where a train reaches the route's stop, the first numbered for it (the first stop is
		 * the first rail), or -1
		 */
		public int getStopRail(int stop) {
			for (int i = 0; i < rawPath.size(); i++) {
				final PathData pathData = rawPath.get(i);
				if (pathData.stopIndex == stop + 1 && pathData.savedRailBaseId != 0) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * The one source of a route's fastest times (decoupling document 5.4, L1): along its own path with its own stops,
		 * for the slowest of the vehicles, stop by stop, so a timetable made from them can be kept by every train running
		 * it. The times along the path are kept for each vehicle and the route's stops.
		 *
		 * @param vehicles the vehicles running the route ({@link #getVehicles(Map, Route)})
		 * @return the fastest arrival and departure at each stop counted from leaving the first, or -1 for stops not found,
		 * as {@link StopTimes#getFastestTimesFromFirstStop}; empty if there is no path
		 */
		public long[][] getFastestTimes(Route route, Collection<PathTiming.Vehicle> vehicles) {
			if (!isFound()) {
				return new long[0][];
			}
			long[][] slowest = null;
			for (final PathTiming.Vehicle vehicle : vehicles) {
				final long[][] times = getStopTimes(vehicle, route.platformIds).getFastestTimesFromFirstStop(route);
				slowest = slowest == null ? times : slowest(slowest, times);
			}
			return slowest == null ? new long[0][] : slowest;
		}

		private StopTimes getStopTimes(PathTiming.Vehicle vehicle, List<Route.RoutePlatform> stopSettings) {
			final List<Object> key = new ArrayList<>();
			key.add(vehicle);
			stopSettings.forEach(routePlatform -> {
				key.add(routePlatform.pass);
				key.add(routePlatform.dwellOverride);
			});
			return times.computeIfAbsent(key, k -> PathTiming.calculateStopTimes(vehicle, getPath(stopSettings)));
		}

		private boolean isStale(Map<BlockPos, Map<BlockPos, Rail>> rails) {
			return RoutePaths.isStale(rawPath, rails);
		}
	}
}
