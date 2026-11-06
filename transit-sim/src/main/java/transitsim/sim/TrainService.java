package transitsim.sim;

import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanTrack;
import transitsim.plan.PlanVersions;
import transitsim.plan.RouteTimetable;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;
import transitsim.plan.TripTail;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;

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
public class TrainService<T extends PlanTrack> {

	private final String trainId;
	private final Vehicle<T> vehicle;
	private final LongSupplier clock;
	private Board depot;
	private LongFunction<? extends PlanRoute> routes;
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
	private StopTimes stopTimes;
	private StopTimes depotStopTimes;
	/**
	 * The path the train is on when it isn't the usual path of the depot, for a service running through to another route or
	 * for another way round a turn back point. The usual path is kept to go back to.
	 */
	private TripPath<T> tripPath;
	private String tripPathKey = "";
	/**
	 * The next route of the trip and the service to run on it.
	 */
	private int linkedService = -1;
	private long linkedRouteId;
	private Map<String, TripPath<T>> tripPaths = new HashMap<>();
	private TurnBacks turnBacks = TurnBacks.NONE;
	/**
	 * The stop where the train has taken a turn back rail of the point after it, or -1.
	 */
	private int turnBackStop = -1;
	private final List<PlanRoute> tripRoutes = new ArrayList<>();
	/**
	 * The plan of the service the train is on: its routes and timetable as they were when it took the service (S3d, fix R04).
	 * Edits to the timetable, its exceptions or the stops don't change it; the train takes the newest plan when it changes
	 * service, route, duty segment or path, or comes back to the depot.
	 */
	private List<PlanRoute> planRoutes;
	private RouteTimetable planTimetable;
	private long planTripMillis;
	private long planRouteId;
	private TripPath<T> planTripPath;
	private StopTimes planStopTimes;
	private int planDutyIndex;
	private long sidingId;
	/**
	 * In a depot running by its duties, the slot whose duty the train is running, or -1 (design document 6.12, block E2).
	 */
	private int dutySlot = -1;
	/**
	 * Why the train waits on the line with no duty to go on with, or null.
	 */
	private HoldReason overnightHold;
	/**
	 * The siding whose slot that is, when another siding of its pool sent the train in place of that siding's, else 0 for
	 * the train's own (decoupling document, step D, depots sending trains out 4).
	 */
	private long dutySidingId;
	/**
	 * The stop at which the train chose the siding to go back to after its duty, so it chooses once, or -1.
	 */
	private int returnChosenStop = -1;
	/**
	 * Came back to another siding of its pool than its own, so its own siding lets it go and that one makes a train in its
	 * place.
	 */
	private boolean returnedElsewhere;
	/**
	 * The siding it came back to when {@link #returnedElsewhere}.
	 */
	private long returnedToSidingId;
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
	/**
	 * Sent out for a duty but still in the siding, waiting for its run out of it to be granted. The train keeps its duty
	 * meanwhile, unlike a train back in the depot, which ends it.
	 */
	private boolean isLeavingForDuty;

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
	/**
	 * The duty the train took, so it keeps running it however the depot's duties change (decoupling document, step D).
	 */
	private static final String KEY_DUTY_DAY = "duty_day";
	/**
	 * The siding whose slot the train runs, if not its own (decoupling document, step D).
	 */
	private static final String KEY_DUTY_SIDING = "duty_siding";
	public static final int SAVED_KEYS = 14;
	private static final long MILLIS_PER_TICK = 50;
	/**
	 * How early a train aims to reach the first stop, so a small delay on the way doesn't make it late.
	 */
	private static final int DEPLOY_MARGIN_MILLIS = 15000;

	public TrainService(String trainId, Vehicle<T> vehicle, LongSupplier clock) {
		this.trainId = trainId;
		this.vehicle = vehicle;
		this.clock = clock;
	}

	public void read(KeyedReader reader) {
		tripPathKey = reader.getString(KEY_TRIP_PATH, "");
		linkedService = reader.getInt(KEY_LINKED_SERVICE, -1);
		linkedRouteId = reader.getLong(KEY_LINKED_ROUTE, 0);
		tripMillis = reader.getLong(KEY_TRIP_MILLIS, 0);
		tripRouteId = reader.getLong(KEY_TRIP_ROUTE_ID, 0);
		routeStartMillis = reader.getLong(KEY_ROUTE_START_MILLIS, 0);
		nextTripMillis = reader.getLong(KEY_NEXT_TRIP_MILLIS, 0);
		isJoiningLoop = reader.getBoolean(KEY_IS_JOINING_LOOP, false);
		dutySlot = reader.getInt(KEY_DUTY_SLOT, -1);
		dutyDayStart = reader.getLong(KEY_DUTY_DAY_START, 0);
		dutyIndex = reader.getInt(KEY_DUTY_INDEX, 0);
		dutySidingId = reader.getLong(KEY_DUTY_SIDING, 0);
		// Saved before trains kept their duty, it is read again from the depot when needed
		dutyDay = dutySlot >= 0 ? DepotDuties.DayDuty.fromText(reader.getString(KEY_DUTY_DAY, "")) : null;
		// A train back in the depot ends its duty at once, so one saved in the depot with a duty was waiting to leave
		isLeavingForDuty = dutySlot >= 0;
		needsAlignment = tripMillis != 0;
		setLastLap(reader.getBoolean(KEY_IS_LAST_LAP, false));
	}

