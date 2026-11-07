package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.*;

/**
 * The end-to-end check's rules find what they are for, and nothing in a plain morning's run.
 */
class RunInvariantsTest {

	private static List<TickRunner<ScenarioTrack>.HeadlessTrain> onLine(ScenarioLine line) {
		return line.runner.getTrains().stream().filter(TickRunner.HeadlessTrain::isOnRoute).filter(train -> !train.getPath().get(train.getHeadRail()).isSidingTrack()).collect(Collectors.toList());
	}

	/**
	 * Runs until at least two trains are out on the line.
	 */
	private static List<TickRunner<ScenarioTrack>.HeadlessTrain> twoOnLine(ScenarioLine line) {
		for (long millis = MONDAY + 6 * HOUR; millis < MONDAY + 9 * HOUR; millis += 20 * SECOND) {
			line.runner.runUntil(millis);
			final List<TickRunner<ScenarioTrack>.HeadlessTrain> trains = onLine(line);
			if (trains.size() >= 2) {
				return trains;
			}
		}
		throw new AssertionError("never two trains on the line");
	}

	@Test
	void aPlainMorningBreaksNothing() {
		final ScenarioLine line = new ScenarioLine(400, 4, 4);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		line.runner.setAfterTick(invariants::check);
		line.run(MONDAY + 8 * HOUR + 40 * MINUTE);
		assertTrue(invariants.getChecks() > 1000);
		assertEquals(0, invariants.getViolations().size(), String.join("\n", invariants.getViolations()));
		assertTrue(invariants.getStuck().isEmpty(), invariants.getStuck().toString());
	}

	@Test
	void aJumpIsFound() {
		final ScenarioLine line = new ScenarioLine(400, 4, 4);
		line.run(MONDAY + 6 * HOUR + 12 * MINUTE);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		invariants.check();
		final TickRunner<ScenarioTrack>.HeadlessTrain train = onLine(line).get(0);
		train.setRailProgress(train.getRailProgress() + 300);
		invariants.check();
		assertTrue(invariants.getViolations().stream().anyMatch(text -> text.contains("train " + train.id + " jumped")), invariants.getViolations().toString());
	}

	@Test
	void anOverlapIsFound() {
		final ScenarioLine line = new ScenarioLine(6000, 6, 6);
		final List<TickRunner<ScenarioTrack>.HeadlessTrain> trains = twoOnLine(line);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		trains.get(1).setRailProgress(trains.get(0).getRailProgress());
		invariants.check();
		assertTrue(invariants.getViolations().stream().anyMatch(text -> text.contains("overlaps")), invariants.getViolations().toString());
	}

	@Test
	void aTrainTakenOffTheLineIsFoundUnlessExpected() {
		final ScenarioLine line = new ScenarioLine(6000, 6, 6);
		final List<TickRunner<ScenarioTrack>.HeadlessTrain> trains = twoOnLine(line);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		invariants.check();
		line.runner.removeTrain(trains.get(0).id);
		invariants.expectRemoval(trains.get(1).id);
		line.runner.removeTrain(trains.get(1).id);
		invariants.check();
		assertEquals(1, invariants.getViolations().size(), invariants.getViolations().toString());
		assertTrue(invariants.getViolations().get(0).contains("train " + trains.get(0).id + " vanished"));
	}
}
