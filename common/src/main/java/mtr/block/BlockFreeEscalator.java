package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Items;
import mtr.compat.Compat;
import mtr.compat.Material;
import mtr.compat.MaterialColor;
import mtr.escalator.FreeEscalatorPath;
import mtr.escalator.FreeEscalators;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * A block a free escalator's standing surface passes through (0.100.0): not drawn itself (the first block of each
 * escalator draws the whole of it, {@code RenderFreeEscalator}), it gives the surface's collision from the heights its
 * block entity keeps. Every block knows the first one, which holds the escalator: its points, width, speed, whether it
 * runs up, down or stands still, and all its blocks. Taking any block away takes the whole escalator away
 * ({@link FreeEscalators#onRemoved}). The brush cycles up / down / stop; sneaking with the brush gives the settings.
 */
public class BlockFreeEscalator extends Block implements EntityBlockMapper {

	private static final VoxelShape NOTHING_YET = Block.box(0, 0, 0, 16, 2, 16);

	public BlockFreeEscalator() {
		super(Compat.noPistonPush(Material.properties(Material.METAL, MaterialColor.COLOR_GRAY).strength(2).noOcclusion()));
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		final VoxelShape shape = shapeAt(world, pos);
		return shape.isEmpty() ? NOTHING_YET : shape;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return shapeAt(world, pos);
	}

	@Override
	public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> FreeEscalators.cycleMode(world, pos, player));
	}

	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved) {
		if (!newState.is(this)) {
			final BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof TileEntityFreeEscalator) {
				FreeEscalators.onRemoved(world, pos, (TileEntityFreeEscalator) blockEntity);
			}
		}
		super.onRemove(state, world, pos, newState, moved);
	}

	@Override
	public ItemStack getCloneItemStack(BlockGetter world, BlockPos pos, BlockState state) {
		return new ItemStack(Items.FREE_ESCALATOR.get());
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityFreeEscalator(pos, state);
	}

	@Override
	public BlockEntityType<? extends BlockEntityMapper> getType() {
		return BlockEntityTypes.FREE_ESCALATOR.get();
	}

	@Override
	public <T extends BlockEntityMapper> void tick(Level world, BlockPos pos, T blockEntity) {
		if (blockEntity instanceof TileEntityFreeEscalator && ((TileEntityFreeEscalator) blockEntity).isHead()) {
			FreeEscalators.tick(world, (TileEntityFreeEscalator) blockEntity);
		}
	}

	private static VoxelShape shapeAt(BlockGetter world, BlockPos pos) {
		final BlockEntity blockEntity = world.getBlockEntity(pos);
		return blockEntity instanceof TileEntityFreeEscalator ? ((TileEntityFreeEscalator) blockEntity).getShape() : Shapes.empty();
	}

	public static class TileEntityFreeEscalator extends BlockEntityClientSerializableMapper {

		public static final int MODE_STOP = 0;
		public static final int MODE_UP = 1;
		public static final int MODE_DOWN = 2;

		private static final String KEY_ID = "free_escalator_id";
		private static final String KEY_HEAD = "free_escalator_head";
		private static final String KEY_HEIGHTS = "free_escalator_heights";
		private static final String KEY_POINTS = "free_escalator_points";
		private static final String KEY_WIDTH = "free_escalator_width";
		private static final String KEY_SPEED = "free_escalator_speed";
		private static final String KEY_MODE = "free_escalator_mode";
		private static final String KEY_CELLS = "free_escalator_cells";

		/** Which escalator, the same on all its blocks; 0 for none known */
		private long id;
		private long head;
		private byte[] heights = new byte[16];
		// Kept by the first block only
		private final List<double[]> points = new ArrayList<>();
		private double width = 1;
		private double speed = 0.5;
		private int mode = MODE_UP;
		private long[] cells = new long[0];

		private VoxelShape shape;
		private FreeEscalatorPath path;

		public TileEntityFreeEscalator(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.FREE_ESCALATOR.get(), pos, state);
			head = pos.asLong();
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			// Anything missing or out of range read as the default (the block then has no collision, or the escalator
			// isn't drawn or run), never an error
			FreeEscalators.onUnloaded(level, this);
			id = compoundTag.getLong(KEY_ID);
			head = compoundTag.contains(KEY_HEAD) ? compoundTag.getLong(KEY_HEAD) : worldPosition.asLong();
			final byte[] read = compoundTag.getByteArray(KEY_HEIGHTS);
			heights = new byte[16];
			if (read.length == 16) {
				for (int i = 0; i < 16; i++) {
					heights[i] = (byte) Math.max(0, Math.min(16, read[i]));
				}
			}
			points.clear();
			final long[] pointBits = compoundTag.getLongArray(KEY_POINTS);
			for (int i = 0; i + 2 < pointBits.length; i += 3) {
				final double[] point = {Double.longBitsToDouble(pointBits[i]), Double.longBitsToDouble(pointBits[i + 1]), Double.longBitsToDouble(pointBits[i + 2])};
				if (FreeEscalators.isFinite(point)) {
					points.add(point);
				}
			}
			final double readWidth = compoundTag.getDouble(KEY_WIDTH);
			width = readWidth == 1 || readWidth == 2 ? readWidth : 1;
			final double readSpeed = compoundTag.getDouble(KEY_SPEED);
			speed = Double.isFinite(readSpeed) && readSpeed >= 0.1 && readSpeed <= 2 ? readSpeed : 0.5;
			final int readMode = compoundTag.contains(KEY_MODE) ? compoundTag.getInt(KEY_MODE) : MODE_UP;
			mode = readMode >= MODE_STOP && readMode <= MODE_DOWN ? readMode : MODE_STOP;
			cells = compoundTag.getLongArray(KEY_CELLS);
			shape = null;
			// Data given to a block already in the world (a command, say): known and checked as if loaded
			FreeEscalators.onLoaded(level, this);
			path = null;
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putLong(KEY_ID, id);
			compoundTag.putLong(KEY_HEAD, head);
			compoundTag.putByteArray(KEY_HEIGHTS, heights);
			if (isHead()) {
				final long[] pointBits = new long[points.size() * 3];
				for (int i = 0; i < points.size(); i++) {
					for (int j = 0; j < 3; j++) {
						pointBits[i * 3 + j] = Double.doubleToLongBits(points.get(i)[j]);
					}
				}
				compoundTag.putLongArray(KEY_POINTS, pointBits);
				compoundTag.putDouble(KEY_WIDTH, width);
				compoundTag.putDouble(KEY_SPEED, speed);
				compoundTag.putInt(KEY_MODE, mode);
				compoundTag.putLongArray(KEY_CELLS, cells);
			}
		}

		@Override
		public void setLevel(Level world) {
			super.setLevel(world);
			// Loaded on the server: known by its escalator, and checked that its escalator is still there
			FreeEscalators.onLoaded(world, this);
		}

		@Override
		public void setRemoved() {
			super.setRemoved();
			FreeEscalators.onUnloaded(level, this);
		}

		public long getId() {
			return id;
		}

		public boolean isHead() {
			return head == worldPosition.asLong();
		}

		public BlockPos getHead() {
			return BlockPos.of(head);
		}

		public VoxelShape getShape() {
			if (shape == null) {
				VoxelShape built = Shapes.empty();
				for (int i = 0; i < 4; i++) {
					for (int j = 0; j < 4; j++) {
						final int height = heights[i * 4 + j];
						if (height > 0) {
							built = Shapes.or(built, Block.box(i * 4, 0, j * 4, i * 4 + 4, Math.min(16, height), j * 4 + 4));
						}
					}
				}
				shape = built.optimize();
			}
			return shape;
		}

		/** The run, for the first block: made again from its points when first asked */
		public FreeEscalatorPath getPath() {
			if (path == null && isHead() && points.size() >= 2) {
				path = FreeEscalatorPath.build(points, width).path;
			}
			return path;
		}

		public List<double[]> getPoints() {
			return points;
		}

		public double getWidth() {
			return width;
		}

		public double getSpeed() {
			return speed;
		}

		public int getMode() {
			return mode;
		}

		public long[] getCells() {
			return cells;
		}

		/** A block of an escalator: which is its first and what it holds of the surface */
		public void setCell(long newId, BlockPos headPos, byte[] newHeights) {
			FreeEscalators.onUnloaded(level, this);
			id = newId;
			FreeEscalators.onLoaded(level, this);
			head = headPos.asLong();
			heights = newHeights;
			shape = null;
			setChanged();
			syncData();
		}

		/** The first block: the whole escalator */
		public void setEscalator(List<double[]> newPoints, double newWidth, double newSpeed, int newMode, long[] newCells) {
			points.clear();
			points.addAll(newPoints);
			width = newWidth;
			speed = newSpeed;
			mode = newMode;
			cells = newCells;
			path = null;
			setChanged();
			syncData();
		}

		public void setMode(int newMode) {
			mode = newMode;
			setChanged();
			syncData();
		}

		public void setSpeed(double newSpeed) {
			speed = newSpeed;
			setChanged();
			syncData();
		}
	}
}
