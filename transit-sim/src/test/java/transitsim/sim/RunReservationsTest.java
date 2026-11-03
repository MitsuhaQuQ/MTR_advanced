package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunReservationsTest {

	private static final List<RunReservations.Move> EASTWARDS = Arrays.asList(new RunReservations.Move(1, 2), new RunReservations.Move(2, 3));
	private static final List<RunReservations.Move> WESTWARDS = Arrays.asList(new RunReservations.Move(3, 2), new RunReservations.Move(2, 1));

	/**
	 * One tick for a train that has asked for its run: true if it was granted.
	 */
	private static boolean ask(RunReservations reservations, long trainId, List<RunReservations.Move> moves, long plannedMillis, long currentMillis) {
		reservations.request(trainId, moves, plannedMillis);
		reservations.resolve(currentMillis);
		return reservations.takeGrant(trainId);
	}

	@Test
	void stopsTrainsMeetingHeadOn() {
		final RunReservations reservations = new RunReservations();
		assertTrue(ask(reservations, 1, EASTWARDS, 0, 0));
		reservations.hold(1, EASTWARDS);
		assertFalse(ask(reservations, 2, WESTWARDS, 0, 0));
	}

	@Test
	void keepsTheRunWhileTheTrainIsStopped() {
		// R01: a train held by a signal between stops keeps setting what it holds, however long it stands
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		reservations.resolve(0);
		for (int tick = 1; tick <= 200; tick++) {
			reservations.hold(1, EASTWARDS);
			assertFalse(ask(reservations, 2, WESTWARDS, 0, tick * 50L));
		}
	}

	@Test
	void letsGoOnlyOfRailsTheTailHasLeft() {
		// R02: the head is past rail 1-2 but the tail is still on it, so the train holds it
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		reservations.resolve(0);
		reservations.hold(1, EASTWARDS);
		assertFalse(ask(reservations, 2, Collections.singletonList(new RunReservations.Move(2, 1)), 0, 50));
		// Once the tail has left rail 1-2, it is free the other way, and rail 2-3 still isn't
		reservations.hold(1, Collections.singletonList(new RunReservations.Move(2, 3)));
		assertTrue(ask(reservations, 2, Collections.singletonList(new RunReservations.Move(2, 1)), 0, 100));
		reservations.hold(1, Collections.singletonList(new RunReservations.Move(2, 3)));
		assertFalse(reservations.canHold(3, Collections.singletonList(new RunReservations.Move(3, 2))));
	}

	@Test
	void letsTrainsGoTheSameWay() {
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		assertTrue(ask(reservations, 2, EASTWARDS, 0, 0));
		reservations.hold(1, EASTWARDS);
		reservations.hold(2, EASTWARDS);
		assertFalse(ask(reservations, 3, WESTWARDS, 0, 50));
	}

	@Test
	void dropsTrainsThatAreGone() {
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		reservations.resolve(0);
		// Train 1 no longer sets anything, as it was removed
		assertTrue(ask(reservations, 2, WESTWARDS, 0, 50));
	}

	@Test
	void releasesAtOnce() {
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		reservations.release(1);
		assertTrue(reservations.canHold(2, WESTWARDS));
	}

	@Test
	void aTrainDoesNotBlockItself() {
		final RunReservations reservations = new RunReservations();
		reservations.hold(1, EASTWARDS);
		assertTrue(ask(reservations, 1, WESTWARDS, 0, 0));
	}

	@Test
	void grantsTheTrainDueFirstWhicheverAsksFirst() {
		// R03: two trains asking head on in the same tick get the same answer in either order
		for (final boolean eastFirst : new boolean[]{true, false}) {
			final RunReservations reservations = new RunReservations();
			if (eastFirst) {
				reservations.request(1, EASTWARDS, 2000);
				reservations.request(2, WESTWARDS, 1000);
			} else {
				reservations.request(2, WESTWARDS, 1000);
				reservations.request(1, EASTWARDS, 2000);
			}
			reservations.resolve(0);
			assertFalse(reservations.takeGrant(1));
			assertTrue(reservations.takeGrant(2));
		}
	}

	@Test
	void breaksTiesByTrain() {
		final RunReservations reservations = new RunReservations();
		reservations.request(7, WESTWARDS, 1000);
		reservations.request(3, EASTWARDS, 1000);
		reservations.resolve(0);
		assertTrue(reservations.takeGrant(3));
		assertFalse(reservations.takeGrant(7));
	}

	@Test
	void aTrainWaitingLongGoesFirst() {
		final RunReservations reservations = new RunReservations();
		// Train 9 holds the line eastwards for over a minute while train 2 waits to go west
		long millis = 0;
		for (; millis <= RunReservations.AGING_MILLIS + 10000; millis += 1000) {
			reservations.hold(9, EASTWARDS);
			assertFalse(ask(reservations, 2, WESTWARDS, 100000, millis));
		}
		// Train 9 has gone; train 3, due earlier, asks to go east in the same tick as train 2, which has waited longer
		reservations.request(2, WESTWARDS, 100000);
		reservations.request(3, EASTWARDS, 50000);
		reservations.resolve(millis);
		assertTrue(reservations.takeGrant(2));
		assertFalse(reservations.takeGrant(3));
	}

	@Test
	void forgetsHowLongATrainWaitedOnceItStopsAsking() {
		final RunReservations reservations = new RunReservations();
		for (long millis = 0; millis <= RunReservations.AGING_MILLIS; millis += 1000) {
			reservations.hold(9, EASTWARDS);
			assertFalse(ask(reservations, 2, WESTWARDS, 100000, millis));
		}
		// Train 2 stopped asking for a tick, so it starts waiting again
		reservations.resolve(RunReservations.AGING_MILLIS + 1000);
		reservations.request(2, WESTWARDS, 100000);
		reservations.request(3, EASTWARDS, 50000);
		reservations.resolve(RunReservations.AGING_MILLIS + 2000);
		assertTrue(reservations.takeGrant(3));
		assertFalse(reservations.takeGrant(2));
	}
}
