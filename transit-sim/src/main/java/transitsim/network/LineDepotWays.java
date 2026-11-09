package transitsim.network;

import java.util.*;

/**
 * Ways out of a depot and back for a depot anywhere along the line (2026-11-09, user: out to the station nearest the
 * depot, then on by the rules). A train goes out to one of the stations nearest the siding, entered either way and turned
 * back at if need be; from there on it runs only the way the line runs, along the line's routes, turning back only where
 * the line does, from the end of one route to the start of the next. Back is the other way round: along the line from the
 * last stop to a station near the siding, turned back at if need be, then into the siding.
 * <p>
 * Running only the line's way is what keeps a train going out off the track of trains coming the other way: a way found
 * on the track alone took the line's other track against its trains to the first stop and met them head on.
 */
public final class LineDepotWays {

	private LineDepotWays() {
	}

	/**
	 * A way out of a siding to a platform, standing there the way the line runs it.
	 *
	 * @param stations the stations a train may go out by: the few nearest the siding among the stops of the routes
	 * @param routes   the plans of the depot's routes, found; the line's way at each section they run
	 * @param lines    the line of each route, for its turns, or null
	 * @return a plan from the siding to the platform, whose stops are the siding, every platform the train turns back at on
	 * the way, and the platform; the way straight in as {@link RouteLegs#planBetween} finds it if the platform isn't a stop
	 * of the routes
	 */
	public static RouteLegs.Plan planOut(long siding, long platform, Boolean platformAlong, Collection<Long> stations, List<RouteLegs.Plan> routes, List<String> lines, TrackSections.Snapshot snapshot, RouteLegs.LegSource legSource) {
		final Graph graph = new Graph(routes, lines, snapshot, legSource);
		final double[] best = graph.newCosts();
		final Arrival[] arrivals = new Arrival[best.length];
		final Set<Long> nearest = nearest(stations, station -> {
			double cost = Double.POSITIVE_INFINITY;
			for (final int fromWay : new int[]{1, 0}) {
				final LegRouter.Leg leg = graph.leg(new LegRouter.Request(siding, fromWay == 1, station, new long[0], null, false));
				cost = Math.min(cost, Math.min(leg.getCost(true), leg.getCost(false)));
			}
			return cost;
		});
		// Into the nearest station, as each route there arrives at it
		for (int node = 0; node < best.length; node++) {
			final long section = graph.section(node);
			if (!nearest.contains(section)) {
				continue;
			}
			final int way = graph.arrivalWay(node);
			Arrival arrival = null;
			for (final int fromWay : new int[]{1, 0}) {
				final LegRouter.Leg leg = graph.leg(new LegRouter.Request(siding, fromWay == 1, section, new long[0], null, false));
				final double straight = leg.getCost(way == 1);
				if (straight < (arrival == null ? Double.POSITIVE_INFINITY : arrival.cost)) {
					arrival = new Arrival(straight, fromWay, leg, way, false);
				}
				// Entered against the line and turned back at
				final double turned = leg.getCost(way != 1) + RailRouter.TURN_BACK_PENALTY;
				if (turned < (arrival == null ? Double.POSITIVE_INFINITY : arrival.cost)) {
					arrival = new Arrival(turned, fromWay, leg, 1 - way, true);
				}
			}
			if (arrival != null && Double.isFinite(arrival.cost)) {
				arrivals[node] = arrival;
				best[node] = arrival.cost;
			}
		}
		final int[] previous = graph.search(best, node -> graph.section(node) == platform && (platformAlong == null || platformAlong == (graph.arrivalWay(node) == 1)));
		final int target = previous == null ? -1 : previous[previous.length - 1];
		if (target < 0) {
			return RouteLegs.planBetween(siding, null, platform, platformAlong, legSource);
		}
		final List<Integer> nodes = graph.nodesTo(previous, target);
		final Arrival arrival = arrivals[nodes.get(0)];
		final List<long[]> steps = new ArrayList<>();
		final List<Integer> stops = new ArrayList<>();
		final List<LegRouter.Request> requests = new ArrayList<>();
		steps.add(new long[]{siding, arrival.fromWay, 0});
		stops.add(0);
		steps.addAll(arrival.leg.getSteps(arrival.way == 1));
		requests.add(arrival.leg.request);
		if (arrival.turned) {
			stops.add(steps.size() - 1);
			steps.add(RouteLegs.turnStep(graph.section(nodes.get(0)), graph.arrivalWay(nodes.get(0))));
		}
		graph.addSteps(nodes, steps, stops, requests);
		stops.add(steps.size() - 1);
		return graph.plan(best[target], steps, stops, requests);
	}

