package transitsim.sim;

import transitsim.plan.Contingencies;
import transitsim.plan.Contingency;

/**
 * Which of a depot's {@link Contingency} plans a train running late or stuck falls under, or whether it is only reported
 * (D2). Nothing is done about a late train by itself: what is done is what a plan says, and a plan waiting for a
 * dispatcher, or no plan at all, only reports the train. Both hosts go by this one copy, as with {@link FleetRules}.
 */
public final class DelayRules {

	/**
	 * What reports a train when no plan of the depot is for its route: as a new plan has it.
	 */
	public static final Contingency DEFAULT = new Contingency();

	private DelayRules() {
	}

	/**
	 * @param trainId      the train
	 * @param routeId      the route it runs now
	 * @param lateMillis   how late it is ({@link TrainService#getLateMillis})
	 * @param waitingMillis how long it has waited for its way on, or 0
	 * @param deadlocked   whether it is in a circle of trains waiting on each other
	 */
	public static Outcome evaluate(Contingencies contingencies, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked) {
		return evaluate(contingencies == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(contingencies), trainId, routeId, lateMillis, waitingMillis, deadlocked);
	}

	/**
	 * As {@link #evaluate(Contingencies, long, long, long, long, boolean)}, with the plans of several rail systems that
	 * came to be joined, taken in the order given: what is set for the train in any of them first, then the plans of each
	 * in turn, so none of them is lost when the rails join them.
	 */
	public static Outcome evaluate(java.util.List<Contingencies> systems, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked) {
		// What a dispatcher set for this train holds whatever it is
		for (int i = 0; i < systems.size(); i++) {
			final Contingency adhoc = systems.get(i).adhoc.get(trainId);
			if (adhoc != null) {
				return new Outcome(adhoc, DEFAULT.getReason(lateMillis, waitingMillis, deadlocked), null, 0, i);
			}
		}
		Contingency.Reason waitingReason = null;
		long waitingPlan = 0;
		int waitingSystem = -1;
		boolean anyForRoute = false;
		for (int i = 0; i < systems.size(); i++) {
			final Contingencies contingencies = systems.get(i);
			for (final Contingency plan : contingencies.plans.values()) {
				if (!plan.appliesTo(routeId)) {
					continue;
				}
				anyForRoute = true;
				final Contingency.Reason reason = plan.getReason(lateMillis, waitingMillis, deadlocked);
				if (reason == null) {
					continue;
				}
				if (!plan.manual || contingencies.active.contains(plan.id)) {
					return new Outcome(plan, reason, null, 0, i);
				}
				if (waitingReason == null) {
					waitingReason = reason;
					waitingPlan = plan.id;
					waitingSystem = i;
				}
			}
		}
		if (waitingReason != null) {
			return new Outcome(null, waitingReason, waitingReason, waitingPlan, waitingSystem);
		}
		final Contingency.Reason reason = anyForRoute ? null : DEFAULT.getReason(lateMillis, waitingMillis, deadlocked);
		return new Outcome(null, reason, reason, 0, -1);
	}

	/**
	 * A train passing stations to catch up keeps passing them until it is less late than this (D3), rather than stopping
	 * again as soon as it is under what started the plan, and passing again at the next stop.
	 */
	public static final long KEEP_SKIPPING_MILLIS = 60000;

