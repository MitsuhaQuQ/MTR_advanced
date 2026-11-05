package mtr.data;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The advanced editor's "一键生成运用" plans a depot with its operating lines, as the game plans depots running by a
 * timetable, even when the depot has duties set already, which then don't count as someone else's.
 */
class LineDutiesDraftTest {

	private static final int EIGHT = 8 * 3600 * 1000;
	private static final int NINE = 9 * 3600 * 1000;

	private static Route route(long id, String lineName, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		route.lineName = lineName;
		Arrays.stream(platformIds).forEach(platformId -> route.platformIds.add(new Route.RoutePlatform(platformId)));
		route.timetable.enabled = true;
		route.timetable.services.addAll(Arrays.asList(EIGHT, NINE));
		return route;
	}

	private static Depot depot(long id, long... routeIds) {
		final Depot depot = new Depot(id, TransportMode.TRAIN);
		Arrays.stream(routeIds).forEach(depot.routeIds::add);
		return depot;
	}

	private static void setDuties(Depot depot, DepotDuties.Segment... segments) {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.segments.addAll(Arrays.asList(segments));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		depot.duties.sidings.put(depot.id * 100, Collections.singletonList(slot));
		depot.duties.diagramMode = true;
	}

	@Test
	void aDepotWithDutiesSetIsPlannedWithItsLinesForADraft() {
		final Route up = route(10, "Line", 1, 2), down = route(11, "Line", 2, 1);
		final Depot drafting = depot(1, 10), automatic = depot(2, 11), setByHand = depot(3, 11);
		setDuties(drafting, new DepotDuties.Segment(10, EIGHT, 0, -1));
		setDuties(setByHand, new DepotDuties.Segment(11, NINE, 0, -1));
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(Arrays.asList(up, down)), new HashSet<>(Arrays.asList(drafting, automatic, setByHand)), new HashSet<>());
		dataCache.routeIdMap.put(up.id, up);
		dataCache.routeIdMap.put(down.id, down);
		Arrays.asList(drafting, automatic, setByHand).forEach(depot -> dataCache.depotIdMap.put(depot.id, depot));

		// With its duties set, the game doesn't plan it by line
		assertNull(LineDuties.of(drafting, dataCache, Collections.emptySet()));

		final LineDuties.Unit unit = LineDuties.of(drafting, dataCache, Collections.emptySet(), true);
		assertNotNull(unit);
		// The depot itself and the automatic depot of its line, not the one with duties set
		assertEquals(new TreeSet<>(Arrays.asList(1L, 2L)), new TreeSet<>(unit.depotIds));
		assertEquals(2, unit.getRoutes().size());
		// Its own set duties are what it is drafting again; the other depot's set duties are left to it
		assertEquals(Collections.singleton("11@" + NINE), unit.getTaken());
	}
}
