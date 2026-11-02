package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.CustomResources;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.gui.ErrorScreen;
import cn.zbx1425.mtrsteamloco.render.integration.DeferredModelUpload;
import cn.zbx1425.mtrsteamloco.render.integration.LoadTimings;
import cn.zbx1425.mtrsteamloco.render.integration.MtrModelRegistryUtil;
import cn.zbx1425.mtrsteamloco.render.integration.TrainModelPreloader;
import cn.zbx1425.sowcer.ContextCapability;
import cn.zbx1425.sowcer.util.GlStateTracker;
import cn.zbx1425.sowcerext.model.loader.ObjModelLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.ICustomResources;
import cn.zbx1425.mtrsteamloco.scripting.ScriptHolderBase;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

@Mixin(mtr.client.CustomResources.class)
public class CustomResourcesMixin {

    @Inject(at = @At("HEAD"), method = "reload(Lnet/minecraft/server/packs/resources/ResourceManager;)V")
    private static void reloadHead(ResourceManager manager, CallbackInfo ci) {
        ContextCapability.checkContextVersion();
        String glVersionStr = "OpenGL " + ContextCapability.contextVersion / 10 + "."
                + ContextCapability.contextVersion % 10;
        Main.LOGGER.info("ANTE detected " + glVersionStr + (ContextCapability.isGL4ES ? " (GL4ES)." : "."));

        GlStateTracker.capture();
        MtrModelRegistryUtil.loadingErrorList.clear();
        MtrModelRegistryUtil.resourceManager = manager;

        CustomResources.reset(manager);
        TrainModelPreloader.preload(manager);
        DeferredModelUpload.begin();

        Main.LOGGER.info("MTR has started loading custom resources. (including MTR-ANTE train models and optimizations)");
    }

    @Inject(at = @At("TAIL"), method = "reload(Lnet/minecraft/server/packs/resources/ResourceManager;)V")
    private static void reloadTail(ResourceManager manager, CallbackInfo ci) {
        DeferredModelUpload.finish();
        ObjModelLoader.clearPreloadedModels();
        TrainModelPreloader.clearJson();
        LoadTimings.report();
        CustomResources.resetComponents();
        if (!MtrModelRegistryUtil.loadingErrorList.isEmpty()) {
            Minecraft.getInstance().setScreen(ErrorScreen.createScreen(MtrModelRegistryUtil.loadingErrorList, Minecraft.getInstance().screen));
        }
        GlStateTracker.restore();

        Main.LOGGER.info("MTR-ANTE has finished loading custom resources.");
    }

    @Inject(at = @At("HEAD"), method = "readResource", cancellable = true)
    private static void readResource(ResourceManager manager, String path, Consumer<JsonObject> callback, CallbackInfo ci) {
        if (path.toLowerCase(Locale.ROOT).endsWith(".obj") || path.contains("|")) {
            JsonObject dummyBbData = MtrModelRegistryUtil.createDummyBbDataPack(path, capturedTextureId, capturedFlipV, captureBbModelPreload);
            readDepth++;
            try {
                callback.accept(dummyBbData);
            } finally {
                readDepth--;
            }
            ci.cancel();
            return;
        }

        ResourceLocation location = new ResourceLocation(path);
        // The custom resources file holds the trains, whose model file holds their properties, which is where a train is built
        readDepth++;
        List<JsonObject> preloaded = TrainModelPreloader.takeJson(location);
        try {
            if (preloaded == null) {
                long lookupStartNanos = System.nanoTime();
                List<Resource> resources = UtilitiesClient.getResources(manager, location);
                LoadTimings.add("jsonLookup", lookupStartNanos);
                resources.forEach(resource -> {
                    try (final InputStream stream = Utilities.getInputStream(resource)) {
                        long jsonStartNanos = System.nanoTime();
                        JsonObject modelObject = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                        LoadTimings.add("json", jsonStartNanos);
                        acceptJson(path, modelObject, callback);
                    } catch (Exception e) { Main.LOGGER.error("On behalf of MTR: Parsing JSON " + path, e); }
                    try {
                        Utilities.closeResource(resource);
                    } catch (IOException e) { Main.LOGGER.error("On behalf of MTR: Closing resource " + path, e); }
                });
            } else {
                for (JsonObject modelObject : preloaded) {
                    try {
                        acceptJson(path, modelObject, callback);
                    } catch (Exception e) { Main.LOGGER.error("On behalf of MTR: Parsing JSON " + path, e); }
                }
            }
        } catch (Exception ignored) { }
        readDepth--;
        ci.cancel();
    }

    @Unique
    private static void acceptJson(String path, JsonObject modelObject, Consumer<JsonObject> callback) {
        if (path.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
            JsonObject dummyBbData = MtrModelRegistryUtil.createDummyBbDataPack(path, capturedTextureId, capturedFlipV, captureBbModelPreload);
            modelObject.add("dummyBbData", dummyBbData);
        }
        long buildStartNanos = System.nanoTime();
        callback.accept(modelObject);
        if (readDepth == TRAIN_BUILD_READ_DEPTH) {
            LoadTimings.add("trainBuild", buildStartNanos);
        }
    }

    @Unique private static final int TRAIN_BUILD_READ_DEPTH = 3;
    @Unique private static int readDepth = 0;
    @Unique private static String capturedTextureId = "";
    @Unique private static boolean capturedFlipV = false;
    @Unique private static boolean captureBbModelPreload = false;

    @SuppressWarnings("unchecked")
    @Inject(at = @At("RETURN"), method = "getOrDefault", remap = false, cancellable = true)
    private static <T> void getOrDefault(JsonObject jsonObject, String key, T defaultValue, Function<JsonElement, T> function, CallbackInfoReturnable<T> cir) {
        if (key.equals(ICustomResources.CUSTOM_TRAINS_TEXTURE_ID)) {
            capturedTextureId = jsonObject.has(key) ? jsonObject.get(key).getAsString() : defaultValue.toString();
            capturedFlipV = jsonObject.has("flipV") && jsonObject.get("flipV").getAsBoolean();
            captureBbModelPreload = jsonObject.has("preloadBbModel") && jsonObject.get("preloadBbModel").getAsBoolean();
        } else if (key.equals(ICustomResources.CUSTOM_TRAINS_BASE_TRAIN_TYPE)) {
            if (jsonObject.has("base_type")) {
                cir.setReturnValue((T)"$NTE_DUMMY_BLANK_PROPERTY");
            }
        }
    }

}