	public void write(KeyedWriter writer) throws IOException {
		writer.writeLong(KEY_TRIP_MILLIS, tripMillis);
		writer.writeLong(KEY_NEXT_TRIP_MILLIS, nextTripMillis);
		writer.writeBoolean(KEY_IS_LAST_LAP, isLastLap);
		writer.writeLong(KEY_TRIP_ROUTE_ID, tripRouteId);
		writer.writeLong(KEY_ROUTE_START_MILLIS, routeStartMillis);
		writer.writeBoolean(KEY_IS_JOINING_LOOP, isJoiningLoop);
		writer.writeString(KEY_TRIP_PATH, tripPathKey);
		writer.writeInt(KEY_LINKED_SERVICE, linkedService);
		writer.writeLong(KEY_LINKED_ROUTE, linkedRouteId);
		writer.writeInt(KEY_DUTY_SLOT, dutySlot);
		writer.writeLong(KEY_DUTY_DAY_START, dutyDayStart);
		writer.writeInt(KEY_DUTY_INDEX, dutyIndex);
		writer.writeString(KEY_DUTY_DAY, dutySlot >= 0 && getDutyDay() != null ? getDutyDay().toText() : "");
		writer.writeLong(KEY_DUTY_SIDING, dutySidingId);
	}

	public void setSiding(long newSidingId) {
		sidingId = newSidingId;
	}

