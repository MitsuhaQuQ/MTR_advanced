package transitsim.plan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which service a train runs next after reaching the end of a route, where that is known for sure rather than guessed: set
 * by the player (a service carrying on from its last stop onto another's first, the same data as through running), or one
 * after the other in a duty. The player's choice comes first. Services left over are paired with the nearest one by the
 * check that uses this ({@link TimetableConflicts}), and in the web editor the same way.
 * <p>
 * A train turning back at the platform it arrived at stays there from its arrival until its next service leaves, so its
 * last stop's departure is the next service's, and the next service's first arrival is its own.
 */
public final class TerminalPairs {

	private TerminalPairs() {
	}

	/**
	 * @param routes the routes, by ID, for their timetables
	 * @param duties the duties of the depots running by them, each a train's services of one day in order
	 * @return by route, then by service: the route and service its train runs next, as {@code {route, service}}
	 */
	public static Map<Long, Map<Integer, long[]>> fixedNext(Map<Long, RouteTimetable> routes, List<List<DepotDuties.Segment>> duties) {
		final Map<Long, Map<Integer, long[]>> next = new HashMap<>();
		// One after the other in a duty, from the end of one route to the start of the next
		duties.forEach(segments -> {
			for (int i = 0; i + 1 < segments.size(); i++) {
				final DepotDuties.Segment segment = segments.get(i);
				final DepotDuties.Segment following = segments.get(i + 1);
				if (segment.toStop < 0 && following.fromStop == 0) {
					next.computeIfAbsent(segment.routeId, key -> new HashMap<>()).put(segment.service, new long[]{following.routeId, following.service});
				}
			}
		});
		// Set by the player, which comes first
		routes.forEach((routeId, timetable) -> timetable.getTables().forEach(table -> table.links.forEach((service, link) -> {
			if (link.fromStop < 0 && link.toStop == 0) {
				next.computeIfAbsent(routeId, key -> new HashMap<>()).put(service, new long[]{link.routeId, link.service});
			}
		})));
		return next;
	}
}
