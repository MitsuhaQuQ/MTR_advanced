package mtr.block;

import mtr.mappings.BlockEntityClientSerializableMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A signal's block entity, keeping which block section each of its sides protects (0.99.0, design 7.6): the node the
 * section starts at and the way trains leave it, worked out when the signal is put down ({@link SignalBinding}) or set by
 * hand with the brush, and sent to the players. Side 0 is the way the signal faces, side 1 the way back (double-sided
 * signals). A signal from before 0.99.0 has no binding saved and is drawn as before, from the nearest node.
 */
public abstract class TileEntitySignalBase extends BlockEntityClientSerializableMapper {

	/**
	 * Whether a binding has been worked out at all, even if no node was found for a side
	 */
	private boolean hasBinding;
	private final long[] nodes = {0, 0};
	private final float[] angles = {0, 0};
	private final boolean[] bound = {false, false};
	private final boolean[] manual = {false, false};

	private static final String KEY_BOUND = "signal_binding";
	private static final String KEY_NODE = "signal_node_";
	private static final String KEY_ANGLE = "signal_angle_";
	private static final String KEY_MANUAL = "signal_manual_";

	public TileEntitySignalBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void readCompoundTag(CompoundTag compoundTag) {
		hasBinding = compoundTag.getBoolean(KEY_BOUND);
		for (int side = 0; side < 2; side++) {
			bound[side] = compoundTag.contains(KEY_NODE + side);
			nodes[side] = compoundTag.getLong(KEY_NODE + side);
			angles[side] = compoundTag.getFloat(KEY_ANGLE + side);
			// An angle that isn't a number (bad data): that side unbound, shown as before binding
			if (!Float.isFinite(angles[side])) {
				bound[side] = false;
				angles[side] = 0;
			}
			manual[side] = compoundTag.getBoolean(KEY_MANUAL + side);
		}
	}

	@Override
	public void writeCompoundTag(CompoundTag compoundTag) {
		if (!hasBinding) {
			return;
		}
		compoundTag.putBoolean(KEY_BOUND, true);
		for (int side = 0; side < 2; side++) {
			if (bound[side]) {
				compoundTag.putLong(KEY_NODE + side, nodes[side]);
				compoundTag.putFloat(KEY_ANGLE + side, angles[side]);
				if (manual[side]) {
					compoundTag.putBoolean(KEY_MANUAL + side, true);
				}
			}
		}
	}

	/**
	 * @return whether the signal has a binding worked out (0.99.0 or later), so it is drawn by it
	 */
	public boolean hasBinding() {
		return hasBinding;
	}

	/**
	 * @return the node a side's section starts at, or null when that side has none
	 */
	public BlockPos getNode(int side) {
		return bound[side] ? BlockPos.of(nodes[side]) : null;
	}

	/**
	 * @return the way trains leave the node into the side's section, in degrees as rail angles have it
	 */
	public float getAngle(int side) {
		return angles[side];
	}

	public boolean isManual(int side) {
		return manual[side];
	}

	/**
	 * Sets a side's binding, null for none, and sends it to the players
	 */
	public void setBinding(int side, BlockPos node, float angle, boolean isManual) {
		hasBinding = true;
		bound[side] = node != null;
		nodes[side] = node == null ? 0 : node.asLong();
		angles[side] = angle;
		manual[side] = node != null && isManual;
		setChanged();
		syncData();
	}
}
