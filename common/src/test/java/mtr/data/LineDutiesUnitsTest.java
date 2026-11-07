package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The check for changes to the auto duties makes every depot's planning unit at once ({@link LineDuties#unitsOf}): the
 * same units as made depot by depot, each made once and shared by its depots.
 */
class LineDutiesUnitsTest {

	private static final int EIGHT = 8 * 3600 * 1000;

	private static Route route(long id, String lineName, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		route.lineName = lineName;
		Arrays.stream(platformIds).forEach(platformId -> route.platformIds.add(new Route.RoutePlatform(platformId)));
		route.timetable.enabled = true;
		route.timetable.services.add(EIGHT);
		return route;
	}

	private static Depot depot(long id, long... routeIds) {
		final Depot depot = new Depot(id, TransportMode.TRAIN);
		Arrays.stream(routeIds).forEach(depot.routeIds::add);
		return depot;
	}

	@Test
	void unitsMadeAtOnceAreTheUnitsMadeDepotByDepot() {
		// Line A run by depots 1 and 2, line B by depot 3, line C with no timetable by depot 4
		final Route a1 = route(10, "A", 1, 2), a2 = route(11, "A", 2, 1), b = route(20, "B", 3, 4), c = route(30, "C", 5, 6);
		c.timetable.enabled = false;
		final List<Depot> depots = Arrays.asList(depot(1, 10), depot(2, 11), depot(3, 20), depot(4, 30));
		final List<Route> routes = Arrays.asList(a1, a2, b, c);
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(routes), new HashSet<>(depots), new HashSet<>());
		routes.forEach(route -> dataCache.routeIdMap.put(route.id, route));
		depots.forEach(depot -> dataCache.depotIdMap.put(depot.id, depot));

		final Map<Long, LineDuties.Unit> units = LineDuties.unitsOf(depots, dataCache, Collections.emptySet());
		depots.forEach(depot -> {
			final LineDuties.Unit one = LineDuties.of(depot, dataCache, Collections.emptySet());
			final LineDuties.Unit shared = units.get(depot.id);
			if (one == null) {
				assertNull(shared);
			} else {
				assertEquals(one.key, shared.key);
				assertEquals(one.depotIds, shared.depotIds);
			}
		});
		// The depots of line A share one unit, made once
		assertSame(units.get(1L), units.get(2L));
		assertNotSame(units.get(1L), units.get(3L));
		assertFalse(units.containsKey(4L));
	}
}
