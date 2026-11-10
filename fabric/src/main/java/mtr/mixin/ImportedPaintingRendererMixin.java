package mtr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.render.ImportedPaintingRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.culling.Frustum;
import mtr.image.PaintingImage;
import mtr.image.PaintingSettings;
import net.minecraft.world.entity.decoration.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PaintingRenderer.class)
public abstract class ImportedPaintingRendererMixin extends EntityRenderer<Painting> {
	protected ImportedPaintingRendererMixin(EntityRendererProvider.Context context) { super(context); }
	@Override public boolean shouldRender(Painting painting, Frustum frustum, double x, double y, double z) {
		try { if (!PaintingSettings.parse(((PaintingImage) painting).mtr$getImageSettings()).image.isEmpty()) return painting.shouldRender(x, y, z) && frustum.isVisible(painting.getBoundingBox().inflate(32)); }
		catch (RuntimeException ignored) { }
		return super.shouldRender(painting, frustum, x, y, z);
	}
	@Inject(method = "render(Lnet/minecraft/world/entity/decoration/Painting;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), cancellable = true)
	private void mtr$render(Painting painting, float yaw, float delta, PoseStack matrices, MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (ImportedPaintingRenderer.render(painting, yaw, matrices, buffers)) ci.cancel();
	}
}
