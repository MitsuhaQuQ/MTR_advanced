package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

public abstract class CheckboxBase extends Checkbox {

	public CheckboxBase(int x, int y, int width, int height, Component text, boolean checked, boolean showMessage) {
		super(x, y, width, height, text, checked, showMessage);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		render(guiGraphics.pose(), mouseX, mouseY, delta);
	}

	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.render(GuiCompat.graphics(matrices), mouseX, mouseY, delta);
	}
}
