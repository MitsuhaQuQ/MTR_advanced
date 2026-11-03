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
		return DutyGenerator.generate(services, sidings, Collections.emptyMap(), depot.cycleRoutes ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS);
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

	private static SimDataset.DepotData findDepot(SimDataset dataset, long depotId) {
		return dataset.depots.stream().filter(depot -> depot.id == depotId).findFirst().orElse(null);
	}
}
