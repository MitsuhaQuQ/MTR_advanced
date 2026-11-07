package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.plan.RouteTimetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lines with more trips one way than the other, as the yel world's Yellow Line (YellowLinePlanTest): the trains left over
 * at one end go back to the depot rather than wait there for days, so staying on the line overnight needs about as many
 * trains as going back every time, and the duties repeat week after week.
 */
class UnevenServicesTest {

	private static final long UP_FIRST = 1;
	private static final long UP_LAST = 2;
	private static final long DOWN_FIRST = 3;
	private static final long DOWN_LAST = 4;
	private static final long SIDING = 100;

	@Test
	void trainsLeftOverGoBack() {
		for (int seed = 0; seed < 60; seed++) {
			final Random random = new Random(seed);
			final long headway = (3 + random.nextInt(10)) * 60000L;
			final long upRun = (10 + random.nextInt(30)) * 60000L;
			final long downRun = upRun + (random.nextInt(5) - 2) * 60000L;
			final long firstUp = (5 + random.nextInt(2)) * 3600000L;
			final long firstDown = firstUp + random.nextInt(4) * 60000L;
			final int upCount = 40 + random.nextInt(100);
			final int downCount = upCount + random.nextInt(20) - 5;
			final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
			add(services, 10, UP_FIRST, UP_LAST, upRun, firstUp, headway, upCount);
			add(services, 20, DOWN_FIRST, DOWN_LAST, downRun, firstDown, headway, downCount);
			final Map<Long, Long> stationOf = new HashMap<>();
			stationOf.put(UP_FIRST, 1000L);
			stationOf.put(DOWN_LAST, 1000L);
			stationOf.put(UP_LAST, 2000L);
			stationOf.put(DOWN_FIRST, 2000L);
			final Map<Long, Map<Long, Long>> turnTimes = new TreeMap<>();
			turnTimes.computeIfAbsent(UP_LAST, key -> new TreeMap<>()).put(DOWN_FIRST, 60000L + random.nextInt(120) * 1000L);
			turnTimes.computeIfAbsent(DOWN_LAST, key -> new TreeMap<>()).put(UP_FIRST, 60000L + random.nextInt(120) * 1000L);
			final Map<Long, Map<Long, Long>> backLinks = new TreeMap<>();
			backLinks.put(SIDING, map(UP_LAST, 120000, DOWN_LAST, upRun));
			final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(turnTimes, backLinks);
			final List<DutyGenerator.SidingInput> sidings = Arrays.asList(new DutyGenerator.SidingInput(SIDING, -1, map(DOWN_FIRST, 120000, UP_FIRST, upRun)));

			final DutyGenerator.Result back = DutyGenerator.generate(services, sidings, stationOf, RouteTimetable.MAX_LAYOVER_MILLIS, inputs);
			final DutyGenerator.Result stays = DutyGenerator.generate(services, sidings, stationOf, RouteTimetable.MAX_STAY_MILLIS, inputs);
			final String what = "seed " + seed + " headway " + headway / 60000 + " min, run " + upRun / 60000 + "/" + downRun / 60000 + " min, trips " + upCount + "/" + downCount + ": back " + back.trains + ", stays " + stays.trains;
			assertEquals(0, back.uncovered, what);
			assertEquals(0, stays.uncovered, what);
			// Round the line at a train every headway, and the depot's trains for the trips one way more
			final long round = upRun + downRun + 2 * 180000L + 2 * 120000L;
			final int enough = (int) (round / headway) + 2 + Math.abs(downCount - upCount);
			assertTrue(stays.trains <= enough, what);
			assertTrue(stays.trains <= back.trains + 2, what);
			assertTrue(stays.issues.stream().noneMatch(issue -> issue.startsWith("NOT_PERIODIC")), what);
		}
	}

	private static void add(List<DutyGenerator.ServiceInput> services, long routeId, long first, long last, long run, long firstStart, long headway, int count) {
		final long[][] plans = {{0, 0}, {run / 2, run / 2 + 30000}, {run, run + 30000}};
		final long[] platforms = {first, routeId * 10 + 5, last};
		for (int i = 0; i < count; i++) {
			final long start = firstStart + i * headway;
			if (start + run >= 86400000L) {
				break;
			}
			services.add(new DutyGenerator.ServiceInput(routeId, (int) start, 127, start, platforms, plans, new boolean[3], 0, 0, 0, 0));
		}
	}

	private static Map<Long, Long> map(long... values) {
		final Map<Long, Long> map = new TreeMap<>();
		for (int i = 0; i < values.length; i += 2) {
			map.put(values[i], values[i + 1]);
		}
		return map;
	}
}
