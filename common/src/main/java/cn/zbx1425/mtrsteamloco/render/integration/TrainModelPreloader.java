package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.sowcerext.model.RawModel;
import cn.zbx1425.sowcerext.model.loader.ObjModelLoader;
import cn.zbx1425.sowcerext.reuse.AtlasManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.ICustomResources;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Parses the OBJ and JSON files of custom trains in parallel before MTR loads the trains one by one.
 * A file that fails here is left out, so the normal loading path reports the error as usual.
 */
public class TrainModelPreloader {

    private static final int MAX_THREADS = 24;
    private static final int TIMEOUT_MINUTES = 5;
    private static final int SLOWEST_FILES_TO_LOG = 10;
    /**
     * Everything prepared here stays in memory until its train is loaded, which a small heap may not have room for.
     */
    private static final long MIN_HEAP_TO_PREPARE_BYTES = 6L << 30;

    private static final Map<ResourceLocation, Queue<List<JsonObject>>> PRELOADED_JSON = new ConcurrentHashMap<>();

    public static void preload(ResourceManager resourceManager) {
        long startMillis = System.currentTimeMillis();
        long phaseStartNanos = System.nanoTime();
        Map<ResourceLocation, Integer> objUses = new HashMap<>();
        List<ResourceLocation> jsonUses = new ArrayList<>();
        PRELOADED_JSON.clear();
        scanCustomTrains(resourceManager, objUses, jsonUses);
        LoadTimings.add("preloadScan", phaseStartNanos);
        if (objUses.isEmpty() && jsonUses.isEmpty()) return;

        // Workers only read the atlas, so it has to be complete before they start
        AtlasManager atlasManager = MainClient.atlasManager;
        Map<ResourceLocation, Map<String, RawModel>> results = new ConcurrentHashMap<>();
        Map<ResourceLocation, Long> fileMillis = new ConcurrentHashMap<>();
        List<Runnable> tasks = new ArrayList<>(objUses.size() + jsonUses.size());
        for (ResourceLocation objLocation : objUses.keySet()) {
            tasks.add(() -> {
                long fileStartMillis = System.currentTimeMillis();
                try {
                    results.put(objLocation, ObjModelLoader.parseModels(resourceManager, objLocation, atlasManager));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    fileMillis.put(objLocation, System.currentTimeMillis() - fileStartMillis);
                }
            });
        }
        // Trains change what they are given, so each use gets a parsed copy of its own
        boolean canPrepare = Runtime.getRuntime().maxMemory() >= MIN_HEAP_TO_PREPARE_BYTES;
        if (canPrepare) {
            for (ResourceLocation jsonLocation : jsonUses) {
                tasks.add(() -> preloadJson(resourceManager, jsonLocation));
            }
        }
        phaseStartNanos = System.nanoTime();
        runParallel(tasks);
        LoadTimings.add("preloadParse", phaseStartNanos);

        ObjModelLoader.setPreloadedModels(results);
        if (canPrepare) {
            phaseStartNanos = System.nanoTime();
            List<Runnable> copyTasks = new ArrayList<>(objUses.size());
            objUses.forEach((location, uses) -> copyTasks.add(() -> ObjModelLoader.prepareCopies(location, uses)));
            runParallel(copyTasks);
            LoadTimings.add("preloadCopy", phaseStartNanos);
        }
        Main.LOGGER.info("Preloaded " + results.size() + " of " + objUses.size() + " OBJ model(s) and "
                + PRELOADED_JSON.values().stream().mapToInt(Queue::size).sum() + " of " + jsonUses.size() + " JSON file(s) in "
                + (System.currentTimeMillis() - startMillis) + " ms on " + getThreadCount() + " thread(s)");
        Main.LOGGER.info("Slowest OBJ models: " + fileMillis.entrySet().stream()
                .sorted(Map.Entry.<ResourceLocation, Long>comparingByValue().reversed())
                .limit(SLOWEST_FILES_TO_LOG)
                .map(entry -> entry.getKey() + " " + entry.getValue() + " ms " + getFaceCount(results.get(entry.getKey())) + " faces")
                .collect(Collectors.joining(", ")));
    }

    /**
     * @return one use of a preloaded file, with an object for each resource pack that has it, or null to read it the normal way
     */
    public static List<JsonObject> takeJson(ResourceLocation location) {
        Queue<List<JsonObject>> uses = PRELOADED_JSON.get(location);
        return uses == null ? null : uses.poll();
    }

