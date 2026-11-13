package mtr.block;

import mtr.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A single-leaf manual end gate made from the matching platform glass panel. Its leaf stands where the glass of a screen
 * door or gate panel facing the same way does, so placed beside one it takes that one's facing and lines up in the same
 * row of blocks; which way it swings open is its own (0.65.29): away from the side it faces, or towards it.
 */
public final class BlockPlatformEndDoor extends BlockPSDAPGBase {
	public static final BooleanProperty OPEN = BooleanProperty.create("open");
	/**
	 * Whether the leaf swings open towards the back of the block (+z of a north facing gate) rather than out in front.
	 */
	public static final BooleanProperty INWARD = BooleanProperty.create("inward");
	private final int style;

	public BlockPlatformEndDoor(int style) {
		this.style = style;
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER).setValue(SIDE_EXTENDED, EnumSide.SINGLE).setValue(OPEN, false).setValue(INWARD, true));
	}

	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, HALF, SIDE_EXTENDED, OPEN, INWARD); }

	/**
	 * Beside a screen door or gate panel along its row, the gate faces the way it does, so its leaf is in line with the
	 * glass. It swings towards the platform, whichever side it is put down from (user, 2026-11-14, who tried both ways
	 * round in the game), which sneaking and right clicking turns round. {@link mtr.item.ItemPSDAPGBase} puts the block
	 * down from its default state rather than through here, so that state carries the same side.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		final BlockState state = super.getStateForPlacement(ctx);
		if (state == null) return null;
		final Direction looking = ctx.getHorizontalDirection();
		final Direction facing = rowFacing(ctx.getLevel(), ctx.getClickedPos(), looking);
		return state.setValue(FACING, facing).setValue(INWARD, true);
	}

	/**
	 * @return the facing of a screen door or gate block beside this position along its own row, the nearest one looked
	 * at first, or the way the player looks if there is none
	 */
	static Direction rowFacing(BlockGetter world, BlockPos pos, Direction looking) {
		for (final Direction side : new Direction[]{looking.getClockWise(), looking.getCounterClockWise(), looking, looking.getOpposite()}) {
			final BlockState neighbour = world.getBlockState(pos.relative(side));
			if (neighbour.getBlock() instanceof BlockPSDAPGBase && neighbour.hasProperty(FACING)) {
				final Direction neighbourFacing = neighbour.getValue(FACING);
				// Along its row: the side is across the way it faces
				if (neighbourFacing.getAxis() != side.getAxis()) return neighbourFacing;
			}
		}
		return looking;
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity livingEntity, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, livingEntity, itemStack);
		if (!world.isClientSide) {
			final BlockState upper = world.getBlockState(pos.above());
			if (upper.is(this)) world.setBlock(pos.above(), upper.setValue(INWARD, state.getValue(INWARD)), 3);
		}
	}
	/** Styles: 0 the gate, 1 the screen door, 2 the screen door without the handle outside, 3 the gate without it */
	@Override protected boolean isAPG() { return style == 0 || style == 3; }
	@Override public Item asItem() { return style == 0 ? Items.PLATFORM_END_APG.get() : style == 1 ? Items.PLATFORM_END_PSD_1.get() : style == 2 ? Items.PLATFORM_END_PSD_2.get() : Items.PLATFORM_END_APG_2.get(); }
	@Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return outline(style, state.getValue(HALF), state.getValue(FACING), state.getValue(OPEN), state.getValue(INWARD));
	}
	static VoxelShape outline(int style, DoubleBlockHalf half, Direction facing, boolean open) {
		return outline(style, half, facing, open, false);
	}
	static VoxelShape outline(int style, DoubleBlockHalf half, Direction facing, boolean open, boolean inward) {
		final int height = (style == 0 || style == 3) && half == DoubleBlockHalf.UPPER ? 9 : 16;
		if (open) {
			// The north-facing leaf swings around the jamb at (15, 3), in line with the screen door glass at z 2..4: out
			// in front of the block, or back into it and the one behind
			return inward ? IBlock.getVoxelShapeByDirection(14, 0, 2, 16, height, 18, facing) : IBlock.getVoxelShapeByDirection(14, 0, -12, 16, height, 4, facing);
		}
		return IBlock.getVoxelShapeByDirection(0, 0, 0, 16, height, 4, facing);
	}

	@Override public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (world.isClientSide) return InteractionResult.SUCCESS;
		final BlockPos base = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		// Sneaking, the way it swings is changed (closed first), not whether it is open
		final boolean swing = player.isShiftKeyDown();
		final boolean open = !swing && !state.getValue(OPEN);
		final boolean inward = swing != state.getValue(INWARD);
		for (int y = 0; y < 2; y++) {
			final BlockPos part = base.above(y);
			final BlockState other = world.getBlockState(part);
			if (other.is(this)) world.setBlockAndUpdate(part, other.setValue(OPEN, open).setValue(INWARD, inward));
		}
		world.playSound(null, base, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1, 1);
		return InteractionResult.SUCCESS;
	}
}
