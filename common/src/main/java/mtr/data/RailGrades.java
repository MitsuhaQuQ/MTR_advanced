package mtr.data;

import mtr.block.BlockNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import transitsim.geometry.VerticalProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The grades of nodes (design 9.2): every rail with grades ({@link Rail#hasGrades()}) leaves or reaches a node at the
 * node's grade, so the height runs on smoothly through it. A node's grade is set on the node ({@link BlockNode#setGrade})
 * or, by default, worked out from the mean grades of its rails ({@link VerticalProfile#autoNodeGrade}). Grades of a node
 * go the way the node itself faces ({@link BlockNode#getAngle}), which stays the same however its rails come and go.
 * <p>
 * The server calls {@link #refreshNode} after a rail of a node is added, taken away or turned, and after the node's
 * settings change; the brush screen and the node's floating text read {@link #getNodeGrade} on the client.
 */
public final class RailGrades {

	private static final double CHANGE_THRESHOLD = 1E-9;

	private RailGrades() {
	}

	/**
	 * @return the grade of the node going the way it faces, as set or as worked out from its rails
	 */
	public static double getNodeGrade(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		final float grade = BlockNode.getGrade(world, pos);
		return Float.isNaN(grade) ? getAutoGrade(world, rails, pos) : grade;
	}

	/**
	 * @return the grade the node would have if it isn't set
	 */
	public static double getAutoGrade(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections == null || connections.isEmpty()) {
			return 0;
		}
		final RailAngle facing = getFacing(world, pos);
		final double[] gradesAway = new double[connections.size()];
		final boolean[] forwardSide = new boolean[connections.size()];
		boolean anyLevelEnd = false;
		int i = 0;
		for (final Map.Entry<BlockPos, Rail> entry : connections.entrySet()) {
			final Rail rail = entry.getValue();
			final double length = rail.getLength();
			gradesAway[i] = length > 0 ? (entry.getKey().getY() - pos.getY()) / length : 0;
			forwardSide[i] = isForward(rail, facing);
			anyLevelEnd |= hasLevelEnd(rail) || hasLevelEnd(getBackwards(rails, pos, entry.getKey()));
			i++;
		}
		return VerticalProfile.autoNodeGrade(gradesAway, forwardSide, anyLevelEnd);
	}

	/**
	 * @return whether a rail of the node keeps the old shape, level at the node whatever its grade
	 */
	public static boolean hasOldRails(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		return connections != null && connections.values().stream().anyMatch(RailGrades::hasLevelEnd);
	}

	/**
	 * Sets the node's grade and vertical curve radius on the ends of its rails that have grades, both ways.
	 *
	 * @return the far nodes of the rails changed
	 */
	public static List<BlockPos> refreshNode(BlockGetter world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		final List<BlockPos> changed = new ArrayList<>();
		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections == null || connections.isEmpty()) {
			return changed;
		}
		final RailAngle facing = getFacing(world, pos);
		final double grade = getNodeGrade(world, rails, pos);
		final double radius = VerticalProfile.clampRadius(BlockNode.getVerticalRadius(world, pos));
		connections.forEach((posEnd, rail) -> {
			final Rail backwardsRail = getBackwards(rails, pos, posEnd);
			if (rail.hasGrades() && backwardsRail != null && backwardsRail.hasGrades()) {
				final double gradeAlong = isForward(rail, facing) ? grade : -grade;
				if (Math.abs(rail.getGradeStart() - gradeAlong) > CHANGE_THRESHOLD || Math.abs(rail.getVerticalRadiusStart() - radius) > CHANGE_THRESHOLD || Math.abs(backwardsRail.getGradeEnd() + gradeAlong) > CHANGE_THRESHOLD || Math.abs(backwardsRail.getVerticalRadiusEnd() - radius) > CHANGE_THRESHOLD) {
					rail.setGradeStart(gradeAlong, radius);
					// Coming back along the rail, the end at this node is reached going the other way
					backwardsRail.setGradeEnd(-gradeAlong, radius);
					changed.add(posEnd);
				}
			}
		});
		return changed;
	}

	/**
	 * Gives a new rail and its rail back level ends with the radii of their nodes, before {@link #refreshNode} sets the
	 * nodes' grades on them.
	 */
	public static void startGrades(BlockGetter world, BlockPos posStart, BlockPos posEnd, Rail rail, Rail backwardsRail) {
		final double radiusStart = BlockNode.getVerticalRadius(world, posStart);
		final double radiusEnd = BlockNode.getVerticalRadius(world, posEnd);
		rail.setGrades(0, 0, radiusStart, radiusEnd);
		backwardsRail.setGrades(0, 0, radiusEnd, radiusStart);
	}

	private static RailAngle getFacing(BlockGetter world, BlockPos pos) {
		return RailAngle.fromAngle(BlockNode.getAngle(world, pos, world.getBlockState(pos)));
	}

	private static boolean isForward(Rail rail, RailAngle facing) {
		final RailAngle leaving = rail.getLeavingFacing();
		return leaving.cos * facing.cos + leaving.sin * facing.sin >= 0;
	}

	private static boolean hasLevelEnd(Rail rail) {
		return rail != null && !rail.hasGrades() && rail.railType.railSlopeStyle != RailType.RailSlopeStyle.CABLE;
	}

	private static Rail getBackwards(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, BlockPos posEnd) {
		final Map<BlockPos, Rail> backwardsConnections = rails.get(posEnd);
		return backwardsConnections == null ? null : backwardsConnections.get(pos);
	}
}
