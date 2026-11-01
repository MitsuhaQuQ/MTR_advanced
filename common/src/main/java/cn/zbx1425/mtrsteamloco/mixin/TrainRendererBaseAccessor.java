package cn.zbx1425.mtrsteamloco.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import mtr.render.TrainRendererBase;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(TrainRendererBase.class)
public interface TrainRendererBaseAccessor {
    @Accessor(remap = false)
    static PoseStack getMatrices() {
        throw new AssertionError();
    };

    @Accessor(remap = false)
    static Camera getCamera() {
        throw new AssertionError();
    };

    @Accessor(remap = false)
    static LocalPlayer getPlayer() {
        throw new AssertionError();
    };

    @Accessor(remap = false)
    static Level getWorld() {
        throw new AssertionError();
    };

    @Accessor(remap = false)
    static MultiBufferSource getVertexConsumers() {
        throw new AssertionError();
    }

    @Accessor(remap = false)
    static EntityRenderDispatcher getEntityRenderDispatcher() {
        throw new AssertionError();
    }

    @Invoker(remap = false)
    static BlockPos invokeApplyAverageTransform(Vec3 viewOffset, double x, double y, double z) {
        throw new AssertionError();
    }
}