    public static void clearJson() {
        PRELOADED_JSON.clear();
    }

    private static void preloadJson(ResourceManager resourceManager, ResourceLocation location) {
        long startNanos = System.nanoTime();
        List<JsonObject> objects = new ArrayList<>();
        try {
            for (Resource resource : UtilitiesClient.getResources(resourceManager, location)) {
                try (InputStream stream = Utilities.getInputStream(resource)) {
                    objects.add(new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
                }
                Utilities.closeResource(resource);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PRELOADED_JSON.computeIfAbsent(location, key -> new ConcurrentLinkedQueue<>()).add(objects);
        LoadTimings.add("preloadJson", startNanos);
    }

    private static int getThreadCount() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, MAX_THREADS));
    }

    private static int getFaceCount(Map<String, RawModel> models) {
        if (models == null) return 0;
        return models.values().stream()
                .flatMap(model -> model.meshList.values().stream())
                .mapToInt(mesh -> mesh.faces.size())
                .sum();
    }

    /**
     * Runs the tasks on worker threads and waits for them. A failed task is skipped, the caller falls back for it.
     */
    public static void runParallel(List<Runnable> tasks) {
        ExecutorService executor = Executors.newFixedThreadPool(getThreadCount(), runnable -> {
            Thread thread = new Thread(runnable, "ANTE Model Loader");
            thread.setDaemon(true);
            return thread;
        });
        for (Runnable task : tasks) {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError ignored) {
                }
            });
        }
        executor.shutdown();

        try {
            if (!executor.awaitTermination(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                Main.LOGGER.warn("Parallel model loading timed out");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void scanCustomTrains(ResourceManager resourceManager, Map<ResourceLocation, Integer> objUses, List<ResourceLocation> jsonUses) {
        readJson(resourceManager, new ResourceLocation("mtr", ICustomResources.CUSTOM_RESOURCES_ID + ".json"), config -> {
            if (!config.has(ICustomResources.CUSTOM_TRAINS_KEY)) return;
            for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject(ICustomResources.CUSTOM_TRAINS_KEY).entrySet()) {
                try {
                    JsonObject train = entry.getValue().getAsJsonObject();
                    if (!train.has(ICustomResources.CUSTOM_TRAINS_MODEL) || !train.has(ICustomResources.CUSTOM_TRAINS_MODEL_PROPERTIES)) continue;
                    String model = train.get(ICustomResources.CUSTOM_TRAINS_MODEL).getAsString();
                    ResourceLocation propertiesLocation = new ResourceLocation(train.get(ICustomResources.CUSTOM_TRAINS_MODEL_PROPERTIES).getAsString());

                    if (model.contains("|")) {
                        String[] pairs = model.split("\\|");
                        for (int i = 0; i < pairs.length / 2; i++) objUses.merge(new ResourceLocation(pairs[i * 2]), 1, Integer::sum);
                    } else if (model.toLowerCase(Locale.ROOT).endsWith(".obj")) {
                        objUses.merge(new ResourceLocation(model), 1, Integer::sum);
                    } else {
                        jsonUses.add(new ResourceLocation(model));
                        jsonUses.add(propertiesLocation);
                        continue;
                    }
                    jsonUses.add(propertiesLocation);

                    readJson(resourceManager, propertiesLocation, properties -> {
                        if (properties.has("atlasIndex")) loadAtlas(resourceManager, properties.get("atlasIndex").getAsString());
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void loadAtlas(ResourceManager resourceManager, String atlasIndex) {
        long startNanos = System.nanoTime();
        try {
            MainClient.atlasManager.load(resourceManager, new ResourceLocation(atlasIndex));
        } catch (IOException | RuntimeException ignored) {
        }
        LoadTimings.add("preloadAtlas", startNanos);
    }

    private static void readJson(ResourceManager resourceManager, ResourceLocation location, Consumer<JsonObject> callback) {
        try {
            for (Resource resource : UtilitiesClient.getResources(resourceManager, location)) {
                try (InputStream stream = Utilities.getInputStream(resource)) {
                    callback.accept(new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
                } catch (Exception ignored) {
                }
                Utilities.closeResource(resource);
            }
        } catch (Exception ignored) {
        }
    }
}
