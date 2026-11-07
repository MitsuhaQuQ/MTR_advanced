package transitsim.network;

import java.util.*;

/**
 * Finds a leg (stages document round 2): the way from one stop of a route to the next, through the key nodes listed
 * between them, on the track sections rather than rail by rail. It keeps {@link RailRouter}'s rules: a train passes a
 * node only onto a section leaving it another way than it came (any way at the end of a turn back rail), may turn back
 * right after a stop at a cost, and pays a cost for running over another platform; a section is costed as its rails'
 * lengths over their speed limits. A route runs only on sections its line owns ({@link TrackOwnership}).
 * <p>
 * A leg starts on its first stop run one way, and is found to its last stop run either way, so a whole route is the
 * best choice of ways at each stop ({@link RouteLegs}). The search notes every section it touched and every node it
 * went on from: what it found depends on nothing else, so a change elsewhere never makes it wrong, and a change there
 * finds it again ({@link LegCache}).
 * <p>
 * A pure function of a snapshot: it can run on any thread.
 */
public final class LegRouter {

	/**
	 * Changed whenever the rules change, so legs found by older rules are found again.
	 */
	public static final int VERSION = 1;

	private LegRouter() {
	}

	/**
	 * Where nodes are, for the estimate of what is left.
	 */
	public interface Coordinates {

		int getX(long node);

		int getZ(long node);
	}

	/**
	 * What a leg is found from.
	 */
	public static final class Request {

		public final long fromSection;
		public final boolean fromAlong;
		public final long toSection;
		public final long[] waypoints;
		/**
		 * The route's line, or null for a connecting line, which runs anywhere.
		 */
		public final String line;
		public final long key;

		public Request(long fromSection, boolean fromAlong, long toSection, long[] waypoints, String line) {
			this.fromSection = fromSection;
			this.fromAlong = fromAlong;
			this.toSection = toSection;
			this.waypoints = waypoints.clone();
			this.line = line;
			long hash = mix(VERSION, fromSection);
			hash = mix(hash, fromAlong ? 1 : 2);
			hash = mix(hash, toSection);
			hash = mix(hash, waypoints.length);
			for (final long waypoint : waypoints) {
				hash = mix(hash, waypoint);
			}
			key = mix(hash, line == null ? 0 : line.hashCode() * 31L + line.length());
		}
	}

	/**
	 * A leg as found, for its last stop run along its section's order and against it.
	 */
	public static final class Leg {

		public final Request request;
		/**
		 * The version of the network it was found on.
		 */
		public final long version;
		private final double[] costs;
		private final List<long[]>[] steps;
		/**
		 * Every section the search touched, in order, and every node it went on from.
		 */
		public final long[] domainSections;
		public final long[] domainNodes;
		/**
		 * A key node listed that isn't where sections meet, so no leg can pass it, or 0.
		 */
		public final long badWaypoint;

		@SuppressWarnings("unchecked")
		private Leg(Request request, long version, double[] costs, List<long[]>[] steps, long[] domainSections, long[] domainNodes, long badWaypoint) {
			this.request = request;
			this.version = version;
			this.costs = costs;
			this.steps = steps;
			this.domainSections = domainSections;
			this.domainNodes = domainNodes;
			this.badWaypoint = badWaypoint;
		}

