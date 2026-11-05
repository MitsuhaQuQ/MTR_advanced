package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;
import transitsim.sim.DayRun;
import transitsim.sim.SimDataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duties the planner works out, checked by the running kernel (S6): the planner only counts trains with rough times for
 * turning back, so whether the duties really run is what a day simulated with them shows.
 */
public final class PlanCheck {

	public final DayRun dayRun;
	public final int notRun;
	public final int late;
	public final int deadlocks;
	/**
	 * Waits for a platform, holds on the line and stops longer than planned.
	 */
	public final int held;

	private PlanCheck(DayRun dayRun) {
		this.dayRun = dayRun;
		int newNotRun = 0;
		int newLate = 0;
		int newDeadlocks = 0;
		int newHeld = 0;
		for (final DayRun.Problem problem : dayRun.problems) {
			switch (problem.kind) {
				case NOT_RUN:
					newNotRun++;
					break;
				case LATE:
					newLate++;
					break;
				case DEADLOCK:
					newDeadlocks++;
					break;
				default:
					newHeld++;
					break;
			}
		}
		notRun = newNotRun;
		late = newLate;
		deadlocks = newDeadlocks;
		held = newHeld;
	}

	/**
	 * @return true if every service ran, on time and without trains locking each other
	 */
	public boolean passes() {
		return notRun == 0 && late == 0 && deadlocks == 0;
	}

	/**
	 * Works out a depot's duties from the world's data the way the editor does: the services of its routes with the times
	 * of its sidings' paths. The data has no stations, so trains only turn back on the platform they arrive at.
	 */
	public static DutyGenerator.Result generate(SimDataset dataset, long depotId, int dayStartMillis) {
		final SimDataset.DepotData depot = findDepot(dataset, depotId);
		if (depot == null) {
			return new DutyGenerator.Result();
		}
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		final List<PlanRoute> depotRoutes = new ArrayList<>();
		for (final long routeId : depot.routeIds) {
			if (routes.containsKey(routeId)) {
				depotRoutes.add(routes.get(routeId));
			}
		}
		final Map<Long, long[][]> fastestTimes = dataset.getFastestTimes(depotId);
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(depotRoutes, route -> fastestTimes.get(route.getRouteId()), dayStartMillis, route -> {
		});
		final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		dataset.sidings.stream().filter(siding -> siding.depotId == depotId).forEach(siding -> sidings.add(new DutyGenerator.SidingInput(siding.id, siding.maxTrains >= SimDataset.UNLIMITED_TRAINS ? -1 : siding.maxTrains)));
		return DutyGenerator.generateSpread(services, sidings, Collections.emptyMap(), depot.cycleRoutes ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS);
	}

	/**
	 * Runs a day with these duties for the depot, on a copy of the data. The data needs the paths of the duties' shapes, as
	 * the game's depot finds them along the rails.
	 *
	 * @param slots the duties of each siding, as {@link DutyGenerator.Result#sidings} has them
	 */
	public static PlanCheck check(SimDataset dataset, long depotId, Map<Long, List<DepotDuties.Slot>> slots, long dayStart) {
		final SimDataset copy = SimDataset.fromBytes(dataset.toBytes());
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(slots);
		copy.replaceDuties(depotId, duties.toBytes());
		final Set<Long> routeIds = new HashSet<>();
		final SimDataset.DepotData depot = findDepot(dataset, depotId);
		if (depot != null) {
			for (final long routeId : depot.routeIds) {
				routeIds.add(routeId);
			}
		}
		return new PlanCheck(DayRun.run(copy, dayStart, routeIds, Collections.emptyList(), () -> false));
	}

	/**
	 * Works out the duties of the depot's planning unit by operating line, as the game's {@code LineDuties} does for depots
	 * running by a timetable: the depots running routes of the same lines, and lines joined by through services, planned
	 * at once. A siding's trains start at the first stops of its own depot's routes, the nearer the sooner, measured in a
	 * straight line between the nodes as the game measures them. Unlike the game, the data has no vehicles' types of
	 * service, so all the unit's sidings run all its routes, and no duties set by hand to leave out.
	 *
	 * @return the duties of every siding of the unit
	 */
	public static DutyGenerator.Result generateByLine(SimDataset dataset, long depotId, int dayStartMillis) {
		final Unit unit = unitOf(dataset, depotId);
		if (unit.depots.isEmpty()) {
			return new DutyGenerator.Result();
		}
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		final List<PlanRoute> unitRoutes = new ArrayList<>();
		unit.routeIds.forEach(routeId -> {
			if (routes.containsKey(routeId)) {
				unitRoutes.add(routes.get(routeId));
			}
		});
		final Map<Long, long[][]> fastestTimes = new java.util.TreeMap<>();
		unit.depots.forEach(depot -> dataset.getFastestTimes(depot.id).forEach(fastestTimes::putIfAbsent));
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(unitRoutes, route -> fastestTimes.get(route.getRouteId()), dayStartMillis, route -> {
		});

		// Where each platform is, from the start of a stop's track on any path
		final Map<Long, Long> platformNodes = new java.util.HashMap<>();
		dataset.sidings.forEach(siding -> {
			siding.path.forEach(track -> addPlatformNode(platformNodes, track));
			siding.tripPaths.forEach(tripPath -> tripPath.path.forEach(track -> addPlatformNode(platformNodes, track)));
		});
		final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		boolean staysOnLine = false;
		for (final SimDataset.DepotData depot : unit.depots) {
			staysOnLine |= depot.cycleRoutes;
			for (final SimDataset.SidingData siding : dataset.sidings) {
				if (siding.depotId != depot.id || siding.path.isEmpty()) {
					continue;
				}
				final double[] sidingPosition = transitsim.sim.ThroatLocks.position(siding.path.get(0).getStartNode());
				final Map<Long, Long> links = new java.util.TreeMap<>();
				for (final long routeId : depot.routeIds) {
					final PlanRoute route = routes.get(routeId);
					final Long platformNode = route == null || route.getStops().isEmpty() ? null : platformNodes.get(route.getStops().get(0).getPlatformId());
					if (platformNode != null) {
						final double[] platformPosition = transitsim.sim.ThroatLocks.position(platformNode);
						links.put(route.getStops().get(0).getPlatformId(), (long) (Math.hypot(platformPosition[0] - sidingPosition[0], platformPosition[1] - sidingPosition[1]) * MILLIS_PER_BLOCK));
					}
				}
				sidings.add(new DutyGenerator.SidingInput(siding.id, siding.maxTrains >= SimDataset.UNLIMITED_TRAINS ? -1 : siding.maxTrains, links.isEmpty() ? null : links, depot.id));
			}
		}
		return DutyGenerator.generateSpread(services, sidings, Collections.emptyMap(), staysOnLine ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS);
	}

