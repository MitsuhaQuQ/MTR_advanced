package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockNode;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.IGui;
import mtr.mappings.Text;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Locale;

public class NodeAngleScreen extends ScreenBase implements IGui {

	private final BlockPos pos;
	private final float angle;
	private final WidgetBetterTextField textFieldAngle = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", MAX_ANGLE_LENGTH);

	private static final int MAX_ANGLE_LENGTH = 6;

	public NodeAngleScreen(BlockPos pos) {
		super(Text.literal(""));
		this.pos = pos;
		final Level world = Minecraft.getInstance().level;
		angle = world == null ? 0 : BlockNode.getAngle(world, pos, world.getBlockState(pos));
	}

	@Override
	protected void init() {
		super.init();
		IDrawing.setPositionAndWidth(textFieldAngle, SQUARE_SIZE + TEXT_FIELD_PADDING / 2, SQUARE_SIZE + TEXT_HEIGHT + TEXT_PADDING + TEXT_FIELD_PADDING / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		textFieldAngle.setValue(String.format(Locale.ENGLISH, "%.1f", (angle % 180 + 180) % 180));
		addDrawableChild(textFieldAngle);
	}

	@Override
	public void tick() {
		textFieldAngle.tick();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.draw(matrices, font, Text.literal("Angle / 角度 (0-180°)"), SQUARE_SIZE, SQUARE_SIZE, ARGB_WHITE);
			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		try {
			PacketTrainDataGuiClient.sendNodeAngleC2S(pos, Float.parseFloat(textFieldAngle.getValue()));
		} catch (NumberFormatException ignored) {
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
