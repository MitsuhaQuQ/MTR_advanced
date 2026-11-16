package mtr.block;

import mtr.data.Platform;
import mtr.data.PlatformCurves;
import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Curved screen doors and platform gates (0.91.0, tier 3 of diagonal and curved platforms): MTR's straight screen door
 * and gate blocks, each with a curved kind ({@link BlockCurvedPSD}) that keeps all their rules (two blocks high, leaves in
 * pairs, glass joined into a run with a frame at its ends, locking with the brush, the top's route map and arrows) and
 * stands on a platform's edge line instead of a block side. Each block keeps its piece of the edge line (the angle and cut
 * of {@link BlockPlatformEdge}) in its block entity, as a {@link PlatformEdges.Bend}; what the straight block would draw
 * is bent onto it ({@code mtr.render.BendingVertexConsumer}), and where the straight block looks at the block beside it
 * on a side, the curved one looks at the next block along the line ({@link #neighbour}).
 */
public final class CurvedPSD {

	private static final String KEY_ANGLE = "bend_angle";
	private static final String KEY_CUT = "bend_cut";
	private static final Map<Long, VoxelShape> SHAPES = new HashMap<>();
	/**
	 * How far along the run a brush sets the arrows and the persistent tops, as for the straight ones
	 */
	private static final int MOST_STEPS = 256;

	private CurvedPSD() {
	}

	/**
	 * A curved block: which straight block it is the curved kind of
	 */
	public interface CurvedBlock {
		Block getStraight();
	}

	/**
	 * A curved block's block entity, keeping its piece of the edge line
	 */
	public interface Curved {
		BendHolder getBendHolder();

		/**
		 * Sends the block entity to the players again after its bend changed
		 */
		void syncBend();
	}

	public static final class BendHolder {

		private int angle = -1;
		private int cut;
		private PlatformEdges.Bend bend;

		public PlatformEdges.Bend getBend() {
			return bend;
		}

		public int getAngle() {
			return angle;
		}

		public int getCut() {
			return cut;
		}

		public void set(int angle, int cut) {
			this.angle = angle;
			this.cut = cut;
			bend = angle < 0 ? null : PlatformEdges.bend(angle, cut);
		}

		public void read(CompoundTag compoundTag) {
			set(compoundTag.contains(KEY_ANGLE) ? compoundTag.getInt(KEY_ANGLE) : -1, compoundTag.getInt(KEY_CUT));
		}

		public void write(CompoundTag compoundTag) {
			if (angle >= 0) {
				compoundTag.putInt(KEY_ANGLE, angle);
				compoundTag.putInt(KEY_CUT, cut);
			}
		}
	}

	/**
	 * @return the bend of a curved block, null for a straight one or one not yet bent
	 */
	public static PlatformEdges.Bend bendAt(BlockGetter world, BlockPos pos) {
		final BlockEntity entity = world.getBlockEntity(pos);
		return entity instanceof Curved ? ((Curved) entity).getBendHolder().getBend() : null;
	}

	/**
	 * @return the block beside this one on its left (seen from the platform, facing the track) or right: the next along the
	 * edge line for a curved block, the one beside on its facing side for a straight one, itself when it has no facing
	 */
	public static BlockPos neighbour(BlockGetter world, BlockPos pos, boolean left) {
		final PlatformEdges.Bend bend = bendAt(world, pos);
		if (bend != null) {
			final double[] point = bend.beyond(left);
			return new BlockPos((int) Math.floor(pos.getX() + point[0]), pos.getY(), (int) Math.floor(pos.getZ() + point[1]));
		}
		final BlockState state = world.getBlockState(pos);
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return pos.relative(left ? facing.getCounterClockWise() : facing.getClockWise());
		}
		return pos;
	}

	/**
	 * @return the block the given number of blocks along the run on a side, as {@code pos.relative(side, steps)} is for
	 * straight blocks
	 */
	public static BlockPos walk(BlockGetter world, BlockPos pos, boolean left, int steps) {
		BlockPos result = pos;
		for (int i = 0; i < steps; i++) {
			final BlockPos next = neighbour(world, result, left);
			if (next.equals(result)) {
				break;
			}
			result = next;
		}
		return result;
	}

	/**
	 * @return the straight block a block is the curved kind of, or itself
	 */
	public static Block straightOf(Block block) {
		return block instanceof CurvedBlock ? ((CurvedBlock) block).getStraight() : block;
	}

	/**
	 * @return whether two blocks belong to one run: the same block, or a straight block and its curved kind
	 */
	public static boolean sameKind(Block block1, Block block2) {
		return block1 == block2 || straightOf(block1) == straightOf(block2);
	}

	/**
	 * @return the straight block's state with the same properties, to draw its model or ask its rules
	 */
	public static BlockState straightState(BlockState curved) {
		final Block straight = straightOf(curved.getBlock());
		if (straight == curved.getBlock()) {
			return curved;
		}
		BlockState result = straight.defaultBlockState();
		for (final Property<?> property : curved.getProperties()) {
			result = copyProperty(curved, result, property);
		}
		return result;
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
		return to.hasProperty(property) ? to.setValue(property, from.getValue(property)) : to;
	}

	/**
	 * @param fromCells how far behind the edge line the shape starts, in sixteenths
	 * @param toCells   how far behind it ends
	 * @param yFrom     its bottom, in sixteenths
	 * @param yTo       its top
	 * @return the shape of the straight block's part, bent onto the block's piece of the edge line; the given straight
	 * shape when the block isn't bent
	 */
	public static VoxelShape shape(BlockGetter world, BlockPos pos, int fromCells, int toCells, int yFrom, int yTo, VoxelShape straight) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (!(entity instanceof Curved) || ((Curved) entity).getBendHolder().getBend() == null) {
			return straight;
		}
		final BendHolder holder = ((Curved) entity).getBendHolder();
		final long key = (((((long) holder.getAngle() * PlatformEdges.CUTS + holder.getCut()) * 32 + fromCells) * 32 + toCells) * 64 + yFrom) * 64 + yTo;
		synchronized (SHAPES) {
			VoxelShape shape = SHAPES.get(key);
			if (shape == null) {
				final boolean[] mask = PlatformEdges.wallMask(holder.getAngle(), holder.getCut(), fromCells, toCells);
				shape = Shapes.empty();
				for (int x = 0; x < PlatformEdges.CELLS; x++) {
					int start = -1;
					for (int z = 0; z <= PlatformEdges.CELLS; z++) {
						final boolean solid = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z];
						if (solid && start < 0) {
							start = z;
						} else if (!solid && start >= 0) {
							shape = Shapes.or(shape, Block.box(x, yFrom, start, x + 1, yTo, z));
							start = -1;
						}
					}
				}
				shape = shape.optimize();
				SHAPES.put(key, shape);
			}
			return shape;
		}
	}

	/**
	 * @return the four-way direction nearest the line's direction towards the track, the facing of a curved block's state
	 */
	public static Direction facingOf(PlatformEdges.Bend bend) {
		final int[] facing = bend.nearestFacing();
		return Direction.fromDelta(facing[0], 0, facing[1]);
	}

	/**
	 * @return the angle and cut of the edge line where a screen door put at the place stands, as {@link PlatformEdges#pack}:
	 * that of the platform edge block below (so the door follows the edge laid there), else that of the nearest platform's
	 * edge, as the edge block works it out; -1 when no line crosses the block
	 */
	public static int lineAt(Level world, BlockPos pos) {
		final BlockState below = world.getBlockState(pos.below());
		if (below.getBlock() instanceof BlockPlatformEdge) {
			final int angle = IBlock.getStatePropertySafe(below, BlockPlatformEdge.ANGLE);
			final int cut = IBlock.getStatePropertySafe(below, BlockPlatformEdge.CUT);
			if (PlatformEdges.bend(angle, cut) != null) {
				return PlatformEdges.pack(angle, cut);
			}
		}
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return -1;
		}
		final Platform platform = BlockPlatformEdge.nearestPlatform(railwayData, pos.below());
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, railwayData.getRails());
		if (rail == null) {
			return -1;
		}
		final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, nearest.signedSagitta);
		final int packed = PlatformEdges.cutOf(pos.getX(), pos.getZ(), nearest.point.x + nearest.sideX * offset, nearest.point.z + nearest.sideZ * offset, nearest.sideX, nearest.sideZ);
		return packed >= 0 && PlatformEdges.bend(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed)) != null ? packed : -1;
	}

	/**
	 * Gives a curved block just put down its piece of the edge line
	 */
	public static void setBend(Level world, BlockPos pos, int packed) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (entity instanceof Curved) {
			((Curved) entity).getBendHolder().set(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed));
			entity.setChanged();
			((Curved) entity).syncBend();
		}
	}

	/**
	 * Joins a column of screen door or gate glass (its lower block) into the run beside it, as the straight glass does when
	 * put down or brushed, but finding its neighbours along the edge line: the glass gets a frame end only where no glass of
	 * its kind is beside it, and the glass beside loses its end on this side
	 */
	public static void connectGlass(Level world, BlockPos lowerPos) {
		final BlockState state = world.getBlockState(lowerPos);
		if (!(state.getBlock() instanceof BlockPSDAPGGlassBase)) {
			return;
		}
		final BlockPos leftPos = neighbour(world, lowerPos, true);
		final BlockPos rightPos = neighbour(world, lowerPos, false);
		final boolean leftValid = isGlassOfKind(world, leftPos, state.getBlock());
		final boolean rightValid = isGlassOfKind(world, rightPos, state.getBlock());
		if (leftValid) {
			setSideTowards(world, leftPos, false);
		}
		if (rightValid) {
			setSideTowards(world, rightPos, true);
		}
		final IBlock.EnumSide side = leftValid && rightValid ? IBlock.EnumSide.MIDDLE : leftValid ? IBlock.EnumSide.RIGHT : rightValid ? IBlock.EnumSide.LEFT : IBlock.EnumSide.SINGLE;
		setSide(world, lowerPos, side);
	}

	/**
	 * Takes away the frame end of a column of glass on the side towards a joining column: its right side when the joining
	 * one is on its right
	 */
	private static void setSideTowards(Level world, BlockPos lowerPos, boolean joinedOnLeft) {
		final IBlock.EnumSide side = IBlock.getStatePropertySafe(world.getBlockState(lowerPos), IBlock.SIDE_EXTENDED);
		final IBlock.EnumSide newSide;
		if (joinedOnLeft) {
			newSide = side == IBlock.EnumSide.LEFT ? IBlock.EnumSide.MIDDLE : side == IBlock.EnumSide.SINGLE ? IBlock.EnumSide.RIGHT : side;
		} else {
			newSide = side == IBlock.EnumSide.RIGHT ? IBlock.EnumSide.MIDDLE : side == IBlock.EnumSide.SINGLE ? IBlock.EnumSide.LEFT : side;
		}
		setSide(world, lowerPos, newSide);
	}

	private static void setSide(Level world, BlockPos lowerPos, IBlock.EnumSide side) {
		for (int y = 0; y <= 1; y++) {
			final BlockState state = world.getBlockState(lowerPos.above(y));
			if (state.hasProperty(IBlock.SIDE_EXTENDED) && state.getValue(IBlock.SIDE_EXTENDED) != side) {
				world.setBlockAndUpdate(lowerPos.above(y), state.setValue(IBlock.SIDE_EXTENDED, side));
			}
		}
	}

	private static boolean isGlassOfKind(BlockGetter world, BlockPos pos, Block block) {
		final BlockState state = world.getBlockState(pos);
		return sameKind(state.getBlock(), block) && IBlock.getStatePropertySafe(state, BlockPSDAPGBase.HALF) == DoubleBlockHalf.LOWER;
	}

	/**
	 * Calls back for each block of the same kind further along the run on a side, as {@link IBlock#propagate} does for the
	 * straight blocks, looking along the edge line
	 */
	public static void propagate(Level world, BlockPos pos, boolean left, Consumer<BlockPos> callback, int maxBlocksAway) {
		final Block block = world.getBlockState(pos).getBlock();
		BlockPos current = pos;
		for (int step = 0; step < MOST_STEPS; step++) {
			BlockPos found = null;
			BlockPos look = current;
			for (int i = 1; i <= maxBlocksAway; i++) {
				final BlockPos next = neighbour(world, look, left);
				if (next.equals(look)) {
					break;
				}
				look = next;
				if (sameKind(world.getBlockState(look).getBlock(), block)) {
					found = look;
					break;
				}
			}
			if (found == null) {
				return;
			}
			callback.accept(found);
			current = found;
		}
	}
}
