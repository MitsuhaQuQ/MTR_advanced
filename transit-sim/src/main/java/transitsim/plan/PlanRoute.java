package transitsim.plan;

import java.util.List;

/**
 * A route as the timetable rules see it. The host's routes implement it.
 */
public interface PlanRoute {

	long getRouteId();

	RouteTimetable getTimetable();

	/**
	 * The stops of the route in order.
	 */
	List<? extends PlanStop> getStops();

	/**
	 * Whether the route's last stop leads back to its first, so trains go round.
	 */
	boolean isLoop();

	/**
	 * Which kind of vehicle runs the route, only compared between routes.
	 */
	int getTransportModeId();
}
