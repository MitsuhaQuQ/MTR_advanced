package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * A vehicle with a highest speed runs no rail faster, and the times it is planned to are worked out at that speed.
 */
class VehicleMaxSpeedTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	/**
	 * Below the line's rails, which allow 0.5 blocks per tick.
	 */
	private static final float MAX_SPEED = 0.3F;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	private static byte[] line(float maxSpeed) {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		dataset.sidings.get(0).vehicleMaxSpeed = maxSpeed;
		return dataset.toBytes();
	}

	@Test
	void trainsRunNoFasterThanTheirVehicleAllows() {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(line(MAX_SPEED), START));
		float fastest = 0;
		for (long time = START; time < START + 2 * HOUR; time += 1000) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				fastest = Math.max(fastest, train.getSpeed());
			}
		}
		assertTrue(fastest <= MAX_SPEED + 1e-4, "ran at " + fastest);
		assertTrue(fastest > MAX_SPEED - 0.05, "and up to it: " + fastest);
		// Without it, the rails' own limit
		final EventSimulator<SimDataset.DataTrack> free = new EventSimulator<>(SimDataset.start(line(0), START));
		float freeFastest = 0;
		for (long time = START; time < START + 2 * HOUR; time += 1000) {
			free.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : free.getRunner().getTrains()) {
				freeFastest = Math.max(freeFastest, train.getSpeed());
			}
		}
		assertTrue(freeFastest > MAX_SPEED + 0.05, "free ran at " + freeFastest);
	}

	@Test
	void theStopTimesAreWorkedOutAtThatSpeed() {
		final SimDataset slow = SimDataset.fromBytes(line(MAX_SPEED));
		final SimDataset fast = SimDataset.fromBytes(line(0));
		assertEquals(MAX_SPEED, slow.sidings.get(0).vehicleMaxSpeed);
		final long slowDuration = durationOf(line(MAX_SPEED));
		final long fastDuration = durationOf(line(0));
		assertTrue(slowDuration > fastDuration, slowDuration + " against " + fastDuration);
	}

	private static long durationOf(byte[] bytes) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START));
		for (long time = START; simulator.getRunner().getTrains().isEmpty(); time += 1000) {
			simulator.advanceTo(time);
		}
		return simulator.getRunner().getTrains().get(0).service.getStopTimes().getDurationMillis();
	}

	@Test
	void carryingOnFromARecordRunsAsRunningOnDoes() {
		final byte[] bytes = line(MAX_SPEED);
		final long saveAt = START + 70 * MINUTE + 350;
		final long end = START + 3 * HOUR;
		final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(bytes, START));
		runningOn.advanceTo(end);
		final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
		saved.advanceTo(saveAt);
		final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
		final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, saveAt);
		resumed.advanceTo(end);
		assertEquals(describe(runningOn.events(0), saveAt), describe(resumed.events(0), saveAt));
	}

	private static List<String> describe(List<SimEvent> events, long after) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.time > after && (event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART)).forEach(event -> lines.add(event.time + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
