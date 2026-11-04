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
		data.addProperty("dayStartMillis", dayStartMillis);
		// Local time of day is UTC time of day plus this
		data.addProperty("offsetMillis", Math.floorMod(TimetableConfig.getDayStartHour() * 3600000L - dayStartMillis, (long) Depot.MILLISECONDS_PER_DAY));
		data.addProperty("canEdit", ClientData.hasPermission());
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
		routes.forEach(route -> routesArray.add(buildRoute(route, stationPlatforms)));
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
				sidingObject.addProperty("maxTrains", siding.getTrainLimit());
				sidingsArray.add(sidingObject);
			});
			depotObject.add("sidings", sidingsArray);
			// Which services each train of the depot runs, see DepotDuties
			depotObject.add("duties", EditorDuties.toJson(depot.duties));
			depotsArray.add(depotObject);
		});
		data.add("depots", depotsArray);
		return data;
	}

	private static JsonObject buildRoute(Route route, Map<Long, List<Platform>> stationPlatforms) {
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
		// Others editing it in the game, which the page shows before its draft is applied (multiplayer edit conflicts)
		routeObject.addProperty("editors", String.join(", ", ClientEditing.getEditors(route)));
		// The revision of each part, which a draft keeps so the game checks only the parts it changes
		final JsonObject parts = new JsonObject();
		ClientEditing.getRevisions(route).forEach(parts::addProperty);
		routeObject.add("parts", parts);

		final JsonArray stopsArray = new JsonArray();
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
			stopObject.addProperty("passingTrack", platform != null && platform.passingTrack);
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
			platformsArray.add(platformObject);
		}
		topology.add("platforms", platformsArray);
		topology.addProperty("networkCount", platformGraph.getNetworkCount());
		return topology;
	}

	/**
	 * @return text that changes whenever anything the editor can change on the route does, made from the same packets that
	 * send those changes, so a draft made before another change in the game is noticed before it overwrites it
	 */
	public static String getRevision(Route route) {
		return route.getEditorRevision();
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
