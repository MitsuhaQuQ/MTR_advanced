package transitsim.motion;

import org.junit.jupiter.api.Test;
import transitsim.run.RunMotion;

import static org.junit.jupiter.api.Assertions.*;

class PlatformDwellTest {

	@Test
	void clientStopCheckDistinguishesDwellFromPassingAndParking() {
		final MotionFixture train = new MotionFixture().rail(30, 0, 1).rail(100, 10, 1).rail(30, 0, 1);
		train.clientSide = true;
		train.onRoute = true;
		train.nextStoppingIndex = 1;
		train.railProgress = 130;
		assertTrue(PlatformDwell.isAtStop(train, train));

		train.speed = 0.1F;
		assertFalse(PlatformDwell.isAtStop(train, train));

		train.speed = 0;
		train.railProgress = 129;
		assertFalse(PlatformDwell.isAtStop(train, train));

		train.nextStoppingIndex = 2;
		train.railProgress = 160;
		assertFalse(PlatformDwell.isAtStop(train, train));

		train.onRoute = false;
		train.nextStoppingIndex = 1;
		train.railProgress = 130;
		assertFalse(PlatformDwell.isAtStop(train, train));
	}

	@Test
	void departureIsAvailableOnlyWithNoHoldAndAuthorityPastThePlatform() {
		final MotionFixture train = new MotionFixture().rail(30, 0, 1).rail(100, 10, 1).rail(100, 0, 1);
		train.onRoute = true;
		train.railProgress = 130;
		train.nextStoppingIndex = 1;
		train.nextPlatformIndex = 1;
		train.elapsedDwellTicks = 97;
		train.heldAt = tick -> true;
		RunMotion.tick(train, train, train, train, train, 230);
		assertEquals(1, train.dwellRail);
		assertEquals(-1, train.departureTicks);

		train.heldAt = tick -> false;
		RunMotion.tick(train, train, train, train, train, 130);
		assertEquals(-1, train.departureTicks);
		RunMotion.tick(train, train, train, train, train, 230);
		assertEquals(2, train.departureTicks);

		train.elapsedDwellTicks = 99;
		RunMotion.tick(train, train, train, train, train, 230);
		assertEquals(-1, train.dwellRail);
	}

	/**
	 * A train standing with its doors open whose stop time drops below the time it has stood (its planned departure moved
	 * earlier: a duty swapped or taken over, a dispatch undone) shuts its doors before it leaves (user, 2026-11-15: now and
	 * then a train left with its doors open).
	 */
	@Test
	void aTrainWhoseStopTimeDropsShutsItsDoorsBeforeLeaving() {
		final MotionFixture train = new MotionFixture().rail(30, 0, 1).rail(100, 10, 1).rail(100, 0, 1);
		train.onRoute = true;
		train.railProgress = 130;
		train.nextStoppingIndex = 1;
		train.nextPlatformIndex = 1;
		final int[] total = {400};
		train.totalDwell = () -> total[0];
		// Standing with its doors well open
		for (int tick = 0; tick < 70; tick++) {
			RunMotion.tick(train, train, train, train, train, 230);
		}
		assertTrue(train.doorTarget);
		assertTrue(train.doorValue > 0.5F, "doors open " + train.doorValue);
		assertEquals(0, train.startUps);
		// Its departure moved earlier: the stop time is now shorter than the time it has stood
		total[0] = 40;
		int ticks = 0;
		float doorsBefore = train.doorValue;
		while (train.startUps == 0 && ticks < 400) {
			doorsBefore = train.doorValue;
			RunMotion.tick(train, train, train, train, train, 230);
			ticks++;
		}
		assertEquals(1, train.startUps, "it leaves");
		assertEquals(0F, doorsBefore, "its doors shut before it leaves, " + ticks + " ticks after its stop time dropped");
		assertTrue(ticks > 1, "not at once");
	}

	@Test
	void aStopShortOfThePlatformIsNotAVisit() {
		final MotionFixture train = new MotionFixture().rail(30, 0, 1).rail(100, 10, 1);
		train.onRoute = true;
		train.railProgress = 100;
		RunMotion.tick(train, train, train, train, train, 100);
		assertEquals(-1, train.dwellRail);
	}

	@Test
	void legacyMotionAlsoWithholdsDepartureWhileTheNextRailIsBlocked() {
		final MotionFixture train = new MotionFixture().rail(30, 0, 1).rail(100, 10, 1).rail(100, 0, 1);
		train.onRoute = true;
		train.railProgress = 130;
		train.nextStoppingIndex = 1;
		train.nextPlatformIndex = 1;
		train.elapsedDwellTicks = 97;
		train.blockedAt = (tick, rail) -> true;
		TrainMotion.tick(train, train, train, train, train, 1);
		assertEquals(1, train.dwellRail);
		assertEquals(-1, train.departureTicks);
		train.blockedAt = (tick, rail) -> false;
		TrainMotion.tick(train, train, train, train, train, 1);
		assertEquals(2, train.departureTicks);
	}
}
