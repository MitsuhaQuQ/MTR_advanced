package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.advanced.ServiceExceptions;
import mtr.advanced.TimetableBoundaries;
import mtr.client.ClientData;
import mtr.data.Route;
import mtr.data.RouteTimetable;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies the advanced editor's changes. Each change is checked here first, then made to this client's copy and sent with
 * the same packets the game's own screens use, so the server checks permissions and data the same way.
 */
public final class EditorActions implements IPacket {

	private static final int MAX_OPERATIONS = 512;

	/**
	 * @param request {@code {operations: [...]}}, each with a {@code type}, the {@code route} id, and the revision the page
	 *                based it on, which stops a change made from data that has changed in the game since
	 * @return {@code {ok, applied: [operation index], errors: [{index, route, message}]}}
	 */
	public static JsonObject apply(JsonObject request) {
		final JsonArray applied = new JsonArray();
		final JsonArray errors = new JsonArray();
		final JsonArray operations = request.has("operations") ? request.getAsJsonArray("operations") : new JsonArray();

		if (!ClientData.hasPermission()) {
			addError(errors, -1, "", "没有编辑权限：需要创造模式，或服务端设置的权限等级");
		} else if (operations.size() > MAX_OPERATIONS) {
			addError(errors, -1, "", "一次应用的修改太多，请分几次应用");
		} else {
			for (int i = 0; i < operations.size(); i++) {
				final JsonObject operation = operations.get(i).getAsJsonObject();
				final String routeId = getString(operation, "route");
				String error;
				try {
					error = applyOperation(operation);
				} catch (Exception e) {
					e.printStackTrace();
					error = "修改的格式不对";
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
		result.add("errors", errors);
		return result;
	}

	/**
	 * @return why the change couldn't be made, or null if it was sent
	 */
	private static String applyOperation(JsonObject operation) {
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(parseId(getString(operation, "route")));
		if (route == null) {
			return "找不到这条线路，可能已被删除";
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
			case "managed":
				route.editorManaged = operation.get("managed").getAsBoolean();
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			default:
				return "不认识的修改类型";
		}
	}

	/**
	 * Checks the whole timetable before changing anything, so a bad one leaves the route as it was.
	 */
	private static String readTimetable(Route route, JsonObject timetable) {
		final int stopCount = route.platformIds.size();
		final JsonArray tables = timetable.getAsJsonArray("tables");
		final JsonArray pass = timetable.getAsJsonArray("pass");
		if (tables.size() < 1 || tables.size() > RouteTimetable.MAX_TABLES) {
			return String.format("表的数量要在 1 到 %s 之间", RouteTimetable.MAX_TABLES);
		}
		if (pass.size() != stopCount) {
			return "车站和游戏里的不一致，可能刚在游戏里改过，请刷新";
		}
		for (final JsonElement tableElement : tables) {
			final JsonObject table = tableElement.getAsJsonObject();
			if (table.getAsJsonArray("stops").size() != stopCount) {
				return "车站和游戏里的不一致，可能刚在游戏里改过，请刷新";
			}
			if (table.getAsJsonArray("services").size() > RouteTimetable.MAX_SERVICES) {
				return String.format("一张表最多 %s 个班次", RouteTimetable.MAX_SERVICES);
			}
			for (final JsonElement linkElement : table.getAsJsonArray("links")) {
				final JsonObject link = linkElement.getAsJsonObject();
				final String error = TimetableBoundaries.checkLink(route, new RouteTimetable.ServiceLink(parseId(getString(link, "route")), link.get("toService").getAsInt(), link.get("fromStop").getAsInt(), link.get("toStop").getAsInt()), ClientData.DATA_CACHE.routeIdMap::get, ClientData.DATA_CACHE.platformIdMap::get);
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
