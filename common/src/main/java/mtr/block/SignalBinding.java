package mtr.block;

import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Binding a signal to the block section it protects (0.99.0, design 7.6). For each side of the signal, the way trains run
 * past it (the way it faces for side 0, back for side 1), the candidates are the nodes near it ({@link #REACH} blocks across,
 * a little up and down) that a rail leaves the same way, and that lie ahead of the signal, so a train passes the signal
 * before entering the section; the nearest is taken, ties by position, so the result is the same every time. Worked out
 * once when the signal is put down and kept; the brush works it out again, and sneaking with the brush then clicking a
 * node with it binds by hand.
 */
public final class SignalBinding {

	/**
	 * How far across the signal a node may be, in blocks
	 */
	public static final int REACH = 8;
	private static final int BELOW = 5;
	private static final int ABOVE = 1;
	/**
	 * How long a sneaking brush click waits for the node to bind to, in milliseconds
	 */
	private static final long MANUAL_MILLIS = 60_000;
	/**
	 * Players binding a signal by hand: the signal and until when
	 */
	private static final Map<UUID, long[]> PENDING = new HashMap<>();

	private SignalBinding() {
	}

	/**
	 * @return the way trains run past a side of a signal facing this way, in degrees as rail angles have it
	 */
	public static float angleOf(Direction facing, int side) {
		return (side == 0 ? facing : facing.getOpposite()).toYRot() + 90;
	}

	/**
	 * Works out both sides' bindings of a signal and keeps them in its block entity
	 *
	 * @return how many sides were bound
	 */
	public static int bind(Level world, BlockPos pos) {
		final BlockEntity entity = world.getBlockEntity(pos);
		final BlockState state = world.getBlockState(pos);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (!(entity instanceof TileEntitySignalBase) || railwayData == null || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return 0;
		}
		final Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
		int count = 0;
		for (int side = 0; side < 2; side++) {
			final float angle = angleOf(facing, side);
			final BlockPos node = findNode(railwayData.getRails(), pos, angle);
			((TileEntitySignalBase) entity).setBinding(side, node, angle, false);
			if (node != null) {
				count++;
			}
		}
		return count;
	}

	/**
	 * @return the node a side of a signal at this place protects, or null if none is near
	 */
	public static BlockPos findNode(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, float angle) {
		final double directionX = Math.cos(Math.toRadians(angle));
		final double directionZ = Math.sin(Math.toRadians(angle));
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int y = -BELOW; y <= ABOVE; y++) {
			for (int x = -REACH; x <= REACH; x++) {
				for (int z = -REACH; z <= REACH; z++) {
					final BlockPos node = pos.offset(x, y, z);
					final Map<BlockPos, Rail> connections = rails.get(node);
					if (connections == null || connections.values().stream().noneMatch(rail -> rail.facingStart.similarFacing(angle))) {
						continue;
					}
					// Ahead of the signal the way trains run, so they pass it before entering the section
					if (x * directionX + z * directionZ < -1) {
						continue;
					}
					final double distance = x * x + z * z + y * y * 0.25;
					if (distance < bestDistance || distance == bestDistance && best != null && node.asLong() < best.asLong()) {
						best = node;
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	/**
	 * A brush used on a signal: works its binding out again, or sneaking, waits for a node clicked with the brush
	 */
	public static void onBrush(Level world, BlockPos pos, Player player) {
		if (player.isShiftKeyDown()) {
			PENDING.put(player.getUUID(), new long[]{pos.asLong(), System.currentTimeMillis() + MANUAL_MILLIS});
			player.displayClientMessage(Text.translatable("gui.mtr.signal_manual_start"), true);
			return;
		}
		PENDING.remove(player.getUUID());
		final int sides = bind(world, pos);
		final BlockEntity entity = world.getBlockEntity(pos);
		final BlockPos node = entity instanceof TileEntitySignalBase ? ((TileEntitySignalBase) entity).getNode(0) : null;
		if (sides == 0) {
			player.displayClientMessage(Text.translatable("gui.mtr.signal_unbound", REACH), true);
		} else {
			player.displayClientMessage(Text.translatable("gui.mtr.signal_bound", node == null ? "-" : node.toShortString(), sides), true);
		}
	}

	/**
	 * A node clicked with the brush: binds the signal the player is binding by hand to it, on the side a rail leaves it
	 *
	 * @return true if the click was taken for that, so the node's own screen doesn't open
	 */
	public static boolean onNodeBrush(Level world, BlockPos nodePos, Player player) {
		final long[] pending = PENDING.remove(player.getUUID());
		if (pending == null || System.currentTimeMillis() > pending[1]) {
			return false;
		}
		final BlockPos pos = BlockPos.of(pending[0]);
		final BlockEntity entity = world.getBlockEntity(pos);
		final BlockState state = world.getBlockState(pos);
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (!(entity instanceof TileEntitySignalBase) || railwayData == null || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return false;
		}
		final Map<BlockPos, Rail> connections = railwayData.getRails().get(nodePos);
		final Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
		for (int side = 0; side < 2; side++) {
			final float angle = angleOf(facing, side);
			if (connections != null && connections.values().stream().anyMatch(rail -> rail.facingStart.similarFacing(angle))) {
				((TileEntitySignalBase) entity).setBinding(side, nodePos, angle, true);
				player.displayClientMessage(Text.translatable("gui.mtr.signal_manual_bound", nodePos.toShortString()), true);
				return true;
			}
		}
		player.displayClientMessage(Text.translatable("gui.mtr.signal_manual_no_rail"), true);
		return true;
	}

	/**
	 * @return whether a side's binding still leads anywhere: a rail leaves its node the bound way
	 */
	public static boolean leadsOn(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos node, float angle) {
		final Map<BlockPos, Rail> connections = rails.get(node);
		return connections != null && connections.values().stream().anyMatch(rail -> rail.facingStart.similarFacing(angle));
	}
}
