package transitsim.network;

import java.util.*;

/**
 * Straightens the rails along a route's path for a track diagram of the whole line (design document 6.12, block D): a
 * point becomes its distance along the path, where it is nearest to it, and how far it is to the side, left of the way
 * the route runs positive. Rails further to the side than the corridor are left out, and a rail is cut where it leaves
 * the corridor or where the nearest place on the path jumps, as where a route turning back runs over the same rails again.
 */
public final class Straightener {

	private final float[] path;
	private final double[] distances;
	private final double cellSize;
	private final Map<Long, List<Integer>> cells = new HashMap<>();
	public final double length;

	/**
	 * @param path      the points along the path in order, as x, z, x, z, ...
	 * @param halfWidth how far to the side rails are kept
	 */
	public Straightener(float[] path, double halfWidth) {
		this.path = path.clone();
		final int count = path.length / 2;
		distances = new double[Math.max(count, 1)];
		for (int i = 1; i < count; i++) {
			distances[i] = distances[i - 1] + Math.hypot(path[i * 2] - path[i * 2 - 2], path[i * 2 + 1] - path[i * 2 - 1]);
		}
		length = count == 0 ? 0 : distances[count - 1];
		cellSize = Math.max(halfWidth, 4);
		for (int i = 0; i + 1 < count; i++) {
			final long minX = cell(Math.min(path[i * 2], path[i * 2 + 2])), maxX = cell(Math.max(path[i * 2], path[i * 2 + 2]));
			final long minZ = cell(Math.min(path[i * 2 + 1], path[i * 2 + 3])), maxZ = cell(Math.max(path[i * 2 + 1], path[i * 2 + 3]));
			for (long x = minX; x <= maxX; x++) {
				for (long z = minZ; z <= maxZ; z++) {
					cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(i);
				}
			}
		}
	}

	/**
	 * @param hint the distance along the path of the point before on the same rail, or NaN, which picks the nearer of two
	 *             places on the path about as close, as where the path runs over the same rails twice
	 * @return {distance along the path, offset to the side, distance from the path}, or null if further than {@code maxDistance}
	 */
	public double[] project(double x, double z, double maxDistance, double hint) {
		final long cellX = cell(x), cellZ = cell(z);
		final int reach = (int) Math.ceil(maxDistance / cellSize);
		double[] best = null;
		final Set<Integer> seen = new HashSet<>();
		for (long cx = cellX - reach; cx <= cellX + reach; cx++) {
			for (long cz = cellZ - reach; cz <= cellZ + reach; cz++) {
				final List<Integer> segments = cells.get(key(cx, cz));
				if (segments == null) {
					continue;
				}
				for (final int segment : segments) {
					if (!seen.add(segment)) {
						continue;
					}
					final double[] candidate = projectOnto(segment, x, z);
					if (candidate[2] > maxDistance) {
						continue;
					}
					if (best == null || isBetter(candidate, best, hint)) {
						best = candidate;
					}
				}
			}
		}
		return best;
	}

	/**
	 * @param line      a rail as x, z, x, z, ... along it
	 * @param halfWidth how far to the side to keep it
	 * @return the parts of the rail in the corridor, each as distance, offset, distance, offset, ...
	 */
	public List<float[]> straighten(float[] line, double halfWidth) {
		final List<float[]> pieces = new ArrayList<>();
		final List<Float> piece = new ArrayList<>();
		double lastDistance = Double.NaN;
		double lastX = 0, lastZ = 0;
		for (int i = 0; i + 1 < line.length; i += 2) {
			final double[] projected = project(line[i], line[i + 1], halfWidth, lastDistance);
			final double step = Double.isNaN(lastDistance) ? 0 : Math.hypot(line[i] - lastX, line[i + 1] - lastZ);
			// Along a rail, the place on the path moves about as far as the rail does; much further is another part of the path
			if (projected == null || !Double.isNaN(lastDistance) && Math.abs(projected[0] - lastDistance) > step * 2 + halfWidth) {
				addPiece(pieces, piece);
				piece.clear();
			}
			if (projected == null) {
				lastDistance = Double.NaN;
			} else {
				piece.add((float) projected[0]);
				piece.add((float) projected[1]);
				lastDistance = projected[0];
				lastX = line[i];
				lastZ = line[i + 1];
			}
		}
		addPiece(pieces, piece);
		return pieces;
	}

	private static void addPiece(List<float[]> pieces, List<Float> piece) {
		if (piece.size() >= 4) {
			final float[] values = new float[piece.size()];
			for (int i = 0; i < values.length; i++) {
				values[i] = piece.get(i);
			}
			pieces.add(values);
		}
	}

	/**
	 * Nearer wins, but places on the path about as near count the same, and then the one nearest the hint wins.
	 */
	private static boolean isBetter(double[] candidate, double[] best, double hint) {
		if (Math.abs(candidate[2] - best[2]) > 0.5) {
			return candidate[2] < best[2];
		}
		if (!Double.isNaN(hint)) {
			return Math.abs(candidate[0] - hint) < Math.abs(best[0] - hint);
		}
		return candidate[0] < best[0];
	}

	private double[] projectOnto(int segment, double x, double z) {
		final double x1 = path[segment * 2], z1 = path[segment * 2 + 1];
		final double dx = path[segment * 2 + 2] - x1, dz = path[segment * 2 + 3] - z1;
		final double lengthSquared = dx * dx + dz * dz;
		final double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((x - x1) * dx + (z - z1) * dz) / lengthSquared));
		final double nearestX = x1 + dx * t, nearestZ = z1 + dz * t;
		final double segmentLength = Math.sqrt(lengthSquared);
		// With x east and z south, a point to the left of the way along the segment has a negative cross product
		final double cross = segmentLength == 0 ? 0 : (dx * (z - z1) - dz * (x - x1)) / segmentLength;
		return new double[]{distances[segment] + t * segmentLength, -cross, Math.hypot(x - nearestX, z - nearestZ)};
	}

	private long cell(double value) {
		return (long) Math.floor(value / cellSize);
	}

	private static long key(long x, long z) {
		return x << 32 ^ z & 0xFFFFFFFFL;
	}
}
