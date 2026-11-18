package mtr.data;

import java.util.*;

/**
 * How much each train type is used in a dimension (0.101.0, user 2026-11-18: 保存当前存档正在使用的车型表，按使用频度排序，
 * 模型使用频度随保存的内容一并记入数据库): the number of trains of the type in the world (in their sidings or out on their
 * routes) over time, with the past fading by half every {@link #HALF_LIFE_SECONDS}. Kept in the dimension's database with
 * the rest of the save, and sent to the players, whose clients build the models of the most used types first and keep
 * them longest ({@code mtr.client.TrainModelsOnDemand}).
 * <p>
 * The order sent is by the trains of each type in the world now (user 2026-11-18: 服务端能直接得到每种车的运用数量，直接按照它
 * 做载入；第一次进入服务器的排名直接按照当前状态下的使用量), the faded use over time only putting types with as many trains
 * in order, and coming after them.
 * <p>
 * A score is in train-seconds, faded: a type with n trains in the world all along tends to n times the half-life over
 * ln 2.
 */
public final class TrainUsage {

	public static final double HALF_LIFE_SECONDS = 7 * 24 * 3600;
	/** How often the trains are counted */
	public static final long UPDATE_MILLIS = 20_000;
	/** At most this many types are sent to the players */
	public static final int SENT_TYPES = 200;
	private static final double FORGOTTEN = 1;
	/** A gap longer than this (the server stopped, say) counts as this */
	private static final double LONGEST_STEP_SECONDS = 60;

	private final Map<String, Double> scores = new HashMap<>();
	/** The trains of each type in the world at the last count */
	private Map<String, Integer> current = new HashMap<>();
	private long lastUpdateMillis = -1;
	private boolean dirty;
	private List<String> lastSentOrder = Collections.emptyList();

	/** A score read from the database */
	public synchronized void restore(String trainId, double score) {
		if (trainId != null && !trainId.isEmpty() && Double.isFinite(score) && score > 0) {
			scores.put(trainId, score);
		}
	}

	/**
	 * Counts the trains in the world now: the scores fade for the time since the last count and gain the trains
	 * counted over it.
	 *
	 * @return whether the scores changed
	 */
	public synchronized boolean update(Map<String, Integer> trainsNow, long nowMillis) {
		current = new HashMap<>(trainsNow);
		if (lastUpdateMillis < 0) {
			lastUpdateMillis = nowMillis;
			return false;
		}
		final double seconds = Math.min(LONGEST_STEP_SECONDS, Math.max(0, (nowMillis - lastUpdateMillis) / 1000D));
		lastUpdateMillis = nowMillis;
		if (seconds == 0) {
			return false;
		}
		final double fade = Math.pow(0.5, seconds / HALF_LIFE_SECONDS);
		scores.replaceAll((trainId, score) -> score * fade);
		trainsNow.forEach((trainId, count) -> {
			if (trainId != null && !trainId.isEmpty() && count > 0) {
				scores.merge(trainId, count * seconds, Double::sum);
			}
		});
		scores.values().removeIf(score -> score < FORGOTTEN);
		dirty = true;
		return true;
	}

	/** The trains of a dimension's sidings now, by type */
	public static Map<String, Integer> countTrains(Collection<Siding> sidings) {
		final Map<String, Integer> counts = new HashMap<>();
		for (final Siding siding : sidings) {
			final String trainId = siding.getTrainId();
			siding.forEachTrain(train -> counts.merge(trainId, 1, Integer::sum));
		}
		return counts;
	}

	/** The trains in the world now, by type, without waiting for the next count (a player joining) */
	public synchronized void setCurrent(Map<String, Integer> trainsNow) {
		current = new HashMap<>(trainsNow);
	}

	public synchronized int currentTrains(String trainId) {
		return current.getOrDefault(trainId, 0);
	}

	/**
	 * The types, the most used first: by the trains in the world now, then by the faded use over time, then by name. A
	 * type with no train now but a past comes after those with trains.
	 *
	 * @return each type with its faded score
	 */
	public synchronized List<Map.Entry<String, Double>> ranking() {
		final Set<String> trainIds = new HashSet<>(scores.keySet());
		current.forEach((trainId, count) -> {
			if (count > 0) {
				trainIds.add(trainId);
			}
		});
		final List<Map.Entry<String, Double>> ranking = new ArrayList<>();
		trainIds.forEach(trainId -> ranking.add(new AbstractMap.SimpleImmutableEntry<>(trainId, scores.getOrDefault(trainId, 0D))));
		ranking.sort((a, b) -> {
			final int byNow = Integer.compare(current.getOrDefault(b.getKey(), 0), current.getOrDefault(a.getKey(), 0));
			if (byNow != 0) {
				return byNow;
			}
			final int byScore = Double.compare(b.getValue(), a.getValue());
			return byScore != 0 ? byScore : a.getKey().compareTo(b.getKey());
		});
		return ranking;
	}

	/** The scores to write, if they changed since they were last taken */
	public synchronized Map<String, Double> takeIfChanged() {
		if (!dirty) {
			return null;
		}
		dirty = false;
		return new HashMap<>(scores);
	}

	/** Changed scores not written (a save that failed) */
	public synchronized void keepUnsaved() {
		dirty = true;
	}

	/**
	 * @return whether the order of the types sent ({@link #SENT_TYPES}) differs from what was last sent, noting it as sent
	 */
	public synchronized boolean orderChanged() {
		final List<String> order = new ArrayList<>();
		for (final Map.Entry<String, Double> entry : ranking()) {
			if (order.size() >= SENT_TYPES) {
				break;
			}
			order.add(entry.getKey());
		}
		if (order.equals(lastSentOrder)) {
			return false;
		}
		lastSentOrder = order;
		return true;
	}
}
