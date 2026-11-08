package mtr.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * With {@code -Dmtr.debugConnections=true}, counts the ways between services a depot's duty paths search for and the time
 * they take, over the whole server, to see how many are the same search done again (optimisation round 5, step 3,
 * measured before deciding).
 */
final class ConnectionStats {

	static final boolean ON = Boolean.getBoolean("mtr.debugConnections");
	private static final AtomicLong[] CALLS = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
	private static final AtomicLong[] NANOS = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
	private static final Map<String, Boolean>[] KEYS = new Map[]{new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>()};
	private static final String[] NAMES = {"connections", "nearby turn back nodes", "front turn backs"};

	private ConnectionStats() {
	}

	static void connection(String key, long nanos) {
		add(0, key, nanos);
	}

	static void nearby(long platformId, long nanos) {
		add(1, String.valueOf(platformId), nanos);
	}

	static void front(String key, long nanos) {
		add(2, key, nanos);
	}

	private static void add(int kind, String key, long nanos) {
		CALLS[kind].incrementAndGet();
		NANOS[kind].addAndGet(nanos);
		KEYS[kind].put(key, true);
	}

	static void print() {
		final StringBuilder builder = new StringBuilder("Minecraft Transit Railway connection stats so far:");
		for (int i = 0; i < NAMES.length; i++) {
			builder.append(' ').append(NAMES[i]).append(' ').append(CALLS[i].get()).append(" calls, ").append(KEYS[i].size()).append(" distinct, ").append(NANOS[i].get() / 1000000).append(" ms;");
		}
		System.out.println(builder);
	}
}
