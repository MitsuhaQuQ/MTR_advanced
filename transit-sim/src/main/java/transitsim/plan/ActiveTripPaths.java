package transitsim.plan;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/** Keep valid paths still used by trains when regenerated duties no longer contain their keys. */
public final class ActiveTripPaths {
	private ActiveTripPaths() { }

	public static <T extends PlanTrack> void retain(Map<String, TripPath<T>> replacement, Collection<TripPath<T>> active, Predicate<TripPath<T>> valid) {
		for (TripPath<T> path : active) {
			if (path != null && !replacement.containsKey(path.key) && valid.test(path)) replacement.put(path.key, path);
		}
	}
}
