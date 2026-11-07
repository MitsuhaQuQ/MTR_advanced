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
