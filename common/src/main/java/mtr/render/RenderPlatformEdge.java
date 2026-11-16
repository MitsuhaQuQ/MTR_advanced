package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.BlockPlatformEdge;
import mtr.block.IBlock;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import transitsim.geometry.PlatformEdges;

/**
 * Draws a platform edge block (0.88.0): the platform's part of the block as a prism, its top in the platform's texture,
 * its sides in stone's, the cut face too.
 */
public class RenderPlatformEdge extends BlockEntityRendererMapper<BlockPlatformEdge.TileEntityPlatformEdge> {

	private static final ResourceLocation TOP = new ResourceLocation("mtr:textures/block/platform_hk_1.png");
	private static final ResourceLocation SIDE = new ResourceLocation("minecraft:textures/block/stone.png");

	public RenderPlatformEdge(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(BlockPlatformEdge.TileEntityPlatformEdge entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}
		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof BlockPlatformEdge)) {
			return;
		}
		final double[] outline = PlatformEdges.outline(IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE), IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT));
		final int corners = outline.length / 2;
		if (corners < 3) {
			return;
		}
		final int topLight = LevelRenderer.getLightColor(world, pos.above());
		final int sideLight = LevelRenderer.getLightColor(world, pos);
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();

		// The top and the bottom, as a fan of triangles drawn as quads with the last corner twice
		final VertexConsumer top = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TOP));
		for (int i = 1; i + 1 < corners; i++) {
			final float x0 = (float) outline[0], z0 = (float) outline[1];
			final float x1 = (float) outline[i * 2], z1 = (float) outline[i * 2 + 1];
			final float x2 = (float) outline[i * 2 + 2], z2 = (float) outline[i * 2 + 3];
			vertex(top, pose, normal, x0, 1, z0, x0, z0, 0, 1, 0, topLight);
			vertex(top, pose, normal, x1, 1, z1, x1, z1, 0, 1, 0, topLight);
			vertex(top, pose, normal, x2, 1, z2, x2, z2, 0, 1, 0, topLight);
			vertex(top, pose, normal, x2, 1, z2, x2, z2, 0, 1, 0, topLight);
		}
		final VertexConsumer side = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(SIDE));
		for (int i = 1; i + 1 < corners; i++) {
			final float x0 = (float) outline[0], z0 = (float) outline[1];
			final float x1 = (float) outline[i * 2], z1 = (float) outline[i * 2 + 1];
			final float x2 = (float) outline[i * 2 + 2], z2 = (float) outline[i * 2 + 3];
			vertex(side, pose, normal, x0, 0, z0, x0, z0, 0, -1, 0, sideLight);
			vertex(side, pose, normal, x2, 0, z2, x2, z2, 0, -1, 0, sideLight);
			vertex(side, pose, normal, x1, 0, z1, x1, z1, 0, -1, 0, sideLight);
			vertex(side, pose, normal, x1, 0, z1, x1, z1, 0, -1, 0, sideLight);
		}
		// Each side of the outline, the cut among them, from the bottom to the top
		for (int i = 0; i < corners; i++) {
			final float x1 = (float) outline[i * 2], z1 = (float) outline[i * 2 + 1];
			final float x2 = (float) outline[(i + 1) % corners * 2], z2 = (float) outline[(i + 1) % corners * 2 + 1];
			final float length = (float) Math.hypot(x2 - x1, z2 - z1);
			if (length < 1E-4) {
				continue;
			}
			// Outwards: the outline goes round anticlockwise seen from above when x is east and z south
			final float normalX = (z2 - z1) / length;
			final float normalZ = -(x2 - x1) / length;
			final float u1 = x1 * Math.abs(normalZ) + z1 * Math.abs(normalX);
			final float u2 = x2 * Math.abs(normalZ) + z2 * Math.abs(normalX);
			vertex(side, pose, normal, x1, 0, z1, u1, 1, normalX, 0, normalZ, sideLight);
			vertex(side, pose, normal, x2, 0, z2, u2, 1, normalX, 0, normalZ, sideLight);
			vertex(side, pose, normal, x2, 1, z2, u2, 0, normalX, 0, normalZ, sideLight);
			vertex(side, pose, normal, x1, 1, z1, u1, 0, normalX, 0, normalZ, sideLight);
		}
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ, int light) {
		consumer.vertex(pose, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, normalX, normalY, normalZ).endVertex();
	}
}