		/**
		 * @return the leg as saved: what it was found from, its costs and steps, and its search domain
		 */
		public byte[] toBytes() {
			try {
				final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
				final java.io.DataOutputStream output = new java.io.DataOutputStream(bytes);
				output.writeInt(VERSION);
				output.writeLong(request.fromSection);
				output.writeBoolean(request.fromAlong);
				output.writeLong(request.toSection);
				writeLongs(output, request.waypoints);
				output.writeBoolean(request.line != null);
				if (request.line != null) {
					output.writeUTF(request.line);
				}
				for (int i = 0; i < 2; i++) {
					output.writeDouble(costs[i]);
					output.writeInt(steps[i] == null ? -1 : steps[i].size());
					if (steps[i] != null) {
						for (final long[] step : steps[i]) {
							output.writeLong(step[0]);
							output.writeByte((int) (step[1] | step[2] << 1));
						}
					}
				}
				writeLongs(output, domainSections);
				writeLongs(output, domainNodes);
				output.writeLong(badWaypoint);
				return bytes.toByteArray();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}

		/**
		 * @param version the version of the network it is taken in on
		 * @return the leg, or null if it was found by other rules
		 */
		@SuppressWarnings("unchecked")
		public static Leg fromBytes(byte[] data, long version) throws java.io.IOException {
			final java.io.DataInputStream input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
			if (input.readInt() != VERSION) {
				return null;
			}
			final long fromSection = input.readLong();
			final boolean fromAlong = input.readBoolean();
			final long toSection = input.readLong();
			final long[] waypoints = readLongs(input);
			final String line = input.readBoolean() ? input.readUTF() : null;
			final double[] costs = new double[2];
			final List<long[]>[] steps = new List[2];
			for (int i = 0; i < 2; i++) {
				costs[i] = input.readDouble();
				final int count = input.readInt();
				if (count > 1 << 20) {
					throw new java.io.IOException("Bad step count " + count);
				}
				if (count >= 0) {
					final List<long[]> list = new ArrayList<>(count);
					for (int j = 0; j < count; j++) {
						final long section = input.readLong();
						final int flags = input.readByte();
						list.add(new long[]{section, flags & 1, flags >> 1 & 1});
					}
					steps[i] = Collections.unmodifiableList(list);
				}
			}
			final long[] domainSections = readLongs(input);
			final long[] domainNodes = readLongs(input);
			final long badWaypoint = input.readLong();
			return new Leg(new Request(fromSection, fromAlong, toSection, waypoints, line), version, costs, steps, domainSections, domainNodes, badWaypoint);
		}

		/**
		 * @param toAlong whether the last stop is run along its section's order
		 * @return the cost to the last stop run that way, or infinity if there is no way
		 */
		public double getCost(boolean toAlong) {
			return costs[toAlong ? 1 : 0];
		}

		/**
		 * @return each section run after the first stop, as {section, 1 if along its order, 1 if turned back onto it}, the
		 * last being the last stop; null if there is no way
		 */
		public List<long[]> getSteps(boolean toAlong) {
			return steps[toAlong ? 1 : 0];
		}
	}

	/**
	 * @param maxChordPerCost the most straight distance a cost of 1 covers anywhere ({@link #getMaxChordPerCost})
	 */
	@SuppressWarnings("unchecked")
	public static Leg find(TrackSections.Snapshot graph, long version, Map<Long, Set<String>> owners, Request request, Coordinates coordinates, double maxChordPerCost) {
		final Set<Long> domainSections = new TreeSet<>();
		final Set<Long> domainNodes = new TreeSet<>();
		final double[] costs = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
		final List<long[]>[] steps = new List[2];
		domainSections.add(request.fromSection);
		domainSections.add(request.toSection);
		final TrackSections.Section from = graph.sections.get(request.fromSection);
		final TrackSections.Section to = graph.sections.get(request.toSection);
		if (from == null || to == null) {
			return new Leg(request, version, costs, steps, toArray(domainSections), toArray(domainNodes), 0);
		}
		// Key nodes must be where sections meet, or no search can pass them
		for (final long waypoint : request.waypoints) {
			domainNodes.add(waypoint);
			if (graph.getSectionsAt(waypoint).length == 0) {
				return new Leg(request, version, costs, steps, toArray(domainSections), toArray(domainNodes), waypoint);
			}
		}
		// The same stop twice in a row with nothing between: done at once, the same way
		if (request.fromSection == request.toSection && request.waypoints.length == 0) {
			costs[request.fromAlong ? 1 : 0] = 0;
			steps[request.fromAlong ? 1 : 0] = Collections.emptyList();
			return new Leg(request, version, costs, steps, toArray(domainSections), toArray(domainNodes), 0);
		}

		final Search search = new Search(graph, owners, request, coordinates, Math.max(maxChordPerCost, Double.MIN_VALUE), domainSections);
		// From the first stop run its way, or turned back onto it at once at a cost
		search.push(null, from, request.fromAlong, 0, 0, false, true);
		search.push(null, from, !request.fromAlong, 0, from.getCost() + RailRouter.TURN_BACK_PENALTY, true, true);
		int found = 0;
		while (!search.queue.isEmpty() && found < 2) {
			final State state = search.queue.poll();
			if (state.stale) {
				continue;
			}
			if (state.stage == search.targetCount) {
				final int index = state.along ? 1 : 0;
				if (steps[index] == null) {
					costs[index] = state.cost;
					steps[index] = unwind(state);
					found++;
				}
				continue;
			}
			search.expand(state, domainNodes);
		}
		return new Leg(request, version, costs, steps, toArray(domainSections), toArray(domainNodes), 0);
	}

