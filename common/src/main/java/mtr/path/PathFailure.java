package mtr.path;

import mtr.advanced.PathRegions;
import mtr.data.Rail;
import mtr.data.SavedRailBase;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Explains why a route could not be found, for the depot screen.
 */
public final class PathFailure {

	public static final PathFailure NONE = new PathFailure(Type.NONE, 0);

	public final Type type;
	public final long pos;

	public PathFailure(Type type, long pos) {
		this.type = type;
		this.pos = pos;
	}

	/**
	 * @param reached the number of saved rails reached, as returned by {@link PathFinder}
	 */
	public static PathFailure diagnose(RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int reached) {
		if (savedRailBases.size() < 2) {
			return NONE;
		}
		if (graph == null) {
			final int section = PathFinder.findSectionWithWaypoints(sectionWaypoints, savedRailBases.size());
			return new PathFailure(section < 0 ? Type.NO_ROUTE : Type.OLD_SEARCH, 0);
		}

		final int section = Math.max(reached - 1, 0);
		if (section + 1 >= savedRailBases.size()) {
			return NONE;
		}
		if (sectionWaypoints != null && section < sectionWaypoints.size()) {
			for (final BlockPos waypoint : sectionWaypoints.get(section)) {
				if (!graph.hasNode(waypoint.asLong())) {
					return new PathFailure(Type.MISSING_WAYPOINT, waypoint.asLong());
				}
			}
		}
		final List<BlockPos> startPositions = savedRailBases.get(section).getOrderedPositions(BlockPos.ZERO, false);
		final List<BlockPos> endPositions = savedRailBases.get(section + 1).getOrderedPositions(BlockPos.ZERO, false);
		final long startPos = startPositions.get(0).asLong();
		final long endPos = endPositions.get(0).asLong();
		if (!graph.isConnected(startPos, endPos)) {
			return new PathFailure(Type.DISCONNECTED, 0);
		}
		// Stations in regions that don't touch can only be reached through waypoints on the boundaries between them
		final PathRegions regions = graph.getRegions();
		if (regions != null && (sectionWaypoints == null || section >= sectionWaypoints.size() || sectionWaypoints.get(section).isEmpty())) {
			final int[] startRegions = regions.railRegions(graph, startPos, startPositions.get(1).asLong());
			final int[] endRegions = regions.railRegions(graph, endPos, endPositions.get(1).asLong());
			if (!PathRegions.shares(startRegions, endRegions)) {
				return new PathFailure(Type.BOUNDARY, endPos);
			}
		}
		return new PathFailure(Type.NO_ROUTE, 0);
	}

	public enum Type {
		NONE, OLD_SEARCH, MISSING_WAYPOINT, DISCONNECTED, NO_ROUTE,
		/**
		 * No turn back rail a route lists can be reached, at the first one.
		 */
		TURN_BACK,
		/**
		 * A turn back rail a route lists can't be reached, or not from the same rails as the first one, and trains don't use it.
		 */
		TURN_BACK_UNUSED,
		/**
		 * The next station is in another region of the pathfinding boundaries, and nothing says where to cross.
		 */
		BOUNDARY,
		/**
		 * The path of a duty of a depot running by its duties can't be found, so that train doesn't go out on those days.
		 */
		DUTY
	}
}
