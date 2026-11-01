package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.world.level.Level;
import mtr.data.Rail;
import net.minecraft.core.BlockPos;
import mtr.data.RailwayDataRailActionsModule;

import java.util.List;
import java.util.Map;

public interface RailActionsModuleExtraSupplier {
    List<Rail.RailActions> getRailActions();
    Map<BlockPos, Map<BlockPos, Rail>> getRails();
    Level getWorld();
    void sendUpdateS2C();
}