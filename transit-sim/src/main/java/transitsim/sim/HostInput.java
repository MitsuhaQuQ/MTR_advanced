package transitsim.sim;

import transitsim.plan.PlanRoute;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongFunction;

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
	 * A new version of the plan, made by changing the timetables of the simulation's routes in place and counting up their
	 * revisions. Trains running a service keep the version they started it with (5.1, rule 3). The change is given the
	 * routes of the simulation it is applied to, so a fork replaying it changes its own.
	 */
	public static final class PublishPlan extends HostInput {

		public final Consumer<LongFunction<? extends PlanRoute>> change;

		public PublishPlan(long time, long sequence, Consumer<LongFunction<? extends PlanRoute>> change) {
			super(time, sequence);
			this.change = change;
		}
	}

	/**
	 * A new version of the network without these tracks (5.1, rule 5). A train whose path still has all its tracks runs on;
	 * one with a removed track ahead stops short of it, and is reported; one standing on a removed track is taken off, as
	 * the host has to put it somewhere else. A siding whose path has a removed track sends no more trains.
	 */
	public static final class PublishNetwork extends HostInput {

		public final Set<UUID> removedTracks;

		public PublishNetwork(long time, long sequence, Set<UUID> removedTracks) {
			super(time, sequence);
			this.removedTracks = Collections.unmodifiableSet(new HashSet<>(removedTracks));
		}
	}
}
