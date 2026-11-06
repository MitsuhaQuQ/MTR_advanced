package mtr.data;

import mtr.advanced.LiveSimulation;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import io.netty.buffer.Unpooled;
import mtr.MTR;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.advanced.GraphRecords;
import mtr.advanced.PathBoundaries;
import mtr.advanced.PathRegionSync;
import mtr.advanced.RouteLayouts;
import mtr.block.BlockNode;
import mtr.mappings.PersistentStateMapper;
import mtr.mappings.Utilities;
import mtr.packet.*;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import transitsim.motion.MotionEvent;
import transitsim.network.RailGraph;
import transitsim.sim.Occupancy;
import transitsim.sim.RunReservations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * All railway data of one dimension on the server: rails, stations, platforms, sidings, routes, depots and lifts,
 * with the modules split off from it (saving, driving by hand, route finding for passengers, building tools). One is made
 * for each dimension the first time {@link #getInstance(Level)} is asked for it.
 * <p>
 * {@link #simulateTrains(RailNearPlayer)} is the server tick of the railway: it sends players the rails near them, moves the
 * trains of every siding, sends trains out of the depots, runs the lifts and modules, syncs nearby trains and schedules to
 * players, and saves what changed. The order of its steps is listed in section 4 of {@code MTR-代码说明与流程.md}.
 * <p>
 * ANTE replaces {@link #simulateTrains()} with a mixin, so a change meant for every tick goes in
 * {@link #simulateTrains(RailNearPlayer)}.
 */
public class RailwayData extends PersistentStateMapper implements IPacket {

	public final Set<Station> stations = new HashSet<>();
	public final Set<Platform> platforms = new HashSet<>();
	public final Set<Siding> sidings = new HashSet<>();
	public final Set<BlockPos> keyNodes = new HashSet<>();
	public final PathBoundaries pathBoundaries = new PathBoundaries();
	/**
	 * The contingency plans of each rail system, for every route of a network whichever depot runs it (D2).
	 */
	public final ContingencySystems contingencySystems = new ContingencySystems();
	/**
	 * Set when a player changed them, to send them to the players.
	 */
	public volatile boolean contingenciesChanged;
	/**
	 * The compressed network and the paths found on it, which the save loop writes into the database. See {@link GraphRecords}.
	 */
	public final GraphRecords graphRecords = new GraphRecords();
	/**
	 * @return whether the depot's trains don't lock station throats, as its track is shared with a depot not running by
	 * duties
	 */
	public boolean isThroatLocksOff(long depotId) {
		return throatLocksOffDepots.contains(depotId);
	}

	/**
	 * Works out again which depots share track with a depot not running by duties, and says so in the log when it changes.
	 */
	private void updateLockNetworks() {
		final transitsim.network.LockNetworks lockNetworks = new transitsim.network.LockNetworks();
		sidings.forEach(siding -> {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			// Trains moving continuously, like cable cars, lock nothing whatever their depot does
			if (depot != null && !depot.transportMode.continuousMovement) {
				// A depot of trains sends them out by duties only (R2, R3), so only aircraft can run by the old logic
				lockNetworks.addDepot(depot.id, depot.getRunDuties() != null || Depot.supportsTimetable(depot.transportMode));
				siding.forEachPathNodes(nodes -> lockNetworks.addPath(depot.id, nodes));
			}
		});
		final transitsim.network.LockNetworks.Result result = lockNetworks.result();
		throatLocksOffDepots = result.offDepots;
		final String state = result.describe();
		if (!state.equals(lockNetworksState)) {
			if (result.mixed.isEmpty()) {
				if (lockNetworksState != null) {
					System.out.println("Minecraft Transit Railway: station throat locks are on for every depot running by duties");
				}
			} else {
				result.mixed.forEach(network -> System.out.println("Minecraft Transit Railway: station throat locks are off for depots " + depotNames(network.newDepots) + ": their tracks are shared with depots " + depotNames(network.oldDepots) + " that don't run by duties, such as by frequency; give those a timetable or keep them apart with path boundaries"));
			}
			lockNetworksState = state;
		}
	}

	private String depotNames(Set<Long> depotIds) {
		return depotIds.isEmpty() ? "(none)" : depotIds.stream().map(depotId -> {
			final Depot depot = dataCache.depotIdMap.get(depotId);
			return depot == null || depot.name.isEmpty() ? String.valueOf(depotId) : IGui.formatStationName(depot.name);
		}).collect(Collectors.joining(", "));
	}

	/**
	 * Keeps trains running by duties from meeting head on. See {@link RunReservations}.
	 */
	public final RunReservations runReservations = new RunReservations();
	/**
	 * The depots whose trains don't lock station throats, as their track is shared with a depot not running by duties
	 * ({@link transitsim.network.LockNetworks}), worked out again every few seconds.
	 */
	private Set<Long> throatLocksOffDepots = Collections.emptySet();
	private String lockNetworksState = null;
	/**
	 * Which version of each part of the data players edit the server has (multiplayer edit conflicts).
	 */
	public final EditRevisions editRevisions = new EditRevisions();
	public final Set<Route> routes = new HashSet<>();
	public final Set<Depot> depots = new HashSet<>();
	public final Set<LiftServer> lifts = new HashSet<>();
	public final DataCache dataCache = new DataCache(stations, platforms, sidings, routes, depots, lifts);

	public final RailwayDataLoggingModule railwayDataLoggingModule;
	public final RailwayDataCoolDownModule railwayDataCoolDownModule;
	public final RailwayDataPathGenerationModule railwayDataPathGenerationModule;
	public final RailwayDataRailActionsModule railwayDataRailActionsModule;
	public final RailwayDataDriveTrainModule railwayDataDriveTrainModule;
	public final RailwayDataRouteFinderModule railwayDataRouteFinderModule;

	private int prevPlatformCount;
	private int prevSidingCount;
	private boolean useTimeAndWindSync;

	private final Level world;
	private final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
	/**
	 * The length and speed limit of a rail by its key, for cutting signal blocks into block sections.
	 */
	private final transitsim.network.BlockSections.RailInfo railInfo = new transitsim.network.BlockSections.RailInfo() {
		@Override
		public double getLength(UUID rail) {
			final Rail found = findRail(rail);
			return found == null ? 0 : found.getLength();
		}

		@Override
		public float getMaxSpeed(UUID rail) {
			final Rail found = findRail(rail);
			return found == null ? 0 : TrainServer.cutSpeed(found.railType);
		}

		private Rail findRail(UUID rail) {
			final Map<BlockPos, Rail> connections = rails.get(BlockPos.of(rail.getMostSignificantBits()));
			return connections == null ? null : connections.get(BlockPos.of(rail.getLeastSignificantBits()));
		}
	};
	private final SignalBlocks signalBlocks = new SignalBlocks();
	private final RailNodeRepair railNodeRepair = new RailNodeRepair();
	/**
	 * Each route's path, found once for the depots running it by a timetable (decoupling document 5.4, L1).
	 */
	public final RoutePaths routePaths = new RoutePaths();
	private static final int AUTO_DUTIES_CHECK_TICKS = 100;

	public SignalBlocks getSignalBlocks() {
		return signalBlocks;
	}

	public Level getWorld() {
		return world;
	}

	/**
	 * Puts the trains of a depot the component's simulator runs where its timetable has them now, worked out from the
	 * start of the operating day, instead of sending them out one by one from the depot. The player is told how it went.
	 */
	public void placeTrainsByTimetable(ServerPlayer player, long depotId) {
		liveSimulation.placeByTimetable(this, player, depotId);
	}

	/**
	 * Has an object a player edited saved at the end of the tick (multiplayer edit conflicts).
	 *
	 * @param kind as {@link mtr.packet.EditGuard} names it
	 */
	public void markEdited(String kind, long id) {
		railwayDataFileSaveModule.markObjectChanged(kind, id);
	}
	private final Map<UUID, Long> lastMapRailsRequestMillis = new HashMap<>();
	private final Map<UUID, Long> lastMapRouteRequestMillis = new HashMap<>();
	private final Map<UUID, Map<Integer, Map<Long, long[]>>> pendingMapEntries = new ConcurrentHashMap<>();
	private RailGraph<Rail> mapGraph;
	private final PathRegionSync pathRegionSync = new PathRegionSync(pathBoundaries);

	private final RailwayDataFileSaveModule railwayDataFileSaveModule;

	/**
	 * The rails taken by trains, signal blocks and turn back bookings, kept by the component (decoupling document 3.7);
	 * {@link #trainPositions} is its two ticks, as the rest of the code has always used them.
	 */
	private final Occupancy occupancy = new Occupancy();
	private final LiveSimulation liveSimulation = new LiveSimulation();
	private final List<Map<UUID, Long>> trainPositions = occupancy.getTicks();
	/**
	 * What happened to the trains this tick, collected from every siding and handled together at the end of the tick
	 * (decoupling document 3.7).
	 */
	private final List<MotionEvent> motionEvents = new ArrayList<>();
	/**
	 * Trains found in a circle of trains waiting on each other, until given their run (D2).
	 */
	private final Set<Long> deadlockedTrains = new HashSet<>();
	private final List<MotionEvent> lastMotionEvents = new ArrayList<>();
	/**
	 * Depots that failed sending trains out, each said in the log once.
	 */
	private final Set<Long> failedDepots = new HashSet<>();
	private final TickCompensator tickCompensator = new TickCompensator();
	private final Map<Player, BlockPos> playerLastUpdatedPositions = new HashMap<>();
	private final List<Player> playersToSyncSchedules = new ArrayList<>();
	private final UpdateNearbyMovingObjects<TrainServer> updateNearbyTrains;
	private final UpdateNearbyMovingObjects<LiftServer> updateNearbyLifts;
	private final Map<Long, List<ScheduleEntry>> schedulesForPlatform = new HashMap<>();
	private final Map<Long, List<ScheduleEntry>> lastSchedulesForPlatform = new HashMap<>();
	private final Map<Long, Map<BlockPos, TrainDelay>> trainDelays = new HashMap<>();

	private static final int RAIL_UPDATE_DISTANCE = 128;
	private static final int PLAYER_MOVE_UPDATE_THRESHOLD = 16;
	private static final int SCHEDULE_UPDATE_TICKS = 60;

	private static final int DATA_VERSION = 1;

	private static final String NAME = "mtr_train_data";
	private static final int MAP_RAILS_REQUEST_INTERVAL_MILLIS = 5000;
	private static final int MAP_ROUTE_REQUEST_INTERVAL_MILLIS = 50;
	private static final int MAP_DIGEST_BATCH_SIZE = 20000;
	private static final int MAX_MAP_ENTRY_LONGS = 100000;
	private static final String KEY_RAW_MESSAGE_PACK = "raw_message_pack";
	private static final String KEY_DATA_VERSION = "mtr_data_version";
	private static final String KEY_STATIONS = "stations";
	private static final String KEY_PLATFORMS = "platforms";
	private static final String KEY_SIDINGS = "sidings";
	private static final String KEY_ROUTES = "routes";
	private static final String KEY_DEPOTS = "depots";
	private static final String KEY_LIFTS = "lifts";
	private static final String KEY_RAILS = "rails";
	private static final String KEY_SIGNAL_BLOCKS = "signal_blocks";
	private static final String KEY_USE_TIME_AND_WIND_SYNC = "use_time_and_wind_sync";

	public RailwayData(Level world) {
		super(NAME);
		this.world = world;


		final ResourceLocation dimensionLocation = world.dimension().location();
		final Path savePath = ((ServerLevel) world).getServer().getWorldPath(LevelResource.ROOT).resolve("mtr").resolve(dimensionLocation.getNamespace()).resolve(dimensionLocation.getPath().replace("..", "_"));

		railwayDataFileSaveModule = new RailwayDataFileSaveModule(this, world, rails, savePath, signalBlocks);
		railwayDataLoggingModule = new RailwayDataLoggingModule(this, world, rails, savePath);
		railwayDataPathGenerationModule = new RailwayDataPathGenerationModule(this, world, rails);
		railwayDataRailActionsModule = new RailwayDataRailActionsModule(this, world, rails);
		railwayDataCoolDownModule = new RailwayDataCoolDownModule(this, world, rails);
		railwayDataDriveTrainModule = new RailwayDataDriveTrainModule(this, world, rails);
		railwayDataRouteFinderModule = new RailwayDataRouteFinderModule(this, world, rails);

		updateNearbyTrains = new UpdateNearbyMovingObjects<>(PACKET_DELETE_TRAINS, PACKET_UPDATE_TRAINS, PACKET_UPDATE_TRAIN_STATES);
		updateNearbyLifts = new UpdateNearbyMovingObjects<>(PACKET_DELETE_LIFTS, PACKET_UPDATE_LIFTS);
	}

	@Override
	public void load(CompoundTag compoundTag) {
		// TODO temporary code start
		if (compoundTag.contains(KEY_RAW_MESSAGE_PACK)) {
			try {
				final MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(compoundTag.getByteArray(KEY_RAW_MESSAGE_PACK));
				final int mapSize = messageUnpacker.unpackMapHeader();

				for (int i = 0; i < mapSize; ++i) {
					final String key = messageUnpacker.unpackString();
					if (key.equals(KEY_DATA_VERSION)) {
						if (messageUnpacker.unpackInt() > DATA_VERSION) {
							throw new IllegalArgumentException("Unsupported data version");
						}
						continue;
					}

					final int arraySize = messageUnpacker.unpackArrayHeader();
					switch (key) {
						case KEY_STATIONS:
							for (int j = 0; j < arraySize; ++j) {
								stations.add(new Station(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_PLATFORMS:
							for (int j = 0; j < arraySize; ++j) {
								platforms.add(new Platform(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_SIDINGS:
							for (int j = 0; j < arraySize; ++j) {
								sidings.add(new Siding(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_ROUTES:
							for (int j = 0; j < arraySize; ++j) {
								routes.add(new Route(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_DEPOTS:
							for (int j = 0; j < arraySize; ++j) {
								depots.add(new Depot(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_LIFTS:
							for (int j = 0; j < arraySize; ++j) {
								lifts.add(new LiftServer(readMessagePackSKMap(messageUnpacker)));
							}
							break;
						case KEY_RAILS:
							for (int j = 0; j < arraySize; ++j) {
								final RailEntry railEntry = new RailEntry(readMessagePackSKMap(messageUnpacker));
								rails.put(railEntry.pos, railEntry.connections);
							}
							break;
						case KEY_SIGNAL_BLOCKS:
							for (int j = 0; j < arraySize; ++j) {
								signalBlocks.signalBlocks.add(new SignalBlocks.SignalBlock(readMessagePackSKMap(messageUnpacker)));
							}
							break;
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			try {
				final CompoundTag tagStations = compoundTag.getCompound(KEY_STATIONS);
				for (final String key : tagStations.getAllKeys()) {
					stations.add(new Station(tagStations.getCompound(key)));
				}

				final CompoundTag tagNewPlatforms = compoundTag.getCompound(KEY_PLATFORMS);
				for (final String key : tagNewPlatforms.getAllKeys()) {
					platforms.add(new Platform(tagNewPlatforms.getCompound(key)));
				}

				final CompoundTag tagNewSidings = compoundTag.getCompound(KEY_SIDINGS);
				for (final String key : tagNewSidings.getAllKeys()) {
					sidings.add(new Siding(tagNewSidings.getCompound(key)));
				}

				final CompoundTag tagNewRoutes = compoundTag.getCompound(KEY_ROUTES);
				for (final String key : tagNewRoutes.getAllKeys()) {
					routes.add(new Route(tagNewRoutes.getCompound(key)));
				}

				final CompoundTag tagNewDepots = compoundTag.getCompound(KEY_DEPOTS);
				for (final String key : tagNewDepots.getAllKeys()) {
					depots.add(new Depot(tagNewDepots.getCompound(key)));
				}

				final CompoundTag tagNewRails = compoundTag.getCompound(KEY_RAILS);
				for (final String key : tagNewRails.getAllKeys()) {
					final RailEntry railEntry = new RailEntry(tagNewRails.getCompound(key));
					rails.put(railEntry.pos, railEntry.connections);
				}

				final CompoundTag tagNewSignalBlocks = compoundTag.getCompound(KEY_SIGNAL_BLOCKS);
				for (final String key : tagNewSignalBlocks.getAllKeys()) {
					signalBlocks.signalBlocks.add(new SignalBlocks.SignalBlock(tagNewSignalBlocks.getCompound(key)));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		railwayDataFileSaveModule.load();
		validateData();
		railNodeRepair.start(rails);
		dataCache.sync();
		signalBlocks.writeCache();

		useTimeAndWindSync = compoundTag.getBoolean(KEY_USE_TIME_AND_WIND_SYNC);
		runRealTimeSync();

		try {
			UpdateDynmap.updateDynmap(world, this);
		} catch (NoClassDefFoundError | IllegalStateException ignored) {
			System.out.println("Dynamp is not loaded");
		} catch (Exception ignored) {
		}
		try {
			UpdateBlueMap.updateBlueMap(world, this);
		} catch (NoClassDefFoundError | IllegalStateException ignored) {
			System.out.println("BlueMap is not loaded");
		} catch (Exception ignored) {
		}
		try {
			UpdateSquaremap.updateSquaremap(world, this);
		} catch (NoClassDefFoundError | IllegalStateException ignored) {
			System.out.println("Squaremap is not loaded");
		} catch (Exception ignored) {
		}
	}

	@Override
	public void save(File file) {
		final MinecraftServer minecraftServer = ((ServerLevel) world).getServer();
		if (minecraftServer.isStopped() || !minecraftServer.isRunning()) {
			railwayDataFileSaveModule.fullSave();
		} else {
			railwayDataFileSaveModule.autoSave();
		}
		railwayDataLoggingModule.save();
		setDirty();
		super.save(file);
	}

	@Override
	public CompoundTag save(CompoundTag compoundTag) {
		compoundTag.putBoolean(KEY_USE_TIME_AND_WIND_SYNC, useTimeAndWindSync);
		return compoundTag;
	}

	/**
	 * @return the graph of the rails as path generation searches them, for previewing a route's way; the map's, or made
	 * now if there is none yet
	 */
	public RailGraph<Rail> getPreviewGraph() {
		if (mapGraph == null) {
			mapGraph = DeterministicPathFinder.compile(rails, pathBoundaries);
		}
		return mapGraph;
	}

	public Map<BlockPos, Map<BlockPos, Rail>> getRails() {
		return rails;
	}

	public void simulateTrains() {
		simulateTrains((rail, startPos, endPos, playerPos) -> new AABB(startPos, endPos).inflate(RAIL_UPDATE_DISTANCE).contains(playerPos));
	}

	/**
	 * ANTE's rails can have shapes of their own, so its mixin calls this with its own way of telling which rails are near a
	 * player. Everything else of a tick is the same, so nothing added here is lost when ANTE is in.
	 */
	public void simulateTrains(RailNearPlayer isNearPlayer) {
		final List<? extends Player> players = world.players();
		players.forEach(player -> {
			final BlockPos playerBlockPos = player.blockPosition();
			final Vec3 playerPos = player.position();

			if (!playerLastUpdatedPositions.containsKey(player) || playerLastUpdatedPositions.get(player).distManhattan(playerBlockPos) > PLAYER_MOVE_UPDATE_THRESHOLD) {
				final Map<BlockPos, Map<BlockPos, Rail>> railsToAdd = new HashMap<>();
				rails.forEach((startPos, blockPosRailMap) -> blockPosRailMap.forEach((endPos, rail) -> {
					if (isNearPlayer.test(rail, startPos, endPos, playerPos)) {
						if (!railsToAdd.containsKey(startPos)) {
							railsToAdd.put(startPos, new HashMap<>());
						}
						railsToAdd.get(startPos).put(endPos, rail);
					}
				}));

				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
				packet.writeInt(railsToAdd.size());
				railsToAdd.forEach((posStart, railMap) -> {
					packet.writeBlockPos(posStart);
					packet.writeInt(railMap.size());
					railMap.forEach((posEnd, rail) -> {
						packet.writeBlockPos(posEnd);
						rail.writePacket(packet);
					});
				});

				if (packet.readableBytes() <= MAX_PACKET_BYTES) {
					Registry.sendToPlayer((ServerPlayer) player, PACKET_WRITE_RAILS, packet);
				}
				playerLastUpdatedPositions.put(player, playerBlockPos);
			}
		});

		updateNearbyTrains.startTick();
		occupancy.nextTick();
		lastSchedulesForPlatform.clear();
		lastSchedulesForPlatform.putAll(schedulesForPlatform);
		schedulesForPlatform.clear();
		signalBlocks.resetOccupied();
		// Long rails and long signal blocks are cut into block sections, by what the rails are
		signalBlocks.updateSections(SecurityConfig.interlockColor, railInfo);
		final float ticksElapsed = tickCompensator.nextTick(System.currentTimeMillis());
		// In a fixed order, so a tick runs the same way each time (fix F1, R03 of the architecture review)
		final List<Siding> sortedSidings = sidings.stream().sorted(Comparator.comparingLong(siding -> siding.id)).collect(Collectors.toList());
		// The sidings take on their depots' settings, such as speed curves, before the component's simulator looks at them
		sortedSidings.forEach(siding -> siding.setSidingData(world, dataCache.sidingIdToDepot.get(siding.id), rails));
		// Each depot's sidings with the same vehicle share their trains and duties (decoupling document, step D)
		depots.forEach(Depot::clearPools);
		sortedSidings.forEach(siding -> {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null) {
				depot.addToPool(siding);
			}
		});
		// The component's simulator moves on first, seeing the game's trains where they were at the end of the last tick (G1)
		liveSimulation.tick(this, trainPositions);
		sortedSidings.forEach(siding -> {
			siding.ticksElapsed = ticksElapsed;
			siding.lastSchedulesForPlatform = lastSchedulesForPlatform;
			siding.motionEvents = motionEvents;
			siding.simulateTrain(dataCache, railwayDataDriveTrainModule, trainPositions, signalBlocks, updateNearbyTrains.newDataSetInPlayerRange, updateNearbyTrains.dataSetToSync, schedulesForPlatform, trainDelays);
		});
		// Trains back in a shed in this tick are kept there from the next, whichever order the sidings ran in (P2-C)
		sortedSidings.forEach(Siding::keepArrived);
		// Contingency plans a player changed go to every player (D2)
		if (contingenciesChanged) {
			contingenciesChanged = false;
			mtr.packet.ContingencyPackets.sendAllS2C(world, this);
		}
		// Buffers measured off what they were planned with by more than a little go to the players (D1)
		depots.forEach(depot -> depot.sendBuffersIfChanged(packet -> world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_UPDATE_DEPOT, packet))));
		handleMotionEvents();
		// Runs asked for during the tick are given out together, whatever order the trains ran in
		runReservations.resolve(System.currentTimeMillis());
		// Trains waiting on each other in a circle, which the arbiter holds as they are, said once each so the server log shows them
		for (final Set<Long> deadlock : runReservations.takeDeadlocks()) {
			System.out.println("Minecraft Transit Railway: trains waiting on each other for their runs " + deadlock);
			deadlockedTrains.addAll(deadlock);
		}
		depots.forEach(depot -> {
			// A depot that fails doesn't stop the others or the rest of the tick
			try {
				depot.deployTrain(this, world);
			} catch (Exception e) {
				if (failedDepots.add(depot.id)) {
					System.out.println("Minecraft Transit Railway: depot " + depot.id + " failed sending trains out");
					e.printStackTrace();
				}
			}
		});

		updateNearbyLifts.startTick();
		lifts.forEach(lift -> lift.tickServer(world, updateNearbyLifts.newDataSetInPlayerRange, updateNearbyLifts.dataSetToSync));

		railwayDataCoolDownModule.tick();
		railwayDataDriveTrainModule.tick();
		railwayDataRailActionsModule.tick();
		railwayDataRouteFinderModule.tick();
		pathRegionSync.tick(world, this, rails);
		updateNearbyTrains.tick();
		updateNearbyLifts.tick();

		if (MTR.isGameTickInterval(SCHEDULE_UPDATE_TICKS)) {
			players.forEach(player -> {
				if (!playersToSyncSchedules.contains(player)) {
					playersToSyncSchedules.add(player);
				}
			});
		}
		if (!playersToSyncSchedules.isEmpty()) {
			final Player player = playersToSyncSchedules.remove(0);
			final BlockPos playerBlockPos = player.blockPosition();
			final Vec3 playerPos = player.position();

			final Set<Long> platformIds = platforms.stream().filter(platform -> {
				if (platform.isCloseToSavedRail(playerBlockPos, PLAYER_MOVE_UPDATE_THRESHOLD, PLAYER_MOVE_UPDATE_THRESHOLD, PLAYER_MOVE_UPDATE_THRESHOLD)) {
					return true;
				}
				final Station station = dataCache.platformIdToStation.get(platform.id);
				return station != null && station.inArea(playerBlockPos.getX(), playerBlockPos.getZ());
			}).map(platform -> platform.id).collect(Collectors.toSet());

			final Set<UUID> railsToAdd = new HashSet<>();
			rails.forEach((startPos, blockPosRailMap) -> blockPosRailMap.forEach((endPos, rail) -> {
				if (isNearPlayer.test(rail, startPos, endPos, playerPos)) {
					railsToAdd.add(PathData.getRailProduct(startPos, endPos));
				}
			}));
			// Every signal block's state, not only those near the player, so signals on long lines show what is there
			final Map<Long, Boolean> signalBlockStatus = new HashMap<>();
			signalBlocks.signalBlocks.forEach(signalBlock -> signalBlockStatus.put(signalBlock.id, signalBlock.isOccupied()));
			final Map<UUID, Boolean> occupiedRails = new HashMap<>();
			railsToAdd.forEach(rail -> occupiedRails.put(rail, occupancy.isOccupiedNow(rail)));

			if (!platformIds.isEmpty() || !signalBlockStatus.isEmpty() || !occupiedRails.isEmpty()) {
				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
				packet.writeInt(platformIds.size());
				platformIds.forEach(platformId -> {
					packet.writeLong(platformId);
					final List<ScheduleEntry> scheduleEntries = schedulesForPlatform.get(platformId);
					if (scheduleEntries == null) {
						packet.writeInt(0);
					} else {
						packet.writeInt(scheduleEntries.size());
						scheduleEntries.forEach(scheduleEntry -> scheduleEntry.writePacket(packet));
					}
				});

				packet.writeInt(signalBlockStatus.size());
				signalBlockStatus.forEach((id, occupied) -> {
					packet.writeLong(id);
					packet.writeBoolean(occupied);
				});

				packet.writeInt(occupiedRails.size());
				occupiedRails.forEach((rail, occupied) -> {
					packet.writeUUID(rail);
					packet.writeBoolean(occupied);
				});

				// Every block section trains have taken, as its rail and which piece of it (BlockSections)
				final Set<UUID> takenUnits = new HashSet<>(trainPositions.get(1).keySet());
				packet.writeInt(takenUnits.size());
				takenUnits.forEach(unit -> {
					final Map.Entry<UUID, Integer> piece = signalBlocks.getPiece(unit, railInfo);
					packet.writeUUID(piece.getKey());
					packet.writeInt(piece.getValue());
				});

				if (packet.readableBytes() <= MAX_PACKET_BYTES) {
					Registry.sendToPlayer((ServerPlayer) player, PACKET_UPDATE_SCHEDULE, packet);
				}
			}
		}

		if (prevPlatformCount != platforms.size() || prevSidingCount != sidings.size()) {
			dataCache.sync();
		}
		prevPlatformCount = platforms.size();
		prevSidingCount = sidings.size();

		// The new logic and the old don't share track: depots on track with one not running by duties don't lock throats
		if (world.getGameTime() % AUTO_DUTIES_CHECK_TICKS == 0) {
			updateLockNetworks();
		}
		// A depot running by a timetable with no duties set has its duties worked out with its paths, so its paths are found
		// again when its timetables or sidings change, and once after the world is loaded; one depot at a time
		if (world.getGameTime() % AUTO_DUTIES_CHECK_TICKS == 0 && world.getServer() != null && railwayDataPathGenerationModule.canGenerate()) {
			for (final Depot depot : depots) {
				if (depot.takeAutoDutiesChange(dataCache, sidings)) {
					railwayDataPathGenerationModule.generatePath(world.getServer(), depot.id);
					break;
				}
			}
		}
		// Trains waiting on the line with no duty and orders to go back that couldn't be kept, for the advanced editor: sent
		// when they change, and now and then while there are any, for players who joined since
		if (world.getGameTime() % AUTO_DUTIES_CHECK_TICKS == 0) {
			final boolean resend = world.getGameTime() % (AUTO_DUTIES_CHECK_TICKS * 12) == 0;
			// A train given its run is out of its deadlock (D2)
			final Set<Long> stillWaiting = new HashSet<>();
			sidings.forEach(siding -> siding.forEachTrain(train -> {
				if (train.getWaitingMillis() > 0) {
					stillWaiting.add(train.id);
				}
			}));
			deadlockedTrains.retainAll(stillWaiting);
			// A rail system whose anchor platform was removed moves to another platform of its network (D2)
			if (getPlatformGraph().size() > 0) {
				final Map<Long, Long> moved = contingencySystems.keepAnchors(getPlatformGraph(), platformId -> dataCache.platformIdMap.containsKey(platformId));
				moved.forEach((from, to) -> System.out.println("Minecraft Transit Railway: the anchor platform " + from + " of a rail system's contingency plans was removed, so they are kept with platform " + to));
				if (!moved.isEmpty()) {
					contingenciesChanged = true;
				}
			}
			for (final Depot depot : depots) {
				// Trains late or stuck with no plan on for them, and those a plan is on for (D2)
				final List<String> alarms = mtr.packet.DelayAlarms.collect(depot, sidings, dataCache, deadlockedTrains, contingencySystems, getPlatformGraph());
				mtr.packet.DelayAlarms.log(depot, alarms, depot.loggedAlarms);
				if (!alarms.equals(depot.clientAlarms) || resend && alarms.size() > 1) {
					depot.clientAlarms = alarms;
					mtr.packet.DelayAlarms.sendS2C(world, depot.id, alarms);
				}
				final List<String> held = mtr.packet.HeldTrains.collect(depot, sidings, dataCache);
				final List<String> misses;
				synchronized (depot.returnOrderMisses) {
					misses = new ArrayList<>(depot.returnOrderMisses);
				}
				if (depot.runProblemsChanged || !held.equals(depot.clientHeldTrains) || resend && (!held.isEmpty() || !misses.isEmpty())) {
					depot.runProblemsChanged = false;
					depot.clientHeldTrains = held;
					mtr.packet.HeldTrains.sendS2C(world, depot.id, held, misses);
				}
			}
		}
		// Nodes the world lost while their rails were kept, as when the server stopped before saving the world
		railNodeRepair.tick(world, rails);
		// Track laid or taken away in this tick is saved now, the rest with the next save
		railwayDataFileSaveModule.saveNow();
		railwayDataFileSaveModule.autoSaveTick();
	}

	@FunctionalInterface
	public interface RailNearPlayer {

		boolean test(Rail rail, BlockPos startPos, BlockPos endPos, Vec3 playerPos);
	}

	/**
	 * @return the networks of the platforms, as last worked out for the players' regions
	 */
	public transitsim.network.PlatformGraph getPlatformGraph() {
		return pathRegionSync.getLatest().platformGraph;
	}

	public void onPlayerJoin(ServerPlayer serverPlayer) {
		PacketTrainDataGuiServer.sendAllInChunks(serverPlayer, stations, platforms, sidings, routes, depots, lifts, signalBlocks, keyNodes);
		pathRegionSync.send(serverPlayer);
		mtr.packet.ContingencyPackets.sendS2C(serverPlayer, this);
		railwayDataCoolDownModule.onPlayerJoin(serverPlayer);
	}

	// writing data

	public long addRail(Player player, TransportMode transportMode, BlockPos posStart, BlockPos posEnd, Rail rail, boolean validate) {
		final long newId = validate ? new Random().nextLong() : 0;
		addRail(rails, platforms, sidings, transportMode, posStart, posEnd, rail, newId);
		railwayDataFileSaveModule.markRailsChanged(Arrays.asList(posStart, posEnd));

		if (validate) {
			final Rail backwardsRail = DataCache.tryGet(rails, posEnd, posStart);
			if (backwardsRail != null) {
				final List<String> dataList = new ArrayList<>();
				dataList.add(String.format("type:%s", rail.railType));
				dataList.add(String.format("one_way:%s", backwardsRail.railType == RailType.NONE));
				railwayDataLoggingModule.addEvent((ServerPlayer) player, Rail.class, new ArrayList<>(), dataList, posStart, posEnd);
			}
			validateData();
		}

		return newId;
	}

	// Returns a translation key describing the problem, or null if the rails could be rebuilt
	public boolean isNodeFacing(BlockPos pos, RailAngle angle) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		return connections != null && !connections.isEmpty() && connections.values().stream().allMatch(rail -> rail.facingStart.isParallel(angle));
	}

	public String rotateNodeRails(Player player, BlockPos pos, RailAngle angle) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections == null || connections.isEmpty()) {
			return null;
		}
		if (hasSavedRail(pos)) {
			return "gui.mtr.platform_or_siding_exists";
		}

		final Rail.RotatedNode rotatedNode = Rail.rotateNode(rails, pos, angle);
		if (rotatedNode.error != null) {
			return rotatedNode.error;
		}

		rotatedNode.rails.forEach((posEnd, newRail) -> {
			final Rail newBackwardsRail = rotatedNode.backwardsRails.get(posEnd);
			rails.get(pos).put(posEnd, newRail);
			rails.get(posEnd).put(pos, newBackwardsRail);
			railwayDataFileSaveModule.markRailsChanged(Arrays.asList(pos, posEnd));
			PacketTrainDataGuiServer.createRailS2C(world, newRail.transportMode, pos, posEnd, newRail, newBackwardsRail, 0);
		});
		railwayDataLoggingModule.addEvent((ServerPlayer) player, BlockNode.class, new ArrayList<>(), Collections.singletonList(String.format("angle:%s", angle.angleDegrees)), pos);
		return null;
	}

	public void setTurnBackDwell(BlockPos pos, int turnBackDwell) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		if (connections != null) {
			connections.forEach((posEnd, rail) -> {
				final Rail backwardsRail = DataCache.tryGet(rails, posEnd, pos);
				if (backwardsRail != null && (rail.railType == RailType.TURN_BACK || backwardsRail.railType == RailType.TURN_BACK) && rail.turnBackDwell != turnBackDwell) {
					rail.turnBackDwell = turnBackDwell;
					backwardsRail.turnBackDwell = turnBackDwell;
					railwayDataFileSaveModule.markRailsChanged(Arrays.asList(pos, posEnd));
					PacketTrainDataGuiServer.createRailS2C(world, rail.transportMode, pos, posEnd, rail, backwardsRail, 0);
				}
			});
		}
	}

	public long addSignal(Player player, DyeColor color, BlockPos posStart, BlockPos posEnd) {
		railwayDataLoggingModule.addEvent((ServerPlayer) player, SignalBlocks.SignalBlock.class, new ArrayList<>(), Collections.singletonList(String.format("color:%s", color)), posStart, posEnd);
		return signalBlocks.add(0, color, PathData.getRailProduct(posStart, posEnd));
	}

	public void removeNode(Player player, BlockPos pos, TransportMode transportMode) {
		railwayDataLoggingModule.addEvent((ServerPlayer) player, BlockNode.class, Collections.singletonList(String.format("type:%s", transportMode)), new ArrayList<>(), pos);
		// The node and the ends of its rails, whose rails back to it go with it
		final Set<BlockPos> changed = new HashSet<>(rails.getOrDefault(pos, Collections.emptyMap()).keySet());
		changed.add(pos);
		railwayDataFileSaveModule.markRailsChanged(changed);
		removeNode(world, rails, pos, railNodeRepair::isKept);
		setKeyNode(pos, false);
		// A boundary on a rail that was only taken away stays, as the rail is often laid again, but nothing is left of a node
		pathBoundaries.nodes.remove(pos.asLong());
		pathBoundaries.segments.removeIf(segment -> segment.getMostSignificantBits() == pos.asLong() || segment.getLeastSignificantBits() == pos.asLong());
		validateData();
		final FriendlyByteBuf packet = signalBlocks.getValidationPacket(rails);
		if (packet != null) {
			world.players().forEach(player2 -> Registry.sendToPlayer((ServerPlayer) player2, PACKET_REMOVE_SIGNALS, packet));
		}
	}

	public void sendMapRails(ServerPlayer player) {
		final long millis = System.currentTimeMillis();
		if (millis - lastMapRailsRequestMillis.getOrDefault(player.getUUID(), 0L) < MAP_RAILS_REQUEST_INTERVAL_MILLIS) {
			return;
		}
		lastMapRailsRequestMillis.put(player.getUUID(), millis);

		final Map<Long, List<Long>> regions = new HashMap<>();
		final Map<Long, List<Long>> shapeRegions = new HashMap<>();
		rails.forEach((startPos, railMap) -> railMap.forEach((endPos, rail) -> {
			// Each connection is stored once in each direction
			if (startPos.asLong() < endPos.asLong()) {
				final long regionKey = MapSyncData.regionKey(startPos.getX(), startPos.getZ());
				final List<Long> region = regions.computeIfAbsent(regionKey, key -> new ArrayList<>());
				region.add(startPos.asLong());
				region.add(endPos.asLong());
				region.add((long) rail.transportMode.ordinal());
				final float[] shape = rail.getMapShape();
				if (shape != null) {
					final List<Long> shapeRegion = shapeRegions.computeIfAbsent(regionKey, key -> new ArrayList<>());
					shapeRegion.add(startPos.asLong());
					shapeRegion.add(endPos.asLong());
					shapeRegion.add((long) shape.length / 2);
					for (int i = 0; i + 1 < shape.length; i += 2) {
						shapeRegion.add(RailShapes.pack(shape[i], shape[i + 1]));
					}
				}
			}
		}));
		// Regions with rails but no curves are sent empty, so a client drops the shapes of rails straightened or removed
		regions.keySet().forEach(regionKey -> shapeRegions.computeIfAbsent(regionKey, key -> new ArrayList<>()));
		final Map<Long, long[]> shapePayloads = MapSyncData.toPayloads(shapeRegions);
		final Map<Long, Long> shapeHashes = new HashMap<>();
		shapePayloads.forEach((key, payload) -> shapeHashes.put(key, MapSyncData.hashRoute(payload)));
		final Map<Long, long[]> railPayloads = MapSyncData.toPayloads(regions);
		final Map<Long, Long> railHashes = new HashMap<>();
		railPayloads.forEach((key, payload) -> railHashes.put(key, MapSyncData.hashRails(payload)));
		storeAndSendDigest(player, MapSyncData.KIND_RAILS, railPayloads, railHashes);
		storeAndSendDigest(player, MapSyncData.KIND_RAIL_SHAPES, shapePayloads, shapeHashes);

		// Generated paths include the runs to and from the depot and any turn back rails
		final Map<Long, long[]> sidingPayloads = new HashMap<>();
		final Map<Long, Long> sidingHashes = new HashMap<>();
		sidings.forEach(siding -> {
			final long[] nodes = siding.getPathNodes();
			sidingPayloads.put(siding.id, nodes);
			sidingHashes.put(siding.id, MapSyncData.hashRoute(nodes));
		});
		storeAndSendDigest(player, MapSyncData.KIND_SIDINGS, sidingPayloads, sidingHashes);

		final Set<Long> staleDepots = new HashSet<>();
		sidings.forEach(siding -> {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			if (depot != null && siding.isPathStale(rails)) {
				staleDepots.add(depot.id);
			}
		});
		PacketTrainDataGuiServer.sendStaleDepotsS2C(player, staleDepots);

		mapGraph = DeterministicPathFinder.compile(rails, pathBoundaries);
		final RailGraph<Rail> graph = mapGraph;
		final List<MapRouteQuery> queries = new ArrayList<>();
		final RoutePaths.Search mapSearch = getMapSearch(graph);
		final DataCache mapData = dataCache.copyIdMaps();
		routes.forEach(route -> queries.add(new MapRouteQuery(route, mapData, mapSearch)));
		final Thread thread = new Thread(() -> {
			final Map<Long, long[]> routePayloads = new HashMap<>();
			final Map<Long, Long> routeHashes = new HashMap<>();
			queries.forEach(query -> {
				final long[] nodes = query.findNodes(graph);
				graphRecords.addPath("route:" + query.routeId, GraphRecords.getMoves(nodes));
				routePayloads.put(query.routeId, nodes);
				routeHashes.put(query.routeId, MapSyncData.hashRoute(nodes));
			});
			storeAndSendDigest(player, MapSyncData.KIND_ROUTES, routePayloads, routeHashes);
		});
		thread.setDaemon(true);
		thread.start();
	}

	private void storeAndSendDigest(ServerPlayer player, int kind, Map<Long, long[]> payloads, Map<Long, Long> hashes) {
		pendingMapEntries.computeIfAbsent(player.getUUID(), key -> new ConcurrentHashMap<>()).put(kind, new ConcurrentHashMap<>(payloads));
		final List<Long> keys = new ArrayList<>(payloads.keySet());
		int start = 0;
		do {
			final int end = Math.min(start + MAP_DIGEST_BATCH_SIZE, keys.size());
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeByte(kind);
			packet.writeBoolean(end == keys.size());
			packet.writeInt(end - start);
			for (int i = start; i < end; i++) {
				packet.writeLong(keys.get(i));
				packet.writeLong(hashes.get(keys.get(i)));
			}
			Registry.sendToPlayer(player, PACKET_MAP_DIGEST, packet);
			start = end;
		} while (start < keys.size());
	}

	public void sendMapEntries(ServerPlayer player, int kind, boolean isLast, long[] keys) {
		final Map<Integer, Map<Long, long[]>> pendingKinds = pendingMapEntries.get(player.getUUID());
		final Map<Long, long[]> pending = pendingKinds == null ? null : pendingKinds.get(kind);
		if (pending != null) {
			for (final long key : keys) {
				// Removing each entry as it is served means a client can't ask for the same data twice
				final long[] payload = pending.remove(key);
				if (payload != null) {
					sendMapEntry(player, kind, key, payload);
				}
			}
			if (isLast) {
				pendingKinds.remove(kind);
			}
		}
	}

	private static void sendMapEntry(ServerPlayer player, int kind, long key, long[] payload) {
		final boolean tooLarge = payload.length > MAX_MAP_ENTRY_LONGS;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeByte(kind);
		packet.writeLong(key);
		packet.writeLong(kind == MapSyncData.KIND_RAILS ? MapSyncData.hashRails(payload) : MapSyncData.hashRoute(payload));
		packet.writeInt(tooLarge ? 0 : payload.length);
		if (!tooLarge) {
			for (final long value : payload) {
				packet.writeLong(value);
			}
		}
		Registry.sendToPlayer(player, PACKET_MAP_DATA, packet);
	}

	/**
	 * Sends the straightened track diagram of a route to a player, worked out on a snapshot of the rails away from the
	 * server thread ({@link RouteLayouts}).
	 */
	public void sendRouteLayout(ServerPlayer player, long routeId, int halfWidth) {
		final Route route = dataCache.routeIdMap.get(routeId);
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails, pathBoundaries);
		final DataCache layoutData = dataCache.copyIdMaps();
		final Set<UUID> boundarySegments = new HashSet<>(pathBoundaries.segments);
		final Set<Long> boundaryNodes = new HashSet<>(pathBoundaries.nodes);
		final RoutePaths.Search layoutSearch = getMapSearch(graph);
		final MapRouteQuery query = route == null ? null : new MapRouteQuery(route, layoutData, layoutSearch);
		final List<long[]> stops = new ArrayList<>();
		final List<long[]> platformEnds = new ArrayList<>();
		if (route != null) {
			route.platformIds.forEach(routePlatform -> {
				final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
				stops.add(platform == null ? new long[]{routePlatform.platformId, 0, 0} : ends(platform));
			});
			platforms.stream().filter(platform -> platform.transportMode == route.transportMode).forEach(platform -> platformEnds.add(ends(platform)));
		}
		final Thread thread = new Thread(() -> {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			final long[] nodes = query == null ? new long[0] : query.findNodes(graph);
			// One layer of isolation on the diagram: the rails connecting lines take from other routes, those released, and
			// the pathfinding boundaries
			final Map<UUID, Integer> railStatuses = route == null ? new HashMap<>() : layoutSearch.getConnectingRails(route.transportMode, layoutData);
			boundarySegments.forEach(segment -> railStatuses.put(segment, RoutePaths.RAIL_BOUNDARY));
			RouteLayouts.write(routeId, graph, nodes, stops, platformEnds, halfWidth, railStatuses, boundaryNodes, packet);
			Registry.sendToPlayer(player, PACKET_ROUTE_LAYOUT, packet);
		});
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * The route paths on a copy of the rails as they are now, searched in the graph just made of them.
	 */
	private RoutePaths.Search getMapSearch(RailGraph<Rail> graph) {
		return new RoutePaths.Search(routePaths, DataCache.copyRails(rails), graph, pathBoundaries, Collections.emptyMap());
	}

	private static long[] ends(SavedRailBase savedRail) {
		final List<BlockPos> positions = savedRail.getOrderedPositions(BlockPos.ZERO, false);
		return new long[]{savedRail.id, positions.get(0).asLong(), positions.get(1).asLong()};
	}

	public void sendMapRoute(ServerPlayer player, long routeId) {
		final Route route = dataCache.routeIdMap.get(routeId);
		final long millis = System.currentTimeMillis();
		if (route != null && mapGraph != null && millis - lastMapRouteRequestMillis.getOrDefault(player.getUUID(), 0L) >= MAP_ROUTE_REQUEST_INTERVAL_MILLIS) {
			lastMapRouteRequestMillis.put(player.getUUID(), millis);
			// A long route can take a while to trace, so it mustn't hold up the server thread
			final RailGraph<Rail> graph = mapGraph;
			// The map's graph may be older than the rails, so only a route path found already is used, not searched in it
			final MapRouteQuery query = new MapRouteQuery(route, dataCache, null);
			final RoutePaths.RoutePath found = RoutePaths.timesByPath(route) ? routePaths.getFound(route, dataCache, rails) : null;
			query.foundNodes = found == null ? null : found.getNodes();
			final Thread thread = new Thread(() -> sendMapEntry(player, MapSyncData.KIND_ROUTES, routeId, query.findNodes(graph)));
			thread.setDaemon(true);
			thread.start();
		}
	}

	/**
	 * A copy of what is needed to trace one route for the dashboard, which is safe to search from another thread.
	 */
	/**
	 * A route's path as nodes for the map and the track diagram: the route's own path, as its trains run it, keeping off
	 * the connecting lines (L1); traced along the rails as before for routes timed otherwise, or whose path can't be found,
	 * so a route cut off by a connecting line shows where it would have to run.
	 */
	private static class MapRouteQuery {

		private final long routeId;
		private final Route route;
		private final RoutePaths.Search search;
		private final DataCache dataCache;
		private final boolean canTrace;
		private final List<SavedRailBase> platforms = new ArrayList<>();
		private final List<List<BlockPos>> sectionWaypoints = new ArrayList<>();
		/**
		 * The route's path found already, read on the server thread, or null.
		 */
		private long[] foundNodes;

		/**
		 * @param search    the route paths on a copy of the rails, or null to only trace
		 * @param dataCache a copy of the data, read away from the server thread with the search
		 */
		private MapRouteQuery(Route route, DataCache dataCache, RoutePaths.Search search) {
			routeId = route.id;
			this.route = route;
			this.search = RoutePaths.timesByPath(route) ? search : null;
			this.dataCache = dataCache;
			canTrace = route.transportMode != TransportMode.AIRPLANE && !route.transportMode.continuousMovement;
			route.platformIds.forEach(routePlatform -> {
				final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
				if (platform != null && (platforms.isEmpty() || platform.id != platforms.get(platforms.size() - 1).id)) {
					platforms.add(platform);
					sectionWaypoints.add(new ArrayList<>(routePlatform.waypoints));
				}
			});
		}

		private long[] findNodes(RailGraph<Rail> graph) {
			if (foundNodes != null) {
				return foundNodes;
			}
			if (search != null) {
				final RoutePaths.RoutePath routePath = search.get(route, dataCache);
				if (routePath != null && routePath.isFound()) {
					return routePath.getNodes();
				}
			}
			return canTrace ? DeterministicPathFinder.findNodes(graph, platforms, sectionWaypoints) : new long[0];
		}
	}

	public void setKeyNode(BlockPos pos, boolean isKeyNode) {
		if (isKeyNode ? keyNodes.add(pos) : keyNodes.remove(pos)) {
			PacketTrainDataGuiServer.setKeyNodeS2C(world, pos, isKeyNode);
		}
	}

	/**
	 * Marks a node, or the rail from it to another node when {@code pos2} isn't null, as a pathfinding boundary. Players
	 * get the new regions with the next {@link PathRegionSync} check.
	 */
	public void setPathBoundary(Player player, BlockPos pos1, BlockPos pos2, boolean isBoundary) {
		if (pos2 != null && !containsRail(pos1, pos2)) {
			return;
		}
		if (isBoundary && pathBoundaries.nodes.size() + pathBoundaries.segments.size() >= PathBoundaries.MAX_BOUNDARIES) {
			return;
		}
		final boolean changed;
		if (pos2 == null) {
			changed = isBoundary ? pathBoundaries.nodes.add(pos1.asLong()) : pathBoundaries.nodes.remove(pos1.asLong());
		} else {
			final UUID segment = PathBoundaries.segmentKey(pos1.asLong(), pos2.asLong());
			changed = isBoundary ? pathBoundaries.segments.add(segment) : pathBoundaries.segments.remove(segment);
		}
		if (changed) {
			final List<String> dataList = Collections.singletonList(String.format("path_boundary:%s", isBoundary));
			if (pos2 == null) {
				railwayDataLoggingModule.addEvent((ServerPlayer) player, BlockNode.class, new ArrayList<>(), dataList, pos1);
			} else {
				railwayDataLoggingModule.addEvent((ServerPlayer) player, Rail.class, new ArrayList<>(), dataList, pos1, pos2);
			}
		}
	}

	public void removeRailConnection(Player player, BlockPos pos1, BlockPos pos2) {
		final Rail rail1 = DataCache.tryGet(rails, pos1, pos2);
		final Rail rail2 = DataCache.tryGet(rails, pos2, pos1);
		if (rail1 != null && rail2 != null) {
			final List<String> dataList = new ArrayList<>();
			dataList.add(String.format("type:%s", (rail1.railType == RailType.NONE ? rail2 : rail1).railType));
			dataList.add(String.format("one_way:%s", (rail1.railType == RailType.NONE ? rail1 : rail2).railType == RailType.NONE));
			railwayDataLoggingModule.addEvent((ServerPlayer) player, Rail.class, dataList, new ArrayList<>(), pos1, pos2);
		}

		removeRailConnection(world, rails, pos1, pos2, railNodeRepair::isKept);
		railwayDataFileSaveModule.markRailsChanged(Arrays.asList(pos1, pos2));
		validateData();
		final FriendlyByteBuf packet = signalBlocks.getValidationPacket(rails);
		if (packet != null) {
			world.players().forEach(player2 -> Registry.sendToPlayer((ServerPlayer) player2, PACKET_REMOVE_SIGNALS, packet));
		}
	}

	public void removeLiftFloorTrack(BlockPos pos) {
		removeLiftFloorTrack(world, lifts, pos);
		dataCache.sync();
	}

	public boolean hasSavedRail(BlockPos pos) {
		return rails.containsKey(pos) && rails.get(pos).values().stream().anyMatch(rail -> rail.railType.hasSavedRail);
	}

	public boolean containsRail(BlockPos pos1, BlockPos pos2) {
		return containsRail(rails, pos1, pos2);
	}

	public long removeSignal(Player player, DyeColor color, BlockPos posStart, BlockPos posEnd) {
		railwayDataLoggingModule.addEvent((ServerPlayer) player, SignalBlocks.SignalBlock.class, Collections.singletonList(String.format("color:%s", color)), new ArrayList<>(), posStart, posEnd);
		return signalBlocks.remove(0, color, PathData.getRailProduct(posStart, posEnd));
	}

	public void disconnectPlayer(Player player) {
		final UUID uuid = player.getUUID();
		sidings.forEach(siding -> siding.removeRider(uuid));
		lifts.forEach(lift -> lift.ridingEntities.remove(uuid));
		railwayDataCoolDownModule.onPlayerDisconnect(player);
		playerLastUpdatedPositions.remove(player);
	}

	public void getSchedulesForStation(Map<Long, List<ScheduleEntry>> schedulesForStation, long stationId) {
		schedulesForPlatform.forEach((platformId, schedules) -> {
			final Station station = dataCache.platformIdToStation.get(platformId);
			if (station != null && station.id == stationId) {
				schedulesForStation.put(platformId, schedules);
			}
		});
	}

	public List<ScheduleEntry> getSchedulesAtPlatform(long platformId) {
		return schedulesForPlatform.get(platformId);
	}

	public Map<Long, Map<BlockPos, TrainDelay>> getTrainDelays() {
		return trainDelays;
	}

	public void resetTrainDelays(Depot depot) {
		depot.routeIds.forEach(trainDelays::remove);
	}

	/**
	 * Puts this tick's motion events in order (contract 2.1, rule 5), reports emergency braking, and keeps them until the
	 * next tick.
	 */
	private void handleMotionEvents() {
		motionEvents.sort(null);
		motionEvents.forEach(event -> {
			if (event.type == MotionEvent.Type.EMERGENCY_BRAKE) {
				System.out.println("Minecraft Transit Railway: emergency braking, " + event);
			}
		});
		lastMotionEvents.clear();
		lastMotionEvents.addAll(motionEvents);
		motionEvents.clear();
	}

	/**
	 * What happened to the trains in the last tick, in order.
	 */
	public List<MotionEvent> getLastMotionEvents() {
		return Collections.unmodifiableList(lastMotionEvents);
	}

	public boolean getUseTimeAndWindSync() {
		return useTimeAndWindSync;
	}

	public void setUseTimeAndWindSync(boolean useTimeAndWindSync) {
		this.useTimeAndWindSync = useTimeAndWindSync;
		runRealTimeSync();
	}

	private void validateData() {
		removeSavedRailS2C(world, platforms, rails, PACKET_DELETE_PLATFORM);
		removeSavedRailS2C(world, sidings, rails, PACKET_DELETE_SIDING);

		final List<BlockPos> railsToRemove = new ArrayList<>();
		rails.forEach((startPos, railMap) -> railMap.forEach((endPos, rail) -> {
			if (rail.railType.hasSavedRail && SavedRailBase.isInvalidSavedRail(rails, endPos, startPos)) {
				railsToRemove.add(startPos);
				railsToRemove.add(endPos);
			}
		}));
		for (int i = 0; i < railsToRemove.size() - 1; i += 2) {
			removeRailConnection(null, rails, railsToRemove.get(i), railsToRemove.get(i + 1));
		}
	}

	private void runRealTimeSync() {
		if (useTimeAndWindSync) {
			final MinecraftServer server = world.getServer();
			if (server != null) {
				final CommandSourceStack commandSourceStack = server.createCommandSourceStack();
				runCommand(server, commandSourceStack, "/gamerule doDaylightCycle true");
				runCommand(server, commandSourceStack, "/taw set-cycle-length " + world.dimension().location() + " 864000 864000");
				runCommand(server, commandSourceStack, "/taw reload");
				final Calendar calendar = Calendar.getInstance();
				final long ticks = Math.round((calendar.get(Calendar.HOUR_OF_DAY) + Depot.HOURS_IN_DAY - 6) * 1000 + calendar.get(Calendar.MINUTE) / 0.06 + calendar.get(Calendar.SECOND) / 3.6) % 24000;
				runCommand(server, commandSourceStack, "/time set " + ticks);
			}
		}
	}

	// static finders

	public static Platform getPlatformByPos(Set<Platform> platforms, BlockPos pos) {
		try {
			return platforms.stream().filter(platform -> platform.containsPos(pos)).findFirst().orElse(null);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	// other

	public static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Platform> platforms, Set<Siding> sidings, TransportMode transportMode, BlockPos posStart, BlockPos posEnd, Rail rail, long savedRailId) {
		try {
			if (!rails.containsKey(posStart)) {
				rails.put(posStart, new HashMap<>());
			}
			rails.get(posStart).put(posEnd, rail);

			if (savedRailId != 0) {
				if (rail.railType == RailType.PLATFORM && platforms.stream().noneMatch(platform -> platform.containsPos(posStart) || platform.containsPos(posEnd))) {
					platforms.add(new Platform(savedRailId, transportMode, posStart, posEnd));
				} else if (rail.railType == RailType.SIDING && sidings.stream().noneMatch(siding -> siding.containsPos(posStart) || siding.containsPos(posEnd))) {
					sidings.add(new Siding(savedRailId, transportMode, posStart, posEnd, (int) Math.floor(rail.getLength())));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void removeNode(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos) {
		removeNode(world, rails, pos, node -> false);
	}

	/**
	 * @param kept nodes whose block is missing that are kept anyway (see {@link #validateRails})
	 */
	private static void removeNode(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, Predicate<BlockPos> kept) {
		try {
			rails.remove(pos);
			rails.forEach((startPos, railMap) -> {
				railMap.remove(pos);
				if (railMap.isEmpty() && world != null) {
					BlockNode.resetRailNode(world, startPos);
				}
			});
			if (world != null) {
				validateRails(world, rails, kept);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static <T extends Lift> void removeLiftFloorTrack(Level world, Set<T> lifts, BlockPos pos) {
		lifts.removeIf(lift -> lift.hasFloor(pos));
		if (world != null) {
			validateLifts(world, lifts);
		}
	}

	public static void removeRailConnection(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos1, BlockPos pos2) {
		removeRailConnection(world, rails, pos1, pos2, node -> false);
	}

	private static void removeRailConnection(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos1, BlockPos pos2, Predicate<BlockPos> kept) {
		try {
			if (rails.containsKey(pos1)) {
				rails.get(pos1).remove(pos2);
				if (rails.get(pos1).isEmpty() && world != null) {
					BlockNode.resetRailNode(world, pos1);
				}
			}
			if (rails.containsKey(pos2)) {
				rails.get(pos2).remove(pos1);
				if (rails.get(pos2).isEmpty() && world != null) {
					BlockNode.resetRailNode(world, pos2);
				}
			}
			if (world != null) {
				validateRails(world, rails, kept);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static boolean containsRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos1, BlockPos pos2) {
		return rails.containsKey(pos1) && rails.get(pos1).containsKey(pos2);
	}

	/**
	 * See {@link DataCache#getStationAt}: by the platform next to it, else the station whose area takes it in, at its height,
	 * with the platform nearest to it.
	 */
	public static Station getStation(Set<Station> stations, DataCache dataCache, BlockPos pos) {
		try {
			return dataCache.getStationAt(pos);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static long getClosePlatformId(Set<Platform> platforms, DataCache dataCache, BlockPos pos) {
		return getClosePlatformId(platforms, dataCache, pos, 5, 0, 4);
	}

	public static long getClosePlatformId(Set<Platform> platforms, DataCache dataCache, BlockPos pos, int radius, int lower, int upper) {
		try {
			final long posLong = pos.asLong();
			if (dataCache.blockPosToPlatformId.containsKey(posLong)) {
				return dataCache.blockPosToPlatformId.get(posLong);
			} else {
				long platformId = 0;
				for (int i = 1; i <= radius; i++) {
					final int searchRadius = i;
					platformId = platforms.stream().filter(platform -> platform.isCloseToSavedRail(pos, searchRadius, lower, upper)).min(Comparator.comparingInt(platform -> platform.getMidPos().distManhattan(pos))).map(platform -> platform.id).orElse(0L);
					if (platformId != 0) {
						break;
					}
				}
				dataCache.blockPosToPlatformId.put(posLong, platformId);
				return platformId;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	public static boolean useRoutesAndStationsFromIndex(int stopIndex, List<Long> routeIds, DataCache dataCache, RouteAndStationsCallback routeAndStationsCallback) {
		if (stopIndex < 0) {
			return false;
		}

		int sum = 0;
		for (int i = 0; i < routeIds.size(); i++) {
			final Route thisRoute = dataCache.routeIdMap.get(routeIds.get(i));
			final Route nextRoute = i < routeIds.size() - 1 && !dataCache.routeIdMap.get(routeIds.get(i + 1)).isHidden ? dataCache.routeIdMap.get(routeIds.get(i + 1)) : null;
			if (thisRoute != null) {
				final int difference = stopIndex - sum;
				sum += thisRoute.platformIds.size();
				if (!thisRoute.platformIds.isEmpty() && nextRoute != null && !nextRoute.platformIds.isEmpty() && thisRoute.getLastPlatformId() == nextRoute.getFirstPlatformId()) {
					sum--;
				}
				if (stopIndex < sum) {
					final Station thisStation = dataCache.platformIdToStation.get(thisRoute.platformIds.get(difference).platformId);
					int nextStationIndex = difference + 1;
				while (nextStationIndex < thisRoute.platformIds.size() - 1 && thisRoute.platformIds.get(nextStationIndex).pass) {
					nextStationIndex++;
				}
				final Station nextStation = nextStationIndex < thisRoute.platformIds.size() ? dataCache.platformIdToStation.get(thisRoute.platformIds.get(nextStationIndex).platformId) : null;
					final Station lastStation = thisRoute.platformIds.isEmpty() ? null : dataCache.platformIdToStation.get(thisRoute.getLastPlatformId());
					routeAndStationsCallback.routeAndStationsCallback(difference, thisRoute, nextRoute, thisStation, nextStation, lastStation);
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isBetween(double value, double value1, double value2) {
		return isBetween(value, value1, value2, 0);
	}

	public static boolean isBetween(double value, double value1, double value2, double padding) {
		return value >= Math.min(value1, value2) - padding && value <= Math.max(value1, value2) + padding;
	}

	public static float round(double value, int decimalPlaces) {
		int factor = 1;
		for (int i = 0; i < decimalPlaces; i++) {
			factor *= 10;
		}
		return (float) Math.round(value * factor) / factor;
	}

	public static void writeMessagePackDataset(MessagePacker messagePacker, Collection<? extends SerializedDataBase> dataSet, String key) throws IOException {
		messagePacker.packString(key);
		messagePacker.packArrayHeader(dataSet.size());
		for (final SerializedDataBase data : dataSet) {
			messagePacker.packMapHeader(data.messagePackLength());
			data.toMessagePack(messagePacker);
		}
	}

	public static Map<String, Value> castMessagePackValueToSKMap(Value value) {
		final Map<Value, Value> oldMap = value == null ? new HashMap<>() : value.asMapValue().map();
		final HashMap<String, Value> resultMap = new HashMap<>(oldMap.size());
		oldMap.forEach((key, newValue) -> resultMap.put(key.asStringValue().asString(), newValue));
		return resultMap;
	}

	public static boolean hasNoPermission(ServerPlayer serverPlayer) {
		return !hasPermission(serverPlayer.gameMode.getGameModeForPlayer()) || !serverPlayer.hasPermissions(SecurityConfig.editPermissionLevel);
	}

	public static boolean hasPermission(GameType gameType) {
		return gameType == GameType.CREATIVE || gameType == GameType.SURVIVAL;
	}

	public static boolean chunkLoaded(Level world, BlockPos pos) {
		return world.getChunkSource().getChunkNow(pos.getX() / 16, pos.getZ() / 16) != null && world.hasChunk(pos.getX() / 16, pos.getZ() / 16);
	}

	public static String prettyPrint(JsonElement jsonElement) {
		return new GsonBuilder().setPrettyPrinting().create().toJson(jsonElement);
	}

	public static BlockPos newBlockPos(Vec3 vec3) {
		return newBlockPos(vec3.x, vec3.y, vec3.z);
	}

	public static BlockPos newBlockPos(double x, double y, double z) {
		return RailwayData.newBlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
	}

	public static BlockPos newBlockPos(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	public static BlockPos offsetBlockPos(BlockPos pos, double x, double y, double z) {
		return x == 0 && y == 0 && z == 0 ? pos : newBlockPos(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
	}

	public static RailwayData getInstance(Level world) {
		return getInstance(world, () -> new RailwayData(world), NAME);
	}

	public static void benchmark(Runnable runnable, float threshold) {
		final long nanos = System.nanoTime();
		runnable.run();
		final float duration = (System.nanoTime() - nanos) / 1000000000F;
		if (duration >= threshold) {
			System.out.println(duration);
		}
	}

	/**
	 * Takes away the rails of nodes whose block is gone, as when blown up, and the rails left with no connections.
	 *
	 * @param kept nodes whose block is missing that are kept anyway: the ones the world lost while their rails were saved,
	 *             which are put back or reported (see {@link RailNodeRepair}), so taking away one rail doesn't take theirs
	 *             away too, with the platforms and sidings on them
	 */
	private static void validateRails(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, Predicate<BlockPos> kept) {
		final Set<BlockPos> railsToRemove = new HashSet<>();
		final Set<BlockPos> railsNodesToRemove = new HashSet<>();
		rails.forEach((startPos, railMap) -> {
			final boolean chunkLoaded = chunkLoaded(world, startPos);
			if (chunkLoaded && !(world.getBlockState(startPos).getBlock() instanceof BlockNode) && !kept.test(startPos)) {
				railsNodesToRemove.add(startPos);
			}

			if (railMap.isEmpty()) {
				railsToRemove.add(startPos);
			}
		});
		railsToRemove.forEach(rails::remove);
		railsNodesToRemove.forEach(pos -> removeNode(null, rails, pos));
	}

	private static <T extends Lift> void validateLifts(Level world, Set<T> lifts) {
		lifts.removeIf(lift -> lift.isInvalidLift(world));
	}

	private static void removeSavedRailS2C(Level world, Set<? extends SavedRailBase> savedRailBases, Map<BlockPos, Map<BlockPos, Rail>> rails, ResourceLocation packetId) {
		savedRailBases.removeIf(savedRailBase -> {
			final boolean delete = savedRailBase.isInvalidSavedRail(rails);
			if (delete) {
				final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
				packet.writeLong(savedRailBase.id);
				world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, packetId, packet));
			}
			return delete;
		});
	}

	private static void runCommand(MinecraftServer server, CommandSourceStack commandSourceStack, String command) {
		System.out.println("Running command " + command);
		Utilities.sendCommand(server, commandSourceStack, command);
	}

	// TODO temporary code start
	private static Map<String, Value> readMessagePackSKMap(MessageUnpacker messageUnpacker) throws IOException {
		final int size = messageUnpacker.unpackMapHeader();
		final HashMap<String, Value> result = new HashMap<>(size);
		for (int i = 0; i < size; ++i) {
			result.put(messageUnpacker.unpackString(), messageUnpacker.unpackValue());
		}
		return result;
	}

	@Deprecated
	private static class RailEntry extends SerializedDataBase {

		public final BlockPos pos;
		public final Map<BlockPos, Rail> connections;

		private static final String KEY_NODE_POS = "node_pos";
		private static final String KEY_RAIL_CONNECTIONS = "rail_connections";

		public RailEntry(BlockPos pos, Map<BlockPos, Rail> connections) {
			this.pos = pos;
			this.connections = connections;
		}

		public RailEntry(CompoundTag compoundTag) {
			pos = BlockPos.of(compoundTag.getLong(KEY_NODE_POS));
			connections = new HashMap<>();

			final CompoundTag tagConnections = compoundTag.getCompound(KEY_RAIL_CONNECTIONS);
			for (final String keyConnection : tagConnections.getAllKeys()) {
				connections.put(BlockPos.of(tagConnections.getCompound(keyConnection).getLong(KEY_NODE_POS)), new Rail(tagConnections.getCompound(keyConnection)));
			}
		}

		public RailEntry(Map<String, Value> map) {
			final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
			pos = BlockPos.of(messagePackHelper.getLong(KEY_NODE_POS));
			connections = new HashMap<>();
			messagePackHelper.iterateArrayValue(KEY_RAIL_CONNECTIONS, value -> {
				final Map<String, Value> mapSK = RailwayData.castMessagePackValueToSKMap(value);
				connections.put(BlockPos.of(new MessagePackHelper(mapSK).getLong(KEY_NODE_POS)), new Rail(mapSK));
			});
		}

		@Override
		public void toMessagePack(MessagePacker messagePacker) throws IOException {
			messagePacker.packString(KEY_NODE_POS).packLong(pos.asLong());

			messagePacker.packString(KEY_RAIL_CONNECTIONS).packArrayHeader(connections.size());
			for (final Map.Entry<BlockPos, Rail> entry : connections.entrySet()) {
				final BlockPos endNodePos = entry.getKey();
				messagePacker.packMapHeader(entry.getValue().messagePackLength() + 1);
				messagePacker.packString(KEY_NODE_POS).packLong(endNodePos.asLong());
				entry.getValue().toMessagePack(messagePacker);
			}
		}

		@Override
		public int messagePackLength() {
			return 2;
		}

		@Override
		public void writePacket(FriendlyByteBuf packet) {
		}
	}
	// TODO temporary code end

	@FunctionalInterface
	public interface RouteAndStationsCallback {
		void routeAndStationsCallback(int currentStationIndex, Route thisRoute, Route nextRoute, Station thisStation, Station nextStation, Station lastStation);
	}
}
