package mtr.render;

import mtr.compat.GuiCompat;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.*;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

public class RenderDrivingOverlay implements IGui {

	private static int accelerationSign;
	private static float doorValue;
	private static float speed;
	private static String thisStation;
	private static String nextStation;
	private static String thisRoute;
	private static String lastStation;
	private static int coolDown;

	private static final int HOT_BAR_WIDTH = 182;
	private static final int HOT_BAR_HEIGHT = 22;
	private static final ResourceLocation WIDGETS_LOCATION = new ResourceLocation("textures/gui/widgets.png");

	public static void render(Object matrices) {
		render(GuiCompat.getPose(matrices));
	}

	public static void render(PoseStack matrices) {
		// The brush's debug display of the train looked at (0.65.31), drawn here so both loaders draw it
		RenderTrainDebug.render(matrices);
		if (coolDown > 0) {
			coolDown--;
		} else {
			return;
		}

		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer player = client.player;
		final Window window = client.getWindow();
		if (window == null || player == null) {
			return;
		}

		matrices.pushPose();
		RenderSystem.enableBlend();
		UtilitiesClient.beginDrawingTexture(WIDGETS_LOCATION);
		final int startX = (window.getGuiScaledWidth() - HOT_BAR_WIDTH) / 2;
		final int startY = window.getGuiScaledHeight() - (player.isCreative() ? 47 : 63);

		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX, startY, 0, 0, 61, HOT_BAR_HEIGHT);
		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX + 61, startY, 141, 0, 41, HOT_BAR_HEIGHT);
		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX + 120, startY, 0, 0, 21, HOT_BAR_HEIGHT);
		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX + 141, startY, 141, 0, 41, HOT_BAR_HEIGHT);

		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX + 39 + Math.max(accelerationSign, -2) * 20, startY - 1, 0, 22, 24, 24);
		GuiCompat.blit(matrices, WIDGETS_LOCATION, startX + (doorValue > 0 ? doorValue < 1 ? 139 : 159 : 119), startY - 1, 0, 22, 24, 24);

		GuiCompat.drawShadow(matrices, client.font, "B2", startX + 5.5F, startY + 7.5F, doorValue == 0 && accelerationSign == -2 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, "B1", startX + 25.5F, startY + 7.5F, doorValue == 0 && accelerationSign == -1 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, "N", startX + 48.5F, startY + 7.5F, doorValue == 0 && accelerationSign == 0 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, "P1", startX + 65.5F, startY + 7.5F, doorValue == 0 && accelerationSign == 1 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, "P2", startX + 85.5F, startY + 7.5F, doorValue == 0 && accelerationSign == 2 ? ARGB_WHITE : ARGB_GRAY);

		GuiCompat.drawShadow(matrices, client.font, "DC", startX + 125.5F, startY + 7.5F, speed == 0 && doorValue == 0 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, String.valueOf(Math.round(doorValue * 10) / 10F), startX + 144.5F, startY + 7.5F, doorValue > 0 && doorValue < 1 ? ARGB_WHITE : ARGB_GRAY);
		GuiCompat.drawShadow(matrices, client.font, "DO", startX + 165.5F, startY + 7.5F, speed == 0 && doorValue == 1 ? ARGB_WHITE : ARGB_GRAY);

		final String speedText = RailwayData.round(speed * 3.6F, 1) + " km/h";
		GuiCompat.drawShadow(matrices, client.font, speedText, startX - client.font.width(speedText) - TEXT_PADDING, window.getGuiScaledHeight() - 14.5F, ARGB_WHITE);
		if (thisStation != null) {
			GuiCompat.drawShadow(matrices, client.font, thisStation, startX + HOT_BAR_WIDTH + TEXT_PADDING, window.getGuiScaledHeight() - 44.5F, ARGB_WHITE);
		}
		if (nextStation != null) {
			GuiCompat.drawShadow(matrices, client.font, "> " + nextStation, startX + HOT_BAR_WIDTH + TEXT_PADDING, window.getGuiScaledHeight() - 34.5F, ARGB_WHITE);
		}
		if (thisRoute != null) {
			GuiCompat.drawShadow(matrices, client.font, thisRoute, startX + HOT_BAR_WIDTH + TEXT_PADDING, window.getGuiScaledHeight() - 19.5F, ARGB_WHITE);
		}
		if (lastStation != null) {
			GuiCompat.drawShadow(matrices, client.font, "> " + lastStation, startX + HOT_BAR_WIDTH + TEXT_PADDING, window.getGuiScaledHeight() - 9.5F, ARGB_WHITE);
		}

		RenderSystem.disableBlend();
		matrices.popPose();
	}

	public static void setData(int accelerationSign, TrainClient trainClient) {
		RenderDrivingOverlay.accelerationSign = accelerationSign;
		RenderDrivingOverlay.doorValue = trainClient.getDoorValue();
		coolDown = 2;
		RenderDrivingOverlay.speed = trainClient.getSpeed() * 20;
		final Route thisRoute = trainClient.getThisRoute();
		final Route nextRoute = trainClient.getNextRoute();
		final Station thisStation = trainClient.getThisStation();
		final Station nextStation = trainClient.getNextStation();
		final Station lastStation = trainClient.getLastStation();
		RenderDrivingOverlay.thisStation = thisStation == null ? null : IGui.formatStationName(thisStation.name);
		RenderDrivingOverlay.nextStation = nextStation == null ? nextRoute == null ? null : IGui.formatStationName(nextRoute.name) : IGui.formatStationName(nextStation.name);
		RenderDrivingOverlay.thisRoute = thisRoute == null ? null : IGui.formatStationName(thisRoute.name);
		RenderDrivingOverlay.lastStation = lastStation == null ? null : IGui.formatStationName(lastStation.name);
	}
}
