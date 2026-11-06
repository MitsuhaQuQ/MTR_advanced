package transitsim.plan;

import java.util.*;

/**
 * How much longer than planned trains were measured to take, running, out of a siding to a first stop, turning round
 * from the last stop of one service to the first of the next, and back from a last stop to a siding (D1, the user's
 * idea of recording actual overruns as buffers). The duties are planned with these added, so the next plan absorbs what
 * made trains late, rather than handling it each time; the timetables stay as they are.
 */
public final class Buffers {

	public static final Buffers NONE = new Buffers(Collections.emptyMap());
	public static final int OUT = 0;
	public static final int TURN = 1;
	public static final int BACK = 2;

	/**
	 * {@link #key} → milliseconds, each more than nothing.
	 */
	private final Map<String, Long> millis;

	public Buffers(Map<String, Long> millis) {
		this.millis = Collections.unmodifiableMap(new TreeMap<>(millis));
	}

	/**
	 * @param kind {@link #OUT} (siding → platform), {@link #TURN} (platform → platform) or {@link #BACK} (platform → siding)
	 */
	public static String key(int kind, long from, long to) {
		return kind + ":" + from + ":" + to;
	}

	/**
	 * @return the kind, from and to of a {@link #key}
	 */
	public static long[] parse(String key) {
		final String[] parts = key.split(":");
		return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])};
	}

	public long get(int kind, long from, long to) {
		return millis.getOrDefault(key(kind, from, to), 0L);
	}

	public long out(long sidingId, long platformId) {
		return get(OUT, sidingId, platformId);
	}

	public long turn(long fromPlatformId, long toPlatformId) {
		return get(TURN, fromPlatformId, toPlatformId);
	}

	public long back(long platformId, long sidingId) {
		return get(BACK, platformId, sidingId);
	}

	public Map<String, Long> getMillis() {
		return millis;
	}

	public boolean isEmpty() {
		return millis.isEmpty();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Buffers && millis.equals(((Buffers) other).millis);
	}

	@Override
	public int hashCode() {
		return millis.hashCode();
	}

	/**
	 * For packets and saves: the count, then each kind, from, to and milliseconds.
	 */
	public List<Long> toList() {
		final List<Long> list = new ArrayList<>();
		list.add((long) millis.size());
		millis.forEach((key, value) -> {
			for (final long part : parse(key)) {
				list.add(part);
			}
			list.add(value);
		});
		return list;
	}

	public static Buffers fromList(List<Long> list) {
		if (list == null || list.isEmpty()) {
			return NONE;
		}
		final Map<String, Long> millis = new TreeMap<>();
		final int count = list.get(0).intValue();
		for (int i = 0; i < count && 4 * i + 4 < list.size(); i++) {
			final int start = 1 + 4 * i;
			millis.put(key(list.get(start).intValue(), list.get(start + 1), list.get(start + 2)), list.get(start + 3));
		}
		return millis.isEmpty() ? NONE : new Buffers(millis);
	}
}
