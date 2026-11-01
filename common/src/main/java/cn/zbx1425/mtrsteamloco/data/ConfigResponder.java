package cn.zbx1425.mtrsteamloco.data;

import mtr.mappings.Text;
import net.minecraft.network.chat.Component;
import me.shedaniel.clothconfig2.impl.builders.StringFieldBuilder;
import me.shedaniel.clothconfig2.gui.entries.*;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.impl.builders.TextDescriptionBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import me.shedaniel.clothconfig2.impl.builders.TextFieldBuilder;
import cn.zbx1425.mtrsteamloco.gui.entries.ButtonCycleListEntry;
import cn.zbx1425.mtrsteamloco.Main;

import java.util.*;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface ConfigResponder {

    public abstract String key();
    public abstract void init(Map<String, String> map);
    public abstract List<AbstractConfigListEntry> getListEntries(Map<String, String> map, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier);

    public static class TextField implements ConfigResponder {
        public Function<String, String> transformer = str -> str;
        public Function<String, Optional<Component>> errorSupplier = str -> Optional.empty();
        public Consumer<String> saveConsumer = str -> {};
        public String key;
        public String defaultValue;
        public Component name;
        public Function<String, Optional<Component[]>> tooltipSupplier = str -> Optional.empty();
        public boolean requireRestart = false;
        
        public TextField(String key, Component name, String defaultValue) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
        }

        public TextField(String key, Component name, String defaultValue, Function<String, String> transformer, Function<String, Optional<Component>> errorSupplier, Consumer<String> saveConsumer, Function<String, Optional<Component[]>> tooltipSupplier, boolean requireRestart) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setTransformer(transformer);
            setErrorSupplier(errorSupplier);
            setSaveConsumer(saveConsumer);
            setTooltipSupplier(tooltipSupplier);
            setRequireRestart(requireRestart);
        }
        @Override
        public void init(Map<String, String> map) {
            if (!map.containsKey(key)) {
                map.put(key, defaultValue);
            }
        }
        @Override
        public String key() {
            return key;
        }
        @Override
        public List<AbstractConfigListEntry> getListEntries(Map<String, String> map, ConfigEntryBuilder builder,Supplier<Screen> screenSupplier) {
            TextFieldBuilder textFieldbuilder = builder.startTextField(name, transformer.apply(map.getOrDefault(key,defaultValue))).setSaveConsumer((str) -> {map.put(key, str); saveConsumer.accept(str);}).setDefaultValue(defaultValue).setErrorSupplier(errorSupplier).setTooltipSupplier(tooltipSupplier);
            if (requireRestart) {
                textFieldbuilder.requireRestart();
            }
            return Collections.singletonList(textFieldbuilder.build());
        }
        public TextField setTransformer(Function<String, String> transformer) {
            this.transformer = transformer;
            return this;
        }
        public TextField setErrorSupplier(Function<String, Optional<Component>> errorSupplier) {
            this.errorSupplier = errorSupplier;
            return this;
        }
        public TextField setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }
        public TextField setName(Component name) {
            this.name = name;
            return this;
        }
        public TextField setSaveConsumer(Consumer<String> saveConsumer) {
            this.saveConsumer = saveConsumer;
            return this;
        }
        public TextField setTooltipSupplier(Function<String, Optional<Component[]>> tooltipSupplier) {
            this.tooltipSupplier = tooltipSupplier;
            return this;
        }
        public TextField setRequireRestart(boolean requireRestart) {
            this.requireRestart = requireRestart;
            return this;
        }
    }

    public static class CycleToggle implements ConfigResponder {
        public List<String> values;
        public int defaultValue;
        public String key;
        public Component name;
        public Function<Integer, Optional<Component[]>> tooltipSupplier = str -> Optional.empty();
        public Consumer<Integer> saveConsumer = ind -> {};
        public boolean requireRestart = false;
        
        public CycleToggle(String key, Component name, int defaultValue, List<String> values) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setValues(values);
        }

        public CycleToggle(String key, Component name, int defaultValue, List<String> values, Function<Integer, Optional<Component[]>> tooltipSupplier, Consumer<Integer> saveConsumer, boolean requireRestart) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setValues(values);
            setTooltipSupplier(tooltipSupplier);
            setSaveConsumer(saveConsumer);
            setRequireRestart(requireRestart);
        }

        @Override
        public void init(Map<String, String> map) {
            if (!map.containsKey(key)) {
                map.put(key, values.get(0));
            }
        }

        @Override
        public String key() {
            return key;
        }

        public CycleToggle setDefaultValue(int defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public CycleToggle setName(Component name) {
            this.name = name;
            return this;
        }

        public CycleToggle setSaveConsumer(Consumer<Integer> saveConsumer) {
            this.saveConsumer = saveConsumer;
            return this;
        }

        public CycleToggle setTooltipSupplier(Function<Integer, Optional<Component[]>> tooltipSupplier) {
            this.tooltipSupplier = tooltipSupplier;
            return this;
        }

        public CycleToggle setRequireRestart(boolean requireRestart) {
            this.requireRestart = requireRestart;
            return this;
        }

        public CycleToggle setValues(List<String> values) {
            this.values = values;
            return this;
        }

        @Override
        public List<AbstractConfigListEntry> getListEntries(Map<String, String> map, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
            int now = defaultValue;
            try {
                now = Integer.parseInt(map.get(key));
            } catch (Exception e) {
                Main.LOGGER.error("Error while parsing cycle value for " + key + " : " + e.getMessage());
            }
            ButtonCycleListEntry entry =  new ButtonCycleListEntry(name, now, values, builder.getResetButtonKey(), () -> defaultValue, ind -> {
                    map.put(key, ind + "");
                    saveConsumer.accept(ind);
                }, null, requireRestart);
            entry.setTooltipSupplier(() -> tooltipSupplier.apply(entry.getValue()));
            return Collections.singletonList(entry);
        }
    }

    public static class BooleanToggle implements ConfigResponder {
        public String key;
        public Component name;
        public boolean defaultValue;
        public Function<Boolean, Optional<Component[]>> tooltipSupplier = str -> Optional.empty();
        public Consumer<Boolean> saveConsumer = bool -> {};
        public boolean requireRestart = false;
        
        public BooleanToggle(String key, Component name, boolean defaultValue) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
        }


        public BooleanToggle(String key, Component name, boolean defaultValue, Function<Boolean, Optional<Component[]>> tooltipSupplier, Consumer<Boolean> saveConsumer, boolean requireRestart) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setTooltipSupplier(tooltipSupplier);
            setSaveConsumer(saveConsumer);
            setRequireRestart(requireRestart);
        }

        public BooleanToggle setDefaultValue(boolean defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public BooleanToggle setName(Component name) {
            this.name = name;
            return this;
        }

        public BooleanToggle setSaveConsumer(Consumer<Boolean> saveConsumer) {
            this.saveConsumer = saveConsumer;
            return this;
        }

        public BooleanToggle setTooltipSupplier(Function<Boolean, Optional<Component[]>> tooltipSupplier) {
            this.tooltipSupplier = tooltipSupplier;
            return this;
        }

        public BooleanToggle setRequireRestart(boolean requireRestart) {
            this.requireRestart = requireRestart;
            return this;
        }

        @Override
        public void init(Map<String, String> map) {
            if (!map.containsKey(key)) {
                map.put(key, defaultValue + "");
            }
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public List<AbstractConfigListEntry> getListEntries(Map<String, String> map, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
            boolean now = defaultValue;
            try {
                now = Boolean.parseBoolean(map.get(key));
            } catch (Exception e) {
                Main.LOGGER.error("Error while parsing boolean value for " + key + " : " + e.getMessage());
            }
            BooleanListEntry entry = builder.startBooleanToggle(name, now).setDefaultValue(defaultValue).setSaveConsumer((bool) -> {map.put(key, bool + ""); saveConsumer.accept(bool);}).setTooltipSupplier(tooltipSupplier).build();
            if (requireRestart) {
                entry.setRequiresRestart(true);
            }
            return Collections.singletonList(entry);
        }
    }

    public static class DoubleSlider implements ConfigResponder {
        public String key;
        public Component name;
        public double defaultValue;
        public double min;
        public double max;
        public int step;    
        public Function<Double, Optional<Component[]>> tooltipSupplier = str -> Optional.empty();
        public Function<Double, Component> textGetter = str -> Text.literal(String.format("%.2f", str));
        public Consumer<Double> saveConsumer = num -> {};
        public boolean requireRestart = false;

        public DoubleSlider(String key, Component name, double defaultValue, double min, double max, int step, Function<Double, Optional<Component[]>> tooltipSupplier, Consumer<Double> saveConsumer, boolean requireRestart) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setMin(min);
            setMax(max);
            setStep(step);
            setTooltipSupplier(tooltipSupplier);
            setSaveConsumer(saveConsumer);
        }

        public DoubleSlider(String key, Component name, double defaultValue, double min, double max, int step) {
            this.key = key;
            setName(name);
            setDefaultValue(defaultValue);
            setMin(min);
            setMax(max);
            setStep(step);
        }

        public DoubleSlider setDefaultValue(double defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public DoubleSlider setName(Component name) {
            this.name = name;
            return this;
        }

        public DoubleSlider setSaveConsumer(Consumer<Double> saveConsumer) {
            this.saveConsumer = saveConsumer;
            return this;
        }

        public DoubleSlider setTooltipSupplier(Function<Double, Optional<Component[]>> tooltipSupplier) {
            this.tooltipSupplier = tooltipSupplier;
            return this;
        }

        public DoubleSlider setRequireRestart(boolean requireRestart) {
            this.requireRestart = requireRestart;
            return this;
        }

        public DoubleSlider setMin(double min) {
            this.min = min;
            return this;
        }

        public DoubleSlider setMax(double max) {
            this.max = max;
            return this;
        }

        public DoubleSlider setStep(int step) {
            this.step = step;
            return this;
        }

        public DoubleSlider setTextGetter(Function<Double, Component> textGetter) {
            this.textGetter = textGetter;
            return this;
        }

        @Override
        public void init(Map<String, String> map) {
            if (!map.containsKey(key)) {
                map.put(key, defaultValue + "");
            }
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public List<AbstractConfigListEntry> getListEntries(Map<String, String> map, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
            double now = map.containsKey(key) ? Double.parseDouble(map.get(key)) : defaultValue;
            AbstractConfigListEntry entry = builder.startIntSlider(name, getLevel(now), 0, step)
                .setDefaultValue(getLevel(defaultValue))
                .setSaveConsumer(level -> {
                    map.put(key, getValue(level) + "");
                    saveConsumer.accept(getValue(level));
                })
                .setTooltipSupplier(
                    level -> tooltipSupplier.apply(getValue(level))
                )
                .setTextGetter(level -> textGetter.apply(getValue(level)))
                .build();
            if (requireRestart) 
                entry.setRequiresRestart(true);
            return Collections.singletonList(entry);
        }

        private int getLevel(double value) {
            return (int) Math.round((value - min) / (max - min) * step);
        }

        public double getValue(int level) {
            return min + (max - min) * level / step;
        }

    }
    
    public static List<AbstractConfigListEntry> getEntrysFromMaps(Map<String, String> customConfigs, Map<String, ConfigResponder> customResponders, ConfigEntryBuilder builder, Supplier<Screen> screenSupplier) {
        List<AbstractConfigListEntry> hasResponders = new ArrayList<>();
        List<AbstractConfigListEntry> noResponders = new ArrayList<>();
        if (!customConfigs.isEmpty()) {
            Set<String> keys = customConfigs.keySet();
            for (String key : keys) {
                if (customResponders.containsKey(key)) {
                    ConfigResponder responder = customResponders.get(key);
                    hasResponders.addAll(responder.getListEntries(customConfigs, builder, screenSupplier));
                } else {
                    noResponders.add(builder.startTextDescription(Text.literal(key + " : " + customConfigs.get(key))).build());
                }
            }
        }
        List<AbstractConfigListEntry> entries = new ArrayList<>();
        if (!hasResponders.isEmpty()) {
            entries.add(builder.startTextDescription(Text.translatable("gui.mtrsteamloco.custom_config.editable")).build());
            entries.addAll(hasResponders);
        }
        /* if (!noResponders.isEmpty()) {
            entries.add(builder.startTextDescription(Text.translatable("gui.mtrsteamloco.custom_config.uneditable")).build());
            entries.addAll(noResponders);
        }*/
        return entries;
    }
}