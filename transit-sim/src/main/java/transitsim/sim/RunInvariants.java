package transitsim.sim;

import java.util.*;

/**
 * Checks a run as it goes for what must never happen, whatever the plan and whatever goes wrong on the day (end-to-end
 * check): trains don't appear on or vanish from the line except at their sidings, don't jump, don't overlap, and aren't
 * kept from going on for long. Read only: it changes nothing in the runner.
 * <p>
 * Call {@link #check()} after each tick, for example from {@link TickRunner#setAfterTick}. What breaks a rule is kept as a
 * readable line in {@link #getViolations()}; trains kept for long from going on, by a train ahead or waiting for their
 * way, are kept apart in {@link #getStuck()}, as a train held on purpose waits too. A train standing where its plan has
 * it wait, as on a turn back rail for its next service, isn't stuck.
 */
public final class RunInvariants<T extends RunTrack> {

	/**
	 * The fastest a train runs, 300 km/h, in blocks per millisecond, with some room.
	 */
	private static final double MAX_BLOCKS_PER_MILLI = 320 / 3.6 / 1000;
	/**
	 * How much two trains may seem to overlap, in blocks, from rounding.
	 */
	private static final double OVERLAP_ROOM = 0.5;
	private static final int MAX_LINES = 200;

	private final TickRunner<T> runner;
	private final Map<Long, Seen> seen = new HashMap<>();
	private final List<String> violations = new ArrayList<>();
	private final Map<Long, String> stuck = new TreeMap<>();
	private final Set<Long> expectedRemovals = new HashSet<>();
	private long stuckMillis = 10 * 60 * 1000;
	private long checks;

	public RunInvariants(TickRunner<T> runner) {
		this.runner = runner;
	}

	/**
	 * How long a train may be kept from going on before it is listed as stuck.
	 */
	public RunInvariants<T> setStuckMillis(long stuckMillis) {
		this.stuckMillis = stuckMillis;
		return this;
	}

	/**
	 * The host is about to take this train away on purpose, so its going isn't a train vanishing.
	 */
	public void expectRemoval(long trainId) {
		expectedRemovals.add(trainId);
	}

	public List<String> getViolations() {
		return Collections.unmodifiableList(violations);
	}

	/**
	 * @return each train that has stood still on the line, away from a stop, for longer than allowed, with where
	 */
	public Map<Long, String> getStuck() {
		return Collections.unmodifiableMap(stuck);
	}

	public long getChecks() {
		return checks;
	}

	public void check() {
		checks++;
		final long millis = runner.getMillis();
		// The first look only notes where every train is
		final boolean first = checks == 1;
		final Map<Long, Seen> now = new HashMap<>();
		final List<TickRunner<T>.HeadlessTrain> onLine = new ArrayList<>();
		for (final TickRunner<T>.HeadlessTrain train : runner.getTrains()) {
			final Seen state = new Seen(train, millis);
			now.put(train.id, state);
			final Seen before = seen.get(train.id);
			if (first) {
				if (state.onRoute) {
					onLine.add(train);
				}
			} else if (state.onRoute) {
				onLine.add(train);
				if (before == null || !before.onRoute) {
					if (!state.atSiding) {
						add(millis, train.id, "appeared on the line away from its siding", before, state);
					}
				} else {
					checkMove(millis, train, before, state);
				}
				checkStuck(millis, train, before, state);
			} else if (before != null && before.onRoute && !before.atSiding) {
				add(millis, train.id, "left the line away from its siding", before, state);
			}
		}
		seen.forEach((trainId, before) -> {
			if (!now.containsKey(trainId) && before.onRoute && !before.atSiding && !expectedRemovals.remove(trainId)) {
				add(millis, trainId, "vanished from the line", before, null);
			}
		});
		checkOverlaps(millis, onLine);
		seen.clear();
		seen.putAll(now);
	}

	/**
	 * A train goes on from where it was, along its path, no further than it can run; a turn back can move its head by the
	 * length of the train.
	 */
	private void checkMove(long millis, TickRunner<T>.HeadlessTrain train, Seen before, Seen state) {
		final double allowed = MAX_BLOCKS_PER_MILLI * Math.max(50, millis - before.millis) + state.length + 2;
		if (before.physical.equals(state.physical) && Math.abs(before.offset - state.offset) <= allowed) {
			return;
		}
		// Along the path it has now, back from its head to where its head was
		final List<T> path = train.getPath();
		double along = state.headProgress - (state.headRail == 0 ? 0 : train.getDistance(state.headRail - 1));
		for (int rail = state.headRail - 1; rail >= 0 && along <= allowed; rail--) {
			final T track = path.get(rail);
			if (physical(track).equals(before.physical)) {
				return;
			}
			along += track.getTrackLength();
		}
		// Onto a path that starts next to where it was, as when a segment is replaced: not far into the track after it
		final T head = path.get(state.headRail);
		final double intoHead = state.headProgress - (state.headRail == 0 ? 0 : train.getDistance(state.headRail - 1));
		if (!before.physical.equals(state.physical) && before.nodes.contains(head.getStartNode()) && intoHead <= allowed) {
			return;
		}
		add(millis, train.id, "jumped", before, state);
	}

