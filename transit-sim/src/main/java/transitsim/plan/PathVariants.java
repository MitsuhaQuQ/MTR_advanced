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
	 * @return a copy of the track a train stops at the end of for this long, in half seconds, as a stop at this platform,
	 * the same in all else; or null if the host can't make one (D5)
	 */
	default T withStop(T track, int dwellTime, long platformId) {
		return null;
	}

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

	/**
	 * @return the way from the siding out to a platform where a segment of its duties starts, for a train taking over the
	 * rest of a duty or sent out late (D5), or null
	 */
	default java.util.List<T> getWayOut(long platformId) {
		return null;
	}

	/**
	 * @return the other ways round the turn back points of the siding's duties, from which a duty's path taking one of them
	 * is made when a train needs it (0.84.0, {@link TripPath#withTurnBackWay}), or null for none
	 */
	default TurnBackWayStore<T> getTurnBackWays() {
		return null;
	}

	/**
	 * Has the host keep a duty's path made here as it keeps the paths it makes itself: its stops numbered its way, its
	 * rails kept in the pieces it saves. Nothing by default.
	 *
	 * @param dutyStarts the rail of the first stop of each segment
	 * @param boundaries where the host's pieces of the path end
	 */
	default java.util.List<T> indexDuty(java.util.List<T> path, int[] dutyStarts, int[] boundaries) {
		return path;
	}
}
