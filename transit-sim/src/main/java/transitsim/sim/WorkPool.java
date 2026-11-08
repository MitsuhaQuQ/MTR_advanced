package transitsim.sim;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The threads shared by the whole server (optimisation round 5, steps 4 and 5): the cores but one, at most 8, or as many
 * as {@code -Dmtr.workThreads} says, below the server thread's priority. Long tasks, as a depot's path generation, are
 * {@link #submit submitted}; work split into independent parts is done with {@link #forEach}, each part working only on
 * its own item, so the result is the same as doing them one after another, whatever the number of threads.
 */
public final class WorkPool {

	public static final int THREADS = Math.max(1, Math.min(64, Integer.getInteger("mtr.workThreads", Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)))));
	private static final ThreadLocal<Boolean> IN_POOL = new ThreadLocal<>();
	private static volatile ThreadPoolExecutor executor;
	private static final AtomicLong ORDER = new AtomicLong();
	private static final int SHORT = 0;
	private static final int LONG = 1;

	private WorkPool() {
	}

	/**
	 * Runs a long task on the pool, as many at once as it has threads. Cancelling the future interrupts the task.
	 */
	public static Future<?> submit(Runnable task) {
		final FutureTask<Void> futureTask = new FutureTask<>(task, null);
		getExecutor().execute(new Queued(LONG, futureTask));
		return futureTask;
	}

	/**
	 * Does the task for each item and returns once all are done. The calling thread takes items too, and waits only for
	 * the items others took, never for a free thread: while the pool's threads are busy with long tasks, the caller does
	 * them all itself. Done one after another where there is one item or thread, or when called from the pool itself.
	 *
	 * @throws RuntimeException the first a task threw, once all are done
	 */
	public static <T> void forEach(List<T> items, Consumer<T> task) {
		if (items.size() <= 1 || THREADS <= 1 || Boolean.TRUE.equals(IN_POOL.get())) {
			items.forEach(task);
			return;
		}
		final AtomicInteger next = new AtomicInteger();
		final CountDownLatch done = new CountDownLatch(items.size());
		final AtomicReference<RuntimeException> thrown = new AtomicReference<>();
		final Runnable worker = () -> {
			int index;
			while ((index = next.getAndIncrement()) < items.size()) {
				try {
					task.accept(items.get(index));
				} catch (RuntimeException e) {
					thrown.compareAndSet(null, e);
				} catch (Throwable e) {
					thrown.compareAndSet(null, new IllegalStateException(e));
				} finally {
					done.countDown();
				}
			}
		};
		final int helpers = Math.min(THREADS, items.size() - 1);
		// Ahead of the long tasks waiting, as the caller is waiting for these
		for (int i = 0; i < helpers; i++) {
			getExecutor().execute(new Queued(SHORT, worker));
		}
		worker.run();
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		if (thrown.get() != null) {
			throw thrown.get();
		}
	}

	private static ThreadPoolExecutor getExecutor() {
		if (executor == null) {
			synchronized (WorkPool.class) {
				if (executor == null) {
					final AtomicInteger count = new AtomicInteger();
					executor = new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), runnable -> {
						final Thread thread = new Thread(() -> {
							IN_POOL.set(true);
							runnable.run();
						}, "MTR work pool " + count.incrementAndGet());
						thread.setDaemon(true);
						thread.setPriority(Thread.NORM_PRIORITY - 1);
						return thread;
					});
				}
			}
		}
		return executor;
	}

	/**
	 * A task waiting for a thread: parts of work split by {@link #forEach} first, then in the order they came.
	 */
	private static final class Queued implements Runnable, Comparable<Queued> {

		private final int kind;
		private final long order = ORDER.getAndIncrement();
		private final Runnable task;

		private Queued(int kind, Runnable task) {
			this.kind = kind;
			this.task = task;
		}

		@Override
		public void run() {
			task.run();
		}

		@Override
		public int compareTo(Queued other) {
			return kind != other.kind ? Integer.compare(kind, other.kind) : Long.compare(order, other.order);
		}
	}
}
