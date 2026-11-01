package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.render.RenderUtil;
import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import mtr.render.JonModelTrainRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.sowcer.math.PoseStackUtil;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = JonModelTrainRenderer.class, remap = false)
public abstract class JonModelTrainRendererMixin extends TrainRendererBase{

    @Shadow @Final private TrainClient train;

    @Inject(method = "renderCar", at = @At("HEAD"), remap = false, cancellable = true)
    public void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }

    @Inject(method = "renderCar", at = @At(value = "INVOKE", target = "Lmtr/mappings/UtilitiesClient;rotateX(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", ordinal = 0, shift = At.Shift.AFTER), remap = true)
    private void injectRenderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen, CallbackInfo ci) {
        float roll = TrainExtraSupplier.getRollAngleAt(train, carIndex);
        boolean isReversed = train.isReversed();
        matrices.translate(0D, 1D, 0D);
        PoseStackUtil.rotZ(matrices, isReversed ? roll : -roll);
        matrices.translate(0D, -1D, 0D);
    }

    @Inject(method = "renderConnection", at = @At("HEAD"), cancellable = true)
    public void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }

    @Inject(method = "renderBarrier", at = @At("HEAD"), cancellable = true)
    public void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }
}
