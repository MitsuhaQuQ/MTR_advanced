package transitsim.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * The speed curves of a train's whole path (design document section 8): one {@link SpeedProfile} for each run between
 * stops. A stop is the end of a rail with a stop time, and the path ends with one. At a reversal the head jumps to the
 * other end of the train, so the next run starts a train's length further on, as everywhere else in the game.
 * <p>
 * The speed limit at a head position is the lowest of every rail the train is on, so a train slows down before its head
 * enters a slower rail and speeds up only once its tail has left one. Rails a train can't speed up on, such as platforms
 * and sidings, keep the limit of the rail before them.
 */
public final class SpeedCurves {

	public final List<Run> runs;
	public final double acceleration;
	public final double deceleration;
	public final double minSpeed;

	private SpeedCurves(List<Run> runs, double acceleration, double deceleration, double minSpeed) {
		this.runs = Collections.unmodifiableList(runs);
		this.acceleration = acceleration;
		this.deceleration = deceleration;
		this.minSpeed = minSpeed;
	}

	/**
	 * @param startPosition where the head starts, in the siding
	 * @param trainLength   the length of the train, which is how far the head moves at a reversal
	 */
	public static SpeedCurves of(TrackView track, double startPosition, double trainLength, double acceleration, double deceleration) {
		final double minSpeed = track.minSpeed;
		final int count = track.size();
		final double[] ends = new double[count];
		final double[] limits = new double[count];
		double distance = 0;
		double lastLimit = minSpeed;
		for (int i = 0; i < count; i++) {
			distance += track.getLength(i);
			ends[i] = distance;
			if (track.getLimit(i) >= 0) {
				lastLimit = Math.max(track.getLimit(i), minSpeed);
			}
			limits[i] = lastLimit;
		}

		final List<Run> runs = new ArrayList<>();
		double runStart = startPosition;
		for (int i = 0; i < count; i++) {
			if (track.isStop(i) || i == count - 1) {
				runs.add(new Run(runStart, Math.max(runStart, ends[i]), i, ends, limits, trainLength, acceleration, deceleration));
				runStart = ends[i] + (i + 1 < count && track.reversesAfter(i) ? trainLength : 0);
			}
		}
		return new SpeedCurves(runs, acceleration, deceleration, minSpeed);
	}

	/**
	 * @return the run a head position belongs to: the one it is on, or the one it is about to start when standing at a stop
	 */
	public Run getRun(double position) {
		for (final Run run : runs) {
			if (position < run.fastest.endPosition - 1E-6) {
				return run;
			}
		}
		return runs.isEmpty() ? null : runs.get(runs.size() - 1);
	}

	/**
	 * @return ticks from the start of the path to the end of each run, without stop times
	 */
	public double getRunTicks(int runIndex) {
		return runs.get(runIndex).fastest.getDuration();
	}

	/**
	 * The cruising speed that gets a train setting off to its next stop there in a given time (design document 8.2). A
	 * train setting off part of the way along a run, as after being held back by a train ahead, gets one from where it is
	 * and how fast it is going (review A04), so it can still arrive on time if it has the time to.
	 *
	 * @param stopRailIndex the rail the train stops at next, which has to be the end of the run it is on
	 * @return the cap, or infinity for none
	 */
	public double findCruiseCap(double position, double speed, int stopRailIndex, double ticks) {
		final Run run = getRun(position);
		if (run == null || run.stopRailIndex != stopRailIndex) {
			return Double.POSITIVE_INFINITY;
		}
		return Math.abs(position - run.fastest.startPosition) <= 1 ? run.findCap(ticks, minSpeed) : run.findCapFrom(position, speed, ticks, minSpeed);
	}

	public static final class Run {

		/**
		 * The rail at whose end the run stops.
		 */
		public final int stopRailIndex;
		public final SpeedProfile fastest;
		private final double[] breaks;
		private final double[] limits;
		private final double acceleration;
		private final double deceleration;

		private Run(double start, double end, int stopRailIndex, double[] railEnds, double[] railLimits, double trainLength, double acceleration, double deceleration) {
			this.stopRailIndex = stopRailIndex;
			this.acceleration = acceleration;
			this.deceleration = deceleration;

			// The limit changes where the head enters a rail and where the tail leaves one
			final TreeSet<Double> points = new TreeSet<>();
			points.add(start);
			points.add(end);
			for (int i = firstRailEndingAfter(railEnds, start - trainLength); i < railEnds.length && (i == 0 || railEnds[i - 1] < end); i++) {
				addIfInside(points, railEnds[i], start, end);
				addIfInside(points, railEnds[i] + trainLength, start, end);
			}
			breaks = points.stream().mapToDouble(Double::doubleValue).toArray();
			limits = new double[Math.max(breaks.length - 1, 0)];
			for (int i = 0; i < limits.length; i++) {
				final double middle = (breaks[i] + breaks[i + 1]) / 2;
				limits[i] = getLimit(railEnds, railLimits, middle - trainLength, middle);
			}
			fastest = breaks.length < 2 ? SpeedProfile.build(new double[]{start, start}, new double[]{railLimits.length == 0 ? 0 : railLimits[0]}, acceleration, deceleration, Double.POSITIVE_INFINITY) : SpeedProfile.build(breaks, limits, acceleration, deceleration, Double.POSITIVE_INFINITY);
		}

