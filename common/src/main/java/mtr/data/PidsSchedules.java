package mtr.data;

import mtr.path.PathData;
import transitsim.plan.DepotDuties;
import transitsim.plan.DisplayArrivals;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanVersions;
import transitsim.plan.TripPath;
import transitsim.sim.TrainService;
import transitsim.sim.WorkPool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * The services passenger displays and arrival lists tell after each train's current one (0.83.16): by the timetable, as the
 * depots' duties run them, only those some train will run ({@link DisplayArrivals}). Each train tells its current service
 * itself ({@link TrainServer}); this adds the rest. The duties' planned stops are worked out on the shared work threads,
 * again once a minute or when paths or timetables change; every few seconds they are put together with the trains as they
 * are, which is quick.
 */
final class PidsSchedules {

	private static final long REBUILD_MILLIS = 60000;

	/**
	 * The planned arrivals by platform, earliest first, as last worked out. Read by the web server's thread too.
	 */
	private volatile Map<Long, List<ScheduleEntry>> planned = Collections.emptyMap();
	private volatile List<Built> built;
	private Future<?> building;
	private long builtMillis;
	private long builtSignature;
	private boolean stale = true;
	private String leftOutTold = "";

	/**
	 * The arrivals are worked out again when next asked for.
	 */
	void markStale() {
		stale = true;
	}

	boolean isStale() {
		return stale;
	}

	/**
	 * Works the planned arrivals out again from the duties and the trains as they are now.
	 */
	void refresh(Set<Depot> depots, Collection<Siding> sidings, DataCache dataCache, long now) {
		stale = false;
		if (building != null && building.isDone()) {
			building = null;
		}
		final long signature = getSignature(sidings, dataCache);
		if (building == null && (built == null || signature != builtSignature || now - builtMillis >= REBUILD_MILLIS)) {
			builtSignature = signature;
			builtMillis = now;
			final List<Job> jobs = gather(depots, dataCache, now);
			building = WorkPool.submit(() -> {
				try {
					built = build(jobs);
				} catch (RuntimeException e) {
					// Paths or duties changed under it: the last ones are kept until the next time
					System.out.println("Minecraft Transit Railway: working out the planned arrivals for passenger displays failed: " + e);
				}
			});
		}
		final List<Built> current = built;
		if (current == null) {
			return;
		}

		final Map<String, Built> byKey = new HashMap<>();
		final List<DisplayArrivals.Duty> duties = new ArrayList<>();
		current.forEach(each -> {
			byKey.put(each.duty.key, each);
			duties.add(each.duty);
		});
		final Map<Long, TrainServer> trains = new HashMap<>();
		sidings.forEach(siding -> siding.indexTrains(trains));
		final List<DisplayArrivals.Running> running = new ArrayList<>();
		final Map<Object, Integer> idle = new HashMap<>();
		trains.values().forEach(train -> {
			final TrainService<PathData> service = train.getService();
			final Siding siding = dataCache.sidingIdMap.get(train.sidingId);
			final Depot depot = dataCache.sidingIdToDepot.get(train.sidingId);
			if (service.isOnDuty()) {
				final boolean out = train.getIsOnRoute();
				final String key = getKey(service.getDutySidingId(), service.getDutySlot(), DutyShapes.getDayNumber(service.getDutyDayStart()));
				running.add(new DisplayArrivals.Running(key, train.id, service.getDutyIndex(), out ? service.getDutyIndex() + 1 : service.getDutyIndex(), out ? service.getLateSinceMillis() : 0));
			} else if (!train.getIsOnRoute() && siding != null && depot != null) {
				idle.merge(getPool(depot, siding), 1, Integer::sum);
			}
		});
		final DisplayArrivals.Result result = DisplayArrivals.compute(duties, running, idle, duty -> {
			final Built each = byKey.get(duty.key);
			return each != null && each.depot.hasDutyRun(each.sidingId, each.slot, each.day);
		}, now, now + OperatingDay.MILLIS_PER_DAY);

		final Map<Long, List<ScheduleEntry>> newPlanned = new HashMap<>();
		result.arrivals.forEach((platformId, arrivals) -> {
			final List<ScheduleEntry> entries = new ArrayList<>();
			arrivals.forEach(arrival -> entries.add(new ScheduleEntry(arrival.estimateMillis, arrival.duty.cars, arrival.stop.routeId, arrival.stop.stopIndex, arrival.trainId, arrival.stop.pass, arrival.stop.arrivalMillis, arrival.trainId == 0, false)));
			newPlanned.put(platformId, Collections.unmodifiableList(entries));
		});
		planned = Collections.unmodifiableMap(newPlanned);

		// The duties no train will run, said in the server log when that changes, as the displays leave them out
		final String leftOut = result.noPath + result.noOvernightTrain + result.noTrain == 0 ? "" : "no path " + result.noPath + ", no train staying overnight " + result.noOvernightTrain + ", no train in time " + result.noTrain;
		if (!leftOut.equals(leftOutTold)) {
			leftOutTold = leftOut;
			if (!leftOut.isEmpty()) {
				System.out.println("Minecraft Transit Railway: passenger displays leave out duties no train will run: " + leftOut);
			}
		}
	}

