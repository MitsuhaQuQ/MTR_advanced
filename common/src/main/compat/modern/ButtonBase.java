package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.ButtonMapper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public abstract class ButtonBase extends ButtonMapper {

	public ButtonBase(int x, int y, int width, int height, Component component, OnPress onPress) {
		super(x, y, width, height, component, onPress);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		render(guiGraphics.pose(), mouseX, mouseY, delta);
	}

	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.render(GuiCompat.graphics(matrices), mouseX, mouseY, delta);
	}
}
