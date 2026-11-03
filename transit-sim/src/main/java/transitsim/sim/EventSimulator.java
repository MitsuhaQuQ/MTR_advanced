package transitsim.sim;

import transitsim.motion.MotionConstants;
import transitsim.motion.MotionEvent;
import transitsim.motion.RunSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The simulator of the contracts document (section 9) over a {@link TickRunner} (S4c). Time steps on the runner's ticks,
 * which start from its start time, so where the host stops a step doesn't change anything. Ticks in which nothing can
 * change are skipped (S4c2), which gives the same results as running every one. Inputs take effect before the first tick
 * at or after their time; events come out as the trains have them, sorted within each tick, and are never taken back, as
 * nothing is ever told ahead of its time.
 */
public final class EventSimulator<T extends RunTrack> implements Simulator {

	private final TickRunner<T> runner;
	/**
	 * Makes the simulation's start again, with its own routes and trains, for forks and for carrying on from a snapshot.
	 */
	private final Supplier<TickRunner<T>> start;
	private final boolean skipping;
	private final boolean predicted;
	private final PriorityQueue<HostInput> inputs = new PriorityQueue<>();
	/**
	 * The inputs applied so far, in their order.
	 */
	private final List<HostInput> applied = new ArrayList<>();
	private final List<SimEvent> events = new ArrayList<>();
	private final Map<Long, Long> eventSequences = new HashMap<>();
	private final Map<Long, Boolean> doorsOpen = new TreeMap<>();
	private int motionEventsRead;
	private long cursor;
	/**
	 * Whether it runs for as long as the game does, keeping nothing it would only need for forks and snapshots (G1).
	 */
	private boolean live;

	/**
	 * @param start makes the start of the simulation, each time with routes and trains of its own
	 */
	public EventSimulator(Supplier<TickRunner<T>> start) {
		this(start, true);
	}

	/**
	 * @param skipping false to run every tick, for comparing with skipping
	 */
	public EventSimulator(Supplier<TickRunner<T>> start, boolean skipping) {
		this(start, skipping, false);
	}

	private EventSimulator(Supplier<TickRunner<T>> start, boolean skipping, boolean predicted) {
		this.start = start;
		this.skipping = skipping;
		this.predicted = predicted;
		runner = start.get();
		runner.setSkipping(skipping);
		runner.setAfterTick(this::collectEvents);
	}

	/**
	 * How long a stop can be for trains to carry on as they were (contracts document 6.3).
	 */
	public static final long RECOVERY_MILLIS = 60000;

	/**
	 * Carries on from the trains as the game saved them (G0). After a stop of up to a minute they carry on from the record
	 * and are run on to now, events and all; after a longer one they recover where the plan has them now, and the events of
	 * the time missed don't happen.
	 *
	 * @param startAt makes the world at a time, without trains
	 */
	public static <T extends RunTrack> EventSimulator<T> resume(LongFunction<TickRunner<T>> startAt, RunRecord record, long now) {
		final boolean recover = now - record.time > RECOVERY_MILLIS;
		final long startMillis = recover ? now : record.time;
		final EventSimulator<T> simulator = new EventSimulator<>(() -> {
			final TickRunner<T> runner = startAt.apply(startMillis);
			runner.restore(record, recover);
			return runner;
		});
		simulator.advanceTo(now);
		return simulator;
	}

	/**
	 * Carries on a simulation from a snapshot, running its inputs again from the start (6.4).
	 */
	public static <T extends RunTrack> EventSimulator<T> restore(Supplier<TickRunner<T>> start, SimulationSnapshot snapshot) {
		final EventSimulator<T> simulator = new EventSimulator<>(start);
		snapshot.inputs.forEach(simulator::submit);
		simulator.advanceTo(snapshot.time);
		return simulator;
	}

	public TickRunner<T> getRunner() {
		return runner;
	}

	@Override
	public long getTime() {
		return runner.getMillis();
	}

	/**
	 * Has it run as long as the game does (G1): the inputs applied aren't kept, so it can't be forked or snapshotted, and
	 * events are kept only until {@link #forgetEvents}.
	 */
	public void setLive() {
		live = true;
		applied.clear();
	}

	/**
	 * Lets go of the events up to this cursor, once the host has handled them, and of what it kept for trains that have
	 * gone.
	 */
	public void forgetEvents(long throughCursor) {
		int count = 0;
		while (count < events.size() && events.get(count).cursor <= throughCursor) {
			count++;
		}
		events.subList(0, count).clear();
		eventSequences.keySet().removeIf(trainId -> !runner.hasTrain(trainId));
		doorsOpen.keySet().removeIf(trainId -> !runner.hasTrain(trainId));
	}

	@Override
	public void submit(HostInput input) {
		inputs.add(input);
	}

