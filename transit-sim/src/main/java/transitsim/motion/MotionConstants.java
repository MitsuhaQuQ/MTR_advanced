package transitsim.motion;

/**
 * Numbers and small helpers shared by the motion rules, with the same values as the host's train.
 */
public final class MotionConstants {

	/**
	 * In blocks per tick per tick, and also the speed a train starts off with.
	 */
	public static final float ACCELERATION_DEFAULT = 0.01F;
	public static final int DOOR_MOVE_TIME = 64;
	public static final int DOOR_DELAY = 20;
	public static final int MILLIS_PER_TICK = 50;
	public static final double BRAKING_MARGIN = 2;
	public static final double CURVE_SPEED_TOLERANCE = 1E-3;

	private MotionConstants() {
	}

	/**
	 * The same as Minecraft's {@code Mth.clamp} for floats.
	 */
	public static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	/**
	 * How far the head moves as a train standing at a stop turns back: the head goes to where the tail was, on the rail
	 * running back, which is the train's length plus twice the stop offset further along the path (round 4).
	 */
	public static double getReversalAdvance(MotionTrack track, int stop, double trainLength) {
		return trainLength + (stop >= 0 && stop < track.size() ? 2 * track.getStopOffset(stop) : 0);
	}

	/**
	 * Where the head is once a train standing at this stop has turned back ({@link #getReversalAdvance}): exactly at the
	 * stop on the rail running back when that is where the train stands (0.84.6, a front turn back on a platform stops
	 * in place, {@link transitsim.plan.StopPositions#afterReversal}).
	 */
	public static double getReversalProgress(MotionTrack track, int stop, double railProgress, double trainLength) {
		final double advanced = railProgress + getReversalAdvance(track, stop, trainLength);
		return standsAtStopAfterReversal(track, stop, advanced) ? track.getStopDistance(stop + 1) : advanced;
	}

	/**
	 * @param railProgress where the head is after turning back
	 * @return whether a train that has turned back at this stop already stands at its stop on the rail running back, so
	 * it stops there again without moving (0.84.6): the host's start up keeps it standing, with that stop as its next
	 */
	public static boolean standsAtStopAfterReversal(MotionTrack track, int stop, double railProgress) {
		return stop >= 0 && stop + 1 < track.size() && track.getDwellTime(stop) > 0 && track.getDwellTime(stop + 1) > 0 && track.isOppositeRail(stop, stop + 1) && Math.abs(track.getStopDistance(stop + 1) - railProgress) <= 1E-6;
	}

	/**
	 * @return the first rail ending after the progress, or the last rail. Distances never decrease along a path.
	 */
	public static int findIndex(MotionTrack track, double tempRailProgress, boolean roundDown) {
		final int size = track.size();
		int low = 0;
		int high = size;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			final double tempDistance = track.getDistance(middle);
			if (tempRailProgress < tempDistance || roundDown && tempRailProgress == tempDistance) {
				high = middle;
			} else {
				low = middle + 1;
			}
		}
		return Math.min(low, size - 1);
	}
}
