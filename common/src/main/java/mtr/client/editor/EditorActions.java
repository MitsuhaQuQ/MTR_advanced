package mtr.client.editor;

import mtr.client.ClientEditing;

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
import mtr.data.TransportMode;
import mtr.packet.EditGuard;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import transitsim.network.PlatformGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
		return applyAndSend(request).result;
	}

	/**
	 * Like {@link #apply}, but with the server's answer to wait for: a change the server turns down, as another player
	 * changed the route meanwhile, isn't counted as applied, so the page keeps it in its draft as a conflict. A route the
	 * page's draft is out of date for keeps the whole batch from being sent, and nothing is changed.
	 */
	public static Pending applyAndSend(JsonObject request) {
		final JsonArray operations = request.has("operations") ? request.getAsJsonArray("operations") : new JsonArray();
		// A route the page's draft is out of date for isn't sent at all, and stays in the draft; the others still are
		final Set<String> outOfDate = new HashSet<>();
		if (ClientData.hasPermission() && operations.size() <= MAX_OPERATIONS) {
			for (int i = 0; i < operations.size(); i++) {
				final JsonObject operation = operations.get(i).getAsJsonObject();
				if (isOutOfDate(operation)) {
					outOfDate.add(getString(operation, "route"));
				}
			}
		}
		return applyChecked(request, operations, outOfDate);
	}

	/**
	 * @return the group a change is made all or not at all with: the route it changes, or the depot whose duties it
	 * changes, or a new route on its own
	 */
	private static String getGroup(JsonObject operation, int index) {
		final String type = getString(operation, "type");
		if ("duties".equals(type) || "depot".equals(type)) {
			return EditGuard.DEPOT + ":" + getString(operation, "depot");
		} else if ("newRoute".equals(type)) {
			return "new:" + index;
		} else {
			return EditGuard.ROUTE + ":" + parseId(getString(operation, "route"));
		}
	}

	/**
	 * @return whether the page based this change on a version of its route the game has moved on from
	 */
	private static boolean isOutOfDate(JsonObject operation) {
		final String type = getString(operation, "type");
		if ("duties".equals(type) || "depot".equals(type) || "newRoute".equals(type) || operation.has("force") && operation.get("force").getAsBoolean()) {
			return false;
		}
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(parseId(getString(operation, "route")));
		if (route == null) {
			return false;
		}
		// Part by part, the parts the editor's changes of a route send
		if (operation.has("parts") && operation.get("parts").isJsonObject()) {
			final Map<String, Integer> current = ClientEditing.getRevisions(route);
			final Map<String, Integer> parts = getParts(operation);
			return EDITOR_PARTS.stream().anyMatch(part -> !current.getOrDefault(part, 0).equals(parts.getOrDefault(part, 0)));
		}
		return operation.has("base") && !getString(operation, "base").equals(EditorData.getRevision(route));
	}

	/**
	 * The parts of a route the editor's changes send: its timetable, its stops, and its settings sent with them.
	 */
	private static final List<String> EDITOR_PARTS = Arrays.asList("timetable_services", "platform_ids", "is_light_rail_route");

	private static Map<String, Integer> getParts(JsonObject operation) {
		final Map<String, Integer> parts = new HashMap<>();
		operation.getAsJsonObject("parts").entrySet().forEach(entry -> parts.put(entry.getKey(), entry.getValue().getAsInt()));
		return parts;
	}

	/**
	 * A depot's routes (in no order for one running by a timetable), whether its trains stay on the line, and its
	 * sidings' routes and fleets, as the page drafted them from the depot as it was ({@code base}); then its path is
	 * found again, with the result on the page's sync panel.
	 */
	private static final int MAX_WAYPOINTS = 16;

	private static String applyDepot(JsonObject operation) {
		final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot")));
		if (depot == null) {
			return TranslatedMessage.get("gui.mtr.editor_depot_missing");
		}
		final boolean force = operation.has("force") && operation.get("force").getAsBoolean();
		if (!force && operation.has("base") && !getString(operation, "base").equals(EditorData.getRevision(depot))) {
			return TranslatedMessage.get("gui.mtr.editor_depot_changed");
		}
		if (operation.has("routes")) {
			final List<Long> routeIds = new ArrayList<>();
			for (final JsonElement element : operation.getAsJsonArray("routes")) {
				final long routeId = parseId(element.getAsString());
				final Route route = ClientData.DATA_CACHE.routeIdMap.get(routeId);
				if (route == null || route.transportMode != depot.transportMode) {
					return TranslatedMessage.get("gui.mtr.editor_route_missing");
				}
				if (!routeIds.contains(routeId)) {
					routeIds.add(routeId);
				}
			}
			depot.routeIds.clear();
			depot.routeIds.addAll(routeIds);
		}
		if (operation.has("cycleRoutes")) {
			depot.cycleRoutes = operation.get("cycleRoutes").getAsBoolean();
		}
		depot.setData(packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
		// The trains it gives each pool of its sidings (P2-C); a pool left out, or null, has its sidings' fleets added up
		// How its trains go out and back, and its named deadhead paths (运用补完 step 2)
		if (operation.has("rules")) {
			final transitsim.plan.DepotRules rules = EditorRules.fromJson(operation.get("rules"));
			if (rules != null) {
				depot.setRules(rules, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
			}
		}
		if (operation.has("poolFleets") && operation.get("poolFleets").isJsonObject()) {
			final Map<String, Integer> poolFleets = new TreeMap<>();
			for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("poolFleets").entrySet()) {
				if (!entry.getValue().isJsonNull()) {
					poolFleets.put(entry.getKey(), entry.getValue().getAsInt());
				}
			}
			depot.setPoolFleets(poolFleets, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
		}
		// How the trains of each pool run (R1); a pool left out, or null, runs as its first siding has it set
		if (operation.has("vehicleSettings") && operation.get("vehicleSettings").isJsonObject()) {
			final Map<String, mtr.data.VehicleSettings> settings = new TreeMap<>();
			for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("vehicleSettings").entrySet()) {
				if (entry.getValue().isJsonObject()) {
					final JsonObject value = entry.getValue().getAsJsonObject();
					settings.put(entry.getKey(), new mtr.data.VehicleSettings(value.get("acceleration").getAsFloat(), value.get("deceleration").getAsFloat(), value.get("manual").getAsBoolean(), value.get("maxManualSpeed").getAsInt(), value.get("manualToAutomaticTime").getAsInt(), value.has("maxSpeed") && !value.get("maxSpeed").isJsonNull() ? value.get("maxSpeed").getAsInt() : 0));
				}
			}
			depot.setVehicleSettings(settings, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
		}
		// What each vehicle runs, set here instead of in the siding screen (5c); only those changed are sent
		if (operation.has("serviceTypes") && operation.get("serviceTypes").isJsonObject()) {
			for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("serviceTypes").entrySet()) {
				final String text = entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString();
				if (!Depot.parseServiceTypes(text).equals(depot.getServiceTypes(entry.getKey()))) {
					depot.setVehicleServiceTypes(entry.getKey(), text, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
				}
			}
		}
		// Forget how much longer than planned its trains were measured to take (D1); what was adopted stays
		if (operation.has("clearBuffers") && operation.get("clearBuffers").getAsBoolean()) {
			depot.clearBuffers(packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
		}
		// The time added to the ways out, turns round and ways back, by key in seconds (round 4, step 6); all of them
		if (operation.has("addedBuffers") && operation.get("addedBuffers").isJsonObject()) {
			final Map<String, Long> added = new TreeMap<>();
			for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("addedBuffers").entrySet()) {
				try {
					final long[] parts = transitsim.plan.Buffers.parse(entry.getKey());
					final long seconds = entry.getValue().getAsLong();
					if (seconds > 0) {
						added.put(transitsim.plan.Buffers.key((int) parts[0], parts[1], parts[2]), seconds * 1000);
					}
				} catch (Exception ignored) {
				}
			}
			depot.setAddedBuffers(added.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(added), packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
		}
		if (operation.has("sidings")) {
			for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("sidings").entrySet()) {
				final mtr.data.Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(parseId(entry.getKey()));
				if (siding == null || ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) != depot) {
					return TranslatedMessage.get("gui.mtr.editor_siding_missing");
				}
				final JsonObject sidingObject = entry.getValue().getAsJsonObject();
				final List<Long> sidingRoutes = new ArrayList<>();
				if (sidingObject.has("routes")) {
					sidingObject.getAsJsonArray("routes").forEach(element -> sidingRoutes.add(parseId(element.getAsString())));
				}
				final int fleet = sidingObject.has("fleet") ? sidingObject.get("fleet").getAsInt() : siding.getFleetSize();
				siding.setOperation(sidingRoutes, fleet, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_SIDING, packet));
			}
		}
		ClientData.DATA_CACHE.sync();
		PacketTrainDataGuiClient.generatePathC2S(depot.id);
		return null;
	}

	private static Pending applyChecked(JsonObject request, JsonArray operations, Set<String> outOfDate) {
		final List<String> groups = new ArrayList<>();
		final java.util.concurrent.CompletableFuture<ClientEditing.BatchResult>[] future = new java.util.concurrent.CompletableFuture[]{null};
		final JsonArray applied = new JsonArray();
		final JsonArray created = new JsonArray();
		final JsonArray errors = new JsonArray();

		if (!ClientData.hasPermission()) {
			addError(errors, -1, "", TranslatedMessage.get("gui.mtr.editor_no_permission"));
		} else if (operations.size() > MAX_OPERATIONS) {
			addError(errors, -1, "", TranslatedMessage.get("gui.mtr.editor_too_many_changes"));
		} else {
			// Sent to the server as one batch, which it makes all or not at all, checking each route against the revision
			// the page based its changes on, as the game has it then (multiplayer edit conflicts)
			final Map<Long, String> routeBases = new HashMap<>();
			ClientEditing.startBatch();
			try {
				for (int i = 0; i < operations.size(); i++) {
					final JsonObject operation = operations.get(i).getAsJsonObject();
					final String routeId = getString(operation, "route");
					final boolean force = operation.has("force") && operation.get("force").getAsBoolean();
					groups.add(getGroup(operation, i));
					if (outOfDate.contains(routeId) && !"duties".equals(getString(operation, "type")) && !"depot".equals(getString(operation, "type")) && !"newRoute".equals(getString(operation, "type"))) {
						addError(errors, i, routeId, "CONFLICT");
						continue;
					}
					ClientEditing.setBatchGroup(groups.get(i));
					// The server checks the route's changes part by part, from the revisions the page drafted them from
					final Route draftedRoute = ClientData.DATA_CACHE.routeIdMap.get(parseId(routeId));
					if (draftedRoute != null && !force && operation.has("parts") && operation.get("parts").isJsonObject()) {
						ClientEditing.setBatchBases(draftedRoute, getParts(operation));
					}
					String error;
					try {
						error = applyOperation(operation, i, created);
					} catch (Exception e) {
						e.printStackTrace();
						error = TranslatedMessage.get("gui.mtr.editor_bad_change");
					}
					if (error == null) {
						applied.add(i);
						if (!force && operation.has("base") && !operation.has("parts") && !"newRoute".equals(getString(operation, "type")) && !"duties".equals(getString(operation, "type")) && !"depot".equals(getString(operation, "type"))) {
							routeBases.putIfAbsent(parseId(routeId), getString(operation, "base"));
						}
					} else {
						addError(errors, i, routeId, error);
					}
				}
			} finally {
				future[0] = ClientEditing.sendBatch(routeBases);
			}
		}

		final JsonObject result = new JsonObject();
		result.addProperty("ok", errors.size() == 0);
		result.add("applied", applied);
		result.add("created", created);
		result.add("errors", errors);
		return new Pending(result, future[0], operations, groups);
	}

	/**
	 * What was sent, and the server's answer to wait for.
	 */
	public static final class Pending {

		private final JsonObject result;
		private final java.util.concurrent.CompletableFuture<ClientEditing.BatchResult> future;
		private final JsonArray operations;
		private final List<String> groups;

		private static final int ANSWER_SECONDS = 10;

		private Pending(JsonObject result, java.util.concurrent.CompletableFuture<ClientEditing.BatchResult> future, JsonArray operations, List<String> groups) {
			this.result = result;
			this.future = future;
			this.operations = operations;
			this.groups = groups;
		}

		/**
		 * @return the result for the page: a group the server didn't make, as another player changed that route
		 * meanwhile, doesn't count as applied, and the page keeps it in its draft; the other groups are applied. With no
		 * answer, nothing counts as applied.
		 */
		public JsonObject await() {
			if (future == null) {
				return result;
			}
			Map<String, String[]> rejected;
			String general = null;
			try {
				rejected = future.get(ANSWER_SECONDS, java.util.concurrent.TimeUnit.SECONDS).rejected;
				if (rejected.isEmpty()) {
					return result;
				}
				final String[] first = rejected.values().iterator().next();
				// Changed in the game by the player themself, as on the dashboard after the draft was made: said as that (2026-11-09)
				final net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
				final boolean self = player != null && player.getGameProfile().getName().equals(first[1]);
				general = Boolean.parseBoolean(first[0]) ? TranslatedMessage.get("gui.mtr.editor_server_deleted", first[1].isEmpty() ? "?" : first[1]) : self ? TranslatedMessage.get("gui.mtr.editor_server_rejected_self") : TranslatedMessage.get("gui.mtr.editor_server_rejected", first[1].isEmpty() ? "?" : first[1]);
			} catch (Exception e) {
				rejected = null;
				general = TranslatedMessage.get("gui.mtr.editor_no_answer");
			}
			final JsonArray errors = new JsonArray();
			addError(errors, -1, "", general);
			final JsonArray applied = new JsonArray();
			final JsonArray created = new JsonArray();
			for (final JsonElement indexElement : result.getAsJsonArray("applied")) {
				final int index = indexElement.getAsInt();
				final JsonObject operation = operations.get(index).getAsJsonObject();
				if (rejected == null) {
					addError(errors, index, getString(operation, "route"), general);
				} else if (rejected.containsKey(groups.get(index))) {
					// With who changed it in the game, for the page to say whose edits it conflicts with (2026-11-14)
					final String by = rejected.get(groups.get(index))[1];
					addError(errors, index, getString(operation, "route"), by == null || by.isEmpty() ? "CONFLICT" : "CONFLICT:" + by);
				} else {
					applied.add(index);
				}
			}
			for (final JsonElement made : result.getAsJsonArray("created")) {
				final int index = made.getAsJsonObject().get("index").getAsInt();
				if (rejected != null && !rejected.containsKey(groups.get(index))) {
					created.add(made);
				}
			}
			for (final JsonElement error : result.getAsJsonArray("errors")) {
				errors.add(error);
			}
			final JsonObject newResult = new JsonObject();
			newResult.addProperty("ok", false);
			newResult.add("applied", applied);
			newResult.add("created", created);
			newResult.add("errors", errors);
			return newResult;
		}
	}

	/**
	 * @return why the change couldn't be made, or null if it was sent
	 */
	private static String applyOperation(JsonObject operation, int index, JsonArray created) {
		// A depot's duties belong to the depot, not to a route
		if ("duties".equals(getString(operation, "type"))) {
			return EditorDuties.apply(operation);
		}
		// A depot's contingency plans, drafted, and a dispatcher's commands about them, sent at once (D2)
		if ("contingencies".equals(getString(operation, "type"))) {
			return EditorContingencies.applyPlans(operation);
		}
		if ("contingency".equals(getString(operation, "type"))) {
			return EditorContingencies.applyCommand(operation);
		}
		// A dispatcher's command for one train, from the console, sent at once (D6)
		if ("dispatch".equals(getString(operation, "type"))) {
			return EditorConsole.applyCommand(operation);
		}
		// A train waiting on the line with no duty, taken straight back to its siding by the player's command (P1-C)
		if ("recall".equals(getString(operation, "type"))) {
			final long depotId = parseId(getString(operation, "depot"));
			final long sidingId = parseId(getString(operation, "siding"));
			if (depotId == 0 || sidingId == 0 || !operation.has("slot")) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			mtr.packet.HeldTrains.sendRecallC2S(depotId, sidingId, operation.get("slot").getAsInt());
			return null;
		}
		// How a depot's trains go out and back, and its named deadhead paths (运用补完 step 2), on their own
		if ("depotRules".equals(getString(operation, "type"))) {
			final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot")));
			final transitsim.plan.DepotRules rules = EditorRules.fromJson(operation.get("rules"));
			if (depot == null) {
				return TranslatedMessage.get("gui.mtr.editor_depot_missing");
			}
			if (rules == null) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			depot.setRules(rules, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
			return null;
		}
		// What a depot runs: its routes, whether its trains stay on the line, its sidings' routes and fleets
		if ("depot".equals(getString(operation, "type"))) {
			return applyDepot(operation);
		}
		// How fast trains may pass over a platform (round 3b)
		if ("platformSpeed".equals(getString(operation, "type"))) {
			final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(parseId(getString(operation, "platform")));
			if (platform == null || !operation.has("speed") || operation.get("speed").getAsInt() <= 0 || operation.get("speed").getAsInt() > 1000) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			final int speed = operation.get("speed").getAsInt();
			platform.setPassSpeed(speed == mtr.data.PlatformSpeeds.DEFAULT_KMH ? 0 : speed, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
			ClientData.DATA_CACHE.sync();
			return null;
		}
		// The network's settings (round 4): the least time to change ends and the early arrival threshold, in milliseconds
		if ("networkSettings".equals(getString(operation, "type"))) {
			final mtr.data.NetworkSettings settings = new mtr.data.NetworkSettings();
			settings.copyFrom(ClientData.DATA_CACHE.networkSettings);
			if (operation.has("minReverseMillis")) {
				settings.minReverseMillis = mtr.data.NetworkSettings.clamp(operation.get("minReverseMillis").getAsLong());
			}
			if (operation.has("waitThresholdMillis")) {
				settings.waitThresholdMillis = mtr.data.NetworkSettings.clampWait(operation.get("waitThresholdMillis").getAsLong());
			}
			// Each station's own wait threshold; null takes it back to the network one (运用补完 step 2)
			if (operation.has("stationWaitThresholds") && operation.get("stationWaitThresholds").isJsonObject()) {
				for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("stationWaitThresholds").entrySet()) {
					final long stationId = Long.parseLong(entry.getKey());
					if (entry.getValue().isJsonNull()) {
						settings.stationWaitThresholds.remove(stationId);
					} else {
						settings.stationWaitThresholds.put(stationId, mtr.data.NetworkSettings.clampWait(entry.getValue().getAsLong()));
					}
				}
			}
			if (operation.has("earlyThresholdMillis")) {
				settings.earlyThresholdMillis = mtr.data.NetworkSettings.clamp(operation.get("earlyThresholdMillis").getAsLong());
			}
			// The owners of track sections set by hand, by a rail's two nodes: the lines, none for open to every line, or null
			// to work them out again (web layers step 2)
			if (operation.has("ownerOverrides") && operation.get("ownerOverrides").isJsonObject()) {
				for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("ownerOverrides").entrySet()) {
					final java.util.UUID railKey = mtr.data.NetworkSettings.parseRailKey(entry.getKey());
					if (railKey == null) {
						return TranslatedMessage.get("gui.mtr.editor_bad_change");
					}
					if (entry.getValue().isJsonNull()) {
						settings.ownerOverrides.remove(railKey);
					} else if (entry.getValue().isJsonArray() && entry.getValue().getAsJsonArray().size() <= mtr.data.NetworkSettings.MAX_OVERRIDE_LINES) {
						final List<String> lines = new ArrayList<>();
						entry.getValue().getAsJsonArray().forEach(line -> lines.add(line.getAsString()));
						settings.ownerOverrides.put(railKey, lines);
					} else {
						return TranslatedMessage.get("gui.mtr.editor_bad_change");
					}
				}
				if (settings.ownerOverrides.size() > mtr.data.NetworkSettings.MAX_OWNER_OVERRIDES) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
			}
			// Logical lines (2026-11-09): by ID, {name, color, connecting}, or null to take one away with its sections and choices
			try {
				if (operation.has("logicalLines") && operation.get("logicalLines").isJsonObject()) {
					for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("logicalLines").entrySet()) {
						final long id = Long.parseLong(entry.getKey());
						if (entry.getValue().isJsonNull()) {
							settings.logicalLines.remove(id);
							settings.railLines.values().removeIf(lineId -> lineId == id);
							settings.lineTracks.values().forEach(ids -> ids.remove(id));
						} else {
							final JsonObject lineObject = entry.getValue().getAsJsonObject();
							settings.logicalLines.put(id, new mtr.data.NetworkSettings.LogicalLine(getString(lineObject, "name"), lineObject.has("color") ? lineObject.get("color").getAsInt() : 0, lineObject.has("connecting") && lineObject.get("connecting").getAsBoolean()));
						}
					}
				}
				// The sections put in them, by a rail's two nodes: the logical line, or null to take the section out
				if (operation.has("railLines") && operation.get("railLines").isJsonObject()) {
					for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("railLines").entrySet()) {
						final java.util.UUID railKey = mtr.data.NetworkSettings.parseRailKey(entry.getKey());
						if (railKey == null) {
							return TranslatedMessage.get("gui.mtr.editor_bad_change");
						}
						if (entry.getValue().isJsonNull()) {
							settings.railLines.remove(railKey);
						} else {
							final long id = Long.parseLong(entry.getValue().getAsString());
							if (!settings.logicalLines.containsKey(id)) {
								return TranslatedMessage.get("gui.mtr.editor_bad_change");
							}
							settings.railLines.put(railKey, id);
						}
					}
				}
				// The logical lines each operating line runs on, or null to go by its platforms
				if (operation.has("lineTracks") && operation.get("lineTracks").isJsonObject()) {
					for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("lineTracks").entrySet()) {
						if (entry.getValue().isJsonNull()) {
							settings.lineTracks.remove(entry.getKey());
						} else {
							final List<Long> ids = new ArrayList<>();
							for (final JsonElement element : entry.getValue().getAsJsonArray()) {
								final long id = Long.parseLong(element.getAsString());
								if (settings.logicalLines.containsKey(id) && !ids.contains(id)) {
									ids.add(id);
								}
							}
							settings.lineTracks.put(entry.getKey(), ids);
						}
					}
				}
				// Running sides (user, 2026-11-12): "" the network's, "L" + id a logical line's, "R" + key an operating line's;
				// 1 right hand, -1 left hand, null to take the next one up
				if (operation.has("runningSides") && operation.get("runningSides").isJsonObject()) {
					for (final Map.Entry<String, JsonElement> entry : operation.getAsJsonObject("runningSides").entrySet()) {
						if (entry.getValue().isJsonNull()) {
							settings.runningSides.remove(entry.getKey());
						} else {
							final int side = entry.getValue().getAsInt();
							if (side != 1 && side != -1) {
								return TranslatedMessage.get("gui.mtr.editor_bad_change");
							}
							settings.runningSides.put(entry.getKey(), side);
						}
					}
				}
			} catch (RuntimeException e) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			if (settings.logicalLines.size() > mtr.data.NetworkSettings.MAX_LOGICAL_LINES || settings.railLines.size() > mtr.data.NetworkSettings.MAX_RAIL_LINES || settings.lineTracks.size() > mtr.data.NetworkSettings.MAX_LINE_TRACKS || settings.runningSides.size() > mtr.data.NetworkSettings.MAX_RUNNING_SIDES) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			ClientData.DATA_CACHE.networkSettings.copyFrom(settings);
			mtr.packet.NetworkSettingsPackets.sendC2S(settings);
			return null;
		}
		// Where trains stop on a platform (round 4): 0 the exit end, -1 centred, or blocks short of the exit end
		if ("platformStop".equals(getString(operation, "type"))) {
			final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(parseId(getString(operation, "platform")));
			if (platform == null || !operation.has("position") || operation.get("position").getAsInt() < -1 || operation.get("position").getAsInt() > 1000) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			platform.setStopPosition(operation.get("position").getAsInt(), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
			ClientData.DATA_CACHE.sync();
			return null;
		}
		// The station a platform is put in, wherever it is, or "0" for by its area (R6)
		if ("platformStation".equals(getString(operation, "type"))) {
			final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(parseId(getString(operation, "platform")));
			final long stationId = parseId(getString(operation, "station"));
			if (platform == null || stationId != 0 && !ClientData.DATA_CACHE.stationIdMap.containsKey(stationId)) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			platform.setStationId(stationId, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
			ClientData.DATA_CACHE.sync();
			return null;
		}
		// A pathfinding boundary on a rail belongs to the network, set the same way as in the game's node screen
		if ("pathBoundary".equals(getString(operation, "type"))) {
			final long node1 = parseId(getString(operation, "a"));
			final long node2 = parseId(getString(operation, "b"));
			if (node1 == 0 || node2 == 0) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			PacketTrainDataGuiClient.sendPathBoundaryC2S(net.minecraft.core.BlockPos.of(node1), net.minecraft.core.BlockPos.of(node2), operation.get("boundary").getAsBoolean());
			return null;
		}
		// A new line has no route to copy (L4): only its stations, no services and no depot
		if ("newRoute".equals(getString(operation, "type")) && getString(operation, "route").isEmpty()) {
			return createRoute(null, operation, index, created);
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
			case "stops":
				// The stops in a new order, some added or taken away (web layers, step 1, 2026-11-09); the timetables and duties
				// that count stops come in the same batch, renumbered by the page
				final String stopsError = changeStops(route, operation.getAsJsonArray("stops"));
				if (stopsError != null) {
					return stopsError;
				}
				route.editorManaged = true;
				route.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				route.setCustomPath(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				ClientData.DATA_CACHE.sync();
				return null;
			case "managed":
				route.editorManaged = operation.get("managed").getAsBoolean();
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "connectingLine":
				route.connectingLine = operation.get("connectingLine").getAsBoolean();
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "speedLimit":
				// The route's speed limit in km/h, 0 for none, no higher than the fastest rail along it (line speed limits)
				final int speedLimit = operation.has("speedLimit") && !operation.get("speedLimit").isJsonNull() ? operation.get("speedLimit").getAsInt() : 0;
				if (speedLimit < 0 || speedLimit > mtr.data.VehicleSettings.MAX_SPEED) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				route.speedLimit = speedLimit;
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "connectingRelease":
				// The whole list of the connecting line's released rails, each as its two nodes
				final JsonArray releasedArray = operation.getAsJsonArray("rails");
				if (releasedArray == null || releasedArray.size() > Route.MAX_CONNECTING_RELEASED) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				final List<long[]> released = new ArrayList<>();
				for (final JsonElement element : releasedArray) {
					final JsonArray railArray = element.getAsJsonArray();
					released.add(new long[]{parseId(railArray.get(0).getAsString()), parseId(railArray.get(1).getAsString())});
				}
				route.connectingReleased.clear();
				route.connectingReleased.addAll(released);
				route.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
				return null;
			case "turnPlatforms":
				// The platforms of the last station it turns back at (站前折返) and of the first it would rather start from
				final JsonArray terminalArray = operation.getAsJsonArray("terminal");
				final JsonArray startArray = operation.getAsJsonArray("start");
				if (terminalArray == null || startArray == null || terminalArray.size() > Route.MAX_TURN_PLATFORMS || startArray.size() > Route.MAX_TURN_PLATFORMS) {
					return TranslatedMessage.get("gui.mtr.editor_bad_change");
				}
				route.terminalPlatforms.clear();
				terminalArray.forEach(element -> route.terminalPlatforms.add(parseId(element.getAsString())));
				route.startPlatforms.clear();
				startArray.forEach(element -> route.startPlatforms.add(parseId(element.getAsString())));
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
	/**
	 * @param source the route a new type copies, or null for a new line, which gets only its stations
	 */
	private static String createRoute(Route source, JsonObject operation, int index, JsonArray created) {
		final TransportMode transportMode;
		final String lineName;
		if (source == null) {
			try {
				transportMode = TransportMode.valueOf(getString(operation, "mode"));
			} catch (IllegalArgumentException e) {
				return TranslatedMessage.get("gui.mtr.editor_bad_change");
			}
			lineName = getString(operation, "line").trim();
			if (lineName.isEmpty() || lineName.length() > MAX_NAME_LENGTH) {
				return TranslatedMessage.get("gui.mtr.editor_bad_line_name");
			}
		} else {
			transportMode = source.transportMode;
			lineName = source.getLineName();
		}
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
		// The nodes the way from each stop to the next goes through, chosen on the whole network map
		final List<List<net.minecraft.core.BlockPos>> waypoints = new ArrayList<>();
		for (int i = 0; i < stopCount; i++) {
			final JsonObject stop = stops.get(i).getAsJsonObject();
			final List<net.minecraft.core.BlockPos> stopWaypoints = new ArrayList<>();
			if (stop.has("waypoints") && stop.get("waypoints").isJsonArray()) {
				for (final JsonElement node : stop.getAsJsonArray("waypoints")) {
					if (stopWaypoints.size() < MAX_WAYPOINTS) {
						stopWaypoints.add(net.minecraft.core.BlockPos.of(parseId(node.getAsString())));
					}
				}
			}
			waypoints.add(stopWaypoints);
			platformIds[i] = parseId(getString(stop, "platformId"));
			pass[i] = stop.get("pass").getAsBoolean() && i > 0 && i < stopCount - 1;
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformIds[i]);
			if (platform == null) {
				return TranslatedMessage.get("gui.mtr.editor_platform_missing", i + 1);
			}
			if (platform.transportMode != transportMode) {
				return TranslatedMessage.get("gui.mtr.editor_platform_mode", i + 1);
			}
		}
		final String connectionError = checkConnected(platformIds);
		if (connectionError != null) {
			return connectionError;
		}
		final Depot depot = source != null && operation.has("depot") ? ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot"))) : null;
		if (depot != null && depot.transportMode != transportMode) {
			return TranslatedMessage.get("gui.mtr.editor_depot_mode");
		}

		final Route newRoute = source == null ? new Route(transportMode) : source.duplicate();
		newRoute.name = name;
		newRoute.serviceType = serviceType;
		newRoute.lineName = lineName;
		if (operation.has("color")) {
			// The line keeps the colour of its first route, which applyLineColors gives back to this one
			newRoute.setOwnColor(operation.get("color").getAsInt() & 0xFFFFFF);
			if (source == null) {
				newRoute.color = operation.get("color").getAsInt() & 0xFFFFFF;
			}
		}
		newRoute.editorManaged = true;

		// Its own stations, so nothing of the stops of the route it is based on is kept, nor a path drawn for them
		newRoute.platformIds.clear();
		for (int i = 0; i < stopCount; i++) {
			final Route.RoutePlatform newStop = new Route.RoutePlatform(platformIds[i]);
			newStop.pass = pass[i];
			newStop.waypoints.addAll(waypoints.get(i));
			newRoute.platformIds.add(newStop);
		}
		newRoute.getPathData().clear();
		if (newRoute.platformIds.stream().noneMatch(routePlatform -> routePlatform.platformId == newRoute.timetable.entryPlatformId)) {
			newRoute.timetable.entryPlatformId = 0;
		}

		// A new line starts with its stations only; services and depots are added afterwards
		final JsonArray services = source != null && operation.has("services") ? operation.getAsJsonArray("services") : new JsonArray();
		final int servicesTable = operation.has("table") ? operation.get("table").getAsInt() : 0;
		if (services.size() > RouteTimetable.MAX_SERVICES) {
			return TranslatedMessage.get("gui.mtr.editor_too_many_services", RouteTimetable.MAX_SERVICES);
		}
		for (int i = 0; i < newRoute.timetable.getTableCount(); i++) {
			final RouteTimetable table = newRoute.timetable.getTable(i);
			table.services.clear();
			table.links.clear();
			table.returnServices.clear();
			table.suspended.clear();
			table.returnOrders.clear();
			table.deployOrders.clear();
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
	/**
	 * Gives a route a new list of stops: each as a platform, whether it is passed, and the stop it was before (-1 for one
	 * added), which keeps what was set for it while its platform is the same. The way from each stop to the next must be
	 * there, and a path drawn for the old stops is dropped.
	 *
	 * @return why it can't, or null once the route has been changed
	 */
	private static String changeStops(Route route, JsonArray stops) {
		if (stops == null || stops.size() < 2 || stops.size() > MAX_STOPS) {
			return TranslatedMessage.get("gui.mtr.editor_stop_count", MAX_STOPS);
		}
		final int stopCount = stops.size();
		final long[] platformIds = new long[stopCount];
		final List<Route.RoutePlatform> newStops = new ArrayList<>();
		final Set<Integer> taken = new HashSet<>();
		for (int i = 0; i < stopCount; i++) {
			final JsonObject stop = stops.get(i).getAsJsonObject();
			platformIds[i] = parseId(getString(stop, "platformId"));
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformIds[i]);
			if (platform == null) {
				return TranslatedMessage.get("gui.mtr.editor_platform_missing", i + 1);
			}
			if (platform.transportMode != route.transportMode) {
				return TranslatedMessage.get("gui.mtr.editor_platform_mode", i + 1);
			}
			final int from = stop.has("from") ? stop.get("from").getAsInt() : -1;
			final boolean kept = from >= 0 && from < route.platformIds.size() && taken.add(from) && route.platformIds.get(from).platformId == platformIds[i];
			final Route.RoutePlatform newStop = kept ? route.platformIds.get(from) : new Route.RoutePlatform(platformIds[i]);
			newStop.pass = stop.has("pass") && stop.get("pass").getAsBoolean() && i > 0 && i < stopCount - 1;
			newStops.add(newStop);
		}
		final String connectionError = checkConnected(platformIds);
		if (connectionError != null) {
			return connectionError;
		}
		route.platformIds.clear();
		route.platformIds.addAll(newStops);
		route.getPathData().clear();
		if (route.platformIds.stream().noneMatch(routePlatform -> routePlatform.platformId == route.timetable.entryPlatformId)) {
			route.timetable.entryPlatformId = 0;
		}
		return null;
	}

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
			newStop.allowLonger = oldStop.allowLonger;
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
	/**
	 * Puts the page's timetable into the route, checking it, without sending anything.
	 *
	 * @return why it can't be applied, or null
	 */
	static String readTimetable(Route route, JsonObject timetable) {
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
		// A timetable with services runs whatever this says (2026-11-09); saved as on, so older readers run it too
		route.timetable.enabled = true;
		route.timetable.entryPlatformId = parseId(getString(timetable, "entryPlatform"));

		for (int i = 0; i < tables.size(); i++) {
			final JsonObject tableObject = tables.get(i).getAsJsonObject();
			final RouteTimetable table = route.timetable.getTable(i);
			table.name = getString(tableObject, "name");
			table.weekdays = tableObject.get("weekdays").getAsInt();
			table.marginPercent = tableObject.get("marginPercent").getAsInt();
			if (tableObject.has("minAcceleration")) {
				table.minAcceleration = Math.max(0, tableObject.get("minAcceleration").getAsFloat());
				table.minDeceleration = Math.max(0, tableObject.get("minDeceleration").getAsFloat());
				table.minMaxSpeed = Math.max(0, tableObject.get("minMaxSpeed").getAsFloat());
			}
			table.services.clear();
			tableObject.getAsJsonArray("services").forEach(service -> table.services.add(service.getAsInt()));
			table.links.clear();
			tableObject.getAsJsonArray("links").forEach(linkElement -> {
				final JsonObject link = linkElement.getAsJsonObject();
				table.links.put(link.get("service").getAsInt(), new RouteTimetable.ServiceLink(parseId(getString(link, "route")), link.get("toService").getAsInt(), link.get("fromStop").getAsInt(), link.get("toStop").getAsInt()));
			});
			table.returnServices.clear();
			tableObject.getAsJsonArray("returns").forEach(service -> table.returnServices.add(service.getAsInt()));
			table.suspended.clear();
			if (tableObject.has("suspended")) {
				tableObject.getAsJsonArray("suspended").forEach(service -> table.suspended.add(service.getAsInt()));
			}
			table.returnOrders.clear();
			table.deployOrders.clear();
			if (tableObject.has("orders")) {
				tableObject.getAsJsonArray("orders").forEach(orderElement -> {
					final JsonObject order = orderElement.getAsJsonObject();
					final RouteTimetable.ServiceOrder serviceOrder = new RouteTimetable.ServiceOrder(order.get("stop").getAsInt(), parseId(getString(order, "depot")));
					("deploy".equals(getString(order, "kind")) ? table.deployOrders : table.returnOrders).put(order.get("service").getAsInt(), serviceOrder);
				});
			}
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
			// Trains longer than the platform allowed to stop, set from the page (round 3b)
			if (timetable.has("allowLonger") && timetable.getAsJsonArray("allowLonger").size() == pass.size()) {
				route.platformIds.get(j).allowLonger = timetable.getAsJsonArray("allowLonger").get(j).getAsBoolean();
			}
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
