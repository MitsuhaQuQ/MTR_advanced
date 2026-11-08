package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedCurves;
import transitsim.motion.TrackView;
import transitsim.plan.StopPositions;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Where trains stop on a platform (optimisation round 4, stop points): short of the exit end by the platform's stop
 * offset, and turning back from there with the head where the tail was.
 */
class StopPositionsTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void offsetsKeepTheTrainOnThePlatform() {
		assertEquals(0, StopPositions.getOffset(StopPositions.EXIT_END, 30, 20), 1E-9);
		assertEquals(5, StopPositions.getOffset(StopPositions.CENTRED, 30, 20), 1E-9);
		assertEquals(7, StopPositions.getOffset(7, 30, 20), 1E-9);
		// Never so far back that the tail leaves the platform
		assertEquals(10, StopPositions.getOffset(12, 30, 20), 1E-9);
		// A train as long as the platform or longer stops at the exit end
		assertEquals(0, StopPositions.getOffset(StopPositions.CENTRED, 30, 40), 1E-9);
		assertEquals(0, StopPositions.getOffset(5, 30, 40), 1E-9);
	}

	@Test
	void speedCurvesStopShortAndTurnBackFromWhereTheTrainStands() {
		final double[] lengths = {20, 100, 30, 30, 100};
		final double[] limits = {0.5, 1, -1, -1, 1};
		final boolean[] stops = {false, false, true, false, true};
		final boolean[] reversals = {false, false, true, false, false};
		final double[] offsets = {0, 0, 5, 0, 0};
		final SpeedCurves curves = SpeedCurves.of(new TrackView(lengths, limits, stops, reversals, offsets, 0.2), 10, 20, 0.01, 0.02);
		assertEquals(2, curves.runs.size());
		// The first run stops 5 short of the end of the platform at 150
		assertEquals(145, curves.runs.get(0).fastest.endPosition, 1E-9);
		// Turning back, the head goes to where the tail was: 125 on the platform, which is 175 along the path back
		assertEquals(175, curves.runs.get(1).fastest.startPosition, 1E-9);
	}

	@Test
	void trainsStandWhereThePlatformHasThem() {
		final SimDataset dataset = centred(SimDatasetTest.lines(1, 400, 2, 4, 10));
		final SimDataset roundTrip = SimDataset.fromBytes(dataset.toBytes());
		assertEquals(dataset.stopPositions, roundTrip.stopPositions);
		final List<SimDataset.DataTrack> path = roundTrip.sidings.get(0).path;
		assertEquals(5, path.get(2).getStopOffset(20), 1E-9);

		final double[] ends = new double[path.size()];
		double distance = 0;
		for (int i = 0; i < path.size(); i++) {
			distance += path.get(i).getTrackLength();
			ends[i] = distance;
		}
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(dataset.toBytes(), START));
		int standing = 0;
		for (long time = START + 70 * MINUTE; time < START + 3 * HOUR; time += 7 * 1000 + 350) {
			simulator.advanceTo(time);
			for (final RunRecord.TrainRecord train : simulator.getRunner().record().trains) {
				final int stop = train.nextStoppingIndex;
				if (train.onRoute && train.speed <= 0 && train.elapsedDwellTicks > 0 && stop > 0 && stop < path.size() && path.get(stop).getDwellTime() > 0 && path.get(stop).isPlatformTrack()) {
					// Standing at a platform: 5 short of its end, the 20 long train in the middle of the 30 long platform
					assertEquals(ends[stop] - 5, train.railProgress, 1E-6, "train " + train.trainId + " at rail " + stop);
					standing++;
				}
			}
		}
		assertTrue(standing > 20, String.valueOf(standing));
	}

	@Test
	void trainsDoTheSameThingsWithTheirStopsMoved() {
		final long end = START + 3 * HOUR;
		final EventSimulator<SimDataset.DataTrack> atEnd = new EventSimulator<>(SimDataset.start(SimDatasetTest.lines(1, 400, 2, 4, 10).toBytes(), START));
		final EventSimulator<SimDataset.DataTrack> centred = new EventSimulator<>(SimDataset.start(centred(SimDatasetTest.lines(1, 400, 2, 4, 10)).toBytes(), START));
		atEnd.advanceTo(end);
		centred.advanceTo(end);
		// Each train arrives, leaves and passes the same stops in the same order: none is taken twice or missed
		assertEquals(byTrain(atEnd.events(0)), byTrain(centred.events(0)));
		// Stopping 5 short of the end, trains arrive earlier
		assertNotEquals(times(atEnd.events(0)), times(centred.events(0)));
	}

	@Test
	void skippingTicksGivesTheSameResultWithStopsMoved() {
		final byte[] bytes = centred(SimDatasetTest.lines(1, 400, 2, 4, 10)).toBytes();
		final long end = START + 3 * HOUR;
		final EventSimulator<SimDataset.DataTrack> everyTick = new EventSimulator<>(SimDataset.start(bytes, START), false);
		final EventSimulator<SimDataset.DataTrack> skipping = new EventSimulator<>(SimDataset.start(bytes, START), true);
		everyTick.advanceTo(end);
		skipping.advanceTo(end);
		assertEquals(times(everyTick.events(0)), times(skipping.events(0)));
	}

	/**
	 * @return the lines with every platform's trains stopping centred
	 */
	static SimDataset centred(SimDataset dataset) {
		dataset.sidings.forEach(siding -> siding.path.forEach(track -> {
			if (track.isPlatformTrack()) {
				dataset.stopPositions.put(track.getStopId(), StopPositions.CENTRED);
			}
		}));
		dataset.applyStopPositions();
		return dataset;
	}

	private static Map<Long, List<String>> byTrain(List<SimEvent> events) {
		final Map<Long, List<String>> byTrain = new TreeMap<>();
		events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED).forEach(event -> byTrain.computeIfAbsent(event.trainId, key -> new ArrayList<>()).add(event.kind + " " + event.rail));
		return byTrain;
	}

	private static List<String> times(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}
}
