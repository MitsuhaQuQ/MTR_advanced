package transitsim.fare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The network fares are worked out on (design document 6.13): stations joined by the track between the stops of each
 * route, each join marked with the route's group. Only the stations a trip enters and leaves at count, not the trains
 * taken. A join can be taken either way.
 * <p>
 * The fare path crosses the fewest groups and, of those, is the shortest. Track several groups share counts as the group
 * a trip is already in, so a trip isn't split where it needn't be.
 */
public final class FareNetwork {

	private static final double CROSSING_COST = 1E12;

	private final Map<Long, List<Join>> joins = new HashMap<>();
	private final Map<String, Long> longest = new HashMap<>();
	/**
	 * Paths found, the most recently used kept (2026-11-12): trips between the same stations are charged again and again.
	 */
	private final Map<CacheKey, Path> cache = new LinkedHashMap<CacheKey, Path>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<CacheKey, Path> eldest) {
			return size() > MAX_CACHED_PATHS;
		}
	};
	private static final int MAX_CACHED_PATHS = 8192;
	private static final Path NO_PATH = new Path(Collections.emptyList(), -1);

	/**
	 * @param legs the track between two stops in a row of a route: its group, the two stations and its length in blocks
	 */
	public FareNetwork(Collection<Leg> legs) {
		final Map<List<Object>, Long> shortest = new HashMap<>();
		for (final Leg leg : legs) {
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
	public synchronized long getLongestBlocks(String group) {
		return longest.computeIfAbsent(group, key -> {
			long most = 0;
			for (final long station : joins.keySet()) {
				if (!getGroups(station).contains(key)) {
					continue;
				}
				most = Math.max(most, farthest(station, key));
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
		return find(fromStation, toStation, startGroups, Collections.singletonList(endGroups))[0];
	}

	/**
	 * The fare paths to one station for several sets of end groups at once (2026-11-12): one search from the start serves
	 * them all, as only what ending in a group costs differs, and it stops once nothing still to be reached can end better
	 * for any of them. Each path is the one {@link #find(long, long, Set, Set)} gives for its set. Paths found are kept
	 * for the network, which is made again when the routes change.
	 *
	 * @param startGroups  the groups the trip may start in, or empty for any
	 * @param endGroupSets for each path wanted, the groups the trip should end in (empty for any)
	 * @return the fare path for each set of end groups, or null where the stations aren't joined
	 */
	public Path[] find(long fromStation, long toStation, Set<String> startGroups, List<Set<String>> endGroupSets) {
		final Path[] paths = new Path[endGroupSets.size()];
		final List<Integer> missing = new ArrayList<>();
		synchronized (cache) {
			for (int i = 0; i < paths.length; i++) {
				final Path cached = cache.get(new CacheKey(fromStation, toStation, startGroups, endGroupSets.get(i)));
				if (cached == null) {
					missing.add(i);
				} else {
					paths[i] = cached == NO_PATH ? null : cached;
				}
			}
		}
		if (missing.isEmpty()) {
			return paths;
		}
		final List<Set<String>> searched = new ArrayList<>();
		missing.forEach(i -> searched.add(endGroupSets.get(i)));
		final Path[] found = search(fromStation, toStation, startGroups, searched);
		synchronized (cache) {
			for (int j = 0; j < found.length; j++) {
				final int i = missing.get(j);
				paths[i] = found[j];
				cache.put(new CacheKey(fromStation, toStation, startGroups, endGroupSets.get(i)), found[j] == null ? NO_PATH : found[j]);
			}
		}
		return paths;
	}

	/**
	 * Dijkstra over (station, group last in), the cost being the crossings (each {@link #CROSSING_COST}) then the
	 * distance; ending in a group not wanted is one more crossing. The search is the same whatever the end groups, so it
	 * stops once the cost reached is no less than the best end of every set: nothing later can be strictly better.
	 */
	private Path[] search(long fromStation, long toStation, Set<String> startGroups, List<Set<String>> endGroupSets) {
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
		final int sets = endGroupSets.size();
		final State[] bestEnd = new State[sets];
		final double[] bestEndCost = new double[sets];
		Arrays.fill(bestEndCost, Double.MAX_VALUE);
		double worstBestEnd = Double.MAX_VALUE;
		final Set<State> done = new HashSet<>();
		while (!queue.isEmpty()) {
			final Object[] item = queue.poll();
			final State state = (State) item[0];
			if (!done.add(state)) {
				continue;
			}
			final double[] cost = best.get(state);
			if (cost[0] >= worstBestEnd) {
				break;
			}
			if (state.station == toStation) {
				boolean changed = false;
				for (int i = 0; i < sets; i++) {
					final Set<String> endGroups = endGroupSets.get(i);
					final double endCost = cost[0] + (endGroups.isEmpty() || state.group == null || endGroups.contains(state.group) ? 0 : CROSSING_COST);
					if (endCost < bestEndCost[i]) {
						bestEndCost[i] = endCost;
						bestEnd[i] = state;
						changed = true;
					}
				}
				if (changed) {
					double worst = 0;
					for (final double endCost : bestEndCost) {
						worst = Math.max(worst, endCost);
					}
					worstBestEnd = worst;
				}
			}
			for (final Join join : joins.getOrDefault(state.station, Collections.emptyList())) {
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
		final Path[] paths = new Path[sets];
		for (int i = 0; i < sets; i++) {
			if (bestEnd[i] == null) {
				paths[i] = fromStation == toStation ? new Path(Collections.emptyList(), 0) : null;
			} else {
				paths[i] = makePath(bestEnd[i], previous, previousBlocks, startGroups, endGroupSets.get(i));
			}
		}
		return paths;
	}

	/**
	 * @return the path ending at a state, in parts by group, put together from the end back
	 */
	private static Path makePath(State end, Map<State, State> previous, Map<State, Long> previousBlocks, Set<String> startGroups, Set<String> endGroups) {
		final List<Part> parts = new ArrayList<>();
		State state = end;
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
	 * @return how far the farthest station is from a station along one group's own track
	 */
	private long farthest(long fromStation, String onlyGroup) {
		final Map<Long, Long> best = new HashMap<>();
		final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
		best.put(fromStation, 0L);
		queue.add(new long[]{fromStation, 0});
		long farthest = 0;
		while (!queue.isEmpty()) {
			final long[] item = queue.poll();
			if (item[1] > best.get(item[0])) {
				continue;
			}
			farthest = Math.max(farthest, item[1]);
			for (final Join join : joins.getOrDefault(item[0], Collections.emptyList())) {
				if (onlyGroup.equals(join.group)) {
					final long next = item[1] + join.blocks;
					final Long known = best.get(join.station);
					if (known == null || next < known) {
						best.put(join.station, next);
						queue.add(new long[]{join.station, next});
					}
				}
			}
		}
		return farthest;
	}

	public static final class Leg {

		public final String group;
		public final long fromStation;
		public final long toStation;
		public final long blocks;

		public Leg(String group, long fromStation, long toStation, long blocks) {
			this.group = group;
			this.fromStation = fromStation;
			this.toStation = toStation;
			this.blocks = blocks;
		}
	}

	/**
	 * A fare path: its parts, each in one group, and how many times it crosses from one group to another.
	 */
	public static final class Path {

		public final List<Part> parts;
		public final int crossings;

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

	private static final class CacheKey {

		private final long fromStation;
		private final long toStation;
		private final Set<String> startGroups;
		private final Set<String> endGroups;

		private CacheKey(long fromStation, long toStation, Set<String> startGroups, Set<String> endGroups) {
			this.fromStation = fromStation;
			this.toStation = toStation;
			this.startGroups = new HashSet<>(startGroups);
			this.endGroups = new HashSet<>(endGroups);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof CacheKey)) {
				return false;
			}
			final CacheKey other = (CacheKey) o;
			return other.fromStation == fromStation && other.toStation == toStation && other.startGroups.equals(startGroups) && other.endGroups.equals(endGroups);
		}

		@Override
		public int hashCode() {
			return ((Long.hashCode(fromStation) * 31 + Long.hashCode(toStation)) * 31 + startGroups.hashCode()) * 31 + endGroups.hashCode();
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
