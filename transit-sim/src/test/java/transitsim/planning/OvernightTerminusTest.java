package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains staying on the line overnight at a terminal of one platform (the user's Village depot, 2026-11-15): only one of
 * them can take the morning's first service there, and the others go back to the depot at once, so the depot sends
 * them out for the next services instead of keeping them out for half an hour with nothing to run.
 */
class OvernightTerminusTest {

	private static final long SECOND = 1000;
	private static final long DOWN = 1;
	private static final long UP = 2;

	/**
	 * Five stations, one way 154 s with 10 s stops; the ends have one platform each (1 and 5), the middle stations one
	 * each way.
	 */
	private static long[][] plans() {
		final long[][] plans = new long[5][2];
		final long leg = (154 - 3 * 10) * SECOND / 4;
		for (int i = 0; i < 5; i++) {
			plans[i][0] = i * (leg + 10 * SECOND);
			plans[i][1] = i == 0 || i == 4 ? plans[i][0] : plans[i][0] + 10 * SECOND;
		}
		return plans;
	}

	/**
	 * Every 150 s each way from 06:01 (down) and 06:04:30 (up) to about 02:00, the operating day starting at 03:00.
	 */
	private static List<DutyGenerator.ServiceInput> services() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (int n = 0; n < 481; n++) {
			services.add(new DutyGenerator.ServiceInput(DOWN, n, DepotDuties.ALL_WEEKDAYS, (3 * 3600 + 60 + n * 150) * SECOND, new long[]{1, 2, 3, 4, 5}, plans(), new boolean[5], 0, -1, -1, -1));
			services.add(new DutyGenerator.ServiceInput(UP, n, DepotDuties.ALL_WEEKDAYS, (3 * 3600 + 270 + n * 150) * SECOND, new long[]{5, 6, 7, 8, 1}, plans(), new boolean[5], 0, -1, -1, -1));
		}
		return services;
	}

	private static Map<Long, Long> stations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(1L, 10L);
		stationOf.put(2L, 20L);
		stationOf.put(8L, 20L);
		stationOf.put(3L, 30L);
		stationOf.put(7L, 30L);
		stationOf.put(4L, 40L);
		stationOf.put(6L, 40L);
		stationOf.put(5L, 50L);
		return stationOf;
	}

	private static DutyGenerator.Result plan(int fleet) {
		// The depot in the middle of the line, about as far from both ends
		final Map<Long, Long> out = new HashMap<>();
		out.put(1L, 75730L);
		out.put(5L, 74173L);
		final DutyGenerator.SidingInput siding = new DutyGenerator.SidingInput(100, fleet, out, 9);
		final Map<Long, Long> back = new HashMap<>();
		back.put(1L, 85595L);
		back.put(5L, 86298L);
		final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(null, Collections.singletonMap(100L, back), Collections.emptyMap(), transitsim.plan.Buffers.NONE, 10000, Collections.emptyMap());
		return DutyGenerator.generateSpread(services(), Collections.singletonList(siding), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs);
	}

	@Test
	void threeTrainsRunEveryServiceFromTheFirst() {
		final DutyGenerator.Result result = plan(3);
		assertEquals(3, result.trains);
		// Before: the two trains not taking the 06:01 stood at the terminal until 06:31, and 15 services a day had no train
		assertEquals(0, result.uncovered, "services a week with no train");
	}

	@Test
	void twoTrainsBothRunFromTheMorning() {
		final DutyGenerator.Result result = plan(2);
		assertEquals(2, result.trains);
		// The second train's first service of the day: out of the depot for the second or third service, not half an hour later
		long secondStart = Long.MAX_VALUE;
		long firstStart = Long.MAX_VALUE;
		for (final List<DepotDuties.Slot> slots : result.sidings.values()) {
			for (final DepotDuties.Slot slot : slots) {
				for (final DepotDuties.DayDuty day : slot.days) {
					if ((day.weekdays & 1) != 0 && !day.segments.isEmpty()) {
						final long start = day.segments.get(0).service;
						if (start < firstStart) {
							secondStart = firstStart;
							firstStart = start;
						} else if (start < secondStart) {
							secondStart = start;
						}
					}
				}
			}
		}
		assertTrue(secondStart <= 3, "the second train starts with service " + secondStart);
	}
}
