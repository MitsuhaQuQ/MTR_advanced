package mtr.data;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The check of lengths when a route's stops are put together (optimisation round 3b, step 3): a train longer than a
 * platform it stops at is pointed out, unless the stop is set to allow it. The game and the web editor ask the same.
 */
public final class StopLengths {

	/**
	 * How much longer than a platform a train may be without being pointed out, in blocks.
	 */
	private static final double TOLERANCE = 1.5;

	private StopLengths() {
	}

	/**
	 * @param sidings every siding, to find the longest train of the depots running the route
	 * @return the length of the longest train running the route, in blocks, or 0 if none runs it
	 */
	public static double getLongestTrain(Route route, DataCache dataCache, Collection<Siding> sidings) {
		double longest = 0;
		for (final Siding siding : sidings) {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null && depot.routeIds.contains(route.id)) {
				longest = Math.max(longest, siding.getTrainLength());
			}
		}
		return longest;
	}

	/**
	 * @return the length of train the platform takes, in blocks: its nodes are the middles of its end blocks, so a train
	 * as long as the blocks it covers is one more than the distance between them (a 30 block tram fits nodes 29 apart)
	 */
	public static double getLength(Platform platform) {
		final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
		return Math.sqrt(positions.get(0).distSqr(positions.get(1))) + 1;
	}

	/**
	 * @param longestTrain the longest train running the route, from {@link #getLongestTrain}
	 * @return the indices of the stops where that train is longer than the platform and the stop doesn't allow it; stops
	 * passed don't count
	 */
	public static List<Integer> getTooShort(Route route, DataCache dataCache, double longestTrain) {
		final List<Integer> tooShort = new ArrayList<>();
		if (longestTrain <= 0) {
			return tooShort;
		}
		for (int i = 0; i < route.platformIds.size(); i++) {
			final Route.RoutePlatform stop = route.platformIds.get(i);
			final Platform platform = dataCache.platformIdMap.get(stop.platformId);
			final boolean passed = stop.pass && i > 0 && i < route.platformIds.size() - 1;
			// A block over is let through: the length is measured straight between the ends, a little short on a curve
			if (platform != null && !passed && !stop.allowLonger && longestTrain > getLength(platform) + TOLERANCE) {
				tooShort.add(i);
			}
		}
		return tooShort;
	}
}
