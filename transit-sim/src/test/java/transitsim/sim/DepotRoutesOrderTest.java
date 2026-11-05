package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A depot's routes are the lines it runs, in no order: one of them with a timetable makes the depot run by it, whichever
 * comes first.
 */
class DepotRoutesOrderTest {

	@Test
	void aTimetableIsFoundWhereverItsRouteIs() {
		final TestRoute timed = ScenarioLine.makeRoute(1, 0, 1, 2, 3);
		final TestRoute untimed = new TestRoute(2, new long[]{3, 2, 1});
		final DepotBoard first = new DepotBoard(Arrays.asList(timed, untimed));
		final DepotBoard second = new DepotBoard(Arrays.asList(untimed, timed));
		assertNotNull(first.getTimetable("train"));
		assertSame(timed.timetable, first.getTimetable("train"));
		assertSame(timed.timetable, second.getTimetable("train"), "the untimed route first doesn't hide the timetable");
	}
}
