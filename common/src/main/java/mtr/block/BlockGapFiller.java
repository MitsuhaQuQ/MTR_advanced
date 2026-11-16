package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Blocks;
import mtr.data.Rail;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.shapes.Shapes;
import transitsim.geometry.PlatformEdges;
import transitsim.geometry.SlopedTops;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A block cut along a line beside a rail (0.93.0), filling the gaps the tunnel, bridge and wall creators leave beside
 * diagonal and curved track, where they could only take a block whole or leave it out: a bridge's deck is cut where its
 * edge should be, the ground a tunnel cut into is put back up to the tunnel's side, a tunnel wall's inner face runs along
 * the track. The cut is the same block state as a platform edge block's ({@link BlockPlatformEdge#ANGLE}, {@link
 * BlockPlatformEdge#CUT}, from {@link PlatformEdges#fillerCutOf}) and the block entity keeps the block it was cut from,
 * whose look it has ({@link mtr.render.RenderPlatformEdge} draws it, without an edge marking) and which it drops. It is
 * no platform: trains don't open their doors to it.
 * <p>
 * Its top may slope (0.94.0, {@link SlopedTops}), following the height of the rail over it, so the ground, a bridge's
 * deck or a tunnel's floor meets track going up or down; the top is kept in the block entity. A filler with no cut
 * across ({@link #WHOLE_ACROSS}) is only sloped.
 */
public class BlockGapFiller extends BlockMapper implements EntityBlockMapper {

	/**
	 * How fine the nearest point of a rail is looked for, in blocks along it: first coarse, then fine around the best
	 */
	private static final double COARSE_STEP = 0.25;
	private static final double FINE_STEP = 0.02;
	/**
	 * The cut that keeps all of the block across: the farthest cut, which no column reaches
	 */
	public static final int WHOLE_ACROSS = PlatformEdges.pack(0, PlatformEdges.CUTS - 1);
	private static final Map<Long, VoxelShape> SLOPED_SHAPES = new ConcurrentHashMap<>();

	public BlockGapFiller(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(BlockPlatformEdge.ANGLE, 0).setValue(BlockPlatformEdge.CUT, PlatformEdges.CUT_CENTRE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockPlatformEdge.ANGLE, BlockPlatformEdge.CUT);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		final int angle = IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE);
		final int cut = IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT);
		final BlockEntity entity = world.getBlockEntity(pos);
		final int top = entity instanceof TileEntityGapFiller ? ((TileEntityGapFiller) entity).getTop() : SlopedTops.FLAT;
		return top == SlopedTops.FLAT ? BlockPlatformEdge.shapeOf(angle, cut) : slopedShapeOf(angle, cut, top);
	}

	/**
	 * The shape depends on the sloped top in the block entity, not only the state
	 */
	@Override
	public boolean hasDynamicShape() {
		return true;
	}

	/**
	 * @return the cut's columns, each as high as the sloped top over it, joined along each row where they are as high
	 */
	private static VoxelShape slopedShapeOf(int angle, int cut, int top) {
		final long key = (long) PlatformEdges.pack(angle, cut) << 20 | top;
		return SLOPED_SHAPES.computeIfAbsent(key, ignored -> {
			final boolean[] mask = PlatformEdges.mask(angle, cut);
			final int[] heights = SlopedTops.columnHeights(top);
			VoxelShape shape = Shapes.empty();
			for (int x = 0; x < PlatformEdges.CELLS; x++) {
				int start = -1;
				int startHeight = 0;
				for (int z = 0; z <= PlatformEdges.CELLS; z++) {
					final int height = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z] ? heights[x * PlatformEdges.CELLS + z] : 0;
					if (start >= 0 && height != startHeight) {
						shape = Shapes.or(shape, Block.box(x, 0, start, x + 1, startHeight, z));
						start = -1;
					}
					if (start < 0 && height > 0) {
						start = z;
						startHeight = height;
					}
				}
			}
			return shape.optimize();
		});
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.ENTITYBLOCK_ANIMATED;
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityGapFiller(pos, state);
	}

	/**
	 * Drops what the block it was cut from drops, so a creator's cut ground or deck gives back its own blocks
	 */
	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		final BlockEntity entity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		final BlockState source = entity instanceof TileEntityGapFiller ? ((TileEntityGapFiller) entity).getSource() : null;
		return source == null ? Collections.emptyList() : source.getDrops(builder);
	}

	/**
	 * @return the state cutting a block by a line beside a rail; {@link PlatformEdges#FULL} or {@link PlatformEdges#EMPTY}
	 * packed as {@link PlatformEdges#fillerCutOf} gives them when the whole block or none of it is kept
	 */
	public static BlockState stateOf(int packed) {
		if (packed == PlatformEdges.FULL) {
			packed = WHOLE_ACROSS;
		}
		return Blocks.GAP_FILLER.get().defaultBlockState().setValue(BlockPlatformEdge.ANGLE, PlatformEdges.angleOf(packed)).setValue(BlockPlatformEdge.CUT, PlatformEdges.cutOfPacked(packed));
	}

	/**
	 * Puts a filler cut from a block in a place, or cuts one already there again
	 *
	 * @return whether it was put down
	 */
	public static boolean put(Level world, BlockPos pos, int packed, BlockState source) {
		return put(world, pos, packed, SlopedTops.FLAT, source);
	}

	/**
	 * Puts a filler cut across and with a sloped top (0.94.0), or changes one already there
	 *
	 * @param packed the cut across, {@link PlatformEdges#FULL} for none
	 * @param top    the sloped top, {@link SlopedTops#FLAT} for none
	 * @return whether it was put down; not when nothing of the block is left
	 */
	public static boolean put(Level world, BlockPos pos, int packed, int top, BlockState source) {
		if (packed == PlatformEdges.EMPTY || top == SlopedTops.NONE || source == null || source.isAir()) {
			return false;
		}
		world.setBlockAndUpdate(pos, stateOf(packed));
		final BlockEntity entity = world.getBlockEntity(pos);
		if (entity instanceof TileEntityGapFiller) {
			((TileEntityGapFiller) entity).setSourceAndTop(source, top);
			return true;
		}
		return false;
	}

	/**
	 * @return the rail's height over each of a block column's corners, in the order of {@link SlopedTops}, the rail's
	 * nearest points looked for between two distances along it
	 */
	public static double[] cornerHeights(Rail rail, int blockX, int blockZ, double from, double to) {
		final double[] heights = new double[4];
		final int[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		for (int i = 0; i < 4; i++) {
			final double x = blockX + corners[i][0];
			final double z = blockZ + corners[i][1];
			heights[i] = rail.getPosition(nearestAlong(rail, x, z, from, to)).y;
		}
		return heights;
	}

	/**
	 * @return whether a filler can be cut from a block: a whole block with no block entity, not a fluid
	 */
	public static boolean canBeCutFrom(Level world, BlockPos pos, BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && world.getBlockEntity(pos) == null && state.isCollisionShapeFullBlock(world, pos);
	}

	/**
	 * @return the distance along the rail of its point nearest a place, looked for between two distances along it
	 */
	public static double nearestAlong(Rail rail, double x, double z, double from, double to) {
		final double length = rail.getLength();
		final double start = Math.max(0, from);
		final double end = Math.min(length, to);
		double best = Double.MAX_VALUE;
		double bestAlong = start;
		for (double along = start; along <= end + 1E-9; along += COARSE_STEP) {
			final double distance = horizontalDistance(rail.getPosition(Math.min(along, length)), x, z);
			if (distance < best) {
				best = distance;
				bestAlong = Math.min(along, length);
			}
		}
		final double fineStart = Math.max(start, bestAlong - COARSE_STEP);
		final double fineEnd = Math.min(end, bestAlong + COARSE_STEP);
		for (double along = fineStart; along <= fineEnd + 1E-9; along += FINE_STEP) {
			final double distance = horizontalDistance(rail.getPosition(Math.min(along, length)), x, z);
			if (distance < best) {
				best = distance;
				bestAlong = Math.min(along, length);
			}
		}
		return bestAlong;
	}

	/**
	 * @return the rail's point at a distance along it, and the horizontal direction square to it there towards a place, as
	 * {x, z, outX, outZ}; the direction is unit, and to the rail's left when the place is on the rail
	 */
	public static double[] pointAndOut(Rail rail, double along, double x, double z) {
		final double length = rail.getLength();
		final Vec3 point = rail.getPosition(along);
		final Vec3 ahead = rail.getPosition(Math.min(length, along + 0.25));
		final Vec3 behind = rail.getPosition(Math.max(0, along - 0.25));
		final double directionX = ahead.x - behind.x;
		final double directionZ = ahead.z - behind.z;
		final double directionLength = Math.hypot(directionX, directionZ);
		if (directionLength == 0) {
			final double toX = x - point.x;
			final double toZ = z - point.z;
			final double toLength = Math.hypot(toX, toZ);
			return toLength == 0 ? new double[]{point.x, point.z, 1, 0} : new double[]{point.x, point.z, toX / toLength, toZ / toLength};
		}
		double outX = directionZ / directionLength;
		double outZ = -directionX / directionLength;
		if (outX * (x - point.x) + outZ * (z - point.z) < 0) {
			outX = -outX;
			outZ = -outZ;
		}
		return new double[]{point.x, point.z, outX, outZ};
	}

	/**
	 * @return the cut of a block column by the line a distance beside a rail, the rail's nearest point looked for between
	 * two distances along it, as {@link PlatformEdges#fillerCutOf}
	 */
	public static int cutOf(Rail rail, int blockX, int blockZ, double from, double to, double distance, boolean keepInside) {
		final double centreX = blockX + 0.5;
		final double centreZ = blockZ + 0.5;
		final double[] pointAndOut = pointAndOut(rail, nearestAlong(rail, centreX, centreZ, from, to), centreX, centreZ);
		return PlatformEdges.fillerCutOf(blockX, blockZ, pointAndOut[0], pointAndOut[1], pointAndOut[2], pointAndOut[3], distance, keepInside);
	}

	private static double horizontalDistance(Vec3 point, double x, double z) {
		return Math.hypot(point.x - x, point.z - z);
	}

	/**
	 * Keeps the block it was cut from, as an edge block keeps its source (sent to the players with it)
	 */
	public static class TileEntityGapFiller extends BlockPlatformEdge.TileEntityPlatformEdge {

		private static final String KEY_TOP = "top";
		private int top = SlopedTops.FLAT;

		public TileEntityGapFiller(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.GAP_FILLER_TILE_ENTITY.get(), pos, state);
		}

		/**
		 * @return the sloped top (0.94.0), {@link SlopedTops#FLAT} for a whole block's
		 */
		public int getTop() {
			return top;
		}

		public void setSourceAndTop(BlockState source, int top) {
			this.top = top == SlopedTops.NONE ? SlopedTops.FLAT : top;
			setSource(source);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			super.readCompoundTag(compoundTag);
			top = compoundTag.contains(KEY_TOP) ? compoundTag.getInt(KEY_TOP) : SlopedTops.FLAT;
			if (top == SlopedTops.NONE) {
				top = SlopedTops.FLAT;
			}
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			super.writeCompoundTag(compoundTag);
			if (top != SlopedTops.FLAT) {
				compoundTag.putInt(KEY_TOP, top);
			}
		}
	}
}
