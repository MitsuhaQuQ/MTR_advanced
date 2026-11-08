package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Work split over the shared threads gives what doing it one part after another gives (optimisation round 5, step 4).
 */
class WorkPoolTest {

	private static final class Part {
		private final int seed;
		private long result;

		private Part(int seed) {
			this.seed = seed;
		}

		private void work() {
			long value = seed;
			for (int i = 0; i < 10000; i++) {
				value = value * 6364136223846793005L + 1442695040888963407L;
			}
			result = value;
		}
	}

	private static List<Part> parts() {
		final List<Part> parts = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			parts.add(new Part(i));
		}
		return parts;
	}

	@Test
	void theResultIsTheSameAsOneAfterAnother() {
		final List<Part> serial = parts();
		serial.forEach(Part::work);
		final List<Part> pooled = parts();
		WorkPool.forEach(pooled, Part::work);
		for (int i = 0; i < serial.size(); i++) {
			assertEquals(serial.get(i).result, pooled.get(i).result);
		}
	}

	@Test
	void everyPartIsDoneBeforeAFailureIsThrown() {
		final AtomicInteger done = new AtomicInteger();
		final List<Integer> items = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			items.add(i);
		}
		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> WorkPool.forEach(items, item -> {
			if (item == 7) {
				throw new IllegalArgumentException("part 7");
			}
			done.incrementAndGet();
		}));
		assertEquals("part 7", thrown.getMessage());
		assertEquals(99, done.get());
	}

	@Test
	void workSplitWhileThePoolIsBusyWithLongTasksIsDoneByTheCaller() throws Exception {
		final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		final List<java.util.concurrent.Future<?>> longTasks = new ArrayList<>();
		for (int i = 0; i < WorkPool.THREADS; i++) {
			longTasks.add(WorkPool.submit(() -> {
				try {
					release.await();
				} catch (InterruptedException ignored) {
				}
			}));
		}
		try {
			final List<Part> parts = parts();
			WorkPool.forEach(parts, Part::work);
			final List<Part> serial = parts();
			serial.forEach(Part::work);
			for (int i = 0; i < serial.size(); i++) {
				assertEquals(serial.get(i).result, parts.get(i).result);
			}
		} finally {
			release.countDown();
		}
		for (final java.util.concurrent.Future<?> longTask : longTasks) {
			longTask.get();
		}
	}

	@Test
	void aLongTaskCancelledIsInterrupted() throws Exception {
		final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.Future<?> task = WorkPool.submit(() -> {
			started.countDown();
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				interrupted.countDown();
			}
		});
		started.await();
		task.cancel(true);
		assertTrue(interrupted.await(10, java.util.concurrent.TimeUnit.SECONDS));
	}

	@Test
	void workSplitAgainFromThePoolIsDoneThereRatherThanWaitingForItself() {
		final List<Integer> outer = new ArrayList<>();
		for (int i = 0; i < WorkPool.THREADS * 3; i++) {
			outer.add(i);
		}
		final AtomicInteger done = new AtomicInteger();
		WorkPool.forEach(outer, item -> WorkPool.forEach(new ArrayList<>(Collections.nCopies(5, item)), inner -> done.incrementAndGet()));
		assertEquals(outer.size() * 5, done.get());
	}
}