	/**
	 * @return the most straight distance a cost of 1 covers on any rail, for an estimate that never says more than is left
	 */
	public static double getMaxChordPerCost(TrackSections.Snapshot graph, Coordinates coordinates) {
		double max = 0;
		for (final TrackSections.Section section : graph.sections.values()) {
			for (int i = 0; i < section.getRailCount(); i++) {
				final TrackSections.Rail rail = section.getRail(i);
				final double cost = rail.getCost();
				if (cost > 0) {
					max = Math.max(max, Math.hypot(coordinates.getX(rail.node1) - coordinates.getX(rail.node2), coordinates.getZ(rail.node1) - coordinates.getZ(rail.node2)) / cost);
				}
			}
		}
		return max;
	}

	private static List<long[]> unwind(State last) {
		final List<long[]> steps = new ArrayList<>();
		for (State state = last; state != null; state = state.parent) {
			// The first stop run its way is where the leg starts, not a step of it; turned back onto it at once is
			if (state.parent == null && !state.reversal) {
				break;
			}
			steps.add(new long[]{state.section.hash, state.along ? 1 : 0, state.reversal ? 1 : 0});
		}
		Collections.reverse(steps);
		return Collections.unmodifiableList(steps);
	}

	private static void writeLongs(java.io.DataOutputStream output, long[] values) throws java.io.IOException {
		output.writeInt(values.length);
		for (final long value : values) {
			output.writeLong(value);
		}
	}

	private static long[] readLongs(java.io.DataInputStream input) throws java.io.IOException {
		final int count = input.readInt();
		if (count < 0 || count > 1 << 22) {
			throw new java.io.IOException("Bad count " + count);
		}
		final long[] values = new long[count];
		for (int i = 0; i < count; i++) {
			values[i] = input.readLong();
		}
		return values;
	}

	private static long[] toArray(Set<Long> values) {
		return values.stream().mapToLong(Long::longValue).toArray();
	}

	private static long mix(long value1, long value2) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
		hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
		return hash ^ hash >>> 31;
	}

	private static final class State {

		private final State parent;
		private final TrackSections.Section section;
		private final boolean along;
		private final int stage;
		private final double cost;
		private final double estimate;
		private final boolean reversal;
		private boolean stale;

		private State(State parent, TrackSections.Section section, boolean along, int stage, double cost, double estimate, boolean reversal) {
			this.parent = parent;
			this.section = section;
			this.along = along;
			this.stage = stage;
			this.cost = cost;
			this.estimate = estimate;
			this.reversal = reversal;
		}

		/**
		 * @return the node it leaves the section at
		 */
		private long exit() {
			return along ? section.getEnd() : section.getStart();
		}
	}

	private static final class Search {

		private final TrackSections.Snapshot graph;
		private final Map<Long, Set<String>> owners;
		private final Request request;
		private final Coordinates coordinates;
		private final double speed;
		private final Set<Long> domainSections;
		private final int targetCount;
		private final double[] remainingAfter;
		private final Map<String, State> best = new HashMap<>();
		private final PriorityQueue<State> queue = new PriorityQueue<>((state1, state2) -> {
			final int compare = Double.compare(state1.estimate, state2.estimate);
			if (compare != 0) {
				return compare;
			}
			if (state1.stage != state2.stage) {
				return state2.stage - state1.stage;
			}
			final int compareHash = Long.compare(state1.section.hash, state2.section.hash);
			if (compareHash != 0) {
				return compareHash;
			}
			return Boolean.compare(state1.along, state2.along) * 2 + Boolean.compare(state1.reversal, state2.reversal);
		});

		private Search(TrackSections.Snapshot graph, Map<Long, Set<String>> owners, Request request, Coordinates coordinates, double speed, Set<Long> domainSections) {
			this.graph = graph;
			this.owners = owners;
			this.request = request;
			this.coordinates = coordinates;
			this.speed = speed;
			this.domainSections = domainSections;
			// The key nodes, then the last stop
			targetCount = request.waypoints.length + 1;
			remainingAfter = new double[targetCount + 1];
			for (int i = targetCount - 2; i >= 0; i--) {
				remainingAfter[i] = remainingAfter[i + 1] + chordBetween(i, i + 1) / speed;
			}
		}

