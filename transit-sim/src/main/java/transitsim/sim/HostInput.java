package transitsim.sim;

import transitsim.plan.PlanRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
	/**
	 * Has the train pass these platforms without stopping from its next stop on, or none to stop as planned again (D3).
	 */
	public static final class Skip extends HostInput {

		public final long trainId;
		public final long[] platformIds;

		public Skip(long time, long sequence, long trainId, long[] platformIds) {
			super(time, sequence);
			this.trainId = trainId;
			this.platformIds = platformIds.clone();
		}
	}

	/**
	 * Has the train turn back early (D4), or {@code mode} 0 not to: see {@link TrainService#setTurnBack}.
	 */
	public static final class TurnBack extends HostInput {

		public final long trainId;
		public final int mode;
		public final long[] platformIds;
		public final int after;

		public TurnBack(long time, long sequence, long trainId, int mode, long[] platformIds, int after) {
			super(time, sequence);
			this.trainId = trainId;
			this.mode = mode;
			this.platformIds = platformIds.clone();
			this.after = after;
		}
	}

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
	 * The rails a train the simulation doesn't move has now (G0), such as one driven by hand or of a depot still on the old
	 * rules, in place of the ones it had before. No rails means it has gone. A train taken over is reported this way too.
	 */
	public static final class ExternalTrain extends HostInput {

		public final long trainId;
		public final Set<UUID> rails;

		public ExternalTrain(long time, long sequence, long trainId, Set<UUID> rails) {
			super(time, sequence);
			this.trainId = trainId;
			this.rails = Collections.unmodifiableSet(new HashSet<>(rails));
		}
	}

	/**
	 * The runs a train the simulation doesn't move holds with their directions now (G1), such as one of a depot still run
	 * by the game, in place of the ones it held before. Nothing means it holds none. The simulation's trains aren't given
	 * runs against them.
	 */
	public static final class ExternalRun extends HostInput {

		public final long trainId;
		public final List<RunReservations.Move> moves;

		public ExternalRun(long time, long sequence, long trainId, List<RunReservations.Move> moves) {
			super(time, sequence);
			this.trainId = trainId;
			this.moves = Collections.unmodifiableList(new ArrayList<>(moves));
		}
	}

	/**
	 * The host takes a train over (3.1, rule 4), as a player starts driving it: the simulation stops moving it and keeps
	 * the rails it has taken as an external train's, until the host reports them with {@link ExternalTrain}.
	 */
	public static final class TakeOver extends HostInput {

		public final long trainId;

		public TakeOver(long time, long sequence, long trainId) {
			super(time, sequence);
			this.trainId = trainId;
		}
	}

	/**
	 * The host gives a train it took over back (3.1, rule 4), where it is now along its path and at its speed. The
	 * simulation works out its run from there and asks for its runs again.
	 */
	public static final class Release extends HostInput {

		public final long trainId;
		public final double railProgress;
		public final float speed;

		public Release(long time, long sequence, long trainId, double railProgress, float speed) {
			super(time, sequence);
			this.trainId = trainId;
			this.railProgress = railProgress;
			this.speed = speed;
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
