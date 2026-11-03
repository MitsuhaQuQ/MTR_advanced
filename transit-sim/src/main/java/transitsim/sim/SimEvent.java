package transitsim.sim;

/**
 * Something that happened in a simulation (contracts document 7.1, rule 2). Its ID is the train and its sequence for
 * that train; the cursor counts all events of the simulation in their order, for the host to read on from.
 */
public final class SimEvent {

	/**
	 * In the fixed order events of the same moment sort in.
	 */
	public enum Kind {
		ARRIVE, PASS, RETURNED, DOORS_CLOSE, HELD, RELEASED, CONFLICT, DEADLOCK, SEGMENT_REPLACED, DOORS_OPEN, DEPART
	}

	public final long cursor;
	public final long time;
	public final Kind kind;
	public final long trainId;
	/**
	 * Counts up for each event of the same train.
	 */
	public final long sequence;
	/**
	 * The rail the event happened at, as an index along the train's path, or -1.
	 */
	public final int rail;
	/**
	 * Why the train was held, or what the conflict was, or empty.
	 */
	public final String reason;
	/**
	 * The event happened in a fork of the simulation, so it is a prediction the host acts on in no way (6.2, rule 3).
	 */
	public final boolean predicted;

	SimEvent(long cursor, long time, Kind kind, long trainId, long sequence, int rail, String reason, boolean predicted) {
		this.cursor = cursor;
		this.time = time;
		this.kind = kind;
		this.trainId = trainId;
		this.sequence = sequence;
		this.rail = rail;
		this.reason = reason;
		this.predicted = predicted;
	}

	@Override
	public String toString() {
		return time + " " + kind + " " + trainId + "#" + sequence + " " + rail + (reason.isEmpty() ? "" : " " + reason);
	}
}
