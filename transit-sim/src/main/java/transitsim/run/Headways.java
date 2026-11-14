package transitsim.run;

import transitsim.network.BlockSections;
import transitsim.plan.TimeSegment;
import transitsim.sim.RunTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * How close behind another a train can run on a path (B, 2026-11-14): the least time between two trains at each block
 * section, by the rules they run by, for the timetable conflict check. Line 7 of the test lines ran services every 30
 * seconds the check passed, while trains managed only 35–39 seconds apart.
 * <p>
 * A train holds a section until its tail, and the gap it keeps behind it (one car, 0.69.0), have left it; the one behind
 * must have the section granted before it has to start braking to stop short of it, the same gap before its start. So at
 * section [b, e] the one behind is held up unless it reaches its braking point for b later than the one ahead reaches
 * e + its length + the gap, taken a couple of ticks to be granted. Sections are the groups trains claim
 * ({@link PathClaims#between}: block sections, painted blocks whole, a single track stretch at once), so the check
 * follows the rules the trains run by. Times are each train's planned times along the path.
 */
public final class Headways {

	/**
	 * Ticks a section takes to be granted once free: claims are resolved at the end of a tick, and the train moves on the
	 * next.
	 */
	public static final int GRANT_TICKS = 2;
	private static final double MILLIS_PER_TICK = 50;

	private Headways() {
	}

	/**
	 * @param path     the path both trains run
	 * @param sections the painted signal blocks, or null for none
	 * @param ahead    the train ahead
	 * @param behind   the train behind
	 * @return each section of the path both trains run through, with the least time the one behind has to be behind the
	 * one ahead to run it at its planned speed; sections at either end of their times are left out
	 */
	public static <T extends RunTrack> List<Gap> find(List<T> path, BlockSections sections, Train ahead, Train behind) {
		final List<Gap> gaps = new ArrayList<>();
		if (path.isEmpty() || ahead.times.isEmpty() || behind.times.isEmpty()) {
			return gaps;
		}
		final double[] ends = new double[path.size()];
		double distance = 0;
		for (int i = 0; i < path.size(); i++) {
			distance += path.get(i).getTrackLength();
			ends[i] = distance;
		}
		final PathClaims.Path<T> claimPath = new PathClaims.Path<T>() {
			@Override
			public List<T> getTracks() {
				return path;
			}

			@Override
			public double getEnd(int track) {
				return ends[track];
			}

			@Override
			public boolean turnsBack(int track, int nextTrack) {
				return path.get(track).isOppositeOf(path.get(nextTrack));
			}
		};
		final double aheadLast = last(ahead.times);
		final double behindFirst = behind.times.get(0).startRailProgress;
		// A path coming along the same rails again (a duty's laps, a turn back) is taken in stretches each rail is on once, as a
		// train claims only what is ahead of it: claims already taken in one call would join the next group
		final List<PathClaims.Group> groups = new ArrayList<>();
		final java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
		double stretchStart = 0;
		for (int i = 0; i < path.size(); i++) {
			final double start = i == 0 ? 0 : ends[i - 1];
			if (!seen.add(path.get(i).getTrackKey())) {
				groups.addAll(PathClaims.between(claimPath, stretchStart, start, sections));
				seen.clear();
				seen.add(path.get(i).getTrackKey());
				stretchStart = start;
			}
		}
		groups.addAll(PathClaims.between(claimPath, stretchStart, distance, sections));
		for (final PathClaims.Group group : groups) {
			// The one ahead frees the section once its tail and the gap behind it are past the end; standing at the last stop
			// with its tail still in it, as before a terminus, once it leaves there
			final double freedAt = group.end + ahead.length + ahead.gap;
			// The one behind must have it by the point it starts braking to stop the same gap short of its start
			final double needed = group.start - behind.gap;
			if (freedAt > aheadLast && group.end > aheadLast - 1E-6 || needed <= behindFirst) {
				continue;
			}
			final double aheadTime = freedAt > aheadLast ? timeAt(ahead.times, aheadLast) + path.get(path.size() - 1).getDwellTime() * 10 : timeAt(ahead.times, freedAt);
			final double braking = brakingPoint(behind, needed);
			if (braking <= behindFirst) {
				continue;
			}
			// Stopping anyway short of the section (a platform before it), it needs the section only to leave
			final double stop = stopBetween(behind.times, braking, group.start);
			final double behindTime = Double.isNaN(stop) ? timeAt(behind.times, braking) : departureAt(behind.times, stop);
			final double ticks = aheadTime - behindTime + GRANT_TICKS;
			boolean singleTrack = false;
			for (final transitsim.sim.Arbiter.Claim<java.util.UUID> claim : group.claims) {
				singleTrack |= claim.kind == transitsim.network.Resource.Kind.SINGLE_TRACK && group.end - group.start > 0;
			}
			gaps.add(new Gap(group.start, group.end, Math.round(Math.max(0, ticks) * MILLIS_PER_TICK), singleTrack));
		}
		return gaps;
	}

	/**
	 * @param gaps          the sections of a route's path, as {@link #find} gives them
	 * @param stopPositions how far along the path each stop of the route is, in order, NaN where the path doesn't reach it
	 * @return for each stop, the most time needed between two trains in the sections from it to the next stop; 0 for none
	 */
	public static long[] byStop(List<Gap> gaps, double[] stopPositions) {
		final long[] byStop = new long[stopPositions.length];
		for (final Gap gap : gaps) {
			int stop = -1;
			for (int i = 0; i < stopPositions.length; i++) {
				if (!Double.isNaN(stopPositions[i]) && stopPositions[i] <= gap.start + 1E-6) {
					stop = i;
				}
			}
			if (stop >= 0) {
				byStop[stop] = Math.max(byStop[stop], gap.millis);
			}
		}
		return byStop;
	}

	/**
	 * @return the furthest point from which the train, at its planned speed there, can stop by {@code stopAt}
	 */
	private static double brakingPoint(Train train, double stopAt) {
		double low = stopAt - topSpeedBraking(train) - 1;
		double high = stopAt;
		for (int i = 0; i < 50; i++) {
			final double middle = (low + high) / 2;
			final double speed = speedAt(train.times, middle);
			if (middle + speed * speed / (2 * train.deceleration) <= stopAt) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return low;
	}

	/**
	 * @return where the train stands at a stop between two points along its path, or NaN if it runs on
	 */
	private static double stopBetween(List<TimeSegment> times, double from, double to) {
		for (int i = 0; i + 1 < times.size(); i++) {
			final TimeSegment segment = times.get(i);
			if (segment.endRailProgress > from - 1E-6 && segment.endRailProgress <= to + 1E-6 && segment.getSpeed(segment.endRailProgress) < 1E-3 && times.get(i + 1).getTime(times.get(i + 1).startRailProgress) > segment.getTime(segment.endRailProgress) + 1E-3) {
				return segment.endRailProgress;
			}
		}
		return Double.NaN;
	}

	/**
	 * @return when the train leaves a stop it stands at, from the segment after it
	 */
	private static double departureAt(List<TimeSegment> times, double stop) {
		for (final TimeSegment segment : times) {
			if (segment.startRailProgress >= stop - 1E-6) {
				return segment.getTime(segment.startRailProgress);
			}
		}
		return timeAt(times, stop);
	}

	private static double topSpeedBraking(Train train) {
		double top = 0;
		for (final TimeSegment segment : train.times) {
			top = Math.max(top, Math.max(segment.getSpeed(segment.startRailProgress), segment.getSpeed(segment.endRailProgress)));
		}
		return top * top / (2 * train.deceleration);
	}

	private static double last(List<TimeSegment> times) {
		return times.get(times.size() - 1).endRailProgress;
	}

	private static TimeSegment segmentAt(List<TimeSegment> times, double position) {
		if (position <= times.get(0).startRailProgress) {
			return times.get(0);
		}
		final int index = TimeSegment.indexAt(times, position);
		return index < 0 ? times.get(times.size() - 1) : times.get(index);
	}

	private static double timeAt(List<TimeSegment> times, double position) {
		final TimeSegment segment = segmentAt(times, position);
		return segment.getTime(Math.max(segment.startRailProgress, Math.min(segment.endRailProgress, position)));
	}

	private static double speedAt(List<TimeSegment> times, double position) {
		final TimeSegment segment = segmentAt(times, position);
		return segment.getSpeed(Math.max(segment.startRailProgress, Math.min(segment.endRailProgress, position)));
	}

	/**
	 * A train as the check needs it: its planned times along the path, its length, the gap it keeps behind it, and how hard
	 * it brakes.
	 */
	public static final class Train {

		public final List<TimeSegment> times;
		public final double length;
		public final double gap;
		/**
		 * In blocks per tick².
		 */
		public final double deceleration;

		public Train(List<TimeSegment> times, double length, double gap, double deceleration) {
			this.times = times;
			this.length = length;
			this.gap = gap;
			this.deceleration = deceleration <= 0 ? transitsim.motion.MotionConstants.ACCELERATION_DEFAULT : deceleration;
		}
	}

	/**
	 * A section, from and to how far along the path, and the least time between two trains through it.
	 */
	public static final class Gap {

		public final double start;
		public final double end;
		public final long millis;
		/**
		 * Whether trains claim it with a direction, as a stretch run both ways, rather than as a block section.
		 */
		public final boolean singleTrack;

		public Gap(double start, double end, long millis, boolean singleTrack) {
			this.start = start;
			this.end = end;
			this.millis = millis;
			this.singleTrack = singleTrack;
		}
	}
}