		/**
		 * @return the run with a cruising speed that takes this many ticks, or the fastest run when it has less time
		 */
		public SpeedProfile withTime(double ticks, double minSpeed) {
			if (breaks.length < 2) {
				return fastest;
			}
			final double cap = SpeedProfile.findCap(breaks, limits, acceleration, deceleration, ticks, minSpeed);
			return withCap(cap);
		}

		/**
		 * @return the cruising speed that takes this many ticks, or infinity when the run has no time to spare
		 */
		public double findCap(double ticks, double minSpeed) {
			return breaks.length < 2 ? Double.POSITIVE_INFINITY : SpeedProfile.findCap(breaks, limits, acceleration, deceleration, ticks, minSpeed);
		}

		public SpeedProfile withCap(double cap) {
			return breaks.length < 2 || Double.isInfinite(cap) ? fastest : SpeedProfile.build(breaks, limits, acceleration, deceleration, cap);
		}

		/**
		 * @return the rest of the run, for a train setting off part of the way along it at some speed
		 */
		public SpeedProfile from(double position, double speed, double cap) {
			return fromTo(position, speed, cap, fastest.endPosition, deceleration);
		}

		/**
		 * @param end          where the train has to stop, which can be short of the end of the run when the line ahead is taken
		 * @param deceleration the braking to use, which can be harder than the train's own in an emergency
		 * @return the run from part of the way along it to a stop, ending exactly at {@code end}
		 */
		public SpeedProfile fromTo(double position, double speed, double cap, double end, double deceleration) {
			final double[][] rest = getRest(position, end);
			return rest == null ? SpeedProfile.build(new double[]{end, end}, new double[]{0}, acceleration, deceleration, cap) : SpeedProfile.build(rest[0], rest[1], acceleration, deceleration, cap, speed);
		}

		/**
		 * @return the cruising speed that takes the rest of the run this many ticks, or infinity when it has no time to spare
		 */
		public double findCapFrom(double position, double speed, double ticks, double minSpeed) {
			final double[][] rest = getRest(position, fastest.endPosition);
			return rest == null ? Double.POSITIVE_INFINITY : SpeedProfile.findCap(rest[0], rest[1], acceleration, deceleration, ticks, minSpeed, speed);
		}

		public double getDeceleration() {
			return deceleration;
		}

		/**
		 * @return the breaks and limits from a position to an end no further than the end of the run, or null if there is
		 * nothing between them
		 */
		private double[][] getRest(double position, double rawEnd) {
			final double end = Math.min(rawEnd, breaks.length < 2 ? rawEnd : breaks[breaks.length - 1]);
			if (breaks.length < 2 || position >= end - 1E-6) {
				return null;
			}
			final double start = Math.max(position, breaks[0]);
			int first = 0;
			while (first + 1 < limits.length && breaks[first + 1] <= start + 1E-6) {
				first++;
			}
			int last = first;
			while (last + 1 < limits.length && breaks[last + 1] < end - 1E-6) {
				last++;
			}
			final int count = last - first + 1;
			final double[] restBreaks = new double[count + 1];
			final double[] restLimits = new double[count];
			restBreaks[0] = start;
			System.arraycopy(breaks, first + 1, restBreaks, 1, count - 1);
			restBreaks[count] = end;
			System.arraycopy(limits, first, restLimits, 0, count);
			return new double[][]{restBreaks, restLimits};
		}

		private static void addIfInside(TreeSet<Double> points, double point, double start, double end) {
			if (point > start + 1E-6 && point < end - 1E-6) {
				points.add(point);
			}
		}

		/**
		 * @return the lowest limit of the rails between the tail and the head
		 */
		private static double getLimit(double[] railEnds, double[] railLimits, double tail, double head) {
			double limit = Double.POSITIVE_INFINITY;
			for (int i = firstRailEndingAfter(railEnds, tail); i < railEnds.length && (i == 0 || railEnds[i - 1] < head); i++) {
				limit = Math.min(limit, railLimits[i]);
			}
			return Double.isInfinite(limit) ? (railLimits.length == 0 ? 0 : railLimits[railLimits.length - 1]) : limit;
		}

		private static int firstRailEndingAfter(double[] railEnds, double position) {
			int low = 0;
			int high = railEnds.length;
			while (low < high) {
				final int middle = (low + high) >>> 1;
				if (railEnds[middle] <= position) {
					low = middle + 1;
				} else {
					high = middle;
				}
			}
			return low;
		}
	}
}
