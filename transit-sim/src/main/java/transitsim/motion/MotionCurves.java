package transitsim.motion;

/**
 * The speed curves of a train's path, which the host keeps and works out again when the path or the braking changes.
 */
public interface MotionCurves {

	/**
	 * @return the curve of the run the train is on, cruising slower when it has time to spare, or null if there is none
	 */
	SpeedProfile getProfile(double railProgress);

	/**
	 * @return the run a head position belongs to, or null if there is none
	 */
	SpeedCurves.Run getRun(double railProgress);

	/**
	 * The cruising speed for the run to the next stop, or infinity for none.
	 */
	double getCruiseCap();
}
