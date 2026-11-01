package mtr.path;

import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.SavedRailBase;
import mtr.path.RailGraph.Connection;
import mtr.path.RailGraph.Kind;
import mtr.path.RailRouter.Result;
import mtr.path.RailRouter.Step;
import mtr.path.RailRouter.Target;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Connects {@link RailRouter} to the game's rail data. The graph must be compiled on the server thread, and the
 * search can then run on a path generation thread without touching {@code rails}.
 */
public final class DeterministicPathFinder {

	private DeterministicPathFinder() {
	}

	public static RailGraph<Rail> compile(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final RailGraph.Builder<Rail> builder = new RailGraph.Builder<>();
		rails.forEach((startPos, connections) -> connections.forEach((endPos, rail) -> builder.add(
				startPos.asLong(), startPos.getX(), startPos.getZ(), Math.round(rail.facingStart.angleDegrees * 10),
				endPos.asLong(), endPos.getX(), endPos.getY(), endPos.getZ(), Math.round(rail.facingEnd.angleDegrees * 10),
				getKind(rail.railType), rail.getLength() / rail.railType.maxBlocksPerTick, rail
		)));
		return builder.build();
	}

	/**
	 * @param sectionWaypoints the nodes to pass after each saved rail, or null when there are none
	 * @return the number of saved rails reached, like {@link PathFinder#findPath}
	 */
	public static int findPath(List<PathData> path, RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints, int stopIndexOffset) {
		path.clear();
		if (savedRailBases.size() < 2) {
			return 0;
		}

		final List<Target> targets = new ArrayList<>();
		final List<Integer> savedIndices = new ArrayList<>();
		for (int i = 0; i < savedRailBases.size(); i++) {
			final List<BlockPos> positions = savedRailBases.get(i).getOrderedPositions(BlockPos.ZERO, false);
			final BlockPos pos1 = positions.get(0);
			final BlockPos pos2 = positions.get(1);
			targets.add(Target.rail(pos1.asLong(), pos1.getX(), pos1.getZ(), pos2.asLong(), pos2.getX(), pos2.getZ()));
			savedIndices.add(i);
			if (sectionWaypoints != null && i < sectionWaypoints.size() && i + 1 < savedRailBases.size()) {
				for (final BlockPos waypoint : sectionWaypoints.get(i)) {
					targets.add(Target.node(waypoint.asLong(), waypoint.getX(), waypoint.getZ()));
					savedIndices.add(i);
				}
			}
		}

		final Result<Rail> result = RailRouter.route(graph, targets);
		if (!result.isSuccess()) {
			return result.completedTargets == 0 ? 1 : savedIndices.get(result.completedTargets - 1) + 1;
		}

		int reached = 0;
		for (int i = 0; i < result.steps.size(); i++) {
			final Step<Rail> step = result.steps.get(i);
			final Connection<Rail> connection = step.connection;
			final BlockPos startPos = BlockPos.of(connection.fromPos);
			final BlockPos endPos = BlockPos.of(connection.toPos);

			if (i == 0) {
				path.add(new PathData(connection.rail, savedRailBases.get(0).id, 0, startPos, endPos, stopIndexOffset));
			} else if (step.completedTarget >= 0) {
				reached = savedIndices.get(step.completedTarget);
				final SavedRailBase savedRailBase = savedRailBases.get(reached);
				path.add(new PathData(connection.rail, savedRailBase.id, savedRailBase instanceof Platform ? savedRailBase.getDwellTime() : 0, startPos, endPos, stopIndexOffset + reached));
			} else if (step.reversal) {
				path.add(new PathData(connection.rail, savedRailBases.get(reached).id, 0, startPos, endPos, stopIndexOffset + reached));
			} else {
				final boolean turningBack = connection.kind == Kind.TURN_BACK && i + 1 < result.steps.size() && result.steps.get(i + 1).connection.toPos == connection.fromPos;
				path.add(new PathData(connection.rail, 0, turningBack ? connection.rail.getTurnBackDwellTime() : 0, startPos, endPos, stopIndexOffset + reached));
			}
		}

		return savedRailBases.size();
	}

	/**
	 * @return the nodes passed in order, or an empty array when the route can't be completed
	 */
	public static long[] findNodes(RailGraph<Rail> graph, List<SavedRailBase> savedRailBases, List<List<BlockPos>> sectionWaypoints) {
		final List<PathData> path = new ArrayList<>();
		findPath(path, graph, savedRailBases, sectionWaypoints, 0);
		if (path.isEmpty()) {
			return new long[0];
		}
		final long[] nodes = new long[path.size() + 1];
		for (int i = 0; i < path.size(); i++) {
			nodes[i] = path.get(i).startingPos.asLong();
		}
		nodes[path.size()] = path.get(path.size() - 1).getLastPos().asLong();
		return nodes;
	}

	private static Kind getKind(RailType railType) {
		if (railType == RailType.NONE) {
			return Kind.DISABLED;
		} else if (railType == RailType.TURN_BACK) {
			return Kind.TURN_BACK;
		} else {
			return railType.hasSavedRail ? Kind.PLATFORM : Kind.NORMAL;
		}
	}
}
