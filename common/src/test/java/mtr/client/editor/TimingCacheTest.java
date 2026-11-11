package mtr.client.editor;

import mtr.data.Route;
import mtr.data.TransportMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimingCacheTest {
	@Test
	void staleResponsesAndChangedPerformanceAreRejectedButDwellEditsKeepTheBaseline() {
		TimingCache cache = new TimingCache();
		Route route = new Route(1, TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		int old = cache.request(route);
		int latest = cache.request(route);
		assertFalse(cache.accept(route, old));
		assertTrue(cache.accept(route, latest));
		assertTrue(cache.matches(route));
		route.platformIds.get(1).setDeparture(0, 120);
		assertTrue(cache.matches(route));
		route.platformIds.get(1).pass = true;
		assertFalse(cache.matches(route));
		latest = cache.request(route);
		route.timetable.minAcceleration = 0.002F;
		assertFalse(cache.accept(route, latest));
		cache.remember(route);
		assertTrue(cache.matches(route));
		cache.clear();
		assertFalse(cache.matches(route));
	}
}
