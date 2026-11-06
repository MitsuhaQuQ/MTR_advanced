package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.client.ClientData;
import mtr.data.*;
import transitsim.plan.Contingency;

import java.util.*;

/**
 * The contingency plans of each rail system for trains running late or stuck, and each depot's trains reported and those a
 * plan is on for, for the advanced editor (D2): plans are edited in the draft; starting a plan, setting what is done with
 * one train and ending either are a dispatcher's commands, sent at once.
 */
public final class EditorContingencies {

	private static final String[] TURNS = {"none", "nearest", "station"};
	private static final String[] AFTERS = {"resume", "deadhead"};

	private EditorContingencies() {
	}

	public static JsonObject toJson(Contingency contingency) {
		final JsonObject object = new JsonObject();
		object.addProperty("id", String.valueOf(contingency.id));
		object.addProperty("name", contingency.name);
		final JsonArray routes = new JsonArray();
		contingency.routeIds.forEach(routeId -> routes.add(String.valueOf(routeId)));
		object.add("routes", routes);
		object.addProperty("lateMinutes", contingency.lateMinutes);
		object.addProperty("waitMinutes", contingency.waitMinutes);
		object.addProperty("deadlock", contingency.deadlock);
		object.addProperty("manual", contingency.manual);
		object.addProperty("turn", TURNS[contingency.turn]);
		object.addProperty("turnStation", String.valueOf(contingency.turnStationId));
		object.addProperty("afterTurn", AFTERS[contingency.afterTurn]);
		final JsonArray skips = new JsonArray();
		contingency.skipStationIds.forEach(stationId -> skips.add(String.valueOf(stationId)));
		object.add("skipStations", skips);
		object.addProperty("takeover", contingency.takeover);
		return object;
	}

	public static Contingency fromJson(JsonObject object) {
		final Contingency contingency = new Contingency();
		contingency.id = parseId(object, "id");
		contingency.name = Contingency.limitName(object.has("name") ? object.get("name").getAsString() : "");
		readIds(object, "routes", contingency.routeIds, Contingency.MAX_ROUTES);
		contingency.lateMinutes = clamp(getInt(object, "lateMinutes", 5), Contingency.MAX_MINUTES);
		contingency.waitMinutes = clamp(getInt(object, "waitMinutes", 10), Contingency.MAX_MINUTES);
		contingency.deadlock = getBoolean(object, "deadlock", true);
		contingency.manual = getBoolean(object, "manual", false);
		contingency.turn = Math.max(0, Arrays.asList(TURNS).indexOf(object.has("turn") ? object.get("turn").getAsString() : "none"));
		contingency.turnStationId = parseId(object, "turnStation");
		contingency.afterTurn = Math.max(0, Arrays.asList(AFTERS).indexOf(object.has("afterTurn") ? object.get("afterTurn").getAsString() : "resume"));
		readIds(object, "skipStations", contingency.skipStationIds, Contingency.MAX_STATIONS);
		contingency.takeover = getBoolean(object, "takeover", false);
		return contingency;
	}

	/**
	 * Adds the depot's trains reported late or stuck and those a plan is on for, each with the rail system its route is in.
	 */
	public static void addToDepot(JsonObject depotObject, Depot depot) {
		final JsonArray alarms = new JsonArray();
		boolean handled = false;
		for (final String entry : depot.clientAlarms) {
			if ("|".equals(entry)) {
				handled = true;
				continue;
			}
			final String[] parts = entry.split(" ");
			if (parts.length < 9) {
				continue;
			}
			final JsonObject alarm = new JsonObject();
			alarm.addProperty("train", parts[0]);
			alarm.addProperty("siding", parts[1]);
			alarm.addProperty("slot", Integer.parseInt(parts[2]));
			alarm.addProperty("route", parts[3]);
			alarm.addProperty("reason", parts[4]);
			alarm.addProperty("late", Long.parseLong(parts[5]));
			alarm.addProperty("waiting", Long.parseLong(parts[6]));
			alarm.addProperty("plan", parts[7]);
			alarm.addProperty("system", parts[8]);
			alarm.addProperty("handled", handled);
			alarms.add(alarm);
		}
		depotObject.add("alarms", alarms);
	}

