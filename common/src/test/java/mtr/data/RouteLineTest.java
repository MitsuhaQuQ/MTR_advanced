package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	void serviceTypesOfALineMatch() {
		assertTrue(route("1号线 快速 上行").isSameLine(route("1号线 普通 下行")));
		assertTrue(route("快速 中央線").isSameLine(route("各停 中央線")));
		assertTrue(route("中央線（特急）").isSameLine(route("中央線")));
		assertTrue(route("Line 1 Express|1号线 快速").isSameLine(route("Line 1 Local|1号线 普通")));
	}

	@Test
	void wordsInsideANameAreNotCut() {
		assertEquals("快速路", Route.getAutoLineName("快速路"));
		assertEquals("Expressway", Route.getAutoLineName("Expressway"));
		assertEquals("Localhost", Route.getAutoLineName("Localhost"));
	}

	@Test
	void aNameOfOnlyServiceWordsStays() {
		assertEquals("快速 上行", Route.getAutoLineName("快速 上行"));
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
	void everyRouteOfALineShowsTheColorOfItsFirstRoute() {
		final Route down = route("1号线 下行");
		final Route up = route("1号线 上行");
		final Route other = route("2号线");
		down.setOwnColor(0x111111);
		up.setOwnColor(0x222222);
		other.setOwnColor(0x333333);

		Route.applyLineColors(Arrays.asList(down, up, other));

		assertEquals(0x222222, down.color);
		assertEquals(0x222222, up.color);
		assertEquals(0x333333, other.color);
		assertEquals(0x111111, down.getOwnColor());
		assertEquals(0x222222, up.getOwnColor());
	}

	@Test
	void routesSavedWithoutAnOwnColorKeepTheColorTheyHad() {
		final Route route1 = route("1号线 上行");
		final Route route2 = route("1号线 下行");
		route1.color = 0x111111;
		route2.color = 0x222222;

		Route.applyLineColors(Arrays.asList(route1, route2));
		Route.applyLineColors(Arrays.asList(route1, route2));

		assertEquals(0x111111, route1.getOwnColor());
		assertEquals(0x222222, route2.getOwnColor());
		assertEquals(0x111111, route2.color);
	}

	@Test
	void serviceTypeComesFromTheNameUnlessSet() {
		assertEquals("快速", route("1号线 快速 上行").getServiceType());
		assertEquals("express", route("Line 1 Express").getServiceType());
		assertEquals("", route("1号线 上行").getServiceType());

		final Route route = route("1号线 快速");
		route.serviceType = " 通勤 ";
		assertEquals("通勤", route.getServiceType());
	}

	@Test
	void serviceTypesAreSplitByCommonSeparators() {
		final Set<String> serviceTypes = new HashSet<>(Arrays.asList("普通", "急行", "local"));
		assertEquals(serviceTypes, Depot.parseServiceTypes("普通,急行，LOCAL"));
		assertEquals(serviceTypes, Depot.parseServiceTypes(" 普通、急行 local "));
		assertTrue(Depot.parseServiceTypes("  ").isEmpty());
	}

	@Test
	void vehiclesOnlyRunTheirServiceTypes() {
		final Route local = route("1号线 普通");
		final Route express = route("1号线 急行");
		final Route rapid = route("1号线 快速");
		final Route limited = route("1号线 特急");
		final Route other = route("1号线");
		final List<Route> routes = Arrays.asList(local, express, rapid, limited, other);

		assertEquals(Arrays.asList(local, express, other), Depot.filterRoutes(routes, Depot.parseServiceTypes("普通,急行")));
		assertEquals(Arrays.asList(rapid, limited, other), Depot.filterRoutes(routes, Depot.parseServiceTypes("快速,特急")));
		assertEquals(routes, Depot.filterRoutes(routes, Depot.parseServiceTypes("")));
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
