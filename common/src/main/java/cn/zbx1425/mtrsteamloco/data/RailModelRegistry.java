package cn.zbx1425.mtrsteamloco.data;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.mtrsteamloco.render.integration.MtrModelRegistryUtil;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.model.Model;
import cn.zbx1425.sowcerext.model.ModelCluster;
import cn.zbx1425.sowcerext.model.RawModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import mtr.mappings.Text;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import mtr.mappings.Utilities;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import cn.zbx1425.mtrsteamloco.scripting.ScriptHolderBase;
import cn.zbx1425.mtrsteamloco.scripting.ScriptHolderClient;
import net.minecraft.server.packs.resources.ResourceManager;
import cn.zbx1425.sowcerext.util.ResourceUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RailModelRegistry {

    public static Map<String, RailModelProperties> ELEMENTS = new HashMap<>();
    public static final Map<String, RailModelProperties> PATH_MAP = new HashMap<>();
    public static Tree.Root<RailModelProperties> TREE = new Tree.Root<>("rail.mtrsteamloco.type");

    public static ModelCluster railNodeModel;

    /** MTR's flat rail as a model (see ClientConfig.useFlatRailChunks); not in PATH_MAP, so no one picks it */
    public static final String FLAT_RAIL_KEY = "mtm_flat_rail";
    private static final float FLAT_RAIL_REPEAT = 4;
    
    public static void register(String key, RailModelProperties properties) {
        if (properties == null) return;
        ELEMENTS.put(key, properties);
        PATH_MAP.put(properties.path, properties);
    }

    public static void reload(ResourceManager resourceManager) {
        ELEMENTS.clear();
        PATH_MAP.clear();
        TREE = new Tree.Root<>("rail.mtrsteamloco.type");

        //
        register("", new RailModelProperties("", Text.translatable("rail.mtrsteamloco.default"), null, 1f, 0f, null, "group.mtrsteamloco.builtin"));
        // This is pulled from registry and shouldn't be shown
        register("null", new RailModelProperties("null", Text.translatable("rail.mtrsteamloco.hidden"), null, Float.MAX_VALUE, 0f, null, "group.mtrsteamloco.builtin"));
        try {
            ELEMENTS.put(FLAT_RAIL_KEY, new RailModelProperties(FLAT_RAIL_KEY, Text.literal(""), flatRailModel(), FLAT_RAIL_REPEAT, 0.0625F + mtr.data.IGui.SMALL_OFFSET, null, "group.mtrsteamloco.builtin"));
        } catch (Exception ex) {
            Main.LOGGER.error("Failed making the flat rail model", ex);
        }

        try {
            RawModel railNodeRawModel = MainClient.modelManager.loadRawModel(resourceManager,
                    new ResourceLocation("mtrsteamloco:models/rail_node.csv"), MainClient.atlasManager);
            railNodeModel = MainClient.modelManager.uploadVertArrays(railNodeRawModel);
        } catch (Exception ex) {
            Main.LOGGER.error("Failed loading rail node model", ex);
            MtrModelRegistryUtil.recordLoadingError("Failed loading Rail Node", ex);
        }

        List<Pair<ResourceLocation, Resource>> resources =
                MtrModelRegistryUtil.listResources(resourceManager, "mtrsteamloco", "rails", ".json");
        for (Pair<ResourceLocation, Resource> pair : resources) {
            try {
                try (InputStream is = Utilities.getInputStream(pair.getSecond())) {
                    JsonObject rootObj = (new JsonParser()).parse(IOUtils.toString(is, StandardCharsets.UTF_8)).getAsJsonObject();
                #if MC_VERSION >= "11902"
                    String baseGroup = rootObj.has("group") ? rootObj.get("group").getAsString() : pair.getSecond().sourcePackId() + '/' + pair.getFirst().getPath().replaceAll("rails/", "").replaceAll(".json", "");
                #else 
                    String baseGroup = rootObj.has("group") ? rootObj.get("group").getAsString() : pair.getSecond().getSourceName() + '/' + pair.getSecond().getLocation().getPath().replaceAll("rails/", "").replaceAll(".json", "");
                #endif
                    if (rootObj.has("model")) {
                        String key = FilenameUtils.getBaseName(pair.getFirst().getPath());
                        register(key, loadFromJson(resourceManager, key, rootObj, baseGroup));
                    } else {
                        for (Map.Entry<String, JsonElement> entry : rootObj.entrySet()) {
                            if (!entry.getValue().isJsonObject()) continue;
                            JsonObject obj = entry.getValue().getAsJsonObject();
                            String key = entry.getKey().toLowerCase(Locale.ROOT);
                            register(key, loadFromJson(resourceManager, key, obj, baseGroup));
                        }
                    }
                }
            } catch (Exception ex) {
                Main.LOGGER.error("Failed loading rail: " + pair.getFirst().toString(), ex);
                MtrModelRegistryUtil.recordLoadingError("Failed loading Rail " + pair.getFirst().toString(), ex);
            }
        }

        MainClient.railRenderDispatcher.clearRail();
        TREE = Tree.loadTree("rail.mtrsteamloco.type", PATH_MAP, t -> t.name.getString());
    }

    /**
     * The quads RenderTrains.renderRailStandard draws for a train rail, four blocks of them: two blocks wide, the texture
     * across the width and a strip of it along each block, the strip moving on a quarter each block as MTR's does (which
     * quarter MTR starts on depends on where the block is; here every four blocks start on the first). Both faces, as MTR
     * draws both.
     */
    private static RawModel flatRailModel() {
        final cn.zbx1425.sowcer.batch.MaterialProp material = new cn.zbx1425.sowcer.batch.MaterialProp("rendertype_entity_cutout");
        material.texture = new ResourceLocation("textures/block/rail.png");
        final cn.zbx1425.sowcerext.model.RawMesh mesh = new cn.zbx1425.sowcerext.model.RawMesh(material);
        final float configOffset = (float) mtr.client.Config.trackTextureOffset() / mtr.client.Config.TRACK_OFFSET_COUNT;
        for (int block = 0; block < FLAT_RAIL_REPEAT; block++) {
            final float zStart = block - FLAT_RAIL_REPEAT / 2;
            final float offset = block * 0.25F + configOffset;
            for (int side = 0; side < 2; side++) {
                final boolean up = side == 0;
                final int first = mesh.vertices.size();
                mesh.vertices.add(flatRailVertex(-1, zStart, 0, 0.3125F + offset, up));
                mesh.vertices.add(flatRailVertex(1, zStart, 1, 0.3125F + offset, up));
                mesh.vertices.add(flatRailVertex(1, zStart + 1, 1, 0.1875F + offset, up));
                mesh.vertices.add(flatRailVertex(-1, zStart + 1, 0, 0.1875F + offset, up));
                if (up) {
                    mesh.faces.add(new cn.zbx1425.sowcerext.model.Face(new int[]{first, first + 2, first + 1}));
                    mesh.faces.add(new cn.zbx1425.sowcerext.model.Face(new int[]{first, first + 3, first + 2}));
                } else {
                    mesh.faces.add(new cn.zbx1425.sowcerext.model.Face(new int[]{first, first + 1, first + 2}));
                    mesh.faces.add(new cn.zbx1425.sowcerext.model.Face(new int[]{first, first + 2, first + 3}));
                }
            }
        }
        final RawModel model = new RawModel();
        model.append(mesh);
        model.sourceLocation = new ResourceLocation("mtrsteamloco", FLAT_RAIL_KEY);
        return model;
    }

    private static cn.zbx1425.sowcerext.model.Vertex flatRailVertex(float x, float z, float u, float v, boolean up) {
        final cn.zbx1425.sowcerext.model.Vertex vertex = new cn.zbx1425.sowcerext.model.Vertex(new Vector3f(x, 0, z), new Vector3f(0, up ? 1 : -1, 0));
        vertex.u = u;
        vertex.v = v;
        return vertex;
    }

    private static final RailModelProperties EMPTY_PROPERTY = new RailModelProperties(
            "null", Text.literal(""), null, 1f, 0, null, "group.mtrsteamloco.builtin"
    );

    public static RailModelProperties getProperty(String key) {
        return ELEMENTS.getOrDefault(key, EMPTY_PROPERTY);
    }

    private static RailModelProperties loadFromJson(ResourceManager resourceManager, String key, JsonObject obj, String baseGroup) throws Exception {
        if (obj.has("atlasIndex")) {
            MainClient.atlasManager.load(
                    MtrModelRegistryUtil.resourceManager,  new ResourceLocation(obj.get("atlasIndex").getAsString())
            );
        }
        
        RawModel rawModel = null;

        if (obj.has("model")) {
            rawModel = MainClient.modelManager.loadRawModel(resourceManager,
                    new ResourceLocation(obj.get("model").getAsString()), MainClient.atlasManager).copy();

            if (obj.has("textureId")) {
                rawModel.replaceTexture("default.png", new ResourceLocation(obj.get("textureId").getAsString()));
            }
            if (obj.has("flipV") && obj.get("flipV").getAsBoolean()) {
                rawModel.applyUVMirror(false, true);
            }

            rawModel.sourceLocation = new ResourceLocation(rawModel.sourceLocation.toString() + "/" + key);
        }
        
        float repeatInterval = obj.has("repeatInterval") ? obj.get("repeatInterval").getAsFloat() : 0.5f;
        float yOffset = obj.has("yOffset") ? obj.get("yOffset").getAsFloat() : 0f;


        ScriptHolderBase script = null;
        if (obj.has("scriptFiles")) {
            script = new ScriptHolderClient();
            Map<ResourceLocation, String> scripts = new Object2ObjectArrayMap<>();
            if (obj.has("scriptTexts")) {
                JsonArray scriptTexts = obj.get("scriptTexts").getAsJsonArray();
                for (int i = 0; i < scriptTexts.size(); i++) {
                    scripts.put(new ResourceLocation("mtrsteamloco", "script_texts/" + key + "/" + i),
                            scriptTexts.get(i).getAsString());
                }
            }
            JsonArray scriptFiles = obj.get("scriptFiles").getAsJsonArray();
            for (int i = 0; i < scriptFiles.size(); i++) {
                ResourceLocation scriptLocation = new ResourceLocation(scriptFiles.get(i).getAsString());
                scripts.put(scriptLocation, ResourceUtil.readResource(resourceManager, scriptLocation));
            }
            script.load("Rail " + key, "Rail", resourceManager, scripts, obj, key, "create", "render", "dispose");
        }
        String group = obj.has("group") ? obj.get("group").getAsString() : baseGroup;

        return new RailModelProperties(key, Text.translatable(obj.get("name").getAsString()), rawModel, repeatInterval, yOffset, script, group);
    }
}
