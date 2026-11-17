package mtr.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPlatform;
import mtr.block.CurvedPSD;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
		final PlatformEdges.Bend ownBend = entity instanceof CurvedPSD.Curved ? ((CurvedPSD.Curved) entity).getBendHolder().getBend() : null;
		if (ownBend == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return;
		}
		// Along the edge curve itself where the client knows the platform's rail (0.92.0), else along the block's chord
		final CurvePiece curvePiece = CurvePiece.of(((CurvedPSD.Curved) entity).getBendHolder().getCurve(), entity.getBlockPos());
		final PlatformEdges.Piece bend = curvePiece == null ? ownBend : curvePiece;
		final BendingVertexConsumer.Context context = new BendingVertexConsumer.Context(bend, state.getValue(BlockStateProperties.HORIZONTAL_FACING), entity.getBlockPos(), matrices.last());
		final BlockState straightState = CurvedPSD.straightState(state);
		drawModel(straightState, matrices, new BendingVertexConsumer(vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)), context), light);
		// The boarding arrows on the platform behind a door and the glass either side (0.97.0): MTR's platform block's
		// arrows as it has them under straight doors, a block down and bent along the edge with the column
		if (state.hasProperty(BlockPSDAPGBase.HALF) && state.getValue(BlockPSDAPGBase.HALF) == DoubleBlockHalf.LOWER) {
			final int side = BlockPlatform.arrowSide(world, entity.getBlockPos());
			if (side > 0) {
				drawArrows(straightState, side, matrices, new BendingVertexConsumer(vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)), context), LevelRenderer.getLightColor(world, entity.getBlockPos()));
			}
		}
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
	 * Draws the arrow layer of MTR's platform block facing as the straight door does, with those arrows, a block lower:
	 * only the quads above the platform's top, not the platform itself
	 */
	private static void drawArrows(BlockState straightState, int side, PoseStack matrices, VertexConsumer consumer, int light) {
		final BlockState platformState = mtr.Blocks.PLATFORM.get().defaultBlockState().setValue(BlockPlatform.FACING, straightState.getValue(BlockStateProperties.HORIZONTAL_FACING)).setValue(BlockPlatform.SIDE, side);
		final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(platformState);
		final RandomSource random = RandomSource.create();
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();
		for (final Direction quadSide : new Direction[]{null, Direction.UP}) {
			random.setSeed(42);
			for (final BakedQuad quad : model.getQuads(platformState, quadSide, random)) {
				final int[] vertices = quad.getVertices();
				boolean aboveTop = quad.getDirection() == Direction.UP;
				for (int i = 0; i < 4 && aboveTop; i++) {
					aboveTop = Float.intBitsToFloat(vertices[i * VERTEX_SIZE + 1]) > 1.001F;
				}
				if (!aboveTop) {
					continue;
				}
				for (int i = 0; i < 4; i++) {
					final int offset = i * VERTEX_SIZE;
					consumer.vertex(pose, Float.intBitsToFloat(vertices[offset]), Float.intBitsToFloat(vertices[offset + 1]) - 1, Float.intBitsToFloat(vertices[offset + 2]))
							.color(255, 255, 255, 255)
							.uv(Float.intBitsToFloat(vertices[offset + 4]), Float.intBitsToFloat(vertices[offset + 5]))
							.overlayCoords(OverlayTexture.NO_OVERLAY)
							.uv2(light)
							.normal(normal, 0, 1, 0)
							.endVertex();
				}
			}
		}
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
