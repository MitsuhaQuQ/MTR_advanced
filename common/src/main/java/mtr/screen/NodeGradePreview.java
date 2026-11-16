package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.compat.GuiCompat;
import mtr.data.IGui;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.mappings.Text;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import transitsim.geometry.VerticalProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The rails of a node seen from the side, for the vertical tab of the brush screen: going the way the node faces to the
 * right, height up and stretched so that the grades can be seen. The rails as they are in grey, with the node's grade
 * and vertical curve radius typed in yellow (red where the radius doesn't fit), and the grade's line through the node in
 * blue. Dragging on it sets the grade.
 */
public class NodeGradePreview implements IGui {

	private final BlockPos pos;
	private final RailAngle facing;
	private final List<Side> sides = new ArrayList<>();
	private final double blocksShown;
	private final double heightShown;
	private int x;
	private int y;
	private int size;

	private static final int COLOR_BACKGROUND = 0xA0000000;
	private static final int COLOR_BORDER = 0xFF555555;
	private static final int COLOR_OLD_RAIL = 0xFF777777;
	private static final int COLOR_NEW_RAIL = 0xFFFFFF00;
	private static final int COLOR_BAD_RAIL = 0xFFFF4040;
	private static final int COLOR_GRADE_LINE = 0xFF3399FF;
	private static final int COLOR_LEVEL = 0xFF444444;
	private static final int COLOR_NODE = 0xFFFFFFFF;
	private static final double MIN_BLOCKS_SHOWN = 8;
	private static final double MIN_HEIGHT_SHOWN = 1;

	public NodeGradePreview(BlockPos pos, RailAngle facing) {
		this.pos = pos;
		this.facing = facing;
		double farthest = MIN_BLOCKS_SHOWN;
		double highest = MIN_HEIGHT_SHOWN;
		final Map<BlockPos, Rail> connections = ClientData.RAILS.get(pos);
		if (connections != null) {
			connections.forEach((posEnd, rail) -> {
				final RailAngle leaving = rail.getLeavingFacing();
				sides.add(new Side(posEnd, rail, leaving.cos * facing.cos + leaving.sin * facing.sin >= 0));
			});
			for (final Side side : sides) {
				farthest = Math.max(farthest, side.rail.getLength());
				highest = Math.max(highest, Math.abs(side.posEnd.getY() - pos.getY()));
			}
		}
		blocksShown = farthest * 1.1;
		heightShown = highest * 1.6;
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
	 * @return how many times heights are stretched against distances
	 */
	public double getStretch() {
		return Math.max(1, blocksShown / heightShown);
	}

	/**
	 * @return the grade of the line from the node to the mouse, in per thousand
	 */
	public double getGradePerMille(double mouseX, double mouseY) {
		final double across = mouseX - (x + size / 2D);
		final double up = (y + size / 2D) - mouseY;
		if (Math.abs(across) < 1) {
			return 0;
		}
		return Math.round(up / getStretch() / across * 1000 * 2) / 2D;
	}

	/**
	 * @param grade  the node's grade going the way it faces
	 * @param radius the radius of the vertical curves at the node
	 * @return the first rail whose curve here can't take the radius, or null
	 */
	public Side render(PoseStack matrices, Font font, double grade, double radius) {
		Side tooShort = null;
		for (final Side side : sides) {
			if (side.getProfile(grade, radius) != null && !side.getProfile(grade, radius).fitsRadius()) {
				tooShort = side;
				break;
			}
		}
		if (size <= 0) {
			return tooShort;
		}
		GuiCompat.fill(matrices, x, y, x + size, y + size, COLOR_BACKGROUND);
		GuiCompat.fill(matrices, x, y, x + size, y + 1, COLOR_BORDER);
		GuiCompat.fill(matrices, x, y + size - 1, x + size, y + size, COLOR_BORDER);
		GuiCompat.fill(matrices, x, y, x + 1, y + size, COLOR_BORDER);
		GuiCompat.fill(matrices, x + size - 1, y, x + size, y + size, COLOR_BORDER);

		final float centerX = x + size / 2F;
		final float centerY = y + size / 2F;
		final double scale = size / 2D / blocksShown;
		final double heightScale = scale * getStretch();
		drawLine(matrices, x + 1, centerY, x + size - 1, centerY, COLOR_LEVEL);
		final float lineX = size / 2F;
		final float lineY = (float) Mth.clamp(grade * heightScale / scale * lineX, -size, size);
		drawLine(matrices, centerX - lineX, centerY + lineY, centerX + lineX, centerY - lineY, COLOR_GRADE_LINE);

		for (final Side side : sides) {
			final double length = side.rail.getLength();
			final int steps = Mth.clamp((int) Math.ceil(length * scale), 1, 2048);
			final int direction = side.forward ? 1 : -1;
			final VerticalProfile profile = side.getProfile(grade, radius);
			for (int i = 0; i <= steps; i++) {
				final double distance = length * i / steps;
				final float pointX = centerX + (float) (direction * distance * scale);
				plot(matrices, pointX, centerY - (float) ((side.rail.getPosition(distance).y - pos.getY()) * heightScale), COLOR_OLD_RAIL);
				if (profile != null) {
					plot(matrices, pointX, centerY - (float) ((profile.getY(distance) - pos.getY()) * heightScale), profile.fitsRadius() ? COLOR_NEW_RAIL : COLOR_BAD_RAIL);
				}
			}
		}
		GuiCompat.fill(matrices, Math.round(centerX) - 1, Math.round(centerY) - 1, Math.round(centerX) + 2, Math.round(centerY) + 2, COLOR_NODE);
		final String forwardLabel = Text.translatable("gui.mtr.node_grade_forward").getString();
		GuiCompat.draw(matrices, font, forwardLabel, x + size - 3 - font.width(forwardLabel), y + size - 11, COLOR_GRADE_LINE);
		return tooShort;
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

	/**
	 * A rail of the node and which side of it it leaves from.
	 */
	public final class Side {

		public final BlockPos posEnd;
		private final Rail rail;
		private final boolean forward;

		private Side(BlockPos posEnd, Rail rail, boolean forward) {
			this.posEnd = posEnd;
			this.rail = rail;
			this.forward = forward;
		}

		/**
		 * @return the rail's height with the grade and radius typed for the node, or null for a rail of the old shape
		 */
		public VerticalProfile getProfile(double grade, double radius) {
			if (!rail.hasGrades() || rail.railType.railSlopeStyle == RailType.RailSlopeStyle.CABLE) {
				return null;
			}
			return VerticalProfile.of(rail.getLength(), pos.getY(), posEnd.getY(), forward ? grade : -grade, rail.getGradeEnd(), radius, rail.getVerticalRadiusEnd());
		}

		/**
		 * @return the radius the rail's curve at the node gets, which is smaller than asked when it doesn't fit
		 */
		public double getFittingRadius(double grade, double radius) {
			final VerticalProfile profile = getProfile(grade, radius);
			return profile == null ? radius : VerticalProfile.clampRadius(radius) * profile.getRadiusScale();
		}
	}
}
