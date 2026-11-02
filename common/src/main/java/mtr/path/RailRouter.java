package mtr.path;

import mtr.advanced.PathRegions;
import mtr.path.RailGraph.Connection;
import mtr.path.RailGraph.Kind;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic A* over a whole route. The state is "targets completed + current connection", so the direction a
 * train arrives in carries over to the next section. Ties are broken by the sorted connection order.
 */
public final class RailRouter {

	public static final double TURN_BACK_PENALTY = 600;
	public static final double OTHER_PLATFORM_PENALTY = 600;

	private RailRouter() {
	}

	public static <R> Result<R> route(RailGraph<R> graph, List<Target> targets) {
		if (targets.size() < 2 || !targets.get(0).isRail()) {
			throw new IllegalArgumentException("A route starts on a saved rail and needs a second target");
		}

		for (int i = 1; i < targets.size(); i++) {
			if (!graph.isConnected(targets.get(0).pos1, targets.get(i).pos1)) {
				return new Result<>(Collections.emptyList(), i);
			}
		}

		final BitSet[] legs = getLegRegions(graph, targets);
		final Map<Long, State<R>> best = new HashMap<>();
		final PriorityQueue<State<R>> queue = new PriorityQueue<>((state1, state2) -> {
			final int compare = Double.compare(state1.estimate, state2.estimate);
			if (compare != 0) {
				return compare;
			}
			return state1.completed != state2.completed ? state2.completed - state1.completed : state1.connection.index() - state2.connection.index();
		});

		final Target start = targets.get(0);
		final Connection<R> forward = graph.find(start.pos1, start.pos2);
		final Connection<R> backward = graph.find(start.pos2, start.pos1);
		if (forward == null || backward == null) {
			return new Result<>(Collections.emptyList(), 0);
		}
		push(graph, targets, legs, best, queue, null, forward, 1, 0, false);
		push(graph, targets, legs, best, queue, null, backward, 1, 0, false);

		int mostCompleted = 1;
		while (!queue.isEmpty()) {
			final State<R> state = queue.poll();
			if (state.stale) {
				continue;
			}
			mostCompleted = Math.max(mostCompleted, state.completed);
			if (state.completed == targets.size()) {
				return new Result<>(unwind(state), targets.size());
			}

			final Connection<R> connection = state.connection;
			if (state.justCompletedRail) {
				final Connection<R> opposite = graph.reverseOf(connection);
				if (opposite != null) {
					push(graph, targets, legs, best, queue, state, opposite, state.completed, state.cost + opposite.cost + TURN_BACK_PENALTY, true);
				}
			}

			final boolean canTurnBack = connection.kind == Kind.TURN_BACK;
			for (final Connection<R> next : graph.outgoing(connection.toPos)) {
				if (canTurnBack || next.continuesFrom(connection)) {
					final boolean reversing = next.toPos == connection.fromPos;
					final boolean isTarget = targets.get(state.completed).matches(next);
					final double penalty = (reversing ? TURN_BACK_PENALTY : 0) + (next.kind == Kind.PLATFORM && !isTarget ? OTHER_PLATFORM_PENALTY : 0);
					push(graph, targets, legs, best, queue, state, next, state.completed, state.cost + next.cost + penalty, false);
				}
			}
		}

		return new Result<>(Collections.emptyList(), mostCompleted);
	}

	/**
	 * With pathfinding boundaries, each part of the route between two targets stays in the regions of its ends.
	 *
	 * @return the regions allowed while heading for each target, or null when the graph has no boundaries
	 */
	private static <R> BitSet[] getLegRegions(RailGraph<R> graph, List<Target> targets) {
		final PathRegions regions = graph.getRegions();
		if (regions == null) {
			return null;
		}
		final BitSet[] legs = new BitSet[targets.size()];
		for (int i = 1; i < targets.size(); i++) {
			legs[i] = PathRegions.legRegions(targets.get(i - 1).getRegions(graph, regions), targets.get(i).getRegions(graph, regions));
		}
		return legs;
	}

