package mtr.client;

import transitsim.plan.TimetableConflicts;
import mtr.KeyMappings;
import mtr.MTRClient;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import transitsim.network.RegionData;

import java.util.*;
import java.util.function.Function;

/**
 * The railway data the client knows about: stations, platforms, sidings, routes, depots, the trains near the
 * player ({@link #TRAINS}), lifts, and the lookups ({@link #DATA_CACHE}). Filled from the server's packets, read by the
 * screens and renderers. {@link #tick()} runs every client tick (driving by hand, key presses).
 */
public final class ClientData {

	public static String DASHBOARD_SEARCH = "";
	public static String ROUTES_PLATFORMS_SEARCH = "";
	public static String ROUTES_PLATFORMS_SELECTED_SEARCH = "";
	public static String TRAINS_SEARCH = "";
	public static String EXIT_PARENTS_SEARCH = "";
	public static String EXIT_DESTINATIONS_SEARCH = "";

	private static boolean pressingAccelerate = false;
	private static boolean pressingBrake = false;
	private static boolean pressingDoors = false;
	private static float shiftHoldingTicks = 0;

	public static final Set<Station> STATIONS = new HashSet<>();
	public static final Set<Platform> PLATFORMS = new HashSet<>();
	public static final Set<Siding> SIDINGS = new HashSet<>();
	public static final Set<Route> ROUTES = new HashSet<>();
	public static final Set<Depot> DEPOTS = new HashSet<>();
	public static final Set<LiftClient> LIFTS = new HashSet<>();
	public static final SignalBlocks SIGNAL_BLOCKS = new SignalBlocks();
	public static final Set<BlockPos> KEY_NODES = new HashSet<>();
	/**
	 * The pathfinding boundaries and the regions they make, sent by the server whenever they change.
	 */
	public static RegionData PATH_REGIONS = RegionData.EMPTY;
	/**
	 * The contingency plans of each rail system, as the server sent them (D2).
	 */
	public static final ContingencySystems CONTINGENCY_SYSTEMS = new ContingencySystems();
	public static final List<MapRail> MAP_RAILS = new ArrayList<>();
	public static final Map<Long, long[]> MAP_ROUTES = new HashMap<>();
	public static final Map<Long, long[]> MAP_SIDINGS = new HashMap<>();
	/**
	 * A few points along each curved rail, by rail key ({@code RailKeys.of}), as x, z, ... from the rail's lower node.
	 */
	public static final Map<java.util.UUID, float[]> MAP_RAIL_SHAPES = new HashMap<>();
	public static final Set<Long> STALE_DEPOTS = new HashSet<>();
	public static final Map<Long, long[][]> TIMETABLE_TIMES = new HashMap<>();
	/**
	 * Why the routes the advanced editor asked times for have no path, sent with the times.
	 */
	public static final Map<Long, mtr.path.PathFailure> ROUTE_PATH_FAILURES = new HashMap<>();
	public static final Map<Long, TimetableConflicts.Result> TIMETABLE_CONFLICTS = new HashMap<>();
	public static final Map<UUID, Boolean> OCCUPIED_RAILS = new HashMap<>();
	/**
	 * Every block section trains have taken, all over the world: the pieces taken of each rail, by its key, the first being
	 * 0 ({@code BlockSections}); null from a server that doesn't send them.
	 */
	public static Map<UUID, Set<Integer>> OCCUPIED_SECTIONS = null;
	public static final Map<BlockPos, Map<BlockPos, Rail>> RAILS = new HashMap<>();
	public static final Set<TrainClient> TRAINS = new HashSet<>();
	public static final List<DataConverter> RAIL_ACTIONS = new ArrayList<>();
	public static final Map<Long, Set<ScheduleEntry>> SCHEDULES_FOR_PLATFORM = new HashMap<>();

	public static final ClientCache DATA_CACHE = new ClientCache(STATIONS, PLATFORMS, SIDINGS, ROUTES, DEPOTS, LIFTS);

	private static final Map<UUID, Integer> PLAYER_RIDING_COOL_DOWN = new HashMap<>();

