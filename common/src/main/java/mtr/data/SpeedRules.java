package mtr.data;

import transitsim.network.SpeedLimits;

/**
 * The two speeds a speed label stands for in the game ({@link SpeedLimits}, optimisation document 5.6): trains run up to
 * the physical limit and timetables are planned at the planned speed. Cable cars and aircraft keep their labels as they
 * are (optimisation document 5.12).
 */
public final class SpeedRules {

	private SpeedRules() {
	}

	/**
	 * The speed label of a siding, in km/h, which trains run on by its own limit (optimisation round 3b, the user's value).
	 */
	public static final int SIDING_KMH = 25;
	/**
	 * The speed label of a turn back rail, in km/h.
	 */
	public static final int TURN_BACK_KMH = 65;

	/**
	 * @return the speed label of a rail type in km/h: sidings and turn back rails have their own (round 3b), other rails
	 * the type's; for modes the two speeds don't apply to, always the type's
	 */
	public static int labelKmh(RailType railType, TransportMode transportMode) {
		return labelKmh(railType, transportMode, 0, 0);
	}

	/**
	 * @param node1 a node of the rail, for a platform's own pass speed ({@link PlatformSpeeds}), or 0
	 * @return the speed label of a rail in km/h
	 */
	public static int labelKmh(RailType railType, TransportMode transportMode, long node1, long node2) {
		if (applies(transportMode)) {
			if (railType == RailType.PLATFORM && node1 != node2) {
				return PlatformSpeeds.getKmh(node1, node2);
			} else if (railType == RailType.SIDING) {
				return SIDING_KMH;
			} else if (railType == RailType.TURN_BACK) {
				return TURN_BACK_KMH;
			}
		}
		return railType.speedLimit;
	}

	/**
	 * @return the speed label of a rail type in blocks per tick
	 */
	public static float label(RailType railType, TransportMode transportMode) {
		return label(railType, transportMode, 0, 0);
	}

	/**
	 * @return the speed label of a rail in blocks per tick, a platform's its own pass speed
	 */
	public static float label(RailType railType, TransportMode transportMode, long node1, long node2) {
		final int kmh = labelKmh(railType, transportMode, node1, node2);
		return kmh == railType.speedLimit ? railType.maxBlocksPerTick : SpeedLimits.toBlocksPerTick(kmh);
	}

	/**
	 * @return whether a rail of the type keeps the speed limit of the rail before it, as platforms, sidings and turn back
	 * rails did; since round 3b only for modes the two speeds don't apply to. Signal blocks still never cut them
	 * ({@link RailType#canAccelerate}).
	 */
	public static boolean keepsLimitBefore(RailType railType, TransportMode transportMode) {
		return !railType.canAccelerate && !applies(transportMode);
	}

	/**
	 * @return whether the two speeds apply to the transport mode
	 */
	public static boolean applies(TransportMode transportMode) {
		return !transportMode.continuousMovement && transportMode != TransportMode.AIRPLANE;
	}

	/**
	 * @param blocksPerTick a speed label in blocks per tick, or 0 for none
	 * @return how fast a train may run where it is set
	 */
	public static float run(float blocksPerTick, TransportMode transportMode) {
		return applies(transportMode) ? SpeedLimits.physicalBlocksPerTick(blocksPerTick) : blocksPerTick;
	}

	/**
	 * @return the speed timetables are planned at where it is set
	 */
	public static float plan(float blocksPerTick, TransportMode transportMode) {
		return applies(transportMode) ? SpeedLimits.planBlocksPerTick(blocksPerTick) : blocksPerTick;
	}
}
