package mtr.data;

import net.minecraft.core.BlockPos;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;
import transitsim.planning.DutyCheck;
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
	 * How long the siding's trains take out to each of the platforms: as path generation found it on the track sections
	 * ({@link Siding#getRecordedLegs}, round 4), otherwise, before that, the estimate.
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

	/**
	 * @return platform to from how long a wait for the next service a train waits on a turn back rail beyond it rather than
	 * at the platform (运用补完 step 2), at the stops of the routes: by a rule of one of the depots for the route's line, else
	 * the station's own, else the network one; only where not the runtime's default
	 */
	public static Map<Long, Long> waitThresholdsOf(Collection<Route> routes, Collection<Depot> depots, DataCache dataCache) {
		final Map<Long, Long> thresholds = new TreeMap<>();
		for (final Route route : routes) {
			long ruleMillis = -1;
			final String lineKey = getLineKey(route);
			for (final Depot depot : depots) {
				for (int weekday = 0; weekday < 7 && ruleMillis < 0; weekday++) {
					final transitsim.plan.DepotRules.Rule rule = depot.rules.match(lineKey, weekday);
					if (rule != null && rule.waitThresholdMillis >= 0) {
						ruleMillis = rule.waitThresholdMillis;
					}
				}
			}
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				final Station station = dataCache.platformIdToStation.get(routePlatform.platformId);
				final long millis = ruleMillis >= 0 ? ruleMillis : dataCache.networkSettings.getWaitThreshold(station == null ? 0 : station.id);
				if (millis != transitsim.sim.TrainService.LONG_TURN_BACK_WAIT_MILLIS) {
					thresholds.put(routePlatform.platformId, millis);
				}
			}
		}
		return thresholds;
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
		final Lines lines = new Lines(depot.transportMode, dataCache, forDraft ? depot : null);
		return lines.unitOf(depot, dataCache, sidings, forDraft ? depot.id : 0);
	}

	/**
	 * The planning units of the depots, the routes and depots of the world read once for them all and each unit made once
	 * for all its depots, as the check for changes to the auto duties does for every depot at once.
	 *
	 * @param sidings every siding of the world
	 * @return each depot's planning unit by its id, left out if it doesn't plan by line; depots of one unit share it
	 */
	public static Map<Long, Unit> unitsOf(Collection<Depot> depots, DataCache dataCache, Collection<Siding> sidings) {
		final Map<Long, Unit> units = new HashMap<>();
		final Map<TransportMode, Lines> linesByMode = new EnumMap<>(TransportMode.class);
		depots.forEach(depot -> {
			if (units.containsKey(depot.id) || !plansByLine(depot, dataCache)) {
				return;
			}
			final Lines lines = linesByMode.computeIfAbsent(depot.transportMode, transportMode -> new Lines(transportMode, dataCache, null));
			final Unit unit = lines.unitOf(depot, dataCache, sidings, 0);
			// The unit is everything reached from any of its depots, so it is the same unit from each of them
			unit.depotIds.forEach(depotId -> units.put(depotId, unit));
		});
		return units;
	}

	/**
	 * The lines of one transport mode and the depots planning by line, joined by the routes they list and the through
	 * links between services.
	 */

	private static final class Lines {

		private final Map<String, List<Route>> lineRoutes = new TreeMap<>();
		private final Map<String, List<Depot>> lineDepots = new HashMap<>();
		private final Map<Long, Set<String>> depotLines = new HashMap<>();
		private final Map<String, Set<String>> linkedLines = new HashMap<>();

		/**
		 * @param draftDepot the depot drafting its duties, counted as planning by line though it has duties set, or null
		 */
		private Lines(TransportMode transportMode, DataCache dataCache, Depot draftDepot) {
			// The routes of each line, and the depots planning by line, by the lines they run
			dataCache.routeIdMap.values().forEach(route -> {
				if (route.transportMode == transportMode) {
					lineRoutes.computeIfAbsent(getLineKey(route), key -> new ArrayList<>()).add(route);
				}
			});
			dataCache.depotIdMap.values().forEach(other -> {
				if (other.transportMode == transportMode && (plansByLine(other, dataCache) || other == draftDepot)) {
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
			dataCache.routeIdMap.values().forEach(route -> {
				if (route.transportMode != transportMode) {
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
		}

		/**
		 * @return the unit: everything reached from the depot through the lines it runs
		 */
		private Unit unitOf(Depot depot, DataCache dataCache, Collection<Siding> sidings, long draftDepotId) {
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
			return new Unit(lines, lineRoutes, depotIds, dataCache, sidings, draftDepotId);
		}
	}

	/**
	 * The lines, depots and sidings planned together, read on the server thread, and what makes their duties change.
	 */
	public static final class Unit {

		public final String key;
		/**
		 * What the duties are made of, leaving out every time (round 4, step 4): the timetables and stops, the sidings and
		 * their trains, the services left to others and the start of the operating day. Duties planned from the same are
		 * kept while they still hold with new times ({@link #keep}).
		 */
		public final String basis;
		/**
		 * The time the unit's depots add to the ways out, turns round and ways back, as adopted (D1, round 4, step 6).
		 */
		private final transitsim.plan.Buffers buffers;
		/**
		 * The network's least time to change ends (round 4).
		 */
		private final long minReverseMillis;
		/**
		 * Platform to from how long a wait a train waits on a turn back rail beyond it, where not the default.
		 */
		private final Map<Long, Long> waitThresholds;
		/**
		 * A digest of {@link #buffers}, empty for none, for telling the duties kept were planned with others (round 4, step 6).
		 */
		private final String buffersKey;
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
		 * Each siding's first stops, which its trains go out to, and last stops, which they come back from.
		 */
		private final Map<Long, List<Long>> sidingFirsts = new TreeMap<>();
		private final Map<Long, List<Long>> sidingLasts = new TreeMap<>();

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
				// The depot's own sidings, as the game gives them out: by the heights too, for depots one above another
				sidings.stream().filter(siding -> siding.isTransportMode(depot.transportMode) && isOwnSiding(dataCache, depot, siding)).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
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
					sidingFirsts.put(siding.id, firstPlatforms);
					sidingLasts.put(siding.id, lastPlatforms);
					final Map<Long, Long> links = sidingLinks(siding, firstPlatforms, dataCache);
					// And back to it from each last stop
					backLinks.put(siding.id, sidingBackLinks(siding, lastPlatforms, dataCache));
					// Its fleet, however many trains its rails hold
					final DutyGenerator.SidingInput sidingInput = new DutyGenerator.SidingInput(siding.id, siding.getFleetSize(), links, depot.id);
					// Where its way out runs through a terminus's turn back behind trains turning there (first come, first out)
					final Map<Long, Long> passes = new TreeMap<>(siding.getRecordedLegs().outPasses);
					siding.getRecordedLegs().outPassNodes.forEach((platformId, node) -> {
						final net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(node);
						dataCache.platformIdMap.values().stream().filter(platform -> platform.containsPos(pos)).findFirst().ifPresent(platform -> passes.putIfAbsent(platformId, platform.id));
					});
					sidingInput.passes = passes;
					// Said once, and again only when it changes: the duties are checked every few seconds (Q-world run, L14)
					if (passes.isEmpty()) {
						LOGGED_PASSES.remove(siding.id);
					} else {
						final String message = "Minecraft Transit Railway: siding " + siding.name + " reaches " + passes.keySet() + " only through " + passes.values() + " and its turn back (first come, first out there)";
						if (!message.equals(LOGGED_PASSES.put(siding.id, message))) {
							System.out.println(message);
						}
					}
					// The runtime's pool: the unit's sidings of the same vehicle run each other's duties and take each other's trains
					sidingInput.poolKey = siding.getPoolKey();
					group.sidings.add(sidingInput);
					group.depotOf.put(siding.id, depot);
					group.sidingNames.put(siding.id, siding.name);
					// Named as players know the vehicle, without the prefix of custom trains
					group.vehicles.add(siding.getTrainId().startsWith(CUSTOM_TRAIN_PREFIX) ? siding.getTrainId().substring(CUSTOM_TRAIN_PREFIX.length()) : siding.getTrainId());
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
				// The ways out and back aren't in the key: path generation measures them and plans with them at once (round 4)
				group.sidings.forEach(siding -> keyBuilder.append(siding.sidingId).append('/').append(siding.maxTrains).append(';'));
			});
			keyBuilder.append("taken:").append(taken.hashCode());
			// Each depot's trains for each pool, which the planner shares between the pool's sidings
			final StringBuilder basisBuilder = new StringBuilder(keyBuilder);
			depotIds.stream().sorted().map(dataCache.depotIdMap::get).filter(Objects::nonNull).forEach(depot -> basisBuilder.append("pools:").append(depot.id).append(depot.poolFleets));
			basisBuilder.append("day:").append(TimetableConfig.getDayStartMillis());
			basis = digest(basisBuilder.toString());
			if (Boolean.getBoolean("mtr.debugPlanBasis")) {
				System.out.println("Minecraft Transit Railway plan basis " + basis + " of " + name + ": " + basisBuilder);
			}
			// The time its depots add to the ways out, turns round and ways back, as adopted from what was measured (D1, round 4,
			// step 6): what was measured alone no longer changes the plan
			final Map<String, Long> unitBuffers = new TreeMap<>();
			depotIds.stream().sorted().map(dataCache.depotIdMap::get).filter(Objects::nonNull).forEach(depot -> depot.getAddedBuffers().getMillis().forEach((bufferKey, millis) -> unitBuffers.merge(bufferKey, millis, Math::max)));
			buffers = unitBuffers.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(unitBuffers);
			buffersKey = unitBuffers.isEmpty() ? "" : digest(unitBuffers.toString());
			keyBuilder.append("buffers:").append(unitBuffers);
			// The network's least time to change ends (round 4)
			minReverseMillis = dataCache.networkSettings.minReverseMillis;
			keyBuilder.append("reverse:").append(minReverseMillis);
			// From how long a wait trains wait off the platform, at each platform (运用补完 step 2)
			waitThresholds = waitThresholdsOf(routes, depots, dataCache);
			keyBuilder.append("waits:").append(waitThresholds);
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
			return plan(fastestTimes, dayStartMillis, turnTimes, null);
		}

		/**
		 * What the planner plans by, as measured: the changes of platform, the ways back, the pools' trains, what was measured
		 * on top (D1) and the least time to change ends.
		 */
		private DutyGenerator.PlanInputs inputsOf(Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured) {
			final Map<String, Integer> poolFleets = new HashMap<>();
			groups.forEach(group -> poolFleets.putAll(poolFleetsOf(group.sidings, group.depotOf::get)));
			final Map<Long, Map<Long, Long>> planBackLinks = new TreeMap<>(backLinks);
			if (measured != null) {
				measured.forEach((sidingId, legs) -> {
					final Map<Long, Long> back = new TreeMap<>(planBackLinks.getOrDefault(sidingId, Collections.emptyMap()));
					sidingLasts.getOrDefault(sidingId, Collections.emptyList()).forEach(platformId -> {
						if (legs.unreachableBack.contains(platformId)) {
							back.put(platformId, transitsim.planning.OperationNetwork.UNREACHABLE);
						} else if (legs.backMillis.containsKey(platformId)) {
							back.put(platformId, legs.backMillis.get(platformId));
						}
					});
					planBackLinks.put(sidingId, back);
				});
			}
			return new DutyGenerator.PlanInputs(turnTimes, planBackLinks, poolFleets, buffers, minReverseMillis, waitThresholds);
		}

		/**
		 * @return a digest of which ways the track gives, not how long they take: the changes of platform and turns round
		 * measured, and the first and last stops each siding was found to have a way out to and back from; empty where the
		 * ways out and back weren't measured just now
		 */
		static String waysKey(Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured) {
			if (measured == null) {
				return "";
			}
			final StringBuilder key = new StringBuilder();
			if (turnTimes != null) {
				new TreeMap<>(turnTimes).forEach((from, times) -> new TreeSet<>(times.keySet()).forEach(to -> {
					if (to != DutyGenerator.HOLD_IN_KEY) {
						key.append(from).append('>').append(to).append(';');
					}
				}));
			}
			new TreeMap<>(measured).forEach((sidingId, legs) -> key.append('s').append(sidingId).append(new TreeSet<>(legs.outMillis.keySet())).append(new TreeSet<>(legs.unreachableOut)).append(new TreeSet<>(legs.backMillis.keySet())).append(new TreeSet<>(legs.unreachableBack)).append(legs.outPasses).append(legs.outPassNodes).append(';'));
			return digest(key.toString());
		}

		private long getMaxLayover() {
			return staysOnLine ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS;
		}

		/**
		 * The duties planned before, kept if they were planned from what the unit is made of now and still hold with these
		 * times (optimisation round 4, step 4): checked by the planner's rules ({@link DutyCheck}), so a small change in the
		 * times leaves them as they are, and one breaking a rule by however little has them planned again.
		 *
		 * @param records each depot's record of the plan its duties were given from ({@link Plan#toBytes}), by depot
		 * @param slots   the duties each siding has, by siding
		 * @param why     told why they are planned again, for the log
		 * @return the plan kept, or null to plan again
		 */
		public Plan keep(Map<Long, byte[]> records, Map<Long, List<DepotDuties.Slot>> slots, Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured, java.util.function.Consumer<String> why) {
			return keep(records, slots, fastestTimes, dayStartMillis, turnTimes, measured, Collections.emptySet(), why);
		}

		/**
		 * @param forced the duty groups a player asked to have planned again, by key (duty groups, step 4)
		 */
		public Plan keep(Map<Long, byte[]> records, Map<Long, List<DepotDuties.Slot>> slots, Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured, Set<String> forced, java.util.function.Consumer<String> why) {
			// Every depot with sidings in the unit was given its duties from a plan made of the same
			Plan record = null;
			final Set<Long> sidingDepots = new TreeSet<>();
			groups.forEach(group -> group.depotOf.values().forEach(depot -> sidingDepots.add(depot.id)));
			for (final long depotId : sidingDepots) {
				final Plan depotRecord = Plan.fromBytes(records.get(depotId), name);
				if (depotRecord == null) {
					why.accept("depot " + depotId + " has no record of the plan its duties came from");
					return null;
				}
				if (depotRecord.beforeItems) {
					why.accept("depot " + depotId + "'s duties were planned before duties kept where trains wait, when they leave and where they go back");
					return null;
				}
				if (!basis.equals(depotRecord.basis)) {
					why.accept("depot " + depotId + "'s duties were planned from other timetables or sidings (" + depotRecord.basis + ", now " + basis + ")");
					// The duty groups whose own services and sidings are the same may still be kept (duty groups, step 2)
					return keepParts(depotRecord, slots, fastestTimes, dayStartMillis, turnTimes, measured, why, Collections.emptyList(), forced);
				}
				record = record == null ? depotRecord : record;
			}
			if (record == null) {
				why.accept("no depot has sidings");
				return null;
			}
			final Plan kept = record;
			final DutyGenerator.PlanInputs inputs = inputsOf(turnTimes, measured);
			final List<DutyCheck.Group> checkGroups = new ArrayList<>();
			for (final Group group : groups) {
				final Map<Long, List<DepotDuties.Slot>> groupSlots = new TreeMap<>();
				group.sidings.forEach(siding -> {
					final List<DepotDuties.Slot> sidingSlots = slots.get(siding.sidingId);
					if (sidingSlots != null && !sidingSlots.isEmpty()) {
						groupSlots.put(siding.sidingId, sidingSlots);
						kept.sidings.put(siding.sidingId, sidingSlots);
					}
				});
				if (!groupSlots.isEmpty()) {
					final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(group.routes, route -> fastestTimes.apply((Route) route), dayStartMillis, route -> {
					});
					checkGroups.add(new DutyCheck.Group(services, withMeasured(group.sidings, measured), groupSlots));
				}
			}
			if (kept.sidings.isEmpty()) {
				why.accept("there are no duties");
				return null;
			}
			final List<DutyCheck.Break> breaks = DutyCheck.check(checkGroups, stationOf, getMaxLayover(), inputs);
			if (breaks.isEmpty() && !forced.isEmpty()) {
				why.accept("a player asked to plan duty groups " + forced + " again");
				return keepParts(kept, slots, fastestTimes, dayStartMillis, turnTimes, measured, why, breaks, forced);
			}
			if (!breaks.isEmpty()) {
				final StringBuilder text = new StringBuilder(breaks.size() + " rules broke: ");
				for (int i = 0; i < Math.min(breaks.size(), 5); i++) {
					text.append(i == 0 ? "" : "; ").append(breaks.get(i));
				}
				why.accept(text.append(breaks.size() > 5 ? "; ..." : "").toString());
				// The duty groups whose duties still hold are kept, the others planned again (duty groups, step 2)
				return keepParts(kept, slots, fastestTimes, dayStartMillis, turnTimes, measured, why, breaks, forced);
			}
			kept.kept = true;
			// Kept with settings changed since: a player may want them planned again by the new settings
			final List<String> hints = new ArrayList<>();
			if (kept.minReverseMillis != minReverseMillis) {
				hints.add("minReverse:" + kept.minReverseMillis + ":" + minReverseMillis);
			}
			if (!kept.buffersKey.equals(buffersKey)) {
				hints.add("buffers");
			}
			// Ways the track gives that it didn't, or the other way round, with services left with no train: planned again,
			// more of them may be run (2026-11-09, a turn added at a terminus)
			final String waysNow = waysKey(turnTimes, measured);
			if (kept.uncovered > 0 && !kept.waysKey.isEmpty() && !waysNow.isEmpty() && !kept.waysKey.equals(waysNow)) {
				hints.add("ways");
			}
			kept.replanHint = String.join(";", hints);
			return kept;
		}

		/**
		 * @param measured the ways out and back of the unit's sidings as path generation found them on the track sections
		 *                 just now (round 4, step 3), by siding, in place of what was kept; null to plan with what was kept
		 */
		public Plan plan(Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured) {
			return build(fastestTimes, dayStartMillis, inputsOf(turnTimes, measured), measured, null, null);
		}

		/**
		 * Plans the unit by duty groups (duty groups, step 2): each group of sidings in turn, leaving the services it takes to
		 * the groups after it, and within it each day type ({@link DutyGenerator#dayTypes}) on its own.
		 *
		 * @param kept  the duty groups kept as they were, by key, or null to plan them all
		 * @param slots the duties the sidings have, which the groups kept take theirs from
		 */
		private Plan build(Function<Route, long[][]> fastestTimes, int dayStartMillis, DutyGenerator.PlanInputs inputs, Map<Long, DepotLegs> measured, Map<String, Part> kept, Map<Long, List<DepotDuties.Slot>> slots) {
			final Plan plan = new Plan(name);
			plan.basis = basis;
			plan.minReverseMillis = minReverseMillis;
			plan.buffersKey = buffersKey;
			plan.waysKey = waysKey(inputs.turnTimes, measured);
			final Set<String> unitTaken = new HashSet<>(taken);
			final long maxLayover = getMaxLayover();
			for (final Group group : groups) {
				final List<Route> missing = new ArrayList<>();
				final List<DutyGenerator.ServiceInput> allServices = DutyGenerator.servicesOf(group.routes, route -> fastestTimes.apply((Route) route), dayStartMillis, route -> missing.add((Route) route));
				allServices.removeIf(service -> taken.contains(service.routeId + "@" + service.service));
				final List<DutyGenerator.ServiceInput> services = new ArrayList<>(allServices);
				services.removeIf(service -> unitTaken.contains(service.routeId + "@" + service.service));
				plan.missingTimes += missing.size();
				if (services.isEmpty() || group.sidings.isEmpty()) {
					continue;
				}
				final List<DutyGenerator.SidingInput> groupSidings = withMeasured(group.sidings, measured);
				// With -Dmtr.debugPlanInputs=true, what the planner is given, to tell why services have no train
				if (Boolean.getBoolean("mtr.debugPlanInputs")) {
					final StringBuilder text = new StringBuilder("Minecraft Transit Railway plan inputs of " + name + " group " + String.join("/", group.vehicles) + ": " + services.size() + " services, day types " + DutyGenerator.dayTypes(services) + ", routes " + group.routes.stream().map(route -> route.id + "=" + route.platformIds.stream().map(platform -> String.valueOf(platform.platformId)).collect(java.util.stream.Collectors.joining(">"))).collect(java.util.stream.Collectors.joining(" ")));
					groupSidings.forEach(siding -> text.append("; siding ").append(siding.sidingId).append(" trains ").append(siding.maxTrains).append(" pool ").append(siding.poolKey).append(" out ").append(siding.links).append(" back ").append(inputs.backLinks.get(siding.sidingId)));
					text.append("; turns ").append(inputs.turnTimes).append("; stations ");
					group.routes.forEach(route -> route.platformIds.forEach(platform -> text.append(platform.platformId).append('@').append(stationOf.get(platform.platformId)).append(' ')));
					System.out.println(text);
				}
				final Pool pool = new Pool(String.join("/", group.vehicles), 0, group.available, 0);
				plan.pools.add(pool);
				plan.available += group.available;
				int groupTrains = 0;
				int groupNeeded = 0;
				final Map<Long, List<DepotDuties.Slot>> groupSlots = new TreeMap<>();
				for (final int mask : DutyGenerator.dayTypes(services)) {
					final Part part = new Part(partKey(group, mask), partBasis(group, DutyGenerator.onDays(allServices, mask), mask, dayStartMillis));
					final Part keptPart = kept == null ? null : kept.get(part.key);
					final Map<Long, List<DepotDuties.Slot>> partSlots;
					if (keptPart != null && keptPart.basis.equals(part.basis)) {
						// Kept as it was: its own days of the sidings' duties, with what was found when it was planned
						partSlots = slotsOn(group, slots, mask);
						part.trains = keptPart.trains;
						part.needed = keptPart.needed;
						part.uncovered = keptPart.uncovered;
						part.issues.addAll(keptPart.issues);
						keptPart.returnSidings.forEach(plan.returnSidings::put);
						part.returnSidings.putAll(keptPart.returnSidings);
						plan.keptParts.add(part.key);
					} else {
						final DutyGenerator.Result result = DutyGenerator.generateDayType(services, mask, groupSidings, stationOf, maxLayover, inputs, true);
						partSlots = result.sidings;
						part.trains = result.trains;
						part.uncovered = result.uncovered;
						part.issues.addAll(result.issues);
						part.returnSidings.putAll(result.returnSidings);
						plan.returnSidings.putAll(result.returnSidings);
						if (result.uncovered > 0) {
							// Services no number of trains could run, as no siding reaches them and no train can turn to them,
							// aren't trains short: told apart, so the trains needed aren't all the trains there are (2026-11-09)
							final int unrunnable = result.unrunnable >= 0 ? result.unrunnable : DutyGenerator.unrunnable(DutyGenerator.onDays(services, mask), groupSidings, stationOf, DutyGenerator.layoverOf(mask, maxLayover), inputs);
							if (unrunnable > 0) {
								part.issues.add(UNRUNNABLE + " " + unrunnable);
							}
							final DutyGenerator.SidingInput nearest = unrunnable >= result.uncovered ? null : nearestLimited(groupSidings);
							part.needed = nearest == null ? result.trains : group.available + extraTrains(services, mask, groupSidings, nearest, maxLayover, inputs);
						} else {
							part.needed = result.trains;
						}
						if (kept != null) {
							plan.plannedParts.add(part.key);
						}
					}
					mergeSlots(groupSlots, partSlots, mask);
					plan.parts.add(part);
					// Counts of each part add up rather than standing for one another
					part.issues.stream().filter(issue -> issue.startsWith(UNRUNNABLE + " ") || !plan.issues.contains(issue)).forEach(plan.issues::add);
					pool.uncovered += part.uncovered;
					groupTrains = Math.max(groupTrains, part.trains);
					groupNeeded = Math.max(groupNeeded, part.needed);
				}
				// In the order of the weekdays, as one plan gives them
				groupSlots.values().forEach(list -> list.forEach(slot -> slot.days.sort(Comparator.comparingInt(day -> Integer.numberOfTrailingZeros(day.weekdays)))));
				plan.sidings.putAll(groupSlots);
				groupSlots.values().forEach(list -> list.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> unitTaken.add(segment.routeId + "@" + segment.service)))));
				plan.trains += groupTrains;
				plan.uncovered += pool.uncovered;
				pool.needed = groupNeeded;
				plan.needed += groupNeeded;
				// Where trains are short, not where they can't get to the services
				if (groupNeeded > group.available && plan.suggestedSiding.isEmpty()) {
					// Where to add trains: the siding nearest the line
					final DutyGenerator.SidingInput nearest = nearestLimited(groupSidings);
					if (nearest != null) {
						final Depot depot = group.depotOf.get(nearest.sidingId);
						plan.suggestedSiding = (depot == null || depot.name.isEmpty() ? "" : IGui.formatStationName(depot.name) + " ") + IGui.formatStationName(group.sidingNames.getOrDefault(nearest.sidingId, ""));
					}
				}
			}
			// Duties that don't settle into the same week each week were planned a week on its own, so what they'd need isn't
			// known: -1, shown as that rather than as a number of trains
			if (plan.issues.stream().anyMatch(issue -> issue.startsWith("NOT_PERIODIC"))) {
				plan.needed = -1;
				plan.pools.forEach(pool -> pool.needed = -1);
			}
			return plan;
		}

		/**
		 * @return the siding nearest the line by its way out that holds a number of trains, to add trains to, or null
		 */
		private static DutyGenerator.SidingInput nearestLimited(List<DutyGenerator.SidingInput> sidings) {
			DutyGenerator.SidingInput nearest = null;
			long nearestMillis = Long.MAX_VALUE;
			for (final DutyGenerator.SidingInput siding : sidings) {
				final long millis = siding.links == null || siding.links.isEmpty() ? Long.MAX_VALUE : Collections.min(siding.links.values());
				if (siding.maxTrains >= 0 && (nearest == null || millis < nearestMillis)) {
					nearest = siding;
					nearestMillis = millis;
				}
			}
			return nearest;
		}

		/**
		 * @return the key of a duty group: the group of sidings and its day type
		 */
		private static String partKey(Group group, int mask) {
			return String.join("/", group.vehicles) + "#" + digest(group.serviceTypes + "|" + group.routeIds + "|" + group.sidings.stream().map(siding -> String.valueOf(siding.sidingId)).collect(java.util.stream.Collectors.joining(","))).substring(0, 8) + "|" + mask;
		}

		/**
		 * @return what a duty group is made of, leaving out every time: its services on its days (their stops, links and
		 * orders), its sidings and their trains, the pools' trains and the start of the operating day
		 */
		private String partBasis(Group group, List<DutyGenerator.ServiceInput> services, int mask, int dayStartMillis) {
			final StringBuilder builder = new StringBuilder().append(mask).append(':');
			services.stream().sorted(Comparator.comparingLong((DutyGenerator.ServiceInput service) -> service.routeId).thenComparingInt(service -> service.service).thenComparingInt(service -> service.weekdays)).forEach(service -> {
				builder.append(service.routeId).append('@').append(service.service).append('/').append(service.weekdays).append('/').append(service.startMillis).append('/').append(Arrays.toString(service.platformIds)).append(Arrays.toString(service.pass));
				builder.append('/').append(service.linkRoute).append(',').append(service.linkService).append(',').append(service.linkFromStop).append(',').append(service.linkToStop);
				builder.append('/').append(service.deployStop).append(',').append(service.deployDepot).append(',').append(service.returnStop).append(',').append(service.returnDepot).append(';');
			});
			builder.append('[').append(group.serviceTypes).append(group.routeIds).append(']');
			group.sidings.forEach(siding -> builder.append(siding.sidingId).append('/').append(siding.maxTrains).append(';'));
			group.depotOf.values().stream().map(depot -> depot.id).distinct().sorted().forEach(depotId -> {
				final Depot depot = group.depotOf.values().stream().filter(other -> other.id == depotId).findFirst().orElse(null);
				builder.append("pools:").append(depotId).append(depot == null ? "" : depot.poolFleets);
			});
			builder.append("day:").append(dayStartMillis);
			return digest(builder.toString());
		}

		/**
		 * @return the duties of the group's sidings on these days, as copies; a slot keeps its place and number
		 */
		private static Map<Long, List<DepotDuties.Slot>> slotsOn(Group group, Map<Long, List<DepotDuties.Slot>> slots, int mask) {
			final Map<Long, List<DepotDuties.Slot>> on = new TreeMap<>();
			group.sidings.forEach(siding -> {
				final List<DepotDuties.Slot> sidingSlots = slots.get(siding.sidingId);
				if (sidingSlots == null) {
					return;
				}
				final List<DepotDuties.Slot> copies = new ArrayList<>();
				sidingSlots.forEach(slot -> {
					final DepotDuties.Slot copy = new DepotDuties.Slot();
					copy.id = slot.id;
					copy.start = slot.start;
					slot.days.forEach(day -> {
						if ((day.weekdays & ~mask) == 0) {
							copy.days.add(day.copy());
						}
					});
					copies.add(copy);
				});
				on.put(siding.sidingId, copies);
			});
			return on;
		}

		/**
		 * Adds a duty group's duties to the slots of its sidings, a slot running the duty of its place in each group.
		 */
		private static void mergeSlots(Map<Long, List<DepotDuties.Slot>> merged, Map<Long, List<DepotDuties.Slot>> part, int mask) {
			part.forEach((sidingId, slots) -> {
				final List<DepotDuties.Slot> mergedSlots = merged.computeIfAbsent(sidingId, key -> new ArrayList<>());
				for (int i = 0; i < slots.size(); i++) {
					while (mergedSlots.size() <= i) {
						final DepotDuties.Slot slot = new DepotDuties.Slot();
						slot.id = DepotDuties.defaultId(sidingId, mergedSlots.size());
						mergedSlots.add(slot);
					}
					final DepotDuties.Slot from = slots.get(i);
					final DepotDuties.Slot into = mergedSlots.get(i);
					if (into.days.isEmpty()) {
						into.id = from.id;
						into.start = from.start;
					}
					for (final DepotDuties.DayDuty day : from.days) {
						day.weekdays &= mask;
						if (day.weekdays != 0) {
							into.days.add(day);
						}
					}
				}
			});
			// Slots with nothing at the end, as a group with fewer trains leaves them, aren't kept
			merged.values().forEach(list -> {
				while (!list.isEmpty() && list.get(list.size() - 1).days.isEmpty()) {
					list.remove(list.size() - 1);
				}
			});
		}

		/**
		 * Keeps the duty groups planned from the same services and sidings whose duties still hold, and plans the others again
		 * (duty groups, step 2): a group broken, or changed, takes the groups after it on the same days and routes with it,
		 * as it may run other services than before. What comes out is checked whole; broken anywhere, it is planned again.
		 *
		 * @param breaks the rules the duties broke as they are
		 * @param forced the groups a player asked to have planned again
		 * @return the plan, or null to plan them all again, as when the record has no duty groups or none can be kept
		 */
		private Plan keepParts(Plan record, Map<Long, List<DepotDuties.Slot>> slots, Function<Route, long[][]> fastestTimes, int dayStartMillis, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measured, java.util.function.Consumer<String> why, List<DutyCheck.Break> breaks, Set<String> forced) {
			if (record.parts.isEmpty()) {
				return null;
			}
			final Map<String, Part> recorded = new HashMap<>();
			record.parts.forEach(part -> recorded.put(part.key, part));
			// The groups each kept part would be, by its day type now, and whether its duties fit those days
			final Map<String, Part> candidates = new HashMap<>();
			final Map<String, Integer> candidateMasks = new HashMap<>();
			final Map<String, Group> candidateGroups = new HashMap<>();
			final List<String> order = new ArrayList<>();
			final Map<String, Set<Long>> partRoutes = new HashMap<>();
			final Map<String, Integer> partMasks = new HashMap<>();
			for (final Group group : groups) {
				final List<DutyGenerator.ServiceInput> allServices = DutyGenerator.servicesOf(group.routes, route -> fastestTimes.apply((Route) route), dayStartMillis, route -> {
				});
				allServices.removeIf(service -> taken.contains(service.routeId + "@" + service.service));
				final List<Integer> types = DutyGenerator.dayTypes(allServices);
				// Each duty of the group's sidings has to lie within one day type, or none of its parts is kept
				final boolean fits = group.sidings.stream().allMatch(siding -> slots.getOrDefault(siding.sidingId, Collections.emptyList()).stream().allMatch(slot -> slot.days.stream().allMatch(day -> types.stream().anyMatch(mask -> (day.weekdays & ~mask) == 0))));
				final Set<Long> routeIds = new HashSet<>();
				group.routes.forEach(route -> routeIds.add(route.id));
				for (final int mask : types) {
					final String key = partKey(group, mask);
					order.add(key);
					partRoutes.put(key, routeIds);
					partMasks.put(key, mask);
					final Part part = recorded.get(key);
					if (fits && part != null && part.basis.equals(partBasis(group, DutyGenerator.onDays(allServices, mask), mask, dayStartMillis))) {
						candidates.put(key, part);
						candidateMasks.put(key, mask);
						candidateGroups.put(key, group);
					}
				}
			}
			// Broken where its duties break a rule
			breaks.forEach(broken -> candidateGroups.forEach((key, group) -> {
				if ((candidateMasks.get(key) >> broken.day & 1) != 0 && group.sidings.stream().anyMatch(siding -> siding.sidingId == broken.sidingId)) {
					candidates.remove(key);
				}
			}));
			// Asked for by a player
			forced.forEach(candidates::remove);
			// A group planned again takes the later groups on its days and routes with it
			for (int i = 0; i < order.size(); i++) {
				if (candidates.containsKey(order.get(i))) {
					continue;
				}
				for (int j = i + 1; j < order.size(); j++) {
					final String later = order.get(j);
					if ((partMasks.get(later) & partMasks.get(order.get(i))) != 0 && !Collections.disjoint(partRoutes.get(later), partRoutes.get(order.get(i)))) {
						candidates.remove(later);
					}
				}
			}
			if (candidates.isEmpty()) {
				why.accept("no duty group can be kept");
				return null;
			}
			final DutyGenerator.PlanInputs inputs = inputsOf(turnTimes, measured);
			final Plan plan = build(fastestTimes, dayStartMillis, inputs, measured, candidates, slots);
			// Checked whole: the groups kept and those planned again together
			final List<DutyCheck.Group> checkGroups = new ArrayList<>();
			for (final Group group : groups) {
				final Map<Long, List<DepotDuties.Slot>> groupSlots = new TreeMap<>();
				group.sidings.forEach(siding -> {
					final List<DepotDuties.Slot> sidingSlots = plan.sidings.get(siding.sidingId);
					if (sidingSlots != null && !sidingSlots.isEmpty()) {
						groupSlots.put(siding.sidingId, sidingSlots);
					}
				});
				if (!groupSlots.isEmpty()) {
					checkGroups.add(new DutyCheck.Group(DutyGenerator.servicesOf(group.routes, route -> fastestTimes.apply((Route) route), dayStartMillis, route -> {
					}), withMeasured(group.sidings, measured), groupSlots));
				}
			}
			final List<DutyCheck.Break> after = DutyCheck.check(checkGroups, stationOf, getMaxLayover(), inputs);
			// Those planned again break rules of their own as a plan from scratch would, told as issues; only a kept one broken
			// counts
			final boolean keptBroken = after.stream().anyMatch(broken -> plan.keptParts.stream().anyMatch(key -> (partMasks.get(key) >> broken.day & 1) != 0 && candidateGroups.get(key).sidings.stream().anyMatch(siding -> siding.sidingId == broken.sidingId)));
			if (keptBroken) {
				why.accept("the duty groups kept don't hold with those planned again");
				return null;
			}
			why.accept("kept " + plan.keptParts.size() + " of " + (plan.keptParts.size() + plan.plannedParts.size()) + " duty groups, planned again " + plan.plannedParts);
			return plan;
		}

		/**
		 * @return the sidings with their ways out as measured just now, where they were: out to each of their first stops
		 * they have a way to, none where they have none
		 */
		private List<DutyGenerator.SidingInput> withMeasured(List<DutyGenerator.SidingInput> sidings, Map<Long, DepotLegs> measured) {
			if (measured == null) {
				return sidings;
			}
			final List<DutyGenerator.SidingInput> result = new ArrayList<>();
			sidings.forEach(siding -> {
				final DepotLegs legs = measured.get(siding.sidingId);
				if (legs == null) {
					result.add(siding);
					return;
				}
				final Map<Long, Long> links = new TreeMap<>();
				sidingFirsts.getOrDefault(siding.sidingId, Collections.emptyList()).forEach(platformId -> {
					if (legs.outMillis.containsKey(platformId)) {
						links.put(platformId, legs.outMillis.get(platformId));
					} else if (!legs.unreachableOut.contains(platformId) && siding.links != null && siding.links.containsKey(platformId)) {
						links.put(platformId, siding.links.get(platformId));
					}
				});
				final DutyGenerator.SidingInput changed = new DutyGenerator.SidingInput(siding.sidingId, siding.maxTrains, links, siding.depotId);
				changed.poolKey = siding.poolKey;
				result.add(changed);
			});
			return result;
		}

		/**
		 * @return each siding of the unit with its first and last stops, for path generation to measure the ways out and back
		 */
		public Map<Long, List<List<Long>>> getSidingStops() {
			final Map<Long, List<List<Long>>> stops = new TreeMap<>();
			sidingFirsts.forEach((sidingId, firsts) -> stops.put(sidingId, Arrays.asList(firsts, sidingLasts.getOrDefault(sidingId, Collections.emptyList()))));
			return stops;
		}

		/**
		 * @return how few trains added to the siding run as many services as any number of trains could, found by halving
		 */
		private int extraTrains(List<DutyGenerator.ServiceInput> services, int mask, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, long maxLayover, DutyGenerator.PlanInputs inputs) {
			final int best = uncoveredWith(services, mask, sidings, siding, DepotDuties.MAX_SLOTS, maxLayover, inputs);
			int low = 0;
			int high = Math.max(0, DepotDuties.MAX_SLOTS - siding.maxTrains);
			while (low < high) {
				final int middle = (low + high) / 2;
				if (uncoveredWith(services, mask, sidings, siding, siding.maxTrains + middle, maxLayover, inputs) <= best) {
					high = middle;
				} else {
					low = middle + 1;
				}
			}
			return low;
		}

		private int uncoveredWith(List<DutyGenerator.ServiceInput> services, int mask, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, int trains, long maxLayover, DutyGenerator.PlanInputs inputs) {
			final List<DutyGenerator.SidingInput> changed = new ArrayList<>();
			sidings.forEach(other -> changed.add(other == siding ? other.withCapacity(Math.min(trains, DepotDuties.MAX_SLOTS)) : other));
			final int added = Math.max(0, Math.min(trains, DepotDuties.MAX_SLOTS) - siding.maxTrains);
			return DutyGenerator.uncoveredOnDayType(services, mask, changed, stationOf, maxLayover, inputs.withAdditionalPoolTrains(siding.poolKey, added));
		}
	}

	private static final String CUSTOM_TRAIN_PREFIX = "mtr_custom_train_";
	/**
	 * The last first-come-first-out line printed for each siding, so it is printed only when it changes.
	 */
	private static final Map<Long, String> LOGGED_PASSES = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * An issue of a plan: how many services, counted once for each day they run, no number of trains could run.
	 */
	public static final String UNRUNNABLE = "UNRUNNABLE";

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
		/**
		 * The vehicles of its sidings, which name it where the unit has more than one group.
		 */
		private final Set<String> vehicles = new TreeSet<>();

		private Group(Set<String> serviceTypes, Set<Long> routeIds) {
			this.serviceTypes = serviceTypes;
			this.routeIds = new TreeSet<>(routeIds);
		}
	}

	/**
	 * @return a short digest of a key, for keeping in saves
	 */
	/**
	 * @return whether the siding is the depot's: as the game gives sidings out, by the heights too where depots are one
	 * above another; one not given out yet, as in a copy of the data, if the depot's area takes it in across
	 */
	private static boolean isOwnSiding(DataCache dataCache, Depot depot, Siding siding) {
		final Depot given = dataCache.sidingIdToDepot.get(siding.id);
		return given == null ? depot.inArea(siding.getMidPos().getX(), siding.getMidPos().getZ()) : given == depot;
	}

	static String digest(String key) {
		try {
			final byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			final StringBuilder builder = new StringBuilder();
			for (int i = 0; i < 16; i++) {
				builder.append(String.format("%02x", hash[i]));
			}
			return builder.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			return Integer.toHexString(key.hashCode());
		}
	}

	/**
	 * The plans worked out lately, by what they were planned from, so the depots of a planning unit found again together
	 * plan their duties once: the first to ask plans them, the others asking meanwhile wait for its plan rather than plan
	 * the same again (path generation runs several depots at once). Plans are only read once made.
	 */
	public static final class PlanCache<T> {

		private static final int MAX_PLANS = 64;
		private final Map<String, java.util.concurrent.FutureTask<T>> plans = new LinkedHashMap<>(16, 0.75F, true);

		/** Completed least-recently-used results are evicted; running work stays shared. */
		private void trim() {
			final java.util.Iterator<Map.Entry<String, java.util.concurrent.FutureTask<T>>> iterator = plans.entrySet().iterator();
			while (plans.size() > MAX_PLANS && iterator.hasNext()) {
				if (iterator.next().getValue().isDone()) iterator.remove();
			}
		}

		public T get(String key, java.util.concurrent.Callable<T> planner) throws Exception {
			final java.util.concurrent.FutureTask<T> task;
			final boolean owner;
			synchronized (plans) {
				final java.util.concurrent.FutureTask<T> existing = plans.get(key);
				owner = existing == null;
				task = owner ? new java.util.concurrent.FutureTask<>(planner) : existing;
				if (owner) plans.put(key, task);
				trim();
			}
			if (owner) task.run();
			try {
				return task.get();
			} catch (java.util.concurrent.ExecutionException e) {
				synchronized (plans) { plans.remove(key, task); }
				if (e.getCause() instanceof java.util.concurrent.CancellationException) {
					if (owner) throw (java.util.concurrent.CancellationException) e.getCause();
					return get(key, planner);
				}
				throw e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new java.util.concurrent.CancellationException("Interrupted waiting for a plan");
			} finally {
				synchronized (plans) { trim(); }
			}
		}

	}

	/**
	 * A duty group as planned (duty groups, step 2): a group of sidings on one day type, what it was planned from and what
	 * was found, so it can be kept while its own services and sidings stay the same and its duties still hold, however the
	 * other groups change.
	 */
	public static final class Part {

		public final String key;
		public final String basis;
		public int trains;
		public int needed;
		/**
		 * Services with no train, counted once for each day of the week they run.
		 */
		public int uncovered;
		public final List<String> issues = new ArrayList<>();
		public final Map<String, Long> returnSidings = new TreeMap<>();

		public Part(String key, String basis) {
			this.key = key;
			this.basis = basis;
		}
	}

	/**
	 * What a group of sidings planned apart needs and has (as {@link Plan#needed}).
	 */
	public static final class Pool {

		public final String label;
		public int needed;
		public final int available;
		/**
		 * Services no train of the group was free for, counted once for each day of the week they run.
		 */
		public int uncovered;

		public Pool(String label, int needed, int available, int uncovered) {
			this.label = label;
			this.needed = needed;
			this.available = available;
			this.uncovered = uncovered;
		}

		@Override
		public String toString() {
			return label + ": " + (needed < 0 ? "needs ?" : "needs " + needed) + ", has " + available + (uncovered == 0 ? "" : ", " + uncovered / 7 + " services a day with no train");
		}
	}

	/**
	 * The duties of a unit: the slots of every siding, and whether it has trains enough.
	 */
	public static final class Plan {

		public final String lineNames;
		public final Map<Long, List<DepotDuties.Slot>> sidings = new TreeMap<>();
		/**
		 * What the unit was made of when planned ({@link Unit#basis}).
		 */
		public String basis = "";
		/**
		 * Read from a record made before duties kept where trains wait, when they leave and where they go back (运用补完
		 * step 1): shown as it is, but not kept, so the duties are planned again once
		 */
		boolean beforeItems;
		/**
		 * The duties planned before, kept as they still hold (round 4, step 4).
		 */
		public boolean kept;
		/**
		 * The network's least time to change ends the duties were planned with.
		 */
		public long minReverseMillis = DutyGenerator.MIN_TURNAROUND_MILLIS;
		/**
		 * A digest of the time added to the ways out, turns round and ways back the duties were planned with, empty for none.
		 */
		public String buffersKey = "";
		/**
		 * A digest of which ways the track gave when planned ({@link Unit#waysKey}), empty if not known (record 7).
		 */
		public String waysKey = "";
		/**
		 * The settings changed since the duties kept were planned, each as {@code name:then:now}, joined by ';': planned
		 * again only when a player asks (user 2026-11-08), so the duties don't change of themselves; empty if none.
		 */
		public String replanHint = "";
		/**
		 * The trains the duties use at most, those needed to run every service (-1 if not known, as the duties don't repeat
		 * week after week), and those the sidings hold.
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
		/**
		 * The same for each group of sidings planned apart (each pool of vehicles), as trains aren't lent from one to
		 * another: one short while another has more than it needs isn't covered by the total.
		 */
		public final List<Pool> pools = new ArrayList<>();
		/**
		 * The duty groups it was planned as (record 5); and, planned with some kept as they were, those kept and those
		 * planned again, by key, for the log.
		 */
		public final List<Part> parts = new ArrayList<>();
		public final List<String> keptParts = new ArrayList<>();
		public final List<String> plannedParts = new ArrayList<>();

		private Plan(String lineNames) {
			this.lineNames = lineNames;
		}

		/**
		 * For the depots' saves: what it was planned from and what it found, all but the duties, which the depots keep.
		 */
		public byte[] toBytes() {
			final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
			writer.writeByte(RECORD_VERSION);
			writeString(writer, basis);
			writer.writeLong(minReverseMillis);
			writeString(writer, buffersKey);
			writer.writeInt(trains);
			writer.writeInt(needed);
			writer.writeInt(available);
			writer.writeInt(uncovered);
			writer.writeInt(missingTimes);
			// At most as many as are read back, so what follows still lines up
			writer.writeVarInt(Math.min(issues.size(), MAX_RECORD_ITEMS));
			issues.stream().limit(MAX_RECORD_ITEMS).forEach(issue -> writeString(writer, issue));
			writer.writeVarInt(Math.min(returnSidings.size(), MAX_RECORD_ITEMS));
			returnSidings.entrySet().stream().limit(MAX_RECORD_ITEMS).forEach(entry -> {
				writeString(writer, entry.getKey());
				writer.writeLong(entry.getValue());
			});
			writeString(writer, suggestedSiding);
			// From record 4
			writer.writeVarInt(Math.min(pools.size(), MAX_RECORD_ITEMS));
			pools.stream().limit(MAX_RECORD_ITEMS).forEach(pool -> {
				writeString(writer, pool.label);
				writer.writeInt(pool.needed);
				writer.writeInt(pool.available);
				writer.writeInt(pool.uncovered);
			});
			// From record 5
			writer.writeVarInt(Math.min(parts.size(), MAX_RECORD_ITEMS));
			parts.stream().limit(MAX_RECORD_ITEMS).forEach(part -> {
				writeString(writer, part.key);
				writeString(writer, part.basis);
				writer.writeInt(part.trains);
				writer.writeInt(part.needed);
				writer.writeInt(part.uncovered);
				writer.writeVarInt(Math.min(part.issues.size(), MAX_PART_ITEMS));
				part.issues.stream().limit(MAX_PART_ITEMS).forEach(issue -> writeString(writer, issue));
				writer.writeVarInt(Math.min(part.returnSidings.size(), MAX_PART_ITEMS));
				part.returnSidings.entrySet().stream().limit(MAX_PART_ITEMS).forEach(entry -> {
					writeString(writer, entry.getKey());
					writer.writeLong(entry.getValue());
				});
			});
			// From record 7
			writeString(writer, waysKey);
			return writer.toBytes();
		}

		/**
		 * @return as {@link #toBytes} wrote it, with no duties, or null if there is none or it doesn't read
		 */
		public static Plan fromBytes(byte[] bytes, String lineNames) {
			if (bytes == null || bytes.length == 0) {
				return null;
			}
			try {
				final transitsim.io.ByteArrayBinary.Reader reader = new transitsim.io.ByteArrayBinary.Reader(bytes);
				// Record 3, before the groups' counts, reads the same without them
				final int version = reader.readByte();
				if (version < 3 || version > RECORD_VERSION) {
					return null;
				}
				final Plan plan = new Plan(lineNames);
				plan.beforeItems = version < 6;
				plan.basis = readString(reader);
				plan.minReverseMillis = reader.readLong();
				plan.buffersKey = readString(reader);
				plan.trains = reader.readInt();
				plan.needed = reader.readInt();
				plan.available = reader.readInt();
				plan.uncovered = reader.readInt();
				plan.missingTimes = reader.readInt();
				for (int i = Math.min(reader.readVarInt(), MAX_RECORD_ITEMS); i > 0; i--) {
					plan.issues.add(readString(reader));
				}
				for (int i = Math.min(reader.readVarInt(), MAX_RECORD_ITEMS); i > 0; i--) {
					plan.returnSidings.put(readString(reader), reader.readLong());
				}
				plan.suggestedSiding = readString(reader);
				if (version >= 4) {
					for (int i = Math.min(reader.readVarInt(), MAX_RECORD_ITEMS); i > 0; i--) {
						plan.pools.add(new Pool(readString(reader), reader.readInt(), reader.readInt(), reader.readInt()));
					}
				}
				if (version >= 5) {
					for (int i = Math.min(reader.readVarInt(), MAX_RECORD_ITEMS); i > 0; i--) {
						final Part part = new Part(readString(reader), readString(reader));
						part.trains = reader.readInt();
						part.needed = reader.readInt();
						part.uncovered = reader.readInt();
						for (int j = Math.min(reader.readVarInt(), MAX_PART_ITEMS); j > 0; j--) {
							part.issues.add(readString(reader));
						}
						for (int j = Math.min(reader.readVarInt(), MAX_PART_ITEMS); j > 0; j--) {
							part.returnSidings.put(readString(reader), reader.readLong());
						}
						plan.parts.add(part);
					}
				}
				if (version >= 7) {
					plan.waysKey = readString(reader);
				}
				return plan;
			} catch (RuntimeException e) {
				return null;
			}
		}

		/**
		 * 6: duties planned since keep where trains wait, when they leave and where they go back (运用补完 step 1); a
		 * record before that reads as none, so those duties are planned again once, with them. 7: which ways the track gave
		 * (2026-11-09), for suggesting a plan again when they change
		 */
		private static final int RECORD_VERSION = 7;
		private static final int MAX_PART_ITEMS = 256;
		private static final int MAX_RECORD_ITEMS = 4096;
		private static final int MAX_STRING_BYTES = 65536;

		private static void writeString(transitsim.io.ByteArrayBinary.Writer writer, String text) {
			final byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			final int length = Math.min(bytes.length, MAX_STRING_BYTES);
			writer.writeVarInt(length);
			for (int i = 0; i < length; i++) {
				writer.writeByte(bytes[i]);
			}
		}

		private static String readString(transitsim.io.ByteArrayBinary.Reader reader) {
			final int length = reader.readVarInt();
			if (length < 0 || length > MAX_STRING_BYTES) {
				throw new IllegalArgumentException("String too long: " + length);
			}
			final byte[] bytes = new byte[length];
			for (int i = 0; i < length; i++) {
				bytes[i] = reader.readByte();
			}
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		}

		/**
		 * @return how many services no number of trains could run, counted once for each day they run
		 */
		public int getUnrunnable() {
			int unrunnable = 0;
			for (final String issue : issues) {
				if (issue.startsWith(UNRUNNABLE + " ")) {
					try {
						unrunnable += Integer.parseInt(issue.substring(UNRUNNABLE.length() + 1));
					} catch (NumberFormatException ignored) {
					}
				}
			}
			return Math.min(unrunnable, uncovered);
		}

		public String describe() {
			final boolean settled = needed >= 0;
			final int unrunnable = getUnrunnable();
			return "line " + lineNames + (settled ? ": needs " + needed + " trains, has " : ": the duties don't repeat week after week, so the trains needed aren't known; has ") + available + (uncovered == 0 ? "" : ", " + uncovered / 7 + " services a day with no train" + (unrunnable == 0 ? "" : " (" + unrunnable / 7 + " of them no number of trains could run: no siding reaches them and no train can turn to them)") + (suggestedSiding.isEmpty() ? "" : "; add trains to " + suggestedSiding)) + (missingTimes == 0 ? "" : ", " + missingTimes + " routes left out as their times aren't known") + (returnSidings.isEmpty() ? "" : ", " + returnSidings.size() + " duties a week go back to another siding of their pool") + (pools.size() > 1 ? " (" + pools.stream().map(Pool::toString).collect(java.util.stream.Collectors.joining("; ")) + ")" : "") + shortTurnarounds();
		}

		private String shortTurnarounds() {
			final StringBuilder text = new StringBuilder();
			issues.stream().filter(issue -> issue.startsWith("SHORT_TURNAROUND ")).forEach(issue -> {
				final String[] parts = issue.split(" ");
				if (parts.length >= 4) {
					text.append("; turnaround too short at platform ").append(parts[1]).append(": ").append(Long.parseLong(parts[2]) / 1000).append(" s, needs ").append(Long.parseLong(parts[3]) / 1000).append(" s");
				}
			});
			// One way round taking longer than trains come in (7号线, 2026-11-12)
			issues.stream().filter(issue -> issue.startsWith("TURN_BACK_CAPACITY ")).forEach(issue -> {
				final String[] parts = issue.split(" ");
				if (parts.length >= 4) {
					text.append("; trains come in at platform ").append(parts[1]).append(" every ").append(Long.parseLong(parts[2]) / 1000).append(" s, but turning round takes ").append(Long.parseLong(parts[3]) / 1000).append(" s: its turn back can't keep up");
				}
			});
			return text.toString();
		}
	}
}
