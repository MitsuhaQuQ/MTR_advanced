package mtr.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteLineTest {

	private static Route route(String name) {
		final Route route = new Route(TransportMode.TRAIN);
		route.name = name;
		return route;
	}

	@Test
	void directionsOfALineMatchWithoutIdenticalNames() {
		assertTrue(route("1号线 上行").isSameLine(route("1号线 下行")));
		assertTrue(route("1号线(内环)").isSameLine(route("1号线（外环）")));
		assertTrue(route("中央線 上り|Chuo Line Up").isSameLine(route("中央線 下り|Chuo Line Down")));
		assertTrue(route("Line 1 Northbound").isSameLine(route("line 1 southbound")));
	}

	@Test
	void textAfterTheDoubleBarIsIgnored() {
		assertTrue(route("1号线||往A").isSameLine(route("1号线||往B")));
	}

	@Test
	void identicalNamesStillMatch() {
		assertTrue(route("机场线").isSameLine(route("机场线")));
	}

	@Test
	void differentLinesAreKeptApart() {
		assertFalse(route("1号线 上行").isSameLine(route("2号线 上行")));
		assertFalse(route("1号线").isSameLine(null));
	}

	@Test
	void wordsEndingLikeADirectionAreNotCut() {
		assertEquals("Backup", Route.getAutoLineName("Backup"));
		assertEquals("Lockdown", Route.getAutoLineName("Lockdown"));
	}

	@Test
	void aNameThatIsOnlyADirectionStays() {
		assertEquals("上行", Route.getAutoLineName("上行"));
	}

	@Test
	void setLineOverridesTheName() {
		final Route route1 = route("东线");
		final Route route2 = route("西线");
		assertFalse(route1.isSameLine(route2));
		route1.lineName = "环线";
		route2.lineName = " 环线 ";
		assertTrue(route1.isSameLine(route2));
	}

	@Test
	void duplicateIsAnIndependentCopyInTheSameLine() {
		final Route route = route("1号线 上行");
		route.color = 0x123456;
		final Route.RoutePlatform stop = new Route.RoutePlatform(10);
		stop.pass = true;
		stop.setArrival(60);
		stop.setDeparture(90);
		stop.overtake = true;
		route.platformIds.add(stop);
		route.timetable.enabled = true;
		route.timetable.services.add(1000);

		final Route copy = route.duplicate();

		assertNotEquals(route.id, copy.id);
		assertEquals("1号线 上行_duplicate", copy.name);
		assertEquals(0x123456, copy.color);
		assertTrue(route.isSameLine(copy));
		assertNotSame(stop, copy.platformIds.get(0));
		assertTrue(copy.platformIds.get(0).pass);
		assertEquals(60, copy.platformIds.get(0).getArrival());
		assertEquals(90, copy.platformIds.get(0).getDeparture());
		assertTrue(copy.platformIds.get(0).overtake);

		copy.timetable.services.clear();
		copy.platformIds.get(0).setArrival(70);
		assertEquals(1, route.timetable.services.size());
		assertEquals(60, stop.getArrival());
	}
}
