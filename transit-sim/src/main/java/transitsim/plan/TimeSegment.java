package transitsim.plan;

import transitsim.motion.MotionConstants;

/**
 * A stretch of a path where a train speeds up, slows down or keeps its speed, for the time it reaches each point.
 */
public class TimeSegment {

	/** First interval containing the position, including its endpoints; -1 in a reversal gap. */
	public static int indexAt(java.util.List<TimeSegment> segments, double position) {
		int low = 0, high = segments.size();
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (segments.get(middle).endRailProgress < position) low = middle + 1; else high = middle;
		}
		return low < segments.size() && segments.get(low).startRailProgress <= position ? low : -1;
	}

	public double endRailProgress;
	public long savedRailBaseId;
	public boolean pass;
	public long routeId;
	public int currentStationIndex;
	public float endTime;
	/**
	 * The path index of {@link #endRailProgress} as last worked out, and what it was worked out on, so a train writing
	 * its schedule every few ticks doesn't search the path again for every segment. Not saved.
	 */
	public transient Object indexDistances;
	public transient int indexDistancesSize = -1;
	public transient double indexProgress = Double.NaN;
	public transient int index = -1;

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
