package transitsim.planning;

import transitsim.plan.PlanTrack;
import transitsim.plan.TimeSegment;

import java.util.*;

/**
 * How long trains take between services at a station, as the planner reads them ({@link DutyGenerator.PlanInputs}
 * turn times): round a turn back rail beyond a route's last platform and back to it, where a train waits for its next
 * service (混合折返), and from each route's last platform to each other route's first platform at the same station.
 * Moved out of the game's depot path building (2026-11-13, step 2b of splitting the depot); the host finds the ways and
 * times them, the rules of what is kept and how the times are read are here.
 */
public final class TurnTimes {

	private TurnTimes() {
	}

	/**
	 * What the host finds and measures for the planner.
	 *
	 * @param <R> the host's route
	 * @param <P> the host's way, passed back for logging only
	 */
	public interface Host<R, P> {

		/**
		 * @return the route's last platform, or 0 for a route with no platforms
		 */
		long lastPlatform(R route);

		/**
		 * @return the route's first platform, or 0 for a route with no platforms
		 */
		long firstPlatform(R route);

		/**
		 * @return the station of a platform, or 0 for none
		 */
		long station(long platform);

		/**
		 * @return the way round a turn back rail beyond the platform and back to it, after the route ends there, or null
		 */
		Way<P> holdWay(R before, long platform);

		/**
		 * @return the way from where one route ends to where the other starts, or null for none
		 */
		Way<P> changeWay(R before, R after, long from, long to);

		/**
		 * Told of each time kept, for logging.
		 */
		default void kept(R before, R after, long from, long to, Way<P> way, long[] slowest) {
		}

		/**
		 * Told of each way going too far off to be a change of platform, for logging.
		 */
		default void tooFar(long from, long to, Way<P> way) {
		}
	}

	/**
	 * A way between two services as measured.
	 */
	public static final class Way<P> {

		public final P path;
		/**
		 * For each vehicle running either route, in the host's order: how long it runs in, to where it turns back, and
		 * out again ({@link #legs}).
		 */
		public final List<long[]> legsByVehicle;
		/**
		 * How far the way goes from the platform it starts at, in blocks.
		 */
		public final double farthest;
		/**
		 * How many trains can wait on the ways round, for a way round a turn back rail.
		 */
		public final long places;
		/**
		 * The nodes of the turn back rail the way runs round, or null.
		 */
		public final long[] turnBackRail;

		public Way(P path, List<long[]> legsByVehicle, double farthest, long places, long[] turnBackRail) {
			this.path = path;
			this.legsByVehicle = legsByVehicle;
			this.farthest = farthest;
			this.places = places;
			this.turnBackRail = turnBackRail;
		}
	}

	/**
	 * @param routes   the routes planned together
	 * @param maxReach how far off a change of platform may go, in blocks; going further it runs along the line and back
	 *                 on track other trains run the other way (2026-11-09) and isn't one
	 * @return from platform → to platform → milliseconds, the from platform itself for the way round its turn back rail
	 * with {@link DutyGenerator#HOLD_IN_KEY}, {@link DutyGenerator#HOLD_COUNT_KEY} and {@link DutyGenerator#HOLD_RAIL_KEY};
	 * a pair with no way between is left out, which the planner takes as no way
	 */
	public static <R, P> Map<Long, Map<Long, Long>> of(List<R> routes, Host<R, P> host, double maxReach) {
		final Map<Long, Map<Long, Long>> turnTimes = new TreeMap<>();
		for (final R before : routes) {
			final long from = host.lastPlatform(before);
			final long station = from == 0 ? 0 : host.station(from);
			if (station == 0) {
				continue;
			}
			// Only once for each platform
			if (!has(turnTimes, from, from)) {
				final Way<P> hold = host.holdWay(before, from);
				if (hold != null) {
					// In to the turn back rail and back out, each leg as measured (round 4): the slowest vehicle's
					final long[] slowest = slowest(hold.legsByVehicle);
					final Map<Long, Long> fromHere = turnTimes.computeIfAbsent(from, key -> new TreeMap<>());
					fromHere.put(from, slowest[0] + slowest[1]);
					fromHere.put(DutyGenerator.HOLD_IN_KEY, slowest[0]);
					// How many trains can wait there at once (运用补完 step 4)
					if (hold.places > 1) {
						fromHere.put(DutyGenerator.HOLD_COUNT_KEY, hold.places);
					}
					// Which turn back rail: platforms turning round on the same one share it (8号线, 2026-11-12)
					if (hold.turnBackRail != null) {
						fromHere.put(DutyGenerator.HOLD_RAIL_KEY, railKey(hold.turnBackRail[0], hold.turnBackRail[1]));
					}
					host.kept(before, before, from, from, hold, slowest);
				}
			}
			for (final R after : routes) {
				final long to = host.firstPlatform(after);
				if (to == 0 || to == from || host.station(to) != station || has(turnTimes, from, to)) {
					continue;
				}
				final Way<P> change = host.changeWay(before, after, from, to);
				if (change == null) {
					continue;
				}
				if (change.farthest > maxReach) {
					host.tooFar(from, to, change);
					continue;
				}
				// The legs in and out, without standing where it turns back: changing ends there is the network's least
				// time, which the plan adds (round 4); timed for the slowest of the vehicles running either route
				final long[] slowest = slowest(change.legsByVehicle);
				turnTimes.computeIfAbsent(from, key -> new TreeMap<>()).put(to, slowest[0] + slowest[1]);
				host.kept(before, after, from, to, change, slowest);
			}
		}
		return turnTimes;
	}

