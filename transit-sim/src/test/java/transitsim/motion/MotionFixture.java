package transitsim.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

/**
 * A train on a made-up path for the motion tests, standing in for the host: it keeps the state, and its hooks behave like
 * the host's server train in the simple cases, with blocked rails, holds and departures scripted by tick.
 */
final class MotionFixture implements MotionState, MotionTrack, MotionVehicle, MotionHooks, MotionCurves {

	// State
	float speed;
	double railProgress;
	boolean doorTarget;
	float doorValue;
	float elapsedDwellTicks;
	int nextStoppingIndex;
	int nextPlatformIndex;
	boolean reversed;
	boolean onRoute;
	boolean currentlyManual;
	int manualNotch;

	// Path
	private final List<Double> lengths = new ArrayList<>();
	private final List<Integer> dwellTimes = new ArrayList<>();
	private final List<Float> maxSpeeds = new ArrayList<>();
	private final List<Boolean> accelerates = new ArrayList<>();
	private final List<Boolean> reversesAfter = new ArrayList<>();
	private double[] distances = new double[0];
	int repeatIndex1;
	int repeatIndex2;

	// Train
	int trainCars = 2;
	int spacing = 10;
	float railLength = 30;
	float acceleration = 0.01F;
	float deceleration = 0.02F;
	boolean speedCurve;
	boolean continuousMovement;
	float cruiseCap = Float.POSITIVE_INFINITY;
	private SpeedCurves curves;

	// Script
	boolean clientSide;
	boolean keepsOnLineAtEnd;
	IntPredicate deployAt = tick -> tick >= 5;
	BiPredicate<Integer, Integer> blockedAt = (tick, rail) -> false;
	IntPredicate heldAt = tick -> false;
	/**
	 * The driver's notch from this tick on, for a manual train, or null to leave it.
	 */
	Integer[] notchFrom = new Integer[0];
	/**
	 * When the train should arrive at one of its stops, for the cruising cap the host sets as the train sets off for it, or
	 * -1 for none.
	 */
	int plannedArrivalTick = -1;
	int plannedStopRail = -1;
	/**
	 * Whether a train setting off part of the way along a run gets a cap from where it is, as with the new rules, or none.
	 */
	boolean capFromState = true;
	int tick;

	// What happened
	int laps;
	int stopsReached;
	int ridersCleared;
	int startUps;

	MotionFixture rail(double length, int dwellTime, float maxSpeed) {
		return rail(length, dwellTime, maxSpeed, true, false);
	}

	MotionFixture rail(double length, int dwellTime, float maxSpeed, boolean canAccelerate, boolean reversesAfterIt) {
		lengths.add(length);
		dwellTimes.add(dwellTime);
		maxSpeeds.add(maxSpeed);
		accelerates.add(canAccelerate);
		reversesAfter.add(reversesAfterIt);
		distances = new double[lengths.size()];
		double total = 0;
		for (int i = 0; i < distances.length; i++) {
			total += lengths.get(i);
			distances[i] = total;
		}
		curves = null;
		return this;
	}

	void step(boolean legacy, float ticksElapsed) {
		if (tick < notchFrom.length && notchFrom[tick] != null) {
			manualNotch = notchFrom[tick];
		}
		if (legacy) {
			transitsim.motion.legacy.LegacyTrainMotion.tick(this, this, this, this, this, ticksElapsed);
		} else {
			TrainMotion.tick(this, this, this, this, this, ticksElapsed);
		}
		tick++;
	}

	String describe() {
		return String.format("tick=%d speed=%s progress=%s doorTarget=%s door=%s dwell=%s stop=%d platform=%d reversed=%s onRoute=%s notch=%d laps=%d stops=%d cleared=%d startUps=%d",
				tick, Float.floatToIntBits(speed), Double.doubleToLongBits(railProgress), doorTarget, Float.floatToIntBits(doorValue), Float.floatToIntBits(elapsedDwellTicks),
				nextStoppingIndex, nextPlatformIndex, reversed, onRoute, manualNotch, laps, stopsReached, ridersCleared, startUps);
	}

