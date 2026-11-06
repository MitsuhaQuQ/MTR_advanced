package transitsim.plan;

/**
 * What a host gives to make other versions of a path on demand, as one passing some of its stops (D3): the same track with
 * no stop, and the way the host times a path.
 */
public interface PathVariants<T extends PlanTrack> {

	/**
	 * @return a copy of the track a train passes without stopping, the same in all else
	 */
	T pass(T track);

	TimeGenerator<T> getTimeGenerator();
}
