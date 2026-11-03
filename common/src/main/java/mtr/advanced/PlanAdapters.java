package mtr.advanced;

import mtr.data.Platform;

import java.util.Map;
import java.util.function.LongPredicate;

/**
 * What the component's timetable rules need from the host's data, other than what routes give through
 * {@link transitsim.plan.PlanRoute}.
 */
public final class PlanAdapters {

	private PlanAdapters() {
	}

	/**
	 * @return whether a platform is marked as a timetable boundary, for {@link transitsim.plan.TimetableBoundaries}
	 */
	public static LongPredicate boundaryPlatforms(Map<Long, Platform> platforms) {
		return platformId -> {
			final Platform platform = platforms.get(platformId);
			return platform != null && platform.timetableBoundary;
		};
	}
}
