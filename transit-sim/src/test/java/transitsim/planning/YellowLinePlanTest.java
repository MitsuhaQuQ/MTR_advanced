package transitsim.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import transitsim.plan.RouteTimetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The yel world's Yellow Line as the game planned it (2026-11-07): every 5 minutes each way, 24 minutes end to end,
 * turning round in 133 and 91 seconds, which some 11 trains run; the planner asked for 140, as the ten trains a day more
 * ending at the west end than leaving it stayed there for days (the next day's trips leave from another platform).
 */
class YellowLinePlanTest {

	private static final long UP = -2233059242991721934L;
	private static final long DOWN = 7542877867840343529L;
	private static final long UP_FIRST = -7635197065791355231L;
	private static final long UP_LAST = -7214856037291641293L;
	private static final long DOWN_FIRST = 1380568120117696406L;
	private static final long DOWN_LAST = -6414561950113375906L;
	private static final long WEST = 769575939067525848L;
	private static final long EAST = 3745187235734458998L;

	private static final long[][] UP_PLANS = {{0, 0}, {145000, 175000}, {242667, 272667}, {345125, 375125}, {443079, 473079}, {539182, 569182}, {601480, 631480}, {683846, 713846}, {750596, 780596}, {831568, 861568}, {893876, 923876}, {963582, 993582}, {1040593, 1070593}, {1127526, 1157526}, {1290922, 1320922}, {1375682, 1425682}};
	private static final long[][] DOWN_PLANS = {{0, 0}, {180000, 210000}, {335479, 365479}, {413583, 443583}, {483761, 513761}, {546425, 576425}, {601857, 631857}, {676321, 706321}, {735583, 765583}, {811255, 841255}, {866841, 896841}, {961197, 991197}, {1050023, 1080023}, {1143020, 1173020}, {1239270, 1269270}, {1349432, 1399432}};

	@Test
	void yellowLine() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		addServices(services, UP, UP_FIRST, UP_LAST, UP_PLANS, 201);
		addServices(services, DOWN, DOWN_FIRST, DOWN_LAST, DOWN_PLANS, 211);
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(UP_FIRST, WEST);
		stationOf.put(DOWN_LAST, WEST);
		stationOf.put(UP_LAST, EAST);
		stationOf.put(DOWN_FIRST, EAST);
		final Map<Long, Map<Long, Long>> turnTimes = new TreeMap<>();
		turnTimes.computeIfAbsent(UP_LAST, key -> new TreeMap<>()).put(DOWN_FIRST, 133331L);
		turnTimes.computeIfAbsent(DOWN_LAST, key -> new TreeMap<>()).put(UP_FIRST, 91345L);
		turnTimes.computeIfAbsent(DOWN_LAST, key -> new TreeMap<>()).put(DOWN_LAST, 1259965L);
		final Map<Long, Map<Long, Long>> backLinks = new TreeMap<>();
		backLinks.put(8828205967860855991L, map(UP_LAST, 581462));
		backLinks.put(2067655506669394971L, map(UP_LAST, 57344, DOWN_LAST, 73062));
		final DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(turnTimes, backLinks);

		for (final int[] limits : new int[][]{{-1, -1}, {1, 30}}) {
			final List<DutyGenerator.SidingInput> sidings = Arrays.asList(
					new DutyGenerator.SidingInput(8828205967860855991L, limits[0], map(UP_FIRST, 41082)),
					new DutyGenerator.SidingInput(2067655506669394971L, limits[1], map(UP_FIRST, 600159, DOWN_FIRST, 57122))
			);
			// Staying on the line (as the game planned it) and not
			for (final long layover : new long[]{RouteTimetable.MAX_STAY_MILLIS, RouteTimetable.MAX_LAYOVER_MILLIS}) {
				final DutyGenerator.Result result = DutyGenerator.generate(services, sidings, stationOf, layover, inputs);
				final String what = "limits " + Arrays.toString(limits) + " layover " + layover + ": trains " + result.trains + " uncovered " + result.uncovered;
				System.out.println(what + " issues " + result.issues.stream().collect(Collectors.groupingBy(issue -> issue.split(" ")[0] + " " + (issue.split(" ").length > 2 ? issue.split(" ")[2] : ""), java.util.TreeMap::new, Collectors.counting())) + " sample " + result.issues.stream().filter(issue -> !issue.startsWith("PLATFORM_CONFLICT platform -6414561950113375906")).limit(4).collect(Collectors.toList()));
				assertEquals(0, result.uncovered, what);
				// About 52 minutes round at a train every 5 minutes, and the depot's trains out and back
				assertTrue(result.trains <= 20, what);
				assertTrue(result.issues.stream().noneMatch(issue -> issue.startsWith("NOT_PERIODIC")), what);
				// Still known: trains wait for their next trip at the platform they arrive at, which the next arriving train
				// needs (see the decoupling document, 5.5); not asserted until they wait elsewhere
			}
		}
	}

	private static void addServices(List<DutyGenerator.ServiceInput> services, long routeId, long first, long last, long[][] plans, int count) {
		final long[] platforms = new long[plans.length];
		for (int i = 0; i < platforms.length; i++) {
			platforms[i] = 1000 + i + (routeId == UP ? 0 : 100);
		}
		platforms[0] = first;
		platforms[platforms.length - 1] = last;
		for (int i = 0; i < count; i++) {
			final long start = 7200000L + i * 300000L;
			services.add(new DutyGenerator.ServiceInput(routeId, (int) ((start + 64800000L) % 86400000L), 127, start, platforms, plans, new boolean[plans.length], 0, 0, 0, 0));
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
