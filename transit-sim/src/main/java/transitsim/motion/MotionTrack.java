package transitsim.motion;

/**
 * A train's path as the motion rules see it: rails by their index along the path. The host reads its own path each time,
 * so a path swapped by the host is seen at once.
 */
public interface MotionTrack {

	/**
	 * The number of rails on the path.
	 */
	int size();

	/**
	 * The number of distances, which is the number of rails unless the host's lists are out of step.
	 */
	int getDistanceCount();

	/**
	 * How far along the path the rail ends.
	 */
	double getDistance(int rail);

	/**
	 * How far short of the end of the rail a train stopping there stands, in blocks (optimisation round 4, stop points): 0
	 * for most stops; a platform may have its trains stop further back, as centred. Only rails with a stop time have one.
	 */
	default double getStopOffset(int rail) {
		return 0;
	}

	/**
	 * Where the head stands at the stop at the end of the rail: the end of the rail less the stop offset.
	 */
	default double getStopDistance(int rail) {
		return getDistance(rail) - getStopOffset(rail);
	}

	/**
	 * How long a train stops at the end of the rail, in seconds, or 0 if it doesn't.
	 */
	int getDwellTime(int rail);

	/**
	 * False for rails such as platforms, where a train keeps the speed it came in with.
	 */
	boolean canAccelerate(int rail);

	/**
	 * The speed limit of the rail, in blocks per tick.
	 */
	float getMaxSpeed(int rail);

	/**
	 * Whether the second rail runs back along the first, so the train turns back between them.
	 */
	boolean isOppositeRail(int rail, int nextRail);

	/**
	 * Where a repeating path starts its next lap, or 0 if it doesn't repeat.
	 */
	int getRepeatIndex1();

	/**
	 * Where a repeating path finishes its lap, or 0 if it doesn't repeat.
	 */
	int getRepeatIndex2();
}
