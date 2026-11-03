package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.motion.SpeedCurves;
import transitsim.plan.OperatingDay;
import transitsim.plan.PathTimes;
import transitsim.plan.StopTimes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Fixed scenarios run from the depot through the timetable to the depot again, tick by tick as the game runs them
 * (S4a). Their results are pinned in {@code src/test/resources/scenarios}, so a change to any rule they go through shows
 * here; the event driven simulator is checked against the same scenarios. Each run also writes what it got to
 * {@code build/scenario-results}, to compare or to pin again after a change that is meant.
 */
class WholeChainScenarioTest {

	private static final long SECOND = 1000;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;
	/**
	 * Monday the 5th of January 2026 at midnight in UTC.
	 */
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final long SIDING = 100;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTerminalLine() throws IOException {
		final ScenarioLine scenario = new ScenarioLine(400, 2, 4);
		checkPinned("terminal-line", scenario.run(MONDAY + 7 * HOUR));
	}

	@Test
	void aLongLineWithLongTrains() throws IOException {
		final ScenarioLine scenario = new ScenarioLine(2400, 6, 6);
		checkPinned("long-line", scenario.run(MONDAY + 7 * HOUR + 30 * MINUTE));
	}

	@Test
	void aTrainHeldUpOnTheWay() throws IOException {
		final ScenarioLine scenario = new ScenarioLine(400, 2, 4);
		// Something the runner doesn't move stands on the line between the first two stations for two and a half minutes,
		// from just before the 06:20 train leaves the first one
		scenario.at(MONDAY + 6 * HOUR + 20 * MINUTE, () -> scenario.runner.occupyUntil(ScenarioTrack.plain(3, 4, 400, false).getTrackKey(), MONDAY + 6 * HOUR + 22 * MINUTE + 30 * SECOND));
		checkPinned("held-up", scenario.run(MONDAY + 7 * HOUR));
	}

	@Test
	void theTimetableChangedWhileTrainsRun() throws IOException {
		final ScenarioLine scenario = new ScenarioLine(400, 2, 4);
		scenario.at(MONDAY + 6 * HOUR + 21 * MINUTE, () -> {
			scenario.up.timetable.marginPercent = 40;
			scenario.up.timetable.revision++;
		});
		checkPinned("timetable-edit", scenario.run(MONDAY + 7 * HOUR));
	}

	@Test
	void eachScenarioRunsTheSameWayEveryTime() {
		assertEquals(new ScenarioLine(2400, 6, 6).run(MONDAY + 7 * HOUR), new ScenarioLine(2400, 6, 6).run(MONDAY + 7 * HOUR));
	}

	private static void checkPinned(String name, List<String> lines) throws IOException {
		final Path resultFolder = Paths.get("build", "scenario-results");
		Files.createDirectories(resultFolder);
		Files.write(resultFolder.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
		final InputStream pinned = WholeChainScenarioTest.class.getResourceAsStream("/scenarios/" + name + ".txt");
		assertNotNull(pinned, "No pinned result for " + name + ", see build/scenario-results");
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(pinned, StandardCharsets.UTF_8))) {
			assertEquals(reader.lines().collect(Collectors.joining("\n")), String.join("\n", lines), name);
		}
	}

}
