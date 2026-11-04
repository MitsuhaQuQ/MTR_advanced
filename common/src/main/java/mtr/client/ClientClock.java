package mtr.client;

import java.util.ArrayDeque;

/**
 * The server's clock as the client sees it (G2), so the client works out where trains are and shows times by the server's
 * time even when the two computers' clocks don't agree. The server sends its time now and then; as a message only arrives
 * late, never early, the largest of the last few differences is the closest. A small change is caught up on slowly, so the
 * time shown never goes back; a large one, as on joining or when the server's clock is set, is taken at once.
 */
public final class ClientClock {

	private final ArrayDeque<Long> differences = new ArrayDeque<>();
	private boolean known;
	private long target;
	private long offset;
	private long lastLocalMillis;
	private long lastServerMillis;

	public static final ClientClock INSTANCE = new ClientClock();
	/**
	 * How many of the last differences are looked at.
	 */
	private static final int SAMPLES = 6;
	/**
	 * A change larger than this is taken at once.
	 */
	private static final long JUMP_MILLIS = 5000;
	/**
	 * A smaller change is caught up on by a tenth of the time going by.
	 */
	private static final int SLEW_DIVISOR = 10;

	/**
	 * The server's time arrived.
	 */
	public void onServerTime(long serverMillis) {
		onServerTime(serverMillis, System.currentTimeMillis());
	}

	public synchronized void onServerTime(long serverMillis, long localMillis) {
		differences.addLast(serverMillis - localMillis);
		while (differences.size() > SAMPLES) {
			differences.removeFirst();
		}
		long best = Long.MIN_VALUE;
		for (final long difference : differences) {
			best = Math.max(best, difference);
		}
		target = best;
		if (!known || Math.abs(target - currentOffset(localMillis)) > JUMP_MILLIS) {
			known = true;
			offset = target;
			lastLocalMillis = localMillis;
			lastServerMillis = localMillis + offset;
		}
	}

	/**
	 * @return the server's time now, which never goes back unless the server's clock jumped
	 */
	public long serverMillis() {
		return serverMillis(System.currentTimeMillis());
	}

	public synchronized long serverMillis(long localMillis) {
		offset = currentOffset(localMillis);
		lastLocalMillis = Math.max(lastLocalMillis, localMillis);
		final long serverMillis = Math.max(localMillis + offset, lastServerMillis);
		lastServerMillis = serverMillis;
		return serverMillis;
	}

	/**
	 * Forgets the server, as when leaving it.
	 */
	public synchronized void reset() {
		differences.clear();
		known = false;
		target = 0;
		offset = 0;
		lastLocalMillis = 0;
		lastServerMillis = 0;
	}

	private long currentOffset(long localMillis) {
		if (offset == target) {
			return offset;
		}
		final long step = Math.max(0, localMillis - lastLocalMillis) / SLEW_DIVISOR;
		return target > offset ? Math.min(target, offset + step) : Math.max(target, offset - step);
	}
}
