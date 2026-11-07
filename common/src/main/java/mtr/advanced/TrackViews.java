package mtr.advanced;

import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.path.PathData;
import transitsim.motion.TrackView;

import java.util.List;

/**
 * Turns a train's path into the {@link TrackView} the simulation component reads.
 */
public final class TrackViews {

	private TrackViews() {
	}

	public static TrackView of(List<PathData> path, TransportMode transportMode) {
		return of(path, transportMode, 0);
	}

	/**
	 * @param maxSpeed the highest speed of the vehicle, in blocks per tick, or 0 for none
	 */
	public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed) {
		return of(path, transportMode, maxSpeed, false);
	}

	/**
	 * @param plan whether the speeds are the ones timetables are planned at, rather than the ones trains run at
	 *             (optimisation document 5.6)
	 */
	public static TrackView of(List<PathData> path, TransportMode transportMode, float maxSpeed, boolean plan) {
		final float vehicleSpeed = plan ? mtr.data.SpeedRules.plan(maxSpeed, transportMode) : mtr.data.SpeedRules.run(maxSpeed, transportMode);
		final int count = path.size();
		final double[] lengths = new double[count];
		final double[] limits = new double[count];
		final boolean[] stops = new boolean[count];
		final boolean[] reversals = new boolean[count];
		for (int i = 0; i < count; i++) {
			final PathData pathData = path.get(i);
			final RailType railType = pathData.rail.railType;
			lengths[i] = pathData.rail.getLength();
			limits[i] = !mtr.data.SpeedRules.keepsLimitBefore(railType, transportMode) ? limit(plan ? pathData.getPlanSpeed() : pathData.getRunSpeed(), vehicleSpeed) : -1;
			stops[i] = pathData.dwellTime > 0;
			reversals[i] = i + 1 < count && pathData.isOppositeRail(path.get(i + 1));
		}
		return new TrackView(lengths, limits, stops, reversals, limit(RailType.getDefaultMaxBlocksPerTick(transportMode), vehicleSpeed));
	}

	private static float limit(float speedLimit, float maxSpeed) {
		return maxSpeed > 0 ? Math.min(speedLimit, maxSpeed) : speedLimit;
	}
}
