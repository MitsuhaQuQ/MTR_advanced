package mtr.data;

import mtr.Blocks;
import mtr.block.BlockNode;
import mtr.compat.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Puts back node blocks the world lost while the railway data kept their rails. Rails laid by a player are saved at the
 * end of the tick, but the chunks with their nodes only with the next world save, so a server that stops without saving
 * the world (a crash, a kill) comes back with rails whose nodes aren't there. Each node of the data as it was loaded is
 * looked at once its chunk is loaded; a missing one is placed again where there is air or another block that can be
 * built over, facing the way its rails leave it. A node whose place something else took is left as it is and logged.
 */
public final class RailNodeRepair {

	private final Set<BlockPos> nodesToCheck = new HashSet<>();

	private static final int CHECK_INTERVAL_TICKS = 20;

	/**
	 * Called once the railway data is loaded.
	 */
	public void start(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		nodesToCheck.clear();
		rails.forEach((pos, railMap) -> {
			if (!railMap.isEmpty()) {
				nodesToCheck.add(pos);
			}
		});
	}

	public void tick(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		if (nodesToCheck.isEmpty() || world.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		final int[] repaired = {0};
		nodesToCheck.removeIf(pos -> {
			if (world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
				return false;
			}
			final Map<BlockPos, Rail> railMap = rails.get(pos);
			if (railMap == null || railMap.isEmpty()) {
				return true;
			}
			final BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof BlockNode) {
				return true;
			}
			final Rail rail = railMap.values().iterator().next();
			if (!state.isAir() && !Compat.isReplaceable(state)) {
				System.out.println("Minecraft Transit Railway found the node of a rail at " + pos.toShortString() + " in " + world.dimension().location() + " taken by " + state.getBlock().getDescriptionId() + "; it is left as it is");
				return true;
			}
			placeNode(world, pos, rail);
			repaired[0]++;
			return true;
		});
		if (repaired[0] > 0) {
			System.out.println("Minecraft Transit Railway put back " + repaired[0] + " node(s) the world had lost in " + world.dimension().location());
		}
	}

	/**
	 * @return the remaining nodes to look at, for tests
	 */
	public int getRemaining() {
		return nodesToCheck.size();
	}

	private static void placeNode(Level world, BlockPos pos, Rail rail) {
		final Block block = getBlock(rail.transportMode);
		final float angle = rail.facingStart.angleDegrees;
		final boolean continuous = block instanceof BlockNode.BlockContinuousMovementNode;
		// The angle of a node's block state, see BlockNode.getAngle: 0 if facing, else 90, plus 22.5 and 45
		final float halfTurn = ((angle % 180) + 180) % 180;
		final boolean facing = halfTurn < 90;
		final float rest = facing ? halfTurn : halfTurn - 90;
		final int steps = Math.round(rest / 22.5F);
		final boolean onQuadrant = Math.abs(rest - steps * 22.5F) < 0.05F && steps < 4 && (!continuous || steps % 2 == 0);
		final BlockState state = block.defaultBlockState().setValue(BlockNode.FACING, facing).setValue(BlockNode.IS_45, onQuadrant && steps >= 2).setValue(BlockNode.IS_22_5, onQuadrant && steps % 2 == 1).setValue(BlockNode.IS_CONNECTED, true);
		world.setBlockAndUpdate(pos, state);
		if (!onQuadrant) {
			BlockNode.setCustomAngle(world, pos, angle);
		}
	}

	private static Block getBlock(TransportMode transportMode) {
		switch (transportMode) {
			case BOAT:
				return Blocks.BOAT_NODE.get();
			case CABLE_CAR:
				return Blocks.CABLE_CAR_NODE_LOWER.get();
			case AIRPLANE:
				return Blocks.AIRPLANE_NODE.get();
			default:
				return Blocks.RAIL_NODE.get();
		}
	}
}
