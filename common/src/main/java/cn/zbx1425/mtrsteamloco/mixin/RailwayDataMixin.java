package cn.zbx1425.mtrsteamloco.mixin;

import net.minecraft.world.phys.Vec3;
import mtr.data.*;
import cn.zbx1425.mtrsteamloco.data.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import io.netty.buffer.Unpooled;
import mtr.MTR;
import mtr.Registry;
import mtr.block.BlockNode;
import mtr.mappings.PersistentStateMapper;
import mtr.mappings.Utilities;
import mtr.packet.*;
import mtr.path.PathData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import java.util.stream.Collectors;

import java.util.*;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mutable;

@Mixin(RailwayData.class)
public class RailwayDataMixin implements IPacket {

    @Shadow(remap = false) private Set<Station> stations = new HashSet<>();
	@Shadow(remap = false) private Set<Platform> platforms = new HashSet<>();
	@Shadow(remap = false) private Set<Siding> sidings = new HashSet<>();
	@Shadow(remap = false) private Set<Route> routes = new HashSet<>();
	@Shadow(remap = false) private Set<Depot> depots = new HashSet<>();
	@Shadow(remap = false) private Set<LiftServer> lifts = new HashSet<>();
	@Shadow(remap = false) private DataCache dataCache = new DataCache(stations, platforms, sidings, routes, depots, lifts);

	@Shadow(remap = false) private RailwayDataLoggingModule railwayDataLoggingModule;
	@Shadow(remap = false) private RailwayDataCoolDownModule railwayDataCoolDownModule;
	@Shadow(remap = false) private RailwayDataPathGenerationModule railwayDataPathGenerationModule;
	@Shadow(remap = false) private RailwayDataRailActionsModule railwayDataRailActionsModule;
	@Shadow(remap = false) private RailwayDataDriveTrainModule railwayDataDriveTrainModule;
	@Shadow(remap = false) private RailwayDataRouteFinderModule railwayDataRouteFinderModule;

	@Shadow(remap = false) private int prevPlatformCount;
	@Shadow(remap = false) private int prevSidingCount;
	@Shadow(remap = false) private boolean useTimeAndWindSync;

	@Shadow(remap = false) private Level world;
	@Shadow(remap = false) private Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
	@Shadow(remap = false) private SignalBlocks signalBlocks = new SignalBlocks();

	@Shadow(remap = false) private RailwayDataFileSaveModule railwayDataFileSaveModule;

	@Shadow(remap = false) private List<Map<UUID, Long>> trainPositions = new ArrayList<>(2);
	@Shadow(remap = false) private Map<Player, BlockPos> playerLastUpdatedPositions = new HashMap<>();
	@Shadow(remap = false) private List<Player> playersToSyncSchedules = new ArrayList<>();
	@Shadow(remap = false) private UpdateNearbyMovingObjects<TrainServer> updateNearbyTrains;
	@Shadow(remap = false) private UpdateNearbyMovingObjects<LiftServer> updateNearbyLifts;
	@Shadow(remap = false) private Map<Long, List<ScheduleEntry>> schedulesForPlatform = new HashMap<>();
	@Shadow(remap = false) private Map<Long, Map<BlockPos, TrainDelay>> trainDelays = new HashMap<>();

    @Shadow(remap = false) @Final @Mutable private static int RAIL_UPDATE_DISTANCE = 128;
	@Shadow(remap = false) private static int PLAYER_MOVE_UPDATE_THRESHOLD = 16;
	@Shadow(remap = false) private static int SCHEDULE_UPDATE_TICKS = 60;

	@Shadow(remap = false) private static int DATA_VERSION = 1;

	@Shadow(remap = false) private static String NAME = "mtr_train_data";
	@Shadow(remap = false) private static String KEY_RAW_MESSAGE_PACK = "raw_message_pack";
	@Shadow(remap = false) private static String KEY_DATA_VERSION = "mtr_data_version";
	@Shadow(remap = false) private static String KEY_STATIONS = "stations";
	@Shadow(remap = false) private static String KEY_PLATFORMS = "platforms";
	@Shadow(remap = false) private static String KEY_SIDINGS = "sidings";
	@Shadow(remap = false) private static String KEY_ROUTES = "routes";
	@Shadow(remap = false) private static String KEY_DEPOTS = "depots";
	@Shadow(remap = false) private static String KEY_LIFTS = "lifts";
	@Shadow(remap = false) private static String KEY_RAILS = "rails";
	@Shadow(remap = false) private static String KEY_SIGNAL_BLOCKS = "signal_blocks";
	@Shadow(remap = false) private static String KEY_USE_TIME_AND_WIND_SYNC = "use_time_and_wind_sync";

    /**
     * ANTE's rails can have shapes of their own, so this only changes how rails near a player are found and how far that
     * reaches. The rest of the tick is MTR's own, so nothing added to it is lost when ANTE is in.
     */
    public void simulateTrains() {
        RAIL_UPDATE_DISTANCE = world.getServer().getPlayerList().getViewDistance() * 16;
        final int railUpdateDistance = RAIL_UPDATE_DISTANCE;
        ((RailwayData) (Object) this).simulateTrains((rail, startPos, endPos, playerPos) -> ((RailExtraSupplier) (Object) rail).isBetween(playerPos.x, playerPos.y, playerPos.z, railUpdateDistance));
	}
}