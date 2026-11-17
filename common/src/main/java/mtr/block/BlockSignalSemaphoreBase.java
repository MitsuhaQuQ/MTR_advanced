package mtr.block;

import mtr.compat.Compat;
import mtr.mappings.BlockDirectionalMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class BlockSignalSemaphoreBase extends BlockDirectionalMapper implements EntityBlockMapper {

	public BlockSignalSemaphoreBase(Properties settings) {
		super(Compat.noPistonPush(settings));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter blockGetter, BlockPos pos, CollisionContext collisionContext) {
		return Shapes.or(IBlock.getVoxelShapeByDirection(4, 4, 5, 12, 8, 11, IBlock.getStatePropertySafe(state, FACING)), Block.box(6, 0, 6, 10, 12, 10));
	}

	/**
	 * Binds the signal to the block sections it protects when put down (0.99.0, {@link SignalBinding})
	 */
	@Override
	public void setPlacedBy(net.minecraft.world.level.Level world, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack itemStack) {
		if (!world.isClientSide) {
			SignalBinding.bind(world, pos);
		}
	}

	/**
	 * The brush binds the signal again, or sneaking, by hand to the node clicked next (0.99.0)
	 */
	@Override
	public net.minecraft.world.InteractionResult use(BlockState state, net.minecraft.world.level.Level world, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		if (!player.isHolding(mtr.Items.BRUSH.get())) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		return IBlock.checkHoldingBrush(world, player, () -> SignalBinding.onBrush(world, pos, player));
	}

	public PushReaction getPistonPushReaction(BlockState blockState) {
		return PushReaction.BLOCK;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	public static abstract class TileEntitySignalSemaphoreBase extends TileEntitySignalBase {

		public float angle1;
		public float angle2;

		public TileEntitySignalSemaphoreBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}
	}
}
