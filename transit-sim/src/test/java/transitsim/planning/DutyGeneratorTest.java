package transitsim.planning;

import transitsim.plan.DepotDuties;
import transitsim.io.ByteArrayBinary;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DutyGeneratorTest {

	private static final long MINUTE = 60000;
	private static final long ROUTE_UP = 1;
	private static final long ROUTE_DOWN = 2;

	/**
	 * A two station shuttle: up from platform 11 to 21, down from 22 to 12, with stations 1 and 2 having one platform for
	 * each direction. Each run takes 20 minutes.
	 */
	private static DutyGenerator.ServiceInput service(long routeId, long startMinutes, int weekdays, long linkRoute, long linkStartMinutes) {
		final long[] platforms = routeId == ROUTE_UP ? new long[]{11, 21} : new long[]{22, 12};
		return new DutyGenerator.ServiceInput(routeId, (int) (startMinutes * MINUTE), weekdays, startMinutes * MINUTE, platforms, new long[][]{{0, 0}, {20 * MINUTE, 20 * MINUTE}}, new boolean[2], linkRoute, (int) (linkStartMinutes * MINUTE), -1, 0);
	}

	private static Map<Long, Long> stations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(11L, 1L);
		stationOf.put(12L, 1L);
		stationOf.put(21L, 2L);
		stationOf.put(22L, 2L);
		return stationOf;
	}

	@Test
	void capacityChangesPreserveSharedPoolLimits() {
		DutyGenerator.SidingInput original = new DutyGenerator.SidingInput(100, 1, Collections.singletonMap(11L, 60000L), 9);
		original.poolKey = "shared";
		DutyGenerator.SidingInput copy = original.withCapacity(8);
		DutyGenerator.SidingInput other = new DutyGenerator.SidingInput(200, 8);
		other.poolKey = "shared";
		DutyGenerator.PlanInputs inputs = new DutyGenerator.PlanInputs(null, Collections.emptyMap(), Collections.singletonMap("shared", 3));
		OperationNetwork network = DutyGenerator.networkOf(Collections.emptyList(), Arrays.asList(copy, other), stations(), inputs);
		assertEquals(3, network.getStartingTrains().values().stream().mapToInt(Integer::intValue).sum());
		DutyGenerator.PlanInputs added = inputs.withAdditionalPoolTrains(copy.poolKey, 2);
		OperationNetwork increased = DutyGenerator.networkOf(Collections.emptyList(), Arrays.asList(copy, other), stations(), added);
		assertEquals(5, increased.getStartingTrains().values().stream().mapToInt(Integer::intValue).sum());
		assertEquals(3, inputs.poolFleets.get("shared").intValue());
		assertEquals(1, original.maxTrains);
		assertEquals(9, copy.depotId);
		assertEquals(original.links, copy.links);
	}

	@Test
	void capacityStatisticsMatchFullDutyGeneration() {
		for (int fleet : new int[]{1, 2, 4}) for (int days : new int[]{1, 0x60, 0x7F}) {
			List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, fleet));
			int full = DutyGenerator.generateDayType(shuttle(), days, sidings, stations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE, false).uncovered;
			assertEquals(full, DutyGenerator.uncoveredOnDayType(shuttle(), days, sidings, stations(), 30 * MINUTE, DutyGenerator.PlanInputs.NONE));
		}
	}

	@Test
	void spreadRetainsTheUnlimitedCapacityDiagnosis() {
		List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1, Collections.emptyMap()));
		DutyGenerator.Result result = DutyGenerator.generateSpread(shuttle(), sidings, stations(), 30 * MINUTE);
		assertEquals(12 * 7, result.uncovered);
		assertEquals(result.uncovered, result.unrunnable);
	}

	@Test
	void runsEveryServiceOnceWithTheFewestTrains() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			services.add(service(ROUTE_UP, 360 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
			services.add(service(ROUTE_DOWN, 385 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
		}
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 4)), stations(), 30 * MINUTE);

		// A round trip is an hour and a train leaves every half hour, so two trains run it
		assertEquals(2, result.trains);
		assertEquals(0, result.uncovered);
		final List<DepotDuties.Slot> slots = result.sidings.get(100L);
		assertEquals(2, slots.size());
		final Set<DepotDuties.Segment> runs = new HashSet<>();
		slots.forEach(slot -> {
			// Every day is the same, so each train has one duty for the whole week
			assertEquals(1, slot.days.size());
			assertEquals(DepotDuties.ALL_WEEKDAYS, slot.days.get(0).weekdays);
			assertFalse(slot.days.get(0).stayOnLine);
			slot.days.get(0).segments.forEach(segment -> assertTrue(runs.add(segment)));
		});
		assertEquals(12, runs.size());
	}

	@Test
	void aSidingStartingOnlyOnTheUpLineRunsBothDirections() {
		// The depot lists only the up route, so its trains start at its first stop, then turn back onto the down route
		final List<DutyGenerator.ServiceInput> services = shuttle();
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 4, Collections.singletonMap(11L, 0L))), stations(), 30 * MINUTE);
		assertEquals(0, result.uncovered);
		final Set<Long> routes = new HashSet<>();
		result.sidings.get(100L).forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> routes.add(segment.routeId))));
		assertEquals(new HashSet<>(Arrays.asList(ROUTE_UP, ROUTE_DOWN)), routes);
	}

	@Test
	void theNearerSidingIsTakenFirst() {
		final Map<Long, Long> far = new HashMap<>();
		far.put(11L, 10 * MINUTE);
		far.put(22L, 10 * MINUTE);
		final Map<Long, Long> near = new HashMap<>();
		near.put(11L, MINUTE);
		near.put(22L, MINUTE);
		final DutyGenerator.Result result = DutyGenerator.generate(shuttle(), Arrays.asList(new DutyGenerator.SidingInput(100, 4, far), new DutyGenerator.SidingInput(200, 4, near)), stations(), 30 * MINUTE);
		assertEquals(0, result.uncovered);
		assertTrue(result.sidings.getOrDefault(100L, Collections.emptyList()).isEmpty(), "the far siding has nothing to do");
		assertEquals(2, result.sidings.get(200L).size());
	}

	@Test
	void tooFewTrainsAreCountedAgainstWhatIsNeeded() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1));
		final DutyGenerator.Result result = DutyGenerator.generate(shuttle(), sidings, stations(), 30 * MINUTE);
		assertTrue(result.uncovered > 0);
		assertEquals(2, DutyGenerator.neededTrains(shuttle(), sidings, stations(), 30 * MINUTE));
	}

	/**
	 * A service every minute each way all day, far more than a few trains can run.
	 */
	private static List<DutyGenerator.ServiceInput> everyMinute() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (int minute = 0; minute < 24 * 60; minute++) {
			services.add(service(ROUTE_UP, minute, DepotDuties.ALL_WEEKDAYS, 0, 0));
			services.add(service(ROUTE_DOWN, minute, DepotDuties.ALL_WEEKDAYS, 0, 0));
		}
		return services;
	}

	/**
	 * The start times in minutes of the services the duties run on one day, by route.
	 */
	private static Map<Long, List<Long>> runMinutes(DutyGenerator.Result result) {
		final Map<Long, List<Long>> minutes = new TreeMap<>();
		result.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.stream().filter(day -> (day.weekdays & 1) != 0).forEach(day -> day.segments.forEach(segment -> minutes.computeIfAbsent(segment.routeId, key -> new ArrayList<>()).add(segment.service / MINUTE)))));
		minutes.values().forEach(Collections::sort);
		return minutes;
	}

	@Test
	void tooFewTrainsRunServicesEvenlyThroughTheDay() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1));
		final DutyGenerator.Result result = DutyGenerator.generateSpread(everyMinute(), sidings, stations(), 30 * MINUTE);
		assertTrue(result.trains <= 1);
		assertTrue(result.uncovered > 0, "most services still have no train");
		final Map<Long, List<Long>> minutes = runMinutes(result);
		for (final List<Long> run : minutes.values()) {
			assertTrue(run.size() > 10);
			// Every k-th service: the same gap between each and the next, and the last late in the day
			final long gap = run.get(1) - run.get(0);
			for (int i = 1; i < run.size(); i++) {
				assertEquals(gap, run.get(i) - run.get(i - 1));
			}
			assertTrue(run.get(run.size() - 1) > 20 * 60, "runs until late in the day, not only early");
		}
	}

	@Test
	void tooFewTrainsDontBunch() {
		// Three trains taken greedily leave one after another and then run as a bunch; spread, they keep equal gaps
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 3));
		final List<Long> greedy = runMinutes(DutyGenerator.generate(everyMinute(), sidings, stations(), 30 * MINUTE)).get(ROUTE_UP);
		final long closest = java.util.stream.IntStream.range(1, greedy.size()).mapToLong(i -> greedy.get(i) - greedy.get(i - 1)).min().orElse(0);
		assertEquals(1, closest, "greedily, two trains run a minute apart");
		final List<Long> spread = runMinutes(DutyGenerator.generateSpread(everyMinute(), sidings, stations(), 30 * MINUTE)).get(ROUTE_UP);
		final long gap = spread.get(1) - spread.get(0);
		assertTrue(gap > 1);
		for (int i = 1; i < spread.size(); i++) {
			assertEquals(gap, spread.get(i) - spread.get(i - 1));
		}
	}

	@Test
	void moreTrainsRunServicesCloserTogether() {
		final long gapOne = gapOf(DutyGenerator.generateSpread(everyMinute(), Collections.singletonList(new DutyGenerator.SidingInput(100, 1)), stations(), 30 * MINUTE));
		final long gapThree = gapOf(DutyGenerator.generateSpread(everyMinute(), Collections.singletonList(new DutyGenerator.SidingInput(100, 3)), stations(), 30 * MINUTE));
		assertTrue(gapThree < gapOne, gapThree + " " + gapOne);
	}

	private static long gapOf(DutyGenerator.Result result) {
		final List<Long> run = runMinutes(result).get(ROUTE_UP);
		return run.get(1) - run.get(0);
	}

	@Test
	void withTrainsEnoughSpreadIsTheSame() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 4));
		final DutyGenerator.Result plain = DutyGenerator.generate(shuttle(), sidings, stations(), 30 * MINUTE);
		final DutyGenerator.Result spread = DutyGenerator.generateSpread(shuttle(), sidings, stations(), 30 * MINUTE);
		assertEquals(0, spread.uncovered);
		assertEquals(plain.trains, spread.trains);
		assertEquals(runMinutes(plain), runMinutes(spread));
	}

	@Test
	void spreadDoesNotDependOnTheOrderOfServices() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 2));
		final List<DutyGenerator.ServiceInput> shuffled = everyMinute();
		Collections.shuffle(shuffled, new Random(7));
		final DutyGenerator.Result ordered = DutyGenerator.generateSpread(everyMinute(), sidings, stations(), 30 * MINUTE);
		final DutyGenerator.Result other = DutyGenerator.generateSpread(shuffled, sidings, stations(), 30 * MINUTE);
		assertEquals(runMinutes(ordered), runMinutes(other));
		assertEquals(ordered.uncovered, other.uncovered);
	}

	/**
	 * A short line with one platform at each end, as the VH line: out from platform 100 to 102 on the hour and every
	 * {@code headway} minutes, back from 102 to 100 two minutes later, each run taking {@code runSeconds}.
	 */
	private static List<DutyGenerator.ServiceInput> shortLine(int headway, long runSeconds) {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		final long[][] plans = {{0, 0}, {runSeconds * 600, runSeconds * 600 + 10000}, {runSeconds * 1000, runSeconds * 1000}};
		for (int minute = 0; minute < 24 * 60; minute += headway) {
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (minute * MINUTE), DepotDuties.ALL_WEEKDAYS, minute * MINUTE, new long[]{100, 101, 102}, plans, new boolean[3], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) ((minute + 2) * MINUTE), DepotDuties.ALL_WEEKDAYS, (minute + 2) * MINUTE, new long[]{102, 103, 100}, plans, new boolean[3], 0, 0, -1, 0));
		}
		return services;
	}

	private static Map<Long, Long> shortLineStations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(100L, 1L);
		stationOf.put(101L, 2L);
		stationOf.put(103L, 2L);
		stationOf.put(102L, 3L);
		return stationOf;
	}

	@Test
	void aLongDayOfOneTrainIsKeptWhole() {
		// Every 6 minutes each way: 480 services a day, all run by one train
		final DutyGenerator.Result result = DutyGenerator.generate(shortLine(6, 84), Collections.singletonList(new DutyGenerator.SidingInput(100, 40)), shortLineStations(), 30 * MINUTE);
		assertEquals(0, result.uncovered);
		assertEquals(1, result.sidings.get(100L).size());
		final int segments = result.sidings.get(100L).get(0).days.stream().mapToInt(day -> day.segments.size()).max().orElse(0);
		assertTrue(segments > 128, String.valueOf(segments));
	}

	@Test
	void aTurnaroundTooShortIsTold() {
		// Back 2 minutes after leaving, with a run of 95 seconds: 25 seconds to turn round, under the 30 needed. Each
		// train then runs one round trip and goes back to its siding, a duty each, though few trains are out at once
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 40));
		final DutyGenerator.Result result = DutyGenerator.generate(shortLine(10, 95), sidings, shortLineStations(), 30 * MINUTE);
		assertTrue(result.sidings.get(100L).size() > 40, "more duties than the siding holds trains");
		assertTrue(result.trains <= 40);
		assertEquals(0, result.uncovered);
		final String shortTurnaround = result.issues.stream().filter(issue -> issue.startsWith("SHORT_TURNAROUND 102 ")).findFirst().orElse("");
		assertEquals("SHORT_TURNAROUND 102 25000 " + DutyGenerator.MIN_TURNAROUND_MILLIS, shortTurnaround, result.issues.toString());

	}

	@Test
	void aTurnaroundLongEnoughIsntTold() {
		final DutyGenerator.Result result = DutyGenerator.generate(shortLine(10, 84), Collections.singletonList(new DutyGenerator.SidingInput(100, 40)), shortLineStations(), 30 * MINUTE);
		assertEquals(0, result.uncovered);
		assertEquals(1, result.trains);
		assertTrue(result.issues.stream().noneMatch(issue -> issue.startsWith("SHORT_TURNAROUND")), result.issues.toString());
	}

	@Test
	void aDutyOfMoreThan128ServicesIsSavedWhole() {
		final DepotDuties duties = new DepotDuties();
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		for (int i = 0; i < 300; i++) {
			day.segments.add(new DepotDuties.Segment(ROUTE_UP, (int) (i * MINUTE), 0, -1));
		}
		slot.days.add(day);
		duties.sidings.put(100L, Collections.singletonList(slot));
		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties.toBytes());
		assertEquals(300, read.sidings.get(100L).get(0).days.get(0).segments.size());
		assertEquals(day.segments, read.sidings.get(100L).get(0).days.get(0).segments);
	}

	@Test
	void aTrainNeverGoingBackStillHasADutyEveryDay() {
		// Both ways at the same minutes from 05:00 to 01:50, one train staying on the line: it ends each week at the other
		// end, so no week repeats the one before, and the week is planned on its own
		final long[][] plans = {{0, 0}, {51000, 61000}, {84000, 84000}};
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (long t = 2 * 60; t <= 22 * 60 + 50; t += 5) {
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (t * MINUTE), DepotDuties.ALL_WEEKDAYS, t * MINUTE, new long[]{100, 101, 102}, plans, new boolean[3], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) (t * MINUTE), DepotDuties.ALL_WEEKDAYS, t * MINUTE, new long[]{102, 103, 100}, plans, new boolean[3], 0, 0, -1, 0));
		}
		final DutyGenerator.Result result = DutyGenerator.generateSpread(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 1)), shortLineStations(), transitsim.plan.RouteTimetable.MAX_STAY_MILLIS);
		int days = 0;
		for (final DepotDuties.Slot slot : result.sidings.get(100L)) {
			for (final DepotDuties.DayDuty day : slot.days) {
				assertFalse(day.segments.isEmpty());
				days |= day.weekdays;
			}
		}
		assertEquals(DepotDuties.ALL_WEEKDAYS, days, "a duty every day of the week");
	}

	@Test
	void aServiceWithNoTrainDoesntKeepATrainFromWaitingAtItsPlatform() {
		// Both ways every 5 minutes, 28 seconds short of turning round at one end: one train has to wait at that
		// platform for the service after next. The services between have no train, so they don't stand there
		final long[][] plans = {{0, 0}, {100000, 110000}, {180000, 190000}, {272000, 272000}};
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (long t = 2 * 60; t <= 22 * 60 + 50; t += 5) {
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (t * MINUTE), DepotDuties.ALL_WEEKDAYS, t * MINUTE, new long[]{100, 101, 102, 104}, plans, new boolean[4], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) (t * MINUTE), DepotDuties.ALL_WEEKDAYS, t * MINUTE, new long[]{105, 103, 106, 100}, plans, new boolean[4], 0, 0, -1, 0));
		}
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(100L, 1L);
		stationOf.put(101L, 2L);
		stationOf.put(103L, 2L);
		stationOf.put(102L, 3L);
		stationOf.put(106L, 3L);
		stationOf.put(104L, 4L);
		stationOf.put(105L, 4L);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 1)), stationOf, transitsim.plan.RouteTimetable.MAX_STAY_MILLIS);
		int days = 0;
		for (final DepotDuties.Slot slot : result.sidings.get(100L)) {
			for (final DepotDuties.DayDuty day : slot.days) {
				assertTrue(day.segments.size() > 100, "the train runs all day: " + day.segments.size());
				days |= day.weekdays;
			}
		}
		assertEquals(DepotDuties.ALL_WEEKDAYS, days);

		// Spread, the one train runs every 4th service of each way, every 20 minutes: a round trip takes just over 15, as
		// each end is 2 seconds short of turning round for the next service. Not one in eleven, as the trains needed for
		// every service would suggest
		final List<Long> run = runMinutes(DutyGenerator.generateSpread(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 1)), stationOf, transitsim.plan.RouteTimetable.MAX_STAY_MILLIS)).get(ROUTE_UP);
		for (int i = 1; i < run.size(); i++) {
			assertEquals(20, run.get(i) - run.get(i - 1), run.toString());
		}
	}

	/**
	 * Down from platform 100 to 104 at the hour, up from 105 (beside 104 at the same station) back to 100 at the given
	 * minutes past; each run 4½ minutes.
	 */
	private static List<DutyGenerator.ServiceInput> changeOfPlatform(long... upMinutes) {
		final long[][] plans = {{0, 0}, {100000, 110000}, {180000, 190000}, {272000, 272000}};
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (6 * 60 * MINUTE), DepotDuties.ALL_WEEKDAYS, 3 * 60 * MINUTE, new long[]{100, 101, 102, 104}, plans, new boolean[4], 0, 0, -1, 0));
		for (final long minutes : upMinutes) {
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) ((6 * 60 + minutes) * MINUTE), DepotDuties.ALL_WEEKDAYS, (3 * 60 + minutes) * MINUTE, new long[]{105, 103, 106, 100}, plans, new boolean[4], 0, 0, -1, 0));
		}
		return services;
	}

	private static Map<Long, Long> changeOfPlatformStations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(100L, 1L);
		stationOf.put(101L, 2L);
		stationOf.put(103L, 2L);
		stationOf.put(102L, 3L);
		stationOf.put(106L, 3L);
		stationOf.put(104L, 4L);
		stationOf.put(105L, 4L);
		return stationOf;
	}

	/**
	 * The services the slot's train runs on Monday, by the minutes past 03:00 they leave.
	 */
	private static List<Long> mondayRun(DutyGenerator.Result result) {
		final List<Long> run = new ArrayList<>();
		result.sidings.getOrDefault(100L, Collections.emptyList()).stream().findFirst().ifPresent(slot -> slot.days.stream().filter(day -> (day.weekdays & 1) != 0).forEach(day -> day.segments.forEach(segment -> run.add(segment.service / MINUTE - 6 * 60))));
		return run;
	}

	@Test
	void aChangeOfPlatformTakesAsLongAsMeasured() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1));
		// Estimated at 2 minutes, the train makes the up service 8 minutes past
		assertEquals(Arrays.asList(0L, 8L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE)));
		// Measured at 5, only the one 15 minutes past
		final Map<Long, Map<Long, Long>> turnTimes = Collections.singletonMap(104L, Collections.singletonMap(105L, 5 * MINUTE));
		final DutyGenerator.PlanInputs measured = new DutyGenerator.PlanInputs(turnTimes, null);
		assertEquals(Arrays.asList(0L, 15L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, measured)));
		// With no way between the platforms the train can't take either
		final DutyGenerator.PlanInputs noWay = new DutyGenerator.PlanInputs(Collections.emptyMap(), null);
		assertEquals(Collections.singletonList(0L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, noWay)));
	}

	@Test
	void aChangeOfPlatformTakesTheNetworksLeastTimeToChangeEnds() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1));
		final Map<Long, Map<Long, Long>> turnTimes = Collections.singletonMap(104L, Collections.singletonMap(105L, 5 * MINUTE));
		// The legs in and out measured at 5 minutes, and changing ends 30 s by default: in time for 15 past
		assertEquals(Arrays.asList(0L, 15L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, new DutyGenerator.PlanInputs(turnTimes, null, null, null, 30000))));
		// The network set to take 10 minutes to change ends (round 4): in time for neither
		assertEquals(Collections.singletonList(0L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, new DutyGenerator.PlanInputs(turnTimes, null, null, null, 10 * MINUTE))));
	}

	@Test
	void aBufferMeasuredRoundIsAddedToTheChangeOfPlatform() {
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1));
		final Map<Long, Map<Long, Long>> turnTimes = Collections.singletonMap(104L, Collections.singletonMap(105L, 5 * MINUTE));
		// Measured at 5 minutes, and trains took 10 minutes longer round than that (D1): not in time for 15 past either
		final transitsim.plan.Buffers buffers = new transitsim.plan.Buffers(Collections.singletonMap(transitsim.plan.Buffers.key(transitsim.plan.Buffers.TURN, 104, 105), 10 * MINUTE));
		final DutyGenerator.PlanInputs buffered = new DutyGenerator.PlanInputs(turnTimes, null, null, buffers);
		assertEquals(Collections.singletonList(0L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, buffered)));
		// Two minutes longer: still in time
		final transitsim.plan.Buffers less = new transitsim.plan.Buffers(Collections.singletonMap(transitsim.plan.Buffers.key(transitsim.plan.Buffers.TURN, 104, 105), 2 * MINUTE));
		assertEquals(Arrays.asList(0L, 15L), mondayRun(DutyGenerator.generate(changeOfPlatform(8, 15), sidings, changeOfPlatformStations(), 30 * MINUTE, new DutyGenerator.PlanInputs(turnTimes, null, null, less))));
	}

	@Test
	void aTrainIsBackInItsSidingOnlyOnceItHasRunThere() {
		// One train, the down service at the hour and again 20 minutes past; nothing to wait for at the far end, so it
		// goes back between them. Out from the siding takes a minute
		final long[][] plans = {{0, 0}, {272000, 272000}};
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (final long minutes : new long[]{0, 20}) {
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) ((6 * 60 + minutes) * MINUTE), DepotDuties.ALL_WEEKDAYS, (3 * 60 + minutes) * MINUTE, new long[]{100, 104}, plans, new boolean[2], 0, 0, -1, 0));
		}
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, 1, Collections.singletonMap(100L, MINUTE)));
		// Back as long as the way out, a minute: in time for the second
		assertEquals(0, DutyGenerator.generate(services, sidings, changeOfPlatformStations(), MINUTE).uncovered);
		// Measured at 20 minutes back from the far end: not
		final DutyGenerator.PlanInputs measured = new DutyGenerator.PlanInputs(null, Collections.singletonMap(100L, Collections.singletonMap(104L, 20 * MINUTE)));
		assertEquals(7, DutyGenerator.generate(services, sidings, changeOfPlatformStations(), MINUTE, measured).uncovered);
	}

	@Test
	void aTrainStayingOutOvernightGoesOnFromTheSamePlatform() {
		// The last down service of the day reaches the far end after the operating day ends; the first up service of
		// the next day leaves 8 minutes into it, from the platform it stopped at or from the one beside it
		final long[][] plans = {{0, 0}, {272000, 272000}};
		for (final long upPlatform : new long[]{104, 105}) {
			final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
			services.add(new DutyGenerator.ServiceInput(ROUTE_UP, (int) (2 * 60 * MINUTE + 1435 * MINUTE), DepotDuties.ALL_WEEKDAYS, 1435 * MINUTE, new long[]{100, 104}, plans, new boolean[2], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) (3 * 60 * MINUTE + 8 * MINUTE), DepotDuties.ALL_WEEKDAYS, 8 * MINUTE, new long[]{upPlatform, 100}, plans, new boolean[2], 0, 0, -1, 0));
			final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, 1, Collections.singletonMap(100L, MINUTE))), changeOfPlatformStations(), transitsim.plan.RouteTimetable.MAX_STAY_MILLIS);
			// From the same platform the train goes on and runs every service; from the other one it can't go on
			if (upPlatform == 104) {
				assertEquals(0, result.uncovered, "up from the same platform");
			} else {
				assertTrue(result.uncovered >= 7, "up from the other platform: " + result.uncovered);
			}
		}
	}

	private static List<DutyGenerator.ServiceInput> shuttle() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			services.add(service(ROUTE_UP, 360 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
			services.add(service(ROUTE_DOWN, 385 + i * 30L, DepotDuties.ALL_WEEKDAYS, 0, 0));
		}
		return services;
	}

	@Test
	void runsLinkedServicesAsOneTrain() {
		final List<DutyGenerator.ServiceInput> services = Arrays.asList(
				service(ROUTE_UP, 360, DepotDuties.ALL_WEEKDAYS, ROUTE_DOWN, 385),
				service(ROUTE_DOWN, 385, DepotDuties.ALL_WEEKDAYS, 0, 0)
		);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, -1)), stations(), 30 * MINUTE);
		final List<DepotDuties.Slot> slots = result.sidings.get(100L);
		assertEquals(1, slots.size());
		final List<DepotDuties.Segment> segments = slots.get(0).days.get(0).segments;
		assertEquals(2, segments.size());
		assertEquals(ROUTE_UP, segments.get(0).routeId);
		assertEquals(ROUTE_DOWN, segments.get(1).routeId);
	}

	@Test
	void keepsWeekendsApartWhenTheyDiffer() {
		final List<DutyGenerator.ServiceInput> services = Arrays.asList(
				service(ROUTE_UP, 360, 0x1F, 0, 0),
				service(ROUTE_UP, 420, 0x60, 0, 0)
		);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100, -1)), stations(), 30 * MINUTE);
		final DepotDuties.Slot slot = result.sidings.get(100L).get(0);
		assertEquals(2, slot.days.size());
		assertEquals(360 * MINUTE, slot.getDuty(0).segments.get(0).service);
		assertEquals(420 * MINUTE, slot.getDuty(6).segments.get(0).service);
	}

	@Test
	void savesAndReadsDuties() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = 0x1F;
		day.stayOnLine = true;
		day.segments.add(new DepotDuties.Segment(ROUTE_UP, 21600000, 0, -1));
		day.segments.add(new DepotDuties.Segment(ROUTE_DOWN, 23100000, 1, 3));
		slot.days.add(day);
		duties.setSlot(100, 2, 1, slot);

		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties.toBytes());
		assertTrue(read.diagramMode);
		assertEquals(2, read.getSlots(100).size());
		assertTrue(read.getSlots(100).get(0).days.isEmpty());
		final DepotDuties.DayDuty readDay = read.getSlots(100).get(1).getDuty(2);
		assertNotNull(readDay);
		assertTrue(readDay.stayOnLine);
		assertEquals(day.segments, readDay.segments);
		assertNull(read.getSlots(100).get(1).getDuty(5));

		// One slot at a time, as the game sends them
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		slot.write(writer);
		read.setSlot(100, 1, 0, DepotDuties.Slot.read(new ByteArrayBinary.Reader(writer.toBytes())));
		assertEquals(1, read.getSlots(100).size());
		assertEquals(day.segments, read.getSlots(100).get(0).getDuty(0).segments);
		read.setSlot(100, 0, -1, null);
		assertTrue(read.isEmpty());
	}
}
