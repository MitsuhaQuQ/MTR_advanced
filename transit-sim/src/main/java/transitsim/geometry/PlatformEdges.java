package transitsim.geometry;

/**
 * Where the edge of a platform beside a curved or diagonal track goes, and how a block is cut along it (0.88.0, design
 * of diagonal and curved platforms, tiers 1 and 2).
 * <p>
 * A car is a straight body between two points on the rail one car apart (its chord), so on a curve the middle of the car
 * stands in from the rail by the sagitta {@code length² / (8 radius)} and its ends stand on the rail. A platform on the
 * inside of the curve has its edge moved out by that much, so the middle of the car keeps the usual gap; one on the
 * outside keeps the usual offset, and the gap at the middle of the car grows by that much. Either way the gap is the
 * usual one plus the sagitta at most.
 * <p>
 * Distances are in blocks on the horizontal plane. Sides: a positive side is left of the direction the rail runs (x, z as
 * Minecraft has them: left of direction (dx, dz) is (dz, -dx)).
 */
public final class PlatformEdges {

	/**
	 * The gap between a car's side and an ordinary platform beside a straight track, as MTR's platforms are built: one
	 * block between the rail's block and the platform's, for a car two blocks wide.
	 */
	public static final double STANDARD_GAP = 0.5;
	/**
	 * More gap than the usual one by this much at a door is told to the player, and the boarding bridge reaches over it.
	 */
	public static final double WARN_EXTRA_GAP = 0.5;
	/**
	 * How far the boarding bridge reaches over a gap beyond the usual one, at most.
	 */
	public static final double MAX_BRIDGE = 1.5;
	/**
	 * Directions of a cut line: this many over the full circle.
	 */
	public static final int ANGLES = 64;
	/**
	 * Distances of a cut line from the block's centre: from -{@link #CUT_CENTRE} to +{@link #CUT_CENTRE} sixteenths.
	 */
	public static final int CUT_CENTRE = 12;
	public static final int CUTS = CUT_CENTRE * 2 + 1;
	public static final int CELLS = 16;

	private static final boolean[][] MASKS = new boolean[ANGLES * CUTS][];

	private PlatformEdges() {
	}

	/**
	 * @param prevX  the rail half a car before the point
	 * @param pointX the rail at the point
	 * @param nextX  the rail half a car after the point
	 * @param sideX  a horizontal direction towards the platform
	 * @return how far the middle of a car centred at the point stands in towards the platform from the rail: positive when
	 * the platform is on the inside of the curve, negative on the outside, 0 on a straight track
	 */
	public static double signedSagitta(double prevX, double prevZ, double pointX, double pointZ, double nextX, double nextZ, double sideX, double sideZ) {
		final double sideLength = Math.hypot(sideX, sideZ);
		if (sideLength == 0) {
			return 0;
		}
		// The middle of the chord, against the rail at the point
		final double middleX = (prevX + nextX) / 2 - pointX;
		final double middleZ = (prevZ + nextZ) / 2 - pointZ;
		return (middleX * sideX + middleZ * sideZ) / sideLength;
	}

	/**
	 * @return the sagitta of a car of this length on a curve of this radius, 0 for a straight track (radius 0 or less, or
	 * infinite)
	 */
	public static double sagitta(double carLength, double radius) {
		return radius <= 0 || Double.isInfinite(radius) ? 0 : carLength * carLength / (8 * radius);
	}

	/**
	 * @param halfWidth     half the widest car stopping there
	 * @param signedSagitta of the longest car, as {@link #signedSagitta} works it out
	 * @return how far the platform's edge goes from the rail's centre line
	 */
	public static double edgeOffset(double halfWidth, double signedSagitta) {
		return halfWidth + STANDARD_GAP + Math.max(0, signedSagitta);
	}

	/**
	 * @return the widest gap between a car's side and an edge put by {@link #edgeOffset}, at the middle of the car on the
	 * outside of a curve or at its ends on the inside
	 */
	public static double largestGap(double signedSagitta) {
		return STANDARD_GAP + Math.abs(signedSagitta);
	}

	/**
	 * @return the smallest radius a platform can curve at for cars of this length without a gap wider than the usual one
	 * by more than {@link #WARN_EXTRA_GAP}
	 */
	public static double smallestRadius(double carLength) {
		return carLength * carLength / (8 * WARN_EXTRA_GAP);
	}

