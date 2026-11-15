package transitsim.plan;

/**
 * Where on a platform trains stop (optimisation round 4, stop points), as a platform's setting: at the exit end, as
 * always, centred by the train's length, or a number of blocks short of the exit end. The game and the component work the
 * stop offset out the same way here.
 */
public final class StopPositions {

	/**
	 * The head at the exit end of the platform, as trains always stopped.
	 */
	public static final int EXIT_END = 0;
	/**
	 * The train in the middle of the platform.
	 */
	public static final int CENTRED = -1;

	private StopPositions() {
	}

	/**
	 * @param setting        {@link #EXIT_END}, {@link #CENTRED}, or a number of blocks short of the exit end
	 * @param platformLength the length of the platform's rail
	 * @param trainLength    the length of the train
	 * @return how far short of the exit end the head stops: never so far that the tail leaves the platform, and 0 for a
	 * train as long as the platform or longer
	 */
	public static double getOffset(int setting, double platformLength, double trainLength) {
		final double room = Math.max(0, platformLength - trainLength);
		if (setting == CENTRED) {
			return room / 2;
		}
		return setting > 0 ? Math.min(setting, room) : 0;
	}

	/**
	 * @return the setting as it is written down: exit end, centred, or a number of blocks
	 */
	public static int normalise(int setting) {
		return setting < 0 ? CENTRED : setting;
	}
}
