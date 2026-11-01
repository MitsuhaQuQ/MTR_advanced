package cn.zbx1425.mtrsteamloco.gui.entries;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Window;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#else
import com.mojang.blaze3d.vertex.PoseStack;
#endif
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import mtr.mappings.UtilitiesClient;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class ButtonListEntry extends TooltipListEntry<String> implements ContainerEventHandler{
    
    public static ButtonListEntry createCenteredInstance(Component filler, Button.OnPress onPress) {
        Button btn = UtilitiesClient.newButton(filler, onPress);
        btn.setWidth(300);
        ButtonListEntry entry = new ButtonListEntry(Text.literal(""), btn, 
            (e, b, a1, a2, a3, a4, a5, a6, a7, a8, a9) -> {
                Window window = Minecraft.getInstance().getWindow();
                UtilitiesClient.setWidgetX(b, (window.getGuiScaledWidth() / 2 - 150));
            }
        );
        return entry;
    }

    private final Button buttonWidget;
    private final List<AbstractWidget> widgets;
    private final Processor processor;

    @ApiStatus.Internal
    @Deprecated
    public ButtonListEntry(Component name, Button button, Processor processor) {
        this(name, button, processor, null);
    }
    
    @ApiStatus.Internal
    @Deprecated
    public ButtonListEntry(Component name, Button button, Processor processor, Supplier<Optional<Component[]>> tooltipSupplier) {
        this(name, button, processor, tooltipSupplier, false);
    }
    
    @ApiStatus.Internal
    @Deprecated
    public ButtonListEntry(Component name, Button button, Processor processor, Supplier<Optional<Component[]>> tooltipSupplier, boolean requiresRestart) {
        super(name, tooltipSupplier, requiresRestart);
        this.processor = processor;
        this.buttonWidget = button;
        this.widgets = Lists.newArrayList(buttonWidget);
    }
    
    @Override
    public boolean isEdited() {
        return false;
    }
    
    @Override
    public String getValue() {
        return "";
    }
    
    @Override
    public Optional<String> getDefaultValue() {
        return Optional.empty();
    }
    
    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#else
    public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#endif
        super.render(matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        processor.process(this, buttonWidget, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        this.buttonWidget.active = isEditable();
#if MC_VERSION >= "11903"
        this.buttonWidget.setY(y);
#else
        this.buttonWidget.y = y;
#endif
        buttonWidget.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return widgets;
    }
    
    @Override
    public List<? extends NarratableEntry> narratables() {
        return widgets;
    }
    
    @Override
    public void save() {
        
    }

    public interface Processor {
        void process(ButtonListEntry entry, Button button, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta);
    }
}
