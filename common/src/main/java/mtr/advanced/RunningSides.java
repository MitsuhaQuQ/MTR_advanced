package mtr.advanced;

import mtr.data.LineDuties;
import mtr.data.NetworkSettings;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.RoutePaths;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import transitsim.network.RailKeys;
import transitsim.network.RunningDirections;
import transitsim.sim.RunTrack;

import java.util.*;

/**
 * The way each track of the world's double track lines runs, by their running sides (user, 2026-11-12;
 * {@link RunningDirections} works it out, {@link NetworkSettings#runningSides} sets the sides): the rails the routes'
 * paths run on, with the lines running on each. Worked out again when the rails, the routes' paths or the settings change,
 * and kept for the paths the trains run ({@link SimExport#toTracks}).
 */
public final class RunningSides {

	private static final Map<RailwayData, Cached> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	/**
	 * The latest, by the rails they were worked out for, for paths made where only the rails are at hand.
	 */
	private static final Map<Map<BlockPos, Map<BlockPos, Rail>>, Map<UUID, Integer>> BY_RAILS = Collections.synchronizedMap(new WeakHashMap<>());

	private RunningSides() {
	}

	private static final class Cached {
		private final String key;
		private final Map<UUID, Integer> directions;

		private Cached(String key, Map<UUID, Integer> directions) {
			this.key = key;
			this.directions = directions;
		}
	}

	/**
	 * @return for each rail with a way of its own, by {@link RailKeys#of}, 1 if trains run it from its smaller node to its
	 * larger, -1 the other way
	 */
	public static Map<UUID, Integer> get(RailwayData railwayData) {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		final NetworkSettings settings = railwayData.dataCache.networkSettings;
		final String key = rails.size() + ":" + railwayData.routePaths.getTimingVersion() + ":" + railwayData.routes.size() + ":" + settings.runningSides.hashCode() + ":" + settings.lineTracks.hashCode();
		final Cached cached = CACHE.get(railwayData);
		if (cached != null && cached.key.equals(key)) {
			return cached.directions;
		}
		final Map<UUID, Integer> directions = compute(railwayData, rails, settings);
		CACHE.put(railwayData, new Cached(key, directions));
		BY_RAILS.put(rails, directions);
		return directions;
	}

	/**
	 * @return the latest worked out for these rails, or none
	 */
	public static Map<UUID, Integer> get(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final Map<UUID, Integer> directions = BY_RAILS.get(rails);
		return directions == null ? Collections.emptyMap() : directions;
	}

	/**
	 * @return how a path runs along this track as its line's running side has it ({@link RunTrack#getConvention()})
	 */
	public static int getConvention(Map<UUID, Integer> directions, BlockPos from, BlockPos to) {
		final long fromNode = from.asLong();
		final long toNode = to.asLong();
		final Integer way = directions.get(RailKeys.of(fromNode, toNode));
		if (way == null) {
			return RunTrack.CONVENTION_NONE;
		}
		return (fromNode < toNode) == (way > 0) ? RunTrack.CONVENTION_WITH : RunTrack.CONVENTION_AGAINST;
	}

	private static Map<UUID, Integer> compute(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails, NetworkSettings settings) {
		// The lines running on each rail the routes' paths take
		final Map<UUID, Set<String>> lines = new HashMap<>();
		final Map<UUID, BlockPos[]> ends = new HashMap<>();
		for (final Route route : railwayData.routes) {
			final RoutePaths.RoutePath routePath = railwayData.routePaths.getFound(route, railwayData.dataCache, rails);
			final List<PathData> path = routePath == null ? route.getPathData() : routePath.getPath(route.platformIds);
			final String lineKey = LineDuties.getLineKey(route);
			for (final PathData pathData : path) {
				final UUID railKey = RailKeys.of(pathData.startingPos.asLong(), pathData.getLastPos().asLong());
				lines.computeIfAbsent(railKey, k -> new TreeSet<>()).add(lineKey);
				ends.putIfAbsent(railKey, new BlockPos[]{pathData.startingPos, pathData.getLastPos()});
			}
		}
		final List<RunningDirections.Rail> input = new ArrayList<>();
		lines.forEach((railKey, railLines) -> {
			final BlockPos[] pair = ends.get(railKey);
			final Rail rail = Optional.ofNullable(rails.get(pair[0])).map(map -> map.get(pair[1])).orElse(null);
			if (rail == null || rail.railType == RailType.NONE) {
				return;
			}
			// Only two way tracks have a way to be given
			final Rail back = Optional.ofNullable(rails.get(pair[1])).map(map -> map.get(pair[0])).orElse(null);
			if (back == null || back.railType == RailType.NONE) {
				return;
			}
			int side = 0;
			for (final String lineKey : railLines) {
				final int lineSide = settings.getRunningSide(lineKey);
				if (side != 0 && side != lineSide) {
					side = 0;
					break;
				}
				side = lineSide;
			}
			input.add(new RunningDirections.Rail(railKey, pair[0].asLong(), pair[1].asLong(), points(rail), railLines, side));
		});
		return Collections.unmodifiableMap(RunningDirections.compute(input));
	}

	private static double[] points(Rail rail) {
		final double length = rail.getLength();
		final int pieces = Math.max(1, (int) Math.ceil(length / RunningDirections.SAMPLE_SPACING));
		final double[] points = new double[(pieces + 1) * 2];
		for (int i = 0; i <= pieces; i++) {
			final Vec3 position = rail.getPosition(length * i / pieces);
			points[i * 2] = position.x;
			points[i * 2 + 1] = position.z;
		}
		return points;
	}
}
