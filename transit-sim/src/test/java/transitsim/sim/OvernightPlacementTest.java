package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * A day simulated from its start has the trains that stayed on the line overnight standing where their duties start
 * (2026-11-09): with none, the duties run by such trains got no train at all, as none leaves the siding for them.
 */
class OvernightPlacementTest {

	private static final long DEPOT = 1;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void dutiesOfTrainsStayingOvernightRunFromTheStartOfTheDay() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, DEPOT, 0);
		// The first duty of each siding stays on the line, so the next day's is run by the train left from the day before;
		// one train a platform, as the planner leaves them
		result.sidings.values().forEach(slots -> slots.get(0).days.forEach(day -> day.stayOnLine = true));
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		result.sidings.forEach((sidingId, slots) -> {
			final SimDataset.SidingData siding = dataset.sidings.stream().filter(each -> each.id == sidingId).findFirst().orElseThrow(AssertionError::new);
			final Set<String> keys = new HashSet<>();
			for (int i = 0; i < slots.size(); i++) {
				for (int weekday = 0; weekday < 7; weekday++) {
					final String key = DutyShapes.getSlotKey(slots, i, weekday, routes::get);
					if (key != null && keys.add(key)) {
						final List<Long> shape = slots.get(i).getDuty(weekday).segments.stream().map(segment -> segment.routeId).collect(Collectors.toList());
						siding.tripPaths.add(PlanCheckTest.dutyPath(siding.path, key, shape, i == 0));
					}
				}
			}
		});
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(DEPOT, duties.toBytes());
		final byte[] bytes = dataset.toBytes();
		final long dayStart = MONDAY + 3 * HOUR;
		final int weekday = OperatingDay.getWeekday(dayStart);
		final long overnight = result.sidings.values().stream().filter(slots -> {
			final DepotDuties.DayDuty day = slots.get(0).getDuty(weekday);
			return day != null && !day.segments.isEmpty();
		}).count();
		assertTrue(overnight > 0);

		// From the start of the day as before: no train for them
		final TickRunner<SimDataset.DataTrack> cold = SimDataset.start(bytes, dayStart).get();
		cold.runUntil(MONDAY + 10 * HOUR);
		final List<String> coldRuns = departures(cold);

		// With the trains put where they stood: one on each duty, standing, then running them
		final TickRunner<SimDataset.DataTrack> placed = DayRun.startOfDay(bytes, dayStart).get();
		assertEquals(overnight, placed.getTrains().size(), "one on each duty run by a train staying overnight, nothing else yet");
		assertTrue(placed.getTrains().stream().allMatch(train -> train.getSpeed() == 0), "standing at their first stops");
		final Set<Long> overnightTrains = placed.getTrains().stream().map(train -> train.id).collect(Collectors.toSet());
		placed.runUntil(MONDAY + 10 * HOUR);
		final List<String> runs = departures(placed);
		// The trains put there run their duties, stop after stop, which no train did without them (the test line has no room
		// for them to stay at the end, so the rest of the day isn't compared)
		final long overnightDepartures = placed.getEvents().stream().filter(event -> event.type == MotionEvent.Type.DEPARTED && overnightTrains.contains(event.trainId)).count();
		assertTrue(overnightDepartures >= 3 * overnight, overnightDepartures + " departures of the trains staying overnight");
		assertFalse(coldRuns.isEmpty(), "the other duties run from the siding");

		// Built again from the same start, the day runs the same
		final TickRunner<SimDataset.DataTrack> again = DayRun.startOfDay(bytes, dayStart).get();
		again.runUntil(MONDAY + 10 * HOUR);
		assertEquals(runs, departures(again));
	}

	private static List<String> departures(TickRunner<SimDataset.DataTrack> runner) {
		final List<String> lines = new ArrayList<>();
		for (final MotionEvent event : runner.getEvents()) {
			if (event.type == MotionEvent.Type.DEPARTED) {
				lines.add(event.rail + " " + event.millis);
			}
		}
		return lines;
	}
}
