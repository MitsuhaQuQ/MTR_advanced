package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The next service of a train where known for sure: one after the other in a duty, or set by the player, which comes first.
 */
class TerminalPairsTest {

	private static final int SEVEN = 7 * 60 * 60 * 1000;
	private static final int MINUTE = 60 * 1000;

	@Test
	void aDutyPairsTheEndOfOneRouteWithTheStartOfTheNext() {
		final List<DepotDuties.Segment> day = Arrays.asList(new DepotDuties.Segment(1, SEVEN, 0, -1), new DepotDuties.Segment(2, SEVEN + 20 * MINUTE, 0, -1), new DepotDuties.Segment(1, SEVEN + 40 * MINUTE, 0, 2));
		final Map<Long, Map<Integer, long[]>> next = TerminalPairs.fixedNext(Collections.emptyMap(), Collections.singletonList(day));
		assertArrayEquals(new long[]{2, SEVEN + 20 * MINUTE}, next.get(1L).get(SEVEN));
		assertArrayEquals(new long[]{1, SEVEN + 40 * MINUTE}, next.get(2L).get(SEVEN + 20 * MINUTE));
		// Leaving part of the way along isn't the end of the route
		assertNull(next.get(1L).get(SEVEN + 40 * MINUTE));
	}

	@Test
	void theTrainThePlayerSetComesFirst() {
		final List<DepotDuties.Segment> day = Arrays.asList(new DepotDuties.Segment(1, SEVEN, 0, -1), new DepotDuties.Segment(2, SEVEN + 20 * MINUTE, 0, -1));
		final RouteTimetable timetable = new RouteTimetable();
		timetable.services.add(SEVEN);
		timetable.links.put(SEVEN, new RouteTimetable.ServiceLink(2, SEVEN + 30 * MINUTE, -1, 0));
		// Through running part of the way along isn't turning back at the end
		timetable.services.add(SEVEN + MINUTE);
		timetable.links.put(SEVEN + MINUTE, new RouteTimetable.ServiceLink(3, SEVEN, 2, 1));
		final Map<Long, Map<Integer, long[]>> next = TerminalPairs.fixedNext(Collections.singletonMap(1L, timetable), Collections.singletonList(day));
		assertArrayEquals(new long[]{2, SEVEN + 30 * MINUTE}, next.get(1L).get(SEVEN));
		assertNull(next.get(1L).get(SEVEN + MINUTE));
	}
}
