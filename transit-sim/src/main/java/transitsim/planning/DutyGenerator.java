package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.RouteTimetable;
import transitsim.plan.StopRules;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Works out a first draft of a depot's duties with {@link DutyPlanner} (design document 6.12, block E1): the fewest
 * trains, and the same result for the same timetables. Each siding is a depot to the planner, through links make one
 * trip of their services, and trains turn back between platforms of the same station. The weekly plan is then cut into
 * operating days, given to slots, and days with the same duty are kept together.
 */
public final class DutyGenerator {

	/**
	 * The least time a train waits at the end of a service before its next one.
	 */
	public static final long MIN_TURNAROUND_MILLIS = 30000;
	/**
	 * The time to turn back onto another platform of the same station, as nothing finds the real time yet.
	 */
	public static final long TURN_BACK_MILLIS = 120000;
	private static final int MAX_LINKS = 8;
	private static final int UNLIMITED_TRAINS = DepotDuties.MAX_SLOTS;

	private DutyGenerator() {
	}

	/**
	 * Each service of the routes, once for each table that runs it on a day no earlier table has, with its times and
	 * passing as trains and the timetable page have them ({@link StopRules}) and its own exceptions.
	 *
	 * @param fastestTimes   the fastest arrival and departure at each stop of a route, or null if they aren't known yet
	 * @param dayStartMillis when the operating day starts, as a time of day
	 * @param missingTimes   told about each route left out as it has no fastest times
	 */
	public static List<ServiceInput> servicesOf(List<? extends PlanRoute> routes, Function<PlanRoute, long[][]> fastestTimes, int dayStartMillis, Consumer<PlanRoute> missingTimes) {
		final List<ServiceInput> services = new ArrayList<>();
		for (final PlanRoute route : routes) {
			if (route.getStops().size() < 2) {
				continue;
			}
			final long[][] times = fastestTimes.apply(route);
			if (times == null || times.length != route.getStops().size()) {
				missingTimes.accept(route);
				continue;
			}
			final long[] platformIds = route.getStops().stream().mapToLong(stop -> stop.getPlatformId()).toArray();
			final List<RouteTimetable> tables = route.getTimetable().getTables();
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
					final List<StopRules.StopTime> stopTimes = StopRules.times(route.getStops(), times, table.marginPercent, i, table.exceptions.get(service));
					final long[][] plans = new long[platformIds.length][];
					final boolean[] pass = new boolean[platformIds.length];
					for (int j = 0; j < plans.length; j++) {
						final StopRules.StopTime stopTime = j < stopTimes.size() ? stopTimes.get(j) : null;
						plans[j] = stopTime == null ? new long[]{0, 0} : new long[]{stopTime.arrivalMillis, stopTime.departureMillis};
						pass[j] = stopTime != null && stopTime.passes;
					}
					final RouteTimetable.ServiceLink link = table.links.get(service);
					final ServiceInput input = new ServiceInput(route.getRouteId(), service, days, Math.floorMod(service - dayStartMillis, (long) OperatingDay.MILLIS_PER_DAY), platformIds, plans, pass,
							link == null ? 0 : link.routeId, link == null ? 0 : link.service, link == null ? -1 : link.fromStop, link == null ? 0 : link.toStop);
					final RouteTimetable.ServiceOrder deployOrder = table.deployOrders.get(service);
					if (deployOrder != null && deployOrder.stop < platformIds.length - 1) {
						input.deployStop = deployOrder.stop;
						input.deployDepot = deployOrder.depotId;
					}
					final RouteTimetable.ServiceOrder returnOrder = table.returnOrders.get(service);
					if (returnOrder != null && returnOrder.stop > Math.max(input.deployStop, 0)) {
						input.returnStop = Math.min(returnOrder.stop, platformIds.length - 1);
						input.returnDepot = returnOrder.depotId;
					}
					services.add(input);
				}
			}
		}
		return services;
	}

	/**
	 * @param services        each service of the depot's routes, once for each table that has it
	 * @param sidings         the depot's sidings
	 * @param stationOf       the station of each platform, to turn back between its platforms
	 * @param maxLayoverMillis how long a train may wait on the line for its next service
	 */
	public static Result generate(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis) {
		final Result result = new Result();
		if (services.isEmpty() || sidings.isEmpty()) {
			return result;
		}

		final List<Chain> chains = buildChains(services);
		final List<TimetableService> timetableServices = new ArrayList<>();
		for (int i = 0; i < chains.size(); i++) {
			final Chain chain = chains.get(i);
			final TimetableService timetableService = chain.toTimetableService(i);
			// Set on the running diagram: a train fresh out of a depot, of that depot if it has a siding here
			if (chain.deployDepot >= 0) {
				final Set<Long> startDepots = new TreeSet<>();
				sidings.forEach(siding -> {
					if (chain.deployDepot == 0 || siding.depotId == chain.deployDepot) {
						startDepots.add(siding.sidingId);
					}
				});
				if (startDepots.isEmpty()) {
					result.issues.add("DEPLOY_DEPOT " + chain.deployDepot + " has no siding for " + chain.segments.get(0).routeId + "@" + chain.segments.get(0).service);
					sidings.forEach(siding -> startDepots.add(siding.sidingId));
				}
				timetableService.startDepots = startDepots;
			}
			timetableService.endsDuty = chain.endsDuty;
			timetableServices.add(timetableService);
		}

		final OperationNetwork network = new OperationNetwork();
		sidings.forEach(siding -> network.addDepot(siding.sidingId, siding.maxTrains < 0 ? UNLIMITED_TRAINS : Math.min(siding.maxTrains, UNLIMITED_TRAINS)));
		final Set<Long> firstPlatforms = new TreeSet<>();
		final Set<Long> lastPlatforms = new TreeSet<>();
		timetableServices.forEach(service -> {
			firstPlatforms.add(service.stops.get(0).platformId);
			lastPlatforms.add(service.stops.get(service.stops.size() - 1).platformId);
		});
		// A siding with links of its own starts its trains only there, the nearest first; otherwise at any first stop
		sidings.forEach(siding -> {
			if (siding.links == null) {
				firstPlatforms.forEach(platformId -> network.addDepotLink(siding.sidingId, platformId, 0));
			} else {
				siding.links.forEach((platformId, millis) -> {
					if (firstPlatforms.contains(platformId)) {
						network.addDepotLink(siding.sidingId, platformId, millis);
					}
				});
			}
		});
		// A train sent out for a service at a stop of the line runs there from its siding, taking as long as to the nearest
		// first stop the siding knows, the best guess without the rails
		timetableServices.forEach(service -> {
			if (service.startDepots == null) {
				return;
			}
			final long platformId = service.stops.get(0).platformId;
			sidings.forEach(siding -> {
				if (siding.links != null && service.startDepots.contains(siding.sidingId) && !siding.links.containsKey(platformId)) {
					network.addDepotLink(siding.sidingId, platformId, siding.links.values().stream().mapToLong(Long::longValue).min().orElse(0));
				}
			});
		});
		lastPlatforms.forEach(lastPlatform -> {
			network.setTurnaround(lastPlatform, MIN_TURNAROUND_MILLIS);
			final Long station = stationOf.get(lastPlatform);
			firstPlatforms.forEach(firstPlatform -> {
				if (firstPlatform.longValue() != lastPlatform && station != null && station.equals(stationOf.get(firstPlatform))) {
					network.addConnection(lastPlatform, firstPlatform, TURN_BACK_MILLIS);
				}
			});
		});

		final OperationPlan plan = DutyPlanner.plan(timetableServices, Collections.emptyList(), network, maxLayoverMillis);
		plan.issues.forEach(issue -> {
			if (issue.type == OperationPlan.IssueType.NO_TRAIN) {
				result.uncovered++;
			} else if (issue.type != OperationPlan.IssueType.UNUSED_BLANK) {
				result.issues.add(issue.type + " " + issue.detail);
			}
		});
		result.trains = plan.peakTrains;
		assignSlots(plan, chains, sidings, result);
		return result;
	}

	/**
	 * A service and the services its through links carry it on to, as one trip. A linked service runs as part of the
	 * trip on the days both run, and on its own on the other days.
	 */
	private static List<Chain> buildChains(List<ServiceInput> services) {
		final Map<String, List<ServiceInput>> byService = new HashMap<>();
		services.forEach(service -> byService.computeIfAbsent(service.routeId + "@" + service.service, key -> new ArrayList<>()).add(service));

		final Map<ServiceInput, Integer> joinedDays = new IdentityHashMap<>();
		services.forEach(service -> {
			int days = service.weekdays;
			ServiceInput current = service;
			for (int i = 0; i < MAX_LINKS && current.linkRoute != 0 && current.returnStop < 0 && days != 0; i++) {
				final ServiceInput next = findLinked(byService, current, days);
				// A service a train is sent out of a depot for starts a trip of its own
				if (next == null || next == service || next.deployStop >= 0) {
					break;
				}
				days &= next.weekdays;
				joinedDays.merge(next, days, (a, b) -> a | b);
				current = next;
			}
		});

		final List<Chain> chains = new ArrayList<>();
		services.forEach(service -> {
			final int ownDays = service.weekdays & ~joinedDays.getOrDefault(service, 0);
			if (ownDays == 0) {
				return;
			}
			final Chain chain = new Chain(ownDays);
			chain.add(service, Math.max(service.deployStop, 0));
			ServiceInput current = service;
			for (int i = 0; i < MAX_LINKS && current.linkRoute != 0 && current.returnStop < 0; i++) {
				final ServiceInput next = findLinked(byService, current, chain.weekdays);
				if (next == null || next == service || next.deployStop >= 0 || (chain.weekdays & next.weekdays) == 0) {
					break;
				}
				chain.weekdays &= next.weekdays;
				chain.cut(current.linkFromStop);
				chain.add(next, current.linkToStop);
				current = next;
			}
			// A service set to end at a stop leaves the line there
			if (current.returnStop >= 0) {
				chain.cut(current.returnStop);
			}
			chain.deployDepot = service.deployStop >= 0 ? service.deployDepot : -1;
			chain.endsDuty = current.returnStop >= 0;
			chains.add(chain);
		});
		chains.sort(Comparator.comparingLong((Chain chain) -> chain.startMillis).thenComparingLong(chain -> chain.segments.get(0).routeId));
		return chains;
	}

	private static ServiceInput findLinked(Map<String, List<ServiceInput>> byService, ServiceInput service, int days) {
		final List<ServiceInput> candidates = byService.get(service.linkRoute + "@" + service.linkService);
		if (candidates == null) {
			return null;
		}
		return candidates.stream().filter(candidate -> (candidate.weekdays & days) != 0).findFirst().orElse(null);
	}

	/**
	 * Cuts the weekly duties into operating days, and gives each siding's days to its slots so a train staying on the line
	 * overnight keeps its slot the next day. Days of a slot with the same duty become one duty for all of them.
	 */
	private static void assignSlots(OperationPlan plan, List<Chain> chains, List<SidingInput> sidings, Result result) {
		final Map<Long, List<OperationPlan.Duty>> dutiesBySiding = new TreeMap<>();
		plan.duties.forEach(duty -> dutiesBySiding.computeIfAbsent(duty.depotId, key -> new ArrayList<>()).add(duty));

		sidings.forEach(siding -> {
			final List<OperationPlan.Duty> duties = dutiesBySiding.getOrDefault(siding.sidingId, Collections.emptyList());
			// Day → slot → pieces of duty, each a list of segments and whether it stays on the line
			final List<Map<Integer, Piece>> days = new ArrayList<>();
			for (int day = 0; day < 7; day++) {
				days.add(new TreeMap<>());
			}
			final Map<Integer, Integer> slotOfDuty = new HashMap<>();
			// Duties still out at the end of the week go first, so the duties they carry on into on Monday can keep their slot
			final List<OperationPlan.Duty> ordered = new ArrayList<>(duties);
			ordered.sort(Comparator.comparing((OperationPlan.Duty duty) -> duty.nextDuty == OperationPlan.NONE).thenComparingLong(duty -> duty.trips.get(0).startMillis()));

			for (final OperationPlan.Duty duty : ordered) {
				// A duty carried over from the week before keeps the slot of the duty it continues
				final int carriedSlot = duty.previousDuty == OperationPlan.NONE ? -1 : slotOfDuty.getOrDefault(duty.previousDuty, -1);
				final Map<Integer, Piece> pieces = new TreeMap<>();
				for (final OperationPlan.Trip trip : duty.trips) {
					final int day = (int) Math.floorMod(trip.startMillis() / OperationTime.DAY_MILLIS, 7L);
					pieces.computeIfAbsent(day, key -> new Piece()).segments.addAll(chains.get((int) trip.sourceId).segments);
				}
				final List<Integer> pieceDays = new ArrayList<>(pieces.keySet());
				for (int i = 0; i + 1 < pieceDays.size(); i++) {
					pieces.get(pieceDays.get(i)).stayOnLine = true;
				}
				if (duty.nextDuty != OperationPlan.NONE && !pieceDays.isEmpty()) {
					pieces.get(pieceDays.get(pieceDays.size() - 1)).stayOnLine = true;
				}

				int slot = carriedSlot;
				if (slot < 0 || pieceDays.stream().anyMatch(day -> days.get(day).containsKey(carriedSlot))) {
					slot = 0;
					while (true) {
						final int testSlot = slot;
						if (pieceDays.stream().noneMatch(day -> days.get(day).containsKey(testSlot))) {
							break;
						}
						slot++;
					}
				}
				slotOfDuty.put(duty.index, slot);
				for (final int day : pieceDays) {
					days.get(day).put(slot, pieces.get(day));
				}
			}

			final int slotCount = days.stream().mapToInt(day -> day.isEmpty() ? 0 : Collections.max(day.keySet()) + 1).max().orElse(0);
			final List<DepotDuties.Slot> slots = new ArrayList<>();
			for (int slotIndex = 0; slotIndex < Math.min(slotCount, DepotDuties.MAX_SLOTS); slotIndex++) {
				final DepotDuties.Slot slot = new DepotDuties.Slot();
				for (int day = 0; day < 7; day++) {
					final Piece piece = days.get(day).get(slotIndex);
					if (piece == null) {
						continue;
					}
					final DepotDuties.DayDuty same = slot.days.stream().filter(dayDuty -> dayDuty.stayOnLine == piece.stayOnLine && dayDuty.segments.equals(piece.segments)).findFirst().orElse(null);
					if (same == null) {
						final DepotDuties.DayDuty dayDuty = new DepotDuties.DayDuty();
						dayDuty.weekdays = 1 << day;
						dayDuty.stayOnLine = piece.stayOnLine;
						dayDuty.segments.addAll(piece.segments.subList(0, Math.min(piece.segments.size(), DepotDuties.MAX_SEGMENTS)));
						slot.days.add(dayDuty);
					} else {
						same.weekdays |= 1 << day;
					}
				}
				slots.add(slot);
			}
			if (!slots.isEmpty()) {
				result.sidings.put(siding.sidingId, slots);
			}
		});
	}

	private static final class Piece {

		private final List<DepotDuties.Segment> segments = new ArrayList<>();
		private boolean stayOnLine;
	}

	private static final class Chain {

		private int weekdays;
		/**
		 * The depot a train for it has to come out of, 0 for any, or -1 if it may be any train.
		 */
		private long deployDepot = -1;
		private boolean endsDuty;
		private long startMillis = -1;
		private final List<DepotDuties.Segment> segments = new ArrayList<>();
		private final List<TimetableService.Stop> stops = new ArrayList<>();
		// The services, kept to cut the last one where it leaves for the next
		private final List<ServiceInput> inputs = new ArrayList<>();
		private final List<Integer> fromStops = new ArrayList<>();
		private final List<Long> offsets = new ArrayList<>();

		private Chain(int weekdays) {
			this.weekdays = weekdays;
		}

		private void add(ServiceInput service, int fromStop) {
			long offset = 0;
			if (startMillis < 0) {
				startMillis = service.startMillis;
			} else {
				offset = service.startMillis - startMillis;
				final long lastDeparture = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).departureOffsetMillis;
				// A service that reads as earlier in the day than the one before it runs after midnight
				while (offset + service.plans[Math.min(fromStop, service.plans.length - 1)][0] < lastDeparture) {
					offset += OperationTime.DAY_MILLIS;
				}
			}
			final int from = Math.max(0, Math.min(fromStop, service.platformIds.length - 1));
			for (int i = from; i < service.platformIds.length; i++) {
				stops.add(new TimetableService.Stop(service.platformIds[i], offset + service.plans[i][0], offset + service.plans[i][1], service.pass[i]));
			}
			inputs.add(service);
			fromStops.add(from);
			offsets.add(offset);
			segments.add(new DepotDuties.Segment(service.routeId, service.service, from, -1));
		}

		/**
		 * Ends the last service at a stop, where it leaves for the next one.
		 */
		private void cut(int toStop) {
			final int last = inputs.size() - 1;
			final ServiceInput service = inputs.get(last);
			if (toStop < 0 || toStop >= service.platformIds.length - 1) {
				return;
			}
			final int from = fromStops.get(last);
			final int keep = Math.max(1, toStop - from + 1);
			final int remove = service.platformIds.length - from - keep;
			for (int i = 0; i < remove; i++) {
				stops.remove(stops.size() - 1);
			}
			final DepotDuties.Segment segment = segments.get(last);
			segments.set(last, new DepotDuties.Segment(segment.routeId, segment.service, segment.fromStop, toStop));
		}

		private TimetableService toTimetableService(int id) {
			return new TimetableService(id, segments.get(0).routeId, weekdays, startMillis, false, stops);
		}
	}

	/**
	 * A service of one table of a route.
	 */
	public static final class ServiceInput {

		public final long routeId;
		public final int service;
		public final int weekdays;
		/**
		 * When it leaves the first stop, in milliseconds since the start of the operating day.
		 */
		public final long startMillis;
		public final long[] platformIds;
		/**
		 * Planned arrival and departure at each stop, in milliseconds from the first stop.
		 */
		public final long[][] plans;
		public final boolean[] pass;
		public final long linkRoute;
		public final int linkService;
		public final int linkFromStop;
		public final int linkToStop;
		/**
		 * Set on the running diagram: the stop a train sent out of a depot joins it at and the depot, or -1; the stop it
		 * ends at to run empty back to a depot and the depot, or -1. A depot of 0 is any.
		 */
		public int deployStop = -1;
		public long deployDepot;
		public int returnStop = -1;
		public long returnDepot;

		public ServiceInput(long routeId, int service, int weekdays, long startMillis, long[] platformIds, long[][] plans, boolean[] pass, long linkRoute, int linkService, int linkFromStop, int linkToStop) {
			this.routeId = routeId;
			this.service = service;
			this.weekdays = weekdays;
			this.startMillis = startMillis;
			this.platformIds = platformIds;
			this.plans = plans;
			this.pass = pass;
			this.linkRoute = linkRoute;
			this.linkService = linkService;
			this.linkFromStop = linkFromStop;
			this.linkToStop = linkToStop;
		}
	}

	/**
	 * Like {@link #generate}, but when the sidings hold too few trains for every service, the trains run every k-th
	 * service of each route evenly through the day, rather than every service early in the day and none later: k is the
	 * smallest that leaves no kept service without a train, starting from what's needed over what's held. The services
	 * left out count as without a train. With trains enough, or a siding with no limit, the result is
	 * {@link #generate}'s.
	 */
	public static Result generateSpread(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis) {
		final Result all = generate(services, sidings, stationOf, maxLayoverMillis);
		if (all.uncovered == 0 || sidings.stream().anyMatch(siding -> siding.maxTrains < 0)) {
			return all;
		}
		final int held = sidings.stream().mapToInt(siding -> Math.min(siding.maxTrains, UNLIMITED_TRAINS)).sum();
		if (held <= 0) {
			return all;
		}

		// Each route's services on the same days, in the order they leave in the operating day
		final Map<String, List<ServiceInput>> groups = new TreeMap<>();
		services.forEach(service -> groups.computeIfAbsent(service.routeId + "/" + service.weekdays, key -> new ArrayList<>()).add(service));
		groups.values().forEach(group -> group.sort(Comparator.comparingLong((ServiceInput service) -> service.startMillis).thenComparingInt(service -> service.service)));
		final int longest = groups.values().stream().mapToInt(List::size).max().orElse(1);

		final int needed = neededTrains(services, sidings, stationOf, maxLayoverMillis);
		// Up one at a time while k is small, then by an eighth, so a long day of services is planned only a few dozen times
		for (int k = Math.max(2, (needed + held - 1) / held); k <= longest; k += Math.max(1, k / 8)) {
			final List<ServiceInput> kept = new ArrayList<>();
			int left = 0;
			for (final List<ServiceInput> group : groups.values()) {
				for (int i = 0; i < group.size(); i++) {
					if (i % k == 0) {
						kept.add(group.get(i));
					} else {
						left += Integer.bitCount(group.get(i).weekdays & DepotDuties.ALL_WEEKDAYS);
					}
				}
			}
			final Result result = generate(kept, sidings, stationOf, maxLayoverMillis);
			if (result.uncovered == 0) {
				result.uncovered = left;
				return result;
			}
		}
		// Not even one service a route has a train for every kept one: as many as the trains can take, early first
		return all;
	}

	/**
	 * @return how many trains the services need at most when the sidings have as many as they like, to compare with what
	 * they have
	 */
	public static int neededTrains(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis) {
		final List<SidingInput> unlimited = new ArrayList<>();
		sidings.forEach(siding -> unlimited.add(new SidingInput(siding.sidingId, -1, siding.links)));
		return generate(services, unlimited, stationOf, maxLayoverMillis).trains;
	}

	public static final class SidingInput {

		public final long sidingId;
		/**
		 * The trains the siding holds, or -1 for no limit.
		 */
		public final int maxTrains;
		/**
		 * The platforms its trains can start at and how long the run there takes, or null for every first stop at once.
		 * Planning by operating line, a siding starts at the first stops of its own depot's routes, and the nearer siding
		 * is taken first.
		 */
		public final Map<Long, Long> links;
		/**
		 * The depot of the siding, for the services set to be sent out of a depot, or 0 if not known.
		 */
		public final long depotId;

		public SidingInput(long sidingId, int maxTrains) {
			this(sidingId, maxTrains, null);
		}

		public SidingInput(long sidingId, int maxTrains, Map<Long, Long> links) {
			this(sidingId, maxTrains, links, 0);
		}

		public SidingInput(long sidingId, int maxTrains, Map<Long, Long> links, long depotId) {
			this.sidingId = sidingId;
			this.maxTrains = maxTrains;
			this.links = links;
			this.depotId = depotId;
		}
	}

	public static final class Result {

		public final Map<Long, List<DepotDuties.Slot>> sidings = new TreeMap<>();
		public final List<String> issues = new ArrayList<>();
		public int trains;
		/**
		 * Trips no train was free for, which the sidings need more trains to run.
		 */
		public int uncovered;
	}
}
