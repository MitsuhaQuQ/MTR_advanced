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
 * A train's doors are never open while it moves (0.84.8, {@link RunInvariants}): after 0.84.6 had trains stop in place
 * after a front turn back, trains on the user's server ran along the platform with their doors open and then went out.
 * Looked at every tick, on a line whose trains turn back in front of the terminal platform (stopping there coming in and
 * going back), with every platform stop setting, running straight through and started again from a record while trains
 * stand at their stops with their doors open, as the game starts its simulator again whenever routes or paths change.
 */
class DoorsShutWhileMovingTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final int[] SETTINGS = {StopPositions.EXIT_END, StopPositions.CENTRED, StopPositions.TAIL_AT_ENTRY_END, 3};
	/**
	 * The terminal platform of {@link SimDatasetTest#lines} and the same platform the way back.
	 */
	private static final int ARRIVING = 6;
	private static final int LEAVING = 7;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void doorsOpenOnAMovingTrainAreFound() {
		final ScenarioLine line = new ScenarioLine(400, 4, 4);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		final TickRunner<ScenarioTrack>.HeadlessTrain[] moving = new TickRunner.HeadlessTrain[1];
		line.runner.setAfterTick(() -> {
			if (moving[0] == null) {
				line.runner.getTrains().stream().filter(train -> train.isOnRoute() && train.getSpeed() > 0.1).findFirst().ifPresent(train -> {
					moving[0] = train;
					train.setDoorValue(0.5F);
				});
			}
			invariants.check();
		});
		line.run(MONDAY + 6 * HOUR + 30 * MINUTE);
		assertNotNull(moving[0]);
		assertTrue(invariants.countDoorViolations() > 0, invariants.getViolations().toString());
	}

	@Test
	void aPlainMorningMovesNoTrainWithItsDoorsOpen() {
		final ScenarioLine line = new ScenarioLine(400, 4, 4);
		final RunInvariants<ScenarioTrack> invariants = new RunInvariants<>(line.runner);
		line.runner.setAfterTick(invariants::check);
		line.run(MONDAY + 8 * HOUR + 40 * MINUTE);
		assertTrue(invariants.getChecks() > 1000);
		assertEquals(0, invariants.countDoorViolations(), String.join("\n", invariants.getViolations()));
	}

	@Test
	void frontTurnBacksMoveNoTrainWithItsDoorsOpen() {
		for (final int setting : SETTINGS) {
			final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(frontTurnBack(setting).toBytes(), START), false);
			final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(simulator.getRunner());
			int turns = 0;
			for (long time = START; time < START + 3 * HOUR; time += 50) {
				simulator.advanceTo(time);
				invariants.check();
			}
			for (final SimEvent event : simulator.events(0)) {
				if (event.kind == SimEvent.Kind.DEPART && event.rail == ARRIVING) {
					turns++;
				}
			}
			assertTrue(turns > 5, "setting " + setting + ": " + turns + " turns");
			assertEquals(0, invariants.countDoorViolations(), "setting " + setting + ": " + String.join("\n", invariants.getViolations()));
		}
	}

	/**
	 * Started again from a record at moments trains stand at the terminal platform with their doors open, coming in or going
	 * back, as the game starts its simulator again from its trains: none of them moves off with its doors open.
	 */
	@Test
	void startedAgainAtAStopNoTrainMovesWithItsDoorsOpen() {
		for (final int setting : SETTINGS) {
			final byte[] bytes = frontTurnBack(setting).toBytes();
			final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(bytes, START));
			final List<Long> moments = new ArrayList<>();
			for (long time = START + 70 * MINUTE; time < START + 2 * HOUR && moments.size() < 12; time += 7 * SECOND) {
				runningOn.advanceTo(time);
				final boolean standingOpen = runningOn.getRunner().getTrains().stream().anyMatch(train -> train.isOnRoute() && train.getSpeed() <= 0 && train.getDoorValue() > 0 && (train.getNextStoppingIndex() == ARRIVING || train.getNextStoppingIndex() == LEAVING));
				if (standingOpen) {
					moments.add(time);
				}
			}
			assertFalse(moments.isEmpty(), "setting " + setting);
			for (final long time : moments) {
				final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
				saved.advanceTo(time);
				final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
				final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
				final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(resumed.getRunner());
				for (long tick = time; tick < time + 3 * MINUTE; tick += 50) {
					resumed.advanceTo(tick);
					invariants.check();
				}
				assertEquals(0, invariants.countDoorViolations(), "setting " + setting + ", started again at " + ScenarioLine.clock(time) + ": " + String.join("\n", invariants.getViolations()));
			}
		}
	}

	/**
	 * @return the line of {@link SimDatasetTest#lines}, its trains stopping at the terminal platform twice, coming in and
	 * going back on the same platform (a front turn back), and every platform with the setting
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
}
