package mtr.render;

import mtr.block.BlockPlatformEdge;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.PlatformCurves;
import mtr.data.Rail;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.EdgeOutline;
import transitsim.geometry.PlatformEdges;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Platform edge blocks drawn along the platform's edge curve itself (0.95.0), so the edges and the edge marking of a run
 * of them join into one smooth curve: the block state keeps the cut its collision uses, a straight line of one of 64
 * angles and sixteenths across, and each block on its own line made the curve jagged. The curve is worked out on the
 * client the way the server works out the cuts ({@link BlockPlatformEdge#stateAt}): the platform's rail, moved out to the
 * edge by the widest car and the sagitta of the longest one, here every {@link #STEP} along the rail.
 * <p>
 * Shapes are kept by block entity and made again when the platform's rail changes (the client gets a new rail object);
 * blocks with no platform near are looked at again now and then.
 */
public final class PlatformEdgeCurves {

	/**
	 * How far apart the points of the edge curve are, along the rail
	 */
	private static final double STEP = 0.25;
	/**
	 * How far round a block its piece of the curve is taken
	 */
	private static final double NEAR = 3;
	private static final long RETRY_MILLIS = 5000;
	private static final Map<BlockPlatformEdge.TileEntityPlatformEdge, Shape> SHAPES = new WeakHashMap<>();
	private static final Map<Rail, Map<Integer, Line>> LINES = new WeakHashMap<>();

	private PlatformEdgeCurves() {
	}

	/**
	 * @param recess how much further from the track the platform is below a recess, in blocks (0 for none)
	 * @return the block's shape along the curve, or null when it can't be had (no platform near, the client doesn't know
	 * the rail, or the curve doesn't simply cross the block), and the straight cut is drawn
	 */
	public static Shape shapeOf(BlockPlatformEdge.TileEntityPlatformEdge entity, BlockPos pos, double recess) {
		final Shape cached = SHAPES.get(entity);
		if (cached != null && cached.isCurrent(recess)) {
			return cached.outline == null ? null : cached;
		}
		final Shape shape = make(pos, recess);
		SHAPES.put(entity, shape);
		return shape.outline == null ? null : shape;
	}

	private static Shape make(BlockPos pos, double recess) {
		final Platform platform = BlockPlatformEdge.nearestPlatform(ClientData.PLATFORMS, ClientData.RAILS, pos);
		final Rail rail = platform == null ? null : PlatformCurves.railOf(platform, ClientData.RAILS);
		if (rail == null) {
			return new Shape(null, null, recess, null, null, null, false);
		}
		final int[] cars = PlatformCurves.carsAt(platform.id, ClientData.ROUTES, ClientData.SIDINGS, ClientData.DATA_CACHE.sidingIdToDepot);
		final double centreX = pos.getX() + 0.5;
		final double centreZ = pos.getZ() + 0.5;
		final PlatformCurves.Nearest nearest = PlatformCurves.nearest(rail, centreX, centreZ, cars[0]);
		final Line line = LINES.computeIfAbsent(rail, key -> new HashMap<>()).computeIfAbsent(cars[0] * 1000 + cars[1], key -> new Line(rail, cars));

		// The sample nearest the block, and which side of the rail the block is on
		int nearestIndex = 0;
		double nearestDistance = Double.MAX_VALUE;
		for (int i = 0; i < line.count; i++) {
			final double distance = Math.hypot(line.railX[i] - centreX, line.railZ[i] - centreZ);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearestIndex = i;
			}
		}
		final boolean left = line.leftX[nearestIndex] * nearest.sideX + line.leftZ[nearestIndex] * nearest.sideZ >= 0;
		final int side = left ? 0 : 1;

		// The curve's piece round the block, in block coordinates
		int first = nearestIndex;
		int last = nearestIndex;
		while (first > 0 && distanceFrom(line, first - 1, side, recess, centreX, centreZ) <= NEAR) {
			first--;
		}
		while (last < line.count - 1 && distanceFrom(line, last + 1, side, recess, centreX, centreZ) <= NEAR) {
			last++;
		}
		final double[] chain = new double[(last - first + 1) * 2];
		final double[] lowerChain = recess == 0 ? chain : new double[chain.length];
		for (int i = first; i <= last; i++) {
			final double[] point = line.edge(i, side, 0);
			chain[(i - first) * 2] = point[0] - pos.getX();
			chain[(i - first) * 2 + 1] = point[1] - pos.getZ();
			if (recess != 0) {
				final double[] lowerPoint = line.edge(i, side, recess);
				lowerChain[(i - first) * 2] = lowerPoint[0] - pos.getX();
				lowerChain[(i - first) * 2 + 1] = lowerPoint[1] - pos.getZ();
			}
		}
		final double[] outline = EdgeOutline.cut(chain, left);
		final double[] lowerOutline = recess == 0 ? outline : EdgeOutline.cut(lowerChain, left);
		// An empty piece would leave the block's collision with nothing drawn: the straight cut is drawn then
		final boolean usable = outline != null && lowerOutline != null && outline.length >= 6;
		return new Shape(platform, rail, recess, usable ? outline : null, usable ? lowerOutline : null, chain, left);
	}

	private static double distanceFrom(Line line, int index, int side, double recess, double x, double z) {
		final double[] point = line.edge(index, side, recess);
		return Math.hypot(point[0] - x, point[1] - z);
	}

	/**
	 * A block's part of the platform along the curve, with the curve's piece round it for the edge marking
	 */
	public static final class Shape {

		public final double[] outline;
		public final double[] lowerOutline;
		private final Platform platform;
		private final Rail rail;
		private final double recess;
		private final double[] chain;
		private final boolean left;
		private final long made = System.currentTimeMillis();
		/**
		 * Along the curve's piece and towards the track, the same over the block
		 */
		public final double alongX, alongZ, towardsTrackX, towardsTrackZ;

		private Shape(Platform platform, Rail rail, double recess, double[] outline, double[] lowerOutline, double[] chain, boolean left) {
			this.platform = platform;
			this.rail = rail;
			this.recess = recess;
			this.outline = outline;
			this.lowerOutline = lowerOutline;
			this.chain = chain;
			this.left = left;
			if (chain != null && chain.length >= 4) {
				final double dx = chain[chain.length - 2] - chain[0];
				final double dz = chain[chain.length - 1] - chain[1];
				final double length = Math.hypot(dx, dz);
				alongX = length == 0 ? 1 : dx / length;
				alongZ = length == 0 ? 0 : dz / length;
			} else {
				alongX = 1;
				alongZ = 0;
			}
			// Left of (dx, dz) is (dz, -dx); the track is on the other side from the platform
			final double sign = left ? -1 : 1;
			towardsTrackX = sign * alongZ;
			towardsTrackZ = sign * -alongX;
		}

		/**
		 * @return how far a point of the block is from the edge, positive into the platform
		 */
		public double depth(double x, double z) {
			return EdgeOutline.depth(chain, left, x, z);
		}

		private boolean isCurrent(double recess) {
			if (this.recess != recess) {
				return false;
			}
			if (platform == null) {
				return System.currentTimeMillis() - made < RETRY_MILLIS;
			}
			return ClientData.PLATFORMS.contains(platform) && PlatformCurves.railOf(platform, ClientData.RAILS) == rail;
		}
	}

	/**
	 * A platform rail's points every {@link #STEP}, with the way to its left and how far out the edge is on each side
	 */
	private static final class Line {

		private final int count;
		private final double[] railX, railZ, leftX, leftZ;
		private final double[][] offsets;

		private Line(Rail rail, int[] cars) {
			final double length = rail.getLength();
			count = Math.max(2, (int) Math.ceil(length / STEP) + 1);
			railX = new double[count];
			railZ = new double[count];
			leftX = new double[count];
			leftZ = new double[count];
			offsets = new double[2][count];
			final double half = Math.min(cars[0], length) / 2;
			for (int i = 0; i < count; i++) {
				final double along = Math.min(length, i * length / (count - 1));
				final Vec3 point = rail.getPosition(along);
				final Vec3 ahead = rail.getPosition(Math.min(length, along + 0.25));
				final Vec3 behind = rail.getPosition(Math.max(0, along - 0.25));
				final double directionX = ahead.x - behind.x;
				final double directionZ = ahead.z - behind.z;
				final double directionLength = Math.hypot(directionX, directionZ);
				railX[i] = point.x;
				railZ[i] = point.z;
				leftX[i] = directionLength == 0 ? 0 : directionZ / directionLength;
				leftZ[i] = directionLength == 0 ? 0 : -directionX / directionLength;
				// As the server cuts the blocks: the sagitta of a car centred here, kept off the ends
				final double centred = Math.max(half, Math.min(length - half, along));
				final Vec3 centre = rail.getPosition(centred);
				final Vec3 previous = rail.getPosition(centred - half);
				final Vec3 next = rail.getPosition(centred + half);
				for (int side = 0; side < 2; side++) {
					final double sign = side == 0 ? 1 : -1;
					final double sagitta = PlatformEdges.signedSagitta(previous.x, previous.z, centre.x, centre.z, next.x, next.z, sign * leftX[i], sign * leftZ[i]);
					offsets[side][i] = PlatformEdges.edgeOffset(cars[1] / 2D, sagitta);
				}
			}
		}

		/**
		 * @param side 0 for the left of the rail, 1 for the right
		 */
		private double[] edge(int index, int side, double extra) {
			final double sign = side == 0 ? 1 : -1;
			final double offset = offsets[side][index] + extra;
			return new double[]{railX[index] + sign * leftX[index] * offset, railZ[index] + sign * leftZ[index] * offset};
		}
	}
}
