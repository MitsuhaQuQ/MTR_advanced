package mtr.data;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Lookups built from the sets in {@link RailwayData} (or {@code ClientData} on the client): objects by ID, and the
 * relations between them, such as the station of a platform, the depot of a siding or the stations connected to a
 * station. Nothing here is saved; call {@link #sync()} after the sets change.
 */
public class DataCache {

	private long lastRefreshedTime;

	public final Map<Long, Station> stationIdMap = new HashMap<>();
	public final Map<Long, Platform> platformIdMap = new HashMap<>();
	/**
	 * The settings of the dimension's whole network (optimisation round 4): the server's own, or what it sent the client.
	 */
	public final NetworkSettings networkSettings = new NetworkSettings();
	public final Map<Long, Siding> sidingIdMap = new HashMap<>();
	public final Map<Long, Route> routeIdMap = new HashMap<>();
	public final Map<Long, Depot> depotIdMap = new HashMap<>();
	public final Map<Long, LiftServer> liftsServerIdMap = new HashMap<>();

	public final Map<Long, Station> platformIdToStation = new HashMap<>();
	public final Map<Long, Depot> sidingIdToDepot = new HashMap<>();
	public final Map<Long, Depot> routeIdToOneDepot = new HashMap<>();
	public final Map<Station, Set<Station>> stationIdToConnectingStations = new HashMap<>();
	public final Map<BlockPos, Station> blockPosToStation = new HashMap<>();
	/**
	 * Platforms in more than one station's area, at their heights, with no station set for them, and the station each was
	 * given by the nearest platform ({@link #mapPlatformsToStations}); those the station was chosen for by its ID alone, as
	 * none of the stations had a platform to go by, in {@link #platformsByIdAlone}.
	 */
	public final Map<Long, Station> overlappingPlatforms = new TreeMap<>();
	public final Set<Long> platformsByIdAlone = new TreeSet<>();
	public final Long2LongOpenHashMap blockPosToPlatformId = new Long2LongOpenHashMap();
	public final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<RailwayDataRouteFinderModule.ConnectionDetails>> platformConnections = new Long2ObjectOpenHashMap<>();

	protected final Set<Station> stations;
	protected final Set<Platform> platforms;
	protected final Set<Siding> sidings;
	protected final Set<Route> routes;
	protected final Set<Depot> depots;
	private final Set<LiftServer> liftsServer;

	/**
	 * Counts up each time the data is synced after a change, so whoever keeps a copy of it knows to look again.
	 */
	private int version;

	/**
	 * How much more a difference in height counts than one across, in finding the nearest platform: stations one above
	 * another are much the same across and apart in height.
	 */
	public static final int HEIGHT_WEIGHT = 4;
	private static final int AREA_CELL_BITS = 8;
	private static final int MAX_AREA_CELLS = 1024;

	public DataCache(Set<Station> stations, Set<Platform> platforms, Set<Siding> sidings, Set<Route> routes, Set<Depot> depots, Set<LiftServer> lifts) {
		this.stations = stations;
		this.platforms = platforms;
		this.sidings = sidings;
		this.routes = routes;
		this.depots = depots;
		liftsServer = lifts;
	}

	public int getVersion() {
		return version;
	}

	/**
	 * The maps by ID as they are now, for work away from the server thread (path generation), which must not see them
	 * emptied and filled again by {@link #sync} part of the way through. The data in them is the same, not copied, and the
	 * copy is never synced.
	 */
	public DataCache copyIdMaps() {
		final DataCache copy = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		copy.stationIdMap.putAll(stationIdMap);
		copy.platformIdMap.putAll(platformIdMap);
		copy.sidingIdMap.putAll(sidingIdMap);
		copy.routeIdMap.putAll(routeIdMap);
		copy.depotIdMap.putAll(depotIdMap);
		copy.platformIdToStation.putAll(platformIdToStation);
		return copy;
	}

	/**
	 * The rails as they are now, for work away from the server thread (path generation), which must not see rails laid or
	 * taken away part of the way through. The rails themselves are the same, as a rail is built again rather than changed.
	 */
	public static Map<BlockPos, Map<BlockPos, Rail>> copyRails(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final Map<BlockPos, Map<BlockPos, Rail>> copy = new HashMap<>(rails.size());
		rails.forEach((pos, connections) -> copy.put(pos, new HashMap<>(connections)));
		return copy;
	}

	public final void sync() {
		version++;
		try {
			mapIds(stationIdMap, stations);
			mapIds(platformIdMap, platforms);
			// Platforms set to pass faster or slower than the default, which paths look up as they run (round 3b)
			PlatformSpeeds.update(this, platforms);
			// Platforms stopping trains other than at the exit end, which paths look up (round 4)
			PlatformStops.update(this, platforms);
			mapIds(sidingIdMap, sidings);
			mapIds(routeIdMap, routes);
			mapIds(depotIdMap, depots);
			mapIds(liftsServerIdMap, liftsServer);
			Route.applyLineColors(routes);
			routes.forEach(route -> route.timetable.clearLinkedServices());
			routes.forEach(route -> route.timetable.getTables().forEach(table -> table.links.values().forEach(link -> {
				final Route linkedRoute = routeIdMap.get(link.routeId);
				if (linkedRoute != null) {
					linkedRoute.timetable.addLinkedService(link.service);
				}
			})));

			routeIdToOneDepot.clear();
			routes.forEach(route -> route.platformIds.removeIf(platformId -> !platformIdMap.containsKey(platformId.platformId)));
			depots.forEach(depot -> {
				depot.routeIds.removeIf(routeId -> routeIdMap.get(routeId) == null);
				depot.routeIds.forEach(routeId -> routeIdToOneDepot.put(routeId, depot));
				depot.clearSharedServices();
			});
			// Depots running a route in common share its services, so each service is run by one train
			final List<Depot> depotList = new ArrayList<>(depots);
			for (int i = 0; i < depotList.size(); i++) {
				for (int j = i + 1; j < depotList.size(); j++) {
					final Depot depot1 = depotList.get(i), depot2 = depotList.get(j);
					if (depot1.routeIds.stream().anyMatch(depot2.routeIds::contains)) {
						depot1.shareServicesWith(depot2);
					}
				}
			}

			platformConnections.clear();
			routes.forEach(route -> {
				final Depot depot = routeIdToOneDepot.get(route.id);
				if (depot != null) {
					for (int i = 1; i < route.platformIds.size(); i++) {
						final long prevPlatformId = route.platformIds.get(i - 1).platformId;
						final long thisPlatformId = route.platformIds.get(i).platformId;
						final Platform prevPlatform = platformIdMap.get(prevPlatformId);
						final Platform thisPlatform = platformIdMap.get(thisPlatformId);
						if (prevPlatform != null && thisPlatform != null) {
							final float duration = tryGet(depot.platformTimes, prevPlatformId, thisPlatformId, 0F);
							if (duration > 0) {
								final long thisPlatformPosLong = thisPlatform.getMidPos().asLong();
								put(platformConnections, prevPlatform.getMidPos().asLong(), thisPlatformPosLong, oldValue -> {
									final int newValue = Math.round(duration);
									if (oldValue == null) {
										final RailwayDataRouteFinderModule.ConnectionDetails connectionDetails = new RailwayDataRouteFinderModule.ConnectionDetails(prevPlatform);
										connectionDetails.addDurationInfo(route.id, newValue);
										return connectionDetails;
									} else {
										oldValue.addDurationInfo(route.id, newValue);
										return oldValue;
									}
								});
								if (i == route.platformIds.size() - 1 && !platformConnections.containsKey(thisPlatformPosLong)) {
									platformConnections.put(thisPlatformPosLong, new Long2ObjectOpenHashMap<>());
								}
							}
						}
					}
				}
			});

			stationIdToConnectingStations.clear();
			stations.forEach(station -> stationIdToConnectingStations.put(station, new HashSet<>()));
			final List<Station> sortedStations = new ArrayList<>();
			stations.forEach(station -> {
				if (AreaBase.nonNullCorners(station)) {
					sortedStations.add(station);
				}
			});
			sortedStations.sort(Comparator.comparingInt(DataCache::getMinX));
			for (int i = 0; i < sortedStations.size(); i++) {
				final Station station1 = sortedStations.get(i);
				final int maxX = getMaxX(station1);
				for (int j = i + 1; j < sortedStations.size(); j++) {
					final Station station2 = sortedStations.get(j);
					if (getMinX(station2) > maxX) {
						break;
					}
					if (station1.intersecting(station2)) {
						stationIdToConnectingStations.get(station1).add(station2);
						stationIdToConnectingStations.get(station2).add(station1);
					}
				}
			}

			mapPlatformsToStations();
			mapSavedRailIdToStation(sidingIdToDepot, sidings, depots);

			blockPosToPlatformId.clear();
			blockPosToStation.clear();
			syncAdditional();
		} catch (Exception e) {
			e.printStackTrace();
		}

		lastRefreshedTime = System.currentTimeMillis();
	}

	public boolean needsRefresh(long cachedRefreshTime) {
		return lastRefreshedTime > cachedRefreshTime;
	}

	protected void syncAdditional() {
	}

	public static <T, U> U tryGet(Map<T, Map<T, U>> map, T key1, T key2, U defaultValue) {
		final U result = tryGet(map, key1, key2);
		return result == null ? defaultValue : result;
	}

	public static <T, U> U tryGet(Map<T, Map<T, U>> map, T key1, T key2) {
		final Map<T, U> innerMap = map.get(key1);
		if (innerMap == null) {
			return null;
		} else {
			return innerMap.get(key2);
		}
	}

	public static <U> U tryGet(Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<U>> map, long key1, long key2) {
		final Long2ObjectOpenHashMap<U> innerMap = map.get(key1);
		if (innerMap == null) {
			return null;
		} else {
			return innerMap.get(key2);
		}
	}

	public static int tryGet2(Long2ObjectOpenHashMap<Long2IntOpenHashMap> map, long key1, long key2, int defaultValue) {
		final Long2IntOpenHashMap innerMap = map.get(key1);
		if (innerMap == null) {
			return defaultValue;
		} else {
			return innerMap.getOrDefault(key2, defaultValue);
		}
	}

	public static <U> void put(Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<U>> map, long key1, long key2, Function<U, U> putValue) {
		final Long2ObjectOpenHashMap<U> innerMap = map.get(key1);
		final Long2ObjectOpenHashMap<U> newInnerMap;
		if (innerMap == null) {
			newInnerMap = new Long2ObjectOpenHashMap<>();
			map.put(key1, newInnerMap);
		} else {
			newInnerMap = innerMap;
		}
		newInnerMap.put(key2, putValue.apply(newInnerMap.get(key2)));
	}

	public static void put2(Long2ObjectOpenHashMap<Long2IntOpenHashMap> map, long key1, long key2, Function<Integer, Integer> putValue) {
		final Long2IntOpenHashMap innerMap = map.get(key1);
		final Long2IntOpenHashMap newInnerMap;
		if (innerMap == null) {
			newInnerMap = new Long2IntOpenHashMap();
			map.put(key1, newInnerMap);
		} else {
			newInnerMap = innerMap;
		}
		newInnerMap.put(key2, putValue.apply(newInnerMap.get(key2)).intValue());
	}

	protected static <U extends NameColorDataBase> void mapIds(Map<Long, U> map, Set<U> source) {
		map.clear();
		source.forEach(data -> map.put(data.id, data));
	}

	/**
	 * Gives each platform its station: the one set for it, wherever that is; else the one whose area takes it in, at its
	 * height where the area has heights; and with more than one, the one with a platform nearest to it, the height counting
	 * {@link #HEIGHT_WEIGHT} times, going by the platforms whose station is already known (set, or in one station's area
	 * alone), so it doesn't go round in a circle. Each one the same however the data is ordered.
	 */
	private void mapPlatformsToStations() {
		platformIdToStation.clear();
		overlappingPlatforms.clear();
		platformsByIdAlone.clear();
		final List<Station> sortedStations = new ArrayList<>(stations);
		sortedStations.sort(Comparator.comparingLong(station -> station.id));
		final List<Platform> sortedPlatforms = new ArrayList<>(platforms);
		sortedPlatforms.sort(Comparator.comparingLong(platform -> platform.id));
		final Map<Station, List<BlockPos>> known = new HashMap<>();
		final List<Platform> undecided = new ArrayList<>();
		final Map<Long, List<Station>> candidatesOf = new HashMap<>();
		for (final Platform platform : sortedPlatforms) {
			final Station set = stationIdMap.get(platform.stationId);
			if (set != null) {
				platformIdToStation.put(platform.id, set);
				known.computeIfAbsent(set, key -> new ArrayList<>()).add(platform.getMidPos());
				continue;
			}
			final List<Station> candidates = getCandidates(sortedStations, platform.getMidPos());
			if (candidates.size() == 1) {
				platformIdToStation.put(platform.id, candidates.get(0));
				known.computeIfAbsent(candidates.get(0), key -> new ArrayList<>()).add(platform.getMidPos());
			} else if (candidates.size() > 1) {
				undecided.add(platform);
				candidatesOf.put(platform.id, candidates);
			}
		}
		for (final Platform platform : undecided) {
			final List<Station> candidates = candidatesOf.get(platform.id);
			noteByIdAlone(platform.id, candidates.stream().noneMatch(known::containsKey));
			final Station station = nearestByPlatforms(candidates, platform.getMidPos(), known);
			platformIdToStation.put(platform.id, station);
			overlappingPlatforms.put(platform.id, station);
		}
	}

	/**
	 * @return the stations whose areas take the place in, in order of their IDs: those taking in its height where the area
	 * has heights, or, if none does, those taking it in across
	 */
	private static List<Station> getCandidates(List<Station> sortedStations, BlockPos pos) {
		final List<Station> across = new ArrayList<>();
		for (final Station station : sortedStations) {
			if (station.inArea(pos.getX(), pos.getZ())) {
				across.add(station);
			}
		}
		if (across.size() < 2) {
			return across;
		}
		final List<Station> atHeight = new ArrayList<>();
		for (final Station station : across) {
			if (station.inArea(pos.getX(), pos.getY(), pos.getZ())) {
				atHeight.add(station);
			}
		}
		return atHeight.isEmpty() ? across : atHeight;
	}

	/**
	 * @return of the stations, the one with a platform nearest to the place, the height counting more; with none to go by,
	 * or equally near, the one of the lowest ID
	 */
	private Station nearestByPlatforms(List<Station> candidates, BlockPos pos, Map<Station, List<BlockPos>> platformsOf) {
		Station best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final Station station : candidates) {
			for (final BlockPos platformPos : platformsOf.getOrDefault(station, java.util.Collections.emptyList())) {
				final double distance = weightedDistance(pos, platformPos);
				if (distance < bestDistance || distance == bestDistance && best != null && station.id < best.id) {
					bestDistance = distance;
					best = station;
				}
			}
		}
		return best == null ? candidates.get(0) : best;
	}

	private void noteByIdAlone(long platformId, boolean byIdAlone) {
		if (byIdAlone) {
			platformsByIdAlone.add(platformId);
		}
	}

	public static double weightedDistance(BlockPos a, BlockPos b) {
		final double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(), dy = (a.getY() - b.getY()) * (double) HEIGHT_WEIGHT;
		return Math.sqrt(dx * dx + dz * dz + dy * dy);
	}

	/**
	 * @return the station a block belongs to (station names, signs, PIDS, ticket barriers): the station of the platform next
	 * to it if there is one, so blocks by a platform set to another station show that station, and blocks of each level
	 * of stations one above another their own; else, of the stations whose areas take it in, the one with a platform
	 * nearest to it; null for none. Kept by the place until the data changes.
	 */
	public Station getStationAt(BlockPos pos) {
		final Station kept = blockPosToStation.get(pos);
		if (kept != null || blockPosToStation.containsKey(pos)) {
			return kept;
		}
		final long platformId = RailwayData.getClosePlatformId(platforms, this, pos);
		Station station = platformId == 0 ? null : platformIdToStation.get(platformId);
		if (station == null) {
			final List<Station> sortedStations = new ArrayList<>(stations);
			sortedStations.sort(Comparator.comparingLong(eachStation -> eachStation.id));
			final List<Station> candidates = getCandidates(sortedStations, pos);
			if (candidates.size() == 1) {
				station = candidates.get(0);
			} else if (candidates.size() > 1) {
				final Map<Station, List<BlockPos>> platformsOf = new HashMap<>();
				platformIdToStation.forEach((eachPlatformId, eachStation) -> {
					final Platform platform = platformIdMap.get(eachPlatformId);
					if (platform != null) {
						platformsOf.computeIfAbsent(eachStation, key -> new ArrayList<>()).add(platform.getMidPos());
					}
				});
				station = nearestByPlatforms(candidates, pos, platformsOf);
			}
		}
		blockPosToStation.put(pos, station);
		return station;
	}

	private static <U extends SavedRailBase, V extends AreaBase> void mapSavedRailIdToStation(Map<Long, V> map, Set<U> savedRails, Set<V> areas) {
		map.clear();

		// Each cell lists the areas overlapping it, in the same order as the set, so that the first matching area is always the same one
		final List<V> areaList = new ArrayList<>(areas);
		final List<Integer> largeAreas = new ArrayList<>();
		final Long2ObjectOpenHashMap<List<Integer>> grid = new Long2ObjectOpenHashMap<>();
		for (int i = 0; i < areaList.size(); i++) {
			final V area = areaList.get(i);
			for (final int[] rectangle : area.getRectangles()) {
				final int cellX1 = Math.min(rectangle[0], rectangle[2]) >> AREA_CELL_BITS;
				final int cellX2 = Math.max(rectangle[0], rectangle[2]) >> AREA_CELL_BITS;
				final int cellZ1 = Math.min(rectangle[1], rectangle[3]) >> AREA_CELL_BITS;
				final int cellZ2 = Math.max(rectangle[1], rectangle[3]) >> AREA_CELL_BITS;
				if ((long) (cellX2 - cellX1 + 1) * (cellZ2 - cellZ1 + 1) > MAX_AREA_CELLS) {
					addOnce(largeAreas, i);
				} else {
					for (int cellX = cellX1; cellX <= cellX2; cellX++) {
						for (int cellZ = cellZ1; cellZ <= cellZ2; cellZ++) {
							addOnce(grid.computeIfAbsent(getCellKey(cellX, cellZ), key -> new ArrayList<>()), i);
						}
					}
				}
			}
		}

		savedRails.forEach(savedRail -> {
			final BlockPos pos = savedRail.getMidPos();
			final List<Integer> cellAreas = grid.get(getCellKey(pos.getX() >> AREA_CELL_BITS, pos.getZ() >> AREA_CELL_BITS));
			final int index = Math.min(getFirstMatch(areaList, cellAreas, savedRail, pos), getFirstMatch(areaList, largeAreas, savedRail, pos));
			if (index < areaList.size()) {
				map.put(savedRail.id, areaList.get(index));
			}
		});
	}

	private static <U extends SavedRailBase, V extends AreaBase> int getFirstMatch(List<V> areaList, List<Integer> indices, U savedRail, BlockPos pos) {
		if (indices != null) {
			for (final int index : indices) {
				final V area = areaList.get(index);
				if (area.isTransportMode(savedRail.transportMode) && area.inArea(pos.getX(), pos.getZ())) {
					return index;
				}
			}
		}
		return Integer.MAX_VALUE;
	}

	private static void addOnce(List<Integer> indices, int index) {
		if (indices.isEmpty() || indices.get(indices.size() - 1) != index) {
			indices.add(index);
		}
	}

	private static long getCellKey(int cellX, int cellZ) {
		return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
	}

	private static int getMinX(AreaBase area) {
		return area.getRectangles().stream().mapToInt(rectangle -> Math.min(rectangle[0], rectangle[2])).min().orElse(0);
	}

	private static int getMaxX(AreaBase area) {
		return area.getRectangles().stream().mapToInt(rectangle -> Math.max(rectangle[0], rectangle[2])).max().orElse(0);
	}
}
