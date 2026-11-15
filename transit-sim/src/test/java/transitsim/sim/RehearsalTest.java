package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * The rehearsal of a plan (2026-11-15): a simulated day summed up as warnings by route, with what held the trains up.
 */
class RehearsalTest {

	private static final long DAY_START = MONDAY + 3 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aPlanThatRunsOnTimeHasNoWarnings() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		assertEquals(Collections.emptyList(), Rehearsal.run(dataset, DAY_START, routes(dataset), () -> false));
	}

	@Test
	void lateServicesAreWarnedOfWithWhatHeldThemUp() {
		// A rail taken from outside for nine minutes: the trains behind it wait, and the warning says what they waited for
		final List<HostInput> inputs = Collections.singletonList(new HostInput.ExternalOccupancy(MONDAY + 6 * HOUR + 5 * MINUTE, 1, SimDatasetTest.plain(3, 4, 400).getTrackKey(), MONDAY + 6 * HOUR + 14 * MINUTE));
		final DayRun dayRun = DayRun.run(SimDatasetTest.lines(1, 400, 2, 4, 10), DAY_START, Collections.emptySet(), inputs, () -> false);
		final List<Rehearsal.Warning> warnings = Rehearsal.summarize(dayRun);
		final Rehearsal.Warning late = warnings.stream().filter(warning -> warning.kind == Rehearsal.Kind.LATE).findFirst().orElse(null);
		assertNotNull(late, describe(warnings));
		assertTrue(late.maxMillis > 2 * MINUTE, describe(warnings));
		assertTrue(late.serviceOfDay >= 0, describe(warnings));
		assertTrue(warnings.stream().anyMatch(warning -> warning.causeKnown), describe(warnings));
		// One warning a route and kind, however many services
		final Set<String> keys = new HashSet<>();
		warnings.forEach(warning -> assertTrue(keys.add(warning.kind + "@" + warning.routeId), describe(warnings)));
	}

	@Test
	void servicesNoTrainRunsAreWarnedOfOnceARoute() {
		final SimDataset dataset = SimDatasetTest.lines(1, 2400, 2, 0, 3);
		final List<Rehearsal.Warning> warnings = Rehearsal.run(dataset, DAY_START, routes(dataset), () -> false);
		final List<Rehearsal.Warning> notRun = new ArrayList<>();
		warnings.forEach(warning -> {
			if (warning.kind == Rehearsal.Kind.NOT_RUN) {
				notRun.add(warning);
			}
		});
		assertFalse(notRun.isEmpty(), describe(warnings));
		assertTrue(notRun.stream().mapToInt(warning -> warning.count).sum() > 100, describe(warnings));
		// Services not run come before lateness
		assertEquals(Rehearsal.Kind.NOT_RUN, warnings.get(0).kind);
	}

	@Test
	void aWarningReadsBackAsItWasWritten() {
		final Rehearsal.Warning warning = new Rehearsal.Warning(Rehearsal.Kind.LATE, -5, 16, 192000, 3901000, 3840000, 0, 77, true, 12, 3780000, true, "TRACK", 0, "a|b");
		final Rehearsal.Warning read = Rehearsal.Warning.decode(warning.encode());
		assertNotNull(read);
		assertEquals(warning.encode(), read.encode());
		assertEquals(-5, read.routeId);
		assertEquals("a b", read.detail);
		assertNull(Rehearsal.Warning.decode("not one"));
		final Rehearsal.Warning deadlock = new Rehearsal.Warning(Rehearsal.Kind.DEADLOCK, 0, 1, 0, 0, -1, -1, 0, false, 0, -1, false, "", 0, "[1, 2]");
		assertEquals(Rehearsal.Kind.DEADLOCK, Rehearsal.Warning.decode(deadlock.encode()).kind);
	}

	@Test
	void aRehearsalStoppedGivesNoWarnings() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		assertNull(Rehearsal.run(dataset, DAY_START, routes(dataset), () -> true));
	}

	private static Set<Long> routes(SimDataset dataset) {
		final Set<Long> routes = new TreeSet<>();
		dataset.routes.forEach(route -> routes.add(route.id));
		return routes;
	}

	private static String describe(List<Rehearsal.Warning> warnings) {
		final StringBuilder builder = new StringBuilder();
		warnings.forEach(warning -> builder.append(warning.encode()).append('\n'));
		return builder.toString();
	}
}
