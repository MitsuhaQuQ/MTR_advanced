package mtr.advanced;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunReservationsTest {

	private static final List<RunReservations.Move> EASTWARDS = Arrays.asList(new RunReservations.Move(1, 2), new RunReservations.Move(2, 3));
	private static final List<RunReservations.Move> WESTWARDS = Arrays.asList(new RunReservations.Move(3, 2), new RunReservations.Move(2, 1));

	@Test
	void stopsTrainsMeetingHeadOn() {
		final RunReservations reservations = new RunReservations();
		reservations.book(1, EASTWARDS);
		assertFalse(reservations.canBook(2, WESTWARDS));
		reservations.tick();
		// Still booked once the tick is over, while the train renews it
		assertFalse(reservations.canBook(2, WESTWARDS));
		reservations.book(1, EASTWARDS);
		reservations.tick();
		assertFalse(reservations.canBook(2, Collections.singletonList(new RunReservations.Move(2, 1))));
	}

	@Test
	void letsTrainsGoTheSameWay() {
		final RunReservations reservations = new RunReservations();
		reservations.book(1, EASTWARDS);
		assertTrue(reservations.canBook(2, EASTWARDS));
		reservations.book(2, EASTWARDS);
		assertFalse(reservations.canBook(3, WESTWARDS));
	}

	@Test
	void forgetsBookingsNotRenewed() {
		final RunReservations reservations = new RunReservations();
		reservations.book(1, EASTWARDS);
		reservations.tick();
		reservations.tick();
		assertTrue(reservations.canBook(2, WESTWARDS));
	}

	@Test
	void letsGoOfRailsAlreadyPassed() {
		final RunReservations reservations = new RunReservations();
		reservations.book(1, EASTWARDS);
		reservations.tick();
		// Past the first rail, only the rest is renewed
		reservations.book(1, Collections.singletonList(new RunReservations.Move(2, 3)));
		reservations.tick();
		assertTrue(reservations.canBook(2, Collections.singletonList(new RunReservations.Move(2, 1))));
		assertFalse(reservations.canBook(2, Collections.singletonList(new RunReservations.Move(3, 2))));
	}

	@Test
	void aTrainDoesNotBlockItself() {
		final RunReservations reservations = new RunReservations();
		reservations.book(1, EASTWARDS);
		assertTrue(reservations.canBook(1, WESTWARDS));
	}
}
