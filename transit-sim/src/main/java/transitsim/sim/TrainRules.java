package transitsim.sim;

import transitsim.motion.MotionConstants;
import transitsim.motion.MotionState;
import transitsim.motion.MotionTrack;
import transitsim.motion.SpeedCurves;
import transitsim.plan.RouteTimetable;

/**
 * The rules a train running by itself follows at its stops: how long it stops, when it may leave, when the doors open and
 * how fast it cruises to keep time (S4a). The game's server trains and the component's simulators use the same ones.
 * Moving the train is {@link transitsim.motion.TrainMotion}; which service it runs is {@link TrainService}.
 */
public final class TrainRules {

	/**
	 * The shortest stop of a late train catching up, in ticks, which is what the doors need.
	 */
	public static final int MIN_DWELL_TICKS = 17 * 10;
	/**
	 * A train closes its doors this long before its planned departure, so it leaves on time.
	 */
	public static final int DOOR_CLOSE_MILLIS = (MotionConstants.DOOR_MOVE_TIME + MotionConstants.DOOR_DELAY) * MotionConstants.MILLIS_PER_TICK;

	private TrainRules() {
	}

	/**
	 * A late train shortens its stop to catch up, without going below the time doors need.
	 *
	 * @param dwellTicks the stop time of the path in ticks
	 */
	public static int getTotalDwellTicks(Train train, int dwellTicks) {
		final MotionState state = train.getState();
		final RouteTimetable.StopPlan stopPlan = state.isCurrentlyManual() ? null : train.getService().getPlanAt(state.getNextStoppingIndex());
		if (stopPlan == null) {
			return dwellTicks;
		} else {
			return getTotalDwellTicks(dwellTicks, state.getElapsedDwellTicks(), train.getService().getPlannedMillis(stopPlan.departureMillis), train.getMillis());
		}
	}

	/**
	 * {@link #getTotalDwellTicks(Train, int)} for a stop with a planned departure, from what it goes by.
	 */
	public static int getTotalDwellTicks(int dwellTicks, float elapsedDwellTicks, long departureMillis, long currentMillis) {
		final long ticksAtStop = (long) elapsedDwellTicks + (departureMillis - currentMillis) / MotionConstants.MILLIS_PER_TICK;
		return (int) Math.max(Math.min(dwellTicks, ticksAtStop), Math.min(dwellTicks, MIN_DWELL_TICKS));
	}

	/**
	 * @return true while a train at a timed stop waits for its planned departure
	 */
	public static boolean isWaitingForDeparture(long departureMillis, long currentMillis) {
		return currentMillis < departureMillis - DOOR_CLOSE_MILLIS;
	}

	/** Clock/dispatch holds are checked before reserving track beyond a platform. */
	public static boolean waitsBeforeRequestingRun(Train train, int stop) {
		final TrainService<?> service = train.getService();
		if (service.isDispatchHeld() || service.isStayingOnLine()) return true;
		final long now = train.getMillis();
		if (service.getDutyDepartureMillis() > 0 && isWaitingForDeparture(service.getDutyDepartureMillis(), now)) return true;
		if (service.getTurnBackLeaveMillis() > now) return true;
		final RouteTimetable.StopPlan plan = service.getPlanAt(stop);
		return plan != null && plan.isTimed && isWaitingForDeparture(service.getPlannedMillis(plan.departureMillis), now);
	}

	/**
	 * @return true while a stopped train keeps its doors open and doesn't leave
	 */
	/**
	 * How long until the doors of a train standing at a stop start to close, for the clients' door sounds and animations
	 * (0.65.30): they otherwise count from the stop time of the platform's rail, which a timetable's later departure
	 * makes too short, so a closing chime sounded while the doors stayed open.
	 *
	 * @return ticks until the doors start to close, or -1 if the doors aren't open or the train is held for something
	 * whose end isn't known (a dispatcher, an overtaking train, the run ahead not granted yet)
	 */
	public static int getTicksUntilDoorsClose(Train train) {
		final MotionState state = train.getState();
		if (state.isCurrentlyManual() || !state.getDoorTarget() || train.isContinuousMovement()) {
			return -1;
		}
		final TrainService<?> service = train.getService();
		if (service.isDispatchHeld() || service.isStayingOnLine()) {
			return -1;
		}
		final long currentMillis = train.getMillis();
		long holdUntil = 0;
		final long dutyDepartureMillis = service.getDutyDepartureMillis();
		if (dutyDepartureMillis > 0) {
			holdUntil = dutyDepartureMillis - DOOR_CLOSE_MILLIS;
		}
		final int nextStoppingIndex = state.getNextStoppingIndex();
		final RouteTimetable.StopPlan stopPlan = service.getPlanAt(nextStoppingIndex);
		if (stopPlan != null && stopPlan.isTimed) {
			final long departureMillis = service.getPlannedMillis(stopPlan.departureMillis);
			if (stopPlan.overtake && currentMillis < departureMillis + RouteTimetable.MAX_OVERTAKE_WAIT_MILLIS && train.isOvertakingTrainComing(train.getStopId(nextStoppingIndex), service.getPlannedMillis(stopPlan.arrivalMillis), departureMillis, currentMillis)) {
				return -1;
			}
			holdUntil = Math.max(holdUntil, departureMillis - DOOR_CLOSE_MILLIS);
		}
		if (service.isOnDuty() && !train.isRunGranted(nextStoppingIndex + 1) && currentMillis >= holdUntil) {
			return -1;
		}
		return getTicksUntilDoorsClose(train.getTotalDwellTicks(), state.getElapsedDwellTicks(), holdUntil, currentMillis);
	}

