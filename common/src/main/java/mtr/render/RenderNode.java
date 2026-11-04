package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Items;
import mtr.block.BlockNode;
import mtr.block.IBlock;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.IGui;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class RenderNode<T extends BlockNode.TileEntityNode> extends BlockEntityRendererMapper<T> {

	private static final float LINE_LENGTH = 1.5F;
	private static final float LINE_Y_CONNECTED = 0.125F;
	private static final float LINE_Y_NOT_CONNECTED = 1.05F;
	private static final float TEXT_Y_OFFSET = 0.6F;
	private static final float TEXT_SCALE = 0.025F;
	private static final int TEXT_LINE_HEIGHT = 10;
	private static final int MAX_TEXT_DISTANCE = 16;
	private static final float DIAL_RADIUS = 1;
	private static final float DIAL_Y_OFFSET = 0.01F;
	private static final float DIAL_DEGREES_PER_SEGMENT = 7.5F;
	private static final float DIAL_ARROW_LENGTH = 0.15F;

	public RenderNode(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		final Player player = Minecraft.getInstance().player;
		if (world == null || player == null) {
			return;
		}

		final boolean holdingBrush = player.isHolding(Items.BRUSH.get());
		if (!holdingBrush && !RenderTrains.isHoldingRailRelated(player)) {
			return;
		}

		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof BlockNode)) {
			return;
		}

		final float angle = BlockNode.getAngle(world, pos, state);
		final float offsetX = LINE_LENGTH * (float) Math.cos(Math.toRadians(angle));
		final float offsetZ = LINE_LENGTH * (float) Math.sin(Math.toRadians(angle));
		final float y = IBlock.getStatePropertySafe(state, BlockNode.IS_CONNECTED) ? LINE_Y_CONNECTED : LINE_Y_NOT_CONNECTED;
		IDrawing.drawLine(matrices, vertexConsumers, 0.5F - offsetX, y, 0.5F - offsetZ, 0.5F + offsetX, y, 0.5F + offsetZ, 0xFF, 0xFF, 0);

		// Connected nodes show the way their rails face, the same number the brush screen uses
		final RailAngle facing = Rail.getNodeFacing(ClientData.RAILS.get(pos));
		final float shownAngle = facing == null ? angle : facing.angleDegrees;
		if (holdingBrush) {
			renderAngleDial(matrices, vertexConsumers, y, shownAngle);
		}
		final String angleText = BlockNode.getAngleText(shownAngle);
		final Component directionText = BlockNode.getAngleDirection(shownAngle);
		final HitResult hitResult = Minecraft.getInstance().hitResult;
		if (hitResult instanceof BlockHitResult && ((BlockHitResult) hitResult).getBlockPos().equals(pos)) {
			player.displayClientMessage(Text.translatable("gui.mtr.node_angle_status", angleText, directionText, BlockNode.getAngleText(BlockNode.getPlayerAngle(player))), true);
		}

		if (holdingBrush && player.blockPosition().distSqr(pos) <= MAX_TEXT_DISTANCE * MAX_TEXT_DISTANCE) {
			renderText(matrices, vertexConsumers, angleText, directionText, getSlopeText(pos), y + TEXT_Y_OFFSET);
		}
	}

	/**
	 * Shows what the angle means: a red line from the node to the east, where angles start (0°), and a red arc from it to
	 * the angle, turning the way the angle counts (clockwise seen from above for a positive one), with an arrowhead at its
	 * end.
	 */
	private static void renderAngleDial(PoseStack matrices, MultiBufferSource vertexConsumers, float y, float angle) {
		final float dialY = y + DIAL_Y_OFFSET;
		IDrawing.drawLine(matrices, vertexConsumers, 0.5F, dialY, 0.5F, 0.5F + LINE_LENGTH, dialY, 0.5F, 0xFF, 0x30, 0x30);
		final float wrappedAngle = Mth.wrapDegrees(angle);
		final int segments = Math.max(1, Math.round(Math.abs(wrappedAngle) / DIAL_DEGREES_PER_SEGMENT));
		float previousX = 0.5F + DIAL_RADIUS;
		float previousZ = 0.5F;
		for (int i = 1; i <= segments; i++) {
			final double radians = Math.toRadians(wrappedAngle * i / segments);
			final float x = 0.5F + DIAL_RADIUS * (float) Math.cos(radians);
			final float z = 0.5F + DIAL_RADIUS * (float) Math.sin(radians);
			IDrawing.drawLine(matrices, vertexConsumers, previousX, dialY, previousZ, x, dialY, z, 0xFF, 0x30, 0x30);
			previousX = x;
			previousZ = z;
		}
		if (Math.abs(wrappedAngle) >= DIAL_DEGREES_PER_SEGMENT) {
			// Two short strokes back from the end of the arc, on either side of it
			final double endRadians = Math.toRadians(wrappedAngle);
			final double back = -Math.signum(wrappedAngle);
			final double tangentX = -Math.sin(endRadians) * back;
			final double tangentZ = Math.cos(endRadians) * back;
			for (final int side : new int[]{-1, 1}) {
				final float x = previousX + (float) ((tangentX + side * Math.cos(endRadians) * 0.6) * DIAL_ARROW_LENGTH);
				final float z = previousZ + (float) ((tangentZ + side * Math.sin(endRadians) * 0.6) * DIAL_ARROW_LENGTH);
				IDrawing.drawLine(matrices, vertexConsumers, previousX, dialY, previousZ, x, dialY, z, 0xFF, 0x30, 0x30);
			}
		}
	}

	private static void renderText(PoseStack matrices, MultiBufferSource vertexConsumers, String angleText, Component directionText, String slopeText, float y) {
		final Minecraft minecraft = Minecraft.getInstance();
		final Camera camera = minecraft.gameRenderer.getMainCamera();
		matrices.pushPose();
		matrices.translate(0.5, y, 0.5);
		UtilitiesClient.rotateYDegrees(matrices, -camera.getYRot());
		UtilitiesClient.rotateXDegrees(matrices, camera.getXRot());
		matrices.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
		drawCentered(matrices, vertexConsumers, minecraft.font, Text.literal(angleText), -TEXT_LINE_HEIGHT * 2);
		drawCentered(matrices, vertexConsumers, minecraft.font, directionText, -TEXT_LINE_HEIGHT);
		drawCentered(matrices, vertexConsumers, minecraft.font, Text.literal(slopeText), 0);
		matrices.popPose();
	}

	private static void drawCentered(PoseStack matrices, MultiBufferSource vertexConsumers, Font font, Component text, int y) {
		UtilitiesClient.drawInBatch(font, text.getVisualOrderText(), -font.width(text) / 2F, y, IGui.ARGB_WHITE, true, matrices.last().pose(), vertexConsumers, 0, IGui.MAX_LIGHT_GLOWING);
	}

	// Nodes don't store a slope yet, so this is the average grade of each connected rail, going away from the node
	private static String getSlopeText(BlockPos pos) {
		final Map<BlockPos, Rail> connections = ClientData.RAILS.get(pos);
		if (connections == null || connections.isEmpty()) {
			return "-";
		}
		return connections.entrySet().stream().map(entry -> {
			final double length = entry.getValue().getLength();
			return String.format(Locale.ENGLISH, "%+.2f%%", length > 0 ? (entry.getKey().getY() - pos.getY()) * 100 / length : 0);
		}).collect(Collectors.joining(" / "));
	}
}
