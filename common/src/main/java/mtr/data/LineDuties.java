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
		return !depot.duties.diagramMode && !depot.transportMode.continuousMovement && depot.transportMode != TransportMode.AIRPLANE && depot.findTimetable(dataCache) != null;
	}

	/**
	 * Read on the server thread.
	 *
	 * @param sidings every siding of the world
	 * @return the planning unit the depot belongs to, or null if it doesn't plan by line
	 */
	public static Unit of(Depot depot, DataCache dataCache, Collection<Siding> sidings) {
		if (!plansByLine(depot, dataCache)) {
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
			if (other.transportMode == depot.transportMode && plansByLine(other, dataCache)) {
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
		return new Unit(lines, lineRoutes, depotIds, dataCache, sidings);
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

		private Unit(Set<String> lines, Map<String, List<Route>> lineRoutes, Set<Long> depotIds, DataCache dataCache, Collection<Siding> sidings) {
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
				if (other.transportMode == transportMode && other.duties.diagramMode) {
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
				final List<Route> depotRoutes = depot.getRoutes(dataCache);
				sidings.stream().filter(siding -> siding.isTransportMode(depot.transportMode) && depot.inArea(siding.getMidPos().getX(), siding.getMidPos().getZ())).sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
					final Set<String> serviceTypes = depot.getServiceTypes(siding.getTrainId());
					final Group group = groupsByTypes.computeIfAbsent(String.join(",", serviceTypes), key -> new Group(serviceTypes));
					// Its trains start at the first stops of its own depot's routes, the nearer the sooner
					final Map<Long, Long> links = new TreeMap<>();
					final BlockPos sidingPos = siding.getMidPos();
					Depot.filterRoutes(depotRoutes, serviceTypes).forEach(route -> {
						final Platform platform = dataCache.platformIdMap.get(route.getFirstPlatformId());
						if (platform != null) {
							final BlockPos platformPos = platform.getMidPos();
							links.put(platform.id, (long) (Math.sqrt(sidingPos.distSqr(platformPos)) * MILLIS_PER_BLOCK));
						}
					});
					group.sidings.add(new DutyGenerator.SidingInput(siding.id, siding.getTrainLimit(), links));
					group.depotOf.put(siding.id, depot);
					group.sidingNames.put(siding.id, siding.name);
					group.available += siding.getTrainLimit() < 0 ? DepotDuties.MAX_SLOTS : siding.getTrainLimit();
				});
			}
			staysOnLine = anyStays;
			groups.addAll(groupsByTypes.values());
			groups.forEach(group -> group.routes.addAll(Depot.filterRoutes(routes, group.serviceTypes)));

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
				keyBuilder.append('[').append(group.serviceTypes).append(']');
				group.sidings.forEach(siding -> keyBuilder.append(siding.sidingId).append('/').append(siding.maxTrains).append(siding.links).append(';'));
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
		 * Plans the unit, away from the server thread: each group of sidings in turn, leaving the services it takes to the
		 * groups after it.
		 *
		 * @param fastestTimes a route's fastest times, or null if not known
		 */
		public Plan plan(Function<Route, long[][]> fastestTimes, int dayStartMillis) {
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
				final DutyGenerator.Result result = DutyGenerator.generate(services, group.sidings, stationOf, maxLayover);
				plan.sidings.putAll(result.sidings);
				result.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> unitTaken.add(segment.routeId + "@" + segment.service)))));
				plan.trains += result.trains;
				plan.uncovered += result.uncovered;
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
					plan.needed += group.available + (nearest == null ? 0 : extraTrains(services, group.sidings, nearest, maxLayover));
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
		private int extraTrains(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, long maxLayover) {
			final int best = uncoveredWith(services, sidings, siding, DepotDuties.MAX_SLOTS, maxLayover);
			int low = 0;
			int high = Math.max(0, DepotDuties.MAX_SLOTS - siding.maxTrains);
			while (low < high) {
				final int middle = (low + high) / 2;
				if (uncoveredWith(services, sidings, siding, siding.maxTrains + middle, maxLayover) <= best) {
					high = middle;
				} else {
					low = middle + 1;
				}
			}
			return low;
		}

		private int uncoveredWith(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, DutyGenerator.SidingInput siding, int trains, long maxLayover) {
			final List<DutyGenerator.SidingInput> changed = new ArrayList<>();
			sidings.forEach(other -> changed.add(other == siding ? new DutyGenerator.SidingInput(other.sidingId, Math.min(trains, DepotDuties.MAX_SLOTS), other.links) : other));
			return DutyGenerator.generate(services, changed, stationOf, maxLayover).uncovered;
		}
	}

	private static final class Group {

		private final Set<String> serviceTypes;
		private final List<DutyGenerator.SidingInput> sidings = new ArrayList<>();
		private final List<Route> routes = new ArrayList<>();
		private final Map<Long, Depot> depotOf = new HashMap<>();
		private final Map<Long, String> sidingNames = new HashMap<>();
		private int available;

		private Group(Set<String> serviceTypes) {
			this.serviceTypes = serviceTypes;
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
		 * The siding to add trains to when there are too few, as depot and siding name, or empty.
		 */
		public String suggestedSiding = "";

		private Plan(String lineNames) {
			this.lineNames = lineNames;
		}

		public String describe() {
			return "line " + lineNames + ": needs " + needed + " trains, has " + available + (uncovered == 0 ? "" : ", " + uncovered / 7 + " services a day with no train" + (suggestedSiding.isEmpty() ? "" : "; add trains to " + suggestedSiding)) + (missingTimes == 0 ? "" : ", " + missingTimes + " routes left out as their times aren't known");
		}
	}
}
