package transitsim.plan;

import transitsim.motion.MotionConstants;

/**
 * A stretch of a path where a train speeds up, slows down or keeps its speed, for the time it reaches each point.
 */
public class TimeSegment {

	public double endRailProgress;
	public long savedRailBaseId;
	public boolean pass;
	public long routeId;
	public int currentStationIndex;
	public float endTime;

	public final double startRailProgress;
	private final float startSpeed;
	private final float startTime;
	public final int speedChange;
	private final float accelerationConstant;

	public TimeSegment(double startRailProgress, float startSpeed, float startTime, int speedChange, float accelerationConstant) {
		this.startRailProgress = startRailProgress;
		this.startSpeed = startSpeed;
		this.startTime = startTime;
		this.speedChange = Integer.compare(speedChange, 0);
		final float tempAccelerationConstant = round(accelerationConstant, 3);
		this.accelerationConstant = tempAccelerationConstant <= 0 ? MotionConstants.ACCELERATION_DEFAULT : tempAccelerationConstant;
	}

	/**
	 * @return the speed at this rail progress on the segment, in blocks a tick
	 */
	public double getSpeed(double railProgress) {
		if (speedChange == 0) {
			return startSpeed;
		}
		return Math.sqrt(Math.max(0, 2 * speedChange * accelerationConstant * (railProgress - startRailProgress) + startSpeed * startSpeed));
	}

	public double getTime(double railProgress) {
		final double distance = railProgress - startRailProgress;
		if (speedChange == 0) {
			return startTime + distance / startSpeed;
		} else {
			final float acceleration = speedChange * accelerationConstant;
			return startTime + (distance == 0 ? 0 : (Math.sqrt(2 * acceleration * distance + startSpeed * startSpeed) - startSpeed) / acceleration);
		}
	}

	private static float round(double value, int decimalPlaces) {
		int factor = 1;
		for (int i = 0; i < decimalPlaces; i++) {
			factor *= 10;
		}
		return (float) Math.round(value * factor) / factor;
	}
}
