package transitsim.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which services of a depot's duties no train will run on time, so a late train may take one of them over instead of its
 * own (user 2026-11-14: 晚点超过 10 分钟改挂同线路最近一班还赶得上、且没人跑的班次). A service is known by its route, the stop
 * its duty segment starts at and when it is due, as a full timestamp.
 * <ul>
 * <li>given up: its own train reached the segment's first stop more than {@link TrainService#LATE_REHOOK_MILLIS} after its
 * departure and took another service, so it didn't run;</li>
 * <li>expected late: its own train is that late now, a stop or more before it, so it is not expected to run it either;</li>
 * <li>taken: another train took it over, which no other train may then, and which its own train finds when it gets there.</li>
 * </ul>
 * Kept by the depot's {@link DepotDispatcher} and saved by the host as {@link #toList()}.
 */
public final class LateServices {

	/**
	 * Given up services by {@link #routeKey}, as when they were due.
	 */
	private final Map<String, TreeSet<Long>> givenUp = new TreeMap<>();
	/**
	 * The service each late train is expected not to run, by train, as {@link #serviceKey}.
	 */
	private final Map<String, String> expected = new TreeMap<>();
	/**
	 * Services taken over, as {@link #serviceKey}.
	 */
	private final TreeSet<String> taken = new TreeSet<>();

	static String routeKey(long routeId, int fromStop) {
		return routeId + ":" + fromStop;
	}

	static String serviceKey(long routeId, int fromStop, long serviceMillis) {
		return routeKey(routeId, fromStop) + ":" + serviceMillis;
	}

	public boolean isEmpty() {
		return givenUp.isEmpty() && expected.isEmpty() && taken.isEmpty();
	}

	void giveUp(long routeId, int fromStop, long serviceMillis) {
		givenUp.computeIfAbsent(routeKey(routeId, fromStop), key -> new TreeSet<>()).add(serviceMillis);
	}

	/**
	 * @param serviceMillis 0 when the train is no longer expected to miss one
	 */
	void expect(String trainId, long routeId, int fromStop, long serviceMillis) {
		if (serviceMillis == 0) {
			expected.remove(trainId);
		} else {
			expected.put(trainId, serviceKey(routeId, fromStop, serviceMillis));
		}
	}

	boolean isTaken(long routeId, int fromStop, long serviceMillis) {
		return taken.contains(serviceKey(routeId, fromStop, serviceMillis));
	}

	/**
	 * @return the earliest service here that no train runs, due after {@code afterMillis}, from {@code fromMillis} until
	 * {@code untilMillis}, and not taken yet, or 0
	 */
	long findFree(long routeId, int fromStop, long afterMillis, long fromMillis, long untilMillis) {
		final long from = Math.max(afterMillis + 1, fromMillis);
		if (from > untilMillis) {
			return 0;
		}
		long best = 0;
		final TreeSet<Long> services = givenUp.get(routeKey(routeId, fromStop));
		if (services != null) {
			for (final long serviceMillis : services.subSet(from, true, untilMillis, true)) {
				if (!isTaken(routeId, fromStop, serviceMillis)) {
					best = serviceMillis;
					break;
				}
			}
		}
		final String prefix = routeKey(routeId, fromStop) + ":";
		for (final String key : expected.values()) {
			if (key.startsWith(prefix) && !taken.contains(key)) {
				final long serviceMillis = Long.parseLong(key.substring(prefix.length()));
				if (serviceMillis >= from && serviceMillis <= untilMillis && (best == 0 || serviceMillis < best)) {
					best = serviceMillis;
				}
			}
		}
		return best;
	}

	void take(long routeId, int fromStop, long serviceMillis) {
		taken.add(serviceKey(routeId, fromStop, serviceMillis));
		final TreeSet<Long> services = givenUp.get(routeKey(routeId, fromStop));
		if (services != null) {
			services.remove(serviceMillis);
			if (services.isEmpty()) {
				givenUp.remove(routeKey(routeId, fromStop));
			}
		}
	}

	/**
	 * Forgets services more than a day old.
	 */
	void prune(long currentMillis) {
		final long before = currentMillis - transitsim.plan.OperatingDay.MILLIS_PER_DAY;
		givenUp.values().forEach(services -> services.headSet(before, false).clear());
		givenUp.values().removeIf(NavigableSet::isEmpty);
		expected.values().removeIf(key -> millisOf(key) < before);
		taken.removeIf(key -> millisOf(key) < before);
	}

	private static long millisOf(String serviceKey) {
		return Long.parseLong(serviceKey.substring(serviceKey.lastIndexOf(':') + 1));
	}

	/**
	 * @return everything kept, one line each, in a fixed order, for the host to save
	 */
	public List<String> toList() {
		final List<String> list = new ArrayList<>();
		givenUp.forEach((key, services) -> services.forEach(serviceMillis -> list.add("g " + key + ":" + serviceMillis)));
		expected.forEach((trainId, key) -> list.add("e " + key + " " + trainId));
		taken.forEach(key -> list.add("t " + key));
		return list;
	}

	/**
	 * Reads back what {@link #toList()} gave, adding to what is kept; lines it doesn't know are left out.
	 */
	public void addAll(List<String> list) {
		for (final String line : list) {
			try {
				final String[] parts = line.split(" ", 3);
				final String[] service = parts[1].split(":");
				final long routeId = Long.parseLong(service[0]);
				final int fromStop = Integer.parseInt(service[1]);
				final long serviceMillis = Long.parseLong(service[2]);
				switch (parts[0]) {
					case "g":
						giveUp(routeId, fromStop, serviceMillis);
						break;
					case "e":
						expect(parts[2], routeId, fromStop, serviceMillis);
						break;
					case "t":
						taken.add(serviceKey(routeId, fromStop, serviceMillis));
						break;
					default:
						break;
				}
			} catch (RuntimeException ignored) {
				// A line from a later version
			}
		}
	}

	public void clear() {
		givenUp.clear();
		expected.clear();
		taken.clear();
	}
}
