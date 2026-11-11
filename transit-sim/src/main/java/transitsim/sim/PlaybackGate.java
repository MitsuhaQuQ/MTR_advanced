package transitsim.sim;

/** Ignores triggers during the configured playback/cooldown window; never queues them. */
public final class PlaybackGate {
	private long next;
	public boolean accept(long tick, long durationTicks, boolean finish, long intervalTicks) {
		if (tick < next) return false;
		next = tick + (finish ? Math.max(0, durationTicks) : 0) + Math.max(0, intervalTicks);
		return true;
	}
	public void reset() { next = 0; }
}