		private void expand(State state, Set<Long> domainNodes) {
			final long node = state.exit();
			domainNodes.add(node);
			// The facing it arrives with, as the way its last rail leaves the node
			final int arriving = state.section.getEndFacing(!state.along);
			final boolean canTurnBack = state.section.getType() == SectionType.TURN_BACK;
			for (final long hash : graph.getSectionsAt(node)) {
				final TrackSections.Section next = graph.sections.get(hash);
				domainSections.add(hash);
				for (final boolean along : new boolean[]{true, false}) {
					if ((along ? next.getStart() : next.getEnd()) != node) {
						continue;
					}
					if (!canTurnBack && next.getEndFacing(along) == arriving) {
						continue;
					}
					final boolean reversing = next.hash == state.section.hash && along != state.along;
					final boolean isTarget = isNextTarget(state.stage, next, along);
					final boolean otherPlatform = (next.getType() == SectionType.PLATFORM || next.getType() == SectionType.SIDING) && !isTarget;
					final double penalty = (reversing ? RailRouter.TURN_BACK_PENALTY : 0) + (otherPlatform ? RailRouter.OTHER_PLATFORM_PENALTY : 0);
					push(state, next, along, state.stage, state.cost + next.getCost() + penalty, false, false);
				}
			}
		}

		/**
		 * @param start a state the leg starts with, on its first stop, which needn't be the route's line's
		 */
		private void push(State parent, TrackSections.Section section, boolean along, int stageBefore, double cost, boolean reversal, boolean start) {
			domainSections.add(section.hash);
			// The first stop is started on whichever way it is laid, as RailRouter starts on it
			if (!start && !section.canRun(along)) {
				return;
			}
			final boolean stop = section.hash == request.fromSection || section.hash == request.toSection;
			if (!start && !stop && !TrackOwnership.mayRun(owners.get(section.hash), request.line)) {
				return;
			}
			int stage = stageBefore;
			if (!reversal && !start) {
				while (stage < targetCount && matches(stage, section, along)) {
					stage++;
				}
			}
			final String key = stage + "/" + section.hash + "/" + along + "/" + reversal;
			final State existing = best.get(key);
			if (existing != null && existing.cost <= cost) {
				return;
			}
			if (existing != null) {
				existing.stale = true;
			}
			final long exit = along ? section.getEnd() : section.getStart();
			final double remaining = stage == targetCount ? 0 : chordFrom(exit, stage) / speed + remainingAfter[stage];
			final State state = new State(parent, section, along, stage, cost, cost + remaining, reversal);
			best.put(key, state);
			queue.add(state);
		}

		/**
		 * @return whether running the section that way reaches the target: a key node it leaves the section at, or the last
		 * stop either way
		 */
		private boolean matches(int stage, TrackSections.Section section, boolean along) {
			if (stage < request.waypoints.length) {
				return (along ? section.getEnd() : section.getStart()) == request.waypoints[stage];
			}
			return section.hash == request.toSection;
		}

		private boolean isNextTarget(int stage, TrackSections.Section section, boolean along) {
			return stage < targetCount && matches(stage, section, along);
		}

		private double chordFrom(long node, int stage) {
			if (stage < request.waypoints.length) {
				return distance(node, request.waypoints[stage]);
			}
			final TrackSections.Section to = graph.sections.get(request.toSection);
			return Math.min(distance(node, to.getStart()), distance(node, to.getEnd()));
		}

		private double chordBetween(int stage1, int stage2) {
			final long[] ends1 = endsOf(stage1);
			final long[] ends2 = endsOf(stage2);
			double min = Double.POSITIVE_INFINITY;
			for (final long end1 : ends1) {
				for (final long end2 : ends2) {
					min = Math.min(min, distance(end1, end2));
				}
			}
			return min;
		}

		private long[] endsOf(int stage) {
			if (stage < request.waypoints.length) {
				return new long[]{request.waypoints[stage]};
			}
			final TrackSections.Section to = graph.sections.get(request.toSection);
			return new long[]{to.getStart(), to.getEnd()};
		}

		private double distance(long node1, long node2) {
			return Math.hypot(coordinates.getX(node1) - coordinates.getX(node2), coordinates.getZ(node1) - coordinates.getZ(node2));
		}
	}
}