	private static <R> void push(RailGraph<R> graph, List<Target> targets, BitSet[] legs, Map<Long, State<R>> best, PriorityQueue<State<R>> queue, State<R> parent, Connection<R> connection, int completedBefore, double cost, boolean reversal) {
		if (legs != null && !graph.getRegions().allows(connection, legs[completedBefore])) {
			return;
		}
		int completed = completedBefore;
		boolean completedRail = false;
		int completedTarget = -1;

		if (!reversal) {
			while (completed < targets.size() && targets.get(completed).matches(connection)) {
				completedRail = targets.get(completed).isRail();
				if (completedRail) {
					completedTarget = completed;
				}
				completed++;
			}
		}

		final long key = (long) completed << 32 | connection.index() << 1 | (reversal ? 1 : 0);
		final State<R> existing = best.get(key);
		if (existing != null && existing.cost <= cost) {
			return;
		}
		if (existing != null) {
			existing.stale = true;
		}

		final double remaining = completed == targets.size() ? 0 : targets.get(completed).chordFrom(connection) / Math.max(graph.maxChordPerCost, Double.MIN_VALUE);
		final State<R> state = new State<>(parent, connection, completed, cost, cost + remaining, completedRail, completedTarget, reversal);
		best.put(key, state);
		queue.add(state);
	}

	private static <R> List<Step<R>> unwind(State<R> last) {
		final List<Step<R>> steps = new ArrayList<>();
		for (State<R> state = last; state != null; state = state.parent) {
			steps.add(new Step<>(state.connection, state.completedTarget, state.reversal));
		}
		Collections.reverse(steps);
		return steps;
	}

	/**
	 * A saved rail must be run along, in either direction. A node must be passed through.
	 */
	public static final class Target {

		private final long pos1;
		private final long pos2;
		private final int x1;
		private final int z1;
		private final int x2;
		private final int z2;
		private final boolean rail;

		private Target(long pos1, int x1, int z1, long pos2, int x2, int z2, boolean rail) {
			this.pos1 = pos1;
			this.x1 = x1;
			this.z1 = z1;
			this.pos2 = pos2;
			this.x2 = x2;
			this.z2 = z2;
			this.rail = rail;
		}

		public static Target rail(long pos1, int x1, int z1, long pos2, int x2, int z2) {
			return new Target(pos1, x1, z1, pos2, x2, z2, true);
		}

		public static Target node(long pos, int x, int z) {
			return new Target(pos, x, z, pos, x, z, false);
		}

		public boolean isRail() {
			return rail;
		}

		private int[] getRegions(RailGraph<?> graph, PathRegions regions) {
			return rail ? regions.railRegions(graph, pos1, pos2) : regions.nodeRegions(pos1);
		}

		private boolean matches(Connection<?> connection) {
			return rail ? connection.fromPos == pos1 && connection.toPos == pos2 || connection.fromPos == pos2 && connection.toPos == pos1 : connection.toPos == pos1;
		}

		private double chordFrom(Connection<?> connection) {
			return Math.min(connection.chordTo(x1, z1), connection.chordTo(x2, z2));
		}
	}

	/**
	 * @param <R> the rail type carried by the graph
	 */
	public static final class Step<R> {

		public final Connection<R> connection;
		public final int completedTarget;
		public final boolean reversal;

		private Step(Connection<R> connection, int completedTarget, boolean reversal) {
			this.connection = connection;
			this.completedTarget = completedTarget;
			this.reversal = reversal;
		}
	}

	public static final class Result<R> {

		public final List<Step<R>> steps;
		public final int completedTargets;

		private Result(List<Step<R>> steps, int completedTargets) {
			this.steps = Collections.unmodifiableList(steps);
			this.completedTargets = completedTargets;
		}

		public boolean isSuccess() {
			return !steps.isEmpty();
		}
	}

	private static final class State<R> {

		private final State<R> parent;
		private final Connection<R> connection;
		private final int completed;
		private final double cost;
		private final double estimate;
		private final boolean justCompletedRail;
		private final int completedTarget;
		private final boolean reversal;
		private boolean stale;

		private State(State<R> parent, Connection<R> connection, int completed, double cost, double estimate, boolean justCompletedRail, int completedTarget, boolean reversal) {
			this.parent = parent;
			this.connection = connection;
			this.completed = completed;
			this.cost = cost;
			this.estimate = estimate;
			this.justCompletedRail = justCompletedRail;
			this.completedTarget = completedTarget;
			this.reversal = reversal;
		}
	}
}
