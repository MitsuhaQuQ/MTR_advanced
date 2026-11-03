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

/**
 * The simulator of the contracts document (section 9) over a {@link TickRunner} (S4c). Time steps on the runner's ticks,
 * which start from its start time, so where the host stops a step doesn't change anything. Inputs take effect before the
 * first tick at or after their time; events come out as the trains have them, sorted within each tick, and are never
 * taken back, as nothing is ever told ahead of its time.
 */
public final class EventSimulator<T extends RunTrack> implements Simulator {

	private final TickRunner<T> runner;
	private final PriorityQueue<HostInput> inputs = new PriorityQueue<>();
	private final List<SimEvent> events = new ArrayList<>();
	private final Map<Long, Long> eventSequences = new HashMap<>();
	private final Map<Long, Boolean> doorsOpen = new TreeMap<>();
	private int motionEventsRead;
	private long cursor;

	public EventSimulator(TickRunner<T> runner) {
		this.runner = runner;
	}

	public TickRunner<T> getRunner() {
		return runner;
	}

	@Override
	public long getTime() {
		return runner.getMillis();
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
			runner.tick();
			collectEvents();
		}
		return runner.getMillis();
	}

	@Override
	public TrainState query(long trainId) {
		final TickRunner<T>.HeadlessTrain train = runner.getTrain(trainId);
		if (train == null) {
			return null;
		}
		return new TrainState(train.id, train.getHeadRail(), train.getRailProgress(), train.getSpeed(), train.isOnRoute(), train.getSpeed() == 0, train.getDoorTarget(), train.getHostHold(), train.service.getTripMillis());
	}

	@Override
	public RunSegment getSegment(long trainId) {
		final TickRunner<T>.HeadlessTrain train = runner.getTrain(trainId);
		return train == null ? null : train.getRunSegment();
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
		} else if (input instanceof HostInput.PublishPlan) {
			((HostInput.PublishPlan) input).change.run();
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
		for (final TickRunner<T>.HeadlessTrain train : runner.getTrains()) {
			final boolean open = train.getDoorTarget();
			final Boolean wasOpen = doorsOpen.put(train.id, open);
			if (wasOpen != null && wasOpen != open) {
				tickEvents.add(new Object[]{open ? SimEvent.Kind.DOORS_OPEN : SimEvent.Kind.DOORS_CLOSE, train.id, train.getHeadRail(), Integer.MAX_VALUE});
			}
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
		events.add(new SimEvent(++cursor, millis, kind, trainId, sequence, rail, kind == SimEvent.Kind.HELD && reason.isEmpty() && rail >= 0 ? "blocked" : reason));
	}
}
