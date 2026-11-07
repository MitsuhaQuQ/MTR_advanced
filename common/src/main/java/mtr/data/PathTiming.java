package mtr.data;

import mtr.advanced.TrackViews;
import mtr.path.PathData;
import transitsim.motion.SpeedCurves;
import transitsim.plan.PathTimes;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How long a kind of vehicle takes along a path: its time segments, the times at each stop and between platforms. A
 * siding times its paths with its own vehicle, and a route's path is timed with each vehicle that runs it
 * ({@link RoutePaths}), by the same rules, so the times agree wherever they are worked out.
 */
public final class PathTiming {

	private PathTiming() {
	}

	public static StopTimes calculateStopTimes(Vehicle vehicle, List<PathData> path) {
		final StopTimes stopTimes = new StopTimes();
		generateTimeSegments(vehicle, path, new ArrayList<>(), new HashMap<>(), stopTimes, 0, 0);
		return stopTimes;
	}

	/**
	 * With speed curves, by {@link PathTimes#curveTimes}; without, tick by tick as trains without them run, accelerating
	 * and braking at the vehicle's acceleration up to each rail's speed.
	 *
	 * @param platformTimes filled with the time between each pair of platforms one after the other
	 * @param joinIndex     where the path joins a loop, or 0
	 * @param lapEndIndex   where a lap of the loop ends, or 0
	 */
	public static void generateTimeSegments(Vehicle vehicle, List<PathData> path, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
		timeSegments.clear();
		stopTimes.clear();
		stopTimes.setJoinIndex(joinIndex);
		if (vehicle.useSpeedCurve && !vehicle.transportMode.continuousMovement && !path.isEmpty()) {
			// Timetables are planned at the planned speeds, below what trains may run, which leaves them room to catch up
			final SpeedCurves curves = SpeedCurves.of(TrackViews.of(path, vehicle.transportMode, vehicle.maxSpeed, true), (vehicle.railLength + vehicle.trainCars * vehicle.spacing) / 2, vehicle.trainCars * vehicle.spacing, vehicle.acceleration, vehicle.deceleration);
			PathTimes.curveTimes(path, curves, timeSegments, platformTimes, stopTimes);
			stopTimes.linkJoinStops(lapEndIndex);
			return;
		}

		final float accelerationConstant = vehicle.acceleration;
		double distanceSum1 = 0;
		final List<Double> stoppingDistances = new ArrayList<>();
		for (final PathData pathData : path) {
			distanceSum1 += pathData.rail.getLength();
			if (pathData.dwellTime > 0) {
				stoppingDistances.add(distanceSum1);
			}
		}

		double railProgress = (vehicle.railLength + vehicle.trainCars * vehicle.spacing) / 2;
		double nextStoppingDistance = 0;
		float speed = 0;
		float time = 0;
		float timeOld = 0;
		long savedRailBaseIdOld = 0;
		double distanceSum2 = 0;
		boolean startNewSegment = false;
		for (int i = 0; i < path.size(); i++) {
			if (railProgress >= nextStoppingDistance) {
				if (stoppingDistances.isEmpty()) {
					nextStoppingDistance = distanceSum1;
				} else {
					nextStoppingDistance = stoppingDistances.remove(0);
				}
			}

			final PathData pathData = path.get(i);
			final float railSpeed = vehicle.limit(!SpeedRules.keepsLimitBefore(pathData.rail.railType, vehicle.transportMode) ? pathData.getPlanSpeed() : Math.max(speed, RailType.getDefaultMaxBlocksPerTick(vehicle.transportMode)));
			distanceSum2 += pathData.rail.getLength();

			while (railProgress < distanceSum2) {
				final int speedChange;
				if (speed > railSpeed || nextStoppingDistance - railProgress + 1 < 0.5 * speed * speed / accelerationConstant) {
					speed = Math.max(speed - accelerationConstant, accelerationConstant);
					speedChange = -1;
				} else if (speed < railSpeed) {
					speed = Math.min(speed + accelerationConstant, railSpeed);
					speedChange = 1;
				} else {
					speedChange = 0;
				}

				if (startNewSegment || timeSegments.isEmpty() || timeSegments.get(timeSegments.size() - 1).speedChange != speedChange) {
					timeSegments.add(new TimeSegment(railProgress, speed, time, speedChange, accelerationConstant));
					startNewSegment = false;
				}

				railProgress = Math.min(railProgress + speed, distanceSum2);
				time++;

				final TimeSegment timeSegment = timeSegments.get(timeSegments.size() - 1);
				timeSegment.endRailProgress = railProgress;
				timeSegment.endTime = time;
				timeSegment.pass = railProgress == distanceSum2 && PathTimes.isPassingPlatform(path, i);
				timeSegment.savedRailBaseId = timeSegment.pass || nextStoppingDistance != distanceSum1 && railProgress == distanceSum2 && pathData.dwellTime > 0 ? pathData.savedRailBaseId : 0;
				// The next rail would otherwise extend this segment and clear the platform
				startNewSegment = timeSegment.pass;
			}

			final boolean isPlatformStop = pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0;
			if (pathData.dwellTime > 0 || PathTimes.isPassingPlatform(path, i)) {
				final boolean isTurnBack = !isPlatformStop && i + 1 < path.size() && pathData.isOppositeRail(path.get(i + 1));
				stopTimes.add(i, isPlatformStop ? pathData.savedRailBaseId : 0, isTurnBack ? pathData.getRailProduct() : null, time, time + pathData.dwellTime * 10, isPlatformStop && pathData.dwellTime > 0);
			}

			time += pathData.dwellTime * 5;

			if (pathData.savedRailBaseId != 0) {
				if (savedRailBaseIdOld != 0) {
					if (!platformTimes.containsKey(savedRailBaseIdOld)) {
						platformTimes.put(savedRailBaseIdOld, new HashMap<>());
					}
					platformTimes.get(savedRailBaseIdOld).put(pathData.savedRailBaseId, time - timeOld);
				}
				savedRailBaseIdOld = pathData.savedRailBaseId;
				timeOld = time;
			}

			time += pathData.dwellTime * 5;
			// A train stopping sets off again from standing, though braking to the end of the rail may leave it some speed
			if (pathData.dwellTime > 0) {
				speed = 0;
			}

			if (i + 1 < path.size() && pathData.isOppositeRail(path.get(i + 1))) {
				railProgress += vehicle.spacing * vehicle.trainCars;
			}
		}

		stopTimes.linkJoinStops(lapEndIndex);
	}

