package mtr.advanced;

import transitsim.planning.DutyPlanner;
import transitsim.planning.OperationNetwork;
import transitsim.planning.OperationPlan;
import transitsim.planning.OperationTime;
import transitsim.planning.TimetableService;

import java.util.*;

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
			timetableServices.add(chains.get(i).toTimetableService(i));
		}

		final OperationNetwork network = new OperationNetwork();
		sidings.forEach(siding -> network.addDepot(siding.sidingId, siding.maxTrains < 0 ? UNLIMITED_TRAINS : Math.min(siding.maxTrains, UNLIMITED_TRAINS)));
		final Set<Long> firstPlatforms = new TreeSet<>();
		final Set<Long> lastPlatforms = new TreeSet<>();
		timetableServices.forEach(service -> {
			firstPlatforms.add(service.stops.get(0).platformId);
			lastPlatforms.add(service.stops.get(service.stops.size() - 1).platformId);
		});
		sidings.forEach(siding -> firstPlatforms.forEach(platformId -> network.addDepotLink(siding.sidingId, platformId, 0)));
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
			for (int i = 0; i < MAX_LINKS && current.linkRoute != 0 && days != 0; i++) {
				final ServiceInput next = findLinked(byService, current, days);
				if (next == null || next == service) {
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
			chain.add(service, 0);
			ServiceInput current = service;
			for (int i = 0; i < MAX_LINKS && current.linkRoute != 0; i++) {
				final ServiceInput next = findLinked(byService, current, chain.weekdays);
				if (next == null || next == service || (chain.weekdays & next.weekdays) == 0) {
					break;
				}
				chain.weekdays &= next.weekdays;
				chain.cut(current.linkFromStop);
				chain.add(next, current.linkToStop);
				current = next;
			}
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

	public static final class SidingInput {

		public final long sidingId;
		/**
		 * The trains the siding holds, or -1 for no limit.
		 */
		public final int maxTrains;

		public SidingInput(long sidingId, int maxTrains) {
			this.sidingId = sidingId;
			this.maxTrains = maxTrains;
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
