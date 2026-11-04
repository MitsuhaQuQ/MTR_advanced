package mtr.advanced;

import transitsim.sim.HostInput;
import transitsim.sim.RunReservations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * The trains the component's simulator doesn't move, as it takes them in (G0, contracts document 3.1 rule 4): trains
 * driven by hand and trains of depots still on the old rules. Each tick the game's rails taken are looked at, and a train
 * is reported only when its rails change, or with none once it has gone. G1 puts these into the running simulator.
 */
public final class SimInputs {

	/**
	 * The rails last reported for each train.
	 */
	private final Map<Long, Set<UUID>> reported = new HashMap<>();
	/**
	 * The runs last reported for each train holding some.
	 */
	private final Map<Long, List<RunReservations.Move>> reportedRuns = new HashMap<>();
	private long sequence;

	/**
	 * Forgets what was reported, for a new simulator that knows nothing yet.
	 */
	public void reset() {
		reported.clear();
		reportedRuns.clear();
	}

	/**
	 * @return the next sequence number, for other inputs of the host so they don't share one with these
	 */
	public long nextSequence() {
		return sequence++;
	}

	/**
	 * @param time       the time of the tick
	 * @param occupancy  the rails taken this tick, each with the train that has it, as the game's train positions have them
	 * @param isExternal whether the simulator leaves a train alone
	 * @return the trains whose rails changed since the last tick, in the order of their IDs
	 */
	public List<HostInput.ExternalTrain> update(long time, Map<UUID, Long> occupancy, LongPredicate isExternal) {
		final Map<Long, Set<UUID>> current = new TreeMap<>();
		occupancy.forEach((rail, trainId) -> {
			if (trainId != null && isExternal.test(trainId)) {
				current.computeIfAbsent(trainId, key -> new HashSet<>()).add(rail);
			}
		});

		final Map<Long, Set<UUID>> changed = new TreeMap<>();
		current.forEach((trainId, rails) -> {
			if (!rails.equals(reported.get(trainId))) {
				changed.put(trainId, rails);
			}
		});
		for (final Iterator<Long> iterator = reported.keySet().iterator(); iterator.hasNext(); ) {
			final long trainId = iterator.next();
			if (!current.containsKey(trainId)) {
				changed.put(trainId, Collections.emptySet());
				iterator.remove();
			}
		}

		final List<HostInput.ExternalTrain> inputs = new ArrayList<>();
		changed.forEach((trainId, rails) -> {
			inputs.add(new HostInput.ExternalTrain(time, sequence++, trainId, rails));
			if (!rails.isEmpty()) {
				reported.put(trainId, rails);
			}
		});
		return inputs;
	}

	/**
	 * @param time the time of the tick
	 * @param runs the runs each train the simulator leaves alone holds now, as the game's run reservations have them
	 * @return the trains whose runs changed since the last tick, with none for a train that holds none any more, in the
	 * order of their IDs
	 */
	public List<HostInput.ExternalRun> updateRuns(long time, Map<Long, List<RunReservations.Move>> runs) {
		final Map<Long, List<RunReservations.Move>> changed = new TreeMap<>();
		runs.forEach((trainId, moves) -> {
			if (!moves.isEmpty() && !moves.equals(reportedRuns.get(trainId))) {
				changed.put(trainId, moves);
			}
		});
		for (final Iterator<Long> iterator = reportedRuns.keySet().iterator(); iterator.hasNext(); ) {
			final long trainId = iterator.next();
			final List<RunReservations.Move> moves = runs.get(trainId);
			if (moves == null || moves.isEmpty()) {
				changed.put(trainId, Collections.emptyList());
				iterator.remove();
			}
		}

		final List<HostInput.ExternalRun> inputs = new ArrayList<>();
		changed.forEach((trainId, moves) -> {
			inputs.add(new HostInput.ExternalRun(time, sequence++, trainId, moves));
			if (!moves.isEmpty()) {
				reportedRuns.put(trainId, moves);
			}
		});
		return inputs;
	}
}
