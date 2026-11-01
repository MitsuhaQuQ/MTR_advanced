package cn.zbx1425.mtrsteamloco.network;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.mixin.RailwayDataAccessor;
import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.RegistryClient;
import mtr.data.Rail;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.data.RailwayData;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import mtr.data.RailType;

import java.util.Map;

public class PacketUpdateRail {

    public static ResourceLocation PACKET_UPDATE_RAIL = new ResourceLocation(Main.MOD_ID, "update_rail");

    public static void sendUpdateC2S(Rail newState, BlockPos posStart, BlockPos posEnd) {
        final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeResourceLocation(Minecraft.getInstance().level.dimension().location());
        packet.writeBlockPos(posStart);
        packet.writeBlockPos(posEnd);
        newState.writePacket(packet);

        RegistryClient.sendToServer(PACKET_UPDATE_RAIL, packet);
    }

    public static void receiveUpdateC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
#if MC_VERSION >= "11903"
        ResourceKey<Level> levelKey = packet.readResourceKey(net.minecraft.core.registries.Registries.DIMENSION);
#else
        ResourceKey<Level> levelKey = ResourceKey.create(net.minecraft.core.Registry.DIMENSION_REGISTRY, packet.readResourceLocation());
#endif
        BlockPos posStart = packet.readBlockPos();
        BlockPos posEnd = packet.readBlockPos();
        Rail forward = new Rail(packet);
        RailExtraSupplier extraTarget = (RailExtraSupplier)(forward);
        server.execute(() -> {
            ServerLevel level = server.getLevel(levelKey);
            if (level == null) return;

            RailwayData railwayData = RailwayData.getInstance(level);
            Map<BlockPos, Map<BlockPos, Rail>> rails = ((RailwayDataAccessor) railwayData).getRails();
            Rail railForward = rails.get(posStart).get(posEnd);
            Rail railBackward = rails.get(posEnd).get(posStart);
            if (railForward == null || railBackward == null) return;

            Rail backward = ((RailExtraSupplier) (Object) forward).getTransposition(railBackward.railType);

            railwayData.addRail(null, forward.transportMode, posStart, posEnd, forward, false);
            railwayData.addRail(null, backward.transportMode, posEnd, posStart, backward, false);
            PacketTrainDataGuiServer.createRailS2C(level, forward.transportMode, posStart, posEnd, forward, backward, 0);
            ((RailwayDataAccessor) railwayData)._validateData();
        });
    }
}
