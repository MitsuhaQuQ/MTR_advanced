package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import mtr.block.BlockEdgeScreenDoor;
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
 * Draws a screen door cut along a platform's edge (0.90.0, {@link BlockEdgeScreenDoor}): a wall two blocks high on the
 * edge line where it crosses the block, with the glass or door leaf textures of MTR's screen doors 1 and 2 stretched over
 * that piece of the line, front towards the track and back towards the platform. A leaf slides along the line as it opens
 * and is drawn only within its own block, as if into a pocket behind the glass beside it.
 */
public class RenderEdgeScreenDoor extends BlockEntityRendererMapper<BlockEdgeScreenDoor.TileEntityEdgeScreenDoor> {

	private static final ResourceLocation BLACK = new ResourceLocation("mtr:textures/block/black.png");
	/**
	 * Where the face of a leaf is on MTR's door leaf textures (a box 16 by 16 by 2 laid out on 36 by 18): front and back
	 */
	private static final float LEAF_FRONT_U1 = 2F / 36, LEAF_FRONT_U2 = 18F / 36, LEAF_BACK_U1 = 20F / 36, LEAF_BACK_U2 = 1, LEAF_V1 = 2F / 18, LEAF_V2 = 1;

	public RenderEdgeScreenDoor(BlockEntityRenderDispatcher dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(BlockEdgeScreenDoor.TileEntityEdgeScreenDoor entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		// An error drawing one block (bad data read from a save, say) skips it, never crashing the game (0.100.0)
		RenderGuard.draw("Edge screen door", entity.getBlockPos(), matrices, guarded -> renderBlock(entity, tickDelta, guarded, vertexConsumers, light, overlay));
	}

	private void renderBlock(BlockEdgeScreenDoor.TileEntityEdgeScreenDoor entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
		final Level world = entity.getLevel();
		if (world == null) {
			return;
		}
		final BlockPos pos = entity.getBlockPos();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof BlockEdgeScreenDoor)) {
			return;
		}
		final int angle = IBlock.getStatePropertySafe(state, BlockPlatformEdge.ANGLE);
		final double[] segment = PlatformEdges.wallSegment(angle, IBlock.getStatePropertySafe(state, BlockPlatformEdge.CUT));
		if (segment == null) {
			return;
		}
		final float towardsTrackX = (float) Math.cos(angle * 2 * Math.PI / PlatformEdges.ANGLES);
		final float towardsTrackZ = (float) Math.sin(angle * 2 * Math.PI / PlatformEdges.ANGLES);
		final float thickness = (float) PlatformEdges.WALL_THICKNESS;
		final float x1 = (float) segment[0], z1 = (float) segment[1], x2 = (float) segment[2], z2 = (float) segment[3];
		final float backX = -towardsTrackX * thickness, backZ = -towardsTrackZ * thickness;
		final String style = String.valueOf(entity.getStyle() + 1);
		final int part = entity.getPart();
		final float open = entity.isDoor() ? entity.getOpen(MTRClient.getLastFrameDuration()) : 0;
		final Matrix4f pose = matrices.last().pose();
		final Matrix3f normal = matrices.last().normal();

