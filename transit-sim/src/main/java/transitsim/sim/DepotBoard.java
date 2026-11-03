package transitsim.sim;

import transitsim.plan.DepotDuties;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;

import java.util.ArrayList;
import java.util.List;

/**
 * A depot as its trains see it when it runs without a world: its routes, the services its trains have taken and its
 * duties, decided by a {@link DepotDispatcher} the same way as the game's depot.
 */
public final class DepotBoard implements TrainService.Board {

	public final DepotDispatcher dispatcher = new DepotDispatcher();
	public final DepotDuties duties = new DepotDuties();
	private final List<PlanRoute> routes = new ArrayList<>();
	/**
	 * Trains turn back from the last route onto the first instead of going back to the depot.
	 */
	public boolean cycleRoutes;

	public DepotBoard(List<? extends PlanRoute> routes) {
		this.routes.addAll(routes);
	}

	public List<PlanRoute> getRoutes() {
		return routes;
	}

	@Override
	public RouteTimetable getTimetable(String trainId) {
		return routes.isEmpty() || !routes.get(0).getTimetable().isActive() ? null : routes.get(0).getTimetable();
	}

	@Override
	public List<? extends PlanRoute> getTrainRoutes(String trainId) {
		return routes;
	}

	@Override
	public long findNextTrip(String trainId, long readyMillis) {
		return routes.isEmpty() ? 0 : findNextService(routes.get(0), readyMillis);
	}

	@Override
	public long findNextService(PlanRoute route, long readyMillis) {
		return dispatcher.findNextService(route, readyMillis, cycleRoutes);
	}

	@Override
	public void reserveTrip(long routeId, long tripMillis) {
		dispatcher.reserveTrip(routeId, tripMillis);
	}

	@Override
	public boolean isTripReserved(long routeId, long tripMillis) {
		return dispatcher.isTripReserved(routeId, tripMillis);
	}

	@Override
	public boolean keepsTrainsOnLine() {
		return cycleRoutes;
	}

	@Override
	public List<DepotDuties.Slot> getDutySlots(long sidingId) {
		return duties.getSlots(sidingId);
	}

	@Override
	public boolean hasDutyRun(long sidingId, int slot, long day) {
		return dispatcher.hasDutyRun(sidingId, slot, day);
	}

	@Override
	public void markDutyRun(long sidingId, int slot, long day) {
		dispatcher.markDutyRun(sidingId, slot, day);
	}
}
