package transitsim.plan;

/**
 * What a host gives to make other versions of a path on demand, as one passing some of its stops (D3): the same track with
 * no stop, and the way the host times a path.
 */
public interface PathVariants<T extends PlanTrack> {

	/**
	 * @return a copy of the track a train passes without stopping, the same in all else
	 */
	T pass(T track);

	TimeGenerator<T> getTimeGenerator();

	/**
	 * @return the platforms of the same station a train at this platform can turn back to, as path generation found ways
	 * there (D4), or none
	 */
	default java.util.Collection<Long> getTurnTargets(long platformId) {
		return java.util.Collections.emptyList();
	}

	/**
	 * @return the way from one platform round to another of its station (D4), from the first's rail to the other's, or null
	 */
	default java.util.List<T> getTurnLink(long fromPlatformId, long toPlatformId) {
		return null;
	}

	/**
	 * @return the way from a platform back to the siding, for a train turned back there going back empty (D4), or null
	 */
	default java.util.List<T> getTurnReturn(long platformId) {
		return null;
	}
}
