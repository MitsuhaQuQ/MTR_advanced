package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * A simulator running as long as the game does (G1), which keeps no history: it runs as one that keeps it, and lets go
 * of the events the host has handled.
 */
class LiveModeTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aLiveSimulatorRunsTheSameAndKeepsNoHistory() {
		final byte[] bytes = SimDatasetTest.lines(3, 400, 2, 8, 3).toBytes();
		final EventSimulator<SimDataset.DataTrack> keeping = new EventSimulator<>(SimDataset.start(bytes, START));
		final EventSimulator<SimDataset.DataTrack> live = new EventSimulator<>(SimDataset.start(bytes, START));
		live.setLive();
		final List<String> liveEvents = new ArrayList<>();
		long cursor = 0;
		for (long time = START; time < START + 2 * HOUR; time += 5 * SECOND) {
			keeping.advanceTo(time);
			live.advanceTo(time);
			final List<SimEvent> events = live.events(cursor);
			if (!events.isEmpty()) {
				cursor = events.get(events.size() - 1).cursor;
				events.forEach(event -> liveEvents.add(describe(event)));
				live.forgetEvents(cursor);
				assertTrue(live.events(0).isEmpty());
			}
		}
		assertTrue(liveEvents.size() > 100, String.valueOf(liveEvents.size()));
		assertEquals(keeping.events(0).stream().map(LiveModeTest::describe).collect(Collectors.toList()), liveEvents);
		assertThrows(IllegalStateException.class, live::fork);
		assertThrows(IllegalStateException.class, live::snapshot);
	}

	private static String describe(SimEvent event) {
		return event.cursor + " " + ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.sequence + " " + event.rail;
	}
}
