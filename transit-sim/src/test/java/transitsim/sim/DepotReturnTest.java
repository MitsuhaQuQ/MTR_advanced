package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.*;

class DepotReturnTest {
	@Test
	void parksAtTheCenterAsSoonAsTheDutyPathIsReleased() {
		OperatingDay.set("UTC", 0);
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, 1, 0);
		PlanCheckTest.addDutyPaths(dataset, result, false);
		final SimDataset.SidingData original = dataset.sidings.get(0);
		final SimDataset.SidingData siding = new SimDataset.SidingData(original.id, original.depotId, original.routeIds,
				original.trainCars, original.spacing, original.railLength, original.acceleration, original.deceleration,
				original.maxTrains, original.usesSpeedCurve, original.minSpeed, 0, 0, 0,
				Collections.singletonList(original.path.get(0)), original.turnBacks);
		siding.tripPaths.addAll(original.tripPaths);
		dataset.sidings.set(0, siding);
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(1, duties.toBytes());
		final TickRunner<SimDataset.DataTrack> runner = SimDataset.start(dataset.toBytes(), MONDAY + 5 * HOUR).get();
		int seen = 0;
		while (runner.getMillis() < MONDAY + 8 * HOUR + 10 * MINUTE) {
			runner.tick();
			while (seen < runner.getEvents().size()) {
				final MotionEvent event = runner.getEvents().get(seen++);
				if (event.type != MotionEvent.Type.RETURNED) continue;
				final TickRunner<SimDataset.DataTrack>.HeadlessTrain train = runner.getTrain(event.trainId);
				if (train == null || train.getPath().size() != 1) continue;
				assertFalse(train.isOnRoute());
				assertEquals(0, train.getNextStoppingIndex());
				assertNull(train.getSegmentSpec());
				assertEquals((siding.railLength + siding.trainCars * siding.spacing) / 2.0, train.getRailProgress(), 1e-6);
				return;
			}
		}
		fail("No return tick with the siding path");
	}
}
