package transitsim.run;

import transitsim.motion.*;

/**
 * How a train of the run layer moves in a tick (decoupling document 5.5): along a run segment to whichever comes first of
 * its next stop and the end of its movement authority. Unlike the rules taken over from MTR ({@link TrainMotion}), the
 * next stop is worked out from where the train is on its path every tick, never kept from an earlier path; a train held
 * short of its stop by its authority waits there without opening its doors or counting as arrived, and goes on as soon as
 * its authority reaches further; nothing looks ahead at rails other trains stand on.
 * <p>
 * Trains in their siding and trains coming back to it go as before. A train keeps its last run segment while it stands,
 * so the next one has a higher version, which clients go by.
 */
public final class RunMotion {

	private static final double AT = 1E-3;

	private RunMotion() {
	}

	/**
	 * @return the first stop at or after where the train is, or the end of the path
	 */
	public static int findNextStop(MotionTrack track, double position) {
		return findNextStop(track, position, false);
	}

	/**
	 * @param moving whether the train is on its way, so a stop it is just leaving isn't its next one
	 * @return the next stop of a train, or the end of the path
	 */
	public static int findNextStop(MotionTrack track, double position, boolean moving) {
		for (int i = 0; i < track.size(); i++) {
			if (track.getDwellTime(i) > 0 && (moving ? track.getDistance(i) > position + AT : track.getDistance(i) >= position - AT)) {
				return i;
			}
		}
		return track.size() - 1;
	}

	/**
	 * @return the stop after this one, or the end of the path
	 */
	public static int findStopAfter(MotionTrack track, int stop) {
		for (int i = stop + 1; i < track.size(); i++) {
			if (track.getDwellTime(i) > 0) {
				return i;
			}
		}
		return track.size() - 1;
	}

	/**
	 * @return whether the train stands at its next stop
	 */
	public static boolean isAtStop(MotionState state, MotionTrack track) {
		final int stop = findNextStop(track, state.getRailProgress());
		return state.getSpeed() <= 0 && Math.abs(track.getDistance(stop) - state.getRailProgress()) <= AT;
	}

