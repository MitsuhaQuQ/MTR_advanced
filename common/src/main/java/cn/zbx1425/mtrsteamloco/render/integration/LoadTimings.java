package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adds up how long each phase of a custom resource reload takes on the render thread.
 */
public class LoadTimings {

    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();
    private static final long BYTES_PER_MEGABYTE = 1024 * 1024;

    public static synchronized void add(String phase, long startNanos) {
        long[] total = TOTALS.computeIfAbsent(phase, ignored -> new long[2]);
        total[0] += System.nanoTime() - startNanos;
        total[1]++;
    }

    public static synchronized void report() {
        Main.LOGGER.info("Load timings: " + TOTALS.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()[0] / 1000000 + " ms x" + entry.getValue()[1])
                .collect(Collectors.joining(", ")));
        TOTALS.clear();
        reportMemory();
    }

    /**
     * What the operating system shows is how much the heap has grown to, which says little about what is in use. The size
     * after the last collection is the closest thing to what was really needed.
     */
    private static void reportMemory() {
        long used = 0;
        long committed = 0;
        long peak = 0;
        long afterCollection = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                used += pool.getUsage().getUsed();
                committed += pool.getUsage().getCommitted();
                peak += pool.getPeakUsage().getUsed();
                MemoryUsage collectionUsage = pool.getCollectionUsage();
                afterCollection += collectionUsage == null ? 0 : collectionUsage.getUsed();
            }
        }
        Main.LOGGER.info("Heap: " + used / BYTES_PER_MEGABYTE + " MB used, " + committed / BYTES_PER_MEGABYTE + " MB committed, "
                + peak / BYTES_PER_MEGABYTE + " MB peak, " + afterCollection / BYTES_PER_MEGABYTE + " MB after the last collection, "
                + Runtime.getRuntime().maxMemory() / BYTES_PER_MEGABYTE + " MB maximum");
    }
}
