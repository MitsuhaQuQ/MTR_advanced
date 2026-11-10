package cn.zbx1425.mtrsteamloco.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.render.WorldRenderVisibility;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
#if MC_VERSION >= "12000"
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
#endif
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

	@Shadow public Level level;

	@Shadow public abstract <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E entity);

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void cullVanillaDecoration(BlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource buffers, CallbackInfo ci) {
		if (!WorldRenderVisibility.hasFrustum() || entity.getLevel() != level) {
			return;
		}

		final Class<?> vanillaRenderer;
		final BlockEntityType<?> type = entity.getType();
		if (type == BlockEntityType.BANNER) {
			vanillaRenderer = BannerRenderer.class;
		} else if (type == BlockEntityType.SIGN) {
			vanillaRenderer = SignRenderer.class;
#if MC_VERSION >= "12000"
		} else if (type == BlockEntityType.HANGING_SIGN) {
			vanillaRenderer = HangingSignRenderer.class;
#endif
		} else if (type == BlockEntityType.SKULL) {
			vanillaRenderer = SkullBlockRenderer.class;
		} else {
			return;
		}

		final BlockPos pos = entity.getBlockPos();
		// Include geometry overhanging the block, such as wall banners and animated dragon heads.
		if (!WorldRenderVisibility.isVisible(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.5)) {
			final BlockEntityRenderer<?> renderer = getRenderer(entity);
			if (renderer != null && renderer.getClass() == vanillaRenderer) {
				ci.cancel();
			}
		}
	}
}
