package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;
import transitsim.plan.StopPositions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * A platform's stop position changed while a train stands there (2026-11-16, the user's S2 line in 0.84.6: each change of
 * the stop position at a turn back platform started the simulator again from the game's trains, and the train standing
 * there with its doors open ran on along the platform to where the stop was now, or past the stop now behind it on
 * through the turn back without turning). Past its stop, or turning back there, the train finishes the stop where it
 * stands and turns back from there; short of a stop it goes on from, it shuts its doors before it runs on to the stop. It
 * never moves with its doors open.
 */
class StopMovedUnderTrainTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	/**
	 * Before and after: the stop moving on, back, and to and from the end of the platform. Platforms 30 long, trains 20.
	 */
	private static final int[][] CHANGES = {{StopPositions.TAIL_AT_ENTRY_END, 2}, {2, StopPositions.TAIL_AT_ENTRY_END}, {StopPositions.EXIT_END, StopPositions.CENTRED}, {StopPositions.CENTRED, StopPositions.EXIT_END}};

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void turningBackAtTheTerminal() {
		check(false);
	}

	@Test
	void turningBackInFrontOfTheTerminal() {
		check(true);
	}

	private static void check(boolean frontTurnBack) {
		for (final int[] change : CHANGES) {
			final byte[] before = dataset(change[0], frontTurnBack).toBytes();
			final byte[] after = dataset(change[1], frontTurnBack).toBytes();
			final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(before, START));
			final List<long[]> moments = new ArrayList<>();
			for (long time = START + 70 * MINUTE; time < START + 2 * HOUR && moments.size() < 16; time += 3 * SECOND) {
				runningOn.advanceTo(time);
				for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : runningOn.getRunner().getTrains()) {
					if (train.isOnRoute() && train.getSpeed() <= 0 && train.getDoorValue() > 0 && train.getPath().get(train.getNextStoppingIndex()).isPlatformTrack()) {
						moments.add(new long[]{time, train.id, train.getNextStoppingIndex()});
						break;
					}
				}
			}
			assertTrue(moments.size() >= 5, "change " + change[0] + " to " + change[1] + ": " + moments.size());
			int turns = 0;
			for (final long[] moment : moments) {
				final long time = moment[0];
				final String what = (frontTurnBack ? "front turn back, " : "") + "stop position " + change[0] + " to " + change[1] + ", train " + moment[1] + " at stop " + moment[2] + " at " + ScenarioLine.clock(time);
				final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(before, START));
				saved.advanceTo(time);
				final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
				final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(after, startMillis).get(), record, time);
				final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(resumed.getRunner());
				// Looked at every tick: the train moves no further than its speed takes it, but for the head going to where the
				// tail was as it turns back
				final List<String> jumps = new ArrayList<>();
				double lastProgress = Double.NaN;
				boolean lastReversed = false;
				float lastSpeed = 0;
				for (long tick = time; tick < time + 4 * MINUTE; tick += 50) {
					resumed.advanceTo(tick);
					invariants.check();
					final TickRunner<SimDataset.DataTrack>.HeadlessTrain train = resumed.getRunner().getTrains().stream().filter(other -> other.id == moment[1]).findFirst().orElse(null);
					if (train != null && train.isOnRoute()) {
						final double moved = train.getRailProgress() - lastProgress;
						if (!Double.isNaN(lastProgress) && train.isReversed() == lastReversed && Math.abs(moved) > Math.max(lastSpeed, train.getSpeed()) + 0.05) {
							jumps.add(ScenarioLine.clock(tick) + " moved " + moved + " at speed " + train.getSpeed());
						}
						lastProgress = train.getRailProgress();
						lastReversed = train.isReversed();
						lastSpeed = train.getSpeed();
					}
				}
				assertTrue(jumps.isEmpty(), what + ": " + jumps);
				assertEquals(0, invariants.getViolations().size(), what + ": " + String.join("\n", invariants.getViolations()) + " EVENTS " + resumed.events(0).stream().filter(event -> event.trainId == moment[1]).limit(40).map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.rail).collect(java.util.stream.Collectors.toList()));
				// It leaves the stop it stood at
				final List<SimEvent> events = resumed.events(0);
				assertTrue(events.stream().anyMatch(event -> event.trainId == moment[1] && event.kind == SimEvent.Kind.DEPART && event.rail == moment[2]), what + ": never left");
				// Turning back there, it turns back from where it stands; short of a stop it goes on from, it runs on to where the
				// stop is now, its doors shut, and stops there afresh
				final List<SimDataset.DataTrack> path = saved.getRunner().getTrains().stream().filter(train -> train.id == moment[1]).findFirst().orElseThrow(AssertionError::new).getPath();
				final int stop = (int) moment[2];
				if (stop + 1 < path.size() && path.get(stop).isOppositeOf(path.get(stop + 1))) {
					assertTrue(events.stream().noneMatch(event -> event.trainId == moment[1] && event.kind == SimEvent.Kind.ARRIVE && event.rail == moment[2]), what + ": stopped there again");
					turns++;
				}
			}
			assertTrue(turns > 0, "change " + change[0] + " to " + change[1] + ": no train stood at a turn back");
		}
	}

	/**
	 * @return the line of {@link SimDatasetTest#lines} with every platform at the stop position; with a front turn back, its
	 * trains stop at the terminal platform coming in and going back
	 */
	private static SimDataset dataset(int setting, boolean frontTurnBack) {
		final SimDataset dataset = frontTurnBack ? DoorsShutWhileMovingTest.frontTurnBack(setting) : SimDatasetTest.lines(1, 400, 2, 4, 10);
		dataset.sidings.forEach(siding -> siding.path.forEach(track -> {
			if (track.isPlatformTrack()) {
				dataset.stopPositions.put(track.getStopId(), setting);
			}
		}));
		dataset.applyStopPositions();
		return dataset;
	}
}