	/**
	 * @param newRoutes      finds a route by ID, or null if the routes aren't known yet
	 * @param newRouteDepots finds the depot running a route, or null if the routes aren't known yet
	 */
	public void setNetwork(Board newDepot, LongFunction<? extends PlanRoute> newRoutes, LongFunction<? extends Board> newRouteDepots) {
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
			isLeavingForDuty = false;
		} else if (isLeavingForDuty && isOnDuty()) {
			return;
		}
		if (isOnRoute) {
			// Duties say which train runs what, so nothing is reserved for others to see
			if (isOnDuty()) {
				return;
			}
			reserveTrip(tripRouteId, tripMillis);
			if (nextTripMillis != 0) {
				final PlanRoute lapRoute = getLapRoute();
				if (lapRoute != null) {
					reserveTrip(lapRoute.getRouteId(), nextTripMillis);
				}
			}
		} else if (tripMillis != 0 || isLastLap || tripPath != null || dutySlot >= 0) {
			if (TripPath.isReturnKey(tripPathKey)) {
				returnedElsewhere = true;
				returnedToSidingId = TripPath.getReturnSidingId(tripPathKey);
			}
			dutySlot = -1;
			dutySidingId = 0;
			returnChosenStop = -1;
			dutyIndex = 0;
			dutyDay = null;
			isLeavingForDuty = false;
			tripMillis = 0;
			nextTripMillis = 0;
			routeStartMillis = 0;
			isJoiningLoop = false;
			leaveTripPath();
			setLastLap(false);
		}
	}

	public void onLapComplete() {
		final PlanRoute lapRoute = isOnTrip() ? getLapRoute() : null;
		if (lapRoute != null) {
			tripMillis = nextTripMillis == 0 ? findLapService(lapRoute, clock.getAsLong()) : nextTripMillis;
			tripRouteId = lapRoute.getRouteId();
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
			tripRouteId = depot.getTrainRoutes(trainId).get(0).getRouteId();
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
		if (startPlan != null && startPlan.startsRoute && startPlan.route.getRouteId() != tripRouteId) {
			final long plannedStartMillis = getPlannedMillis(startPlan.routeStartMillis);
			final long readyMillis = clock.getAsLong() + startPlan.arrivalMillis - stopPlan.arrivalMillis;
			final boolean isLinked = tripPath != null && linkedService >= 0 && startPlan.route.getRouteId() == linkedRouteId;
			final long serviceMillis = !startPlan.waitsForService ? 0 : isLinked ? RouteTimetable.getClosestService(plannedStartMillis, linkedService) : depot.findNextService(getCurrentRoute(startPlan.route), Math.max(plannedStartMillis, readyMillis));
			tripMillis = serviceMillis == 0 ? plannedStartMillis : serviceMillis;
			tripRouteId = startPlan.route.getRouteId();
			if (isLinked) {
				setNextLink(getCurrentRoute(startPlan.route));
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
		final List<T> path = vehicle.getPath();
		final int firstIndex = isSameService ? nextStoppingIndex + 1 : stopTimes.getFirstIndex();
		for (int i = stopIndex; i >= firstIndex && i >= 0; i--) {
			if (path.get(i).getDwellTime() > 0 && vehicle.isStopFree(i)) {
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
	/**
	 * A train sent out much later than its duty needed joins it at the first stop it can still keep to, as it is put
	 * there when the simulation starts again, rather than running the whole way late (decoupling document, step D, depots
	 * sending trains out 3). It is put at a stop, not on a run, as it comes from the depot.
	 */
	public void joinDutyLate() {
		if (isOnDuty()) {
			alignDuty(false);
		}
	}

	private void alignDuty() {
		alignDuty(true);
	}

	/**
	 * @param onRun whether the train may be put on its way between two stops, not only at one
	 */
	private void alignDuty(boolean onRun) {
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

		final List<T> path = vehicle.getPath();
		int stopIndex = -1;
		for (int i = starts[index]; i < path.size() && stopIndex < 0; i++) {
			if (path.get(i).getDwellTime() > 0 && !path.get(i).isSidingTrack()) {
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
		for (int i = starts[0] + 1; onRun && i < path.size(); i++) {
			if (path.get(i).getDwellTime() > 0 && !path.get(i).isSidingTrack() && placeOnRun(i, starts, currentMillis)) {
				return;
			}
		}
		for (int i = stopIndex; i >= starts[0]; i--) {
			if (path.get(i).getDwellTime() > 0 && vehicle.isStopFree(i)) {
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
	 * Puts the train where its duty has it now on the run to this stop, if it has left the stop before and not got here
	 * yet: as far along as the fastest run would be, stretched to the planned running time, at the speed it has there.
	 * Unlike choosing a stop to start from again, no slack is given, so a train that has just left is put on its way.
	 *
	 * @return false if the train isn't on this run now, or the run's times aren't known
	 */
	private boolean placeOnRun(int stopIndex, int[] starts, long currentMillis) {
		final List<T> path = vehicle.getPath();
		int fromStop = -1;
		for (int i = stopIndex - 1; i >= starts[0] && i >= 0; i--) {
			if (path.get(i).getDwellTime() > 0 && !path.get(i).isSidingTrack()) {
				fromStop = i;
				break;
			}
		}
		if (fromStop < 0 || stopIndex >= path.size() || tripPath.timeSegments.isEmpty() || tripPath.distances.size() <= stopIndex) {
			return false;
		}
		int segment = 0;
		while (segment + 1 < starts.length && fromStop >= starts[segment + 1]) {
			segment++;
		}
		startDutySegment(segment);
		final RouteTimetable.StopPlan fromPlan = getStopPlan(fromStop);
		final RouteTimetable.StopPlan toPlan = getStopPlan(stopIndex);
		if (fromPlan == null || toPlan == null) {
			return false;
		}
		final long departMillis = getPlannedMillis(fromPlan.departureMillis);
		final long arriveMillis = getPlannedMillis(toPlan.arrivalMillis);
		if (currentMillis <= departMillis || currentMillis >= arriveMillis || !vehicle.isStopFree(stopIndex)) {
			return false;
		}
		final double from = tripPath.distances.get(fromStop), to = tripPath.distances.get(stopIndex);
		final double fastFrom = getFastTime(from, true), fastTo = getFastTime(to, false);
		if (Double.isNaN(fastFrom) || Double.isNaN(fastTo) || fastTo <= fastFrom) {
			return false;
		}
		// How much slower than the fastest run the plan is, and where the fastest run would be by now at that pace
		final double stretch = (arriveMillis - departMillis) / ((fastTo - fastFrom) * MILLIS_PER_TICK);
		final double fastNow = fastFrom + (currentMillis - departMillis) / (MILLIS_PER_TICK * stretch);
		double low = from, high = to;
		for (int i = 0; i < 40; i++) {
			final double middle = (low + high) / 2;
			if (getFastTime(middle, true) < fastNow) {
				low = middle;
			} else {
				high = middle;
			}
		}
		final TimeSegment timeSegment = getTimeSegment(low, true);
		final float speed = timeSegment == null ? 0 : (float) (timeSegment.getSpeed(low) / stretch);
		return vehicle.moveBetweenStops(fromStop, stopIndex, low, speed);
	}

	/**
	 * @param leaving at a stop, the time it leaves rather than arrives
	 * @return the time of the fastest run along the duty's path at this rail progress, in ticks, or NaN
	 */
	private double getFastTime(double railProgress, boolean leaving) {
		final TimeSegment timeSegment = getTimeSegment(railProgress, leaving);
		return timeSegment == null ? Double.NaN : timeSegment.getTime(Math.max(railProgress, timeSegment.startRailProgress));
	}

	/**
	 * Where a train turns back its progress jumps on by its length, so there the segment after the jump is taken.
	 */
	private TimeSegment getTimeSegment(double railProgress, boolean leaving) {
		for (final TimeSegment timeSegment : tripPath.timeSegments) {
			if (leaving ? railProgress >= timeSegment.startRailProgress && railProgress < timeSegment.endRailProgress : railProgress > timeSegment.startRailProgress && railProgress <= timeSegment.endRailProgress) {
				return timeSegment;
			}
		}
		if (leaving) {
			for (final TimeSegment timeSegment : tripPath.timeSegments) {
				if (timeSegment.startRailProgress >= railProgress) {
					return timeSegment;
				}
			}
		}
		return null;
	}

	/**
	 * @return the first stop from this one that the current service has not left yet, or a route after this one the train
	 * can start, or -1 if the service is over
	 */
	private int findRunningStop(int fromIndex, long currentMillis) {
		final List<T> path = vehicle.getPath();
		final int endIndex = vehicle.isRepeat() ? vehicle.getRepeatIndex2() : path.size();
		for (int i = Math.max(fromIndex, 0); i < endIndex; i++) {
			if (path.get(i).getDwellTime() > 0) {
				if (path.get(i).isSidingTrack() || isJoiningLoop && i >= getJoinEndIndex()) {
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
				if (startPlan != null && startPlan.startsRoute && startPlan.route.getRouteId() != tripRouteId) {
					final PlanRoute route = getCurrentRoute(startPlan.route);
					return !startPlan.waitsForService || route.getTimetable().getNextService(currentMillis, currentMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(route.getRouteId(), millis)) != 0 ? i : -1;
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
		final List<? extends PlanRoute> trainRoutes = depot.getTrainRoutes(trainId);
		if (isOnTrip() || trainRoutes.isEmpty()) {
			return false;
		}

		final PlanRoute firstRoute = trainRoutes.get(0);
		final RouteTimetable timetable = depot.getTimetable(trainId);
		final long runningMillis = timetable.getLatestService(currentMillis - stopTimes.getDurationMillis(), currentMillis, millis -> depot.isTripReserved(firstRoute.getRouteId(), millis));
		final long newTripMillis = runningMillis == 0 ? timetable.getNextService(currentMillis, currentMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(firstRoute.getRouteId(), millis)) : runningMillis;
		if (newTripMillis == 0) {
			return false;
		}

		tripMillis = newTripMillis;
		tripRouteId = firstRoute.getRouteId();
		routeStartMillis = 0;
		nextTripMillis = 0;
		isJoiningLoop = false;
		depot.reserveTrip(tripRouteId, tripMillis);
		updateTripPath();
		return true;
	}

	/**
	 * At a stop before a place where a train can turn back more than one way (the turn back rails after the last stop of a
	 * route, or the platforms it can turn back at), chooses one as the train is about to leave, and keeps it for the train
	 * until it is through. Each way round has a path of its own, which is the same as the one the train is on up to this
	 * stop, so the train changes onto it here.
	 * <p>
	 * Of the ways whose turn back rails are free, it takes the first in order with which the train leaves the first stop of
	 * the next route by the time its next service is planned to, and whose rails no other train has or holds; if none
	 * does, the one it can leave there soonest by. A train with no planned time for its next service takes the first free
	 * way.
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

		final TurnBackPoint turnBackPoint = turnBacks.get(point);
		final String tripKey = isOnTrip() ? tripPath.tripKey : "";
		int chosenWay = -1;
		TripPath<T> chosenPath = null;
		int chosenStopIndex = -1;
		// Of the ways that don't make the next service: clear ones before ones another train is on, then the soonest
		boolean chosenClear = false;
		long chosenReadyMillis = Long.MAX_VALUE;
		final long nowMillis = clock.getAsLong();
		for (int way = 0; way < turnBackPoint.getWayCount(); way++) {
			final List<UUID> rails = turnBackPoint.getRails(way);
			final boolean isDefaultWay = way == turnBackPoint.defaultWay;
			final TripPath<T> newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(TripPath.getTurnBackKey(tripKey, point, way));
			if (rails == null || newPath == null && !(isDefaultWay && tripKey.isEmpty()) || !vehicle.isTurnBackFree(rails)) {
				continue;
			}
			final int newStopIndex = turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point);
			final long[] times = getWayTimes(newPath, newStopIndex, newStopIndex + turnBackPoint.getLength(way), nowMillis);
			final List<UUID> wayRails = turnBackPoint.getWayRails(way);
			final boolean clear = wayRails == null || vehicle.isWayClear(wayRails);
			// No planned time to make, or making it on a clear way: the first such way is the one
			if (times == null || clear && times[0] <= times[1]) {
				chosenWay = way;
				chosenPath = newPath;
				chosenStopIndex = newStopIndex;
				break;
			}
			if (chosenWay < 0 || clear && !chosenClear || clear == chosenClear && times[0] < chosenReadyMillis) {
				chosenWay = way;
				chosenPath = newPath;
				chosenStopIndex = newStopIndex;
				chosenClear = clear;
				chosenReadyMillis = times[0];
			}
		}
		if (chosenWay < 0) {
			return true;
		}
		turnBackStop = chosenStopIndex;
		vehicle.reserveTurnBack(turnBackPoint.getRails(chosenWay), chosenStopIndex + turnBackPoint.getLength(chosenWay));
		if (chosenPath == tripPath) {
			return false;
		}
		tripPath = chosenPath;
		tripPathKey = chosenPath == null ? "" : chosenPath.key;
		stopTimes = chosenPath == null ? depotStopTimes : chosenPath.stopTimes;
		vehicle.changePath(chosenPath, chosenStopIndex);
		setLastLap(isLastLap);
		return true;
	}

	/**
	 * @param wayPath   a way round's path, or null for the usual path of the depot
	 * @param stopIndex the rail of the stop the train is leaving, on that path
	 * @param nextStart the rail of the first stop of the next route, on that path
	 * @return when the train, leaving now, could leave the first stop of the next route this way at the fastest, and when its
	 * next service is planned to leave there; or null if the next service has no planned time
	 */
	private long[] getWayTimes(TripPath<T> wayPath, int stopIndex, int nextStart, long nowMillis) {
		if (tripMillis == 0) {
			return null;
		}
		updatePlan();
		final StopTimes wayStopTimes = wayPath == null ? depotStopTimes : wayPath.stopTimes;
		if (planTimetable == null || wayStopTimes == null) {
			return null;
		}
		final int table = getTripTable(planRoutes);
		final int service = (int) Math.floorMod(tripMillis, (long) OperatingDay.MILLIS_PER_DAY);
		final RouteTimetable.StopPlan leavingPlan = wayStopTimes.getPlan(planRoutes, planTimetable, stopIndex, tripRouteId, table, service);
		final RouteTimetable.StopPlan nextPlan = wayStopTimes.getPlan(planRoutes, planTimetable, nextStart, tripRouteId, table, service);
		if (leavingPlan == null || nextPlan == null) {
			return null;
		}
		final long readyMillis = nowMillis + nextPlan.fastestDepartureMillis - leavingPlan.fastestDepartureMillis;
		// A duty plans every service it runs; other trains take the next service only as they reach it, except round a loop,
		// where it is reserved ahead
		final long plannedMillis = isOnDuty() ? getPlannedMillis(nextPlan.departureMillis) : nextTripMillis;
		return plannedMillis <= 0 ? null : new long[]{readyMillis, plannedMillis};
	}

	/**
	 * @return true if {@link #chooseTurnBack} or {@link #chooseDepotReturn} may do something at the next stop, so whether the
	 * train may leave there has to be asked every tick (S4f)
	 */
	public boolean mayChooseWayAtStop() {
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (depot != null && isOnDuty() && !tripPath.staysOnLine && !TripPath.isReturnKey(tripPathKey) && stopIndex != returnChosenStop && stopIndex == getLastDutyStop()) {
			return true;
		}
		if (turnBackStop != stopIndex && turnBacks.getPoint(tripPath == null ? null : tripPath.turnBackStops, stopIndex) >= 0) {
			return true;
		}
		if (depot == null || !depot.keepsTrainsOnLine() || tripMillis == 0 || isOnTrip() || tripPath != null && tripPath.changeStop >= 0) {
			return false;
		}
		final List<T> path = vehicle.getPath();
		if (stopIndex < 0 || stopIndex >= path.size()) {
			return false;
		}
		final long platformId = path.get(stopIndex).getStopId();
		final List<? extends PlanRoute> trainRoutes = depot.getTrainRoutes(trainId);
		for (int i = 0; i < trainRoutes.size(); i++) {
			final PlanRoute route = trainRoutes.get(i);
			if (route.getRouteId() == tripRouteId && !route.getStops().isEmpty() && route.getLastPlatformId() == platformId && route.getTimetable().isReturnService(tripMillis) && tripPaths.get(TripPath.getDepotReturnKey(i)) != null) {
				return true;
			}
		}
		return false;
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
		final List<T> path = vehicle.getPath();
		if (stopIndex < 0 || stopIndex >= path.size()) {
			return false;
		}
		final long platformId = path.get(stopIndex).getStopId();
		final List<? extends PlanRoute> trainRoutes = depot.getTrainRoutes(trainId);
		for (int i = 0; i < trainRoutes.size(); i++) {
			final PlanRoute route = trainRoutes.get(i);
			if (route.getRouteId() == tripRouteId && !route.getStops().isEmpty() && route.getLastPlatformId() == platformId && route.getTimetable().isReturnService(tripMillis)) {
				final TripPath<T> returnPath = tripPaths.get(TripPath.getDepotReturnKey(i));
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
		final PlanRoute firstRoute = depot.getTrainRoutes(trainId).get(0);
		final long currentMillis = clock.getAsLong();
		final long leadMillis = getLeadMillis();
		return timetable.getLatestService(Math.max(lastDeployedMillis, currentMillis + Math.min(leadMillis, 0) - RouteTimetable.MAX_DEPLOY_DELAY_MILLIS), currentMillis + leadMillis + DEPLOY_MARGIN_MILLIS, millis -> depot.isTripReserved(firstRoute.getRouteId(), millis));
	}

	/**
	 * @param lastDeployedMillis the latest service of the first route the depot has sent a train out for
	 * @return the earliest time {@link #findDeployService} could find a service, as long as no service is taken or let go
	 * meanwhile, or {@link Long#MAX_VALUE} if it never can
	 */
	public long getNextDeployMillis(long lastDeployedMillis) {
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		if (timetable == null) {
			return Long.MAX_VALUE;
		}
		final PlanRoute firstRoute = depot.getTrainRoutes(trainId).get(0);
		final long currentMillis = clock.getAsLong();
		final long leadMillis = getLeadMillis();
		final long fromMillis = Math.max(lastDeployedMillis, currentMillis + Math.min(leadMillis, 0) - RouteTimetable.MAX_DEPLOY_DELAY_MILLIS);
		final long serviceMillis = timetable.getNextService(fromMillis, currentMillis + RouteTimetable.MAX_STAY_MILLIS, millis -> depot.isTripReserved(firstRoute.getRouteId(), millis));
		return serviceMillis == 0 ? Long.MAX_VALUE : serviceMillis - leadMillis - DEPLOY_MARGIN_MILLIS;
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
	public void deployDuty(int slot, long dayStart, TripPath<T> dutyPath) {
		deployDuty(slot, 0, dayStart, dutyPath);
	}

	/**
	 * @param newDutySidingId the siding whose slot it is, if not the train's own, else 0 or the train's own
	 */
	public void deployDuty(int slot, long newDutySidingId, long dayStart, TripPath<T> dutyPath) {
		dutySlot = slot;
		dutySidingId = newDutySidingId == sidingId ? 0 : newDutySidingId;
		returnChosenStop = -1;
		dutyDayStart = dayStart;
		dutyDay = readDutyDay();
		isLeavingForDuty = true;
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
	/**
	 * Why a train staying on the line overnight has no duty to go on with from where it stands.
	 */
	public enum HoldReason {
		/**
		 * The slot it takes over has no duty that day.
		 */
		NO_DUTY,
		/**
		 * The duty starts from another platform than the one it stands at.
		 */
		OTHER_PLATFORM,
		/**
		 * Another train already ran the duty that day.
		 */
		ALREADY_RUN
	}

	/**
	 * @return why the train is waiting on the line with no duty to go on with, or null if it isn't
	 */
	public HoldReason getOvernightHold() {
		return overnightHold;
	}

	/**
	 * @return the stop the train waits at with no duty, or 0
	 */
	public long getHeldStopId() {
		final List<T> path = vehicle.getPath();
		return overnightHold == null || path.isEmpty() ? 0 : path.get(path.size() - 1).getStopId();
	}

	/**
	 * Takes a train waiting on the line with no duty straight back to its siding, by the player's command.
	 *
	 * @return whether it was waiting
	 */
	public boolean recallHeld() {
		if (overnightHold == null) {
			return false;
		}
		overnightHold = null;
		vehicle.returnToDepot();
		return true;
	}

	public void updateOvernight() {
		if (!isOnDuty() || !tripPath.staysOnLine || vehicle.getNextStoppingIndex() < vehicle.getPath().size() - 1) {
			return;
		}
		final long dayStart = DutyShapes.getDayStart(clock.getAsLong());
		if (dayStart <= dutyDayStart + OperatingDay.MILLIS_PER_DAY / 2) {
			return;
		}
		// The new day is where an edit takes effect, so which slot comes next is read as the depot has it now
		final DepotDuties.DayDuty day = readDutyDay();
		final long slotSidingId = getDutySidingId();
		final List<DepotDuties.Slot> slots = depot.getDutySlots(slotSidingId);
		final int nextSlot = day == null ? -1 : DutyShapes.getNextSlot(day, dutySlot);
		final int weekday = OperatingDay.getWeekday(dayStart);
		final String key = nextSlot < 0 || routes == null ? null : DutyShapes.getSlotKey(slots, nextSlot, weekday, routes);
		final TripPath<T> nextPath = key == null ? null : tripPaths.get(key);
		final List<T> path = vehicle.getPath();
		final long dayNumber = DutyShapes.getDayNumber(dayStart);
		// With no duty to go on with from where it stands, it waits there to be seen to, rather than vanishing into its siding
		// (P1-C): it goes on once a duty starting there is set, or when the player takes it straight back
		final HoldReason reason = nextPath == null || nextPath.path.isEmpty() ? HoldReason.NO_DUTY : nextPath.path.get(0).getStopId() != path.get(path.size() - 1).getStopId() ? HoldReason.OTHER_PLATFORM : depot.hasDutyRun(slotSidingId, nextSlot, dayNumber) ? HoldReason.ALREADY_RUN : null;
		if (reason != null) {
			if (overnightHold != reason) {
				System.out.println("A train staying on the line overnight has no duty starting where it is (" + reason + "), and waits there: " + key);
			}
			overnightHold = reason;
			return;
		}
		overnightHold = null;
		depot.markDutyRun(slotSidingId, nextSlot, dayNumber);
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
			final PlanRoute route = routes == null ? null : routes.apply(segment.routeId);
			if (route != null && segment.fromStop > 0 && stopTimes != null) {
				tripMillis += stopTimes.getEntryOffsetMillis(route, segment.fromStop, route.getTimetable().getTableIndex(tripMillis), segment.service);
			}
		} else {
			final RouteTimetable.StopPlan stopPlan = getStopPlan(tripPath.dutyStarts[index]);
			final RouteTimetable.StopPlan startPlan = stopPlan == null || stopPlan.startsRoute ? stopPlan : stopPlan.nextRouteStart;
			routeStartMillis = startPlan == null ? 0 : startPlan.routeStartMillis;
		}
	}

	/**
	 * @return the duty the train took on, kept with it when saved, or for a train saved before that the one of its slot
	 * read again from the depot
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
		final List<DepotDuties.Slot> slots = depot.getDutySlots(getDutySidingId());
		final DepotDuties.DayDuty day = dutySlot < slots.size() ? slots.get(dutySlot).getDuty(OperatingDay.getWeekday(dutyDayStart + OperatingDay.MILLIS_PER_DAY / 2)) : null;
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
	 * @return the siding whose slot the train's duty is
	 */
	public long getDutySidingId() {
		return dutySidingId == 0 ? sidingId : dutySidingId;
	}

	/**
	 * At the last stop of its duty, about to go back, a train of a depot whose sidings share their trains chooses the
	 * siding of its pool that needs a train next and changes onto the same duty's path back to that siding (decoupling
	 * document, step D, depots sending trains out 5). It chooses once, at that stop.
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseDutyReturn() {
		if (depot == null || !isOnDuty() || tripPath.staysOnLine || TripPath.isReturnKey(tripPathKey)) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (stopIndex == returnChosenStop || stopIndex != getLastDutyStop()) {
			return false;
		}
		returnChosenStop = stopIndex;
		// The last service of the duty may be set on the running diagram to go back to a depot of its own choosing
		final long orderDepotId = getReturnOrderDepot();
		// A train that went another way round a turn back point takes the way back of the duty's usual path, whose last stop
		// can be on another rail
		final boolean otherWayRound = tripPath.turnBackPoint >= 0;
		// Only a siding the duty has a way back to is chosen, rather than one it can't reach and then its own after all
		final String returnFrom = otherWayRound ? tripPath.tripKey : tripPathKey;
		final long returnSidingId = depot.chooseReturnSiding(sidingId, orderDepotId, otherSidingId -> otherSidingId == sidingId || tripPaths.get(TripPath.getReturnKey(returnFrom, otherSidingId)) != null);
		final TripPath<T> returnPath = returnSidingId == sidingId ? null : tripPaths.get(TripPath.getReturnKey(otherWayRound ? tripPath.tripKey : tripPathKey, returnSidingId));
		if (returnPath == null) {
			return false;
		}
		final int newStopIndex = otherWayRound ? getLastDutyStop(returnPath) : stopIndex;
		if (newStopIndex < 0) {
			return false;
		}
		tripPath = returnPath;
		tripPathKey = returnPath.key;
		stopTimes = returnPath.stopTimes;
		vehicle.changePath(returnPath, newStopIndex);
		return true;
	}

	/**
	 * @return the depot the duty's last service is set to go back to, or 0
	 */
	private long getReturnOrderDepot() {
		if (dutyDay == null || dutyDay.segments.isEmpty() || routes == null) {
			return 0;
		}
		final DepotDuties.Segment segment = dutyDay.segments.get(dutyDay.segments.size() - 1);
		final PlanRoute route = routes.apply(segment.routeId);
		final RouteTimetable.ServiceOrder order = route == null ? null : route.getTimetable().getReturnOrder(segment.service);
		return order == null ? 0 : order.depotId;
	}

	/**
	 * @return the last stop of the duty's path before the way into the siding, or -1
	 */
	private int getLastDutyStop() {
		return getLastDutyStop(tripPath);
	}

	private static <T extends PlanTrack> int getLastDutyStop(TripPath<T> dutyPath) {
		final List<T> path = dutyPath.path;
		for (int i = path.size() - 1; i >= 0; i--) {
			if (path.get(i).getDwellTime() > 0 && !path.get(i).isSidingTrack()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @return true once, after the train came back to another siding of its pool than its own, for its own siding to let it
	 * go
	 */
	public boolean takeReturnedElsewhere() {
		final boolean returned = returnedElsewhere;
		returnedElsewhere = false;
		return returned;
	}

	/**
	 * As {@link #takeReturnedElsewhere}, saying where to.
	 *
	 * @return once, the siding the train came back to if it isn't its own, else 0
	 */
	public long takeReturnedElsewhereSiding() {
		final long sidingId = returnedElsewhere ? returnedToSidingId : 0;
		returnedElsewhere = false;
		return sidingId;
	}

	/**
	 * @param depotStopTimes the stop times of the usual path of the siding
	 * @param newTripPaths   the paths of services leaving it
	 */
	public void setStopTimes(StopTimes newDepotStopTimes, Map<String, TripPath<T>> newTripPaths) {
		setStopTimes(newDepotStopTimes, newTripPaths, TurnBacks.NONE);
	}

	/**
	 * @param newTurnBacks where trains of the siding can turn back on more than one rail
	 */
	public void setStopTimes(StopTimes newDepotStopTimes, Map<String, TripPath<T>> newTripPaths, TurnBacks newTurnBacks) {
		depotStopTimes = newDepotStopTimes;
		stopTimes = tripPath == null ? newDepotStopTimes : tripPath.stopTimes;
		tripPaths = newTripPaths;
		turnBacks = newTurnBacks;
	}

	public StopTimes getStopTimes() {
		return stopTimes;
	}

	/**
	 * @return true if the train isn't on the usual path of the depot, which it then keeps until it is back
	 */
	/**
	 * @return the key of the path of the service the train is on, or empty for the usual path of its siding
	 */
	public String getTripPathKey() {
		return tripPathKey;
	}

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
	public boolean restoreTripPath(Map<String, TripPath<T>> newTripPaths) {
		if (tripPathKey.isEmpty()) {
			return true;
		}
		final TripPath<T> newTripPath = newTripPaths.get(tripPathKey);
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
		final List<? extends PlanRoute> trainRoutes = depot.getTrainRoutes(trainId);
		final PlanRoute lastRoute = trainRoutes.isEmpty() ? null : trainRoutes.get(trainRoutes.size() - 1);
		if (lastRoute != null && lastRoute.getRouteId() == tripRouteId) {
			final RouteTimetable.ServiceLink link = lastRoute.getTimetable().getLink(tripMillis);
			final TripPath<T> newTripPath = link == null || routes == null ? null : tripPaths.get(TripTail.getKey(RouteTimetable.getLinkChain(link, routeId -> {
				final PlanRoute route = routes.apply(routeId);
				return route == null ? null : route.getTimetable();
			})));
			// A train that went another way round a turn back point keeps to it, as it may be past the point already
			final TripPath<T> turnBackPath = newTripPath == null || tripPath == null ? newTripPath : tripPaths.get(TripPath.getTurnBackKey(newTripPath.tripKey, tripPath.turnBackPoint, tripPath.turnBackWay));
			if (turnBackPath != null) {
				linkedService = link.service;
				linkedRouteId = link.routeId;
				useTripPath(turnBackPath);
			}
		}
	}

	private void useTripPath(TripPath<T> newTripPath) {
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
	private void setNextLink(PlanRoute route) {
		final RouteTimetable.ServiceLink link = route.getTimetable().getLinkOfService(linkedService);
		linkedRouteId = link == null ? 0 : link.routeId;
		linkedService = link == null ? -1 : link.service;
	}

	/**
	 * Reaching the stop where the trip joins a loop part of the way round. Like a train from the depot joining a loop, the
	 * train reaches this stop when that service would, and that service ends at the first stop of the loop.
	 */
	private void joinLapRoute() {
		final PlanRoute lapRoute = getLapRoute();
		if (lapRoute != null && linkedService >= 0) {
			final long currentMillis = clock.getAsLong();
			tripRouteId = lapRoute.getRouteId();
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
	private PlanRoute getLapRoute() {
		final List<? extends PlanRoute> newTripRoutes = getTripRoutes();
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
	private long findLapService(PlanRoute route, long readyMillis) {
		final Board routeDepot = routeDepots == null ? null : routeDepots.apply(route.getRouteId());
		final long newTripMillis = route.getTimetable().getNextService(readyMillis, readyMillis + RouteTimetable.MAX_LAYOVER_MILLIS, millis -> depot.isTripReserved(route.getRouteId(), millis) || routeDepot != null && routeDepot.isTripReserved(route.getRouteId(), millis));
		if (newTripMillis != 0) {
			reserveTrip(route.getRouteId(), newTripMillis);
		}
		return newTripMillis;
	}

	/**
	 * @return the routes of the path the train is on
	 */
	private List<? extends PlanRoute> getTripRoutes() {
		if (!isOnTrip()) {
			return depot.getTrainRoutes(trainId);
		}
		if (tripRoutes.isEmpty() && routes != null) {
			tripPath.routeIds.forEach(routeId -> {
				final PlanRoute route = routes.apply(routeId);
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
			final List<? extends PlanRoute> trainRoutes = depot.getTrainRoutes(trainId);
			final RouteTimetable.StopPlan stopPlan = stopTimes.getPlan(trainRoutes, timetable, stopTimes.getFirstJoinStopLapIndex(), trainRoutes.get(0).getRouteId(), 0, -1);
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
		if (tripMillis == 0 || stopTimes == null) {
			return null;
		}
		updatePlan();
		if (planTimetable == null) {
			return null;
		}
		return stopTimes.getPlan(planRoutes, planTimetable, stopIndex, tripRouteId, getTripTable(planRoutes), (int) Math.floorMod(tripMillis, (long) OperatingDay.MILLIS_PER_DAY));
	}

	/**
	 * Takes the newest plan when the train has changed service, route, duty segment or path since it took the one it has.
	 */
	private void updatePlan() {
		final int newDutyIndex = isOnDuty() ? dutyIndex : -1;
		if (planRoutes != null && planTripMillis == tripMillis && planRouteId == tripRouteId && planTripPath == tripPath && planStopTimes == stopTimes && planDutyIndex == newDutyIndex) {
			return;
		}
		final PlanRoute dutyRoute = isOnDuty() && routes != null ? routes.apply(tripRouteId) : null;
		final RouteTimetable timetable = dutyRoute != null ? dutyRoute.getTimetable() : depot == null ? null : depot.getTimetable(trainId);
		planRoutes = PlanVersions.fix(getTripRoutes());
		planTimetable = timetable == null ? null : PlanVersions.fix(timetable);
		planTripMillis = tripMillis;
		planRouteId = tripRouteId;
		planTripPath = tripPath;
		planStopTimes = stopTimes;
		planDutyIndex = newDutyIndex;
	}

	/**
	 * @return the route as it is now, for choosing what the train does next, rather than as the train's plan has it
	 */
	private PlanRoute getCurrentRoute(PlanRoute planRoute) {
		final PlanRoute route = routes == null ? null : routes.apply(planRoute.getRouteId());
		return route == null ? planRoute : route;
	}

	/**
	 * @return the table of the current route that the current service belongs to
	 */
	private int getTripTable(List<? extends PlanRoute> newTripRoutes) {
		for (final PlanRoute route : newTripRoutes) {
			if (route.getRouteId() == tripRouteId) {
				return route.getTimetable().getTableIndex(tripMillis);
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
	public interface Vehicle<T extends PlanTrack> {

		List<T> getPath();

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
		void followPath(TripPath<T> tripPath);

		/**
		 * Moves the train, stopped before a turn back point, onto a path that is the same from there to the point.
		 *
		 * @param tripPath     the new path, or null for the usual path of the depot
		 * @param newStopIndex the rail of the same stop on the new path
		 */
		void changePath(TripPath<T> tripPath, int newStopIndex);

		/**
		 * @return true if no other train is on these rails or has them kept for itself
		 */
		boolean isTurnBackFree(List<UUID> rails);

		/**
		 * Keeps these rails for the train until it reaches this rail of its path.
		 */
		void reserveTurnBack(List<UUID> rails, int untilIndex);

		/**
		 * @return true if no other train is on these rails, nor holds them or a turnout between them (single track and
		 * station throats), so the train could run along them now without waiting
		 */
		boolean isWayClear(List<UUID> rails);

		/**
		 * @return true if the whole train fits behind the end of this stop's rail without touching another train
		 */
		boolean isStopFree(int stopIndex);

		void moveToStop(int stopIndex);

		/**
		 * Puts the train on its run from one stop to the next, at this rail progress and speed, as when its duty has it
		 * there at the time the simulation starts again (decoupling document, step D).
		 *
		 * @return false if the vehicle can't be put there, and is then put at a stop instead
		 */
		default boolean moveBetweenStops(int fromStop, int toStop, double railProgress, float speed) {
			return false;
		}

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
		List<? extends PlanRoute> getTrainRoutes(String trainId);

		/**
		 * @return the first service of this vehicle's first route from this time on that no train has taken, which is then taken, or 0 if a train would wait too long for it
		 */
		long findNextTrip(String trainId, long readyMillis);

		/**
		 * @return the first service of this route from this time on that no train has taken, which is then taken, or 0 if a train would wait too long for it
		 */
		long findNextService(PlanRoute route, long readyMillis);

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

		/**
		 * @param depotId   the depot the duty's last service is set to go back to, or 0: then one of its sidings with the same
		 *                  vehicle and room, the one needing a train soonest; with none, the usual choice, which is told
		 * @param canGoBack whether the duty has a way back to a siding, which only those are chosen from
		 * @return the siding of the pool a train of this siding goes back to after its duty, its own unless another one needs
		 * a train sooner (see {@link DepotDispatcher#chooseReturnSiding})
		 */
		default long chooseReturnSiding(long sidingId, long depotId, java.util.function.LongPredicate canGoBack) {
			return sidingId;
		}
	}
}
