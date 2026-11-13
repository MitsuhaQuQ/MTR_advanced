package mtr.data;

import transitsim.sim.DepotBoard;
import transitsim.sim.DepotDispatcher;
import transitsim.sim.RunRecord;
import transitsim.sim.TrainService;
import transitsim.plan.PlanRoute;
import transitsim.plan.DepotReturn;
import transitsim.plan.DutyPlan;
import transitsim.plan.TripPath;
import transitsim.plan.TripTail;
import transitsim.plan.FrontTurnBacks;
import transitsim.plan.TurnBackSection;
import transitsim.plan.TurnBackWays;
import transitsim.plan.TimeSegment;
import transitsim.plan.StopTimes;
import mtr.advanced.PacketBinary;
import transitsim.plan.RouteTimetable;
import io.netty.buffer.Unpooled;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.planning.DutyGenerator;
import transitsim.planning.AutoDuties;
import mtr.advanced.PathBoundaries;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import transitsim.network.RailGraph;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * Builds a depot's paths: the routes joined into the paths of its sidings' duties, the ways between services and turning
 * back, the nearby turn back rails and stabling blocks, the times of changing platforms, and the duties planned from the
 * timetables when the depot has none set. Moved as it was out of {@link Depot} (2026-11-13, step 1 of splitting the depot:
 * the depot keeps its place, rules, resources and saving; this keeps finding paths), so it can be moved on into the
 * component piece by piece.
 */
final class DutyPathBuilder {

	/**
	 * The depot whose paths these are.
	 */
	private final Depot depot;

	/**
	 * The rails the nearby turn back rails were listed from, and the component finding them for each platform
	 * ({@link #getNearbyTurnBackNodes}, {@link transitsim.network.NearbyTurnBacks}, step 2a of splitting the depot).
	 */
	Map<BlockPos, Map<BlockPos, Rail>> turnBackRailsOf;
	transitsim.network.NearbyTurnBacks nearbyTurnBacks;
	/**
	 * The ways between services found in this search ({@link #findConnection}), by the rails searched.
	 */
	Map<BlockPos, Map<BlockPos, Rail>> connectionsOf;
	final Map<String, Connection> connections = new HashMap<>();

