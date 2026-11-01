package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(RailwayData.class)
public interface RailwayDataAccessor {

    @Accessor(remap = false)
    Map<BlockPos, Map<BlockPos, Rail>> getRails();

    @Invoker(remap = false, value = "validateData")
    void _validateData();
}
