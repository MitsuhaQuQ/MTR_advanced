package mtr.data;

import mtr.path.PathData;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.RouteTimetable;
import transitsim.plan.TripPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Planned arrivals for passenger displays, kept separate from the live schedules used to operate trains. */
final class PidsSchedules {

	private PidsSchedules() {
	}

	static Map<Long, PlannedArrival> nextArrivals(Set<Depot> depots, DataCache dataCache, long now) {
		final Map<Long, PlannedArrival> arrivals = new HashMap<>();
		final Map<TripPath<PathData>, Map<Integer, long[][]>> segmentTimes = new IdentityHashMap<>();
		final long today = DutyShapes.getDayStart(now);
		for (final Depot depot : depots) {
			final DepotDuties duties = depot.getRunDuties();
			if (duties == null) {
				continue;
			}
			duties.sidings.forEach((sidingId, slots) -> {
				final Siding siding = dataCache.sidingIdMap.get(sidingId);
				if (siding == null || dataCache.sidingIdToDepot.get(sidingId) != depot || siding.getTrainCars() <= 0) {
					return;
				}
				for (long dayStart = today; dayStart <= now + OperatingDay.MILLIS_PER_DAY; dayStart += OperatingDay.MILLIS_PER_DAY) {
					final long day = DutyShapes.getDayNumber(dayStart);
					final int weekday = OperatingDay.getWeekday(dayStart + OperatingDay.MILLIS_PER_DAY / 2);
					for (int slot = 0; slot < slots.size(); slot++) {
						final DepotDuties.DayDuty duty = slots.get(slot).getDuty(weekday);
						if (duty == null || duty.segments.isEmpty() || depot.hasDutyRun(sidingId, slot, day)) {
							continue;
						}
						final String key = DutyShapes.getSlotKey(slots, slot, weekday, dataCache.routeIdMap::get);
						final TripPath<PathData> path = key == null ? null : siding.getTripPaths().get(key);
						if (path != null && path.isDuty() && !path.stopTimes.isEmpty() && path.dutyStarts.length == duty.segments.size()) {
							addDuty(arrivals, segmentTimes.computeIfAbsent(path, ignored -> new HashMap<>()), depot, siding, slot, dayStart, duty, path, dataCache, now);
						}
					}
				}
			});
		}
		return arrivals;
	}

	private static void addDuty(Map<Long, PlannedArrival> arrivals, Map<Integer, long[][]> segmentTimes, Depot depot, Siding siding, int slot, long dayStart, DepotDuties.DayDuty duty, TripPath<PathData> path, DataCache dataCache, long now) {
		final List<Route> routes = path.routeIds.stream().map(dataCache.routeIdMap::get).collect(Collectors.toList());
		if (routes.size() != duty.segments.size() || routes.contains(null)) {
			return;
		}
		final int weekday = OperatingDay.getWeekday(dayStart + OperatingDay.MILLIS_PER_DAY / 2);
		for (int index = 0; index < duty.segments.size(); index++) {
			final DepotDuties.Segment segment = duty.segments.get(index);
			final Route route = routes.get(index);
			final RouteTimetable timetable = route.timetable;
			final long service = DutyShapes.getServiceMillis(dayStart, segment.service);
			final int table = timetable.getTableIndex(service);
			final RouteTimetable serviceTable = timetable.getTable(table);
			if (route.id != segment.routeId || route.platformIds.size() < 2 || !serviceTable.runsOn(weekday) || !serviceTable.services.contains(segment.service) || serviceTable.suspended.contains(segment.service)) {
				continue;
			}

			final int first = DutyShapes.getFirstStop(route, segment);
			final int last = DutyShapes.getLastStop(route, segment);
			final boolean[] passes = DutyShapes.getPassFlags(route, segment, weekday);
			final long[][] times = segmentTimes.computeIfAbsent(index, key -> path.stopTimes.getSegmentTimes(key, routes));
			final long routeStart;
			if (index == 0) {
				routeStart = first == 0 ? 0 : -path.stopTimes.getEntryOffsetMillis(route, first, table, segment.service);
			} else {
				final RouteTimetable.StopPlan plan = path.stopTimes.getPlan(routes, timetable, path.dutyStarts[index], route.id, table, segment.service);
				final RouteTimetable.StopPlan start = plan == null || plan.startsRoute ? plan : plan.nextRouteStart;
				if (start == null) {
					continue;
				}
				routeStart = start.routeStartMillis;
			}

			for (int stop = first; stop <= last; stop++) {
				if (passes[stop - first] || times[stop][2] < 0) {
					continue;
				}
				final int rail = (int) times[stop][2];
				final long platformId = path.path.get(rail).getStopId();
				if (!dataCache.platformIdMap.containsKey(platformId)) {
					continue;
				}
				final RouteTimetable.StopPlan plan = path.stopTimes.getPlan(routes, timetable, rail, route.id, table, segment.service);
				if (plan == null) {
					continue;
				}
				final long arrival = service + plan.arrivalMillis - routeStart;
				final PlannedArrival previous = arrivals.get(platformId);
				if (arrival > now && arrival <= now + OperatingDay.MILLIS_PER_DAY && (previous == null || arrival < previous.entry.arrivalMillis)) {
					final ScheduleEntry entry = new ScheduleEntry(arrival, siding.getTrainCars(), route.id, stop, 0, false, arrival, true, false);
					arrivals.put(platformId, new PlannedArrival(entry, depot, siding.id, slot, DutyShapes.getDayNumber(dayStart)));
				}
			}
		}
	}

	static List<ScheduleEntry> forDisplay(List<ScheduleEntry> live, PlannedArrival planned, long now) {
		if (live == null && planned == null) {
			return Collections.emptyList();
		}
		final List<ScheduleEntry> display = new ArrayList<>();
		if (live != null) {
			for (final ScheduleEntry entry : live) {
				if (!entry.standingForDuty) {
					display.add(entry);
				}
			}
		}
		if (planned != null && planned.entry.arrivalMillis > now && !planned.depot.hasDutyRun(planned.sidingId, planned.slot, planned.day)) {
			final ScheduleEntry entry = planned.entry;
			if (display.stream().noneMatch(liveEntry -> liveEntry.routeId == entry.routeId && liveEntry.currentStationIndex == entry.currentStationIndex && liveEntry.scheduledMillis == entry.scheduledMillis)) {
				display.add(entry);
			}
		}
		return display;
	}

	static final class PlannedArrival {
		private final ScheduleEntry entry;
		private final Depot depot;
		private final long sidingId;
		private final int slot;
		private final long day;

		PlannedArrival(ScheduleEntry entry, Depot depot, long sidingId, int slot, long day) {
			this.entry = entry;
			this.depot = depot;
			this.sidingId = sidingId;
			this.slot = slot;
			this.day = day;
		}
	}
}
