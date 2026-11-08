package transitsim.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The threads shared by the whole server for work split into independent parts (optimisation round 5, step 4): the
 * cores but one, at most 8, below the server thread's priority. Each part works only on its own item, so the result is
 * the same as doing them one after another, whatever the number of threads.
 */
public final class WorkPool {

	public static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
	private static final ThreadLocal<Boolean> IN_POOL = new ThreadLocal<>();
	private static volatile ExecutorService executor;

	private WorkPool() {
	}

	/**
	 * Does the task for each item, on the pool's threads, and returns once all are done. Done one after another where
	 * there is one item or thread, or when called from the pool itself.
	 *
	 * @throws RuntimeException the first a task threw, once all are done
	 */
	public static <T> void forEach(List<T> items, Consumer<T> task) {
		if (items.size() <= 1 || THREADS <= 1 || Boolean.TRUE.equals(IN_POOL.get())) {
			items.forEach(task);
			return;
		}
		final List<Callable<Void>> callables = new ArrayList<>(items.size());
		for (final T item : items) {
			callables.add(() -> {
				task.accept(item);
				return null;
			});
		}
		final List<Future<Void>> futures;
		try {
			futures = getExecutor().invokeAll(callables);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		RuntimeException thrown = null;
		for (final Future<Void> future : futures) {
			try {
				future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			} catch (ExecutionException e) {
				if (thrown == null) {
					thrown = e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
				}
			}
		}
		if (thrown != null) {
			throw thrown;
		}
	}

	private static ExecutorService getExecutor() {
		if (executor == null) {
			synchronized (WorkPool.class) {
				if (executor == null) {
					final AtomicInteger count = new AtomicInteger();
					executor = Executors.newFixedThreadPool(THREADS, runnable -> {
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
}
