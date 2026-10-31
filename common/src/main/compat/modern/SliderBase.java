package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public abstract class SliderBase extends AbstractSliderButton {

	public SliderBase(int x, int y, int width, int height, Component message, double value) {
		super(x, y, width, height, message, value);
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		renderWidget(guiGraphics.pose(), mouseX, mouseY, delta);
	}

	public abstract void renderWidget(PoseStack matrices, int mouseX, int mouseY, float delta);
}