	// MotionState

	@Override
	public float getSpeed() {
		return speed;
	}

	@Override
	public void setSpeed(float speed) {
		this.speed = speed;
	}

	@Override
	public double getRailProgress() {
		return railProgress;
	}

	@Override
	public void setRailProgress(double railProgress) {
		this.railProgress = railProgress;
	}

	@Override
	public boolean getDoorTarget() {
		return doorTarget;
	}

	@Override
	public void setDoorTarget(boolean doorTarget) {
		this.doorTarget = doorTarget;
	}

	@Override
	public float getDoorValue() {
		return doorValue;
	}

	@Override
	public void setDoorValue(float doorValue) {
		this.doorValue = doorValue;
	}

	@Override
	public float getElapsedDwellTicks() {
		return elapsedDwellTicks;
	}

	@Override
	public void setElapsedDwellTicks(float elapsedDwellTicks) {
		this.elapsedDwellTicks = elapsedDwellTicks;
	}

	@Override
	public int getNextStoppingIndex() {
		return nextStoppingIndex;
	}

	@Override
	public void setNextStoppingIndex(int nextStoppingIndex) {
		this.nextStoppingIndex = nextStoppingIndex;
	}

	@Override
	public int getNextPlatformIndex() {
		return nextPlatformIndex;
	}

	@Override
	public void setNextPlatformIndex(int nextPlatformIndex) {
		this.nextPlatformIndex = nextPlatformIndex;
	}

	@Override
	public boolean isReversed() {
		return reversed;
	}

	@Override
	public void setReversed(boolean reversed) {
		this.reversed = reversed;
	}

	@Override
	public boolean isOnRoute() {
		return onRoute;
	}

	@Override
	public void setOnRoute(boolean onRoute) {
		this.onRoute = onRoute;
	}

	@Override
	public boolean isCurrentlyManual() {
		return currentlyManual;
	}

	@Override
	public int getManualNotch() {
		return manualNotch;
	}

	@Override
	public void setManualNotch(int manualNotch) {
		this.manualNotch = manualNotch;
	}

	// MotionTrack

	@Override
	public int size() {
		return distances.length;
	}

	@Override
	public int getDistanceCount() {
		return distances.length;
	}

	@Override
	public double getDistance(int rail) {
		return distances[rail];
	}

	@Override
	public int getDwellTime(int rail) {
		return dwellTimes.get(rail);
	}

	@Override
	public boolean canAccelerate(int rail) {
		return accelerates.get(rail);
	}

	@Override
	public float getMaxSpeed(int rail) {
		return maxSpeeds.get(rail);
	}

	@Override
	public boolean isOppositeRail(int rail, int nextRail) {
		return nextRail == rail + 1 && reversesAfter.get(rail) || rail == repeatIndex2 && nextRail == repeatIndex1 && repeatIndex1 > 0 && reversesAfter.get(repeatIndex2 - 1);
	}

	@Override
	public int getRepeatIndex1() {
		return repeatIndex1;
	}

	@Override
	public int getRepeatIndex2() {
		return repeatIndex2;
	}

	// MotionVehicle

	@Override
	public int getTrainCars() {
		return trainCars;
	}

	@Override
	public int getSpacing() {
		return spacing;
	}

	@Override
	public float getRailLength() {
		return railLength;
	}

	@Override
	public float getAcceleration() {
		return acceleration;
	}

	@Override
	public float getDeceleration() {
		return deceleration;
	}

	@Override
	public boolean usesSpeedCurve() {
		return speedCurve && !currentlyManual && !continuousMovement;
	}

	@Override
	public boolean isContinuousMovement() {
		return continuousMovement;
	}

	@Override
	public float getDefaultMaxSpeed() {
		return 0.2F;
	}

	@Override
	public float getManualMaxSpeed() {
		return 0.6F;
	}

	// MotionHooks

	@Override
	public boolean isClientSide() {
		return clientSide;
	}

	@Override
	public int getTotalDwellTicks() {
		return dwellTimes.get(nextStoppingIndex) * 10;
	}

