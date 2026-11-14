package transitsim.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where trains running opposite ways meet at a passing point whose two tracks are in one interlocking zone (2026-11-14,
 * user feedback: a single line with a passing loop, the turnouts painted in the interlocking colour, deadlocked at once). A
 * zone is taken by a route as a whole, so a train waiting on one track of the loop with its tail still in the zone holds the
 * other track too, and the train the other way can never get past it. Only told, the rules stay as they are (user's choice):
 * the zone should cover the turnouts only, not the tracks trains wait on to pass, nor platforms.
 * <p>
 * A passing point is where a track used both ways (the line, or a platform turned back at) splits: trains one way come onto
 * it from one track and trains the other way leave it onto another, each of which is only used that one way.
 */
public final class MeetingZones {

	private MeetingZones() {
	}

	/**
	 * @param paths  the paths trains run, each in the order run
	 * @param zoneOf the interlocking zone of a rail, or null if none
	 * @return each passing point found, once, in the order first met
	 */
	public static <T extends PlanTrack> List<Finding<T>> find(List<? extends List<T>> paths, Function<UUID, Long> zoneOf) {
		// Each rail as first met, and whether it is ever run the other way
		final Map<UUID, T> firstRun = new HashMap<>();
		final Map<UUID, Boolean> bothWays = new HashMap<>();
		// The moves from one rail onto another, by the rail moved onto and off
		final List<T[]> moves = new ArrayList<>();
		for (final List<T> path : paths) {
			for (int i = 0; i < path.size(); i++) {
				final T track = path.get(i);
				final T first = firstRun.putIfAbsent(track.getTrackKey(), track);
				if (first != null && track.isOppositeOf(first)) {
					bothWays.put(track.getTrackKey(), true);
				}
				if (i + 1 < path.size() && !path.get(i + 1).getTrackKey().equals(track.getTrackKey())) {
					@SuppressWarnings("unchecked") final T[] move = (T[]) new PlanTrack[]{track, path.get(i + 1)};
					moves.add(move);
				}
			}
		}
		final Map<String, Finding<T>> found = new LinkedHashMap<>();
		for (final T[] onto : moves) {
			// A train coming onto the shared track from one track...
			final T from = onto[0];
			final T shared = onto[1];
			if (bothWays.containsKey(from.getTrackKey())) {
				continue;
			}
			final Long zone = zoneOf.apply(from.getTrackKey());
			if (zone == null) {
				continue;
			}
			for (final T[] off : moves) {
				// ...and one the other way leaving it onto another, at the same end
				final T back = off[0];
				final T to = off[1];
				if (!back.getTrackKey().equals(shared.getTrackKey()) || !back.isOppositeOf(shared) || to.getTrackKey().equals(from.getTrackKey()) || bothWays.containsKey(to.getTrackKey())) {
					continue;
				}
				if (zone.equals(zoneOf.apply(to.getTrackKey()))) {
					final String key = zone + ":" + shared.getTrackKey();
					found.putIfAbsent(key, new Finding<>(zone, from, shared, to));
				}
			}
		}
		return new ArrayList<>(found.values());
	}

	/**
	 * A passing point: the zone, the track trains one way come off, the track used both ways, and the track trains the other
	 * way go onto.
	 */
	public static final class Finding<T extends PlanTrack> {

		public final long zone;
		public final T from;
		public final T shared;
		public final T to;

		private Finding(long zone, T from, T shared, T to) {
			this.zone = zone;
			this.from = from;
			this.shared = shared;
			this.to = to;
		}
	}
}