	/**
	 * {@link #getTicksUntilDoorsClose(Train)} from what it goes by: the doors close once the stop time less the time they
	 * take to close is reached ({@link #areDoorsOpen}), which a hold until a known time puts off until then.
	 *
	 * @param holdUntilMillis until when the train is held with its doors open, or 0
	 */
	public static int getTicksUntilDoorsClose(int totalDwellTicks, float elapsedDwellTicks, long holdUntilMillis, long currentMillis) {
		final float closeAt = totalDwellTicks - MotionConstants.DOOR_DELAY - Math.min(MotionConstants.DOOR_MOVE_TIME, totalDwellTicks / 2 - MotionConstants.DOOR_DELAY);
		final int toClose = (int) Math.max(0, Math.ceil(closeAt - elapsedDwellTicks));
		final int held = holdUntilMillis > currentMillis ? (int) ((holdUntilMillis - currentMillis + MotionConstants.MILLIS_PER_TICK - 1) / MotionConstants.MILLIS_PER_TICK) : 0;
		return toClose + held;
	}

	/**
	 * Why a train standing at a stop is held there, for the debug display (0.65.31), checked the way
	 * {@link #isHeldAtStop} does but choosing nothing: the choices it makes there change the train.
	 *
	 * @return "dispatch", "staying", "duty_departure", "timetable", "overtake", "turn_back", "run" (the run ahead not
	 * granted yet), or "" for none of these
	 */
	public static String describeHold(Train train) {
		final MotionState state = train.getState();
		if (state.isCurrentlyManual()) {
			return "";
		}
		final TrainService<?> service = train.getService();
		if (service.isDispatchHeld()) {
			return "dispatch";
		}
		if (service.isStayingOnLine()) {
			return "staying";
		}
		final long currentMillis = train.getMillis();
		final long dutyDepartureMillis = service.getDutyDepartureMillis();
		if (dutyDepartureMillis > 0 && currentMillis < dutyDepartureMillis - DOOR_CLOSE_MILLIS) {
			return "duty_departure";
		}
		final long turnBackLeaveMillis = service.getTurnBackLeaveMillis();
		if (turnBackLeaveMillis > 0 && currentMillis < turnBackLeaveMillis) {
			return "turn_back";
		}
		final int nextStoppingIndex = state.getNextStoppingIndex();
		final RouteTimetable.StopPlan stopPlan = service.getPlanAt(nextStoppingIndex);
		if (stopPlan != null && stopPlan.isTimed) {
			final long departureMillis = service.getPlannedMillis(stopPlan.departureMillis);
			if (isWaitingForDeparture(departureMillis, currentMillis)) {
				return "timetable";
			}
			if (stopPlan.overtake && currentMillis < departureMillis + RouteTimetable.MAX_OVERTAKE_WAIT_MILLIS && train.isOvertakingTrainComing(train.getStopId(nextStoppingIndex), service.getPlannedMillis(stopPlan.arrivalMillis), departureMillis, currentMillis)) {
				return "overtake";
			}
		}
		return service.isOnDuty() && !train.isRunGranted(nextStoppingIndex + 1) ? "run" : "";
	}

