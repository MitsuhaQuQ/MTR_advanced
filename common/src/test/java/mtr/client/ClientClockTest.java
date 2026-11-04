package mtr.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's clock as the client sees it (G2).
 */
class ClientClockTest {

	@Test
	void theFirstTimeIsTakenAtOnce() {
		final ClientClock clock = new ClientClock();
		// The server is 30 seconds ahead; the message took 40 ms
		clock.onServerTime(1_030_000, 1_000_040);
		assertEquals(1_029_960 + 1000, clock.serverMillis(1_001_000));
	}

	@Test
	void theLeastDelayedOfTheLastFewCounts() {
		final ClientClock clock = new ClientClock();
		clock.onServerTime(100_000, 100_200);
		clock.onServerTime(110_000, 110_030);
		clock.onServerTime(120_000, 120_500);
		// The difference of the message that took 30 ms is caught up on, by a tenth of the time going by
		final long later = clock.serverMillis(200_000);
		assertEquals(200_000 - 30, later);
	}

	@Test
	void aSmallChangeNeverTakesTimeBack() {
		final ClientClock clock = new ClientClock();
		clock.onServerTime(1_000_000, 1_000_000);
		long last = clock.serverMillis(1_000_000);
		// The server turns out to be 2 seconds behind, which the client takes in slowly
		for (int i = 0; i < 7; i++) {
			clock.onServerTime(1_000_000 + i * 10_000L - 2000, 1_000_000 + i * 10_000L);
		}
		for (long local = 1_070_000; local < 1_200_000; local += 50) {
			final long now = clock.serverMillis(local);
			assertTrue(now >= last, local + " " + now + " " + last);
			last = now;
		}
		assertEquals(1_200_000 - 2000, clock.serverMillis(1_200_000));
	}

	@Test
	void aLargeChangeIsTakenAtOnce() {
		final ClientClock clock = new ClientClock();
		clock.onServerTime(1_000_000, 1_000_000);
		// The server's clock is set an hour on
		for (int i = 1; i <= 6; i++) {
			clock.onServerTime(1_000_000 + i * 10_000L + 3_600_000, 1_000_000 + i * 10_000L);
		}
		assertEquals(1_070_000 + 3_600_000, clock.serverMillis(1_070_000));
	}
}
