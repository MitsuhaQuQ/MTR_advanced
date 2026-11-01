package cn.zbx1425.mtrsteamloco.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode;
import cn.zbx1425.mtrsteamloco.mixin.RailwayDataAccessor;
import cn.zbx1425.mtrsteamloco.network.PacketScreen;
import mtr.CreativeModeTabs;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.item.ItemWithCreativeTabBase;
import mtr.mappings.Text;

import java.util.*;

public class RailPathEditor extends ItemWithCreativeTabBase {

    public RailPathEditor() {
        super(
            CreativeModeTabs.CORE, p -> p.stacksTo(1)
        );
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack itemStack = player.getItemInHand(usedHand);
        if (level.isClientSide) return InteractionResultHolder.success(itemStack);

        CompoundTag tag = itemStack.getOrCreateTag();
        if (tag.contains("start") && tag.contains("end")) {
            BlockPos posStart = BlockPos.of(tag.getLong("start"));
            BlockPos posEnd = BlockPos.of(tag.getLong("end"));
            RailwayData railwayData = RailwayData.getInstance(level);
            boolean success = false;
            if (railwayData != null) { 
                Map<BlockPos, Rail> map = ((RailwayDataAccessor) (Object) railwayData).getRails().get(posStart);
                if (map != null) {
                    Rail rail = map.get(posEnd);
                    if (rail != null && player instanceof ServerPlayer sp) {
                        PacketScreen.sendScreenRailPathEditorS2C(sp, rail, posStart, posEnd);
                        success = true;
                    }
                }
            }
            if (!success) {
                player.displayClientMessage(Text.translatable("tooltip.mtrsteamloco.rail_path_editor.data_not_found"), true);
            }
        } else {
            player.displayClientMessage(Text.translatable("tooltip.mtrsteamloco.rail_path_editor.no_data"), true);
        }
        return InteractionResultHolder.success(itemStack);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        boolean success = updateRail(ctx);

        return InteractionResult.SUCCESS;
    }

    // 在服务器端
    private boolean updateRail(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return true;
        final RailwayData railwayData = RailwayData.getInstance(ctx.getLevel());
        if (railwayData == null) return false;
        BlockPos rPosStart = ctx.getClickedPos();
        Map<BlockPos, Rail> map = ((RailwayDataAccessor) (Object) railwayData).getRails().get(rPosStart);
        if (map == null) return false;
        
        Player player = ctx.getPlayer();
        if (player == null) return false;
        Optional<Map.Entry<BlockPos, Rail>> closestEntry = map.entrySet().stream().min(Comparator.comparingDouble(entry ->
                Mth.degreesDifferenceAbs((float) -Math.toDegrees(Math.atan2(entry.getKey().getX() - rPosStart.getX(), entry.getKey().getZ() - rPosStart.getZ())), player.getYRot())
        ));
        if (closestEntry.isEmpty()) return false;
        BlockPos posEnd = closestEntry.get().getKey();
        Rail rail = closestEntry.get().getValue();
        BlockPos posStart = rPosStart;
        if (rail.railType == RailType.NONE) {
            posStart = posEnd;
            posEnd = rPosStart;
        }
        ItemStack itemStack = ctx.getItemInHand();
        CompoundTag tag = itemStack.getOrCreateTag();
        tag.putLong("start", posStart.asLong());
        tag.putLong("end", posEnd.asLong());
        if (ctx.getPlayer() != null) {
            if (ctx.getPlayer() instanceof ServerPlayer sp) {
                sp.setItemSlot(EquipmentSlot.MAINHAND, itemStack);

                sp.displayClientMessage(Text.translatable("tooltip.mtrsteamloco.rail_path_editor.success_update", posStart.getX(), posStart.getY(), posStart.getZ(), posEnd.getX(), posEnd.getY(), posEnd.getZ()), true);
                return true;
            }
        } 
        return false;
    }
}