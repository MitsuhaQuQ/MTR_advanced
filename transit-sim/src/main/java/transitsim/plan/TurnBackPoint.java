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
	/**
	 * For each way round, every rail between the stop and the first stop of the next route, or null if it can't be used:
	 * the way a train taking it runs, to tell whether another train has it.
	 */
	private final List<List<UUID>> wayRails;
	public final int defaultWay;

	/**
	 * @param rails   for each way round in order, the rails where a train turns back, or null if it can't be used
	 * @param lengths for each way round, the number of rails from the stop to the first stop of the next route
	 */
	public TurnBackPoint(List<List<UUID>> rails, int[] lengths, int defaultWay) {
		this(rails, lengths, rails, defaultWay);
	}

	/**
	 * @param wayRails for each way round, every rail between the stop and the first stop of the next route
	 */
	public TurnBackPoint(List<List<UUID>> rails, int[] lengths, List<List<UUID>> wayRails, int defaultWay) {
		this.rails = rails;
		this.lengths = lengths;
		this.wayRails = wayRails;
		this.defaultWay = defaultWay;
	}

	TurnBackPoint(TurnBackSection<?> section) {
		this(section.ways, section.defaultWay, section.start - section.getStopRail());
	}

	/**
	 * @param ways       for each way round in order, its rails from the stop it leaves to the first stop of the next route,
	 *                   or null if it can't be used
	 * @param stopOffset how many rails before the first rail of the ways the stop is
	 */
	public TurnBackPoint(List<? extends List<? extends PlanTrack>> ways, int defaultWay, int stopOffset) {
		rails = new ArrayList<>();
		wayRails = new ArrayList<>();
		lengths = new int[ways.size()];
		for (int i = 0; i < ways.size(); i++) {
			final List<? extends PlanTrack> way = ways.get(i);
			if (way == null) {
				rails.add(null);
				wayRails.add(null);
			} else {
				final List<UUID> allRails = new ArrayList<>();
				for (int j = 1; j < way.size() - 1; j++) {
					allRails.add(way.get(j).getTrackKey());
				}
				wayRails.add(allRails);
				final List<UUID> wayTurnRails = new ArrayList<>();
				for (int j = 1; j < way.size() - 1; j++) {
					if (way.get(j).isOppositeOf(way.get(j + 1))) {
						wayTurnRails.add(way.get(j).getTrackKey());
					}
				}
				// A way round without turning back, like a loop, is kept clear as a whole
				if (wayTurnRails.isEmpty()) {
					wayTurnRails.addAll(allRails);
				}
				rails.add(wayTurnRails);
				lengths[i] = stopOffset + way.size() - 1;
			}
		}
		this.defaultWay = defaultWay;
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
		// Written after the ways, so older values without them still read, keeping only the turn back rails
		final List<List<UUID>> allRails = new ArrayList<>();
		for (int i = 0; i < wayCount; i++) {
			if (index >= values.length) {
				return new TurnBackPoint(rails, lengths, defaultWay);
			}
			final int railCount = (int) values[index++];
			if (railCount < 0) {
				allRails.add(null);
			} else {
				final List<UUID> wayRails = new ArrayList<>();
				for (int j = 0; j < railCount; j++) {
					wayRails.add(new UUID(values[index++], values[index++]));
				}
				allRails.add(wayRails);
			}
		}
		return new TurnBackPoint(rails, lengths, allRails, defaultWay);
	}

	long[] write() {
		final List<Long> values = new ArrayList<>();
		values.add((long) defaultWay);
		values.add((long) rails.size());
		for (int i = 0; i < rails.size(); i++) {
			final List<UUID> turnRails = rails.get(i);
			values.add((long) lengths[i]);
			values.add(turnRails == null ? -1L : turnRails.size());
			if (turnRails != null) {
				turnRails.forEach(rail -> {
					values.add(rail.getMostSignificantBits());
					values.add(rail.getLeastSignificantBits());
				});
			}
		}
		for (final List<UUID> allRails : wayRails) {
			values.add(allRails == null ? -1L : allRails.size());
			if (allRails != null) {
				allRails.forEach(rail -> {
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
	 * @return every rail between the stop and the first stop of the next route this way round, or null if it can't be used
	 */
	public List<UUID> getWayRails(int way) {
		return wayRails.get(way);
	}

	/**
	 * @return the number of rails from the stop to the first stop of the next route this way round
	 */
	public int getLength(int way) {
		return lengths[way];
	}
}
