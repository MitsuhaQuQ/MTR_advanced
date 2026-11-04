package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionConstants;
import transitsim.motion.RunSegment;
import transitsim.plan.OperatingDay;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Run segments described for someone who has the same speed curves (G2), on two lines of {@link SimDatasetTest#lines}:
 * the curve rebuilt from a description has each train where the runner has it, and the description is the same whether
 * the runner skips ticks or not.
 */
class SegmentSpecTest {

	private static final long START = MONDAY + 4 * HOUR + 55 * MINUTE;
	private static final long END = MONDAY + 5 * HOUR + 40 * MINUTE;
	private static final long TICK = MotionConstants.MILLIS_PER_TICK;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aRebuiltSegmentHasTheTrainWhereTheRunnerHasIt() {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(SimDatasetTest.lines(2, 2400, 2, 8, 3).toBytes(), START));
		final Map<Long, RunSegment> rebuilt = new HashMap<>();
		int compared = 0;
		for (long time = START; time < END; time += 7 * TICK) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				final RunSegment.Spec spec = train.getSegmentSpec();
				if (spec == null) {
					continue;
				}
				// Rebuilt once per segment, then followed by the clock alone, as a client does
				final RunSegment segment = rebuilt.compute(train.id, (id, old) -> old != null && old.version == spec.version ? old : spec.rebuild(train.getRun(spec.startPosition)));
				final double ticks = spec.getTicksAt(time);
				assertEquals(train.getRailProgress(), segment.getPositionAt(ticks), 1E-6, ScenarioLine.clock(time) + " train " + train.id);
				assertEquals(train.getSpeed(), segment.getSpeedAt(ticks), 1E-4, ScenarioLine.clock(time) + " train " + train.id);
				compared++;
			}
		}
		assertTrue(compared > 1000, String.valueOf(compared));
	}

	@Test
	void theDescriptionIsTheSameWhetherTicksAreSkippedOrNot() {
		final byte[] bytes = SimDatasetTest.lines(2, 2400, 2, 8, 3).toBytes();
		final EventSimulator<SimDataset.DataTrack> everyTick = new EventSimulator<>(SimDataset.start(bytes, START), false);
		final EventSimulator<SimDataset.DataTrack> skipping = new EventSimulator<>(SimDataset.start(bytes, START), true);
		int compared = 0;
		for (long time = START; time < END; time += 13 * TICK) {
			everyTick.advanceTo(time);
			skipping.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : everyTick.getRunner().getTrains()) {
				final RunSegment.Spec spec = train.getSegmentSpec();
				final TickRunner<SimDataset.DataTrack>.HeadlessTrain other = skipping.getRunner().getTrain(train.id);
				if (spec != null && other != null) {
					assertEquals(spec, other.getSegmentSpec(), ScenarioLine.clock(time) + " train " + train.id);
					compared++;
				}
			}
		}
		assertTrue(compared > 100, String.valueOf(compared));
	}
}
