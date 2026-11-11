package transitsim.fare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Test only: FareNetwork as it was before 2026-11-12 (a search for each pair of groups, run to the end), to compare
 * the faster one with. The network fares are worked out on (design document 6.13): stations joined by the track between the stops of each
 * route, each join marked with the route's group. Only the stations a trip enters and leaves at count, not the trains
 * taken. A join can be taken either way.
 * <p>
 * The fare path crosses the fewest groups and, of those, is the shortest. Track several groups share counts as the group
 * a trip is already in, so a trip isn't split where it needn't be.
 */
final class ReferenceFareNetwork {

	private static final double CROSSING_COST = 1E12;

	private final Map<Long, List<Join>> joins = new HashMap<>();
	private final Map<String, Long> longest = new HashMap<>();

	/**
	 * @param legs the track between two stops in a row of a route: its group, the two stations and its length in blocks
	 */
	ReferenceFareNetwork(Collection<FareNetwork.Leg> legs) {
		final Map<List<Object>, Long> shortest = new HashMap<>();
		for (final FareNetwork.Leg leg : legs) {
			if (leg.fromStation == leg.toStation || leg.blocks < 0) {
				continue;
			}
			final long low = Math.min(leg.fromStation, leg.toStation);
			final long high = Math.max(leg.fromStation, leg.toStation);
			shortest.merge(java.util.Arrays.asList(leg.group, low, high), leg.blocks, Math::min);
		}
		shortest.forEach((key, blocks) -> {
			final String group = (String) key.get(0);
			final long low = (Long) key.get(1);
			final long high = (Long) key.get(2);
			joins.computeIfAbsent(low, station -> new ArrayList<>()).add(new Join(high, group, blocks));
			joins.computeIfAbsent(high, station -> new ArrayList<>()).add(new Join(low, group, blocks));
		});
	}

	/**
	 * @return the groups with a join at the station
	 */
	public Set<String> getGroups(long station) {
		final Set<String> groups = new HashSet<>();
		joins.getOrDefault(station, Collections.emptyList()).forEach(join -> groups.add(join.group));
		return groups;
	}

	/**
	 * @return the longest fare distance between two stations of a group, along the group's own track
	 */
	public long getLongestBlocks(String group) {
		return longest.computeIfAbsent(group, key -> {
			long most = 0;
			for (final long station : joins.keySet()) {
				if (!getGroups(station).contains(key)) {
					continue;
				}
				final Path path = find(station, -1, Collections.singleton(key), Collections.emptySet(), key);
				most = Math.max(most, path == null ? 0 : path.farthestBlocks);
			}
			return most;
		});
	}

	/**
	 * @param startGroups the groups the trip may start in, or empty for any
	 * @param endGroups   the groups the trip should end in; ending in another counts as one more crossing. Empty for any.
	 * @return the fare path, or null if the stations aren't joined
	 */
	public Path find(long fromStation, long toStation, Set<String> startGroups, Set<String> endGroups) {
		return find(fromStation, toStation, startGroups, endGroups, null);
	}

	/**
	 * @param onlyGroup if not null, only that group's track is used; with a destination of -1, the farthest station
	 */
	private Path find(long fromStation, long toStation, Set<String> startGroups, Set<String> endGroups, String onlyGroup) {
		final Map<State, double[]> best = new HashMap<>();
		final Map<State, State> previous = new HashMap<>();
		final Map<State, Long> previousBlocks = new HashMap<>();
		final PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
		if (startGroups.isEmpty()) {
			final State start = new State(fromStation, null);
			best.put(start, new double[]{0, 0});
			queue.add(new Object[]{start, 0D});
		} else {
			for (final String group : startGroups) {
				final State start = new State(fromStation, group);
				best.put(start, new double[]{0, 0});
				queue.add(new Object[]{start, 0D});
			}
		}
		final Set<State> done = new HashSet<>();
		State bestEnd = null;
		double bestEndCost = Double.MAX_VALUE;
		long farthest = 0;
		while (!queue.isEmpty()) {
			final Object[] item = queue.poll();
			final State state = (State) item[0];
			if (!done.add(state)) {
				continue;
			}
			final double[] cost = best.get(state);
			if (toStation < 0) {
				farthest = Math.max(farthest, (long) cost[1]);
			} else if (state.station == toStation) {
				final double endCost = cost[0] + (endGroups.isEmpty() || state.group == null || endGroups.contains(state.group) ? 0 : CROSSING_COST);
				if (endCost < bestEndCost) {
					bestEndCost = endCost;
					bestEnd = state;
				}
			}
			for (final Join join : joins.getOrDefault(state.station, Collections.emptyList())) {
				if (onlyGroup != null && !onlyGroup.equals(join.group)) {
					continue;
				}
				final boolean crossing = state.group != null && !state.group.equals(join.group);
				final State next = new State(join.station, join.group);
				final double nextCost = cost[0] + (crossing ? CROSSING_COST : 0) + join.blocks;
				final double[] known = best.get(next);
				if (known == null || nextCost < known[0]) {
					best.put(next, new double[]{nextCost, cost[1] + join.blocks});
					previous.put(next, state);
					previousBlocks.put(next, join.blocks);
					queue.add(new Object[]{next, nextCost});
				}
			}
		}
		if (toStation < 0) {
			final Path path = new Path(Collections.emptyList(), 0);
			path.farthestBlocks = farthest;
			return path;
		}
		if (bestEnd == null) {
			return fromStation == toStation ? new Path(Collections.emptyList(), 0) : null;
		}
		// The parts in groups, from the end back
		final List<Part> parts = new ArrayList<>();
		State state = bestEnd;
		while (previous.containsKey(state)) {
			final long blocks = previousBlocks.get(state);
			if (!parts.isEmpty() && parts.get(0).group.equals(state.group)) {
				parts.set(0, new Part(state.group, parts.get(0).blocks + blocks));
			} else {
				parts.add(0, new Part(state.group, blocks));
			}
			state = previous.get(state);
		}
		int crossings = Math.max(0, parts.size() - 1);
		if (!parts.isEmpty() && !startGroups.isEmpty() && !startGroups.contains(parts.get(0).group)) {
			crossings++;
		}
		if (!parts.isEmpty() && !endGroups.isEmpty() && !endGroups.contains(parts.get(parts.size() - 1).group)) {
			crossings++;
		}
		return new Path(parts, crossings);
	}

	/**
	 * A fare path: its parts, each in one group, and how many times it crosses from one group to another.
	 */
	public static final class Path {

		public final List<Part> parts;
		public final int crossings;
		private long farthestBlocks;

		Path(List<Part> parts, int crossings) {
			this.parts = Collections.unmodifiableList(parts);
			this.crossings = crossings;
		}

		public long getBlocks() {
			long blocks = 0;
			for (final Part part : parts) {
				blocks += part.blocks;
			}
			return blocks;
		}
	}

	public static final class Part {

		public final String group;
		public final long blocks;

		public Part(String group, long blocks) {
			this.group = group;
			this.blocks = blocks;
		}
	}

	private static final class Join {

		private final long station;
		private final String group;
		private final long blocks;

		private Join(long station, String group, long blocks) {
			this.station = station;
			this.group = group;
			this.blocks = blocks;
		}
	}

	private static final class State {

		private final long station;
		private final String group;

		private State(long station, String group) {
			this.station = station;
			this.group = group;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof State && ((State) o).station == station && Objects.equals(((State) o).group, group);
		}

		@Override
		public int hashCode() {
			return Long.hashCode(station) * 31 + Objects.hashCode(group);
		}
	}
}
