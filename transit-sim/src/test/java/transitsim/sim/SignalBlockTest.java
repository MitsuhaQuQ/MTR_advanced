package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Signal blocks spanning several rails (G1), on a long line of {@link SimDatasetTest#lines} with a service every three
 * minutes: a block from the first platform to the far end lets only one train in at a time, as the game's signals do.
 */
class SignalBlockTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long END = MONDAY + 6 * HOUR + 30 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * @param withBlock whether the up line from the first platform to the far end is one signal block
	 */
	static SimDataset line(boolean withBlock) {
		final SimDataset dataset = SimDatasetTest.lines(1, 2400, 2, 8, 3);
		if (withBlock) {
			dataset.signalBlocks.add(Arrays.asList(rail(2, 3), rail(3, 4), rail(4, 5), rail(5, 6)));
		}
		return dataset;
	}

	@Test
	void onlyOneTrainIsInABlockAtATime() {
		assertTrue(mostTrainsInBlock(line(false)) >= 2);
		assertEquals(1, mostTrainsInBlock(line(true)));
	}

	@Test
	void aTrainInABlockHasAllItsRails() {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(line(true).toBytes(), START));
		for (long time = START; time < END; time += 2 * SECOND) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				final int head = train.getHeadRail();
				if (train.isOnRoute() && head >= 3 && head <= 5) {
					// Ahead of it on the way the next train would come in, the platform the block starts at counts as taken
					assertTrue(simulator.getRunner().getOccupant(rail(2, 3)) == train.id, ScenarioLine.clock(time) + " train " + train.id);
				}
			}
		}
	}

	@Test
	void blocksGiveTheSameWhetherTicksAreSkippedOrNot() {
		final byte[] bytes = line(true).toBytes();
		final EventSimulator<SimDataset.DataTrack> everyTick = new EventSimulator<>(SimDataset.start(bytes, START), false);
		final EventSimulator<SimDataset.DataTrack> skipping = new EventSimulator<>(SimDataset.start(bytes, START), true);
		everyTick.advanceTo(END);
		skipping.advanceTo(END);
		final List<String> expected = describe(everyTick.events(0));
		assertTrue(expected.stream().anyMatch(event -> event.contains("ARRIVE")), expected.toString());
		assertEquals(expected, describe(skipping.events(0)));
	}

	@Test
	void hostIdsAreUsedAndNeverRepeated() {
		final TickRunner<SimDataset.DataTrack> runner = SimDataset.start(line(false).toBytes(), START).get();
		final long[] next = {0};
		// Hands out each ID twice, so the runner has to ask again
		runner.setTrainIds(() -> 1000 + next[0]++ / 2);
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(() -> runner);
		simulator.advanceTo(START + HOUR);
		final List<Long> ids = simulator.getRunner().getTrains().stream().map(train -> train.id).collect(Collectors.toList());
		assertTrue(ids.size() > 2, ids.toString());
		assertEquals(ids.size(), new HashSet<>(ids).size());
		assertTrue(ids.stream().allMatch(id -> id >= 1000), ids.toString());
	}

	private static int mostTrainsInBlock(SimDataset dataset) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(dataset.toBytes(), START));
		int most = 0;
		for (long time = START; time < END; time += 2 * SECOND) {
			simulator.advanceTo(time);
			final Set<Long> inBlock = simulator.getRunner().getTrains().stream().filter(train -> train.isOnRoute() && train.getHeadRail() >= 3 && train.getHeadRail() <= 5).map(train -> train.id).collect(Collectors.toSet());
			most = Math.max(most, inBlock.size());
		}
		return most;
	}

	static UUID rail(long from, long to) {
		return SimDatasetTest.plain(from, to, 1).getTrackKey();
	}

	private static List<String> describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}
}
