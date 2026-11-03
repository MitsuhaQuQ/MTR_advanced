package transitsim.sim;

import transitsim.plan.PlanTrack;

import java.util.UUID;

/**
 * A track between two numbered nodes for the whole-chain scenarios. The same two nodes the other way round are the same
 * track run the other way.
 */
final class ScenarioTrack implements RunTrack {

	enum Kind {PLAIN, PLATFORM, SIDING}

	private final long startNode;
	private final long endNode;
	private final double length;
	private final Kind kind;
	private final long stopId;
	private final int dwellTime;
	private final float maxSpeed;
	private final boolean twoWay;
	private int stopIndex;

	/**
	 * @param dwellTime in half seconds, or 0 for no stop
	 * @param twoWay    whether trains also run the other way along it
	 */
	ScenarioTrack(long startNode, long endNode, double length, Kind kind, long stopId, int dwellTime, float maxSpeed, boolean twoWay) {
		this.startNode = startNode;
		this.endNode = endNode;
		this.length = length;
		this.kind = kind;
		this.stopId = stopId;
		this.dwellTime = dwellTime;
		this.maxSpeed = maxSpeed;
		this.twoWay = twoWay;
	}

	static ScenarioTrack plain(long startNode, long endNode, double length, boolean twoWay) {
		return new ScenarioTrack(startNode, endNode, length, Kind.PLAIN, 0, 0, 1, twoWay);
	}

	static ScenarioTrack platform(long startNode, long endNode, long platformId, int dwellTime, boolean twoWay) {
		return new ScenarioTrack(startNode, endNode, 30, Kind.PLATFORM, platformId, dwellTime, 1, twoWay);
	}

	static ScenarioTrack siding(long startNode, long endNode, long sidingId, double length) {
		return new ScenarioTrack(startNode, endNode, length, Kind.SIDING, sidingId, 0, 0.5F, true);
	}

	/**
	 * The same track run the other way, stopping as given.
	 */
	ScenarioTrack reversed(int newDwellTime) {
		return new ScenarioTrack(endNode, startNode, length, kind, stopId, newDwellTime, maxSpeed, twoWay);
	}

	@Override
	public float getMaxSpeed() {
		return maxSpeed;
	}

	@Override
	public boolean canAccelerate() {
		return kind == Kind.PLAIN;
	}

	@Override
	public boolean hasSignal() {
		return kind != Kind.SIDING;
	}

	@Override
	public boolean isTwoWay() {
		return twoWay;
	}

	@Override
	public long getStartNode() {
		return startNode;
	}

	@Override
	public long getEndNode() {
		return endNode;
	}

	@Override
	public double getTrackLength() {
		return length;
	}

	@Override
	public int getDwellTime() {
		return dwellTime;
	}

	@Override
	public boolean isPlatformTrack() {
		return kind == Kind.PLATFORM;
	}

	@Override
	public boolean isSidingTrack() {
		return kind == Kind.SIDING;
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
		return track instanceof ScenarioTrack && ((ScenarioTrack) track).startNode == endNode && ((ScenarioTrack) track).endNode == startNode;
	}

	@Override
	public UUID getTrackKey() {
		return new UUID(Math.min(startNode, endNode), Math.max(startNode, endNode));
	}
}
