package transitsim.planning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * A depot sending trains out by frequency, or by a list of departures, as services of its routes' timetables (R2), so it
 * runs by duties as every other depot does.
 * <p>
 * A depot of trains is given the minutes between trains for each hour of the real day ({@link #departuresOfHeadways}). The
 * game's own frequencies were for its hours, of which its day of 20 minutes has 24, a frequency of N sending a train every
 * {@code 200 / N} seconds; an old depot's become the minutes between trains in the real hour of the same number, as close to
 * that spacing as whole minutes go ({@link #headwayOf}). A train ran the depot's routes one after another, so each route
 * after the first has a service a turn after the one before arrives at its last stop.
 */
public final class FrequencyTimetable {

	/**
	 * How long one of the game's hours lasts, a thousand ticks.
	 */
	public static final int GAME_HOUR_MILLIS = 1000 * 50;
	/**
	 * How long trains are spaced at a frequency of 1, as the game spaces them.
	 */
	public static final int FREQUENCY_SPACING_MILLIS = GAME_HOUR_MILLIS * 4;
	public static final int DAY_MILLIS = 24 * 60 * 60 * 1000;
	/**
	 * How long a train takes at least to turn from one route onto the next.
	 */
	public static final long TURN_MILLIS = 60 * 1000;

	private FrequencyTimetable() {
	}

	/**
	 * The most minutes between trains a depot of trains can be set to.
	 */
	public static final int MAX_HEADWAY_MINUTES = 60;

	/**
	 * @param headways the minutes between trains in each hour of the real day, from midnight, 0 for none
	 * @return the departures through the real day, in order: from the start of each hour with trains, every so many
	 * minutes until it ends, at most {@code max}
	 */
	public static List<Integer> departuresOfHeadways(int[] headways, int max) {
		final List<Integer> departures = new ArrayList<>();
		for (int hour = 0; hour < Math.min(24, headways == null ? 0 : headways.length); hour++) {
			final int minutes = headways[hour];
			if (minutes <= 0) {
				continue;
			}
			for (int time = hour * 60 * 60 * 1000; time < (hour + 1) * 60 * 60 * 1000 && departures.size() < max; time += minutes * 60 * 1000) {
				departures.add(time);
			}
		}
		return departures;
	}

	/**
	 * @param frequency trains in one of the game's hours, as the game counts them, which sent one every {@code 200 / N}
	 *                  seconds
	 * @return as minutes between trains in the real hour of the same number (R2): the same spacing, to the nearest minute,
	 * at least one; 0 for none
	 */
	public static int headwayOf(int frequency) {
		return frequency <= 0 ? 0 : Math.max(1, Math.min(MAX_HEADWAY_MINUTES, Math.round(FREQUENCY_SPACING_MILLIS / (float) frequency / 60000)));
	}

	/**
	 * @param frequencies trains for each of the game's 24 hours, as the game counts them
	 * @return the departures through a real day, in order, as the game sends them, at most {@code max}
	 */
	public static List<Integer> departuresOf(int[] frequencies, int max) {
		final List<Integer> departures = new ArrayList<>();
		if (frequencies == null || frequencies.length == 0) {
			return departures;
		}
		long offset = 0;
		while (offset < DAY_MILLIS && departures.size() < max) {
			final int hour = (int) (offset / GAME_HOUR_MILLIS % frequencies.length);
			final int frequency = frequencies[hour];
			if (frequency <= 0) {
				offset = (offset / GAME_HOUR_MILLIS + 1) * GAME_HOUR_MILLIS;
			} else {
				departures.add((int) offset);
				offset += FREQUENCY_SPACING_MILLIS / frequency;
			}
		}
		return departures;
	}

	/**
	 * @param departures times of the day trains are sent out, from a list or {@link #departuresOf}
	 * @return the departures within a day, without the same one twice, in order, at most {@code max}
	 */
	public static List<Integer> cleaned(Collection<Integer> departures, int max) {
		final TreeSet<Integer> times = new TreeSet<>();
		departures.forEach(time -> times.add(Math.floorMod(time, DAY_MILLIS)));
		final List<Integer> list = new ArrayList<>(times);
		return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
	}

	/**
	 * @param firstServices the services of the first route
	 * @param routeTimes    each route's fastest times, {arrival, departure} at each stop from its first departure, or null
	 *                      when not known
	 * @return the services of each route, in order: the first route's, then each next route's a turn after the one before
	 * arrives at its last stop, as long as the routes' times are known
	 */
	public static List<List<Integer>> chain(List<Integer> firstServices, List<long[][]> routeTimes) {
		final List<List<Integer>> services = new ArrayList<>();
		services.add(new ArrayList<>(firstServices));
		for (int i = 1; i < routeTimes.size(); i++) {
			final long[][] times = routeTimes.get(i - 1);
			if (times == null || times.length == 0) {
				break;
			}
			final long after = times[times.length - 1][0] + TURN_MILLIS;
			final List<Integer> next = new ArrayList<>();
			services.get(i - 1).forEach(service -> next.add((int) Math.floorMod(service + after, (long) DAY_MILLIS)));
			services.add(next);
		}
		return services;
	}
}
