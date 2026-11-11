package transitsim.fare;

import java.util.Arrays;

/**
 * The fares of one route group, which is a railway company (design document 6.13): a formula, the tiers it makes, which
 * may be changed by hand, an optional cap and a fine for staying in too long. Distances are in blocks, 1000 to a
 * kilometre.
 * <p>
 * Formula: fare = start fare + ⌈max(0, distance − initial distance) ÷ unit distance⌉ × unit fare. Its tiers have a bound
 * every unit distance from the initial distance up to the longest distance; a tier runs from just over the bound before
 * it up to its own bound, and past the last bound the last tier's fare is charged.
 */
public final class FareLevels {

	public static final long BLOCKS_PER_KILOMETRE = 1000;
	/**
	 * More tiers than this are not made from the formula: the last tier is then the cap of the rest.
	 */
	public static final int MAX_TIERS = 200;

	public int startFare = 2;
	public long initialBlocks = 4000;
	public long unitBlocks = 4000;
	public int unitFare = 1;
	/**
	 * Whether the tiers were changed by hand: they are then kept as set, rather than made from the formula.
	 */
	public boolean manualTiers;
	/**
	 * The upper bound of each tier but the last, rising; with {@link #manualTiers} only.
	 */
	public long[] bounds = new long[0];
	/**
	 * The fare of each tier, one more than the bounds; with {@link #manualTiers} only.
	 */
	public int[] prices = {2};
	/**
	 * The longest distance the tiers are made up to, or 0 to use the longest fare distance between two stations of the
	 * group as worked out ({@link FareNetwork#getLongestBlocks}).
	 */
	public long maxBlocks;
	/**
	 * The most one part of a trip in this group is charged, or -1 for no cap.
	 */
	public int cap = -1;
	public boolean overtime;
	public int overtimeMinutes = 120;
	public int overtimeFine = 20;

	public FareLevels copy() {
		final FareLevels copy = new FareLevels();
		copy.startFare = startFare;
		copy.initialBlocks = initialBlocks;
		copy.unitBlocks = unitBlocks;
		copy.unitFare = unitFare;
		copy.manualTiers = manualTiers;
		copy.bounds = bounds.clone();
		copy.prices = prices.clone();
		copy.maxBlocks = maxBlocks;
		copy.cap = cap;
		copy.overtime = overtime;
		copy.overtimeMinutes = overtimeMinutes;
		copy.overtimeFine = overtimeFine;
		return copy;
	}

	/**
	 * Keeps every value in range, as fares read from a save or a packet may not be: no fare, fine, distance or time below
	 * 0 (a negative fare would pay a player for a trip), a unit distance of at least one block, a cap of -1 (none) or more
	 * and the bounds rising.
	 *
	 * @return this
	 */
	public FareLevels clamp() {
		startFare = Math.max(0, startFare);
		unitFare = Math.max(0, unitFare);
		initialBlocks = Math.max(0, initialBlocks);
		unitBlocks = Math.max(1, unitBlocks);
		maxBlocks = Math.max(0, maxBlocks);
		cap = Math.max(-1, cap);
		overtimeMinutes = Math.max(0, overtimeMinutes);
		overtimeFine = Math.max(0, overtimeFine);
		final long[] newBounds = bounds.clone();
		for (int i = 0; i < newBounds.length; i++) {
			newBounds[i] = Math.max(0, newBounds[i]);
		}
		Arrays.sort(newBounds);
		bounds = newBounds;
		final int[] newPrices = prices.clone();
		for (int i = 0; i < newPrices.length; i++) {
			newPrices[i] = Math.max(0, newPrices[i]);
		}
		prices = newPrices;
		return this;
	}

	/**
	 * @param longestBlocks the longest distance to make tiers up to when {@link #maxBlocks} is 0
	 * @return the tiers as {bounds, prices}: made from the formula, or as set by hand
	 */
	public Tiers getTiers(long longestBlocks) {
		if (manualTiers && prices.length == bounds.length + 1 && prices.length > 0) {
			return new Tiers(bounds.clone(), prices.clone());
		}
		return formulaTiers(maxBlocks > 0 ? maxBlocks : longestBlocks);
	}

