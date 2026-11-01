package cn.zbx1425.mtrsteamloco.gui.entries;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Window;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
#endif
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import me.shedaniel.clothconfig2.gui.entries.*;
import mtr.mappings.Text;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class ButtonCycleListEntry extends TooltipListEntry<Integer> implements ContainerEventHandler{

    private AtomicInteger index = new AtomicInteger();
    private List<String> list;
    private final Integer original;
    private final Supplier<Integer> defaultValue;
    protected final Consumer<Integer> saveCallback;

    private final Button buttonWidget;
    private final Button resetButton;
    private final List<AbstractWidget> widgets;

    public ButtonCycleListEntry(Component fieldName, int index, List<String> list, Component resetButtonKey, Supplier<Integer> defaultValue, Consumer<Integer> saveConsumer, Supplier<Optional<Component[]>> tooltipSupplier, boolean requiresRestart) {
        super(fieldName, tooltipSupplier, requiresRestart);
        this.list = list;
        this.index.set(index);
        this.original = index;
        this.defaultValue = defaultValue;
        this.buttonWidget = UtilitiesClient.newButton(btn -> {
            this.index.set((this.index.get() + 1) % list.size());
        });
        this.buttonWidget.setWidth(150);
        this.resetButton = UtilitiesClient.newButton(resetButtonKey, widget -> {
            this.index.set(original);
        });
        this.resetButton.setWidth(Minecraft.getInstance().font.width(resetButtonKey) + 6);
        this.resetButton.setMessage(resetButtonKey);
        this.widgets = Lists.newArrayList(buttonWidget, resetButton);
        this.saveCallback = saveConsumer;
    }
    
    @Override
    public boolean isEdited() {
        return super.isEdited() || original != index.get();
    }
    
    @Override
    public Integer getValue() {
        return index.get();
    }
    
    @Override
    public Optional<Integer> getDefaultValue() {
        return defaultValue == null ? Optional.empty() : Optional.ofNullable(defaultValue.get());
    }
    
#if MC_VERSION >= "12000"
    @Override
    public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#else
    @Override
    public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#endif
        super.render(matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        Window window = Minecraft.getInstance().getWindow();
        this.resetButton.active = isEditable() && getDefaultValue().isPresent() && defaultValue.get() != this.index.get();
        UtilitiesClient.setWidgetY(resetButton, y);
        this.buttonWidget.active = isEditable();
        UtilitiesClient.setWidgetY(buttonWidget, y);
        this.buttonWidget.setMessage(Text.translatable(list.get(this.index.get())));
        Component displayedFieldName = getDisplayedFieldName();
        if (Minecraft.getInstance().font.isBidirectional()) {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), window.getGuiScaledWidth() - x - Minecraft.getInstance().font.width(displayedFieldName), y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(resetButton, x);
            UtilitiesClient.setWidgetX(buttonWidget, x + resetButton.getWidth() + 2);
        } else {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), x, y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(resetButton, x + entryWidth - resetButton.getWidth());
            UtilitiesClient.setWidgetX(buttonWidget, x + entryWidth - 150);
        }
        this.buttonWidget.setWidth(150 - resetButton.getWidth() - 2);
        resetButton.render(matrices, mouseX, mouseY, delta);
        buttonWidget.render(matrices, mouseX, mouseY, delta);
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

    @Override
    public void save() {
        saveCallback.accept(index.get());
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return widgets;
    }
    
    @Override
    public List<? extends NarratableEntry> narratables() {
        return widgets;
    }
}