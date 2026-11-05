package transitsim.motion;

/**
 * The rules moving a train (decoupling document 2.2), taken out of the host's train in S2. They started as exactly the
 * rules of {@link transitsim.motion.legacy.LegacyTrainMotion}; the comparison tests keep the parts meant to stay the same.
 * <p>
 * What changed: on the server, an automatic train on speed curves runs along a {@link RunSegment}, its position worked out
 * from the time since it set off rather than added up tick by tick (S2c). A client still follows the curve tick by tick
 * between the positions the server sends. On the server, the rules tell the host what happened through events (S2d).
 */
public final class TrainMotion {

	private TrainMotion() {
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
				if (state.isOnRoute()) {
					emit(hooks, MotionEvent.Type.DEPARTED, 0);
				}
			}
		} else {
			final float newAcceleration = vehicle.getAcceleration() * ticksElapsed;
			// How far a train on its speed curve moves this tick, or -1 to move at its speed
			double curveAdvance = -1;
			// Whether the train ran along a run segment this tick, and whether a loop moved it back
			boolean onSegment = false;
			boolean lapped = false;

			if (state.getRailProgress() >= track.getDistance(track.getDistanceCount() - 1) - (railLength - trainCars * spacing) / 2 && !hooks.keepsOnLineAtEnd()) {
				state.setOnRoute(false);
				state.setManualNotch(-2);
				hooks.clearRiders();
				emit(hooks, MotionEvent.Type.RETURNED, track.size() - 1);
				tempDoorOpen = false;
				tempDoorValue = 0;
			} else {
				final boolean wasMoving = state.getSpeed() > 0;
				// Where the train has to stop short of a taken block section part of the way along a rail, or NaN
				double sectionStop = Double.NaN;
				if (state.getSpeed() <= 0) {
					state.setSpeed(0);

					final boolean isOppositeRail = isOppositeRail(state, track);
					final int stoppedHead = getIndex(state, track, 0, spacing, true);
					// Also while a block section just ahead on the rail it stands on is taken
					final int nextRail = stoppedHead + (isOppositeRail ? 2 : 1);
					final boolean railBlocked = isTakenAtStart(hooks, track, nextRail, hooks.isRailBlocked(nextRail)) || !isOppositeRail && isSectionTakenJustAhead(hooks, stoppedHead, state.getRailProgress());

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
							emit(hooks, MotionEvent.Type.LAP, track.getRepeatIndex1());
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
						final int stoppedAt = state.getNextStoppingIndex();
						hooks.startUp(isOppositeRail);
						emit(hooks, MotionEvent.Type.DEPARTED, stoppedAt);
					}
				} else {
					if (!hooks.isClientSide()) {
						final int lastCheckIndex = state.getNextPlatformIndex() > 0 ? Math.min(state.getNextPlatformIndex(), track.size() - 1) : track.size() - 1;
						final double brakingDistance = 0.5 * state.getSpeed() * state.getSpeed() / (vehicle.usesSpeedCurve() ? vehicle.getDeceleration() : vehicle.getAcceleration()) + state.getSpeed() * ticksElapsed + MotionConstants.BRAKING_MARGIN;
						final int headIndex = getIndex(state, track, 0, spacing, true);
						// A taken section further along the rail the head is on comes first
						final double ownTaken = hooks.getSectionBlockedPosition(headIndex, state.getRailProgress());
						final int blockedIndex = Double.isNaN(ownTaken) ? findBlockedIndex(track, headIndex, lastCheckIndex, state.getRailProgress(), brakingDistance, hooks) : -1;
						final double blockedAt = blockedIndex > 0 ? hooks.getSectionBlockedPosition(blockedIndex, track.getDistance(blockedIndex - 1)) : Double.NaN;
						if (!Double.isNaN(ownTaken)) {
							state.setNextStoppingIndex(headIndex);
							sectionStop = ownTaken;
						} else if (blockedIndex >= 0 && !Double.isNaN(blockedAt) && blockedAt > track.getDistance(blockedIndex - 1) + 1E-6) {
							// Free from the start of the rail to the taken section
							state.setNextStoppingIndex(blockedIndex);
							sectionStop = blockedAt;
						} else if (blockedIndex >= 0) {
							state.setNextStoppingIndex(blockedIndex - 1);
						} else if (state.getNextPlatformIndex() > 0 && state.getNextPlatformIndex() < track.size()) {
							state.setNextStoppingIndex(state.getNextPlatformIndex());
							if (state.getManualNotch() < -2) {
								state.setManualNotch(0);
							}
						}
					}

					final double stoppingPosition = Double.isNaN(sectionStop) ? track.getDistance(state.getNextStoppingIndex()) : Math.min(sectionStop, track.getDistance(state.getNextStoppingIndex()));
					final double stoppingDistance = stoppingPosition - state.getRailProgress();
					if (vehicle.usesSpeedCurve()) {
						if (hooks.isClientSide()) {
							curveAdvance = followSpeedCurve(state, vehicle, curves, ticksElapsed, stoppingPosition);
						} else {
							curveAdvance = followRunSegment(state, curves, hooks, ticksElapsed, stoppingPosition);
							onSegment = true;
						}
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
					emit(hooks, MotionEvent.Type.LAP, track.getRepeatIndex1());
					continueWithoutStopping(state, hooks);
					lapped = true;
				}

				state.setRailProgress(state.getRailProgress() + (curveAdvance >= 0 ? curveAdvance : state.getSpeed() * ticksElapsed));
				// A train at the end of its segment is exactly where it stops, so stops and turning back see it there
				final RunSegment runSegment = state.getRunSegment();
				if (onSegment && !lapped && runSegment != null && runSegment.isFinished()) {
					state.setRailProgress(runSegment.target);
				}
				// Stopped short of a taken section, as at the end of a rail
				if (!Double.isNaN(sectionStop) && state.getRailProgress() > sectionStop) {
					state.setRailProgress(sectionStop);
					state.setSpeed(0);
					state.setManualNotch(-2);
				}
				if (!vehicle.isContinuousMovement() && state.getRailProgress() > track.getDistance(state.getNextStoppingIndex())) {
					if (mustStop(state, track, hooks, state.getNextStoppingIndex())) {
						state.setRailProgress(track.getDistance(state.getNextStoppingIndex()));
						state.setSpeed(0);
						state.setManualNotch(-2);
					} else {
						emit(hooks, MotionEvent.Type.PASSED, state.getNextStoppingIndex());
						continueWithoutStopping(state, hooks);
					}
				}
				if (wasMoving && state.getSpeed() == 0) {
					emit(hooks, MotionEvent.Type.STOPPED, state.getNextStoppingIndex());
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
	 * Moves the train along its run segment, starting a new one from where it is when the place to stop, the run or its
	 * cruising cap has changed, or the train isn't where the segment has it (S2c).
	 *
	 * @param stoppingPosition where the train has to stop, which is nearer than the end of the run when the line ahead is taken
	 * @return how far the train moves
	 */
	private static double followRunSegment(MotionState state, MotionCurves curves, MotionHooks hooks, float ticksElapsed, double stoppingPosition) {
		final double railProgress = state.getRailProgress();
		final SpeedCurves.Run run = curves.getRun(railProgress);
		if (run == null) {
			return state.getSpeed() * ticksElapsed;
		}
		final double target = stoppingPosition > run.fastest.endPosition + RunSegment.POSITION_TOLERANCE ? run.fastest.endPosition : stoppingPosition;
		final double cap = curves.getCruiseCap();
		RunSegment runSegment = state.getRunSegment();
		if (runSegment == null || !runSegment.matches(run, cap, target, railProgress)) {
			// The host starts a train off at a crawl only to mark it as moving: it sets off from standing, as the timetable has it
			final float speed = state.getSpeed();
			final boolean replaces = runSegment != null;
			runSegment = RunSegment.start(runSegment, run, railProgress, speed <= MotionConstants.ACCELERATION_DEFAULT ? 0 : speed, cap, target);
			state.setRunSegment(runSegment);
			if (replaces) {
				emit(hooks, MotionEvent.Type.SEGMENT_REPLACED, state.getNextStoppingIndex());
			}
			if (runSegment.emergency) {
				emit(hooks, MotionEvent.Type.EMERGENCY_BRAKE, state.getNextStoppingIndex());
			}
		}
		runSegment.advance(ticksElapsed);
		final float oldSpeed = state.getSpeed();
		final float newSpeed = runSegment.isFinished() ? 0 : (float) runSegment.getSpeed();
		state.setSpeed(newSpeed);
		state.setManualNotch(newSpeed > oldSpeed ? 2 : 0);
		return runSegment.getPosition() - railProgress;
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

	/**
	 * A driver's notch moves one step at a time, up to 2 and down to -2, and not up while the doors are open. The doors
	 * only open or close while the train is standing; asking while it moves closes them.
	 *
	 * @return whether the command changed anything
	 */
	public static boolean applyCommand(MotionState state, MotionCommand command) {
		switch (command) {
			case ACCELERATE:
				if (state.getDoorValue() == 0 && state.getManualNotch() >= -2 && state.getManualNotch() < 2) {
					state.setManualNotch(state.getManualNotch() + 1);
					return true;
				}
				return false;
			case BRAKE:
				if (state.getManualNotch() > -2) {
					state.setManualNotch(state.getManualNotch() - 1);
					return true;
				}
				return false;
			default:
				if (state.getSpeed() == 0) {
					state.setDoorTarget(!state.getDoorTarget());
					state.setManualNotch(-2);
					return true;
				}
				state.setDoorTarget(false);
				return false;
		}
	}

	/**
	 * Clients only follow the server, so they have no events.
	 */
	private static void emit(MotionHooks hooks, MotionEvent.Type type, int rail) {
		if (!hooks.isClientSide()) {
			hooks.emit(type, rail);
		}
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

	/**
	 * How close ahead a taken section keeps a train standing in front of it from moving off.
	 */
	private static final double SECTION_STANDING_MARGIN = 1;

	/**
	 * @return whether a block section just ahead on the rail a standing train's head is on is taken, so it can't move off
	 */
	public static boolean isSectionTakenJustAhead(MotionHooks hooks, int headIndex, double railProgress) {
		final double taken = hooks.getSectionBlockedPosition(headIndex, railProgress);
		return !Double.isNaN(taken) && taken - railProgress <= SECTION_STANDING_MARGIN;
	}

	/**
	 * A standing train can move onto a rail cut into block sections when only sections further along it are taken; it
	 * stops again short of them.
	 *
	 * @param taken whether the rail counts as taken as a whole
	 * @return whether it is taken where the train would move onto it
	 */
	public static boolean isTakenAtStart(MotionHooks hooks, MotionTrack track, int rail, boolean taken) {
		if (!taken || rail <= 0 || rail >= track.size()) {
			return taken;
		}
		final double start = track.getDistance(rail - 1);
		final double at = hooks.getSectionBlockedPosition(rail, start);
		return Double.isNaN(at) || at - start <= SECTION_STANDING_MARGIN;
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
