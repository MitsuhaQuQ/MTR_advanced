package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.advanced.SandboxRuns;
import mtr.data.Route;
import mtr.data.TransportMode;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;
import transitsim.plan.RouteTimetable;
import transitsim.sim.SimDataset;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor sends to be simulated (S5): drafts on copies of routes, and the report in parts.
 */
class EditorSimulationTest {

	@Test
	void aCopiedRouteIsTheSameRouteToTheSimulation() {
		final Route route = makeRoute();
		assertArrayEquals(new SimDataset.RouteData(route).toBytes(), new SimDataset.RouteData(EditorSimulation.copyRoute(route)).toBytes());
	}

	@Test
	void aDraftChangesTheCopyOnly() {
		final Route route = makeRoute();
		final byte[] before = new SimDataset.RouteData(route).toBytes();
		final Route copy = EditorSimulation.copyRoute(route);
		assertNull(EditorActions.readTimetable(copy, timetableJson(new int[]{6 * 3600000, 7 * 3600000}, 120)));
		copy.timetable.sanitize();

		assertArrayEquals(before, new SimDataset.RouteData(route).toBytes());
		assertEquals(Arrays.asList(6 * 3600000, 7 * 3600000), copy.timetable.services);
		assertEquals(120, copy.platformIds.get(1).getArrival(0));
		assertFalse(Arrays.equals(before, new SimDataset.RouteData(copy).toBytes()));
	}

	@Test
	void theReportIsPutBackTogetherFromItsParts() {
		final byte[] bytes = new byte[1234567];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) (i * 31);
		}
		final List<byte[]> parts = SandboxRuns.split(bytes, 512 * 1024);
		assertEquals(3, parts.size());
		assertArrayEquals(bytes, SandboxRuns.join(parts.toArray(new byte[0][])));
		assertEquals(1, SandboxRuns.split(new byte[0], 512 * 1024).size());
	}

	@Test
	void theDayIsTheNextOneOnTheWeekday() {
		OperatingDay.set("UTC", 3);
		// A Wednesday at noon
		final long now = 1_700_049_600_000L;
		for (int weekday = 0; weekday < 7; weekday++) {
			final long dayStart = SandboxRuns.getDayStart(now, weekday);
			assertEquals(weekday, OperatingDay.getWeekday(dayStart + 3600000));
			assertEquals(0, OperatingDay.getTimeInDay(dayStart));
			assertTrue(dayStart > now - OperatingDay.MILLIS_PER_DAY && dayStart < now + 7L * OperatingDay.MILLIS_PER_DAY);
		}
		OperatingDay.set("UTC", 0);
	}

	private static Route makeRoute() {
		final Route route = new Route(TransportMode.TRAIN);
		for (int i = 1; i <= 3; i++) {
			route.platformIds.add(new Route.RoutePlatform(i));
		}
		route.timetable.enabled = true;
		route.timetable.services.add(6 * 3600000);
		route.timetable.services.add(6 * 3600000 + 600000);
		route.platformIds.get(1).setArrival(0, 90);
		route.timetable.sanitize();
		return route;
	}

	private static JsonObject timetableJson(int[] services, int secondArrival) {
		final JsonObject table = new JsonObject();
		table.addProperty("name", "");
		table.addProperty("weekdays", RouteTimetable.ALL_WEEKDAYS);
		table.addProperty("marginPercent", 0);
		final JsonArray servicesArray = new JsonArray();
		Arrays.stream(services).forEach(servicesArray::add);
		table.add("services", servicesArray);
		table.add("links", new JsonArray());
		table.add("returns", new JsonArray());
		table.add("exceptions", new JsonArray());
		final JsonArray stops = new JsonArray();
		for (int i = 0; i < 3; i++) {
			final JsonObject stop = new JsonObject();
			stop.addProperty("arrival", i == 1 ? secondArrival : -1);
			stop.addProperty("departure", -1);
			stop.addProperty("overtake", false);
			stops.add(stop);
		}
		table.add("stops", stops);
		final JsonArray tables = new JsonArray();
		tables.add(table);
		final JsonArray pass = new JsonArray();
		for (int i = 0; i < 3; i++) {
			pass.add(false);
		}
		final JsonObject timetable = new JsonObject();
		timetable.add("tables", tables);
		timetable.add("pass", pass);
		timetable.addProperty("enabled", true);
		timetable.addProperty("entryPlatform", "0");
		return timetable;
	}
}
