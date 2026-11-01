package cn.zbx1425.mtrsteamloco;

import cn.zbx1425.mtrsteamloco.render.ShadersModHandler;
import cn.zbx1425.mtrsteamloco.data.ConfigResponder;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.impl.builders.StringFieldBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.impl.builders.TextDescriptionBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import mtr.mappings.Text;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ClientConfig {

    private static Path path;

    public static boolean enableOptimization = true;
    public static boolean enableBbModelPreload = false;
    public static boolean translucentSort = true;

    public static boolean enableScriptDebugOverlay = false;

    public static boolean enableRailDeform = true;
    public static boolean enableRail3D = true;
    public static boolean enableRailRender = true;
    public static boolean enableTrainRender = true;
    public static boolean enableTrainSound = true;
    public static boolean enableSmoke = true;
    public static boolean enableRolling = true;
    public static int railDistanceRendererInterval = 5;
    public static int railDistanceRendererMaxDistanceSqr = 16 * 16;

    public static boolean useEditBoxSetRailRolling = true;

    public static boolean hideRidingTrain = false;

    public static final EyecandyScreenGroup eyecandyScreenGroup = new EyecandyScreenGroup();
    public static final RollAnglesListEntryGroup rollAnglesListEntryGroup = new RollAnglesListEntryGroup();
    public static final Entry directNodeScreenGroup = new Entry("direct_node_screen", "rotation", 0, 180F, 180, 1, 1);

    private static Map<String, String> customConfigs = new HashMap<>();
    private static Map<String, ConfigResponder> customResponders = new HashMap<>();

    public static void load(Path path) {
        ClientConfig.path = path;
        if (!Files.exists(path)) {
            save();
        }
        try {
            JsonObject configObject = Main.JSON_PARSER.parse(Files.readString(path)).getAsJsonObject();
            enableOptimization = !getOrDefault(configObject, "shaderCompatMode", JsonElement::getAsBoolean, false);
            enableBbModelPreload = getOrDefault(configObject, "enableBbModelPreload", JsonElement::getAsBoolean, false);
            translucentSort = getOrDefault(configObject, "translucentSort", JsonElement::getAsBoolean, false);
            enableScriptDebugOverlay = getOrDefault(configObject, "enableScriptDebugOverlay", JsonElement::getAsBoolean, false);
            enableRailDeform = getOrDefault(configObject, "enableRailDeform", JsonElement::getAsBoolean, true);
            enableRail3D = getOrDefault(configObject, "enableRail3D", JsonElement::getAsBoolean, true);
            enableRailRender = getOrDefault(configObject, "enableRailRender", JsonElement::getAsBoolean, true);
            enableTrainRender = getOrDefault(configObject, "enableTrainRender", JsonElement::getAsBoolean, true);
            enableTrainSound = getOrDefault(configObject, "enableTrainSound", JsonElement::getAsBoolean, true);
            enableSmoke = getOrDefault(configObject, "enableSmoke", JsonElement::getAsBoolean, true);
            hideRidingTrain = getOrDefault(configObject, "hideRidingTrain", JsonElement::getAsBoolean, false);
            enableRolling = getOrDefault(configObject, "enableRolling", JsonElement::getAsBoolean, true);
            useEditBoxSetRailRolling = getOrDefault(configObject, "useEditBoxSetRailRolling", JsonElement::getAsBoolean, true);
            railDistanceRendererInterval = getOrDefault(configObject, "railDistanceRendererInterval", JsonElement::getAsInt, 5);
            railDistanceRendererMaxDistanceSqr = getOrDefault(configObject, "railDistanceRendererMaxDistanceSqr", JsonElement::getAsInt, 16 * 16);

            eyecandyScreenGroup.init(configObject);
            directNodeScreenGroup.init(configObject);
            rollAnglesListEntryGroup.init(configObject);

            customConfigs.clear();
            if (configObject.has("custom")) {
                JsonObject customObject = configObject.getAsJsonObject("custom");
                Set<Map.Entry<String, JsonElement>> entries = customObject.entrySet();
                for (Map.Entry<String, JsonElement> entry : entries) {
                    customConfigs.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception ex) {
            Main.LOGGER.warn("Failed loading client config:", ex);
            save();
        }
    }

    private static <T> T getOrDefault(JsonObject jsonObject, String key, Function<JsonElement, T> getter, T defaultValue) {
        if (jsonObject == null) return defaultValue;
        if (jsonObject.has(key)) {
            return getter.apply(jsonObject.get(key));
        } else {
            return defaultValue;
        }
    }

    private static <T> T getOrDefault(JsonArray jsonArray, int index, Function<JsonElement, T> getter, T defaultValue) {
        if (jsonArray == null) return defaultValue;
        if (jsonArray.size() > index) {
            return getter.apply(jsonArray.get(index));
        } else {
            return defaultValue;
        }
    }

    public static int getRailRenderLevel() {
        if (!useRenderOptimization()) {
            return enableRailRender ? 1 : 0;
        } else {
            return enableRailRender
                    ? (enableRail3D ? (ShadersModHandler.canInstance() && !enableRailDeform ? 3 : 2) : 1)
                    : 0;
        }
    }

    public static boolean useRenderOptimization() {
        return enableOptimization && ShadersModHandler.canDrawWithBuffer();
    }

    public static void save() {
        try {
            if (path == null) return;
            JsonObject configObject = new JsonObject();
            configObject.addProperty("shaderCompatMode", !enableOptimization);
            configObject.addProperty("enableBbModelPreload", enableBbModelPreload);
            configObject.addProperty("translucentSort", translucentSort);
            configObject.addProperty("enableScriptDebugOverlay", enableScriptDebugOverlay);
            configObject.addProperty("enableRailDeform", enableRailDeform);
            configObject.addProperty("enableRail3D", enableRail3D);
            configObject.addProperty("enableRailRender", enableRailRender);
            configObject.addProperty("enableTrainRender", enableTrainRender);
            configObject.addProperty("enableTrainSound", enableTrainSound);
            configObject.addProperty("enableSmoke", enableSmoke);
            configObject.addProperty("hideRidingTrain", hideRidingTrain);
            configObject.addProperty("useEditBoxSetRailRolling", useEditBoxSetRailRolling);
            configObject.addProperty("railDistanceRendererInterval", railDistanceRendererInterval);
            configObject.addProperty("railDistanceRendererMaxDistanceSqr", railDistanceRendererMaxDistanceSqr);
            configObject.addProperty("enableRolling", enableRolling);
            eyecandyScreenGroup.save(configObject);
            directNodeScreenGroup.save(configObject);
            rollAnglesListEntryGroup.save(configObject);

            JsonObject customObject = new JsonObject();
            for (Map.Entry<String, String> entry : customConfigs.entrySet()) {
                customObject.addProperty(entry.getKey(), entry.getValue());
            }

            configObject.add("custom", customObject);

            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(configObject));
        } catch (Exception ex) {
            Main.LOGGER.warn("Failed loading client config:", ex);
        }
    }

    public static void load() {
        load(Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mtrsteamloco.json"));
    }

    public static void register(ConfigResponder responder) {
        responder.init(customConfigs);
        customResponders.put(responder.key(), responder);
    }

    public static String get(String key) {
        return customConfigs.get(key);
    }

    public static Map<String, String> getCustomConfigs() {
        return customConfigs;
    }

    public static void clearCustomResponders() {
        customResponders.clear();
    }

    public static List<AbstractConfigListEntry> getCustomConfigEntrys(ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
        Set<String> keys = customConfigs.keySet();
        List<String> usedKeys = new ArrayList<>();
        List<String> unusedKeys = new ArrayList<>();
        for (String key : keys) {
            if (customResponders.containsKey(key)) {
                usedKeys.add(key);
            } else {
                unusedKeys.add(key);
            }
        }
        List<AbstractConfigListEntry> entries = new ArrayList<>();
        if (!usedKeys.isEmpty()) {
            entries.add(builder.startTextDescription(Text.translatable("gui.mtrsteamloco.config.client.custom_config.engaged")).build());
            for (String key : usedKeys) {
                entries.addAll(customResponders.get(key).getListEntries(customConfigs, builder, screenSupplier));
            }
        }
        // if (!unusedKeys.isEmpty()) {
        if (false) {
            entries.add(builder.startTextDescription(Text.translatable("gui.mtrsteamloco.config.client.custom_config.untapped")).build());
            for (String key : unusedKeys) {
                entries.add(builder.startTextDescription(Text.literal(key + " : " + customConfigs.get(key))).build());
            };
        }
        return entries;
    }

    public static interface ConfigGroup {
        String key();
        void init(JsonObject configObject);
        void save(JsonObject configObject);
        void getListEntries(List<AbstractConfigListEntry> entries, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier);
    }

    public static abstract class EntryGroup implements ConfigGroup {
        public final String key;
        public final Entry[] entries;

        public EntryGroup(String key, Entry... entries) {
            this.key = key;
            this.entries = entries;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public void init(JsonObject configObject) {
            JsonObject entryObject = configObject.getAsJsonObject(key());
            if (entryObject == null) return;
            for (Entry entry : entries) {
                entry.init(entryObject);
            }
        }

        @Override
        public void save(JsonObject configObject) {
            JsonObject entryObject = new JsonObject();
            for (Entry entry : entries) {
                entry.save(entryObject);
            }
            configObject.add(key(), entryObject);
        }

        @Override
        public void getListEntries(List<AbstractConfigListEntry> entries, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
            for (Entry entry : this.entries) {
                entry.getListEntries(entries, builder, screenSupplier);
            }
        }
    }

    public static class RollAnglesListEntryGroup extends EntryGroup {

        public RollAnglesListEntryGroup() {
            super(
                "roll_angles_list_entry", 
                new Entry("rotation", -20F, 20F, 80, 1, 2),
                new Entry("offset", 0F, 1F, 10000, 1, 2)
            );
        }
    }

    public static class EyecandyScreenGroup extends EntryGroup {

        public EyecandyScreenGroup() {
            super(
                "eyecandy_screen", 
                new Entry("translation", -1.0F, 1.0F, 40, 1, 3),
                new Entry("rotation", -180F, 180F, 36, 1, 3),
                new Entry("scale", -2.0F, 2.0F, 40, 1, 3)
            );
        }
    }

    public static class Entry implements ConfigGroup {
        public float defaultMin, defaultMax;
        public int defaultStep, defaultMode, quantity;

        public float min, max;
        public int step;
        public int[] modes;
        public String key, tooltipKey;

        public Entry(String key, float defaultMin, float defaultMax, int defaultStep, int defaultMode, int quantity) {
            this(key, key, defaultMin, defaultMax, defaultStep, defaultMode, quantity);
        }
        
        public Entry(String key, String tooltipKey, float defaultMin, float defaultMax, int defaultStep, int defaultMode, int quantity) {
            this.defaultMin = defaultMin;
            this.defaultMax = defaultMax;
            this.defaultStep = defaultStep;
            this.defaultMode = defaultMode;
            this.quantity = quantity;

            min = defaultMin;
            max = defaultMax;
            step = defaultStep;
            modes = new int[quantity];
            Arrays.fill(modes, defaultMode);
            this.key = key;
            this.tooltipKey = tooltipKey;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public void init(JsonObject configObject) {
            JsonObject entryObject = configObject.getAsJsonObject(key);
            if (entryObject == null) return;
            min = getOrDefault(configObject, "min", JsonElement::getAsFloat, min);
            max = getOrDefault(configObject, "max", JsonElement::getAsFloat, max);
            step = getOrDefault(configObject, "step", JsonElement::getAsInt, step);
            JsonArray modesArray = entryObject.getAsJsonArray("modes");
            if (modesArray == null) return;
            int [] modes = new int[quantity];
            for (int i = 0; i < quantity; i++) {
                modes[i] = getOrDefault(modesArray, i, JsonElement::getAsInt, defaultMode);
            }
            this.modes = modes;
        }

        @Override
        public void save(JsonObject configObject) {
            JsonObject entryObject = new JsonObject();
            entryObject.addProperty("min", min);
            entryObject.addProperty("max", max);
            entryObject.addProperty("step", step);
            JsonArray modesArray = new JsonArray();
            for (int i = 0; i < quantity; i++) {
                modesArray.add(modes[i]);
            }
            entryObject.add("modes", modesArray);
            configObject.add(key, entryObject);
        }

        @Override
        public void getListEntries(List<AbstractConfigListEntry> entries, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
            entries.add(
                builder.startFloatField(Text.translatable("gui.mtrsteamloco." + tooltipKey + ".min"), min)
                .setDefaultValue(defaultMin)
                .setSaveConsumer(value -> min = value)
                .build()
            );

            entries.add(
                builder.startFloatField(Text.translatable("gui.mtrsteamloco." + tooltipKey + ".max"), max)
                .setDefaultValue(defaultMax)
                .setSaveConsumer(value -> max = value)
                .build()
            );

            entries.add(
                builder.startIntField(Text.translatable("gui.mtrsteamloco." + tooltipKey + ".step"), step)
                .setDefaultValue(defaultStep)
                .setSaveConsumer(value -> step = value)
                .build()
            );
        }
    }
}