		// The piece of the segment the glass or leaf covers, from 0 at its start to 1 at its end, and where on the leaf
		final float from, to, leafFrom;
		if (part == BlockEdgeScreenDoor.PART_DOOR_AHEAD) {
			from = open;
			to = 1;
			leafFrom = 0;
		} else if (part == BlockEdgeScreenDoor.PART_DOOR_BACK) {
			from = 0;
			to = 1 - open;
			leafFrom = open;
		} else {
			from = 0;
			to = 1;
			leafFrom = 0;
		}
		if (to - from > 1E-3) {
			final float ax = x1 + (x2 - x1) * from, az = z1 + (z2 - z1) * from;
			final float bx = x1 + (x2 - x1) * to, bz = z1 + (z2 - z1) * to;
			final float leafTo = leafFrom + to - from;
			for (int half = 0; half < 2; half++) {
				final int faceLight = LevelRenderer.getLightColor(world, pos.above(half));
				final String height = half == 0 ? "bottom" : "top";
				final ResourceLocation front;
				final ResourceLocation back;
				final float frontU1, frontU2, backU1, backU2, v1, v2;
				if (part == BlockEdgeScreenDoor.PART_GLASS) {
					front = new ResourceLocation(String.format("mtr:textures/block/psd_glass_%s_%s.png", height, style));
					back = new ResourceLocation(String.format("mtr:textures/block/psd_glass_%s_back_%s.png", height, style));
					// Seen from the front the texture's right is against the line's direction, from the back along it
					frontU1 = 1 - leafFrom;
					frontU2 = 1 - leafTo;
					backU1 = leafFrom;
					backU2 = leafTo;
					v1 = 1;
					v2 = 0;
				} else {
					// A leaf sliding against the line's direction slides to the right seen from the track
					front = new ResourceLocation(String.format("mtr:textures/block/psd_door_%s_%s_%s.png", height, part == BlockEdgeScreenDoor.PART_DOOR_BACK ? "right" : "left", style));
					back = front;
					frontU1 = LEAF_FRONT_U2 - (LEAF_FRONT_U2 - LEAF_FRONT_U1) * leafFrom;
					frontU2 = LEAF_FRONT_U2 - (LEAF_FRONT_U2 - LEAF_FRONT_U1) * leafTo;
					backU1 = LEAF_BACK_U1 + (LEAF_BACK_U2 - LEAF_BACK_U1) * leafFrom;
					backU2 = LEAF_BACK_U1 + (LEAF_BACK_U2 - LEAF_BACK_U1) * leafTo;
					v1 = LEAF_V2;
					v2 = LEAF_V1;
				}
				final float y1 = half, y2 = half + 1;
				final VertexConsumer frontConsumer = vertexConsumers.getBuffer(RenderType.entityTranslucent(front));
				quad(frontConsumer, pose, normal, ax, az, bx, bz, y1, y2, frontU1, frontU2, v1, v2, towardsTrackX, towardsTrackZ, faceLight);
				final VertexConsumer backConsumer = vertexConsumers.getBuffer(RenderType.entityTranslucent(back));
				quad(backConsumer, pose, normal, ax + backX, az + backZ, bx + backX, bz + backZ, y1, y2, backU1, backU2, v1, v2, -towardsTrackX, -towardsTrackZ, faceLight);
			}
		}

		// The frame: the wall's top and its two ends, closed whatever the leaf does
		final VertexConsumer frame = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(BLACK));
		final int topLight = LevelRenderer.getLightColor(world, pos.above(2));
		vertex(frame, pose, normal, x1, 2, z1, 0, 0, 0, 1, 0, topLight);
		vertex(frame, pose, normal, x2, 2, z2, 1, 0, 0, 1, 0, topLight);
		vertex(frame, pose, normal, x2 + backX, 2, z2 + backZ, 1, 1, 0, 1, 0, topLight);
		vertex(frame, pose, normal, x1 + backX, 2, z1 + backZ, 0, 1, 0, 1, 0, topLight);
		final int sideLight = LevelRenderer.getLightColor(world, pos);
		final float alongX = (float) PlatformEdges.alongX(angle), alongZ = (float) PlatformEdges.alongZ(angle);
		quad(frame, pose, normal, x1, z1, x1 + backX, z1 + backZ, 0, 2, 0, 1, 0, 1, -alongX, -alongZ, sideLight);
		quad(frame, pose, normal, x2, z2, x2 + backX, z2 + backZ, 0, 2, 0, 1, 0, 1, alongX, alongZ, sideLight);
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	@Override
	public boolean shouldRenderOffScreen(BlockEdgeScreenDoor.TileEntityEdgeScreenDoor blockEntity) {
		return true;
	}

	private static void quad(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, float ax, float az, float bx, float bz, float y1, float y2, float u1, float u2, float v1, float v2, float normalX, float normalZ, int light) {
		vertex(consumer, pose, normal, ax, y1, az, u1, v1, normalX, 0, normalZ, light);
		vertex(consumer, pose, normal, bx, y1, bz, u2, v1, normalX, 0, normalZ, light);
		vertex(consumer, pose, normal, bx, y2, bz, u2, v2, normalX, 0, normalZ, light);
		vertex(consumer, pose, normal, ax, y2, az, u1, v2, normalX, 0, normalZ, light);
	}

	private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ, int light) {
		consumer.vertex(pose, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, normalX, normalY, normalZ).endVertex();
	}
}
