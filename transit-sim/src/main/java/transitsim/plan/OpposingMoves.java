package transitsim.plan;

import java.util.*;

/**
 * Moves between two stops of the depot's paths that run against each other (item A of MTR-待验证.md, 2026-11-14): line 8 of
 * the six test lines had trains of one route change ends at a station through a dead-end turn-back rail beside it, turning
 * three times, along the same rails the other way as the trains of another route turning back through it. Each such pair
 * is a pair of conflicting routes (对向进路), safe only because a train goes into a dead end only with its way out; the
 * depot's hints tell of them, with a better layout.
 * <ul>
 * <li>A move turning twice or more between two stops.</li>
 * <li>Two moves running some of the same track the other way, at least one of them turning back on the way: trains on
 * single track meet head on by design, not this.</li>
 * </ul>
 * Ways out of and back to the sidings aren't moves here: running them against the line is told apart.
 */
public final class OpposingMoves {

	private OpposingMoves() {
	}

	/**
	 * A way between two stops, turning back on the way at least once.
	 */
	public static final class Move<T extends PlanTrack> {

		public final long fromStop;
		public final long toStop;
		/**
		 * How many times the train changes ends on the way.
		 */
		public final int turns;
		final List<T> tracks;

		Move(long fromStop, long toStop, int turns, List<T> tracks) {
			this.fromStop = fromStop;
			this.toStop = toStop;
			this.turns = turns;
			this.tracks = tracks;
		}

		private List<UUID> keys() {
			final List<UUID> keys = new ArrayList<>(tracks.size());
			tracks.forEach(track -> keys.add(track.getTrackKey()));
			return keys;
		}
	}

	/**
	 * Something found: a move turning too often (the second one null), or two moves against each other.
	 */
	public static final class Finding {

		public final Move<?> move;
		public final Move<?> other;

		Finding(Move<?> move, Move<?> other) {
			this.move = move;
			this.other = other;
		}

		/**
		 * @return as sent to the clients: {@code turns from to turns} for a move turning too often, {@code against from to
		 * turns from to turns} for a pair
		 */
		@Override
		public String toString() {
			return other == null ? "turns " + move.fromStop + " " + move.toStop + " " + move.turns : "against " + move.fromStop + " " + move.toStop + " " + move.turns + " " + other.fromStop + " " + other.toStop + " " + other.turns;
		}
	}

	/**
	 * @return the moves of a path that turn back between two of its stops, each once
	 */
	public static <T extends PlanTrack> List<Move<T>> movesOf(List<T> path) {
		final List<Move<T>> moves = new ArrayList<>();
		int from = -1;
		for (int i = 0; i < path.size(); i++) {
			final T track = path.get(i);
			if (track.isSidingTrack()) {
				from = -1;
			} else if (isStop(track)) {
				if (from >= 0) {
					final List<T> tracks = new ArrayList<>(path.subList(from + 1, i));
					int turns = 0;
					for (int j = from; j < i; j++) {
						if (path.get(j + 1).isOppositeOf(path.get(j))) {
							turns++;
						}
					}
					if (turns > 0) {
						moves.add(new Move<>(path.get(from).getStopId(), track.getStopId(), turns, tracks));
					}
				}
				from = i;
			}
		}
		return moves;
	}

	private static boolean isStop(PlanTrack track) {
		return track.isPlatformTrack() && track.getStopId() != 0 && track.getDwellTime() > 0;
	}

	/**
	 * @param moves the moves of all the depot's paths, the same move from several paths taken once
	 * @return the moves turning twice or more, then each pair against each other, in the order the moves are given
	 */
	public static <T extends PlanTrack> List<Finding> find(Collection<Move<T>> moves) {
		final List<Move<T>> distinct = new ArrayList<>();
		final Set<List<Object>> seen = new HashSet<>();
		for (final Move<T> move : moves) {
			if (seen.add(Arrays.asList(move.fromStop, move.toStop, move.keys(), directions(move)))) {
				distinct.add(move);
			}
		}
		final List<Finding> findings = new ArrayList<>();
		for (final Move<T> move : distinct) {
			if (move.turns >= 2) {
				findings.add(new Finding(move, null));
			}
		}
		// Which moves run each track, so only moves sharing one are compared
		final Map<UUID, List<Integer>> byTrack = new HashMap<>();
		for (int i = 0; i < distinct.size(); i++) {
			for (final T track : distinct.get(i).tracks) {
				final List<Integer> list = byTrack.computeIfAbsent(track.getTrackKey(), key -> new ArrayList<>());
				if (list.isEmpty() || list.get(list.size() - 1) != i) {
					list.add(i);
				}
			}
		}
		for (int i = 0; i < distinct.size(); i++) {
			final Move<T> move = distinct.get(i);
			final Set<Integer> against = new TreeSet<>();
			for (final T track : move.tracks) {
				for (final int j : byTrack.get(track.getTrackKey())) {
					if (j > i && !against.contains(j) && runsAgainst(track, distinct.get(j))) {
						against.add(j);
					}
				}
			}
			against.forEach(j -> findings.add(new Finding(move, distinct.get(j))));
		}
		return findings;
	}

	/**
	 * @return whether the other move runs this track only the other way
	 */
	private static <T extends PlanTrack> boolean runsAgainst(T track, Move<T> other) {
		boolean opposite = false;
		for (final T otherTrack : other.tracks) {
			if (otherTrack.getTrackKey().equals(track.getTrackKey())) {
				if (otherTrack.isOppositeOf(track)) {
					opposite = true;
				} else {
					// Run the same way too, as both ways on a turn back rail both turn on: not against each other there
					return false;
				}
			}
		}
		return opposite;
	}

	/**
	 * @return for telling two moves along the same tracks the other way round apart
	 */
	private static <T extends PlanTrack> List<Boolean> directions(Move<T> move) {
		final List<Boolean> directions = new ArrayList<>(move.tracks.size());
		for (int i = 0; i + 1 < move.tracks.size(); i++) {
			directions.add(move.tracks.get(i + 1).isOppositeOf(move.tracks.get(i)));
		}
		return directions;
	}
}
