package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.ScreenMapper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public abstract class ScreenBase extends ScreenMapper {

	protected ScreenBase(Component title) {
		super(title);
	}

	@Override
	public final void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		render(guiGraphics.pose(), mouseX, mouseY, delta);
	}

	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.render(GuiCompat.graphics(matrices), mouseX, mouseY, delta);
	}

	public void renderBackground(PoseStack matrices) {
		super.renderBackground(GuiCompat.graphics(matrices));
	}
}
