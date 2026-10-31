package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.WidgetMapper;
import net.minecraft.client.gui.GuiGraphics;

public interface WidgetBase extends WidgetMapper {

	void render(PoseStack matrices, int mouseX, int mouseY, float delta);

	@Override
	default void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		render(guiGraphics.pose(), mouseX, mouseY, delta);
	}
}