	/**
	 * Runs a day with the duties of a planning unit, each siding's slots given to its own depot, on a copy of the data, and
	 * reports on all the unit's routes.
	 */
	public static PlanCheck checkByLine(SimDataset dataset, long depotId, Map<Long, List<DepotDuties.Slot>> slots, long dayStart) {
		final SimDataset copy = SimDataset.fromBytes(dataset.toBytes());
		final Unit unit = unitOf(dataset, depotId);
		for (final SimDataset.DepotData depot : unit.depots) {
			final DepotDuties duties = new DepotDuties();
			duties.diagramMode = true;
			copy.sidings.forEach(siding -> {
				if (siding.depotId == depot.id && slots.containsKey(siding.id)) {
					duties.sidings.put(siding.id, slots.get(siding.id));
				}
			});
			copy.replaceDuties(depot.id, duties.toBytes());
		}
		return new PlanCheck(DayRun.run(copy, dayStart, unit.routeIds, Collections.emptyList(), () -> false));
	}

	/**
	 * How long a train takes to run a block to a first stop, for choosing the nearest siding, as the game has it.
	 */
	private static final long MILLIS_PER_BLOCK = 50;

	private static void addPlatformNode(Map<Long, Long> platformNodes, SimDataset.DataTrack track) {
		if (track.isPlatformTrack() && track.getStopId() != 0) {
			platformNodes.putIfAbsent(track.getStopId(), track.getStartNode());
		}
	}

	/**
	 * The depots and routes planned together with a depot: everything reached through the operating lines of the routes
	 * they list and the routes their services run through to. A route with no line is a line of its own.
	 */
	private static Unit unitOf(SimDataset dataset, long depotId) {
		final Unit unit = new Unit();
		final SimDataset.DepotData start = findDepot(dataset, depotId);
		if (start == null) {
			return unit;
		}
		final Map<Long, String> lineOf = new java.util.HashMap<>();
		final Map<String, Set<Long>> lineRoutes = new java.util.HashMap<>();
		dataset.routes.forEach(route -> {
			final String lineKey = route.lineKey.isEmpty() ? "route:" + route.id : route.lineKey;
			lineOf.put(route.id, lineKey);
			lineRoutes.computeIfAbsent(lineKey, key -> new java.util.TreeSet<>()).add(route.id);
		});
		// Lines joined by through services
		final Map<String, Set<String>> linkedLines = new java.util.HashMap<>();
		dataset.getPlanRoutes().values().forEach(route -> route.getTimetable().getTables().forEach(table -> table.links.values().forEach(link -> {
			final String from = lineOf.get(route.getRouteId()), to = lineOf.get(link.routeId);
			if (from != null && to != null) {
				linkedLines.computeIfAbsent(from, key -> new HashSet<>()).add(to);
				linkedLines.computeIfAbsent(to, key -> new HashSet<>()).add(from);
			}
		})));
		final Set<String> lines = new java.util.TreeSet<>();
		final Set<Long> depotIds = new java.util.TreeSet<>();
		final java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
		depotIds.add(start.id);
		queue.add(start);
		while (!queue.isEmpty()) {
			final Object next = queue.poll();
			if (next instanceof SimDataset.DepotData) {
				for (final long routeId : ((SimDataset.DepotData) next).routeIds) {
					final String lineKey = lineOf.get(routeId);
					if (lineKey != null && lines.add(lineKey)) {
						queue.add(lineKey);
					}
				}
			} else {
				final String lineKey = (String) next;
				final Set<Long> routeIds = lineRoutes.getOrDefault(lineKey, Collections.emptySet());
				dataset.depots.forEach(depot -> {
					if (java.util.Arrays.stream(depot.routeIds).anyMatch(routeIds::contains) && depotIds.add(depot.id)) {
						queue.add(depot);
					}
				});
				linkedLines.getOrDefault(lineKey, Collections.emptySet()).forEach(other -> {
					if (lines.add(other)) {
						queue.add(other);
					}
				});
			}
		}
		lines.forEach(lineKey -> unit.routeIds.addAll(lineRoutes.getOrDefault(lineKey, Collections.emptySet())));
		dataset.depots.forEach(depot -> {
			if (depotIds.contains(depot.id)) {
				unit.depots.add(depot);
			}
		});
		return unit;
	}

	private static final class Unit {

		private final List<SimDataset.DepotData> depots = new ArrayList<>();
		private final Set<Long> routeIds = new java.util.TreeSet<>();
	}

	private static SimDataset.DepotData findDepot(SimDataset dataset, long depotId) {
		return dataset.depots.stream().filter(depot -> depot.id == depotId).findFirst().orElse(null);
	}
}
