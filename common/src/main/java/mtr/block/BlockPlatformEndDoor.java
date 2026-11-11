package mtr.block;

import mtr.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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

/** A single-leaf manual end gate made from the matching platform glass panel. */
public final class BlockPlatformEndDoor extends BlockPSDAPGBase {
	public static final BooleanProperty OPEN = BooleanProperty.create("open");
	private final int style;

	public BlockPlatformEndDoor(int style) {
		this.style = style;
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER).setValue(SIDE_EXTENDED, EnumSide.SINGLE).setValue(OPEN, false));
	}

	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, HALF, SIDE_EXTENDED, OPEN); }
	@Override protected boolean isAPG() { return style == 0; }
	@Override public Item asItem() { return style == 0 ? Items.PLATFORM_END_APG.get() : style == 1 ? Items.PLATFORM_END_PSD_1.get() : Items.PLATFORM_END_PSD_2.get(); }
	@Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return outline(style, state.getValue(HALF), state.getValue(FACING), state.getValue(OPEN));
	}
	static VoxelShape outline(int style, DoubleBlockHalf half, Direction facing, boolean open) {
		final int height = style == 0 && half == DoubleBlockHalf.UPPER ? 9 : 16;
		if (open) {
			// The north-facing leaf swings outward around the jamb at (15, 1).
			return IBlock.getVoxelShapeByDirection(14, 0, -14, 16, height, 2, facing);
		}
		return IBlock.getVoxelShapeByDirection(0, 0, 0, 16, height, 4, facing);
	}

	@Override public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (world.isClientSide) return InteractionResult.SUCCESS;
		final BlockPos base = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		final boolean open = !state.getValue(OPEN);
		for (int y = 0; y < 2; y++) {
			final BlockPos part = base.above(y);
			final BlockState other = world.getBlockState(part);
			if (other.is(this)) world.setBlockAndUpdate(part, other.setValue(OPEN, open));
		}
		world.playSound(null, base, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1, 1);
		return InteractionResult.SUCCESS;
	}
}
