package transitsim.plan;

/**
 * A stop of a route as the timetable rules see it: its platform and its settings in each table. The host's route stops
 * implement it.
 */
public interface PlanStop {

	long getPlatformId();

	/**
	 * Whether the route passes this stop without stopping.
	 */
	boolean isPass();

	/**
	 * @return the set arrival in seconds from where the route starts, or -1 to follow the stop before
	 */
	int getArrival(int table);

	/**
	 * @return the set departure in seconds from where the route starts, or -1 to stop for the usual time
	 */
	int getDeparture(int table);

	/**
	 * Whether the train waits here to be overtaken.
	 */
	boolean getOvertake(int table);
}
