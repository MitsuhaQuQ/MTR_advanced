package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.advanced.PathBoundaries;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import transitsim.network.Boundaries;
import transitsim.network.RegionData;

import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import java.util.Map;

public class NodeAngleScreen extends ScreenBase implements IGui {

	private final BlockPos pos;
	private final float angle;
	private final WidgetBetterTextField textFieldAngle = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.SIGNED_DECIMAL, "", MAX_VALUE_LENGTH);
	private final WidgetBetterTextField textFieldTransition = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", MAX_VALUE_LENGTH);
	private final WidgetBetterCheckbox checkboxKeyNode = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.node_key_node"), checked -> {
	});
	private final WidgetBetterTextField textFieldTurnBack = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "0", MAX_VALUE_LENGTH);
	private final Rail turnBackRail;
	private final WidgetBetterCheckbox checkboxBoundary = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.node_path_boundary"), checked -> {
	});
	// The rails from this node, each of which can be a pathfinding boundary on its own
	private final List<BlockPos> connectedNodes = new ArrayList<>();
	private final List<WidgetBetterCheckbox> checkboxSegments = new ArrayList<>();
	private final NodeAnglePreview preview;
	private boolean draggingPreview;
	private final Button buttonSet;
	private final Button buttonCancel;

	private static final int MAX_VALUE_LENGTH = 6;
	private static final int LABEL_WIDTH = 150;
	private static final int ROW_HEIGHT = SQUARE_SIZE + TEXT_FIELD_PADDING;
	private static final int ROW_ANGLE = 0;
	// What the angle typed means, and how angles are counted
	private static final int ROW_ANGLE_DIRECTION = 1;
	private static final int ROW_ANGLE_RULE = 2;
	private static final int ROW_TRANSITION = 4;
	private static final int ROW_KEY_NODE = 5;
	private static final int ROW_TURN_BACK = 6;
	private static final int ROW_BOUNDARY = 7;
	private static final int ROW_SEGMENTS = 8;
	private static final int MIN_PREVIEW_SIZE = 64;
	private static final int MAX_PREVIEW_SIZE = 240;
	private static final Component LABEL_ANGLE = Text.translatable("gui.mtr.node_angle");
	private static final Component LABEL_PREVIEW_HINT = Text.translatable("gui.mtr.node_angle_preview_hint");
	private static final Component LABEL_ANGLE_RULE = Text.translatable("gui.mtr.node_angle_rule");
	private static final Component LABEL_TRANSITION = Text.translatable("gui.mtr.node_transition");
	private static final Component LABEL_TURN_BACK = Text.translatable("gui.mtr.node_turn_back");

	public NodeAngleScreen(BlockPos pos) {
		super(Text.literal(""));
		this.pos = pos;
		final Level world = Minecraft.getInstance().level;
		final RailAngle facing = Rail.getNodeFacing(ClientData.RAILS.get(pos));
		angle = facing != null ? facing.angleDegrees : world == null ? 0 : BlockNode.getAngle(world, pos, world.getBlockState(pos));
		turnBackRail = getTurnBackRail(pos);
		final Map<BlockPos, Rail> connections = ClientData.RAILS.get(pos);
		if (connections != null) {
			connectedNodes.addAll(connections.keySet());
			connectedNodes.sort(Comparator.comparingDouble((BlockPos endPos) -> endPos.distSqr(pos)).thenComparingLong(BlockPos::asLong));
		}
		connectedNodes.forEach(endPos -> checkboxSegments.add(new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.node_boundary_rail", endPos.getX(), endPos.getY(), endPos.getZ()), checked -> {
		})));
		preview = new NodeAnglePreview(pos);
		buttonSet = UtilitiesClient.newButton(Text.translatable("gui.mtr.node_set"), button -> onSet());
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
		final RegionData regionData = ClientData.PATH_REGIONS;
		IDrawing.setPositionAndWidth(checkboxBoundary, SQUARE_SIZE, getRowY(ROW_BOUNDARY), PANEL_WIDTH);
		checkboxBoundary.setChecked(regionData.boundaries.nodes.contains(pos.asLong()));
		for (int i = 0; i < checkboxSegments.size(); i++) {
			IDrawing.setPositionAndWidth(checkboxSegments.get(i), SQUARE_SIZE, getRowY(ROW_SEGMENTS + i), PANEL_WIDTH);
			checkboxSegments.get(i).setChecked(regionData.boundaries.isSegment(pos.asLong(), connectedNodes.get(i).asLong()));
		}
		// The map to the right of everything else, as large as fits
		final int previewX = fieldX + PANEL_WIDTH / 2 + SQUARE_SIZE;
		final int previewSize = Math.min(MAX_PREVIEW_SIZE, Math.min(width - previewX - SQUARE_SIZE, height - SQUARE_SIZE * 3));
		preview.setBounds(previewX, SQUARE_SIZE, previewSize >= MIN_PREVIEW_SIZE ? previewSize : 0);
		IDrawing.setPositionAndWidth(buttonSet, SQUARE_SIZE, getRowY(getButtonsRow()), PANEL_WIDTH / 2);
		IDrawing.setPositionAndWidth(buttonCancel, SQUARE_SIZE + PANEL_WIDTH / 2, getRowY(getButtonsRow()), PANEL_WIDTH / 2);

		textFieldAngle.setValue(BlockNode.getAngleValue(angle));
		textFieldTransition.setValue("0");
		textFieldTransition.setEditable(false);

		addDrawableChild(textFieldAngle);
		addDrawableChild(textFieldTransition);
		addDrawableChild(checkboxKeyNode);
		if (turnBackRail != null) {
			addDrawableChild(textFieldTurnBack);
		}
		addDrawableChild(checkboxBoundary);
		checkboxSegments.forEach(this::addDrawableChild);
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
			GuiCompat.draw(matrices, font, getAngleDirectionText(), SQUARE_SIZE, getRowY(ROW_ANGLE_DIRECTION) + TEXT_PADDING, ARGB_LIGHT_GRAY);
			final List<FormattedCharSequence> ruleLines = font.split(LABEL_ANGLE_RULE, LABEL_WIDTH + PANEL_WIDTH / 2);
			for (int i = 0; i < Math.min(2, ruleLines.size()); i++) {
				GuiCompat.draw(matrices, font, ruleLines.get(i), SQUARE_SIZE, getRowY(ROW_ANGLE_RULE) + TEXT_PADDING + i * (ROW_HEIGHT - 6), ARGB_LIGHT_GRAY);
			}
			final String previewError = preview.render(matrices, font, textFieldAngle.getValue());
			if (preview.getSize() > 0) {
				// Under the map and as wide as it, on as many lines as it takes
				final List<FormattedCharSequence> previewLines = font.split(previewError == null ? LABEL_PREVIEW_HINT : Text.translatable(previewError), Math.max(preview.getSize(), width - preview.getX() - TEXT_PADDING));
				for (int i = 0; i < previewLines.size(); i++) {
					GuiCompat.draw(matrices, font, previewLines.get(i), preview.getX(), preview.getY() + preview.getSize() + TEXT_PADDING + i * (TEXT_HEIGHT + 2), previewError == null ? ARGB_LIGHT_GRAY : 0xFFFF4040);
				}
			}
			GuiCompat.draw(matrices, font, LABEL_TRANSITION, SQUARE_SIZE, getRowY(ROW_TRANSITION) + TEXT_PADDING, ARGB_LIGHT_GRAY);
			if (turnBackRail != null) {
				GuiCompat.draw(matrices, font, LABEL_TURN_BACK, SQUARE_SIZE, getRowY(ROW_TURN_BACK) + TEXT_PADDING, ARGB_WHITE);
			}
			final String boundaryStatus = getBoundaryStatus();
			if (boundaryStatus != null) {
				GuiCompat.draw(matrices, font, boundaryStatus, SQUARE_SIZE, getRowY(getButtonsRow() + 1) + TEXT_PADDING, ARGB_LIGHT_GRAY);
			}
			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && preview.isInside(mouseX, mouseY)) {
			draggingPreview = true;
			setAngleFromPreview(mouseX, mouseY);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (draggingPreview) {
			setAngleFromPreview(mouseX, mouseY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingPreview = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	/**
	 * Points the node to the mouse, in steps of 22.5° with shift held.
	 */
	private void setAngleFromPreview(double mouseX, double mouseY) {
		textFieldAngle.setValue(BlockNode.getAngleValue(preview.getAngle(mouseX, mouseY, hasShiftDown())));
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
			final Boundaries boundaries = ClientData.PATH_REGIONS.boundaries;
			if (checkboxBoundary.selected() != boundaries.nodes.contains(pos.asLong())) {
				PacketTrainDataGuiClient.sendPathBoundaryC2S(pos, null, checkboxBoundary.selected());
			}
			for (int i = 0; i < checkboxSegments.size(); i++) {
				if (checkboxSegments.get(i).selected() != boundaries.isSegment(pos.asLong(), connectedNodes.get(i).asLong())) {
					PacketTrainDataGuiClient.sendPathBoundaryC2S(pos, connectedNodes.get(i), checkboxSegments.get(i).selected());
				}
			}
			onClose();
		} catch (NumberFormatException ignored) {
		}
	}

	/**
	 * The way the angle typed points, and the way the player faces to compare it with.
	 */
	private Component getAngleDirectionText() {
		final Player player = Minecraft.getInstance().player;
		final String playerAngle = player == null ? "-" : BlockNode.getAngleText(BlockNode.getPlayerAngle(player));
		try {
			return Text.translatable("gui.mtr.node_angle_direction", BlockNode.getAngleDirection(Float.parseFloat(textFieldAngle.getValue())), playerAngle);
		} catch (NumberFormatException e) {
			return Text.translatable("gui.mtr.node_angle_direction", "-", playerAngle);
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

	private int getButtonsRow() {
		return ROW_SEGMENTS + checkboxSegments.size();
	}

	/**
	 * Warns about boundaries here that don't cut anything, since the two regions would then be one.
	 */
	private String getBoundaryStatus() {
		final RegionData regionData = ClientData.PATH_REGIONS;
		final long node = pos.asLong();
		// At a turnout a node boundary separates every branch from the others, which is rarely what is wanted
		if (checkboxBoundary.selected() && connectedNodes.size() > 2) {
			return Text.translatable("gui.mtr.node_turnout_boundary").getString();
		}
		if (regionData.uncutNodes.contains(node)) {
			return Text.translatable("gui.mtr.node_not_cut_node").getString();
		}
		for (final BlockPos endPos : connectedNodes) {
			if (regionData.uncutSegments.contains(PathBoundaries.segmentKey(node, endPos.asLong()))) {
				return Text.translatable("gui.mtr.node_not_cut_rail").getString();
			}
		}
		return regionData.boundaries.isEmpty() ? null : Text.translatable("gui.mtr.node_regions", regionData.regionCount).getString();
	}

	private static int getRowY(int row) {
		return SQUARE_SIZE + ROW_HEIGHT * row;
	}
}
