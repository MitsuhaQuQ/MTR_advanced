package cn.zbx1425.mtrsteamloco.mixin;

import mtr.item.ItemNodeModifierBase;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode.BlockEntityDirectNode;
import mtr.CreativeModeTabs;
import mtr.block.BlockNode;
import mtr.data.RailAngle;
import mtr.data.RailwayData;
import mtr.data.TransportMode;
import mtr.mappings.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import cn.zbx1425.mtrsteamloco.data.RailCalculator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.RailAngleExtra;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemNodeModifierBase.class)
public abstract class ItemNodeModifierBaseMixin {
    private static final String TAG_TRANSPORT_MODE = "transport_mode";
    @Shadow(remap = false) private boolean isConnector;

    @Shadow abstract void onConnect(Level world, ItemStack stack, TransportMode transportMode, BlockState stateStart, BlockState stateEnd, BlockPos posStart, BlockPos posEnd, RailAngle railAngleStart, RailAngle railAngleEnd, Player player, RailwayData railwayData);

    @Shadow abstract void onRemove(Level world, BlockPos posStart, BlockPos posEnd, Player player, RailwayData railwayData);

    @Inject(method = "onEndClick", at = @At("HEAD"), cancellable = true)
    private void onEndClick(UseOnContext context, BlockPos posEnd, CompoundTag compoundTag, CallbackInfo info) {
		final Level world = context.getLevel();
		final RailwayData railwayData = RailwayData.getInstance(world);
		final BlockPos posStart = context.getClickedPos();
		final BlockState stateStart = world.getBlockState(posStart);
		final Block blockStart = stateStart.getBlock();
		final BlockState stateEnd = world.getBlockState(posEnd);

		if (railwayData != null && stateEnd.getBlock() instanceof BlockNode && ((BlockNode) blockStart).transportMode.toString().equals(compoundTag.getString(TAG_TRANSPORT_MODE))) {
			final Player player = context.getPlayer();

			if (isConnector) {
				if (!posStart.equals(posEnd)) {
                    
                    final float angle1 = BlockNode.getAngle(stateStart);
					final float angle2 = BlockNode.getAngle(stateEnd);

					final float angleDifference = (float) Math.toDegrees(Math.atan2(posEnd.getZ() - posStart.getZ(), posEnd.getX() - posStart.getX()));
					RailAngle railAngleStart = RailAngle.fromAngle(angle1);//  + (RailAngle.similarFacing(angleDifference, angle1) ? 0 : 180));
					RailAngle railAngleEnd = RailAngle.fromAngle(angle2);// + (RailAngle.similarFacing(angleDifference, angle2) ? 180 : 0));

                    BlockEntityDirectNode beStart = getBlockEntity(world, posStart);
                    BlockEntityDirectNode beEnd = getBlockEntity(world, posEnd);
                    if (beStart != null && beEnd != null) {
                        if (!beStart.isBound() && !beEnd.isBound()) {
                            beStart.bind(beEnd);
                            if (player != null) player.displayClientMessage(Text.translatable("gui.mtrsteamloco.direct_node.success_bind"), true);
                        }
                    }
                    boolean s1 = false, s2 = false;
                    if (beStart == null) s1 = true;
                    else {
                        RailAngle f = beStart.getRailAngle();
                        if (f == null) s1 = false;
                        else {
                            railAngleStart = f;
                            s1 = true;
                        }
                    }

                    if (beEnd == null) s2 = true;
                    else {
                        RailAngle f = beEnd.getRailAngle();
                        if (f == null) s2 = false;
                        else {
                            railAngleEnd = f;
                            s2 = true;
                        }
                    }

                    if (s1 && !s2) {
                        Double deg = RailCalculator.calculateMaxRadiusAngle(posStart.getX(), posStart.getZ(), posEnd.getX(), posEnd.getZ(), railAngleStart.angleRadians);
                        if (deg == null) {
                            player.displayClientMessage(Text.translatable("gui.mtr.invalid_orientation"), true);
                            return;
                        } else {
                            beEnd.bind(deg);
                            railAngleEnd = RailAngleExtra.fromDegrees(deg);
                            s2 = true;
                            if (player != null) player.displayClientMessage(Text.translatable("gui.mtrsteamloco.direct_node.success_bind"), true);
                        }
                    } else if (s2 && !s1) {
                        Double deg = RailCalculator.calculateMaxRadiusAngle(posEnd.getX(), posEnd.getZ(), posStart.getX(), posStart.getZ(), railAngleEnd.angleRadians);
                        if (deg == null) {
                            player.displayClientMessage(Text.translatable("gui.mtr.invalid_orientation"), true);
                            return;
                        } else {
                            beStart.bind(deg);
                            railAngleStart = RailAngleExtra.fromDegrees(deg);
                            s1 = true;
                            if (player != null) player.displayClientMessage(Text.translatable("gui.mtrsteamloco.direct_node.success_bind"), true);
                        }
                    }

                    if (s1 && s2) {
                        if (!RailAngle.similarFacing(angleDifference, railAngleStart.angleDegrees)) railAngleStart = railAngleStart.getOpposite();
                        if (RailAngle.similarFacing(angleDifference, railAngleEnd.angleDegrees)) railAngleEnd = railAngleEnd.getOpposite();
                        
                        onConnect(world, context.getItemInHand(), ((BlockNode) blockStart).transportMode, stateStart, stateEnd, posStart, posEnd, railAngleStart, railAngleEnd, player, railwayData);
                    } else {
                        if (player != null) player.displayClientMessage(Text.translatable("gui.mtrsteamloco.direct_node.unbound"), true);
                    }
				}
			} else {
				onRemove(world, posStart, posEnd, player, railwayData);
			}
		}

		compoundTag.remove(TAG_TRANSPORT_MODE);
        info.cancel();
        return;
	}

    private BlockEntityDirectNode getBlockEntity(Level world, BlockPos pos) {
        BlockEntity entity = world.getBlockEntity(pos);
        if (entity instanceof BlockEntityDirectNode e) {
            return e;
        }
        return null;
    }
}