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
 * Fares by distance in the game (design document 6.13, 0.68.0): the fare rules (票务规则, 0.73.0; called groups in the
 * fare component), the network fares are worked out on, made from the routes' paths, and what a station's gates stand
 * for.
 * <p>
 * The rules are made and removed by hand ({@link NetworkSettings#fareRules}); a line goes by the rule it was put in, set
 * for all its routes ({@link NetworkSettings#fareRouteGroups}), else by the default rule. A station belongs to the rules
 * of the routes calling at it unless set by hand ({@link NetworkSettings#fareStationGroups}); its gates, found by the
 * station's area, which may be set by height, stand for its rules, shared if there are several. A rule's tiers go up to
 * the length of its reference line if it has one. The fare rules themselves are in {@link transitsim.fare}.
 */
public final class FareData {

	private static final String AUTO_PREFIX = "line:";

	private FareNetwork network;
	private long networkKey = Long.MIN_VALUE;

	/**
	 * @return the key of a route's line, the same for all its directions and service types: what a fare rule is chosen
	 * for and what a rule's reference line names
	 */
	public static String getAutoGroupKey(Route route) {
		return AUTO_PREFIX + route.transportMode + ":" + route.getLineKey();
	}

	/**
	 * @return the fare rule a route goes by: its own, else that of another route of its line (one added to the line since
	 * it was put in a rule), else the default
	 */
	public static String getGroupKey(Route route, DataCache dataCache, NetworkSettings settings) {
		final String own = settings.fareRouteGroups.get(route.id);
		if (own != null && settings.fareRules.containsKey(own)) {
			return own;
		}
		final String line = getAutoGroupKey(route);
		for (final Route other : dataCache.routeIdMap.values()) {
			final String set = other.id == route.id ? null : settings.fareRouteGroups.get(other.id);
			if (set != null && settings.fareRules.containsKey(set) && getAutoGroupKey(other).equals(line)) {
				return set;
			}
		}
		return settings.getDefaultFareRule();
	}

	/**
	 * @return every fare rule with its name, in order, the default first
	 */
	public static Map<String, String> getAllGroups(DataCache dataCache) {
		return getAllGroups(dataCache, dataCache.networkSettings);
	}

	public static Map<String, String> getAllGroups(DataCache dataCache, NetworkSettings settings) {
		final Map<String, String> rules = new java.util.LinkedHashMap<>();
		settings.fareRules.forEach((id, rule) -> rules.put(id, rule.name.isEmpty() ? id : rule.name));
		return rules;
	}

	/**
	 * @return the lines of the routes shown, by key ({@link #getAutoGroupKey}), with their names, by name
	 */
	public static Map<String, String> getLines(DataCache dataCache) {
		final Map<String, String> lines = new TreeMap<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (!route.isHidden && route.platformIds.size() >= 2) {
				lines.putIfAbsent(getAutoGroupKey(route), IGui.formatStationName(route.getLineName()));
			}
		});
		return lines;
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
				groups.add(getGroupKey(route, dataCache, settings));
			}
		});
		return groups;
	}

	/**
	 * @return the groups a station belongs to: as set by hand, or those of the routes calling at it
	 */
	public static Set<String> getStationGroups(DataCache dataCache, long stationId) {
		final List<String> set = dataCache.networkSettings.fareStationGroups.get(stationId);
		final Set<String> known = new TreeSet<>();
		if (set != null) {
			set.stream().filter(dataCache.networkSettings.fareRules::containsKey).forEach(known::add);
		}
		return known.isEmpty() ? getRouteGroups(dataCache, stationId) : known;
	}

	/**
	 * @return the network fares go by, made again once the routes, their groups or their paths change
	 */
	public FareNetwork getNetwork(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		long key = (railwayData.routePaths.getTimingVersion() * 31 + railwayData.dataCache.networkSettings.fareRouteGroups.hashCode()) * 31 + railwayData.dataCache.networkSettings.fareRules.hashCode();
		for (final Route route : railwayData.dataCache.routeIdMap.values()) {
			key = key * 31 + route.id;
			key = key * 31 + (route.isHidden ? 1 : 0) + route.getLineKey().hashCode();
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				key = key * 31 + routePlatform.platformId;
			}
			// A path drawn by hand, drawn again
			final List<mtr.path.PathData> drawn = route.getPathData();
			key = key * 31 + drawn.size() + (drawn.isEmpty() ? 0 : System.identityHashCode(drawn.get(0)) * 7L + System.identityHashCode(drawn.get(drawn.size() - 1)));
		}
		if (network == null || key != networkKey) {
			final List<Route> crowFlies = new ArrayList<>();
			final Map<String, Long> lineLengths = new java.util.HashMap<>();
			network = new FareNetwork(getLegs(railwayData, rails, crowFlies, lineLengths));
			// A rule's tiers go up to its reference line's length (0.73.0)
			railwayData.dataCache.networkSettings.fareRules.forEach((id, rule) -> network.setLongestBlocks(id, lineLengths.getOrDefault(rule.referenceLine, 0L)));
			networkKey = key;
			findPathsLater(railwayData, crowFlies);
		}
		return network;
	}

	/**
	 * How long before the path of a route charged as the crow flies is searched for again, if it wasn't found.
	 */
	private static final long SEARCH_AGAIN_MILLIS = 60000;
	private final Map<Long, Long> searchedMillis = new java.util.HashMap<>();
	private final java.util.concurrent.atomic.AtomicBoolean searching = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Routes charged as the crow flies have their paths found in the background (2026-11-12), one search at a time and
	 * each route at most once a minute; once found, the network is made again with their track.
	 */
	private void findPathsLater(RailwayData railwayData, List<Route> crowFlies) {
		final long millis = System.currentTimeMillis();
		final List<Route> toFind = new ArrayList<>();
		crowFlies.forEach(route -> {
			if (millis - searchedMillis.getOrDefault(route.id, Long.MIN_VALUE / 2) >= SEARCH_AGAIN_MILLIS) {
				toFind.add(route);
			}
		});
		if (toFind.isEmpty() || !searching.compareAndSet(false, true)) {
			return;
		}
		toFind.forEach(route -> searchedMillis.put(route.id, millis));
		railwayData.findRoutePathsInBackground(toFind, () -> searching.set(false));
	}

	/**
	 * The track between the stops in a row of every route: along its path found or drawn by hand, or as the crow flies if
	 * it has none (yet).
	 *
	 * @param crowFlies   given the routes that could have a path found but have none yet
	 * @param lineLengths given each line's length: its longest route's, first stop to last
	 */
	private static List<FareNetwork.Leg> getLegs(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails, List<Route> crowFlies, Map<String, Long> lineLengths) {
		final DataCache dataCache = railwayData.dataCache;
		final List<FareNetwork.Leg> legs = new ArrayList<>();
		for (final Route route : dataCache.routeIdMap.values()) {
			if (route.isHidden || route.platformIds.size() < 2) {
				continue;
			}
			final String group = getGroupKey(route, dataCache, dataCache.networkSettings);
			long routeBlocks = 0;
			final List<mtr.path.PathData> drawn = route.getPathData();
			final RoutePaths.RoutePath path = drawn.isEmpty() ? railwayData.routePaths.getFound(route, dataCache, rails) : null;
			if (drawn.isEmpty() && path == null && RoutePaths.timesByPath(route)) {
				crowFlies.add(route);
			}
			for (int i = 0; i + 1 < route.platformIds.size(); i++) {
				final Platform from = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
				final Platform to = dataCache.platformIdMap.get(route.platformIds.get(i + 1).platformId);
				final Station fromStation = from == null ? null : dataCache.platformIdToStation.get(from.id);
				final Station toStation = to == null ? null : dataCache.platformIdToStation.get(to.id);
				if (fromStation == null || toStation == null) {
					continue;
				}
				double blocks = !drawn.isEmpty() ? RoutePaths.getBlocksBetweenStops(drawn, i, i + 1) : path == null ? -1 : path.getBlocksBetweenStops(i, i + 1);
				if (blocks < 0) {
					blocks = Math.sqrt(from.getMidPos().distSqr(to.getMidPos()));
				}
				legs.add(new FareNetwork.Leg(group, fromStation.id, toStation.id, Math.round(blocks)));
				routeBlocks += Math.round(blocks);
			}
			lineLengths.merge(getAutoGroupKey(route), routeBlocks, Math::max);
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
