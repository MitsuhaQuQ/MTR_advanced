package mtr.path;

import net.minecraft.network.FriendlyByteBuf;
import mtr.data.Rail;
import mtr.data.SavedRailBase;
import net.minecraft.core.BlockPos;
import transitsim.network.RailGraph;

import java.util.List;

/**
 * Explains why a route could not be found, for the depot screen.
 */
public final class PathFailure {

	public static final PathFailure NONE = new PathFailure(Type.NONE, 0);

	public final Type type;
	public final long pos;
	/**
	 * The two nodes of the rail in the way, or 0: for {@link Type#CONNECTING_LINE}, the first rail of the connecting line
	 * the route would have to run over.
	 */
	public final long node1;
	public final long node2;

	public PathFailure(Type type, long pos) {
		this(type, pos, 0, 0);
	}

	public PathFailure(Type type, long pos, long node1, long node2) {
		this.type = type;
		this.pos = pos;
		this.node1 = node1;
		this.node2 = node2;
	}

	public void writePacket(FriendlyByteBuf packet) {
		packet.writeByte(type.ordinal());
		packet.writeLong(pos);
		packet.writeLong(node1);
		packet.writeLong(node2);
	}

	public static PathFailure readPacket(FriendlyByteBuf packet) {
		final int type = packet.readByte();
		final long pos = packet.readLong();
		final long node1 = packet.readLong();
		final long node2 = packet.readLong();
		return new PathFailure(type >= 0 && type < Type.values().length ? Type.values()[type] : Type.NO_ROUTE, pos, node1, node2);
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
		// Pathfinding boundaries never stop a route that has a way (they only narrow the search), so they are no reason
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
		 * No longer given: pathfinding boundaries only narrow searches. Kept so the types after it keep their numbers.
		 */
		BOUNDARY,
		/**
		 * The path of a duty of a depot running by its duties can't be found, so that train doesn't go out on those days.
		 */
		DUTY,
		/**
		 * The route can only be found over a connecting line, which other routes keep off; the position is that line's ID.
		 */
		CONNECTING_LINE,
		/**
		 * Trains staying on the line can't get from the last stop of the depot's last route back to the first stop of its
		 * first route; the position is the platform they leave from, {@link #node1} the one they should reach.
		 */
		CYCLE_BACK
	}
}