	public static boolean isHeldAtStop(Train train) {
		final MotionState state = train.getState();
		if (state.isCurrentlyManual()) {
			return false;
		}
		final TrainService<?> service = train.getService();
		// Held or standing longer by a dispatcher (D6)
		if (service.isDispatchHeld()) {
			return true;
		}
		// A train on a duty stays overnight at its last stop, waits for each segment's planned departure, and doesn't leave
		// onto rails another train of a duty has booked the other way
		if (service.isStayingOnLine()) {
			return true;
		}
		final long currentMillis = train.getMillis();
		final long dutyDepartureMillis = service.getDutyDepartureMillis();
		if (dutyDepartureMillis > 0 && currentMillis < dutyDepartureMillis - DOOR_CLOSE_MILLIS) {
			return true;
		}
		// On a turn back rail before its next service, it waits there until it's time to run to the platform
		final long turnBackLeaveMillis = service.getTurnBackLeaveMillis();
		if (turnBackLeaveMillis > 0 && currentMillis < turnBackLeaveMillis) {
			return true;
		}
		final int nextStoppingIndex = state.getNextStoppingIndex();
		final RouteTimetable.StopPlan stopPlan = service.getPlanAt(nextStoppingIndex);
		if (stopPlan != null && stopPlan.isTimed) {
			final long departureMillis = service.getPlannedMillis(stopPlan.departureMillis);
			if (isWaitingForDeparture(departureMillis, currentMillis) || stopPlan.overtake && currentMillis < departureMillis + RouteTimetable.MAX_OVERTAKE_WAIT_MILLIS && train.isOvertakingTrainComing(train.getStopId(nextStoppingIndex), service.getPlannedMillis(stopPlan.arrivalMillis), departureMillis, currentMillis)) {
				return true;
			}
		}
		// The turn back rail is taken once the train is ready to go, so it isn't kept from other trains while this one waits
		// Passing stops a plan or a dispatcher has it pass (D3) is chosen last, on the path the other choices left it on
		if (!train.isContinuousMovement() && (service.chooseDepotReturn() || service.chooseDutyReturn() || service.chooseTurnBack() || service.chooseDispatch() || service.chooseHandOver() || service.chooseEarlyTurn() || service.chooseSkip())) {
			return true;
		}
		// The run is asked for last, on the path the train leaves by, so it isn't kept from other trains while this one waits
		return service.isOnDuty() && !train.isRunGranted(nextStoppingIndex + 1);
	}

	/**
	 * How early a train may reach its next stop running at full speed before it cruises slower (早到阈值, optimisation
	 * round 4, step 5), unless the network sets another: 30 seconds.
	 */
	public static final long DEFAULT_EARLY_THRESHOLD_MILLIS = 30000;

	/**
	 * With time to spare before the planned arrival at the next stop, the train cruises slower, but only by as much as it
	 * would be more than the early threshold early ({@link Train#getEarlyThresholdMillis}): a train up to that early runs
	 * at full speed and spends the time it has in hand standing at the stop, which leaves it room for a delay ahead; one
	 * earlier still cruises to arrive that much early rather than on time (round 4, step 5). With a threshold of 0 it
	 * arrives on time, as before. A late train gets no cap and runs as fast as it can to catch up (design document 8.2). A
	 * train setting off again after being held back on the way gets a cap from where it is (review A04); with the old
	 * motion rules it got none, being late already.
	 */
	public static float getCruiseCap(Train train) {
		return getCruiseCapOf(train);
	}

	/**
	 * @return the ticks a train has to reach its next stop in, by the rule of {@link #getCruiseCap}: until the planned
	 * arrival less the early threshold; a run that takes no longer at full speed is run at full speed
	 */
	public static double getTargetTicks(long plannedMillis, long nowMillis, long earlyThresholdMillis) {
		return (plannedMillis - Math.max(0, earlyThresholdMillis) - nowMillis) / (double) MotionConstants.MILLIS_PER_TICK;
	}

	private static float getCruiseCapOf(Train train) {
		final MotionState state = train.getState();
		final int nextStoppingIndex = state.getNextStoppingIndex();
		if (!train.usesSpeedCurve() || nextStoppingIndex >= train.getTrack().size()) {
			return Float.POSITIVE_INFINITY;
		}
		final long plannedMillis = train.getService().getPlannedArrivalMillis(nextStoppingIndex);
		final SpeedCurves curves = train.getSpeedCurves();
		final double railProgress = state.getRailProgress();
		final double ticks = getTargetTicks(plannedMillis, train.getMillis(), train.getEarlyThresholdMillis());
		return plannedMillis <= 0 ? Float.POSITIVE_INFINITY : (float) curves.findCruiseCap(railProgress, state.getSpeed(), nextStoppingIndex, ticks);
	}

