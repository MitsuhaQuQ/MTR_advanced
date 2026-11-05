package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.io.ByteArrayBinary;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.plan.RouteTimetable;
import transitsim.plan.TimetableBinary;
import transitsim.planning.DutyGenerator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Services set on the running diagram to be joined part of the way by a train sent out of a depot, or left part of the way
 * for a depot: kept with the timetable, planned as the start or the end of a duty, from or to that depot's sidings.
 */
class ServiceOrdersTest {

	private static final long HOUR = 60 * 60 * 1000;
	private static final long MINUTE = 60 * 1000;
	private static final int FIRST = (int) (6 * HOUR);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void ordersAreKeptWithTheTimetable() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3, 4);
		route.timetable.deployOrders.put(FIRST, new RouteTimetable.ServiceOrder(1, 7));
		route.timetable.returnOrders.put(FIRST, new RouteTimetable.ServiceOrder(2, 0));
		route.timetable.returnOrders.put(FIRST + (int) (10 * MINUTE), new RouteTimetable.ServiceOrder(2, 8));
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		TimetableBinary.write(writer, route.timetable);
		final RouteTimetable read = TimetableBinary.read(new ByteArrayBinary.Reader(writer.toBytes()));
		assertEquals(route.timetable.deployOrders, read.deployOrders);
		assertEquals(route.timetable.returnOrders, read.returnOrders);
		assertEquals(1, read.getFirstStop(FIRST));
		assertEquals(2, read.getLastStop(FIRST, 4));
		assertFalse(read.servesStop(FIRST, 0, 4));
		assertTrue(read.servesStop(FIRST, 2, 4));
		assertFalse(read.servesStop(FIRST, 3, 4));
		assertEquals(3, read.getLastStop(FIRST + (int) (20 * MINUTE), 4));
	}

	@Test
	void timetablesWrittenBeforeOrdersStillRead() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		TimetableBinary.write(writer, route.timetable);
		final byte[] bytes = writer.toBytes();
		// One table with no orders ends with two empty counts, which older data doesn't have
		final RouteTimetable read = TimetableBinary.read(new ByteArrayBinary.Reader(Arrays.copyOf(bytes, bytes.length - 2)));
		assertEquals(route.timetable.services, read.services);
		assertTrue(read.deployOrders.isEmpty() && read.returnOrders.isEmpty());
	}

	@Test
	void aTrainCantJoinAServiceAfterItLeavesIt() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3, 4);
		route.timetable.deployOrders.put(FIRST, new RouteTimetable.ServiceOrder(2, 7));
		route.timetable.returnOrders.put(FIRST, new RouteTimetable.ServiceOrder(1, 0));
		route.timetable.sanitize();
		assertTrue(route.timetable.deployOrders.isEmpty());
		assertEquals(1, route.timetable.returnOrders.size());
	}

	/**
	 * A line out and back: up 1, 2, 3, 4 and down 4, 5, 6, 7, every ten minutes for two hours.
	 */
	private static List<DutyGenerator.ServiceInput> services(TestRoute up, TestRoute down) {
		final Map<Long, long[][]> times = new HashMap<>();
		for (final TestRoute route : Arrays.asList(up, down)) {
			final long[][] stopTimes = new long[route.getStops().size()][];
			for (int i = 0; i < stopTimes.length; i++) {
				stopTimes[i] = new long[]{i * 2 * MINUTE, i * 2 * MINUTE + 20000};
			}
			times.put(route.id, stopTimes);
		}
		return DutyGenerator.servicesOf(Arrays.asList(up, down), route -> times.get(route.getRouteId()), 0, route -> fail());
	}

	private static TestRoute line(long id, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		for (long millis = 6 * HOUR; millis < 8 * HOUR; millis += 10 * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}

	/**
	 * @return each siding's duties as their segments, with the siding they are of
	 */
	private static Map<DepotDuties.Segment, Long> sidingOfSegment(DutyGenerator.Result result) {
		final Map<DepotDuties.Segment, Long> sidings = new HashMap<>();
		result.sidings.forEach((sidingId, slots) -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> sidings.put(segment, sidingId)))));
		return sidings;
	}

	private static DepotDuties.DayDuty dutyOf(DutyGenerator.Result result, long routeId, int service) {
		for (final List<DepotDuties.Slot> slots : result.sidings.values()) {
			for (final DepotDuties.Slot slot : slots) {
				for (final DepotDuties.DayDuty day : slot.days) {
					if (day.segments.stream().anyMatch(segment -> segment.routeId == routeId && segment.service == service)) {
						return day;
					}
				}
			}
		}
		return null;
	}

	@Test
	void aServiceSentOutOfADepotStartsADutyFromItsSidingAtThatStop() {
		final TestRoute up = line(1, 1, 2, 3, 4), down = line(2, 4, 5, 6, 7);
		final int service = FIRST + (int) (30 * MINUTE);
		up.timetable.deployOrders.put(service, new RouteTimetable.ServiceOrder(1, 8));
		final List<DutyGenerator.SidingInput> sidings = Arrays.asList(new DutyGenerator.SidingInput(100, 8, null, 7), new DutyGenerator.SidingInput(200, 8, null, 8));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), sidings, Collections.emptyMap(), 30 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));

		final DepotDuties.DayDuty duty = dutyOf(result, 1, service);
		assertNotNull(duty);
		final DepotDuties.Segment first = duty.segments.get(0);
		assertEquals(1, first.routeId);
		assertEquals(service, first.service);
		assertEquals(1, first.fromStop);
		assertEquals(200L, sidingOfSegment(result).get(first));
	}

	@Test
	void aServiceEndingPartOfTheWayEndsItsDuty() {
		final TestRoute up = line(1, 1, 2, 3, 4), down = line(2, 4, 5, 6, 7);
		final int service = FIRST + (int) (40 * MINUTE);
		up.timetable.returnOrders.put(service, new RouteTimetable.ServiceOrder(2, 0));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Collections.singletonList(new DutyGenerator.SidingInput(100, 8, null, 7)), Collections.emptyMap(), 30 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));

		final DepotDuties.DayDuty duty = dutyOf(result, 1, service);
		assertNotNull(duty);
		final DepotDuties.Segment last = duty.segments.get(duty.segments.size() - 1);
		assertEquals(service, last.service);
		assertEquals(2, last.toStop);
		assertFalse(duty.stayOnLine);
	}

	@Test
	void aDepotWithNoSidingHereIsReportedAndAnyTakesItsPlace() {
		final TestRoute up = line(1, 1, 2, 3, 4), down = line(2, 4, 5, 6, 7);
		up.timetable.deployOrders.put(FIRST, new RouteTimetable.ServiceOrder(0, 99));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Collections.singletonList(new DutyGenerator.SidingInput(100, 8, null, 7)), Collections.emptyMap(), 30 * MINUTE);
		assertEquals(0, result.uncovered);
		assertTrue(result.issues.stream().anyMatch(issue -> issue.startsWith("DEPLOY_DEPOT 99")), result.issues.toString());
	}
}
