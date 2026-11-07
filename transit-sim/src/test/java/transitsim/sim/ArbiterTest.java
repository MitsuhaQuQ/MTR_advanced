package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of contracts document section 4 for every kind of resource (S4b). Holders here are granted for good, as a
 * simulator grants them, by renewing in each step what they still hold.
 */
class ArbiterTest {

	private static final long NOW = 1000000;

	@Test
	void aTrackIsOneTrainsAtATime() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.request(1, claims(Arbiter.Claim.track(10L)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.track(10L)), NOW + 1);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(1));
		assertFalse(arbiter.takeGrant(2));
	}

	@Test
	void aSingleTrackTakesTrainsOneWayAtATime() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.request(1, claims(Arbiter.Claim.singleTrack(10L, true)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.singleTrack(10L, true)), NOW + 1);
		arbiter.request(3, claims(Arbiter.Claim.singleTrack(10L, false)), NOW + 2);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(1));
		assertTrue(arbiter.takeGrant(2));
		assertFalse(arbiter.takeGrant(3));
	}

	@Test
	void aTurnoutTakesOneRouteAtATime() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.request(1, claims(Arbiter.Claim.turnout(10L, 1)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.turnout(10L, 2)), NOW + 1);
		arbiter.request(3, claims(Arbiter.Claim.turnout(10L, 1)), NOW + 2);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(1));
		assertFalse(arbiter.takeGrant(2));
		assertTrue(arbiter.takeGrant(3));
	}

	@Test
	void aBerthTakesAsManyTrainsAsItHasRoomFor() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		for (long holder = 1; holder <= 3; holder++) {
			arbiter.request(holder, claims(Arbiter.Claim.berth(10L, 2)), NOW + holder);
		}
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(1));
		assertTrue(arbiter.takeGrant(2));
		assertFalse(arbiter.takeGrant(3));
	}

	@Test
	void aRequestGetsAllItAsksForOrNothing() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.hold(1, claims(Arbiter.Claim.track(11L)));
		arbiter.request(2, claims(Arbiter.Claim.track(10L), Arbiter.Claim.track(11L)), NOW);
		resolve(arbiter, NOW);
		assertFalse(arbiter.takeGrant(2));
		assertTrue(arbiter.getHoldings(2).isEmpty());
		// Track 10 is still free for another train
		assertTrue(arbiter.canHold(3, claims(Arbiter.Claim.track(10L))));
	}

	@Test
	void theTrainDueFirstGoesFirstWhateverOrderTheyAskIn() {
		for (final List<Long> order : Arrays.asList(Arrays.asList(1L, 2L), Arrays.asList(2L, 1L))) {
			final Arbiter<Long> arbiter = new Arbiter<>();
			// Train 2 is due to leave first
			order.forEach(holder -> arbiter.request(holder, claims(Arbiter.Claim.track(10L)), holder == 2 ? NOW : NOW + 1));
			resolve(arbiter, NOW);
			assertTrue(arbiter.takeGrant(2));
			assertFalse(arbiter.takeGrant(1));
		}
	}

	@Test
	void whatATrainStandsOnKeepsOthersOffWithoutAGrant() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		// A train driven by hand, which the arbiter doesn't grant anything
		arbiter.setOccupied(9, Collections.singletonList(10L));
		arbiter.request(1, claims(Arbiter.Claim.track(10L)), NOW);
		resolve(arbiter, NOW);
		assertFalse(arbiter.takeGrant(1));
		arbiter.setOccupied(9, Collections.emptyList());
		arbiter.request(1, claims(Arbiter.Claim.track(10L)), NOW);
		resolve(arbiter, NOW + 50);
		assertTrue(arbiter.takeGrant(1));
	}

	@Test
	void aStoppedTrainKeepsWhatItHolds() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.request(1, claims(Arbiter.Claim.track(10L)), NOW);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(1));
		// Standing still for a long time, renewing what it holds, doesn't let another train in
		for (int step = 1; step <= 100; step++) {
			arbiter.hold(1, claims(Arbiter.Claim.track(10L)));
			arbiter.request(2, claims(Arbiter.Claim.track(10L)), NOW);
			resolve(arbiter, NOW + step * 1000L);
			assertFalse(arbiter.takeGrant(2));
		}
		assertEquals(1, arbiter.getHoldings(1).size());
	}

	@Test
	void aPredictionDoesNotCountForGrants() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.setPredicted(1, Collections.singletonList(10L));
		arbiter.request(2, claims(Arbiter.Claim.track(10L)), NOW);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeGrant(2));
		assertEquals(Collections.singleton(10L), arbiter.getPredicted(1));
	}

	@Test
	void aGrantIsOnlyTakenBackBeforeTheTrainEntersIt() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		arbiter.hold(1, claims(Arbiter.Claim.track(10L), Arbiter.Claim.track(11L)));
		// Moving, or with its head past the start of the grant, it can't be taken back
		assertFalse(arbiter.cancel(1, false, true, claims(Arbiter.Claim.track(10L))));
		assertFalse(arbiter.cancel(1, true, false, claims(Arbiter.Claim.track(10L))));
		assertEquals(2, arbiter.getHoldings(1).size());
		assertTrue(arbiter.cancel(1, true, true, claims(Arbiter.Claim.track(10L))));
		assertEquals(1, arbiter.getHoldings(1).size());
		assertTrue(arbiter.canHold(2, claims(Arbiter.Claim.track(11L))));
	}

	@Test
	void trainsWaitingForEachOtherAreReportedOnce() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		// Two trains each on a track the other needs next: a deadlock once neither has been granted anything for a while, as
		// trains following each other round a loop wait for each other too while they still run
		for (int step = 0; step < 4; step++) {
			arbiter.hold(1, claims(Arbiter.Claim.track(10L)));
			arbiter.hold(2, claims(Arbiter.Claim.track(11L)));
			arbiter.request(1, claims(Arbiter.Claim.track(10L), Arbiter.Claim.track(11L)), NOW);
			arbiter.request(2, claims(Arbiter.Claim.track(11L), Arbiter.Claim.track(10L)), NOW);
			resolve(arbiter, NOW + (step == 0 ? 0 : Arbiter.AGING_MILLIS + step * 1000L));
			if (step == 1) {
				assertEquals(Collections.singletonList(new TreeSet<>(Arrays.asList(1L, 2L))), arbiter.takeDeadlocks());
			} else {
				assertTrue(arbiter.takeDeadlocks().isEmpty());
			}
		}
		assertTrue(arbiter.isFrozen(10L));
		assertTrue(arbiter.isFrozen(11L));
		// Nothing was let go
		assertEquals(1, arbiter.getHoldings(1).size());
		assertEquals(1, arbiter.getHoldings(2).size());
	}

	@Test
	void noNewTrainIsLetIntoADeadlock() {
		final Arbiter<Long> arbiter = new Arbiter<>();
		// Train 1 and train 2 are deadlocked on single tracks 10 and 11, held their own way
		arbiter.hold(1, claims(Arbiter.Claim.singleTrack(10L, true)));
		arbiter.hold(2, claims(Arbiter.Claim.singleTrack(11L, false)));
		arbiter.request(1, claims(Arbiter.Claim.singleTrack(10L, true), Arbiter.Claim.singleTrack(11L, true)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.singleTrack(11L, false), Arbiter.Claim.singleTrack(10L, false)), NOW);
		resolve(arbiter, NOW);
		assertTrue(arbiter.takeDeadlocks().isEmpty());
		arbiter.hold(1, claims(Arbiter.Claim.singleTrack(10L, true)));
		arbiter.hold(2, claims(Arbiter.Claim.singleTrack(11L, false)));
		arbiter.request(1, claims(Arbiter.Claim.singleTrack(10L, true), Arbiter.Claim.singleTrack(11L, true)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.singleTrack(11L, false), Arbiter.Claim.singleTrack(10L, false)), NOW);
		resolve(arbiter, NOW + Arbiter.AGING_MILLIS);
		assertEquals(1, arbiter.takeDeadlocks().size());
		// Train 3 following train 1 the same way could share track 10, but isn't let into the deadlock
		arbiter.hold(1, claims(Arbiter.Claim.singleTrack(10L, true)));
		arbiter.hold(2, claims(Arbiter.Claim.singleTrack(11L, false)));
		arbiter.request(1, claims(Arbiter.Claim.singleTrack(10L, true), Arbiter.Claim.singleTrack(11L, true)), NOW);
		arbiter.request(2, claims(Arbiter.Claim.singleTrack(11L, false), Arbiter.Claim.singleTrack(10L, false)), NOW);
		arbiter.request(3, claims(Arbiter.Claim.singleTrack(10L, true)), NOW);
		resolve(arbiter, NOW + Arbiter.AGING_MILLIS + 1000);
		assertFalse(arbiter.takeGrant(3));
		// Once the host removes train 2, the deadlock is gone
		arbiter.remove(2);
		arbiter.hold(1, claims(Arbiter.Claim.singleTrack(10L, true)));
		arbiter.request(1, claims(Arbiter.Claim.singleTrack(10L, true), Arbiter.Claim.singleTrack(11L, true)), NOW);
		resolve(arbiter, NOW + Arbiter.AGING_MILLIS + 2000);
		assertTrue(arbiter.takeGrant(1));
		assertFalse(arbiter.isFrozen(10L));
	}

	private static void resolve(Arbiter<Long> arbiter, long millis) {
		arbiter.resolve(millis);
	}

	@SafeVarargs
	private static List<Arbiter.Claim<Long>> claims(Arbiter.Claim<Long>... claims) {
		return Arrays.asList(claims);
	}
}
