package transitsim.plan;

import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The other ways round the turn back points of a siding's duties (0.84.0): each distinct way stored once, and for each point
 * which of them each of its ways round is, with how many rails the usual way has there. A train taking another way round
 * a point has its path made when it needs it, the duty's usual path with that one way spliced in
 * ({@link TripPath#withTurnBackWay}), rather than the siding keeping a whole path for each point and way: a duty turning
 * back in front of the station a hundred and sixty times a day (站前折返, the user's 明珠线) had as many full copies of its
 * day, which took seconds to make and tens of megabytes to save.
 *
 * @param <T> the host's track
 */
public final class TurnBackWayStore<T extends PlanTrack> {

	public static final String KEY_POINTS = "turn_back_way_points";

	private static final TurnBackWayStore<?> NONE = new TurnBackWayStore<>(Collections.emptyList(), Collections.emptyMap());

	/**
	 * Each distinct way, from the stop it leaves to the end of the section, its first and last rail the usual path's.
	 */
	private final List<List<T>> ways;
	/**
	 * By point: how many rails the usual way has after the stop, then the way of {@link #ways} each way round is, or -1
	 * for the usual way and ways that can't be used.
	 */
	private final Map<Integer, int[]> points;

	private TurnBackWayStore(List<List<T>> ways, Map<Integer, int[]> points) {
		this.ways = ways;
		this.points = points;
	}

	@SuppressWarnings("unchecked")
	public static <T extends PlanTrack> TurnBackWayStore<T> none() {
		return (TurnBackWayStore<T>) NONE;
	}

	/**
	 * @return the way taken this way round the point, or null for the usual way, a way that can't be used or a point that
	 * isn't a duty's
	 */
	public List<T> get(int point, int way) {
		final int[] entry = points.get(point);
		if (entry == null || way < 0 || way + 1 >= entry.length) {
			return null;
		}
		final int index = entry[way + 1];
		return index < 0 || index >= ways.size() ? null : ways.get(index);
	}

	/**
	 * @return how many rails the usual way has after the stop it leaves, so it ends that far on, or -1 if the point isn't
	 * stored
	 */
	public int getUsualLength(int point) {
		final int[] entry = points.get(point);
		return entry == null ? -1 : entry[0];
	}

	/**
	 * @return each distinct way, once, for whatever needs the rails the siding's trains may run: interlocking, the track
	 * uses and opposing moves, the graph records, whether a rail changed
	 */
	public List<List<T>> getWays() {
		return Collections.unmodifiableList(ways);
	}

	public boolean isEmpty() {
		return points.isEmpty();
	}

	/**
	 * @return how many points have ways stored
	 */
	public int pointCount() {
		return points.size();
	}

	/**
	 * Writes {@link #KEY_POINTS}: for each point, the point, the usual way's rails, then the way of each way round. The host
	 * writes {@link #getWays} itself, in their order.
	 */
	public void write(KeyedWriter writer) throws IOException {
		writer.writeLongArrays(KEY_POINTS, getPointValues());
	}

	/**
	 * @param ways the ways the host wrote, in their order
	 * @return what {@link #write} wrote, or none where it wrote nothing, as in a save from before 0.84.0
	 */
	public static <T extends PlanTrack> TurnBackWayStore<T> read(KeyedReader reader, List<List<T>> ways) {
		return of(reader.getLongArrays(KEY_POINTS), ways);
	}

	/**
	 * @param pointValues as {@link #write} wrote them
	 */
	public static <T extends PlanTrack> TurnBackWayStore<T> of(List<long[]> pointValues, List<List<T>> ways) {
		final Map<Integer, int[]> points = new HashMap<>();
		for (final long[] values : pointValues) {
			if (values.length < 2) {
				continue;
			}
			final int[] entry = new int[values.length - 1];
			for (int i = 0; i < entry.length; i++) {
				entry[i] = (int) values[i + 1];
			}
			points.put((int) values[0], entry);
		}
		return points.isEmpty() ? none() : new TurnBackWayStore<>(new ArrayList<>(ways), points);
	}

	/**
	 * @return the values {@link #write} writes, for a host writing them its own way
	 */
	public List<long[]> getPointValues() {
		final List<long[]> values = new ArrayList<>();
		new TreeMap<>(points).forEach((point, entry) -> {
			final long[] pointValues = new long[entry.length + 1];
			pointValues[0] = point;
			for (int i = 0; i < entry.length; i++) {
				pointValues[i + 1] = entry[i];
			}
			values.add(pointValues);
		});
		return values;
	}

	/**
	 * Gathers the ways of a siding's duties' points as path generation finds them, keeping each distinct way once.
	 */
	public static final class Builder<T extends PlanTrack> {

		private final Function<List<T>, String> signature;
		private final List<List<T>> ways = new ArrayList<>();
		private final Map<String, Integer> bySignature = new HashMap<>();
		private final Map<Integer, int[]> points = new HashMap<>();
		/**
		 * The first route of the duty each way was first found on, for showing which route uses the rails.
		 */
		private final List<Long> routeIds = new ArrayList<>();

		/**
		 * @param signature the content of a way, the same for two ways a train runs the same, whatever their stop numbers
		 */
		public Builder(Function<List<T>, String> signature) {
			this.signature = signature;
		}

		/**
		 * Adds a duty's turn back point, which starts at the stop it leaves.
		 *
		 * @param routeId the duty's first route
		 */
		public void add(int point, TurnBackSection<T> section, long routeId) {
			final int[] entry = new int[section.ways.size() + 1];
			entry[0] = section.getLength() - 1;
			for (int way = 0; way < section.ways.size(); way++) {
				final List<T> tracks = section.ways.get(way);
				if (way == section.defaultWay || tracks == null || tracks.size() < 2) {
					entry[way + 1] = -1;
					continue;
				}
				final String key = signature.apply(tracks);
				Integer index = bySignature.get(key);
				if (index == null) {
					index = ways.size();
					ways.add(new ArrayList<>(tracks));
					routeIds.add(routeId);
					bySignature.put(key, index);
				}
				entry[way + 1] = index;
			}
			points.put(point, entry);
		}

		/**
		 * @return the first route of a duty each way of {@link #build} was found on, in their order
		 */
		public List<Long> getRouteIds() {
			return Collections.unmodifiableList(routeIds);
		}

		public TurnBackWayStore<T> build() {
			return points.isEmpty() ? none() : new TurnBackWayStore<>(new ArrayList<>(ways), new HashMap<>(points));
		}
	}
}
