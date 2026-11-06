package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.plan.PlanRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The duties of a depot that runs by a timetable but has none set, worked out from its timetables with the fewest trains
 * ({@link DutyGenerator}). A depot running by a timetable always runs by duties, so it only sends trains out when their
 * duties start and each train runs the services of its own duty (decoupling document, step D). The same timetables and
 * sidings always give the same duties, so working them out again doesn't move trains for nothing. Services left over
 * when the sidings have too few trains are run by none.
 */
public final class AutoDuties {

	public final DepotDuties duties = new DepotDuties();
	/**
	 * The trains the duties need at most.
	 */
	public final int trains;
	/**
	 * Services no train was free for, counted once for each day of the week they run.
	 */
	public final int uncovered;
	/**
	 * Routes left out, as their fastest times aren't known yet.
	 */
	public final List<PlanRoute> missingTimes = new ArrayList<>();

	private AutoDuties(DutyGenerator.Result result) {
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		trains = result.trains;
		uncovered = result.uncovered;
	}

	/**
	 * @param routes         the depot's routes
	 * @param fastestTimes   the fastest arrival and departure at each stop of a route, or null if not known yet
	 * @param dayStartMillis when the operating day starts, as a time of day
	 * @param sidings        the depot's sidings in their order, with the trains each holds
	 * @param stationOf      the station of each platform, to turn back between its platforms
	 * @param staysOnLine    whether the depot keeps its trains on the line between services
	 */
	public static AutoDuties generate(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, List<DutyGenerator.SidingInput> sidings, Map<Long, Long> stationOf, boolean staysOnLine) {
		return generate(routes, fastestTimes, dayStartMillis, sidings, stationOf, staysOnLine, new java.util.HashSet<>());
	}

	/**
	 * @param taken the services other sidings of the depot already run, as {@code route@service}, which these sidings
	 *              leave to them; the services these sidings take are added
	 */
	public static AutoDuties generate(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, List<DutyGenerator.SidingInput> sidings, Map<Long, Long> stationOf, boolean staysOnLine, java.util.Set<String> taken) {
		return generate(routes, fastestTimes, dayStartMillis, sidings, stationOf, staysOnLine, taken, DutyGenerator.PlanInputs.NONE);
	}

	/**
	 * @param inputs what path generation measured on the rails
	 */
	public static AutoDuties generate(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, List<DutyGenerator.SidingInput> sidings, Map<Long, Long> stationOf, boolean staysOnLine, java.util.Set<String> taken, DutyGenerator.PlanInputs inputs) {
		final List<PlanRoute> missing = new ArrayList<>();
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(routes, fastestTimes, dayStartMillis, missing::add);
		services.removeIf(service -> taken.contains(service.routeId + "@" + service.service));
		final AutoDuties autoDuties = new AutoDuties(DutyGenerator.generateSpread(services, sidings, stationOf, staysOnLine ? transitsim.plan.RouteTimetable.MAX_STAY_MILLIS : transitsim.plan.RouteTimetable.MAX_LAYOVER_MILLIS, inputs));
		autoDuties.missingTimes.addAll(missing);
		autoDuties.duties.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> taken.add(segment.routeId + "@" + segment.service)))));
		return autoDuties;
	}
}
