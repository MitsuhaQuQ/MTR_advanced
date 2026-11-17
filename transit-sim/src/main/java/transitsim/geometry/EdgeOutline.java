package transitsim.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * A block's part of a platform cut by the platform's edge curve itself (0.95.0), so that the edges of neighbouring blocks
 * join into one smooth curve instead of each block having its own straight line of a few angles and cuts
 * ({@link PlatformEdges#cutOf}). The curve is given as a chain of points close together along it, in block coordinates
 * (the block from 0 to 1), with the platform on its left or right as the game has sides: left of the direction (dx, dz)
 * is (dz, -dx).
 * <p>
 * Outlines go round with a positive area by the shoelace sum on (x, z), as the platform edge drawing has them.
 */
public final class EdgeOutline {

	public static final double[] FULL = {0, 0, 1, 0, 1, 1, 0, 1};
	public static final double[] EMPTY = {};

	private static final double EPSILON = 1E-9;
	private static final double ON_SIDE = 1E-6;

	private EdgeOutline() {
	}

	/**
	 * @param chain           x, z, x, z, ... along the edge curve, reaching past the block both ways where it crosses it
	 * @param platformOnLeft  whether the platform is on the left of the chain's direction
	 * @return the platform's part of the block; {@link #FULL} or {@link #EMPTY} when the curve doesn't cross it, null when it
	 * crosses it more than once or ends inside it
	 */
	public static double[] cut(double[] chain, boolean platformOnLeft) {
		final List<List<double[]>> runs = new ArrayList<>();
		List<double[]> run = null;
		for (int i = 0; i + 3 < chain.length; i += 2) {
			final double x1 = chain[i], z1 = chain[i + 1], x2 = chain[i + 2], z2 = chain[i + 3];
			final double[] clipped = clipSegment(x1, z1, x2, z2);
			if (clipped == null) {
				run = null;
				continue;
			}
			final double[] from = {x1 + (x2 - x1) * clipped[0], z1 + (z2 - z1) * clipped[0]};
			final double[] to = {x1 + (x2 - x1) * clipped[1], z1 + (z2 - z1) * clipped[1]};
			if (run == null || clipped[0] > EPSILON) {
				run = new ArrayList<>();
				runs.add(run);
				run.add(from);
			}
			run.add(to);
			if (clipped[1] < 1 - EPSILON) {
				run = null;
			}
		}
		// Runs only touching the block at a point don't count
		runs.removeIf(points -> length(points) < ON_SIDE);
		if (runs.isEmpty()) {
			return depth(chain, platformOnLeft, 0.5, 0.5) >= 0 ? FULL.clone() : EMPTY;
		}
		if (runs.size() > 1) {
			return null;
		}
		final List<double[]> points = runs.get(0);
		final double[] entry = points.get(0);
		final double[] exit = points.get(points.size() - 1);
		if (!onBoundary(entry) || !onBoundary(exit)) {
			return null;
		}

		// Round the block's side from the exit back to the entry, the way that leaves the platform inside
		final double[] increasing = close(points, perimeter(exit), perimeter(entry), true);
		final double area = area(increasing);
		// A positive area has its inside on the right of the chain as the game has sides
		final double[] polygon = (area < 0) == platformOnLeft ? increasing : close(points, perimeter(exit), perimeter(entry), false);
		return area(polygon) < 0 ? reverse(polygon) : polygon;
	}

	/**
	 * @return how far a point is from the chain, positive on the platform's side
	 */
	public static double depth(double[] chain, boolean platformOnLeft, double x, double z) {
		double best = Double.MAX_VALUE;
		double sign = 1;
		for (int i = 0; i + 3 < chain.length; i += 2) {
			final double x1 = chain[i], z1 = chain[i + 1], x2 = chain[i + 2], z2 = chain[i + 3];
			final double dx = x2 - x1, dz = z2 - z1;
			final double lengthSquared = dx * dx + dz * dz;
			final double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((x - x1) * dx + (z - z1) * dz) / lengthSquared));
			final double nearestX = x1 + dx * t, nearestZ = z1 + dz * t;
			final double distance = Math.hypot(x - nearestX, z - nearestZ);
			if (distance < best) {
				best = distance;
				// Left of (dx, dz) is (dz, -dx)
				final double left = (x - x1) * dz - (z - z1) * dx;
				sign = (left >= 0) == platformOnLeft ? 1 : -1;
			}
		}
		return best == Double.MAX_VALUE ? 0 : best * sign;
	}

	/**
	 * @return how far a point is past the chain's ends, along the chain there (0.100.0): positive beyond the first point
	 * or the last, square to the chain, negative or 0 alongside it. A platform's marking stops there instead of fanning
	 * round the end of the edge (user 2026-11-18: 端头有材质问题).
	 */
	public static double pastEnds(double[] chain, double x, double z) {
		final int points = chain.length / 2;
		if (points < 2) {
			return 0;
		}
		final double startDx = chain[2] - chain[0], startDz = chain[3] - chain[1];
		final double startLength = Math.hypot(startDx, startDz);
		final double endDx = chain[points * 2 - 2] - chain[points * 2 - 4], endDz = chain[points * 2 - 1] - chain[points * 2 - 3];
		final double endLength = Math.hypot(endDx, endDz);
		final double beforeStart = startLength == 0 ? 0 : ((chain[0] - x) * startDx + (chain[1] - z) * startDz) / startLength;
		final double afterEnd = endLength == 0 ? 0 : ((x - chain[points * 2 - 2]) * endDx + (z - chain[points * 2 - 1]) * endDz) / endLength;
		return Math.max(beforeStart, afterEnd);
	}

	private static double[] close(List<double[]> points, double from, double to, boolean increasing) {
		final List<double[]> polygon = new ArrayList<>(points);
		final double span = increasing ? floorMod(to - from, 4) : floorMod(from - to, 4);
		for (int i = 0; i < 4; i++) {
			// The corners passed going round, in order
			final double corner = increasing ? Math.ceil(from + EPSILON) + i : Math.floor(from - EPSILON) - i;
			final double passed = increasing ? corner - from : from - corner;
			if (passed > EPSILON && passed < span - EPSILON) {
				polygon.add(cornerAt(floorMod(corner, 4)));
			}
		}
		final double[] result = new double[polygon.size() * 2];
		for (int i = 0; i < polygon.size(); i++) {
			result[i * 2] = polygon.get(i)[0];
			result[i * 2 + 1] = polygon.get(i)[1];
		}
		return result;
	}

	private static double floorMod(double value, double modulus) {
		return value - Math.floor(value / modulus) * modulus;
	}

	private static double[] cornerAt(double index) {
		switch ((int) Math.round(index)) {
			case 0:
				return new double[]{0, 0};
			case 1:
				return new double[]{1, 0};
			case 2:
				return new double[]{1, 1};
			default:
				return new double[]{0, 1};
		}
	}

	/**
	 * @return where a point on the block's side is going round it: 0 to 1 along z = 0, then x = 1, z = 1 back and x = 0 back
	 */
	private static double perimeter(double[] point) {
		final double x = point[0], z = point[1];
		if (z <= ON_SIDE) {
			return x;
		}
		if (x >= 1 - ON_SIDE) {
			return 1 + z;
		}
		if (z >= 1 - ON_SIDE) {
			return 2 + (1 - x);
		}
		return 3 + (1 - z);
	}

	private static boolean onBoundary(double[] point) {
		return point[0] <= ON_SIDE || point[0] >= 1 - ON_SIDE || point[1] <= ON_SIDE || point[1] >= 1 - ON_SIDE;
	}

	/**
	 * @return the part of a segment inside the block as {from, to} between 0 and 1, or null if none is
	 */
	private static double[] clipSegment(double x1, double z1, double x2, double z2) {
		double from = 0;
		double to = 1;
		final double[] p = {-(x2 - x1), x2 - x1, -(z2 - z1), z2 - z1};
		final double[] q = {x1, 1 - x1, z1, 1 - z1};
		for (int i = 0; i < 4; i++) {
			if (Math.abs(p[i]) < EPSILON) {
				if (q[i] < 0) {
					return null;
				}
			} else {
				final double t = q[i] / p[i];
				if (p[i] < 0) {
					from = Math.max(from, t);
				} else {
					to = Math.min(to, t);
				}
			}
		}
		return from <= to ? new double[]{from, to} : null;
	}

	private static double length(List<double[]> points) {
		double total = 0;
		for (int i = 1; i < points.size(); i++) {
			total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
		}
		return total;
	}

	public static double area(double[] polygon) {
		final int corners = polygon.length / 2;
		double sum = 0;
		for (int i = 0; i < corners; i++) {
			final int next = (i + 1) % corners;
			sum += polygon[i * 2] * polygon[next * 2 + 1] - polygon[next * 2] * polygon[i * 2 + 1];
		}
		return sum / 2;
	}

	private static double[] reverse(double[] polygon) {
		final int corners = polygon.length / 2;
		final double[] result = new double[polygon.length];
		for (int i = 0; i < corners; i++) {
			result[i * 2] = polygon[(corners - 1 - i) * 2];
			result[i * 2 + 1] = polygon[(corners - 1 - i) * 2 + 1];
		}
		return result;
	}
}
