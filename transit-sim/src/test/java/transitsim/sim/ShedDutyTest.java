package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * A siding of a depot running by duties with one track between it and the line, used both ways (six-line test, line 8,
 * siding 0403, 2026-11-15): a train is sent out for its duty while the train before it is on its way back along that track,
 * so its run out isn't granted and it stands. The tick the other one comes home, two stand in the siding, and the second in
 * the siding's list, the one made after the first had left, is kept in the shed: the train waiting with its duty due
 * vanished with the duty, the one just back stood on with none, and the duty's services never ran, with nothing said.
 * <p>
 * Each duty here is one round trip, up and back down; the first train is back in the siding at 06:02:58.9.
 */
class ShedDutyTest {

	private static final long SIDING_ID = 100000;
	private static final long UP = 1;
	private static final long DOWN = 2;
	private static final long START = MONDAY + 5 * HOUR + 50 * MINUTE;
	private static final long END = MONDAY + 6 * HOUR + 40 * MINUTE;
	private static final int H6 = (int) (6 * HOUR);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * The second duty's train is due out of the siding at 06:02:50.9, while the first train is on the shared track coming
	 * back: it stands with its duty, and when the first is home it must not be the one taken away.
	 */
	@Test
	void theTrainWaitingWithItsDutyIsNotShedAndEveryDutyRuns() {
		check(new int[]{H6, H6 + (int) (3 * MINUTE + 15 * SECOND), H6 + (int) (10 * MINUTE), H6 + (int) (20 * MINUTE)});
	}

	/**
	 * As above, with the third duty due a second after the second, while the second's train still waits for its run out:
	 * that train must not be given the third duty in place of the second, which would then never run.
	 */
	@Test
	void aTrainWaitingForItsRunOutKeepsItsDuty() {
		check(new int[]{H6, H6 + (int) (3 * MINUTE + 15 * SECOND), H6 + (int) (3 * MINUTE + 16 * SECOND), H6 + (int) (20 * MINUTE)});
	}

	private static void check(int[] upServices) {
		final PrintStream out = System.out;
		final ByteArrayOutputStream said = new ByteArrayOutputStream();
		final List<String> shedWithDuty = new ArrayList<>();
		final List<String> dutyChangedStanding = new ArrayList<>();
		final TickRunner<SimDataset.DataTrack> runner;
		System.setOut(new PrintStream(said, true));
		try {
			runner = SimDataset.start(dataset(upServices).toBytes(), START).get();
			runner.setSkipping(false);
			final Map<Long, String> before = new HashMap<>();
			for (long time = START; time < END; time += 100) {
				runner.runUntil(time);
				final Map<Long, String> now = new HashMap<>();
				for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : runner.getTrains()) {
					now.put(train.id, (train.isOnRoute() ? "on the line" : "standing") + ", duty slot " + train.service.getDutySlot());
				}
				for (final Map.Entry<Long, String> entry : before.entrySet()) {
					final String was = entry.getValue();
					final String is = now.get(entry.getKey());
					final boolean hadDuty = was.startsWith("standing") && !was.endsWith("slot -1");
					if (hadDuty && is == null) {
						shedWithDuty.add("train " + entry.getKey() + " at " + ScenarioLine.clock(time) + " (" + was + ")");
					} else if (hadDuty && is.startsWith("standing") && !is.equals(was)) {
						dutyChangedStanding.add("train " + entry.getKey() + " at " + ScenarioLine.clock(time) + ": " + was + " -> " + is);
					}
				}
				before.clear();
				before.putAll(now);
			}
		} finally {
			System.setOut(out);
		}
		final String log = said.toString().trim();
		out.println(log);

		// Each duty leaves the first platform of the up route (rail 2 of the siding's path) once
		final List<String> upDepartures = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		for (final MotionEvent event : runner.getEvents()) {
			if (event.type == MotionEvent.Type.DEPARTED && event.rail == 2) {
				upDepartures.add(ScenarioLine.clock(event.millis));
			}
			if (event.type == MotionEvent.Type.DEPARTED && event.rail == 0 || event.type == MotionEvent.Type.RETURNED) {
				events.add(event.trainId + (event.type == MotionEvent.Type.DEPARTED ? " out " : " back ") + ScenarioLine.clock(event.millis));
			}
		}
		out.println("ShedDutyTest trains: " + events + "; up departures " + upDepartures + "; shed " + shedWithDuty + "; changed " + dutyChangedStanding);
		assertTrue(shedWithDuty.isEmpty(), "a train standing with its duty due was kept in the shed: " + shedWithDuty + "; trains " + events);
		assertTrue(dutyChangedStanding.isEmpty(), "a train waiting to leave for its duty was given another one: " + dutyChangedStanding + "; trains " + events);
		assertTrue(upDepartures.size() == upServices.length || log.contains("uty"), "only " + upDepartures + " of " + upServices.length + " duties ran, and nothing was said; trains " + events);
		assertEquals(upServices.length, upDepartures.size(), "duties run, said: " + log);
	}

	/**
	 * The line of {@link SimDatasetTest#lines}, its siding joined to the line by one track used both ways, the up route with
	 * the duties' services added, and a duty of one round trip for each, two trains in all.
	 */
	static SimDataset dataset(int[] upServices) {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final TestRoute up = SimDatasetTest.route(UP, 0, 10, 1, 2, 3);
		for (final int service : upServices) {
			if (!up.timetable.services.contains(service)) {
				up.timetable.services.add(service);
			}
		}
		up.timetable.sanitize();
		dataset.routes.set(0, new SimDataset.RouteData(up));

		final SimDataset.SidingData lineSiding = dataset.sidings.get(0);
		final List<SimDataset.DataTrack> path = new ArrayList<>(lineSiding.path.subList(0, 12));
		// Out along rail 1, and back from the down platform onto it the other way
		path.set(1, new SimDataset.DataTrack(1, 2, 60, false, false, 0, 0, 0, 1, true, true, true));
		path.add(SimDatasetTest.plain(12, 2, 60));
		path.add(new SimDataset.DataTrack(2, 1, 60, false, false, 0, 0, 0, 1, true, true, true));
		path.add(lineSiding.path.get(13));
		final SimDataset.SidingData siding = new SimDataset.SidingData(SIDING_ID, 1, new long[]{UP, DOWN}, 2, 10, 30, 0.01F, 0.02F, 4, true, 0.5F, 0, 0, 0, path, new byte[0]);
		siding.poolFleet = 2;
		dataset.sidings.set(0, siding);

		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final List<DepotDuties.Slot> slots = new ArrayList<>();
		for (final int service : upServices) {
			final DepotDuties.Slot slot = new DepotDuties.Slot();
			// Back on the first down service after it gets to the far end
			final int down = H6 + (int) (5 * MINUTE) + (int) (Math.max(0, service - H6) / (10 * MINUTE) * 10 * MINUTE);
			slot.days.add(DepotDuties.DayDuty.fromText("127,0,-1,0," + SIDING_ID + ",0;" + UP + "," + service + ",0,-1;" + DOWN + "," + down + ",0,-1"));
			slots.add(slot);
		}
		duties.sidings.put(SIDING_ID, slots);
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		final Set<String> keys = new HashSet<>();
		for (int i = 0; i < slots.size(); i++) {
			final String key = DutyShapes.getSlotKey(slots, i, 0, routes::get);
			if (key != null && keys.add(key)) {
				siding.tripPaths.add(PlanCheckTest.dutyPath(siding.path, key, Arrays.asList(UP, DOWN), false));
			}
		}
		dataset.replaceDuties(1, duties.toBytes());
		return dataset;
	}
}
