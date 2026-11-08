package mtr.client.editor;

import mtr.client.ClientEditing;

import transitsim.plan.StopRules;
import mtr.advanced.PlanAdapters;
import transitsim.plan.RouteTimetable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.data.*;
import transitsim.plan.TimetableBoundaries;
import mtr.client.ClientData;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import transitsim.network.PlatformGraph;

import java.util.*;
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

	/**
	 * @return the name of a siding, or of a platform with its station, for the buffers shown (D1)
	 */
	private static String bufferPlaceName(boolean siding, long id) {
		if (siding) {
			final Siding found = ClientData.DATA_CACHE.sidingIdMap.get(id);
			return found == null ? String.valueOf(id) : found.name;
		}
		final mtr.data.Platform platform = ClientData.DATA_CACHE.platformIdMap.get(id);
		if (platform == null) {
			return String.valueOf(id);
		}
		final mtr.data.Station station = ClientData.DATA_CACHE.platformIdToStation.get(id);
		return (station == null ? "" : station.name + " ") + platform.name;
	}

	/**
	 * The game's language, such as en_us, for the pages to show the same one.
	 */
	public static String getLanguage() {
		return Minecraft.getInstance().options.languageCode;
	}

	public static JsonObject build() {
		if (Minecraft.getInstance().level == null) {
			throw new IllegalStateException("Not in a world");
		}
		final JsonObject data = new JsonObject();
		final int dayStartMillis = TimetableConfig.getDayStartMillis();
		// The turnouts of the throats each route passes (L2), for the stop editor
		throats = mtr.client.ClientThroats.compute();
		data.addProperty("dayStartMillis", dayStartMillis);
		// Local time of day is UTC time of day plus this
		data.addProperty("offsetMillis", Math.floorMod(TimetableConfig.getDayStartHour() * 3600000L - dayStartMillis, (long) Depot.MILLISECONDS_PER_DAY));
		data.addProperty("canEdit", ClientData.hasPermission());
		// The network's settings (round 4)
		final JsonObject networkSettingsObject = new JsonObject();
		networkSettingsObject.addProperty("minReverseMillis", ClientData.DATA_CACHE.networkSettings.minReverseMillis);
		networkSettingsObject.addProperty("earlyThresholdMillis", ClientData.DATA_CACHE.networkSettings.earlyThresholdMillis);
		data.add("networkSettings", networkSettingsObject);
		// The fast simulation of a day is a debugging tool the server turns on
		data.addProperty("simulation", ClientData.editorSimulation);
		data.addProperty("language", getLanguage());
		data.addProperty("regionCount", ClientData.PATH_REGIONS.regionCount);
		data.add("topology", buildTopology());

		final Map<Long, List<Platform>> stationPlatforms = new HashMap<>();
		ClientData.PLATFORMS.stream().sorted(Comparator.comparing((Platform platform) -> platform.name).thenComparingLong(platform -> platform.id)).forEach(platform -> {
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
			if (station != null) {
				stationPlatforms.computeIfAbsent(station.id, key -> new ArrayList<>()).add(platform);
			}
		});

		final List<Route> routes = ClientData.ROUTES.stream().filter(route -> !route.isHidden).sorted(Comparator.comparing((Route route) -> route.name).thenComparingLong(route -> route.id)).collect(Collectors.toList());
		final JsonArray routesArray = new JsonArray();
		// The speed limit of each rail, to tell each route the fastest rail along it (line speed limits)
		railSpeeds.clear();
		ClientData.MAP_RAILS.forEach(mapRail -> railSpeeds.put(transitsim.network.RailKeys.of(mapRail.node1, mapRail.node2), mapRail.speedLimit));
		routes.forEach(route -> routesArray.add(buildRoute(route, stationPlatforms)));
		data.add("routes", routesArray);

		// Which depots' trains don't lock station throats, as their track is shared with a depot not running by duties
		// (LockNetworks), worked out as the server does from the sidings' paths the client has
		final transitsim.network.LockNetworks lockNetworks = new transitsim.network.LockNetworks();
		ClientData.SIDINGS.forEach(siding -> {
			final Depot depot = ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id);
			final long[] nodes = ClientData.MAP_SIDINGS.get(siding.id);
			if (depot != null && !depot.transportMode.continuousMovement && nodes != null) {
				lockNetworks.addDepot(depot.id, depot.getRunDuties() != null || Depot.supportsTimetable(depot.transportMode));
				lockNetworks.addPath(depot.id, nodes);
			}
		});
		final Map<Long, java.util.Set<Long>> sharedWithOld = new HashMap<>();
		lockNetworks.result().mixed.forEach(network -> {
			network.newDepots.forEach(depotId -> sharedWithOld.put(depotId, network.oldDepots));
		});

		final JsonArray depotsArray = new JsonArray();
		ClientData.DEPOTS.stream().sorted(Comparator.comparing((Depot depot) -> depot.name).thenComparingLong(depot -> depot.id)).forEach(depot -> {
			final JsonObject depotObject = new JsonObject();
			depotObject.addProperty("id", String.valueOf(depot.id));
			depotObject.addProperty("name", depot.name);
			depotObject.addProperty("cycleRoutes", depot.cycleRoutes);
			depotObject.addProperty("releaseInterval", depot.releaseInterval);
			// Not running by duties: the old logic, whose trains don't lock station throats
			// Not configured: neither duties nor anything the old logic would send a train for (lines and departures or a
			// frequency), as a depot just made; shown as such, not as the old logic
			// A depot of trains sends them out by duties only (R2, R3): with none, it isn't set up yet; only aircraft run by the
			// old logic
			final boolean oldLogic = depot.getRunDuties() == null && !depot.transportMode.continuousMovement && !Depot.supportsTimetable(depot.transportMode);
			final boolean sendsNothing = depot.routeIds.isEmpty() || (depot.useRealTime ? depot.departures.isEmpty() : java.util.stream.IntStream.range(0, Depot.HOURS_IN_DAY).allMatch(hour -> depot.getFrequency(hour) == 0));
			depotObject.addProperty("oldLogic", oldLogic && !sendsNothing);
			depotObject.addProperty("notConfigured", depot.getRunDuties() == null && Depot.supportsTimetable(depot.transportMode) || oldLogic && sendsNothing);
			final java.util.Set<Long> oldDepots = sharedWithOld.get(depot.id);
			if (oldDepots != null) {
				final JsonArray oldArray = new JsonArray();
				oldDepots.forEach(oldId -> oldArray.add(String.valueOf(oldId)));
				depotObject.add("throatLocksOffBy", oldArray);
			}
			final JsonArray routeIds = new JsonArray();
			depot.routeIds.forEach(routeId -> routeIds.add(String.valueOf(routeId)));
			depotObject.add("routes", routeIds);
			// The trains it gives each pool of its sidings, the same vehicle (P2-C): as set, or null for its sidings' fleets
			// added up, which is the sum
			final Map<String, List<Siding>> poolSidings = new TreeMap<>();
			ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).forEach(siding -> poolSidings.computeIfAbsent(siding.getPoolKey(), key -> new ArrayList<>()).add(siding));
			final JsonArray poolsArray = new JsonArray();
			poolSidings.forEach((poolKey, eachSidings) -> {
				final JsonObject poolObject = new JsonObject();
				poolObject.addProperty("key", poolKey);
				poolObject.addProperty("vehicle", eachSidings.get(0).getTrainId());
				final Integer fleet = depot.poolFleets.get(poolKey);
				if (fleet == null) {
					poolObject.add("fleet", com.google.gson.JsonNull.INSTANCE);
				} else {
					poolObject.addProperty("fleet", fleet);
				}
				poolObject.addProperty("sum", eachSidings.stream().anyMatch(siding -> siding.getFleetSize() < 0) ? -1 : eachSidings.stream().mapToInt(Siding::getFleetSize).sum());
				final JsonArray ids = new JsonArray();
				eachSidings.forEach(siding -> ids.add(String.valueOf(siding.id)));
				poolObject.add("sidings", ids);
				// How its trains run (R1): as set for it in the depot, or as its first siding has it
				final mtr.data.VehicleSettings vehicle = depot.vehicleSettings.containsKey(poolKey) ? depot.vehicleSettings.get(poolKey) : eachSidings.stream().min(Comparator.comparingLong(siding -> siding.id)).get().getOwnVehicleSettings();
				final JsonObject vehicleObject = new JsonObject();
				vehicleObject.addProperty("set", depot.vehicleSettings.containsKey(poolKey));
				vehicleObject.addProperty("acceleration", vehicle.acceleration);
				vehicleObject.addProperty("deceleration", vehicle.deceleration);
				vehicleObject.addProperty("manual", vehicle.manual);
				vehicleObject.addProperty("maxManualSpeed", vehicle.maxManualSpeed);
				vehicleObject.addProperty("manualToAutomaticTime", vehicle.manualToAutomaticTime);
				vehicleObject.addProperty("maxSpeed", vehicle.maxSpeed);
				poolObject.add("vehicleSettings", vehicleObject);
				// What its vehicle runs, shared by the pools of the same vehicle (5c)
				poolObject.addProperty("serviceTypes", depot.vehicleServiceTypes.getOrDefault(eachSidings.get(0).getTrainId(), ""));
				poolsArray.add(poolObject);
			});
			depotObject.add("pools", poolsArray);
			// The highest speeds driving by hand can have, by number (5c): MTR's own rails, up to the diamond one
			final JsonArray manualSpeeds = new JsonArray();
			for (int i = 0; i <= mtr.data.RailType.DIAMOND.ordinal(); i++) {
				manualSpeeds.add(mtr.data.RailType.values()[i].speedLimit);
			}
			depotObject.add("manualSpeeds", manualSpeeds);
			depotObject.addProperty("serviceTypesUsed", Depot.supportsTimetable(depot.transportMode));
			// How much longer than planned its trains were measured to take out, round and back (D1), as suggestions, and the
			// time adopted, which its duties are planned with (round 4, step 6): every place either has, by key
			final JsonArray buffersArray = new JsonArray();
			final Set<String> bufferKeys = new TreeSet<>(depot.getBuffers().getMillis().keySet());
			bufferKeys.addAll(depot.getAddedBuffers().getMillis().keySet());
			bufferKeys.forEach(bufferKey -> {
				final long[] parts = transitsim.plan.Buffers.parse(bufferKey);
				final JsonObject bufferObject = new JsonObject();
				final int kind = (int) parts[0];
				bufferObject.addProperty("key", bufferKey);
				bufferObject.addProperty("kind", kind == transitsim.plan.Buffers.OUT ? "out" : kind == transitsim.plan.Buffers.TURN ? "turn" : "back");
				bufferObject.addProperty("from", bufferPlaceName(kind == transitsim.plan.Buffers.OUT, parts[1]));
				bufferObject.addProperty("to", bufferPlaceName(kind == transitsim.plan.Buffers.BACK, parts[2]));
				bufferObject.addProperty("seconds", depot.getBuffers().getMillis().getOrDefault(bufferKey, 0L) / 1000);
				final Long added = depot.getAddedBuffers().getMillis().get(bufferKey);
				if (added != null) {
					bufferObject.addProperty("added", added / 1000);
				}
				buffersArray.add(bufferObject);
			});
			depotObject.add("buffers", buffersArray);
			final JsonArray sidingsArray = new JsonArray();
			ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
				final JsonObject sidingObject = new JsonObject();
				sidingObject.addProperty("id", String.valueOf(siding.id));
				sidingObject.addProperty("name", siding.name);
				sidingObject.addProperty("maxTrains", siding.getTrainLimit());
				// Its fleet and the routes it's given (empty for all of the depot's)
				sidingObject.addProperty("fleet", siding.getFleetSize());
				final JsonArray sidingRoutes = new JsonArray();
				siding.getRouteIds().forEach(routeId -> sidingRoutes.add(String.valueOf(routeId)));
				sidingObject.add("routes", sidingRoutes);
				sidingObject.addProperty("vehicle", siding.getTrainId());
				// How its trains get out to their first stops and back (DepotLegs), from the last path generation
				final mtr.data.DepotLegs legs = siding.getDepotLegs();
				if (legs.entrancePlatform != 0 || !legs.outMillis.isEmpty()) {
					final JsonObject legsObject = new JsonObject();
					final Station entrance = ClientData.DATA_CACHE.platformIdToStation.get(legs.entrancePlatform);
					legsObject.addProperty("entrance", entrance == null ? "" : String.valueOf(entrance.id));
					final JsonObject out = new JsonObject();
					legs.outMillis.forEach((platformId, millis) -> out.addProperty(String.valueOf(platformId), millis));
					legsObject.add("out", out);
					final JsonObject back = new JsonObject();
					legs.backMillis.forEach((platformId, millis) -> back.addProperty(String.valueOf(platformId), millis));
					legsObject.add("back", back);
					sidingObject.add("legs", legsObject);
				}
				// Ways out and back the planning goes by: found missing, or not measured yet (estimated), P2-A
				final mtr.data.DepotLegs recorded = siding.getRecordedLegs();
				final JsonArray noWayOut = new JsonArray();
				recorded.unreachableOut.forEach(platformId -> noWayOut.add(String.valueOf(platformId)));
				sidingObject.add("noWayOut", noWayOut);
				final JsonArray noWayBack = new JsonArray();
				recorded.unreachableBack.forEach(platformId -> noWayBack.add(String.valueOf(platformId)));
				sidingObject.add("noWayBack", noWayBack);
				final JsonArray measuredOut = new JsonArray();
				final java.util.Set<Long> measuredOutIds = new java.util.TreeSet<>(recorded.outMillis.keySet());
				measuredOutIds.addAll(legs.outMillis.keySet());
				measuredOutIds.forEach(platformId -> measuredOut.add(String.valueOf(platformId)));
				sidingObject.add("measuredOut", measuredOut);
				sidingsArray.add(sidingObject);
			});
			depotObject.add("sidings", sidingsArray);
			depotObject.addProperty("revision", getRevision(depot));
			depotObject.addProperty("byTimetable", depot.runsByDutiesWith(depot.getRoutes(ClientData.DATA_CACHE)));
			// The last path generation as the server told it: generating, found, failed and why, and duties not found
			final JsonObject pathObject = new JsonObject();
			final int segments = depot.clientPathGenerationSuccessfulSegments;
			final String failureText = mtr.client.PathFailureText.get(depot.clientPathFailure);
			pathObject.addProperty("state", segments < 0 ? "generating" : !failureText.isEmpty() || segments == 0 ? "failed" : ClientData.STALE_DEPOTS.contains(depot.id) ? "stale" : "found");
			pathObject.addProperty("text", failureText);
			pathObject.addProperty("failedDuties", depot.clientFailedDutyCount);
			final JsonArray failedArray = new JsonArray();
			depot.clientFailedDuties.forEach(failedArray::add);
			pathObject.add("failedDutyKeys", failedArray);
			// Ways between services far longer than their platforms are apart: from, to, length and how far apart
			final JsonArray detoursArray = new JsonArray();
			depot.clientDetours.forEach(detour -> {
				final String[] parts = detour.split(" ");
				if (parts.length >= 4) {
					final JsonObject detourObject = new JsonObject();
					detourObject.addProperty("from", platformLabel(Long.parseLong(parts[0])));
					detourObject.addProperty("to", platformLabel(Long.parseLong(parts[1])));
					detourObject.addProperty("length", Long.parseLong(parts[2]));
					detourObject.addProperty("apart", Long.parseLong(parts[3]));
					detoursArray.add(detourObject);
				}
			});
			pathObject.add("detours", detoursArray);
			// How long each change of platform within a station takes on the rails: from platform → to platform → millis,
			// a pair left out having no way between once measured
			pathObject.addProperty("turnTimesMeasured", depot.turnTimesMeasured);
			final JsonObject turnTimesObject = new JsonObject();
			// How long the in leg of the way round a turn back rail takes, by platform (round 4); the rest of the round is
			// the out leg
			final JsonObject holdInsObject = new JsonObject();
			depot.clientTurnTimes.forEach((from, times) -> {
				final JsonObject toObject = new JsonObject();
				times.forEach((to, millis) -> {
					if (to == transitsim.planning.DutyGenerator.HOLD_IN_KEY) {
						holdInsObject.addProperty(String.valueOf(from), millis);
					} else {
						toObject.addProperty(String.valueOf(to), millis);
					}
				});
				turnTimesObject.add(String.valueOf(from), toObject);
			});
			pathObject.add("turnTimes", turnTimesObject);
			pathObject.add("holdIns", holdInsObject);
			// Trains waiting on the line with no duty: siding, slot, where and why (P1-C)
			final JsonArray heldArray = new JsonArray();
			depot.clientHeldTrains.forEach(entry -> {
				final String[] parts = entry.split(" ");
				if (parts.length >= 4) {
					final JsonObject heldObject = new JsonObject();
					heldObject.addProperty("siding", parts[0]);
					heldObject.addProperty("slot", Integer.parseInt(parts[1]));
					heldObject.addProperty("platform", platformLabel(Long.parseLong(parts[2])));
					heldObject.addProperty("reason", parts[3]);
					heldArray.add(heldObject);
				}
			});
			depotObject.add("held", heldArray);
			// Trains reported late or stuck and those a contingency plan is on for (D2)
			EditorContingencies.addToDepot(depotObject, depot);
			// Services set to go back to a depot whose trains couldn't: siding, depot, why (P1-B)
			final JsonArray missesArray = new JsonArray();
			synchronized (depot.returnOrderMisses) {
				depot.returnOrderMisses.forEach(entry -> {
					final String[] parts = entry.split(" ", 3);
					if (parts.length >= 2) {
						final JsonObject missObject = new JsonObject();
						missObject.addProperty("siding", parts[0]);
						missObject.addProperty("depot", parts[1]);
						missObject.addProperty("reason", parts.length > 2 ? parts[2] : "");
						missesArray.add(missObject);
					}
				});
			}
			depotObject.add("returnMisses", missesArray);
			depotObject.add("path", pathObject);
			// Which services each train of the depot runs, see DepotDuties
			depotObject.add("duties", EditorDuties.toJson(depot.duties));
			// Worked out from the timetables when the depot runs by a timetable and has no duties set
			depotObject.add("autoDuties", EditorDuties.toJson(depot.autoDuties));
			// Whether the lines planned with it have trains enough (LineDuties)
			if (depot.lineShortfall != null) {
				final JsonObject shortfallObject = new JsonObject();
				shortfallObject.addProperty("lines", depot.lineShortfall.lineNames);
				shortfallObject.addProperty("needed", depot.lineShortfall.needed);
				shortfallObject.addProperty("available", depot.lineShortfall.available);
				shortfallObject.addProperty("uncovered", depot.lineShortfall.uncoveredPerDay);
				shortfallObject.addProperty("suggested", depot.lineShortfall.suggestedSiding);
				// Settings changed since the duties kept were planned, as name:then:now joined by ';' (round 4, step 4)
				shortfallObject.addProperty("replan", depot.lineShortfall.replanHint);
				// Each group of sidings planned apart (each pool of vehicles), as trains aren't lent between them
				final JsonArray shortfallPools = new JsonArray();
				depot.lineShortfall.pools.forEach(pool -> {
					final JsonObject poolObject = new JsonObject();
					poolObject.addProperty("label", pool.label);
					poolObject.addProperty("needed", pool.needed);
					poolObject.addProperty("available", pool.available);
					poolObject.addProperty("uncovered", pool.uncovered);
					shortfallPools.add(poolObject);
				});
				shortfallObject.add("pools", shortfallPools);
				shortfallObject.addProperty("missing", depot.lineShortfall.getMissingTrains());
				// The duty groups of the unit: each group of sidings on one day type (duty groups, step 4)
				final JsonArray dutyGroups = new JsonArray();
				depot.lineShortfall.groups.forEach(group -> {
					final JsonObject groupObject = new JsonObject();
					groupObject.addProperty("key", group.key);
					groupObject.addProperty("label", group.getLabel());
					groupObject.addProperty("days", group.getWeekdays());
					groupObject.addProperty("trains", group.trains);
					groupObject.addProperty("needed", group.needed);
					groupObject.addProperty("uncovered", group.uncoveredPerDay);
					groupObject.addProperty("kept", group.kept);
					dutyGroups.add(groupObject);
				});
				shortfallObject.add("groups", dutyGroups);
				depotObject.add("shortfall", shortfallObject);
			}
			depotsArray.add(depotObject);
		});
		data.add("depots", depotsArray);
		// The contingency plans of each rail system (D2)
		data.add("contingencySystems", EditorContingencies.systemsToJson());
		return data;
	}

	private static Map<Long, List<transitsim.network.ThroatRoutes.StopThroat>> throats = new HashMap<>();

	private static final Map<java.util.UUID, Integer> railSpeeds = new HashMap<>();

	/**
	 * @return the highest speed limit of the rails along a route's path on the map, in km/h, or 0 if not known
	 */
	private static int getFastestRail(Route route) {
		final long[] nodes = ClientData.MAP_ROUTES.get(route.id);
		int fastest = 0;
		for (int i = 0; nodes != null && i + 1 < nodes.length; i++) {
			fastest = Math.max(fastest, railSpeeds.getOrDefault(transitsim.network.RailKeys.of(nodes[i], nodes[i + 1]), 0));
		}
		return fastest;
	}

	private static JsonObject buildRoute(Route route, Map<Long, List<Platform>> stationPlatforms) {
		final JsonObject routeObject = new JsonObject();
		// Its speed limit, and the fastest rail along it, above which it can't be set (line speed limits)
		routeObject.addProperty("speedLimit", route.speedLimit);
		routeObject.addProperty("fastestRail", getFastestRail(route));
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
		routeObject.addProperty("connectingLine", route.connectingLine);
		final JsonArray releasedArray = new JsonArray();
		route.connectingReleased.forEach(rail -> {
			final JsonArray railArray = new JsonArray();
			railArray.add(String.valueOf(rail[0]));
			railArray.add(String.valueOf(rail[1]));
			releasedArray.add(railArray);
		});
		routeObject.add("connectingReleased", releasedArray);
		// The platforms it turns back at (站前折返) and would rather start from (种别优先)
		final JsonArray terminalArray = new JsonArray();
		route.terminalPlatforms.forEach(platformId -> terminalArray.add(String.valueOf(platformId)));
		routeObject.add("terminalPlatforms", terminalArray);
		final JsonArray startArray = new JsonArray();
		route.startPlatforms.forEach(platformId -> startArray.add(String.valueOf(platformId)));
		routeObject.add("startPlatforms", startArray);
		routeObject.addProperty("revision", getRevision(route));
		// Others editing it in the game, which the page shows before its draft is applied (multiplayer edit conflicts)
		routeObject.addProperty("editors", String.join(", ", ClientEditing.getEditors(route)));
		// The revision of each part, which a draft keeps so the game checks only the parts it changes
		final JsonObject parts = new JsonObject();
		ClientEditing.getRevisions(route).forEach(parts::addProperty);
		routeObject.add("parts", parts);

		final JsonArray stopsArray = new JsonArray();
		// Stops where the longest train running the route is longer than the platform (round 3b)
		final java.util.List<Integer> tooShort = mtr.data.StopLengths.getTooShort(route, ClientData.DATA_CACHE, mtr.data.StopLengths.getLongestTrain(route, ClientData.DATA_CACHE, ClientData.SIDINGS));
		for (int i = 0; i < route.platformIds.size(); i++) {
			final Route.RoutePlatform routePlatform = route.platformIds.get(i);
			final JsonObject stopObject = new JsonObject();
			// Where a service can leave or join this route part of the way along
			stopObject.addProperty("boundary", TimetableBoundaries.isBoundaryStop(route, i, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap)));
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
			stopObject.addProperty("platformId", String.valueOf(routePlatform.platformId));
			stopObject.addProperty("dwellOverride", routePlatform.dwellOverride);
			stopObject.addProperty("station", station == null ? "" : String.valueOf(station.id));
			stopObject.addProperty("name", station == null ? "" : station.name);
			stopObject.addProperty("platform", platform == null ? "" : platform.name);
			stopObject.addProperty("pass", routePlatform.pass);
			stopObject.addProperty("allowLonger", routePlatform.allowLonger);
			stopObject.addProperty("tooShort", tooShort.contains(i));
			stopObject.addProperty("platformLength", platform == null ? 0 : (int) Math.round(mtr.data.StopLengths.getLength(platform)));
			stopObject.addProperty("passingTrack", platform != null && platform.passingTrack);
			// After the last stop these are the nodes of the turn back rails (站后折返)
			stopObject.addProperty("waypoints", routePlatform.waypoints.size());
			// The tracks of the same station joined by rails to this one; the page works out which of them a train can use
			final PlatformGraph platformGraph = ClientData.PATH_REGIONS.platformGraph;
			final long network = platformGraph.getNetworkOf(routePlatform.platformId);
			stopObject.addProperty("network", String.valueOf(network));
			final JsonArray candidatesArray = new JsonArray();
			final List<Platform> candidates = station == null ? Collections.emptyList() : stationPlatforms.getOrDefault(station.id, Collections.emptyList());
			candidates.stream().filter(candidate -> candidate.id == routePlatform.platformId || network != 0 && platformGraph.getNetworkOf(candidate.id) == network && candidate.transportMode == route.transportMode).forEach(candidate -> {
				final JsonObject candidateObject = new JsonObject();
				candidateObject.addProperty("id", String.valueOf(candidate.id));
				candidateObject.addProperty("name", candidate.name);
				candidateObject.addProperty("passingTrack", candidate.passingTrack);
				candidatesArray.add(candidateObject);
			});
			stopObject.add("candidates", candidatesArray);
			// The turnouts it passes coming in and going out, and the routes crossing them at this station
			final List<transitsim.network.ThroatRoutes.StopThroat> routeThroats = throats.get(route.id);
			if (routeThroats != null && i < routeThroats.size()) {
				final JsonObject throatObject = new JsonObject();
				// Turnouts, and interlocking zones apart from them
				throatObject.addProperty("entry", routeThroats.get(i).entry.stream().filter(item -> !item.isZone()).count());
				throatObject.addProperty("exit", routeThroats.get(i).exit.stream().filter(item -> !item.isZone()).count());
				throatObject.addProperty("zones", java.util.stream.Stream.concat(routeThroats.get(i).entry.stream(), routeThroats.get(i).exit.stream()).filter(transitsim.network.ThroatRoutes.Item::isZone).map(item -> item.node).distinct().count());
				final JsonArray crossingsArray = new JsonArray();
				mtr.client.ClientThroats.crossings(throats, route, i, 20).forEach(crossing -> {
					final JsonObject crossingObject = new JsonObject();
					crossingObject.addProperty("route", String.valueOf(crossing.otherRouteId));
					crossingObject.addProperty("stop", crossing.otherStop);
					crossingObject.addProperty("entry", crossing.entry);
					crossingObject.addProperty("otherEntry", crossing.otherEntry);
					crossingObject.addProperty("zone", crossing.zone);
					final BlockPos node = BlockPos.of(crossing.node);
					crossingObject.addProperty("x", node.getX());
					crossingObject.addProperty("z", node.getZ());
					crossingsArray.add(crossingObject);
				});
				throatObject.add("crossings", crossingsArray);
				stopObject.add("throat", throatObject);
			}
			stopsArray.add(stopObject);
		}
		routeObject.add("stops", stopsArray);

		final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
		final boolean hasTimes = times != null && times.length == route.platformIds.size();
		routeObject.addProperty("hasTimes", hasTimes);
		final mtr.path.PathFailure pathFailure = ClientData.ROUTE_PATH_FAILURES.get(route.id);
		if (pathFailure != null) {
			routeObject.addProperty("pathFailure", mtr.client.PathFailureText.get(pathFailure));
		}
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
			tableObject.addProperty("minAcceleration", table.minAcceleration);
			tableObject.addProperty("minDeceleration", table.minDeceleration);
			tableObject.addProperty("minMaxSpeed", table.minMaxSpeed);
			final JsonArray servicesArray = new JsonArray();
			table.services.forEach(servicesArray::add);
			tableObject.add("services", servicesArray);

			// Arrival and departure at each stop in milliseconds from the service's time at the first stop
			final JsonArray plansArray = new JsonArray();
			StopRules.plan(route.platformIds, hasTimes ? times : null, table.marginPercent, i, null).forEach(plan -> {
				final JsonArray planArray = new JsonArray();
				planArray.add(plan.arrivalMillis);
				planArray.add(plan.departureMillis);
				plansArray.add(planArray);
			});
			tableObject.add("plans", plansArray);
			// The same times with whether each stop is passed, waited at or planned too fast (StopRules), for the page
			tableObject.add("times", EditorTimes.toJson(StopRules.times(route.platformIds, hasTimes ? times : null, table.marginPercent, i, null)));

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
			// Services that don't run, kept to run again
			final JsonArray suspendedArray = new JsonArray();
			new java.util.TreeSet<>(table.suspended).forEach(suspendedArray::add);
			tableObject.add("suspended", suspendedArray);
			// Services joined or left part of the way by a train from or to a depot
			final JsonArray ordersArray = new JsonArray();
			table.deployOrders.forEach((service, order) -> ordersArray.add(orderJson(service, "deploy", order)));
			table.returnOrders.forEach((service, order) -> ordersArray.add(orderJson(service, "return", order)));
			tableObject.add("orders", ordersArray);

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
				StopRules.plan(route.platformIds, hasTimes ? times : null, table.marginPercent, tableIndex, stopExceptions).forEach(plan -> {
					final JsonArray planArray = new JsonArray();
					planArray.add(plan.arrivalMillis);
					planArray.add(plan.departureMillis);
					servicePlansArray.add(planArray);
				});
				exceptionObject.add("plans", servicePlansArray);
				exceptionObject.add("times", EditorTimes.toJson(StopRules.times(route.platformIds, hasTimes ? times : null, table.marginPercent, tableIndex, stopExceptions)));
				exceptionsArray.add(exceptionObject);
			});
			tableObject.add("exceptions", exceptionsArray);
			tablesArray.add(tableObject);
		}
		routeObject.add("tables", tablesArray);
		return routeObject;
	}

	/**
	 * How trains can run between platforms without turning back ({@link PlatformGraph}), for picking tracks and making new
	 * types in the page: each platform in the graph's order, with its station and network, and for each way along it whether
	 * it can be used and the edges from it as [state, cost, state, cost, ...], a state being a platform's index times two
	 * plus its way.
	 */
	private static JsonObject buildTopology() {
		final PlatformGraph platformGraph = ClientData.PATH_REGIONS.platformGraph;
		final JsonObject topology = new JsonObject();
		final JsonArray platformsArray = new JsonArray();
		// Up or down, main line or passing track, and the order across the station (L2)
		final Map<Long, transitsim.network.StationTracks.Track> tracks = mtr.client.ClientStationTracks.compute();
		for (int i = 0; i < platformGraph.size(); i++) {
			final long platformId = platformGraph.getPlatformId(i);
			final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platformId);
			final JsonObject platformObject = new JsonObject();
			platformObject.addProperty("id", String.valueOf(platformId));
			platformObject.addProperty("name", platform == null ? "" : platform.name);
			platformObject.addProperty("station", station == null ? "" : String.valueOf(station.id));
			platformObject.addProperty("stationName", station == null ? "" : station.name);
			platformObject.addProperty("mode", platform == null ? "" : platform.transportMode.toString());
			platformObject.addProperty("passingTrack", platform != null && platform.passingTrack);
			platformObject.addProperty("passSpeed", platform == null ? mtr.data.PlatformSpeeds.DEFAULT_KMH : platform.getPassSpeedKmh());
			platformObject.addProperty("stopPosition", platform == null ? 0 : platform.stopPosition);
			// The station set for it, "0" for none, and where it is, for choosing a station to put it in
			platformObject.addProperty("stationSet", platform == null ? "0" : String.valueOf(platform.stationId));
			if (platform != null) {
				final BlockPos mid = platform.getMidPos();
				platformObject.addProperty("x", mid.getX());
				platformObject.addProperty("y", mid.getY());
				platformObject.addProperty("z", mid.getZ());
			}
			platformObject.addProperty("network", String.valueOf(platformGraph.getNetwork(i)));
			// Where it lies, so the page can put a station's tracks in the order they lie side by side
			if (platform != null) {
				final List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
				final JsonArray line = new JsonArray();
				line.add(ends.get(0).getX() + 0.5);
				line.add(ends.get(0).getZ() + 0.5);
				line.add(ends.get(1).getX() + 0.5);
				line.add(ends.get(1).getZ() + 0.5);
				platformObject.add("line", line);
			}
			final JsonArray waysArray = new JsonArray();
			for (int direction = 0; direction < 2; direction++) {
				final int state = i * 2 + direction;
				final JsonArray edgesArray = new JsonArray();
				if (platformGraph.isUsable(state)) {
					final int[] targets = platformGraph.getTargets(state);
					final double[] costs = platformGraph.getCosts(state);
					for (int j = 0; j < targets.length; j++) {
						edgesArray.add(targets[j]);
						edgesArray.add(Math.round(costs[j] * 10) / 10.0);
					}
				}
				final JsonObject wayObject = new JsonObject();
				wayObject.addProperty("usable", platformGraph.isUsable(state));
				wayObject.add("edges", edgesArray);
				waysArray.add(wayObject);
			}
			platformObject.add("ways", waysArray);
			final transitsim.network.StationTracks.Track track = tracks.get(platformId);
			if (track != null) {
				final JsonObject trackObject = new JsonObject();
				trackObject.addProperty("direction", track.direction);
				trackObject.addProperty("kind", track.kind);
				trackObject.addProperty("order", track.order);
				platformObject.add("track", trackObject);
			}
			platformsArray.add(platformObject);
		}
		topology.add("platforms", platformsArray);
		// The stations a platform can be put in, by where their main areas are
		final JsonArray stationsArray = new JsonArray();
		ClientData.STATIONS.stream().filter(AreaBase::nonNullCorners).sorted(Comparator.comparingLong(station -> station.id)).forEach(station -> {
			final JsonObject stationObject = new JsonObject();
			stationObject.addProperty("id", String.valueOf(station.id));
			stationObject.addProperty("name", station.name);
			stationObject.addProperty("x", (station.corner1.getA() + station.corner2.getA()) / 2);
			stationObject.addProperty("z", (station.corner1.getB() + station.corner2.getB()) / 2);
			stationsArray.add(stationObject);
		});
		topology.add("stations", stationsArray);
		// Platforms in more than one station's area with none set, and the station each went to by the nearest platform
		final JsonArray overlappingArray = new JsonArray();
		ClientData.DATA_CACHE.overlappingPlatforms.forEach((platformId, station) -> {
			final JsonObject overlapObject = new JsonObject();
			overlapObject.addProperty("platform", String.valueOf(platformId));
			overlapObject.addProperty("station", String.valueOf(station.id));
			overlapObject.addProperty("byIdAlone", ClientData.DATA_CACHE.platformsByIdAlone.contains(platformId));
			overlappingArray.add(overlapObject);
		});
		topology.add("overlapping", overlappingArray);
		topology.addProperty("networkCount", platformGraph.getNetworkCount());
		return topology;
	}

	/**
	 * @return text that changes whenever anything the editor can change on the route does, made from the same packets that
	 * send those changes, so a draft made before another change in the game is noticed before it overwrites it
	 */
	/**
	 * @return a platform as its station's name and its own, or its ID if it's gone
	 */
	private static String platformLabel(long platformId) {
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
		if (platform == null) {
			return String.valueOf(platformId);
		}
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platformId);
		return (station == null ? "" : station.name + " ") + platform.name;
	}

	public static String getRevision(Route route) {
		return route.getEditorRevision();
	}

	/**
	 * @return what the advanced editor changes of a depot, as it is in the game: its routes, whether its trains stay on
	 * the line, its way of running and its sidings' routes and fleets; a draft based on another is out of date
	 */
	public static String getRevision(Depot depot) {
		final StringBuilder text = new StringBuilder();
		new java.util.TreeSet<>(depot.routeIds).forEach(routeId -> text.append(routeId).append(','));
		text.append('|').append(depot.cycleRoutes).append('|').append(depot.duties.diagramMode);
		ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).sorted(Comparator.comparingLong(siding -> siding.id)).forEach(siding -> text.append('|').append(siding.id).append(':').append(siding.getRouteIds()).append('/').append(siding.getFleetSize()));
		return Integer.toHexString(text.toString().hashCode());
	}

	private static void requestTimes(long routeId) {
		final long millis = System.currentTimeMillis();
		final Long lastRequested = TIMES_REQUESTED.get(routeId);
		if (lastRequested == null || millis - lastRequested >= TIMES_REQUEST_INTERVAL_MILLIS) {
			TIMES_REQUESTED.put(routeId, millis);
			PacketTrainDataGuiClient.requestTimetableTimesC2S(routeId);
		}
	}

	private static JsonObject orderJson(int service, String kind, transitsim.plan.RouteTimetable.ServiceOrder order) {
		final JsonObject orderObject = new JsonObject();
		orderObject.addProperty("service", service);
		orderObject.addProperty("kind", kind);
		orderObject.addProperty("stop", order.stop);
		orderObject.addProperty("depot", String.valueOf(order.depotId));
		return orderObject;
	}
}
