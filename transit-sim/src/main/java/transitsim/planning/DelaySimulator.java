package transitsim.planning;

import transitsim.planning.OperationPlan.Duty;
import transitsim.planning.OperationPlan.Trip;
import transitsim.planning.OperationPlan.TripStop;

/**
 * Carries lateness along a duty without any track detail. Running times stay as planned, so a train only recovers
 * through spare stop time and layover. A stopping train never leaves before its minimum dwell is over.
 */
public final class DelaySimulator {

	public static final long DOOR_CYCLE_MILLIS = 8400;

	private DelaySimulator() {
	}

	/**
	 * @return the lateness when the duty's last trip ends
	 */
	public static long finalLateness(Duty duty, OperationNetwork network, long minDoorOpenMillis, long delayedStopMillis, long extraDelayMillis) {
		long lateMillis = 0;
		for (int i = 0; i < duty.trips.size(); i++) {
			final Trip trip = duty.trips.get(i);
			for (final TripStop stop : trip.stops) {
				final long actualArrivalMillis = stop.arrivalMillis + lateMillis + (stop.arrivalMillis == delayedStopMillis ? extraDelayMillis : 0);
				final long minDwellMillis = stop.pass ? 0 : DOOR_CYCLE_MILLIS + minDoorOpenMillis;
				final long actualDepartureMillis = Math.max(stop.departureMillis, actualArrivalMillis + minDwellMillis);
				lateMillis = actualDepartureMillis - stop.departureMillis;
			}

			if (i + 1 < duty.trips.size()) {
				final Trip nextTrip = duty.trips.get(i + 1);
				final long readyMillis = trip.endMillis() + lateMillis + network.turnaroundMillis(trip.lastPlatform()) + network.connectionMillis(trip.lastPlatform(), nextTrip.firstPlatform());
				lateMillis = Math.max(0, readyMillis - nextTrip.startMillis());
			}
		}
		return lateMillis;
	}
}
