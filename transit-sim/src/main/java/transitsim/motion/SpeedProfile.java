package transitsim.motion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The speed of a train along one run, from standing at one stop to standing at the next (design document section 8). The
 * speed limit is a step function of the head's position. The fastest run under it, accelerating and braking at fixed
 * rates, has a square of speed that is piecewise linear in position, so the run is kept as nodes of (position, v²) and
 * every time and speed is worked out exactly from them. Units are blocks and ticks.
 * <p>
 * A run can also start part of the way along, at the speed the train has there (review A04), as when a train held back
 * on the way sets off again. A train starting faster than the limit brakes down to it at its own rate rather than all at
 * once; one starting too fast to stop at the end starts at the fastest speed it could still stop from.
 */
public final class SpeedProfile {

	public final double startPosition;
	public final double endPosition;
	private final double[] positions;
	private final double[] speedsSquared;
	private final double[] times;

	private static final double EPSILON = 1E-9;
	private static final int CAP_SEARCH_STEPS = 40;

	private SpeedProfile(double[] positions, double[] speedsSquared) {
		this.positions = positions;
		this.speedsSquared = speedsSquared;
		startPosition = positions[0];
		endPosition = positions[positions.length - 1];
		times = new double[positions.length];
		for (int i = 1; i < positions.length; i++) {
			times[i] = times[i - 1] + pieceTime(positions[i] - positions[i - 1], Math.sqrt(speedsSquared[i - 1]), Math.sqrt(speedsSquared[i]));
		}
	}

	/**
	 * @param breaks       where the speed limit changes, from the start of the run to its end, at least two
	 * @param limits       the speed limit after each break but the last
	 * @param acceleration how fast the train speeds up, in blocks per tick²
	 * @param deceleration how fast the train brakes
	 * @param cap          the highest speed the train may cruise at, or infinity for none
	 */
	public static SpeedProfile build(double[] breaks, double[] limits, double rawAcceleration, double rawDeceleration, double cap) {
		return build(breaks, limits, rawAcceleration, rawDeceleration, cap, 0);
	}

