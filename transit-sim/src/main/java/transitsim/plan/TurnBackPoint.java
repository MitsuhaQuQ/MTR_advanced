package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One place in {@link TurnBacks}: the ways round a train can take there.
 */
public class TurnBackPoint {

	/**
	 * For each way round in order, the rails where a train turns back, or null if it can't be used.
	 */
	final List<List<UUID>> rails;
	/**
	 * For each way round, the number of rails from the stop to the first stop of the next route.
	 */
	private final int[] lengths;
	public final int defaultWay;

	/**
	 * @param rails   for each way round in order, the rails where a train turns back, or null if it can't be used
	 * @param lengths for each way round, the number of rails from the stop to the first stop of the next route
	 */
	public TurnBackPoint(List<List<UUID>> rails, int[] lengths, int defaultWay) {
		this.rails = rails;
		this.lengths = lengths;
		this.defaultWay = defaultWay;
	}

	TurnBackPoint(TurnBackSection<?> section) {
		rails = new ArrayList<>();
		lengths = new int[section.ways.size()];
		for (int i = 0; i < section.ways.size(); i++) {
			final List<? extends PlanTrack> way = section.ways.get(i);
			if (way == null) {
				rails.add(null);
			} else {
				final List<UUID> wayRails = new ArrayList<>();
				for (int j = 1; j < way.size() - 1; j++) {
					if (way.get(j).isOppositeOf(way.get(j + 1))) {
						wayRails.add(way.get(j).getTrackKey());
					}
				}
				// A way round without turning back, like a loop, is kept clear as a whole
				if (wayRails.isEmpty()) {
					for (int j = 1; j < way.size() - 1; j++) {
						wayRails.add(way.get(j).getTrackKey());
					}
				}
				rails.add(wayRails);
				lengths[i] = section.start - section.getStopRail() + way.size() - 1;
			}
		}
		defaultWay = section.defaultWay;
	}

	static TurnBackPoint read(long[] values) {
		final int defaultWay = (int) values[0];
		final int wayCount = (int) values[1];
		final List<List<UUID>> rails = new ArrayList<>();
		final int[] lengths = new int[wayCount];
		int index = 2;
		for (int i = 0; i < wayCount; i++) {
			lengths[i] = (int) values[index++];
			final int railCount = (int) values[index++];
			if (railCount < 0) {
				rails.add(null);
			} else {
				final List<UUID> wayRails = new ArrayList<>();
				for (int j = 0; j < railCount; j++) {
					wayRails.add(new UUID(values[index++], values[index++]));
				}
				rails.add(wayRails);
			}
		}
		return new TurnBackPoint(rails, lengths, defaultWay);
	}

	long[] write() {
		final List<Long> values = new ArrayList<>();
		values.add((long) defaultWay);
		values.add((long) rails.size());
		for (int i = 0; i < rails.size(); i++) {
			final List<UUID> wayRails = rails.get(i);
			values.add((long) lengths[i]);
			values.add(wayRails == null ? -1L : wayRails.size());
			if (wayRails != null) {
				wayRails.forEach(rail -> {
					values.add(rail.getMostSignificantBits());
					values.add(rail.getLeastSignificantBits());
				});
			}
		}
		return values.stream().mapToLong(Long::longValue).toArray();
	}

	public int getWayCount() {
		return rails.size();
	}

	/**
	 * @return the rails where a train taking this way round turns back, or null if it can't be used
	 */
	public List<UUID> getRails(int way) {
		return rails.get(way);
	}

	/**
	 * @return the number of rails from the stop to the first stop of the next route this way round
	 */
	public int getLength(int way) {
		return lengths[way];
	}
}
