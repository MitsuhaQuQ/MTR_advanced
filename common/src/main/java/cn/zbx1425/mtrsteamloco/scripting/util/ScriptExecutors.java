package cn.zbx1425.mtrsteamloco.scripting.util;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.scripting.ScriptHolderBase;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for java.util.concurrent.Executors in scripts (injected as "Executors").
 * Resource packs schedule their own timers, but a JS context must never be entered by two threads at once,
 * so each run keeps its timing here and is executed on the script thread.
 */
public class ScriptExecutors {

    private static final List<ScriptScheduledPool> POOLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger THREAD_INDEX = new AtomicInteger();

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return create(corePoolSize);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return create(1);
    }

    public static ExecutorService newFixedThreadPool(int threads) {
        return create(threads);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return create(1);
    }

    public static ExecutorService newCachedThreadPool() {
        return create(1);
    }

    // Called when scripts are reset, so pools of unloaded scripts stop calling into closed contexts
    public static void shutdownAll() {
        for (ScriptScheduledPool pool : POOLS) {
            pool.shutdownNow();
        }
        POOLS.clear();
    }

    private static ScriptScheduledPool create(int size) {
        ScriptScheduledPool pool = new ScriptScheduledPool(Math.max(1, size));
        POOLS.add(pool);
        return pool;
    }

    private static Runnable onScriptThread(Runnable command) {
        return () -> {
            try {
                ScriptHolderBase.runOnScriptThread(() -> {
                    command.run();
                    return null;
                });
            } catch (Exception ex) {
                // Keep periodic tasks alive, like a failing render call does
                Main.LOGGER.error("Error in ANTE Resource Pack scheduled task", ex);
            }
        };
    }

    private static class ScriptScheduledPool extends ScheduledThreadPoolExecutor {

        ScriptScheduledPool(int size) {
            super(size, runnable -> {
                Thread thread = new Thread(runnable, "ANTE-Script-Scheduler-" + THREAD_INDEX.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }

        // execute and submit go through schedule, so these four cover every entry
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return super.schedule(onScriptThread(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return super.schedule((Callable<V>) () -> ScriptHolderBase.runOnScriptThread(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return super.scheduleAtFixedRate(onScriptThread(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return super.scheduleWithFixedDelay(onScriptThread(command), initialDelay, delay, unit);
        }
    }
}
