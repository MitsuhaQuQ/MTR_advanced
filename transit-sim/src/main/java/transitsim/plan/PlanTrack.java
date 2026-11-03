package transitsim.plan;

import java.util.UUID;

/**
 * One track of a path as the plans of trips see it. The host's path entries implement it.
 */
public interface PlanTrack {

	double getTrackLength();

	/**
	 * @return the time a train stops at the end of this track, in half seconds, or 0 if it doesn't stop
	 */
	int getDwellTime();

	/**
	 * @return true if this track is a platform, as opposed to a siding or plain track
	 */
	boolean isPlatformTrack();

	/**
	 * @return the platform or siding this track belongs to, or 0
	 */
	long getStopId();

	/**
	 * @return how many stops come before this track on the path
	 */
	int getStopIndex();

	void setStopIndex(int stopIndex);

	/**
	 * @return true if the other track is this one run the other way, where a train turns back
	 */
	boolean isOppositeOf(PlanTrack track);

	/**
	 * @return a key that is the same for both directions of this track
	 */
	UUID getTrackKey();
}
