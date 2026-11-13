package transitsim.sim;

import transitsim.motion.MotionEvent;
import transitsim.plan.Buffers;
import transitsim.plan.PlanTrack;
import transitsim.plan.TripPath;

import java.util.*;

/**
 * Measures, from what trains on duties do, how much longer than planned they take out of a siding to their first stop,
 * round from the last stop of one service to the first of the next, and back from their last stop (D1), which the next
 * plan of the duties adds ({@link Buffers}). Both hosts feed it their trains' {@link MotionEvent}s; the game after each
 * tick, the component as they happen, so it takes everything it needs of a leg when the leg starts.
 * <p>
 * The time a leg takes is from leaving to reaching, less the time stood at stops on the way, such as a turn back rail a
 * train waits on for its next service; standing short of a taken rail counts, as that is the kind of thing that makes
 * trains late. The plan's time is the fastest run along the same rails. Each kind of leg keeps its last
 * {@link #SAMPLES} measurements, and its buffer is the {@link #PERCENTILE}th percentile of how much longer they took,
 * kept only from {@link #TOLERANCE_MILLIS} up, as with the ways out and back measured on the rails (0.17.0).
 */
public final class BufferRecorder {

	public static final int SAMPLES = 20;
	public static final int PERCENTILE = 85;
	public static final long TOLERANCE_MILLIS = 15000;
	/**
	 * A leg this much longer than planned was held up by something else, a train stuck or a deadlock, not by the leg.
	 */
	public static final long MAX_EXCESS_MILLIS = 20 * 60 * 1000;
	private static final int MAX_KEYS = 1024;

	private final Map<Long, Leg> legs = new HashMap<>();
	/**
	 * {@link Buffers#key} → the latest measurements, the newest last.
	 */
	private final Map<String, ArrayDeque<Long>> samples = new TreeMap<>();
	private Buffers buffers = Buffers.NONE;
	private boolean changed;
	/**
	 * Told each way out measured by how late the train reached its first stop: the way's {@link Buffers#key} and how late
	 * (2026-11-13, for {@link OutLeads}); set by the host, or null.
	 */
	public volatile java.util.function.ObjLongConsumer<String> outListener;

	/**
	 * @return the planned arrival at a rail of the path a train's service is on, 0 where there is none, for
	 * {@link #onEvent(MotionEvent, long, TripPath, java.util.function.IntToLongFunction)}
	 */
	public static java.util.function.IntToLongFunction plannedArrivals(TrainService<?> service) {
		return rail -> {
			final transitsim.plan.RouteTimetable.StopPlan plan = service.getPlanAt(rail);
			return plan == null ? 0 : service.getPlannedMillis(plan.arrivalMillis);
		};
	}

	/**
	 * @param sidingId the siding the train belongs to
	 * @param tripPath the path the train is on now, or null
	 */
	public <T extends PlanTrack> void onEvent(MotionEvent event, long sidingId, TripPath<T> tripPath) {
		onEvent(event, sidingId, tripPath, null);
	}

	/**
	 * @param plannedArrival the planned arrival at a rail of the path, 0 where there is none, or null: a way out is then
	 *                       measured by how late the train reached its first stop (2026-11-13), not by how much longer it
	 *                       ran, as a train leaving earlier for a stop it can't reach sooner runs longer but arrives as late
	 */
	public <T extends PlanTrack> void onEvent(MotionEvent event, long sidingId, TripPath<T> tripPath, java.util.function.IntToLongFunction plannedArrival) {
		final Leg leg = legs.get(event.trainId);
		switch (event.type) {
			case DEPARTED:
				if (leg != null && event.rail != leg.startRail) {
					if (leg.standingSince >= 0) {
						leg.standingMillis += event.millis - leg.standingSince;
						leg.standingSince = -1;
					}
					return;
				}
				legs.remove(event.trainId);
				startLeg(event, sidingId, tripPath, plannedArrival);
				break;
			case STOPPED:
				if (leg == null) {
					return;
				}
				if (leg.kind != Buffers.BACK && event.rail == leg.endRail) {
					finish(leg, event.millis);
					legs.remove(event.trainId);
				} else if (leg.isStopRail(event.rail)) {
					if (leg.isPlatformStop(event.rail)) {
						// At a platform it wasn't going to: on another path than it set off on
						legs.remove(event.trainId);
					} else {
						leg.standingSince = event.millis;
					}
				}
				break;
			case RETURNED:
				if (leg != null && leg.kind == Buffers.BACK) {
					finish(leg, event.millis);
				}
				legs.remove(event.trainId);
				break;
			case LAP:
				legs.remove(event.trainId);
				break;
			default:
				break;
		}
	}

