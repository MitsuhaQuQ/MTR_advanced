package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockNode;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.IGui;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.Locale;

public class NodeAngleScreen extends ScreenBase implements IGui {

	private final BlockPos pos;
	private final float angle;
	private final WidgetBetterTextField textFieldAngle = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", MAX_VALUE_LENGTH);
	private final WidgetBetterTextField textFieldTransition = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", MAX_VALUE_LENGTH);
	private final Button buttonSet;
	private final Button buttonCancel;

	private static final int MAX_VALUE_LENGTH = 6;
	private static final int LABEL_WIDTH = 150;
	private static final int ROW_HEIGHT = SQUARE_SIZE + TEXT_FIELD_PADDING;
	private static final int ROW_ANGLE = 0;
	private static final int ROW_TRANSITION = 1;
	private static final int ROW_BUTTONS = 2;
	private static final Component LABEL_ANGLE = Text.literal("Angle / 角度 (0-180°)");
	private static final Component LABEL_TRANSITION = Text.literal("Transition / 缓和曲线 (Not available)");

	public NodeAngleScreen(BlockPos pos) {
		super(Text.literal(""));
		this.pos = pos;
		final Level world = Minecraft.getInstance().level;
		angle = world == null ? 0 : BlockNode.getAngle(world, pos, world.getBlockState(pos));
		buttonSet = UtilitiesClient.newButton(Text.literal("Set / 设定"), button -> onSet());
		buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
	}

	@Override
	protected void init() {
		super.init();
		final int fieldX = SQUARE_SIZE + LABEL_WIDTH + TEXT_FIELD_PADDING / 2;
		IDrawing.setPositionAndWidth(textFieldAngle, fieldX, getRowY(ROW_ANGLE) + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldTransition, fieldX, getRowY(ROW_TRANSITION) + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(buttonSet, SQUARE_SIZE, getRowY(ROW_BUTTONS), PANEL_WIDTH / 2);
		IDrawing.setPositionAndWidth(buttonCancel, SQUARE_SIZE + PANEL_WIDTH / 2, getRowY(ROW_BUTTONS), PANEL_WIDTH / 2);

		textFieldAngle.setValue(String.format(Locale.ENGLISH, "%.1f", (angle % 180 + 180) % 180));
		textFieldTransition.setValue("0");
		textFieldTransition.setEditable(false);

		addDrawableChild(textFieldAngle);
		addDrawableChild(textFieldTransition);
		addDrawableChild(buttonSet);
		addDrawableChild(buttonCancel);
	}

	@Override
	public void tick() {
		textFieldAngle.tick();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.draw(matrices, font, LABEL_ANGLE, SQUARE_SIZE, getRowY(ROW_ANGLE) + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.draw(matrices, font, LABEL_TRANSITION, SQUARE_SIZE, getRowY(ROW_TRANSITION) + TEXT_PADDING, ARGB_LIGHT_GRAY);
			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void onSet() {
		try {
			PacketTrainDataGuiClient.sendNodeAngleC2S(pos, Float.parseFloat(textFieldAngle.getValue()));
			onClose();
		} catch (NumberFormatException ignored) {
		}
	}

	private static int getRowY(int row) {
		return SQUARE_SIZE + ROW_HEIGHT * row;
	}
}
