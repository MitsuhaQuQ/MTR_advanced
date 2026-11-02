package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockNode;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.IGui;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.Map;

public class NodeAngleScreen extends ScreenBase implements IGui {

	private final BlockPos pos;
	private final float angle;
	private final WidgetBetterTextField textFieldAngle = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.SIGNED_DECIMAL, "", MAX_VALUE_LENGTH);
	private final WidgetBetterTextField textFieldTransition = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", MAX_VALUE_LENGTH);
	private final WidgetBetterCheckbox checkboxKeyNode = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal("Key node / 关键节点"), checked -> {
	});
	private final WidgetBetterTextField textFieldTurnBack = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "0", MAX_VALUE_LENGTH);
	private final Rail turnBackRail;
	private final Button buttonSet;
	private final Button buttonCancel;

	private static final int MAX_VALUE_LENGTH = 6;
	private static final int LABEL_WIDTH = 150;
	private static final int ROW_HEIGHT = SQUARE_SIZE + TEXT_FIELD_PADDING;
	private static final int ROW_ANGLE = 0;
	private static final int ROW_TRANSITION = 1;
	private static final int ROW_KEY_NODE = 2;
	private static final int ROW_TURN_BACK = 3;
	private static final int ROW_BUTTONS = 4;
	private static final Component LABEL_ANGLE = Text.literal("Angle / 角度 (-180~180°)");
	private static final Component LABEL_TRANSITION = Text.literal("Transition / 缓和曲线 (Not available)");
	private static final Component LABEL_TURN_BACK = Text.literal("Turn back wait / 折返停留 (s)");

	public NodeAngleScreen(BlockPos pos) {
		super(Text.literal(""));
		this.pos = pos;
		final Level world = Minecraft.getInstance().level;
		final RailAngle facing = Rail.getNodeFacing(ClientData.RAILS.get(pos));
		angle = facing != null ? facing.angleDegrees : world == null ? 0 : BlockNode.getAngle(world, pos, world.getBlockState(pos));
		turnBackRail = getTurnBackRail(pos);
		buttonSet = UtilitiesClient.newButton(Text.literal("Set / 设定"), button -> onSet());
		buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
	}

	@Override
	protected void init() {
		super.init();
		final int fieldX = SQUARE_SIZE + LABEL_WIDTH + TEXT_FIELD_PADDING / 2;
		IDrawing.setPositionAndWidth(textFieldAngle, fieldX, getRowY(ROW_ANGLE) + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldTransition, fieldX, getRowY(ROW_TRANSITION) + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(checkboxKeyNode, SQUARE_SIZE, getRowY(ROW_KEY_NODE), PANEL_WIDTH);
		checkboxKeyNode.setChecked(ClientData.KEY_NODES.contains(pos));
		IDrawing.setPositionAndWidth(textFieldTurnBack, fieldX, getRowY(ROW_TURN_BACK) + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		textFieldTurnBack.setValue(turnBackRail == null || turnBackRail.turnBackDwell == 0 ? "" : String.valueOf(turnBackRail.turnBackDwell / 2));
		IDrawing.setPositionAndWidth(buttonSet, SQUARE_SIZE, getRowY(ROW_BUTTONS), PANEL_WIDTH / 2);
		IDrawing.setPositionAndWidth(buttonCancel, SQUARE_SIZE + PANEL_WIDTH / 2, getRowY(ROW_BUTTONS), PANEL_WIDTH / 2);

		textFieldAngle.setValue(BlockNode.getAngleValue(angle));
		textFieldTransition.setValue("0");
		textFieldTransition.setEditable(false);

		addDrawableChild(textFieldAngle);
		addDrawableChild(textFieldTransition);
		addDrawableChild(checkboxKeyNode);
		if (turnBackRail != null) {
			addDrawableChild(textFieldTurnBack);
		}
		addDrawableChild(buttonSet);
		addDrawableChild(buttonCancel);
	}

	@Override
	public void tick() {
		textFieldAngle.tick();
		textFieldTurnBack.tick();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.draw(matrices, font, LABEL_ANGLE, SQUARE_SIZE, getRowY(ROW_ANGLE) + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.draw(matrices, font, LABEL_TRANSITION, SQUARE_SIZE, getRowY(ROW_TRANSITION) + TEXT_PADDING, ARGB_LIGHT_GRAY);
			if (turnBackRail != null) {
				GuiCompat.draw(matrices, font, LABEL_TURN_BACK, SQUARE_SIZE, getRowY(ROW_TURN_BACK) + TEXT_PADDING, ARGB_WHITE);
			}
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
			final String turnBackText = textFieldTurnBack.getValue();
			final int turnBackDwell = turnBackRail == null ? -1 : turnBackText.isEmpty() ? 0 : Integer.parseInt(turnBackText) * 2;
			PacketTrainDataGuiClient.sendNodeAngleC2S(pos, Float.parseFloat(textFieldAngle.getValue()), checkboxKeyNode.selected(), turnBackDwell);
			onClose();
		} catch (NumberFormatException ignored) {
		}
	}

	private static Rail getTurnBackRail(BlockPos pos) {
		final Map<BlockPos, Rail> connections = ClientData.RAILS.get(pos);
		if (connections != null) {
			for (final Map.Entry<BlockPos, Rail> entry : connections.entrySet()) {
				final Map<BlockPos, Rail> backwardsConnections = ClientData.RAILS.get(entry.getKey());
				final Rail backwardsRail = backwardsConnections == null ? null : backwardsConnections.get(pos);
				if (entry.getValue().railType == RailType.TURN_BACK || backwardsRail != null && backwardsRail.railType == RailType.TURN_BACK) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	private static int getRowY(int row) {
		return SQUARE_SIZE + ROW_HEIGHT * row;
	}
}
