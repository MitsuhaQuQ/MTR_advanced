package mtr.data;

import mtr.advanced.PathBoundaries;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import transitsim.network.RailGraph;
import transitsim.network.RailKeys;
import transitsim.network.RouteLegs;
import transitsim.plan.StopTimes;

import java.util.ArrayList;
import java.util.Arrays;
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
	 * A pathfinding boundary on the rail. It only splits the network into regions searches look in first, and never
	 * keeps a train off (the user's decision): keeping trains off rails is what connecting lines do. Shown on the maps
	 * with the connecting lines' rails, drawn apart from them.
	 */
	public static final int RAIL_BOUNDARY = 3;

	private final Map<Long, RoutePath> paths = new ConcurrentHashMap<>();
	private final Map<Long, Object> locks = new ConcurrentHashMap<>();
	/**
	 * The track sections, their owners and the legs found on them (stages document round 2).
	 */
	private final mtr.advanced.GraphRecords graphRecords;
	/**
	 * What the owners were last worked out from.
	 */
	private long ownersFingerprint;
	private volatile boolean ownersWorking;
	private volatile boolean ownersReady;

	public RoutePaths(mtr.advanced.GraphRecords graphRecords) {
		this.graphRecords = graphRecords;
	}

	/**
	 * @param graph the rail network, made only to tell why a path wasn't found
	 * @return the route's path, made again of its legs if any of them changed; null if the route has a path drawn by hand,
	 * which is used as it is
	 */
	public RoutePath get(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph) {
		if (!route.getPathData().isEmpty()) {
			return null;
		}
		final String key = getKey(route, dataCache);
		synchronized (locks.computeIfAbsent(route.id, id -> new Object())) {
			final RoutePath existing = paths.get(route.id);
			if (existing != null && existing.key.equals(key) && existing.holds(graphRecords) && !existing.isStale(rails)) {
				return existing;
			}
			final RoutePath found = find(route, key, dataCache, rails, graph);
			paths.put(route.id, found);
			return found;
		}
	}

	/**
	 * @return the route's path if it was made for its platforms and its legs still hold, without finding anything
	 */
	public RoutePath getFound(Route route, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final RoutePath existing = paths.get(route.id);
		return existing != null && existing.key.equals(getKey(route, dataCache)) && existing.isFound() && existing.holds(graphRecords) && !existing.isStale(rails) ? existing : null;
	}

	/**
	 * Works out the owners of the track sections again if anything they come from changed (stages document round 2): the
	 * pathfinding boundaries cut the network into regions owned by the lines with platforms in them, and each connecting
	 * line owns its own part, but the rails released from it by hand. Called on the server thread, after the sections are
	 * brought up to date; the connecting lines' paths are found here, as they run anywhere.
	 */
	public void updateOwners(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, mtr.advanced.PathBoundaries boundaries, Supplier<RailGraph<Rail>> graph) {
		updateOwners(dataCache, rails, boundaries, graph, null);
	}

	/**
	 * @param serverThread runs what is given on the server thread, to work the owners out on a thread of their own and
	 *                     put them in there; null to work them out at once
	 */
	public void updateOwners(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, mtr.advanced.PathBoundaries boundaries, Supplier<RailGraph<Rail>> graph, java.util.concurrent.Executor serverThread) {
		final mtr.advanced.GraphRecords.Legs legs = graphRecords.getLegs();
		if (legs == null || ownersWorking) {
			return;
		}
		long fingerprint = legs.snapshot.fingerprint;
		for (final long node : boundaries.nodes) {
			fingerprint += mixKey(node, 1);
		}
		for (final UUID segment : boundaries.segments) {
			fingerprint += mixKey(segment.getMostSignificantBits() * 31 + segment.getLeastSignificantBits(), 2);
		}
		for (final Route route : dataCache.routeIdMap.values()) {
			fingerprint += mixKey(route.id, (getKey(route, dataCache) + "|" + route.connectingLine + "|" + LineDuties.getLineKey(route) + "|" + getReleased(route)).hashCode());
		}
		if (fingerprint == ownersFingerprint && ownersReady) {
			return;
		}
		ownersFingerprint = fingerprint;
		if (serverThread == null) {
			graphRecords.setOwners(computeOwners(dataCache, rails, boundaries, graph, legs));
			ownersReady = true;
			return;
		}
		// The connecting lines' paths can take long to find on a large network: on a thread of their own, on copies
		ownersWorking = true;
		final DataCache dataCopy = dataCache.copyIdMaps();
		final Map<BlockPos, Map<BlockPos, Rail>> railsCopy = DataCache.copyRails(rails);
		final mtr.advanced.PathBoundaries boundariesCopy = boundaries.copy();
		final Thread thread = new Thread(() -> {
			Map<Long, Set<String>> owners = null;
			try {
				final long start = System.currentTimeMillis();
				final RailGraph<Rail>[] copyGraph = new RailGraph[1];
				owners = computeOwners(dataCopy, railsCopy, boundariesCopy, () -> {
					if (copyGraph[0] == null) {
						copyGraph[0] = DeterministicPathFinder.compile(railsCopy, boundariesCopy);
					}
					return copyGraph[0];
				}, legs);
				System.out.println("Minecraft Transit Railway: worked out the owners of the track sections in " + (System.currentTimeMillis() - start) + " ms");
			} catch (Exception e) {
				e.printStackTrace();
			}
			final Map<Long, Set<String>> finalOwners = owners;
			serverThread.execute(() -> {
				if (finalOwners != null) {
					graphRecords.setOwners(finalOwners);
					ownersReady = true;
				}
				ownersWorking = false;
			});
		}, "MTR track owners");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * @return whether the owners have been worked out once, so routes keep to their lines' track
	 */
	public boolean isOwnersReady() {
		return ownersReady;
	}

	private Map<Long, Set<String>> computeOwners(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, mtr.advanced.PathBoundaries boundaries, Supplier<RailGraph<Rail>> graph, mtr.advanced.GraphRecords.Legs legs) {
		final Map<Long, List<String>> platformLines = new HashMap<>();
		final Map<Long, String> connectingSections = new HashMap<>();
		dataCache.routeIdMap.values().stream().sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			if (route.connectingLine) {
				if (timesByPath(route)) {
					final Set<UUID> released = getReleased(route);
					forEachLineRail(route, get(route, dataCache, rails, graph), rails, (fromPos, toPos) -> {
						if (!released.contains(RailKeys.of(fromPos, toPos))) {
							final transitsim.network.TrackSections.Section section = legs.snapshot.getSectionOf(fromPos, toPos);
							if (section != null) {
								connectingSections.put(section.hash, String.valueOf(route.id));
							}
						}
					});
				}
			} else {
				route.platformIds.forEach(routePlatform -> {
					final List<String> lines = platformLines.computeIfAbsent(routePlatform.platformId, id -> new ArrayList<>());
					final String line = LineDuties.getLineKey(route);
					if (!lines.contains(line)) {
						lines.add(line);
					}
				});
			}
		});
		return transitsim.network.TrackOwnership.compute(legs.snapshot, boundaries.nodes, boundaries.segments, platformLines, connectingSections);
	}

	/**
	 * Sets the least performance of the tables of routes running by a timetable that have none (optimisation round 3):
	 * that of the slowest vehicle running the route when it is first timed, or the default vehicle's. Called on the server
	 * thread.
	 *
	 * @param vehicles the vehicles running each route ({@link #getVehicles(RailwayData)})
	 * @return the routes changed
	 */
	public static List<Route> fillMinPerformance(Collection<Route> routes, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
		final List<Route> changed = new ArrayList<>();
		// A depot often lists one way of a line and runs the other too, so the vehicles of the whole line count
		final Map<String, Set<PathTiming.Vehicle>> lineVehicles = new HashMap<>();
		routes.forEach(route -> lineVehicles.computeIfAbsent(LineDuties.getLineKey(route), key -> new HashSet<>()).addAll(vehicles.getOrDefault(route.id, Collections.emptySet())));
		routes.forEach(route -> {
			if (!timesByPath(route) || !route.timetable.isActive()) {
				return;
			}
			final Set<PathTiming.Vehicle> ofLine = lineVehicles.get(LineDuties.getLineKey(route));
			final Collection<PathTiming.Vehicle> routeVehicles = ofLine == null || ofLine.isEmpty() ? getVehicles(vehicles, route) : ofLine;
			float acceleration = Float.MAX_VALUE;
			float deceleration = Float.MAX_VALUE;
			float maxSpeed = Float.MAX_VALUE;
			for (final PathTiming.Vehicle vehicle : routeVehicles) {
				acceleration = Math.min(acceleration, vehicle.acceleration);
				deceleration = Math.min(deceleration, vehicle.deceleration);
				if (vehicle.maxSpeed > 0) {
					maxSpeed = Math.min(maxSpeed, vehicle.maxSpeed);
				}
			}
			boolean set = false;
			for (final transitsim.plan.RouteTimetable table : route.timetable.getTables()) {
				if (!table.hasMinPerformance()) {
					table.minAcceleration = acceleration;
					table.minDeceleration = deceleration;
					table.minMaxSpeed = maxSpeed == Float.MAX_VALUE ? 0 : maxSpeed;
					set = true;
				}
			}
			if (set) {
				route.timetable.revision++;
				changed.add(route);
			}
		});
		return changed;
	}

	/**
	 * @return the vehicles running the route that are slower than its timetable's least performance, to point out
	 */
	public static List<PathTiming.Vehicle> getSlowerVehicles(Route route, Map<Long, Set<PathTiming.Vehicle>> vehicles) {
		final transitsim.plan.RouteTimetable table = route.timetable;
		final List<PathTiming.Vehicle> slower = new ArrayList<>();
		if (table.hasMinPerformance()) {
			vehicles.getOrDefault(route.id, Collections.emptySet()).forEach(vehicle -> {
				if (vehicle.acceleration < table.minAcceleration - 1E-7 || vehicle.deceleration < table.minDeceleration - 1E-7 || table.minMaxSpeed > 0 && vehicle.maxSpeed > 0 && vehicle.maxSpeed < table.minMaxSpeed - 1E-6) {
					slower.add(vehicle);
				}
			});
		}
		return slower;
	}

	/**
	 * @return the rails released from connecting lines by hand, which other routes may run over
	 */
	public static Set<UUID> getReleasedRails(DataCache dataCache) {
		final Set<UUID> released = new HashSet<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (route.connectingLine) {
				released.addAll(getReleased(route));
			}
		});
		return released;
	}

	private static long mixKey(long value1, long value2) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L;
		return hash ^ hash >>> 29;
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
			forEachLineRail(route, connectingPath, rails, (fromPos, toPos) -> {
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
	 * Gives each rail of a connecting line's own part that isn't a platform rail: from its first waypoint (key node), or
	 * else the first fork after its first platform, to its last waypoint after that, or else the last fork before its last
	 * platform.
	 * A connecting line leaving a station along the main line and branching off later keeps only the branch to itself, not
	 * the main line it starts on (decoupling document 5.4: the ends joining main lines or platforms don't count); with no
	 * fork at all, everything between its first and last platform is its own, as before.
	 */
	private static void forEachLineRail(Route connectingLine, RoutePath connectingPath, Map<BlockPos, Map<BlockPos, Rail>> rails, java.util.function.BiConsumer<Long, Long> consumer) {
		if (connectingPath == null || !connectingPath.isFound()) {
			return;
		}
		final int lastStop = connectingPath.getStopRail(connectingPath.getStopCount() - 1);
		final int[] range = getOwnRange(connectingLine, connectingPath, rails, lastStop);
		for (int i = range[0]; i < range[1]; i++) {
			final PathData pathData = connectingPath.rawPath.get(i);
			if (pathData.rail.railType != RailType.PLATFORM) {
				consumer.accept(pathData.startingPos.asLong(), pathData.getLastPos().asLong());
			}
		}
	}

	/**
	 * @return the connecting line's own part of its path, as the first and one past the last index of its rails
	 */
	private static int[] getOwnRange(Route connectingLine, RoutePath connectingPath, Map<BlockPos, Map<BlockPos, Rail>> rails, int lastStop) {
		final List<BlockPos> keyNodes = new ArrayList<>();
		connectingLine.platformIds.forEach(routePlatform -> keyNodes.addAll(routePlatform.waypoints));
		if (!keyNodes.isEmpty()) {
			final BlockPos first = keyNodes.get(0);
			final BlockPos last = keyNodes.get(keyNodes.size() - 1);
			int from = -1;
			int to = -1;
			for (int i = 1; i < lastStop; i++) {
				final PathData pathData = connectingPath.rawPath.get(i);
				if (from < 0 && pathData.startingPos.equals(first)) {
					from = i;
				}
				if (from >= 0 && i >= from && pathData.getLastPos().equals(last)) {
					to = i + 1;
				}
			}
			if (from >= 0) {
				// With one key node, or none after the first, it ends at the last fork before its last platform
				return new int[]{from, to > from ? to : lastForkEnd(connectingPath, rails, from, lastStop)};
			}
		}
		int from = 1;
		for (int i = 1; i < lastStop; i++) {
			if (isFork(rails, connectingPath.rawPath.get(i).startingPos)) {
				from = i;
				break;
			}
		}
		return new int[]{from, lastForkEnd(connectingPath, rails, from, lastStop)};
	}

	private static int lastForkEnd(RoutePath connectingPath, Map<BlockPos, Map<BlockPos, Rail>> rails, int from, int lastStop) {
		for (int i = lastStop - 1; i > from; i--) {
			if (isFork(rails, connectingPath.rawPath.get(i).getLastPos())) {
				return i + 1;
			}
		}
		return lastStop;
	}

	private static boolean isFork(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		final Map<BlockPos, Rail> connections = rails == null ? null : rails.get(pos);
		return connections != null && connections.size() > 2;
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

	/**
	 * The route's path made of its legs (stages document round 2): each stop's track section, the key nodes after it, and
	 * the route's line, whose track it keeps to; a connecting line runs anywhere.
	 */
	private RoutePath find(Route route, String key, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Supplier<RailGraph<Rail>> graph) {
		final long startNanos = System.nanoTime();
		final mtr.advanced.GraphRecords.Legs legs = graphRecords.getLegs();
		if (legs == null) {
			// Before the track sections are there: found again as soon as they are
			return new RoutePath(route.id, key, Collections.emptyList(), new PathFailure(PathFailure.Type.NO_ROUTE, 0), null);
		}
		final List<SavedRailBase> platforms = new ArrayList<>();
		final List<Long> stopSections = new ArrayList<>();
		final List<long[]> waypoints = new ArrayList<>();
		final List<List<BlockPos>> waypointPositions = new ArrayList<>();
		for (final Route.RoutePlatform routePlatform : route.platformIds) {
			final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
			if (platform == null) {
				return new RoutePath(route.id, key, Collections.emptyList(), new PathFailure(PathFailure.Type.NO_ROUTE, 0), Collections.emptyList());
			}
			final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
			final transitsim.network.TrackSections.Section section = legs.snapshot.getSectionOf(positions.get(0).asLong(), positions.get(1).asLong());
			platforms.add(platform);
			stopSections.add(section == null ? 0L : section.hash);
			waypoints.add(routePlatform.waypoints.stream().mapToLong(BlockPos::asLong).toArray());
			waypointPositions.add(routePlatform.waypoints);
		}
		final String line = route.connectingLine ? null : LineDuties.getLineKey(route);
		final RouteLegs.Plan plan = RouteLegs.plan(stopSections, waypoints, line, legs);
		final List<transitsim.network.LegRouter.Leg> consulted = new ArrayList<>(plan.consulted);
		List<PathData> path = plan.isFound() ? expand(plan, legs.snapshot, platforms, rails) : Collections.emptyList();
		PathFailure failure = PathFailure.NONE;
		if (path == null) {
			// The rails copy and the sections differ for a moment: found again with the next
			path = Collections.emptyList();
			failure = new PathFailure(PathFailure.Type.NO_ROUTE, 0);
			consulted.clear();
		} else if (path.isEmpty() && platforms.size() >= 2) {
			failure = diagnose(plan, line, stopSections, waypoints, waypointPositions, platforms, legs, consulted, graph);
		}
		// The route's speed limit on its rails (line speed limits)
		PathData.capSpeed(path, route.speedLimit);
		System.out.println("Minecraft Transit Railway: " + (path.isEmpty() ? "found no path for" : "found the path of") + " route " + (route.name.isEmpty() ? String.valueOf(route.id) : route.name) + " (" + path.size() + " rails, " + plan.legs.size() + " legs) in " + (System.nanoTime() - startNanos) / 1000000 + " ms");
		return new RoutePath(route.id, key, path, failure, consulted);
	}

	/**
	 * Why no path: a key node that isn't one any more, another line's track or a connecting line in the way, or the
	 * network itself.
	 */
	private static PathFailure diagnose(RouteLegs.Plan plan, String line, List<Long> stopSections, List<long[]> waypoints, List<List<BlockPos>> waypointPositions, List<SavedRailBase> platforms, mtr.advanced.GraphRecords.Legs legs, List<transitsim.network.LegRouter.Leg> consulted, Supplier<RailGraph<Rail>> graph) {
		if (plan.badWaypoint != 0) {
			// A node with rails that isn't where sections meet isn't a key node any more
			return new PathFailure(graph.get().hasNode(plan.badWaypoint) ? PathFailure.Type.NOT_KEY_NODE : PathFailure.Type.MISSING_WAYPOINT, plan.badWaypoint);
		}
		if (line != null) {
			// Found when it may run anywhere: the first section it would have to take that its line doesn't own
			final RouteLegs.Plan anywhere = RouteLegs.plan(stopSections, waypoints, null, legs);
			consulted.addAll(anywhere.consulted);
			if (anywhere.isFound()) {
				for (final long[] step : anywhere.steps) {
					final Set<String> owners = legs.owners.get(step[0]);
					if (!transitsim.network.TrackOwnership.mayRun(owners, line)) {
						final transitsim.network.TrackSections.Section section = legs.snapshot.sections.get(step[0]);
						final long node1 = section == null ? 0 : section.getStart();
						final long node2 = section == null ? 0 : section.getEnd();
						final String owner = owners.iterator().next();
						if (owner.startsWith(transitsim.network.TrackOwnership.CONNECTING_PREFIX)) {
							return new PathFailure(PathFailure.Type.CONNECTING_LINE, Long.parseLong(owner.substring(transitsim.network.TrackOwnership.CONNECTING_PREFIX.length())), node1, node2);
						}
						return new PathFailure(PathFailure.Type.OTHER_LINE, 0, node1, node2);
					}
				}
			}
		}
		return PathFailure.diagnose(graph.get(), platforms, waypointPositions, plan.reachedStops);
	}

	/**
	 * A way out of a siding to a platform, or back from a platform to a siding, found on the track sections as a route's
	 * legs are (optimisation round 4, step 3: measured against the depot's own paths before it replaces them), and timed for
	 * a vehicle: out, from the middle of the siding to standing at the platform; back, from standing at the platform to the
	 * end of the way.
	 *
	 * @return the milliseconds, or -1 with no way
	 */
	public long measureDepotLeg(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, PathTiming.Vehicle vehicle, boolean out) {
		return measureDepotLeg(from, to, rails, vehicle, out, 0);
	}

	/**
	 * @param platformEnd the node the line runs the platform towards (at the first stop going out, at the last coming
	 *                    back), so the train runs it the same way; 0 for either
	 */
	public long measureDepotLeg(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, PathTiming.Vehicle vehicle, boolean out, long platformEnd) {
		final mtr.advanced.GraphRecords.Legs legs = graphRecords.getLegs();
		if (legs == null) {
			return -1;
		}
		final List<transitsim.network.TrackSections.Section> sections = new ArrayList<>();
		for (final SavedRailBase savedRailBase : Arrays.asList(from, to)) {
			final List<BlockPos> positions = savedRailBase.getOrderedPositions(BlockPos.ZERO, false);
			final transitsim.network.TrackSections.Section section = legs.snapshot.getSectionOf(positions.get(0).asLong(), positions.get(1).asLong());
			if (section == null) {
				return -1;
			}
			sections.add(section);
		}
		// The platform run the way the line runs it (round 4): along the section's order if that is where it ends
		final transitsim.network.TrackSections.Section platformSection = sections.get(out ? 1 : 0);
		final long[] platformNodes = platformSection.getNodes();
		final Boolean platformAlong = platformEnd == 0 ? null : platformEnd == platformNodes[platformNodes.length - 1];
		// Not kept for now: measured against the depot's paths before it replaces them
		final RouteLegs.Plan plan = RouteLegs.planBetween(sections.get(0).hash, out ? null : platformAlong, sections.get(1).hash, out ? platformAlong : null, legs.withoutKeeping());
		if (!plan.isFound()) {
			return -1;
		}
		if (Boolean.getBoolean("mtr.debugDepotLegs") && !plan.consulted.isEmpty()) {
			int domain = 0;
			for (final transitsim.network.LegRouter.Leg leg : plan.consulted) {
				domain = Math.max(domain, leg.domainSections.length);
			}
			System.out.println("Minecraft Transit Railway depot leg domain: " + domain + " sections of " + legs.snapshot.sections.size());
		}
		final List<PathData> path = expand(plan, legs.snapshot, Arrays.asList(from, to), rails);
		if (path == null || path.isEmpty()) {
			return -1;
		}
		if (Boolean.getBoolean("mtr.debugDepotLegs")) {
			final StringBuilder builder = new StringBuilder();
			double length = 0;
			int reversals = 0;
			for (int i = 0; i < path.size(); i++) {
				length += path.get(i).rail.getLength();
				if (i + 1 < path.size() && path.get(i).isOppositeRail(path.get(i + 1))) {
					reversals++;
					builder.append(" reverse@").append(Math.round(length));
				}
				if (path.get(i).savedRailBaseId != 0 && (i == 0 || path.get(i - 1).savedRailBaseId != path.get(i).savedRailBaseId)) {
					builder.append(" ").append(path.get(i).rail.railType).append(":").append(path.get(i).savedRailBaseId).append("@").append(Math.round(length));
				}
			}
			System.out.println("Minecraft Transit Railway depot leg path: " + (out ? "out" : "back") + " " + from.id + " -> " + to.id + ": " + path.size() + " rails, " + Math.round(length) + " blocks, " + reversals + " reversals, cost " + Math.round(plan.cost) + ":" + builder);
		}
		if (out) {
			return PathTiming.calculateStopTimes(vehicle, path).getLeadMillis();
		}
		final double headStart = path.get(0).rail.getLength();
		final PathTiming.Vehicle standing = new PathTiming.Vehicle((float) (2 * headStart - vehicle.trainCars * vehicle.spacing), vehicle.trainCars, vehicle.spacing, vehicle.acceleration, vehicle.deceleration, vehicle.useSpeedCurve, vehicle.transportMode, vehicle.maxSpeed);
		final List<transitsim.plan.TimeSegment> timeSegments = new ArrayList<>();
		PathTiming.generateTimeSegments(standing, path, timeSegments, new HashMap<>(), new StopTimes(), 0, 0);
		return timeSegments.isEmpty() ? -1 : (long) (timeSegments.get(timeSegments.size() - 1).endTime * 50);
	}

	/**
	 * The plan's sections as the rails a train runs, each with its stop, as {@link DeterministicPathFinder} lays a path
	 * out.
	 *
	 * @return the path, or null if a rail of the sections isn't in the rails given
	 */
	static List<PathData> expand(RouteLegs.Plan plan, transitsim.network.TrackSections.Snapshot snapshot, List<SavedRailBase> platforms, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		// The stop each step is, the last where several are the same step
		final Map<Integer, Integer> stopOfStep = new HashMap<>();
		for (int i = 0; i < plan.stopSteps.length; i++) {
			stopOfStep.put(plan.stopSteps[i], i);
		}
		final List<PathData> path = new ArrayList<>();
		int reached = 0;
		for (int i = 0; i < plan.steps.size(); i++) {
			final long[] step = plan.steps.get(i);
			final transitsim.network.TrackSections.Section section = snapshot.sections.get(step[0]);
			if (section == null) {
				return null;
			}
			final long[] nodes = section.getNodes();
			final boolean along = step[1] == 1;
			final boolean reversal = step[2] == 1;
			final Integer stop = stopOfStep.get(i);
			final boolean turningBack = section.getType() == transitsim.network.SectionType.TURN_BACK && i + 1 < plan.steps.size() && plan.steps.get(i + 1)[0] == step[0] && plan.steps.get(i + 1)[1] != step[1];
			for (int j = 0; j < section.getRailCount(); j++) {
				final long from = along ? nodes[j] : nodes[nodes.length - 1 - j];
				final long to = along ? nodes[j + 1] : nodes[nodes.length - 2 - j];
				final BlockPos startPos = BlockPos.of(from);
				final BlockPos endPos = BlockPos.of(to);
				final Rail rail = DataCache.tryGet(rails, startPos, endPos);
				if (rail == null) {
					return null;
				}
				final boolean lastRail = j == section.getRailCount() - 1;
				if (i == 0) {
					path.add(new PathData(rail, platforms.get(0).id, 0, startPos, endPos, 1));
				} else if (stop != null && lastRail && !reversal) {
					reached = stop;
					final SavedRailBase savedRailBase = platforms.get(reached);
					path.add(new PathData(rail, savedRailBase.id, savedRailBase instanceof Platform ? savedRailBase.getDwellTime() : 0, startPos, endPos, 1 + reached));
				} else if (reversal) {
					path.add(new PathData(rail, platforms.get(reached).id, 0, startPos, endPos, 1 + reached));
				} else {
					path.add(new PathData(rail, 0, turningBack ? rail.getTurnBackDwellTime() : 0, startPos, endPos, 1 + reached));
				}
			}
		}
		return path;
	}

	/**
	 * What a route's path is found from: its platforms in order with their own dwell times, and the waypoints after each;
	 * its line, whose track it keeps to, or that it is a connecting line. The route's stop settings don't change
	 * the path, as they are applied by whoever uses it.
	 */
	static String getKey(Route route, DataCache dataCache) {
		// A new speed limit finds the path again, with the limit on it
		final StringBuilder key = new StringBuilder().append(route.speedLimit).append('|').append(route.connectingLine ? "connecting" : LineDuties.getLineKey(route)).append('|');
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
		path.forEach(pathData -> copy.add(pathData.copy(pathData.savedRailBaseId, pathData.dwellTime)));
		return copy;
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
		/**
		 * @return the vehicles the route is timed with
		 */
		public Collection<PathTiming.Vehicle> getVehicles(Route route) {
			return RoutePaths.getVehicles(vehicles, route);
		}

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
		/**
		 * Every leg it was worked out from, chosen or not, or null if it is to be found again at once.
		 */
		private final List<transitsim.network.LegRouter.Leg> consulted;

		RoutePath(long routeId, String key, List<PathData> rawPath, PathFailure failure) {
			this(routeId, key, rawPath, failure, Collections.emptyList());
		}

		RoutePath(long routeId, String key, List<PathData> rawPath, PathFailure failure, List<transitsim.network.LegRouter.Leg> consulted) {
			this.routeId = routeId;
			this.key = key;
			this.rawPath = Collections.unmodifiableList(rawPath);
			this.failure = failure;
			this.consulted = consulted == null ? null : Collections.unmodifiableList(new ArrayList<>(consulted));
		}

		/**
		 * @return whether every leg it was worked out from is still the one kept, so nothing it depends on changed
		 */
		private boolean holds(mtr.advanced.GraphRecords graphRecords) {
			return consulted != null && graphRecords.getLegCache().holds(consulted);
		}

		public boolean isFound() {
			return rawPath.size() >= 2;
		}

		/**
		 * @return the turnouts each stop's train passes coming in and going out ({@link transitsim.network.ThroatRoutes},
		 * L2), with the rails meeting at each node counted on the rails as they are
		 *
		 * @param zones the interlocking zone of each rail in one, as {@code SignalBlocks.getZones} gives them
		 */
		public List<transitsim.network.ThroatRoutes.StopThroat> getThroats(int stopCount, Map<BlockPos, Map<BlockPos, Rail>> rails, Map<UUID, Long> zones) {
			final long[] nodes = getNodes();
			final int[] stopRails = new int[stopCount];
			for (int i = 0; i < stopCount; i++) {
				stopRails[i] = getStopRail(i);
			}
			return transitsim.network.ThroatRoutes.of(nodes, node -> {
				final BlockPos pos = BlockPos.of(node);
				return new double[]{pos.getX(), pos.getZ()};
			}, stopRails, node -> {
				final Map<BlockPos, Rail> connections = rails.get(BlockPos.of(node));
				return connections == null ? 0 : connections.size();
			}, rail -> zones.getOrDefault(rail, 0L));
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
			slice.set(0, first.copy(first.savedRailBaseId, 0));
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
			for (final PathTiming.Vehicle vehicle : withMinPerformance(route, vehicles)) {
				final long[][] times = getStopTimes(vehicle, route.platformIds).getFastestTimesFromFirstStop(route);
				slowest = slowest == null ? times : slowest(slowest, times);
			}
			return slowest == null ? new long[0][] : slowest;
		}

		/**
		 * @return the vehicles with the least performance the route's timetable asks for in place of their own
		 * (optimisation round 3), so the times don't change with the vehicles running it; as they are if it asks for none
		 */
		static Collection<PathTiming.Vehicle> withMinPerformance(Route route, Collection<PathTiming.Vehicle> vehicles) {
			final transitsim.plan.RouteTimetable table = route.timetable;
			if (!table.hasMinPerformance()) {
				return vehicles;
			}
			final Set<PathTiming.Vehicle> timed = new java.util.LinkedHashSet<>();
			vehicles.forEach(vehicle -> timed.add(new PathTiming.Vehicle(vehicle.railLength, vehicle.trainCars, vehicle.spacing, table.minAcceleration, table.minDeceleration, vehicle.useSpeedCurve, vehicle.transportMode, table.minMaxSpeed)));
			return timed;
		}

		private StopTimes getStopTimes(PathTiming.Vehicle vehicle, List<Route.RoutePlatform> stopSettings) {
			final List<Object> key = new ArrayList<>();
			key.add(vehicle);
			stopSettings.forEach(routePlatform -> {
				key.add(routePlatform.pass);
				key.add(routePlatform.dwellOverride);
			});
			// Where trains stop on the platforms (round 4), so the times follow a change at once
			key.add(PlatformStops.fingerprint());
			return times.computeIfAbsent(key, k -> PathTiming.calculateStopTimes(vehicle, getPath(stopSettings)));
		}

		private boolean isStale(Map<BlockPos, Map<BlockPos, Rail>> rails) {
			return RoutePaths.isStale(rawPath, rails);
		}
	}
}
