package mtr.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.CurvedPSD;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import transitsim.geometry.PlatformEdges;

/**
 * Draws a curved screen door or gate block (0.91.0, {@link CurvedPSD}) as its straight block, bent onto its piece of the
 * edge line: the straight block's model (the glass, the top's body) and then what the straight block's own renderer draws
 * (the leaves, the top's route map, colour strip and arrows, the gate's sign), all through {@link BendingVertexConsumer}.
 */
public class RenderCurvedPSD<T extends BlockEntityMapper> extends BlockEntityRendererMapper<T> {

	private static final Direction[] QUAD_SIDES = {null, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final int VERTEX_SIZE = DefaultVertexFormat.BLOCK.getIntegerSize();

	/**
	 * The straight block's renderer, null for one drawn by its model only
	 */
	private final BlockEntityRenderer<? super T> straight;

	public RenderCurvedPSD(BlockEntityRenderDispatcher dispatcher, BlockEntityRenderer<? super T> straight) {
		super(dispatcher);
		this.straight = straight;
	}

	@Override
	public void render(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}
		final BlockState state = world.getBlockState(entity.getBlockPos());
		final PlatformEdges.Bend bend = entity instanceof CurvedPSD.Curved ? ((CurvedPSD.Curved) entity).getBendHolder().getBend() : null;
		if (bend == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return;
		}
		final BendingVertexConsumer.Context context = new BendingVertexConsumer.Context(entity.getBlockPos(), bend, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
		final BlockState straightState = CurvedPSD.straightState(state);
		drawModel(straightState, matrices, new BendingVertexConsumer(vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)), context), light);
		if (straight != null) {
			BendingVertexConsumer.drawBent(context, () -> straight.render(entity, tickDelta, matrices, BendingVertexConsumer.wrap(vertexConsumers, context), light, overlay));
		}
	}

	@Override
	public boolean shouldRenderOffScreen(T blockEntity) {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	/**
	 * Draws the straight block's model as the chunk would, through the bending consumer
	 */
	private static void drawModel(BlockState straightState, PoseStack matrices, VertexConsumer consumer, int light) {
		final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(straightState);
		final RandomSource random = RandomSource.create();
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();
		for (final Direction side : QUAD_SIDES) {
			random.setSeed(42);
			for (final BakedQuad quad : model.getQuads(straightState, side, random)) {
				final int[] vertices = quad.getVertices();
				final Direction direction = quad.getDirection();
				for (int i = 0; i < 4; i++) {
					final int offset = i * VERTEX_SIZE;
					consumer.vertex(pose, Float.intBitsToFloat(vertices[offset]), Float.intBitsToFloat(vertices[offset + 1]), Float.intBitsToFloat(vertices[offset + 2]))
							.color(255, 255, 255, 255)
							.uv(Float.intBitsToFloat(vertices[offset + 4]), Float.intBitsToFloat(vertices[offset + 5]))
							.overlayCoords(OverlayTexture.NO_OVERLAY)
							.uv2(light)
							.normal(normal, direction.getStepX(), direction.getStepY(), direction.getStepZ())
							.endVertex();
				}
			}
		}
	}
}
