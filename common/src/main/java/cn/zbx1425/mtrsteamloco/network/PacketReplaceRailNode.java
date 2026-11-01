package cn.zbx1425.mtrsteamloco.network;

import mtr.data.TransportMode;
import mtr.block.BlockNode;
import net.minecraft.resources.ResourceLocation;
import mtr.RegistryClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import cn.zbx1425.mtrsteamloco.Main;
import io.netty.buffer.Unpooled;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode;
import net.minecraft.world.level.Level;
import cn.zbx1425.mtrsteamloco.block.BlockDirectNode.BlockEntityDirectNode;
import net.minecraft.world.level.block.Block;


public class PacketReplaceRailNode {
    public static ResourceLocation C2S = new ResourceLocation(Main.MOD_ID, "replace_rail_node");

    public static void sendUpdateC2S(Level level, BlockPos pos, BlockState state, String screenName) {
        Block block = state.getBlock();
        if (!(block instanceof BlockNode)) return;

        BlockNode blockNode = (BlockNode) block;
        if (blockNode.transportMode != TransportMode.TRAIN) return;

        float dir = BlockNode.getAngle(state);
        
        if (level == null) return;

        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeResourceLocation(level.dimension().location());
        packet.writeBlockPos(pos);
        packet.writeFloat(dir);
        packet.writeUtf(screenName);

        RegistryClient.sendToServer(C2S, packet);

        // 由于暂停时不会接受服务器端的包，所以这里直接先在客户端创建节点，再从服务器同步
        Block block1 = Main.BLOCK_DIRECT_NODE.get();
        BlockState state1 = block1.defaultBlockState();
        level.setBlockAndUpdate(pos, state1);
        BlockEntityDirectNode blockEntity = new BlockEntityDirectNode(pos, state1, dir);
        level.setBlockEntity(blockEntity);
    }

    public static void receiveUpdateC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
#if MC_VERSION >= "11903"
        ResourceKey<Level> levelKey = packet.readResourceKey(net.minecraft.core.registries.Registries.DIMENSION);
#else
        ResourceKey<Level> levelKey = ResourceKey.create(net.minecraft.core.Registry.DIMENSION_REGISTRY, packet.readResourceLocation());
#endif
        
        BlockPos pos = packet.readBlockPos();
        float dir = packet.readFloat();
        String screenName = packet.readUtf();
        server.execute(() -> {
            ServerLevel level = server.getLevel(levelKey);
            if (level == null) return;

            Block block = Main.BLOCK_DIRECT_NODE.get();
            BlockState state = block.defaultBlockState();
            level.setBlockAndUpdate(pos, state);
            BlockEntityDirectNode blockEntity = new BlockEntityDirectNode(pos, state, dir);
            level.setBlockEntity(blockEntity);
            PacketScreen.sendScreenBlockS2C(player, screenName, pos);
        });
    }
}