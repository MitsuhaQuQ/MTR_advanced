package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.client.ClientData;
import mtr.data.Route;
import transitsim.plan.PlanStop;
import transitsim.plan.RouteTimetable;
import transitsim.plan.ServiceExceptions;
import transitsim.plan.StopRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The times of each stop of a route's services as the game works them out ({@link StopRules}), for the timetable page.
 * The page sends its drafts here instead of working out times itself, so what it shows is what trains will run (S3e, A09).
 */
public final class EditorTimes {

	private static final int MAX_ROUTES = 256;

	private EditorTimes() {
	}

	/**
	 * @return the fastest arrival and departure at each stop of the route, or null if the game doesn't have them yet
	 */
	public static long[][] getFastest(Route route) {
		final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
		return times != null && times.length == route.platformIds.size() ? times : null;
	}

	/**
	 * Each stop as {@code [arrival, departure, overtake, passes, tooFast]}, the times in milliseconds from the first stop
	 * and the rest 1 or 0.
	 */
	public static JsonArray toJson(List<StopRules.StopTime> stopTimes) {
		final JsonArray timesArray = new JsonArray();
		stopTimes.forEach(stopTime -> {
			final JsonArray stopArray = new JsonArray();
			stopArray.add(stopTime.arrivalMillis);
			stopArray.add(stopTime.departureMillis);
			stopArray.add(stopTime.overtake ? 1 : 0);
			stopArray.add(stopTime.passes ? 1 : 0);
			stopArray.add(stopTime.tooFast ? 1 : 0);
			timesArray.add(stopArray);
		});
		return timesArray;
	}

	/**
	 * @param request {@code {routes: [{id, timetable}]}}, each timetable in the form the page edits it
	 * @return {@code {routes: [{id, tables: [{times, exceptions: [{service, times}]}]}]}} for the routes the game has
	 */
	public static JsonObject fromDraft(JsonObject request) {
		final JsonArray routesArray = new JsonArray();
		final JsonArray requestRoutes = request.getAsJsonArray("routes");
		for (int i = 0; requestRoutes != null && i < requestRoutes.size() && i < MAX_ROUTES; i++) {
			final JsonObject routeObject = requestRoutes.get(i).getAsJsonObject();
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(Long.parseLong(routeObject.get("id").getAsString()));
			if (route != null) {
				final JsonObject resultObject = new JsonObject();
				resultObject.addProperty("id", String.valueOf(route.id));
				resultObject.add("tables", forDraft(route, routeObject.getAsJsonObject("timetable")));
				routesArray.add(resultObject);
			}
		}
		final JsonObject resultObject = new JsonObject();
		resultObject.add("routes", routesArray);
		return resultObject;
	}

