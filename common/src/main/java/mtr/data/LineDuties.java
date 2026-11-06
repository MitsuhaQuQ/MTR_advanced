package mtr.data;

import net.minecraft.core.BlockPos;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;
import transitsim.planning.DutyGenerator;

import java.util.*;
import java.util.function.Function;

/**
 * Vehicle working planned by operating line rather than by depot (decoupling document 5.4, after L1). An operating line
 * is a route group ({@code lineKey}: transport mode and {@link Route#getLineKey}), both directions, every type of service
 * and branches. The depots running by a timetable with no duties set, and the lines they run, joined through the routes
 * they list and the through links between services, make one planning unit, planned at once: a service is planned once
 * for the whole unit, whichever depot lists its route, and a depot listing only one direction of a line runs the other
 * direction too, as its trains start at the first stops of its own routes and turn back along the line.
 * <p>
 * Each depot of a unit works the unit out on its path generation thread and keeps the slots of its own sidings; the
 * planner gives the same duties for the same unit, so the depots agree.
 */
public final class LineDuties {

	/**
	 * How long a train takes to run a block to its first stop, for choosing the nearest siding: 20 blocks a second.
	 */
	private static final long MILLIS_PER_BLOCK = 50;

	/**
	 * @return the trains each pool of the sidings has in all (P2-C, R3): what each depot gives it, or else its sidings'
	 * fleets added up; a pool one of whose depots has as many as needed is left out
	 */
	public static Map<String, Integer> poolFleetsOf(List<DutyGenerator.SidingInput> sidings, java.util.function.LongFunction<Depot> depotOf) {
		// What each depot gives a pool, or each siding's fleet where it gives none, added up as the component adds them (S7)
		final Map<String, List<Integer>> fleets = new HashMap<>();
		final Set<String> counted = new HashSet<>();
		sidings.forEach(siding -> {
			if (siding.poolKey == null) {
				return;
			}
			final Depot depot = depotOf.apply(siding.sidingId);
			final Integer set = depot == null ? null : depot.poolFleets.get(siding.poolKey);
			if (set == null || counted.add(depot.id + "/" + siding.poolKey)) {
				fleets.computeIfAbsent(siding.poolKey, key -> new ArrayList<>()).add(set == null ? siding.maxTrains : set);
			}
		});
		final Map<String, Integer> totals = new HashMap<>();
		fleets.forEach((poolKey, poolFleets) -> {
			final int total = transitsim.sim.FleetRules.sumFleets(poolFleets);
			if (total >= 0) {
				totals.put(poolKey, total);
			}
		});
		return totals;
	}

	/**
	 * How long the siding's trains take out to each of the platforms: as kept where measuring found the estimate off
	 * ({@link Siding#getRecordedLegs}), otherwise the estimate.
	 */
	public static Map<Long, Long> sidingLinks(Siding siding, Collection<Long> platformIds, DataCache dataCache) {
		// No way out found there: the siding doesn't start trains there (P2-A)
		final Set<Long> reachable = new TreeSet<>(platformIds);
		reachable.removeAll(siding.getRecordedLegs().unreachableOut);
		return legsOrEstimates(siding, siding.getRecordedLegs().outMillis, reachable, dataCache);
	}

	/**
	 * How long the siding's trains take back from each of the platforms, the same way.
	 */
	public static Map<Long, Long> sidingBackLinks(Siding siding, Collection<Long> platformIds, DataCache dataCache) {
		final Map<Long, Long> links = legsOrEstimates(siding, siding.getRecordedLegs().backMillis, platformIds, dataCache);
		// No way back found from there: the planner sends its trains to another siding of the pool (P2-A)
		siding.getRecordedLegs().unreachableBack.forEach(platformId -> links.put(platformId, transitsim.planning.OperationNetwork.UNREACHABLE));
		return links;
	}

	/**
	 * @return each siding's way back from each of the platforms
	 */
	public static Map<Long, Map<Long, Long>> backLinksOf(Collection<Siding> sidings, Collection<Long> platformIds, DataCache dataCache) {
		final Map<Long, Map<Long, Long>> backLinks = new TreeMap<>();
		sidings.forEach(siding -> backLinks.put(siding.id, sidingBackLinks(siding, platformIds, dataCache)));
		return backLinks;
	}

