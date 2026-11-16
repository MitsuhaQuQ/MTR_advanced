package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Blocks;
import mtr.Items;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The curved kinds of MTR's screen door and platform gate blocks (0.91.0, {@link CurvedPSD}): each extends the straight
 * block, so its rules stay, and changes only what depends on standing along a block side: its shape, the block found
 * beside it, and how it is drawn (by its block entity renderer, bent).
 */
public final class BlockCurvedPSD {

	/**
	 * The depth of a screen door's or gate's glass and leaves behind the edge, and of the screen door's top, in sixteenths,
	 * as the straight blocks' shapes have it
	 */
	private static final int PANEL_DEPTH = 4;
	private static final int TOP_DEPTH = 6;

	private BlockCurvedPSD() {
	}

	private static VoxelShape panelShape(BlockState state, BlockGetter world, BlockPos pos, boolean isAPG, VoxelShape straight) {
		final int height = isAPG && IBlock.getStatePropertySafe(state, BlockPSDAPGBase.HALF) == DoubleBlockHalf.UPPER ? 9 : 16;
		return CurvedPSD.shape(world, pos, 0, PANEL_DEPTH, 0, height, straight);
	}

	private static BlockPos lowerOf(BlockState state, BlockPos pos) {
		return IBlock.getStatePropertySafe(state, BlockPSDAPGBase.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/**
	 * Brushing glass joins it into the run along the edge line
	 */
	private static InteractionResult useGlass(Level world, BlockPos pos, BlockState state, Player player) {
		return IBlock.checkHoldingBrush(world, player, () -> CurvedPSD.connectGlass(world, lowerOf(state, pos)));
	}

	/**
	 * Brushing a leaf locks or unlocks it, the leaf above or below and the other leaf of the door along the edge line
	 */
	private static InteractionResult useDoor(Level world, BlockPos pos, BlockState state, Player player) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			final boolean unlocked = !IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.UNLOCKED);
			final BlockPos lowerPos = lowerOf(state, pos);
			final boolean isLeft = IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.LEFT;
			final BlockPos otherLowerPos = CurvedPSD.neighbour(world, lowerPos, !isLeft);
			for (final BlockPos columnPos : new BlockPos[]{lowerPos, otherLowerPos}) {
				for (int y = 0; y <= 1; y++) {
					final BlockState scanState = world.getBlockState(columnPos.above(y));
					if (CurvedPSD.sameKind(scanState.getBlock(), state.getBlock()) && scanState.getBlock() instanceof BlockPSDAPGDoorBase) {
						world.setBlockAndUpdate(columnPos.above(y), scanState.setValue(BlockPSDAPGDoorBase.UNLOCKED, unlocked));
					}
				}
			}
			player.displayClientMessage(unlocked ? mtr.mappings.Text.translatable("gui.mtr.psd_apg_door_unlocked") : mtr.mappings.Text.translatable("gui.mtr.psd_apg_door_locked"), true);
		});
	}

	/**
	 * @return where the other leaf of a door is, along the edge line
	 */
	private static BlockPos otherLeaf(Level world, BlockPos lowerPos, BlockState state) {
		return CurvedPSD.neighbour(world, lowerPos, IBlock.getStatePropertySafe(state, IBlock.SIDE) != IBlock.EnumSide.LEFT);
	}

	/**
	 * Takes the other leaf of a door away with this one (the straight door's leaf goes when the block beside it does)
	 */
	private static void removeOtherLeaf(Level world, BlockPos pos, BlockState state) {
		final BlockPos lowerPos = lowerOf(state, pos);
		final BlockPos otherPos = otherLeaf(world, lowerPos, state);
		final BlockState otherState = world.getBlockState(otherPos);
		if (!otherPos.equals(lowerPos) && otherState.is(state.getBlock())) {
			world.setBlock(otherPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
		}
	}

	public static class PSDGlass extends BlockPSDGlass implements EntityBlockMapper, CurvedPSD.CurvedBlock {

		private final int style;

		public PSDGlass(int style) {
			super(style);
			this.style = style;
		}

		@Override
		public Block getStraight() {
			return (style == 0 ? Blocks.PSD_GLASS_1 : Blocks.PSD_GLASS_2).get();
		}

		@Override
		public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
			return panelShape(state, world, pos, false, super.getShape(state, world, pos, context));
		}

		@Override
		public RenderShape getRenderShape(BlockState state) {
			return RenderShape.ENTITYBLOCK_ANIMATED;
		}

		@Override
		public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
			return useGlass(world, pos, state, player);
		}

		@Override
		public Item asItem() {
			return (style == 0 ? Items.CURVED_PSD_GLASS_1 : Items.CURVED_PSD_GLASS_2).get();
		}

		@Override
		public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
			return new GlassEntity(style == 0 ? BlockEntityTypes.CURVED_PSD_GLASS_1_TILE_ENTITY.get() : BlockEntityTypes.CURVED_PSD_GLASS_2_TILE_ENTITY.get(), pos, state);
		}
	}

	public static class PSDDoor extends BlockPSDDoor implements CurvedPSD.CurvedBlock {

		private final int style;

		public PSDDoor(int style) {
			super(style);
			this.style = style;
		}

		@Override
		public Block getStraight() {
			return (style == 0 ? Blocks.PSD_DOOR_1 : Blocks.PSD_DOOR_2).get();
		}

		@Override
		public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
			return panelShape(state, world, pos, false, super.getShape(state, world, pos, context));
		}

		@Override
		public RenderShape getRenderShape(BlockState state) {
			return RenderShape.ENTITYBLOCK_ANIMATED;
		}

		@Override
		public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
			return useDoor(world, pos, state, player);
		}

		@Override
		public BlockState updateShape(BlockState state, net.minecraft.core.Direction direction, BlockState newState, LevelAccessor world, BlockPos pos, BlockPos posFrom) {
			return direction.getAxis().isHorizontal() ? state : super.updateShape(state, direction, newState, world, pos, posFrom);
		}

		@Override
		public void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
			removeOtherLeaf(world, pos, state);
			super.playerWillDestroy(world, pos, state, player);
		}

		@Override
		protected BlockPos getLeftLeafPos(Level world, BlockPos lowerPos, BlockState state) {
			return lowerPos;
		}

		@Override
		protected boolean followsLeafBeside() {
			return false;
		}

		@Override
		public Item asItem() {
			return (style == 0 ? Items.CURVED_PSD_DOOR_1 : Items.CURVED_PSD_DOOR_2).get();
		}

		@Override
		public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
			return new PSDDoorEntity(style == 0 ? BlockEntityTypes.CURVED_PSD_DOOR_1_TILE_ENTITY.get() : BlockEntityTypes.CURVED_PSD_DOOR_2_TILE_ENTITY.get(), pos, state);
		}
	}

	public static class PSDTop extends BlockPSDTop implements CurvedPSD.CurvedBlock {

		@Override
		public Block getStraight() {
			return Blocks.PSD_TOP.get();
		}

		@Override
		public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
			final int bottom = IBlock.getStatePropertySafe(state, PERSISTENT) == EnumPersistent.NONE ? 0 : 8;
			return CurvedPSD.shape(world, pos, 0, TOP_DEPTH, bottom, 16, super.getShape(state, world, pos, context));
		}

		@Override
		public RenderShape getRenderShape(BlockState state) {
			return RenderShape.ENTITYBLOCK_ANIMATED;
		}

		/**
		 * As the straight top: the brush turns the arrows of the run, shears make the run keep its sign without a door
		 * below; the run found along the edge line
		 */
		@Override
		public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
			return IBlock.checkHoldingItem(world, player, item -> {
				if (item == Items.BRUSH.get()) {
					final BlockState cycled = state.cycle(ARROW_DIRECTION);
					world.setBlockAndUpdate(pos, cycled);
					final Integer arrow = cycled.getValue(ARROW_DIRECTION);
					for (final boolean left : new boolean[]{false, true}) {
						CurvedPSD.propagate(world, pos, left, offsetPos -> world.setBlockAndUpdate(offsetPos, world.getBlockState(offsetPos).setValue(ARROW_DIRECTION, arrow)), 1);
					}
				} else {
					final boolean shouldBePersistent = IBlock.getStatePropertySafe(state, PERSISTENT) == EnumPersistent.NONE;
					setPersistent(world, pos, shouldBePersistent);
					for (final boolean left : new boolean[]{false, true}) {
						CurvedPSD.propagate(world, pos, left, offsetPos -> setPersistent(world, offsetPos, shouldBePersistent), 1);
					}
				}
			}, null, Items.BRUSH.get(), net.minecraft.world.item.Items.SHEARS);
		}

		private static void setPersistent(Level world, BlockPos pos, boolean shouldBePersistent) {
			final Block blockBelow = world.getBlockState(pos.below()).getBlock();
			if (blockBelow instanceof BlockPSDDoor || blockBelow instanceof BlockPSDGlass) {
				world.setBlockAndUpdate(pos, world.getBlockState(pos).setValue(PERSISTENT, shouldBePersistent ? blockBelow instanceof BlockPSDDoor ? EnumPersistent.ARROW : EnumPersistent.ROUTE : EnumPersistent.NONE));
			}
		}

		@Override
		public Item asItem() {
			return Items.CURVED_PSD_GLASS_1.get();
		}

		@Override
		public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
			return new TopEntity(pos, state);
		}
	}

	public static class APGGlass extends BlockAPGGlass implements CurvedPSD.CurvedBlock {

		@Override
		public Block getStraight() {
			return Blocks.APG_GLASS.get();
		}

		@Override
		public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
			return panelShape(state, world, pos, true, super.getShape(state, world, pos, context));
		}

		@Override
		public RenderShape getRenderShape(BlockState state) {
			return RenderShape.ENTITYBLOCK_ANIMATED;
		}

		/**
		 * As the straight gate glass: the brush on the sign at the top of the upper half turns the run's arrows, elsewhere
		 * joins the glass; the run found along the edge line
		 */
		@Override
		public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
			final double y = blockHitResult.getLocation().y;
			if (IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER && y - Math.floor(y) > 0.21875) {
				return IBlock.checkHoldingBrush(world, player, () -> {
					final BlockState cycled = state.cycle(ARROW_DIRECTION);
					world.setBlockAndUpdate(pos, cycled);
					final Integer arrow = cycled.getValue(ARROW_DIRECTION);
					for (final boolean left : new boolean[]{false, true}) {
						CurvedPSD.propagate(world, pos, left, offsetPos -> world.setBlockAndUpdate(offsetPos, world.getBlockState(offsetPos).setValue(ARROW_DIRECTION, arrow)), 3);
					}
				});
			} else {
				return useGlass(world, pos, state, player);
			}
		}

		@Override
		public Item asItem() {
			return Items.CURVED_APG_GLASS.get();
		}

		@Override
		public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
			return new APGGlassEntity(pos, state);
		}
	}

	public static class APGDoor extends BlockAPGDoor implements CurvedPSD.CurvedBlock {

		@Override
		public Block getStraight() {
			return Blocks.APG_DOOR.get();
		}

		@Override
		public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
			return panelShape(state, world, pos, true, super.getShape(state, world, pos, context));
		}

		@Override
		public RenderShape getRenderShape(BlockState state) {
			return RenderShape.ENTITYBLOCK_ANIMATED;
		}

		@Override
		public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
			return useDoor(world, pos, state, player);
		}

		@Override
		public BlockState updateShape(BlockState state, net.minecraft.core.Direction direction, BlockState newState, LevelAccessor world, BlockPos pos, BlockPos posFrom) {
			return direction.getAxis().isHorizontal() ? state : super.updateShape(state, direction, newState, world, pos, posFrom);
		}

		@Override
		public void playerWillDestroy(Level world, BlockPos pos, BlockState state, Player player) {
			removeOtherLeaf(world, pos, state);
			super.playerWillDestroy(world, pos, state, player);
		}

		@Override
		protected BlockPos getLeftLeafPos(Level world, BlockPos lowerPos, BlockState state) {
			return lowerPos;
		}

		@Override
		protected boolean followsLeafBeside() {
			return false;
		}

		@Override
		public Item asItem() {
			return Items.CURVED_APG_DOOR.get();
		}

		@Override
		public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
			return new APGDoorEntity(pos, state);
		}
	}

	/**
	 * Sends a block entity that MTR doesn't send to the players with its data (the top's, the gate glass's), for its bend
	 */
	private static Packet<ClientGamePacketListener> updatePacket(BlockEntity entity) {
		return ClientboundBlockEntityDataPacket.create(entity);
	}

	private static void resend(BlockEntity entity) {
		if (entity.getLevel() instanceof ServerLevel) {
			((ServerLevel) entity.getLevel()).getChunkSource().blockChanged(entity.getBlockPos());
		}
	}

	public static class GlassEntity extends BlockEntityClientSerializableMapper implements CurvedPSD.Curved {

		private final CurvedPSD.BendHolder bendHolder = new CurvedPSD.BendHolder();

		public GlassEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}

		@Override
		public CurvedPSD.BendHolder getBendHolder() {
			return bendHolder;
		}

		@Override
		public void syncBend() {
			syncData();
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			bendHolder.read(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			bendHolder.write(compoundTag);
		}
	}

	public static class PSDDoorEntity extends BlockPSDDoor.TileEntityPSDDoor implements CurvedPSD.Curved {

		private final CurvedPSD.BendHolder bendHolder = new CurvedPSD.BendHolder();

		public PSDDoorEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}

		@Override
		public CurvedPSD.BendHolder getBendHolder() {
			return bendHolder;
		}

		@Override
		public void syncBend() {
			syncData();
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			super.readCompoundTag(compoundTag);
			bendHolder.read(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			super.writeCompoundTag(compoundTag);
			bendHolder.write(compoundTag);
		}
	}

	public static class APGDoorEntity extends BlockAPGDoor.TileEntityAPGDoor implements CurvedPSD.Curved {

		private final CurvedPSD.BendHolder bendHolder = new CurvedPSD.BendHolder();

		public APGDoorEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.CURVED_APG_DOOR_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public CurvedPSD.BendHolder getBendHolder() {
			return bendHolder;
		}

		@Override
		public void syncBend() {
			syncData();
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			super.readCompoundTag(compoundTag);
			bendHolder.read(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			super.writeCompoundTag(compoundTag);
			bendHolder.write(compoundTag);
		}
	}

	public static class TopEntity extends BlockPSDTop.TileEntityPSDTop implements CurvedPSD.Curved {

		private final CurvedPSD.BendHolder bendHolder = new CurvedPSD.BendHolder();

		public TopEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.CURVED_PSD_TOP_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public CurvedPSD.BendHolder getBendHolder() {
			return bendHolder;
		}

		@Override
		public void syncBend() {
			resend(this);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			super.readCompoundTag(compoundTag);
			bendHolder.read(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			super.writeCompoundTag(compoundTag);
			bendHolder.write(compoundTag);
		}

		@Override
		public Packet<ClientGamePacketListener> getUpdatePacket() {
			return updatePacket(this);
		}

		@Override
		public CompoundTag getUpdateTag() {
			final CompoundTag compoundTag = super.getUpdateTag();
			writeCompoundTag(compoundTag);
			return compoundTag;
		}
	}

	public static class APGGlassEntity extends BlockAPGGlass.TileEntityAPGGlass implements CurvedPSD.Curved {

		private final CurvedPSD.BendHolder bendHolder = new CurvedPSD.BendHolder();

		public APGGlassEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.CURVED_APG_GLASS_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public CurvedPSD.BendHolder getBendHolder() {
			return bendHolder;
		}

		@Override
		public void syncBend() {
			resend(this);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			super.readCompoundTag(compoundTag);
			bendHolder.read(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			super.writeCompoundTag(compoundTag);
			bendHolder.write(compoundTag);
		}

		@Override
		public Packet<ClientGamePacketListener> getUpdatePacket() {
			return updatePacket(this);
		}

		@Override
		public CompoundTag getUpdateTag() {
			final CompoundTag compoundTag = super.getUpdateTag();
			writeCompoundTag(compoundTag);
			return compoundTag;
		}
	}
}
