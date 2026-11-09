package mtr.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import transitsim.plan.DepotDuties;
import transitsim.plan.DepotRules;

/**
 * A depot's rules for going out and back and its named deadhead paths (运用补完 step 2) as the advanced editor has them:
 * IDs as strings, times in milliseconds.
 */
public final class EditorRules {

	private EditorRules() {
	}

	public static JsonObject toJson(DepotRules rules) {
		final JsonObject object = new JsonObject();
		final JsonArray rulesArray = new JsonArray();
		rules.rules.forEach(rule -> {
			final JsonObject ruleObject = new JsonObject();
			ruleObject.addProperty("id", String.valueOf(rule.id));
			final JsonArray lines = new JsonArray();
			rule.lineKeys.forEach(lines::add);
			ruleObject.add("lines", lines);
			ruleObject.addProperty("weekdays", rule.weekdays);
			ruleObject.addProperty("outEntrance", String.valueOf(rule.outEntrance));
			ruleObject.addProperty("backEntrance", String.valueOf(rule.backEntrance));
			ruleObject.addProperty("extraLead", rule.extraLeadMillis);
			ruleObject.addProperty("backSiding", String.valueOf(rule.backSiding));
			ruleObject.addProperty("outPath", String.valueOf(rule.outPath));
			ruleObject.addProperty("backPath", String.valueOf(rule.backPath));
			ruleObject.addProperty("waitThreshold", rule.waitThresholdMillis);
			rulesArray.add(ruleObject);
		});
		object.add("rules", rulesArray);
		final JsonArray pathsArray = new JsonArray();
		rules.paths.forEach(path -> {
			final JsonObject pathObject = new JsonObject();
			pathObject.addProperty("id", String.valueOf(path.id));
			pathObject.addProperty("name", path.name);
			pathObject.addProperty("kind", path.kind == DepotRules.DeadheadPath.BACK ? "back" : path.kind == DepotRules.DeadheadPath.SHUNT ? "shunt" : "out");
			pathObject.addProperty("from", String.valueOf(path.from));
			pathObject.addProperty("to", String.valueOf(path.to));
			final JsonArray waypoints = new JsonArray();
			for (final long waypoint : path.waypoints) {
				waypoints.add(String.valueOf(waypoint));
			}
			pathObject.add("waypoints", waypoints);
			pathsArray.add(pathObject);
		});
		object.add("paths", pathsArray);
		return object;
	}

	/**
	 * @return the rules and paths the page sent, as far as they read; null if it isn't rules
	 */
	public static DepotRules fromJson(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		final JsonObject object = element.getAsJsonObject();
		final DepotRules rules = new DepotRules();
		if (object.has("rules") && object.get("rules").isJsonArray()) {
			for (final JsonElement ruleElement : object.getAsJsonArray("rules")) {
				if (!ruleElement.isJsonObject() || rules.rules.size() >= DepotRules.MAX_RULES) {
					continue;
				}
				final JsonObject ruleObject = ruleElement.getAsJsonObject();
				final DepotRules.Rule rule = new DepotRules.Rule();
				rule.id = id(ruleObject, "id");
				if (ruleObject.has("lines") && ruleObject.get("lines").isJsonArray()) {
					ruleObject.getAsJsonArray("lines").forEach(line -> rule.lineKeys.add(line.getAsString()));
				}
				rule.weekdays = ruleObject.has("weekdays") ? ruleObject.get("weekdays").getAsInt() & DepotDuties.ALL_WEEKDAYS : 0;
				rule.outEntrance = id(ruleObject, "outEntrance");
				rule.backEntrance = id(ruleObject, "backEntrance");
				rule.extraLeadMillis = ruleObject.has("extraLead") ? Math.max(0, Math.min(3600000, ruleObject.get("extraLead").getAsLong())) : 0;
				rule.backSiding = id(ruleObject, "backSiding");
				rule.outPath = id(ruleObject, "outPath");
				rule.backPath = id(ruleObject, "backPath");
				rule.waitThresholdMillis = ruleObject.has("waitThreshold") ? Math.max(-1, Math.min(mtr.data.NetworkSettings.MAX_WAIT_THRESHOLD_MILLIS, ruleObject.get("waitThreshold").getAsLong())) : -1;
				rules.rules.add(rule);
			}
		}
		if (object.has("paths") && object.get("paths").isJsonArray()) {
			for (final JsonElement pathElement : object.getAsJsonArray("paths")) {
				if (!pathElement.isJsonObject() || rules.paths.size() >= DepotRules.MAX_PATHS) {
					continue;
				}
				final JsonObject pathObject = pathElement.getAsJsonObject();
				final DepotRules.DeadheadPath path = new DepotRules.DeadheadPath();
				path.id = id(pathObject, "id");
				path.name = pathObject.has("name") ? pathObject.get("name").getAsString() : "";
				final String kind = pathObject.has("kind") ? pathObject.get("kind").getAsString() : "out";
				path.kind = "back".equals(kind) ? DepotRules.DeadheadPath.BACK : "shunt".equals(kind) ? DepotRules.DeadheadPath.SHUNT : DepotRules.DeadheadPath.OUT;
				path.from = id(pathObject, "from");
				path.to = id(pathObject, "to");
				if (pathObject.has("waypoints") && pathObject.get("waypoints").isJsonArray()) {
					final JsonArray waypoints = pathObject.getAsJsonArray("waypoints");
					path.waypoints = new long[Math.min(waypoints.size(), DepotRules.MAX_WAYPOINTS)];
					for (int i = 0; i < path.waypoints.length; i++) {
						path.waypoints[i] = Long.parseLong(waypoints.get(i).getAsString());
					}
				}
				rules.paths.add(path);
			}
		}
		return rules;
	}

	private static long id(JsonObject object, String key) {
		try {
			return object.has(key) && !object.get(key).isJsonNull() ? Long.parseLong(object.get(key).getAsString()) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
