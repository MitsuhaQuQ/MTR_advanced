package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto duties are kept while they hold (optimisation round 4, step 4): what a unit's duties are made of leaves out every
 * time, so a change in times alone has them checked rather than planned again, and the depots keep a record of the plan
 * their duties came from.
 */
class LineDutiesKeepTest {

	private static final int EIGHT = 8 * 3600 * 1000;
	private static final int NINE = 9 * 3600 * 1000;

	private static Route route(long id, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		route.lineName = "Line";
		Arrays.stream(platformIds).forEach(platformId -> route.platformIds.add(new Route.RoutePlatform(platformId)));
		route.timetable.enabled = true;
		route.timetable.services.add(EIGHT);
		return route;
	}

	private static DataCache dataCache(Route... routes) {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		Arrays.stream(routes).forEach(route -> depot.routeIds.add(route.id));
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(Arrays.asList(routes)), new HashSet<>(Collections.singleton(depot)), new HashSet<>());
		Arrays.stream(routes).forEach(route -> dataCache.routeIdMap.put(route.id, route));
		dataCache.depotIdMap.put(depot.id, depot);
		return dataCache;
	}

	private static LineDuties.Unit unit(DataCache dataCache) {
		return LineDuties.of(dataCache.depotIdMap.get(1L), dataCache, Collections.emptySet());
	}

	@Test
	void theLeastTimeToChangeEndsChangesTheKeyButNotWhatTheDutiesAreMadeOf() {
		final Route up = route(10, 1, 2), down = route(11, 2, 1);
		final DataCache dataCache = dataCache(up, down);
		final LineDuties.Unit before = unit(dataCache);
		dataCache.networkSettings.minReverseMillis = 45000;
		final LineDuties.Unit after = unit(dataCache);
		assertNotEquals(before.key, after.key, "path generation runs again");
		assertEquals(before.basis, after.basis, "the duties are checked, not planned again");
	}

	@Test
	void aChangedTimetableChangesWhatTheDutiesAreMadeOf() {
		final Route up = route(10, 1, 2), down = route(11, 2, 1);
		final DataCache dataCache = dataCache(up, down);
		final LineDuties.Unit before = unit(dataCache);
		up.timetable.services.add(NINE);
		assertNotEquals(before.basis, unit(dataCache).basis);
	}

	@Test
	void aPlanRecordReadsBackAsWritten() {
		final DataCache dataCache = dataCache(route(10, 1, 2), route(11, 2, 1));
		final LineDuties.Unit unit = unit(dataCache);
		final LineDuties.Plan plan = unit.plan(route -> null, 0);
		assertEquals(unit.basis, plan.basis);
		assertEquals(dataCache.networkSettings.minReverseMillis, plan.minReverseMillis, "planned with the network's least time to change ends");
		plan.minReverseMillis = 45000;
		plan.trains = 3;
		plan.needed = 4;
		plan.issues.add("SHORT_TURNAROUND 2 25000 30000");
		plan.returnSidings.put("100/0/1", 200L);
		plan.suggestedSiding = "车厂 一号线";
		final LineDuties.Plan read = LineDuties.Plan.fromBytes(plan.toBytes(), plan.lineNames);
		assertNotNull(read);
		assertEquals(plan.basis, read.basis);
		assertEquals(45000, read.minReverseMillis);
		assertEquals(3, read.trains);
		assertEquals(4, read.needed);
		assertEquals(plan.missingTimes, read.missingTimes);
		assertEquals(plan.issues, read.issues);
		assertEquals(plan.returnSidings, read.returnSidings);
		assertEquals("车厂 一号线", read.suggestedSiding);
		assertNull(LineDuties.Plan.fromBytes(new byte[0], ""));
		// More issues than are kept: the rest still reads
		for (int i = 0; i < 5000; i++) {
			plan.issues.add("PLATFORM_CONFLICT platform 2 at " + i);
		}
		final LineDuties.Plan many = LineDuties.Plan.fromBytes(plan.toBytes(), plan.lineNames);
		assertNotNull(many);
		assertEquals(plan.returnSidings, many.returnSidings);
		assertEquals("车厂 一号线", many.suggestedSiding);
		assertNull(LineDuties.Plan.fromBytes(new byte[]{9, 9}, ""));
	}

	@Test
	void withNoRecordTheDutiesArePlannedAgain() {
		final DataCache dataCache = dataCache(route(10, 1, 2), route(11, 2, 1));
		final LineDuties.Unit unit = unit(dataCache);
		final List<String> why = new ArrayList<>();
		assertNull(unit.keep(Collections.emptyMap(), Collections.emptyMap(), route -> null, 0, null, null, why::add));
		assertEquals(1, why.size(), why.toString());
	}
}