	/**
	 * @return whether the fares depend on the longest distance worked out ({@link FareNetwork#getLongestBlocks}): only tiers
	 * made from the formula with no longest distance set do, so it need not be worked out otherwise
	 */
	public boolean needsLongest() {
		return !(manualTiers && prices.length == bounds.length + 1 && prices.length > 0) && maxBlocks <= 0;
	}

	/**
	 * @return the tiers the formula makes up to that distance
	 */
	public Tiers formulaTiers(long longestBlocks) {
		final long unit = Math.max(1, unitBlocks);
		final long initial = Math.max(0, initialBlocks);
		final int count = longestBlocks <= initial ? 0 : (int) Math.min(MAX_TIERS - 1, (longestBlocks - initial + unit - 1) / unit);
		final long[] newBounds = new long[count];
		final int[] newPrices = new int[count + 1];
		for (int i = 0; i <= count; i++) {
			newPrices[i] = startFare + i * unitFare;
			if (i < count) {
				newBounds[i] = initial + i * unit;
			}
		}
		return new Tiers(newBounds, newPrices);
	}

	/**
	 * Keeps the tiers given as set by hand.
	 */
	public void setTiers(long[] newBounds, int[] newPrices) {
		if (newPrices.length != newBounds.length + 1) {
			throw new IllegalArgumentException("a tier more than bounds");
		}
		final long[] sortedBounds = newBounds.clone();
		Arrays.sort(sortedBounds);
		bounds = sortedBounds;
		prices = newPrices.clone();
		manualTiers = true;
	}

	/**
	 * @return the fare of one part of a trip in this group, capped
	 */
	public int getFare(long blocks, long longestBlocks) {
		final Tiers tiers = getTiers(longestBlocks);
		int tier = 0;
		while (tier < tiers.bounds.length && blocks > tiers.bounds[tier]) {
			tier++;
		}
		final int fare = tiers.prices[tier];
		return cap >= 0 ? Math.min(cap, fare) : fare;
	}

	/**
	 * @return what entering and leaving at the same station costs, or the fine if it was for longer than allowed
	 */
	public int getSameStationFare(long minutesInside) {
		if (overtime && minutesInside > overtimeMinutes) {
			return overtimeFine;
		}
		final int fare = manualTiers && prices.length > 0 ? prices[0] : startFare;
		return cap >= 0 ? Math.min(cap, fare) : fare;
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof FareLevels)) {
			return false;
		}
		// The tiers kept count only when set by hand: otherwise they are not saved or sent, so they don't make two equal
		final FareLevels other = (FareLevels) object;
		return startFare == other.startFare && initialBlocks == other.initialBlocks && unitBlocks == other.unitBlocks && unitFare == other.unitFare && manualTiers == other.manualTiers && (!manualTiers || Arrays.equals(bounds, other.bounds) && Arrays.equals(prices, other.prices)) && maxBlocks == other.maxBlocks && cap == other.cap && overtime == other.overtime && overtimeMinutes == other.overtimeMinutes && overtimeFine == other.overtimeFine;
	}

	@Override
	public int hashCode() {
		return ((((startFare * 31 + Long.hashCode(initialBlocks)) * 31 + Long.hashCode(unitBlocks)) * 31 + unitFare) * 31 + (manualTiers ? Arrays.hashCode(bounds) : 0)) * 31 + (manualTiers ? Arrays.hashCode(prices) : 0) + Long.hashCode(maxBlocks) * 7 + cap * 13 + (overtime ? overtimeMinutes * 17 + overtimeFine : 0) + (manualTiers ? 1 : 0);
	}

	public static final class Tiers {

		public final long[] bounds;
		public final int[] prices;

		public Tiers(long[] bounds, int[] prices) {
			this.bounds = bounds;
			this.prices = prices;
		}
	}
}
