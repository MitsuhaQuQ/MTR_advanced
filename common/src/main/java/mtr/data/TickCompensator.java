package mtr.data;

/**
 * Trains used to move one step per server tick, so they fell behind real time whenever the server slowed down. This
 * turns the real time since the last tick into a number of steps, and carries the rounding over to later ticks so
 * that normal jitter doesn't speed trains up.
 */
public class TickCompensator {

	private long lastMillis;
	private float debt;

	public static final float MAX_TICKS_ELAPSED = 4;
	private static final float MAX_DEBT = 8;
	private static final float MIN_DEBT = -1;

	public float nextTick(long currentMillis) {
		if (lastMillis == 0) {
			lastMillis = currentMillis;
			return 1;
		}

		debt = Math.min(debt + (float) (currentMillis - lastMillis) / Depot.MILLIS_PER_TICK, MAX_DEBT);
		lastMillis = currentMillis;
		final float ticksElapsed = Math.max(1, Math.min(debt, MAX_TICKS_ELAPSED));
		debt = Math.max(debt - ticksElapsed, MIN_DEBT);
		return ticksElapsed;
	}
}