	/**
	 * The platforms a train is to pass without stopping now (D3): those of the stations its plan, or what a dispatcher set
	 * for it, passes. One passing them already goes on while it is a minute late or more and the same plan would still be
	 * on for it were it as late as before, so a plan turned off or a setting taken away stops it at once.
	 *
	 * @param outcome           what {@link #evaluate(java.util.List, long, long, long, long, boolean)} gave for the train
	 * @param skipping          whether it passes stations now
	 * @param stationPlatforms  the platforms of a station
	 * @return the platforms, or none
	 */
	public static java.util.Set<Long> getSkipPlatforms(java.util.List<Contingencies> systems, Outcome outcome, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked, boolean skipping, java.util.function.LongFunction<? extends java.util.Collection<Long>> stationPlatforms) {
		Contingency contingency = outcome.contingency;
		if ((contingency == null || contingency.skipStationIds.isEmpty()) && skipping && lateMillis >= KEEP_SKIPPING_MILLIS) {
			final Contingency kept = evaluate(systems, trainId, routeId, Long.MAX_VALUE / 4, waitingMillis, deadlocked).contingency;
			if (kept != null && !kept.skipStationIds.isEmpty()) {
				contingency = kept;
			}
		}
		if (contingency == null || contingency.skipStationIds.isEmpty()) {
			return java.util.Collections.emptySet();
		}
		final java.util.Set<Long> platformIds = new java.util.TreeSet<>();
		for (final long stationId : contingency.skipStationIds) {
			final java.util.Collection<Long> platforms = stationPlatforms.apply(stationId);
			if (platforms != null) {
				platformIds.addAll(platforms);
			}
		}
		return platformIds;
	}

	/**
	 * Where a train is to turn back early now (D4), from its plan or what a dispatcher set for it, as for passing stations
	 * ({@link #getSkipPlatforms}): one turned back or on its way there goes on while it is a minute late or more and the same
	 * plan would still be on for it.
	 *
	 * @param turning whether it is told to turn back now
	 * @return {mode, after}, with the platforms of a given station after them; or {0, 0} not to
	 */
	public static long[] getTurnBack(java.util.List<Contingencies> systems, Outcome outcome, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked, boolean turning, java.util.function.LongFunction<? extends java.util.Collection<Long>> stationPlatforms) {
		Contingency contingency = outcome.contingency;
		if ((contingency == null || contingency.turn == Contingency.TURN_NONE) && turning && lateMillis >= KEEP_SKIPPING_MILLIS) {
			final Contingency kept = evaluate(systems, trainId, routeId, Long.MAX_VALUE / 4, waitingMillis, deadlocked).contingency;
			if (kept != null && kept.turn != Contingency.TURN_NONE) {
				contingency = kept;
			}
		}
		if (contingency == null || contingency.turn == Contingency.TURN_NONE) {
			return new long[]{0, 0};
		}
		final java.util.Collection<Long> platforms = contingency.turn == Contingency.TURN_STATION ? stationPlatforms.apply(contingency.turnStationId) : null;
		final long[] values = new long[2 + (platforms == null ? 0 : platforms.size())];
		values[0] = contingency.turn;
		values[1] = contingency.afterTurn;
		if (platforms != null) {
			int i = 2;
			for (final long platformId : new java.util.TreeSet<>(platforms)) {
				values[i++] = platformId;
			}
		}
		return values;
	}

	/**
	 * @return the plan a train falls under, or null
	 */
	public static Contingency affecting(Contingencies contingencies, long trainId, long routeId, long lateMillis, long waitingMillis, boolean deadlocked) {
		return evaluate(contingencies, trainId, routeId, lateMillis, waitingMillis, deadlocked).contingency;
	}

	public static final class Outcome {

		/**
		 * The plan the train falls under, or null.
		 */
		public final Contingency contingency;
		/**
		 * What makes it late or stuck, or null.
		 */
		public final Contingency.Reason reason;
		/**
		 * Why it is reported, with no plan on for it, or null.
		 */
		public final Contingency.Reason alarm;
		/**
		 * The plan waiting for a dispatcher that is for it, or 0.
		 */
		public final long waitingPlanId;
		/**
		 * Of the systems given, the one the plan, what is set for the train, or the plan waiting for a dispatcher is of, or -1.
		 */
		public final int systemIndex;

		private Outcome(Contingency contingency, Contingency.Reason reason, Contingency.Reason alarm, long waitingPlanId, int systemIndex) {
			this.contingency = contingency;
			this.reason = reason;
			this.alarm = alarm;
			this.waitingPlanId = waitingPlanId;
			this.systemIndex = systemIndex;
		}
	}
}