	@Override
	public long advanceTo(long time) {
		while (runner.getMillis() + MotionConstants.MILLIS_PER_TICK <= time) {
			final long tickMillis = runner.getMillis() + MotionConstants.MILLIS_PER_TICK;
			while (!inputs.isEmpty() && inputs.peek().time <= tickMillis) {
				apply(inputs.poll(), tickMillis);
			}
			// Runs on up to the tick before the next input, which is applied before its tick
			final long untilMillis = inputs.isEmpty() ? time : Math.min(time, Math.max(tickMillis, inputs.peek().time - 1));
			runner.runUntil(untilMillis);
		}
		return runner.getMillis();
	}

	@Override
	public TrainState query(long trainId) {
		final TickRunner<T>.HeadlessTrain train = runner.getTrain(trainId);
		if (train == null) {
			return null;
		}
		return new TrainState(train.id, train.getHeadRail(), train.getRailProgress(), train.getSpeed(), train.isOnRoute(), train.getSpeed() == 0, train.getDoorTarget(), train.getHostHold(), train.service.getTripMillis(), train.isTakenOver());
	}

	@Override
	public RunSegment getSegment(long trainId) {
		final TickRunner<T>.HeadlessTrain train = runner.getTrain(trainId);
		return train == null ? null : train.getRunSegment();
	}

	@Override
	public EventSimulator<T> fork() {
		if (live) {
			throw new IllegalStateException("A live simulator keeps no inputs to fork from");
		}
		final EventSimulator<T> fork = new EventSimulator<>(start, skipping, true);
		applied.forEach(fork::submit);
		inputs.forEach(fork::submit);
		fork.advanceTo(getTime());
		return fork;
	}

	@Override
	public SimulationSnapshot snapshot() {
		if (live) {
			throw new IllegalStateException("A live simulator keeps no inputs to snapshot");
		}
		final List<HostInput> allInputs = new ArrayList<>(applied);
		final List<HostInput> pending = new ArrayList<>(inputs);
		Collections.sort(pending);
		allInputs.addAll(pending);
		return new SimulationSnapshot(getTime(), allInputs, cursor);
	}

	public boolean isPrediction() {
		return predicted;
	}

