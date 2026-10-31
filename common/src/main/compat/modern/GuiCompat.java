package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public interface GuiCompat {

	int FULL_BRIGHT = 0xF000F0;

	static PoseStack getPose(Object context) {
		return context instanceof GuiGraphics ? ((GuiGraphics) context).pose() : (PoseStack) context;
	}

	// Our drawing code keeps passing a pose stack around, so a GuiGraphics is only made for the calls that need one
	static GuiGraphics graphics(PoseStack matrices) {
		final Minecraft minecraft = Minecraft.getInstance();
		final GuiGraphics guiGraphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
		guiGraphics.pose().mulPoseMatrix(matrices.last().pose());
		return guiGraphics;
	}

	static void draw(PoseStack matrices, Font font, String text, float x, float y, int color) {
		drawText(matrices, font, text, x, y, color, false);
	}

	static void draw(PoseStack matrices, Font font, Component text, float x, float y, int color) {
		draw(matrices, font, text.getVisualOrderText(), x, y, color);
	}

	static void draw(PoseStack matrices, Font font, FormattedCharSequence text, float x, float y, int color) {
		drawText(matrices, font, text, x, y, color, false);
	}

	static void drawShadow(PoseStack matrices, Font font, String text, float x, float y, int color) {
		drawText(matrices, font, text, x, y, color, true);
	}

	static void drawString(PoseStack matrices, Font font, String text, int x, int y, int color) {
		drawText(matrices, font, text, x, y, color, true);
	}

	static void drawString(PoseStack matrices, Font font, Component text, int x, int y, int color) {
		drawText(matrices, font, text.getVisualOrderText(), x, y, color, true);
	}

	static void drawCenteredString(PoseStack matrices, Font font, String text, int x, int y, int color) {
		drawText(matrices, font, text, x - font.width(text) / 2, y, color, true);
	}

	static void drawCenteredString(PoseStack matrices, Font font, Component text, int x, int y, int color) {
		final FormattedCharSequence formattedCharSequence = text.getVisualOrderText();
		drawText(matrices, font, formattedCharSequence, x - font.width(formattedCharSequence) / 2, y, color, true);
	}

	static void fill(PoseStack matrices, int x1, int y1, int x2, int y2, int color) {
		graphics(matrices).fill(x1, y1, x2, y2, color);
	}

	static void vLine(PoseStack matrices, int x, int y1, int y2, int color) {
		fill(matrices, x, y1 + 1, x + 1, y2, color);
	}

	static void blit(PoseStack matrices, ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
		graphics(matrices).blit(texture, x, y, u, v, width, height);
	}

	static void blit(PoseStack matrices, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		graphics(matrices).blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
	}

	private static void drawText(PoseStack matrices, Font font, String text, float x, float y, int color, boolean shadow) {
		final MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
		font.drawInBatch(text, x, y, color, shadow, matrices.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
		bufferSource.endBatch();
	}

	private static void drawText(PoseStack matrices, Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
		final MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
		font.drawInBatch(text, x, y, color, shadow, matrices.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
		bufferSource.endBatch();
	}
}
