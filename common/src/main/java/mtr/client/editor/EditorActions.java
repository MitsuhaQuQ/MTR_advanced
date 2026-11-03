package mtr.client.editor;

import mtr.advanced.PlanAdapters;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import transitsim.plan.ServiceExceptions;
import transitsim.plan.TimetableBoundaries;
import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Route;
import transitsim.plan.RouteTimetable;
import mtr.data.Station;
import mtr.data.TranslatedMessage;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import transitsim.network.PlatformGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the advanced editor's changes. Each change is checked here first, then made to this client's copy and sent with
 * the same packets the game's own screens use, so the server checks permissions and data the same way.
 */
public final class EditorActions implements IPacket {

	private static final int MAX_OPERATIONS = 512;
	private static final int MAX_NAME_LENGTH = 256;
	private static final int MAX_SERVICE_TYPE_LENGTH = 32;
	private static final int MAX_STOPS = 256;

	/**
	 * @param request {@code {operations: [...]}}, each with a {@code type}, the {@code route} id, and the revision the page
	 *                based it on, which stops a change made from data that has changed in the game since
	 * @return {@code {ok, applied: [operation index], created: [{index, id}], errors: [{index, route, message}]}}
	 */
	public static JsonObject apply(JsonObject request) {
		final JsonArray applied = new JsonArray();
		final JsonArray created = new JsonArray();
		final JsonArray errors = new JsonArray();
		final JsonArray operations = request.has("operations") ? request.getAsJsonArray("operations") : new JsonArray();

		if (!ClientData.hasPermission()) {
			addError(errors, -1, "", TranslatedMessage.get("gui.mtr.editor_no_permission"));
		} else if (operations.size() > MAX_OPERATIONS) {
			addError(errors, -1, "", TranslatedMessage.get("gui.mtr.editor_too_many_changes"));
		} else {
			for (int i = 0; i < operations.size(); i++) {
				final JsonObject operation = operations.get(i).getAsJsonObject();
				final String routeId = getString(operation, "route");
				String error;
				try {
					error = applyOperation(operation, i, created);
				} catch (Exception e) {
					e.printStackTrace();
					error = TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				if (error == null) {
					applied.add(i);
				} else {
					addError(errors, i, routeId, error);
				}
			}
		}

		final JsonObject result = new JsonObject();
		result.addProperty("ok", errors.size() == 0);
		result.add("applied", applied);
		result.add("created", created);
		result.add("errors", errors);
		return result;
	}

	/**
	 * @return why the change couldn't be made, or null if it was sent
	 */
	private static String applyOperation(JsonObject operation, int index, JsonArray created) {
		// A depot's duties belong to the depot, not to a route
		if ("duties".equals(getString(operation, "type"))) {
			return EditorDuties.apply(operation);
		}
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(parseId(getString(operation, "route")));
		if (route == null) {
			return TranslatedMessage.get("gui.mtr.editor_route_missing");
		}
		// A new type is made from the route it copies, which doesn't change, so it isn't checked against a revision
		if ("newRoute".equals(getString(operation, "type"))) {
			return createRoute(route, operation, index, created);
		}
		final boolean force = operation.has("force") && operation.get("force").getAsBoolean();
		if (!force && operation.has("base") && !getString(operation, "base").equals(EditorData.getRevision(route))) {
			return "CONFLICT";
		}

		switch (getString(operation, "type")) {
			case "timetable":
				final String error = readTimetable(route, operation.getAsJsonObject("timetable"));
				if (error != null) {
					return error;
				}
				route.editorManaged = true;
				route.timetable.sanitize();
				route.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "platforms":
				final String platformsError = changePlatforms(route, operation.getAsJsonArray("platforms"));
				if (platformsError != null) {
					return platformsError;
				}
				route.editorManaged = true;
				route.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				route.setCustomPath(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				// Times are kept by stop, and the server drops those of a stop whose platform changed, so they are sent again
				route.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "managed":
				route.editorManaged = operation.get("managed").getAsBoolean();
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "deleteRoute":
				// The same as deleting it on the dashboard; depots, duties and through links naming it are left as they are
				PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_ROUTE, route.id);
				ClientData.ROUTES.remove(route);
				return null;
			default:
				return TranslatedMessage.get("gui.mtr.editor_unknown_change");
		}
	}

	/**
	 * A new type (种别) of a line, with the stations the page found along the rails ({@link PlatformGraph}): the platforms a
	 * way between the ends the player picked runs through, each at a track the player may have changed. The route it is
	 * based on gives the line, colour, tables and depot; its stations aren't copied. Set times start empty.
	 */
	private static String createRoute(Route source, JsonObject operation, int index, JsonArray created) {
		final String name = getString(operation, "name").trim();
		final String serviceType = getString(operation, "serviceType").trim();
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			return TranslatedMessage.get("gui.mtr.editor_bad_route_name");
		}
		if (serviceType.isEmpty() || serviceType.length() > MAX_SERVICE_TYPE_LENGTH) {
			return TranslatedMessage.get("gui.mtr.editor_bad_service_type");
		}
		final JsonArray stops = operation.getAsJsonArray("stops");
		if (stops == null || stops.size() < 2 || stops.size() > MAX_STOPS) {
			return TranslatedMessage.get("gui.mtr.editor_stop_count", MAX_STOPS);
		}
		final int stopCount = stops.size();
		final long[] platformIds = new long[stopCount];
		final boolean[] pass = new boolean[stopCount];
		for (int i = 0; i < stopCount; i++) {
			final JsonObject stop = stops.get(i).getAsJsonObject();
			platformIds[i] = parseId(getString(stop, "platformId"));
			pass[i] = stop.get("pass").getAsBoolean() && i > 0 && i < stopCount - 1;
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformIds[i]);
			if (platform == null) {
				return TranslatedMessage.get("gui.mtr.editor_platform_missing", i + 1);
			}
			if (platform.transportMode != source.transportMode) {
				return TranslatedMessage.get("gui.mtr.editor_platform_mode", i + 1);
			}
		}
		final String connectionError = checkConnected(platformIds);
		if (connectionError != null) {
			return connectionError;
		}
		final Depot depot = operation.has("depot") ? ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot"))) : null;
		if (depot != null && depot.transportMode != source.transportMode) {
			return TranslatedMessage.get("gui.mtr.editor_depot_mode");
		}

