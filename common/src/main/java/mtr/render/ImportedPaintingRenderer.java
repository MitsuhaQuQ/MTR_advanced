package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.ImportedImages;
import mtr.compat.Compat;
import mtr.image.PaintingImage;
import mtr.image.PaintingSettings;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.Painting;

/** Vanilla-style tiled painting geometry, wall direction, backing and per-block lighting. */
public final class ImportedPaintingRenderer {
	private ImportedPaintingRenderer() { }
	public static boolean render(Painting painting, float yaw, PoseStack pose, MultiBufferSource buffers) {
		final PaintingSettings settings;
		try { settings = PaintingSettings.parse(((PaintingImage) painting).mtr$getImageSettings()); } catch (RuntimeException e) { return false; }
		if (settings.image.isEmpty()) return false;
		final ResourceLocation texture = ImportedImages.texture(settings.image); if (texture == null) return false;
		pose.pushPose(); UtilitiesClient.rotateYDegrees(pose, 180 - yaw); pose.translate(-settings.x, settings.y, -settings.z);
		final VertexConsumer front = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
		final TextureAtlasSprite back = Minecraft.getInstance().getPaintingTextures().getBackSprite();
		final VertexConsumer frame = buffers.getBuffer(RenderType.entitySolid(back.atlasLocation()));
		final int columns = (int) Math.ceil(settings.width), rows = (int) Math.ceil(settings.height);
		final float depth = 1F / 32;
		final double angle = Math.toRadians(180 - yaw);
		for (int col = 0; col < columns; col++) for (int row = 0; row < rows; row++) {
			final float x0 = (float) (-settings.width / 2 + col * settings.width / columns), x1 = (float) (-settings.width / 2 + (col + 1) * settings.width / columns);
			final float y0 = (float) (-settings.height / 2 + row * settings.height / rows), y1 = (float) (-settings.height / 2 + (row + 1) * settings.height / rows);
			final double localX = (x0 + x1) / 2 - settings.x, localZ = -settings.z;
			final int light = LevelRenderer.getLightColor(Compat.getLevel(painting), BlockPos.containing(painting.getX() + localX * Math.cos(angle) + localZ * Math.sin(angle), painting.getY() + settings.y + (y0 + y1) / 2, painting.getZ() - localX * Math.sin(angle) + localZ * Math.cos(angle)));
			final float u0 = (float) (0.5 - x1 / settings.width), u1 = (float) (0.5 - x0 / settings.width), v0 = (float) (0.5 - y1 / settings.height), v1 = (float) (0.5 - y0 / settings.height);
			quad(pose, front, new float[]{x1,y0,-depth, x0,y0,-depth, x0,y1,-depth, x1,y1,-depth}, u0,u1,v0,v1, 0,0,-1, light);
			quad(pose, frame, new float[]{x0,y0,depth, x1,y0,depth, x1,y1,depth, x0,y1,depth}, back.getU0(),back.getU1(),back.getV0(),back.getV1(), 0,0,1, light);
			if (row == rows - 1) quad(pose, frame, new float[]{x0,y1,-depth, x0,y1,depth, x1,y1,depth, x1,y1,-depth}, back.getU0(),back.getU1(),back.getV0(),back.getV1(), 0,1,0, light);
			if (row == 0) quad(pose, frame, new float[]{x1,y0,-depth, x1,y0,depth, x0,y0,depth, x0,y0,-depth}, back.getU0(),back.getU1(),back.getV0(),back.getV1(), 0,-1,0, light);
			if (col == 0) quad(pose, frame, new float[]{x0,y0,-depth, x0,y0,depth, x0,y1,depth, x0,y1,-depth}, back.getU0(),back.getU1(),back.getV0(),back.getV1(), -1,0,0, light);
			if (col == columns - 1) quad(pose, frame, new float[]{x1,y1,-depth, x1,y1,depth, x1,y0,depth, x1,y0,-depth}, back.getU0(),back.getU1(),back.getV0(),back.getV1(), 1,0,0, light);
		}
		// A later painting may evict this texture from the bounded cache; submit its vertices before that happens.
		if (buffers instanceof MultiBufferSource.BufferSource) {
			((MultiBufferSource.BufferSource) buffers).endBatch(RenderType.entityCutoutNoCull(texture));
			((MultiBufferSource.BufferSource) buffers).endBatch(RenderType.entitySolid(back.atlasLocation()));
		}
		pose.popPose(); return true;
	}
	private static void quad(PoseStack matrices, VertexConsumer buffer, float[] positions, float u0, float u1, float v0, float v1, float nx, float ny, float nz, int light) {
		for (int i = 0; i < 4; i++) buffer.vertex(matrices.last().pose(), positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).color(255,255,255,255).uv(i == 0 || i == 3 ? u0 : u1, i < 2 ? v1 : v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrices.last().normal(), nx,ny,nz).endVertex();
	}
}
