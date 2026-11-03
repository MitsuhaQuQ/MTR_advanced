package transitsim.motion;

/**
 * The moving state of a train, which stays with the host (decoupling document 2.2): the motion rules read and write it
 * through this, so add-ons reading the host's fields see the same values as before.
 */
public interface MotionState {

	float getSpeed();

	void setSpeed(float speed);

	/**
	 * How far the front of the train is along its path.
	 */
	double getRailProgress();

	void setRailProgress(double railProgress);

	boolean getDoorTarget();

	void setDoorTarget(boolean doorTarget);

	float getDoorValue();

	void setDoorValue(float doorValue);

	float getElapsedDwellTicks();

	void setElapsedDwellTicks(float elapsedDwellTicks);

	int getNextStoppingIndex();

	void setNextStoppingIndex(int nextStoppingIndex);

	int getNextPlatformIndex();

	void setNextPlatformIndex(int nextPlatformIndex);

	boolean isReversed();

	void setReversed(boolean reversed);

	boolean isOnRoute();

	void setOnRoute(boolean onRoute);

	boolean isCurrentlyManual();

	/**
	 * The driver's notch: positive to speed up, negative to brake, -2 with the doors open and -3 braking for a stop.
	 */
	int getManualNotch();

	void setManualNotch(int manualNotch);
}
