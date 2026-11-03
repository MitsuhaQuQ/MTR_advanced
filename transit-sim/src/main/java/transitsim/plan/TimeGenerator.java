package transitsim.plan;

import java.util.List;

/**
 * Works out how a train runs along a path at the fastest it can, which depends on the host's train settings.
 */
public interface TimeGenerator<T extends PlanTrack> {

	StopTimes calculateStopTimes(List<T> path);

	/**
	 * @param joinIndex   the first rail where trains join a loop part of the way round, or 0
	 * @param lapEndIndex the rail where a lap ends, or 0
	 */
	void generateTimeSegments(List<T> path, List<TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex);
}