	/**
	 * A way back from a platform, standing there the way the line runs it, to a siding.
	 *
	 * @return a plan from the platform to the siding, whose stops are the platform, every platform the train turns back at
	 * on the way, and the siding; the way straight back as {@link RouteLegs#planBetween} finds it if the platform isn't a
	 * stop of the routes
	 */
	public static RouteLegs.Plan planBack(long platform, Boolean platformAlong, long siding, Collection<Long> stations, List<RouteLegs.Plan> routes, List<String> lines, TrackSections.Snapshot snapshot, RouteLegs.LegSource legSource) {
		final Graph graph = new Graph(routes, lines, snapshot, legSource);
		final double[] best = graph.newCosts();
		for (int node = 0; node < best.length; node++) {
			if (graph.section(node) == platform && (platformAlong == null || platformAlong == (graph.arrivalWay(node) == 1))) {
				best[node] = 0;
			}
		}
		final Set<Long> nearest = nearest(stations, station -> {
			double cost = Double.POSITIVE_INFINITY;
			for (final int leaveWay : new int[]{1, 0}) {
				final LegRouter.Leg leg = graph.leg(new LegRouter.Request(station, leaveWay == 1, siding, new long[0], null, false));
				cost = Math.min(cost, Math.min(leg.getCost(true), leg.getCost(false)));
			}
			return cost;
		});
		// From the nearest station reached along the line, left the way it was reached or turned back at
		final Map<Integer, Arrival> leaves = new HashMap<>();
		final int[] previous = graph.search(best, null, node -> {
			final long section = graph.section(node);
			if (!nearest.contains(section)) {
				return Double.POSITIVE_INFINITY;
			}
			final int way = graph.arrivalWay(node);
			Arrival leave = null;
			for (final int leaveWay : new int[]{way, 1 - way}) {
				final LegRouter.Leg leg = graph.leg(new LegRouter.Request(section, leaveWay == 1, siding, new long[0], null, false));
				for (final int toWay : new int[]{1, 0}) {
					final double cost = leg.getCost(toWay == 1) + (leaveWay == way ? 0 : RailRouter.TURN_BACK_PENALTY);
					if (cost < (leave == null ? Double.POSITIVE_INFINITY : leave.cost)) {
						leave = new Arrival(cost, leaveWay, leg, toWay, leaveWay != way);
					}
				}
			}
			if (leave == null) {
				return Double.POSITIVE_INFINITY;
			}
			leaves.put(node, leave);
			return leave.cost;
		});
		final int last = previous == null ? -1 : previous[previous.length - 1];
		if (last < 0) {
			return RouteLegs.planBetween(platform, platformAlong, siding, null, legSource);
		}
		final List<Integer> nodes = graph.nodesTo(previous, last);
		final Arrival leave = leaves.get(last);
		final List<long[]> steps = new ArrayList<>();
		final List<Integer> stops = new ArrayList<>();
		final List<LegRouter.Request> requests = new ArrayList<>();
		steps.add(new long[]{platform, graph.arrivalWay(nodes.get(0)), 0});
		stops.add(0);
		graph.addSteps(nodes, steps, stops, requests);
		if (leave.turned) {
			if (steps.size() > 1) {
				stops.add(steps.size() - 1);
			}
			steps.add(RouteLegs.turnStep(graph.section(last), leave.fromWay));
		}
		steps.addAll(leave.leg.getSteps(leave.way == 1));
		requests.add(leave.leg.request);
		stops.add(steps.size() - 1);
		return graph.plan(best[last] + leave.cost, steps, stops, requests);
	}

	/**
	 * The station nearest the siding by the way between them, as its platforms: those no more than a turn back further than
	 * the nearest, as the platforms of a station for each way are; any further, and a train would run the track between
	 * them, maybe against the line, rather than along the line.
	 */
	private static Set<Long> nearest(Collection<Long> stations, java.util.function.ToDoubleFunction<Long> cost) {
		final Map<Long, Double> costs = new LinkedHashMap<>();
		double least = Double.POSITIVE_INFINITY;
		for (final long station : stations) {
			final double stationCost = cost.applyAsDouble(station);
			costs.put(station, stationCost);
			least = Math.min(least, stationCost);
		}
		final Set<Long> nearest = new HashSet<>();
		for (final Map.Entry<Long, Double> entry : costs.entrySet()) {
			if (Double.isFinite(entry.getValue()) && entry.getValue() <= least + RailRouter.TURN_BACK_PENALTY) {
				nearest.add(entry.getKey());
			}
		}
		return nearest;
	}

