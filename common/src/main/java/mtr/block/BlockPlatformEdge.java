package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Blocks;
import mtr.data.PlatformCurves;
import mtr.data.RailwayData;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A platform block cut along the track beside it (0.88.0, diagonal and curved platforms, tier 2): the platform's part of
 * the block is what lies beyond the edge line, worked out from the nearest platform's rail and the cars stopping there
 * when it is put down ({@link PlatformEdges#edgeOffset}). The cut is the block state, a direction of the line and its
 * distance from the block's centre in sixteenths, so the game keeps the shape of each state; the block is drawn by
 * {@link mtr.render.RenderPlatformEdge}.
 */
public class BlockPlatformEdge extends BlockMapper implements EntityBlockMapper {

	public static final IntegerProperty ANGLE = IntegerProperty.create("angle", 0, PlatformEdges.ANGLES - 1);
	public static final IntegerProperty CUT = IntegerProperty.create("cut", 0, PlatformEdges.CUTS - 1);
	/**
	 * How far from a platform's rail a block is looked at for its edge, in blocks
	 */
	public static final double REACH = 4.5;

	private static final VoxelShape[] SHAPES = new VoxelShape[PlatformEdges.ANGLES * PlatformEdges.CUTS];
	private static final Map<Long, VoxelShape> RECESSED_SHAPES = new ConcurrentHashMap<>();
	/**
	 * The recess of each source block, as {@link #recessOf} works it out; {@link #NO_RECESS} for none
	 */
	private static final Map<BlockState, int[]> RECESSES = new ConcurrentHashMap<>();
	private static final int[] NO_RECESS = {0, 0};

	public BlockPlatformEdge(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(ANGLE, 0).setValue(CUT, PlatformEdges.CUTS - 1));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ANGLE, CUT);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		final int angle = IBlock.getStatePropertySafe(state, ANGLE);
		final int cut = IBlock.getStatePropertySafe(state, CUT);
		final BlockEntity entity = world.getBlockEntity(pos);
		final int[] recess = recessOf(entity instanceof TileEntityPlatformEdge ? ((TileEntityPlatformEdge) entity).getSource() : null);
		return recess[0] == 0 ? shapeOf(angle, cut) : recessedShapeOf(angle, cut, recess);
	}

	/**
	 * A platform block whose side towards the track is set back under its top (MTR's indented platforms, 0.89.3) has the
	 * same recess along the cut: below its height the platform's part starts that much further from the track.
	 *
	 * @param source the block the edge block looks like, null for the default platform
	 * @return the recess's depth and height, in sixteenths; {0, 0} for none, also for a block with no facing
	 */
	public static int[] recessOf(BlockState source) {
		if (source == null || !source.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return NO_RECESS;
		}
		return RECESSES.computeIfAbsent(source, key -> {
			final Direction facing = source.getValue(HorizontalDirectionalBlock.FACING);
			final java.util.List<AABB> boxes;
			try {
				boxes = source.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
			} catch (Exception ignored) {
				return NO_RECESS;
			}
			// How far each box stays back from the side the block faces
			final double[] setBack = new double[boxes.size()];
			double height = 1;
			for (int i = 0; i < boxes.size(); i++) {
				final AABB box = boxes.get(i);
				final double reach = facing.getStepX() > 0 ? box.maxX : facing.getStepX() < 0 ? 1 - box.minX : facing.getStepZ() > 0 ? box.maxZ : 1 - box.minZ;
				setBack[i] = 1 - reach;
				if (setBack[i] < 1E-3) {
					height = Math.min(height, box.minY);
				}
			}
			if (height < 1E-3 || height > 1 - 1E-3) {
				return NO_RECESS;
			}
			double depth = 1;
			for (int i = 0; i < boxes.size(); i++) {
				if (boxes.get(i).minY < height - 1E-3) {
					depth = Math.min(depth, setBack[i]);
				}
			}
			final int depthSixteenths = (int) Math.round(depth * PlatformEdges.CELLS);
			return depthSixteenths <= 0 ? NO_RECESS : new int[]{depthSixteenths, (int) Math.round(height * PlatformEdges.CELLS)};
		});
	}

	/**
	 * @return the cut below a recess: further from the track by its depth
	 */
	public static int recessedCut(int cut, int[] recess) {
		return Math.max(0, cut - recess[0]);
	}

	private static VoxelShape recessedShapeOf(int angle, int cut, int[] recess) {
		final long key = ((long) PlatformEdges.pack(angle, cut) << 16) | (long) recess[0] << 8 | recess[1];
		return RECESSED_SHAPES.computeIfAbsent(key, ignored -> Shapes.or(
				columns(PlatformEdges.mask(angle, recessedCut(cut, recess)), 0, recess[1]),
				columns(PlatformEdges.mask(angle, cut), recess[1], PlatformEdges.CELLS)
		).optimize());
	}

	/**
	 * The shape depends on the block it looks like (its recess), not only the state, so the game must not keep one shape
	 * per state
	 */
	@Override
	public boolean hasDynamicShape() {
		return true;
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.ENTITYBLOCK_ANIMATED;
	}

	/**
	 * Never walked through, as for {@link BlockPlatform}
	 */
	@Override
	public boolean isPathfindable(BlockState state, BlockGetter world, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
		return false;
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityPlatformEdge(pos, state);
	}

	/**
	 * @return the shape of a cut: each row of the 16 by 16 columns that are platform, joined along the row
	 */
	public static VoxelShape shapeOf(int angle, int cut) {
		final int index = PlatformEdges.pack(angle, cut);
		VoxelShape shape = SHAPES[index];
		if (shape == null) {
			shape = columns(PlatformEdges.mask(angle, cut), 0, PlatformEdges.CELLS).optimize();
			SHAPES[index] = shape;
		}
		return shape;
	}

	/**
	 * @return the mask's columns between two heights in sixteenths, each row joined along it
	 */
	private static VoxelShape columns(boolean[] mask, int bottom, int top) {
		VoxelShape shape = Shapes.empty();
		if (top <= bottom) {
			return shape;
		}
		for (int x = 0; x < PlatformEdges.CELLS; x++) {
			int start = -1;
			for (int z = 0; z <= PlatformEdges.CELLS; z++) {
				final boolean solid = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z];
				if (solid && start < 0) {
					start = z;
				} else if (!solid && start >= 0) {
					shape = Shapes.or(shape, Block.box(x, bottom, start, x + 1, top, z));
					start = -1;
				}
			}
		}
		return shape;
	}

	/**
	 * @return the state cutting a block along the nearest platform's edge, as {@link PlatformEdges#cutOf} works it out; the
	 * whole block when no platform's rail is near, null when none of the block is platform (it stands in the cars' way)
	 */
	public static BlockState stateAt(Level world, BlockPos pos, Platform platform) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final BlockState whole = Blocks.PLATFORM_EDGE.get().defaultBlockState();
		if (railwayData == null) {
			return whole;
		}
		final Platform found = platform == null ? nearestPlatform(railwayData, pos) : platform;
		final Rail rail = found == null ? null : PlatformCurves.railOf(found, railwayData.getRails());
		if (rail == null) {
			return whole;
		}
		final int[] cars = PlatformCurves.carsAt(found.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, nearest.signedSagitta);
		final int packed = PlatformEdges.cutOf(pos.getX(), pos.getZ(), nearest.point.x + nearest.sideX * offset, nearest.point.z + nearest.sideZ * offset, nearest.sideX, nearest.sideZ);
		if (packed == PlatformEdges.EMPTY) {
			return null;
		}
		return packed == PlatformEdges.FULL ? whole : whole.setValue(ANGLE, PlatformEdges.angleOf(packed)).setValue(CUT, PlatformEdges.cutOfPacked(packed));
	}

	/**
	 * @return the platform whose rail passes nearest the block, within {@link #REACH} and a few blocks up or down; null if
	 * none does
	 */
	public static Platform nearestPlatform(RailwayData railwayData, BlockPos pos) {
		Platform best = null;
		double bestDistance = REACH;
		for (final Platform platform : railwayData.platforms) {
			final BlockPos mid = platform.getMidPos();
			if (Math.abs(mid.getY() - pos.getY()) > 3) {
				continue;
			}
			final Rail rail = PlatformCurves.railOf(platform, railwayData.getRails());
			if (rail == null || Math.hypot(mid.getX() - pos.getX(), mid.getZ() - pos.getZ()) > rail.getLength() / 2 + REACH + 2) {
				continue;
			}
			final double distance = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, 0).distance;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = platform;
			}
		}
		return best;
	}

	/**
	 * @return the block whose look an edge block put down here takes (0.89.0): the block clicked when it is a platform or an
	 * edge block, else a platform or edge block beside the place, else any whole block beside it; null for the default look
	 */
	public static BlockState sourceFor(Level world, BlockPos pos, BlockPos clicked) {
		final BlockState clickedSource = sourceOf(world, clicked, false);
		if (clickedSource != null) {
			return clickedSource;
		}
		for (final boolean anyWholeBlock : new boolean[]{false, true}) {
			for (final Direction direction : Direction.Plane.HORIZONTAL) {
				final BlockState source = sourceOf(world, pos.relative(direction), anyWholeBlock);
				if (source != null) {
					return source;
				}
			}
		}
		return null;
	}

	private static BlockState sourceOf(Level world, BlockPos pos, boolean anyWholeBlock) {
		final BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof BlockPlatformEdge) {
			final BlockEntity entity = world.getBlockEntity(pos);
			// An edge block with no source of its own looks like the default platform, so that is its look to copy, not
			// a reason to look further and take a plain block beside it (0.89.2)
			final BlockState source = entity instanceof TileEntityPlatformEdge ? ((TileEntityPlatformEdge) entity).getSource() : null;
			return source == null ? Blocks.PLATFORM.get().defaultBlockState() : source;
		}
		if (state.getBlock() instanceof BlockPlatform || anyWholeBlock && !state.isAir() && state.isCollisionShapeFullBlock(world, pos)) {
			return state;
		}
		return null;
	}

	/**
	 * Keeps the block whose look it takes (0.89.0), sent to the players with it
	 */
	public static class TileEntityPlatformEdge extends BlockEntityClientSerializableMapper {

		private static final String KEY_SOURCE = "source";
		private BlockState source;

		public TileEntityPlatformEdge(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.PLATFORM_EDGE_TILE_ENTITY.get(), pos, state);
		}

		/**
		 * @return the block whose top, sides and edge marking it is drawn with; null for the default platform
		 */
		public BlockState getSource() {
			return source;
		}

		public void setSource(BlockState source) {
			this.source = source;
			setChanged();
			syncData();
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			source = compoundTag.contains(KEY_SOURCE) ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), compoundTag.getCompound(KEY_SOURCE)) : null;
			if (source != null && source.isAir()) {
				source = null;
			}
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			if (source != null) {
				compoundTag.put(KEY_SOURCE, NbtUtils.writeBlockState(source));
			}
		}
	}
}
