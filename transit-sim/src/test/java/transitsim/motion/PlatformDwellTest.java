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