	private static JsonArray forDraft(Route route, JsonObject timetableObject) {
		final long[][] fastest = getFastest(route);
		final JsonArray passArray = timetableObject.getAsJsonArray("pass");
		final JsonArray tablesArray = timetableObject.getAsJsonArray("tables");
		final int tableCount = Math.min(tablesArray.size(), RouteTimetable.MAX_TABLES);
		final List<DraftStop> stops = new ArrayList<>();
		for (int i = 0; i < route.platformIds.size(); i++) {
			stops.add(new DraftStop(route.platformIds.get(i).platformId, getBoolean(passArray, i), tableCount));
		}

		final JsonArray resultTables = new JsonArray();
		for (int table = 0; table < tableCount; table++) {
			final JsonObject tableObject = tablesArray.get(table).getAsJsonObject();
			final int marginPercent = Math.max(0, Math.min(tableObject.get("marginPercent").getAsInt(), RouteTimetable.MAX_MARGIN_PERCENT));
			final JsonArray stopsArray = tableObject.getAsJsonArray("stops");
			for (int i = 0; i < stops.size(); i++) {
				final JsonObject stopObject = stopsArray != null && i < stopsArray.size() ? stopsArray.get(i).getAsJsonObject() : null;
				if (stopObject != null) {
					stops.get(i).arrivals[table] = clampSeconds(stopObject.get("arrival").getAsInt());
					stops.get(i).departures[table] = clampSeconds(stopObject.get("departure").getAsInt());
					stops.get(i).overtakes[table] = stopObject.get("overtake").getAsBoolean();
				}
			}

			final JsonObject resultTable = new JsonObject();
			resultTable.add("times", toJson(StopRules.times(stops, fastest, marginPercent, table, null)));
			final JsonArray resultExceptions = new JsonArray();
			final JsonArray exceptionsArray = tableObject.getAsJsonArray("exceptions");
			for (int i = 0; exceptionsArray != null && i < exceptionsArray.size() && i < RouteTimetable.MAX_SERVICES; i++) {
				final JsonObject exceptionObject = exceptionsArray.get(i).getAsJsonObject();
				final JsonObject resultException = new JsonObject();
				resultException.addProperty("service", exceptionObject.get("service").getAsInt());
				resultException.add("times", toJson(StopRules.times(stops, fastest, marginPercent, table, getStopExceptions(exceptionObject.getAsJsonArray("stops"), stops.size()))));
				resultExceptions.add(resultException);
			}
			resultTable.add("exceptions", resultExceptions);
			resultTables.add(resultTable);
		}
		return resultTables;
	}

	private static Map<Integer, ServiceExceptions.StopException> getStopExceptions(JsonArray stopsArray, int stopCount) {
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		for (int i = 0; stopsArray != null && i < stopsArray.size(); i++) {
			final JsonObject stopObject = stopsArray.get(i).getAsJsonObject();
			final int stop = stopObject.get("stop").getAsInt();
			if (stop >= 0 && stop < stopCount) {
				final ServiceExceptions.StopException stopException = new ServiceExceptions.StopException();
				stopException.runSeconds = clampSeconds(stopObject.get("run").getAsInt());
				stopException.dwellSeconds = clampSeconds(stopObject.get("dwell").getAsInt());
				stopException.pass = Math.max(-1, Math.min(stopObject.get("pass").getAsInt(), 1));
				stopException.overtake = Math.max(-1, Math.min(stopObject.get("overtake").getAsInt(), 1));
				stopExceptions.put(stop, stopException);
			}
		}
		return stopExceptions;
	}

	private static int clampSeconds(int seconds) {
		return Math.max(-1, Math.min(seconds, RouteTimetable.MAX_STOP_SECONDS));
	}

	private static boolean getBoolean(JsonArray array, int index) {
		final JsonElement element = array != null && index < array.size() ? array.get(index) : null;
		return element != null && !element.isJsonNull() && element.getAsBoolean();
	}

	/**
	 * A stop of a route with the page's draft of its times and passing.
	 */
	private static class DraftStop implements PlanStop {

		private final long platformId;
		private final boolean pass;
		private final int[] arrivals;
		private final int[] departures;
		private final boolean[] overtakes;

		private DraftStop(long platformId, boolean pass, int tableCount) {
			this.platformId = platformId;
			this.pass = pass;
			arrivals = new int[tableCount];
			departures = new int[tableCount];
			overtakes = new boolean[tableCount];
			Arrays.fill(arrivals, -1);
			Arrays.fill(departures, -1);
		}

		@Override
		public long getPlatformId() {
			return platformId;
		}

		@Override
		public boolean isPass() {
			return pass;
		}

		@Override
		public int getArrival(int table) {
			return table >= 0 && table < arrivals.length ? arrivals[table] : -1;
		}

		@Override
		public int getDeparture(int table) {
			return table >= 0 && table < departures.length ? departures[table] : -1;
		}

		@Override
		public boolean getOvertake(int table) {
			return table >= 0 && table < overtakes.length && overtakes[table];
		}
	}
}
