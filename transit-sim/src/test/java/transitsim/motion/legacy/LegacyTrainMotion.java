package transitsim.motion.legacy;

import transitsim.motion.*;

/**
 * The rules moving a train exactly as they were before S2 (tag {@code pre-s2}, {@code Train.simulateTrain}), only reading
 * and writing through the motion interfaces. Kept so the new rules in {@link TrainMotion} can be compared against them, in
 * tests and in the game through the dashboard setting. Don't change them: they go once the new rules are tested.
 */
public final class LegacyTrainMotion {

	private LegacyTrainMotion() {
	}

	/**
	 * @return the stop time worked out at the start of the tick, which the host uses for the doors of the cars, or -1 if the
	 * train has nowhere to go
	 */
	public static int tick(MotionState state, MotionTrack track, MotionVehicle vehicle, MotionHooks hooks, MotionCurves curves, float ticksElapsed) {
		if (state.getNextStoppingIndex() >= track.size()) {
			return -1;
		}

		final boolean tempDoorOpen;
		final float tempDoorValue;
		final int totalDwellTicks = hooks.getTotalDwellTicks();
		final int trainCars = vehicle.getTrainCars();
		final int spacing = vehicle.getSpacing();
		final float railLength = vehicle.getRailLength();

		if (!state.isOnRoute()) {
			state.setRailProgress((railLength + trainCars * spacing) / 2);
			state.setReversed(false);
			tempDoorOpen = false;
			tempDoorValue = 0;
			state.setSpeed(0);
			state.setNextStoppingIndex(0);

			if (!state.isCurrentlyManual() && hooks.canDeploy() || state.isCurrentlyManual() && state.getManualNotch() > 0) {
				hooks.startUp(isOppositeRail(state, track));
			}
		} else {
			final float newAcceleration = vehicle.getAcceleration() * ticksElapsed;
			// How far a train on its speed curve moves this tick, or -1 to move at its speed
			double curveAdvance = -1;

			if (state.getRailProgress() >= track.getDistance(track.getDistanceCount() - 1) - (railLength - trainCars * spacing) / 2 && !hooks.keepsOnLineAtEnd()) {
				state.setOnRoute(false);
				state.setManualNotch(-2);
				hooks.clearRiders();
				tempDoorOpen = false;
				tempDoorValue = 0;
			} else {
				if (state.getSpeed() <= 0) {
					state.setSpeed(0);

					final boolean isOppositeRail = isOppositeRail(state, track);
					final boolean railBlocked = hooks.isRailBlocked(getIndex(state, track, 0, spacing, true) + (isOppositeRail ? 2 : 1));

					if (totalDwellTicks == 0) {
						tempDoorOpen = false;
					} else {
						if (state.getElapsedDwellTicks() == 0 && hooks.isRepeat() && getIndex(track, state.getRailProgress(), false) >= track.getRepeatIndex2() && track.getDistanceCount() > track.getRepeatIndex1()) {
							if (track.isOppositeRail(track.getRepeatIndex2(), track.getRepeatIndex1())) {
								state.setRailProgress(track.getDistance(track.getRepeatIndex1() - 1) + trainCars * spacing);
								state.setReversed(!state.isReversed());
							} else {
								state.setRailProgress(track.getDistance(track.getRepeatIndex1()));
							}
							hooks.onLapComplete();
						}

						if (state.getElapsedDwellTicks() == 0) {
							hooks.onStopReached();
						}

						// Holding can change the way the train leaves by, so it is checked even while the usual way is blocked
						if (state.getElapsedDwellTicks() < totalDwellTicks - MotionConstants.DOOR_MOVE_TIME - MotionConstants.DOOR_DELAY - ticksElapsed || !hooks.isHeldAtStop() && !railBlocked) {
							state.setElapsedDwellTicks(state.getElapsedDwellTicks() + ticksElapsed);
						}

						tempDoorOpen = hooks.openDoors();
					}

					if (!hooks.isClientSide() && (state.isCurrentlyManual() || state.getElapsedDwellTicks() >= totalDwellTicks) && !railBlocked && (!state.isCurrentlyManual() || state.getManualNotch() > 0)) {
						hooks.startUp(isOppositeRail);
					}
				} else {
					if (!hooks.isClientSide()) {
						final int lastCheckIndex = state.getNextPlatformIndex() > 0 ? Math.min(state.getNextPlatformIndex(), track.size() - 1) : track.size() - 1;
						final double brakingDistance = 0.5 * state.getSpeed() * state.getSpeed() / (vehicle.usesSpeedCurve() ? vehicle.getDeceleration() : vehicle.getAcceleration()) + state.getSpeed() * ticksElapsed + MotionConstants.BRAKING_MARGIN;
						final int blockedIndex = findBlockedIndex(track, getIndex(state, track, 0, spacing, true), lastCheckIndex, state.getRailProgress(), brakingDistance, hooks);
						if (blockedIndex >= 0) {
							state.setNextStoppingIndex(blockedIndex - 1);
						} else if (state.getNextPlatformIndex() > 0 && state.getNextPlatformIndex() < track.size()) {
							state.setNextStoppingIndex(state.getNextPlatformIndex());
							if (state.getManualNotch() < -2) {
								state.setManualNotch(0);
							}
						}
					}

					final double stoppingDistance = track.getDistance(state.getNextStoppingIndex()) - state.getRailProgress();
					if (vehicle.usesSpeedCurve()) {
						curveAdvance = followSpeedCurve(state, vehicle, curves, ticksElapsed, track.getDistance(state.getNextStoppingIndex()));
					} else if (!vehicle.isContinuousMovement() && stoppingDistance < 0.5 * state.getSpeed() * state.getSpeed() / vehicle.getAcceleration() && mustStop(state, track, hooks, state.getNextStoppingIndex())) {
						final float speed = state.getSpeed();
						state.setSpeed(stoppingDistance <= 0 ? MotionConstants.ACCELERATION_DEFAULT : (float) Math.max(speed - (0.5 * speed * speed / stoppingDistance) * ticksElapsed, MotionConstants.ACCELERATION_DEFAULT));
						state.setManualNotch(-3);
					} else {
						if (state.isCurrentlyManual()) {
							if (state.getManualNotch() >= -2) {
								state.setSpeed(MotionConstants.clamp(state.getSpeed() + state.getManualNotch() * newAcceleration / 2, 0, vehicle.getManualMaxSpeed()));
							}
						} else {
							final float railSpeed = getRailSpeed(state, track, vehicle, getIndex(state, track, 0, spacing, false));
							final float speed = state.getSpeed();
							if (speed < railSpeed) {
								state.setSpeed(Math.min(speed + newAcceleration, railSpeed));
								state.setManualNotch(2);
							} else if (speed > railSpeed) {
								state.setSpeed(Math.max(speed - newAcceleration, railSpeed));
								state.setManualNotch(-2);
							} else {
								state.setManualNotch(0);
							}
						}
					}

					tempDoorOpen = vehicle.isContinuousMovement() && hooks.openDoors();
				}

				// A loop joins back onto itself, so the train keeps running instead of stopping at the end of the path
				if (loopsWithoutReversing(track, hooks) && state.getRailProgress() + (curveAdvance >= 0 ? curveAdvance : state.getSpeed() * ticksElapsed) >= track.getDistance(track.getRepeatIndex2())) {
					state.setRailProgress(state.getRailProgress() + (track.getDistance(track.getRepeatIndex1() - 1) - track.getDistance(track.getRepeatIndex2() - 1)));
					hooks.onLapComplete();
					continueWithoutStopping(state, hooks);
				}

				state.setRailProgress(state.getRailProgress() + (curveAdvance >= 0 ? curveAdvance : state.getSpeed() * ticksElapsed));
				if (!vehicle.isContinuousMovement() && state.getRailProgress() > track.getDistance(state.getNextStoppingIndex())) {
					if (mustStop(state, track, hooks, state.getNextStoppingIndex())) {
						state.setRailProgress(track.getDistance(state.getNextStoppingIndex()));
						state.setSpeed(0);
						state.setManualNotch(-2);
					} else {
						continueWithoutStopping(state, hooks);
					}
				}

				tempDoorValue = MotionConstants.clamp(state.getDoorValue() + ticksElapsed * (state.getDoorTarget() ? 1 : -1) / MotionConstants.DOOR_MOVE_TIME, 0, 1);
			}
		}

		state.setDoorTarget(tempDoorOpen);
		state.setDoorValue(tempDoorValue);
		if (state.getDoorTarget() || state.getDoorValue() != 0) {
			state.setManualNotch(-2);
		}

		return totalDwellTicks;
	}

