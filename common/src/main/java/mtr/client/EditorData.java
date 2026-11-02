package mtr.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.data.*;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the advanced editor shows, from what this client knows. Services are UTC milliseconds of the day, like
 * {@link RouteTimetable#services}, and the times at each stop are worked out here per table, the same way the timetable
 * screen does, so the page doesn't work them out itself.
 */
public final class EditorData {

	/**
	 * When the fastest times of each route were last asked for, so a page polling for changes doesn't ask again every time.
	 */
	private static final Map<Long, Long> TIMES_REQUESTED = new HashMap<>();
	private static final long TIMES_REQUEST_INTERVAL_MILLIS = 30000;

	public static JsonObject build() {
		if (Minecraft.getInstance().level == null) {
			throw new IllegalStateException("Not in a world");
		}
		final JsonObject data = new JsonObject();
		final int dayStartMillis = TimetableConfig.getDayStartMillis();
		data.addProperty("dayStartMillis", dayStartMillis);
		// Local time of day is UTC time of day plus this
		data.addProperty("offsetMillis", Math.floorMod(TimetableConfig.getDayStartHour() * 3600000L - dayStartMillis, (long) Depot.MILLISECONDS_PER_DAY));

		final List<Route> routes = ClientData.ROUTES.stream().filter(route -> !route.isHidden).sorted(Comparator.comparing((Route route) -> route.name).thenComparingLong(route -> route.id)).collect(Collectors.toList());
		final JsonArray routesArray = new JsonArray();
		routes.forEach(route -> routesArray.add(buildRoute(route)));
		data.add("routes", routesArray);

		final JsonArray depotsArray = new JsonArray();
		ClientData.DEPOTS.stream().sorted(Comparator.comparing((Depot depot) -> depot.name).thenComparingLong(depot -> depot.id)).forEach(depot -> {
			final JsonObject depotObject = new JsonObject();
			depotObject.addProperty("id", String.valueOf(depot.id));
			depotObject.addProperty("name", depot.name);
			depotObject.addProperty("cycleRoutes", depot.cycleRoutes);
			final JsonArray routeIds = new JsonArray();
			depot.routeIds.forEach(routeId -> routeIds.add(String.valueOf(routeId)));
			depotObject.add("routes", routeIds);
			final JsonArray sidingsArray = new JsonArray();
			ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
				final JsonObject sidingObject = new JsonObject();
				sidingObject.addProperty("id", String.valueOf(siding.id));
				sidingObject.addProperty("name", siding.name);
				sidingObject.addProperty("maxTrains", siding.getUnlimitedTrains() ? -1 : siding.getMaxTrains());
				sidingsArray.add(sidingObject);
			});
			depotObject.add("sidings", sidingsArray);
			depotsArray.add(depotObject);
		});
		data.add("depots", depotsArray);
		return data;
	}

	private static JsonObject buildRoute(Route route) {
		final JsonObject routeObject = new JsonObject();
		// Ids don't fit in a JavaScript number
		routeObject.addProperty("id", String.valueOf(route.id));
		routeObject.addProperty("name", route.name);
		routeObject.addProperty("line", route.getLineName());
		routeObject.addProperty("lineKey", route.transportMode + "|" + route.getLineKey());
		routeObject.addProperty("serviceType", route.getServiceType());
		routeObject.addProperty("color", route.getOwnColor());
		routeObject.addProperty("lineColor", route.color);
		routeObject.addProperty("circular", route.circularState != Route.CircularState.NONE);
		routeObject.addProperty("timetable", route.timetable.enabled);

		final JsonArray stopsArray = new JsonArray();
		route.platformIds.forEach(routePlatform -> {
			final JsonObject stopObject = new JsonObject();
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
			stopObject.addProperty("station", station == null ? "" : String.valueOf(station.id));
			stopObject.addProperty("name", station == null ? "" : station.name);
			stopObject.addProperty("platform", platform == null ? "" : platform.name);
			stopObject.addProperty("pass", routePlatform.pass);
			stopsArray.add(stopObject);
		});
		routeObject.add("stops", stopsArray);

		final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
		final boolean hasTimes = times != null && times.length == route.platformIds.size();
		routeObject.addProperty("hasTimes", hasTimes);
		if (!hasTimes) {
			requestTimes(route.id);
		}

		final JsonArray tablesArray = new JsonArray();
		final List<RouteTimetable> tables = route.timetable.getTables();
		for (int i = 0; i < tables.size(); i++) {
			final RouteTimetable table = tables.get(i);
			final JsonObject tableObject = new JsonObject();
			tableObject.addProperty("name", table.name);
			tableObject.addProperty("weekdays", table.weekdays);
			tableObject.addProperty("marginPercent", table.marginPercent);
			final JsonArray servicesArray = new JsonArray();
			table.services.forEach(servicesArray::add);
			tableObject.add("services", servicesArray);

			// Arrival and departure at each stop in milliseconds from the service's time at the first stop
			final JsonArray plansArray = new JsonArray();
			RouteTimetable.getStopPlans(route.platformIds, hasTimes ? times : null, table.marginPercent, i).forEach(plan -> {
				final JsonArray planArray = new JsonArray();
				planArray.add(plan.arrivalMillis);
				planArray.add(plan.departureMillis);
				plansArray.add(planArray);
			});
			tableObject.add("plans", plansArray);

			final JsonArray linksArray = new JsonArray();
			table.links.forEach((service, link) -> {
				final JsonObject linkObject = new JsonObject();
				linkObject.addProperty("service", service);
				linkObject.addProperty("route", String.valueOf(link.routeId));
				linkObject.addProperty("toService", link.service);
				linkObject.addProperty("fromStop", link.fromStop);
				linkObject.addProperty("toStop", link.toStop);
				linksArray.add(linkObject);
			});
			tableObject.add("links", linksArray);
			final JsonArray returnsArray = new JsonArray();
			table.returnServices.forEach(returnsArray::add);
			tableObject.add("returns", returnsArray);
			tablesArray.add(tableObject);
		}
		routeObject.add("tables", tablesArray);
		return routeObject;
	}

	private static void requestTimes(long routeId) {
		final long millis = System.currentTimeMillis();
		final Long lastRequested = TIMES_REQUESTED.get(routeId);
		if (lastRequested == null || millis - lastRequested >= TIMES_REQUEST_INTERVAL_MILLIS) {
			TIMES_REQUESTED.put(routeId, millis);
			PacketTrainDataGuiClient.requestTimetableTimesC2S(routeId);
		}
	}
}