	/**
	 * An estimate of how long a train takes between the siding and the platform, out or back: from how far apart they
	 * are, half as much again with room for leaving the siding and the bends of the rails.
	 */
	public static long estimateMillis(Siding siding, long platformId, DataCache dataCache) {
		final Platform platform = dataCache.platformIdMap.get(platformId);
		return platform == null ? ESTIMATE_MARGIN_MILLIS : (long) (Math.sqrt(siding.getMidPos().distSqr(platform.getMidPos())) * MILLIS_PER_BLOCK * 3 / 2) + ESTIMATE_MARGIN_MILLIS;
	}

	private static Map<Long, Long> legsOrEstimates(Siding siding, Map<Long, Long> kept, Collection<Long> platformIds, DataCache dataCache) {
		final Map<Long, Long> links = new TreeMap<>();
		platformIds.forEach(platformId -> {
			if (dataCache.platformIdMap.containsKey(platformId)) {
				final Long recorded = kept.get(platformId);
				links.put(platformId, recorded != null ? recorded : estimateMillis(siding, platformId, dataCache));
			}
		});
		return links;
	}

	/**
	 * @return the changes of platform measured for the depots, merged, or null with none measured yet, to estimate them
	 */
	public static Map<Long, Map<Long, Long>> turnTimesOf(Collection<Depot> depots) {
		final Map<Long, Map<Long, Long>> turnTimes = new TreeMap<>();
		depots.forEach(depot -> depot.clientTurnTimes.forEach((from, times) -> turnTimes.computeIfAbsent(from, key -> new TreeMap<>()).putAll(times)));
		return depots.stream().anyMatch(depot -> depot.turnTimesMeasured) ? turnTimes : null;
	}
	/**
	 * Added to a way out estimated from how far it is, for leaving the siding and the bends of the rails.
	 */
	private static final long ESTIMATE_MARGIN_MILLIS = 30000;

	private LineDuties() {
	}

	public static String getLineKey(Route route) {
		return route.transportMode + "|" + route.getLineKey();
	}

	/**
	 * @return whether the depot's duties are planned with its lines: it runs by a timetable, has no duties set, and isn't
	 * moving continuously or flying
	 */
	public static boolean plansByLine(Depot depot, DataCache dataCache) {
		return !depot.duties.diagramMode && canPlanByLine(depot, dataCache);
	}

	/**
	 * @return whether the depot could plan by line, duties set or not
	 */
	private static boolean canPlanByLine(Depot depot, DataCache dataCache) {
		return !depot.transportMode.continuousMovement && depot.transportMode != TransportMode.AIRPLANE && depot.findTimetable(dataCache) != null;
	}

	/**
	 * Read on the server thread.
	 *
	 * @param sidings every siding of the world
	 * @return the planning unit the depot belongs to, or null if it doesn't plan by line
	 */
	public static Unit of(Depot depot, DataCache dataCache, Collection<Siding> sidings) {
		return of(depot, dataCache, sidings, false);
	}

