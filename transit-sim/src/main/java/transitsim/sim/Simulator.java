package transitsim.sim;

import transitsim.motion.RunSegment;

import java.util.List;

/**
 * One simulation of the whole network (contracts document section 9, S4c). Real running has one; predictions run in
 * forks of it (S4d). Time only goes forward, and the host's inputs and the events coming out are ordered the same way
 * every time, so the same start and the same inputs give the same results however the host steps it (2.1).
 */
public interface Simulator {

	/**
	 * The simulation time now, which only goes forward (2.1, rule 1).
	 */
	long getTime();

	/**
	 * Hands in an input from the host, which takes effect at its time, in the order of its time and sequence (2.1, rule 5).
	 */
	void submit(HostInput input);

	/**
	 * Runs on to this time. An earlier time does nothing.
	 *
	 * @return the simulation time now
	 */
	long advanceTo(long time);

	/**
	 * What a train is doing now, without changing anything or running on (7.1, rule 1).
	 *
	 * @return null if there is no such train, or it isn't moved by the simulation
	 */
	TrainState query(long trainId);

	/**
	 * The run segment the train is on, from which its position at any time of the segment follows, or null.
	 */
	RunSegment getSegment(long trainId);

	/**
	 * @param afterCursor the cursor of the last event the host has dealt with, or 0 for none
	 * @return the events after it, up to the simulation time now, in their order (7.1, rule 3)
	 */
	List<SimEvent> events(long afterCursor);
}