	/**
	 * Into a station from the siding, or out of one to it: the leg, the way it leaves its start and arrives, and whether the
	 * train turns back at the station.
	 */
	private static final class Arrival {

		private final double cost;
		private final int fromWay;
		private final LegRouter.Leg leg;
		private final int way;
		private final boolean turned;

		private Arrival(double cost, int fromWay, LegRouter.Leg leg, int way, boolean turned) {
			this.cost = cost;
			this.fromWay = fromWay;
			this.leg = leg;
			this.way = way;
			this.turned = turned;
		}
	}

	/**
	 * The line as its routes' stops: from each stop to the route's next, and from the last of a route to the first of every
	 * route, turning back as the line does there.
	 */
	private static final class Graph {

		private final List<RouteLegs.Plan> routes;
		private final List<String> lines;
		private final TrackSections.Snapshot snapshot;
		private final RouteLegs.LegSource legSource;
		private final int[] offsets;
		private final List<LegRouter.Leg> consulted = new ArrayList<>();
		private final Map<Long, Turn> turns = new HashMap<>();

		private Graph(List<RouteLegs.Plan> routes, List<String> lines, TrackSections.Snapshot snapshot, RouteLegs.LegSource legSource) {
			this.routes = new ArrayList<>();
			this.lines = new ArrayList<>();
			for (int i = 0; i < routes.size(); i++) {
				if (routes.get(i).isFound()) {
					this.routes.add(routes.get(i));
					this.lines.add(lines == null || i >= lines.size() ? null : lines.get(i));
				}
			}
			this.snapshot = snapshot;
			this.legSource = legSource;
			offsets = new int[this.routes.size() + 1];
			for (int i = 0; i < this.routes.size(); i++) {
				offsets[i + 1] = offsets[i] + this.routes.get(i).stopSteps.length;
			}
		}

		private double[] newCosts() {
			final double[] costs = new double[offsets[offsets.length - 1]];
			Arrays.fill(costs, Double.POSITIVE_INFINITY);
			return costs;
		}

		private int route(int node) {
			int route = 0;
			while (offsets[route + 1] <= node) {
				route++;
			}
			return route;
		}

		private long[] stopStep(int node) {
			final int route = route(node);
			final RouteLegs.Plan plan = routes.get(route);
			return plan.steps.get(plan.stopSteps[node - offsets[route]]);
		}

		private long section(int node) {
			return stopStep(node)[0];
		}

		private int arrivalWay(int node) {
			return (int) stopStep(node)[1];
		}

		private LegRouter.Leg leg(LegRouter.Request request) {
			final LegRouter.Leg leg = legSource.get(request);
			consulted.add(leg);
			return leg;
		}

		private double sectionCost(long[] step) {
			final TrackSections.Section section = snapshot.sections.get(step[0]);
			return (section == null ? 0 : section.getCost()) + (step[2] == 1 ? RailRouter.TURN_BACK_PENALTY : 0);
		}

		/**
		 * From the last stop of one route to the first of another, turning back as the line does there: on at once, turned
		 * back at the platform itself, or on to turn back beyond it.
		 */
		private Turn turn(int fromRoute, int toRoute) {
			return turns.computeIfAbsent((long) fromRoute << 32 | toRoute, key -> {
				final int fromNode = offsets[fromRoute + 1] - 1;
				final int toNode = offsets[toRoute];
				final long from = section(fromNode);
				final int fromWay = arrivalWay(fromNode);
				final long to = section(toNode);
				final int toWay = arrivalWay(toNode);
				if (from == to) {
					return fromWay == toWay ? new Turn(0, Collections.emptyList(), null, false) : new Turn(RailRouter.TURN_BACK_PENALTY, Collections.singletonList(RouteLegs.turnStep(to, toWay)), null, true);
				}
				Turn best = null;
				for (final String line : lines.get(fromRoute) == null ? new String[]{null} : new String[]{lines.get(fromRoute), null}) {
					for (final int leaveWay : new int[]{fromWay, 1 - fromWay}) {
						final LegRouter.Leg leg = leg(new LegRouter.Request(from, leaveWay == 1, to, new long[0], line, false));
						final double cost = leg.getCost(toWay == 1) + (leaveWay == fromWay ? 0 : RailRouter.TURN_BACK_PENALTY);
						if (Double.isFinite(cost) && cost < (best == null ? Double.POSITIVE_INFINITY : best.cost)) {
							final List<long[]> steps = new ArrayList<>();
							if (leaveWay != fromWay) {
								steps.add(RouteLegs.turnStep(from, leaveWay));
							}
							steps.addAll(leg.getSteps(toWay == 1));
							best = new Turn(cost, steps, leg.request, leaveWay != fromWay);
						}
					}
					if (best != null) {
						break;
					}
				}
				return best == null ? new Turn(Double.POSITIVE_INFINITY, Collections.emptyList(), null, false) : best;
			});
		}

