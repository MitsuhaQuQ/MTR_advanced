package cn.zbx1425.mtrsteamloco.block;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.block.BlockNode;
import net.minecraft.world.level.Level;
import mtr.mappings.EntityBlockMapper;
import net.minecraft.world.level.block.RenderShape;
import org.jetbrains.annotations.NotNull;
import mtr.mappings.BlockEntityClientSerializableMapper;
import net.minecraft.nbt.CompoundTag;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.world.level.block.state.BlockState;
import mtr.data.RailAngle;
import net.minecraft.core.BlockPos;
import cn.zbx1425.mtrsteamloco.data.RailAngleExtra;
import mtr.data.Rail;
import mtr.data.TransportMode;
import net.minecraft.server.level.ServerLevel;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateBlockEntity;
import mtr.data.RailwayData;
import cn.zbx1425.mtrsteamloco.mixin.RailwayDataAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import mtr.packet.PacketTrainDataGuiServer;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;

import java.util.HashMap;
import java.util.Map;

public class BlockDirectNode extends BlockNode implements EntityBlockMapper {

    public BlockDirectNode() {
        super(TransportMode.TRAIN);
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState blockState) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
	public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
		return new BlockEntityDirectNode(pos, state);
	}

    public static class BlockEntityDirectNode extends BlockEntityClientSerializableMapper {
        private double angle = -114514F;
        private RailAngle railAngle = null;

        public static final String KEY_ANGLE = "angle";

        public BlockEntityDirectNode(BlockPos pos, BlockState state) {
            super(Main.BLOCK_ENTITY_TYPE_DIRECT_NODE.get(), pos, state);
        }

        public BlockEntityDirectNode(BlockPos pos, BlockState state, double angle) {
            super(Main.BLOCK_ENTITY_TYPE_DIRECT_NODE.get(), pos, state);
            this.angle = angle;
            railAngle = RailAngleExtra.fromDegrees(angle);
        }

        public void bind(BlockEntityDirectNode other) {
            if (railAngle != null) return;
            BlockPos thi = getBlockPos();
            BlockPos oth = other.getBlockPos();
            bind(Math.toDegrees(Math.atan2(oth.getZ() - thi.getZ(), oth.getX() - thi.getX())));
            other.bind(this);
        }

        public void bind(double angle) {
            angle = normalize(angle);
            this.angle =angle;
            railAngle = RailAngleExtra.fromDegrees(angle);
            this.setChanged();
            Level level = getLevel();
            if (level == null) return;

            if (level instanceof ServerLevel sl) sl.getChunkSource().blockChanged(getBlockPos());
            else PacketUpdateBlockEntity.sendUpdateC2S(this);
        }

        public void unbind() {
            if (isConnected()) return;
            railAngle = null;
            angle = -114514D;
            this.setChanged();
            Level level = getLevel();
            if (level == null) return;

            if (level instanceof ServerLevel sl) sl.getChunkSource().blockChanged(getBlockPos());
            else PacketUpdateBlockEntity.sendUpdateC2S(this);
        }

        public boolean isConnected() {
            return getBlockState().getValue(BlockNode.IS_CONNECTED);
        }

        private void updateRailwayData() {
            RailAngle angleFrom = getRailAngle();
            BlockPos from = getBlockPos();
            if (!isBound() || !isConnected() || angleFrom == null || from == null) return;

            Level world = getLevel();
            if (world == null) return;

            RailwayData railwayData = RailwayData.getInstance(world);
            if (railwayData == null) return;
            
            Map<BlockPos, Map<BlockPos, Rail>> rails = ((RailwayDataAccessor) railwayData).getRails();
            if (rails == null) return;

            Map<BlockPos, Rail> map = rails.get(getBlockPos());
            if (map == null) return;
            map = new HashMap<>(map);

            for (BlockPos target : map.keySet()) {
                Rail forward = map.get(target);
                Rail backward = rails.get(target).get(getBlockPos());
                if (forward == null || backward == null) continue;
                RailAngle angleTarget = getRailAngle(target, world);
                if (angleTarget == null) continue;

                Rail railForward = newRail(from, angleFrom, target, angleTarget, forward);
                ((RailExtraSupplier) (Object) railForward).partialCopyFrom(forward);
                ((RailExtraSupplier) (Object) railForward).changePathMode(((RailExtraSupplier) (Object) forward).getPathMode());
                Rail railBackward = newRail(target, angleTarget, from, angleFrom, backward);
                ((RailExtraSupplier) (Object) railBackward).partialCopyFrom(backward);
                ((RailExtraSupplier) (Object) railBackward).changePathMode(((RailExtraSupplier) (Object) backward).getPathMode());

                railwayData.addRail(null, forward.transportMode, from, target, railForward, false);
                railwayData.addRail(null, backward.transportMode, target, from, railBackward, false);
                PacketTrainDataGuiServer.createRailS2C(world, forward.transportMode, from, target, railForward, railBackward, 0);
                ((RailwayDataAccessor) railwayData)._validateData();
            }
        }

        private Rail newRail(BlockPos from, RailAngle angleFrom, BlockPos target, RailAngle angleTarget, Rail origin) {
            Rail result = new Rail(from, angleFrom, target, angleTarget, origin.railType, origin.transportMode);
            ((RailExtraSupplier) result).partialCopyFrom(origin);
            return result;
        }

        private static RailAngle getRailAngle(BlockPos pos, Level world) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            BlockEntity entity = world.getBlockEntity(pos);

            if (!(block instanceof BlockNode)) return null;
            if (entity == null) {
                return RailAngle.fromAngle(BlockNode.getAngle(state));
            }
            if (!(entity instanceof BlockEntityDirectNode)) return RailAngle.fromAngle(BlockNode.getAngle(state));
            return ((BlockEntityDirectNode) entity).getRailAngle();
        }

        public double getAngleDegrees() {
            return angle;
        }

        public RailAngle getRailAngle() {
            return railAngle;
        }

        public boolean isBound() {
            return railAngle != null;
        }
        
        @Override
        public void readCompoundTag(CompoundTag compoundTag) {
            if (compoundTag.contains(KEY_ANGLE)) {
                double angle = compoundTag.getDouble(KEY_ANGLE);
                railAngle = RailAngleExtra.fromDegrees(angle);
                if (angle != this.angle) {
                    this.angle = angle;
                    updateRailwayData();
                }
            } else {
                angle = -114514D;
                railAngle = null;
            }
        }

        @Override
        public void writeCompoundTag(CompoundTag compoundTag) {
            if (!isBound()) return;
            compoundTag.putDouble(KEY_ANGLE, angle);
        }

        public static double normalize(double angle) {
            while (angle < 0F) angle += 180D;
            while (angle >= 180F) angle -= 180D;
            return angle;
        }
    }
}