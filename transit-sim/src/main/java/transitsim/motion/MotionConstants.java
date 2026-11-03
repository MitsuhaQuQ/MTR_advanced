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