	public static void tick() {
		final Set<UUID> playersToRemove = new HashSet<>();
		PLAYER_RIDING_COOL_DOWN.forEach((uuid, coolDown) -> {
			if (coolDown <= 0) {
				playersToRemove.add(uuid);
			}
			PLAYER_RIDING_COOL_DOWN.put(uuid, coolDown - 1);
		});
		playersToRemove.forEach(PLAYER_RIDING_COOL_DOWN::remove);


		final boolean tempPressingAccelerate = KeyMappings.TRAIN_ACCELERATE.isDown();
		final boolean tempPressingBrake = KeyMappings.TRAIN_BRAKE.isDown();
		final boolean tempPressingDoors = KeyMappings.TRAIN_TOGGLE_DOORS.isDown();
		PacketTrainDataGuiClient.sendDriveTrainC2S(
				tempPressingAccelerate && !pressingAccelerate,
				tempPressingBrake && !pressingBrake,
				tempPressingDoors && !pressingDoors
		);
		pressingAccelerate = tempPressingAccelerate;
		pressingBrake = tempPressingBrake;
		pressingDoors = tempPressingDoors;

		final Minecraft minecraftClient = Minecraft.getInstance();
		final Player player = minecraftClient.player;
		if (player != null) {
			if (player.isShiftKeyDown()) {
				shiftHoldingTicks += MTRClient.getLastFrameDuration();
			} else {
				shiftHoldingTicks = 0;
			}
		}
	}

	public static void writeRails(Minecraft client, FriendlyByteBuf packet) {
		final Map<BlockPos, Map<BlockPos, Rail>> railsTemp = new HashMap<>();

		final int railsCount = packet.readInt();
		for (int i = 0; i < railsCount; i++) {
			final BlockPos startPos = packet.readBlockPos();
			final Map<BlockPos, Rail> railMap = new HashMap<>();
			final int railCount = packet.readInt();
			for (int j = 0; j < railCount; j++) {
				railMap.put(packet.readBlockPos(), new Rail(packet));
			}
			railsTemp.put(startPos, railMap);
		}

		client.execute(() -> clearAndAddAll(RAILS, railsTemp));
	}

	public static void updateTrains(Minecraft client, FriendlyByteBuf packet) {
		final Set<TrainClient> trainsToUpdate = new HashSet<>();

		while (packet.isReadable()) {
			trainsToUpdate.add(new TrainClient(packet));
		}

		client.execute(() -> trainsToUpdate.forEach(newTrain -> {
			final TrainClient existingTrain = getTrainById(newTrain.id);
			if (existingTrain == null) {
				TRAINS.add(newTrain);
			} else {
				existingTrain.copyFromTrain(newTrain);
			}
		}));
	}

	/**
	 * What changed while trains run, for trains the client has with the same path (G2).
	 */
	public static void updateTrainStates(Minecraft client, FriendlyByteBuf packet) {
		final List<TrainClient.State> states = new ArrayList<>();
		while (packet.isReadable()) {
			states.add(new TrainClient.State(packet));
		}

		client.execute(() -> states.forEach(state -> {
			final TrainClient existingTrain = getTrainById(state.id);
			if (existingTrain != null) {
				existingTrain.applyState(state);
			}
		}));
	}

	public static void deleteTrains(Minecraft client, FriendlyByteBuf packet) {
		final Set<Long> trainIdsToKeep = new HashSet<>();

		final int trainsCount = packet.readInt();
		for (int i = 0; i < trainsCount; i++) {
			trainIdsToKeep.add(packet.readLong());
		}

		client.execute(() -> {
			TRAINS.forEach(trainClient -> {
				if (!trainIdsToKeep.contains(trainClient.id)) {
					trainClient.isRemoved = true;
				}
			});
			TRAINS.removeIf(trainClient -> trainClient.isRemoved);
		});
	}

	public static void updateLifts(Minecraft client, FriendlyByteBuf packet) {
		final Set<LiftClient> liftsToUpdate = new HashSet<>();

		while (packet.isReadable()) {
			liftsToUpdate.add(new LiftClient(packet));
		}

		client.execute(() -> liftsToUpdate.forEach(newLift -> {
			final LiftClient existingLift = DATA_CACHE.liftsClientIdMap.get(newLift.id);
			if (existingLift == null) {
				LIFTS.add(newLift);
				ClientData.DATA_CACHE.syncLiftIds();
			} else {
				existingLift.copyFromLift(newLift);
			}
		}));
	}

	public static void deleteLifts(Minecraft client, FriendlyByteBuf packet) {
		final Set<Long> liftIdsToKeep = new HashSet<>();

		final int liftsCount = packet.readInt();
		for (int i = 0; i < liftsCount; i++) {
			liftIdsToKeep.add(packet.readLong());
		}

		client.execute(() -> {
			final Set<LiftClient> liftsToRemove = new HashSet<>();
			LIFTS.forEach(lift -> {
				if (!liftIdsToKeep.contains(lift.id)) {
					liftsToRemove.add(lift);
				}
			});
			liftsToRemove.forEach(LIFTS::remove);
			ClientData.DATA_CACHE.syncLiftIds();
		});
	}

