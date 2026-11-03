package mtr.client.editor;

import transitsim.plan.StopRules;
import transitsim.plan.RouteTimetable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import transitsim.plan.DepotDuties;
import mtr.advanced.DutyGenerator;
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
		final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(parseId(getString(operation, "depot")));
		if (depot == null) {
			return "找不到这个车厂，可能已被删除";
		}
		final JsonObject dutiesObject = operation.getAsJsonObject("duties");
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = "diagram".equals(getString(dutiesObject, "mode"));
		final Set<String> taken = new HashSet<>();

		for (final Map.Entry<String, JsonElement> sidingEntry : dutiesObject.getAsJsonObject("sidings").entrySet()) {
			final long sidingId = parseId(sidingEntry.getKey());
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
			if (siding == null || ClientData.DATA_CACHE.sidingIdToDepot.get(sidingId) != depot) {
				return "有存车线不在这个车厂里，可能刚被拆掉，请刷新";
			}
			final JsonArray slotsArray = sidingEntry.getValue().getAsJsonArray();
			final String sidingName = siding.name.isEmpty() ? "存车线" : siding.name;
			if (slotsArray.size() > DepotDuties.MAX_SLOTS || !siding.getUnlimitedTrains() && slotsArray.size() > siding.getMaxTrains()) {
				return String.format("%s 只能放 %s 列车，库位太多", sidingName, siding.getUnlimitedTrains() ? DepotDuties.MAX_SLOTS : siding.getMaxTrains());
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
						return String.format("%s 车 %s 的运用星期重叠", sidingName, i + 1);
					}
					usedDays |= day.weekdays;
					day.stayOnLine = dayObject.get("stay").getAsBoolean();
					day.nextSlot = day.stayOnLine && dayObject.has("next") ? dayObject.get("next").getAsInt() : -1;
					if (day.nextSlot >= slotsArray.size() || day.nextSlot < -1) {
						return String.format("%s 车 %s 过夜后接的库位不存在", sidingName, i + 1);
					}
					final JsonArray segmentsArray = dayObject.getAsJsonArray("segments");
					if (segmentsArray.size() > DepotDuties.MAX_SEGMENTS) {
						return String.format("%s 车 %s 一天最多跑 %s 班", sidingName, i + 1, DepotDuties.MAX_SEGMENTS);
					}
					for (final JsonElement segmentElement : segmentsArray) {
						final JsonObject segmentObject = segmentElement.getAsJsonObject();
						final DepotDuties.Segment segment = new DepotDuties.Segment(parseId(getString(segmentObject, "route")), segmentObject.get("service").getAsInt(), segmentObject.get("from").getAsInt(), segmentObject.get("to").getAsInt());
						final String error = checkSegment(depot, segment, day.weekdays, taken);
						if (error != null) {
							return String.format("%s 车 %s：%s", sidingName, i + 1, error);
						}
						day.segments.add(segment);
					}
					slot.days.add(day);
				}
				slots.add(slot);
			}
			// Two trains staying overnight can't both take over the same slot the next day
			for (int weekday = 0; weekday < 7; weekday++) {
				final Set<Integer> takenSlots = new HashSet<>();
				for (int i = 0; i < slots.size(); i++) {
					final DepotDuties.DayDuty day = slots.get(i).getDuty(weekday);
					if (day != null && day.stayOnLine && !day.segments.isEmpty() && !takenSlots.add(day.nextSlot < 0 ? i : day.nextSlot)) {
						return String.format("%s 有两列过夜的车第二天接同一个库位（车 %s）", sidingName, (day.nextSlot < 0 ? i : day.nextSlot) + 1);
					}
				}
			}
			if (!slots.isEmpty()) {
				duties.sidings.put(sidingId, slots);
			}
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
	 * A segment has to be a service of one of the depot's routes on every day of its duty, and no other train of the depot
	 * may run it on those days.
	 */
	private static String checkSegment(Depot depot, DepotDuties.Segment segment, int weekdays, Set<String> taken) {
		final Route route = ClientData.DATA_CACHE.routeIdMap.get(segment.routeId);
		if (route == null || !depot.routeIds.contains(segment.routeId)) {
			return "有一班的线路不在这个车厂里";
		}
		if (segment.fromStop >= route.platformIds.size() || segment.toStop >= route.platformIds.size()) {
			return String.format("%s 的车站和游戏里的不一致，请刷新", route.name);
		}
		for (int day = 0; day < 7; day++) {
			if ((weekdays >> day & 1) == 0) {
				continue;
			}
			final RouteTimetable table = getTable(route, day);
			if (table == null || !table.services.contains(segment.service)) {
				return String.format("%s 的一班在%s不运行，时刻表可能改过", route.name, WEEKDAY_NAMES[day]);
			}
			if (!taken.add(segment.routeId + "@" + segment.service + "/" + day)) {
				return String.format("%s 的一班在%s分给了两列车", route.name, WEEKDAY_NAMES[day]);
			}
		}
		return null;
	}

	private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

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
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		final List<String> missingTimes = new ArrayList<>();
		depot.routeIds.forEach(routeId -> {
			final Route route = ClientData.DATA_CACHE.routeIdMap.get(routeId);
			if (route == null || route.platformIds.size() < 2) {
				return;
			}
			final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
			final boolean hasTimes = times != null && times.length == route.platformIds.size();
			if (!hasTimes) {
				missingTimes.add(route.name);
				return;
			}
			final long[] platformIds = route.platformIds.stream().mapToLong(routePlatform -> routePlatform.platformId).toArray();
			final List<RouteTimetable> tables = route.timetable.getTables();
			for (int i = 0; i < tables.size(); i++) {
				final RouteTimetable table = tables.get(i);
				// A day belongs to the first table that has it
				int days = table.weekdays;
				for (int j = 0; j < i; j++) {
					days &= ~tables.get(j).weekdays;
				}
				if (days == 0) {
					continue;
				}
				for (final int service : table.services) {
					// Times and passing as trains and the timetable page have them, with this service's own exceptions
					final List<StopRules.StopTime> stopTimes = StopRules.times(route.platformIds, times, table.marginPercent, i, table.exceptions.get(service));
					final long[][] plans = new long[platformIds.length][];
					final boolean[] pass = new boolean[platformIds.length];
					for (int j = 0; j < plans.length; j++) {
						final StopRules.StopTime stopTime = j < stopTimes.size() ? stopTimes.get(j) : null;
						plans[j] = stopTime == null ? new long[]{0, 0} : new long[]{stopTime.arrivalMillis, stopTime.departureMillis};
						pass[j] = stopTime != null && stopTime.passes;
					}
					final RouteTimetable.ServiceLink link = table.links.get(service);
					services.add(new DutyGenerator.ServiceInput(route.id, service, days, Math.floorMod(service - dayStartMillis, Depot.MILLISECONDS_PER_DAY), platformIds, plans, pass,
							link == null ? 0 : link.routeId, link == null ? 0 : link.service, link == null ? -1 : link.fromStop, link == null ? 0 : link.toStop));
				}
			}
		});

		final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		ClientData.SIDINGS.stream().filter(siding -> ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id) == depot).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> sidings.add(new DutyGenerator.SidingInput(siding.id, siding.getUnlimitedTrains() ? -1 : siding.getMaxTrains())));
		final Map<Long, Long> stationOf = new HashMap<>();
		ClientData.DATA_CACHE.platformIdToStation.forEach((platformId, station) -> stationOf.put(platformId, station.id));
		final long maxLayoverMillis = depot.cycleRoutes ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS;

		return () -> {
			final DutyGenerator.Result result = DutyGenerator.generate(services, sidings, stationOf, maxLayoverMillis);
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
			return object;
		};
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