		final Route newRoute = source.duplicate();
		newRoute.name = name;
		newRoute.serviceType = serviceType;
		newRoute.lineName = source.getLineName();
		if (operation.has("color")) {
			// The line keeps the colour of its first route, which applyLineColors gives back to this one
			newRoute.setOwnColor(operation.get("color").getAsInt() & 0xFFFFFF);
		}
		newRoute.editorManaged = true;

		// Its own stations, so nothing of the stops of the route it is based on is kept, nor a path drawn for them
		newRoute.platformIds.clear();
		for (int i = 0; i < stopCount; i++) {
			final Route.RoutePlatform newStop = new Route.RoutePlatform(platformIds[i]);
			newStop.pass = pass[i];
			newRoute.platformIds.add(newStop);
		}
		newRoute.getPathData().clear();
		if (newRoute.platformIds.stream().noneMatch(routePlatform -> routePlatform.platformId == newRoute.timetable.entryPlatformId)) {
			newRoute.timetable.entryPlatformId = 0;
		}

		final JsonArray services = operation.has("services") ? operation.getAsJsonArray("services") : new JsonArray();
		final int servicesTable = operation.has("table") ? operation.get("table").getAsInt() : 0;
		if (services.size() > RouteTimetable.MAX_SERVICES) {
			return TranslatedMessage.get("gui.mtr.editor_too_many_services", RouteTimetable.MAX_SERVICES);
		}
		for (int i = 0; i < newRoute.timetable.getTableCount(); i++) {
			final RouteTimetable table = newRoute.timetable.getTable(i);
			table.services.clear();
			table.links.clear();
			table.returnServices.clear();
			table.exceptions.clear();
			if (i == servicesTable) {
				services.forEach(service -> table.services.add(Math.floorMod(service.getAsInt(), Depot.MILLISECONDS_PER_DAY)));
			}
		}
		newRoute.timetable.sanitize();

		ClientData.ROUTES.add(newRoute);
		newRoute.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		newRoute.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		newRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		newRoute.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		if (!newRoute.getPathData().isEmpty()) {
			newRoute.setCustomPath(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		}
		if (depot != null) {
			depot.routeIds.add(newRoute.id);
			depot.setData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		}
		ClientData.DATA_CACHE.sync();

		final JsonObject createdObject = new JsonObject();
		createdObject.addProperty("index", index);
		createdObject.addProperty("id", String.valueOf(newRoute.id));
		created.add(createdObject);
		return null;
	}

