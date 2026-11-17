package mtr.block;

import mtr.mappings.BlockDirectionalMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BlockPlatform extends BlockDirectionalMapper {

	/**
	 * Never walked through, so mobs stand on it: its shape isn't a whole block when indented, which the game took as open like
	 * air, and villagers on the track couldn't step up onto it (user, 2026-11-15), as for stairs and slabs
	 */
	@Override
	public boolean isPathfindable(BlockState state, BlockGetter world, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
		return false;
	}

	private final boolean isIndented;

	public static final EnumProperty<EnumDoorType> DOOR_TYPE = EnumProperty.create("door_type", EnumDoorType.class);
	public static final IntegerProperty SIDE = IntegerProperty.create("side", 0, 4);
	/**
	 * The block's own facing while a straight screen door or platform gate turns it to theirs (0.97.0): 0 when it faces
	 * its own way, else its own facing's 2D data value plus one, given back when the door is taken away. Blocks turned by
	 * a door before 0.97.0 have no record and keep the door's facing.
	 */
	public static final IntegerProperty BASE_FACING = IntegerProperty.create("base_facing", 0, 4);

	public BlockPlatform(Properties settings, boolean isIndented) {
		super(settings);
		this.isIndented = isIndented;
		registerDefaultState(defaultBlockState().setValue(DOOR_TYPE, EnumDoorType.NONE).setValue(BASE_FACING, 0));
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState newState, LevelAccessor world, BlockPos pos, BlockPos posFrom) {
		return getActualState(world, pos, state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		if (isIndented) {
			final Direction facing = IBlock.getStatePropertySafe(state, FACING);
			return Shapes.or(IBlock.getVoxelShapeByDirection(0, 0, 6, 16, 13, 16, facing), Block.box(0, 13, 0, 16, 16, 16));
		} else {
			return super.getShape(state, world, pos, context);
		}
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, DOOR_TYPE, SIDE, BASE_FACING);
	}

	private BlockState getActualState(BlockGetter world, BlockPos pos, BlockState state) {
		// Its own facing: the one it had before a door turned it, if one did (0.97.0)
		final int baseFacing = IBlock.getStatePropertySafe(state, BASE_FACING);
		final Direction ownFacing = baseFacing > 0 ? Direction.from2DDataValue(baseFacing - 1) : IBlock.getStatePropertySafe(state, FACING);
		Direction facing = ownFacing;

		final BlockState stateAbove = world.getBlockState(pos.above());
		final Block blockAbove = stateAbove.getBlock();
		// Curved screen doors and gates (0.91.0) give the top but don't turn the block, and draw their boarding arrows
		// themselves along the edge (0.97.0)
		final boolean curvedAbove = blockAbove instanceof CurvedPSD.CurvedBlock;

		EnumDoorType doorType;
		if (blockAbove instanceof BlockPSDDoor || blockAbove instanceof BlockPSDGlass || blockAbove instanceof BlockPSDGlassEnd) {
			doorType = EnumDoorType.PSD;
			if (!curvedAbove) {
				facing = IBlock.getStatePropertySafe(stateAbove, FACING);
			}
		} else if (blockAbove instanceof BlockAPGDoor || blockAbove instanceof BlockAPGGlass || blockAbove instanceof BlockAPGGlassEnd) {
			doorType = EnumDoorType.APG;
			if (!curvedAbove) {
				facing = IBlock.getStatePropertySafe(stateAbove, FACING);
			}
		} else {
			doorType = EnumDoorType.NONE;
		}

		final boolean aboveIsDoor = blockAbove instanceof BlockPSDAPGDoorBase && !curvedAbove;

		final BlockState stateLeftAbove = world.getBlockState(pos.above().relative(facing.getCounterClockWise()));
		final boolean leftAboveIsDoor = stateLeftAbove.getBlock() instanceof BlockPSDAPGDoorBase && !(stateLeftAbove.getBlock() instanceof CurvedPSD.CurvedBlock) && !curvedAbove;

		final BlockState stateRightAbove = world.getBlockState(pos.above().relative(facing.getClockWise()));
		final boolean rightAboveIsDoor = stateRightAbove.getBlock() instanceof BlockPSDAPGDoorBase && !(stateRightAbove.getBlock() instanceof CurvedPSD.CurvedBlock) && !curvedAbove;

		final int side;
		if (aboveIsDoor && rightAboveIsDoor) {
			side = 2;
		} else if (aboveIsDoor && leftAboveIsDoor) {
			side = 3;
		} else if (rightAboveIsDoor) {
			side = 1;
			facing = IBlock.getStatePropertySafe(stateRightAbove, FACING);
		} else if (leftAboveIsDoor) {
			side = 4;
			facing = IBlock.getStatePropertySafe(stateLeftAbove, FACING);
		} else {
			side = 0;
		}

		return state.setValue(FACING, facing).setValue(DOOR_TYPE, doorType).setValue(SIDE, side).setValue(BASE_FACING, facing == ownFacing ? 0 : ownFacing.get2DDataValue() + 1);
	}

	/**
	 * A platform block's look under what stands on a place (0.97.0, for the platform edge blocks drawn as the platform
	 * they were put against): as {@link #getActualState}, the screen door or platform gate top over glass and doors, and
	 * the boarding arrows by straight doors ({@link #arrowSide}). The arrows by curved screen doors are drawn by the doors
	 * themselves along the edge line ({@code RenderCurvedPSD}), as the block under a curved door is often only a sliver of
	 * the platform. The block keeps its own facing, its side towards the track. Other blocks are returned as they are.
	 */
	public static BlockState withDoorsAbove(BlockGetter world, BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof BlockPlatform)) {
			return state;
		}
		final BlockPos posAbove = pos.above();
		final Block blockAbove = world.getBlockState(posAbove).getBlock();
		final EnumDoorType doorType;
		if (blockAbove instanceof BlockPSDDoor || blockAbove instanceof BlockPSDGlass || blockAbove instanceof BlockPSDGlassEnd) {
			doorType = EnumDoorType.PSD;
		} else if (blockAbove instanceof BlockAPGDoor || blockAbove instanceof BlockAPGGlass || blockAbove instanceof BlockAPGGlassEnd) {
			doorType = EnumDoorType.APG;
		} else {
			doorType = EnumDoorType.NONE;
		}
		return state.setValue(DOOR_TYPE, doorType).setValue(SIDE, blockAbove instanceof CurvedPSD.CurvedBlock ? 0 : arrowSide(world, posAbove));
	}

	/**
	 * Which boarding arrows the platform under a screen door or platform gate column has (0.97.0), as
	 * {@link #getActualState} gives them: 2 under a door's left leaf, 3 under its right, 1 under the glass on a door's
	 * left, 4 on its right, 0 for none. Beside means along the edge line for curved ones, beside on their facing side for
	 * straight ones.
	 *
	 * @param pos the column's lower block
	 */
	public static int arrowSide(BlockGetter world, BlockPos pos) {
		final BlockState state = world.getBlockState(pos);
		final Block block = state.getBlock();
		if (block instanceof BlockPSDAPGDoorBase) {
			return IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.RIGHT ? 3 : 2;
		}
		if (block instanceof BlockPSDAPGBase) {
			if (isDoorBeside(world, pos, false)) {
				return 1;
			} else if (isDoorBeside(world, pos, true)) {
				return 4;
			}
		}
		return 0;
	}

	private static boolean isDoorBeside(BlockGetter world, BlockPos pos, boolean left) {
		final BlockPos beside = CurvedPSD.neighbour(world, pos, left);
		return !beside.equals(pos) && world.getBlockState(beside).getBlock() instanceof BlockPSDAPGDoorBase;
	}

	private enum EnumDoorType implements StringRepresentable {

		NONE("none"), PSD("psd"), APG("apg");
		private final String name;

		EnumDoorType(String nameIn) {
			name = nameIn;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}
}
