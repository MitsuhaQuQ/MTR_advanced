package transitsim.sim;

import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PathVariants;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
	 * The number of the duty the train runs, kept with it when saved, so the duty is found again wherever it has been moved
	 * (P2-D); 0 if not known.
	 */
	private long dutyId;
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
	private transitsim.plan.DutyLedger.Entry dutyLedger;
	/**
	 * Sent out for a duty but still in the siding, waiting for its run out of it to be granted. The train keeps its duty
	 * meanwhile, unlike a train back in the depot, which ends it.
	 */
	private boolean isLeavingForDuty;
	/**
	 * A segment of the duty whose service the train, late, took over from another train, and that service as a full
	 * timestamp, on the duty of {@link #rehookDayStart}; -1 for none (user 2026-11-14).
	 */
	private int rehookSegment = -1;
	private long rehookMillis;
	private long rehookDayStart;
	/**
	 * The platforms a plan or a dispatcher has the train pass without stopping for now, as the host tells it (D3).
	 */
	private Set<Long> skipPlatforms = Collections.emptySet();
	/**
	 * The stop at which the train chose which stops to pass, so it chooses once there, or -1.
	 */
	private int skipChosenStop = -1;
	/**
	 * Makes the paths passing stops on demand, or null where the host gives none, so the train never skips.
	 */
	private PathVariants<T> pathVariants;
	/**
	 * The last path passing stops made, which is the one the train is on, and those of its siding share no copy of it.
	 */
	private TripPath<T> skipPath;
	/**
	 * Where the host has the train turn back early (D4): 0 not, {@link transitsim.plan.Contingency#TURN_NEAREST} at the
	 * nearest stop it can, {@link transitsim.plan.Contingency#TURN_STATION} at one of {@link #turnPlatforms}; and then on with
	 * its duty or back to the siding ({@link transitsim.plan.Contingency#AFTER_RESUME} or {@code AFTER_DEADHEAD}).
	 */
	private int turnMode;
	private Set<Long> turnPlatforms = Collections.emptySet();
	private int turnAfter;
	/**
	 * The stop at which the train chose whether to turn back, so it chooses once there, or -1.
	 */
	private int turnChosenStop = -1;
	/**
	 * Told to turn back at a station it can't turn back at, as far as its duty goes on that segment.
	 */
	private boolean turnUnreachable;
	/**
	 * The last path turned back early made, which is the one the train is on or passes stops of.
	 */
	private TripPath<T> turnPath;
	/**
	 * What a dispatcher ordered for the train (D6): {@link #DISPATCH_HOLD} to keep it at its next stop until let go,
	 * {@link #DISPATCH_CLEAR_OUT} to clear it out at its next stop and take it back empty, {@link #DISPATCH_RETURN} to take
	 * it back after this segment; the station to turn it back at; and how much longer to stand at a stop.
	 */
	private int dispatchFlags;
	private Set<Long> dispatchTurnPlatforms = Collections.emptySet();
	private long extraDwellMillis;
	/**
	 * Standing at a stop longer than planned, until when, or 0.
	 */
	private long holdUntilMillis;
	/**
	 * The stop at which the train carried out what a dispatcher ordered, so it does once there, or -1.
	 */
	private int dispatchChosenStop = -1;
	public static final int DISPATCH_HOLD = 1;
	public static final int DISPATCH_CLEAR_OUT = 2;
	public static final int DISPATCH_RETURN = 4;
	/**
	 * The commands of {@link #dispatch}.
	 */
	public static final int COMMAND_HOLD = 1;
	public static final int COMMAND_RELEASE = 2;
	public static final int COMMAND_EXTEND_DWELL = 3;
	public static final int COMMAND_TURN_AT = 4;
	public static final int COMMAND_CLEAR_OUT = 5;
	public static final int COMMAND_RETURN = 6;
	public static final int COMMAND_CANCEL = 7;
	/**
	 * Told to hand the rest of its duty over to a spare train from the depot (D5).
	 */
	private boolean takeover;
	/**
	 * The stop at which the train chose whether to hand over, so it chooses once there, or -1.
	 */
	private int handOverChosenStop = -1;
	/**
	 * Why it couldn't hand over when it last tried: {@link #TAKEOVER_NO_SPARE} or {@link #TAKEOVER_CROSSES}, or 0.
	 */
	private int handOverFailure;
	/**
	 * A spare is on its way: it may take over, and has.
	 */
	public static final int TAKEOVER_OK = 0;
	/**
	 * No spare can leave in time, or none is in the depot.
	 */
	public static final int TAKEOVER_NO_SPARE = 3;
	/**
	 * A spare could, but its way out runs through where the late train is held up or still has to run (D5).
	 */
	public static final int TAKEOVER_CROSSES = 4;

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
	private static final String KEY_DUTY_ID = "duty_id";
	/**
	 * The platforms the train is told to pass (D3).
	 */
	private static final String KEY_SKIP_PLATFORMS = "skip_platforms";
	/**
	 * Where the train is told to turn back early (D4): mode, then what after, then the platforms.
	 */
	private static final String KEY_TURN_BACK = "turn_back";
	/**
	 * Whether the train is told to hand its duty over (D5).
	 */
	private static final String KEY_TAKEOVER = "takeover";
	/**
	 * What a dispatcher ordered (D6): flags, extra dwell, held until, then the platforms to turn back at.
	 */
	private static final String KEY_DISPATCH = "dispatch";
	/**
	 * The service a late train took over (0.78.0): the segment, the service, then the duty's day.
	 */
	private static final String KEY_REHOOK = "rehook";
	/**
	 * How many values {@link #write} writes, which hosts count into the size of what they save. Checked by a test, as it was
	 * once left one short.
	 */
	public static final int SAVED_KEYS = 20;
	private static final long MILLIS_PER_TICK = 50;
	/**
	 * How early a train aims to reach the first stop, so a small delay on the way doesn't make it late.
	 */
	private static final int DEPLOY_MARGIN_MILLIS = 15000;
	/**
	 * How late a train on a duty may be at the first stop of a segment before it takes another service there that no train
	 * runs on time, and how late it may be anywhere before its next segment's service counts as one (user 2026-11-14).
	 */
	public static final long LATE_REHOOK_MILLIS = 10 * 60 * 1000;
	private static final boolean DEBUG_TURN_BACK = Boolean.getBoolean("mtr.debugTurnBack");
	/**
	 * How long at most a late train waits at the first stop of a segment for a service it takes over.
	 */
	public static final long REHOOK_WAIT_MILLIS = 60 * 60 * 1000;

	public TrainService(String trainId, Vehicle<T> vehicle, LongSupplier clock) {
		this.trainId = trainId;
		this.vehicle = vehicle;
		this.clock = clock;
	}

	public void read(KeyedReader reader) {
		tripPathKey = transitsim.plan.PathKeyIndex.resolve(reader.getString(KEY_TRIP_PATH, ""));
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
		dutyId = reader.getLong(KEY_DUTY_ID, 0);
		final long[] skips = reader.getLongs(KEY_SKIP_PLATFORMS);
		final Set<Long> newSkips = new TreeSet<>();
		for (final long platformId : skips) {
			newSkips.add(platformId);
		}
		skipPlatforms = newSkips.isEmpty() ? Collections.emptySet() : newSkips;
		final long[] turnValues = reader.getLongs(KEY_TURN_BACK);
		final Set<Long> newTurnPlatforms = new TreeSet<>();
		for (int i = 2; i < turnValues.length; i++) {
			newTurnPlatforms.add(turnValues[i]);
		}
		turnMode = turnValues.length >= 2 ? (int) turnValues[0] : 0;
		turnAfter = turnValues.length >= 2 ? (int) turnValues[1] : 0;
		turnPlatforms = newTurnPlatforms.isEmpty() ? Collections.emptySet() : newTurnPlatforms;
		takeover = reader.getBoolean(KEY_TAKEOVER, false);
		final long[] dispatchValues = reader.getLongs(KEY_DISPATCH);
		dispatchFlags = dispatchValues.length >= 3 ? (int) dispatchValues[0] : 0;
		extraDwellMillis = dispatchValues.length >= 3 ? dispatchValues[1] : 0;
		holdUntilMillis = dispatchValues.length >= 3 ? dispatchValues[2] : 0;
		final Set<Long> newDispatchTurn = new TreeSet<>();
		for (int i = 3; i < dispatchValues.length; i++) {
			newDispatchTurn.add(dispatchValues[i]);
		}
		dispatchTurnPlatforms = newDispatchTurn.isEmpty() ? Collections.emptySet() : newDispatchTurn;
		final long[] rehook = reader.getLongs(KEY_REHOOK);
		rehookSegment = rehook.length >= 3 ? (int) rehook[0] : -1;
		rehookMillis = rehook.length >= 3 ? rehook[1] : 0;
		rehookDayStart = rehook.length >= 3 ? rehook[2] : 0;
		// Saved before trains kept their duty, it is read again from the depot when needed
		dutyLedger = dutySlot >= 0 ? transitsim.plan.DutyLedger.read(reader.getString(KEY_DUTY_DAY, "")) : null;
		dutyDay = dutyLedger == null ? null : dutyLedger.day();
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
		transitsim.plan.PathKeyIndex.Entry pathReference = transitsim.plan.PathKeyIndex.entry(tripPathKey);
		if (pathReference != null) tripPathKey = pathReference.text;
		writer.writeString(KEY_TRIP_PATH, pathReference == null ? "" : pathReference.key);
		writer.writeInt(KEY_LINKED_SERVICE, linkedService);
		writer.writeLong(KEY_LINKED_ROUTE, linkedRouteId);
		writer.writeInt(KEY_DUTY_SLOT, dutySlot);
		writer.writeLong(KEY_DUTY_DAY_START, dutyDayStart);
		writer.writeInt(KEY_DUTY_INDEX, dutyIndex);
		final transitsim.plan.DutyLedger.Entry ledger = getDutyLedger();
		writer.writeString(KEY_DUTY_DAY, ledger == null ? "" : ledger.key);
		writer.writeLong(KEY_DUTY_SIDING, dutySidingId);
		writer.writeLong(KEY_DUTY_ID, dutyId);
		writer.writeLongs(KEY_SKIP_PLATFORMS, skipPlatforms.stream().mapToLong(Long::longValue).toArray());
		final long[] turnValues = new long[turnMode == 0 ? 0 : 2 + turnPlatforms.size()];
		if (turnMode != 0) {
			turnValues[0] = turnMode;
			turnValues[1] = turnAfter;
			int i = 2;
			for (final long platformId : turnPlatforms) {
				turnValues[i++] = platformId;
			}
		}
		writer.writeLongs(KEY_TURN_BACK, turnValues);
		writer.writeBoolean(KEY_TAKEOVER, takeover);
		final long[] dispatchValues = new long[hasDispatch() ? 3 + dispatchTurnPlatforms.size() : 0];
		if (dispatchValues.length > 0) {
			dispatchValues[0] = dispatchFlags;
			dispatchValues[1] = extraDwellMillis;
			dispatchValues[2] = holdUntilMillis;
			int i = 3;
			for (final long platformId : dispatchTurnPlatforms) {
				dispatchValues[i++] = platformId;
			}
		}
		writer.writeLongs(KEY_DISPATCH, dispatchValues);
		writer.writeLongs(KEY_REHOOK, rehookSegment < 0 ? new long[0] : new long[]{rehookSegment, rehookMillis, rehookDayStart});
	}

	/**
	 * Carries out a dispatcher's command (D6). Holding and standing longer take effect at the stop the train is at or comes
	 * to; turning back, clearing out and going back take effect when it chooses at a stop, and override the plans.
	 *
	 * @param command   one of the {@code COMMAND_} values
	 * @param value     for standing longer, how much, in milliseconds
	 * @param platforms for turning back, the platforms of the station
	 */
	public void dispatch(int command, long value, Set<Long> platforms) {
		switch (command) {
			case COMMAND_HOLD:
				dispatchFlags |= DISPATCH_HOLD;
				break;
			case COMMAND_RELEASE:
				dispatchFlags &= ~DISPATCH_HOLD;
				holdUntilMillis = 0;
				extraDwellMillis = 0;
				break;
			case COMMAND_EXTEND_DWELL:
				extraDwellMillis = Math.max(0, Math.min(value, MAX_EXTRA_DWELL_MILLIS));
				break;
			case COMMAND_TURN_AT:
				dispatchTurnPlatforms = platforms == null || platforms.isEmpty() ? Collections.emptySet() : new TreeSet<>(platforms);
				turnChosenStop = -1;
				break;
			case COMMAND_CLEAR_OUT:
				dispatchFlags |= DISPATCH_CLEAR_OUT;
				dispatchChosenStop = -1;
				turnChosenStop = -1;
				break;
			case COMMAND_RETURN:
				dispatchFlags |= DISPATCH_RETURN;
				dispatchChosenStop = -1;
				break;
			case COMMAND_CANCEL:
				dispatchFlags = 0;
				dispatchTurnPlatforms = Collections.emptySet();
				extraDwellMillis = 0;
				holdUntilMillis = 0;
				break;
			default:
				break;
		}
	}

	/**
	 * The longest a dispatcher may have a train stand longer at a stop.
	 */
	public static final long MAX_EXTRA_DWELL_MILLIS = 30 * 60 * 1000;

	public boolean hasDispatch() {
		return dispatchFlags != 0 || !dispatchTurnPlatforms.isEmpty() || extraDwellMillis > 0 || holdUntilMillis > 0;
	}

	/**
	 * @return what a dispatcher ordered, as {@code DISPATCH_} flags, with 8 for standing longer and 16 for turning back
	 */
	public int getDispatchState() {
		return dispatchFlags | (extraDwellMillis > 0 || holdUntilMillis > clock.getAsLong() ? 8 : 0) | (dispatchTurnPlatforms.isEmpty() ? 0 : 16);
	}

	public Set<Long> getDispatchTurnPlatforms() {
		return Collections.unmodifiableSet(dispatchTurnPlatforms);
	}

	/**
	 * @return whether a dispatcher keeps the train at the stop it is at (D6): held until let go, or standing longer, which
	 * starts counting at this stop
	 */
	public boolean isDispatchHeld() {
		final long currentMillis = clock.getAsLong();
		if (extraDwellMillis > 0) {
			holdUntilMillis = currentMillis + extraDwellMillis;
			extraDwellMillis = 0;
		}
		if (holdUntilMillis != 0 && currentMillis >= holdUntilMillis) {
			holdUntilMillis = 0;
		}
		return (dispatchFlags & DISPATCH_HOLD) != 0 || holdUntilMillis != 0;
	}

	/**
	 * At a stop of its duty, ready to go, the train carries out a dispatcher's order to clear out or go back (D6):
	 * <ul>
	 * <li>to clear out at its next stop, it turns back there and goes back empty if it can (as {@link #chooseEarlyTurn});
	 * otherwise it runs empty from there to the end of this segment and back to its siding. With a station to turn back at,
	 * turning back does it, going back empty;</li>
	 * <li>to go back, it runs this segment to its end, everyone gets off, and goes back to its siding empty.</li>
	 * </ul>
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseDispatch() {
		if ((dispatchFlags & (DISPATCH_CLEAR_OUT | DISPATCH_RETURN)) == 0 || !isOnDuty() || pathVariants == null || routes == null) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (stopIndex == dispatchChosenStop) {
			return false;
		}
		dispatchChosenStop = stopIndex;
		final String baseKey = TripPath.getSkipBase(tripPathKey);
		if (TripPath.isChangedKey(baseKey) || TripPath.isReturnKey(baseKey)) {
			// Already going back, or turned back
			dispatchFlags &= ~(DISPATCH_CLEAR_OUT | DISPATCH_RETURN);
			return false;
		}
		final TripPath<T> base = tripPaths.get(baseKey);
		if (base == null || base.dutyStarts == null || base.stopTimes.getSegmentEntry(0) < 0) {
			return false;
		}
		final List<PlanRoute> dutyRoutes = new ArrayList<>();
		for (final long routeId : base.routeIds) {
			final PlanRoute route = routes.apply(routeId);
			if (route == null) {
				return false;
			}
			dutyRoutes.add(route);
		}
		final int segment = dutyIndex;
		if (segment >= dutyRoutes.size()) {
			return false;
		}
		final long[][] times = base.stopTimes.getSegmentTimes(segment, dutyRoutes);
		int lastStopRail = -1;
		int nextStopRail = -1;
		for (final long[] time : times) {
			if (time[0] >= 0) {
				lastStopRail = (int) time[2];
				if (nextStopRail < 0 && time[2] > stopIndex) {
					nextStopRail = (int) time[2];
				}
			}
		}
		final boolean clearOut = (dispatchFlags & DISPATCH_CLEAR_OUT) != 0;
		if (clearOut) {
			// A station to turn back at takes it there, going back empty (chooseEarlyTurn)
			if (!dispatchTurnPlatforms.isEmpty() || turnMode != 0) {
				return false;
			}
			if (nextStopRail >= 0 && turnAt(transitsim.plan.Contingency.TURN_NEAREST, Collections.emptySet(), false, nextStopRail, stopIndex)) {
				dispatchFlags &= ~DISPATCH_CLEAR_OUT;
				return true;
			}
		}
		// The last segment goes back anyway, so going back after it changes nothing; clearing out at its next stop still runs
		// it empty from there
		final boolean lastSegment = segment + 1 >= base.dutyStarts.length;
		if (lastStopRail < stopIndex || lastSegment && (!clearOut || nextStopRail < 0 || nextStopRail >= lastStopRail)) {
			dispatchFlags &= ~(DISPATCH_CLEAR_OUT | DISPATCH_RETURN);
			return false;
		}
		final String endKey = TripPath.getEndKey(baseKey, segment, lastStopRail, clearOut && nextStopRail >= 0 ? nextStopRail : lastStopRail);
		final TripPath<T> newPath = findPath(tripPaths, endKey);
		if (newPath == null) {
			return false;
		}
		dispatchFlags &= ~(DISPATCH_CLEAR_OUT | DISPATCH_RETURN);
		tripPath = newPath;
		tripPathKey = newPath.key;
		stopTimes = newPath.stopTimes;
		vehicle.changePath(newPath, stopIndex);
		return true;
	}

	/**
	 * Tells the train to hand the rest of its duty over to a spare train from the depot (D5), or not to. It chooses at a
	 * stop where, once.
	 */
	public void setTakeover(boolean newTakeover) {
		if (newTakeover != takeover) {
			takeover = newTakeover;
			handOverChosenStop = -1;
			handOverFailure = 0;
		}
	}

	public boolean getTakeover() {
		return takeover;
	}

	/**
	 * @return 0 if not told to hand over, 1 waiting for where to, 2 handed over and going back, {@link #TAKEOVER_NO_SPARE}
	 * or {@link #TAKEOVER_CROSSES} if it couldn't when it last tried (D5)
	 */
	public int getTakeoverState() {
		if (TripPath.isEndKey(tripPathKey)) {
			return 2;
		}
		return !takeover ? 0 : handOverFailure != 0 ? handOverFailure : 1;
	}

	/**
	 * At a stop of its duty, ready to go, a train told to hand its duty over chooses after which segment, from the one it
	 * is on, a spare train from the depot can take the next one over: one that can get out to its first stop in time, by a
	 * way out that doesn't run through where this train is held up or still has to run (D5). The depot takes the request;
	 * this train runs on to that segment's last stop, where everyone gets off, and goes back to its siding empty.
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseHandOver() {
		if (!takeover || !isOnDuty() || depot == null || routes == null || pathVariants == null) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (stopIndex == handOverChosenStop) {
			return false;
		}
		handOverChosenStop = stopIndex;
		final String baseKey = TripPath.getSkipBase(tripPathKey);
		if (TripPath.isChangedKey(baseKey) || TripPath.isReturnKey(baseKey)) {
			return false;
		}
		final TripPath<T> base = tripPaths.get(baseKey);
		final List<DepotDuties.Segment> segments = getDutySegments();
		if (base == null || base.dutyStarts == null || segments.size() != base.dutyStarts.length || base.stopTimes.getSegmentEntry(0) < 0) {
			return false;
		}
		final List<PlanRoute> dutyRoutes = new ArrayList<>();
		for (final long routeId : base.routeIds) {
			final PlanRoute route = routes.apply(routeId);
			if (route == null) {
				return false;
			}
			dutyRoutes.add(route);
		}
		int failure = TAKEOVER_NO_SPARE;
		for (int segment = dutyIndex; segment + 1 < segments.size(); segment++) {
			final long[][] times = base.stopTimes.getSegmentTimes(segment, dutyRoutes);
			int lastStopRail = -1;
			for (final long[] time : times) {
				if (time[0] >= 0) {
					lastStopRail = (int) time[2];
				}
			}
			if (lastStopRail < stopIndex) {
				continue;
			}
			// Where the train still runs before it hands over, and where it is held up
			final Set<java.util.UUID> affected = new HashSet<>();
			final List<java.util.UUID> remaining = new ArrayList<>();
			for (int i = Math.max(0, stopIndex - 1); i <= lastStopRail && i < base.path.size(); i++) {
				remaining.add(base.path.get(i).getTrackKey());
			}
			affected.addAll(vehicle.getAffectedRails(remaining));
			final int next = segment + 1;
			final long serviceMillis = DutyShapes.getServiceMillis(dutyDayStart, segments.get(next).service);
			final int result = depot.requestTakeover(getDutySidingId(), dutySlot, dutyDayStart, next, TripPath.getFromKey(baseKey, next), serviceMillis, affected);
			if (result == TAKEOVER_OK) {
				final TripPath<T> newPath = findPath(tripPaths, TripPath.getEndKey(baseKey, segment, lastStopRail));
				if (newPath == null) {
					return false;
				}
				handOverFailure = 0;
				tripPath = newPath;
				tripPathKey = newPath.key;
				stopTimes = newPath.stopTimes;
				vehicle.changePath(newPath, stopIndex);
				return true;
			}
			if (result == TAKEOVER_CROSSES) {
				failure = TAKEOVER_CROSSES;
			}
		}
		handOverFailure = failure;
		return false;
	}

	/**
	 * Tells the train where to turn back early (D4), or {@code mode} 0 not to. It turns back once on a duty, choosing at a
	 * stop, never between two.
	 *
	 * @param mode        0, {@link transitsim.plan.Contingency#TURN_NEAREST} or {@link transitsim.plan.Contingency#TURN_STATION}
	 * @param platformIds for a station, its platforms
	 * @param after       {@link transitsim.plan.Contingency#AFTER_RESUME} or {@link transitsim.plan.Contingency#AFTER_DEADHEAD}
	 */
	public void setTurnBack(int mode, Set<Long> platformIds, int after) {
		final Set<Long> newPlatforms = mode == 0 || platformIds == null || platformIds.isEmpty() ? Collections.emptySet() : new TreeSet<>(platformIds);
		final int newAfter = mode == 0 ? 0 : after;
		if (mode != turnMode || newAfter != turnAfter || !newPlatforms.equals(turnPlatforms)) {
			turnMode = mode;
			turnAfter = newAfter;
			turnPlatforms = newPlatforms;
			turnChosenStop = -1;
			turnUnreachable = false;
		}
	}

	public int getTurnMode() {
		return turnMode;
	}

	public int getTurnAfter() {
		return turnAfter;
	}

	public Set<Long> getTurnPlatforms() {
		return Collections.unmodifiableSet(turnPlatforms);
	}

	/**
	 * @return 0 if not told to turn back, 1 on its way to where it turns back, 2 turned back, 3 told to turn back at a
	 * station it can't (D4)
	 */
	public int getTurnState() {
		if (tripPath != null && tripPath.turnRail >= 0 && TripPath.isTurnKey(tripPathKey)) {
			return 2;
		}
		return turnMode == 0 ? 0 : turnUnreachable ? 3 : 1;
	}

	/**
	 * @return whether the train stops where it turns back early, where all riders get off (D4)
	 */
	public boolean isClearingOut() {
		return tripPath != null && tripPath.turnRail >= 0 && vehicle.getNextStoppingIndex() == tripPath.turnRail;
	}

	/**
	 * @return whether the train runs empty, from where it turned back early to where it goes on with its duty or back to the
	 * siding (D4)
	 */
	public boolean isOutOfService() {
		if (tripPath == null || tripPath.turnRail < 0) {
			return false;
		}
		// Until it reaches where it goes on with its duty, which starts the next segment there
		int turnSegment = 0;
		for (int i = 0; tripPath.dutyStarts != null && i < tripPath.dutyStarts.length; i++) {
			if (tripPath.dutyStarts[i] <= tripPath.turnRail) {
				turnSegment = i;
			}
		}
		return vehicle.getNextStoppingIndex() > tripPath.turnRail && (tripPath.turnResumeRail < 0 || dutyIndex <= turnSegment);
	}

	/**
	 * At a stop of its duty, ready to go, a train told to turn back early takes its duty's path turned back at the stop it
	 * is told to, from here on and on this segment, if path generation found a way round to the other platform there
	 * (D4). Going on with its duty, it joins the next segment where it gets to, and waits there for that service; otherwise
	 * it goes back to the siding empty. The last stop of the segment doesn't count, as the duty turns back there anyway.
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseEarlyTurn() {
		// What a dispatcher ordered comes before the plans (D6); told to clear out, the train goes back empty after turning
		final boolean dispatched = !dispatchTurnPlatforms.isEmpty();
		final int mode = dispatched ? transitsim.plan.Contingency.TURN_STATION : turnMode;
		final Set<Long> platforms = dispatched ? dispatchTurnPlatforms : turnPlatforms;
		final boolean resume = (dispatched ? transitsim.plan.Contingency.AFTER_RESUME : turnAfter) != transitsim.plan.Contingency.AFTER_DEADHEAD && (dispatchFlags & DISPATCH_CLEAR_OUT) == 0;
		if (mode == 0 || !isOnDuty() || pathVariants == null || routes == null || tripPath.turnRail >= 0) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (stopIndex == turnChosenStop) {
			return false;
		}
		turnChosenStop = stopIndex;
		if (turnAt(mode, platforms, resume, -1, stopIndex)) {
			turnUnreachable = false;
			if (dispatched) {
				dispatchTurnPlatforms = Collections.emptySet();
				dispatchFlags &= ~DISPATCH_CLEAR_OUT;
			}
			return true;
		}
		turnUnreachable = mode == transitsim.plan.Contingency.TURN_STATION;
		return false;
	}

	/**
	 * Changes the train onto its duty's path turned back early (D4), at the first stop of this segment from here that has a
	 * way round, among these platforms for a station, or only on this rail.
	 *
	 * @param onlyRail the only rail of a stop it turns back at, or -1 for any
	 * @return true if it changed paths
	 */
	private boolean turnAt(int mode, Set<Long> platforms, boolean resume, int onlyRail, int stopIndex) {
		final String baseKey = TripPath.getSkipBase(tripPathKey);
		final TripPath<T> base = tripPaths.get(baseKey);
		if (base == null || base.dutyStarts == null || TripPath.isReturnKey(baseKey)) {
			return false;
		}
		final List<PlanRoute> dutyRoutes = new ArrayList<>();
		for (final long routeId : base.routeIds) {
			final PlanRoute route = routes.apply(routeId);
			if (route == null) {
				return false;
			}
			dutyRoutes.add(route);
		}
		final int segment = dutyIndex;
		if (segment >= dutyRoutes.size() || base.stopTimes.getSegmentEntry(segment) < 0) {
			return false;
		}
		if (resume && segment + 1 >= dutyRoutes.size()) {
			return false;
		}
		final long[][] times = base.stopTimes.getSegmentTimes(segment, dutyRoutes);
		int lastStop = -1;
		for (int i = 0; i < times.length; i++) {
			if (times[i][0] >= 0) {
				lastStop = i;
			}
		}
		final long[][] nextTimes = resume ? base.stopTimes.getSegmentTimes(segment + 1, dutyRoutes) : null;
		final PlanRoute route = dutyRoutes.get(segment);
		for (int i = 0; i < lastStop; i++) {
			final int rail = (int) times[i][2];
			if (times[i][0] < 0 || rail < stopIndex || onlyRail >= 0 && rail != onlyRail) {
				continue;
			}
			final long platformId = route.getStops().get(i).getPlatformId();
			if (mode == transitsim.plan.Contingency.TURN_STATION && !platforms.contains(platformId)) {
				continue;
			}
			final java.util.Collection<Long> targets = pathVariants.getTurnTargets(platformId);
			String key = null;
			if (resume) {
				// The first stop of the next segment, short of its last, the train can get round to from here
				final PlanRoute nextRoute = dutyRoutes.get(segment + 1);
				int nextLast = -1;
				for (int j = 0; j < nextTimes.length; j++) {
					if (nextTimes[j][0] >= 0) {
						nextLast = j;
					}
				}
				for (int j = 0; j < nextLast && key == null; j++) {
					if (nextTimes[j][0] >= 0 && targets.contains(nextRoute.getStops().get(j).getPlatformId())) {
						key = TripPath.getTurnKey(baseKey, segment, rail, i, nextTimes[j][2], j, true);
					}
				}
			} else {
				for (final long target : targets) {
					if (key == null && pathVariants.getTurnReturn(target) != null) {
						key = TripPath.getTurnKey(baseKey, segment, rail, i, target, 0, false);
					}
				}
			}
			final TripPath<T> newPath = key == null ? null : findPath(tripPaths, key);
			if (newPath != null) {
				tripPath = newPath;
				tripPathKey = newPath.key;
				stopTimes = newPath.stopTimes;
				vehicle.changePath(newPath, stopIndex);
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells the train which platforms to pass without stopping from its next stop on, or none to stop as planned again
	 * (D3). It changes over at a stop, never between two.
	 */
	public void setSkipPlatforms(Set<Long> platformIds) {
		final Set<Long> newSkips = platformIds == null || platformIds.isEmpty() ? Collections.emptySet() : new TreeSet<>(platformIds);
		if (!newSkips.equals(skipPlatforms)) {
			skipPlatforms = newSkips;
			skipChosenStop = -1;
		}
	}

	public Set<Long> getSkipPlatforms() {
		return Collections.unmodifiableSet(skipPlatforms);
	}

	/**
	 * @return whether the train is on a path passing some of its stops it would stop at (D3)
	 */
	public boolean isSkipping() {
		return TripPath.isSkipKey(tripPathKey);
	}

	public void setPathVariants(PathVariants<T> newPathVariants) {
		pathVariants = newPathVariants;
	}

	/**
	 * @return the path with this key among these, or one passing stops of one of them made from its key (D3), or null
	 */
	public TripPath<T> findPath(Map<String, TripPath<T>> paths, String key) {
		final TripPath<T> path = paths.get(key);
		if (path != null || pathVariants == null) {
			return path;
		}
		if (TripPath.isSkipKey(key)) {
			if (skipPath != null && skipPath.key.equals(key)) {
				return skipPath;
			}
			final TripPath<T> base = findPath(paths, TripPath.getSkipBase(key));
			if (base == null) {
				return null;
			}
			skipPath = TripPath.withSkips(base, TripPath.getSkipRails(key), pathVariants);
			return skipPath;
		}
		if (TripPath.isChangedKey(key)) {
			// A path turned back early (D4), or starting or ending part of the way through the duty (D5)
			if (turnPath != null && turnPath.key.equals(key)) {
				return turnPath;
			}
			final TripPath<T> base = paths.get(TripPath.getTurnBase(key));
			final TripPath<T> newPath = base == null ? null : TripPath.isTurnKey(key) ? TripPath.withTurn(base, key, pathVariants) : TripPath.isFromKey(key) ? TripPath.withFrom(base, key, pathVariants) : TripPath.withEnd(base, key, pathVariants);
			if (newPath != null) {
				turnPath = newPath;
			}
			return newPath;
		}
		return null;
	}

	/**
	 * At a stop of its duty, ready to go, the train takes the version of its path that passes the stops it is told to pass
	 * from here on, or its usual path again when it is told none (D3). The first and last stop of each segment of the duty
	 * are always stops, where it starts and ends its services. Stops it has already passed stay passed, so the path's key
	 * doesn't change at every stop.
	 *
	 * @return true if the train has just changed paths, so it waits a moment at the stop
	 */
	public boolean chooseSkip() {
		if (!isOnDuty() || pathVariants == null) {
			return false;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		if (stopIndex == skipChosenStop) {
			return false;
		}
		skipChosenStop = stopIndex;
		final String baseKey = TripPath.getSkipBase(tripPathKey);
		// A path turned back early (D4) isn't among the siding's
		final TripPath<T> base = findPath(tripPaths, baseKey);
		if (base == null) {
			return false;
		}
		final int lastStop = getLastDutyStop(base);
		final Set<Integer> kept = new HashSet<>();
		if (base.dutyStarts != null) {
			for (final int start : base.dutyStarts) {
				kept.add(start);
				// The last stop of the segment before, where the train turns back onto this one
				for (int i = start - 1; i >= 0; i--) {
					if (base.path.get(i).getDwellTime() > 0) {
						kept.add(i);
						break;
					}
				}
			}
		}
		final List<Integer> rails = new ArrayList<>();
		for (final int rail : TripPath.getSkipRails(tripPathKey)) {
			// Told to stop again, it takes its usual path at once
			if (rail <= stopIndex && !skipPlatforms.isEmpty()) {
				rails.add(rail);
			}
		}
		for (int i = stopIndex + 1; i < lastStop; i++) {
			final T track = base.path.get(i);
			if (track.getDwellTime() > 0 && track.isPlatformTrack() && skipPlatforms.contains(track.getStopId()) && !kept.contains(i)) {
				rails.add(i);
			}
		}
		final int[] railArray = rails.stream().mapToInt(Integer::intValue).toArray();
		final String newKey = TripPath.getSkipKey(baseKey, railArray);
		if (newKey.equals(tripPathKey)) {
			return false;
		}
		final TripPath<T> newPath = findPath(tripPaths, newKey);
		if (newPath == null) {
			return false;
		}
		tripPath = newPath;
		tripPathKey = newPath.key;
		stopTimes = newPath.stopTimes;
		vehicle.changePath(newPath, stopIndex);
		return true;
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
		// Standing at its last stop for the night, the train keeps no turn back rail: it reaches the end of the way it
		// chose only when it runs again, so the rail would be held all night and no other train could turn back there
		// (2026-11-14, the user's own server; the train turns back at the platform and never needed the rail)
		if (isStayingOnLine()) {
			vehicle.reserveTurnBack(java.util.Collections.emptyList(), 0);
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
			// What it is told to pass holds until the host tells it otherwise, on this duty or the next (D3)
			skipChosenStop = -1;
			skipPath = null;
			turnChosenStop = -1;
			turnUnreachable = false;
			turnPath = null;
			handOverChosenStop = -1;
			handOverFailure = 0;
			dispatchChosenStop = -1;
			holdUntilMillis = 0;
			extraDwellMillis = 0;
			dispatchFlags &= ~(DISPATCH_CLEAR_OUT | DISPATCH_RETURN);
			dispatchTurnPlatforms = Collections.emptySet();
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
			boolean started = false;
			while (dutyIndex + 1 < tripPath.dutyStarts.length && nextStoppingIndex >= tripPath.dutyStarts[dutyIndex + 1]) {
				startDutySegment(dutyIndex + 1);
				started = true;
			}
			if (started) {
				rehookIfLate();
			}
			expectLateIfSo();
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
		// A duty started part of the way through starts at that segment (D5)
		final int firstSegment = Math.min(TripPath.getFromSegment(tripPathKey), segments.size() - 1);
		final transitsim.plan.DutyLedger.Entry ledger = getDutyLedger();
		final int index = ledger == null ? firstSegment : ledger.segmentAt(dutyDayStart, currentMillis, firstSegment);
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
		final double from = tripPath.getStopDistance(fromStop, vehicle.getTrainLength()), to = tripPath.getStopDistance(stopIndex, vehicle.getTrainLength());
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
	 * Where the ways are all of one kind (all turn back rails, or all platforms), they take turns: the train tries first the
	 * way after the one the route's service before took, counting by where its service comes in the day, so consecutive
	 * trains use the rails alternately and the choice needs nothing kept (站后双折返, 2026-11-13).
	 * <p>
	 * Of the ways whose turn back rails are free, in that order, it takes the first with which the train leaves the first stop of
	 * the next route by the time its next service is planned to, and whose rails no other train has or holds; if none
	 * does, the one it can leave there soonest by. A train with no planned time for its next service takes the first free
	 * way.
	 *
	 * @return true while the train has to wait at the stop, as every way round is taken or it has only just changed paths
	 */
	public boolean chooseTurnBack() {
		// The other ways round of a path turned back early (D4) would take the train back onto its usual path, so it keeps
		// the usual way round
		if (tripPath != null && tripPath.turnRail >= 0) {
			return false;
		}
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
		// A train with a long wait for its next service waits on a free turn back rail rather than at a platform, leaving
		// the platforms to others (混合折返), where both are ways round; the planner and the web editor take the same way
		// (LONG_TURN_BACK_WAIT_MILLIS)
		boolean hasPlatformWay = false;
		boolean hasRailWay = false;
		final List<Integer> usableWays = new ArrayList<>();
		// Where the duty has the train wait after this segment, as planned (运用补完 step 1): only ways of that kind, waiting
		// here while they are taken; the runtime's own choice where nothing is planned, as for duties planned before
		final DepotDuties.DayDuty plannedDay = isOnDuty() ? getDutyDay() : null;
		final int plannedWait = plannedDay == null ? DepotDuties.DayDuty.WAIT_ANY : plannedDay.getWait(dutyIndex);
		for (int way = 0; way < turnBackPoint.getWayCount(); way++) {
			final boolean isDefaultWay = way == turnBackPoint.defaultWay;
			final TripPath<T> newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(TripPath.getTurnBackKey(tripKey, point, way));
			if (turnBackPoint.getRails(way) != null && (newPath != null || isDefaultWay && tripKey.isEmpty())) {
				usableWays.add(way);
				if (isRailWay(newPath, turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point), turnBackPoint.getLength(way))) {
					hasRailWay = true;
				} else {
					hasPlatformWay = true;
				}
			}
		}
		// Ways of one kind take turns with the services of the route the train is on, so consecutive trains turn back on
		// different rails (站后双折返, alternating, 2026-11-13); a way that is taken or late is passed over as before
		final int[] wayOrder = getWayOrder(turnBackPoint.getWayCount(), usableWays, hasPlatformWay && hasRailWay ? 0 : getServiceOrdinal());
		for (int wayIndex = 0; hasPlatformWay && plannedWait != DepotDuties.DayDuty.WAIT_PLATFORM && wayIndex < wayOrder.length; wayIndex++) {
			final int way = wayOrder[wayIndex];
			final List<UUID> rails = turnBackPoint.getRails(way);
			final boolean isDefaultWay = way == turnBackPoint.defaultWay;
			final TripPath<T> newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(TripPath.getTurnBackKey(tripKey, point, way));
			if (rails == null || newPath == null && !(isDefaultWay && tripKey.isEmpty()) || !vehicle.isTurnBackFree(rails)) {
				continue;
			}
			final int newStopIndex = turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point);
			if (!isRailWay(newPath, newStopIndex, turnBackPoint.getLength(way))) {
				continue;
			}
			final long[] times = getWayTimes(newPath, newStopIndex, newStopIndex + turnBackPoint.getLength(way), nowMillis);
			final List<UUID> wayRails = turnBackPoint.getWayRails(way);
			// How long the wait is, from when the train could be ready to its next duty segment's service
			final long nextMillis = getNextSegmentServiceMillis();
			if (times != null && nextMillis > 0 && (wayRails == null || vehicle.isWayClear(wayRails)) && times[0] <= nextMillis && (plannedWait == DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL || nextMillis - times[0] >= LONG_TURN_BACK_WAIT_MILLIS)) {
				chosenWay = way;
				chosenPath = newPath;
				chosenStopIndex = newStopIndex;
				break;
			}
		}
		final boolean railChosen = chosenWay >= 0;
		for (int wayIndex = 0; !railChosen && wayIndex < wayOrder.length; wayIndex++) {
			final int way = wayOrder[wayIndex];
			final List<UUID> rails = turnBackPoint.getRails(way);
			final boolean isDefaultWay = way == turnBackPoint.defaultWay;
			final TripPath<T> newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(TripPath.getTurnBackKey(tripKey, point, way));
			if (rails == null || newPath == null && !(isDefaultWay && tripKey.isEmpty()) || !vehicle.isTurnBackFree(rails)) {
				continue;
			}
			final int newStopIndex = turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point);
			if (hasPlatformWay && plannedWait != DepotDuties.DayDuty.WAIT_ANY && plannedWait != DepotDuties.DayDuty.WAIT_CHANGE_PLATFORM && isRailWay(newPath, newStopIndex, turnBackPoint.getLength(way)) != (plannedWait == DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL)) {
				continue;
			}
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
		// -Dmtr.debugTurnBack=true: each choice, with every way and whether it was free (2026-11-14, line 6 of the test
		// lines never took its turn back rail in the game while the component's own runner did)
		if (DEBUG_TURN_BACK) {
			final StringBuilder ways = new StringBuilder();
			for (int way = 0; way < turnBackPoint.getWayCount(); way++) {
				final List<UUID> rails = turnBackPoint.getRails(way);
				final boolean isDefaultWay = way == turnBackPoint.defaultWay;
				final TripPath<T> newPath = isDefaultWay ? tripKey.isEmpty() ? null : tripPaths.get(tripKey) : tripPaths.get(TripPath.getTurnBackKey(tripKey, point, way));
				final List<UUID> wayRails = turnBackPoint.getWayRails(way);
				ways.append(" [way ").append(way).append(isDefaultWay ? " default" : "").append(rails == null ? " no rails" : " rails " + rails.size()).append(newPath == null ? " no path" : "")
						.append(rails != null && !vehicle.isTurnBackFree(rails) ? " TAKEN" : "").append(wayRails != null && !vehicle.isWayClear(wayRails) ? " WAY HELD" : "")
						.append(isRailWay(newPath, turnBacks.getStop(newPath == null ? null : newPath.turnBackStops, point), turnBackPoint.getLength(way)) ? " rail" : " platform").append("]");
			}
			System.out.println("Turn back: train " + trainId + " at stop " + stopIndex + " point " + point + " planned wait " + plannedWait + " chose " + chosenWay + (chosenWay == turnBackPoint.defaultWay ? " (default)" : "") + ";" + ways + " trip " + tripKey);
		}
		if (chosenWay < 0) {
			return true;
		}
		turnBackStop = chosenStopIndex;
		vehicle.reserveTurnBack(turnBackPoint.getRails(chosenWay), chosenStopIndex + turnBackPoint.getLength(chosenWay));
		if (chosenPath == tripPath || chosenPath != null && tripPath != null && chosenPath.key.equals(TripPath.getSkipBase(tripPath.key))) {
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
	 * @return every way round a turn back point in the order to try them: the usable ones starting with the one whose turn
	 * it is, then the rest in order
	 */
	static int[] getWayOrder(int wayCount, List<Integer> usableWays, int turn) {
		final int[] order = new int[wayCount];
		int index = 0;
		final int first = usableWays.isEmpty() ? 0 : Math.floorMod(turn, usableWays.size());
		for (int i = 0; i < usableWays.size(); i++) {
			order[index++] = usableWays.get((first + i) % usableWays.size());
		}
		for (int way = 0; way < wayCount; way++) {
			if (!usableWays.contains(way)) {
				order[index++] = way;
			}
		}
		return order;
	}

	/**
	 * @return where the service the train is on comes in its route's day, or 0 for a train with no service
	 */
	private int getServiceOrdinal() {
		if (tripMillis == 0 || routes == null) {
			return 0;
		}
		final PlanRoute route = routes.apply(tripRouteId);
		return route == null ? 0 : route.getTimetable().getServiceOrdinal(tripMillis);
	}

	/**
	 * @return on a duty, when its next segment's service is due, or 0
	 */
	private long getNextSegmentServiceMillis() {
		if (!isOnDuty()) {
			return 0;
		}
		final List<DepotDuties.Segment> segments = getDutySegments();
		return dutyIndex + 1 < segments.size() ? DutyShapes.getServiceMillis(dutyDayStart, segments.get(dutyIndex + 1).service) : 0;
	}

	/**
	 * @return whether this way round turns back on a turn back rail where the train stops, rather than at a platform
	 */
	private boolean isRailWay(TripPath<T> wayPath, int stopIndex, int length) {
		final List<T> path = wayPath == null ? vehicle.getPath() : wayPath.path;
		for (int i = stopIndex + 1; i < Math.min(stopIndex + length, path.size()); i++) {
			if (path.get(i).getStopId() == 0 && path.get(i).getDwellTime() > 0) {
				return true;
			}
		}
		return false;
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
		deployDuty(slot, newDutySidingId, dayStart, dutyPath, 0);
	}

	/**
	 * @param startSegment the segment it starts the duty at, out of the siding: a spare taking the rest of a duty over, or a
	 *                     train sent out late (D5); its path is the duty's from that segment ({@link TripPath#withFrom})
	 */
	public void deployDuty(int slot, long newDutySidingId, long dayStart, TripPath<T> dutyPath, int startSegment) {
		dutySlot = slot;
		dutySidingId = newDutySidingId == sidingId ? 0 : newDutySidingId;
		dutyId = currentDutyId();
		returnChosenStop = -1;
		dutyDayStart = dayStart;
		dutyDay = readDutyDay();
		isLeavingForDuty = true;
		isJoiningLoop = false;
		linkedService = -1;
		linkedRouteId = 0;
		useTripPath(dutyPath);
		startDutySegment(startSegment);
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
	 * On a duty, at a turn back rail just before the first stop of its next segment: when to leave the rail to reach that
	 * stop as its service is due, so the train waits on the rail rather than at the platform, which other trains can use
	 * meanwhile (混合折返). 0 anywhere else.
	 */
	public long getTurnBackLeaveMillis() {
		if (!isOnDuty() || tripPath == null || stopTimes == null) {
			return 0;
		}
		final int stopIndex = vehicle.getNextStoppingIndex();
		final List<T> path = vehicle.getPath();
		final int next = dutyIndex + 1;
		if (stopIndex >= path.size() || path.get(stopIndex).getStopId() != 0 || next >= tripPath.dutyStarts.length || tripPath.dutyStarts[next] <= stopIndex) {
			return 0;
		}
		// Before the next segment's first stop, so not the siding at either end of the path
		final int start = tripPath.dutyStarts[next];
		for (int i = stopIndex + 1; i < start; i++) {
			if (path.get(i).getDwellTime() > 0) {
				return 0;
			}
		}
		final List<DepotDuties.Segment> segments = getDutySegments();
		final long runMillis = stopTimes.getRunMillis(stopIndex, start);
		if (next >= segments.size() || runMillis < 0) {
			return 0;
		}
		return DutyShapes.getServiceMillis(dutyDayStart, segments.get(next).service) - runMillis;
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
	/**
	 * A train waiting this long or more at its last stop for its next service waits on a turn back rail instead, if there is
	 * a free one, so the platform is free for others meanwhile (混合折返).
	 */
	public static final long LONG_TURN_BACK_WAIT_MILLIS = 5 * 60 * 1000;

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
		// The next duty by its number, which may be of another siding of the depot (P2-D)
		final long[] nextDuty = day == null || day.nextDutyId == 0 ? null : depot.findDuty(day.nextDutyId);
		final long slotSidingId = nextDuty != null ? nextDuty[0] : getDutySidingId();
		final List<DepotDuties.Slot> slots = depot.getDutySlots(slotSidingId);
		final int nextSlot = day == null || day.nextDutyId != 0 && nextDuty == null ? -1 : nextDuty != null ? (int) nextDuty[1] : DutyShapes.getNextSlot(day, dutySlot);
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
		dutySidingId = slotSidingId == sidingId ? 0 : slotSidingId;
		dutySlot = nextSlot;
		dutyId = currentDutyId();
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
			// A service taken over late stays taken, also when the train is lined up with its duty again after a restart
			if (index == rehookSegment && dutyDayStart == rehookDayStart) {
				tripMillis = rehookMillis;
			}
		}
	}

	/**
	 * At the first stop of a duty segment, a train more than {@link #LATE_REHOOK_MILLIS} late for its service, or whose
	 * service another train took over, takes the earliest later service of the same route from this stop that no train
	 * runs on time and it can still keep to, and waits here for it; its own service is then given up and doesn't run
	 * (user 2026-11-14). With none, it runs its own late. It can't skip track, so it only changes which service it runs;
	 * the later segments keep theirs. Only here, not when the train is lined up or put on its duty (handover, 2026-11-14).
	 */
	private void rehookIfLate() {
		final List<DepotDuties.Segment> segments = getDutySegments();
		if (depot == null || dutyIndex <= 0 || dutyIndex >= segments.size() || dutyIndex == rehookSegment && dutyDayStart == rehookDayStart) {
			return;
		}
		depot.expectLate(trainId, 0, 0, 0);
		final DepotDuties.Segment segment = segments.get(dutyIndex);
		final RouteTimetable.StopPlan stopPlan = getStopPlan(tripPath.dutyStarts[dutyIndex]);
		if (stopPlan == null || tripMillis == 0) {
			return;
		}
		final long currentMillis = clock.getAsLong();
		// When the train would leave here on a service: the service, then as far into it as this stop's departure is
		final long offset = stopPlan.departureMillis - routeStartMillis;
		final long own = tripMillis;
		final boolean taken = depot.isServiceTaken(segment.routeId, segment.fromStop, own);
		if (!taken && own + offset + LATE_REHOOK_MILLIS >= currentMillis) {
			return;
		}
		final long found = depot.takeFreeService(segment.routeId, segment.fromStop, own, currentMillis - offset, currentMillis - offset + REHOOK_WAIT_MILLIS, currentMillis);
		if (found == 0) {
			return;
		}
		if (!taken) {
			depot.giveUpService(segment.routeId, segment.fromStop, own);
		}
		tripMillis = found;
		rehookSegment = dutyIndex;
		rehookMillis = found;
		rehookDayStart = dutyDayStart;
	}

	/**
	 * At a stop on its duty, a train more than {@link #LATE_REHOOK_MILLIS} late tells the depot it isn't expected to run its
	 * next segment's service on time, which a train ahead of it may then take over; on time again, it takes that back.
	 */
	private void expectLateIfSo() {
		final List<DepotDuties.Segment> segments = getDutySegments();
		if (depot == null || tripPath == null) {
			return;
		}
		final int next = dutyIndex + 1;
		if (next >= segments.size() || next >= tripPath.dutyStarts.length || getLateMillis() <= LATE_REHOOK_MILLIS) {
			depot.expectLate(trainId, 0, 0, 0);
			return;
		}
		final DepotDuties.Segment segment = segments.get(next);
		final long serviceMillis = next == rehookSegment && dutyDayStart == rehookDayStart ? rehookMillis : DutyShapes.getServiceMillis(dutyDayStart, segment.service);
		if (!depot.isServiceTaken(segment.routeId, segment.fromStop, serviceMillis)) {
			depot.expectLate(trainId, segment.routeId, segment.fromStop, serviceMillis);
		}
	}

	/**
	 * @return the duty the train took on, kept with it when saved, or for a train saved before that the one of its slot
	 * read again from the depot
	 */
	public transitsim.plan.DutyLedger.Entry getDutyLedger() {
		if (dutySlot < 0) { dutyLedger = null; return null; }
		final DepotDuties.DayDuty day = getDutyDay();
		if (day == null) return null;
		if (dutyLedger == null || dutyLedger.day() != day) {
			dutyLedger = transitsim.plan.DutyLedger.intern(day);
			dutyDay = dutyLedger == null ? null : dutyLedger.day();
		}
		return dutyLedger;
	}

	private DepotDuties.DayDuty getDutyDay() {
		if (dutyDay == null) {
			dutyDay = readDutyDay();
		}
		return dutyDay;
	}

	/**
	 * @return a copy of the duty of the train's slot on its day, as the depot has it now
	 */
	/**
	 * @return the number of the duty in the slot the train runs, or 0
	 */
	private long currentDutyId() {
		if (depot == null || dutySlot < 0) {
			return 0;
		}
		final List<DepotDuties.Slot> slots = depot.getDutySlots(getDutySidingId());
		return dutySlot < slots.size() ? slots.get(dutySlot).id : 0;
	}

	private DepotDuties.DayDuty readDutyDay() {
		if (depot == null || dutySlot < 0) {
			return null;
		}
		// The duty may have been moved to another siding or place since: found again by its number (P2-D)
		final long[] found = dutyId == 0 ? null : depot.findDuty(dutyId);
		if (found != null) {
			dutySidingId = found[0] == sidingId ? 0 : found[0];
			dutySlot = (int) found[1];
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
		// Turned back early to go back empty (D4), it goes back to its own siding
		if (depot == null || !isOnDuty() || tripPath.staysOnLine || TripPath.isReturnKey(tripPathKey) || tripPath.turnRail >= 0 && tripPath.turnResumeRail < 0) {
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
		// The same for a path turned back early (D4), whose rails after the turn are numbered differently
		final boolean otherWayRound = tripPath.turnBackPoint >= 0 || tripPath.turnRail >= 0 || TripPath.isFromKey(tripPathKey);
		// Only a siding the duty has a way back to is chosen, rather than one it can't reach and then its own after all
		final String returnFrom = tripPath.turnBackPoint >= 0 ? tripPath.tripKey : TripPath.getTurnBase(tripPathKey);
		// Its own siding only when the duty's path goes back there: a duty whose last stop has no way back to it goes back to
		// another siding of the pool (2026-11-13, the six-line test's front turn-back lines, where the siding trains leave
		// from is not the one trains reach from the last stop)
		final long endSidingId = getEndSiding(tripPaths.get(returnFrom));
		final boolean ownReachable = endSidingId == 0 || endSidingId == sidingId;
		final java.util.function.LongPredicate canGoBack = otherSidingId -> otherSidingId == sidingId ? ownReachable : tripPaths.get(TripPath.getReturnKey(returnFrom, otherSidingId)) != null;
		// Set for the duty (A 出 B 入, P2-D): there if there is a way, otherwise told and as the pool would send it
		final long setSidingId = dutyDay == null ? 0 : dutyDay.returnSidingId;
		final boolean setReachable = setSidingId != 0 && canGoBack.test(setSidingId);
		if (setSidingId != 0 && !setReachable) {
			depot.noteReturnMissed(sidingId, setSidingId, "no way back to the siding set for the duty");
		}
		final long chosenSidingId = setReachable ? setSidingId : depot.chooseReturnSiding(sidingId, orderDepotId, canGoBack);
		// Its own chosen with no way back there: where the duty's path does go back to
		final long returnSidingId = chosenSidingId == sidingId && !ownReachable ? endSidingId : chosenSidingId;
		final TripPath<T> returnPath = returnSidingId == sidingId ? null : tripPaths.get(TripPath.getReturnKey(returnFrom, returnSidingId));
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
	 * @return the siding a duty's path ends in, or 0 if not known
	 */
	private static <T extends PlanTrack> long getEndSiding(TripPath<T> dutyPath) {
		if (dutyPath == null || dutyPath.path.isEmpty()) {
			return 0;
		}
		final T last = dutyPath.path.get(dutyPath.path.size() - 1);
		return last.isSidingTrack() ? last.getStopId() : 0;
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

	/**
	 * @return the path the train is on when it isn't the usual path of the depot, such as a duty's, or null
	 */
	public TripPath<T> getTripPath() {
		return tripPath;
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
		// A path passing stops is made again from its key (D3)
		final TripPath<T> newTripPath = findPath(newTripPaths, tripPathKey);
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

	/**
	 * For a copy of another service, read from what that one wrote (the game showing the simulator's train): takes the
	 * path its key names, which reading leaves out as it is an object of the copy's own siding (0.66.1). Without it the
	 * copy had no path and so no duty, and a train on a duty showed as on none, its planned departures unknown. The
	 * train's own path is the host's to change, so it isn't changed here.
	 *
	 * @param newTripPath the path {@link #getTripPathKey} names, or null for none
	 */
	/**
	 * Lets go of a path that is gone, for a train standing in its siding (0.66.2): it needs none there, and keeping the key
	 * of one its siding no longer has made the simulator give the train up, then the whole simulator start again every
	 * half minute trying to carry it on.
	 */
	public void forgetTripPath() {
		turnBackStop = -1;
		tripPath = null;
		tripPathKey = "";
		linkedService = -1;
		linkedRouteId = 0;
		tripRoutes.clear();
		stopTimes = depotStopTimes;
	}

	public void takeTripPath(TripPath<T> newTripPath) {
		tripPath = newTripPath;
		stopTimes = newTripPath == null ? depotStopTimes : newTripPath.stopTimes;
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
		// A refreshed route baseline changes cumulative offsets along a whole-day duty. Re-anchor
		// the current service to its own segment rather than retaining an offset from the old save.
		if (dutyRoute != null && dutyRoute.getTimingBaseline() != null && tripPath != null && tripPath.dutyStarts != null && newDutyIndex >= 0 && newDutyIndex < tripPath.dutyStarts.length) {
			final RouteTimetable.StopPlan start = stopTimes.getPlan(planRoutes, planTimetable, tripPath.dutyStarts[newDutyIndex], tripRouteId, getTripTable(planRoutes), (int) Math.floorMod(tripMillis, (long) OperatingDay.MILLIS_PER_DAY));
			if (start != null) routeStartMillis = start.routeStartMillis;
		}
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

	/**
	 * @return how late the train is: past the planned departure from its next stop, or the one it stands at, or 0 (D2)
	 */
	public long getLateMillis() {
		final long since = getLateSinceMillis();
		return since == 0 ? 0 : Math.max(0, clock.getAsLong() - since);
	}

	/**
	 * @return the planned departure from its next stop, or the one it stands at, if that has passed: the moment the train
	 * has been late since, which stays the same while it is late there, unlike how late it is; or 0
	 */
	public long getLateSinceMillis() {
		if (tripMillis == 0) {
			return 0;
		}
		final RouteTimetable.StopPlan stopPlan = getPlanAt(vehicle.getNextStoppingIndex());
		if (stopPlan == null) {
			return 0;
		}
		final long departureMillis = getPlannedMillis(stopPlan.departureMillis);
		return departureMillis < clock.getAsLong() ? departureMillis : 0;
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

		/**
		 * @param remaining the rails the train still runs before it hands its duty over
		 * @return those rails, with the rails held by trains holding any of them, where the train is held up (D5)
		 */
		default Collection<java.util.UUID> getAffectedRails(List<java.util.UUID> remaining) {
			return remaining;
		}

		List<T> getPath();

		/**
		 * @return the length of the train, which platforms' stop positions depend on (round 4)
		 */
		default double getTrainLength() {
			return 0;
		}

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
		 * A late train gave up this service of a duty segment starting at this stop, having taken another one.
		 */
		default void giveUpService(long routeId, int fromStop, long serviceMillis) {
		}

		/**
		 * @param serviceMillis the service a late train isn't expected to run on time, or 0 when it is again
		 */
		default void expectLate(String trainId, long routeId, int fromStop, long serviceMillis) {
		}

		/**
		 * @return whether another train took this service over
		 */
		default boolean isServiceTaken(long routeId, int fromStop, long serviceMillis) {
			return false;
		}

		/**
		 * @return the earliest service of this route starting at this stop that no train runs on time, due after
		 * {@code afterMillis} and from {@code fromMillis} until {@code untilMillis}, which is then taken, or 0
		 * @see LateServices
		 */
		default long takeFreeService(long routeId, int fromStop, long afterMillis, long fromMillis, long untilMillis, long currentMillis) {
			return 0;
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

		/**
		 * @return the siding and the index of the slot with this duty number, or null if there is none (P2-D)
		 */
		default long[] findDuty(long dutyId) {
			return null;
		}

		/**
		 * A duty set to go back to a siding whose train couldn't, told rather than silently going back elsewhere.
		 */
		default void noteReturnMissed(long sidingId, long targetSidingId, String reason) {
		}

		/**
		 * Asks the depot for a spare train to take a duty over from a segment on (D5), which it then sends out in time.
		 *
		 * @param fromKey       the key of the duty's path starting at that segment
		 * @param serviceMillis when that segment's service is due
		 * @param affected      rails the spare's way out mustn't run along
		 * @return {@link TrainService#TAKEOVER_OK} if a spare is on its way, else why not
		 */
		default int requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<java.util.UUID> affected) {
			return TAKEOVER_NO_SPARE;
		}
	}
}