	private void checkStuck(long millis, TickRunner<T>.HeadlessTrain train, Seen before, Seen state) {
		if (before != null && before.onRoute && state.kept && before.physical.equals(state.physical) && Math.abs(before.offset - state.offset) < 0.01) {
			state.stillSince = before.stillSince;
		}
		if (millis - state.stillSince > stuckMillis && !stuck.containsKey(train.id)) {
			final String hold = train.getHostHold();
			stuck.put(train.id, String.format("%s train %d on %s for %d s%s", clock(millis), train.id, state.physical, (millis - state.stillSince) / 1000, hold == null || hold.isEmpty() ? "" : " (" + hold + ")"));
		}
	}

	/**
	 * No two trains on the same piece of the same track, wherever their paths come from.
	 */
	private void checkOverlaps(long millis, List<TickRunner<T>.HeadlessTrain> trains) {
		final Map<String, List<double[]>> pieces = new HashMap<>();
		for (final TickRunner<T>.HeadlessTrain train : trains) {
			final List<T> path = train.getPath();
			if (path.isEmpty()) {
				continue;
			}
			final double head = train.getRailProgress();
			final double tail = head - train.getTrainCars() * train.getSpacing();
			for (int rail = Math.max(0, train.getHeadRail()); rail >= 0; rail--) {
				final double end = train.getDistance(rail);
				final double start = rail == 0 ? 0 : train.getDistance(rail - 1);
				if (end <= tail) {
					break;
				}
				final T track = path.get(rail);
				if (track.isSidingTrack()) {
					continue;
				}
				final double from = Math.max(start, tail) - start;
				final double to = Math.min(end, head) - start;
				if (to - from <= OVERLAP_ROOM) {
					continue;
				}
				final boolean forwards = track.getStartNode() <= track.getEndNode();
				final double length = track.getTrackLength();
				final double a = forwards ? from : length - to;
				final double b = forwards ? to : length - from;
				final String key = physical(track);
				for (final double[] other : pieces.computeIfAbsent(key, k -> new ArrayList<>())) {
					if (other[2] != train.id && Math.min(b, other[1]) - Math.max(a, other[0]) > OVERLAP_ROOM) {
						add(millis, train.id, String.format("overlaps train %d on %s", (long) other[2], key), null, null);
					}
				}
				pieces.get(key).add(new double[]{a, b, train.id});
			}
		}
	}

	private void add(long millis, long trainId, String what, Seen before, Seen state) {
		if (violations.size() < MAX_LINES) {
			violations.add(String.format("%s train %d %s%s%s", clock(millis), trainId, what, before == null ? "" : ", was " + before, state == null ? "" : ", now " + state));
		}
	}

	private static String physical(RunTrack track) {
		final long a = Math.min(track.getStartNode(), track.getEndNode());
		final long b = Math.max(track.getStartNode(), track.getEndNode());
		return a + "-" + b;
	}

	private static String clock(long millis) {
		final long inDay = Math.floorMod(millis, 24 * 3600000L);
		return String.format("%02d:%02d:%02d", inDay / 3600000, inDay / 60000 % 60, inDay / 1000 % 60);
	}

	private final class Seen {

		private final long millis;
		private final boolean onRoute;
		private final boolean atSiding;
		/**
		 * It would go on but can't: a train is ahead, or it waits for its way.
		 */
		private final boolean kept;
		private final int headRail;
		private final double headProgress;
		private final String physical;
		private final Set<Long> nodes = new HashSet<>();
		/**
		 * Where the head is along its track, from the track's lower node.
		 */
		private final double offset;
		private final double length;
		private long stillSince;

		private Seen(TickRunner<T>.HeadlessTrain train, long millis) {
			this.millis = millis;
			stillSince = millis;
			onRoute = train.isOnRoute();
			length = train.getTrainCars() * train.getSpacing();
			final List<T> path = train.getPath();
			if (path.isEmpty()) {
				atSiding = true;
				kept = false;
				headRail = 0;
				headProgress = 0;
				physical = "";
				offset = 0;
				return;
			}
			headRail = Math.min(train.getHeadRail(), path.size() - 1);
			headProgress = train.getRailProgress();
			final T track = path.get(headRail);
			atSiding = track.isSidingTrack();
			kept = train.getBlockedRail() >= 0 || train.getWaitingMillis() > 0;
			physical = RunInvariants.physical(track);
			nodes.add(track.getStartNode());
			nodes.add(track.getEndNode());
			final double along = headProgress - (headRail == 0 ? 0 : train.getDistance(headRail - 1));
			offset = track.getStartNode() <= track.getEndNode() ? along : track.getTrackLength() - along;
		}

		@Override
		public String toString() {
			return (onRoute ? "on " : "off ") + physical + " at " + Math.round(offset * 10) / 10D;
		}
	}
}
