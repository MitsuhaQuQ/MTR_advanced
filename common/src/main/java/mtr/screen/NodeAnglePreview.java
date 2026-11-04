package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.compat.GuiCompat;
import mtr.data.IGui;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.mappings.Text;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * A map of a node and its rails seen from above, north up, for the brush screen: the rails as they are in grey and as
 * they would be at the angle typed in yellow (red if they can't be), the line of the node, and a red line to the east
 * where angles start with a red arc to the angle, turning the way it counts. Dragging on it turns the node.
 */
public class NodeAnglePreview implements IGui {

	private final BlockPos pos;
	private final Map<BlockPos, Map<BlockPos, Rail>> rails;
	private int x;
	private int y;
	private int size;
	private float blocksShown;
	private String previewedValue;
	private Rail.RotatedNode rotatedNode;

	private static final int COLOR_BACKGROUND = 0xA0000000;
	private static final int COLOR_BORDER = 0xFF555555;
	private static final int COLOR_OLD_RAIL = 0xFF777777;
	private static final int COLOR_NEW_RAIL = 0xFFFFFF00;
	private static final int COLOR_BAD_RAIL = 0xFFFF4040;
	private static final int COLOR_DIAL = 0xFFFF3030;
	private static final int COLOR_NODE = 0xFFFFFFFF;
	private static final float MIN_BLOCKS_SHOWN = 8;
	private static final float DIAL_RADIUS_RATIO = 0.18F;

	public NodeAnglePreview(BlockPos pos) {
		this.pos = pos;
		rails = ClientData.RAILS;
		float farthest = MIN_BLOCKS_SHOWN;
		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections != null) {
			for (final BlockPos posEnd : connections.keySet()) {
				farthest = Math.max(farthest, (float) Math.sqrt(Math.pow(posEnd.getX() - pos.getX(), 2) + Math.pow(posEnd.getZ() - pos.getZ(), 2)));
			}
		}
		// Room around the farthest node, as curves can bulge past it
		blocksShown = farthest * 1.25F;
	}

	public void setBounds(int x, int y, int size) {
		this.x = x;
		this.y = y;
		this.size = size;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getSize() {
		return size;
	}

	public boolean isInside(double mouseX, double mouseY) {
		return size > 0 && mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
	}

	/**
	 * @return the angle pointing from the node to where the mouse is, as typed in the screen
	 */
	public float getAngle(double mouseX, double mouseY, boolean snap) {
		final float angle = (float) Math.toDegrees(Math.atan2(mouseY - (y + size / 2F), mouseX - (x + size / 2F)));
		final float step = snap ? 22.5F : 0.5F;
		return Mth.wrapDegrees(Math.round(angle / step) * step);
	}

	/**
	 * @return the translation key of why the node can't take the angle, or null if it can or none is typed
	 */
	public String render(PoseStack matrices, Font font, String typedValue) {
		if (size <= 0) {
			return null;
		}
		GuiCompat.fill(matrices, x, y, x + size, y + size, COLOR_BACKGROUND);
		GuiCompat.fill(matrices, x, y, x + size, y + 1, COLOR_BORDER);
		GuiCompat.fill(matrices, x, y + size - 1, x + size, y + size, COLOR_BORDER);
		GuiCompat.fill(matrices, x, y, x + 1, y + size, COLOR_BORDER);
		GuiCompat.fill(matrices, x + size - 1, y, x + size, y + size, COLOR_BORDER);

		Float angle;
		try {
			angle = Float.parseFloat(typedValue);
		} catch (NumberFormatException e) {
			angle = null;
		}
		if (!typedValue.equals(previewedValue)) {
			previewedValue = typedValue;
			rotatedNode = angle == null ? null : Rail.rotateNode(rails, pos, RailAngle.fromAngle(angle));
		}

		final float centerX = x + size / 2F;
		final float centerY = y + size / 2F;
		final float scale = size / 2F / blocksShown;

		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections != null) {
			connections.values().forEach(rail -> drawRail(matrices, rail, COLOR_OLD_RAIL, centerX, centerY, scale));
		}
		if (rotatedNode != null) {
			final int color = rotatedNode.error == null ? COLOR_NEW_RAIL : COLOR_BAD_RAIL;
			rotatedNode.rails.values().forEach(rail -> drawRail(matrices, rail, color, centerX, centerY, scale));
		}

		final float dialRadius = size * DIAL_RADIUS_RATIO;
		drawLine(matrices, centerX, centerY, centerX + size / 2F - 2, centerY, COLOR_DIAL);
		if (angle != null) {
			final float wrappedAngle = Mth.wrapDegrees(angle);
			final double radians = Math.toRadians(wrappedAngle);
			// The node's line, through it both ways
			final float lineX = (float) Math.cos(radians) * dialRadius * 1.6F;
			final float lineY = (float) Math.sin(radians) * dialRadius * 1.6F;
			drawLine(matrices, centerX - lineX, centerY - lineY, centerX + lineX, centerY + lineY, COLOR_NEW_RAIL);
			drawArc(matrices, centerX, centerY, dialRadius, wrappedAngle);
		}
		GuiCompat.fill(matrices, Math.round(centerX) - 1, Math.round(centerY) - 1, Math.round(centerX) + 2, Math.round(centerY) + 2, COLOR_NODE);

		GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.direction_n"), Math.round(centerX), y + 3, ARGB_LIGHT_GRAY);
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.direction_e"), x + size - 3 - font.width(Text.translatable("gui.mtr.direction_e")), Math.round(centerY) + 3, COLOR_DIAL);
		GuiCompat.draw(matrices, font, "0°", x + size - 3 - font.width("0°"), Math.round(centerY) - 11, COLOR_DIAL);
		return rotatedNode == null ? null : rotatedNode.error;
	}

	private void drawRail(PoseStack matrices, Rail rail, int color, float centerX, float centerY, float scale) {
		final double length = rail.getLength();
		// About a point a pixel
		final int steps = Mth.clamp((int) Math.ceil(length * scale), 1, 4096);
		for (int i = 0; i <= steps; i++) {
			final Vec3 position = rail.getPosition(length * i / steps);
			plot(matrices, centerX + (float) (position.x - pos.getX()) * scale, centerY + (float) (position.z - pos.getZ()) * scale, color);
		}
	}

	private void drawArc(PoseStack matrices, float centerX, float centerY, float radius, float angle) {
		final int steps = Math.max(1, (int) Math.ceil(Math.abs(Math.toRadians(angle)) * radius));
		float lastX = centerX + radius;
		float lastY = centerY;
		for (int i = 0; i <= steps; i++) {
			final double radians = Math.toRadians(angle * i / steps);
			lastX = centerX + (float) Math.cos(radians) * radius;
			lastY = centerY + (float) Math.sin(radians) * radius;
			plot(matrices, lastX, lastY, COLOR_DIAL);
		}
		if (Math.abs(angle) >= 10) {
			// An arrowhead at the end, pointing the way the arc turns
			final double endRadians = Math.toRadians(angle);
			final double back = -Math.signum(angle);
			for (final int side : new int[]{-1, 1}) {
				final float tipX = lastX + (float) ((-Math.sin(endRadians) * back + side * Math.cos(endRadians) * 0.6) * 5);
				final float tipY = lastY + (float) ((Math.cos(endRadians) * back + side * Math.sin(endRadians) * 0.6) * 5);
				drawLine(matrices, lastX, lastY, tipX, tipY, COLOR_DIAL);
			}
		}
	}

	private void drawLine(PoseStack matrices, float x1, float y1, float x2, float y2, int color) {
		final int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1))));
		for (int i = 0; i <= steps; i++) {
			plot(matrices, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, color);
		}
	}

	private void plot(PoseStack matrices, float pointX, float pointY, int color) {
		final int pixelX = (int) Math.floor(pointX);
		final int pixelY = (int) Math.floor(pointY);
		if (pixelX > x && pixelX < x + size - 1 && pixelY > y && pixelY < y + size - 1) {
			GuiCompat.fill(matrices, pixelX, pixelY, pixelX + 1, pixelY + 1, color);
		}
	}
}
