package transitsim.motion;

/**
 * What the motion rules need to know about a train itself. The braking and whether the train follows speed curves can
 * change while it runs, so they are read each time.
 */
public interface MotionVehicle {

	int getTrainCars();

	/**
	 * The length of each car, in blocks.
	 */
	int getSpacing();

	/**
	 * The length of the siding the train starts from.
	 */
	float getRailLength();

	/**
	 * In blocks per tick per tick.
	 */
	float getAcceleration();

	float getDeceleration();

	/**
	 * Whether the train follows its speed curves this tick, which a manual or continuously moving train doesn't.
	 */
	boolean usesSpeedCurve();

	/**
	 * Whether the train keeps moving through stops, like a cable car.
	 */
	boolean isContinuousMovement();

	/**
	 * The speed a train keeps through a platform it came onto from a rail where it couldn't speed up.
	 */
	float getDefaultMaxSpeed();

	/**
	 * The fastest a driver can go.
	 */
	float getManualMaxSpeed();
}
