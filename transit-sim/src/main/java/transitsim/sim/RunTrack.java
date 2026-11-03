package transitsim.sim;

import transitsim.motion.TrackView;
import transitsim.plan.PlanTrack;

import java.util.List;

/**
 * A track of a path as a simulated train runs it: what plans need ({@link PlanTrack}), with how fast trains may go on it
 * and the nodes it joins, for running trains without a world.
 */
public interface RunTrack extends PlanTrack {

	/**
	 * In blocks per tick.
	 */
	float getMaxSpeed();

	/**
	 * False for tracks such as platforms, where a train keeps the speed it came in with.
	 */
	boolean canAccelerate();

	/**
	 * Whether trains on it are kept apart, as on every track but a siding's own.
	 */
	boolean hasSignal();

	/**
	 * Whether trains can also run the other way along it, so it needs a direction to be booked.
	 */
	boolean isTwoWay();

	long getStartNode();

	long getEndNode();

	/**
	 * The path as the speed curves see it, built the same way as the game builds it from its own path.
	 *
	 * @param minSpeed the lowest cruising speed of the kind of vehicle
	 */
	static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed) {
		final int count = path.size();
		final double[] lengths = new double[count];
		final double[] limits = new double[count];
		final boolean[] stops = new boolean[count];
		final boolean[] reversals = new boolean[count];
		for (int i = 0; i < count; i++) {
			final RunTrack track = path.get(i);
			lengths[i] = track.getTrackLength();
			limits[i] = track.canAccelerate() ? track.getMaxSpeed() : -1;
			stops[i] = track.getDwellTime() > 0;
			reversals[i] = i + 1 < count && track.isOppositeOf(path.get(i + 1));
		}
		return new TrackView(lengths, limits, stops, reversals, minSpeed);
	}
}