	@Override
	public List<SimEvent> events(long afterCursor) {
		final List<SimEvent> newEvents = new ArrayList<>();
		// Events are in cursor order, so the first one after the cursor is found by halving
		int low = 0;
		int high = events.size();
		while (low < high) {
			final int middle = (low + high) >>> 1;
			if (events.get(middle).cursor <= afterCursor) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		for (int i = low; i < events.size(); i++) {
			newEvents.add(events.get(i));
		}
		return Collections.unmodifiableList(newEvents);
	}

	private void apply(HostInput input, long tickMillis) {
		if (!live) {
			applied.add(input);
		}
		// Trains left alone catch up under the old inputs, and work out again under the new ones
		runner.catchUpAll();
		if (input instanceof HostInput.Hold) {
			final HostInput.Hold hold = (HostInput.Hold) input;
			final TickRunner<T>.HeadlessTrain train = runner.getTrain(hold.trainId);
			if (train != null) {
				train.setHostHold(hold.reason);
				addEvent(tickMillis, SimEvent.Kind.HELD, hold.trainId, train.getHeadRail(), hold.reason);
			}
		} else if (input instanceof HostInput.Resume) {
			final HostInput.Resume resume = (HostInput.Resume) input;
			final TickRunner<T>.HeadlessTrain train = runner.getTrain(resume.trainId);
			if (train != null && train.getHostHold() != null) {
				train.setHostHold(null);
				addEvent(tickMillis, SimEvent.Kind.RELEASED, resume.trainId, train.getHeadRail(), "");
			}
		} else if (input instanceof HostInput.Remove) {
			runner.removeTrain(((HostInput.Remove) input).trainId);
			doorsOpen.remove(((HostInput.Remove) input).trainId);
		} else if (input instanceof HostInput.ExternalOccupancy) {
			final HostInput.ExternalOccupancy occupancy = (HostInput.ExternalOccupancy) input;
			runner.occupyUntil(occupancy.rail, occupancy.untilTime);
		} else if (input instanceof HostInput.ExternalTrain) {
			final HostInput.ExternalTrain externalTrain = (HostInput.ExternalTrain) input;
			runner.setExternalTrain(externalTrain.trainId, externalTrain.rails);
		} else if (input instanceof HostInput.TakeOver) {
			runner.takeOver(((HostInput.TakeOver) input).trainId);
		} else if (input instanceof HostInput.Release) {
			final HostInput.Release release = (HostInput.Release) input;
			runner.release(release.trainId, release.railProgress, release.speed);
		} else if (input instanceof HostInput.PublishPlan) {
			((HostInput.PublishPlan) input).change.accept(runner.getRoutes());
		} else if (input instanceof HostInput.PublishNetwork) {
			publishNetwork(((HostInput.PublishNetwork) input).removedTracks, tickMillis);
		}
		runner.wakeAll();
	}

	/**
	 * A train with a removed track ahead stops short of it and is reported; one standing on one is taken off for the host
	 * to put somewhere else (5.1, rule 5).
	 */
	private void publishNetwork(Set<java.util.UUID> removedTracks, long tickMillis) {
		runner.removeTracks(removedTracks);
		for (final TickRunner<T>.HeadlessTrain train : runner.getTrains()) {
			if (!train.isOnRoute()) {
				continue;
			}
			final int removedRail = train.findRemovedRail();
			if (removedRail < 0) {
				continue;
			}
			if (train.isOnRemovedTrack()) {
				addEvent(tickMillis, SimEvent.Kind.CONFLICT, train.id, removedRail, "track removed under the train");
				runner.removeTrain(train.id);
				doorsOpen.remove(train.id);
			} else {
				addEvent(tickMillis, SimEvent.Kind.CONFLICT, train.id, removedRail, "track removed ahead");
			}
		}
	}

	/**
	 * Turns what happened in the tick into events, sorted by kind, then train, then the order each train had them.
	 */
	private void collectEvents() {
		final long millis = runner.getMillis();
		final List<Object[]> tickEvents = new ArrayList<>();
		final List<MotionEvent> motionEvents = runner.getEvents();
		for (int i = motionEventsRead; i < motionEvents.size(); i++) {
			final MotionEvent motionEvent = motionEvents.get(i);
			final SimEvent.Kind kind = toKind(motionEvent);
			if (kind != null) {
				tickEvents.add(new Object[]{kind, motionEvent.trainId, motionEvent.rail, i});
			}
		}
		motionEventsRead = motionEvents.size();
		// Doors only change on trains that moved
		for (final TickRunner<T>.HeadlessTrain train : runner.getMovedTrains()) {
			final boolean open = train.getDoorTarget();
			final Boolean wasOpen = doorsOpen.put(train.id, open);
			if (wasOpen != null && wasOpen != open) {
				tickEvents.add(new Object[]{open ? SimEvent.Kind.DOORS_OPEN : SimEvent.Kind.DOORS_CLOSE, train.id, train.getHeadRail(), Integer.MAX_VALUE});
			}
		}
		final List<String> recovered = runner.takeRecovered();
		if (!recovered.isEmpty()) {
			tickEvents.add(new Object[]{SimEvent.Kind.RECOVERED, 0L, -1, Integer.MAX_VALUE, String.join(", ", recovered)});
		}
		for (final Set<Long> deadlock : runner.reservations.takeDeadlocks()) {
			tickEvents.add(new Object[]{SimEvent.Kind.DEADLOCK, Collections.min(deadlock), -1, Integer.MAX_VALUE, deadlock.toString()});
		}
		tickEvents.sort((event1, event2) -> {
			final int byKind = ((SimEvent.Kind) event1[0]).compareTo((SimEvent.Kind) event2[0]);
			if (byKind != 0) {
				return byKind;
			}
			final int byTrain = Long.compare((long) event1[1], (long) event2[1]);
			return byTrain != 0 ? byTrain : Integer.compare((int) event1[3], (int) event2[3]);
		});
		tickEvents.forEach(event -> addEvent(millis, (SimEvent.Kind) event[0], (long) event[1], (int) event[2], event.length > 4 ? (String) event[4] : ""));
	}

	/**
	 * A train coming to a halt at its stop has arrived; anywhere else it was held up by the rails ahead.
	 */
	private SimEvent.Kind toKind(MotionEvent motionEvent) {
		switch (motionEvent.type) {
			case DEPARTED:
				return SimEvent.Kind.DEPART;
			case PASSED:
				return SimEvent.Kind.PASS;
			case RETURNED:
				return SimEvent.Kind.RETURNED;
			case SEGMENT_REPLACED:
				return SimEvent.Kind.SEGMENT_REPLACED;
			case STOPPED:
				final TickRunner<T>.HeadlessTrain train = runner.getTrain(motionEvent.trainId);
				return train != null && train.getNextStoppingIndex() == motionEvent.rail ? SimEvent.Kind.ARRIVE : SimEvent.Kind.HELD;
			default:
				return null;
		}
	}

	private void addEvent(long millis, SimEvent.Kind kind, long trainId, int rail, String reason) {
		final long sequence = eventSequences.merge(trainId, 1L, Long::sum);
		events.add(new SimEvent(++cursor, millis, kind, trainId, sequence, rail, kind == SimEvent.Kind.HELD && reason.isEmpty() && rail >= 0 ? "blocked" : reason, predicted));
	}
}