	public static void updateTrainPassengers(Minecraft client, FriendlyByteBuf packet) {
		final TrainClient train = getTrainById(packet.readLong());
		final float percentageX = packet.readFloat();
		final float percentageZ = packet.readFloat();
		final UUID uuid = packet.readUUID();
		if (train != null) {
			client.execute(() -> train.startRidingClient(uuid, percentageX, percentageZ));
		}
	}

	public static void updateTrainPassengerPosition(Minecraft client, FriendlyByteBuf packet) {
		final TrainClient train = getTrainById(packet.readLong());
		final float percentageX = packet.readFloat();
		final float percentageZ = packet.readFloat();
		final UUID uuid = packet.readUUID();
		if (train != null) {
			client.execute(() -> train.updateRiderPercentages(uuid, percentageX, percentageZ));
		}
	}

	public static void updateLiftPassengers(Minecraft client, FriendlyByteBuf packet) {
		final LiftClient lift = DATA_CACHE.liftsClientIdMap.get(packet.readLong());
		final float percentageX = packet.readFloat();
		final float percentageZ = packet.readFloat();
		final UUID uuid = packet.readUUID();
		if (lift != null) {
			client.execute(() -> lift.startRidingClient(uuid, percentageX, percentageZ));
		}
	}

	public static void updateLiftPassengerPosition(Minecraft client, FriendlyByteBuf packet) {
		final LiftClient lift = DATA_CACHE.liftsClientIdMap.get(packet.readLong());
		final float percentageX = packet.readFloat();
		final float percentageZ = packet.readFloat();
		final UUID uuid = packet.readUUID();
		if (lift != null) {
			client.execute(() -> lift.updateRiderPercentages(uuid, percentageX, percentageZ));
		}
	}

	public static void updateRailActions(Minecraft client, FriendlyByteBuf packet) {
		final List<DataConverter> railActions = new ArrayList<>();
		final int actionCount = packet.readInt();
		for (int i = 0; i < actionCount; i++) {
			final long id = packet.readLong();
			final String player = packet.readUtf();
			final float length = packet.readFloat();
			final String block = Text.translatable(packet.readUtf()).getString();
			final String name = Text.translatable("gui.mtr." + packet.readUtf(), player, length, block).getString();
			final int color = packet.readInt();
			railActions.add(new DataConverter(id, name, color));
		}
		client.execute(() -> {
			RAIL_ACTIONS.clear();
			RAIL_ACTIONS.addAll(railActions);
		});
	}

	public static void updateSchedule(Minecraft client, FriendlyByteBuf packet) {
		final Map<Long, Set<ScheduleEntry>> tempSchedulesForPlatform = new HashMap<>();
		final int platformCount = packet.readInt();
		for (int i = 0; i < platformCount; i++) {
			final long platformId = packet.readLong();
			final int scheduleCount = packet.readInt();
			for (int j = 0; j < scheduleCount; j++) {
				if (!tempSchedulesForPlatform.containsKey(platformId)) {
					tempSchedulesForPlatform.put(platformId, new HashSet<>());
				}
				tempSchedulesForPlatform.get(platformId).add(new ScheduleEntry(packet));
			}
		}

		final Map<Long, Boolean> signalBlockStatus = new HashMap<>();
		final int signalBlockCount = packet.readInt();
		for (int i = 0; i < signalBlockCount; i++) {
			signalBlockStatus.put(packet.readLong(), packet.readBoolean());
		}

		final Map<UUID, Boolean> occupiedRails = new HashMap<>();
		final int occupiedRailsCount = packet.readInt();
		for (int i = 0; i < occupiedRailsCount; i++) {
			occupiedRails.put(packet.readUUID(), packet.readBoolean());
		}

		final Map<UUID, Set<Integer>> occupiedSections;
		if (packet.readableBytes() >= 4) {
			occupiedSections = new HashMap<>();
			final int sectionCount = packet.readInt();
			for (int i = 0; i < sectionCount; i++) {
				occupiedSections.computeIfAbsent(packet.readUUID(), key -> new HashSet<>()).add(packet.readInt());
			}
		} else {
			occupiedSections = null;
		}

		client.execute(() -> {
			clearAndAddAll(SCHEDULES_FOR_PLATFORM, tempSchedulesForPlatform);
			SIGNAL_BLOCKS.writeSignalBlockStatus(signalBlockStatus);
			OCCUPIED_RAILS.clear();
			OCCUPIED_RAILS.putAll(occupiedRails);
			OCCUPIED_SECTIONS = occupiedSections;
		});
	}