	/**
	 * Whether the doors of a train running by itself are open now. A driver opens them, so a manual train isn't asked.
	 */
	public static boolean openDoors(Train train) {
		final MotionState state = train.getState();
		final MotionTrack track = train.getTrack();
		final double railProgress = state.getRailProgress();
		if (train.isContinuousMovement()) {
			final int index = MotionConstants.findIndex(track, railProgress, false);
			if (track.getDwellTime(index) > 0 && index > 0) {
				final double doorValue1 = (railProgress - track.getDistance(index - 1)) * 0.5;
				final double doorValue2 = (track.getDistance(index) - railProgress) * 0.5;
				return doorValue1 > 0 && (doorValue2 > doorValue1 || doorValue2 > 1);
			} else {
				return false;
			}
		} else {
			// Turn backs have no platform, so a long wait there must not open the doors
			if (train.getStopId(state.getNextStoppingIndex()) == 0) {
				return false;
			}
			return areDoorsOpen(state.getElapsedDwellTicks(), train.getTotalDwellTicks());
		}
	}

	/**
	 * Whether the doors of a train stopped at a platform are open this long into its stop.
	 *
	 * @param dwellTicks the stop time, as {@link #getTotalDwellTicks(Train, int)} works it out
	 */
	public static boolean areDoorsOpen(float elapsedDwellTicks, int dwellTicks) {
		final float maxDoorMoveTime = Math.min(MotionConstants.DOOR_MOVE_TIME, dwellTicks / 2 - MotionConstants.DOOR_DELAY);
		return elapsedDwellTicks >= MotionConstants.DOOR_DELAY && elapsedDwellTicks < dwellTicks - MotionConstants.DOOR_DELAY - maxDoorMoveTime;
	}

	/**
	 * @return the first stop on or after the rail the head is on, or the end of the path
	 */
	public static int findNextStoppingIndex(MotionTrack track, int headIndex) {
		for (int i = headIndex; i < track.size(); i++) {
			if (track.getDwellTime(i) > 0) {
				return i;
			}
		}
		return track.size() - 1;
	}

	/**
	 * The next stop of a train whose head is here: the first stop ahead of it, so a train standing at a stop short of the
	 * end of its rail (round 4) doesn't take the same stop again.
	 *
	 * @return the stop, or the end of the path
	 */
	public static int findNextStop(MotionTrack track, double railProgress) {
		for (int i = MotionConstants.findIndex(track, railProgress, false); i < track.size(); i++) {
			if (track.getDwellTime(i) > 0 && track.getStopDistance(i) > railProgress) {
				return i;
			}
		}
		return track.size() - 1;
	}

	/**
	 * @return the first stop on or after this rail of the path, or the end of the path
	 */
	public static int findRunEnd(MotionTrack track, int fromIndex) {
		for (int i = Math.max(fromIndex, 0); i < track.size(); i++) {
			if (track.getDwellTime(i) > 0) {
				return i;
			}
		}
		return track.size() - 1;
	}

	/**
	 * @return when the train is planned to leave its stop, which decides who goes first when two trains ask at once
	 */
	public static long getPlannedDepartureMillis(Train train) {
		final TrainService<?> service = train.getService();
		final long dutyDepartureMillis = service.getDutyDepartureMillis();
		if (dutyDepartureMillis > 0) {
			return dutyDepartureMillis;
		}
		final RouteTimetable.StopPlan stopPlan = service.getPlanAt(train.getState().getNextStoppingIndex());
		return stopPlan == null ? train.getMillis() : service.getPlannedMillis(stopPlan.departureMillis);
	}

	/**
	 * What the rules read about a train and its surroundings. The game's server train and the simulators implement it.
	 */
	public interface Train {

		/**
		 * The time now, as the timetable counts it.
		 */
		long getMillis();

		TrainService<?> getService();

		MotionState getState();

		MotionTrack getTrack();

		/**
		 * @return the platform or siding of this rail of the path, or 0 for a turn back rail
		 */
		long getStopId(int rail);

		boolean isContinuousMovement();

		boolean usesSpeedCurve();


		SpeedCurves getSpeedCurves();

		/**
		 * The stop time at the next stop, in ticks, as {@link #getTotalDwellTicks(Train, int)} works it out.
		 */
		int getTotalDwellTicks();

		/**
		 * @return true if a train planned to pass the station of this platform while this one is stopped hasn't gone yet,
		 * on another platform of the station
		 */
		boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis);

		/**
		 * @return how early the train may reach its next stop at full speed before it cruises slower, the network's early
		 * threshold (round 4, step 5); 0 to arrive on time
		 */
		default long getEarlyThresholdMillis() {
			return 0;
		}

		/**
		 * Asks for the run from this rail of the path to the next stop after it.
		 *
		 * @return true once the train holds the run
		 */
		boolean isRunGranted(int fromIndex);
	}
}
