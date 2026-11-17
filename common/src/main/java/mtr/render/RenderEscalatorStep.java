package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTR;
import mtr.block.BlockEscalatorBase;
import mtr.block.BlockEscalatorStep;
import mtr.block.IBlock;
import mtr.client.IDrawing;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the moving steps of the original escalator (0.100.0, user 2026-11-17: 原版台阶换成会动的立体台阶), called for each
 * step block near the camera by {@link EscalatorStepsClient} (no block entity, so nothing is added to saves). Each step
 * block draws the parts of the steps that lie over it: treads and the risers between them, half a block deep, sliding along
 * the run at the speed the old animated texture had (one step every 16 ticks), up, down or standing still as the
 * block's state says.
 * <p>
 * Along the run, u goes from the block's back (0) to its front (1, the way it faces, uphill) and v is the height above
 * the block's bottom. The steps' front edges ride on a line g(u): flat blocks have it at the top (1), slopes at u, a
 * bottom transition flat then rising past its front, a top transition rising then flat past its front; so a step that
 * crosses into the next block has the same height on both. A step whose front edge is at u has its tread at g(u) from
 * u - 0.5 to u, and a riser at its back down to the step behind.
 */
public final class RenderEscalatorStep {

	/** Step depth along the run, in blocks */
	public static final float PITCH = 0.5F;
	/** Ticks a step takes to move one step depth, as the old animated texture */
	public static final float TICKS_PER_STEP = 16;
	/** The width the steps take on each half of the escalator, from the middle out */
	public static final float WIDTH = 11 / 16F;

	private static final ResourceLocation TEXTURE = new ResourceLocation(MTR.MOD_ID, "textures/block/escalator_stop.png");
	// Regions of the 320 x 320 texture: a grooved tread with its yellow edge and the riser under it
	private static final float TEX_U1 = 64 / 320F, TEX_U2 = 128 / 320F;
	private static final float TREAD_V1 = 0, TREAD_V2 = 32 / 320F;
	private static final float RISER_V1 = 32 / 320F, RISER_V2 = 64 / 320F;

	private RenderEscalatorStep() {
	}

	/**
	 * Draws the steps over one step block, the matrices at the block's corner. Nothing for a landing or a block that is
	 * not a step (any more).
	 */
	public static void render(Level world, BlockPos pos, BlockState state, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers) {
		if (!(state.getBlock() instanceof BlockEscalatorStep)) {
			return;
		}
		final BlockEscalatorBase.EnumEscalatorOrientation orientation = IBlock.getStatePropertySafe(state, BlockEscalatorBase.ORIENTATION);
		if (orientation == BlockEscalatorBase.EnumEscalatorOrientation.LANDING_BOTTOM || orientation == BlockEscalatorBase.EnumEscalatorOrientation.LANDING_TOP) {
			return;
		}
		final Direction facing = IBlock.getStatePropertySafe(state, BlockEscalatorBase.FACING);
		final Direction partner = IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.RIGHT ? facing.getCounterClockWise() : facing.getClockWise();
		final boolean running = IBlock.getStatePropertySafe(state, BlockEscalatorStep.STATUS);
		final boolean up = IBlock.getStatePropertySafe(state, BlockEscalatorStep.DIRECTION);

		final int lightHere = LevelRenderer.getLightColor(world, pos);
		final int lightAbove = LevelRenderer.getLightColor(world, pos.above());
		final int stepLight = Math.max(lightAbove & 0xFFFF, lightHere & 0xFFFF) | Math.max(lightAbove >>> 16, lightHere >>> 16) << 16;
		final VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

		draw(matrices, vertexConsumer, orientation, facing, partner, running ? phase(world.getGameTime(), tickDelta, up) : 0, stepLight);
	}

	/** Where the steps are, 0 to 1 of a step depth, moving forward (up) or back with time */
	public static float phase(long gameTime, float tickDelta, boolean up) {
		final float steps = ((gameTime % (long) (TICKS_PER_STEP * 1000)) + tickDelta) / TICKS_PER_STEP;
		final float phase = steps - (float) Math.floor(steps);
		return up ? phase : (1 - phase) % 1;
	}

	/** The height of the steps' front edges at u along the run, for a block of this orientation */
	public static float frontLine(BlockEscalatorBase.EnumEscalatorOrientation orientation, float u) {
		switch (orientation) {
			case SLOPE:
				return u;
			case TRANSITION_BOTTOM:
				return Math.max(1, u);
			case TRANSITION_TOP:
				return Math.min(u, 1);
			default:
				return 1;
		}
	}

	private static void draw(PoseStack matrices, VertexConsumer vertexConsumer, BlockEscalatorBase.EnumEscalatorOrientation orientation, Direction facing, Direction partner, float phase, int light) {
		final Direction back = facing.getOpposite();
		for (int k = -1; k <= 3; k++) {
			final float front = (k + phase) * PITCH;
			final float rear = front - PITCH;
			final float height = frontLine(orientation, front);
			// The tread, where it lies over this block
			final float from = Math.max(rear, 0);
			final float to = Math.min(front, 1);
			if (to > from) {
				final float v1 = TREAD_V1 + (from - rear) / PITCH * (TREAD_V2 - TREAD_V1);
				final float v2 = TREAD_V1 + (to - rear) / PITCH * (TREAD_V2 - TREAD_V1);
				quad(matrices, vertexConsumer, facing, partner,
						to, height, WIDTH, to, height, 0, from, height, 0, from, height, WIDTH,
						v1, v2, Direction.UP, light);
			}
			// The riser at its back, down to the step behind
			// (one at the block's front edge is the next block's)
			if (rear >= 0 && rear < 1) {
				final float below = frontLine(orientation, rear);
				if (height - below > 0.001F) {
					final float v1 = RISER_V1;
					final float v2 = RISER_V1 + Math.min(1, (height - below) / PITCH) * (RISER_V2 - RISER_V1);
					quad(matrices, vertexConsumer, facing, partner,
							rear, below, WIDTH, rear, below, 0, rear, height, 0, rear, height, WIDTH,
							v1, v2, back, light);
				}
			}
		}
	}

	/** A quad from points given as (u along the run, v up, w from the middle out), the texture's yellow edge at the outer side */
	private static void quad(PoseStack matrices, VertexConsumer vertexConsumer, Direction facing, Direction partner, float u1, float v1, float w1, float u2, float v2, float w2, float u3, float v3, float w3, float u4, float v4, float w4, float texV1, float texV2, Direction normal, int light) {
		IDrawing.drawTexture(matrices, vertexConsumer,
				x(facing, partner, u1, w1), v1, z(facing, partner, u1, w1),
				x(facing, partner, u2, w2), v2, z(facing, partner, u2, w2),
				x(facing, partner, u3, w3), v3, z(facing, partner, u3, w3),
				x(facing, partner, u4, w4), v4, z(facing, partner, u4, w4),
				TEX_U1, texV1, TEX_U2, texV2, normal, -1, light);
	}

	private static float x(Direction facing, Direction partner, float u, float w) {
		return 0.5F + (u - 0.5F) * facing.getStepX() + (0.5F - w) * partner.getStepX();
	}

	private static float z(Direction facing, Direction partner, float u, float w) {
		return 0.5F + (u - 0.5F) * facing.getStepZ() + (0.5F - w) * partner.getStepZ();
	}
}