	public static void receivePacket(FriendlyByteBuf packet) {
		final FriendlyByteBuf packetCopy = new FriendlyByteBuf(packet.copy());
		clearAndAddAll(STATIONS, deserializeData(packetCopy, Station::new));
		clearAndAddAll(PLATFORMS, deserializeData(packetCopy, Platform::new));
		clearAndAddAll(SIDINGS, deserializeData(packetCopy, Siding::new));
		clearAndAddAll(ROUTES, deserializeData(packetCopy, Route::new));
		clearAndAddAll(DEPOTS, deserializeData(packetCopy, Depot::new));
		clearAndAddAll(LIFTS, deserializeData(packetCopy, LiftClient::new));
		clearAndAddAll(SIGNAL_BLOCKS.signalBlocks, deserializeData(packetCopy, SignalBlocks.SignalBlock::new));
		KEY_NODES.clear();
		final int keyNodeCount = packetCopy.readInt();
		for (int i = 0; i < keyNodeCount; i++) {
			KEY_NODES.add(packetCopy.readBlockPos());
		}

		TRAINS.clear();
		ClientData.DATA_CACHE.sync();
		ClientData.DATA_CACHE.refreshDynamicResources();
		SIGNAL_BLOCKS.writeCache();
	}

	public static <T extends NameColorDataBase> Set<T> getFilteredDataSet(TransportMode transportMode, Set<T> dataSet) {
		final Set<T> returnData = new HashSet<>();
		dataSet.forEach(data -> {
			if (data.isTransportMode(transportMode)) {
				returnData.add(data);
			}
		});
		return returnData;
	}

	public static void updatePlayerRidingOffset(UUID uuid) {
		PLAYER_RIDING_COOL_DOWN.put(uuid, 2);
	}

	public static boolean isRiding(UUID uuid) {
		return PLAYER_RIDING_COOL_DOWN.containsKey(uuid);
	}

	/**
	 * Whether the server's permission level lets this player edit, which the server sends. See {@link mtr.SecurityConfig#editPermissionLevel}.
	 */
	public static boolean hasEditPermissionLevel = true;
	/**
	 * Whether the server lets the advanced editor simulate days, a debugging tool ({@code editorSimulation}).
	 */
	public static boolean editorSimulation = false;
	/**
	 * The signal block colour of interlocking zones, as the server has it ({@code interlockColor}).
	 */
	public static net.minecraft.world.item.DyeColor interlockColor = net.minecraft.world.item.DyeColor.MAGENTA;

	public static boolean hasPermission() {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		final ClientPacketListener clientPacketListener = Minecraft.getInstance().getConnection();
		if (clientPacketListener == null) {
			return false;
		}
		final PlayerInfo playerInfo = clientPacketListener.getPlayerInfo(player.getUUID());
		if (playerInfo == null) {
			return false;
		}
		return hasEditPermissionLevel && RailwayData.hasPermission(playerInfo.getGameMode());
	}

	public static float getShiftHoldingTicks() {
		return shiftHoldingTicks;
	}

	private static <T extends SerializedDataBase> Set<T> deserializeData(FriendlyByteBuf packet, Function<FriendlyByteBuf, T> supplier) {
		final Set<T> objects = new HashSet<>();
		final int dataCount = packet.readInt();
		for (int i = 0; i < dataCount; i++) {
			objects.add(supplier.apply(packet));
		}
		return objects;
	}

	public static class MapRail {

		public final int x1;
		public final int z1;
		public final int x2;
		public final int z2;
		public final TransportMode transportMode;
		public final long node1;
		public final long node2;

		public MapRail(BlockPos pos1, BlockPos pos2, TransportMode transportMode) {
			node1 = pos1.asLong();
			node2 = pos2.asLong();
			x1 = pos1.getX();
			z1 = pos1.getZ();
			x2 = pos2.getX();
			z2 = pos2.getZ();
			this.transportMode = transportMode;
		}
	}

	private static <U> void clearAndAddAll(Collection<U> target, Collection<U> source) {
		target.clear();
		target.addAll(source);
	}

	private static <U, V> void clearAndAddAll(Map<U, V> target, Map<U, V> source) {
		target.clear();
		target.putAll(source);
	}

	private static TrainClient getTrainById(long id) {
		try {
			return TRAINS.stream().filter(item -> item.id == id).findFirst().orElse(null);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}
