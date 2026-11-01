/*
 * This file is part of Cloth Config.
 * Copyright (C) 2020 - 2021 shedaniel
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package cn.zbx1425.mtrsteamloco.gui.entries;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import mtr.mappings.Text;
import net.minecraft.client.gui.narration.NarratableEntry;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.Font;
import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
#if MC_VERSION >= "12000"
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiComponent;
#endif

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class SimpleButtonListEntry extends TooltipListEntry<Boolean> implements ContainerEventHandler {
    
    private final Button buttonWidget;
    private final Button resetButton;
    private final List<AbstractWidget> widgets;
    
    @ApiStatus.Internal
    @Deprecated
    public SimpleButtonListEntry(Component fieldName, Component btnName0, Button.OnPress onPress0, Component btnName1, Button.OnPress onPress1, Component resetButtonKey) {
        super(fieldName, null, false);
        this.buttonWidget = UtilitiesClient.newButton(20, btnName0, onPress0);
        this.buttonWidget.setWidth(150);
        this.resetButton = UtilitiesClient.newButton(20, btnName1, onPress1);
        this.resetButton.setWidth(Minecraft.getInstance().font.width(resetButtonKey) + 6);
        this.widgets = Lists.newArrayList(buttonWidget, resetButton);
    }
    
    @Override
    public boolean isEdited() {
        return false;
    }
    
    @Override
    public Boolean getValue() {
        return false;
    }
    
    @Override
    public Optional<Boolean> getDefaultValue() {
        return Optional.empty();
    }
    
    @Override
#if MC_VERSION >= "12000"
    public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#else
    public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
#endif
        super.render(matrices, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
        Window window = Minecraft.getInstance().getWindow();
        UtilitiesClient.setWidgetY(this.buttonWidget, y);
        UtilitiesClient.setWidgetY(this.resetButton, y);
        Component displayedFieldName = getDisplayedFieldName();
        if (Minecraft.getInstance().font.isBidirectional()) {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), window.getGuiScaledWidth() - x - Minecraft.getInstance().font.width(displayedFieldName), y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(this.resetButton, x);
            UtilitiesClient.setWidgetX(this.buttonWidget, x + this.resetButton.getWidth() + 2);
        } else {
            drawString(matrices, Minecraft.getInstance().font, displayedFieldName.getVisualOrderText(), x, y + 6, getPreferredTextColor());
            UtilitiesClient.setWidgetX(this.resetButton, x + entryWidth - resetButton.getWidth());
            UtilitiesClient.setWidgetX(this.buttonWidget, x + entryWidth - 150);
        }
        this.buttonWidget.setWidth(150 - resetButton.getWidth() - 2);
        resetButton.render(matrices, mouseX, mouseY, delta);
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
