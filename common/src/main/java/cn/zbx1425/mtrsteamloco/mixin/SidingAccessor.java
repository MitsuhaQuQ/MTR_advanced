package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.Siding;
import mtr.data.TrainServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(Siding.class)
public interface SidingAccessor {
    @Accessor(value = "trains", remap = false)
    Set<TrainServer> getTrains();
}