	/**
	 * Moves the train along its speed curve. A train on the curve with nothing in the way moves exactly as the curve says,
	 * so it arrives when the timetable worked out. Off the curve, held back by a train ahead or catching up after, it
	 * speeds up and brakes at its own rates, never faster than the curve.
	 *
	 * @param stoppingDistance where the train has to stop, which is nearer than the end of the run when the line ahead is taken
	 * @return how far the train moves
	 */
	private static double followSpeedCurve(MotionState state, MotionVehicle vehicle, MotionCurves curves, float ticksElapsed, double stoppingDistance) {
		final double railProgress = state.getRailProgress();
		final float speed = state.getSpeed();
		final SpeedProfile profile = curves.getProfile(railProgress);
		if (profile == null) {
			return speed * ticksElapsed;
		}
		final float decelerationConstant = vehicle.getDeceleration();
		final double curveSpeed = profile.getSpeed(railProgress);
		final boolean stopsEarly = stoppingDistance < profile.endPosition - MotionConstants.CURVE_SPEED_TOLERANCE;
		final double blockSpeed = stopsEarly ? Math.sqrt(2 * decelerationConstant * Math.max(0, stoppingDistance - railProgress)) : Double.POSITIVE_INFINITY;
		final double furthest = profile.advance(railProgress, ticksElapsed);
		final double newProgress;
		final float newStateSpeed;
		if (blockSpeed >= curveSpeed - MotionConstants.CURVE_SPEED_TOLERANCE && speed >= curveSpeed - MotionConstants.CURVE_SPEED_TOLERANCE) {
			newProgress = stopsEarly ? Math.min(furthest, stoppingDistance) : furthest;
			newStateSpeed = (float) profile.getSpeed(newProgress);
		} else {
			final double limit = Math.min(curveSpeed, blockSpeed);
			final double newSpeed = speed < limit ? Math.min(speed + vehicle.getAcceleration() * ticksElapsed, limit) : Math.max(limit, speed - decelerationConstant * ticksElapsed);
			newProgress = Math.min(Math.min(railProgress + (speed + newSpeed) / 2 * ticksElapsed, furthest), stopsEarly ? stoppingDistance : Double.POSITIVE_INFINITY);
			newStateSpeed = (float) Math.max(newSpeed, newProgress < (stopsEarly ? stoppingDistance : profile.endPosition) ? MotionConstants.ACCELERATION_DEFAULT / 10 : 0);
		}
		state.setSpeed(newStateSpeed);
		state.setManualNotch(newStateSpeed > curveSpeed - MotionConstants.CURVE_SPEED_TOLERANCE ? 0 : 2);
		return newProgress - railProgress;
	}

