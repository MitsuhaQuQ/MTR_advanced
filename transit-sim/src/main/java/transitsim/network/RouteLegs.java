package transitsim.network;

import java.util.*;

/**
 * A route's path made of its legs (stages document round 2): each stop can be run either way, and the way a train runs
 * a stop is all a leg after it depends on, so the best path is the best choice of ways at each stop, worked out stop by
 * stop. This gives the same cost as searching the whole route at once ({@link RailRouter}); where two ways cost the same,
 * the one run along a section's order, and then the earlier, is kept, so it is always the same.
 */
public final class RouteLegs {

	private RouteLegs() {
	}

	/**
	 * Where legs come from: found, or taken as found before.
	 */
	public interface LegSource {

		LegRouter.Leg get(LegRouter.Request request);
	}

	/**
	 * A route's path.
	 */
	public static final class Plan {

		/**
		 * The stops reached in order before there was no way, all of them if found.
		 */
		public final int reachedStops;
		public final double cost;
		/**
		 * Each section run, as {section, 1 if along its order, 1 if turned back onto it}, from the first stop on.
		 */
		public final List<long[]> steps;
		/**
		 * The step each stop is.
		 */
		public final int[] stopSteps;
		/**
		 * The legs it is made of, in order.
		 */
		public final List<LegRouter.Request> legs;
		/**
		 * A key node listed where sections don't meet, or 0.
		 */
		public final long badWaypoint;
		/**
		 * Every leg looked at, chosen or not: the plan holds while they all do.
		 */
		public final List<LegRouter.Leg> consulted;

		private Plan(int reachedStops, double cost, List<long[]> steps, int[] stopSteps, List<LegRouter.Request> legs, long badWaypoint, List<LegRouter.Leg> consulted) {
			this.reachedStops = reachedStops;
			this.cost = cost;
			this.steps = Collections.unmodifiableList(steps);
			this.stopSteps = stopSteps;
			this.legs = Collections.unmodifiableList(legs);
			this.badWaypoint = badWaypoint;
			this.consulted = Collections.unmodifiableList(consulted);
		}

		public boolean isFound() {
			return reachedStops == stopSteps.length && stopSteps.length >= 2;
		}
	}

	/**
	 * @param stopSections the section of each stop in order
	 * @param waypoints    the key nodes after each stop, before the next
	 * @param line         the route's line, or null for a connecting line
	 */
	/**
	 * A way out of a depot to a platform or back from one (optimisation round 4, step 3): the train leaves and arrives the
	 * ways given, as the line runs at the platform, never turning back where it starts; a way not given (at a siding) may
	 * be either. It keeps to no line's track.
	 *
	 * @param fromAlong the way it leaves the first section, along its order or against it, or null for either
	 * @param toAlong   the way it arrives at the last, or null for either
	 */
	public static Plan planBetween(long fromSection, Boolean fromAlong, long toSection, Boolean toAlong, LegSource legSource) {
		final List<LegRouter.Leg> consulted = new ArrayList<>();
		LegRouter.Leg best = null;
		int bestFrom = 0;
		int bestTo = 0;
		double bestCost = Double.POSITIVE_INFINITY;
		for (final int fromWay : fromAlong == null ? new int[]{1, 0} : new int[]{fromAlong ? 1 : 0}) {
			final LegRouter.Leg leg = legSource.get(new LegRouter.Request(fromSection, fromWay == 1, toSection, new long[0], null, false));
			consulted.add(leg);
			for (final int toWay : toAlong == null ? new int[]{1, 0} : new int[]{toAlong ? 1 : 0}) {
				final double cost = leg.getCost(toWay == 1);
				if (cost < bestCost) {
					bestCost = cost;
					best = leg;
					bestFrom = fromWay;
					bestTo = toWay;
				}
			}
		}
		if (best == null) {
			return new Plan(1, Double.POSITIVE_INFINITY, new ArrayList<>(), new int[2], new ArrayList<>(), 0, consulted);
		}
		final List<long[]> steps = new ArrayList<>();
		steps.add(new long[]{fromSection, bestFrom, 0});
		steps.addAll(best.getSteps(bestTo == 1));
		final List<LegRouter.Request> requests = new ArrayList<>();
		requests.add(best.request);
		return new Plan(2, bestCost, steps, new int[]{0, steps.size() - 1}, requests, 0, consulted);
	}

	public static Plan plan(List<Long> stopSections, List<long[]> waypoints, String line, LegSource legSource) {
		final int stopCount = stopSections.size();
		final List<LegRouter.Leg> consulted = new ArrayList<>();
		if (stopCount < 2) {
			return new Plan(0, 0, new ArrayList<>(), new int[stopCount], new ArrayList<>(), 0, consulted);
		}
		// For each stop and way: the best cost to it, the way at the stop before, and the leg
		final double[][] best = new double[stopCount][2];
		final int[][] previousWay = new int[stopCount][2];
		final LegRouter.Leg[][] legs = new LegRouter.Leg[stopCount][2];
		best[0][0] = 0;
		best[0][1] = 0;
		int reached = 1;
		long badWaypoint = 0;
		for (int i = 0; i + 1 < stopCount; i++) {
			best[i + 1][0] = Double.POSITIVE_INFINITY;
			best[i + 1][1] = Double.POSITIVE_INFINITY;
			// Along first, so ties keep along
			for (final int way : new int[]{1, 0}) {
				if (Double.isInfinite(best[i][way])) {
					continue;
				}
				final long[] stopWaypoints = i < waypoints.size() && waypoints.get(i) != null ? waypoints.get(i) : new long[0];
				final LegRouter.Leg leg = legSource.get(new LegRouter.Request(stopSections.get(i), way == 1, stopSections.get(i + 1), stopWaypoints, line));
				consulted.add(leg);
				if (leg.badWaypoint != 0 && badWaypoint == 0) {
					badWaypoint = leg.badWaypoint;
				}
				for (final int nextWay : new int[]{1, 0}) {
					final double cost = best[i][way] + leg.getCost(nextWay == 1);
					if (cost < best[i + 1][nextWay]) {
						best[i + 1][nextWay] = cost;
						previousWay[i + 1][nextWay] = way;
						legs[i + 1][nextWay] = leg;
					}
				}
			}
			if (Double.isInfinite(best[i + 1][0]) && Double.isInfinite(best[i + 1][1])) {
				return new Plan(reached, Double.POSITIVE_INFINITY, new ArrayList<>(), new int[stopCount], new ArrayList<>(), badWaypoint, consulted);
			}
			reached++;
		}

		// The ways back from the last stop
		final int[] ways = new int[stopCount];
		ways[stopCount - 1] = best[stopCount - 1][1] <= best[stopCount - 1][0] ? 1 : 0;
		for (int i = stopCount - 1; i > 0; i--) {
			ways[i - 1] = previousWay[i][ways[i]];
		}
		final List<long[]> steps = new ArrayList<>();
		final int[] stopSteps = new int[stopCount];
		final List<LegRouter.Request> requests = new ArrayList<>();
		steps.add(new long[]{stopSections.get(0), ways[0], 0});
		stopSteps[0] = 0;
		for (int i = 1; i < stopCount; i++) {
			final LegRouter.Leg leg = legs[i][ways[i]];
			requests.add(leg.request);
			steps.addAll(leg.getSteps(ways[i] == 1));
			stopSteps[i] = steps.size() - 1;
		}
		return new Plan(stopCount, best[stopCount - 1][ways[stopCount - 1]], steps, stopSteps, requests, badWaypoint, consulted);
	}
}
