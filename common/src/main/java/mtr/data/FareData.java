package mtr.data;

import net.minecraft.core.BlockPos;
import transitsim.fare.FareLevels;
import transitsim.fare.FareNetwork;
import transitsim.fare.FareTrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fares by distance in the game (design document 6.13, 0.68.0): the fare groups, which are the railway companies, the
 * network fares are worked out on, made from the routes' paths, and what a station's gates stand for.
 * <p>
 * A route is in the group of its line unless it was put in another by hand ({@link NetworkSettings#fareRouteGroups}). A
 * station belongs to the groups of the routes calling at it unless set by hand ({@link NetworkSettings#fareStationGroups});
 * its gates, found by the station's area, which may be set by height, stand for its groups, shared if there are several.
 * The rules themselves are in {@link transitsim.fare}.
 */
public final class FareData {

	private static final String AUTO_PREFIX = "line:";

	private FareNetwork network;
	private long networkKey = Long.MIN_VALUE;

	/**
	 * @return the group of a route's line, as routes are in unless put in another by hand
	 */
	public static String getAutoGroupKey(Route route) {
		return AUTO_PREFIX + route.transportMode + ":" + route.getLineKey();
	}

	/**
	 * @return the fare group a route is in
	 */
	public static String getGroupKey(Route route, NetworkSettings settings) {
		final String set = settings.fareRouteGroups.get(route.id);
		return set == null || set.isEmpty() ? getAutoGroupKey(route) : set;
	}

	/**
	 * @return every fare group with its name: the line's name for a line's group, its own key for one named by hand
	 */
	public static Map<String, String> getAllGroups(DataCache dataCache) {
		return getAllGroups(dataCache, dataCache.networkSettings);
	}

	/**
	 * @return every fare group with its name, with the groups of routes and stations as in the settings given
	 */
	public static Map<String, String> getAllGroups(DataCache dataCache, NetworkSettings settings) {
		final Map<String, String> groups = new TreeMap<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (!route.isHidden && route.platformIds.size() >= 2) {
				groups.putIfAbsent(getAutoGroupKey(route), IGui.formatStationName(route.getLineName()));
			}
		});
		final Map<String, String> used = new TreeMap<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (!route.isHidden && route.platformIds.size() >= 2) {
				final String group = getGroupKey(route, settings);
				used.put(group, groups.getOrDefault(group, group));
			}
		});
		// Groups named by hand with no route in them yet, as given to stations
		settings.fareStationGroups.values().forEach(stationGroups -> stationGroups.forEach(group -> used.putIfAbsent(group, groups.getOrDefault(group, group))));
		return used;
	}

	/**
	 * @return the groups of the routes calling at a station
	 */
	public static Set<String> getRouteGroups(DataCache dataCache, long stationId) {
		return getRouteGroups(dataCache, dataCache.networkSettings, stationId);
	}

	public static Set<String> getRouteGroups(DataCache dataCache, NetworkSettings settings, long stationId) {
		final Set<String> groups = new TreeSet<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (!route.isHidden && route.platformIds.size() >= 2 && route.platformIds.stream().anyMatch(routePlatform -> {
				final Station station = dataCache.platformIdToStation.get(routePlatform.platformId);
				return station != null && station.id == stationId;
			})) {
				groups.add(getGroupKey(route, settings));
			}
		});
		return groups;
	}

	/**
	 * @return the groups a station belongs to: as set by hand, or those of the routes calling at it
	 */
	public static Set<String> getStationGroups(DataCache dataCache, long stationId) {
		final List<String> set = dataCache.networkSettings.fareStationGroups.get(stationId);
		return set == null || set.isEmpty() ? getRouteGroups(dataCache, stationId) : new TreeSet<>(set);
	}

	/**
	 * @return the network fares go by, made again once the routes, their groups or their paths change
	 */
	public FareNetwork getNetwork(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		long key = railwayData.routePaths.getTimingVersion() * 31 + railwayData.dataCache.networkSettings.fareRouteGroups.hashCode();
		for (final Route route : railwayData.dataCache.routeIdMap.values()) {
			key = key * 31 + route.id;
			key = key * 31 + (route.isHidden ? 1 : 0) + route.getLineKey().hashCode();
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				key = key * 31 + routePlatform.platformId;
			}
		}
		if (network == null || key != networkKey) {
			network = new FareNetwork(getLegs(railwayData, rails));
			networkKey = key;
		}
		return network;
	}

	/**
	 * The track between the stops in a row of every route, along its path, or as the crow flies if it has none found.
	 */
	private static List<FareNetwork.Leg> getLegs(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final DataCache dataCache = railwayData.dataCache;
		final List<FareNetwork.Leg> legs = new ArrayList<>();
		for (final Route route : dataCache.routeIdMap.values()) {
			if (route.isHidden || route.platformIds.size() < 2) {
				continue;
			}
			final String group = getGroupKey(route, dataCache.networkSettings);
			final RoutePaths.RoutePath path = railwayData.routePaths.getFound(route, dataCache, rails);
			for (int i = 0; i + 1 < route.platformIds.size(); i++) {
				final Platform from = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
				final Platform to = dataCache.platformIdMap.get(route.platformIds.get(i + 1).platformId);
				final Station fromStation = from == null ? null : dataCache.platformIdToStation.get(from.id);
				final Station toStation = to == null ? null : dataCache.platformIdToStation.get(to.id);
				if (fromStation == null || toStation == null) {
					continue;
				}
				double blocks = path == null ? -1 : path.getBlocksBetweenStops(i, i + 1);
				if (blocks < 0) {
					blocks = Math.sqrt(from.getMidPos().distSqr(to.getMidPos()));
				}
				legs.add(new FareNetwork.Leg(group, fromStation.id, toStation.id, Math.round(blocks)));
			}
		}
		return legs;
	}

	/**
	 * @return what the gates of a station stand for: its groups, shared if there are several
	 */
	public static FareTrip.Gate getGate(DataCache dataCache, Station station) {
		final Set<String> groups = getStationGroups(dataCache, station.id);
		return new FareTrip.Gate(station.id, groups, groups.size() > 1);
	}

	/**
	 * @return the fares of a group, or the defaults
	 */
	public static FareLevels getLevels(RailwayData railwayData, String group) {
		final FareLevels levels = group == null ? null : railwayData.dataCache.networkSettings.fareLevels.get(group);
		return levels == null ? new FareLevels() : levels;
	}

	/**
	 * @return the names of groups, for messages
	 */
	public static String getGroupNames(DataCache dataCache, Set<String> groups) {
		final Map<String, String> names = getAllGroups(dataCache);
		final List<String> found = new ArrayList<>();
		groups.forEach(group -> found.add(names.getOrDefault(group, group)));
		return String.join(", ", found);
	}
}
