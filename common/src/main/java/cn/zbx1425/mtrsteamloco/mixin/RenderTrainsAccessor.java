package cn.zbx1425.mtrsteamloco.mixin;

import mtr.render.RenderTrains;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderTrains.class)
public interface RenderTrainsAccessor {
    @Accessor(remap = false)
    static float getLastRenderedTick() {
        throw new AssertionError();
    };
}