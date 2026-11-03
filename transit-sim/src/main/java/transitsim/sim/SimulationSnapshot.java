package transitsim.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a simulation needs to carry on from where it was (contracts document 6.4, S4d): its time, the host's inputs in
 * their order and the cursor the host had read events to. The simulation is the same every time for the same start and
 * inputs, so running them again from the start gives the state it had, exactly.
 * <p>
 * This continues a simulation as if it never stopped. Putting trains where the timetable has them after a long stop, with
 * grants cleared (6.3), is the host's recovery, which comes with taking over the game's trains (G0).
 */
public final class SimulationSnapshot {

	public final long time;
	public final List<HostInput> inputs;
	public final long eventCursor;

	SimulationSnapshot(long time, List<HostInput> inputs, long eventCursor) {
		this.time = time;
		this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
		this.eventCursor = eventCursor;
	}
}