	/**
	 * Works out the duties of these sidings from the timetables of their routes when the depot runs by a timetable
	 * and has none set, and gives them to the depot on the server thread (decoupling document, step D). The routes'
	 * fastest times are the routes' own ({@link RoutePaths.RoutePath#getFastestTimes}), for the slowest vehicle
	 * running each, as the editor shows them; a route with a path drawn by hand is timed along this depot's path.
	 *
	 * @return the duties, or null if the depot doesn't run by a timetable
	 */
	DepotDuties generateAutoDuties(MinecraftServer minecraftServer, LineDuties.Plan linePlan) {
		final Set<Long> sidingIds = new HashSet<>();
		sidings.forEach(siding -> sidingIds.add(siding.id));
		final boolean byTimetable = depot.findTimetabledRoute(mainRoutes) != null && !depot.transportMode.continuousMovement && !sidings.isEmpty();
		if (!byTimetable) {
			minecraftServer.execute(() -> {
				depot.autoDuties.sidings.keySet().removeAll(sidingIds);
				depot.autoDuties.diagramMode = !depot.autoDuties.sidings.isEmpty();
				depot.autoPlanRecord = new byte[0];
				// The clients drop them too, or the editor goes on showing duties of routes no longer run
				depot.sendAutoDuties(minecraftServer);
			});
			return null;
		}
		if (linePlan != null) {
			// Planned with the depot's whole planning unit: these sidings' slots of it
			final DepotDuties planned = new DepotDuties();
			planned.diagramMode = true;
			sidingIds.forEach(sidingId -> {
				final List<DepotDuties.Slot> slots = linePlan.sidings.get(sidingId);
				if (slots != null && !slots.isEmpty()) {
					planned.sidings.put(sidingId, slots);
				}
			});
			depot.applyRules(planned, depot.autoDuties, dataCache);
			final byte[] record = linePlan.toBytes();
			minecraftServer.execute(() -> {
				depot.autoDuties.sidings.keySet().removeAll(sidingIds);
				depot.autoDuties.sidings.putAll(planned.sidings);
				depot.autoDuties.diagramMode = true;
				depot.autoPlanRecord = record;
				depot.lineShortfall = new Depot.Shortfall(linePlan);
				depot.lineShortfall.entrancesUnset.addAll(new TreeSet<>(depot.entrancesUnset));
				// The list also holds the platforms run against the line outside an interlocking zone, told on their own
				final Set<String> unsetLines = new TreeSet<>(depot.entrancesUnset);
				unsetLines.removeIf(entry -> entry.startsWith(RoutePaths.DepotLine.AGAINST_UNLOCKED));
				if (!unsetLines.isEmpty()) {
					System.out.println("Minecraft Transit Railway: depot " + (depot.name.isEmpty() ? String.valueOf(depot.id) : depot.name) + " reaches the line at more than one station; lines with no rule saying which their trains go by, going by the most convenient: " + String.join(", ", unsetLines));
				}
				depot.sendAutoDuties(minecraftServer);
			});
			return planned;
		}
		final Set<String> autoTaken = new HashSet<>();
		final Map<Long, long[][]> fastestTimes = new HashMap<>();
		for (int i = 0; i < mainRoutes.size(); i++) {
			final List<PathData> routePath = pathsInRoute.get(i);
			// The route's own times, the same as the editor's, for the slowest vehicle running it
			final long[][] routeTimes = usesRoutePaths() ? routeSearch.getTimetableTimes(mainRoutes.get(i), dataCache) : null;
			if (routeTimes != null) {
				if (routeTimes.length > 0) {
					fastestTimes.put(mainRoutes.get(i).id, routeTimes);
				}
			} else if (routePath.size() > 1) {
				fastestTimes.put(mainRoutes.get(i).id, sidings.get(0).calculateStopTimes(routePath).getFastestTimesFromFirstStop(mainRoutes.get(i)));
			}
		}
		// Out to the first stops and back as measured on the rails, and the changes of platform as measured
		final List<Long> firstPlatforms = new ArrayList<>();
		final List<Long> lastPlatforms = new ArrayList<>();
		mainRoutes.forEach(route -> {
			firstPlatforms.add(route.getFirstPlatformId());
			lastPlatforms.add(route.getLastPlatformId());
		});
		final List<DutyGenerator.SidingInput> sidingInputs = new ArrayList<>();
		sidings.stream().sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> {
			final DutyGenerator.SidingInput sidingInput = new DutyGenerator.SidingInput(siding.id, siding.getFleetSize(), LineDuties.sidingLinks(siding, firstPlatforms, dataCache), depot.id);
			sidingInput.poolKey = siding.getPoolKey();
			sidingInputs.add(sidingInput);
		});
		final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(LineDuties.turnTimesOf(Collections.singletonList(depot)), LineDuties.backLinksOf(sidings, lastPlatforms, dataCache), LineDuties.poolFleetsOf(sidingInputs, sidingId -> depot), depot.getAddedBuffers(), dataCache.networkSettings.minReverseMillis, LineDuties.waitThresholdsOf(mainRoutes, Collections.singletonList(depot), dataCache));
		final Map<Long, Long> stationOf = new HashMap<>();
		dataCache.platformIdToStation.forEach((platformId, station) -> stationOf.put(platformId, station.id));
		final Set<Long> mainRouteIds = new HashSet<>();
		mainRoutes.forEach(route -> mainRouteIds.add(route.id));
		final long leftToOthers = autoTaken.stream().filter(service -> mainRouteIds.contains(Long.parseLong(service.substring(0, service.indexOf('@'))))).count();
		final AutoDuties generated = AutoDuties.generate(mainRoutes, route -> fastestTimes.get(route.getRouteId()), TimetableConfig.getDayStartMillis(), sidingInputs, stationOf, depot.cycleRoutes, autoTaken, inputs);
		depot.applyRules(generated.duties, depot.autoDuties, dataCache);
		System.out.println("Minecraft Transit Railway: worked out the duties of depot " + (depot.name.isEmpty() ? String.valueOf(depot.id) : depot.name) + " from its timetables: " + generated.trains + " trains, " + generated.uncovered / 7 + " services a day with no train" + (leftToOthers == 0 ? "" : ", " + leftToOthers + " services left to other sidings or depots running its routes") + (generated.missingTimes.isEmpty() ? "" : ", " + generated.missingTimes.size() + " routes left out as their times aren't known"));
		minecraftServer.execute(() -> {
			depot.autoDuties.sidings.keySet().removeAll(sidingIds);
			depot.autoDuties.sidings.putAll(generated.duties.sidings);
			depot.autoDuties.diagramMode = true;
			depot.autoPlanRecord = new byte[0];
			depot.sendAutoDuties(minecraftServer);
		});
		return generated.duties;
	}

	PathFailure failure = PathFailure.NONE;

	final List<Siding> sidings;
	/**
	 * The sidings of the other depots of the depot's planning unit, or none.
	 */
	List<Siding> lineSidings = Collections.emptyList();
	final boolean repeatPath;
	/**
	 * The path turns back from the last route to the first stop of the first one, so trains go round without the depot.
	 */
	final boolean closesPath;
	/**
	 * The depot runs by duties ({@link #runsByDutiesWith}): each route is only checked, and the trains run on their
	 * duties' paths, so the order of the routes doesn't matter and they needn't join one to the next.
	 */
	final boolean dutiesOnly;
	/**
	 * The loop that trains join part of the way round, with the stop where they join it.
	 */
	final Route loopRoute;
	final int loopEntryStop;
	final List<Route> mainRoutes;
	final DataCache dataCache;
	/**
	 * The routes' own paths, for a depot running by a timetable, or null to search each route here as before.
	 */
	RoutePaths.Search routeSearch;
	final List<SavedRailBase> platformsMerged = new ArrayList<>();
	final List<List<SavedRailBase>> platformsInRoute = new ArrayList<>();
	final List<List<PathData>> pathsInRoute = new ArrayList<>();
	final List<List<List<BlockPos>>> waypointsInRoute = new ArrayList<>();
	final List<List<Route.RoutePlatform>> stopSettingsInRoute = new ArrayList<>();

	DutyPathBuilder(Depot depot, List<Route> routes, List<Siding> sidings, DataCache dataCache) {
		this.depot = depot;
		this.sidings = sidings;
		mainRoutes = routes;
		this.dataCache = dataCache;

		routes.forEach(route -> {
			final List<PathData> routePath = new ArrayList<>();
			route.getPathData().forEach(pathData -> routePath.add(pathData.copy(pathData.savedRailBaseId, pathData.dwellTime)));
			final List<SavedRailBase> routePlatforms = new ArrayList<>();
			final List<List<BlockPos>> routeWaypoints = new ArrayList<>();
			final List<Route.RoutePlatform> routeStopSettings = new ArrayList<>();

			if (routePath.isEmpty()) {
				route.platformIds.forEach(platformId -> {
					final Platform platform = dataCache.platformIdMap.get(platformId.platformId);
					if (platform != null) {
						routePlatforms.add(platform);
						routeWaypoints.add(platformId.waypoints);
						routeStopSettings.add(platformId);
						if (platformsMerged.isEmpty() || platform.id != platformsMerged.get(platformsMerged.size() - 1).id) {
							platformsMerged.add(platform);
						}
					}
				});
			} else {
				for (int i = 0; i < route.platformIds.size(); i++) {
					final Platform platform = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
					if (platform != null) {
						routePlatforms.add(platform);
						if (i != 0 || platformsMerged.isEmpty() || platform.id != platformsMerged.get(platformsMerged.size() - 1).id) {
							platformsMerged.add(platform);
						}
					}
				}
			}

			platformsInRoute.add(routePlatforms);
			pathsInRoute.add(routePath);
			waypointsInRoute.add(routeWaypoints);
			stopSettingsInRoute.add(routeStopSettings);
		});

		// A timetabled line that ends where it starts runs lap after lap, and only returns to the depot when service ends
		final boolean hasTimetable = depot.findTimetabledRoute(routes) != null;
		dutiesOnly = depot.runsByDutiesWith(routes);
		// Trains running by duties go round on their duties' paths, so the routes aren't joined up into a lap
		closesPath = !dutiesOnly && depot.cycleRoutes && platformsMerged.size() > 1 && platformsMerged.get(0).id != platformsMerged.get(platformsMerged.size() - 1).id;
		if (closesPath) {
			platformsMerged.add(platformsMerged.get(0));
		}
		repeatPath = depot.repeatInfinitely || closesPath || hasTimetable && platformsMerged.size() > 1 && platformsMerged.get(0).id == platformsMerged.get(platformsMerged.size() - 1).id;
		final boolean canJoinLoop = routes.size() == 1 && repeatPath && hasTimetable && routes.get(0).getPathData().isEmpty();
		loopRoute = canJoinLoop ? routes.get(0) : null;
		loopEntryStop = canJoinLoop ? routes.get(0).getLoopEntryStop() : 0;
	}

	/**
	 * Finds where services of the last route carry on onto other routes. Each way through only needs one path, however
	 * many services use it. A route that can't be reached is left out, and its services stay on the main path.
	 */
	List<TripTail<PathData, SavedRailBase>> getTripTails(List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final List<TripTail<PathData, SavedRailBase>> tripTails = new ArrayList<>();
		final Route lastRoute = mainRoutes.isEmpty() ? null : mainRoutes.get(mainRoutes.size() - 1);
		if (lastRoute == null || mainPath.isEmpty() || depot.getActiveTimetable(lastRoute) == null) {
			return tripTails;
		}

		final Set<String> keys = new HashSet<>();
		lastRoute.timetable.getTables().forEach(table -> table.links.values().forEach(link -> {
			final List<RouteTimetable.ServiceLink> links = RouteTimetable.getLinkChain(link, routeId -> {
				final Route route = dataCache.routeIdMap.get(routeId);
				return route == null ? null : route.timetable;
			});
			if (keys.add(TripTail.getKey(links))) {
				final TripTail<PathData, SavedRailBase> tripTail = getTripTail(lastRoute, links, mainPath, rails, graph, useFastSpeed);
				if (tripTail != null) {
					tripTails.add(tripTail);
				}
			}
		}));
		return tripTails;
	}

	TripTail<PathData, SavedRailBase> getTripTail(Route lastRoute, List<RouteTimetable.ServiceLink> links, List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final int leaveRail = isCut(lastRoute, links.get(0)) ? Siding.getStopRail(mainPath, lastRoute.platformIds, links.get(0).fromStop) : mainPath.size() - 1;
		if (leaveRail < 0) {
			return null;
		}

		final List<Route> tripRoutes = new ArrayList<>(mainRoutes);
		final Map<Long, Integer> cutStops = new HashMap<>();
		final Map<Long, Integer> entryStops = new HashMap<>();
		final Map<Long, List<PathData>> entryLeads = new HashMap<>();
		final List<PathData> tailPath = new ArrayList<>();
		Route previousRoute = lastRoute;
		SavedRailBase lastPlatform = null;
		int lapStart = TripTail.NO_RAIL;
		int entryRail = TripTail.NO_RAIL;
		boolean isLapReversed = false;

		for (int i = 0; i < links.size(); i++) {
			final RouteTimetable.ServiceLink link = links.get(i);
			final RouteTimetable.ServiceLink nextLink = i + 1 < links.size() ? links.get(i + 1) : null;
			final Route nextRoute = dataCache.routeIdMap.get(link.routeId);
			final boolean isCut = isCut(previousRoute, link);
			final int leaveStop = isCut ? link.fromStop : previousRoute.platformIds.size() - 1;
			final Platform leavePlatform = previousRoute.platformIds.isEmpty() ? null : dataCache.platformIdMap.get(previousRoute.platformIds.get(leaveStop).platformId);
			if (nextRoute == null || nextRoute.platformIds.isEmpty() || nextRoute.transportMode != depot.transportMode || leavePlatform == null) {
				return null;
			}
			if (isCut) {
				cutStops.put(previousRoute.id, leaveStop);
			}

			final List<SavedRailBase> nextPlatforms = new ArrayList<>();
			final List<List<BlockPos>> nextWaypoints = new ArrayList<>();
			final List<Route.RoutePlatform> nextStopSettings = new ArrayList<>();
			nextRoute.platformIds.forEach(routePlatform -> {
				final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
				if (platform != null) {
					nextPlatforms.add(platform);
					nextWaypoints.add(routePlatform.waypoints);
					nextStopSettings.add(routePlatform);
				}
			});
			if (nextPlatforms.size() < 2) {
				return null;
			}

			// A train on a loop at the end of its trip stays on it, and can join it at any stop. Other routes can be joined
			// part of the way along at a timetable boundary, which the links were checked against when they were saved.
			final boolean isLap = nextLink == null && nextRoute.isLoop() && depot.getActiveTimetable(nextRoute) != null;
			final int entryStop = nextPlatforms.size() == nextRoute.platformIds.size() && link.toStop > 0 && link.toStop < nextPlatforms.size() - 1 ? link.toStop : 0;
			final SavedRailBase entryPlatform = nextPlatforms.get(entryStop);

			if (entryPlatform.id != leavePlatform.id) {
				final List<PathData> connectingPath = new ArrayList<>();
				// A through service turns back on the first turn back rail the route lists, without choosing between them
				final List<BlockPos> turnBackNodes = isCut ? Collections.emptyList() : getTurnBackNodes(previousRoute, graph);
				if (turnBackNodes.isEmpty()) {
					PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(leavePlatform, entryPlatform), null, 1, depot.cruisingAltitude, useFastSpeed);
				} else {
					final TurnBackSection<PathData> turnBackSection = findTurnBackSection(leavePlatform, entryPlatform, turnBackNodes, 0, rails, graph, useFastSpeed);
					if (turnBackSection != null) {
						connectingPath.addAll(turnBackSection.getDefaultWay());
					}
				}
				if (connectingPath.isEmpty()) {
					return null;
				}
				PathFinder.appendPath(tailPath, connectingPath);
			}
			final List<PathData> routePath = new ArrayList<>();
			// The route's own path where the depot takes its routes' paths from the routes
			final RoutePaths.RoutePath ownPath = usesRoutePaths() && nextPlatforms.size() == nextRoute.platformIds.size() ? routeSearch.get(nextRoute, dataCache) : null;
			if (ownPath != null) {
				if (ownPath.isFound()) {
					routePath.addAll(ownPath.getPath(nextStopSettings));
				}
			} else {
				PathFinder.findPath(routePath, rails, graph, nextPlatforms, nextWaypoints, 1, depot.cruisingAltitude, useFastSpeed);
				PathFinder.applyStopSettings(routePath, nextStopSettings);
			}
			if (routePath.size() <= 2) {
				return null;
			}

			// Stops are counted through every route before this one in full, so this route starts after all of them
			tripRoutes.add(nextRoute);
			int stopIndex = getStopCount(tripRoutes) + 1;
			for (int j = 0; j < routePath.size(); j++) {
				final PathData pathData = routePath.get(j);
				if (j > 0 && pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && routePath.get(j - 1).rail.railType != RailType.PLATFORM) {
					stopIndex++;
				}
				pathData.stopIndex = stopIndex;
			}

			final int joinRail = entryStop > 0 ? Siding.getStopRail(routePath, nextRoute.platformIds, entryStop) : 0;
			if (joinRail < 0) {
				return null;
			}
			if (entryStop > 0 && isLap) {
				final List<PathData> joinPath = new ArrayList<>();
				for (int j = joinRail; j < routePath.size(); j++) {
					final PathData pathData = routePath.get(j);
					joinPath.add(pathData.copy(pathData.savedRailBaseId, pathData.dwellTime));
				}
				entryRail = tailPath.size() - 1;
				addToTail(tailPath, mainPath.get(leaveRail), joinPath);
			} else if (entryStop > 0) {
				// The train runs from the stop where it joins, and plans by the way from the first stop to there
				entryStops.put(nextRoute.id, entryStop);
				entryLeads.put(nextRoute.id, new ArrayList<>(routePath.subList(0, joinRail + 1)));
			}

			final List<PathData> usedPath;
			final int usedStart = isLap ? 0 : joinRail;
			if (nextLink != null && isCut(nextRoute, nextLink)) {
				final int cutRail = Siding.getStopRail(routePath, nextRoute.platformIds, nextLink.fromStop);
				if (cutRail <= usedStart) {
					return null;
				}
				usedPath = new ArrayList<>(routePath.subList(usedStart, cutRail + 1));
			} else {
				usedPath = new ArrayList<>(routePath.subList(usedStart, routePath.size()));
			}

			if (isLap) {
				final PathData lastRail = tailPath.isEmpty() ? mainPath.get(leaveRail) : tailPath.get(tailPath.size() - 1);
				lapStart = tailPath.size() - 1;
				isLapReversed = usedPath.get(0).isOppositeRail(lastRail);
			}
			addToTail(tailPath, mainPath.get(leaveRail), usedPath);

			previousRoute = nextRoute;
			lastPlatform = nextPlatforms.get(nextPlatforms.size() - 1);
		}

		final List<Long> tripRouteIds = new ArrayList<>();
		tripRoutes.forEach(route -> tripRouteIds.add(route.id));
		return new TripTail<>(TripTail.getKey(links), tripRouteIds, cutStops, leaveRail, tailPath, lastPlatform, lapStart, isLapReversed, entryRail, entryStops, entryLeads);
	}

	/**
	 * Each shape of duty a siding's slots run, once (design document 6.12, block E2).
	 */
	/**
	 * @param dutiesToPlan the depot's duties, or a draft of them
	 * @param failed       told about the key of each duty shape whose plan can't be found
	 */
	List<DutyPlan<PathData, SavedRailBase>> getDutyPlans(DepotDuties dutiesToPlan, Siding siding, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, Consumer<String> failed) {
		final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = new ArrayList<>();
		final Set<String> keys = new HashSet<>();
		// The duties of every siding of its pool, as its trains may run them in place of the other sidings' trains
		for (final Siding poolSiding : getPoolSidings(siding)) {
			final List<DepotDuties.Slot> slots = dutiesToPlan.getSlots(poolSiding.id);
			for (int i = 0; i < slots.size(); i++) {
				for (int weekday = 0; weekday < 7; weekday++) {
					final String key = DutyShapes.getSlotKey(slots, i, weekday, dataCache.routeIdMap::get);
					if (key == null || !keys.add(key)) {
						continue;
					}
					final DepotDuties.DayDuty day = slots.get(i).getDuty(weekday);
					final DutyPlan<PathData, SavedRailBase> dutyPlan = getDutyPlan(key, day.segments, weekday, DutyShapes.getOvernightSlot(slots, i, weekday) >= 0, day.stayOnLine, siding, rails, graph, useFastSpeed);
					if (dutyPlan == null) {
						failure = new PathFailure(PathFailure.Type.DUTY, 0);
						failed.accept(key);
					} else {
						dutyPlans.add(dutyPlan);
					}
				}
			}
		}
		return dutyPlans;
	}

	/**
	 * @return the sidings of the group with the same vehicle as this one, itself first, then those of the other depots of
	 * its planning unit with the same vehicle, which its duties may go back to
	 */
	List<Siding> getPoolSidings(Siding siding) {
		final List<Siding> poolSidings = new ArrayList<>();
		poolSidings.add(siding);
		sidings.stream().filter(other -> other != siding && other.getPoolKey().equals(siding.getPoolKey())).sorted(Comparator.comparingLong(other -> other.id)).forEach(poolSidings::add);
		lineSidings.stream().filter(other -> other.getPoolKey().equals(siding.getPoolKey())).sorted(Comparator.comparingLong(other -> other.id)).forEach(poolSidings::add);
		// Every siding of the pool in the depot, whatever routes it is given, as the plan sends trains back to the nearest
		// of them (round 4, step 3)
		dataCache.sidingIdMap.values().stream().filter(other -> !poolSidings.contains(other) && dataCache.sidingIdToDepot.get(other.id) == depot && other.getPoolKey().equals(siding.getPoolKey())).sorted(Comparator.comparingLong(other -> other.id)).forEach(poolSidings::add);
		return poolSidings;
	}

	/**
	 * Joins up a duty's segments: each service from where it joins its route to where it leaves, with the stops it passes
	 * passed, which is how a single service's passing or stopping becomes real, then on to the next segment the way the
	 * depot's own path would turn back.
	 *
	 * @return the plan, or null if a part of it can't be found
	 */
	DutyPlan<PathData, SavedRailBase> getDutyPlan(String key, List<DepotDuties.Segment> segments, int weekday, boolean startsOnLine, boolean staysOnLine, Siding siding, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final List<PathData> body = new transitsim.plan.JoinedPath<>();
		final Map<String, List<PathData>> segmentTemplates = new HashMap<>();
		final Set<Integer> indexBoundaries = new TreeSet<>();
		indexBoundaries.add(0);
		final int[] segmentStarts = new int[segments.size()];
		final List<Long> routeIds = new ArrayList<>();
		final Map<Long, Integer> cutStops = new HashMap<>();
		final Map<Long, Integer> entryStops = new HashMap<>();
		final Map<Long, List<PathData>> entryLeads = new HashMap<>();
		// Where each segment is cut and joined, by segment, as a route can be in several (D4)
		final Map<Integer, Integer> segmentCuts = new HashMap<>();
		final Map<Integer, Integer> segmentEntries = new HashMap<>();
		SavedRailBase firstPlatform = null;
		SavedRailBase lastPlatform = null;
		Route previousRoute = null;
		boolean previousRanToEnd = false;
		final List<TurnBackSection<PathData>> turnBackSections = new ArrayList<>();
		List<SavedRailBase> previousPlatforms = Collections.emptyList();
		List<List<BlockPos>> previousWaypoints = Collections.emptyList();
		int previousStart = 0;

		for (int k = 0; k < segments.size(); k++) {
			final DepotDuties.Segment segment = segments.get(k);
			final Route route = dataCache.routeIdMap.get(segment.routeId);
			if (route == null || route.transportMode != depot.transportMode || route.platformIds.size() < 2) {
				return null;
			}
			final int firstStop = DutyShapes.getFirstStop(route, segment);
			final int lastStop = DutyShapes.getLastStop(route, segment);
			final boolean[] pass = DutyShapes.getPassFlags(route, segment, weekday);
			final List<SavedRailBase> platforms = new ArrayList<>();
			final List<List<BlockPos>> waypoints = new ArrayList<>();
			final List<Route.RoutePlatform> stopSettings = new ArrayList<>();
			for (int i = firstStop; i <= lastStop; i++) {
				final Route.RoutePlatform routePlatform = route.platformIds.get(i);
				final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
				if (platform == null) {
					return null;
				}
				platforms.add(platform);
				waypoints.add(routePlatform.waypoints);
				final Route.RoutePlatform stopSetting = new Route.RoutePlatform(routePlatform.platformId);
				stopSetting.pass = pass[i - firstStop];
				stopSetting.dwellOverride = routePlatform.dwellOverride;
				stopSettings.add(stopSetting);
			}
			// Out of the depot, a train may start at another platform of the first stop's station that the route lists, where
			// its siding has a way out only to that one (2026-11-13, 单独出库站台): the way from there to the second stop is found
			// rather than the route's own
			final Siding.DepotWays depotWays = Siding.DEPOT_WAYS.get();
			final long startId = k == 0 && !startsOnLine && firstStop == 0 && siding != null && depotWays != null ? depotWays.startAt(siding, platforms.get(0).id) : platforms.get(0).id;
			final Platform startPlatform = startId == platforms.get(0).id ? null : dataCache.platformIdMap.get(startId);
			if (startPlatform != null) {
				platforms.set(0, startPlatform);
				waypoints.set(0, Collections.emptyList());
				final Route.RoutePlatform startSetting = new Route.RoutePlatform(startId);
				startSetting.pass = stopSettings.get(0).pass;
				startSetting.dwellOverride = stopSettings.get(0).dwellOverride;
				stopSettings.set(0, startSetting);
			}
			final RoutePaths.RoutePath ownPath = usesRoutePaths() && startPlatform == null ? routeSearch.get(route, dataCache) : null;
			final String templateKey = route.id + ":" + firstStop + ":" + lastStop + ":" + route.speedLimit + ":" + Arrays.toString(pass) + (startPlatform == null ? "" : ":" + startId);
			List<PathData> template = segmentTemplates.get(templateKey);
			if (template == null) {
				template = new ArrayList<>();
				if (ownPath != null) {
					template.addAll(ownPath.getSlice(firstStop, lastStop));
					// A slice starts standing; a duty's train comes to its first stop from the way out or the service
					// before, so it stops there and waits for its departure, as the paths found stop by stop had it
					// (without it trains ran through their first stop out of the depot, U-L05)
					if (!template.isEmpty() && template.get(0).dwellTime <= 0) {
						template.set(0, template.get(0).copy(template.get(0).savedRailBaseId, platforms.get(0).getDwellTime()));
					}
				}
				else PathFinder.findPath(template, rails, graph, platforms, waypoints, 1, depot.cruisingAltitude, useFastSpeed);
				if (template.size() < 2) return null;
				PathFinder.applyStopSettings(template, stopSettings);
				PathData.capSpeed(template, route.speedLimit);
				segmentTemplates.put(templateKey, template);
			}
			final List<PathData> segmentPath = new transitsim.plan.JoinedPath<>(template);

			if (lastPlatform != null && lastPlatform.id != platforms.get(0).id) {
				final List<PathData> connectingPath = new ArrayList<>();
				final TurnBackSection<PathData> turnBackSection = findConnection(connectingPath, lastPlatform, platforms.get(0), previousRanToEnd ? previousRoute : null, rails, graph, useFastSpeed);
				if (connectingPath.isEmpty()) {
					return null;
				}
				// A way between two services far longer than the two platforms are apart is told of: the train runs a
				// long way empty, as where no turn back rail is listed or near
				final double connectingLength = depot.pathLength(connectingPath);
				final double apart = Math.sqrt(lastPlatform.getMidPos().distSqr(platforms.get(0).getMidPos()));
				if (connectingLength > Math.max(depot.DETOUR_MIN_BLOCKS, apart * depot.DETOUR_FACTOR)) {
					depot.pathDetours.add(lastPlatform.id + " " + platforms.get(0).id + " " + Math.round(connectingLength) + " " + Math.round(apart));
				}
				// More than one way round, each from the last stop, which is already the last rail of the body
				if (turnBackSection != null && turnBackSection.ways.size() > 1 && !body.isEmpty() && body.get(body.size() - 1).isSameRail(connectingPath.get(0))) {
					turnBackSections.add(new TurnBackSection<>(body.size() - 1, turnBackSection.ways, turnBackSection.defaultWay));
				}
				indexBoundaries.add(body.size());
				PathFinder.appendPath(body, connectingPath);
				indexBoundaries.add(Math.max(0, body.size() - 1));
			}

			final PathData first = segmentPath.get(0).copy(segmentPath.get(0).savedRailBaseId, segmentPath.get(0).dwellTime);
			segmentPath.set(0, first);
			final PathData last = body.isEmpty() ? null : body.get(body.size() - 1);
			if (last != null && first.isSameRail(last)) {
				segmentStarts[k] = body.size() - 1;
				// Come from another platform to this route's first stop, the train stops there for its departure: the first
				// stop of a route's own path has no stop time, and without the way there's it would run straight through
				final boolean arrivedHere = k > 0 && first.savedRailBaseId != 0 && first.dwellTime == 0 && last.savedRailBaseId == first.savedRailBaseId;
				final int stopDwell = Math.max(last.dwellTime, platforms.get(0).getDwellTime());
				body.set(body.size() - 1, arrivedHere && stopDwell > 0 ? first.copy(first.savedRailBaseId, stopDwell) : first);
				body.addAll(segmentPath.subList(1, segmentPath.size()));
			} else if (last != null && first.isOppositeRail(last)) {
				// Turning back on the platform: the stop is the arrival there, and the platform reversed is only the way out
				first.dwellTime = 0;
				first.savedRailBaseId = 0;
				segmentStarts[k] = body.size() - 1;
				body.addAll(segmentPath);
			} else {
				segmentStarts[k] = body.size();
				body.addAll(segmentPath);
			}

			// The route before may turn back at other platforms of its last station (站前折返)
			if (k > 0 && previousRanToEnd && firstStop == 0) {
				addFrontTurnBack(body, previousRoute, previousPlatforms, previousWaypoints, previousStart, route, platforms, waypoints, segmentStarts[k], turnBackSections, rails, graph, useFastSpeed);
			}

			if (firstPlatform == null) {
				firstPlatform = platforms.get(0);
			}
			lastPlatform = platforms.get(platforms.size() - 1);
			routeIds.add(route.id);
			if (lastStop < route.platformIds.size() - 1) {
				cutStops.putIfAbsent(route.id, lastStop);
				segmentCuts.put(k, lastStop);
			}
			if (firstStop > 0) {
				segmentEntries.put(k, firstStop);
			}
			if (firstStop > 0 && !entryStops.containsKey(route.id)) {
				// The plan of a route joined part of the way along counts from its first stop, so the way there is timed too
				final List<SavedRailBase> leadPlatforms = new ArrayList<>();
				final List<List<BlockPos>> leadWaypoints = new ArrayList<>();
				final List<Route.RoutePlatform> leadSettings = new ArrayList<>();
				for (int i = 0; i <= firstStop; i++) {
					final Platform platform = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
					if (platform != null) {
						leadPlatforms.add(platform);
						leadWaypoints.add(route.platformIds.get(i).waypoints);
						leadSettings.add(route.platformIds.get(i));
					}
				}
				final List<PathData> leadPath = new ArrayList<>();
				if (ownPath != null) {
					leadPath.addAll(ownPath.getSlice(0, firstStop));
				} else {
					PathFinder.findPath(leadPath, rails, graph, leadPlatforms, leadWaypoints, 1, depot.cruisingAltitude, useFastSpeed);
				}
				if (leadPath.size() >= 2 && leadPlatforms.size() == firstStop + 1) {
					PathFinder.applyStopSettings(leadPath, leadSettings);
					entryStops.put(route.id, firstStop);
					entryLeads.put(route.id, leadPath);
				}
			}
			previousRoute = route;
			previousRanToEnd = lastStop == route.platformIds.size() - 1;
			previousPlatforms = platforms;
			previousWaypoints = waypoints;
			previousStart = segmentStarts[k];
		}
		final DutyPlan<PathData, SavedRailBase> dutyPlan = new DutyPlan<>(key, routeIds, cutStops, entryStops, entryLeads, body, segmentStarts, firstPlatform, lastPlatform, startsOnLine, staysOnLine);
		for (int start : segmentStarts) indexBoundaries.add(start);
		indexBoundaries.add(body.size());
		dutyPlan.indexBoundaries = indexBoundaries.stream().mapToInt(Integer::intValue).toArray();
		dutyPlan.turnBackSections.addAll(turnBackSections);
		dutyPlan.segmentCuts.putAll(segmentCuts);
		dutyPlan.segmentEntries.putAll(segmentEntries);
		dutyPlan.segmentsKnown = true;
		return dutyPlan;
	}

	/**
	 * Where a route ends at a station whose other platforms it lists to turn back at (站前折返), and the next segment's
	 * route starts in that station, adds the ways from the stop before the last to the next route's second stop, one at
	 * each of those platforms, in the order the next route would rather start from (种别优先). The usual way is one of
	 * them when the train usually turns back at its last platform, and the last when it turns back after the station
	 * (站后), so the platforms come first and the way after the station is taken only when none of them does.
	 *
	 * @param previousStart the rail of the body where the route before starts
	 * @param nextStart     the rail of the body where the next route starts
	 */
	void addFrontTurnBack(List<PathData> body, Route previousRoute, List<SavedRailBase> previousPlatforms, List<List<BlockPos>> previousWaypoints, int previousStart, Route route, List<SavedRailBase> platforms, List<List<BlockPos>> waypoints, int nextStart, List<TurnBackSection<PathData>> turnBackSections, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final long start = ConnectionStats.ON ? System.nanoTime() : 0;
		try {
			addFrontTurnBackTimed(body, previousRoute, previousPlatforms, previousWaypoints, previousStart, route, platforms, waypoints, nextStart, turnBackSections, rails, graph, useFastSpeed);
		} finally {
			if (ConnectionStats.ON) {
				ConnectionStats.front(previousRoute.id + ">" + route.id, System.nanoTime() - start);
			}
		}
	}

	void addFrontTurnBackTimed(List<PathData> body, Route previousRoute, List<SavedRailBase> previousPlatforms, List<List<BlockPos>> previousWaypoints, int previousStart, Route route, List<SavedRailBase> platforms, List<List<BlockPos>> waypoints, int nextStart, List<TurnBackSection<PathData>> turnBackSections, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		if (previousRoute.terminalPlatforms.isEmpty() || previousPlatforms.size() < 2 || platforms.size() < 2 || graph == null) {
			return;
		}
		final long lastPlatformId = previousRoute.getLastPlatformId();
		final Station station = dataCache.platformIdToStation.get(lastPlatformId);
		if (station == null || dataCache.platformIdToStation.get(platforms.get(0).id) != station) {
			return;
		}
		final int stopRail = findStopRail(body, previousStart, previousPlatforms.get(previousPlatforms.size() - 2).id);
		final int lastRail = stopRail < 0 ? -1 : findStopRail(body, stopRail + 1, lastPlatformId);
		final int secondRail = lastRail < 0 ? -1 : findStopRail(body, Math.max(nextStart, lastRail) + 1, platforms.get(1).id);
		if (secondRail < 0) {
			return;
		}
		final List<PathData> usualWay = new ArrayList<>(body.subList(stopRail, secondRail + 1));
		// The rules are the component's (step 2c of splitting the depot); the ways are found here
		final long nextFirstPlatformId = platforms.get(0).id;
		final List<Long> ordered = FrontTurnBacks.order(lastPlatformId, nextFirstPlatformId, previousRoute.terminalPlatforms, route.startPlatforms,
				platformId -> dataCache.platformIdToStation.get(platformId) == station && dataCache.platformIdMap.containsKey(platformId));
		final TurnBackSection<PathData> section = FrontTurnBacks.section(stopRail, lastPlatformId, nextFirstPlatformId, ordered, usualWay, new FrontTurnBacks.Ways<PathData>() {
			@Override
			public List<PathData> find(long platformId) {
				return findFrontWay(previousPlatforms.get(previousPlatforms.size() - 2), dataCache.platformIdMap.get(platformId), platforms.get(1), previousWaypoints.get(previousWaypoints.size() - 2), waypoints.get(0), body.get(lastRail).dwellTime, usualWay, rails, graph, useFastSpeed);
			}

			@Override
			public void unusable(long platformId) {
				failure = new PathFailure(PathFailure.Type.TURN_BACK_UNUSED, dataCache.platformIdMap.get(platformId).getMidPos().asLong());
			}
		});
		if (section != null) {
			turnBackSections.add(section);
		}
	}

	/**
	 * @return the way from a stop into a platform and back out of it to the next route's second stop, turning back on the
	 * platform, or null if there is none that leaves and reaches the stops along the same rails as the usual way (any
	 * way, with no usual way given: for the turn times)
	 */
	List<PathData> findFrontWay(SavedRailBase from, Platform platform, SavedRailBase to, List<BlockPos> fromWaypoints, List<BlockPos> toWaypoints, int dwellTime, List<PathData> usualWay, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		if (platform == null) {
			return null;
		}
		final List<PathData> wayIn = new ArrayList<>();
		PathFinder.findPath(wayIn, rails, graph, Arrays.asList(from, platform), Collections.singletonList(fromWaypoints), 1, depot.cruisingAltitude, useFastSpeed);
		final List<PathData> wayOut = new ArrayList<>();
		PathFinder.findPath(wayOut, rails, graph, Arrays.asList(platform, to), Collections.singletonList(toWaypoints), 1, depot.cruisingAltitude, useFastSpeed);
		if (wayIn.size() < 2 || wayOut.size() < 2) {
			return null;
		}
		// The stop is the arrival at the platform, with the stop time of the route's last stop; the platform reversed is
		// only the way out
		final PathData arrival = wayIn.get(wayIn.size() - 1);
		arrival.dwellTime = dwellTime;
		final PathData leaving = wayOut.get(0);
		if (!leaving.isOppositeRail(arrival)) {
			return null;
		}
		leaving.dwellTime = 0;
		leaving.savedRailBaseId = 0;
		wayIn.addAll(wayOut);
		return usualWay == null || wayIn.get(0).isSameRail(usualWay.get(0)) && wayIn.get(wayIn.size() - 1).isSameRail(usualWay.get(usualWay.size() - 1)) ? wayIn : null;
	}

	/**
	 * @return the first rail from this one on that is this platform, or -1
	 */
	static int findStopRail(List<PathData> body, int from, long platformId) {
		for (int i = Math.max(from, 0); i < body.size(); i++) {
			if (body.get(i).savedRailBaseId == platformId && body.get(i).rail.railType == RailType.PLATFORM) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @return whether the routes' paths are taken from the routes: for a depot running by a timetable, not moving
	 * continuously and not of airplanes, which search their whole way as before
	 */
	boolean usesRoutePaths() {
		return routeSearch != null && depot.findTimetabledRoute(mainRoutes) != null && !depot.transportMode.continuousMovement && depot.transportMode != TransportMode.AIRPLANE;
	}

	boolean isCut(Route route, RouteTimetable.ServiceLink link) {
		return link.fromStop >= 0 && link.fromStop < route.platformIds.size() - 1;
	}

	/**
	 * @return the number of stops before the last route of these routes
	 */
	int getStopCount(List<Route> routes) {
		int stopCount = 0;
		Route previousRoute = null;
		for (int i = 0; i < routes.size(); i++) {
			final Route route = routes.get(i);
			if (i < routes.size() - 1) {
				stopCount += route.platformIds.size();
			}
			if (previousRoute != null && previousRoute.getLastPlatformId() == route.getFirstPlatformId()) {
				stopCount--;
			}
			previousRoute = route;
		}
		return stopCount;
	}

	void addToTail(List<PathData> tailPath, PathData lastMainRail, List<PathData> newPath) {
		final PathData first = newPath.get(0);
		final PathData last = tailPath.isEmpty() ? lastMainRail : tailPath.get(tailPath.size() - 1);
		if (first.isOppositeRail(last)) {
			first.dwellTime = 0;
			first.savedRailBaseId = 0;
		} else if (first.isSameRail(last)) {
			newPath.remove(0);
		}
		tailPath.addAll(newPath);
	}

	/**
	 * Adds the way from the last stop of a route to the first stop of the next one, round the turn back rails the route lists
	 * after its last stop if it has any.
	 *
	 * @param routeBefore the route whose last stop this is, or -1 if it has no stops
	 * @return false if none of the turn back rails can be reached, which fails the path
	 */
	/**
	 * For a depot running by duties: checks each route's own path, reporting the first that can't be found, then gives
	 * each siding its duties' paths and the siding alone as its path.
	 *
	 * @return {@link #DUTIES_ONLY_FOUND} if every route was found, otherwise 0
	 */
	int generateDutiesOnly(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, LineDuties.Plan linePlan) {
		for (int i = 0; i < pathsInRoute.size(); i++) {
			final List<SavedRailBase> platforms = platformsInRoute.get(i);
			if (platforms.size() < 2 || !pathsInRoute.get(i).isEmpty()) {
				continue;
			}
			final RoutePaths.RoutePath ownPath = usesRoutePaths() ? routeSearch.get(mainRoutes.get(i), dataCache) : null;
			if (ownPath != null) {
				if (!ownPath.isFound() && failure == PathFailure.NONE) {
					failure = ownPath.failure == PathFailure.NONE ? new PathFailure(PathFailure.Type.NO_ROUTE, 0) : ownPath.failure;
				}
			} else {
				final List<PathData> routePath = new ArrayList<>();
				final int reached = PathFinder.findPath(routePath, rails, graph, platforms, waypointsInRoute.get(i), 1, depot.cruisingAltitude, useFastSpeed);
				if (routePath.isEmpty() && failure == PathFailure.NONE) {
					failure = PathFailure.diagnose(graph, platforms, waypointsInRoute.get(i), reached);
				}
			}
		}

		final DepotDuties runDuties = depot.duties.diagramMode ? depot.duties : generateAutoDuties(minecraftServer, linePlan);
		// Ways round for turning back early, found once for each pair of platforms in this generation (D4)
		final Map<String, List<PathData>> turnLinkCache = new HashMap<>();
		for (final Siding siding : sidings) {
			final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = runDuties != null ? getDutyPlans(runDuties, siding, rails, graph, useFastSpeed, key -> {
			}) : new ArrayList<>();
			setTurnLinks(siding, dutyPlans, turnLinkCache, rails, graph, useFastSpeed);
			siding.generateDutyRoute(minecraftServer, rails, graph, depot.cruisingAltitude, useFastSpeed, dutyPlans, getPoolSidings(siding));
		}
		return failure == PathFailure.NONE ? depot.DUTIES_ONLY_FOUND : 0;
	}

	boolean addConnection(List<PathData> tempPath, SavedRailBase from, SavedRailBase to, int routeBefore, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final int start = tempPath.size();
		final List<BlockPos> listedNodes = routeBefore >= 0 ? getTurnBackNodes(mainRoutes.get(routeBefore), graph) : Collections.emptyList();
		// With the stabling blocks near the platform as more ways round (运用补完 step 4)
		final List<BlockPos> turnBackNodes = listedNodes.isEmpty() ? listedNodes : withStabling(listedNodes, from, rails);
		final List<PathData> connectingPath = new ArrayList<>();
		if (turnBackNodes.isEmpty()) {
			PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(from, to), null, 1, depot.cruisingAltitude, useFastSpeed);
		} else {
			final TurnBackSection<PathData> turnBackSection = findTurnBackSection(from, to, turnBackNodes, start, rails, graph, useFastSpeed);
			if (turnBackSection == null) {
				failure = new PathFailure(PathFailure.Type.TURN_BACK, turnBackNodes.get(0).asLong());
				tempPath.clear();
				return false;
			}
			connectingPath.addAll(turnBackSection.getDefaultWay());
			turnBackSections.add(turnBackSection);
		}
		tempPath.addAll(connectingPath);
		// Only trains staying on the line need a way back to the depot after a route
		if (depot.cycleRoutes && routeBefore >= 0 && !connectingPath.isEmpty()) {
			depotReturns.add(new DepotReturn<>(routeBefore, start, connectingPath, from));
		}
		return true;
	}

	/**
	 * Nodes a route lists after its last stop are the ends of the turn back rails it can use there, in the order trains try them.
	 */
	List<BlockPos> getTurnBackNodes(Route route, RailGraph<Rail> graph) {
		// The old search can't follow nodes
		return graph == null || route.platformIds.isEmpty() ? Collections.emptyList() : route.platformIds.get(route.platformIds.size() - 1).waypoints;
	}

	/**
	 * Finds a way from one stop to the next round each turn back node. Every way round has to leave the first stop and reach
	 * the next one along the same rails as the first way that can be used, so a train can take any of them from the stop.
	 *
	 * @param start where the ways round start on the main path
	 * @return the ways round, or null if none of them can be used
	 */
	/**
	 * Finds where the siding's duties can turn back early (D4) and tells the siding: from each stop of a segment but its
	 * last, round to each platform of the same station the next segment stops at but its last, the way a duty turns back
	 * between two segments ({@link #findConnection}), if there is one no longer than {@link #MAX_TURN_LINK_BLOCKS}.
	 */
	Map<String, List<PathData>> setTurnLinks(Siding siding, List<DutyPlan<PathData, SavedRailBase>> dutyPlans, Map<String, List<PathData>> cache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final Map<String, List<PathData>> links = new HashMap<>();
		final Map<Long, SavedRailBase> targets = new HashMap<>();
		for (final DutyPlan<PathData, SavedRailBase> dutyPlan : dutyPlans) {
			for (int k = 0; k + 1 < dutyPlan.segmentStarts.length; k++) {
				final List<Long> from = getSegmentStops(dutyPlan, k);
				final List<Long> to = getSegmentStops(dutyPlan, k + 1);
				for (int i = 0; i + 1 < from.size(); i++) {
					final Station station = dataCache.platformIdToStation.get(from.get(i));
					for (int j = 0; j + 1 < to.size(); j++) {
						final long fromId = from.get(i);
						final long toId = to.get(j);
						final Station toStation = dataCache.platformIdToStation.get(toId);
						final Platform fromPlatform = dataCache.platformIdMap.get(fromId);
						final Platform toPlatform = dataCache.platformIdMap.get(toId);
						if (fromId == toId || station == null || toStation == null || station.id != toStation.id || fromPlatform == null || toPlatform == null) {
							continue;
						}
						final List<PathData> link = cache.computeIfAbsent(fromId + ">" + toId, key -> {
							final List<PathData> connectingPath = new ArrayList<>();
							findConnection(connectingPath, fromPlatform, toPlatform, null, rails, graph, useFastSpeed);
							return connectingPath.size() >= 2 && depot.pathLength(connectingPath) <= depot.MAX_TURN_LINK_BLOCKS ? connectingPath : Collections.emptyList();
						});
						if (!link.isEmpty()) {
							links.put(fromId + ">" + toId, link);
							targets.put(toId, toPlatform);
						}
					}
				}
			}
		}
		// Handing a duty over (D5): ways back from where each segment but the last ends, and ways out to where each but the
		// first starts, the platform a segment starting by turning back on it arrives on
		final Map<Long, SavedRailBase> wayOutTargets = new HashMap<>();
		for (final DutyPlan<PathData, SavedRailBase> dutyPlan : dutyPlans) {
			for (int k = 0; k < dutyPlan.segmentStarts.length; k++) {
				final List<Long> stops = getSegmentStops(dutyPlan, k);
				if (k + 1 < dutyPlan.segmentStarts.length && !stops.isEmpty()) {
					final Platform end = dataCache.platformIdMap.get(stops.get(stops.size() - 1));
					if (end != null) {
						targets.put(end.id, end);
					}
				}
				if (k > 0) {
					final int start = dutyPlan.segmentStarts[k];
					final long startId = dutyPlan.body.get(start).savedRailBaseId != 0 ? dutyPlan.body.get(start).savedRailBaseId : start > 0 ? dutyPlan.body.get(start - 1).savedRailBaseId : 0;
					final Platform startPlatform = dataCache.platformIdMap.get(startId);
					if (startPlatform != null) {
						wayOutTargets.put(startId, startPlatform);
					}
				}
			}
		}
		siding.setTurnLinks(links, targets, wayOutTargets);
		return links;
	}

	/**
	 * @return the platforms a segment of a duty stops at, in order
	 */
	List<Long> getSegmentStops(DutyPlan<PathData, SavedRailBase> dutyPlan, int segment) {
		final int start = dutyPlan.segmentStarts[segment];
		final int end = segment + 1 < dutyPlan.segmentStarts.length ? dutyPlan.segmentStarts[segment + 1] : dutyPlan.body.size() - 1;
		final List<Long> stops = new ArrayList<>();
		for (int i = start; i <= end && i < dutyPlan.body.size(); i++) {
			final PathData pathData = dutyPlan.body.get(i);
			if (pathData.savedRailBaseId != 0 && pathData.dwellTime > 0 && pathData.rail.railType == RailType.PLATFORM && (stops.isEmpty() || stops.get(stops.size() - 1) != pathData.savedRailBaseId)) {
				stops.add(pathData.savedRailBaseId);
			}
		}
		return stops;
	}

	/**
	 * The way between two services, from where one ends to where the next starts, as a duty runs it: round the turn
	 * back rails the route before lists after its end, or, with none listed, the shorter of the way found and the way
	 * round a turn back rail near the station (站后折返). The planner's times between platforms are measured along it
	 * ({@link #turnTimesFor}), so the plan and the run take the same way.
	 *
	 * @param connectingPath filled with the way, or left empty if there is none
	 * @param routeBefore    the route the train ran to its end before, whose turn back rails are taken, or null
	 * @return the ways round a turn back rail when there are any, or null
	 */
	TurnBackSection<PathData> findConnection(List<PathData> connectingPath, SavedRailBase from, SavedRailBase to, Route routeBefore, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		final long start = ConnectionStats.ON ? System.nanoTime() : 0;
		try {
			// The same way between two services is asked for again for each duty and siding (on a large network 13 000
			// times for 1 900 ways): found once per search, and each asking given a copy of its own, as the duty's path
			// numbers its stops and changes its stops (optimisation round 5, step 5)
			if (connectionsOf != rails) {
				connectionsOf = rails;
				connections.clear();
			}
			final String key = from.id + ">" + to.id + ">" + (routeBefore == null ? 0 : routeBefore.id) + ">" + useFastSpeed;
			Connection connection = connections.get(key);
			if (connection == null) {
				final List<PathData> path = new ArrayList<>();
				final TurnBackSection<PathData> section = findConnectionTimed(path, from, to, routeBefore, rails, graph, useFastSpeed);
				connection = new Connection(path, section);
				connections.put(key, connection);
			}
			return connection.copyInto(connectingPath);
		} finally {
			if (ConnectionStats.ON) {
				ConnectionStats.connection(from.id + ">" + to.id + ">" + (routeBefore == null ? 0 : routeBefore.id), System.nanoTime() - start);
			}
		}
	}

	TurnBackSection<PathData> findConnectionTimed(List<PathData> connectingPath, SavedRailBase from, SavedRailBase to, Route routeBefore, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		// The rules are the component's (step 2c of splitting the depot); the ways are found here
		final List<BlockPos> listedNodes = routeBefore != null ? getTurnBackNodes(routeBefore, graph) : Collections.emptyList();
		final List<BlockPos> turnBackNodes = listedNodes.isEmpty() ? listedNodes : withStabling(listedNodes, from, rails);
		return TurnBackWays.connection(connectingPath, turnBackNodes, () -> {
			final List<PathData> direct = new ArrayList<>();
			PathFinder.findPath(direct, rails, graph, Arrays.asList(from, to), null, 1, depot.cruisingAltitude, useFastSpeed);
			return direct;
		}, () -> getNearbyTurnBackNodes(from, rails), waysRound(from, to, rails, graph, useFastSpeed));
	}

	/**
	 * @return the ways round each turn back node from one stop to the next, as the component asks for them
	 */
	TurnBackWays.Ways<BlockPos, PathData> waysRound(SavedRailBase from, SavedRailBase to, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		return new TurnBackWays.Ways<BlockPos, PathData>() {
			@Override
			public List<PathData> find(BlockPos node) {
				final List<PathData> way = new ArrayList<>();
				PathFinder.findPath(way, rails, graph, Arrays.asList(from, to), Collections.singletonList(Collections.singletonList(node)), 1, depot.cruisingAltitude, useFastSpeed);
				return way;
			}

			@Override
			public boolean sameRail(PathData track, PathData other) {
				return track.isSameRail(other);
			}

			@Override
			public void unusable(BlockPos node) {
				failure = new PathFailure(PathFailure.Type.TURN_BACK_UNUSED, node.asLong());
			}
		};
	}

	/**
	 * How long each change of platform within a station takes as a duty runs it ({@link #findConnection}): from each
	 * route's last platform to each other route's first platform at the same station, with a margin; a pair with no
	 * way between is left out, which the planner takes as no way.
	 *
	 * @param routes the routes planned together
	 * @return from platform → to platform → milliseconds
	 */
	Map<Long, Map<Long, Long>> turnTimesFor(List<Route> routes, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		// The rules are the component's (step 2b of splitting the depot); the ways are found and timed here
		return transitsim.planning.TurnTimes.of(routes, new transitsim.planning.TurnTimes.Host<Route, List<PathData>>() {
			@Override
			public long lastPlatform(Route route) {
				return route.platformIds.isEmpty() ? 0 : route.getLastPlatformId();
			}

			@Override
			public long firstPlatform(Route route) {
				return route.platformIds.isEmpty() ? 0 : route.getFirstPlatformId();
			}

			@Override
			public long station(long platform) {
				final Station station = dataCache.platformIdMap.get(platform) == null ? null : dataCache.platformIdToStation.get(platform);
				return station == null ? 0 : station.id;
			}

			@Override
			public transitsim.planning.TurnTimes.Way<List<PathData>> holdWay(Route before, long platform) {
				// Round a turn back rail beyond the platform and back to it, where a train can wait for its next service
				// there while others use the platform (混合折返)
				final Platform from = dataCache.platformIdMap.get(platform);
				final List<BlockPos> listed = getTurnBackNodes(before, graph);
				final List<BlockPos> nodes = listed.isEmpty() ? getNearbyTurnBackNodes(from, rails) : withStabling(listed, from, rails);
				final TurnBackSection<PathData> hold = nodes.isEmpty() ? null : findTurnBackSection(from, from, nodes, 0, rails, graph, useFastSpeed);
				if (hold == null || hold.getDefaultWay().isEmpty()) {
					return null;
				}
				final List<PathData> way = hold.getDefaultWay();
				// One train can wait on each way round that can be used (运用补完 step 4)
				final long places = hold.ways.stream().filter(Objects::nonNull).count();
				final long[] turnBackRail = way.stream().filter(pathData -> pathData.rail.railType == RailType.TURN_BACK).findFirst().map(pathData -> new long[]{pathData.startingPos.asLong(), pathData.getLastPos().asLong()}).orElse(null);
				return new transitsim.planning.TurnTimes.Way<>(way, legsByVehicle(before, before, way), farthestFrom(from, way), places, turnBackRail);
			}

			@Override
			public transitsim.planning.TurnTimes.Way<List<PathData>> changeWay(Route before, Route after, long fromId, long toId) {
				final Platform from = dataCache.platformIdMap.get(fromId);
				final List<PathData> way = new ArrayList<>();
				findConnection(way, from, dataCache.platformIdMap.get(toId), before, rails, graph, useFastSpeed);
				return way.isEmpty() ? null : new transitsim.planning.TurnTimes.Way<>(way, legsByVehicle(before, after, way), farthestFrom(from, way), 1, null);
			}

			@Override
			public boolean turnsInto(Route before, Route after, long toId) {
				// The way the duty path would add in front of the station ({@link #addFrontTurnBack})
				final int count = before.platformIds.size();
				if (graph == null || !before.terminalPlatforms.contains(toId) || count < 2 || after.platformIds.size() < 2) {
					return false;
				}
				final Platform stopBefore = dataCache.platformIdMap.get(before.platformIds.get(count - 2).platformId);
				final Platform to = dataCache.platformIdMap.get(toId);
				final Platform second = dataCache.platformIdMap.get(after.platformIds.get(1).platformId);
				return stopBefore != null && second != null && findFrontWay(stopBefore, to, second, before.platformIds.get(count - 2).waypoints, after.platformIds.get(0).waypoints, 1, null, rails, graph, useFastSpeed) != null;
			}

			@Override
			public void kept(Route before, Route after, long from, long to, transitsim.planning.TurnTimes.Way<List<PathData>> way, long[] slowest) {
				if (way == null) {
					if (Boolean.getBoolean("mtr.debugTurnTimes")) {
						System.out.println("Minecraft Transit Railway turn time: " + dataCache.platformIdMap.get(from).name + " (" + from + ") to " + dataCache.platformIdMap.get(to).name + " (" + to + "): turns back in front of the station, ending at " + to);
					}
				} else {
					debugTurnTime(dataCache.platformIdMap.get(from), dataCache.platformIdMap.get(to), before, after, way.path, slowest);
				}
			}

			@Override
			public void tooFar(long fromId, long toId, transitsim.planning.TurnTimes.Way<List<PathData>> way) {
				if (Boolean.getBoolean("mtr.debugTurnTimes")) {
					final Platform from = dataCache.platformIdMap.get(fromId);
					final Platform to = dataCache.platformIdMap.get(toId);
					System.out.println("Minecraft Transit Railway turn time: " + from.name + " (" + from.id + ") to " + to.name + " (" + to.id + "): turns back " + countReversals(way.path) + " times, " + Math.round(way.farthest) + " blocks off, not a change of platform");
				}
			}
		}, depot.MAX_PLATFORM_CHANGE_REACH);
	}

	/**
	 * @return the in and out legs along a way for each vehicle running either route ({@link #legMillis})
	 */
	List<long[]> legsByVehicle(Route before, Route after, List<PathData> way) {
		final List<long[]> legs = new ArrayList<>();
		for (final PathTiming.Vehicle vehicle : vehiclesOf(before, after)) {
			legs.add(legMillis(vehicle, way));
		}
		return legs;
	}

	/**
	 * @return the vehicles timed with either route, or the default one without the routes' vehicles
	 */
	Set<PathTiming.Vehicle> vehiclesOf(Route before, Route after) {
		final Set<PathTiming.Vehicle> vehicles = new HashSet<>();
		if (routeSearch == null) {
			vehicles.add(RoutePaths.getDefaultVehicle(depot.transportMode));
		} else {
			vehicles.addAll(routeSearch.getVehicles(before));
			vehicles.addAll(routeSearch.getVehicles(after));
		}
		return vehicles;
	}

	/**
	 * @return the in and out legs in milliseconds of the vehicle along a way between two services, starting with its head
	 * at the end of the platform it stopped at, as a train stands there, rather than with its middle at the middle of a
	 * siding; cut where it turns back by the component ({@link transitsim.planning.TurnTimes#legs})
	 */
	long[] legMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath) {
		final double headStart = connectingPath.get(0).rail.getLength();
		final PathTiming.Vehicle standing = new PathTiming.Vehicle((float) (2 * headStart - vehicle.trainCars * vehicle.spacing), vehicle.trainCars, vehicle.spacing, vehicle.acceleration, vehicle.deceleration, vehicle.useSpeedCurve, vehicle.transportMode);
		final List<TimeSegment> timeSegments = new ArrayList<>();
		PathTiming.generateTimeSegments(standing, connectingPath, timeSegments, new HashMap<>(), new StopTimes(), 0, 0);
		return transitsim.planning.TurnTimes.legs(connectingPath, timeSegments, vehicle.trainCars * vehicle.spacing, Depot.MILLIS_PER_TICK);
	}

	/**
	 * With {@code -Dmtr.debugTurnTimes=true}, logs a measured turn round as planned before round 4 (30 s to turn round,
	 * the whole way with its standing, 30 s margin) and now (the network's least time to change ends, in and out legs).
	 */
	void debugTurnTime(Platform from, Platform to, Route before, Route after, List<PathData> way, long[] legs) {
		if (!Boolean.getBoolean("mtr.debugTurnTimes")) {
			return;
		}
		long whole = 0;
		for (final PathTiming.Vehicle vehicle : vehiclesOf(before, after)) {
			whole = Math.max(whole, connectionMillis(vehicle, way));
		}
		final long reverse = dataCache.networkSettings.minReverseMillis;
		System.out.println("Minecraft Transit Railway turn time: " + from.name + " (" + from.id + ") to " + to.name + " (" + to.id + "): before " + (30000 + whole + 30000) + " ms (30000 + " + whole + " + 30000), now " + (reverse + legs[0] + legs[1]) + " ms (" + reverse + " + in " + legs[0] + " + out " + legs[1] + ")");
	}

	/**
	 * @return how many times a path turns back: a rail followed by itself the other way
	 */
	double farthestFrom(SavedRailBase platform, List<PathData> path) {
		final BlockPos center = platform.getMidPos();
		double farthest = 0;
		for (final PathData pathData : path) {
			farthest = Math.max(farthest, Math.sqrt(pathData.getLastPos().distSqr(center)));
		}
		return farthest;
	}

	int countReversals(List<PathData> path) {
		int reversals = 0;
		for (int i = 0; i + 1 < path.size(); i++) {
			if (path.get(i).isOppositeRail(path.get(i + 1))) {
				reversals++;
			}
		}
		return reversals;
	}

	long connectionMillis(PathTiming.Vehicle vehicle, List<PathData> connectingPath) {
		final double headStart = connectingPath.get(0).rail.getLength();
		final PathTiming.Vehicle standing = new PathTiming.Vehicle((float) (2 * headStart - vehicle.trainCars * vehicle.spacing), vehicle.trainCars, vehicle.spacing, vehicle.acceleration, vehicle.deceleration, vehicle.useSpeedCurve, vehicle.transportMode);
		final List<TimeSegment> timeSegments = new ArrayList<>();
		PathTiming.generateTimeSegments(standing, connectingPath, timeSegments, new HashMap<>(), new StopTimes(), 0, 0);
		return timeSegments.isEmpty() ? 0 : (long) (timeSegments.get(timeSegments.size() - 1).endTime * depot.MILLIS_PER_TICK);
	}

	/**
	 * @return the dead ends of the turn back rails within reach of the platform, the nearest first, to turn back round
	 * when a route lists none
	 */
	List<BlockPos> getNearbyTurnBackNodes(SavedRailBase platform, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final long start = ConnectionStats.ON ? System.nanoTime() : 0;
		try {
			return getNearbyTurnBackNodesTimed(platform, rails);
		} finally {
			if (ConnectionStats.ON) {
				ConnectionStats.nearby(platform.id, System.nanoTime() - start);
			}
		}
	}

	List<BlockPos> getNearbyTurnBackNodesTimed(SavedRailBase platform, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		return toPositions(nearbyOf(platform, rails).nodes);
	}

	/**
	 * @return what the component found near the platform; the turn back rails are listed again only when the rails searched
	 * are others
	 */
	transitsim.network.NearbyTurnBacks.Result nearbyOf(SavedRailBase platform, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		if (turnBackRailsOf != rails || nearbyTurnBacks == null) {
			turnBackRailsOf = rails;
			final List<long[]> turnBackRails = new ArrayList<>();
			rails.forEach((startPos, connections) -> connections.forEach((endPos, rail) -> {
				if (rail.railType == RailType.TURN_BACK) {
					turnBackRails.add(new long[]{startPos.asLong(), endPos.asLong()});
				}
			}));
			nearbyTurnBacks = new transitsim.network.NearbyTurnBacks(turnBackRails, node -> {
				final Map<BlockPos, Rail> from = rails.get(BlockPos.of(node));
				return from == null ? 0 : from.size();
			}, (node1, node2) -> {
				final Long block = dataCache.stablingRails.get(PathData.getRailProduct(BlockPos.of(node1), BlockPos.of(node2)));
				return block == null ? transitsim.network.NearbyTurnBacks.NO_BLOCK : block;
			});
		}
		return nearbyTurnBacks.near(platform.id, platform.getMidPos().asLong(), depot.NEARBY_TURN_BACK_BLOCKS, depot.MAX_NEARBY_TURN_BACKS);
	}

	private static List<BlockPos> toPositions(List<Long> nodes) {
		final List<BlockPos> positions = new ArrayList<>(nodes.size());
		nodes.forEach(node -> positions.add(BlockPos.of(node)));
		return positions;
	}

	/**
	 * @return the turn back nodes a route lists after its last stop, with the stabling blocks near the platform after them
	 * (运用补完 step 4), as a player marks those for any route turning back there
	 */
	List<BlockPos> withStabling(List<BlockPos> listed, SavedRailBase platform, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final List<Long> stabling = nearbyOf(platform, rails).stabling;
		if (stabling.isEmpty()) {
			return listed;
		}
		final List<BlockPos> nodes = new ArrayList<>(listed);
		stabling.forEach(node -> {
			final BlockPos pos = BlockPos.of(node);
			if (!nodes.contains(pos)) {
				nodes.add(pos);
			}
		});
		return nodes;
	}

	TurnBackSection<PathData> findTurnBackSection(SavedRailBase from, SavedRailBase to, List<BlockPos> nodes, int start, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		// The rules are the component's (step 2c of splitting the depot)
		return TurnBackWays.section(nodes, start, waysRound(from, to, rails, graph, useFastSpeed));
	}

	/**
	 * @return the part of the loop from the stop where trains join it to the end of the path, or empty if they join at the first stop
	 */
	List<PathData> getLoopJoinPath(List<PathData> mainPath) {
		final List<PathData> loopJoinPath = new ArrayList<>();
		final int joinRail = loopEntryStop > 0 ? Siding.getStopRail(mainPath, loopRoute.platformIds, loopEntryStop) : -1;
		if (joinRail >= 0) {
			for (int i = joinRail; i < mainPath.size(); i++) {
				final PathData pathData = mainPath.get(i);
				loopJoinPath.add(pathData.copy(pathData.savedRailBaseId, pathData.dwellTime));
			}
		}
		return loopJoinPath;
	}

	/**
	 * @param linePlan the duties of the depot's planning unit, or null if there are none
	 */
	int generate(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, LineDuties.Plan linePlan) {
		if (dutiesOnly) {
			return generateDutiesOnly(minecraftServer, rails, graph, useFastSpeed, linePlan);
		}
		final List<PathData> tempPath = new ArrayList<>();
		final List<TurnBackSection<PathData>> turnBackSections = new ArrayList<>();
		final List<DepotReturn<PathData, SavedRailBase>> depotReturns = new ArrayList<>();
		SavedRailBase lastPlatform = null;

		for (int i = 0; i < pathsInRoute.size(); i++) {
			final List<SavedRailBase> platforms = platformsInRoute.get(i);
			if (lastPlatform != null && !platforms.isEmpty() && platforms.get(0) != lastPlatform) {
				final int routeBefore = i > 0 && !platformsInRoute.get(i - 1).isEmpty() ? i - 1 : -1;
				if (!addConnection(tempPath, lastPlatform, platforms.get(0), routeBefore, turnBackSections, depotReturns, rails, graph, useFastSpeed)) {
					break;
				}
			}
			if (!platforms.isEmpty()) {
				lastPlatform = platforms.get(platforms.size() - 1);
			}

			final List<PathData> routePath = pathsInRoute.get(i);
			final RoutePaths.RoutePath ownPath = routePath.isEmpty() && usesRoutePaths() ? routeSearch.get(mainRoutes.get(i), dataCache) : null;
			if (ownPath != null) {
				// The route's own path, with the route's stops
				if (!ownPath.isFound() && platforms.size() >= 2) {
					failure = ownPath.failure == PathFailure.NONE ? new PathFailure(PathFailure.Type.NO_ROUTE, 0) : ownPath.failure;
					tempPath.clear();
					break;
				}
				if (ownPath.isFound()) {
					routePath.addAll(ownPath.getPath(stopSettingsInRoute.get(i)));
				}
			} else if (routePath.isEmpty()) {
				final int reached = PathFinder.findPath(routePath, rails, graph, platforms, waypointsInRoute.get(i), 1, depot.cruisingAltitude, useFastSpeed);
				if (routePath.isEmpty() && platforms.size() >= 2) {
					failure = PathFailure.diagnose(graph, platforms, waypointsInRoute.get(i), reached);
					tempPath.clear();
					break;
				}
				PathFinder.applyStopSettings(routePath, stopSettingsInRoute.get(i));
				PathData.capSpeed(routePath, mainRoutes.get(i).speedLimit);
			}

			if (routePath.size() > 2) {
				final PathData first = routePath.get(0);
				final PathData last = tempPath.isEmpty() ? null : tempPath.get(tempPath.size() - 1);
				if (last != null) {
					if (first.isOppositeRail(last)) {
						first.dwellTime = 0;
						first.savedRailBaseId = 0;
					} else if (first.isSameRail(last)) {
						routePath.remove(0);
					}
				}
				tempPath.addAll(routePath);
			}
		}

		// Trains staying on the line turn back from the last route onto the first one, and a lap ends at its first stop
		if (closesPath && !tempPath.isEmpty() && lastPlatform != null) {
			final int lengthBefore = tempPath.size();
			if (!addConnection(tempPath, lastPlatform, platformsMerged.get(0), pathsInRoute.size() - 1, turnBackSections, depotReturns, rails, graph, useFastSpeed)) {
				tempPath.clear();
			} else if (tempPath.size() == lengthBefore) {
				// No way back round: said as such, rather than only that the path wasn't found
				failure = new PathFailure(PathFailure.Type.CYCLE_BACK, lastPlatform.id, platformsMerged.get(0).id, 0);
				tempPath.clear();
			}
		}

		int stopIndex = 1;
		for (int i = 1; i < tempPath.size(); i++) {
			final PathData pathData = tempPath.get(i);
			// Passed platforms have no stop time but still count, as route indices include them
			if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && tempPath.get(i - 1).rail.railType != RailType.PLATFORM) {
				stopIndex++;
			}
			pathData.stopIndex = stopIndex;
		}
		turnBackSections.forEach(turnBackSection -> turnBackSection.numberStops(tempPath));
		if (closesPath && !tempPath.isEmpty()) {
			// The end of a lap is the first stop again, which is where the stations are counted from
			final int firstStopIndex = tempPath.get(0).stopIndex;
			tempPath.get(tempPath.size() - 1).stopIndex = firstStopIndex;
			if (!turnBackSections.isEmpty()) {
				turnBackSections.get(turnBackSections.size() - 1).setEndStopIndex(tempPath.size(), firstStopIndex);
			}
		}
		depotReturns.forEach(depotReturn -> depotReturn.findStop(tempPath));

		final List<PathData> loopJoinPath = getLoopJoinPath(tempPath);
		final SavedRailBase entryPlatform = loopJoinPath.isEmpty() ? null : platformsInRoute.get(0).get(loopEntryStop);
		final List<TripTail<PathData, SavedRailBase>> tripTails = getTripTails(tempPath, rails, graph, useFastSpeed);

		final DepotDuties runDuties = depot.duties.diagramMode ? depot.duties : generateAutoDuties(minecraftServer, linePlan);
		// Ways round for turning back early, found once for each pair of platforms in this generation (D4)
		final Map<String, List<PathData>> turnLinkCache = new HashMap<>();

		int successfulSegments = Integer.MAX_VALUE;
		for (final Siding siding : sidings) {
			final SavedRailBase firstPlatform = entryPlatform != null ? entryPlatform : platformsMerged.isEmpty() ? null : platformsMerged.get(0);
			final SavedRailBase finalPlatform = platformsMerged.isEmpty() ? null : platformsMerged.get(platformsMerged.size() - 1);
			final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = runDuties != null ? getDutyPlans(runDuties, siding, rails, graph, useFastSpeed, key -> {
			}) : new ArrayList<>();
			setTurnLinks(siding, dutyPlans, turnLinkCache, rails, graph, useFastSpeed);
			successfulSegments = Math.min(successfulSegments, siding.generateRoute(minecraftServer, tempPath, platformsMerged.size(), rails, graph, firstPlatform, finalPlatform, repeatPath, depot.cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, turnBackSections, depotReturns, dutyPlans, getPoolSidings(siding)));
		}
		return successfulSegments;
	}

	/**
	 * A way between two services as found, given out as copies that keep which rails the way and its ways round a turn
	 * back rail share.
	 */
	private static final class Connection {

		private final List<PathData> path;
		private final TurnBackSection<PathData> section;

		private Connection(List<PathData> path, TurnBackSection<PathData> section) {
			this.path = path;
			this.section = section;
		}

		/**
		 * @return a copy of the ways round, or null for none, with the way itself copied into {@code connectingPath}
		 */
		private TurnBackSection<PathData> copyInto(List<PathData> connectingPath) {
			final Map<PathData, PathData> copies = new IdentityHashMap<>();
			path.forEach(pathData -> connectingPath.add(copyOf(pathData, copies)));
			if (section == null) {
				return null;
			}
			final List<List<PathData>> ways = new ArrayList<>();
			for (final List<PathData> way : section.ways) {
				if (way == null) {
					ways.add(null);
				} else {
					final List<PathData> copy = new ArrayList<>(way.size());
					way.forEach(pathData -> copy.add(copyOf(pathData, copies)));
					ways.add(copy);
				}
			}
			return new TurnBackSection<>(section.start, ways, section.defaultWay);
		}

		private static PathData copyOf(PathData pathData, Map<PathData, PathData> copies) {
			return copies.computeIfAbsent(pathData, key -> {
				final PathData copy = key.copy(key.savedRailBaseId, key.dwellTime);
				copy.stopIndex = key.stopIndex;
				return copy;
			});
		}
	}
}
