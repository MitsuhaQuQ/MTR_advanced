package mtr.data;

import mtr.advanced.DepotDuties;
import mtr.advanced.DutyShapes;
import mtr.path.PathData;
import org.msgpack.core.MessagePacker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Which service a train runs and what it does at the end of it: taking the next service, changing routes, lapping a loop,
 * running through to another route and lining up with the timetable after a restart. Moving the train is left to the
 * {@link Vehicle}, so whole days of running can be tested without a world.
 */
public class TrainService {

	private final String trainId;
	private final Vehicle vehicle;
	private final LongSupplier clock;
	private Board depot;
	private LongFunction<Route> routes;
	private LongFunction<? extends Board> routeDepots;
	/**
	 * The planned arrival at the first stop of the current service as a full timestamp, or 0 without a timetable.
	 */
	private long tripMillis;
	private long tripRouteId;
	/**
	 * When the current service's route starts, in the same time as the stop plans. A route after a turn back has its own service.
	 */
	private long routeStartMillis;
	/**
	 * The service this train runs after the current lap, reserved up front so the depot doesn't send a train for it.
	 */
	private long nextTripMillis;
	/**
	 * With no service left for this train, it leaves the loop and returns to the depot after this lap.
	 */
	private boolean isLastLap;
	/**
	 * Still on the way round a loop to its first stop, having joined it part of the way round.
	 */
	private boolean isJoiningLoop;
	/**
	 * Just loaded from a save, when the time the world was stopped for still has to be dealt with.
	 */
	private boolean needsAlignment;
	private Siding.StopTimes stopTimes;
	private Siding.StopTimes depotStopTimes;
	/**
	 * The path the train is on when it isn't the usual path of the depot, for a service running through to another route or
	 * for another way round a turn back point. The usual path is kept to go back to.
	 */
	private Siding.TripPath tripPath;
	private String tripPathKey = "";
	/**
	 * The next route of the trip and the service to run on it.
	 */
	private int linkedService = -1;
	private long linkedRouteId;
	private Map<String, Siding.TripPath> tripPaths = new HashMap<>();
	private Siding.TurnBacks turnBacks = Siding.TurnBacks.NONE;
	/**
	 * The stop where the train has taken a turn back rail of the point after it, or -1.
	 */
	private int turnBackStop = -1;
	private final List<Route> tripRoutes = new ArrayList<>();
	private long sidingId;
	/**
	 * In a depot running by its duties, the slot whose duty the train is running, or -1 (design document 6.12, block E2).
	 */
	private int dutySlot = -1;
	/**
	 * When the operating day of that duty started, as a full timestamp.
	 */
	private long dutyDayStart;
	/**
	 * The segment of the duty the train is on.
	 */
	private int dutyIndex;
	/**
	 * A copy of that day's duty, taken when the train takes it on, so editing the duties doesn't change a duty under way
	 * (fix F2, R04 of the architecture review). It isn't saved: after a restart the duty is read again from the depot.
	 */
	private DepotDuties.DayDuty dutyDay;

	private static final String KEY_TRIP_MILLIS = "trip_millis";
	private static final String KEY_NEXT_TRIP_MILLIS = "next_trip_millis";
	private static final String KEY_IS_LAST_LAP = "is_last_lap";
	private static final String KEY_TRIP_ROUTE_ID = "trip_route_id";
	private static final String KEY_ROUTE_START_MILLIS = "route_start_millis";
	private static final String KEY_IS_JOINING_LOOP = "is_joining_loop";
	private static final String KEY_TRIP_PATH = "trip_path";
	private static final String KEY_LINKED_SERVICE = "linked_service";
	private static final String KEY_LINKED_ROUTE = "linked_route";
	private static final String KEY_DUTY_SLOT = "duty_slot";
	private static final String KEY_DUTY_DAY_START = "duty_day_start";
	private static final String KEY_DUTY_INDEX = "duty_index";
	public static final int SAVED_KEYS = 12;
	/**
	 * How early a train aims to reach the first stop, so a small delay on the way doesn't make it late.
	 */
	private static final int DEPLOY_MARGIN_MILLIS = 15000;

	public TrainService(String trainId, Vehicle vehicle, LongSupplier clock) {
		this.trainId = trainId;
		this.vehicle = vehicle;
		this.clock = clock;
	}

	public void read(MessagePackHelper messagePackHelper) {
		tripPathKey = messagePackHelper.getString(KEY_TRIP_PATH);
		linkedService = messagePackHelper.getInt(KEY_LINKED_SERVICE, -1);
		linkedRouteId = messagePackHelper.getLong(KEY_LINKED_ROUTE);
		tripMillis = messagePackHelper.getLong(KEY_TRIP_MILLIS);
		tripRouteId = messagePackHelper.getLong(KEY_TRIP_ROUTE_ID);
		routeStartMillis = messagePackHelper.getLong(KEY_ROUTE_START_MILLIS);
		nextTripMillis = messagePackHelper.getLong(KEY_NEXT_TRIP_MILLIS);
		isJoiningLoop = messagePackHelper.getBoolean(KEY_IS_JOINING_LOOP);
		dutySlot = messagePackHelper.getInt(KEY_DUTY_SLOT, -1);
		dutyDayStart = messagePackHelper.getLong(KEY_DUTY_DAY_START);
		dutyIndex = messagePackHelper.getInt(KEY_DUTY_INDEX);
		needsAlignment = tripMillis != 0;
		setLastLap(messagePackHelper.getBoolean(KEY_IS_LAST_LAP));
	}

