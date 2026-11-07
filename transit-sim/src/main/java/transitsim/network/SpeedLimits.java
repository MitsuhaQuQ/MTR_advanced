package transitsim.network;

/**
 * The two speeds a speed label stands for (optimisation document 5.6, 5.15): the physical limit a train may never pass,
 * and the speed timetables are planned at, below it, so a late train has room to make time up.
 * <p>
 * Labels of 60 km/h and over keep their value as the planned speed and allow 5 km/h more; labels under 60 keep their
 * value as the limit and are planned 5 km/h lower. The same rule holds for rails, line speed limits and vehicles.
 */
public final class SpeedLimits {

	/**
	 * Labels from this one up are planned at their own value.
	 */
	public static final int BOUNDARY_KMH = 60;
	/**
	 * The room between the physical limit and the planned speed.
	 */
	public static final int MARGIN_KMH = 5;

	private SpeedLimits() {
	}

	/**
	 * @return the speed no train may pass where this label is set, in km/h
	 */
	public static int physicalKmh(int labelKmh) {
		return labelKmh >= BOUNDARY_KMH ? labelKmh + MARGIN_KMH : labelKmh;
	}

	/**
	 * @return the speed timetables are planned at where this label is set, in km/h, at least 1
	 */
	public static int planKmh(int labelKmh) {
		return labelKmh >= BOUNDARY_KMH ? labelKmh : Math.max(1, labelKmh - MARGIN_KMH);
	}

	/**
	 * @param blocksPerTick a speed label as blocks per tick, as rail types and settings keep it, or 0 or less for none
	 * @return the physical limit it stands for, in blocks per tick; none stays none
	 */
	public static float physicalBlocksPerTick(float blocksPerTick) {
		final int kmh = toKmh(blocksPerTick);
		return kmh <= 0 ? blocksPerTick : toBlocksPerTick(physicalKmh(kmh));
	}

	/**
	 * @return the speed planned at for a label given as blocks per tick; none stays none
	 */
	public static float planBlocksPerTick(float blocksPerTick) {
		final int kmh = toKmh(blocksPerTick);
		return kmh <= 0 ? blocksPerTick : toBlocksPerTick(planKmh(kmh));
	}

	/**
	 * @return a speed in blocks per tick as the km/h it was set as, rounded
	 */
	public static int toKmh(float blocksPerTick) {
		return blocksPerTick <= 0 ? 0 : Math.round(blocksPerTick * 72);
	}

	/**
	 * @return a speed in km/h as blocks per tick
	 */
	public static float toBlocksPerTick(int kmh) {
		return kmh / 3.6F / 20;
	}
}