	private <T extends PlanTrack> void startLeg(MotionEvent event, long sidingId, TripPath<T> tripPath, java.util.function.IntToLongFunction plannedArrival) {
		if (tripPath == null || !tripPath.isDuty() || tripPath.dutyStarts == null) {
			return;
		}
		final List<T> path = tripPath.path;
		final int rail = event.rail;
		if (rail == 0) {
			final int first = nextPlatformStop(path, 0);
			if (first > 0) {
				final Leg leg = new Leg(Buffers.OUT, sidingId, path.get(first).getStopId(), rail, first, tripPath.stopTimes.getLeadMillis(), event.millis, path);
				leg.plannedArrival = plannedArrival == null ? 0 : plannedArrival.applyAsLong(first);
				legs.put(event.trainId, leg);
			}
			return;
		}
		if (rail >= path.size() || !isPlatformStop(path.get(rail))) {
			return;
		}
		final int next = nextPlatformStop(path, rail);
		if (next < 0) {
			final long backMillis = tripPath.getBackMillis();
			if (!tripPath.staysOnLine && backMillis > 0) {
				final long toSiding = TripPath.isReturnKey(tripPath.key) ? TripPath.getReturnSidingId(tripPath.key) : sidingId;
				legs.put(event.trainId, new Leg(Buffers.BACK, path.get(rail).getStopId(), toSiding, rail, path.size() - 1, backMillis, event.millis, path));
			}
			return;
		}
		// Round to the next service: the next stop starts a segment of the duty
		for (int k = 1; k < tripPath.dutyStarts.length; k++) {
			if (tripPath.dutyStarts[k] == next) {
				final long planned = tripPath.stopTimes.getMovingMillis(rail, next);
				if (planned >= 0) {
					legs.put(event.trainId, new Leg(Buffers.TURN, path.get(rail).getStopId(), path.get(next).getStopId(), rail, next, planned, event.millis, path));
				}
				return;
			}
		}
	}

	private void finish(Leg leg, long millis) {
		final boolean byLateness = leg.kind == Buffers.OUT && leg.plannedArrival > 0;
		final long excess = byLateness ? millis - leg.plannedArrival : millis - leg.startMillis - leg.standingMillis - leg.plannedMillis;
		if (excess > MAX_EXCESS_MILLIS) {
			return;
		}
		final String key = Buffers.key(leg.kind, leg.from, leg.to);
		add(key, excess);
		final java.util.function.ObjLongConsumer<String> listener = outListener;
		if (byLateness && listener != null) {
			listener.accept(key, excess);
		}
	}

	/**
	 * Adds a measurement of how much longer than planned a leg took, negative if quicker.
	 */
	public void add(String key, long excessMillis) {
		if (!samples.containsKey(key) && samples.size() >= MAX_KEYS) {
			return;
		}
		final ArrayDeque<Long> keySamples = samples.computeIfAbsent(key, newKey -> new ArrayDeque<>());
		keySamples.addLast(excessMillis);
		while (keySamples.size() > SAMPLES) {
			keySamples.removeFirst();
		}
		update(key);
	}

	private void update(String key) {
		final long before = buffers.getMillis().getOrDefault(key, 0L);
		final long after = getBuffer(samples.get(key));
		if (Math.abs(after - before) > TOLERANCE_MILLIS || after == 0 && before != 0 || before == 0 && after != 0) {
			final Map<String, Long> newMillis = new TreeMap<>(buffers.getMillis());
			if (after == 0) {
				newMillis.remove(key);
			} else {
				newMillis.put(key, after);
			}
			buffers = new Buffers(newMillis);
			changed = true;
		}
	}

