package mtr.escalator;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a free escalator (0.100.0, user 2026-11-17: 扶梯自由方向，标记点后一次生成；只在同一个竖直面里折；任意坡度含
 * 自动人行道；后改为三档坡度 0°、26.57°、45°). Plain geometry, no game classes.
 * <p>
 * The points marked lie in one vertical plane: seen from above they are on the line from the first to the last (within
 * {@link #LATERAL_TOLERANCE}), each further along it than the one before, and each part between two is level, 1 in 2
 * (26.57°) or 1 in 1 (45°) ({@link #SLOPE_DEGREES}). Along that line s is the horizontal distance
 * from the first point and h the height; between the points the run is straight, and where the slope changes it bends
 * smoothly over a parabola. The run is turned round if needed so that it rises with s ("up" is towards larger s).
 * <p>
 * Steps or pallets: if no part is steeper than {@link #WALKWAY_GRADE} (12°) it is a moving walkway, its pallets lying
 * along the run; otherwise an escalator, whose treads stay level, each {@link #STEP_DEPTH} deep, the step's uphill end
 * on the run's line and a riser at its downhill end. What people stand on (the collision, {@link #standHeight}) is the
 * run's line half a step further on, between the treads' two ends.
 */
public final class FreeEscalatorPath {

	/**
	 * The only slopes a part between two points may have (0.100.0, user 2026-11-17: 角度限制，三档 0 26.52 45): level,
	 * 1 up for 2 along (26.57°) and 1 up for 1 along (45°), each within {@link #SLOPE_TOLERANCE_DEGREES}
	 */
	public static final double[] SLOPE_DEGREES = {0, Math.toDegrees(Math.atan(0.5)), 45};
	public static final double SLOPE_TOLERANCE_DEGREES = 1;
	/** Steepest slope allowed, as a grade */
	public static final double MAX_GRADE = Math.tan(Math.toRadians(45 + SLOPE_TOLERANCE_DEGREES));
	/** Up to this slope (12°) everywhere the run is a moving walkway */
	public static final double WALKWAY_GRADE = Math.tan(Math.toRadians(12.5));
	/** How far seen from above a point may be from the line from the first point to the last */
	public static final double LATERAL_TOLERANCE = 0.75;
	/** How far over the top of a block marked the surface lies, so that the blocks marked stay as they are */
	public static final double DECK = 2 / 16D;
	/** Tread depth, horizontal */
	public static final double STEP_DEPTH = 0.4;
	/** The longest half-length a bend takes */
	public static final double MAX_BEND = 1.5;
	/** At most this many blocks of collision, so that a mistaken mark can't fill the world */
	public static final int MAX_CELLS = 8192;

	public final double originX, originZ;
	/** Unit vector, seen from above, along the run (towards larger s) */
	public final double directionX, directionZ;
	public final double width;
	public final double length;
	/** True for an escalator with level treads, false for a moving walkway */
	public final boolean stepped;
	public final double maxGrade;

	private final double[] s;
	private final double[] h;
	private final double[] bend;

	private FreeEscalatorPath(double originX, double originZ, double directionX, double directionZ, double[] s, double[] h, double width) {
		this.originX = originX;
		this.originZ = originZ;
		this.directionX = directionX;
		this.directionZ = directionZ;
		this.s = s;
		this.h = h;
		this.width = width;
		length = s[s.length - 1];
		bend = new double[s.length];
		double steepest = 0;
		for (int i = 0; i < s.length - 1; i++) {
			steepest = Math.max(steepest, Math.abs(grade(i)));
		}
		for (int i = 1; i < s.length - 1; i++) {
			bend[i] = Math.min(MAX_BEND, 0.45 * Math.min(s[i] - s[i - 1], s[i + 1] - s[i]));
		}
		maxGrade = steepest;
		stepped = steepest > WALKWAY_GRADE;
	}

	/**
	 * The run through these points (x, y, z: the middle of the blocks marked, {@link #DECK} over their tops), or why there
	 * can't be one.
	 *
	 * @param points the points in the order they were marked
	 * @param width  1 or 2 blocks
	 */
	public static Result build(List<double[]> points, double width) {
		if (points.size() < 2) {
			return Result.error("too_few");
		}
		final List<double[]> ordered = new ArrayList<>(points);
		if (ordered.get(ordered.size() - 1)[1] < ordered.get(0)[1]) {
			java.util.Collections.reverse(ordered);
		}
		final double[] first = ordered.get(0);
		final double[] last = ordered.get(ordered.size() - 1);
		final double dx = last[0] - first[0];
		final double dz = last[2] - first[2];
		final double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1) {
			return Result.error("too_short");
		}
		final double ux = dx / horizontal;
		final double uz = dz / horizontal;
		final double[] s = new double[ordered.size()];
		final double[] h = new double[ordered.size()];
		for (int i = 0; i < ordered.size(); i++) {
			final double[] point = ordered.get(i);
			final double px = point[0] - first[0];
			final double pz = point[2] - first[2];
			s[i] = px * ux + pz * uz;
			h[i] = point[1];
			final double lateral = -px * uz + pz * ux;
			final int marked = points.indexOf(point) + 1;
			if (Math.abs(lateral) > LATERAL_TOLERANCE) {
				return Result.error("not_same_plane", marked, Math.round(Math.abs(lateral) * 10) / 10D);
			}
			if (i > 0 && s[i] - s[i - 1] < 0.5) {
				return Result.error("backwards", marked);
			}
			if (i > 0) {
				final double degrees = Math.toDegrees(Math.atan(Math.abs(h[i] - h[i - 1]) / (s[i] - s[i - 1])));
				if (!allowedSlope(degrees)) {
					return Result.error("bad_slope", marked, Math.round(degrees * 10) / 10D);
				}
			}
		}
		final FreeEscalatorPath path = new FreeEscalatorPath(first[0], first[2], ux, uz, s, h, width);
		final List<int[]> cells = path.cells();
		if (cells.size() > MAX_CELLS) {
			return Result.error("too_big", cells.size());
		}
		return new Result(path, null, new Object[0]);
	}

	/** Whether a part may slope so: one of {@link #SLOPE_DEGREES} */
	public static boolean allowedSlope(double degrees) {
		for (final double allowed : SLOPE_DEGREES) {
			if (Math.abs(degrees - allowed) <= SLOPE_TOLERANCE_DEGREES) {
				return true;
			}
		}
		return false;
	}

	private double grade(int segment) {
		return (h[segment + 1] - h[segment]) / (s[segment + 1] - s[segment]);
	}

	private int segmentAt(double distance) {
		for (int i = 0; i < s.length - 2; i++) {
			if (distance < s[i + 1]) {
				return i;
			}
		}
		return s.length - 2;
	}

	/** Height of the run's line at s (clamped to the run), bends smoothed */
	public double height(double distance) {
		final double at = Math.max(0, Math.min(length, distance));
		for (int i = 1; i < s.length - 1; i++) {
			final double t = bend[i];
			if (t > 0 && at > s[i] - t && at < s[i] + t) {
				final double g1 = grade(i - 1);
				final double g2 = grade(i);
				final double offset = at - s[i];
				return h[i] + g1 * offset + (g2 - g1) * (offset + t) * (offset + t) / (4 * t);
			}
		}
		final int segment = segmentAt(at);
		return h[segment] + grade(segment) * (at - s[segment]);
	}

	/** Slope (rise over run) of the line at s */
	public double grade(double distance) {
		final double at = Math.max(0, Math.min(length, distance));
		for (int i = 1; i < s.length - 1; i++) {
			final double t = bend[i];
			if (t > 0 && at > s[i] - t && at < s[i] + t) {
				final double g1 = grade(i - 1);
				final double g2 = grade(i);
				return g1 + (g2 - g1) * (at - s[i] + t) / (2 * t);
			}
		}
		return grade(segmentAt(at));
	}

	/** Height people stand at, at s: between a tread's two ends on an escalator, the pallet on a walkway */
	public double standHeight(double distance) {
		return stepped ? height(Math.min(length, distance + STEP_DEPTH / 2)) : height(distance);
	}

	/** Distance along the run of a point seen from above */
	public double along(double x, double z) {
		return (x - originX) * directionX + (z - originZ) * directionZ;
	}

	/** Distance to the side of the run's middle (positive to the right looking up the run) */
	public double across(double x, double z) {
		return -(x - originX) * directionZ + (z - originZ) * directionX;
	}

	public double worldX(double distance, double side) {
		return originX + distance * directionX - side * directionZ;
	}

	public double worldZ(double distance, double side) {
		return originZ + distance * directionZ + side * directionX;
	}

	/**
	 * What a block holds of the run, as the height of the standing surface in sixteenths of a block over each of its
	 * 4 x 4 columns (0: none, 16: up to its top), or null if the surface doesn't pass through it. Columns under the
	 * surface by more than the block are full where the surface passes through some other column of the same block.
	 */
	public byte[] columnHeights(int blockX, int blockY, int blockZ) {
		final byte[] heights = new byte[16];
		boolean through = false;
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				final double x = blockX + (i + 0.5) / 4;
				final double z = blockZ + (j + 0.5) / 4;
				final double distance = along(x, z);
				if (distance < 0 || distance > length || Math.abs(across(x, z)) > width / 2) {
					continue;
				}
				final double above = standHeight(distance) - blockY;
				if (above > 0 && above <= 1) {
					through = true;
				}
				// A surface just over the block's bottom still counts, as a sixteenth
				heights[i * 4 + j] = above <= 0 ? 0 : (byte) Math.max(1, Math.min(16, Math.round(above * 16)));
			}
		}
		return through ? heights : null;
	}

	/** The blocks the standing surface passes through, as x, y, z */
	public List<int[]> cells() {
		final List<int[]> cells = new ArrayList<>();
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (final double distance : new double[]{0, length}) {
			for (final double side : new double[]{-width / 2, width / 2}) {
				minX = Math.min(minX, worldX(distance, side));
				maxX = Math.max(maxX, worldX(distance, side));
				minZ = Math.min(minZ, worldZ(distance, side));
				maxZ = Math.max(maxZ, worldZ(distance, side));
			}
		}
		final int minY = (int) Math.floor(Math.min(standHeight(0), height(0))) - 1;
		final int maxY = (int) Math.ceil(standHeight(length)) + 1;
		for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++) {
			for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
				for (int y = minY; y <= maxY; y++) {
					if (columnHeights(x, y, z) != null) {
						cells.add(new int[]{x, y, z});
						if (cells.size() > MAX_CELLS) {
							return cells;
						}
					}
				}
			}
		}
		return cells;
	}

	/** How fast the steps move seen from above, in blocks a tick, for a speed along the steepest part in metres a second */
	public double horizontalSpeed(double metresPerSecond) {
		return metresPerSecond / 20 / Math.sqrt(1 + maxGrade * maxGrade);
	}

	/**
	 * Calls back with each step's tread over the run, for steps moved on by phase (0 to 1 of a step depth): from s, at
	 * height, to s, at height. On an escalator both heights are the same (the tread is level); on a walkway the pallet
	 * follows the run.
	 */
	public void forEachStep(double phase, StepConsumer consumer) {
		final int count = (int) Math.ceil(length / STEP_DEPTH) + 1;
		for (int k = 0; k <= count; k++) {
			final double front = (k + phase) * STEP_DEPTH;
			final double back = front - STEP_DEPTH;
			final double from = Math.max(0, back);
			final double to = Math.min(length, front);
			if (to <= from) {
				continue;
			}
			if (stepped) {
				final double tread = height(front);
				consumer.accept(back, from, tread, to, tread, height(back));
			} else {
				consumer.accept(back, from, height(from), to, height(to), height(back));
			}
		}
	}

	@FunctionalInterface
	public interface StepConsumer {
		/**
		 * @param back        where the whole step begins (may be before the run)
		 * @param from        where the part over the run begins
		 * @param fromHeight  its height there
		 * @param to          where it ends
		 * @param toHeight    its height there
		 * @param belowHeight the height of the step behind, where this one begins (the riser runs from there up)
		 */
		void accept(double back, double from, double fromHeight, double to, double toHeight, double belowHeight);
	}

	/** A run, or the key of what is wrong (gui.mtr.free_escalator_ + key) with its arguments */
	public static final class Result {

		public final FreeEscalatorPath path;
		public final String error;
		public final Object[] arguments;

		private Result(FreeEscalatorPath path, String error, Object[] arguments) {
			this.path = path;
			this.error = error;
			this.arguments = arguments;
		}

		private static Result error(String error, Object... arguments) {
			return new Result(null, error, arguments);
		}
	}
}