	/**
	 * Moves the train through one tick.
	 *
	 * @param authorityEnd how far along its path the train may run
	 * @return the stop time worked out, which the host uses for the doors of the cars, or -1 if the train has nowhere to go
	 */
	public static int tick(MotionState state, MotionTrack track, MotionVehicle vehicle, MotionHooks hooks, MotionCurves curves, double authorityEnd) {
		if (track.size() == 0) {
			return -1;
		}
		final int trainCars = vehicle.getTrainCars();
		final int spacing = vehicle.getSpacing();
		final float railLength = vehicle.getRailLength();

		if (!state.isOnRoute()) {
			// In its siding, as before
			state.setNextStoppingIndex(0);
			state.setNextPlatformIndex(0);
			state.setRailProgress((railLength + trainCars * spacing) / 2);
			state.setReversed(false);
			state.setSpeed(0);
			state.setDoorTarget(false);
			state.setDoorValue(0);
			if (!state.isCurrentlyManual() && hooks.canDeploy() || state.isCurrentlyManual() && state.getManualNotch() > 0) {
				hooks.startUp(track.size() > 1 && track.isOppositeRail(0, 1));
				if (state.isOnRoute()) {
					state.setNextStoppingIndex(findNextStop(track, state.getRailProgress()));
					state.setNextPlatformIndex(state.getNextStoppingIndex());
					emit(hooks, MotionEvent.Type.DEPARTED, 0);
				}
			}
			return 0;
		}

		// Back in its siding at the end of its path
		if (state.getRailProgress() >= track.getDistance(track.getDistanceCount() - 1) - (railLength - trainCars * spacing) / 2 && !hooks.keepsOnLineAtEnd()) {
			state.setOnRoute(false);
			state.setManualNotch(-2);
			state.setSpeed(0);
			hooks.clearRiders();
			emit(hooks, MotionEvent.Type.RETURNED, track.size() - 1);
			state.setDoorTarget(false);
			state.setDoorValue(0);
			return 0;
		}

		final int stop = findNextStop(track, state.getRailProgress(), state.getSpeed() > 0);
		state.setNextStoppingIndex(stop);
		state.setNextPlatformIndex(stop);
		final double stopPosition = track.getDistance(stop);
		final int totalDwellTicks = hooks.getTotalDwellTicks();
		boolean doorsOpen = false;

		if (state.getSpeed() <= 0 && Math.abs(stopPosition - state.getRailProgress()) <= AT) {
			// At the stop: doors, the stop time, and away once the train may go and has somewhere to go to
			state.setRailProgress(stopPosition);
			if (state.getElapsedDwellTicks() == 0) {
				// The first tick at the stop is when it arrived, however it came to stand there
				emit(hooks, MotionEvent.Type.STOPPED, stop);
				hooks.onStopReached();
			}
			final boolean wayClear = authorityEnd > stopPosition + AT;
			if (state.getElapsedDwellTicks() < totalDwellTicks - MotionConstants.DOOR_MOVE_TIME - MotionConstants.DOOR_DELAY - 1 || !hooks.isHeldAtStop() && wayClear) {
				state.setElapsedDwellTicks(state.getElapsedDwellTicks() + 1);
			}
			doorsOpen = hooks.openDoors();
			if (!hooks.isClientSide() && state.getElapsedDwellTicks() >= totalDwellTicks && wayClear && !hooks.isHeldAtStop()) {
				hooks.startUp(stop + 1 < track.size() && track.isOppositeRail(stop, stop + 1));
				state.setNextStoppingIndex(findNextStop(track, state.getRailProgress() + AT * 2));
				state.setNextPlatformIndex(state.getNextStoppingIndex());
				emit(hooks, MotionEvent.Type.DEPARTED, stop);
			}
		} else {
			// Running, or waiting where its authority ends for it to reach further
			final double target = Math.min(stopPosition, Math.max(authorityEnd, state.getRailProgress()));
			final boolean wasMoving = state.getSpeed() > 0;
			if (target <= state.getRailProgress() + AT) {
				state.setSpeed(0);
					state.setManualNotch(-2);
				if (wasMoving && Math.abs(state.getRailProgress() - stopPosition) <= AT) {
					state.setRailProgress(stopPosition);
					state.setElapsedDwellTicks(0);
				}
			} else {
				final double advance = follow(state, curves, hooks, target);
				state.setRailProgress(Math.min(state.getRailProgress() + advance, target));
				final RunSegment segment = state.getRunSegment();
				if (segment != null && segment.isFinished()) {
					state.setRailProgress(target);
					state.setSpeed(0);
				}
				if (state.getSpeed() <= 0 && Math.abs(state.getRailProgress() - stopPosition) <= AT) {
					state.setRailProgress(stopPosition);
					state.setElapsedDwellTicks(0);
					state.setManualNotch(-2);
				}
			}
			if (wasMoving && state.getSpeed() <= 0 && Math.abs(state.getRailProgress() - stopPosition) > AT) {
				// Short of the stop, held by its authority: it waits, doors shut
				state.setManualNotch(-2);
			}
		}

		state.setDoorTarget(doorsOpen);
		state.setDoorValue(MotionConstants.clamp(state.getDoorValue() + (doorsOpen ? 1F : -1F) / MotionConstants.DOOR_MOVE_TIME, 0, 1));
		return totalDwellTicks;
	}

	/**
	 * Moves the train along its run segment to the target, starting a new one when the target, the run or the cruising cap
	 * changed, or the train isn't where the segment has it.
	 *
	 * @return how far the train moves
	 */
	private static double follow(MotionState state, MotionCurves curves, MotionHooks hooks, double target) {
		final double railProgress = state.getRailProgress();
		final SpeedCurves.Run run = curves.getRun(railProgress);
		if (run == null) {
			return 0;
		}
		final double end = Math.min(target, run.fastest.endPosition);
		final double cap = curves.getCruiseCap();
		RunSegment segment = state.getRunSegment();
		if (segment == null || !segment.matches(run, cap, end, railProgress)) {
			final float speed = state.getSpeed();
			final boolean replaces = segment != null;
			segment = RunSegment.start(segment, run, railProgress, speed <= MotionConstants.ACCELERATION_DEFAULT ? 0 : speed, cap, end);
			state.setRunSegment(segment);
			if (replaces) {
				emit(hooks, MotionEvent.Type.SEGMENT_REPLACED, state.getNextStoppingIndex());
			}
			if (segment.emergency) {
				emit(hooks, MotionEvent.Type.EMERGENCY_BRAKE, state.getNextStoppingIndex());
			}
		}
		segment.advance(1);
		final float oldSpeed = state.getSpeed();
		final float newSpeed = segment.isFinished() ? 0 : (float) segment.getSpeed();
		state.setSpeed(newSpeed);
		state.setManualNotch(newSpeed > oldSpeed ? 2 : 0);
		return segment.getPosition() - railProgress;
	}

	private static void emit(MotionHooks hooks, MotionEvent.Type type, int rail) {
		hooks.emit(type, rail);
	}
}
