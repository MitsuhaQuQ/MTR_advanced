package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Where a route ends at a station whose other platforms it lists to turn back at (站前折返) and the next route starts in
 * that station: which of those platforms a train may end at, in the order the next route would rather start from
 * (种别优先), and the ways from the stop before the last to the next route's second stop, one at each. Moved out of the
 * game's depot path building (2026-11-13, step 2c of splitting the depot); the host finds each way, the rules of which
 * platforms count, in what order, and when the ways make a section are here.
 */
public final class FrontTurnBacks {

	private FrontTurnBacks() {
	}

	/**
	 * The ways the host finds.
	 *
	 * @param <T> the host's track
	 */
	public interface Ways<T extends PlanTrack> {

		/**
		 * @return the way from the stop before the last into that platform and back out to the next route's second stop,
		 * or null if there is none a train could take in place of the usual way
		 */
		List<T> find(long platformId);

		/**
		 * Told of each platform with no way to it.
		 */
		default void unusable(long platformId) {
		}
	}

	/**
	 * @param lastPlatformId      the platform the route before ends at
	 * @param nextFirstPlatformId the platform the next route starts at
	 * @param terminalPlatforms   the platforms the route before lists to turn back at
	 * @param startPlatforms      the platforms the next route would rather start from, best first
	 * @param atStation           whether a platform is at the same station and still exists
	 * @return the platforms a train may end the route before at, the one it would rather start the next route from first
	 */
	public static List<Long> order(long lastPlatformId, long nextFirstPlatformId, List<Long> terminalPlatforms, List<Long> startPlatforms, LongPredicate atStation) {
		final List<Long> candidates = new ArrayList<>();
		// The usual way is one of them when the train usually turns back at its last platform
		if (lastPlatformId == nextFirstPlatformId) {
			candidates.add(lastPlatformId);
		}
		terminalPlatforms.forEach(platformId -> {
			if (platformId != lastPlatformId && !candidates.contains(platformId) && atStation.test(platformId)) {
				candidates.add(platformId);
			}
		});
		final List<Long> ordered = new ArrayList<>();
		startPlatforms.forEach(platformId -> {
			if (candidates.contains(platformId) && !ordered.contains(platformId)) {
				ordered.add(platformId);
			}
		});
		candidates.forEach(platformId -> {
			if (!ordered.contains(platformId)) {
				ordered.add(platformId);
			}
		});
		return ordered;
	}

	/**
	 * The usual way is one of the ways when the train usually turns back at its last platform, and the last when it turns
	 * back after the station (站后), so the platforms come first and the way after the station is taken only when none of
	 * them does.
	 *
	 * @param stopRail the rail of the body of the stop before the last, where the ways start
	 * @param ordered  the platforms as {@link #order} gives them
	 * @param usualWay the way the body takes
	 * @return the section, or null unless at least two ways can be used, as one way is no choice
	 */
	public static <T extends PlanTrack> TurnBackSection<T> section(int stopRail, long lastPlatformId, long nextFirstPlatformId, List<Long> ordered, List<T> usualWay, Ways<T> ways) {
		final List<List<T>> found = new ArrayList<>();
		final boolean usualAtPlatform = lastPlatformId == nextFirstPlatformId;
		int defaultWay = -1;
		for (final long platformId : ordered) {
			if (platformId == lastPlatformId) {
				defaultWay = found.size();
				found.add(usualWay);
			} else {
				final List<T> way = ways.find(platformId);
				found.add(way);
				if (way == null) {
					ways.unusable(platformId);
				}
			}
		}
		if (!usualAtPlatform) {
			defaultWay = found.size();
			found.add(usualWay);
		}
		int usable = 0;
		for (final List<T> way : found) {
			if (way != null) {
				usable++;
			}
		}
		return usable >= 2 ? new TurnBackSection<>(stopRail, found, defaultWay) : null;
	}
}