	/**
	 * @return the planned arrivals at a platform, earliest first, or null for none
	 */
	List<ScheduleEntry> getPlanned(long platformId) {
		return planned.get(platformId);
	}

	/**
	 * @return the platforms with planned arrivals
	 */
	Set<Long> getPlannedPlatforms() {
		return planned.keySet();
	}

	/**
	 * What displays are told at a platform: the trains' own arrivals for their current services, a depot's estimate for a
	 * train standing there for its duty left out (the plan tells it), and the planned arrivals still to come that no train
	 * tells already, by route, stop and planned time.
	 */
	static List<ScheduleEntry> forDisplay(List<ScheduleEntry> live, List<ScheduleEntry> planned, long now) {
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
		if (planned != null) {
			final int liveCount = display.size();
			for (final ScheduleEntry entry : planned) {
				if (entry.arrivalMillis > now && !isTold(display, liveCount, entry)) {
					display.add(entry);
				}
			}
		}
		return display;
	}

	private static boolean isTold(List<ScheduleEntry> display, int liveCount, ScheduleEntry entry) {
		for (int i = 0; i < liveCount; i++) {
			final ScheduleEntry liveEntry = display.get(i);
			if (liveEntry.routeId == entry.routeId && liveEntry.currentStationIndex == entry.currentStationIndex && liveEntry.scheduledMillis == entry.scheduledMillis) {
				return true;
			}
		}
		return false;
	}

	static String getKey(long sidingId, int slot, long day) {
		return sidingId + ":" + slot + ":" + day;
	}

	private static Object getPool(Depot depot, Siding siding) {
		return depot.id + "/" + siding.getPoolKey();
	}

	/**
	 * @return what changes when the paths of the sidings or the timetables of the routes do
	 */
	private static long getSignature(Collection<Siding> sidings, DataCache dataCache) {
		long signature = 0;
		for (final Siding siding : sidings) {
			signature = signature * 31 + siding.id * 17 + siding.getPathSignature();
		}
		for (final Route route : dataCache.routeIdMap.values()) {
			signature += route.id * 0x9E3779B97F4A7C15L ^ route.timetable.revision;
		}
		return signature;
	}