	/**
	 * Checks a run of platforms against the rails: all joined into one network, and a train can get from each to the next,
	 * passing other platforms on the way. Turning back at a stop is allowed here; the page warns about it.
	 *
	 * @return why it can't run, or null
	 */
	private static String checkConnected(long[] platformIds) {
		final PlatformGraph platformGraph = ClientData.PATH_REGIONS.platformGraph;
		final long network = platformGraph.getNetworkOf(platformIds[0]);
		for (int i = 0; i < platformIds.length; i++) {
			if (network == 0 || platformGraph.getNetworkOf(platformIds[i]) != network) {
				return TranslatedMessage.get("gui.mtr.editor_other_network", i + 1);
			}
			if (i > 0 && !platformGraph.canReach(platformIds[i - 1], platformIds[i])) {
				return TranslatedMessage.get("gui.mtr.editor_no_path", i, i + 1);
			}
		}
		return null;
	}

	/**
	 * Moves stops of a route to other tracks of the same station, from those the server found for each stop. Everything
	 * else of a stop stays, its set times included.
	 *
	 * @return why it can't, or null once the route has been changed
	 */
	private static String changePlatforms(Route route, JsonArray platforms) {
		final int stopCount = route.platformIds.size();
		if (platforms == null || platforms.size() != stopCount) {
			return TranslatedMessage.get("gui.mtr.editor_stations_changed");
		}
		final long[] platformIds = new long[stopCount];
		for (int i = 0; i < stopCount; i++) {
			final long platformId = parseId(platforms.get(i).getAsString());
			platformIds[i] = platformId;
			final long oldPlatformId = route.platformIds.get(i).platformId;
			final Station oldStation = ClientData.DATA_CACHE.platformIdToStation.get(oldPlatformId);
			if (platformId != oldPlatformId && (oldStation == null || ClientData.DATA_CACHE.platformIdToStation.get(platformId) != oldStation)) {
				return TranslatedMessage.get("gui.mtr.editor_wrong_track", i + 1);
			}
		}
		// Only tracks changed are checked, so a route that was already broken in the game can still be put right a stop at a time
		if (!Arrays.equals(platformIds, route.platformIds.stream().mapToLong(routePlatform -> routePlatform.platformId).toArray())) {
			final String connectionError = checkConnected(platformIds);
			if (connectionError != null) {
				return connectionError;
			}
		}

		final int tableCount = route.timetable.getTableCount();
		boolean changed = false;
		for (int i = 0; i < stopCount; i++) {
			final Route.RoutePlatform oldStop = route.platformIds.get(i);
			if (oldStop.platformId == platformIds[i]) {
				continue;
			}
			final Route.RoutePlatform newStop = new Route.RoutePlatform(platformIds[i]);
			newStop.customDestination = oldStop.customDestination;
			newStop.waypoints.addAll(oldStop.waypoints);
			newStop.dwellOverride = oldStop.dwellOverride;
			newStop.pass = oldStop.pass;
			for (int j = 0; j < tableCount; j++) {
				newStop.setArrival(j, oldStop.getArrival(j));
				newStop.setDeparture(j, oldStop.getDeparture(j));
				newStop.setOvertake(j, oldStop.getOvertake(j));
			}
			if (route.timetable.entryPlatformId == oldStop.platformId) {
				route.timetable.entryPlatformId = platformIds[i];
			}
			route.platformIds.set(i, newStop);
			changed = true;
		}
		// A drawn path follows the old tracks
		if (changed) {
			route.getPathData().clear();
		}
		return null;
	}

	/**
	 * Checks the whole timetable before changing anything, so a bad one leaves the route as it was.
	 */
	private static String readTimetable(Route route, JsonObject timetable) {
		final int stopCount = route.platformIds.size();
		final JsonArray tables = timetable.getAsJsonArray("tables");
		final JsonArray pass = timetable.getAsJsonArray("pass");
		if (tables.size() < 1 || tables.size() > RouteTimetable.MAX_TABLES) {
			return TranslatedMessage.get("gui.mtr.editor_table_count", RouteTimetable.MAX_TABLES);
		}
		if (pass.size() != stopCount) {
			return TranslatedMessage.get("gui.mtr.editor_stations_changed");
		}
		for (final JsonElement tableElement : tables) {
			final JsonObject table = tableElement.getAsJsonObject();
			if (table.getAsJsonArray("stops").size() != stopCount) {
				return TranslatedMessage.get("gui.mtr.editor_stations_changed");
			}
			if (table.getAsJsonArray("services").size() > RouteTimetable.MAX_SERVICES) {
				return TranslatedMessage.get("gui.mtr.editor_too_many_services", RouteTimetable.MAX_SERVICES);
			}
			for (final JsonElement linkElement : table.getAsJsonArray("links")) {
				final JsonObject link = linkElement.getAsJsonObject();
				final String error = TimetableBoundaries.checkLink(route, new RouteTimetable.ServiceLink(parseId(getString(link, "route")), link.get("toService").getAsInt(), link.get("fromStop").getAsInt(), link.get("toStop").getAsInt()), ClientData.DATA_CACHE.routeIdMap::get, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap));
				if (error != null) {
					return error;
				}
			}
		}

