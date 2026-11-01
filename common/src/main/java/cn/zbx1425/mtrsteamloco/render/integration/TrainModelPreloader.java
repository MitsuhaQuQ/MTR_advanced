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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Parses the OBJ files of custom trains in parallel before MTR loads the trains one by one.
 * A file that fails here is left out, so the normal loading path reports the error as usual.
 */
public class TrainModelPreloader {

    private static final int MAX_THREADS = 8;
    private static final int TIMEOUT_MINUTES = 5;

    public static void preload(ResourceManager resourceManager) {
        long startMillis = System.currentTimeMillis();
        Set<ResourceLocation> objLocations = new HashSet<>();
        scanCustomTrains(resourceManager, objLocations);
        if (objLocations.isEmpty()) return;

        // Workers only read the atlas, so it has to be complete before they start
        AtlasManager atlasManager = MainClient.atlasManager;
        Map<ResourceLocation, Map<String, RawModel>> results = new ConcurrentHashMap<>();
        List<Runnable> tasks = new ArrayList<>(objLocations.size());
        for (ResourceLocation objLocation : objLocations) {
            tasks.add(() -> {
                try {
                    results.put(objLocation, ObjModelLoader.parseModels(resourceManager, objLocation, atlasManager));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        runParallel(tasks);

        ObjModelLoader.setPreloadedModels(Map.copyOf(results));
        Main.LOGGER.info("Preloaded " + results.size() + " of " + objLocations.size() + " OBJ model(s) in "
                + (System.currentTimeMillis() - startMillis) + " ms");
    }

    /**
     * Runs the tasks on worker threads and waits for them. A failed task is skipped, the caller falls back for it.
     */
    public static void runParallel(List<Runnable> tasks) {
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, MAX_THREADS));
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
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

    private static void scanCustomTrains(ResourceManager resourceManager, Set<ResourceLocation> objLocations) {
        readJson(resourceManager, new ResourceLocation("mtr", ICustomResources.CUSTOM_RESOURCES_ID + ".json"), config -> {
            if (!config.has(ICustomResources.CUSTOM_TRAINS_KEY)) return;
            for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject(ICustomResources.CUSTOM_TRAINS_KEY).entrySet()) {
                try {
                    JsonObject train = entry.getValue().getAsJsonObject();
                    if (!train.has(ICustomResources.CUSTOM_TRAINS_MODEL) || !train.has(ICustomResources.CUSTOM_TRAINS_MODEL_PROPERTIES)) continue;
                    String model = train.get(ICustomResources.CUSTOM_TRAINS_MODEL).getAsString();

                    if (model.contains("|")) {
                        String[] pairs = model.split("\\|");
                        for (int i = 0; i < pairs.length / 2; i++) objLocations.add(new ResourceLocation(pairs[i * 2]));
                    } else if (model.toLowerCase(Locale.ROOT).endsWith(".obj")) {
                        objLocations.add(new ResourceLocation(model));
                    } else {
                        continue;
                    }

                    readJson(resourceManager, new ResourceLocation(train.get(ICustomResources.CUSTOM_TRAINS_MODEL_PROPERTIES).getAsString()), properties -> {
                        if (properties.has("atlasIndex")) loadAtlas(resourceManager, properties.get("atlasIndex").getAsString());
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void loadAtlas(ResourceManager resourceManager, String atlasIndex) {
        try {
            MainClient.atlasManager.load(resourceManager, new ResourceLocation(atlasIndex));
        } catch (IOException | RuntimeException ignored) {
        }
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
