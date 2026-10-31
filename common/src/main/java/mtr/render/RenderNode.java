package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.BlockNode;
import mtr.block.IBlock;
import mtr.client.IDrawing;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class RenderNode<T extends BlockNode.TileEntityNode> extends BlockEntityRendererMapper<T> {

	private static final float LINE_LENGTH = 1.5F;
	private static final float LINE_Y_CONNECTED = 0.125F;
	private static final float LINE_Y_NOT_CONNECTED = 1.05F;

	public RenderNode(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		final Player player = Minecraft.getInstance().player;
		if (world == null || player == null || !RenderTrains.isHoldingRailRelated(player)) {
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

		final HitResult hitResult = Minecraft.getInstance().hitResult;
		if (hitResult instanceof BlockHitResult && ((BlockHitResult) hitResult).getBlockPos().equals(pos)) {
			player.displayClientMessage(Text.literal(BlockNode.getAngleText(angle)), true);
		}
	}
}
