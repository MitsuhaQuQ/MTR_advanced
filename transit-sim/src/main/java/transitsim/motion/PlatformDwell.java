package transitsim.motion;

/** Actual time at a stop and, once departure has been cleared, the remaining dwell projected onto the clock. */
public final class PlatformDwell {

	public final long platformId;
	public final long arrivedMillis;
	/** Zero while held, or when the departure cannot be predicted. */
	public final long departureMillis;

	private PlatformDwell(long platformId, long arrivedMillis, long departureMillis) {
		this.platformId = platformId;
		this.arrivedMillis = arrivedMillis;
		this.departureMillis = departureMillis;
	}

	public static PlatformDwell update(PlatformDwell previous, long platformId, long now, float remainingTicks) {
		if (platformId == 0) {
			return null;
		}
		final long arrived = previous != null && previous.platformId == platformId ? previous.arrivedMillis : now;
		final long departure = remainingTicks < 0 ? 0 : now + (long) Math.ceil(remainingTicks * MotionConstants.MILLIS_PER_TICK);
		return previous != null && previous.platformId == platformId && previous.departureMillis == departure
				? previous : new PlatformDwell(platformId, arrived, departure);
	}

	/** Reports a stop only at its stopping point, never a halt at a signal before it. */
	public static void report(MotionState state, MotionTrack track, MotionHooks hooks, float departureTicks) {
		if (!hooks.isClientSide()) {
			final int rail = state.getNextStoppingIndex();
			final boolean atStop = state.isOnRoute() && state.getSpeed() == 0 && rail >= 0 && rail < track.size()
					&& track.getDwellTime(rail) > 0 && Math.abs(state.getRailProgress() - track.getStopDistance(rail)) <= 1E-3;
			hooks.onDwell(atStop ? rail : -1, atStop && !state.isCurrentlyManual() ? departureTicks : -1);
		}
	}
}