	/**
	 * @return the percentile of the measurements, or 0 below the tolerance
	 */
	static long getBuffer(Collection<Long> keySamples) {
		if (keySamples == null || keySamples.isEmpty()) {
			return 0;
		}
		final List<Long> sorted = new ArrayList<>(keySamples);
		Collections.sort(sorted);
		final int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * PERCENTILE / 100.0) - 1);
		final long value = sorted.get(Math.max(0, index));
		return value >= TOLERANCE_MILLIS ? value : 0;
	}

	public Buffers getBuffers() {
		return buffers;
	}

	/**
	 * @return whether anything has been measured, as only the server measures
	 */
	public boolean hasSamples() {
		return !samples.isEmpty();
	}

	/**
	 * @return whether the buffers changed since this was last asked, for the host to send them on
	 */
	public boolean takeChanged() {
		final boolean wasChanged = changed;
		changed = false;
		return wasChanged;
	}

	public void clear() {
		samples.clear();
		legs.clear();
		if (!buffers.isEmpty()) {
			buffers = Buffers.NONE;
			changed = true;
		}
	}

	/**
	 * For saves: the count of keys, then for each its kind, from, to, the count of measurements and each of them.
	 */
	public List<Long> toList() {
		final List<Long> list = new ArrayList<>();
		list.add((long) samples.size());
		samples.forEach((key, keySamples) -> {
			for (final long part : Buffers.parse(key)) {
				list.add(part);
			}
			list.add((long) keySamples.size());
			list.addAll(keySamples);
		});
		return list;
	}

	public void readList(List<Long> list) {
		samples.clear();
		legs.clear();
		buffers = Buffers.NONE;
		if (list == null || list.isEmpty()) {
			return;
		}
		int index = 1;
		final int count = list.get(0).intValue();
		for (int i = 0; i < count && index + 3 < list.size(); i++) {
			final String key = Buffers.key(list.get(index).intValue(), list.get(index + 1), list.get(index + 2));
			final int size = list.get(index + 3).intValue();
			index += 4;
			final ArrayDeque<Long> keySamples = new ArrayDeque<>();
			for (int j = 0; j < size && index < list.size(); j++) {
				keySamples.addLast(list.get(index++));
			}
			while (keySamples.size() > SAMPLES) {
				keySamples.removeFirst();
			}
			if (!keySamples.isEmpty()) {
				samples.put(key, keySamples);
			}
		}
		final Map<String, Long> newMillis = new TreeMap<>();
		samples.forEach((key, keySamples) -> {
			final long buffer = getBuffer(keySamples);
			if (buffer > 0) {
				newMillis.put(key, buffer);
			}
		});
		buffers = newMillis.isEmpty() ? Buffers.NONE : new Buffers(newMillis);
		changed = false;
	}

	private static int nextPlatformStop(List<? extends PlanTrack> path, int after) {
		for (int i = after + 1; i < path.size(); i++) {
			if (isPlatformStop(path.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isPlatformStop(PlanTrack track) {
		return track.getDwellTime() > 0 && track.getStopId() != 0 && !track.isSidingTrack();
	}

	private static final class Leg {

		private final int kind;
		private final long from;
		private final long to;
		private final int startRail;
		private final int endRail;
		private final long plannedMillis;
		private final long startMillis;
		private final List<? extends PlanTrack> path;
		private long standingMillis;
		private long standingSince = -1;
		/**
		 * For a way out, the planned arrival at its first stop, or 0 if not known.
		 */
		private long plannedArrival;

		private Leg(int kind, long from, long to, int startRail, int endRail, long plannedMillis, long startMillis, List<? extends PlanTrack> path) {
			this.kind = kind;
			this.from = from;
			this.to = to;
			this.startRail = startRail;
			this.endRail = endRail;
			this.plannedMillis = plannedMillis;
			this.startMillis = startMillis;
			this.path = path;
		}

		private boolean isStopRail(int rail) {
			return rail >= 0 && rail < path.size() && path.get(rail).getDwellTime() > 0;
		}

		private boolean isPlatformStop(int rail) {
			return rail >= 0 && rail < path.size() && BufferRecorder.isPlatformStop(path.get(rail));
		}
	}
}
