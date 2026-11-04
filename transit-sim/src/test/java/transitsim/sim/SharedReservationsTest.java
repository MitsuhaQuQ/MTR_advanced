package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionConstants;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Run reservations shared with the host's own trains (G1), on the line of {@link RestoreTest#byDuties} whose trains run
 * by duties: the host deals with the requests once its tick is over and has the runner renew what its trains hold in
 * every tick of its own, whether the runner ticked in it or not.
 */
class SharedReservationsTest {

	private static final long START = MONDAY + 5 * HOUR + 50 * MINUTE;
	private static final long END = MONDAY + 7 * HOUR;
	private static final long TICK = MotionConstants.MILLIS_PER_TICK;
	/**
	 * A train of the host's, holding the far end of the line the other way.
	 */
	private static final long HOST_TRAIN = 77;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void sharedReservationsRunTheSameAsTheRunnersOwn() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final EventSimulator<SimDataset.DataTrack> own = new EventSimulator<>(SimDataset.start(bytes, START), false);
		for (long time = START + TICK; time <= END; time += TICK) {
			own.advanceTo(time);
		}
		final List<String> expected = describe(own.events(0));
		assertTrue(expected.stream().anyMatch(event -> event.contains("ARRIVE")), expected.toString());
		assertEquals(expected, describe(runShared(bytes, false, null).events(0)));
	}

	@Test
	void hostTicksWithoutARunnerTickKeepWhatTrainsHold() {
		// Every other tick of the host the runner doesn't tick, as when the host's clock runs a little ahead
		final List<String> events = describe(runShared(RestoreTest.byDuties().toBytes(), true, null).events(0));
		assertTrue(events.stream().anyMatch(event -> event.contains("ARRIVE")), events.toString());
		assertTrue(events.stream().noneMatch(event -> event.contains("DEADLOCK")), events.toString());
	}

	@Test
	void aHostTrainsRunKeepsTheRunnersTrainsOff() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final List<String> free = arrivals(runShared(bytes, false, null), 6);
		assertFalse(free.isEmpty());
		// The host's train holds the far end of the line the other way until 06:30
		final List<String> held = arrivals(runShared(bytes, false, MONDAY + 6 * HOUR + 30 * MINUTE), 6);
		assertFalse(held.isEmpty());
		assertTrue(held.stream().allMatch(time -> time.compareTo(ScenarioLine.clock(MONDAY + 6 * HOUR + 30 * MINUTE)) >= 0), held.toString());
	}

	/**
	 * Runs the line a tick at a time with reservations the host resolves after each of its ticks.
	 *
	 * @param skipEveryOther whether the runner leaves out every other tick of the host
	 * @param hostHoldsUntil until when the host's own train holds the far end the other way, or null
	 */
	private static EventSimulator<SimDataset.DataTrack> runShared(byte[] bytes, boolean skipEveryOther, Long hostHoldsUntil) {
		final RunReservations shared = new RunReservations();
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(() -> {
			final TickRunner<SimDataset.DataTrack> runner = SimDataset.fromBytes(bytes).buildRunner(START);
			runner.shareReservations(shared);
			return runner;
		}, false);
		final List<RunReservations.Move> hostRun = Collections.singletonList(new RunReservations.Move(SignalBlockTest.rail(6, 7), false));
		long runnerTime = START;
		int hostTick = 0;
		for (long time = START + TICK; time <= END; time += TICK) {
			if (!skipEveryOther || hostTick++ % 2 == 1) {
				// Catching up the ticks it left out
				simulator.advanceTo(time);
				runnerTime = time;
			}
			if (hostHoldsUntil != null && time < hostHoldsUntil) {
				shared.hold(HOST_TRAIN, hostRun);
			}
			simulator.getRunner().renewReservations();
			shared.resolve(runnerTime);
		}
		return simulator;
	}

	private static List<String> arrivals(EventSimulator<SimDataset.DataTrack> simulator, int rail) {
		final List<String> times = new ArrayList<>();
		simulator.events(0).forEach(event -> {
			if (event.kind == SimEvent.Kind.ARRIVE && event.rail == rail) {
				times.add(ScenarioLine.clock(event.time));
			}
		});
		return times;
	}

	private static List<String> describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}
}
