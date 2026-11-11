package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The depots of a planning unit found again at once plan their duties once ({@link LineDuties.PlanCache}).
 */
class PlanCacheTest {

	@Test
	void evictionKeepsRecentlyUsedPlans() throws Exception {
		LineDuties.PlanCache<String> cache = new LineDuties.PlanCache<>();
		for (int i = 0; i < 64; i++) { final String key = "p" + i; cache.get(key, () -> key); }
		assertEquals("p0", cache.get("p0", () -> "wrong"));
		cache.get("p64", () -> "p64");
		assertEquals("p0", cache.get("p0", () -> "wrong"));
		assertEquals("rebuilt", cache.get("p1", () -> "rebuilt"));
	}

	@Test
	void cachePressureNeverDuplicatesAnInFlightPlan() throws Exception {
		LineDuties.PlanCache<String> cache = new LineDuties.PlanCache<>();
		java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		try {
			java.util.concurrent.Future<String> first = workers.submit(() -> cache.get("busy", () -> {
				calls.incrementAndGet(); started.countDown(); release.await(); return "shared";
			}));
			org.junit.jupiter.api.Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
			for (int i = 0; i < 80; i++) { final String key = "p" + i; cache.get(key, () -> key); }
			java.util.concurrent.Future<String> second = workers.submit(() -> cache.get("busy", () -> { calls.incrementAndGet(); return "duplicate"; }));
			release.countDown();
			assertEquals("shared", first.get(5, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals("shared", second.get(5, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(1, calls.get());
		} finally { release.countDown(); workers.shutdownNow(); }
	}

	@Test
	void depotsAskingAtOnceShareOnePlan() throws Exception {
		final LineDuties.PlanCache<String> cache = new LineDuties.PlanCache<>();
		final AtomicInteger planned = new AtomicInteger();
		final CountDownLatch planning = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<String> other = new AtomicReference<>();
		final Thread first = new Thread(() -> {
			try {
				other.set(cache.get("unit", () -> {
					planned.incrementAndGet();
					planning.countDown();
					release.await();
					return "plan";
				}));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		first.start();
		planning.await();
		// A second depot asks while the first plans: it waits for that plan
		final Thread second = new Thread(() -> {
			try {
				assertEquals("plan", cache.get("unit", () -> {
					planned.incrementAndGet();
					return "another";
				}));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		second.start();
		Thread.sleep(100);
		release.countDown();
		first.join();
		second.join();
		assertEquals("plan", other.get());
		assertEquals(1, planned.get());
		// Asked again later from the same inputs, the plan is the one made
		assertEquals("plan", cache.get("unit", () -> "again"));
		// Other inputs plan anew
		assertEquals("changed", cache.get("unit changed", () -> "changed"));
	}

	@Test
	void aPlanStoppedForANewerRequestIsPlannedByTheOthers() throws Exception {
		final LineDuties.PlanCache<String> cache = new LineDuties.PlanCache<>();
		assertThrows(CancellationException.class, () -> cache.get("unit", () -> {
			throw new CancellationException("restarted");
		}));
		// Not kept: the next to ask plans it
		assertEquals("plan", cache.get("unit", () -> "plan"));
	}
}
