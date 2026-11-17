package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.block.TileEntitySignalBase;
import mtr.client.IDrawing;
import mtr.client.SignalAspects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * While a signal is looked at, the block section it protects is drawn along its rails (0.99.0, design 7.6), from the node
 * it is bound to up to the next section, every way past a turnout.
 */
public final class RenderSignalHighlight {

	private static final double LINE_Y_OFFSET = 0.5;

	private RenderSignalHighlight() {
	}

	public static void render(PoseStack matrices, MultiBufferSource vertexConsumers, Level world) {
		final HitResult hitResult = Minecraft.getInstance().hitResult;
		if (world == null || !(hitResult instanceof BlockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
			return;
		}
		final BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
		final BlockEntity entity = world.getBlockEntity(pos);
		if (!(entity instanceof TileEntitySignalBase) || !((TileEntitySignalBase) entity).hasBinding()) {
			return;
		}
		final TileEntitySignalBase signal = (TileEntitySignalBase) entity;
		final BlockPos node = signal.getNode(0);
		if (node == null) {
			return;
		}
		final List<double[]> lines = SignalAspects.getFirstSection(node, signal.getAngle(0));
		for (final double[] line : lines) {
			IDrawing.drawLineWorld(matrices, vertexConsumers, line[0], line[1] + LINE_Y_OFFSET, line[2], line[3], line[4] + LINE_Y_OFFSET, line[5], 0, 0xFF, 0xFF);
		}
		// The node itself, a short post
		IDrawing.drawLineWorld(matrices, vertexConsumers, node.getX() + 0.5, node.getY(), node.getZ() + 0.5, node.getX() + 0.5, node.getY() + 2, node.getZ() + 0.5, 0, 0xFF, 0xFF);
	}
}
