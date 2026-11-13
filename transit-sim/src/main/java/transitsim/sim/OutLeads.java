package transitsim.sim;

import java.util.*;

/**
 * Corrects how early a depot sends its duties' trains out by how late they reach their first stop (user 2026-11-13:
 * record the actual runs and correct the lead by them, by the lateness at the first stop, and stop where it doesn't
 * help). Each way out, a siding to a first platform ({@link transitsim.plan.Buffers#key} of kind OUT), collects how late
 * its trains arrived; every {@link #RUNS} runs the median decides. Late by less than
 * {@link BufferRecorder#TOLERANCE_MILLIS}: nothing changes. Late by more: the time adopted for that way out is raised by
 * as much, so the trains leave that much earlier. If the next runs are still late by more than half of that raise less
 * than before, leaving earlier didn't help (the trains wait on the way for something that goes by the clock, such as a
 * service train ahead on the turn back track): the raise is taken back, the way out is marked
 * {@link #getIneffective ineffective} with how late it still is, and nothing more is raised for it until {@link #clear}.
 * Plain Java, no host; both hosts feed it from their {@link BufferRecorder}.
 */
public final class OutLeads {

	public static final int RUNS = 3;

	private final Map<String, State> states = new TreeMap<>();

	/**
	 * @param key            the way out ({@link transitsim.plan.Buffers#key})
	 * @param latenessMillis how late a train of it arrived at its first stop, negative if early
	 * @param adoptedMillis  the time adopted for it now
	 * @return the time to adopt for it now, or -1 to leave it as it is
	 */
	public synchronized long add(String key, long latenessMillis, long adoptedMillis) {
		final State state = states.computeIfAbsent(key, newKey -> new State());
		if (state.ineffective >= 0) {
			return -1;
		}
		state.runs.add(latenessMillis);
		if (state.runs.size() < RUNS) {
			return -1;
		}
		final List<Long> sorted = new ArrayList<>(state.runs);
		state.runs.clear();
		Collections.sort(sorted);
		final long lateness = sorted.get(sorted.size() / 2);
		final long lastRaise = state.lastRaise;
		state.lastRaise = 0;
		if (lateness < BufferRecorder.TOLERANCE_MILLIS) {
			return -1;
		}
		if (lastRaise > 0 && lateness > state.lastLateness - lastRaise / 2) {
			state.ineffective = lateness;
			return Math.max(0, adoptedMillis - lastRaise);
		}
		final long raise = Math.min(lateness, BufferRecorder.MAX_EXCESS_MILLIS - adoptedMillis);
		if (raise <= 0) {
			return -1;
		}
		state.lastLateness = lateness;
		state.lastRaise = raise;
		return adoptedMillis + raise;
	}

	/**
	 * @return the ways out leaving earlier didn't help, with how late their trains still were
	 */
	public synchronized Map<String, Long> getIneffective() {
		final Map<String, Long> ineffective = new TreeMap<>();
		states.forEach((key, state) -> {
			if (state.ineffective >= 0) {
				ineffective.put(key, state.ineffective);
			}
		});
		return ineffective;
	}

	/**
	 * Forgets everything, as when a player clears what was measured: ways out found ineffective are tried again.
	 */
	public synchronized void clear() {
		states.clear();
	}

	private static final class State {
		private final List<Long> runs = new ArrayList<>();
		private long lastLateness;
		private long lastRaise;
		private long ineffective = -1;
	}
}