		/**
		 * Cheapest costs over the line from the costs given, until a target is reached.
		 *
		 * @return the node before each, or -1, with the target last; null if none is reached
		 */
		private int[] search(double[] best, java.util.function.IntPredicate target) {
			return search(best, target, null);
		}

		/**
		 * @param finish what finishing at a node costs further, or null to finish at the first target reached
		 */
		private int[] search(double[] best, java.util.function.IntPredicate target, java.util.function.IntToDoubleFunction finish) {
			final int count = best.length;
			final int[] previous = new int[count + 1];
			Arrays.fill(previous, -1);
			final boolean[] done = new boolean[count];
			double bestTotal = Double.POSITIVE_INFINITY;
			while (true) {
				int node = -1;
				for (int i = 0; i < count; i++) {
					if (!done[i] && Double.isFinite(best[i]) && (node < 0 || best[i] < best[node])) {
						node = i;
					}
				}
				if (node < 0 || best[node] >= bestTotal) {
					break;
				}
				done[node] = true;
				if (finish == null) {
					if (target.test(node)) {
						previous[count] = node;
						return previous;
					}
				} else {
					final double total = best[node] + finish.applyAsDouble(node);
					if (total < bestTotal) {
						bestTotal = total;
						previous[count] = node;
					}
				}
				final int route = route(node);
				final RouteLegs.Plan plan = routes.get(route);
				final int stop = node - offsets[route];
				if (stop + 1 < plan.stopSteps.length) {
					double cost = 0;
					for (int i = plan.stopSteps[stop] + 1; i <= plan.stopSteps[stop + 1]; i++) {
						cost += sectionCost(plan.steps.get(i));
					}
					relax(best, previous, node, node + 1, cost);
				} else {
					for (int toRoute = 0; toRoute < routes.size(); toRoute++) {
						relax(best, previous, node, offsets[toRoute], turn(route, toRoute).cost);
					}
				}
			}
			return previous[count] < 0 ? null : previous;
		}

		private static void relax(double[] best, int[] previous, int from, int to, double cost) {
			if (best[from] + cost < best[to]) {
				best[to] = best[from] + cost;
				previous[to] = from;
			}
		}

		private List<Integer> nodesTo(int[] previous, int target) {
			final List<Integer> nodes = new ArrayList<>();
			for (int node = target; node >= 0; node = previous[node]) {
				nodes.add(0, node);
			}
			return nodes;
		}

		/**
		 * The steps from the first node to the last along the line; a platform turned back at where one route ends is a stop.
		 */
		private void addSteps(List<Integer> nodes, List<long[]> steps, List<Integer> stops, List<LegRouter.Request> requests) {
			for (int i = 0; i + 1 < nodes.size(); i++) {
				final int node = nodes.get(i);
				final int next = nodes.get(i + 1);
				final int route = route(node);
				final RouteLegs.Plan plan = routes.get(route);
				if (next == node + 1 && route(next) == route) {
					final int stop = node - offsets[route];
					steps.addAll(plan.steps.subList(plan.stopSteps[stop] + 1, plan.stopSteps[stop + 1] + 1));
				} else {
					final Turn turn = turn(route, route(next));
					if (turn.turnedAtPlatform && steps.size() > 1) {
						stops.add(steps.size() - 1);
					}
					steps.addAll(turn.steps);
					if (turn.request != null) {
						requests.add(turn.request);
					}
				}
			}
		}

		private RouteLegs.Plan plan(double cost, List<long[]> steps, List<Integer> stops, List<LegRouter.Request> requests) {
			final int[] stopSteps = new int[stops.size()];
			for (int i = 0; i < stopSteps.length; i++) {
				stopSteps[i] = stops.get(i);
			}
			return new RouteLegs.Plan(stopSteps.length, cost, steps, stopSteps, requests, 0, consulted);
		}
	}

	/**
	 * Turning from one route's end to another's start: what it costs, the steps after the last stop, the leg if one, and
	 * whether the train turns back at that platform.
	 */
	private static final class Turn {

		private final double cost;
		private final List<long[]> steps;
		private final LegRouter.Request request;
		private final boolean turnedAtPlatform;

		private Turn(double cost, List<long[]> steps, LegRouter.Request request, boolean turnedAtPlatform) {
			this.cost = cost;
			this.steps = steps;
			this.request = request;
			this.turnedAtPlatform = turnedAtPlatform;
		}
	}
}
