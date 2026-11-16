package mtr.block;

import mtr.BlockEntityTypes;
import mtr.Blocks;
import mtr.data.IGui;
import mtr.data.Platform;
import mtr.data.PlatformCurves;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.BlockEntityClientSerializableMapper;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockMapper;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

/**
 * A screen door cut along a curved or diagonal platform's edge (0.90.0, first try at tier 3): a thin wall standing on the
 * edge line ({@link PlatformEdges#wallSegment}), the same line {@link BlockPlatformEdge} cuts the platform along, put a
 * block above the platform's edge. Each block is glass or one door leaf sliding along the line; the leaves open as an
 * ordinary screen door's do, when a train stopped beside them opens its doors on that side ({@link mtr.data.Train#isDoorScanned},
 * {@link mtr.data.TrainServer}). The cut is the block state, as for the edge block; the style, glass or leaf and how far
 * open are kept by the block entity. Drawn by {@link mtr.render.RenderEdgeScreenDoor}.
 */
public class BlockEdgeScreenDoor extends BlockMapper implements EntityBlockMapper {

	/**
	 * Glass
	 */
	public static final int PART_GLASS = 0;
	/**
	 * A leaf sliding against the line's direction ({@link PlatformEdges#alongX})
	 */
	public static final int PART_DOOR_BACK = 1;
	/**
	 * A leaf sliding in the line's direction
	 */
	public static final int PART_DOOR_AHEAD = 2;
	public static final int PARTS = 3;
	/**
	 * The screen door styles: those of MTR's screen doors 1 and 2
	 */
	public static final int STYLES = 2;
	/**
	 * How far open a leaf is when wholly open, as for {@link BlockPSDAPGDoorBase#MAX_OPEN_VALUE}
	 */
	public static final int MAX_OPEN_VALUE = BlockPSDAPGDoorBase.MAX_OPEN_VALUE;

	private static final VoxelShape[] SHAPES = new VoxelShape[PlatformEdges.ANGLES * PlatformEdges.CUTS];
	private static final VoxelShape[] COLLISION_SHAPES = new VoxelShape[PlatformEdges.ANGLES * PlatformEdges.CUTS];

	public BlockEdgeScreenDoor(Properties settings) {
		super(settings);
		registerDefaultState(defaultBlockState().setValue(BlockPlatformEdge.ANGLE, 0).setValue(BlockPlatformEdge.CUT, PlatformEdges.CUT_CENTRE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockPlatformEdge.ANGLE, BlockPlatformEdge.CUT);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return shapeOf(state, false);
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext collisionContext) {
		final BlockEntity entity = world.getBlockEntity(pos);
		return entity instanceof TileEntityEdgeScreenDoor && ((TileEntityEdgeScreenDoor) entity).isOpen() ? Shapes.empty() : shapeOf(state, true);
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.ENTITYBLOCK_ANIMATED;
	}

	@Override
	public boolean isPathfindable(BlockState state, BlockGetter world, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
		return false;
	}

	/**
	 * Shuts a leaf the train left open, as an ordinary screen door's block tick does
	 */
	@Override
	public void tick(BlockState state, ServerLevel world, BlockPos pos) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (entity instanceof TileEntityEdgeScreenDoor) {
			((TileEntityEdgeScreenDoor) entity).setOpen(0);
		}
	}

