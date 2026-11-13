package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Which ways round a train may take between two services, and which of them the path takes ({@link TurnBackSection}):
 * the ways round the turn back rails a route lists after its last stop, or, with none listed, the shorter of the way
 * found straight through and the way round a turn back rail near the stop (站后折返). Moved out of the game's depot path
 * building (2026-11-13, step 2c of splitting the depot); the host finds each way, the rules of which are kept and which
 * is the default are here.
 */
public final class TurnBackWays {

	private TurnBackWays() {
	}

	/**
	 * The ways the host finds.
	 *
	 * @param <N> the host's turn back node
	 * @param <T> the host's track
	 */
	public interface Ways<N, T extends PlanTrack> {

		/**
		 * @return the way from the stop to the next service's start round that node, empty for none
		 */
		List<T> find(N node);

		/**
		 * @return whether two tracks are the same rail run the same way
		 */
		boolean sameRail(T track, T other);

		/**
		 * Told of each node whose way round can't be used.
		 */
		default void unusable(N node) {
		}
	}

	/**
	 * A way round can be used when it has rails to run and leaves and reaches the stops along the same rails as the first
	 * one that can, so that a train takes any of them from the same place and comes back to the same place.
	 *
	 * @param start the rail of the main path where the way from the stop starts
	 * @return the section, with null for each way that can't be used, or null if none can
	 */
	public static <N, T extends PlanTrack> TurnBackSection<T> section(List<N> nodes, int start, Ways<N, T> ways) {
		final List<List<T>> found = new ArrayList<>();
		int defaultWay = -1;
		for (final N node : nodes) {
			final List<T> way = ways.find(node);
			final List<T> firstWay = defaultWay < 0 ? null : found.get(defaultWay);
			final boolean canUse = way != null && way.size() >= 2 && (firstWay == null
					|| ways.sameRail(way.get(0), firstWay.get(0)) && ways.sameRail(way.get(way.size() - 1), firstWay.get(firstWay.size() - 1)));
			found.add(canUse ? way : null);
			if (!canUse) {
				ways.unusable(node);
			} else if (defaultWay < 0) {
				defaultWay = found.size() - 1;
			}
		}
		return defaultWay < 0 ? null : new TurnBackSection<>(start, found, defaultWay);
	}

	/**
	 * The way between two services, from where one ends to where the next starts, as a duty runs it.
	 *
	 * @param into        filled with the way, or left empty if there is none
	 * @param listedNodes the turn back nodes the route before lists after its last stop, empty for none
	 * @param direct      the way found straight through, asked for only with no node listed
	 * @param nearbyNodes the turn back nodes near the stop, asked for only with no node listed
	 * @return the ways round when there are any, or null
	 */
	public static <N, T extends PlanTrack> TurnBackSection<T> connection(List<T> into, List<N> listedNodes, Supplier<List<T>> direct, Supplier<List<N>> nearbyNodes, Ways<N, T> ways) {
		// What the route lists wins, whether or not it is the shortest way
		if (!listedNodes.isEmpty()) {
			final TurnBackSection<T> listed = section(listedNodes, 0, ways);
			if (listed != null) {
				into.addAll(listed.getDefaultWay());
			}
			return listed;
		}
		into.addAll(direct.get());
		final List<N> nearby = nearbyNodes.get();
		if (nearby.isEmpty()) {
			return null;
		}
		final TurnBackSection<T> round = section(nearby, 0, ways);
		// Round a turn back rail near the stop rather than the way straight through, where that is shorter or there is none
		if (round == null || !into.isEmpty() && length(round.getDefaultWay()) >= length(into)) {
			return null;
		}
		into.clear();
		into.addAll(round.getDefaultWay());
		return round;
	}

	/**
	 * @return how long a way is, in blocks
	 */
	public static <T extends PlanTrack> double length(List<T> way) {
		double length = 0;
		for (final T track : way) {
			length += track.getTrackLength();
		}
		return length;
	}
}
