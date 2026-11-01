package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.RailwayDataRailActionsModule;
import net.minecraft.world.level.Level;
import mtr.data.RailwayDataModuleBase;
import net.minecraft.core.BlockPos;
import mtr.data.Rail;
import mtr.data.RailwayData;
import cn.zbx1425.mtrsteamloco.data.RailActionsModuleExtraSupplier;
import mtr.packet.PacketTrainDataGuiServer;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RailwayDataRailActionsModule.class)
public class RailwayDataRailActionsModuleMixin extends RailwayDataModuleBase implements RailActionsModuleExtraSupplier {

    public RailwayDataRailActionsModuleMixin(RailwayData railwayData, Level world, Map<BlockPos, Map<BlockPos, Rail>> rails) {
        super(railwayData, world, rails);
    }

    @Shadow(remap = false)
    private List<Rail.RailActions> railActions = new ArrayList<>();

    @Override
    public List<Rail.RailActions> getRailActions() {
        return railActions;
    }

    @Override
    public Map<BlockPos, Map<BlockPos, Rail>> getRails() {
        return rails;
    }

    @Override
    public Level getWorld() {
        return world;
    }

    @Override
    public void sendUpdateS2C() {
        PacketTrainDataGuiServer.updateRailActionsS2C(world, railActions);
    }
}