package transitsim.sim;

import transitsim.plan.PlanTrack;

import java.util.UUID;

/**
 * A straight track between two points on a line, for running services without a world.
 */
class TestTrack implements PlanTrack {

	private final long start;
	private final long end;
	private final boolean isSiding;
	private final long stopId;
	private final int dwellTime;
	private int stopIndex;

	/**
	 * @param start     where the track starts along the line, in metres
	 * @param end       where it ends
	 * @param isSiding  true for a siding, false for a platform
	 * @param dwellTime the stop time at the end of the track in half seconds, or 0
	 */
	TestTrack(long start, long end, boolean isSiding, long stopId, int dwellTime, int stopIndex) {
		this.start = start;
		this.end = end;
		this.isSiding = isSiding;
		this.stopId = stopId;
		this.dwellTime = dwellTime;
		this.stopIndex = stopIndex;
	}

	@Override
	public double getTrackLength() {
		return Math.abs(end - start);
	}

	@Override
	public int getDwellTime() {
		return dwellTime;
	}

	@Override
	public boolean isPlatformTrack() {
		return !isSiding;
	}

	@Override
	public boolean isSidingTrack() {
		return isSiding;
	}

	@Override
	public long getStopId() {
		return stopId;
	}

	@Override
	public int getStopIndex() {
		return stopIndex;
	}

	@Override
	public void setStopIndex(int stopIndex) {
		this.stopIndex = stopIndex;
	}

	@Override
	public boolean isOppositeOf(PlanTrack track) {
		return track instanceof TestTrack && ((TestTrack) track).start == end && ((TestTrack) track).end == start;
	}

	@Override
	public UUID getTrackKey() {
		return new UUID(Math.min(start, end), Math.max(start, end));
	}
}
