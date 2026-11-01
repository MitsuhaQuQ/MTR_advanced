package cn.zbx1425.mtrsteamloco.network;

import io.netty.buffer.Unpooled;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import cn.zbx1425.mtrsteamloco.Main;
import mtr.RegistryClient;
import cn.zbx1425.mtrsteamloco.network.util.StringMapSerializer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.Train;
import mtr.data.TrainServer;
import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import cn.zbx1425.mtrsteamloco.mixin.SidingAccessor;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class PacketUpdateTrainCustomConfigs {
    
    public static ResourceLocation C2S = new ResourceLocation(Main.MOD_ID, "update_train_custom_configs");

    public static void sendUpdateC2S(Train train) {
        sendUpdateC2S(train.sidingId, train.id, ((TrainExtraSupplier) train).getCustomConfigs());
    }

    private static void sendUpdateC2S(long sidingId, long trainId, Map<String, String> customConfigss) {
        if (customConfigss == null) return;

        final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeLong(sidingId);
        packet.writeLong(trainId);
        String str = "";
        try {
            str = StringMapSerializer.serializeToString(customConfigss);
        } catch (Exception e) {
            e.printStackTrace();
        }
        packet.writeUtf(str);
        
        RegistryClient.sendToServer(C2S, packet);
    }

    public static void receiveUpdateC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
        long trainId = packet.readLong();
        long sidingId = packet.readLong();
        String str = packet.readUtf();
        Map<String, String> customConfigs = null;
        try {
            customConfigs = StringMapSerializer.deserialize(str);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (customConfigs == null) return;
        if (packet == null) return;
        final Map<String, String> ed = customConfigs;
        
        server.execute(() -> {
#if MC_VERSION >= "12000"
            RailwayData railwayData = RailwayData.getInstance(player.level());
#else
            RailwayData railwayData = RailwayData.getInstance(player.level);
#endif
            if (railwayData == null) return;
            if (railwayData.sidings == null) return;
            Set<Siding> sidings = new HashSet<>(railwayData.sidings);
            for (Siding siding : sidings) {
                if (siding == null) continue;
                if (siding.id == sidingId) {
                    Set<TrainServer> trains = ((SidingAccessor) siding).getTrains();
                    if (trains == null) return;
                    for (TrainServer train : trains) {
                        if (train.id == trainId) {
                            ((TrainExtraSupplier) train).setCustomConfigs(ed);
                            ((TrainExtraSupplier) train).isConfigsChanged(true);
                            break;
                        }
                    }
                    break;
                }
            }
        });
    }
}