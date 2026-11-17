package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTR;
import mtr.block.BlockFreeEscalator.TileEntityFreeEscalator;
import mtr.client.IDrawing;
import mtr.escalator.FreeEscalatorPath;
import mtr.mappings.BlockEntityRendererMapper;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Draws a whole free escalator from its first block (0.100.0): the steps (level treads with risers) or a walkway's
 * pallets moving along the run, the skirts and the underside, glass balustrades with dark handrails along both sides,
 * and comb plates over both ends where the steps go under. The steps move at the escalator's speed seen from above
 * ({@link FreeEscalatorPath#horizontalSpeed}), as the riders are carried.
 */
public class RenderFreeEscalator extends BlockEntityRendererMapper<TileEntityFreeEscalator> {

	private static final ResourceLocation STEPS = new ResourceLocation(MTR.MOD_ID, "textures/block/escalator_stop.png");
	private static final ResourceLocation FRAME = new ResourceLocation(MTR.MOD_ID, "textures/block/escalator.png");
	// The steps' texture (320 x 320): a grooved tread with its yellow edge, the riser under it
	private static final float STEP_U1 = 64 / 320F, STEP_U2 = 128 / 320F, TREAD_V1 = 0, TREAD_V2 = 32 / 320F, RISER_V1 = 32 / 320F, RISER_V2 = 64 / 320F;
	// The frame's texture (128 x 128): a light panel, a dark one and the landing plate
	private static final float PANEL_U1 = 2 / 128F, PANEL_U2 = 30 / 128F, PANEL_V1 = 20 / 128F, PANEL_V2 = 50 / 128F;
	private static final float DARK_U1 = 2 / 128F, DARK_U2 = 12 / 128F, DARK_V1 = 2 / 128F, DARK_V2 = 12 / 128F;
	private static final float PLATE_U1 = 2 / 128F, PLATE_U2 = 30 / 128F, PLATE_V1 = 58 / 128F, PLATE_V2 = 86 / 128F;

	/** Pieces the sides are drawn in, along the run */
	private static final double PIECE = 0.25;
	private static final double SKIRT_TOP = 0.12, SKIRT_BOTTOM = 0.45;
	private static final double RAIL_HEIGHT = 1, RAIL_WIDTH = 0.08, BALUSTRADE_OUT = 0.06;
	private static final double PLATE_LENGTH = 0.45;
	/** How far the handrails run on past the ends */
	private static final double NEWEL = 0.8;
	private static final int GLASS = 0x70D8E8F0, RAIL = 0xFF2A2A2A, SKIRT = 0xFFB8B8B8, UNDERSIDE = 0xFF8A8A8A;

	public RenderFreeEscalator(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(TileEntityFreeEscalator entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		// An error drawing one block (bad data read from a save, say) skips it, never crashing the game (0.100.0)
		RenderGuard.draw("Free escalator", entity.getBlockPos(), matrices, guarded -> renderBlock(entity, tickDelta, guarded, vertexConsumers, light, overlay));
	}

	private void renderBlock(TileEntityFreeEscalator entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null || !entity.isHead()) {
			return;
		}
		final FreeEscalatorPath path = entity.getPath();
		if (path == null) {
			return;
		}
		final BlockPos origin = entity.getBlockPos();
		final Frame frame = new Frame(path, matrices, world, origin);

		final double stepsMoved;
		if (entity.getMode() == TileEntityFreeEscalator.MODE_STOP) {
			stepsMoved = 0;
		} else {
			final double perTick = path.horizontalSpeed(entity.getSpeed()) / FreeEscalatorPath.STEP_DEPTH;
			final double steps = ((world.getGameTime() % 1000000L) + tickDelta) * perTick;
			stepsMoved = entity.getMode() == TileEntityFreeEscalator.MODE_UP ? steps : -steps;
		}
		final double phase = stepsMoved - Math.floor(stepsMoved);

		final VertexConsumer steps = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(STEPS));
		final double half = path.width / 2;
		path.forEachStep(phase, (back, from, fromHeight, to, toHeight, belowHeight) -> {
			final float v1 = (float) (TREAD_V1 + (from - back) / FreeEscalatorPath.STEP_DEPTH * (TREAD_V2 - TREAD_V1));
			final float v2 = (float) (TREAD_V1 + (to - back) / FreeEscalatorPath.STEP_DEPTH * (TREAD_V2 - TREAD_V1));
			// Each half from the middle out, the yellow edge outside
			for (final double side : new double[]{-1, 1}) {
				frame.quad(steps, to, toHeight, side * half, to, toHeight, 0, from, fromHeight, 0, from, fromHeight, side * half, STEP_U1, v1, STEP_U2, v2, Direction.UP, -1);
			}
			if (path.stepped && back >= 0 && fromHeight - belowHeight > 0.002) {
				final float riser = (float) Math.min(1, (fromHeight - belowHeight) / FreeEscalatorPath.STEP_DEPTH);
				for (final double side : new double[]{-1, 1}) {
					frame.quad(steps, back, belowHeight, side * half, back, belowHeight, 0, back, fromHeight, 0, back, fromHeight, side * half, STEP_U1, RISER_V1, STEP_U2, RISER_V1 + riser * (RISER_V2 - RISER_V1), Direction.DOWN, -1);
				}
			}
		});

		final VertexConsumer solid = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(FRAME));
		final VertexConsumer glass = vertexConsumers.getBuffer(RenderType.entityTranslucent(FRAME));
		final int pieces = Math.max(1, (int) Math.ceil(path.length / PIECE));
		for (int i = 0; i < pieces; i++) {
			final double a = path.length * i / pieces;
			final double b = path.length * (i + 1) / pieces;
			final double ha = path.height(a);
			final double hb = path.height(b);
			for (final double side : new double[]{-1, 1}) {
				final double inner = side * half;
				final double outer = side * (half + BALUSTRADE_OUT);
				// Skirt beside the steps, and its outside
				frame.quad(solid, b, hb - SKIRT_BOTTOM, inner, a, ha - SKIRT_BOTTOM, inner, a, ha + SKIRT_TOP, inner, b, hb + SKIRT_TOP, inner, PANEL_U1, PANEL_V1, PANEL_U2, PANEL_V2, Direction.UP, SKIRT);
				frame.quad(solid, b, hb - SKIRT_BOTTOM, outer, a, ha - SKIRT_BOTTOM, outer, a, ha + SKIRT_TOP, outer, b, hb + SKIRT_TOP, outer, PANEL_U1, PANEL_V1, PANEL_U2, PANEL_V2, Direction.UP, SKIRT);
				frame.quad(solid, b, hb + SKIRT_TOP, inner, a, ha + SKIRT_TOP, inner, a, ha + SKIRT_TOP, outer, b, hb + SKIRT_TOP, outer, PANEL_U1, PANEL_V1, PANEL_U2, PANEL_V2, Direction.UP, SKIRT);
			}
			// Underside
			final double edge = half + BALUSTRADE_OUT;
			frame.quad(solid, b, hb - SKIRT_BOTTOM, -edge, a, ha - SKIRT_BOTTOM, -edge, a, ha - SKIRT_BOTTOM, edge, b, hb - SKIRT_BOTTOM, edge, PANEL_U1, PANEL_V1, PANEL_U2, PANEL_V2, Direction.DOWN, UNDERSIDE);
		}

		// Balustrades and handrails, running on level past both ends (the newels), closed there
		final int railPieces = Math.max(1, (int) Math.ceil((path.length + 2 * NEWEL) / PIECE));
		for (int i = 0; i < railPieces; i++) {
			final double a = -NEWEL + (path.length + 2 * NEWEL) * i / railPieces;
			final double b = -NEWEL + (path.length + 2 * NEWEL) * (i + 1) / railPieces;
			final double ha = path.height(a);
			final double hb = path.height(b);
			for (final double side : new double[]{-1, 1}) {
				final double inner = side * half;
				final double outer = side * (half + BALUSTRADE_OUT);
				// Glass balustrade
				final double middle = (inner + outer) / 2;
				frame.quad(glass, b, hb + SKIRT_TOP, middle, a, ha + SKIRT_TOP, middle, a, ha + RAIL_HEIGHT, middle, b, hb + RAIL_HEIGHT, middle, PANEL_U1, PANEL_V1, PANEL_U2, PANEL_V2, Direction.UP, GLASS);
				// Handrail: top and both sides
				final double railIn = middle - RAIL_WIDTH / 2, railOut = middle + RAIL_WIDTH / 2;
				final double top = RAIL_HEIGHT + 0.07;
				frame.quad(solid, b, hb + top, railIn, a, ha + top, railIn, a, ha + top, railOut, b, hb + top, railOut, DARK_U1, DARK_V1, DARK_U2, DARK_V2, Direction.UP, RAIL);
				frame.quad(solid, b, hb + RAIL_HEIGHT, railIn, a, ha + RAIL_HEIGHT, railIn, a, ha + top, railIn, b, hb + top, railIn, DARK_U1, DARK_V1, DARK_U2, DARK_V2, Direction.UP, RAIL);
				frame.quad(solid, b, hb + RAIL_HEIGHT, railOut, a, ha + RAIL_HEIGHT, railOut, a, ha + top, railOut, b, hb + top, railOut, DARK_U1, DARK_V1, DARK_U2, DARK_V2, Direction.UP, RAIL);
				frame.quad(solid, b, hb + RAIL_HEIGHT, railIn, a, ha + RAIL_HEIGHT, railIn, a, ha + RAIL_HEIGHT, railOut, b, hb + RAIL_HEIGHT, railOut, DARK_U1, DARK_V1, DARK_U2, DARK_V2, Direction.DOWN, RAIL);
			}
		}
		for (final double side : new double[]{-1, 1}) {
			final double middle = side * (half + BALUSTRADE_OUT / 2);
			for (final double end : new double[]{-NEWEL, path.length + NEWEL}) {
				final double h = path.height(end);
				frame.quad(solid, end, h + SKIRT_TOP, middle - RAIL_WIDTH / 2, end, h + SKIRT_TOP, middle + RAIL_WIDTH / 2, end, h + RAIL_HEIGHT + 0.07, middle + RAIL_WIDTH / 2, end, h + RAIL_HEIGHT + 0.07, middle - RAIL_WIDTH / 2, DARK_U1, DARK_V1, DARK_U2, DARK_V2, Direction.UP, RAIL);
			}
		}

		// Comb plates over both ends
		final double bottom = path.height(0) + 0.01;
		final double topEnd = path.height(path.length) + 0.01;
		frame.quad(solid, 0.08, bottom, -half, 0.08, bottom, half, -PLATE_LENGTH, bottom, half, -PLATE_LENGTH, bottom, -half, PLATE_U1, PLATE_V1, PLATE_U2, PLATE_V2, Direction.UP, -1);
		frame.quad(solid, path.length + PLATE_LENGTH, topEnd, -half, path.length + PLATE_LENGTH, topEnd, half, path.length - 0.08, topEnd, half, path.length - 0.08, topEnd, -half, PLATE_U1, PLATE_V1, PLATE_U2, PLATE_V2, Direction.UP, -1);
	}

	@Override
	public boolean shouldRenderOffScreen(TileEntityFreeEscalator blockEntity) {
		return true;
	}

	/** Points given along the run (s, height, side) drawn relative to the first block */
	private static final class Frame {

		private final FreeEscalatorPath path;
		private final PoseStack matrices;
		private final Level world;
		private final BlockPos origin;
		private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

		private Frame(FreeEscalatorPath path, PoseStack matrices, Level world, BlockPos origin) {
			this.path = path;
			this.matrices = matrices;
			this.world = world;
			this.origin = origin;
		}

		private void quad(VertexConsumer consumer, double s1, double h1, double w1, double s2, double h2, double w2, double s3, double h3, double w3, double s4, double h4, double w4, float u1, float v1, float u2, float v2, Direction normal, int color) {
			final double midS = (s1 + s3) / 2;
			final double midW = (w1 + w3) / 2;
			lightPos.set(Math.floor(path.worldX(midS, midW)), Math.floor(Math.max(h1, h3) + 0.3), Math.floor(path.worldZ(midS, midW)));
			final int light = LevelRenderer.getLightColor(world, lightPos);
			IDrawing.drawTexture(matrices, consumer,
					x(s1, w1), (float) (h1 - origin.getY()), z(s1, w1),
					x(s2, w2), (float) (h2 - origin.getY()), z(s2, w2),
					x(s3, w3), (float) (h3 - origin.getY()), z(s3, w3),
					x(s4, w4), (float) (h4 - origin.getY()), z(s4, w4),
					u1, v1, u2, v2, normal, color, light);
		}

		private float x(double s, double w) {
			return (float) (path.worldX(s, w) - origin.getX());
		}

		private float z(double s, double w) {
			return (float) (path.worldZ(s, w) - origin.getZ());
		}
	}
}
