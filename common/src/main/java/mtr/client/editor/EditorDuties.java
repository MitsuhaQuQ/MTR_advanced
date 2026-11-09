package mtr.client.editor;

import transitsim.plan.StopRules;
import transitsim.plan.RouteTimetable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import transitsim.plan.DepotDuties;
import transitsim.planning.DutyGenerator;
import mtr.client.ClientData;
import mtr.data.*;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;

import java.util.*;
import java.util.function.Supplier;

/**
 * The advanced editor's side of depot duties (design document 6.12, block E1): the JSON the page reads and sends, the
 * checks before a depot's duties are sent to the server, and a first draft worked out by {@link DutyGenerator}.
 */
public final class EditorDuties implements IPacket {

	private EditorDuties() {
	}

	/**
	 * @return {@code {mode, sidings: {sidingId: [slot: [{days, stay, segments: [{route, service, from, to}]}]]}}}
	 */
	public static JsonObject toJson(DepotDuties duties) {
		final JsonObject object = new JsonObject();
		object.addProperty("mode", duties.diagramMode ? "diagram" : "online");
		final JsonObject sidingsObject = new JsonObject();
		duties.sidings.forEach((sidingId, slots) -> sidingsObject.add(String.valueOf(sidingId), slotsToJson(slots)));
		object.add("sidings", sidingsObject);
		return object;
	}

	private static JsonArray slotsToJson(List<DepotDuties.Slot> slots) {
		final JsonArray slotsArray = new JsonArray();
		slots.forEach(slot -> {
			final JsonArray daysArray = new JsonArray();
			slot.days.forEach(day -> {
				final JsonObject dayObject = new JsonObject();
				dayObject.addProperty("days", day.weekdays);
				dayObject.addProperty("stay", day.stayOnLine);
				dayObject.addProperty("next", day.nextSlot);
				// The duty's own number, on each of its days (P2-D), and where it goes on to and back to
				dayObject.addProperty("duty", String.valueOf(slot.id));
				// Where its train comes from, the same on each day of the slot (S8)
				dayObject.addProperty("start", slot.start == DepotDuties.Slot.START_ONLY ? "only" : slot.start == DepotDuties.Slot.START_ANY ? "any" : "prefer");
				dayObject.addProperty("nextDuty", String.valueOf(day.nextDutyId));
				dayObject.addProperty("returnSiding", String.valueOf(day.returnSidingId));
				dayObject.addProperty("fromOvernight", day.fromOvernight);
				final JsonArray segmentsArray = new JsonArray();
				day.segments.forEach(segment -> {
					final JsonObject segmentObject = new JsonObject();
					segmentObject.addProperty("route", String.valueOf(segment.routeId));
					segmentObject.addProperty("service", segment.service);
					segmentObject.addProperty("from", segment.fromStop);
					segmentObject.addProperty("to", segment.toStop);
					segmentsArray.add(segmentObject);
				});
				dayObject.add("segments", segmentsArray);
				// Where it waits, when it leaves and the stations it goes out and back by, as planned (运用补完 step 1): kept
				// through an edit in the page
				if (day.hasItems()) {
					final JsonArray waitsArray = new JsonArray();
					day.waits.forEach(waitsArray::add);
					dayObject.add("waits", waitsArray);
					dayObject.addProperty("outLead", day.outLeadMillis);
					dayObject.addProperty("outEntrance", String.valueOf(day.outEntrance));
					dayObject.addProperty("backEntrance", String.valueOf(day.backEntrance));
					dayObject.addProperty("outPath", String.valueOf(day.outPathId));
					dayObject.addProperty("backPath", String.valueOf(day.backPathId));
					dayObject.addProperty("exceptions", day.exceptions);
				}
				daysArray.add(dayObject);
			});
			slotsArray.add(daysArray);
		});
		return slotsArray;
	}

