package transitsim.sim;

import java.util.UUID;

/**
 * An input from the host, with its time and the sequence the host gave it, so inputs of the same time take effect in the
 * order the host had them (contracts document 2.1, rule 5).
 */
public abstract class HostInput implements Comparable<HostInput> {

	public final long time;
	public final long sequence;

	private HostInput(long time, long sequence) {
		this.time = time;
		this.sequence = sequence;
	}

	@Override
	public int compareTo(HostInput other) {
		final int byTime = Long.compare(time, other.time);
		return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
	}

	/**
	 * Keeps a train at its next stop with its doors open until it is resumed, for the reason given.
	 */
	public static final class Hold extends HostInput {

		public final long trainId;
		public final String reason;

		public Hold(long time, long sequence, long trainId, String reason) {
			super(time, sequence);
			this.trainId = trainId;
			this.reason = reason;
		}
	}

	public static final class Resume extends HostInput {

		public final long trainId;

		public Resume(long time, long sequence, long trainId) {
			super(time, sequence);
			this.trainId = trainId;
		}
	}

	/**
	 * The train is gone, deleted or cleared away, and gives up everything it held (3.1, rule 4).
	 */
	public static final class Remove extends HostInput {

		public final long trainId;

		public Remove(long time, long sequence, long trainId) {
			super(time, sequence);
			this.trainId = trainId;
		}
	}

	/**
	 * Something the simulation doesn't move, such as a train driven by hand, stands on a rail until a time.
	 */
	public static final class ExternalOccupancy extends HostInput {

		public final UUID rail;
		public final long untilTime;

		public ExternalOccupancy(long time, long sequence, UUID rail, long untilTime) {
			super(time, sequence);
			this.rail = rail;
			this.untilTime = untilTime;
		}
	}

	/**
	 * A new version of the plan, made by changing the host's timetables in place and counting up their revisions. Trains
	 * running a service keep the version they started it with (5.1, rule 3).
	 */
	public static final class PublishPlan extends HostInput {

		public final Runnable change;

		public PublishPlan(long time, long sequence, Runnable change) {
			super(time, sequence);
			this.change = change;
		}
	}
}
