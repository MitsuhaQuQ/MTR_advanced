package transitsim.motion;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The motion rules taken out of the host (S2) against the rules as they were before (decoupling document 5.2, S2): tick
 * by tick, every part of the state must come out the same.
 */
class TrainMotionEquivalenceTest {

	private static final int TICKS = 3000;
	private static final float[] STEPS = {1, 1.7F};

	@Test
	void stopsAtStations() {
		final MotionFixture fixture = compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture1 -> {
		});
		assertTrue(fixture.stopsReached > 0);
	}

	@Test
	void stopsAtStationsOnTheClient() {
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture -> fixture.clientSide = true);
	}

	@Test
	void followsSpeedCurves() {
		final MotionFixture fixture = compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture1 -> fixture1.speedCurve = true);
		assertTrue(fixture.stopsReached > 0);
	}

	@Test
	void cruisesUnderACap() {
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture -> {
			fixture.speedCurve = true;
			fixture.cruiseCap = 0.15F;
		});
	}

	@Test
	void brakesForABlockedRail() {
		for (final boolean speedCurve : new boolean[]{false, true}) {
			final MotionFixture fixture = compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture1 -> {
				fixture1.speedCurve = speedCurve;
				fixture1.blockedAt = (tick, rail) -> rail == 3 && tick >= 60 && tick < 700;
			});
			assertTrue(fixture.stopsReached > 0);
		}
	}

	@Test
	void waitsWhileHeld() {
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture -> fixture.heldAt = tick -> tick < 1200);
	}

	@Test
	void turnsBack() {
		final MotionFixture fixture = compare(MotionFixture::new, fixture1 -> fixture1
				.rail(30, 0, 0.3F)
				.rail(200, 0, 0.6F)
				.rail(60, 3, 0.3F, false, true)
				.rail(60, 0, 0.3F)
				.rail(200, 0, 0.6F)
				.rail(60, 3, 0.3F, false, false)
				.rail(30, 0, 0.3F), fixture1 -> {
		});
		assertTrue(fixture.reversed || fixture.startUps > 2);
	}

	@Test
	void repeatsByTurningBack() {
		compare(MotionFixture::new, fixture -> {
			line(fixture);
			fixture.rail(60, 2, 0.3F, false, true);
			fixture.repeatIndex1 = 2;
			fixture.repeatIndex2 = 6;
		}, fixture -> {
		});
	}

	@Test
	void repeatsRoundALoop() {
		final MotionFixture fixture = compare(MotionFixture::new, fixture1 -> {
			line(fixture1);
			fixture1.rail(80, 0, 0.6F).rail(80, 0, 0.6F);
			fixture1.repeatIndex1 = 2;
			fixture1.repeatIndex2 = 6;
		}, fixture1 -> fixture1.speedCurve = true);
		assertTrue(fixture.laps > 0);
	}

	@Test
	void isDrivenByHand() {
		final Integer[] notches = new Integer[TICKS];
		notches[1] = 2;
		notches[400] = 0;
		notches[700] = -1;
		notches[900] = 2;
		notches[1500] = -2;
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture -> {
			fixture.currentlyManual = true;
			fixture.notchFrom = notches;
		});
	}

	@Test
	void movesContinuously() {
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture -> fixture.continuousMovement = true);
	}

	@Test
	void goesBackToTheSidingOrStaysOnTheLine() {
		final MotionFixture fixture = compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture1 -> {
		});
		assertTrue(fixture.ridersCleared > 0);
		compare(MotionFixture::new, TrainMotionEquivalenceTest::line, fixture1 -> fixture1.keepsOnLineAtEnd = true);
	}

	/**
	 * A siding, two stations with a platform each and the way back into the siding.
	 */
	private static void line(MotionFixture fixture) {
		fixture
				.rail(30, 0, 0.3F)
				.rail(300, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(400, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(200, 0, 0.4F);
	}

	/**
	 * Runs the same train through both sets of rules, at each step size, and checks they agree every tick.
	 *
	 * @return the train moved by the new rules, at the last step size
	 */
	private static MotionFixture compare(Supplier<MotionFixture> create, Consumer<MotionFixture> path, Consumer<MotionFixture> setUp) {
		MotionFixture current = null;
		for (final float step : STEPS) {
			final MotionFixture legacy = create.get();
			current = create.get();
			for (final MotionFixture fixture : new MotionFixture[]{legacy, current}) {
				path.accept(fixture);
				setUp.accept(fixture);
			}
			for (int i = 0; i < TICKS; i++) {
				legacy.step(true, step);
				current.step(false, step);
				assertEquals(legacy.describe(), current.describe(), "step " + step);
			}
			assertTrue(current.startUps > 0, "the train never left");
		}
		return current;
	}
}
