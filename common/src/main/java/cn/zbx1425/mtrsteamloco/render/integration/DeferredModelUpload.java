package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.sowcerext.model.ModelCluster;
import cn.zbx1425.sowcerext.model.RawModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects the train models created during a reload, prepares their vertex data on worker threads,
 * then makes the GL calls in one pass on the render thread. Models wait as objects, which take far more
 * memory than they do once uploaded, so a batch goes as soon as the waiting models reach a share of the heap.
 */
public class DeferredModelUpload {

    private static final List<SowcerModelAgent> PENDING = new ArrayList<>();
    private static final int HEAP_SHARE_FOR_PENDING = 8;
    private static final long BYTES_PER_VERTEX = 128;
    private static final long BYTES_PER_FACE = 48;
    private static boolean active = false;
    private static long pendingBytes = 0;

    public static void begin() {
        active = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void add(SowcerModelAgent agent, RawModel model) {
        PENDING.add(agent);
        pendingBytes += model.getVertexCount() * BYTES_PER_VERTEX + model.getFaceCount() * BYTES_PER_FACE;
        if (pendingBytes > Runtime.getRuntime().maxMemory() / HEAP_SHARE_FOR_PENDING) {
            uploadPending();
        }
    }

    public static void finish() {
        active = false;
        uploadPending();
    }

    private static void uploadPending() {
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

        startNanos = System.nanoTime();
        List<Runnable> compactTasks = new ArrayList<>(PENDING.size());
        PENDING.forEach(agent -> compactTasks.add(agent::compactModel));
        TrainModelPreloader.runParallel(compactTasks);
        LoadTimings.add("compact", startNanos);
        PENDING.clear();
        pendingBytes = 0;
    }
}
