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
		return toTrackView(path, minSpeed, 0);
	}

	/**
	 * @param vehicleMaxSpeed the highest speed of the vehicle, in blocks per tick, or 0 for none: no rail is run faster
	 */
	static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed) {
		return toTrackView(path, minSpeed, vehicleMaxSpeed, Double.NaN);
	}

	/**
	 * @param trainLength the length of the train, which platforms' stop positions depend on (round 4), or NaN to stop at
	 *                    the end of every rail
	 */
	static TrackView toTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed, double trainLength) {
		if (path instanceof transitsim.plan.IndexedPath) return ((transitsim.plan.IndexedPath<?>) path).compiled(java.util.Arrays.asList("physical-track", minSpeed, vehicleMaxSpeed, trainLength), () -> buildTrackView(path, minSpeed, vehicleMaxSpeed, trainLength));
		return buildTrackView(path, minSpeed, vehicleMaxSpeed, trainLength);
	}

	static TrackView buildTrackView(List<? extends RunTrack> path, double minSpeed, double vehicleMaxSpeed, double trainLength) {
		final int count = path.size();
		final double[] stopOffsets = new double[count];
		final double[] lengths = new double[count];
		final double[] limits = new double[count];
		final boolean[] stops = new boolean[count];
		final boolean[] reversals = new boolean[count];
		for (int i = 0; i < count; i++) {
			final RunTrack track = path.get(i);
			lengths[i] = track.getTrackLength();
			// Every track by its own limit, platforms, sidings and turn back rails too (round 3b); canAccelerate only keeps
			// them from being cut into block sections
			limits[i] = runSpeed(track, vehicleMaxSpeed);
			stops[i] = track.getDwellTime() > 0;
			reversals[i] = i + 1 < count && track.isOppositeOf(path.get(i + 1));
			stopOffsets[i] = stops[i] && !Double.isNaN(trainLength) ? track.getStopOffset(trainLength) : 0;
		}
		// Where rails don't set the speed, the vehicle's lowest cruising speed, which its highest speed caps too
		return new TrackView(lengths, limits, stops, reversals, stopOffsets, limit((float) minSpeed, transitsim.network.SpeedLimits.physicalBlocksPerTick((float) vehicleMaxSpeed)));
	}

	/**
	 * @return a rail's speed limit for a vehicle with a highest speed, or 0 for none
	 */
	/**
	 * @return how fast a train may run a track: the physical limits of the track's speed label and of the vehicle's
	 * (optimisation document 5.6), the lower of the two
	 */
	static float runSpeed(RunTrack track, double vehicleMaxSpeed) {
		return limit(transitsim.network.SpeedLimits.physicalBlocksPerTick(track.getMaxSpeed()), transitsim.network.SpeedLimits.physicalBlocksPerTick((float) vehicleMaxSpeed));
	}

	static float limit(float railSpeed, double vehicleMaxSpeed) {
		return vehicleMaxSpeed > 0 ? (float) Math.min(railSpeed, vehicleMaxSpeed) : railSpeed;
	}
}
