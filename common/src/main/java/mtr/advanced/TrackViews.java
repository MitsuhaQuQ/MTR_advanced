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
		final int count = path.size();
		final double[] lengths = new double[count];
		final double[] limits = new double[count];
		final boolean[] stops = new boolean[count];
		final boolean[] reversals = new boolean[count];
		for (int i = 0; i < count; i++) {
			final PathData pathData = path.get(i);
			final RailType railType = pathData.rail.railType;
			lengths[i] = pathData.rail.getLength();
			limits[i] = railType.canAccelerate ? railType.maxBlocksPerTick : -1;
			stops[i] = pathData.dwellTime > 0;
			reversals[i] = i + 1 < count && pathData.isOppositeRail(path.get(i + 1));
		}
		return new TrackView(lengths, limits, stops, reversals, RailType.getDefaultMaxBlocksPerTick(transportMode));
	}
}
