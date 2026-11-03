package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.Lift;
import mtr.data.LiftClient;
import mtr.data.Train;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public class LiftCustomizationScreen extends ScreenBase implements IGui, IPacket {

	private final LiftClient lift;
	private final Button buttonHeightMinus;
	private final Button buttonHeightAdd;
	private final Button buttonWidthMinus;
	private final Button buttonWidthAdd;
	private final Button buttonDepthMinus;
	private final Button buttonDepthAdd;
	private final Button buttonOffsetXMinus;
	private final Button buttonOffsetXAdd;
	private final Button buttonOffsetYMinus;
	private final Button buttonOffsetYAdd;
	private final Button buttonOffsetZMinus;
	private final Button buttonOffsetZAdd;
	private final Button buttonMaxSpeedMinus;
	private final Button buttonMaxSpeedAdd;
	private final Button buttonAccelerationMinus;
	private final Button buttonAccelerationAdd;
	private final WidgetBetterCheckbox buttonIsDoubleSided;
	private final Button buttonLiftStyle;
	private final Button buttonRotateAnticlockwise;
	private final Button buttonRotateClockwise;
	private final int width1;
	private final int width2;

	private static final int MIN_DIMENSION = 2;
	private static final int MAX_DIMENSION = 16;
	private static final int MAX_OFFSET = 16;
	// Max speed changes in steps of 0.025 m/tick (0.5 m/s), acceleration in steps of 0.001 m/tick^2 (0.4 m/s^2)
	private static final int MAX_SPEED_STEPS = 40;
	private static final int ACCELERATION_STEPS = 1000;

	public LiftCustomizationScreen(LiftClient lift) {
		super(Text.literal(""));
		this.lift = lift;

		buttonHeightMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftHeight = Math.max(MIN_DIMENSION * 2, lift.liftHeight - 1);
			updateControls();
		});
		buttonHeightAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftHeight = Math.min(MAX_DIMENSION * 2, lift.liftHeight + 1);
			updateControls();
		});
		buttonWidthMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftWidth = Math.max(MIN_DIMENSION, lift.liftWidth - 1);
			updateControls();
		});
		buttonWidthAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftWidth = Math.min(MAX_DIMENSION, lift.liftWidth + 1);
			updateControls();
		});
		buttonDepthMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftDepth = Math.max(MIN_DIMENSION, lift.liftDepth - 1);
			updateControls();
		});
		buttonDepthAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftDepth = Math.min(MAX_DIMENSION, lift.liftDepth + 1);
			updateControls();
		});
		buttonOffsetXMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetX = Math.max(-MAX_OFFSET * 2, lift.liftOffsetX - 1);
			updateControls();
		});
		buttonOffsetXAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetX = Math.min(MAX_OFFSET * 2, lift.liftOffsetX + 1);
			updateControls();
		});
		buttonOffsetYMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetY = Math.max(-MAX_OFFSET, lift.liftOffsetY - 1);
			updateControls();
		});
		buttonOffsetYAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetY = Math.min(MAX_OFFSET, lift.liftOffsetY + 1);
			updateControls();
		});
		buttonOffsetZMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftOffsetZ = Math.max(-MAX_OFFSET * 2, lift.liftOffsetZ - 1);
			updateControls();
		});
		buttonOffsetZAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftOffsetZ = Math.min(MAX_OFFSET * 2, lift.liftOffsetZ + 1);
			updateControls();
		});
		buttonMaxSpeedMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftMaxSpeed = Lift.clampMaxSpeed((Math.round(lift.liftMaxSpeed * MAX_SPEED_STEPS) - 1D) / MAX_SPEED_STEPS);
			updateControls();
		});
		buttonMaxSpeedAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftMaxSpeed = Lift.clampMaxSpeed((Math.round(lift.liftMaxSpeed * MAX_SPEED_STEPS) + 1D) / MAX_SPEED_STEPS);
			updateControls();
		});
		buttonAccelerationMinus = UtilitiesClient.newButton(Text.literal("-"), button -> {
			lift.liftAcceleration = Lift.clampAcceleration((Math.round(lift.liftAcceleration * ACCELERATION_STEPS) - 1D) / ACCELERATION_STEPS);
			updateControls();
		});
		buttonAccelerationAdd = UtilitiesClient.newButton(Text.literal("+"), button -> {
			lift.liftAcceleration = Lift.clampAcceleration((Math.round(lift.liftAcceleration * ACCELERATION_STEPS) + 1D) / ACCELERATION_STEPS);
			updateControls();
		});
		final Component doubleSidedText = Text.translatable("gui.mtr.lift_is_double_sided");
		final Component rotateAnticlockwiseText = Text.translatable("gui.mtr.rotate_anticlockwise");
		final Component rotateClockwiseText = Text.translatable("gui.mtr.rotate_clockwise");
		buttonIsDoubleSided = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, doubleSidedText, checked -> lift.isDoubleSided = checked);
		buttonLiftStyle = UtilitiesClient.newButton(button -> {
			lift.liftStyle = Lift.LiftStyle.values()[(lift.liftStyle.ordinal() + 1) % Lift.LiftStyle.values().length];
			updateControls();
		});
		buttonRotateAnticlockwise = UtilitiesClient.newButton(rotateAnticlockwiseText, button -> lift.facing = lift.facing.getCounterClockWise());
		buttonRotateClockwise = UtilitiesClient.newButton(rotateClockwiseText, button -> lift.facing = lift.facing.getClockWise());

		font = Minecraft.getInstance().font;
		final int speedTextWidth = Math.max(font.width(getMaxSpeedText(Lift.MAX_SPEED_MAX)), font.width(getAccelerationText(Train.MAX_ACCELERATION))) + SQUARE_SIZE;
		width1 = Math.max(Math.max(Math.max(SQUARE_SIZE * 3, speedTextWidth), font.width(doubleSidedText)), Math.max(font.width(rotateAnticlockwiseText), font.width(rotateClockwiseText))) + TEXT_PADDING * 2;
		width2 = width1 + SQUARE_SIZE;
	}

	@Override
	protected void init() {
		super.init();

		IDrawing.setPositionAndWidth(buttonHeightMinus, 0, 0, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightAdd, width1, 0, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonWidthMinus, 0, SQUARE_SIZE, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonWidthAdd, width1, SQUARE_SIZE, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonDepthMinus, 0, SQUARE_SIZE * 2, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonDepthAdd, width1, SQUARE_SIZE * 2, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetXMinus, 0, SQUARE_SIZE * 3, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetXAdd, width1, SQUARE_SIZE * 3, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetYMinus, 0, SQUARE_SIZE * 4, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetYAdd, width1, SQUARE_SIZE * 4, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetZMinus, 0, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonOffsetZAdd, width1, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonMaxSpeedMinus, 0, SQUARE_SIZE * 7, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonMaxSpeedAdd, width1, SQUARE_SIZE * 7, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonAccelerationMinus, 0, SQUARE_SIZE * 8, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonAccelerationAdd, width1, SQUARE_SIZE * 8, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonIsDoubleSided, 0, SQUARE_SIZE * 10, width2);
		IDrawing.setPositionAndWidth(buttonLiftStyle, 0, SQUARE_SIZE * 11, width2);
		IDrawing.setPositionAndWidth(buttonRotateAnticlockwise, 0, SQUARE_SIZE * 12, width2);
		IDrawing.setPositionAndWidth(buttonRotateClockwise, 0, SQUARE_SIZE * 13, width2);

		addDrawableChild(buttonHeightMinus);
		addDrawableChild(buttonHeightAdd);
		addDrawableChild(buttonWidthMinus);
		addDrawableChild(buttonWidthAdd);
		addDrawableChild(buttonDepthMinus);
		addDrawableChild(buttonDepthAdd);
		addDrawableChild(buttonOffsetXMinus);
		addDrawableChild(buttonOffsetXAdd);
		addDrawableChild(buttonOffsetYMinus);
		addDrawableChild(buttonOffsetYAdd);
		addDrawableChild(buttonOffsetZMinus);
		addDrawableChild(buttonOffsetZAdd);
		addDrawableChild(buttonMaxSpeedMinus);
		addDrawableChild(buttonMaxSpeedAdd);
		addDrawableChild(buttonAccelerationMinus);
		addDrawableChild(buttonAccelerationAdd);
		addDrawableChild(buttonIsDoubleSided);
//		addDrawableChild(buttonLiftStyle);
		addDrawableChild(buttonRotateAnticlockwise);
		addDrawableChild(buttonRotateClockwise);
		updateControls();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			GuiCompat.fill(matrices, 0, 0, width2, height, ARGB_BACKGROUND);
			super.render(matrices, mouseX, mouseY, delta);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("tooltip.mtr.rail_action_height", lift.liftHeight / 2F), width2 / 2, TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("tooltip.mtr.rail_action_width", lift.liftWidth), width2 / 2, SQUARE_SIZE + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("tooltip.mtr.rail_action_depth", lift.liftDepth), width2 / 2, SQUARE_SIZE * 2 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.offset_x", lift.liftOffsetX / 2F), width2 / 2, SQUARE_SIZE * 3 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.offset_y", lift.liftOffsetY), width2 / 2, SQUARE_SIZE * 4 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.offset_z", lift.liftOffsetZ / 2F), width2 / 2, SQUARE_SIZE * 5 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, getMaxSpeedText(lift.liftMaxSpeed), width2 / 2, SQUARE_SIZE * 7 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, getAccelerationText(lift.liftAcceleration), width2 / 2, SQUARE_SIZE * 8 + TEXT_PADDING, ARGB_WHITE);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClose() {
		super.onClose();
		lift.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_LIFT, packet));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void updateControls() {
		buttonHeightMinus.active = lift.liftHeight > MIN_DIMENSION * 2;
		buttonHeightAdd.active = lift.liftHeight < MAX_DIMENSION * 2;
		buttonWidthMinus.active = lift.liftWidth > MIN_DIMENSION;
		buttonWidthAdd.active = lift.liftWidth < MAX_DIMENSION;
		buttonDepthMinus.active = lift.liftDepth > MIN_DIMENSION;
		buttonDepthAdd.active = lift.liftDepth < MAX_DIMENSION;
		buttonOffsetXMinus.active = lift.liftOffsetX > -MAX_OFFSET * 2;
		buttonOffsetXAdd.active = lift.liftOffsetX < MAX_OFFSET * 2;
		buttonOffsetYMinus.active = lift.liftOffsetY > -MAX_OFFSET;
		buttonOffsetYAdd.active = lift.liftOffsetY < MAX_OFFSET;
		buttonOffsetZMinus.active = lift.liftOffsetZ > -MAX_OFFSET * 2;
		buttonOffsetZAdd.active = lift.liftOffsetZ < MAX_OFFSET * 2;
		buttonMaxSpeedMinus.active = lift.liftMaxSpeed > Lift.MAX_SPEED_MIN + 1E-6;
		buttonMaxSpeedAdd.active = lift.liftMaxSpeed < Lift.MAX_SPEED_MAX - 1E-6;
		buttonAccelerationMinus.active = lift.liftAcceleration > Train.MIN_ACCELERATION + 1E-6;
		buttonAccelerationAdd.active = lift.liftAcceleration < Train.MAX_ACCELERATION - 1E-6;
		buttonIsDoubleSided.setChecked(lift.isDoubleSided);
		buttonLiftStyle.setMessage(Text.translatable("gui.mtr.lift_style", Text.translatable("gui.mtr.lift_style_" + lift.liftStyle.toString().toLowerCase(Locale.ENGLISH))));
	}

	private static Component getMaxSpeedText(double maxSpeed) {
		return Text.translatable("gui.mtr.lift_max_speed", String.format(Locale.ENGLISH, "%.1f", maxSpeed * 20));
	}

	private static Component getAccelerationText(double acceleration) {
		return Text.translatable("gui.mtr.lift_acceleration", String.format(Locale.ENGLISH, "%.1f", acceleration * 400));
	}
}
