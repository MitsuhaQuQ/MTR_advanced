package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a path has its train turn back on a turn-back track too short for it (2026-11-14: line 6 of the test lines turned
 * its 75-block trains on a 50-block dead-end track, so each stood over the switch while it turned, holding up both lines).
 * The host says which of its tracks are turn-back tracks and tells the player of what is found.
 */
public final class TurnBackLengths {

	private TurnBackLengths() {
	}

	/**
	 * A place a train turns back on a turn-back track shorter than it.
	 */
	public static final class Short {

		/**
		 * The rail of the path the train turns back on, and the length of turn-back track up to its end.
		 */
		public final int rail;
		public final double length;

		Short(int rail, double length) {
			this.rail = rail;
			this.length = length;
		}
	}

	/**
	 * @param isTurnBack whether a track is a turn-back track
	 * @return each place the path turns back at the end of turn-back track shorter than the train: the rail the train goes
	 * the other way after, with the length of the turn-back track leading up to it
	 */
	public static <T extends PlanTrack> List<Short> find(List<T> path, Predicate<T> isTurnBack, double trainLength) {
		final List<Short> found = new ArrayList<>();
		for (int i = 0; i + 1 < path.size(); i++) {
			final T track = path.get(i);
			if (!isTurnBack.test(track) || !path.get(i + 1).isOppositeOf(track)) {
				continue;
			}
			double length = 0;
			for (int j = i; j >= 0 && isTurnBack.test(path.get(j)); j--) {
				length += path.get(j).getTrackLength();
			}
			// A little short is a rounding of the train's length, not a train over the switch
			if (length + 0.5 < trainLength) {
				found.add(new Short(i, length));
			}
		}
		return found;
	}
}
