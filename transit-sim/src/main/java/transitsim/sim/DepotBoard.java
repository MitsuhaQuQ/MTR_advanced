package transitsim.sim;

import transitsim.plan.DepotDuties;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A depot as its trains see it when it runs without a world: its routes, the services its trains have taken and its
 * duties, decided by a {@link DepotDispatcher} the same way as the game's depot.
 */
public final class DepotBoard implements TrainService.Board {

	public final DepotDispatcher dispatcher = new DepotDispatcher();
	public final DepotDuties duties = new DepotDuties();
	private final List<PlanRoute> routes = new ArrayList<>();
	/**
	 * The routes of vehicles that only run some types of service, by the vehicle, when they aren't all the depot's.
	 */
	private final Map<String, List<PlanRoute>> trainRoutes = new HashMap<>();
	/**
	 * Trains turn back from the last route onto the first instead of going back to the depot.
	 */
	public boolean cycleRoutes;
	/**
	 * Whether the services taken changed in this tick, so trains waiting to be sent out work out again when they can go
	 * (S4f).
	 */
	boolean changed;
	/**
	 * Where the services reserved are written down as well, while a train reserves its services for the tick.
	 */
	private List<Long> recorder;

	public DepotBoard(List<? extends PlanRoute> routes) {
		this.routes.addAll(routes);
	}

	public List<PlanRoute> getRoutes() {
		return routes;
	}

	/**
	 * Gives a vehicle routes of its own, as for one that only runs some types of service.
	 */
	public void setTrainRoutes(String trainId, List<? extends PlanRoute> newRoutes) {
		trainRoutes.put(trainId, new ArrayList<>(newRoutes));
	}

	@Override
	public RouteTimetable getTimetable(String trainId) {
		final List<PlanRoute> vehicleRoutes = getTrainRoutes(trainId);
		return vehicleRoutes.isEmpty() || !vehicleRoutes.get(0).getTimetable().isActive() ? null : vehicleRoutes.get(0).getTimetable();
	}

	@Override
	public List<PlanRoute> getTrainRoutes(String trainId) {
		return trainRoutes.getOrDefault(trainId, routes);
	}

	@Override
	public long findNextTrip(String trainId, long readyMillis) {
		final List<PlanRoute> vehicleRoutes = getTrainRoutes(trainId);
		return vehicleRoutes.isEmpty() ? 0 : findNextService(vehicleRoutes.get(0), readyMillis);
	}

	@Override
	public long findNextService(PlanRoute route, long readyMillis) {
		changed = true;
		return dispatcher.findNextService(route, readyMillis, cycleRoutes);
	}

	@Override
	public void reserveTrip(long routeId, long tripMillis) {
		dispatcher.reserveTrip(routeId, tripMillis);
		if (recorder != null && tripMillis != 0) {
			recorder.add(DepotDispatcher.getTripKey(routeId, tripMillis));
		}
	}

	/**
	 * Writes down the services reserved from now on into this list, or stops with null.
	 */
	void record(List<Long> newRecorder) {
		recorder = newRecorder;
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
