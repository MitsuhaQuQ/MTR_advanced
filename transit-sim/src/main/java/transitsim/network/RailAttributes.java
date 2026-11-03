package transitsim.network;

/**
 * What the network needs to know of a host's rail.
 */
public interface RailAttributes<R> {

	/**
	 * @return {@link SectionType#NORMAL} for an ordinary rail
	 */
	SectionType getType(R rail);

	/**
	 * @return false for rails a train keeps the speed it came in at, such as platforms and sidings
	 */
	boolean canAccelerate(R rail);

	/**
	 * @return the speed limit, in blocks per tick
	 */
	double getSpeedLimit(R rail);

	double getLength(R rail);
}
