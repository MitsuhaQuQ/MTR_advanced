package transitsim.plan;

/**
 * A route as the timetable rules see it. The host's routes implement it.
 */
public interface PlanRoute {

	long getRouteId();

	RouteTimetable getTimetable();
}
