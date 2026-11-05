package transitsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A weekly plan. It repeats exactly, so the plan for any time can be read from the week offset alone.
 */
public final class OperationPlan {

	public static final int NONE = -1;

	public final List<Duty> duties;
	public final List<Issue> issues;
	public final int peakTrains;

	OperationPlan(List<Duty> duties, List<Issue> issues, int peakTrains) {
		this.duties = Collections.unmodifiableList(duties);
		this.issues = Collections.unmodifiableList(issues);
		this.peakTrains = peakTrains;
	}

	public boolean hasIssue(IssueType type) {
		return issues.stream().anyMatch(issue -> issue.type == type);
	}

	public String describe() {
		final StringBuilder builder = new StringBuilder();
		duties.forEach(duty -> {
			builder.append(duty.index).append(" depot=").append(duty.depotId).append(" prev=").append(duty.previousDuty).append(" next=").append(duty.nextDuty);
			duty.trips.forEach(trip -> builder.append(' ').append(trip.key()));
			builder.append('\n');
		});
		issues.forEach(issue -> builder.append(issue.type).append(' ').append(issue.detail).append('\n'));
		return builder.toString();
	}

	public enum IssueType {
		/**
		 * A service with a train has no train available, so that trip is not run.
		 */
		NO_TRAIN,
		/**
		 * Trains arrive at a platform before a trip leaves it, but too late to turn round for it, so the trip needs another
		 * train; detail: the platform, the time they had and the time they needed, in milliseconds.
		 */
		SHORT_TURNAROUND,
		/**
		 * A blank service was not picked up by a through trip, so it is not run. This is normal.
		 */
		UNUSED_BLANK,
		/**
		 * The services of a through trip are missing, or too close together to reach each other.
		 */
		THROUGH_CONFLICT,
		PLATFORM_CONFLICT,
		/**
		 * The depots lose trains from week to week, so the plan never settles into a repeating week.
		 */
		NOT_PERIODIC
	}

	public static final class Issue {

		public final IssueType type;
		public final String detail;

		Issue(IssueType type, String detail) {
			this.type = type;
			this.detail = detail;
		}
	}

	/**
	 * A timed stop of a trip, with times as week offsets.
	 */
	public static final class TripStop {

		public final long serviceId;
		public final long platformId;
		public final long arrivalMillis;
		public final long departureMillis;
		public final boolean pass;

		TripStop(long serviceId, long platformId, long arrivalMillis, long departureMillis, boolean pass) {
			this.serviceId = serviceId;
			this.platformId = platformId;
			this.arrivalMillis = arrivalMillis;
			this.departureMillis = departureMillis;
			this.pass = pass;
		}
	}

	/**
	 * One run of a service or through trip on one day. Each lap of a circular line is its own trip.
	 */
	public static final class Trip {

		public final long sourceId;
		public final List<TripStop> stops;

		Trip(long sourceId, List<TripStop> stops) {
			this.sourceId = sourceId;
			this.stops = Collections.unmodifiableList(new ArrayList<>(stops));
		}

		public long startMillis() {
			return stops.get(0).arrivalMillis;
		}

		public long endMillis() {
			return stops.get(stops.size() - 1).departureMillis;
		}

		public long firstPlatform() {
			return stops.get(0).platformId;
		}

		public long lastPlatform() {
			return stops.get(stops.size() - 1).platformId;
		}

		public String key() {
			return sourceId + "@" + startMillis();
		}
	}

	/**
	 * One train's sequence of trips between leaving a depot and returning to it. A duty that is still out at
	 * the end of the week continues into {@link #nextDuty} after the week wraps.
	 */
	public static final class Duty {

		public final int index;
		public final long depotId;
		public final List<Trip> trips;
		public final int previousDuty;
		public final int nextDuty;
		public final List<Phase> phases;

		Duty(int index, long depotId, List<Trip> trips, int previousDuty, int nextDuty, List<Phase> phases) {
			this.index = index;
			this.depotId = depotId;
			this.trips = Collections.unmodifiableList(new ArrayList<>(trips));
			this.previousDuty = previousDuty;
			this.nextDuty = nextDuty;
			this.phases = Collections.unmodifiableList(new ArrayList<>(phases));
		}
	}

	public enum PhaseType {
		LEAVE_DEPOT, STOP, RUN, LAYOVER, CONNECT, RETURN_TO_DEPOT
	}

	/**
	 * Part of a duty, ending at {@link #endMillis}. {@code fromPlatform} is the only platform while stationary.
	 */
	public static final class Phase {

		public final PhaseType type;
		public final long startMillis;
		public final long endMillis;
		public final long fromPlatform;
		public final long toPlatform;

		Phase(PhaseType type, long startMillis, long endMillis, long fromPlatform, long toPlatform) {
			this.type = type;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
			this.fromPlatform = fromPlatform;
			this.toPlatform = toPlatform;
		}

		public boolean isAtPlatform() {
			return type == PhaseType.STOP || type == PhaseType.LAYOVER;
		}
	}
}