	/**
	 * The rail systems with routes, each a network of rails joined together (D2): its anchor, routes and their stations to
	 * choose from, plans with which are on, and what is set for single trains.
	 */
	public static JsonArray systemsToJson() {
		final transitsim.network.PlatformGraph platformGraph = ClientData.PATH_REGIONS.platformGraph;
		final Map<Long, List<Route>> byNetwork = new TreeMap<>();
		ClientData.DATA_CACHE.routeIdMap.values().stream().filter(route -> !route.platformIds.isEmpty()).sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			final long network = platformGraph.getNetworkOf(route.getFirstPlatformId());
			if (network != 0) {
				byNetwork.computeIfAbsent(network, key -> new ArrayList<>()).add(route);
			}
		});
		final JsonArray systems = new JsonArray();
		byNetwork.forEach((network, routes) -> {
			final long anchor = ClientData.CONTINGENCY_SYSTEMS.anchorOf(network, platformGraph);
			if (anchor == 0) {
				return;
			}
			final transitsim.plan.Contingencies contingencies = ClientData.CONTINGENCY_SYSTEMS.systems.getOrDefault(anchor, new transitsim.plan.Contingencies());
			final JsonObject system = new JsonObject();
			system.addProperty("anchor", String.valueOf(anchor));
			final JsonArray routeIds = new JsonArray();
			final Set<Long> stationIds = new LinkedHashSet<>();
			routes.forEach(route -> {
				routeIds.add(String.valueOf(route.id));
				route.platformIds.forEach(routePlatform -> {
					final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
					if (station != null) {
						stationIds.add(station.id);
					}
				});
			});
			system.add("routes", routeIds);
			final JsonArray stations = new JsonArray();
			stationIds.forEach(stationId -> {
				final Station station = ClientData.DATA_CACHE.stationIdMap.get(stationId);
				final JsonObject object = new JsonObject();
				object.addProperty("id", String.valueOf(stationId));
				object.addProperty("name", station == null ? String.valueOf(stationId) : station.name);
				stations.add(object);
			});
			system.add("stations", stations);
			addPlans(system, contingencies);
			// The other systems rails joined into this network, kept and holding after this one until merged into it
			final JsonArray others = new JsonArray();
			ClientData.CONTINGENCY_SYSTEMS.anchorsOf(network, platformGraph).stream().filter(other -> other != anchor).forEach(other -> {
				final JsonObject otherObject = new JsonObject();
				otherObject.addProperty("anchor", String.valueOf(other));
				addPlans(otherObject, ClientData.CONTINGENCY_SYSTEMS.systems.get(other));
				others.add(otherObject);
			});
			system.add("others", others);
			systems.add(system);
		});
		return systems;
	}

	private static void addPlans(JsonObject object, transitsim.plan.Contingencies contingencies) {
		final JsonArray plans = new JsonArray();
		contingencies.plans.values().forEach(plan -> {
			final JsonObject planObject = toJson(plan);
			planObject.addProperty("active", contingencies.active.contains(plan.id));
			plans.add(planObject);
		});
		object.add("plans", plans);
		final JsonObject adhoc = new JsonObject();
		contingencies.adhoc.forEach((trainId, contingency) -> adhoc.add(String.valueOf(trainId), toJson(contingency)));
		object.add("adhoc", adhoc);
	}

	/**
	 * @return a copy of a system's plans and state as the client has them, to change and send
	 */
	private static transitsim.plan.Contingencies copyOf(long anchor) {
		final transitsim.plan.Contingencies contingencies = ClientData.CONTINGENCY_SYSTEMS.systems.get(anchor);
		return contingencies == null ? new transitsim.plan.Contingencies() : contingencies.copy();
	}

	/**
	 * The plans drafted for a rail system, which take the place of its plans.
	 *
	 * @return why they couldn't be set, or null
	 */
	public static String applyPlans(JsonObject operation) {
		final long anchor = parseId(operation, "system");
		if (ClientData.DATA_CACHE.platformIdMap.get(anchor) == null || !operation.has("plans") || !operation.get("plans").isJsonArray()) {
			return TranslatedMessage.get("gui.mtr.editor_bad_change");
		}
		final transitsim.plan.Contingencies contingencies = copyOf(anchor);
		contingencies.plans.clear();
		for (final JsonElement element : operation.getAsJsonArray("plans")) {
			if (element.isJsonObject() && contingencies.plans.size() < transitsim.plan.Contingencies.MAX_PLANS) {
				final Contingency plan = fromJson(element.getAsJsonObject());
				if (plan.id > 0) {
					contingencies.plans.putIfAbsent(plan.id, plan);
				}
			}
		}
		mtr.packet.ContingencyPackets.sendPlansC2S(anchor, contingencies);
		return null;
	}

	/**
	 * A dispatcher's command: start or end a plan waiting for one, set what is done with a train, or end that.
	 *
	 * @return why it couldn't be sent, or null
	 */
	public static String applyCommand(JsonObject operation) {
		final long anchor = parseId(operation, "system");
		final String action = operation.has("action") ? operation.get("action").getAsString() : "";
		if (ClientData.DATA_CACHE.platformIdMap.get(anchor) == null) {
			return TranslatedMessage.get("gui.mtr.editor_bad_change");
		}
		final transitsim.plan.Contingencies contingencies = copyOf(anchor);
		final long planId = parseId(operation, "plan");
		final long trainId = parseId(operation, "train");
		switch (action) {
			case "activate":
				if (!contingencies.plans.containsKey(planId)) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				contingencies.active.add(planId);
				break;
			case "deactivate":
				contingencies.active.remove(planId);
				break;
			case "adhoc":
				if (trainId == 0 || !operation.has("setting") || !operation.get("setting").isJsonObject() || contingencies.adhoc.size() >= transitsim.plan.Contingencies.MAX_ADHOC && !contingencies.adhoc.containsKey(trainId)) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				final Contingency setting = fromJson(operation.getAsJsonObject("setting"));
				setting.id = 0;
				setting.routeIds.clear();
				setting.manual = false;
				contingencies.adhoc.put(trainId, setting);
				break;
			case "release":
				contingencies.adhoc.remove(trainId);
				break;
			default:
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
		}
		mtr.packet.ContingencyPackets.sendStateC2S(anchor, contingencies);
		return null;
	}

	private static long parseId(JsonObject object, String key) {
		try {
			return object.has(key) && !object.get(key).isJsonNull() ? Long.parseLong(object.get(key).getAsString()) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void readIds(JsonObject object, String key, Set<Long> ids, int max) {
		if (object.has(key) && object.get(key).isJsonArray()) {
			for (final JsonElement element : object.getAsJsonArray(key)) {
				if (ids.size() >= max) {
					break;
				}
				try {
					ids.add(Long.parseLong(element.getAsString()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private static int getInt(JsonObject object, String key, int fallback) {
		try {
			return object.has(key) ? object.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
		try {
			return object.has(key) ? object.get(key).getAsBoolean() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static int clamp(int value, int max) {
		return Math.max(0, Math.min(max, value));
	}
}
