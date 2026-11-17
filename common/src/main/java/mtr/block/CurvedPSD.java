package mtr.block;

import mtr.data.Platform;
import mtr.data.PlatformCurves;
import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Curved screen doors and platform gates (0.91.0, tier 3 of diagonal and curved platforms): MTR's straight screen door
 * and gate blocks, each with a curved kind ({@link BlockCurvedPSD}) that keeps all their rules (two blocks high, leaves in
 * pairs, glass joined into a run with a frame at its ends, locking with the brush, the top's route map and arrows) and
 * stands on a platform's edge line instead of a block side. Each block keeps its piece of the edge line (the angle and cut
 * of {@link BlockPlatformEdge}) in its block entity, as a {@link PlatformEdges.Bend}; what the straight block would draw
 * is bent onto it ({@code mtr.render.BendingVertexConsumer}), and where the straight block looks at the block beside it
 * on a side, the curved one looks at the next block along the line ({@link #neighbour}).
 */
public final class CurvedPSD {

	private static final String KEY_ANGLE = "bend_angle";
	private static final String KEY_CUT = "bend_cut";
	private static final String KEY_CURVE_FROM = "curve_from";
	private static final String KEY_CURVE_TO = "curve_to";
	private static final String KEY_CURVE_OFFSET = "curve_offset";
	private static final String KEY_CURVE_OFFSET_END = "curve_offset_end";
	private static final String KEY_CURVE_SIDE = "curve_side";
	private static final String KEY_CURVE_START = "curve_start";
	private static final String KEY_CURVE_END = "curve_end";
	/**
	 * How finely the edge curve is walked to find where it crosses a block, in blocks along the rail
	 */
	private static final double CURVE_STEP = 0.05;
	private static final Map<Long, VoxelShape> SHAPES = new HashMap<>();
	/**
	 * How far along the run a brush sets the arrows and the persistent tops, as for the straight ones
	 */
	private static final int MOST_STEPS = 256;
	/**
	 * Piece ends further apart than this don't make neighbours, in blocks
	 */
	private static final double MOST_JOIN_GAP = 0.3;

	private CurvedPSD() {
	}

	/**
	 * A curved block: which straight block it is the curved kind of
	 */
	public interface CurvedBlock {
		Block getStraight();
	}

	/**
	 * A curved block's block entity, keeping its piece of the edge line
	 */
	public interface Curved {
		BendHolder getBendHolder();

		/**
		 * Sends the block entity to the players again after its bend changed
		 */
		void syncBend();
	}

	public static final class BendHolder {

		private int angle = -1;
		private int cut;
		private PlatformEdges.Bend bend;
		/**
		 * The block's piece of the edge curve itself (0.92.0): the platform's rail by its two ends, how far from its centre
		 * line the edge is and on which side (1 left of the rail's direction, -1 right), and where along the rail the piece
		 * starts and ends (its start the end on the left seen from the platform); null where only the chord is known
		 */
		private Curve curve;

		public Curve getCurve() {
			return curve;
		}

		public void setCurve(Curve curve) {
			this.curve = curve;
		}

		public PlatformEdges.Bend getBend() {
			return bend;
		}

		public int getAngle() {
			return angle;
		}

		public int getCut() {
			return cut;
		}

		public void set(int angle, int cut) {
			this.angle = angle;
			this.cut = cut;
			bend = angle < 0 ? null : PlatformEdges.bend(angle, cut);
		}

		public void read(CompoundTag compoundTag) {
			set(compoundTag.contains(KEY_ANGLE) ? compoundTag.getInt(KEY_ANGLE) : -1, compoundTag.getInt(KEY_CUT));
			curve = compoundTag.contains(KEY_CURVE_FROM) ? new Curve(BlockPos.of(compoundTag.getLong(KEY_CURVE_FROM)), BlockPos.of(compoundTag.getLong(KEY_CURVE_TO)), compoundTag.getDouble(KEY_CURVE_OFFSET), compoundTag.contains(KEY_CURVE_OFFSET_END) ? compoundTag.getDouble(KEY_CURVE_OFFSET_END) : compoundTag.getDouble(KEY_CURVE_OFFSET), compoundTag.getInt(KEY_CURVE_SIDE), compoundTag.getDouble(KEY_CURVE_START), compoundTag.getDouble(KEY_CURVE_END)) : null;
		}

		public void write(CompoundTag compoundTag) {
			if (angle >= 0) {
				compoundTag.putInt(KEY_ANGLE, angle);
				compoundTag.putInt(KEY_CUT, cut);
			}
			if (curve != null) {
				compoundTag.putLong(KEY_CURVE_FROM, curve.railFrom.asLong());
				compoundTag.putLong(KEY_CURVE_TO, curve.railTo.asLong());
				compoundTag.putDouble(KEY_CURVE_OFFSET, curve.offset);
				compoundTag.putDouble(KEY_CURVE_OFFSET_END, curve.offsetEnd);
				compoundTag.putInt(KEY_CURVE_SIDE, curve.side);
				compoundTag.putDouble(KEY_CURVE_START, curve.start);
				compoundTag.putDouble(KEY_CURVE_END, curve.end);
			}
		}
	}

	/**
	 * A block's piece of a platform's edge curve (0.92.0): drawn along the curve itself, the pieces of a run join smoothly
	 */
	public static final class Curve {

		public final BlockPos railFrom;
		public final BlockPos railTo;
		/**
		 * How far the edge is from the rail at the piece's start and at its end (0.96.0: on a transition curve it changes
		 * along the rail; pieces saved before have the one offset at both ends)
		 */
		public final double offset;
		public final double offsetEnd;
		public final int side;
		public final double start;
		public final double end;

		public Curve(BlockPos railFrom, BlockPos railTo, double offset, double offsetEnd, int side, double start, double end) {
			this.railFrom = railFrom;
			this.railTo = railTo;
			this.offset = offset;
			this.offsetEnd = offsetEnd;
			this.side = side;
			this.start = start;
			this.end = end;
		}

		/**
		 * @param along 0 at the piece's start, 1 at its end
		 * @return how far the edge is from the rail there, between the piece's two ends
		 */
		public double offsetAt(double along) {
			return offset + (offsetEnd - offset) * along;
		}

		/**
		 * @return the edge curve at a distance along the rail and a depth back from it, in world coordinates, as {x, z}
		 */
		public static double[] edgePoint(Rail rail, double distance, int side, double offset) {
			final double length = rail.getLength();
			final net.minecraft.world.phys.Vec3 point = rail.getPosition(distance);
			final net.minecraft.world.phys.Vec3 ahead = rail.getPosition(Math.min(length, distance + 0.05));
			final net.minecraft.world.phys.Vec3 behind = rail.getPosition(Math.max(0, distance - 0.05));
			final double directionX = ahead.x - behind.x;
			final double directionZ = ahead.z - behind.z;
			final double directionLength = Math.hypot(directionX, directionZ);
			if (directionLength == 0) {
				return new double[]{point.x, point.z};
			}
			// Left of the rail's direction (dx, dz) is (dz, -dx)
			final double sideX = directionZ / directionLength * side;
			final double sideZ = -directionX / directionLength * side;
			return new double[]{point.x + sideX * offset, point.z + sideZ * offset};
		}

	}

	/**
	 * @return the bend of a curved block, null for a straight one or one not yet bent
	 */
	public static PlatformEdges.Bend bendAt(BlockGetter world, BlockPos pos) {
		final BlockEntity entity = world.getBlockEntity(pos);
		return entity instanceof Curved ? ((Curved) entity).getBendHolder().getBend() : null;
	}

	/**
	 * @return the block beside this one on its left (seen from the platform, facing the track) or right: the next along the
	 * edge line for a curved block, the one beside on its facing side for a straight one, itself when it has no facing
	 */
	public static BlockPos neighbour(BlockGetter world, BlockPos pos, boolean left) {
		final PlatformEdges.Bend bend = bendAt(world, pos);
		if (bend != null) {
			// The curved block round this one whose piece has an end nearest this piece's end on that side, nearer to it than
			// to this piece's other end (0.91.3: the block just beyond the end was taken, which near a block corner could be
			// another than the one whose way back leads here, so runs broke apart)
			final double endX = left ? bend.startX : bend.endX;
			final double endZ = left ? bend.startZ : bend.endZ;
			final double otherX = left ? bend.endX : bend.startX;
			final double otherZ = left ? bend.endZ : bend.startZ;
			BlockPos best = null;
			double bestDistance = MOST_JOIN_GAP;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					final BlockPos candidate = pos.offset(dx, 0, dz);
					final PlatformEdges.Bend candidateBend = bendAt(world, candidate);
					if (candidateBend == null) {
						continue;
					}
					for (final double[] candidateEnd : new double[][]{{candidateBend.startX + dx, candidateBend.startZ + dz}, {candidateBend.endX + dx, candidateBend.endZ + dz}}) {
						final double distance = Math.hypot(candidateEnd[0] - endX, candidateEnd[1] - endZ);
						if (distance < bestDistance && distance < Math.hypot(candidateEnd[0] - otherX, candidateEnd[1] - otherZ)) {
							bestDistance = distance;
							best = candidate;
						}
					}
				}
			}
			if (best != null) {
				return best;
			}
			final double[] point = bend.beyond(left);
			return new BlockPos((int) Math.floor(pos.getX() + point[0]), pos.getY(), (int) Math.floor(pos.getZ() + point[1]));
		}
		final BlockState state = world.getBlockState(pos);
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return pos.relative(left ? facing.getCounterClockWise() : facing.getClockWise());
		}
		return pos;
	}

	/**
	 * @return the block the given number of blocks along the run on a side, as {@code pos.relative(side, steps)} is for
	 * straight blocks
	 */
	public static BlockPos walk(BlockGetter world, BlockPos pos, boolean left, int steps) {
		BlockPos result = pos;
		for (int i = 0; i < steps; i++) {
			final BlockPos next = neighbour(world, result, left);
			if (next.equals(result)) {
				break;
			}
			result = next;
		}
		return result;
	}

	/**
	 * @return how long the block's piece of a run is, in blocks: its piece of the edge line, a whole block for a straight one
	 */
	public static float pieceLength(BlockGetter world, BlockPos pos) {
		final PlatformEdges.Bend bend = bendAt(world, pos);
		return bend == null ? 1 : (float) Math.hypot(bend.endX - bend.startX, bend.endZ - bend.startZ);
	}

	/**
	 * @return how long the given number of blocks of the run beside this one on a side are together, in blocks
	 */
	public static float runLength(BlockGetter world, BlockPos pos, boolean left, int blocks) {
		float length = 0;
		BlockPos current = pos;
		for (int i = 0; i < blocks; i++) {
			final BlockPos next = neighbour(world, current, left);
			if (next.equals(current)) {
				break;
			}
			current = next;
			length += pieceLength(world, current);
		}
		return length;
	}

	/**
	 * @return the piece of a straight screen door or gate block along its side towards the track, to join curved pieces to
	 */
	public static PlatformEdges.Bend straightBend(Direction facing) {
		final int angle = Math.floorMod(Math.round((float) (Math.atan2(facing.getStepZ(), facing.getStepX()) / (2 * Math.PI) * PlatformEdges.ANGLES)), PlatformEdges.ANGLES);
		return PlatformEdges.bend(angle, PlatformEdges.CUT_CENTRE + PlatformEdges.CELLS / 2);
	}

	/**
	 * @return the straight block a block is the curved kind of, or itself
	 */
	public static Block straightOf(Block block) {
		return block instanceof CurvedBlock ? ((CurvedBlock) block).getStraight() : block;
	}

	/**
	 * @return whether two blocks belong to one run: the same block, or a straight block and its curved kind
	 */
	public static boolean sameKind(Block block1, Block block2) {
		return block1 == block2 || straightOf(block1) == straightOf(block2);
	}

	/**
	 * @return the straight block's state with the same properties, to draw its model or ask its rules
	 */
	public static BlockState straightState(BlockState curved) {
		final Block straight = straightOf(curved.getBlock());
		return straight == curved.getBlock() ? curved : withProperties(curved, straight);
	}

	/**
	 * @return the block's state with the properties of another state where it has them
	 */
	public static BlockState withProperties(BlockState from, Block block) {
		BlockState result = block.defaultBlockState();
		for (final Property<?> property : from.getProperties()) {
			result = copyProperty(from, result, property);
		}
		return result;
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
		return to.hasProperty(property) ? to.setValue(property, from.getValue(property)) : to;
	}

	/**
	 * @param fromCells how far behind the edge line the shape starts, in sixteenths
	 * @param toCells   how far behind it ends
	 * @param yFrom     its bottom, in sixteenths
	 * @param yTo       its top
	 * @return the shape of the straight block's part, bent onto the block's piece of the edge line; the given straight
	 * shape when the block isn't bent
	 */
	public static VoxelShape shape(BlockGetter world, BlockPos pos, int fromCells, int toCells, int yFrom, int yTo, VoxelShape straight) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (!(entity instanceof Curved) || ((Curved) entity).getBendHolder().getBend() == null) {
			return straight;
		}
		final BendHolder holder = ((Curved) entity).getBendHolder();
		final long key = (((((long) holder.getAngle() * PlatformEdges.CUTS + holder.getCut()) * 32 + fromCells) * 32 + toCells) * 64 + yFrom) * 64 + yTo;
		synchronized (SHAPES) {
			VoxelShape shape = SHAPES.get(key);
			if (shape == null) {
				final boolean[] mask = PlatformEdges.wallMask(holder.getAngle(), holder.getCut(), fromCells, toCells);
				shape = Shapes.empty();
				for (int x = 0; x < PlatformEdges.CELLS; x++) {
					int start = -1;
					for (int z = 0; z <= PlatformEdges.CELLS; z++) {
						final boolean solid = z < PlatformEdges.CELLS && mask[x * PlatformEdges.CELLS + z];
						if (solid && start < 0) {
							start = z;
						} else if (!solid && start >= 0) {
							shape = Shapes.or(shape, Block.box(x, yFrom, start, x + 1, yTo, z));
							start = -1;
						}
					}
				}
				shape = shape.optimize();
				SHAPES.put(key, shape);
			}
			return shape;
		}
	}

	/**
	 * @return the four-way direction nearest the line's direction towards the track, the facing of a curved block's state
	 */
	public static Direction facingOf(PlatformEdges.Bend bend) {
		final int[] facing = bend.nearestFacing();
		return Direction.fromDelta(facing[0], 0, facing[1]);
	}

	/**
	 * @return the angle and cut of the edge line where a screen door put at the place stands, as {@link PlatformEdges#pack}:
	 * that of the platform edge block below (so the door follows the edge laid there), else that of the nearest platform's
	 * edge, as the edge block works it out; -1 when no line crosses the block
	 */
	public static int lineAt(Level world, BlockPos pos) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final Platform platform = railwayData == null ? null : BlockPlatformEdge.nearestPlatform(railwayData, pos.below());
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, railwayData.getRails());
		// The chord through where the edge itself enters and leaves the column (0.96.0): the pieces of neighbouring blocks
		// then meet where the edge crosses from one to the other, also where its distance from the rail changes along a
		// transition curve, so the run is joined there (the edge block's own cut is square to the rail at its centre)
		if (rail != null) {
			final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
			final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
			final int side = sideOf(rail, nearest);
			final double[] crossing = crossing(rail, side, cars, pos);
			if (crossing != null) {
				final double[] first = edgePointAt(rail, crossing[0], side, cars);
				final double[] last = edgePointAt(rail, crossing[1], side, cars);
				final double chordX = last[0] - first[0];
				final double chordZ = last[1] - first[1];
				// Square to the chord, towards the platform
				double towardsPlatformX = chordZ;
				double towardsPlatformZ = -chordX;
				if (towardsPlatformX * nearest.sideX + towardsPlatformZ * nearest.sideZ < 0) {
					towardsPlatformX = -towardsPlatformX;
					towardsPlatformZ = -towardsPlatformZ;
				}
				final int packed = PlatformEdges.cutOf(pos.getX(), pos.getZ(), (first[0] + last[0]) / 2, (first[1] + last[1]) / 2, towardsPlatformX, towardsPlatformZ);
				if (packed >= 0 && PlatformEdges.bend(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed)) != null) {
					return packed;
				}
			}
		}
		final BlockState below = world.getBlockState(pos.below());
		if (below.getBlock() instanceof BlockPlatformEdge) {
			final int angle = IBlock.getStatePropertySafe(below, BlockPlatformEdge.ANGLE);
			final int cut = IBlock.getStatePropertySafe(below, BlockPlatformEdge.CUT);
			if (PlatformEdges.bend(angle, cut) != null) {
				return PlatformEdges.pack(angle, cut);
			}
		}
		if (rail == null) {
			return -1;
		}
		final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final double offset = PlatformEdges.edgeOffset(cars[1] / 2D, nearest.signedSagitta);
		final int packed = PlatformEdges.cutOf(pos.getX(), pos.getZ(), nearest.point.x + nearest.sideX * offset, nearest.point.z + nearest.sideZ * offset, nearest.sideX, nearest.sideZ);
		return packed >= 0 && PlatformEdges.bend(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed)) != null ? packed : -1;
	}

	/**
	 * @return the piece of the nearest platform's edge curve crossing the block's column (0.92.0), as {@link #lineAt}
	 * places the edge; null when no platform is near or the curve doesn't cross the column
	 */
	public static Curve curveAt(Level world, BlockPos pos, PlatformEdges.Bend bend) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final Platform platform = railwayData == null || bend == null ? null : BlockPlatformEdge.nearestPlatform(railwayData, pos.below());
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, railwayData.getRails());
		if (rail == null) {
			return null;
		}
		final java.util.List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
		final int[] cars = PlatformCurves.carsAt(platform.id, railwayData.routes, railwayData.sidings, railwayData.dataCache.sidingIdToDepot);
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, pos.getX() + 0.5, pos.getZ() + 0.5, cars[0]);
		final int side = sideOf(rail, nearest);
		final double[] crossing = crossing(rail, side, cars, pos);
		if (crossing == null) {
			return null;
		}
		final double first = crossing[0];
		final double last = crossing[1];
		// The piece's start is the end on the left seen from the platform, as for the chord
		final double[] firstPoint = edgePointAt(rail, first, side, cars);
		final double[] lastPoint = edgePointAt(rail, last, side, cars);
		final double alongX = bend.endX - bend.startX;
		final double alongZ = bend.endZ - bend.startZ;
		final boolean forwards = (lastPoint[0] - firstPoint[0]) * alongX + (lastPoint[1] - firstPoint[1]) * alongZ >= 0;
		final double start = forwards ? first : last;
		final double end = forwards ? last : first;
		return new Curve(ends.get(0), ends.get(1), PlatformCurves.edgeOffsetAt(rail, start, side, cars), PlatformCurves.edgeOffsetAt(rail, end, side, cars), side, start, end);
	}

	/**
	 * @return which side of the rail the platform is on: 1 left of its direction, -1 right
	 */
	private static int sideOf(Rail rail, PlatformCurves.Nearest nearest) {
		final double length = rail.getLength();
		final double[] probe = Curve.edgePoint(rail, length / 2, 1, 1);
		final net.minecraft.world.phys.Vec3 middle = rail.getPosition(length / 2);
		return (probe[0] - middle.x) * nearest.sideX + (probe[1] - middle.z) * nearest.sideZ >= 0 ? 1 : -1;
	}

	/**
	 * @return the platform's edge at a distance along the rail, as far from it as the edge is there (0.96.0: on a
	 * transition curve that changes along the rail, so the pieces of neighbouring blocks meet)
	 */
	private static double[] edgePointAt(Rail rail, double distance, int side, int[] cars) {
		return Curve.edgePoint(rail, distance, side, PlatformCurves.edgeOffsetAt(rail, distance, side, cars));
	}

	/**
	 * @return where along the rail the edge is in the block's column, {first, last}, its ends found more finely between
	 * the steps; null when it only touches it
	 */
	private static double[] crossing(Rail rail, int side, int[] cars, BlockPos pos) {
		final double length = rail.getLength();
		double first = Double.NaN;
		double last = Double.NaN;
		boolean wasInside = false;
		for (double distance = 0; distance <= length + CURVE_STEP / 2; distance += CURVE_STEP) {
			final double clamped = Math.min(distance, length);
			final boolean inside = inColumn(edgePointAt(rail, clamped, side, cars), pos);
			if (inside && !wasInside) {
				first = Double.isNaN(first) ? clamped == 0 ? 0 : boundary(rail, side, cars, pos, clamped - CURVE_STEP, clamped) : first;
			}
			if (!inside && wasInside) {
				last = boundary(rail, side, cars, pos, clamped, clamped - CURVE_STEP);
			}
			if (inside) {
				last = clamped;
			}
			wasInside = inside;
		}
		return Double.isNaN(first) || last - first < PlatformEdges.SHORTEST_WALL / 2 ? null : new double[]{first, last};
	}

	private static boolean inColumn(double[] point, BlockPos pos) {
		return Math.floor(point[0]) == pos.getX() && Math.floor(point[1]) == pos.getZ();
	}

	/**
	 * @return where between a distance outside the column and one inside the curve crosses its side
	 */
	private static double boundary(Rail rail, int side, int[] cars, BlockPos pos, double outside, double inside) {
		double out = outside;
		double in = inside;
		for (int i = 0; i < 12; i++) {
			final double middle = (out + in) / 2;
			if (inColumn(edgePointAt(rail, middle, side, cars), pos)) {
				in = middle;
			} else {
				out = middle;
			}
		}
		return (out + in) / 2;
	}

	/**
	 * Gives a curved block just put down its piece of the edge line
	 */
	public static void setBend(Level world, BlockPos pos, int packed) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (entity instanceof Curved) {
			((Curved) entity).getBendHolder().set(PlatformEdges.angleOf(packed), PlatformEdges.cutOfPacked(packed));
			((Curved) entity).getBendHolder().setCurve(curveAt(world, pos, ((Curved) entity).getBendHolder().getBend()));
			entity.setChanged();
			((Curved) entity).syncBend();
		}
	}

	/**
	 * Joins a column of screen door or gate glass (its lower block) into the run beside it, as the straight glass does when
	 * put down or brushed, but finding its neighbours along the edge line: the glass gets a frame end only where no glass of
	 * its kind is beside it, and the glass beside loses its end on this side
	 */
	public static void connectGlass(Level world, BlockPos lowerPos) {
		final BlockState state = world.getBlockState(lowerPos);
		if (!(state.getBlock() instanceof BlockPSDAPGGlassBase)) {
			return;
		}
		final BlockPos leftPos = neighbour(world, lowerPos, true);
		final BlockPos rightPos = neighbour(world, lowerPos, false);
		final boolean leftValid = isGlassOfKind(world, leftPos, state.getBlock());
		final boolean rightValid = isGlassOfKind(world, rightPos, state.getBlock());
		if (leftValid) {
			setSideTowards(world, leftPos, false);
		}
		if (rightValid) {
			setSideTowards(world, rightPos, true);
		}
		final IBlock.EnumSide side = leftValid && rightValid ? IBlock.EnumSide.MIDDLE : leftValid ? IBlock.EnumSide.RIGHT : rightValid ? IBlock.EnumSide.LEFT : IBlock.EnumSide.SINGLE;
		setSide(world, lowerPos, side);
	}

	/**
	 * Cuts the run of glass between a column and the glass on its right, or joins them when cut (0.92.0)
	 *
	 * @return whether it is cut now
	 */
	public static boolean toggleCut(Level world, BlockPos lowerPos) {
		final BlockState state = world.getBlockState(lowerPos);
		final BlockPos rightPos = neighbour(world, lowerPos, false);
		final boolean rightValid = isGlassOfKind(world, rightPos, state.getBlock());
		final IBlock.EnumSide side = IBlock.getStatePropertySafe(state, IBlock.SIDE_EXTENDED);
		final boolean joinedRight = side == IBlock.EnumSide.MIDDLE || side == IBlock.EnumSide.LEFT;
		if (joinedRight) {
			setSide(world, lowerPos, side == IBlock.EnumSide.MIDDLE ? IBlock.EnumSide.RIGHT : IBlock.EnumSide.SINGLE);
			if (rightValid) {
				final IBlock.EnumSide rightSide = IBlock.getStatePropertySafe(world.getBlockState(rightPos), IBlock.SIDE_EXTENDED);
				setSide(world, rightPos, rightSide == IBlock.EnumSide.MIDDLE ? IBlock.EnumSide.LEFT : rightSide == IBlock.EnumSide.RIGHT ? IBlock.EnumSide.SINGLE : rightSide);
			}
			return true;
		}
		if (rightValid) {
			setSide(world, lowerPos, side == IBlock.EnumSide.RIGHT ? IBlock.EnumSide.MIDDLE : IBlock.EnumSide.LEFT);
			setSideTowards(world, rightPos, true);
		}
		return false;
	}

	/**
	 * Takes away the frame end of a column of glass on the side towards a joining column: its right side when the joining
	 * one is on its right
	 */
	private static void setSideTowards(Level world, BlockPos lowerPos, boolean joinedOnLeft) {
		final IBlock.EnumSide side = IBlock.getStatePropertySafe(world.getBlockState(lowerPos), IBlock.SIDE_EXTENDED);
		final IBlock.EnumSide newSide;
		if (joinedOnLeft) {
			newSide = side == IBlock.EnumSide.LEFT ? IBlock.EnumSide.MIDDLE : side == IBlock.EnumSide.SINGLE ? IBlock.EnumSide.RIGHT : side;
		} else {
			newSide = side == IBlock.EnumSide.RIGHT ? IBlock.EnumSide.MIDDLE : side == IBlock.EnumSide.SINGLE ? IBlock.EnumSide.LEFT : side;
		}
		setSide(world, lowerPos, newSide);
	}

	private static void setSide(Level world, BlockPos lowerPos, IBlock.EnumSide side) {
		for (int y = 0; y <= 1; y++) {
			final BlockState state = world.getBlockState(lowerPos.above(y));
			if (state.hasProperty(IBlock.SIDE_EXTENDED) && state.getValue(IBlock.SIDE_EXTENDED) != side) {
				world.setBlockAndUpdate(lowerPos.above(y), state.setValue(IBlock.SIDE_EXTENDED, side));
			}
		}
	}

	private static boolean isGlassOfKind(BlockGetter world, BlockPos pos, Block block) {
		final BlockState state = world.getBlockState(pos);
		return sameKind(state.getBlock(), block) && IBlock.getStatePropertySafe(state, BlockPSDAPGBase.HALF) == DoubleBlockHalf.LOWER;
	}

	/**
	 * Calls back for each block of the same kind further along the run on a side, as {@link IBlock#propagate} does for the
	 * straight blocks, looking along the edge line
	 */
	public static void propagate(Level world, BlockPos pos, boolean left, Consumer<BlockPos> callback, int maxBlocksAway) {
		final Block block = world.getBlockState(pos).getBlock();
		BlockPos current = pos;
		for (int step = 0; step < MOST_STEPS; step++) {
			BlockPos found = null;
			BlockPos look = current;
			for (int i = 1; i <= maxBlocksAway; i++) {
				final BlockPos next = neighbour(world, look, left);
				if (next.equals(look)) {
					break;
				}
				look = next;
				if (sameKind(world.getBlockState(look).getBlock(), block)) {
					found = look;
					break;
				}
			}
			if (found == null) {
				return;
			}
			callback.accept(found);
			current = found;
		}
	}
}
