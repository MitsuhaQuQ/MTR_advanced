package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;
import transitsim.plan.TripPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Trains of a depot running by duties told to hand their duties over to spares (D5), in the component's simulator: they
 * run to the end of the up route and go back empty, and spares drive out of the siding for the down route. No train
 * appears on the line or vanishes from it.
 */
class HandOverTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long TOLD_AT = START + 90 * MINUTE;
	private static final long END = START + 150 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * The line of {@link RestoreTest#byDuties()}, with a way out of the siding to the down platform of the far terminal
	 * (rail 7 of the siding's path), and a way back from the up one (rail 6), as path generation would find them.
	 *
	 * @param crossingUp whether the way out runs along the up route's middle platform (rail 4), where late trains still run
	 */
	static byte[] withWays(boolean crossingUp) {
		final SimDataset dataset = RestoreTest.byDuties();
		final SimDataset.SidingData siding = dataset.sidings.get(0);
		final List<SimDataset.DataTrack> path = siding.path;
		final SimDataset.DataTrack out = path.get(0);
		final SimDataset.DataTrack in = path.get(path.size() - 1);
		final SimDataset.DataTrack up = path.get(6);
		final SimDataset.DataTrack down = path.get(7);
		final List<SimDataset.DataTrack> wayOut = new ArrayList<>();
		wayOut.add(out);
		if (crossingUp) {
			wayOut.add(SimDatasetTest.plain(out.getEndNode(), path.get(4).getStartNode(), 200));
			wayOut.add(path.get(4));
			wayOut.add(SimDatasetTest.plain(path.get(4).getEndNode(), down.getStartNode(), 200));
		} else {
			wayOut.add(SimDatasetTest.plain(out.getEndNode(), 50, 300));
			wayOut.add(SimDatasetTest.plain(50, down.getStartNode(), 50));
		}
		wayOut.add(down);
		siding.wayOuts.put(down.getStopId(), wayOut);
		siding.turnReturns.put(up.getStopId(), Arrays.asList(up, SimDatasetTest.plain(up.getEndNode(), 51, 50), SimDatasetTest.plain(51, in.getStartNode(), 300), in));
		return dataset.toBytes();
	}

	private static EventSimulator<SimDataset.DataTrack> start(byte[] bytes) {
		return new EventSimulator<>(SimDataset.start(bytes, START));
	}

	private static void tellAll(EventSimulator<SimDataset.DataTrack> simulator, long time) {
		final List<Long> trainIds = new ArrayList<>();
		simulator.getRunner().getTrains().forEach(train -> trainIds.add(train.id));
		trainIds.sort(Long::compare);
		for (int i = 0; i < trainIds.size(); i++) {
			simulator.submit(new HostInput.HandOver(time, 1 + i, trainIds.get(i), true));
		}
	}

	@Test
	void trainsHandOverAndSparesDriveOutOfTheSiding() {
		final EventSimulator<SimDataset.DataTrack> simulator = start(withWays(false));
		simulator.advanceTo(TOLD_AT - 1);
		tellAll(simulator, TOLD_AT);
		boolean handedOver = false;
		boolean spareOut = false;
		final Map<Long, Boolean> wasOnRoute = new HashMap<>();
		for (long time = TOLD_AT; time < END; time += 1000) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				handedOver |= train.service.getTakeoverState() == 2;
				spareOut |= train.isOnRoute() && TripPath.isFromKey(train.service.getTripPathKey());
				// A train setting out is in the siding, not anywhere along the line
				if (train.isOnRoute() && !wasOnRoute.getOrDefault(train.id, true)) {
					assertTrue(train.getRailProgress() < 150, "train " + train.id + " came out at " + train.getRailProgress() + " on " + train.service.getTripPathKey());
				}
				wasOnRoute.put(train.id, train.isOnRoute());
			}
		}
		assertTrue(handedOver, "trains hand their duties over");
		assertTrue(spareOut, "and spares drive out for the rest");
	}

	@Test
	void noSpareDrivesOutAlongWhereTheLateTrainsStillRun() {
		final EventSimulator<SimDataset.DataTrack> simulator = start(withWays(true));
		simulator.advanceTo(TOLD_AT - 1);
		tellAll(simulator, TOLD_AT);
		boolean crosses = false;
		final java.util.Set<Long> handedOver = new java.util.HashSet<>();
		for (long time = TOLD_AT; time < TOLD_AT + 60 * MINUTE; time += 1000) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				crosses |= train.service.getTakeoverState() == TrainService.TAKEOVER_CROSSES;
				// Only from the far terminal (rail 6), once the middle platform the way out runs along is behind it
				if (train.service.getTakeoverState() == 2 && handedOver.add(train.id)) {
					assertTrue(train.getNextStoppingIndex() >= 6, "handed over at " + train.getNextStoppingIndex());
				}
			}
		}
		assertTrue(crosses, "short of it, the trains say why they can't");
	}

	@Test
	void carryingOnFromARecordWhileHandingOverRunsAsRunningOnDoes() {
		final byte[] bytes = withWays(false);
		final EventSimulator<SimDataset.DataTrack> runningOn = start(bytes);
		runningOn.advanceTo(TOLD_AT - 1);
		tellAll(runningOn, TOLD_AT);
		runningOn.advanceTo(END);
		final List<String> expected = describe(runningOn.events(0));
		for (long time = TOLD_AT + 7 * MINUTE + 350; time < END - 5 * MINUTE; time += 9 * MINUTE + 1250) {
			final EventSimulator<SimDataset.DataTrack> saved = start(bytes);
			saved.advanceTo(TOLD_AT - 1);
			tellAll(saved, TOLD_AT);
			saved.advanceTo(time);
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
			resumed.advanceTo(END);
			final long from = time;
			final List<String> expectedAfter = new ArrayList<>();
			expected.forEach(line -> {
				if (Long.parseLong(line.split(" ")[0]) > from) {
					expectedAfter.add(line);
				}
			});
			final List<String> actual = new ArrayList<>();
			describe(resumed.events(0)).forEach(line -> {
				if (Long.parseLong(line.split(" ")[0]) > from) {
					actual.add(line);
				}
			});
			assertEquals(expectedAfter, actual, "saved at " + ScenarioLine.clock(time));
		}
	}

	private static List<String> describe(List<SimEvent> events) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED).forEach(event -> lines.add(event.time + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