	/**
	 * @param forDraft for the advanced editor's draft of the depot's duties ("一键生成运用"): the depot is planned with its
	 *                 lines even if it has duties set, and its own set duties don't count as taken
	 * @return the planning unit the depot belongs to, or null if it can't plan by line
	 */
	public static Unit of(Depot depot, DataCache dataCache, Collection<Siding> sidings, boolean forDraft) {
		if (!plansByLine(depot, dataCache) && !(forDraft && canPlanByLine(depot, dataCache))) {
			return null;
		}
		// The routes of each line, and the depots planning by line, by the lines they run
		final Map<String, List<Route>> lineRoutes = new TreeMap<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (route.transportMode == depot.transportMode) {
				lineRoutes.computeIfAbsent(getLineKey(route), key -> new ArrayList<>()).add(route);
			}
		});
		final Map<String, List<Depot>> lineDepots = new HashMap<>();
		final Map<Long, Set<String>> depotLines = new HashMap<>();
		dataCache.depotIdMap.values().forEach(other -> {
			if (other.transportMode == depot.transportMode && (plansByLine(other, dataCache) || forDraft && other == depot)) {
				other.getRoutes(dataCache).forEach(route -> {
					final String lineKey = getLineKey(route);
					depotLines.computeIfAbsent(other.id, key -> new TreeSet<>()).add(lineKey);
					final List<Depot> depots = lineDepots.computeIfAbsent(lineKey, key -> new ArrayList<>());
					if (!depots.contains(other)) {
						depots.add(other);
					}
				});
			}
		});
		// Lines joined by through links between their services
		final Map<String, Set<String>> linkedLines = new HashMap<>();
		dataCache.routeIdMap.values().forEach(route -> {
			if (route.transportMode != depot.transportMode) {
				return;
			}
			for (int i = 0; i < route.timetable.getTableCount(); i++) {
				route.timetable.getTable(i).links.values().forEach(link -> {
					final Route linked = dataCache.routeIdMap.get(link.routeId);
					if (linked != null && linked.transportMode == route.transportMode) {
						linkedLines.computeIfAbsent(getLineKey(route), key -> new TreeSet<>()).add(getLineKey(linked));
						linkedLines.computeIfAbsent(getLineKey(linked), key -> new TreeSet<>()).add(getLineKey(route));
					}
				});
			}
		});

		// The unit: everything reached from the depot through the lines it runs
		final Set<String> lines = new TreeSet<>();
		final Set<Long> depotIds = new TreeSet<>();
		final ArrayDeque<Object> queue = new ArrayDeque<>();
		depotIds.add(depot.id);
		queue.add(depot);
		while (!queue.isEmpty()) {
			final Object next = queue.poll();
			if (next instanceof Depot) {
				depotLines.getOrDefault(((Depot) next).id, Collections.emptySet()).forEach(lineKey -> {
					if (lines.add(lineKey)) {
						queue.add(lineKey);
					}
				});
			} else {
				final String lineKey = (String) next;
				lineDepots.getOrDefault(lineKey, Collections.emptyList()).forEach(other -> {
					if (depotIds.add(other.id)) {
						queue.add(other);
					}
				});
				linkedLines.getOrDefault(lineKey, Collections.emptySet()).forEach(other -> {
					if (lines.add(other)) {
						queue.add(other);
					}
				});
			}
		}
		return new Unit(lines, lineRoutes, depotIds, dataCache, sidings, forDraft ? depot.id : 0);
	}

	/**
	 * The lines, depots and sidings planned together, read on the server thread, and what makes their duties change.
	 */
	public static final class Unit {

		public final String key;
		/**
		 * The names of the unit's lines, for the log and the shortfall.
		 */
		public final String name;
		public final Set<Long> depotIds;
		private final List<Route> routes = new ArrayList<>();
		private final List<Group> groups = new ArrayList<>();
		private final Set<String> taken = new TreeSet<>();
		private final boolean staysOnLine;
		private final Map<Long, Long> stationOf = new HashMap<>();
		/**
		 * Each siding's way back from each platform, as measured at the last path generation.
		 */
		private final Map<Long, Map<Long, Long>> backLinks = new TreeMap<>();

		/**
		 * @param draftDepotId the depot drafting its duties, whose own set duties aren't taken, or 0
		 */
		private Unit(Set<String> lines, Map<String, List<Route>> lineRoutes, Set<Long> depotIds, DataCache dataCache, Collection<Siding> sidings, long draftDepotId) {
			this.depotIds = depotIds;
			final Set<String> names = new TreeSet<>();
			lines.forEach(lineKey -> lineRoutes.getOrDefault(lineKey, Collections.emptyList()).forEach(route -> {
				if (route.timetable.isActive() && route.platformIds.size() >= 2) {
					routes.add(route);
					names.add(route.getLineName());
				}
			}));
			routes.sort(Comparator.comparingLong(route -> route.id));
			name = String.join(", ", names);
			final Set<Long> routeIds = new HashSet<>();
			routes.forEach(route -> routeIds.add(route.id));
			dataCache.platformIdToStation.forEach((platformId, station) -> stationOf.put(platformId, station.id));

			// Services already run by depots with duties set are left to them
			final TransportMode transportMode = routes.isEmpty() ? null : routes.get(0).transportMode;
			dataCache.depotIdMap.values().forEach(other -> {
				if (other.transportMode == transportMode && other.duties.diagramMode && other.id != draftDepotId) {
					other.duties.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> {
						if (routeIds.contains(segment.routeId)) {
							taken.add(segment.routeId + "@" + segment.service);
						}
					}))));
				}
			});

			// The sidings of the unit's depots, grouped by the types of service their vehicles run
			final Map<String, Group> groupsByTypes = new TreeMap<>();
			boolean anyStays = false;
			final List<Depot> depots = new ArrayList<>();
			depotIds.forEach(depotId -> {
				final Depot depot = dataCache.depotIdMap.get(depotId);
				if (depot != null) {
					depots.add(depot);
				}
			});
			for (final Depot depot : depots) {
				anyStays |= depot.cycleRoutes;
				sidings.stream().filter(siding -> siding.isTransportMode(depot.transportMode) && depot.inArea(siding.getMidPos().getX(), siding.getMidPos().getZ())).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
					final Set<String> serviceTypes = depot.getServiceTypes(siding.getTrainId());
					// Sidings given only some of the routes are planned apart, with those routes
					final Group group = groupsByTypes.computeIfAbsent(Depot.getSidingGroupKey(serviceTypes, siding), key -> new Group(serviceTypes, siding.getRouteIds()));
					// Its trains start at the first stops of its own depot's routes, the nearer the sooner
					// As measured out along the rails at the last path generation (DepotLegs), otherwise from how far it is, with room
					final List<Long> firstPlatforms = new ArrayList<>();
					final List<Long> lastPlatforms = new ArrayList<>();
					depot.getSidingRoutes(siding, dataCache).forEach(route -> {
						firstPlatforms.add(route.getFirstPlatformId());
						lastPlatforms.add(route.getLastPlatformId());
					});
					final Map<Long, Long> links = sidingLinks(siding, firstPlatforms, dataCache);
					// And back to it from each last stop
					backLinks.put(siding.id, sidingBackLinks(siding, lastPlatforms, dataCache));
					// Its fleet, however many trains its rails hold
					final DutyGenerator.SidingInput sidingInput = new DutyGenerator.SidingInput(siding.id, siding.getFleetSize(), links, depot.id);
					// The runtime's pool: the unit's sidings of the same vehicle run each other's duties and take each other's trains
					sidingInput.poolKey = siding.getPoolKey();
					group.sidings.add(sidingInput);
					group.depotOf.put(siding.id, depot);
					group.sidingNames.put(siding.id, siding.name);
					// The trains its pool has: what the depot gives the pool, once, or else each siding's fleet (P2-C)
					final Integer poolFleet = depot.poolFleets.get(siding.getPoolKey());
					if (poolFleet == null) {
						group.available += siding.getFleetSize() < 0 ? DepotDuties.MAX_SLOTS : siding.getFleetSize();
					} else if (group.countedPools.add(depot.id + "/" + siding.getPoolKey())) {
						group.available += poolFleet < 0 ? DepotDuties.MAX_SLOTS : poolFleet;
					}
				});
			}
			staysOnLine = anyStays;
			groups.addAll(groupsByTypes.values());
			groups.forEach(group -> Depot.filterRoutes(routes, group.serviceTypes).forEach(route -> {
				if (group.routeIds.isEmpty() || group.routeIds.contains(route.id)) {
					group.routes.add(route);
				}
			}));

			// What the duties are worked out from: the routes' timetables and stops, the sidings, what others run
			final StringBuilder keyBuilder = new StringBuilder();
			routes.forEach(route -> {
				final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
				transitsim.plan.TimetableBinary.write(writer, route.timetable);
				keyBuilder.append(route.id).append(':').append(Arrays.hashCode(writer.toBytes())).append(':');
				route.platformIds.forEach(platform -> keyBuilder.append(platform.platformId).append(platform.pass ? "p" : "").append(platform.dwellOverride).append(','));
				keyBuilder.append(';');
			});
			groups.forEach(group -> {
				keyBuilder.append('[').append(group.serviceTypes).append(group.routeIds).append(']');
				group.sidings.forEach(siding -> keyBuilder.append(siding.sidingId).append('/').append(siding.maxTrains).append(siding.links).append(backLinks.get(siding.sidingId)).append(';'));
			});
			keyBuilder.append("taken:").append(taken.hashCode());
			// The routes' paths keep off the connecting lines, which changes their times
			keyBuilder.append("connecting:");
			dataCache.routeIdMap.values().stream().filter(route -> route.connectingLine && route.transportMode == transportMode).sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
				keyBuilder.append(route.id).append('/');
				route.connectingReleased.forEach(rail -> keyBuilder.append(transitsim.network.RailKeys.of(rail[0], rail[1]).hashCode()).append('.'));
				keyBuilder.append(',');
			});
			key = keyBuilder.toString();
		}

		/**
		 * @return the services left to depots with duties set, as route and service
		 */
		Set<String> getTaken() {
			return Collections.unmodifiableSet(taken);
		}

		/**
		 * @return the routes of the unit's lines with a timetable
		 */
		public List<Route> getRoutes() {
			return Collections.unmodifiableList(routes);
		}

		/**
		 * Plans the unit, away from the server thread: each group of sidings in turn, leaving the services it takes to the
		 * groups after it.
		 *
		 * @param fastestTimes a route's fastest times, or null if not known
		 */
		public Plan plan(Function<Route, long[][]> fastestTimes, int dayStartMillis) {
			return plan(fastestTimes, dayStartMillis, null);
		}

		/**
		 * @param turnTimes how long each change of platform within a station takes on the rails, a pair left out having no
		 *                  way between, or null to estimate them
		 */
		public Plan plan(Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes) {
			final Map<String, Integer> poolFleets = new HashMap<>();
			groups.forEach(group -> poolFleets.putAll(poolFleetsOf(group.sidings, group.depotOf::get)));
			final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(turnTimes, backLinks, poolFleets);
			final Plan plan = new Plan(name);
			final Set<String> unitTaken = new HashSet<>(taken);
			final long maxLayover = staysOnLine ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS;
			for (final Group group : groups) {
				final List<Route> missing = new ArrayList<>();
				final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(group.routes, route -> fastestTimes.apply((Route) route), dayStartMillis, route -> missing.add((Route) route));
				services.removeIf(service -> unitTaken.contains(service.routeId + "@" + service.service));
				plan.missingTimes += missing.size();
				if (services.isEmpty() || group.sidings.isEmpty()) {
					continue;
				}
				final DutyGenerator.Result result = DutyGenerator.generateSpread(services, group.sidings, stationOf, maxLayover, inputs);
				plan.sidings.putAll(result.sidings);
				plan.returnSidings.putAll(result.returnSidings);
				result.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> unitTaken.add(segment.routeId + "@" + segment.service)))));
				plan.trains += result.trains;
				plan.uncovered += result.uncovered;
				result.issues.stream().filter(issue -> !plan.issues.contains(issue)).forEach(plan.issues::add);
				plan.available += group.available;
				if (result.uncovered > 0) {
					// Where to add trains: the siding nearest the line
					DutyGenerator.SidingInput nearest = null;
					long nearestMillis = Long.MAX_VALUE;
					for (final DutyGenerator.SidingInput siding : group.sidings) {
						final long millis = siding.links == null || siding.links.isEmpty() ? Long.MAX_VALUE : Collections.min(siding.links.values());
						if (siding.maxTrains >= 0 && (nearest == null || millis < nearestMillis)) {
							nearest = siding;
							nearestMillis = millis;
						}
					}
					plan.needed += group.available + (nearest == null ? 0 : extraTrains(services, group.sidings, nearest, maxLayover, inputs));
					if (nearest != null && plan.suggestedSiding.isEmpty()) {
						final Depot depot = group.depotOf.get(nearest.sidingId);
						plan.suggestedSiding = (depot == null || depot.name.isEmpty() ? "" : IGui.formatStationName(depot.name) + " ") + IGui.formatStationName(group.sidingNames.getOrDefault(nearest.sidingId, ""));
					}
				} else {
					plan.needed += result.trains;
				}
			}
			return plan;
		}

		/**
		 * @return how few trains added to the siding run as many services as any number of trains could, found by halving
		 */
		private int extraTrains(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, long maxLayover, DutyGenerator.PlanInputs inputs) {
			final int best = uncoveredWith(services, sidings, siding, DepotDuties.MAX_SLOTS, maxLayover, inputs);
			int low = 0;
			int high = Math.max(0, DepotDuties.MAX_SLOTS - siding.maxTrains);
			while (low < high) {
				final int middle = (low + high) / 2;
				if (uncoveredWith(services, sidings, siding, siding.maxTrains + middle, maxLayover, inputs) <= best) {
					high = middle;
				} else {
					low = middle + 1;
				}
			}
			return low;
		}

		private int uncoveredWith(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, int trains, long maxLayover, DutyGenerator.PlanInputs inputs) {
			final List<DutyGenerator.SidingInput> changed = new ArrayList<>();
			sidings.forEach(other -> changed.add(other == siding ? new DutyGenerator.SidingInput(other.sidingId, Math.min(trains, DepotDuties.MAX_SLOTS), other.links, other.depotId) : other));
			return DutyGenerator.generate(services, changed, stationOf, maxLayover, inputs).uncovered;
		}
	}

	private static final class Group {

		private final Set<String> serviceTypes;
		/**
		 * The routes the group's sidings are given, or empty for all.
		 */
		private final Set<Long> routeIds;
		private final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		private final List<Route> routes = new ArrayList<>();
		private final Map<Long, Depot> depotOf = new HashMap<>();
		private final Map<Long, String> sidingNames = new HashMap<>();
		private int available;
		private final Set<String> countedPools = new HashSet<>();

		private Group(Set<String> serviceTypes, Set<Long> routeIds) {
			this.serviceTypes = serviceTypes;
			this.routeIds = new TreeSet<>(routeIds);
		}
	}

	/**
	 * The duties of a unit: the slots of every siding, and whether it has trains enough.
	 */
	public static final class Plan {

		public final String lineNames;
		public final Map<Long, List<DepotDuties.Slot>> sidings = new TreeMap<>();
		/**
		 * The trains the duties use at most, those needed to run every service, and those the sidings hold.
		 */
		public int trains;
		public int needed;
		public int available;
		/**
		 * Services no train was free for, counted once for each day of the week they run.
		 */
		public int uncovered;
		public int missingTimes;
		/**
		 * The planner's problems with the unit, as {@link transitsim.planning.DutyGenerator.Result#issues}: a type, then its
		 * details, such as {@code SHORT_TURNAROUND <platform> <millis had> <millis needed>}.
		 */
		public final List<String> issues = new ArrayList<>();
		/**
		 * Where duties' trains go back when not to their own siding, as the runtime would send them (see
		 * {@link transitsim.planning.DutyGenerator.Result#returnSidings}).
		 */
		public final Map<String, Long> returnSidings = new TreeMap<>();
		/**
		 * The siding to add trains to when there are too few, as depot and siding name, or empty.
		 */
		public String suggestedSiding = "";

		private Plan(String lineNames) {
			this.lineNames = lineNames;
		}

		public String describe() {
			return "line " + lineNames + ": needs " + needed + " trains, has " + available + (uncovered == 0 ? "" : ", " + uncovered / 7 + " services a day with no train" + (suggestedSiding.isEmpty() ? "" : "; add trains to " + suggestedSiding)) + (missingTimes == 0 ? "" : ", " + missingTimes + " routes left out as their times aren't known") + (returnSidings.isEmpty() ? "" : ", " + returnSidings.size() + " duties a week go back to another siding of their pool") + shortTurnarounds();
		}

		private String shortTurnarounds() {
			final StringBuilder text = new StringBuilder();
			issues.stream().filter(issue -> issue.startsWith("SHORT_TURNAROUND ")).forEach(issue -> {
				final String[] parts = issue.split(" ");
				if (parts.length >= 4) {
					text.append("; turnaround too short at platform ").append(parts[1]).append(": ").append(Long.parseLong(parts[2]) / 1000).append(" s, needs ").append(Long.parseLong(parts[3]) / 1000).append(" s");
				}
			});
			return text.toString();
		}
	}
}
