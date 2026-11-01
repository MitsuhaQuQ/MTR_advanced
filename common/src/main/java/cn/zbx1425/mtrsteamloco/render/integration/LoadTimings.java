package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adds up how long each phase of a custom resource reload takes on the render thread.
 */
public class LoadTimings {

    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();

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
    }
}
