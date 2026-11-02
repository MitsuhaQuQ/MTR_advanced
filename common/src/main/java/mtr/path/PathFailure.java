package mtr.path;

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
		final long startPos = savedRailBases.get(section).getOrderedPositions(BlockPos.ZERO, false).get(0).asLong();
		final long endPos = savedRailBases.get(section + 1).getOrderedPositions(BlockPos.ZERO, false).get(0).asLong();
		return new PathFailure(graph.isConnected(startPos, endPos) ? Type.NO_ROUTE : Type.DISCONNECTED, 0);
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
		TURN_BACK_UNUSED
	}
}
