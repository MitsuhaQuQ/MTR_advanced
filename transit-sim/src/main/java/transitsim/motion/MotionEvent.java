package transitsim.motion;

/**
 * Something that happened to a train while it moved, which the host collects at the end of the tick (decoupling
 * document 3.7). Events sort by time, then kind, then train, then the order the train had them (contract 2.1, rule 5).
 */
public final class MotionEvent implements Comparable<MotionEvent> {

	/**
	 * In the fixed order events of the same moment sort in.
	 */
	public enum Type {
		/**
		 * The train came to a halt at the end of a rail: at a stop, or short of a taken rail.
		 */
		STOPPED,
		/**
		 * The train went back into its siding at the end of its path.
		 */
		RETURNED,
		/**
		 * A repeating train started its next lap.
		 */
		LAP,
		/**
		 * A driven train ran through a stop without stopping.
		 */
		PASSED,
		/**
		 * The train started a new run segment in place of the one it was on.
		 */
		SEGMENT_REPLACED,
		/**
		 * The train couldn't stop in time with its own braking.
		 */
		EMERGENCY_BRAKE,
		/**
		 * The train set off from a stop or from its siding.
		 */
		DEPARTED
	}

	public final long millis;
	public final Type type;
	public final long trainId;
	/**
	 * Counts up for each event of the same train.
	 */
	public final long sequence;
	/**
	 * The rail the event happened at, as an index along the train's path.
	 */
	public final int rail;
	/**
	 * The version of the run segment the train was on, or 0 for none.
	 */
	public final int segmentVersion;

	public MotionEvent(long millis, Type type, long trainId, long sequence, int rail, int segmentVersion) {
		this.millis = millis;
		this.type = type;
		this.trainId = trainId;
		this.sequence = sequence;
		this.rail = rail;
		this.segmentVersion = segmentVersion;
	}

	@Override
	public int compareTo(MotionEvent other) {
		if (millis != other.millis) {
			return Long.compare(millis, other.millis);
		}
		if (type != other.type) {
			return type.compareTo(other.type);
		}
		if (trainId != other.trainId) {
			return Long.compare(trainId, other.trainId);
		}
		return Long.compare(sequence, other.sequence);
	}

	@Override
	public String toString() {
		return String.format("%s train %d rail %d at %d (segment %d, #%d)", type, trainId, rail, millis, segmentVersion, sequence);
	}
}
