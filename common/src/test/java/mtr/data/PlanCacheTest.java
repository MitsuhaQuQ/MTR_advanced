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
