package cn.zbx1425.mtrsteamloco.gui.entries;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.*;
import cn.zbx1425.mtrsteamloco.gui.entries.ButtonListEntry;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateHoldingItem;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateRail;
import cn.zbx1425.mtrsteamloco.render.RailPicker;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.Mth;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.FormattedText;
import mtr.client.IDrawing;
import mtr.data.Rail;
import net.minecraft.client.gui.components.AbstractSliderButton;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.screens.Screen;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import mtr.screen.WidgetBetterCheckbox;
import mtr.screen.WidgetBetterTextField;
import net.minecraft.util.Mth;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.Util;
import mtr.data.RailType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiComponent;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import me.shedaniel.clothconfig2.gui.entries.*;
import mtr.mappings.Text;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.mtrsteamloco.ClientConfig;
import net.minecraft.client.gui.components.EditBox;
import me.shedaniel.clothconfig2.api.Tooltip;
import me.shedaniel.math.Point;
import cn.zbx1425.mtrsteamloco.network.util.DoubleFloatMapSerializer;
import cn.zbx1425.mtrsteamloco.gui.WidgetSlider;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Optional;

public class SliderOrTextFieldListEntry extends TooltipListEntry<Float> implements ContainerEventHandler {
    private EditBox textFieldWidget;
    private WidgetSlider slider;
    private Button btnSwitches;
    private List<AbstractWidget> widgets;
    private AbstractWidget widget;

    private Consumer<Float> saveConsumer;
    private Consumer<Integer> modeSaveConsumer;
    private Function<Float, String> setMessage;
    private float original;
    private float min;
    private float max;
    private int step;

    private float value;

    private int mode;

    public SliderOrTextFieldListEntry(Component fieldName, Component resetButtonKey, float original, float min, float max, int step, Function<Float, String> setMessage, Consumer<Float> saveConsumer, Function<String, Optional<Float>> valueParser, int mode, Consumer<Integer> modeSaveConsumer) {
        super(fieldName, null, false);
        this.min = min;
        this.max = max;
        this.step = step;
        this.original = original;
        this.setMessage = setMessage;
        this.saveConsumer = saveConsumer;
        this.value = original;
        this.modeSaveConsumer = modeSaveConsumer;
        this.mode = mode;

        this.textFieldWidget = new EditBox(Minecraft.getInstance().font, 0, 0, 150, 20, Text.literal(""));
        this.textFieldWidget.setResponder(str -> {
            Float f = valueParser.apply(str).orElse(null);
            if (f == null) {
                this.textFieldWidget.setTextColor(0xFFFF0000);
            } else {
                this.textFieldWidget.setTextColor(0xFFFFFFFF);
                save(f);
            }
        });
        this.textFieldWidget.moveCursorToStart();

        this.btnSwitches = UtilitiesClient.newButton(Text.literal("⇄"), btn -> switchMode(this.mode + 1));
        this.btnSwitches.setWidth(Minecraft.getInstance().font.width(resetButtonKey) + 6);
        this.slider = new WidgetSlider(step, getNowLevel(), i -> {
            float f = i / (float) step * (max - min) + min;
            save(f);
            return setMessage.apply(f);
        });
        slider.setWidth(150);

        switchMode(mode);

        this.widgets = Lists.newArrayList(textFieldWidget, slider, btnSwitches);
    }

    public void save(float newValue) {
        if (value != newValue) {
            value = newValue;
            saveConsumer.accept(newValue);
        }
    }

    public void setValue(float newValue) {
        value = newValue;
        switchMode(mode);
    }

    public int getNowLevel() {
        int v = (int) Math.round((value - min) / (max - min) * step);
        return Math.min(Math.max(v, 0), step);
    }

    public int getMode() {
        return mode;
    }

    public void switchMode(int newMode) {
        int oldMode = mode;
        mode = newMode % 2;
        if (mode == 0) {
            textFieldWidget.visible = true;
            slider.visible = false;
            widget = textFieldWidget;
        } else {
            textFieldWidget.visible = false;
            slider.visible = true;
            widget = slider;
        }
        if (oldMode != mode) {
            modeSaveConsumer.accept(mode);
        }
        textFieldWidget.setValue(Float.toString(value));
        slider.setValue(getNowLevel());
    }

    @Override
    public boolean isEdited() {
        return false;
    }
    
    @Override
    public Float getValue() {
        return value;
    }
    
    @Override
    public Optional<Float> getDefaultValue() {
        return Optional.empty();
    }

    @Override
    public void save() {
        
    }

    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#else
    public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#endif
        super.render(matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        Window window = Minecraft.getInstance().getWindow();
        UtilitiesClient.setWidgetY(this.btnSwitches, y);
        this.widget.active = isEditable();
        UtilitiesClient.setWidgetY(this.widget, y);
        Component displayedFieldName = getDisplayedFieldName();
        if (Minecraft.getInstance().font.isBidirectional()) {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), window.getGuiScaledWidth() - x - Minecraft.getInstance().font.width(displayedFieldName), y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(this.btnSwitches, x);
            UtilitiesClient.setWidgetX(this.widget, x + btnSwitches.getWidth() + 2);
        } else {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), x, y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(this.btnSwitches, x + entryWidth - btnSwitches.getWidth());
            UtilitiesClient.setWidgetX(this.widget, x + entryWidth - 150);
        }
        this.widget.setWidth(150 - btnSwitches.getWidth() - 2);
        btnSwitches.render(matrices, mouseX, mouseY, delta);
        widget.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return widgets;
    }
    
    @Override
    public List<? extends NarratableEntry> narratables() {
        return widgets;
    }

#if MC_VERSION >= "12000"
    public static void drawString(GuiGraphics matrices, Font font, FormattedCharSequence text, int x, int y, int color) {
        matrices.drawString(font, text, x, y, color);
    }
#else
    public static void drawString(PoseStack matrices, Font font, FormattedCharSequence text, int x, int y, int color) {
        GuiComponent.drawString(matrices, font, text, x, y, color);
    }
#endif
}