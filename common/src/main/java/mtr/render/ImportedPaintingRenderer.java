package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.ImportedImages;
import mtr.client.IDrawing;
import mtr.Items;
import mtr.mappings.Text;
import mtr.compat.Compat;
import mtr.image.PaintingImage;
import mtr.image.PaintingSettings;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import mtr.image.PaintingTransform;
import mtr.screen.ImportedPaintingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.Painting;

/** Double-sided image plane with local rotation and per-tile transformed lighting. */
public final class ImportedPaintingRenderer {
	private ImportedPaintingRenderer() { }
	public static boolean render(Painting painting, float yaw, PoseStack pose, MultiBufferSource buffers) {
		final PaintingSettings settings;
		try { final PaintingSettings preview = ImportedPaintingScreen.previewFor(painting); settings = preview == null ? PaintingSettings.parse(((PaintingImage) painting).mtr$getImageSettings()) : preview; } catch (RuntimeException e) { return false; }
		if (settings.image.isEmpty()) return false;
		final ResourceLocation texture = ImportedImages.texture(settings.image);
		if (texture == null) { drawEditAnchor(painting, yaw, settings, pose, buffers); return true; }
		pose.pushPose(); UtilitiesClient.rotateYDegrees(pose, 180 - yaw); pose.translate(-settings.x, settings.y, -settings.z - 1D / 32);
		UtilitiesClient.rotateZDegrees(pose, (float) settings.rotationZ);
		UtilitiesClient.rotateYDegrees(pose, (float) settings.rotationY);
		UtilitiesClient.rotateXDegrees(pose, (float) settings.rotationX);
		// Glowing only lights it fully: the emissive render type writes no depth and culls no faces, so the mirrored back and what is behind showed through (shader packs, 0.70.1)
		final RenderType renderType = RenderType.entityTranslucentCull(texture);
		final VertexConsumer front = buffers.getBuffer(renderType);
		final int columns = (int) Math.ceil(settings.width), rows = (int) Math.ceil(settings.height);
		final double angle = Math.toRadians(180 - yaw);
		for (int col = 0; col < columns; col++) for (int row = 0; row < rows; row++) {
			final float x0 = (float) (-settings.width / 2 + col * settings.width / columns), x1 = (float) (-settings.width / 2 + (col + 1) * settings.width / columns);
			final float y0 = (float) (-settings.height / 2 + row * settings.height / rows), y1 = (float) (-settings.height / 2 + (row + 1) * settings.height / rows);
			final double[] rotated = PaintingTransform.rotate((x0 + x1) / 2, (y0 + y1) / 2, 0, settings.rotationX, settings.rotationY, settings.rotationZ);
			final double localX = rotated[0] - settings.x, localZ = rotated[2] - settings.z - 1D / 32;
			final int light = settings.emissive ? net.minecraft.client.renderer.LightTexture.FULL_BRIGHT : LevelRenderer.getLightColor(Compat.getLevel(painting), BlockPos.containing(painting.getX() + localX * Math.cos(angle) + localZ * Math.sin(angle), painting.getY() + settings.y + rotated[1], painting.getZ() - localX * Math.sin(angle) + localZ * Math.cos(angle)));
			final float u0 = (float) (0.5 - x1 / settings.width), u1 = (float) (0.5 - x0 / settings.width), v0 = (float) (0.5 - y1 / settings.height), v1 = (float) (0.5 - y0 / settings.height);
			quad(pose, front, new float[]{x1,y0,0, x0,y0,0, x0,y1,0, x1,y1,0}, u0,u1,v0,v1, 0,0,-1, light);
			// Reverse winding and mirrored U make the image readable from the back as well.
			quad(pose, front, new float[]{x0,y0,0, x1,y0,0, x1,y1,0, x0,y1,0}, 1-u1,1-u0,v0,v1, 0,0,1, light);
		}
		// A later painting may evict this texture from the bounded cache; submit its vertices before that happens.
		if (buffers instanceof MultiBufferSource.BufferSource) {
			((MultiBufferSource.BufferSource) buffers).endBatch(renderType);
		}
		pose.popPose();
		drawEditAnchor(painting, yaw, settings, pose, buffers);
		return true;
	}
	private static void drawEditAnchor(Painting painting, float yaw, PaintingSettings settings, PoseStack pose, MultiBufferSource buffers) {
		final Minecraft client = Minecraft.getInstance();
		if (client.player == null || !client.player.isHolding(Items.BRUSH.get())) return;
		final float w = painting.getVariant().value().getWidth() / 32F, h = painting.getVariant().value().getHeight() / 32F;
		pose.pushPose();
		UtilitiesClient.rotateYDegrees(pose, 180 - yaw);
		// This is the unchanged vanilla hit target, deliberately independent of image transforms.
		LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), -w, -h, -0.045, w, h, 0.045, 0.2F, 0.9F, 1F, 1F);
		for (float z : new float[]{-0.046F, 0.046F}) {
			IDrawing.drawLine(pose,buffers,-0.14F,0,z,0.14F,0,z,255,240,64);
			IDrawing.drawLine(pose,buffers,0,-0.14F,z,0,0.14F,z,255,240,64);
		}
		if (settings.x != 0 || settings.y != 0 || settings.z != 0) IDrawing.drawLine(pose,buffers,0,0,-0.046F,(float)-settings.x,(float)settings.y,(float)(-settings.z-1D/32),64,220,255);
		pose.popPose();
		pose.pushPose();
		pose.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
		pose.scale(-0.035F,-0.035F,0.035F);
		client.font.drawInBatch("+",-client.font.width("+")/2F,-4,0xFFFFFF40,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,0,0xF000F0);
		pose.popPose();
		pose.pushPose();
		pose.translate(0,h+0.16,0);
		pose.mulPose(client.getEntityRenderDispatcher().cameraOrientation());
		pose.scale(-0.025F,-0.025F,0.025F);
		final net.minecraft.network.chat.Component label = Text.translatable("gui.mtr.image_edit_anchor");
		client.font.drawInBatch(label,-client.font.width(label)/2F,0,0xFF66EEFF,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,0x40000000,0xF000F0);
		pose.popPose();
	}
	private static void quad(PoseStack matrices, VertexConsumer buffer, float[] positions, float u0, float u1, float v0, float v1, float nx, float ny, float nz, int light) {
		for (int i = 0; i < 4; i++) buffer.vertex(matrices.last().pose(), positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).color(255,255,255,255).uv(i == 0 || i == 3 ? u0 : u1, i < 2 ? v1 : v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrices.last().normal(), nx,ny,nz).endVertex();
	}
}
