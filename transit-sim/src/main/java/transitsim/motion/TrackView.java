package transitsim.motion;

/**
 * The line as one train sees it: the rails of its path in order, each with what moving along it depends on. A host makes
 * one from its own path, and the motion rules only read this.
 */
public final class TrackView {

	private final double[] lengths;
	private final double[] limits;
	private final boolean[] stops;
	private final boolean[] reversals;
	private final double[] stopOffsets;
	/**
	 * The lowest speed a train cruises at, which is also the limit before the first rail that sets one.
	 */
	public final double minSpeed;

	/**
	 * @param lengths   the length of each rail
	 * @param limits    the speed limit of each rail in blocks per tick, or -1 for one a train keeps the speed it came in at
	 * @param stops     true for a rail with a stop at its end
	 * @param reversals true for a rail after which the train turns back
	 */
	public TrackView(double[] lengths, double[] limits, boolean[] stops, boolean[] reversals, double minSpeed) {
		this(lengths, limits, stops, reversals, new double[lengths.length], minSpeed);
	}

	/**
	 * @param stopOffsets how far short of the end of each rail with a stop the train stands (round 4), 0 for the end
	 */
	public TrackView(double[] lengths, double[] limits, boolean[] stops, boolean[] reversals, double[] stopOffsets, double minSpeed) {
		if (limits.length != lengths.length || stops.length != lengths.length || reversals.length != lengths.length || stopOffsets.length != lengths.length) {
			throw new IllegalArgumentException("Every rail needs a length, a limit, a stop and a reversal");
		}
		this.lengths = lengths.clone();
		this.limits = limits.clone();
		this.stops = stops.clone();
		this.reversals = reversals.clone();
		this.stopOffsets = stopOffsets.clone();
		this.minSpeed = minSpeed;
	}

	public int size() {
		return lengths.length;
	}

	public double getLength(int rail) {
		return lengths[rail];
	}

	/**
	 * @return the rail's speed limit, or -1 to keep the one before
	 */
	public double getLimit(int rail) {
		return limits[rail];
	}

	public boolean isStop(int rail) {
		return stops[rail];
	}

	public double getStopOffset(int rail) {
		return stopOffsets[rail];
	}

	public boolean reversesAfter(int rail) {
		return reversals[rail];
	}
}