	private static boolean has(Map<Long, Map<Long, Long>> turnTimes, long from, long to) {
		final Map<Long, Long> fromHere = turnTimes.get(from);
		return fromHere != null && fromHere.containsKey(to);
	}

	/**
	 * @return the legs with the longest sum, the first of them when as long, or none
	 */
	public static long[] slowest(List<long[]> legsByVehicle) {
		long[] slowest = {0, 0};
		for (final long[] legs : legsByVehicle) {
			if (legs[0] + legs[1] > slowest[0] + slowest[1]) {
				slowest = legs;
			}
		}
		return slowest;
	}

	/**
	 * @return the same key for a turn back rail either way round, never 0
	 */
	public static long railKey(long node1, long node2) {
		final long rail = Math.min(node1, node2) * 0x9E3779B97F4A7C15L ^ Math.max(node1, node2);
		return rail == 0 ? 1 : rail;
	}

	/**
	 * The way round cut where the train turns back (optimisation round 4): how long it runs in, from setting off to
	 * standing where it turns back, and out, from setting off again to arriving; standing there is left out. A way with
	 * no turn back is all in.
	 *
	 * @param way          the way, as timed
	 * @param timeSegments the way's time segments, in ticks
	 * @param trainLength  the train's length, for where it stops on a platform it turns back at
	 * @return the in and out legs in milliseconds
	 */
	public static <T extends PlanTrack> long[] legs(List<T> way, List<TimeSegment> timeSegments, double trainLength, double millisPerTick) {
		if (timeSegments.isEmpty()) {
			return new long[]{0, 0};
		}
		final double endTicks = timeSegments.get(timeSegments.size() - 1).endTime;
		double reversal = -1;
		double distance = 0;
		for (int i = 0; i + 1 < way.size(); i++) {
			distance += way.get(i).getTrackLength();
			if (way.get(i).isOppositeOf(way.get(i + 1))) {
				reversal = distance - (way.get(i).getDwellTime() > 0 ? way.get(i).getStopOffset(trainLength) : 0);
				break;
			}
		}
		if (reversal < 0) {
			return new long[]{(long) (endTicks * millisPerTick), 0};
		}
		double inTicks = 0;
		double outStartTicks = endTicks;
		for (final TimeSegment timeSegment : timeSegments) {
			if (timeSegment.endRailProgress <= reversal + 1E-6) {
				inTicks = Math.max(inTicks, timeSegment.endTime);
			} else if (timeSegment.startRailProgress > reversal + 1E-6) {
				outStartTicks = Math.min(outStartTicks, timeSegment.getTime(timeSegment.startRailProgress));
			}
		}
		return new long[]{(long) (inTicks * millisPerTick), (long) (Math.max(0, endTicks - outStartTicks) * millisPerTick)};
	}
}
