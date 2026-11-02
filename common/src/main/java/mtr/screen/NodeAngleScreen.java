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
import mtr.advanced.PathBoundaries;
import mtr.advanced.RegionData;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
	private final WidgetBetterCheckbox checkboxBoundary = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal("Path boundary / 寻路边界（寻路不穿过这个节点）"), checked -> {
	});
	// The rails from this node, each of which can be a pathfinding boundary on its own
	private final List<BlockPos> connectedNodes = new ArrayList<>();
	private final List<WidgetBetterCheckbox> checkboxSegments = new ArrayList<>();
	private final Button buttonSet;
	private final Button buttonCancel;

	private static final int MAX_VALUE_LENGTH = 6;
	private static final int LABEL_WIDTH = 150;
	private static final int ROW_HEIGHT = SQUARE_SIZE + TEXT_FIELD_PADDING;
	private static final int ROW_ANGLE = 0;
	private static final int ROW_TRANSITION = 1;
	private static final int ROW_KEY_NODE = 2;
	private static final int ROW_TURN_BACK = 3;
	private static final int ROW_BOUNDARY = 4;
	private static final int ROW_SEGMENTS = 5;
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
		final Map<BlockPos, Rail> connections = ClientData.RAILS.get(pos);
		if (connections != null) {
			connectedNodes.addAll(connections.keySet());
			connectedNodes.sort(Comparator.comparingDouble((BlockPos endPos) -> endPos.distSqr(pos)).thenComparingLong(BlockPos::asLong));
		}
		connectedNodes.forEach(endPos -> checkboxSegments.add(new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal(String.format("Boundary rail to / 边界轨道 → %s, %s, %s", endPos.getX(), endPos.getY(), endPos.getZ())), checked -> {
		})));
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
		final RegionData regionData = ClientData.PATH_REGIONS;
		IDrawing.setPositionAndWidth(checkboxBoundary, SQUARE_SIZE, getRowY(ROW_BOUNDARY), PANEL_WIDTH);
		checkboxBoundary.setChecked(regionData.boundaries.nodes.contains(pos.asLong()));
		for (int i = 0; i < checkboxSegments.size(); i++) {
			IDrawing.setPositionAndWidth(checkboxSegments.get(i), SQUARE_SIZE, getRowY(ROW_SEGMENTS + i), PANEL_WIDTH);
			checkboxSegments.get(i).setChecked(regionData.boundaries.isSegment(pos.asLong(), connectedNodes.get(i).asLong()));
		}
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
	public boolean isPauseScreen() {
		return false;
	}

	private void onSet() {
		try {
			final String turnBackText = textFieldTurnBack.getValue();
			final int turnBackDwell = turnBackRail == null ? -1 : turnBackText.isEmpty() ? 0 : Integer.parseInt(turnBackText) * 2;
			PacketTrainDataGuiClient.sendNodeAngleC2S(pos, Float.parseFloat(textFieldAngle.getValue()), checkboxKeyNode.selected(), turnBackDwell);
			final PathBoundaries boundaries = ClientData.PATH_REGIONS.boundaries;
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
			return "Turnout: use a boundary rail / 这是道岔，节点边界会把每个分支都切开，请改为勾选下面的某一条边界轨道";
		}
		if (regionData.uncutNodes.contains(node)) {
			return "Both sides still connected / 两侧仍有别的连接，这个节点边界没有切开";
		}
		for (final BlockPos endPos : connectedNodes) {
			if (regionData.uncutSegments.contains(PathBoundaries.segmentKey(node, endPos.asLong()))) {
				return "Both sides still connected / 两侧仍有别的连接，这段边界轨道没有切开";
			}
		}
		return regionData.boundaries.isEmpty() ? null : String.format("Regions / 区域 %s 个", regionData.regionCount);
	}

	private static int getRowY(int row) {
		return SQUARE_SIZE + ROW_HEIGHT * row;
	}
}
