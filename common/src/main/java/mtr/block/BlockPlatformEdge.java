package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Blocks;
import mtr.data.PlatformCurves;
import mtr.data.RailwayData;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

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
		return shapeOf(IBlock.getStatePropertySafe(state, ANGLE), IBlock.getStatePropertySafe(state, CUT));
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
			final boolean[] mask = PlatformEdges.mask(angle, cut);
			shape = Shapes.empty();
			for (int x = 0; x < PlatformEdges.CELLS; x++) {
				int start = -1;
				for (int z = 0; z <= PlatformEdges.CELLS; z++) {
					final boolean solid = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z];
					if (solid && start < 0) {
						start = z;
					} else if (!solid && start >= 0) {
						shape = Shapes.or(shape, Block.box(x, 0, start, x + 1, 16, z));
						start = -1;
					}
				}
			}
			shape = shape.optimize();
			SHAPES[index] = shape;
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

	public static class TileEntityPlatformEdge extends BlockEntityMapper {

		public TileEntityPlatformEdge(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.PLATFORM_EDGE_TILE_ENTITY.get(), pos, state);
		}
	}
}