	public void write(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_TRIP_MILLIS).packLong(tripMillis);
		messagePacker.packString(KEY_NEXT_TRIP_MILLIS).packLong(nextTripMillis);
		messagePacker.packString(KEY_IS_LAST_LAP).packBoolean(isLastLap);
		messagePacker.packString(KEY_TRIP_ROUTE_ID).packLong(tripRouteId);
		messagePacker.packString(KEY_ROUTE_START_MILLIS).packLong(routeStartMillis);
		messagePacker.packString(KEY_IS_JOINING_LOOP).packBoolean(isJoiningLoop);
		messagePacker.packString(KEY_TRIP_PATH).packString(tripPathKey);
		messagePacker.packString(KEY_LINKED_SERVICE).packInt(linkedService);
		messagePacker.packString(KEY_LINKED_ROUTE).packLong(linkedRouteId);
		messagePacker.packString(KEY_DUTY_SLOT).packInt(dutySlot);
		messagePacker.packString(KEY_DUTY_DAY_START).packLong(dutyDayStart);
		messagePacker.packString(KEY_DUTY_INDEX).packInt(dutyIndex);
	}

	public void setSiding(long newSidingId) {
		sidingId = newSidingId;
	}

	/**
	 * @param newRoutes      finds a route by ID, or null if the routes aren't known yet
	 * @param newRouteDepots finds the depot running a route, or null if the routes aren't known yet
	 */
	public void setNetwork(Board newDepot, LongFunction<Route> newRoutes, LongFunction<? extends Board> newRouteDepots) {
		depot = newDepot;
		routes = newRoutes;
		routeDepots = newRouteDepots;
	}

	/**
	 * Keeps the services this train is on and will run next taken, as the depot forgets them unless they are taken again
	 * every tick. A train back at the depot ends its service.
	 */
	public void update(boolean isOnRoute) {
		if (isOnRoute) {
			// Duties say which train runs what, so nothing is reserved for others to see
			if (isOnDuty()) {
				return;
			}
			reserveTrip(tripRouteId, tripMillis);
			if (nextTripMillis != 0) {
				final Route lapRoute = getLapRoute();
				if (lapRoute != null) {
					reserveTrip(lapRoute.id, nextTripMillis);
				}
			}
		} else if (tripMillis != 0 || isLastLap || tripPath != null || dutySlot >= 0) {
			dutySlot = -1;
			dutyIndex = 0;
			dutyDay = null;
			tripMillis = 0;
			nextTripMillis = 0;
			routeStartMillis = 0;
			isJoiningLoop = false;
			leaveTripPath();
			setLastLap(false);
		}
	}

	public void onLapComplete() {
		final Route lapRoute = isOnTrip() ? getLapRoute() : null;
		if (lapRoute != null) {
			tripMillis = nextTripMillis == 0 ? findLapService(lapRoute, clock.getAsLong()) : nextTripMillis;
			tripRouteId = lapRoute.id;
			nextTripMillis = 0;
			final RouteTimetable.StopPlan lapPlan = getStopPlan(tripPath.lapStop);
			routeStartMillis = lapPlan == null ? 0 : lapPlan.routeStartMillis;
			reserveNextTrip();
			return;
		}

		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		routeStartMillis = 0;
		if (timetable == null) {
			tripMillis = 0;
			nextTripMillis = 0;
			setLastLap(false);
		} else {
			// A train looping since before the timetable was added joins it here
			tripMillis = nextTripMillis == 0 ? depot.findNextTrip(trainId, clock.getAsLong()) : nextTripMillis;
			tripRouteId = depot.getTrainRoutes(trainId).get(0).id;
			nextTripMillis = 0;
			updateTripPath();
			reserveNextTrip();
		}
	}

	/**
	 * Reaching the start of the next route on the path, or the turn back rail just before it, moves the train onto that route's
	 * next service. The train then waits for it here, so a train with a turn back rail doesn't hold up the platform. Without a
	 * service it carries on with the time it has.
	 */
	public void onStopReached() {
		final int nextStoppingIndex = vehicle.getNextStoppingIndex();
		turnBackStop = -1;
		// On a duty the next segment's service is already chosen, and starts at that segment's first stop
		if (isOnDuty()) {
			while (dutyIndex + 1 < tripPath.dutyStarts.length && nextStoppingIndex >= tripPath.dutyStarts[dutyIndex + 1]) {
				startDutySegment(dutyIndex + 1);
			}
			return;
		}
		// The first stop of a joined loop ends the service the train joined, like the end of any lap
		if (isJoiningLoop && stopTimes != null && nextStoppingIndex >= getJoinEndIndex()) {
			isJoiningLoop = false;
			onLapComplete();
		}

		if (tripPath != null && nextStoppingIndex == tripPath.entryRail && !isJoiningLoop) {
			joinLapRoute();
			return;
		}

		final RouteTimetable.StopPlan stopPlan = getStopPlan(getPlanIndex(nextStoppingIndex));
		final RouteTimetable.StopPlan startPlan = stopPlan == null || stopPlan.startsRoute ? stopPlan : stopPlan.nextRouteStart;
		if (startPlan != null && startPlan.startsRoute && startPlan.route.id != tripRouteId) {
			final long plannedStartMillis = getPlannedMillis(startPlan.routeStartMillis);
			final long readyMillis = clock.getAsLong() + startPlan.arrivalMillis - stopPlan.arrivalMillis;
			final boolean isLinked = tripPath != null && linkedService >= 0 && startPlan.route.id == linkedRouteId;
			final long serviceMillis = !startPlan.waitsForService ? 0 : isLinked ? RouteTimetable.getClosestService(plannedStartMillis, linkedService) : depot.findNextService(startPlan.route, Math.max(plannedStartMillis, readyMillis));
			tripMillis = serviceMillis == 0 ? plannedStartMillis : serviceMillis;
			tripRouteId = startPlan.route.id;
			if (isLinked) {
				setNextLink(startPlan.route);
			}
			updateTripPath();
			// The plan follows the route and table the train is on now, which can move where this route starts in it
			final RouteTimetable.StopPlan newStopPlan = getStopPlan(getPlanIndex(nextStoppingIndex));
			final RouteTimetable.StopPlan newStartPlan = newStopPlan == null || newStopPlan.startsRoute ? newStopPlan : newStopPlan.nextRouteStart;
			routeStartMillis = (newStartPlan == null ? startPlan : newStartPlan).routeStartMillis;
			// How long this route waited changes when the lap ends
			nextTripMillis = 0;
			reserveNextTrip();
		}
	}

	public boolean needsAlignment() {
		return needsAlignment;
	}

	/**
	 * A stopped world doesn't catch up on the time it lost. A train goes straight to the stop it should be at by now, on the
	 * service it is running if that has not ended, or else on the service that is running now. If that stop is taken it goes
	 * to the last free one before it and runs late from there. A train with nothing to run goes back to the depot.
	 *
	 * @param canAlign false for a train that doesn't follow a timetable now, such as one at the depot or driven by hand
	 */
	public void alignToTimetable(boolean canAlign) {
		needsAlignment = false;
		if (isOnDuty()) {
			if (canAlign) {
				alignDuty();
			}
			return;
		}
		if (!canAlign || tripMillis == 0 || stopTimes == null || depot.getTimetable(trainId) == null) {
			return;
		}

		final long currentMillis = clock.getAsLong();
		final int nextStoppingIndex = vehicle.getNextStoppingIndex();
		int stopIndex = findRunningStop(nextStoppingIndex, currentMillis);
		final boolean isSameService = stopIndex >= 0;
		if (!isSameService) {
			if (!takeRunningService(currentMillis)) {
				vehicle.returnToDepot();
				return;
			}
			stopIndex = findRunningStop(stopTimes.getFirstIndex(), currentMillis);
		}

		// A train staying on its service is already at the first stop it can use
		final List<PathData> path = vehicle.getPath();
		final int firstIndex = isSameService ? nextStoppingIndex + 1 : stopTimes.getFirstIndex();
		for (int i = stopIndex; i >= firstIndex && i >= 0; i--) {
			if (path.get(i).dwellTime > 0 && vehicle.isStopFree(i)) {
				vehicle.moveToStop(i);
				nextTripMillis = 0;
				reserveNextTrip();
				return;
			}
		}
		if (!isSameService) {
			vehicle.returnToDepot();
		}
	}

	/**
	 * A train on a duty goes to where its duty has it by now: the latest segment that has started, and on it the first stop
	 * it hasn't left yet, or the last free stop before that. Before the duty starts it waits at its first stop; after the
	 * duty it goes back to the depot, or to its last stop when it stays there overnight.
	 */
	private void alignDuty() {
		final List<DepotDuties.Segment> segments = getDutySegments();
		final int[] starts = tripPath.dutyStarts;
		if (segments.size() != starts.length || segments.isEmpty()) {
			vehicle.returnToDepot();
			return;
		}
		final long currentMillis = clock.getAsLong();
		int index = 0;
		for (int i = segments.size() - 1; i > 0; i--) {
			if (DutyShapes.getServiceMillis(dutyDayStart, segments.get(i).service) <= currentMillis) {
				index = i;
				break;
			}
		}
		startDutySegment(index);

		final List<PathData> path = vehicle.getPath();
		int stopIndex = -1;
		for (int i = starts[index]; i < path.size() && stopIndex < 0; i++) {
			if (path.get(i).dwellTime > 0 && path.get(i).rail.railType != RailType.SIDING) {
				while (dutyIndex + 1 < starts.length && i >= starts[dutyIndex + 1]) {
					startDutySegment(dutyIndex + 1);
				}
				final RouteTimetable.StopPlan stopPlan = getStopPlan(i);
				if (stopPlan != null && getPlannedMillis(stopPlan.departureMillis) + RouteTimetable.ALIGN_TOLERANCE_MILLIS > currentMillis) {
					stopIndex = i;
				}
			}
		}
		if (stopIndex < 0) {
			if (!tripPath.staysOnLine) {
				vehicle.returnToDepot();
				return;
			}
			stopIndex = path.size() - 1;
		}
		for (int i = stopIndex; i >= starts[0]; i--) {
			if (path.get(i).dwellTime > 0 && vehicle.isStopFree(i)) {
				int segment = 0;
				while (segment + 1 < starts.length && i >= starts[segment + 1]) {
					segment++;
				}
				startDutySegment(segment);
				vehicle.moveToStop(i);
				return;
			}
		}
		vehicle.returnToDepot();
	}

	/**
	 * @return the first stop from this one that the current service has not left yet, or a route after this one the train
	 * can start, or -1 if the service is over
	 */
	private int findRunningStop(int fromIndex, long currentMillis) {
		final List<PathData> path = vehicle.getPath();
		final int endIndex = vehicle.isRepeat() ? vehicle.getRepeatIndex2() : path.size();
		for (int i = Math.max(fromIndex, 0); i < endIndex; i++) {
			if (path.get(i).dwellTime > 0) {
				if (path.get(i).rail.railType == RailType.SIDING || isJoiningLoop && i >= getJoinEndIndex()) {
					return -1;
				}
				// Turn backs on the way out of the depot have no plan
				final RouteTimetable.StopPlan stopPlan = getStopPlan(i);
				if (stopPlan == null) {
					continue;
				}
				if (getPlannedMillis(stopPlan.departureMillis) + RouteTimetable.ALIGN_TOLERANCE_MILLIS > currentMillis) {
					return i;
				}
				final RouteTimetable.StopPlan startPlan = stopPlan.startsRoute ? stopPlan : stopPlan.nextRouteStart;
				if (startPlan != null && startPlan.startsRoute && startPlan.route.id != tripRouteId) {
					final Route route = startPlan.route;
					return !startPlan.waitsForService || route.timetable.getNextService(currentMillis, currentMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(route.id, millis)) != 0 ? i : -1;
				}
			}
		}
		return -1;
	}

	/**
	 * Moves on to the service that should be under way, or the next one if none is. A train on the path of a service running
	 * through to another route goes back to the depot instead, as its path only suits that service.
	 *
	 * @return false if there is no service for this train
	 */
	private boolean takeRunningService(long currentMillis) {
		final List<Route> trainRoutes = depot.getTrainRoutes(trainId);
		if (isOnTrip() || trainRoutes.isEmpty()) {
			return false;
		}

		final Route firstRoute = trainRoutes.get(0);
		final RouteTimetable timetable = depot.getTimetable(trainId);
		final long runningMillis = timetable.getLatestService(currentMillis - stopTimes.getDurationMillis(), currentMillis, millis -> depot.isTripReserved(firstRoute.id, millis));
		final long newTripMillis = runningMillis == 0 ? timetable.getNextService(currentMillis, currentMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(firstRoute.id, millis)) : runningMillis;
		if (newTripMillis == 0) {
			return false;
		}

		tripMillis = newTripMillis;
		tripRouteId = firstRoute.id;
		routeStartMillis = 0;
		nextTripMillis = 0;
		isJoiningLoop = false;
		depot.reserveTrip(tripRouteId, tripMillis);
		updateTripPath();
		return true;
	}

	/**
	 * At the last stop of a route that can turn back on more than one rail, takes the first of them that is free as the train
	 * is about to leave, and keeps it for the train until it is through. Each way round has a path of its own, which is the
	 * same as the one the train is on up to this stop, so the train changes onto it here.
	 *
	 * @return true while the train has to wait at the stop, as every way round is taken or it has only just changed paths
	 */
	public boolean chooseTurnBack() {
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (turnBackStop == stopIndex) {
			return false;
		}
		final int point = turnBacks.getPoint(tripPath == null ? null : tripPath.turnBackStops, stopIndex);
		if (point < 0) {
			return false;
		}

		final Siding.TurnBackPoint turnBackPoint = turnBacks.get(point);
		final String tripKey = isOnTrip() ? tripPath.tripKey : "";
		for (int way = 0; way < turnBackPoint.getWayCount(); way++) {
			final List<UUID> rails = turnBackPoint.getRails(way);
			final boolean isDefaultWay = way == turnBackPoint.defaultWay;
			final Siding.TripPath newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(Siding.TripPath.getTurnBackKey(tripKey, point, way));
			if (rails != null && (newPath != null || isDefaultWay && tripKey.isEmpty()) && vehicle.isTurnBackFree(rails)) {
				final int newStopIndex = turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point);
				turnBackStop = newStopIndex;
				vehicle.reserveTurnBack(rails, newStopIndex + turnBackPoint.getLength(way));
				if (newPath == tripPath) {
					return false;
				}
				tripPath = newPath;
				tripPathKey = newPath == null ? "" : newPath.key;
				stopTimes = newPath == null ? depotStopTimes : newPath.stopTimes;
				vehicle.changePath(newPath, newStopIndex);
				setLastLap(isLastLap);
				return true;
			}
		}
		return true;
	}

	/**
	 * A train of a depot whose trains stay on the line goes back to it after a service marked for that. At the last stop of the
	 * service's route it changes onto the way back, which turns back where the train always does and then heads for the depot.
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseDepotReturn() {
		if (depot == null || !depot.keepsTrainsOnLine() || tripMillis == 0 || isOnTrip() || tripPath != null && tripPath.changeStop >= 0) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		final List<PathData> path = vehicle.getPath();
		if (stopIndex < 0 || stopIndex >= path.size()) {
			return false;
		}
		final long platformId = path.get(stopIndex).savedRailBaseId;
		final List<Route> trainRoutes = depot.getTrainRoutes(trainId);
		for (int i = 0; i < trainRoutes.size(); i++) {
			final Route route = trainRoutes.get(i);
			if (route.id == tripRouteId && !route.platformIds.isEmpty() && route.getLastPlatformId() == platformId && route.timetable.isReturnService(tripMillis)) {
				final Siding.TripPath returnPath = tripPaths.get(Siding.TripPath.getDepotReturnKey(i));
				if (returnPath != null) {
					tripPath = returnPath;
					tripPathKey = returnPath.key;
					stopTimes = returnPath.stopTimes;
					tripRoutes.clear();
					turnBackStop = -1;
					// The service after this lap is left for another train
					nextTripMillis = 0;
					vehicle.changePath(returnPath, returnPath.changeStop);
					setLastLap(false);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A train leaves the siding so it reaches its first stop when the service is planned to, or a little late, but never
	 * for a service the depot has already sent a train out after, so services aren't run out of order.
	 *
	 * @param lastDeployedMillis the latest service of the first route the depot has sent a train out for
	 * @return the service a train leaving now runs as a full timestamp, or 0 if it shouldn't leave now
	 */
	public long findDeployService(long lastDeployedMillis) {
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		if (timetable == null) {
			return 0;
		}
		final Route firstRoute = depot.getTrainRoutes(trainId).get(0);
		final long currentMillis = clock.getAsLong();
		final long leadMillis = getLeadMillis();
		return timetable.getLatestService(Math.max(lastDeployedMillis, currentMillis + Math.min(leadMillis, 0) - RouteTimetable.MAX_DEPLOY_DELAY_MILLIS), currentMillis + leadMillis + DEPLOY_MARGIN_MILLIS, millis -> depot.isTripReserved(firstRoute.id, millis));
	}

	/**
	 * Sends this train out for a service of the timetable.
	 */
	public void deploy(long newTripMillis, long routeId) {
		tripMillis = newTripMillis;
		tripRouteId = routeId;
		routeStartMillis = 0;
		nextTripMillis = 0;
		isJoiningLoop = stopTimes != null && stopTimes.hasJoinStops();
		updateTripPath();
		reserveNextTrip();
	}

	/**
	 * Sends this train out for a slot's duty (design document 6.12, block E2).
	 *
	 * @param dayStart when the operating day of the duty started
	 */
	public void deployDuty(int slot, long dayStart, Siding.TripPath dutyPath) {
		dutySlot = slot;
		dutyDayStart = dayStart;
		dutyDay = readDutyDay();
		isJoiningLoop = false;
		linkedService = -1;
		linkedRouteId = 0;
		useTripPath(dutyPath);
		startDutySegment(0);
	}

	/**
	 * @return true if the train runs a duty, which decides every service it runs
	 */
	public boolean isOnDuty() {
		return dutySlot >= 0 && tripPath != null && tripPath.isDuty();
	}

	/**
	 * @return true while a train that stays on the line overnight is at its last stop, waiting for the next day
	 */
	public boolean isStayingOnLine() {
		return isOnDuty() && tripPath.staysOnLine && vehicle.getNextStoppingIndex() >= vehicle.getPath().size() - 1;
	}

	/**
	 * @return true on the path of a duty that ends on the line, whose end is a stop rather than the way into the siding
	 */
	public boolean endsOnLine() {
		return isOnDuty() && tripPath.staysOnLine;
	}

	/**
	 * @return at the first stop of a segment of the duty, when the train leaves, or 0
	 */
	public long getDutyDepartureMillis() {
		if (!isOnDuty()) {
			return 0;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		for (final int start : tripPath.dutyStarts) {
			if (start == stopIndex) {
				final RouteTimetable.StopPlan stopPlan = getStopPlan(stopIndex);
				return stopPlan == null ? 0 : getPlannedMillis(stopPlan.departureMillis);
			}
		}
		return 0;
	}

	/**
	 * Once a new operating day starts, a train that stayed on the line overnight takes over the duty of the slot chosen for
	 * it, which starts where it is. If that duty doesn't start here, as the duties have changed since, it goes back to the
	 * depot.
	 */
	public void updateOvernight() {
		if (!isOnDuty() || !tripPath.staysOnLine || vehicle.getNextStoppingIndex() < vehicle.getPath().size() - 1) {
			return;
		}
		final long dayStart = DutyShapes.getDayStart(clock.getAsLong());
		if (dayStart <= dutyDayStart + Depot.MILLISECONDS_PER_DAY / 2) {
			return;
		}
		// The new day is where an edit takes effect, so which slot comes next is read as the depot has it now
		final DepotDuties.DayDuty day = readDutyDay();
		final List<DepotDuties.Slot> slots = depot.getDutySlots(sidingId);
		final int nextSlot = day == null ? -1 : DutyShapes.getNextSlot(day, dutySlot);
		final int weekday = TimetableConfig.getWeekday(dayStart);
		final String key = nextSlot < 0 || routes == null ? null : DutyShapes.getSlotKey(slots, nextSlot, weekday, routes);
		final Siding.TripPath nextPath = key == null ? null : tripPaths.get(key);
		final List<PathData> path = vehicle.getPath();
		final long dayNumber = DutyShapes.getDayNumber(dayStart);
		if (nextPath == null || nextPath.path.isEmpty() || nextPath.path.get(0).savedRailBaseId != path.get(path.size() - 1).savedRailBaseId || depot.hasDutyRun(sidingId, nextSlot, dayNumber)) {
			System.out.println("A train staying on the line overnight has no duty starting where it is, and goes back to the depot: " + key);
			vehicle.returnToDepot();
			return;
		}
		depot.markDutyRun(sidingId, nextSlot, dayNumber);
		dutySlot = nextSlot;
		dutyDayStart = dayStart;
		dutyDay = readDutyDay();
		tripPath = nextPath;
		tripPathKey = nextPath.key;
		stopTimes = nextPath.stopTimes;
		tripRoutes.clear();
		vehicle.changePath(nextPath, 0);
		startDutySegment(0);
	}

	/**
	 * Moves the train onto a segment of its duty: its route, and its service on the duty's day.
	 */
	private void startDutySegment(int index) {
		final List<DepotDuties.Segment> segments = getDutySegments();
		if (index >= segments.size() || tripPath == null) {
			return;
		}
		dutyIndex = index;
		final DepotDuties.Segment segment = segments.get(index);
		tripRouteId = segment.routeId;
		tripMillis = DutyShapes.getServiceMillis(dutyDayStart, segment.service);
		nextTripMillis = 0;
		if (index == 0) {
			// Plans count from the first stop of the path, which for a route joined part of the way along is later than its service
			routeStartMillis = 0;
			final Route route = routes == null ? null : routes.apply(segment.routeId);
			if (route != null && segment.fromStop > 0 && stopTimes != null) {
				tripMillis += stopTimes.getEntryOffsetMillis(route, segment.fromStop, route.timetable.getTableIndex(tripMillis), segment.service);
			}
		} else {
			final RouteTimetable.StopPlan stopPlan = getStopPlan(tripPath.dutyStarts[index]);
			final RouteTimetable.StopPlan startPlan = stopPlan == null || stopPlan.startsRoute ? stopPlan : stopPlan.nextRouteStart;
			routeStartMillis = startPlan == null ? 0 : startPlan.routeStartMillis;
		}
	}

	/**
	 * @return the duty the train took on, or after a restart the one of its slot read again from the depot
	 */
	private DepotDuties.DayDuty getDutyDay() {
		if (dutyDay == null) {
			dutyDay = readDutyDay();
		}
		return dutyDay;
	}

	/**
	 * @return a copy of the duty of the train's slot on its day, as the depot has it now
	 */
	private DepotDuties.DayDuty readDutyDay() {
		if (depot == null || dutySlot < 0) {
			return null;
		}
		final List<DepotDuties.Slot> slots = depot.getDutySlots(sidingId);
		final DepotDuties.DayDuty day = dutySlot < slots.size() ? slots.get(dutySlot).getDuty(TimetableConfig.getWeekday(dutyDayStart + Depot.MILLISECONDS_PER_DAY / 2)) : null;
		return day == null ? null : day.copy();
	}

	private List<DepotDuties.Segment> getDutySegments() {
		final DepotDuties.DayDuty day = getDutyDay();
		return day == null ? Collections.emptyList() : day.segments;
	}

	public int getDutySlot() {
		return dutySlot;
	}

	/**
	 * @param depotStopTimes the stop times of the usual path of the siding
	 * @param newTripPaths   the paths of services leaving it
	 */
	public void setStopTimes(Siding.StopTimes newDepotStopTimes, Map<String, Siding.TripPath> newTripPaths) {
		setStopTimes(newDepotStopTimes, newTripPaths, Siding.TurnBacks.NONE);
	}

	/**
	 * @param newTurnBacks where trains of the siding can turn back on more than one rail
	 */
	public void setStopTimes(Siding.StopTimes newDepotStopTimes, Map<String, Siding.TripPath> newTripPaths, Siding.TurnBacks newTurnBacks) {
		depotStopTimes = newDepotStopTimes;
		stopTimes = tripPath == null ? newDepotStopTimes : tripPath.stopTimes;
		tripPaths = newTripPaths;
		turnBacks = newTurnBacks;
	}

	public Siding.StopTimes getStopTimes() {
		return stopTimes;
	}

	/**
	 * @return true if the train isn't on the usual path of the depot, which it then keeps until it is back
	 */
	public boolean isOnTripPath() {
		return tripPath != null;
	}

	/**
	 * @return true if the train runs a service through to another route
	 */
	public boolean isOnTrip() {
		return tripPath != null && !tripPath.tripKey.isEmpty();
	}

	public boolean isLastLap() {
		return isLastLap;
	}

	/**
	 * Puts a train from a save back on its path.
	 *
	 * @return false if that path no longer exists
	 */
	public boolean restoreTripPath(Map<String, Siding.TripPath> newTripPaths) {
		if (tripPathKey.isEmpty()) {
			return true;
		}
		final Siding.TripPath newTripPath = newTripPaths.get(tripPathKey);
		if (newTripPath == null) {
			return false;
		}
		final boolean oldIsLastLap = isLastLap;
		final long oldNextTripMillis = nextTripMillis;
		useTripPath(newTripPath);
		nextTripMillis = oldNextTripMillis;
		setLastLap(oldIsLastLap);
		if (linkedService >= 0 && linkedRouteId == 0) {
			linkedRouteId = newTripPath.routeIds.get(newTripPath.routeIds.size() - 1);
		}
		return true;
	}

	/**
	 * Moves the train onto the path of a service running through to another route. The path is the same up to where the train
	 * leaves the usual one, so this works wherever the train is. A loop joined part of the way round changes over at its first stop.
	 */
	private void updateTripPath() {
		if (isOnTrip() || isJoiningLoop || depot == null || tripMillis == 0) {
			return;
		}
		final List<Route> trainRoutes = depot.getTrainRoutes(trainId);
		final Route lastRoute = trainRoutes.isEmpty() ? null : trainRoutes.get(trainRoutes.size() - 1);
		if (lastRoute != null && lastRoute.id == tripRouteId) {
			final RouteTimetable.ServiceLink link = lastRoute.timetable.getLink(tripMillis);
			final Siding.TripPath newTripPath = link == null || routes == null ? null : tripPaths.get(Siding.TripTail.getKey(RouteTimetable.getLinkChain(link, routes)));
			// A train that went another way round a turn back point keeps to it, as it may be past the point already
			final Siding.TripPath turnBackPath = newTripPath == null || tripPath == null ? newTripPath : tripPaths.get(Siding.TripPath.getTurnBackKey(newTripPath.tripKey, tripPath.turnBackPoint, tripPath.turnBackWay));
			if (turnBackPath != null) {
				linkedService = link.service;
				linkedRouteId = link.routeId;
				useTripPath(turnBackPath);
			}
		}
	}

	private void useTripPath(Siding.TripPath newTripPath) {
		tripPath = newTripPath;
		tripPathKey = newTripPath.key;
		stopTimes = newTripPath.stopTimes;
		tripRoutes.clear();
		vehicle.followPath(newTripPath);
		nextTripMillis = 0;
		setLastLap(false);
	}

	private void leaveTripPath() {
		turnBackStop = -1;
		if (tripPath != null) {
			tripPath = null;
			tripPathKey = "";
			linkedService = -1;
			linkedRouteId = 0;
			tripRoutes.clear();
			vehicle.followPath(null);
		}
	}

	/**
	 * After taking a service of a route of the trip, finds the service that follows it.
	 */
	private void setNextLink(Route route) {
		final RouteTimetable.ServiceLink link = route.timetable.getLinkOfService(linkedService);
		linkedRouteId = link == null ? 0 : link.routeId;
		linkedService = link == null ? -1 : link.service;
	}

	/**
	 * Reaching the stop where the trip joins a loop part of the way round. Like a train from the depot joining a loop, the
	 * train reaches this stop when that service would, and that service ends at the first stop of the loop.
	 */
	private void joinLapRoute() {
		final Route lapRoute = getLapRoute();
		if (lapRoute != null && linkedService >= 0) {
			final long currentMillis = clock.getAsLong();
			tripRouteId = lapRoute.id;
			tripMillis = RouteTimetable.getClosestService(currentMillis, linkedService);
			final RouteTimetable.StopPlan stopPlan = getStopPlan(vehicle.getNextStoppingIndex());
			if (stopPlan != null) {
				routeStartMillis = stopPlan.routeStartMillis;
				tripMillis = RouteTimetable.getClosestService(currentMillis - (stopPlan.arrivalMillis - routeStartMillis), linkedService);
			}
			linkedService = -1;
			linkedRouteId = 0;
			isJoiningLoop = true;
			nextTripMillis = 0;
			reserveNextTrip();
		}
	}

	/**
	 * @return the route the train keeps lapping, or null if the trip doesn't end with one
	 */
	private Route getLapRoute() {
		final List<Route> newTripRoutes = getTripRoutes();
		if (newTripRoutes.isEmpty()) {
			return null;
		}
		return !isOnTrip() ? newTripRoutes.get(0) : vehicle.getPathRepeatIndex2() > 0 ? newTripRoutes.get(newTripRoutes.size() - 1) : null;
	}

	/**
	 * @return the rail where a train that joined a loop part of the way round has come to its first stop
	 */
	private int getJoinEndIndex() {
		return tripPath != null && tripPath.entryRail >= 0 && tripRouteId == getLastTripRouteId() ? tripPath.lapStop : stopTimes.getFirstIndex();
	}

	private long getLastTripRouteId() {
		return tripPath.routeIds.get(tripPath.routeIds.size() - 1);
	}

	/**
	 * The route may be run from another depot, which has to know not to send a train for this service.
	 */
	private void reserveTrip(long newRouteId, long newTripMillis) {
		depot.reserveTrip(newRouteId, newTripMillis);
		final Board routeDepot = !isOnTrip() || routeDepots == null ? null : routeDepots.apply(newRouteId);
		if (routeDepot != null && routeDepot != depot) {
			routeDepot.reserveTrip(newRouteId, newTripMillis);
		}
	}

	/**
	 * @return the next service of the loop a trip ends with that no train has taken, including trains of that route's own depot
	 */
	private long findLapService(Route route, long readyMillis) {
		final Board routeDepot = routeDepots == null ? null : routeDepots.apply(route.id);
		final long newTripMillis = route.timetable.getNextService(readyMillis, readyMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(route.id, millis) || routeDepot != null && routeDepot.isTripReserved(route.id, millis));
		if (newTripMillis != 0) {
			reserveTrip(route.id, newTripMillis);
		}
		return newTripMillis;
	}

	/**
	 * @return the routes of the path the train is on
	 */
	private List<Route> getTripRoutes() {
		if (!isOnTrip()) {
			return depot.getTrainRoutes(trainId);
		}
		if (tripRoutes.isEmpty() && routes != null) {
			tripPath.routeIds.forEach(routeId -> {
				final Route route = routes.apply(routeId);
				if (route != null) {
					tripRoutes.add(route);
				}
			});
		}
		return tripRoutes;
	}

	/**
	 * @return the time from leaving the siding until the planned arrival at the first stop of a service. A train joining a
	 * loop part of the way round reaches its first stop after that time, so this can be negative.
	 */
	public long getLeadMillis() {
		if (stopTimes == null) {
			return 0;
		}
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		if (stopTimes.hasJoinStops() && timetable != null) {
			final List<Route> trainRoutes = depot.getTrainRoutes(trainId);
			final RouteTimetable.StopPlan stopPlan = stopTimes.getPlan(trainRoutes, timetable, stopTimes.getFirstJoinStopLapIndex(), trainRoutes.get(0).id, 0, -1);
			return stopTimes.getLeadMillis() - (stopPlan == null ? 0 : stopPlan.arrivalMillis);
		}
		return stopTimes.getLeadMillis();
	}

	private void reserveNextTrip() {
		final int pathRepeatIndex2 = vehicle.getPathRepeatIndex2();
		if (vehicle.getPathRepeatIndex1() > 0 && pathRepeatIndex2 > 0) {
			// On the way to a loop at the end of a trip there is no lap to plan yet
			if (isOnTrip() && tripRouteId != getLastTripRouteId()) {
				return;
			}
			final RouteTimetable.StopPlan lastStopPlan = getStopPlan(pathRepeatIndex2);
			final long lapEndMillis = lastStopPlan == null ? 0 : getPlannedMillis(lastStopPlan.arrivalMillis);
			nextTripMillis = lastStopPlan == null ? 0 : !isOnTrip() ? depot.findNextTrip(trainId, lapEndMillis) : findLapService(getLapRoute(), lapEndMillis);
			setLastLap(nextTripMillis == 0);
		}
	}

	private void setLastLap(boolean newIsLastLap) {
		isLastLap = newIsLastLap;
		vehicle.setLastLap(newIsLastLap);
	}

	/**
	 * @return the planned times of a stop on the path for the service the train is on when it gets there, or null without a timetable
	 */
	public RouteTimetable.StopPlan getPlanAt(int stopIndex) {
		return getStopPlan(getPlanIndex(stopIndex));
	}

	/**
	 * The stop at the end of a lap is also the first stop of the next one, which is the service the train is on by then.
	 */
	private int getPlanIndex(int stopIndex) {
		return vehicle.isRepeat() && stopIndex >= vehicle.getRepeatIndex2() && stopTimes != null ? !isOnTrip() ? stopTimes.getFirstIndex() : tripPath.lapStop : stopIndex;
	}

	private RouteTimetable.StopPlan getStopPlan(int stopIndex) {
		final Route dutyRoute = isOnDuty() && routes != null ? routes.apply(tripRouteId) : null;
		final RouteTimetable timetable = dutyRoute != null ? dutyRoute.timetable : depot == null ? null : depot.getTimetable(trainId);
		if (tripMillis == 0 || timetable == null || stopTimes == null) {
			return null;
		}
		final List<Route> newTripRoutes = getTripRoutes();
		return stopTimes.getPlan(newTripRoutes, timetable, stopIndex, tripRouteId, getTripTable(newTripRoutes), (int) Math.floorMod(tripMillis, (long) Depot.MILLISECONDS_PER_DAY));
	}

	/**
	 * @return the table of the current route that the current service belongs to
	 */
	private int getTripTable(List<Route> newTripRoutes) {
		for (final Route route : newTripRoutes) {
			if (route.id == tripRouteId) {
				return route.timetable.getTableIndex(tripMillis);
			}
		}
		return 0;
	}

	/**
	 * @param planMillis a time from a stop plan
	 * @return the same time on the current service as a full timestamp
	 */
	public long getPlannedMillis(long planMillis) {
		return tripMillis + planMillis - routeStartMillis;
	}

	public long getPlannedArrivalMillis(int stopIndex) {
		if (vehicle.isRepeat() && stopIndex >= vehicle.getRepeatIndex2() || isJoiningLoop && stopTimes != null && stopIndex == getJoinEndIndex()) {
			return nextTripMillis;
		} else {
			final RouteTimetable.StopPlan stopPlan = getStopPlan(stopIndex);
			return stopPlan == null ? 0 : getPlannedMillis(stopPlan.arrivalMillis);
		}
	}

	public long getTripMillis() {
		return tripMillis;
	}

	public long getTripRouteId() {
		return tripRouteId;
	}

	public long getNextTripMillis() {
		return nextTripMillis;
	}

	/**
	 * What the train does when told to by its service.
	 */
	public interface Vehicle {

		List<PathData> getPath();

		int getNextStoppingIndex();

		/**
		 * @return true if the train laps a loop now, which it stops doing on its last lap
		 */
		boolean isRepeat();

		/**
		 * @return the rail where a lap ends, while {@link #isRepeat()}
		 */
		int getRepeatIndex2();

		/**
		 * @return where the loop of the path the train is on starts, or 0 if it has none, whether or not this is the last lap
		 */
		int getPathRepeatIndex1();

		int getPathRepeatIndex2();

		void setLastLap(boolean isLastLap);

		/**
		 * @param tripPath the path of a service running through to another route, or null for the usual path of the depot
		 */
		void followPath(Siding.TripPath tripPath);

		/**
		 * Moves the train, stopped before a turn back point, onto a path that is the same from there to the point.
		 *
		 * @param tripPath     the new path, or null for the usual path of the depot
		 * @param newStopIndex the rail of the same stop on the new path
		 */
		void changePath(Siding.TripPath tripPath, int newStopIndex);

		/**
		 * @return true if no other train is on these rails or has them kept for itself
		 */
		boolean isTurnBackFree(List<UUID> rails);

		/**
		 * Keeps these rails for the train until it reaches this rail of its path.
		 */
		void reserveTurnBack(List<UUID> rails, int untilIndex);

		/**
		 * @return true if the whole train fits behind the end of this stop's rail without touching another train
		 */
		boolean isStopFree(int stopIndex);

		void moveToStop(int stopIndex);

		void returnToDepot();
	}

	/**
	 * The services of a depot and the ones its trains have taken.
	 */
	public interface Board {

		/**
		 * @return the timetable sending out this vehicle, or null if it doesn't follow one
		 */
		RouteTimetable getTimetable(String trainId);

		/**
		 * @return the routes this vehicle runs through, in order
		 */
		List<Route> getTrainRoutes(String trainId);

		/**
		 * @return the first service of this vehicle's first route from this time on that no train has taken, which is then taken, or 0 if a train would wait too long for it
		 */
		long findNextTrip(String trainId, long readyMillis);

		/**
		 * @return the first service of this route from this time on that no train has taken, which is then taken, or 0 if a train would wait too long for it
		 */
		long findNextService(Route route, long readyMillis);

		void reserveTrip(long routeId, long tripMillis);

		boolean isTripReserved(long routeId, long tripMillis);

		/**
		 * @return true if trains turn back from the last route onto the first one instead of going back to the depot, and wait
		 * on the line for their next service however long that is
		 */
		default boolean keepsTrainsOnLine() {
			return false;
		}

		/**
		 * @return the slots of a siding in a depot running by its duties
		 */
		default List<DepotDuties.Slot> getDutySlots(long sidingId) {
			return Collections.emptyList();
		}

		default boolean hasDutyRun(long sidingId, int slot, long day) {
			return false;
		}

		default void markDutyRun(long sidingId, int slot, long day) {
		}
	}
}