	/**
	 * @param startSpeed the speed at the start of the run; at 0 the run is exactly the one starting from standing
	 */
	public static SpeedProfile build(double[] breaks, double[] limits, double rawAcceleration, double rawDeceleration, double cap, double startSpeed) {
		final double acceleration = Math.max(rawAcceleration, EPSILON);
		final double deceleration = Math.max(rawDeceleration, EPSILON);
		final int count = limits.length;
		if (breaks.length != count + 1 || count == 0 || breaks[count] - breaks[0] <= EPSILON) {
			return new SpeedProfile(new double[]{breaks[0], breaks[0]}, new double[]{0, 0});
		}
		final double[] limitsSquared = new double[count];
		for (int i = 0; i < count; i++) {
			final double limit = Math.min(limits[i], cap);
			limitsSquared[i] = limit * limit;
		}

		// A train starting faster than the limit may stay above it while it brakes: the allowed value is the higher of the
		// limit² and the start braking down, startSquared - 2d(s - start)
		final double start0 = breaks[0];
		final boolean slowsDown = startSpeed > 0;
		final double startSquared = slowsDown ? startSpeed * startSpeed : 0;

		// Speeding up from the start: the value at the start of each part, then within it min(limit², start + 2a(s - a_i))
		final double[] forward = new double[count];
		double carried = startSquared;
		for (int i = 0; i < count; i++) {
			forward[i] = Math.min(allowed(limitsSquared[i], slowsDown, startSquared, deceleration, start0, breaks[i]), carried);
			carried = Math.min(allowed(limitsSquared[i], slowsDown, startSquared, deceleration, start0, breaks[i + 1]), forward[i] + 2 * acceleration * (breaks[i + 1] - breaks[i]));
		}
		// Braking for the end: the value at the end of each part, then within it min(limit², end + 2d(b_i - s))
		final double[] backward = new double[count];
		carried = 0;
		for (int i = count - 1; i >= 0; i--) {
			backward[i] = Math.min(allowed(limitsSquared[i], slowsDown, startSquared, deceleration, start0, breaks[i + 1]), carried);
			carried = Math.min(allowed(limitsSquared[i], slowsDown, startSquared, deceleration, start0, breaks[i]), backward[i] + 2 * deceleration * (breaks[i + 1] - breaks[i]));
		}

		final List<double[]> nodes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final double start = breaks[i];
			final double end = breaks[i + 1];
			final double limitSquared = limitsSquared[i];
			final double forwardStart = forward[i];
			final double backwardEnd = backward[i];
			// Where each line meets the limit, and where speeding up meets braking
			final List<Double> candidates = new ArrayList<>();
			candidates.add(start);
			candidates.add(end);
			candidates.add(start + (limitSquared - forwardStart) / (2 * acceleration));
			candidates.add(end - (limitSquared - backwardEnd) / (2 * deceleration));
			candidates.add((backwardEnd + 2 * deceleration * end - forwardStart + 2 * acceleration * start) / (2 * acceleration + 2 * deceleration));
			if (slowsDown) {
				// Where the start braking down meets the limit and speeding up; braking for the end runs parallel to it
				candidates.add(start0 + (startSquared - limitSquared) / (2 * deceleration));
				candidates.add((startSquared + 2 * deceleration * start0 - forwardStart + 2 * acceleration * start) / (2 * acceleration + 2 * deceleration));
			}
			candidates.stream().filter(position -> position >= start && position <= end && Double.isFinite(position)).sorted().forEach(position -> {
				final double upper = allowed(limitSquared, slowsDown, startSquared, deceleration, start0, position);
				final double value = Math.max(0, Math.min(upper, Math.min(forwardStart + 2 * acceleration * (position - start), backwardEnd + 2 * deceleration * (end - position))));
				if (nodes.isEmpty() || position - nodes.get(nodes.size() - 1)[0] > EPSILON) {
					nodes.add(new double[]{position, value});
				} else {
					final double[] last = nodes.get(nodes.size() - 1);
					last[1] = Math.min(last[1], value);
				}
			});
		}
		if (!slowsDown) {
			nodes.get(0)[1] = 0;
		}
		nodes.get(nodes.size() - 1)[1] = 0;
		final double[] positions = new double[nodes.size()];
		final double[] speedsSquared = new double[nodes.size()];
		for (int i = 0; i < nodes.size(); i++) {
			positions[i] = nodes.get(i)[0];
			speedsSquared[i] = nodes.get(i)[1];
		}
		return new SpeedProfile(positions, speedsSquared);
	}

	/**
	 * The cruising speed that makes a run take a given time, found by halving the range (design document 8.2, the time
	 * constraint). A train given more time than it needs runs slower and arrives on time instead of early.
	 *
	 * @param minCap the slowest the train may cruise at; if even that is too fast, the train arrives early
	 * @return the cap, or infinity when the time is no more than the fastest run takes
	 */
	public static double findCap(double[] breaks, double[] limits, double acceleration, double deceleration, double targetTicks, double minCap) {
		return findCap(breaks, limits, acceleration, deceleration, targetTicks, minCap, 0);
	}

	/**
	 * @param startSpeed the speed at the start of the run, as for {@link #build(double[], double[], double, double, double, double)}
	 */
	public static double findCap(double[] breaks, double[] limits, double acceleration, double deceleration, double targetTicks, double minCap, double startSpeed) {
		final SpeedProfile fastest = build(breaks, limits, acceleration, deceleration, Double.POSITIVE_INFINITY, startSpeed);
		if (targetTicks <= fastest.getDuration() + EPSILON) {
			return Double.POSITIVE_INFINITY;
		}
		double low = minCap;
		double high = Arrays.stream(limits).max().orElse(minCap);
		if (high <= low || build(breaks, limits, acceleration, deceleration, low, startSpeed).getDuration() <= targetTicks) {
			return low;
		}
		for (int i = 0; i < CAP_SEARCH_STEPS; i++) {
			final double middle = (low + high) / 2;
			if (build(breaks, limits, acceleration, deceleration, middle, startSpeed).getDuration() > targetTicks) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return high;
	}

	public double getDuration() {
		return times[times.length - 1];
	}

	public double getSpeed(double position) {
		final int index = findPiece(position);
		final double length = positions[index + 1] - positions[index];
		if (length <= EPSILON) {
			return Math.sqrt(speedsSquared[index + 1]);
		}
		final double fraction = clamp((position - positions[index]) / length);
		return Math.sqrt(Math.max(0, speedsSquared[index] + (speedsSquared[index + 1] - speedsSquared[index]) * fraction));
	}

	/**
	 * @return ticks from leaving the start of the run to reaching a position
	 */
	public double getTime(double position) {
		final double clamped = Math.max(startPosition, Math.min(endPosition, position));
		final int index = findPiece(clamped);
		return times[index] + pieceTime(clamped - positions[index], Math.sqrt(speedsSquared[index]), getSpeed(clamped));
	}

	/**
	 * @return where the train is a given number of ticks after leaving the start of the run
	 */
	public double getPosition(double ticks) {
		if (ticks <= 0) {
			return startPosition;
		}
		if (ticks >= getDuration()) {
			return endPosition;
		}
		int low = 0;
		int high = times.length - 1;
		while (high - low > 1) {
			final int middle = (low + high) >>> 1;
			if (times[middle] <= ticks) {
				low = middle;
			} else {
				high = middle;
			}
		}
		final double length = positions[low + 1] - positions[low];
		final double startSpeed = Math.sqrt(speedsSquared[low]);
		final double acceleration = length <= EPSILON ? 0 : (speedsSquared[low + 1] - speedsSquared[low]) / (2 * length);
		final double elapsed = ticks - times[low];
		return Math.min(positions[low + 1], positions[low] + startSpeed * elapsed + acceleration * elapsed * elapsed / 2);
	}

	/**
	 * Moves a train that is on the curve along it for some ticks.
	 *
	 * @return the new position, never past the end of the run
	 */
	public double advance(double position, double ticks) {
		return getPosition(getTime(position) + ticks);
	}

	/**
	 * The parts of the run with constant acceleration, for timing positions in between: each is {start position, start
	 * speed, start time, acceleration}.
	 */
	public List<double[]> getPieces() {
		final List<double[]> pieces = new ArrayList<>();
		for (int i = 0; i + 1 < positions.length; i++) {
			final double length = positions[i + 1] - positions[i];
			if (length > EPSILON) {
				pieces.add(new double[]{positions[i], Math.sqrt(speedsSquared[i]), times[i], (speedsSquared[i + 1] - speedsSquared[i]) / (2 * length)});
			}
		}
		return pieces;
	}

	private int findPiece(double position) {
		int low = 0;
		int high = positions.length - 1;
		while (high - low > 1) {
			final int middle = (low + high) >>> 1;
			if (positions[middle] <= position) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return Math.min(low, positions.length - 2);
	}

	/**
	 * Under constant acceleration the average speed is the mean of the two ends.
	 */
	private static double pieceTime(double length, double startSpeed, double endSpeed) {
		if (length <= EPSILON) {
			return 0;
		}
		return 2 * length / Math.max(startSpeed + endSpeed, EPSILON);
	}

	/**
	 * The highest v² allowed at a position: the limit², or the start braking down while that is higher.
	 */
	private static double allowed(double limitSquared, boolean slowsDown, double startSquared, double deceleration, double start, double position) {
		return slowsDown ? Math.max(limitSquared, startSquared - 2 * deceleration * (position - start)) : limitSquared;
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
