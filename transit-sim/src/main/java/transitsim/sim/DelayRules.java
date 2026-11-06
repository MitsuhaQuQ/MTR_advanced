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
