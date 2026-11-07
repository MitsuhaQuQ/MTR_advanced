package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.io.ByteArrayBinary;
import transitsim.plan.OperatingDay;
import transitsim.plan.RouteTimetable;
import transitsim.plan.TimetableBinary;
import transitsim.planning.DutyGenerator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Services of a table set not to run: kept with the timetable and able to run again, but no train is sent for them and
 * nothing is planned for them.
 */
class SuspendedServicesTest {

	private static final long HOUR = 60 * 60 * 1000;
	private static final long MINUTE = 60 * 1000;
	private static final int SEVEN = (int) (7 * HOUR);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aServiceThatDoesntRunIsntFoundToSendATrainFor() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		final RouteTimetable timetable = route.timetable;
		final long day = 10 * (long) OperatingDay.MILLIS_PER_DAY;
		assertEquals(day + SEVEN, timetable.getNextService(day + SEVEN, day + SEVEN + MINUTE, millis -> false));

		timetable.suspended.add(SEVEN);
		assertTrue(timetable.isSuspended(day + SEVEN));
		assertEquals(0, timetable.getNextService(day + SEVEN, day + SEVEN + MINUTE, millis -> false));
		assertFalse(timetable.getServicesBetween(day + SEVEN, day + SEVEN + MINUTE).contains(day + SEVEN));
		assertEquals(0, timetable.getLatestService(day + SEVEN - MINUTE, day + SEVEN, millis -> false));
		// Still in the table, with the services around it running
		assertTrue(timetable.services.contains(SEVEN));
		assertEquals(day + SEVEN + 10 * MINUTE, timetable.getNextService(day + SEVEN, day + SEVEN + 11 * MINUTE, millis -> false));

		// Running again
		timetable.suspended.remove(SEVEN);
		assertEquals(day + SEVEN, timetable.getNextService(day + SEVEN, day + SEVEN + MINUTE, millis -> false));
	}

	@Test
	void aServiceTakenOutOfTheTableLosesItsMark() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		route.timetable.suspended.add(SEVEN);
		route.timetable.services.remove((Integer) SEVEN);
		route.timetable.sanitize();
		assertTrue(route.timetable.suspended.isEmpty());
	}

	@Test
	void servicesThatDontRunAreKeptWithTheTimetableAndOldDataStillReads() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		route.timetable.suspended.add(SEVEN);
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		TimetableBinary.write(writer, route.timetable);
		final byte[] bytes = writer.toBytes();
		assertEquals(Collections.singleton(SEVEN), TimetableBinary.read(new ByteArrayBinary.Reader(bytes)).suspended);

		// Data written before has no count of them at the end: one table, one byte for the count of one service and four for
		// it, before the 12 bytes of its least performance, which is newer still
		final RouteTimetable old = TimetableBinary.read(new ByteArrayBinary.Reader(Arrays.copyOf(bytes, bytes.length - 5 - 12)));
		assertTrue(old.suspended.isEmpty());
		assertEquals(route.timetable.services, old.services);
	}

	@Test
	void theLeastPerformanceIsKeptWithTheTimetableAndOldDataHasNone() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		route.timetable.minAcceleration = 0.002F;
		route.timetable.minDeceleration = 0.0025F;
		route.timetable.minMaxSpeed = 1.5F;
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		TimetableBinary.write(writer, route.timetable);
		final byte[] bytes = writer.toBytes();
		final RouteTimetable read = TimetableBinary.read(new ByteArrayBinary.Reader(bytes));
		assertTrue(read.hasMinPerformance());
		assertEquals(0.002F, read.minAcceleration);
		assertEquals(0.0025F, read.minDeceleration);
		assertEquals(1.5F, read.minMaxSpeed);
		// Written before: none, to be set from the vehicles
		final RouteTimetable old = TimetableBinary.read(new ByteArrayBinary.Reader(Arrays.copyOf(bytes, bytes.length - 12)));
		assertFalse(old.hasMinPerformance());
		assertEquals(route.timetable.services, old.services);
	}

	@Test
	void nothingIsPlannedForAServiceThatDoesntRun() {
		final TestRoute route = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		route.timetable.suspended.add(SEVEN);
		final long[][] times = {{0, 20000}, {2 * MINUTE, 2 * MINUTE + 20000}, {4 * MINUTE, 4 * MINUTE + 20000}};
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(Collections.singletonList(route), ignored -> times, 0, ignored -> fail());
		assertTrue(services.stream().noneMatch(service -> service.service == SEVEN));
		assertEquals(route.timetable.services.size() - 1, services.size());
	}
}