	@Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new TileEntityEdgeScreenDoor(pos, state);
	}

	/**
	 * @return the wall's columns, a block high to look at and a block and a half to walk into, so it can't be jumped over
	 */
	private static VoxelShape shapeOf(BlockState state, boolean collision) {
		final int index = PlatformEdges.pack(IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE), IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT));
		final VoxelShape[] shapes = collision ? COLLISION_SHAPES : SHAPES;
		VoxelShape shape = shapes[index];
		if (shape == null) {
			final boolean[] mask = PlatformEdges.wallMask(PlatformEdges.angleOf(index), PlatformEdges.cutOfPacked(index));
			final int height = collision ? 24 : 16;
			shape = Shapes.empty();
			for (int x = 0; x < PlatformEdges.CELLS; x++) {
				int start = -1;
				for (int z = 0; z <= PlatformEdges.CELLS; z++) {
					final boolean solid = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z];
					if (solid && start < 0) {
						start = z;
					} else if (!solid && start >= 0) {
						shape = Shapes.or(shape, Block.box(x, 0, start, x + 1, height, z));
						start = -1;
					}
				}
			}
			shape = shape.optimize();
			if (shape.isEmpty()) {
				shape = Block.box(7, 0, 7, 9, height, 9);
			}
			shapes[index] = shape;
		}
		return shape;
	}

	/**
	 * @return the state putting a door's wall on the nearest platform's edge line where it crosses the block; null when no
	 * platform's rail is near or the line doesn't cross the block
	 */
	public static BlockState stateAt(Level world, BlockPos pos, Platform platform) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			return null;
		}
		final Platform found = platform == null ? BlockPlatformEdge.nearestPlatform(railwayData, pos) : platform;
		final Rail rail = found == null ? null : PlatformCurves.railOf(found, railwayData.getRails());
		if (rail == null) {
			return null;
		}
		final int[] cars = PlatformCurves.carsAt(found.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, nearest.signedSagitta);
		final int packed = PlatformEdges.cutOf(pos.getX(), pos.getZ(), nearest.point.x + nearest.sideX * offset, nearest.point.z + nearest.sideZ * offset, nearest.sideX, nearest.sideZ);
		if (packed < 0 || PlatformEdges.wallSegment(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed)) == null) {
			return null;
		}
		return Blocks.EDGE_SCREEN_DOOR.get().defaultBlockState().setValue(BlockPlatformEdge.ANGLE, PlatformEdges.angleOf(packed)).setValue(BlockPlatformEdge.CUT, PlatformEdges.cutOfPacked(packed));
	}

	/**
	 * @param offsetVec the horizontal direction from a car out to the side it scans
	 * @return whether the wall faces that car: its side towards the track looks back at the car
	 */
	public static boolean facesCar(BlockState state, Vec3 offsetVec) {
		final double angle = IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE) * 2 * Math.PI / PlatformEdges.ANGLES;
		return Math.cos(angle) * offsetVec.x + Math.sin(angle) * offsetVec.z < -0.5;
	}

	public static class TileEntityEdgeScreenDoor extends BlockEntityClientSerializableMapper implements IGui {

		private static final String KEY_STYLE = "style";
		private static final String KEY_PART = "part";
		private static final String KEY_OPEN = "open";

		private int style;
		private int part;
		private int open;
		private float openClient;

		public TileEntityEdgeScreenDoor(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.EDGE_SCREEN_DOOR_TILE_ENTITY.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			style = Math.floorMod(compoundTag.getInt(KEY_STYLE), STYLES);
			part = Math.floorMod(compoundTag.getInt(KEY_PART), PARTS);
			open = compoundTag.getInt(KEY_OPEN);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putInt(KEY_STYLE, style);
			compoundTag.putInt(KEY_PART, part);
			compoundTag.putInt(KEY_OPEN, open);
		}

		public AABB getRenderBoundingBox() {
			return new AABB(worldPosition, worldPosition.offset(1, 2, 1));
		}

		public int getStyle() {
			return style;
		}

		public int getPart() {
			return part;
		}

		public boolean isDoor() {
			return part != PART_GLASS;
		}

		public void setLook(int style, int part) {
			this.style = Math.floorMod(style, STYLES);
			this.part = Math.floorMod(part, PARTS);
			if (this.part == PART_GLASS) {
				open = 0;
			}
			setChanged();
			syncData();
		}

		public void setOpen(int open) {
			final int newOpen = isDoor() ? open : 0;
			if (newOpen != this.open) {
				this.open = newOpen;
				setChanged();
				syncData();
			}
		}

		public boolean isOpen() {
			return open > 0;
		}

		/**
		 * @return how far open the leaf is drawn, from 0 to 1, moving smoothly towards the server's value
		 */
		public float getOpen(float lastFrameDuration) {
			final float change = lastFrameDuration * 0.95F;
			if (Math.abs(open - openClient) < change) {
				openClient = open;
			} else if (openClient < open) {
				openClient += change;
			} else {
				openClient -= change;
			}
			return Math.max(0, Math.min(1, openClient / MAX_OPEN_VALUE));
		}
	}
}