	/**
	 * Takes what working the duties out needs, on the server thread: copies of the duties, the routes as they are now, and
	 * the paths of each siding and of the others of its pool.
	 */
	static List<Job> gather(Set<Depot> depots, DataCache dataCache, long now) {
		final List<Job> jobs = new ArrayList<>();
		final Map<Long, PlanRoute> routes = new HashMap<>();
		dataCache.routeIdMap.forEach((routeId, route) -> routes.put(routeId, PlanVersions.fix(route)));
		final long today = DutyShapes.getDayStart(now);
		for (final Depot depot : depots) {
			final DepotDuties duties = depot.getRunDuties();
			if (duties == null) {
				continue;
			}
			final Map<Object, List<Map<String, TripPath<PathData>>>> poolPaths = new HashMap<>();
			final Map<Long, Map<String, TripPath<PathData>>> sidingPaths = new HashMap<>();
			duties.sidings.keySet().forEach(sidingId -> {
				final Siding siding = dataCache.sidingIdMap.get(sidingId);
				if (siding != null && dataCache.sidingIdToDepot.get(sidingId) == depot) {
					final Map<String, TripPath<PathData>> paths = new HashMap<>(siding.getTripPaths());
					sidingPaths.put(sidingId, paths);
					poolPaths.computeIfAbsent(getPool(depot, siding), key -> new ArrayList<>()).add(paths);
				}
			});
			duties.sidings.forEach((sidingId, slots) -> {
				final Siding siding = dataCache.sidingIdMap.get(sidingId);
				if (siding == null || dataCache.sidingIdToDepot.get(sidingId) != depot || siding.getTrainCars() <= 0) {
					return;
				}
				final Object pool = getPool(depot, siding);
				final List<Map<String, TripPath<PathData>>> paths = new ArrayList<>();
				paths.add(sidingPaths.get(sidingId));
				poolPaths.getOrDefault(pool, Collections.emptyList()).forEach(other -> {
					if (other != sidingPaths.get(sidingId)) {
						paths.add(other);
					}
				});
				final List<DepotDuties.Slot> slotsCopy = new ArrayList<>(slots);
				// The day before, for trains still on its duties, today and tomorrow
				for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
					final long dayStart = today + dayOffset * (long) OperatingDay.MILLIS_PER_DAY;
					final long day = DutyShapes.getDayNumber(dayStart);
					final int weekday = OperatingDay.getWeekday(dayStart + OperatingDay.MILLIS_PER_DAY / 2);
					for (int slot = 0; slot < slotsCopy.size(); slot++) {
						final DepotDuties.DayDuty duty = slotsCopy.get(slot).getDuty(weekday);
						if (duty == null || duty.segments.isEmpty()) {
							continue;
						}
						final String nextKey;
						if (!duty.stayOnLine) {
							nextKey = null;
						} else if (duty.nextDutyId != 0) {
							final long[] next = duties.findSlot(duty.nextDutyId);
							nextKey = next == null ? "" : getKey(next[0], (int) next[1], day + 1);
						} else {
							nextKey = getKey(sidingId, DutyShapes.getNextSlot(duty, slot), day + 1);
						}
						final boolean overnightStart = DutyShapes.getOvernightSlot(slotsCopy, slot, weekday) >= 0;
						jobs.add(new Job(depot, sidingId, slotsCopy, slot, dayStart, day, weekday, duty.copy(), overnightStart, nextKey, pool, siding.getTrainCars(), paths, routes));
					}
				}
			});
		}
		return jobs;
	}

	/**
	 * Works each duty's planned stops out, on a work thread.
	 */
	static List<Built> build(List<Job> jobs) {
		final List<Built> builtDuties = new ArrayList<>();
		for (final Job job : jobs) {
			final String pathKey = DutyShapes.getSlotKey(job.slots, job.slot, job.weekday, job.routes::get);
			TripPath<PathData> path = null;
			if (pathKey != null) {
				for (final Map<String, TripPath<PathData>> paths : job.paths) {
					final TripPath<PathData> found = paths.get(pathKey);
					if (found != null && found.isDuty()) {
						path = found;
						break;
					}
				}
			}
			List<List<DisplayArrivals.Stop>> stops = Collections.emptyList();
			long lead = 0;
			if (path != null) {
				final List<PlanRoute> pathRoutes = new ArrayList<>();
				path.routeIds.forEach(routeId -> pathRoutes.add(job.routes.get(routeId)));
				stops = DisplayArrivals.dutyStops(job.duty, job.dayStart, path, pathRoutes);
				lead = path.stopTimes.getLeadMillis();
			}
			// The way back to the siding taken as long as the way out
			final DisplayArrivals.Duty duty = new DisplayArrivals.Duty(getKey(job.sidingId, job.slot, job.day), job.pool, job.cars, job.overnightStart, job.nextKey, DisplayArrivals.leaveMillis(stops, lead), DisplayArrivals.backMillis(stops, job.nextKey == null ? lead : 0), stops);
			builtDuties.add(new Built(duty, job.depot, job.sidingId, job.slot, job.day));
		}
		return builtDuties;
	}

	static final class Job {
		private final Depot depot;
		private final long sidingId;
		private final List<DepotDuties.Slot> slots;
		private final int slot;
		private final long dayStart;
		private final long day;
		private final int weekday;
		private final DepotDuties.DayDuty duty;
		private final boolean overnightStart;
		private final String nextKey;
		private final Object pool;
		private final int cars;
		private final List<Map<String, TripPath<PathData>>> paths;
		private final Map<Long, PlanRoute> routes;

		private Job(Depot depot, long sidingId, List<DepotDuties.Slot> slots, int slot, long dayStart, long day, int weekday, DepotDuties.DayDuty duty, boolean overnightStart, String nextKey, Object pool, int cars, List<Map<String, TripPath<PathData>>> paths, Map<Long, PlanRoute> routes) {
			this.depot = depot;
			this.sidingId = sidingId;
			this.slots = slots;
			this.slot = slot;
			this.dayStart = dayStart;
			this.day = day;
			this.weekday = weekday;
			this.duty = duty;
			this.overnightStart = overnightStart;
			this.nextKey = nextKey;
			this.pool = pool;
			this.cars = cars;
			this.paths = paths;
			this.routes = routes;
		}
	}

	static final class Built {
		final DisplayArrivals.Duty duty;
		private final Depot depot;
		private final long sidingId;
		private final int slot;
		private final long day;

		private Built(DisplayArrivals.Duty duty, Depot depot, long sidingId, int slot, long day) {
			this.duty = duty;
			this.depot = depot;
			this.sidingId = sidingId;
			this.slot = slot;
			this.day = day;
		}
	}
}
