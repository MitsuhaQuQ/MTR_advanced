package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Items;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.Station;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;

/**
 * Holding the station area tool, the rectangles of the stations near the player are drawn in the world: a box for one
 * given heights, its outline at the player's feet for one taking in every height, in the station's colour.
 */
public final class RenderStationAreas {

	private static final int MAX_DISTANCE = 128;

	private RenderStationAreas() {
	}

	public static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Player player) {
		if (!player.isHolding(Items.STATION_AREA_TOOL.get())) {
			return;
		}
		final double feet = Math.floor(player.getY()) + 0.05;
		for (final Station station : ClientData.STATIONS) {
			final int r = station.color >> 16 & 0xFF, g = station.color >> 8 & 0xFF, b = station.color & 0xFF;
			for (final int[] rectangle : station.getRectangles()) {
				final double x1 = Math.min(rectangle[0], rectangle[2]), z1 = Math.min(rectangle[1], rectangle[3]);
				final double x2 = Math.max(rectangle[0], rectangle[2]) + 1, z2 = Math.max(rectangle[1], rectangle[3]) + 1;
				if (player.getX() < x1 - MAX_DISTANCE || player.getX() > x2 + MAX_DISTANCE || player.getZ() < z1 - MAX_DISTANCE || player.getZ() > z2 + MAX_DISTANCE) {
					continue;
				}
				final int[] height = station.getHeight(rectangle);
				if (height == null) {
					outline(matrices, vertexConsumers, x1, z1, x2, z2, feet, r, g, b);
				} else {
					final double y1 = height[0], y2 = height[1] + 1;
					outline(matrices, vertexConsumers, x1, z1, x2, z2, y1, r, g, b);
					outline(matrices, vertexConsumers, x1, z1, x2, z2, y2, r, g, b);
					IDrawing.drawLineWorld(matrices, vertexConsumers, x1, y1, z1, x1, y2, z1, r, g, b);
					IDrawing.drawLineWorld(matrices, vertexConsumers, x2, y1, z1, x2, y2, z1, r, g, b);
					IDrawing.drawLineWorld(matrices, vertexConsumers, x1, y1, z2, x1, y2, z2, r, g, b);
					IDrawing.drawLineWorld(matrices, vertexConsumers, x2, y1, z2, x2, y2, z2, r, g, b);
				}
			}
		}
	}

	private static void outline(PoseStack matrices, MultiBufferSource vertexConsumers, double x1, double z1, double x2, double z2, double y, int r, int g, int b) {
		IDrawing.drawLineWorld(matrices, vertexConsumers, x1, y, z1, x2, y, z1, r, g, b);
		IDrawing.drawLineWorld(matrices, vertexConsumers, x2, y, z1, x2, y, z2, r, g, b);
		IDrawing.drawLineWorld(matrices, vertexConsumers, x2, y, z2, x1, y, z2, r, g, b);
		IDrawing.drawLineWorld(matrices, vertexConsumers, x1, y, z2, x1, y, z1, r, g, b);
	}
}
