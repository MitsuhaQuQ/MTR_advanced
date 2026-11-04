package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Trains put straight where their duties have them at a moment (decoupling document, step D) run on as the trains of the
 * whole day would have.
 */
class DutyPlacementTest {

	private static final long DEPOT = 1;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void placedTrainsRunOnAsTheWholeDayWould() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, DEPOT, 0);
		PlanCheckTest.addDutyPaths(dataset, result, false);
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(DEPOT, duties.toBytes());
		final byte[] bytes = dataset.toBytes();

		// Mid morning, with a train between two stations and others at them
		final long placeAt = MONDAY + 6 * HOUR + 41 * MINUTE + 35 * SECOND;
		final long until = placeAt + 20 * MINUTE;

		final TickRunner<SimDataset.DataTrack> wholeDay = SimDataset.start(bytes, MONDAY + 5 * HOUR).get();
		wholeDay.runUntil(until);
		final List<String> expected = departures(wholeDay, placeAt + 3 * MINUTE);

		final TickRunner<SimDataset.DataTrack> placed = SimDataset.start(bytes, placeAt).get();
		final int trains = placed.placeDutyTrains();
		assertTrue(trains > 0, "no train placed");
		// Some are on their way between two stations, not only standing at them
		assertTrue(placed.getTrains().stream().anyMatch(train -> train.getSpeed() > 0), "every train put at a stop");
		placed.runUntil(until);
		final List<String> actual = departures(placed, placeAt + 3 * MINUTE);

		assertTrue(expected.size() >= 8, "departures: " + expected);
		assertEquals(expected, actual);
	}

	/**
	 * @return where and when trains left a stop from this time on, to the nearest 5 seconds, in order
	 */
	private static List<String> departures(TickRunner<SimDataset.DataTrack> runner, long fromMillis) {
		final List<String> lines = new ArrayList<>();
		for (final MotionEvent event : runner.getEvents()) {
			if (event.type == MotionEvent.Type.DEPARTED && event.millis >= fromMillis) {
				lines.add(event.rail + " " + ScenarioLine.clock(Math.round(event.millis / 5000.0) * 5000));
			}
		}
		lines.sort(String::compareTo);
		return lines;
	}
}