	private static float getRailSpeed(MotionState state, MotionTrack track, MotionVehicle vehicle, int railIndex) {
		final float railSpeed;
		if (track.canAccelerate(railIndex)) {
			railSpeed = track.getMaxSpeed(railIndex);
		} else {
			final int lastRail = railIndex > 0 ? railIndex - 1 : railIndex;
			railSpeed = Math.max(track.canAccelerate(lastRail) ? track.getMaxSpeed(lastRail) : vehicle.getDefaultMaxSpeed(), state.getSpeed());
		}
		return railSpeed;
	}

	/**
	 * Only a manual train may run through a stop, unless the rails end there or the next one is blocked.
	 */
	private static boolean mustStop(MotionState state, MotionTrack track, MotionHooks hooks, int stopIndex) {
		if (!state.isCurrentlyManual()) {
			return true;
		}
		final boolean railsEnd;
		if (hooks.isRepeat() && stopIndex >= track.getRepeatIndex2() && track.getDistanceCount() > track.getRepeatIndex1()) {
			railsEnd = track.isOppositeRail(track.getRepeatIndex2(), track.getRepeatIndex1());
		} else {
			railsEnd = stopIndex >= track.getDistanceCount() - 1 || track.isOppositeRail(stopIndex, stopIndex + 1);
		}
		return railsEnd || hooks.isRailBlocked(stopIndex + 1);
	}

	private static boolean loopsWithoutReversing(MotionTrack track, MotionHooks hooks) {
		return hooks.isRepeat() && track.getRepeatIndex2() > track.getRepeatIndex1() && track.getRepeatIndex2() < track.getDistanceCount() && !track.isOppositeRail(track.getRepeatIndex2(), track.getRepeatIndex1());
	}

	private static void continueWithoutStopping(MotionState state, MotionHooks hooks) {
		final float oldSpeed = state.getSpeed();
		hooks.startUp(false);
		state.setSpeed(oldSpeed);
	}

	private static boolean isOppositeRail(MotionState state, MotionTrack track) {
		final int nextStoppingIndex = state.getNextStoppingIndex();
		return track.size() > nextStoppingIndex + 1 && state.getRailProgress() == track.getDistance(nextStoppingIndex) && track.isOppositeRail(nextStoppingIndex, nextStoppingIndex + 1);
	}

	private static int getIndex(MotionState state, MotionTrack track, int car, int trainSpacing, boolean roundDown) {
		return getIndex(track, state.getRailProgress() - car * trainSpacing, roundDown);
	}

	private static int getIndex(MotionTrack track, double tempRailProgress, boolean roundDown) {
		return MotionConstants.findIndex(track, tempRailProgress, roundDown);
	}

	/**
	 * Looks along the path for a blocked rail the train must start braking for. The next rail is always checked.
	 */
	private static int findBlockedIndex(MotionTrack track, int headIndex, int lastIndex, double railProgress, double lookAhead, MotionHooks hooks) {
		for (int i = headIndex + 1; i == headIndex + 1 || i <= lastIndex && track.getDistance(i - 1) - railProgress <= lookAhead; i++) {
			if (hooks.isRailBlocked(i)) {
				return i;
			}
		}
		return -1;
	}
}