		while (route.timetable.getTableCount() > tables.size()) {
			route.removeTable(route.timetable.getTableCount() - 1);
		}
		while (route.timetable.getTableCount() < tables.size()) {
			route.addTable(0);
		}
		route.timetable.enabled = timetable.get("enabled").getAsBoolean();
		route.timetable.entryPlatformId = parseId(getString(timetable, "entryPlatform"));

		for (int i = 0; i < tables.size(); i++) {
			final JsonObject tableObject = tables.get(i).getAsJsonObject();
			final RouteTimetable table = route.timetable.getTable(i);
			table.name = getString(tableObject, "name");
			table.weekdays = tableObject.get("weekdays").getAsInt();
			table.marginPercent = tableObject.get("marginPercent").getAsInt();
			table.services.clear();
			tableObject.getAsJsonArray("services").forEach(service -> table.services.add(service.getAsInt()));
			table.links.clear();
			tableObject.getAsJsonArray("links").forEach(linkElement -> {
				final JsonObject link = linkElement.getAsJsonObject();
				table.links.put(link.get("service").getAsInt(), new RouteTimetable.ServiceLink(parseId(getString(link, "route")), link.get("toService").getAsInt(), link.get("fromStop").getAsInt(), link.get("toStop").getAsInt()));
			});
			table.returnServices.clear();
			tableObject.getAsJsonArray("returns").forEach(service -> table.returnServices.add(service.getAsInt()));
			table.exceptions.clear();
			if (tableObject.has("exceptions")) {
				tableObject.getAsJsonArray("exceptions").forEach(exceptionElement -> {
					final JsonObject exceptionObject = exceptionElement.getAsJsonObject();
					final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
					exceptionObject.getAsJsonArray("stops").forEach(stopElement -> {
						final JsonObject stopObject = stopElement.getAsJsonObject();
						final int stop = stopObject.get("stop").getAsInt();
						if (stop >= 0 && stop < stopCount) {
							final ServiceExceptions.StopException stopException = new ServiceExceptions.StopException();
							stopException.runSeconds = stopObject.get("run").getAsInt();
							stopException.dwellSeconds = stopObject.get("dwell").getAsInt();
							stopException.pass = stopObject.get("pass").getAsInt();
							stopException.overtake = stopObject.get("overtake").getAsInt();
							stopExceptions.put(stop, stopException);
						}
					});
					table.exceptions.put(exceptionObject.get("service").getAsInt(), stopExceptions);
				});
			}
			final JsonArray stops = tableObject.getAsJsonArray("stops");
			for (int j = 0; j < stopCount; j++) {
				final JsonObject stop = stops.get(j).getAsJsonObject();
				final Route.RoutePlatform routePlatform = route.platformIds.get(j);
				routePlatform.setArrival(i, stop.get("arrival").getAsInt());
				routePlatform.setDeparture(i, stop.get("departure").getAsInt());
				routePlatform.setOvertake(i, stop.get("overtake").getAsBoolean());
			}
		}
		for (int j = 0; j < stopCount; j++) {
			route.platformIds.get(j).pass = pass.get(j).getAsBoolean();
		}
		return null;
	}

	private static void addError(JsonArray errors, int index, String routeId, String message) {
		final JsonObject error = new JsonObject();
		error.addProperty("index", index);
		error.addProperty("route", routeId);
		error.addProperty("message", message);
		errors.add(error);
	}

	private static String getString(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}

	/**
	 * Ids come as text, as they don't fit in a JavaScript number.
	 */
	private static long parseId(String id) {
		try {
			return Long.parseLong(id);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
