package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.sowcerext.model.ModelCluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects the train models created during a reload, prepares their vertex data on worker threads,
 * then makes the GL calls in one pass on the render thread.
 */
public class DeferredModelUpload {

    private static final List<SowcerModelAgent> PENDING = new ArrayList<>();
    private static boolean active = false;

    public static void begin() {
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void add(SowcerModelAgent agent) {
        PENDING.add(agent);
    }

    public static void finish() {
        active = false;
        if (PENDING.isEmpty()) return;

        long startNanos = System.nanoTime();
        List<Runnable> tasks = new ArrayList<>(PENDING.size());
        List<Supplier<ModelCluster>> prepared = new ArrayList<>(PENDING.size());
        for (int i = 0; i < PENDING.size(); i++) {
            SowcerModelAgent agent = PENDING.get(i);
            int index = i;
            prepared.add(null);
            tasks.add(() -> {
                Supplier<ModelCluster> result = agent.prepareUpload();
                synchronized (prepared) {
                    prepared.set(index, result);
                }
            });
        }
        TrainModelPreloader.runParallel(tasks);
        LoadTimings.add("uploadPrepare", startNanos);

        startNanos = System.nanoTime();
        synchronized (prepared) {
            for (int i = 0; i < PENDING.size(); i++) {
                try {
                    PENDING.get(i).completeUpload(prepared.get(i));
                } catch (Exception e) {
                    Main.LOGGER.error("Failed uploading train model", e);
                    MtrModelRegistryUtil.recordLoadingError("Failed uploading train model", e);
                }
            }
        }
        LoadTimings.add("uploadGl", startNanos);
        PENDING.clear();
    }
}