	/**
	 * What the times along a path depend on of a vehicle; equal vehicles give equal times, so times can be kept for each.
	 */
	public static final class Vehicle {

		/**
		 * The length of the siding the train starts in, as a train starts with its middle at the middle of it.
		 */
		public final float railLength;
		public final int trainCars;
		public final int spacing;
		/**
		 * In m/tick², as the siding keeps it ({@code SidingScreen} shows it × 400 in m/s²).
		 */
		public final float acceleration;
		public final float deceleration;
		public final boolean useSpeedCurve;
		public final TransportMode transportMode;
		/**
		 * The highest speed of the vehicle, in blocks per tick, or 0 for none.
		 */
		public final float maxSpeed;

		public Vehicle(float railLength, int trainCars, int spacing, float acceleration, float deceleration, boolean useSpeedCurve, TransportMode transportMode) {
			this(railLength, trainCars, spacing, acceleration, deceleration, useSpeedCurve, transportMode, 0);
		}

		public Vehicle(float railLength, int trainCars, int spacing, float acceleration, float deceleration, boolean useSpeedCurve, TransportMode transportMode, float maxSpeed) {
			this.railLength = railLength;
			this.trainCars = trainCars;
			this.spacing = spacing;
			this.acceleration = acceleration;
			this.deceleration = deceleration;
			this.useSpeedCurve = useSpeedCurve;
			this.transportMode = transportMode;
			this.maxSpeed = maxSpeed;
		}

		/**
		 * @return a speed limit for this vehicle, no higher than its highest speed
		 */
		public float limit(float speedLimit) {
			return maxSpeed > 0 ? Math.min(speedLimit, SpeedRules.plan(maxSpeed, transportMode)) : speedLimit;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Vehicle)) {
				return false;
			}
			final Vehicle other = (Vehicle) object;
			return railLength == other.railLength && trainCars == other.trainCars && spacing == other.spacing && acceleration == other.acceleration && deceleration == other.deceleration && useSpeedCurve == other.useSpeedCurve && transportMode == other.transportMode;
		}

		@Override
		public int hashCode() {
			return Objects.hash(railLength, trainCars, spacing, acceleration, deceleration, useSpeedCurve, transportMode);
		}
	}
}
