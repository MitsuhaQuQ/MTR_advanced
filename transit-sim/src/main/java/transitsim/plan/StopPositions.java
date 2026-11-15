package transitsim.plan;

import java.util.List;

/**
 * Where on a platform trains stop (optimisation round 4, stop points), as a platform's setting: at the exit end, as
 * always, centred by the train's length, with the tail at the entry end (0.84.6), or a number of blocks short of the
 * exit end. The game and the component work the stop offset out the same way here, also for a train turning back on a
 * platform it stopped at (front turn back, 0.84.6), which stops again where it stands, see {@link #getOffset(List, int, double)}.
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
	/**
	 * The tail at the entry end of the platform (0.84.6, 车尾对齐进站端): the head as far short of the exit end as the
	 * platform has room for. Versions before it read the setting as centred.
	 */
	public static final int TAIL_AT_ENTRY_END = -2;

	private StopPositions() {
	}

	/**
	 * @param setting        {@link #EXIT_END}, {@link #CENTRED}, {@link #TAIL_AT_ENTRY_END}, or a number of blocks short
	 *                       of the exit end
	 * @param platformLength the length of the platform's rail
	 * @param trainLength    the length of the train
	 * @return how far short of the exit end the head stops: never so far that the tail leaves the platform, and 0 for a
	 * train as long as the platform or longer
	 */
	public static double getOffset(int setting, double platformLength, double trainLength) {
		final double room = getRoom(platformLength, trainLength);
		if (setting == CENTRED) {
			return room / 2;
		}
		if (setting == TAIL_AT_ENTRY_END) {
			return room;
		}
		return setting > 0 ? Math.min(setting, room) : 0;
	}

	/**
	 * Where a train turning back on a platform it has stopped at stops on the way back (front turn back, 0.84.6): turning
	 * back puts the head where the tail was, which is this far short of the platform's other end, so the train stands at
	 * its stop already and doesn't move along the platform before opening its doors again.
	 *
	 * @param arrivingOffset where the head stood short of the exit end coming in
	 * @return how far short of the exit end of the way back the head stops
	 */
	public static double afterReversal(double arrivingOffset, double platformLength, double trainLength) {
		return Math.max(0, getRoom(platformLength, trainLength) - arrivingOffset);
	}

	/**
	 * @return whether a train stopping at the track turns back onto it from the one before, having stopped there at the
	 * same platform: a front turn back, whose stop on the way back is where the train already stands (0.84.6)
	 */
	public static boolean turnsBackOnPlatform(PlanTrack previous, PlanTrack track) {
		return track.getDwellTime() > 0 && previous.getDwellTime() > 0 && track.isPlatformTrack() && track.getStopId() != 0 && previous.getStopId() == track.getStopId() && previous.isOppositeOf(track);
	}

	/**
	 * How far short of the end of a rail of a path a train stopping there stands: the platform's setting
	 * ({@link PlanTrack#getStopOffset(double)}), but where the train has turned back on the platform it stopped at, the
	 * rest of the room before the platform's other end ({@link #afterReversal}), so it stops where it stands. Both hosts
	 * take every stop offset along a path from here.
	 *
	 * @return the stop offset, 0 for a rail without a stop
	 */
	public static double getOffset(List<? extends PlanTrack> path, int rail, double trainLength) {
		final PlanTrack track = path.get(rail);
		if (track.getDwellTime() <= 0) {
			return 0;
		}
		if (rail > 0 && turnsBackOnPlatform(path.get(rail - 1), track)) {
			return afterReversal(getOffset(path, rail - 1, trainLength), track.getTrackLength(), trainLength);
		}
		return track.getStopOffset(trainLength);
	}

	/**
	 * @return the setting as it is written down: exit end, centred, tail at the entry end, or a number of blocks
	 */
	public static int normalise(int setting) {
		return setting == TAIL_AT_ENTRY_END ? setting : setting < 0 ? CENTRED : setting;
	}

	private static double getRoom(double platformLength, double trainLength) {
		return Math.max(0, platformLength - trainLength);
	}
}
