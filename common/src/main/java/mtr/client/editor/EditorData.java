package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.data.*;
import mtr.advanced.TimetableBoundaries;
import mtr.client.ClientData;
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
		data.addProperty("canEdit", ClientData.hasPermission());
		data.addProperty("regionCount", ClientData.PATH_REGIONS.regionCount);

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
		routeObject.addProperty("entryPlatform", String.valueOf(route.timetable.entryPlatformId));
		routeObject.addProperty("editorManaged", route.editorManaged);
		routeObject.addProperty("revision", getRevision(route));

		final JsonArray stopsArray = new JsonArray();
		for (int i = 0; i < route.platformIds.size(); i++) {
			final Route.RoutePlatform routePlatform = route.platformIds.get(i);
			final JsonObject stopObject = new JsonObject();
			// Where a service can leave or join this route part of the way along
			stopObject.addProperty("boundary", TimetableBoundaries.isBoundaryStop(route, i, ClientData.DATA_CACHE.platformIdMap::get));
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
			stopObject.addProperty("platformId", String.valueOf(routePlatform.platformId));
			stopObject.addProperty("dwellOverride", routePlatform.dwellOverride);
			stopObject.addProperty("station", station == null ? "" : String.valueOf(station.id));
			stopObject.addProperty("name", station == null ? "" : station.name);
			stopObject.addProperty("platform", platform == null ? "" : platform.name);
			stopObject.addProperty("pass", routePlatform.pass);
			stopObject.addProperty("passingTrack", platform != null && platform.passingTrack);
			// The tracks the track picker offers here: the same station, the same region, and reachable from the stops around
			final JsonArray candidatesArray = new JsonArray();
			for (final long candidateId : ClientData.PATH_REGIONS.getCandidates(route.id, i, routePlatform.platformId)) {
				final Platform candidate = ClientData.DATA_CACHE.platformIdMap.get(candidateId);
				if (candidate != null) {
					final JsonObject candidateObject = new JsonObject();
					candidateObject.addProperty("id", String.valueOf(candidateId));
					candidateObject.addProperty("name", candidate.name);
					candidateObject.addProperty("passingTrack", candidate.passingTrack);
					candidatesArray.add(candidateObject);
				}
			}
			stopObject.add("candidates", candidatesArray);
			stopsArray.add(stopObject);
		}
		routeObject.add("stops", stopsArray);

		final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
		final boolean hasTimes = times != null && times.length == route.platformIds.size();
		routeObject.addProperty("hasTimes", hasTimes);
		if (hasTimes) {
			// The fastest arrival and departure at each stop, which set times can't be earlier than
			final JsonArray fastestArray = new JsonArray();
			for (final long[] stopTimes : times) {
				final JsonArray stopArray = new JsonArray();
				stopArray.add(stopTimes[0]);
				stopArray.add(stopTimes[1]);
				fastestArray.add(stopArray);
			}
			routeObject.add("fastest", fastestArray);
		} else {
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

			// The times set at each stop in seconds from the first stop, or -1 to follow the stop before, and whether the
			// train waits there to be overtaken
			final JsonArray setTimesArray = new JsonArray();
			final int tableIndex = i;
			route.platformIds.forEach(routePlatform -> {
				final JsonObject stopObject = new JsonObject();
				stopObject.addProperty("arrival", routePlatform.getArrival(tableIndex));
				stopObject.addProperty("departure", routePlatform.getDeparture(tableIndex));
				stopObject.addProperty("overtake", routePlatform.getOvertake(tableIndex));
				setTimesArray.add(stopObject);
			});
			tableObject.add("stops", setTimesArray);

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

			// Stops where one service runs differently, with its own times worked out
			final JsonArray exceptionsArray = new JsonArray();
			table.exceptions.forEach((service, stopExceptions) -> {
				final JsonObject exceptionObject = new JsonObject();
				exceptionObject.addProperty("service", service);
				final JsonArray exceptionStopsArray = new JsonArray();
				stopExceptions.forEach((stop, stopException) -> {
					final JsonObject stopObject = new JsonObject();
					stopObject.addProperty("stop", stop);
					stopObject.addProperty("run", stopException.runSeconds);
					stopObject.addProperty("dwell", stopException.dwellSeconds);
					stopObject.addProperty("pass", stopException.pass);
					stopObject.addProperty("overtake", stopException.overtake);
					exceptionStopsArray.add(stopObject);
				});
				exceptionObject.add("stops", exceptionStopsArray);
				final JsonArray servicePlansArray = new JsonArray();
				RouteTimetable.getStopPlans(route.platformIds, hasTimes ? times : null, table.marginPercent, tableIndex, stopExceptions).forEach(plan -> {
					final JsonArray planArray = new JsonArray();
					planArray.add(plan.arrivalMillis);
					planArray.add(plan.departureMillis);
					servicePlansArray.add(planArray);
				});
				exceptionObject.add("plans", servicePlansArray);
				exceptionsArray.add(exceptionObject);
			});
			tableObject.add("exceptions", exceptionsArray);
			tablesArray.add(tableObject);
		}
		routeObject.add("tables", tablesArray);
		return routeObject;
	}

	/**
	 * @return text that changes whenever anything the editor can change on the route does, made from the same packets that
	 * send those changes, so a draft made before another change in the game is noticed before it overwrites it
	 */
	public static String getRevision(Route route) {
		final int[] hash = {Boolean.hashCode(route.editorManaged)};
		route.setTimetable(packet -> hash[0] = hash[0] * 31 + packet.nioBuffer().hashCode());
		route.setPlatformIds(packet -> hash[0] = hash[0] * 31 + packet.nioBuffer().hashCode());
		return Integer.toHexString(hash[0]);
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
