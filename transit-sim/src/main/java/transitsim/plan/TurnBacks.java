package transitsim.plan;

import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Where trains of a siding can turn back on more than one rail after the last stop of a route. At each of these points
 * a train takes the first turn back rail that is free as it leaves the stop. The paths of the ways round that the usual
 * path doesn't take are kept with the paths of services, by {@link TripPath#getTurnBackKey}.
 */
public class TurnBacks {

	public static final String KEY_POINTS = "turn_back_points";
	public static final String KEY_STOPS = "turn_back_stops";
	public static final TurnBacks NONE = new TurnBacks(new ArrayList<>(), new int[0]);

	private final List<TurnBackPoint> points;
	/**
	 * The rail of the stop before each point on the usual path.
	 */
	private final int[] depotStops;

	private TurnBacks(List<TurnBackPoint> points, int[] depotStops) {
		this.points = points;
		this.depotStops = depotStops;
	}

	public static TurnBacks fromSections(List<? extends TurnBackSection<?>> turnBackSections, int[] depotStops) {
		final List<TurnBackPoint> points = new ArrayList<>();
		turnBackSections.forEach(section -> points.add(new TurnBackPoint(section)));
		return new TurnBacks(points, depotStops);
	}

	/**
	 * Turn back points worked out elsewhere, for running services without a world.
	 */
	public static TurnBacks of(int[] depotStops, TurnBackPoint... points) {
		return new TurnBacks(Arrays.asList(points), depotStops);
	}

	public static TurnBacks read(KeyedReader reader) {
		final List<TurnBackPoint> points = new ArrayList<>();
		reader.getLongArrays(KEY_POINTS).forEach(values -> points.add(TurnBackPoint.read(values)));
		final long[] stops = reader.getLongs(KEY_STOPS);
		final int[] depotStops = new int[stops.length];
		for (int i = 0; i < stops.length; i++) {
			depotStops[i] = (int) stops[i];
		}
		return points.size() == depotStops.length && !points.isEmpty() ? new TurnBacks(points, depotStops) : NONE;
	}

	/**
	 * Writes the two values {@link #KEY_POINTS} and {@link #KEY_STOPS}.
	 */
	public void write(KeyedWriter writer) throws IOException {
		final List<long[]> values = new ArrayList<>();
		points.forEach(point -> values.add(point.write()));
		writer.writeLongArrays(KEY_POINTS, values);
		writer.writeInts(KEY_STOPS, depotStops);
	}

	/**
	 * @param stops the stops of the path the train is on, or null for the usual path
	 * @return the point after this stop, or -1 if there is none
	 */
	public int getPoint(int[] stops, int stopIndex) {
		final int[] pathStops = stops == null ? depotStops : stops;
		for (int i = 0; i < pathStops.length && i < points.size(); i++) {
			if (pathStops[i] == stopIndex) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @param stops the stops of a path, or null for the usual path
	 * @return the rail of the stop before this point on that path, or -1 if the path doesn't pass it
	 */
	public int getStop(int[] stops, int point) {
		final int[] pathStops = stops == null ? depotStops : stops;
		return point < pathStops.length ? pathStops[point] : -1;
	}

	public TurnBackPoint get(int point) {
		return points.get(point);
	}

	/**
	 * @return the first turn back rail of each way round of the point whose usual way turns back on this rail, or just this rail
	 */
	public List<UUID> getCandidates(UUID rail) {
		for (final TurnBackPoint point : points) {
			final List<UUID> defaultRails = point.rails.get(point.defaultWay);
			if (defaultRails != null && defaultRails.contains(rail)) {
				final List<UUID> candidates = new ArrayList<>();
				point.rails.forEach(rails -> {
					if (rails != null && !rails.isEmpty()) {
						candidates.add(rails.get(0));
					}
				});
				return candidates;
			}
		}
		return Collections.singletonList(rail);
	}
}