	@Override
	public boolean canDeploy() {
		return deployAt.test(tick);
	}

	/**
	 * Like the host's server train: off at a crawl, turned round if the next rail runs back, heading for the next stop.
	 */
	@Override
	public void startUp(boolean isOppositeRail) {
		startUps++;
		onRoute = true;
		elapsedDwellTicks = 0;
		speed = MotionConstants.ACCELERATION_DEFAULT;
		if (isOppositeRail) {
			railProgress += trainCars * spacing;
			reversed = !reversed;
		}
		int next = MotionConstants.findIndex(this, railProgress, false);
		while (next < distances.length - 1 && dwellTimes.get(next) == 0) {
			next++;
		}
		if (distances[next] <= railProgress && next < distances.length - 1) {
			next++;
			while (next < distances.length - 1 && dwellTimes.get(next) == 0) {
				next++;
			}
		}
		nextStoppingIndex = next;
		if (speedCurve && plannedArrivalTick > 0 && nextStoppingIndex == plannedStopRail) {
			final SpeedCurves.Run run = getCurves().getRun(railProgress);
			if (capFromState || run != null && Math.abs(railProgress - run.fastest.startPosition) <= 1) {
				cruiseCap = (float) getCurves().findCruiseCap(railProgress, speed, nextStoppingIndex, plannedArrivalTick - tick);
			} else {
				cruiseCap = Float.POSITIVE_INFINITY;
			}
		}
		doorTarget = false;
		doorValue = 0;
		nextPlatformIndex = nextStoppingIndex;
	}

	@Override
	public boolean keepsOnLineAtEnd() {
		return keepsOnLineAtEnd;
	}

	@Override
	public void clearRiders() {
		ridersCleared++;
	}

	@Override
	public boolean isRepeat() {
		return repeatIndex1 > 0 && repeatIndex2 > 0;
	}

	@Override
	public void onLapComplete() {
		laps++;
	}

	@Override
	public void onStopReached() {
		stopsReached++;
	}

	@Override
	public boolean isHeldAtStop() {
		return heldAt.test(tick);
	}

	@Override
	public boolean isRailBlocked(int rail) {
		return rail < distances.length && blockedAt.test(tick, rail);
	}

	/**
	 * Like the host's server train: open a while after stopping, closed in time to leave.
	 */
	@Override
	public boolean openDoors() {
		if (currentlyManual) {
			return doorTarget;
		}
		if (continuousMovement) {
			final int index = MotionConstants.findIndex(this, railProgress, false);
			return dwellTimes.get(index) > 0 && index > 0 && railProgress - distances[index - 1] > 0.5;
		}
		final int dwellTicks = getTotalDwellTicks();
		final float maxDoorMoveTime = Math.min(MotionConstants.DOOR_MOVE_TIME, dwellTicks / 2 - MotionConstants.DOOR_DELAY);
		return elapsedDwellTicks >= MotionConstants.DOOR_DELAY && elapsedDwellTicks < dwellTicks - MotionConstants.DOOR_DELAY - maxDoorMoveTime;
	}

	// MotionCurves

	@Override
	public SpeedProfile getProfile(double position) {
		final SpeedCurves.Run run = getCurves().getRun(position);
		return run == null ? null : Float.isInfinite(cruiseCap) ? run.fastest : run.withCap(cruiseCap);
	}

	SpeedCurves getCurves() {
		if (curves == null) {
			final double[] lengthArray = new double[lengths.size()];
			final double[] limits = new double[lengths.size()];
			final boolean[] stops = new boolean[lengths.size()];
			final boolean[] reversals = new boolean[lengths.size()];
			for (int i = 0; i < lengthArray.length; i++) {
				lengthArray[i] = lengths.get(i);
				limits[i] = maxSpeeds.get(i);
				stops[i] = dwellTimes.get(i) > 0;
				reversals[i] = reversesAfter.get(i);
			}
			curves = SpeedCurves.of(new TrackView(lengthArray, limits, stops, reversals, 0.02), (railLength + trainCars * spacing) / 2, trainCars * spacing, acceleration, deceleration);
		}
		return curves;
	}
}
