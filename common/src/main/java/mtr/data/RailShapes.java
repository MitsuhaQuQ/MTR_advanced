package mtr.data;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A rough shape of a rail for the maps: a few points along its curve, enough that a long curved rail isn't drawn as one
 * straight line between its nodes, without the maps working out curves themselves. Points are in the maps' frame, where
 * a node is drawn at the middle of its block.
 */
public final class RailShapes {

	/**
	 * How far apart the curve is sampled before it is simplified, in blocks.
	 */
	private static final double SAMPLE_SPACING = 2;
	/**
	 * How far the drawn line may stray from the rail, in blocks.
	 */
	public static final double TOLERANCE = 0.5;
	public static final int MAX_POINTS = 64;
	private static final int MAX_SAMPLES = 4096;
	/**
	 * Points are sent as quarter blocks.
	 */
	private static final float PACK_SCALE = 4;

	private RailShapes() {
	}

	/**
	 * @return the points between the rail's two nodes as x, z, x, z, ... from its start, or null if the rail is straight
	 * within {@link #TOLERANCE}
	 */
	public static float[] of(Rail rail) {
		final double length = rail.getLength();
		if (!(length > SAMPLE_SPACING)) {
			return null;
		}
		final int count = (int) Math.min(MAX_SAMPLES, Math.ceil(length / SAMPLE_SPACING));
		final Vec3 first = rail.getPosition(0);
		// The maps draw a node at the middle of its block, which the rail's own positions needn't match exactly
		final BlockPosOffset offset = new BlockPosOffset(first);
		final double[] xs = new double[count + 1];
		final double[] zs = new double[count + 1];
		for (int i = 0; i <= count; i++) {
			final Vec3 position = rail.getPosition(length * i / count);
			xs[i] = position.x + offset.x;
			zs[i] = position.z + offset.z;
		}
		final boolean[] kept = new boolean[count + 1];
		kept[0] = kept[count] = true;
		simplify(xs, zs, 0, count, kept);
		final List<Integer> interior = new ArrayList<>();
		for (int i = 1; i < count; i++) {
			if (kept[i]) {
				interior.add(i);
			}
		}
		if (interior.isEmpty()) {
			return null;
		}
		// A very winding rail keeps points spread evenly along it
		final int points = Math.min(interior.size(), MAX_POINTS);
		final float[] shape = new float[points * 2];
		for (int i = 0; i < points; i++) {
			final int index = interior.get(points == interior.size() ? i : (int) ((long) i * interior.size() / points));
			shape[i * 2] = (float) xs[index];
			shape[i * 2 + 1] = (float) zs[index];
		}
		return shape;
	}

	/**
	 * Douglas–Peucker: keeps the point furthest from the line between two kept points while it is further than the
	 * tolerance, and does the same on each side of it.
	 */
	private static void simplify(double[] xs, double[] zs, int start, int end, boolean[] kept) {
		if (end - start < 2) {
			return;
		}
		double furthest = -1;
		int furthestIndex = -1;
		for (int i = start + 1; i < end; i++) {
			final double distance = distanceToLine(xs[i], zs[i], xs[start], zs[start], xs[end], zs[end]);
			if (distance > furthest) {
				furthest = distance;
				furthestIndex = i;
			}
		}
		if (furthest > TOLERANCE) {
			kept[furthestIndex] = true;
			simplify(xs, zs, start, furthestIndex, kept);
			simplify(xs, zs, furthestIndex, end, kept);
		}
	}

	static double distanceToLine(double x, double z, double x1, double z1, double x2, double z2) {
		final double dx = x2 - x1;
		final double dz = z2 - z1;
		final double lengthSquared = dx * dx + dz * dz;
		if (lengthSquared == 0) {
			return Math.hypot(x - x1, z - z1);
		}
		final double t = Math.max(0, Math.min(1, ((x - x1) * dx + (z - z1) * dz) / lengthSquared));
		return Math.hypot(x - x1 - t * dx, z - z1 - t * dz);
	}

	public static long pack(float x, float z) {
		return (long) Math.round(x * PACK_SCALE) << 32 | Math.round(z * PACK_SCALE) & 0xFFFFFFFFL;
	}

	public static float unpackX(long packed) {
		return (int) (packed >> 32) / PACK_SCALE;
	}

	public static float unpackZ(long packed) {
		return (int) packed / PACK_SCALE;
	}

	/**
	 * From the rail's first position to the middle of its starting node's block.
	 */
	private static final class BlockPosOffset {

		private final double x;
		private final double z;

		private BlockPosOffset(Vec3 first) {
			x = Math.floor(first.x) + 0.5 - first.x;
			z = Math.floor(first.z) + 0.5 - first.z;
		}
	}
}
