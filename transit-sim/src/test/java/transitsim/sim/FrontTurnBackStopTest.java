package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedCurves;
import transitsim.motion.TrackView;
import transitsim.plan.OperatingDay;
import transitsim.plan.StopPositions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * A train turning back on a platform it stopped at (front turn back, 0.84.6) stops again where it stands: the stop on the
 * way back is the rest of the platform's room less where the head stood coming in, so turning back (the head goes to
 * where the tail was) puts it there, and it doesn't move along the platform before opening its doors. Also the new
 * setting, the tail at the platform's entry end.
 */
class FrontTurnBackStopTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final int[] SETTINGS = {StopPositions.EXIT_END, StopPositions.CENTRED, StopPositions.TAIL_AT_ENTRY_END, 3};
	/**
	 * The terminal platform (30 long) of {@link SimDatasetTest#lines} and the same platform the way back; trains 20 long.
	 */
	private static final int ARRIVING = 6;
	private static final int LEAVING = 7;
	private static final double PLATFORM = 30;
	private static final double TRAIN = 20;
	private static final Set<SimEvent.Kind> KINDS = EnumSet.of(SimEvent.Kind.ARRIVE, SimEvent.Kind.DEPART, SimEvent.Kind.PASS, SimEvent.Kind.RETURNED, SimEvent.Kind.HELD);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void theTailAtTheEntryEnd() {
		assertEquals(10, StopPositions.getOffset(StopPositions.TAIL_AT_ENTRY_END, 30, 20), 1E-9);
		assertEquals(0, StopPositions.getOffset(StopPositions.TAIL_AT_ENTRY_END, 30, 40), 1E-9);
		// Kept as it is written down; other negatives are centred, as before
		assertEquals(StopPositions.TAIL_AT_ENTRY_END, StopPositions.normalise(StopPositions.TAIL_AT_ENTRY_END));
		assertEquals(StopPositions.CENTRED, StopPositions.normalise(-3));
		assertEquals(StopPositions.CENTRED, StopPositions.normalise(StopPositions.CENTRED));
		assertEquals(4, StopPositions.normalise(4));
		assertEquals(10, StopPositions.afterReversal(0, 30, 20), 1E-9);
		assertEquals(0, StopPositions.afterReversal(10, 30, 20), 1E-9);
		assertEquals(0, StopPositions.afterReversal(0, 30, 40), 1E-9);
	}

	@Test
	void theStopOnTheWayBackIsWhereTheTrainStands() {
		for (final int setting : SETTINGS) {
			final List<SimDataset.DataTrack> path = frontTurnBack(setting).sidings.get(0).path;
			final double arriving = StopPositions.getOffset(path, ARRIVING, TRAIN);
			// Coming in as the platform has it, as before
			assertEquals(StopPositions.getOffset(setting, PLATFORM, TRAIN), arriving, 1E-9, "setting " + setting);
			assertEquals(path.get(ARRIVING).getStopOffset(TRAIN), arriving, 1E-9, "setting " + setting);
			// The way back: the head where the tail was
			assertEquals(PLATFORM - TRAIN - arriving, StopPositions.getOffset(path, LEAVING, TRAIN), 1E-9, "setting " + setting);
			// Other stops by the platform alone
			assertEquals(StopPositions.getOffset(setting, PLATFORM, TRAIN), StopPositions.getOffset(path, 2, TRAIN), 1E-9);
			// The speed curves have a run of no length there
			final SpeedCurves curves = SpeedCurves.of(RunTrack.buildTrackView(path, 0.2, 300, TRAIN), 15, TRAIN, 0.01, 0.02);
			final SpeedCurves.Run back = curves.runs.stream().filter(run -> run.stopRailIndex == LEAVING).findFirst().orElseThrow(AssertionError::new);
			assertEquals(back.fastest.startPosition, back.fastest.endPosition, 0, "setting " + setting);
			assertEquals(0, back.fastest.getDuration(), 0, "setting " + setting);
		}
	}

	@Test
	void speedCurvesTurnBackOntoTheStop() {
		// As the round 4 test: a 20 long train stops 5 short of the end of a 30 long platform at 150, turns back to 175,
		// and its stop on the way back is 5 short of 180, where it stands
		final double[] lengths = {20, 100, 30, 30, 100};
		final double[] limits = {0.5, 1, -1, -1, 1};
		final boolean[] stops = {false, false, true, true, true};
		final boolean[] reversals = {false, false, true, false, false};
		final double[] offsets = {0, 0, 5, 5, 0};
		final SpeedCurves curves = SpeedCurves.of(new TrackView(lengths, limits, stops, reversals, offsets, 0.2), 10, 20, 0.01, 0.02);
		assertEquals(3, curves.runs.size());
		assertEquals(175, curves.runs.get(1).fastest.startPosition, 1E-9);
		assertEquals(175, curves.runs.get(1).fastest.endPosition, 1E-9);
		assertEquals(175, curves.runs.get(2).fastest.startPosition, 1E-9);
	}

	@Test
	void trainsDontMoveBetweenTheTwoStops() {
		for (final int setting : SETTINGS) {
			final SimDataset dataset = frontTurnBack(setting);
			final List<SimDataset.DataTrack> path = dataset.sidings.get(0).path;
			final double[] ends = ends(path);
			final double arriving = StopPositions.getOffset(setting, PLATFORM, TRAIN);
			final double leaving = PLATFORM - TRAIN - arriving;
			final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(dataset.toBytes(), START));
			int standingIn = 0;
			int standingOut = 0;
			for (long time = START + 70 * MINUTE; time < START + 3 * HOUR; time += 3 * 1000 + 350) {
				simulator.advanceTo(time);
				for (final RunRecord.TrainRecord train : simulator.getRunner().record().trains) {
					if (train.onRoute && train.speed <= 0 && train.elapsedDwellTicks > 0) {
						if (train.nextStoppingIndex == ARRIVING) {
							assertEquals(ends[ARRIVING] - arriving, train.railProgress, 1E-6, "setting " + setting + ", train " + train.trainId + " coming in");
							standingIn++;
						} else if (train.nextStoppingIndex == LEAVING) {
							// Where turning back put the head: past the train's length and the stop offset twice
							assertEquals(ends[ARRIVING] - arriving + TRAIN + 2 * arriving, train.railProgress, 1E-6, "setting " + setting + ", train " + train.trainId + " going back");
							assertEquals(ends[LEAVING] - leaving, train.railProgress, 1E-6);
							standingOut++;
						}
					}
				}
			}
			assertTrue(standingIn > 5 && standingOut > 5, "setting " + setting + ": " + standingIn + " " + standingOut);

			// Leaving the first stop and arriving at the second are the same moment, give or take the tick between them
			final List<SimEvent> events = simulator.events(0);
			int turns = 0;
			for (final SimEvent departure : events) {
				if (departure.kind == SimEvent.Kind.DEPART && departure.rail == ARRIVING) {
					final SimEvent arrival = events.stream().filter(event -> event.trainId == departure.trainId && event.time >= departure.time && event.kind == SimEvent.Kind.ARRIVE).findFirst().orElse(null);
					if (arrival != null) {
						assertEquals(LEAVING, arrival.rail, "setting " + setting);
						assertTrue(arrival.time - departure.time <= 50, "setting " + setting + ": " + (arrival.time - departure.time) + " ms between the stops");
						turns++;
					}
				}
			}
			assertTrue(turns > 5, "setting " + setting + ": " + turns);
			assertTrue(events.stream().noneMatch(event -> event.kind == SimEvent.Kind.DEADLOCK || event.kind == SimEvent.Kind.CONFLICT), "setting " + setting);
		}
	}

	@Test
	void skippingTicksGivesTheSameResult() {
		for (final int setting : SETTINGS) {
			final byte[] bytes = frontTurnBack(setting).toBytes();
			final long end = START + 3 * HOUR;
			final EventSimulator<SimDataset.DataTrack> everyTick = new EventSimulator<>(SimDataset.start(bytes, START), false);
			final EventSimulator<SimDataset.DataTrack> skipping = new EventSimulator<>(SimDataset.start(bytes, START), true);
			everyTick.advanceTo(end);
			skipping.advanceTo(end);
			assertEquals(describe(everyTick.events(0), 0), describe(skipping.events(0), 0), "setting " + setting);
		}
	}

	@Test
	void carryingOnFromARecordRunsAsRunningOnDoes() {
		for (final int setting : SETTINGS) {
			final byte[] bytes = frontTurnBack(setting).toBytes();
			final long end = START + 2 * HOUR;
			final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(bytes, START));
			runningOn.advanceTo(end);
			// Saved as a train turns back: as it leaves the first stop, standing at the second, and a moment on
			final List<Long> moments = new ArrayList<>();
			runningOn.events(0).stream().filter(event -> event.kind == SimEvent.Kind.DEPART && event.rail == ARRIVING && event.time > START + 70 * MINUTE).limit(2).forEach(event -> moments.addAll(Arrays.asList(event.time, event.time + 50, event.time + 100, event.time + 5000)));
			assertFalse(moments.isEmpty());
			for (final long time : moments) {
				final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
				saved.advanceTo(time);
				final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
				final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
				resumed.advanceTo(end);
				assertEquals(describe(runningOn.events(0), time), describe(resumed.events(0), time), "setting " + setting + ", saved at " + ScenarioLine.clock(time));
			}
		}
	}

	/**
	 * @return the line of {@link SimDatasetTest#lines}, its trains stopping at the terminal platform twice, coming in and
	 * going back, and every platform with the setting
	 */
	static SimDataset frontTurnBack(int setting) {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final List<SimDataset.DataTrack> path = dataset.sidings.get(0).path;
		final SimDataset.DataTrack back = path.get(LEAVING);
		path.set(LEAVING, back.withStop(20, back.getStopId()));
		dataset.sidings.forEach(siding -> siding.path.forEach(track -> {
			if (track.isPlatformTrack()) {
				dataset.stopPositions.put(track.getStopId(), setting);
			}
		}));
		dataset.applyStopPositions();
		return dataset;
	}

	private static double[] ends(List<SimDataset.DataTrack> path) {
		final double[] ends = new double[path.size()];
		double distance = 0;
		for (int i = 0; i < path.size(); i++) {
			distance += path.get(i).getTrackLength();
			ends[i] = distance;
		}
		return ends;
	}

	private static List<String> describe(List<SimEvent> events, long after) {
		return events.stream().filter(event -> event.time > after && KINDS.contains(event.kind)).map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}
}