	/**
	 * The cut of a block by a platform's edge: the platform is where a point's distance along {@code side} from the edge
	 * line is not negative.
	 *
	 * @param blockX the block's corner (its smallest x and z)
	 * @param edgeX  a point on the edge line, near the block
	 * @param sideX  a horizontal direction towards the platform, square to the edge line there
	 * @return the block state's angle and cut, as {@link #pack}; {@link #FULL} when all of the block is platform, {@link
	 * #EMPTY} when none of it is
	 */
	public static int cutOf(int blockX, int blockZ, double edgeX, double edgeZ, double sideX, double sideZ) {
		final double sideLength = Math.hypot(sideX, sideZ);
		if (sideLength == 0) {
			return EMPTY;
		}
		final double towardsTrackX = -sideX / sideLength;
		final double towardsTrackZ = -sideZ / sideLength;
		// Platform where (q - centre) · towardsTrack <= d
		final double centreX = blockX + 0.5;
		final double centreZ = blockZ + 0.5;
		final double d = (edgeX - centreX) * towardsTrackX + (edgeZ - centreZ) * towardsTrackZ;
		if (d >= Math.abs(towardsTrackX) * 0.5 + Math.abs(towardsTrackZ) * 0.5) {
			return FULL;
		}
		if (d <= -(Math.abs(towardsTrackX) * 0.5 + Math.abs(towardsTrackZ) * 0.5)) {
			return EMPTY;
		}
		final double angle = Math.atan2(towardsTrackZ, towardsTrackX);
		final int angleIndex = Math.floorMod((int) Math.round(angle / (2 * Math.PI) * ANGLES), ANGLES);
		final int cutIndex = Math.max(0, Math.min(CUTS - 1, (int) Math.round(d * CELLS) + CUT_CENTRE));
		return pack(angleIndex, cutIndex);
	}

	public static final int FULL = -1;
	public static final int EMPTY = -2;

	public static int pack(int angleIndex, int cutIndex) {
		return angleIndex * CUTS + cutIndex;
	}

	public static int angleOf(int packed) {
		return packed / CUTS;
	}

	public static int cutOfPacked(int packed) {
		return packed % CUTS;
	}

	/**
	 * @return which of the block's 16 by 16 columns are platform, as [x * 16 + z], for a block state's angle and cut
	 */
	public static boolean[] mask(int angleIndex, int cutIndex) {
		final int index = pack(angleIndex, cutIndex);
		boolean[] mask = MASKS[index];
		if (mask == null) {
			mask = new boolean[CELLS * CELLS];
			final double angle = angleIndex * 2 * Math.PI / ANGLES;
			final double towardsTrackX = Math.cos(angle);
			final double towardsTrackZ = Math.sin(angle);
			final double d = (double) (cutIndex - CUT_CENTRE) / CELLS;
			for (int x = 0; x < CELLS; x++) {
				for (int z = 0; z < CELLS; z++) {
					final double localX = (x + 0.5) / CELLS - 0.5;
					final double localZ = (z + 0.5) / CELLS - 0.5;
					mask[x * CELLS + z] = localX * towardsTrackX + localZ * towardsTrackZ <= d;
				}
			}
			MASKS[index] = mask;
		}
		return mask;
	}

	/**
	 * @return the corners of the platform's part of the block, in block coordinates from 0 to 1 as {x, z} pairs going
	 * round, for drawing its top; the cut runs from the second last corner to the last
	 */
	public static double[] outline(int angleIndex, int cutIndex) {
		final double angle = angleIndex * 2 * Math.PI / ANGLES;
		final double towardsTrackX = Math.cos(angle);
		final double towardsTrackZ = Math.sin(angle);
		final double d = (double) (cutIndex - CUT_CENTRE) / CELLS;
		final double[][] square = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		final double[] points = new double[12];
		int count = 0;
		int cutStart = -1;
		for (int i = 0; i < 4; i++) {
			final double[] a = square[i];
			final double[] b = square[(i + 1) % 4];
			final double valueA = (a[0] - 0.5) * towardsTrackX + (a[1] - 0.5) * towardsTrackZ - d;
			final double valueB = (b[0] - 0.5) * towardsTrackX + (b[1] - 0.5) * towardsTrackZ - d;
			if (valueA <= 0) {
				points[count++] = a[0];
				points[count++] = a[1];
			}
			if (valueA <= 0 != valueB <= 0) {
				final double t = valueA / (valueA - valueB);
				points[count++] = a[0] + (b[0] - a[0]) * t;
				points[count++] = a[1] + (b[1] - a[1]) * t;
				if (valueA <= 0) {
					cutStart = count - 2;
				}
			}
		}
		final double[] result = new double[count];
		// Turned so that the cut is from the second last corner to the last
		if (cutStart >= 0) {
			// The cut's two ends follow each other: where the outline leaves the platform, then where it comes back
			final int shift = (cutStart + 4) % count;
			for (int i = 0; i < count; i++) {
				result[i] = points[(i + shift) % count];
			}
		} else {
			System.arraycopy(points, 0, result, 0, count);
		}
		return result;
	}
}
