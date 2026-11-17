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
 * Since 0.100.0 the model and the platform's boarding arrows, which don't move, are bent once and kept ({@link Baked});
 * an error drawing a block skips it ({@link RenderGuard}).
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
		RenderGuard.draw("Curved screen door", entity.getBlockPos(), matrices, guarded -> renderBlock(entity, tickDelta, guarded, vertexConsumers, light, overlay));
	}

	private void renderBlock(T entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}
		final BlockState state = world.getBlockState(entity.getBlockPos());
		final CurvedPSD.BendHolder holder = entity instanceof CurvedPSD.Curved ? ((CurvedPSD.Curved) entity).getBendHolder() : null;
		final PlatformEdges.Bend ownBend = holder == null ? null : holder.getBend();
		if (ownBend == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return;
		}
		// Along the edge curve itself where the client knows the platform's rail (0.92.0), else along the block's chord
		final CurvePiece curvePiece = CurvePiece.of(holder.getCurve(), entity.getBlockPos());
		final PlatformEdges.Piece bend = curvePiece == null ? ownBend : curvePiece;
		final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		final BlockState straightState = CurvedPSD.straightState(state);
		final int arrowSide = state.hasProperty(BlockPSDAPGBase.HALF) && state.getValue(BlockPSDAPGBase.HALF) == DoubleBlockHalf.LOWER ? BlockPlatform.arrowSide(world, entity.getBlockPos()) : 0;

		// What doesn't move (the straight block's model, the platform's boarding arrows) bent once and kept (0.100.0,
		// user 2026-11-17: 缓存掉不变的), made again only when the block, its bend, the rail it follows or the arrows change
		Baked baked = BAKED.get(entity);
		final Object rail = curvePiece == null ? null : curvePiece.getRail();
		if (baked == null || !baked.matches(straightState, ownBend, holder.getCurve(), rail, arrowSide)) {
			baked = new Baked(straightState, ownBend, holder.getCurve(), rail, arrowSide, bend, facing);
			BAKED.put(entity, baked);
		}
		final VertexConsumer consumer = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
		emit(baked.model, matrices, consumer, light);
		if (baked.arrows.length > 0) {
			emit(baked.arrows, matrices, consumer, LevelRenderer.getLightColor(world, entity.getBlockPos()));
		}

		// What the straight block's own renderer draws (the leaves, the top's route map and strip) moves or changes: bent
		// as it is drawn
		if (straight != null) {
			final BendingVertexConsumer.Context context = new BendingVertexConsumer.Context(bend, facing, entity.getBlockPos(), matrices.last());
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

	/** Vertices kept as x, y, z, u, v and normal x, y, z, in the block's own coordinates, already bent */
	private static final int FLOATS = 8;
	private static final java.util.Map<Object, Baked> BAKED = new java.util.WeakHashMap<>();

	private static void emit(float[] vertices, PoseStack matrices, VertexConsumer consumer, int light) {
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();
		for (int i = 0; i + FLOATS <= vertices.length; i += FLOATS) {
			consumer.vertex(pose, vertices[i], vertices[i + 1], vertices[i + 2])
					.color(255, 255, 255, 255)
					.uv(vertices[i + 3], vertices[i + 4])
					.overlayCoords(OverlayTexture.NO_OVERLAY)
					.uv2(light)
					.normal(normal, vertices[i + 5], vertices[i + 6], vertices[i + 7])
					.endVertex();
		}
	}

	/**
	 * The unmoving part of a curved block, bent: the straight block's model as the chunk would draw it, and the arrow
	 * layer of MTR's platform block facing as the straight door does (only the quads above the platform's top), a block
	 * lower, as {@link BendingVertexConsumer} would bend them
	 */
	private static final class Baked {

		private final BlockState straightState;
		private final Object ownBend;
		private final Object curve;
		private final Object rail;
		private final int arrowSide;
		private final float[] model;
		private final float[] arrows;

		private Baked(BlockState straightState, Object ownBend, Object curve, Object rail, int arrowSide, PlatformEdges.Piece bend, Direction facing) {
			this.straightState = straightState;
			this.ownBend = ownBend;
			this.curve = curve;
			this.rail = rail;
			this.arrowSide = arrowSide;
			final int facingX = facing.getStepX();
			final int facingZ = facing.getStepZ();
			final java.util.List<float[]> modelVertices = new java.util.ArrayList<>();
			bake(straightState, QUAD_SIDES, false, bend, facingX, facingZ, modelVertices);
			model = flatten(modelVertices);
			final java.util.List<float[]> arrowVertices = new java.util.ArrayList<>();
			if (arrowSide > 0) {
				final BlockState platformState = mtr.Blocks.PLATFORM.get().defaultBlockState().setValue(BlockPlatform.FACING, straightState.getValue(BlockStateProperties.HORIZONTAL_FACING)).setValue(BlockPlatform.SIDE, arrowSide);
				bake(platformState, new Direction[]{null, Direction.UP}, true, bend, facingX, facingZ, arrowVertices);
			}
			arrows = flatten(arrowVertices);
		}

		private boolean matches(BlockState straightState, Object ownBend, Object curve, Object rail, int arrowSide) {
			return this.straightState == straightState && this.ownBend == ownBend && this.curve == curve && this.rail == rail && this.arrowSide == arrowSide;
		}

		private static void bake(BlockState blockState, Direction[] sides, boolean arrowsOnly, PlatformEdges.Piece bend, int facingX, int facingZ, java.util.List<float[]> out) {
			final BakedModel bakedModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockState);
			final RandomSource random = RandomSource.create();
			for (final Direction side : sides) {
				random.setSeed(42);
				for (final BakedQuad quad : bakedModel.getQuads(blockState, side, random)) {
					final int[] vertices = quad.getVertices();
					if (arrowsOnly) {
						boolean aboveTop = quad.getDirection() == Direction.UP;
						for (int i = 0; i < 4 && aboveTop; i++) {
							aboveTop = Float.intBitsToFloat(vertices[i * VERTEX_SIZE + 1]) > 1.001F;
						}
						if (!aboveTop) {
							continue;
						}
					}
					final Direction direction = arrowsOnly ? Direction.UP : quad.getDirection();
					final double[] bentNormal = bend.direction(facingX, facingZ, direction.getStepX(), direction.getStepZ());
					float normalX = (float) bentNormal[0], normalY = direction.getStepY(), normalZ = (float) bentNormal[1];
					final float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
					if (length < 1E-6F) {
						normalX = direction.getStepX();
						normalY = direction.getStepY();
						normalZ = direction.getStepZ();
					} else {
						normalX /= length;
						normalY /= length;
						normalZ /= length;
					}
					for (int i = 0; i < 4; i++) {
						final int offset = i * VERTEX_SIZE;
						final float x = Float.intBitsToFloat(vertices[offset]);
						final float y = Float.intBitsToFloat(vertices[offset + 1]) - (arrowsOnly ? 1 : 0);
						final float z = Float.intBitsToFloat(vertices[offset + 2]);
						final double[] bent = bend.point(facingX, facingZ, x, z);
						out.add(new float[]{(float) bent[0], y, (float) bent[1], Float.intBitsToFloat(vertices[offset + 4]), Float.intBitsToFloat(vertices[offset + 5]), normalX, normalY, normalZ});
					}
				}
			}
		}

		private static float[] flatten(java.util.List<float[]> vertices) {
			final float[] flat = new float[vertices.size() * FLOATS];
			for (int i = 0; i < vertices.size(); i++) {
				for (final float value : vertices.get(i)) {
					if (!Float.isFinite(value)) {
						// A bend gone wrong (bad data): nothing kept rather than vertices out in the world
						return new float[0];
					}
				}
				System.arraycopy(vertices.get(i), 0, flat, i * FLOATS, FLOATS);
			}
			return flat;
		}
	}
}