	/**
	 * Checks a depot's duties from the page, and sends them a slot at a time.
	 *
	 * @return why they can't be sent, or null once they are
	 */
	public static String apply(JsonObject operation) {
		final Depot depot = getDepot(operation);
		if (depot == null) {
			return TranslatedMessage.get("gui.mtr.editor_depot_missing");
		}
		final DepotDuties duties = new DepotDuties();
		final String error = read(depot, operation, duties);
		if (error != null) {
			return error;
		}

		final Set<Long> sidingIds = new TreeSet<>(depot.duties.sidings.keySet());
		sidingIds.addAll(duties.sidings.keySet());
		depot.duties.diagramMode = duties.diagramMode;
		depot.setDutyMode(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
		sidingIds.forEach(sidingId -> {
			final List<DepotDuties.Slot> slots = duties.getSlots(sidingId);
			depot.duties.setSlot(sidingId, slots.size(), -1, null);
			if (slots.isEmpty()) {
				depot.setDutySlot(sidingId, -1, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
			}
			for (int i = 0; i < slots.size(); i++) {
				depot.duties.setSlot(sidingId, slots.size(), i, slots.get(i));
				depot.setDutySlot(sidingId, i, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
			}
		});
		return null;
	}

	/**
	 * @return the depot a change of duties is for, or null
	 */
	public static Depot getDepot(JsonObject operation) {
		return ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot")));
	}

	/**
	 * Reads the page's duties of a depot into these duties, checking them as applying them does, without sending anything.
	 *
	 * @return why they can't be applied, or null
	 */
	public static String read(Depot depot, JsonObject operation, DepotDuties duties) {
		final JsonObject dutiesObject = operation.getAsJsonObject("duties");
		duties.diagramMode = "diagram".equals(getString(dutiesObject, "mode"));
		final Set<String> taken = new HashSet<>();

		for (final Map.Entry<String, JsonElement> sidingEntry : dutiesObject.getAsJsonObject("sidings").entrySet()) {
			final long sidingId = parseId(sidingEntry.getKey());
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
			if (siding == null || ClientData.DATA_CACHE.sidingIdToDepot.get(sidingId) != depot) {
				return TranslatedMessage.get("gui.mtr.editor_siding_missing");
			}
			final JsonArray slotsArray = sidingEntry.getValue().getAsJsonArray();
			final String sidingName = siding.name.isEmpty() ? TranslatedMessage.get("gui.mtr.editor_siding") : siding.name;
			// More duties than the siding holds trains is fine: a train back in the siding goes out again for a later one
			// (DepotDispatcher sends whichever train is ready), so only the number of duties a siding can keep is limited
			if (slotsArray.size() > DepotDuties.MAX_SLOTS) {
				return TranslatedMessage.get("gui.mtr.editor_too_many_slots", sidingName, DepotDuties.MAX_SLOTS);
			}
			final List<DepotDuties.Slot> slots = new ArrayList<>();
			for (int i = 0; i < slotsArray.size(); i++) {
				final DepotDuties.Slot slot = new DepotDuties.Slot();
				int usedDays = 0;
				for (final JsonElement dayElement : slotsArray.get(i).getAsJsonArray()) {
					final JsonObject dayObject = dayElement.getAsJsonObject();
					final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
					day.weekdays = dayObject.get("days").getAsInt() & DepotDuties.ALL_WEEKDAYS;
					if (day.weekdays == 0 || (day.weekdays & usedDays) != 0 || slot.days.size() >= DepotDuties.MAX_DAYS) {
						return TranslatedMessage.get("gui.mtr.editor_duty_days_overlap", sidingName, i + 1);
					}
					usedDays |= day.weekdays;
					day.stayOnLine = dayObject.get("stay").getAsBoolean();
					day.nextSlot = day.stayOnLine && dayObject.has("next") ? dayObject.get("next").getAsInt() : -1;
					if (dayObject.has("start")) {
						final String start = getString(dayObject, "start");
						slot.start = "only".equals(start) ? DepotDuties.Slot.START_ONLY : "any".equals(start) ? DepotDuties.Slot.START_ANY : DepotDuties.Slot.START_PREFER;
					}
					if (dayObject.has("duty") && slot.id == 0) {
						slot.id = parseId(getString(dayObject, "duty"));
					}
					day.nextDutyId = day.stayOnLine && dayObject.has("nextDuty") ? parseId(getString(dayObject, "nextDuty")) : 0;
					day.fromOvernight = dayObject.has("fromOvernight") && dayObject.get("fromOvernight").getAsBoolean();
					day.returnSidingId = dayObject.has("returnSiding") ? parseId(getString(dayObject, "returnSiding")) : 0;
					// Back to another siding of the same pool only, which the duty's path has a way back to (P2-D)
					if (day.returnSidingId != 0) {
						final Siding returnSiding = ClientData.DATA_CACHE.sidingIdMap.get(day.returnSidingId);
						if (returnSiding == null || !returnSiding.getPoolKey().equals(siding.getPoolKey())) {
							return TranslatedMessage.get("gui.mtr.editor_duty_return_siding", sidingName, i + 1);
						}
					}
					if (day.nextSlot >= slotsArray.size() || day.nextSlot < -1) {
						return TranslatedMessage.get("gui.mtr.editor_duty_next_missing", sidingName, i + 1);
					}
					final JsonArray segmentsArray = dayObject.getAsJsonArray("segments");
					if (segmentsArray.size() > DepotDuties.MAX_SEGMENTS) {
						return TranslatedMessage.get("gui.mtr.editor_duty_too_many", sidingName, i + 1, DepotDuties.MAX_SEGMENTS);
					}
					for (final JsonElement segmentElement : segmentsArray) {
						final JsonObject segmentObject = segmentElement.getAsJsonObject();
						final DepotDuties.Segment segment = new DepotDuties.Segment(parseId(getString(segmentObject, "route")), segmentObject.get("service").getAsInt(), segmentObject.get("from").getAsInt(), segmentObject.get("to").getAsInt());
						final String error = checkSegment(depot, segment, day.weekdays, taken);
						if (error != null) {
							return TranslatedMessage.get("gui.mtr.editor_duty_error", sidingName, i + 1, error);
						}
						day.segments.add(segment);
					}
					// Kept as planned while the duty's services are as many as they were; changed, the runtime decides again
					if (dayObject.has("waits")) {
						final JsonArray waitsArray = dayObject.getAsJsonArray("waits");
						if (waitsArray.size() == Math.max(0, day.segments.size() - 1)) {
							waitsArray.forEach(wait -> day.waits.add(Math.max(DepotDuties.DayDuty.WAIT_ANY, Math.min(DepotDuties.DayDuty.WAIT_CHANGE_PLATFORM, wait.getAsInt()))));
						}
						day.outLeadMillis = dayObject.has("outLead") ? Math.max(-1, dayObject.get("outLead").getAsLong()) : -1;
						day.outEntrance = dayObject.has("outEntrance") ? parseId(getString(dayObject, "outEntrance")) : 0;
						day.backEntrance = dayObject.has("backEntrance") ? parseId(getString(dayObject, "backEntrance")) : 0;
						day.outPathId = dayObject.has("outPath") ? parseId(getString(dayObject, "outPath")) : 0;
						day.backPathId = dayObject.has("backPath") ? parseId(getString(dayObject, "backPath")) : 0;
						day.exceptions = dayObject.has("exceptions") ? dayObject.get("exceptions").getAsInt() & DepotDuties.DayDuty.EXCEPTION_ALL : 0;
					}
					slot.days.add(day);
				}
				if (slot.id == 0) {
					slot.id = DepotDuties.defaultId(sidingId, i);
				}
				slots.add(slot);
			}
			// Two trains staying overnight can't both take over the same slot the next day
			for (int weekday = 0; weekday < 7; weekday++) {
				final Set<Integer> takenSlots = new HashSet<>();
				for (int i = 0; i < slots.size(); i++) {
					final DepotDuties.DayDuty day = slots.get(i).getDuty(weekday);
					if (day != null && day.stayOnLine && !day.segments.isEmpty() && !takenSlots.add(day.nextSlot < 0 ? i : day.nextSlot)) {
						return TranslatedMessage.get("gui.mtr.editor_duty_same_next", sidingName, (day.nextSlot < 0 ? i : day.nextSlot) + 1);
					}
				}
			}
			if (!slots.isEmpty()) {
				duties.sidings.put(sidingId, slots);
			}
		}
		return null;
	}

	/**
	 * A segment has to be a service of one of the depot's routes on every day of its duty, and no other train of the depot
	 * may run it on those days.
	 */
	private static String checkSegment(Depot depot, DepotDuties.Segment segment, int weekdays, Set<String> taken) {
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(segment.routeId);
		if (route == null || !depot.routeIds.contains(segment.routeId)) {
			return TranslatedMessage.get("gui.mtr.editor_segment_route_missing");
		}
		if (segment.fromStop >= route.platformIds.size() || segment.toStop >= route.platformIds.size()) {
			return TranslatedMessage.get("gui.mtr.editor_segment_stations_changed", route.name);
		}
		for (int day = 0; day < 7; day++) {
			if ((weekdays >> day & 1) == 0) {
				continue;
			}
			final RouteTimetable table = getTable(route, day);
			if (table == null || !table.services.contains(segment.service)) {
				return TranslatedMessage.get("gui.mtr.editor_segment_not_running", route.name, TranslatedMessage.get("gui.mtr.editor_weekday_" + day));
			}
			if (!taken.add(segment.routeId + "@" + segment.service + "/" + day)) {
				return TranslatedMessage.get("gui.mtr.editor_segment_twice", route.name, TranslatedMessage.get("gui.mtr.editor_weekday_" + day));
			}
		}
		return null;
	}


	/**
	 * @return the table a route runs on a weekday, the first that has the day
	 */
	private static RouteTimetable getTable(Route route, int weekday) {
		for (final RouteTimetable table : route.timetable.getTables()) {
			if ((table.weekdays >> weekday & 1) != 0) {
				return table;
			}
		}
		return null;
	}

	/**
	 * Copies what a depot's draft needs from the game's data. Must be called on the game thread.
	 *
	 * @return what works out the draft away from the game thread, or null if the depot isn't there
	 */
	public static Supplier<JsonObject> prepareGeneration(long depotId) {
		final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(depotId);
		if (depot == null) {
			return null;
		}
		final int dayStartMillis = TimetableConfig.getDayStartMillis();
		final List<String> missingTimes = new ArrayList<>();
		final List<Route> routes = new ArrayList<>();
		depot.routeIds.forEach(routeId -> {
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(routeId);
			if (route != null) {
				routes.add(route);
			}
		});
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(routes, route -> ClientData.TIMETABLE_TIMES.get(((Route) route).id), dayStartMillis, route -> missingTimes.add(((Route) route).name));

		// Planned with the depot's operating lines, as the game plans depots running by a timetable; the draft keeps the
		// depot's own part, and the other depots of the lines plan what is left once it is applied
		final LineDuties.Unit unit = LineDuties.of(depot, ClientData.DATA_CACHE, ClientData.SIDINGS, true);
		if (unit != null) {
			final Map<Long, long[][]> times = new HashMap<>(ClientData.TIMETABLE_TIMES);
			final List<String> unitMissing = new ArrayList<>();
			unit.getRoutes().forEach(route -> {
				if (!times.containsKey(route.id)) {
					unitMissing.add(route.name);
				}
			});
			final Set<Long> ownSidings = new HashSet<>();
			ClientData.SIDINGS.forEach(siding -> {
				if (ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot) {
					ownSidings.add(siding.id);
				}
			});
			final boolean diagramMode = depot.duties.diagramMode;
			final int unitDepots = unit.depotIds.size();
			// The changes of platform as the server measured them on the rails, so the draft is planned as the game plans
			final List<Depot> unitDepotList = new ArrayList<>();
			unit.depotIds.forEach(unitDepotId -> {
				final Depot unitDepot = ClientData.DATA_CACHE.depotIdMap.get(unitDepotId);
				if (unitDepot != null) {
					unitDepotList.add(unitDepot);
				}
			});
			final Map<Long, Map<Long, Long>> turnTimes = LineDuties.turnTimesOf(unitDepotList);
			return () -> {
				final LineDuties.Plan plan = unit.plan(route -> times.get(route.id), dayStartMillis, turnTimes);
				final DepotDuties duties = new DepotDuties();
				duties.diagramMode = diagramMode;
				plan.sidings.forEach((sidingId, slots) -> {
					if (ownSidings.contains(sidingId)) {
						duties.sidings.put(sidingId, slots);
					}
				});
				final JsonObject object = new JsonObject();
				object.add("duties", toJson(duties));
				object.addProperty("trains", plan.trains);
				object.addProperty("uncovered", plan.uncovered);
				object.addProperty("noSidings", ownSidings.isEmpty());
				object.addProperty("byLine", true);
				object.addProperty("lineNames", plan.lineNames);
				object.addProperty("lineDepots", unitDepots);
				object.addProperty("needed", plan.needed);
				object.addProperty("available", plan.available);
				object.addProperty("suggestedSiding", plan.suggestedSiding);
				final JsonArray missingArray = new JsonArray();
				unitMissing.forEach(missingArray::add);
				object.add("missingTimes", missingArray);
				final JsonArray issuesArray = new JsonArray();
				plan.issues.forEach(issuesArray::add);
				object.add("issues", issuesArray);
				object.add("returns", returnsJson(plan.returnSidings, ownSidings));
				return object;
			};
		}

		// Out and back as measured on the rails, like the game's own plan, rather than no time at all
		final List<Long> firstPlatforms = new ArrayList<>();
		final List<Long> lastPlatforms = new ArrayList<>();
		routes.forEach(route -> {
			firstPlatforms.add(route.getFirstPlatformId());
			lastPlatforms.add(route.getLastPlatformId());
		});
		final List<Siding> depotSidings = new ArrayList<>();
		ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(depotSidings::add);
		final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		depotSidings.forEach(siding -> {
			final DutyGenerator.SidingInput sidingInput = new DutyGenerator.SidingInput(siding.id, siding.getFleetSize(), LineDuties.sidingLinks(siding, firstPlatforms, ClientData.DATA_CACHE), depot.id);
			sidingInput.poolKey = siding.getPoolKey();
			sidings.add(sidingInput);
		});
		final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(LineDuties.turnTimesOf(Collections.singletonList(depot)), LineDuties.backLinksOf(depotSidings, lastPlatforms, ClientData.DATA_CACHE), LineDuties.poolFleetsOf(sidings, sidingId -> depot), depot.getAddedBuffers(), ClientData.DATA_CACHE.networkSettings.minReverseMillis);
		final Map<Long, Long> stationOf = new HashMap<>();
		ClientData.DATA_CACHE.platformIdToStation.forEach((platformId, station) -> stationOf.put(platformId, station.id));
		final long maxLayoverMillis = depot.cycleRoutes ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS;

		return () -> {
			// As the server plans them: each timetable's days on their own (duty groups, step 2)
			final DutyGenerator.Result result = DutyGenerator.generateByDayType(services, sidings, stationOf, maxLayoverMillis, inputs);
			final DepotDuties duties = new DepotDuties();
			duties.diagramMode = depot.duties.diagramMode;
			duties.sidings.putAll(result.sidings);
			final JsonObject object = new JsonObject();
			object.add("duties", toJson(duties));
			object.addProperty("trains", result.trains);
			object.addProperty("uncovered", result.uncovered);
			object.addProperty("noSidings", sidings.isEmpty());
			final JsonArray missingArray = new JsonArray();
			missingTimes.forEach(missingArray::add);
			object.add("missingTimes", missingArray);
			final JsonArray issuesArray = new JsonArray();
			result.issues.forEach(issuesArray::add);
			object.add("issues", issuesArray);
			object.add("returns", returnsJson(result.returnSidings, null));
			return object;
		};
	}

	/**
	 * @param ownSidings the sidings to keep, or null for all
	 * @return where duties' trains go back when not to their own siding, as the runtime would send them: siding/slot/weekday
	 * → the siding
	 */
	private static JsonObject returnsJson(Map<String, Long> returnSidings, Set<Long> ownSidings) {
		final JsonObject returns = new JsonObject();
		returnSidings.forEach((key, sidingId) -> {
			final long from = Long.parseLong(key.substring(0, key.indexOf('/')));
			if (ownSidings == null || ownSidings.contains(from)) {
				returns.addProperty(key, String.valueOf(sidingId));
			}
		});
		return returns;
	}

	private static String getString(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}

	private static long parseId(String id) {
		try {
			return Long.parseLong(id);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
