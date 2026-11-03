package transitsim.sim;

/**
 * What a train is doing at the time of a query (contracts document section 9).
 */
public final class TrainState {

	public final long trainId;
	/**
	 * The rail of its path the head is on.
	 */
	public final int rail;
	/**
	 * How far the head is along the path.
	 */
	public final double position;
	/**
	 * In blocks per tick.
	 */
	public final double speed;
	public final boolean onRoute;
	public final boolean stopped;
	public final boolean doorsOpen;
	/**
	 * Why the host holds the train, or null.
	 */
	public final String heldBy;
	/**
	 * The service it runs as a timestamp, or 0.
	 */
	public final long tripMillis;
	/**
	 * Whether the host has taken the train over, so this is how it was when it was taken (G0).
	 */
	public final boolean takenOver;

	TrainState(long trainId, int rail, double position, double speed, boolean onRoute, boolean stopped, boolean doorsOpen, String heldBy, long tripMillis, boolean takenOver) {
		this.trainId = trainId;
		this.rail = rail;
		this.position = position;
		this.speed = speed;
		this.onRoute = onRoute;
		this.stopped = stopped;
		this.doorsOpen = doorsOpen;
		this.heldBy = heldBy;
		this.tripMillis = tripMillis;
		this.takenOver = takenOver;
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof TrainState)) {
			return false;
		}
		final TrainState other = (TrainState) object;
		return trainId == other.trainId && rail == other.rail && position == other.position && speed == other.speed && onRoute == other.onRoute && stopped == other.stopped && doorsOpen == other.doorsOpen && (heldBy == null ? other.heldBy == null : heldBy.equals(other.heldBy)) && tripMillis == other.tripMillis && takenOver == other.takenOver;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(trainId) * 31 + Double.hashCode(position);
	}

	@Override
	public String toString() {
		return trainId + " rail " + rail + " at " + position + " speed " + speed + (onRoute ? "" : " in depot") + (doorsOpen ? " doors open" : "") + (heldBy == null ? "" : " held " + heldBy) + (takenOver ? " taken over" : "");
	}
}
