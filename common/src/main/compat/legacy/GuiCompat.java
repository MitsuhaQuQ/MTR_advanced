package mtr.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

public interface GuiCompat {

	static PoseStack getPose(Object context) {
		return (PoseStack) context;
	}

	static void draw(PoseStack matrices, Font font, String text, float x, float y, int color) {
		font.draw(matrices, text, x, y, color);
	}

	static void draw(PoseStack matrices, Font font, Component text, float x, float y, int color) {
		font.draw(matrices, text, x, y, color);
	}

	static void draw(PoseStack matrices, Font font, FormattedCharSequence text, float x, float y, int color) {
		font.draw(matrices, text, x, y, color);
	}

	static void drawShadow(PoseStack matrices, Font font, String text, float x, float y, int color) {
		font.drawShadow(matrices, text, x, y, color);
	}

	static void drawString(PoseStack matrices, Font font, String text, int x, int y, int color) {
		GuiComponent.drawString(matrices, font, text, x, y, color);
	}

	static void drawString(PoseStack matrices, Font font, Component text, int x, int y, int color) {
		GuiComponent.drawString(matrices, font, text, x, y, color);
	}

	static void drawCenteredString(PoseStack matrices, Font font, String text, int x, int y, int color) {
		GuiComponent.drawCenteredString(matrices, font, text, x, y, color);
	}

	static void drawCenteredString(PoseStack matrices, Font font, Component text, int x, int y, int color) {
		GuiComponent.drawCenteredString(matrices, font, text, x, y, color);
	}

	static void fill(PoseStack matrices, int x1, int y1, int x2, int y2, int color) {
		GuiComponent.fill(matrices, x1, y1, x2, y2, color);
	}

	static void vLine(PoseStack matrices, int x, int y1, int y2, int color) {
		fill(matrices, x, y1 + 1, x + 1, y2, color);
	}

	static void blit(PoseStack matrices, ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
		blit(matrices, texture, x, y, u, v, width, height, 256, 256);
	}

	static void blit(PoseStack matrices, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		UtilitiesClient.beginDrawingTexture(texture);
		GuiComponent.blit(matrices, x, y, u, v, width, height, textureWidth, textureHeight);
	}
}
