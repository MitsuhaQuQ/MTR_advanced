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
	 * The least time a train takes to change ends where it turns back (换端最短时间, optimisation round 4), unless the
	 * network sets another ({@link PlanInputs#minReverseMillis}).
	 */
	public static final long MIN_TURNAROUND_MILLIS = 30000;
	/**
	 * The time to turn back onto another platform of the same station before the depot's paths have been found, when
	 * nothing has measured it on the rails yet; once measured, the measured time is used.
	 */
	public static final long TURN_BACK_MILLIS = 120000;
	/**
	 * In the measured changes of platform ({@link PlanInputs#turnTimes}), the key under a platform for how long a train takes
	 * from it into the turn back rail beyond it (the in leg, round 4); the platform's own key holds the way in and back out
	 * together, so the out leg is the rest. No platform has this ID.
	 */
	public static final long HOLD_IN_KEY = Long.MIN_VALUE;
	/**
	 * In {@link PlanInputs#turnTimes}, from a platform: how many trains can wait beyond it at once, on its turn back rails
	 * and stabling blocks (运用补完 step 4); one where not given.
	 */
	public static final long HOLD_COUNT_KEY = Long.MIN_VALUE + 1;
	/**
	 * In a platform's turn times, which turn back rail it waits on, as a number the same for every platform turning on it.
	 */
	public static final long HOLD_RAIL_KEY = Long.MIN_VALUE + 2;
	private static final int MAX_LINKS = 8;
	private static final int UNLIMITED_TRAINS = DepotDuties.MAX_SLOTS;
	private static final int MAX_SPREAD_OFFSETS = 8;

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
				List<StopRules.StopTime> standardTimes = null;
				for (final int service : table.services) {
					// A service that doesn't run has nothing planned for it
					if (table.suspended.contains(service)) {
						continue;
					}
					final Map<Integer, transitsim.plan.ServiceExceptions.StopException> exceptions = table.exceptions.get(service);
					final List<StopRules.StopTime> stopTimes;
					if (exceptions == null || exceptions.isEmpty()) {
						if (standardTimes == null) standardTimes = StopRules.times(route.getStops(), times, table.marginPercent, i, null);
						stopTimes = standardTimes;
					} else {
						stopTimes = StopRules.times(route.getStops(), times, table.marginPercent, i, exceptions);
					}
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
		return generate(services, sidings, stationOf, maxLayoverMillis, PlanInputs.NONE);
	}

	/**
	 * What path generation measured on the rails, for planning by it rather than by estimates (decoupling document,
	 * plans by measured data): how long each change of platform within a station takes, where the rails allow one at
	 * all, and how long each siding's trains take back from a platform.
	 */
	public static final class PlanInputs {

		public static final PlanInputs NONE = new PlanInputs(null, Collections.emptyMap());
		/**
		 * From platform → to platform of the same station → milliseconds; a pair left out has no way between. Null to
		 * estimate every change of platform within a station as possible.
		 */
		public final Map<Long, Map<Long, Long>> turnTimes;
		/**
		 * Siding → platform → milliseconds back to the siding, or {@link OperationNetwork#UNREACHABLE} where path generation
		 * found no way back.
		 */
		public final Map<Long, Map<Long, Long>> backLinks;
		/**
		 * The trains each pool has in all ({@link SidingInput#poolKey}, P2-C, R3), which its sidings start the week with
		 * between them; a pool left out has each siding's.
		 */
		public final Map<String, Integer> poolFleets;
		/**
		 * How much longer than planned trains were measured to take out, round and back (D1), added to each.
		 */
		public final transitsim.plan.Buffers buffers;
		/**
		 * The least time a train takes to change ends where it turns back (换端最短时间, round 4): at the platform, on
		 * the turn back rail, or at another platform of the station. A network setting.
		 */
		public final long minReverseMillis;
		/**
		 * Platform → from how long a wait a train waits on the turn back rail beyond it (运用补完 step 2), where set.
		 */
		public final Map<Long, Long> waitThresholds;

		public PlanInputs(Map<Long, Map<Long, Long>> turnTimes, Map<Long, Map<Long, Long>> backLinks) {
			this(turnTimes, backLinks, Collections.emptyMap());
		}

		public PlanInputs(Map<Long, Map<Long, Long>> turnTimes, Map<Long, Map<Long, Long>> backLinks, Map<String, Integer> poolFleets) {
			this(turnTimes, backLinks, poolFleets, transitsim.plan.Buffers.NONE);
		}

		public PlanInputs(Map<Long, Map<Long, Long>> turnTimes, Map<Long, Map<Long, Long>> backLinks, Map<String, Integer> poolFleets, transitsim.plan.Buffers buffers) {
			this(turnTimes, backLinks, poolFleets, buffers, MIN_TURNAROUND_MILLIS);
		}

		public PlanInputs(Map<Long, Map<Long, Long>> turnTimes, Map<Long, Map<Long, Long>> backLinks, Map<String, Integer> poolFleets, transitsim.plan.Buffers buffers, long minReverseMillis) {
			this(turnTimes, backLinks, poolFleets, buffers, minReverseMillis, Collections.emptyMap());
		}

		public PlanInputs(Map<Long, Map<Long, Long>> turnTimes, Map<Long, Map<Long, Long>> backLinks, Map<String, Integer> poolFleets, transitsim.plan.Buffers buffers, long minReverseMillis, Map<Long, Long> waitThresholds) {
			this.waitThresholds = waitThresholds == null ? Collections.emptyMap() : waitThresholds;
			this.minReverseMillis = Math.max(0, minReverseMillis);
			this.turnTimes = turnTimes;
			this.backLinks = backLinks == null ? Collections.emptyMap() : backLinks;
			this.poolFleets = poolFleets == null ? Collections.emptyMap() : poolFleets;
			this.buffers = buffers == null ? transitsim.plan.Buffers.NONE : buffers;
		}

		/**
		 * @return the same with no pool held to a number of trains, to plan as if trains were no limit
		 */
		/** A stock-increase probe retains the pool and raises only that pool's explicit fleet. */
		public PlanInputs withAdditionalPoolTrains(String pool, int added) {
			final Integer current = pool == null ? null : poolFleets.get(pool);
			if (added <= 0 || current == null || current < 0) return this;
			final Map<String, Integer> changed = new HashMap<>(poolFleets);
			changed.put(pool, (int) Math.min(Integer.MAX_VALUE, (long) current + added));
			return new PlanInputs(turnTimes, backLinks, changed, buffers, minReverseMillis, waitThresholds);
		}

		public PlanInputs withoutPoolFleets() {
			return poolFleets.isEmpty() ? this : new PlanInputs(turnTimes, backLinks, Collections.emptyMap(), buffers, minReverseMillis, waitThresholds);
		}
	}

	public static Result generate(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		return generate(services, sidings, stationOf, maxLayoverMillis, inputs, true);
	}

	/** Capacity probes use exactly the same solver, without materializing duty slots. */
	private static Result generate(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs, boolean materialize) {
		final Result result = new Result();
		if (services.isEmpty() || sidings.isEmpty()) {
			return result;
		}

		final List<Chain> chains = buildChains(services);
		final List<TimetableService> timetableServices = timetableServicesOf(chains, sidings, result.issues);
		final OperationNetwork network = networkOf(timetableServices, sidings, stationOf, inputs);

		final OperationPlan plan = DutyPlanner.plan(timetableServices, Collections.emptyList(), network, maxLayoverMillis);
		plan.issues.forEach(issue -> {
			if (issue.type == OperationPlan.IssueType.NO_TRAIN) {
				result.uncovered++;
			} else if (issue.type != OperationPlan.IssueType.UNUSED_BLANK) {
				result.issues.add(issue.type + " " + issue.detail);
			}
		});
		result.trains = plan.peakTrains;
		if (materialize) assignSlots(plan, chains, sidings, result);
		return result;
	}

	/**
	 * The chains as the planner's services, each with the sidings it has to come out of or go back to where the running
	 * diagram sets them; what can't be kept to is told in the issues.
	 */
	static List<TimetableService> timetableServicesOf(List<Chain> chains, List<SidingInput> sidings, List<String> issues) {
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
				// A depot set by the player is kept to: with no siding of it here the service has no train, rather than one
				// from anywhere (P1-A)
				if (startDepots.isEmpty()) {
					issues.add("DEPLOY_DEPOT " + chain.deployDepot + " has no siding for " + chain.segments.get(0).routeId + "@" + chain.segments.get(0).service);
				}
				timetableService.startDepots = startDepots;
			}
			timetableService.endsDuty = chain.endsDuty;
			// Set to go back to a depot: to its sidings only, and with none of them here, no train runs it (P1-B)
			if (chain.endsDuty && chain.returnDepot > 0) {
				final Set<Long> returnDepots = new TreeSet<>();
				sidings.forEach(siding -> {
					if (siding.depotId == chain.returnDepot) {
						returnDepots.add(siding.sidingId);
					}
				});
				if (returnDepots.isEmpty()) {
					issues.add("RETURN_DEPOT " + chain.returnDepot + " has no siding for " + chain.segments.get(0).routeId + "@" + chain.segments.get(0).service);
				}
				timetableService.returnDepots = returnDepots;
			}
			timetableServices.add(timetableService);
		}
		return timetableServices;
	}

	/**
	 * What the planner plans on: the sidings and their trains, the ways out and back, changing ends and the changes of
	 * platform within a station, as measured or set. The same for planning and for checking duties already planned
	 * ({@link DutyCheck}).
	 */
	static OperationNetwork networkOf(List<TimetableService> timetableServices, List<SidingInput> sidings, Map<Long, Long> stationOf, PlanInputs inputs) {
		final OperationNetwork network = new OperationNetwork();
		if (stationOf != null) {
			stationOf.forEach(network::setStation);
		}
		sidings.forEach(siding -> network.addDepot(siding.sidingId, siding.maxTrains < 0 ? UNLIMITED_TRAINS : Math.min(siding.maxTrains, UNLIMITED_TRAINS), siding.poolKey));
		inputs.poolFleets.forEach(network::setPoolFleet);
		inputs.waitThresholds.forEach(network::setWaitThreshold);
		final Set<Long> firstPlatforms = new TreeSet<>();
		final Set<Long> lastPlatforms = new TreeSet<>();
		// Platforms only loops end at, where they started: a train going round again doesn't change ends (2026-11-09)
		final Set<Long> notLoopEnds = new TreeSet<>();
		timetableServices.forEach(service -> {
			final long first = service.stops.get(0).platformId;
			final long last = service.stops.get(service.stops.size() - 1).platformId;
			firstPlatforms.add(first);
			lastPlatforms.add(last);
			if (first != last) {
				notLoopEnds.add(last);
			}
		});
		// A siding with links of its own starts its trains only there, the nearest first; otherwise at any first stop
		sidings.forEach(siding -> {
			if (siding.links == null) {
				firstPlatforms.forEach(platformId -> network.addDepotLink(siding.sidingId, platformId, 0));
			} else {
				siding.links.forEach((platformId, millis) -> {
					if (firstPlatforms.contains(platformId)) {
						network.addDepotLink(siding.sidingId, platformId, millis + inputs.buffers.out(siding.sidingId, platformId));
						final Long passed = siding.passes == null ? null : siding.passes.get(platformId);
						if (passed != null) {
							network.addDepotPassing(siding.sidingId, platformId, passed);
						}
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
					network.addDepotLink(siding.sidingId, platformId, siding.links.values().stream().mapToLong(Long::longValue).min().orElse(0) + inputs.buffers.out(siding.sidingId, platformId));
				}
			});
		});
		// The way back to each siding, as measured from each platform
		sidings.forEach(siding -> inputs.backLinks.getOrDefault(siding.sidingId, Collections.emptyMap()).forEach((platformId, millis) -> network.addDepotBackLink(siding.sidingId, platformId, millis == OperationNetwork.UNREACHABLE ? millis : millis + inputs.buffers.back(platformId, siding.sidingId))));
		lastPlatforms.forEach(lastPlatform -> {
			// Changing ends takes the network's least time, wherever the train turns back (round 4); a loop's train only goes
			// round again, standing as long as the timetable has it
			network.setTurnaround(lastPlatform, (notLoopEnds.contains(lastPlatform) ? inputs.minReverseMillis : 0) + inputs.buffers.turn(lastPlatform, lastPlatform));
			// Measured from the platform back to itself by way of a turn back rail beyond it: a train may wait there (混合折返),
			// running in and out as long as each leg was measured; halves where only the whole was measured
			final Map<Long, Long> fromHere = inputs.turnTimes == null ? Collections.emptyMap() : inputs.turnTimes.getOrDefault(lastPlatform, Collections.emptyMap());
			final Long holdMillis = fromHere.get(lastPlatform);
			if (holdMillis != null) {
				final Long inMillis = fromHere.get(HOLD_IN_KEY);
				final long in = inMillis == null ? holdMillis / 2 : Math.min(inMillis, holdMillis);
				final Long count = fromHere.get(HOLD_COUNT_KEY);
				final Long rail = fromHere.get(HOLD_RAIL_KEY);
				network.setTurnBackHold(lastPlatform, in, holdMillis - in, count == null ? 1 : (int) Math.min(count, Integer.MAX_VALUE), rail == null ? 0 : rail);
			}
			final Long station = stationOf.get(lastPlatform);
			firstPlatforms.forEach(firstPlatform -> {
				if (firstPlatform.longValue() != lastPlatform && station != null && station.equals(stationOf.get(firstPlatform))) {
					// With how much longer trains were measured to take round (D1)
					final long buffer = inputs.buffers.turn(lastPlatform, firstPlatform);
					if (inputs.turnTimes == null) {
						network.addConnection(lastPlatform, firstPlatform, TURN_BACK_MILLIS + buffer);
					} else {
						// Measured on the rails; a pair not measured has no way between
						final Long millis = inputs.turnTimes.getOrDefault(lastPlatform, Collections.emptyMap()).get(firstPlatform);
						if (millis != null) {
							network.addConnection(lastPlatform, firstPlatform, millis + buffer);
						}
					}
				}
			});
		});
		return network;
	}

	/**
	 * A service and the services its through links carry it on to, as one trip. A linked service runs as part of the
	 * trip on the days both run, and on its own on the other days.
	 */
	static List<Chain> buildChains(List<ServiceInput> services) {
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
			chain.returnDepot = current.returnStop >= 0 ? current.returnDepot : -1;
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
				for (int k = 0; k < duty.trips.size(); k++) {
					final OperationPlan.Trip trip = duty.trips.get(k);
					final int day = (int) Math.floorMod(trip.startMillis() / OperationTime.DAY_MILLIS, 7L);
					final Piece piece = pieces.computeIfAbsent(day, key -> new Piece());
					final List<DepotDuties.Segment> segments = chains.get((int) trip.sourceId).segments;
					// Where the train waits for this trip, after the one before on the same day; none between the parts of a
					// service run through (运用补完 step 1)
					if (!piece.segments.isEmpty()) {
						piece.waits.add(k > 0 && k - 1 < duty.waits.size() ? duty.waits.get(k - 1) : DepotDuties.DayDuty.WAIT_ANY);
					}
					for (int j = 1; j < segments.size(); j++) {
						piece.waits.add(DepotDuties.DayDuty.WAIT_ANY);
					}
					piece.segments.addAll(segments);
				}
				final List<Integer> pieceDays = new ArrayList<>(pieces.keySet());
				for (int i = 0; i + 1 < pieceDays.size(); i++) {
					pieces.get(pieceDays.get(i)).stayOnLine = true;
				}
				// Out of the siding the planned time before the first trip, and back to the siding planned
				if (!pieceDays.isEmpty()) {
					if (duty.previousDuty == OperationPlan.NONE) {
						pieces.get(pieceDays.get(0)).outLeadMillis = duty.leaveMillis;
					}
					if (duty.nextDuty == OperationPlan.NONE) {
						pieces.get(pieceDays.get(pieceDays.size() - 1)).returnSidingId = duty.returnDepotId;
					}
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
				// Going back to another siding of its pool, as the runtime would send it
				if (duty.returnDepotId != siding.sidingId && duty.nextDuty == OperationPlan.NONE && !pieceDays.isEmpty()) {
					result.returnSidings.put(siding.sidingId + "/" + slot + "/" + pieceDays.get(pieceDays.size() - 1), duty.returnDepotId);
				}
			}

			final int slotCount = days.stream().mapToInt(day -> day.isEmpty() ? 0 : Collections.max(day.keySet()) + 1).max().orElse(0);
			final List<DepotDuties.Slot> slots = new ArrayList<>();
			for (int slotIndex = 0; slotIndex < Math.min(slotCount, DepotDuties.MAX_SLOTS); slotIndex++) {
				final DepotDuties.Slot slot = new DepotDuties.Slot();
				// Its own number, the same as long as the plan gives the siding the same slots (P2-D)
				slot.id = DepotDuties.defaultId(siding.sidingId, slotIndex);
				for (int day = 0; day < 7; day++) {
					final Piece piece = days.get(day).get(slotIndex);
					if (piece == null) {
						continue;
					}
					final DepotDuties.DayDuty dayDuty = new DepotDuties.DayDuty();
					dayDuty.stayOnLine = piece.stayOnLine;
					final int segmentCount = Math.min(piece.segments.size(), DepotDuties.MAX_SEGMENTS);
					dayDuty.segments.addAll(piece.segments.subList(0, segmentCount));
					dayDuty.waits.addAll(piece.waits.subList(0, Math.min(piece.waits.size(), Math.max(0, segmentCount - 1))));
					dayDuty.outLeadMillis = piece.outLeadMillis;
					dayDuty.returnSidingId = piece.returnSidingId;
					final DepotDuties.DayDuty same = slot.days.stream().filter(other -> other.sameAs(dayDuty) && other.returnSidingId == dayDuty.returnSidingId).findFirst().orElse(null);
					if (same == null) {
						dayDuty.weekdays = 1 << day;
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
		private final List<Integer> waits = new ArrayList<>();
		private boolean stayOnLine;
		private long outLeadMillis = -1;
		private long returnSidingId;
	}

	static final class Chain {

		int weekdays;
		/**
		 * The depot a train for it has to come out of, 0 for any, or -1 if it may be any train.
		 */
		private long deployDepot = -1;
		private boolean endsDuty;
		/**
		 * The depot the train goes back to after it, 0 for any, or -1 if it doesn't end the duty.
		 */
		private long returnDepot = -1;
		long startMillis = -1;
		final List<DepotDuties.Segment> segments = new ArrayList<>();
		final List<TimetableService.Stop> stops = new ArrayList<>();
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

		/**
		 * @return the same service on only these of its days
		 */
		public ServiceInput withWeekdays(int newWeekdays) {
			final ServiceInput copy = new ServiceInput(routeId, service, newWeekdays, startMillis, platformIds, plans, pass, linkRoute, linkService, linkFromStop, linkToStop);
			copy.deployStop = deployStop;
			copy.deployDepot = deployDepot;
			copy.returnStop = returnStop;
			copy.returnDepot = returnDepot;
			return copy;
		}

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
		return generateSpread(services, sidings, stationOf, maxLayoverMillis, PlanInputs.NONE);
	}

	public static Result generateSpread(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		final Result all = generate(services, sidings, stationOf, maxLayoverMillis, inputs);
		if (all.uncovered == 0 || sidings.stream().anyMatch(siding -> siding.maxTrains < 0)) {
			return all;
		}
		final int held = sidings.stream().mapToInt(siding -> Math.min(siding.maxTrains, UNLIMITED_TRAINS)).sum();
		if (held <= 0) {
			return all;
		}
		// Only where more trains would run more services: a service no train can get to, as one starting where no siding
		// reaches and no train turns, isn't a matter of trains, and spreading for it left most services without a train on
		// a player's line with 256 trains (2026-11-09)
		final int unrunnable = unrunnable(services, sidings, stationOf, maxLayoverMillis, inputs);
		all.unrunnable = unrunnable;
		if (unrunnable >= all.uncovered) {
			return all;
		}

		// Each route's services on the same days, in the order they leave in the operating day
		final Map<String, List<ServiceInput>> groups = new TreeMap<>();
		services.forEach(service -> groups.computeIfAbsent(service.routeId + "/" + service.weekdays, key -> new ArrayList<>()).add(service));
		groups.values().forEach(group -> group.sort(Comparator.comparingLong((ServiceInput service) -> service.startMillis).thenComparingInt(service -> service.service)));
		final int longest = groups.values().stream().mapToInt(List::size).max().orElse(1);

		// Up one at a time while k is small, then by an eighth, so a long day of services is planned only a few dozen times
		// From every other service up: what's needed over what's held is only a guess, as trains running fewer services can
		// often turn round for them where they couldn't for every one
		final List<List<ServiceInput>> groupList = new ArrayList<>(groups.values());
		for (int k = 2; k <= longest; k += k < 16 ? 1 : Math.max(1, k / 8)) {
			// The other routes' kept services may fall better a service or two along: each offset is tried, the first
			// route's staying put, so a train coming back can take the next one
			for (int offset = 0; offset < Math.min(k, MAX_SPREAD_OFFSETS); offset++) {
				final List<ServiceInput> kept = new ArrayList<>();
				int left = 0;
				for (int g = 0; g < groupList.size(); g++) {
					final List<ServiceInput> group = groupList.get(g);
					final int shift = g == 0 ? 0 : offset;
					for (int i = 0; i < group.size(); i++) {
						if (Math.floorMod(i - shift, k) == 0) {
							kept.add(group.get(i));
						} else {
							left += Integer.bitCount(group.get(i).weekdays & DepotDuties.ALL_WEEKDAYS);
						}
					}
				}
				final Result result = generate(kept, sidings, stationOf, maxLayoverMillis, inputs);
				// No more without a train than no number of trains could run
				if (result.uncovered <= unrunnable) {
					result.uncovered += left;
					result.unrunnable = unrunnable;
					return result;
				}
			}
		}
		// Not even one service a route has a train for every kept one: as many as the trains can take, early first
		return all;
	}

	/**
	 * The days that run the same services, as by one timetable of each route (duty groups, step 2), each as weekdays (bit 0
	 * Monday), in the order of their first day; days with no service are left out.
	 */
	public static List<Integer> dayTypes(List<ServiceInput> services) {
		final Map<List<Integer>, Integer> byServices = new LinkedHashMap<>();
		for (int day = 0; day < 7; day++) {
			final List<Integer> running = new ArrayList<>();
			for (int i = 0; i < services.size(); i++) {
				if ((services.get(i).weekdays >> day & 1) != 0) {
					running.add(i);
				}
			}
			if (!running.isEmpty()) {
				byServices.merge(running, 1 << day, (a, b) -> a | b);
			}
		}
		return new ArrayList<>(byServices.values());
	}

	/**
	 * Plans each day type ({@link #dayTypes}) on its own, as a group of duties of its own (duty groups, step 2, option B),
	 * with {@link #generateSpread}, and puts the groups together: a train's slot runs the duty of its index in each group.
	 * Trains don't stay on the line from one day type into another, nor over a whole day; from one day of a type to the next
	 * they do as before. With one day type it is
	 * {@link #generateSpread}'s result.
	 */
	public static Result generateByDayType(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		return generateByDayType(services, sidings, stationOf, maxLayoverMillis, inputs, true);
	}

	/**
	 * @param spread with {@link #generateSpread} for each type, else with {@link #generate}
	 */
	public static Result generateByDayType(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs, boolean spread) {
		final List<Integer> types = dayTypes(services);
		if (types.size() <= 1) {
			return spread ? generateSpread(services, sidings, stationOf, maxLayoverMillis, inputs) : generate(services, sidings, stationOf, maxLayoverMillis, inputs);
		}
		final Result merged = new Result();
		for (final int mask : types) {
			final Result result = generateDayType(services, mask, sidings, stationOf, maxLayoverMillis, inputs, spread);
			merged.trains = Math.max(merged.trains, result.trains);
			merged.uncovered += result.uncovered;
			result.issues.stream().filter(issue -> !merged.issues.contains(issue)).forEach(merged.issues::add);
			merged.returnSidings.putAll(result.returnSidings);
			result.sidings.forEach((sidingId, slots) -> {
				final List<DepotDuties.Slot> mergedSlots = merged.sidings.computeIfAbsent(sidingId, key -> new ArrayList<>());
				for (int i = 0; i < slots.size(); i++) {
					while (mergedSlots.size() <= i) {
						final DepotDuties.Slot slot = new DepotDuties.Slot();
						slot.id = DepotDuties.defaultId(sidingId, mergedSlots.size());
						mergedSlots.add(slot);
					}
					for (final DepotDuties.DayDuty day : slots.get(i).days) {
						// Only its own type's days, which no other type has
						day.weekdays &= mask;
						if (day.weekdays != 0) {
							mergedSlots.get(i).days.add(day);
						}
					}
				}
			});
		}
		// In the order of the weekdays, as one plan gives them
		merged.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.sort(Comparator.comparingInt(day -> Integer.numberOfTrailingZeros(day.weekdays)))));
		return merged;
	}

	/**
	 * @return the services running on these days, on only these of their days
	 */
	public static List<ServiceInput> onDays(List<ServiceInput> services, int mask) {
		final List<ServiceInput> part = new ArrayList<>();
		services.forEach(service -> {
			if ((service.weekdays & mask) != 0) {
				part.add(service.weekdays == (service.weekdays & mask) ? service : service.withWeekdays(service.weekdays & mask));
			}
		});
		return part;
	}

	/**
	 * Plans one day type ({@link #dayTypes}) as a group of duties of its own: the services on those days. A train may stay
	 * out overnight to the next day, not over a whole day, as the days between are another type's, whose duties start from
	 * the sidings.
	 */
	public static Result generateDayType(List<ServiceInput> services, int mask, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs, boolean spread) {
		final List<ServiceInput> part = onDays(services, mask);
		final long layover = layoverOf(mask, maxLayoverMillis);
		return spread ? generateSpread(part, sidings, stationOf, layover, inputs) : generate(part, sidings, stationOf, layover, inputs);
	}

	/**
	 * @return the longest a train may stay out planning a day type: over no whole day unless the type is the whole week
	 */
	public static long layoverOf(int mask, long maxLayoverMillis) {
		return (mask & DepotDuties.ALL_WEEKDAYS) == DepotDuties.ALL_WEEKDAYS ? maxLayoverMillis : Math.min(maxLayoverMillis, OperatingDay.MILLIS_PER_DAY);
	}

	/**
	 * @return how many trains the services need at most when the sidings have as many as they like, to compare with what
	 * they have
	 */
	public static int neededTrains(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis) {
		return neededTrains(services, sidings, stationOf, maxLayoverMillis, PlanInputs.NONE);
	}

	public static int neededTrains(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		return generate(services, unlimited(sidings), stationOf, maxLayoverMillis, inputs.withoutPoolFleets(), false).trains;
	}

	/**
	 * @return how many of the services no number of trains could run, counted once for each day they run: those no siding
	 * reaches and no train can turn to, which only a way out or round could change
	 */
	public static int unrunnable(List<ServiceInput> services, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		return generate(services, unlimited(sidings), stationOf, maxLayoverMillis, inputs.withoutPoolFleets(), false).uncovered;
	}

	/**
	 * @return the sidings with as many trains as they like
	 */
	private static List<SidingInput> unlimited(List<SidingInput> sidings) {
		final List<SidingInput> unlimited = new ArrayList<>();
		sidings.forEach(siding -> {
			unlimited.add(siding.withCapacity(-1));
		});
		return unlimited;
	}

	public static int uncoveredOnDayType(List<ServiceInput> services, int mask, List<SidingInput> sidings, Map<Long, Long> stationOf, long maxLayoverMillis, PlanInputs inputs) {
		return generate(onDays(services, mask), sidings, stationOf, layoverOf(mask, maxLayoverMillis), inputs, false).uncovered;
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
		/**
		 * The sidings whose trains run each other's duties and go back to whichever needs a train next, as one pool of the
		 * runtime (same planning unit and vehicle); null for a pool of its own.
		 */
		public String poolKey;
		/**
		 * First platform → the platform its way out runs through just before turning back into it, or null for none
		 * ({@link OperationNetwork#addDepotPassing}).
		 */
		public Map<Long, Long> passes;

		/** Change capacity without changing vehicle-pool membership or access rules. */
		public SidingInput withCapacity(int capacity) {
			final SidingInput copy = new SidingInput(sidingId, capacity, links, depotId);
			copy.poolKey = poolKey;
			copy.passes = passes;
			return copy;
		}

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
		/** Unlimited-capacity coverage already measured for these services; -1 when not evaluated. */
		public int unrunnable = -1;
		/**
		 * Where a duty's train goes back as the runtime would send it, when not to its own siding: siding/slot/weekday
		 * (0 Monday) of the duty's last day → the siding. Worked out, not kept; the runtime chooses as it runs.
		 */
		public final Map<String, Long> returnSidings = new TreeMap<>();
	}
}
