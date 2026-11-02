package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.IPacket;
import mtr.path.PathData;
import mtr.path.PathFinder;
import mtr.path.PathRelocator;
import mtr.path.RailGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Siding extends SavedRailBase implements IPacket, IReducedSaveData {

	private Level world;
	private Depot depot;
	private String trainId;
	private String baseTrainType;
	private int trainCars;
	private boolean unlimitedTrains;
	private int maxTrains;
	private boolean isManual;
	private int maxManualSpeed;
	private int repeatIndex1;
	private int repeatIndex2;
	/**
	 * The rail where a train that joined a loop part of the way round reaches its first stop, or 0 if trains start from there.
	 */
	private int loopJoinIndex;
	private boolean trainsRelocated;
	private float accelerationConstant;

	public final float railLength;
	private final List<PathData> path = new ArrayList<>();
	private final List<Double> distances = new ArrayList<>();
	private final List<TimeSegment> timeSegments = new ArrayList<>();
	private final StopTimes stopTimes = new StopTimes();
	/**
	 * A field rather than a parameter of simulateTrain, which ANTE injects into by its signature.
	 */
	public float ticksElapsed = 1;
	/**
	 * The schedules of the last tick, which are complete unlike the ones still being filled in by this tick's trains.
	 */
	public Map<Long, List<ScheduleEntry>> lastSchedulesForPlatform = new HashMap<>();
	private final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	private final Set<TrainServer> trains = new HashSet<>();
	/**
	 * The paths of services leaving the usual path for another route, by {@link TripTail#getKey()}.
	 */
	private final Map<String, TripPath> tripPaths = new HashMap<>();

	private static final String KEY_RAIL_LENGTH = "rail_length";
	private static final String KEY_TRIP_PATHS = "trip_paths";
	private static final String KEY_TRIP_KEY = "trip_key";
	private static final String KEY_TRIP_ROUTE_IDS = "trip_route_ids";
	private static final String KEY_TRIP_CUT_ROUTE = "trip_cut_route";
	private static final String KEY_TRIP_CUT_STOP = "trip_cut_stop";
	private static final String KEY_TRIP_LAP_END = "trip_lap_end";
	private static final String KEY_BASE_TRAIN_TYPE = "train_type";
	private static final String KEY_TRAIN_ID = "train_custom_id";
	private static final String KEY_UNLIMITED_TRAINS = "unlimited_trains";
	private static final String KEY_MAX_TRAINS = "max_trains";
	private static final int MAX_TRAINS = 999;
	private static final int MAX_UNLIMITED_TRAINS = 4096;
	private static final String KEY_IS_MANUAL = "is_manual";
	private static final String KEY_MAX_MANUAL_SPEED = "max_manual_speed";
	private static final String KEY_PATH = "path";
	private static final String KEY_REPEAT_INDEX_1 = "repeat_index_1";
	private static final String KEY_REPEAT_INDEX_2 = "repeat_index_2";
	private static final String KEY_LOOP_JOIN_INDEX = "loop_join_index";
	private static final String KEY_TRAINS = "trains";
	private static final String KEY_ACCELERATION_CONSTANT = "acceleration_constant";

	public Siding(long id, TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(id, transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		unlimitedTrains = transportMode.continuousMovement;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
	}

	public Siding(TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		unlimitedTrains = transportMode.continuousMovement;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
	}

	public Siding(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		railLength = RailwayData.round(messagePackHelper.getFloat(KEY_RAIL_LENGTH), 3);
		setTrainDetails(messagePackHelper.getString(KEY_TRAIN_ID), messagePackHelper.getString(KEY_BASE_TRAIN_TYPE), false);
		unlimitedTrains = transportMode.continuousMovement || messagePackHelper.getBoolean(KEY_UNLIMITED_TRAINS);
		maxTrains = messagePackHelper.getInt(KEY_MAX_TRAINS);
		isManual = messagePackHelper.getBoolean(KEY_IS_MANUAL);
		maxManualSpeed = messagePackHelper.getInt(KEY_MAX_MANUAL_SPEED);
		repeatIndex1 = messagePackHelper.getInt(KEY_REPEAT_INDEX_1);
		repeatIndex2 = messagePackHelper.getInt(KEY_REPEAT_INDEX_2);
		loopJoinIndex = messagePackHelper.getInt(KEY_LOOP_JOIN_INDEX);
		final float tempAccelerationConstant = RailwayData.round(messagePackHelper.getFloat(KEY_ACCELERATION_CONSTANT, Train.ACCELERATION_DEFAULT), 3);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant <= 0 ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;

		messagePackHelper.iterateArrayValue(KEY_PATH, pathSection -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(pathSection))));

		generateTimeSegments(path, timeSegments, platformTimes, stopTimes, loopJoinIndex, repeatIndex2);

		messagePackHelper.iterateArrayValue(KEY_TRIP_PATHS, value -> {
			final TripPath tripPath = new TripPath(new MessagePackHelper(RailwayData.castMessagePackValueToSKMap(value)));
			tripPath.generateTimes(this);
			tripPaths.put(tripPath.key, tripPath);
		});

		messagePackHelper.iterateArrayValue(KEY_TRAINS, value -> trains.add(new TrainServer(id, railLength, timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, dwellTime, RailwayData.castMessagePackValueToSKMap(value))));
		generateDistances();
		// A train on a path that no longer exists has nowhere to be, and starts again from the siding
		trains.removeIf(train -> !train.restoreTripPath(tripPaths));
	}

	@Deprecated
	public Siding(CompoundTag compoundTag) {
		super(compoundTag);

		railLength = RailwayData.round(compoundTag.getFloat(KEY_RAIL_LENGTH), 3);
		setTrainDetails(compoundTag.getString(KEY_TRAIN_ID), compoundTag.getString(KEY_BASE_TRAIN_TYPE), false);
		unlimitedTrains = transportMode.continuousMovement || compoundTag.getBoolean(KEY_UNLIMITED_TRAINS);
		maxTrains = compoundTag.getInt(KEY_MAX_TRAINS);
		isManual = compoundTag.getBoolean(KEY_IS_MANUAL);
		maxManualSpeed = compoundTag.getInt(KEY_MAX_MANUAL_SPEED);
		repeatIndex1 = compoundTag.getInt(KEY_REPEAT_INDEX_1);
		repeatIndex2 = compoundTag.getInt(KEY_REPEAT_INDEX_2);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;

		final CompoundTag tagPath = compoundTag.getCompound(KEY_PATH);
		final int pathCount = tagPath.getAllKeys().size();
		for (int i = 0; i < pathCount; i++) {
			path.add(new PathData(tagPath.getCompound(KEY_PATH + i)));
		}

		generateTimeSegments(path, timeSegments, platformTimes, stopTimes, 0, 0);

		final CompoundTag tagTrains = compoundTag.getCompound(KEY_TRAINS);
		tagTrains.getAllKeys().forEach(key -> trains.add(new TrainServer(id, railLength, timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, dwellTime, tagTrains.getCompound(key))));
		generateDistances();
	}

	public Siding(FriendlyByteBuf packet) {
		super(packet);
		railLength = RailwayData.round(packet.readFloat(), 3);
		setTrainDetails(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readUtf(PACKET_STRING_READ_LENGTH), false);
		unlimitedTrains = packet.readBoolean() || transportMode.continuousMovement;
		maxTrains = packet.readInt();
		isManual = packet.readBoolean();
		maxManualSpeed = packet.readInt();
		final float tempAccelerationConstant = RailwayData.round(packet.readFloat(), 3);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant <= 0 ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		RailwayData.writeMessagePackDataset(messagePacker, trains, KEY_TRAINS);
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_RAIL_LENGTH).packFloat(railLength);
		messagePacker.packString(KEY_TRAIN_ID).packString(trainId);
		messagePacker.packString(KEY_BASE_TRAIN_TYPE).packString(baseTrainType);
		messagePacker.packString(KEY_UNLIMITED_TRAINS).packBoolean(unlimitedTrains);
		messagePacker.packString(KEY_MAX_TRAINS).packInt(maxTrains);
		messagePacker.packString(KEY_IS_MANUAL).packBoolean(isManual);
		messagePacker.packString(KEY_MAX_MANUAL_SPEED).packInt(maxManualSpeed);
		messagePacker.packString(KEY_REPEAT_INDEX_1).packInt(repeatIndex1);
		messagePacker.packString(KEY_REPEAT_INDEX_2).packInt(repeatIndex2);
		messagePacker.packString(KEY_LOOP_JOIN_INDEX).packInt(loopJoinIndex);
		messagePacker.packString(KEY_ACCELERATION_CONSTANT).packFloat(accelerationConstant);
		RailwayData.writeMessagePackDataset(messagePacker, path, KEY_PATH);
		messagePacker.packString(KEY_TRIP_PATHS).packArrayHeader(tripPaths.size());
		for (final TripPath tripPath : tripPaths.values()) {
			tripPath.toMessagePack(messagePacker);
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 14;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 1;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeFloat(railLength);
		packet.writeUtf(trainId);
		packet.writeUtf(baseTrainType);
		packet.writeBoolean(unlimitedTrains);
		packet.writeInt(maxTrains);
		packet.writeBoolean(isManual);
		packet.writeInt(maxManualSpeed);
		packet.writeFloat(accelerationConstant);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_BASE_TRAIN_TYPE:
				setTrainDetails(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readUtf(PACKET_STRING_READ_LENGTH), false);
				trains.clear();
				break;
			case KEY_UNLIMITED_TRAINS:
				name = packet.readUtf(PACKET_STRING_READ_LENGTH);
				color = packet.readInt();
				dwellTime = packet.readInt();
				dwellTime = transportMode.continuousMovement ? 1 : dwellTime;
				unlimitedTrains = packet.readBoolean() || transportMode.continuousMovement;
				maxTrains = Mth.clamp(packet.readInt(), -1, MAX_TRAINS);
				isManual = packet.readBoolean();
				maxManualSpeed = packet.readInt();
				final float newAccelerationConstant = RailwayData.round(packet.readFloat(), 3);
				accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Float.isNaN(newAccelerationConstant) ? Train.ACCELERATION_DEFAULT : Mth.clamp(newAccelerationConstant, Train.MIN_ACCELERATION, Train.MAX_ACCELERATION);
				if (packet.readBoolean()) {
					trains.clear();
				}
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	public void setTrainIdAndBaseType(String customId, String trainType, Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_BASE_TRAIN_TYPE);
		packet.writeUtf(customId);
		packet.writeUtf(trainType);
		sendPacket.accept(packet);
		setTrainDetails(customId, trainType, false);
	}

	public void setUnlimitedTrains(boolean unlimitedTrains, int maxTrains, boolean isManual, int maxManualSpeed, float accelerationConstant, int newDwellTime, boolean clearTrains, Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_UNLIMITED_TRAINS);
		packet.writeUtf(name);
		packet.writeInt(color);
		writeDwellTimePacket(packet, newDwellTime);
		packet.writeBoolean(unlimitedTrains);
		packet.writeInt(maxTrains);
		packet.writeBoolean(isManual);
		packet.writeInt(maxManualSpeed);
		final float tempAccelerationConstant = RailwayData.round(accelerationConstant, 3);
		packet.writeFloat(tempAccelerationConstant);
		packet.writeBoolean(clearTrains);
		sendPacket.accept(packet);
		this.unlimitedTrains = transportMode.continuousMovement || unlimitedTrains;
		this.maxTrains = maxTrains;
		this.isManual = isManual;
		this.maxManualSpeed = maxManualSpeed;
		this.accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant;
		if (clearTrains) {
			trains.clear();
		}
	}

	public String getTrainId() {
		return trainId;
	}

	public float getAccelerationConstant() {
		return accelerationConstant;
	}

	public void removeRider(UUID uuid) {
		trains.forEach(train -> train.ridingEntities.remove(uuid));
	}

	public void setSidingData(Level world, Depot depot, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		this.world = world;
		this.depot = depot;

		if (depot == null) {
			trains.clear();
			path.clear();
			distances.clear();
		} else {
			if (path.isEmpty()) {
				generateDefaultPath(rails);
				generateDistances();
			}
			depot.platformTimes.clear();
			depot.platformTimes.putAll(platformTimes);
		}
	}

	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed) {
		return generateRoute(minecraftServer, mainPath, successfulSegmentsMain, rails, null, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed);
	}

	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed) {
		return generateRoute(minecraftServer, mainPath, successfulSegmentsMain, rails, graph, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed, new ArrayList<>(), new ArrayList<>());
	}

	/**
	 * @param firstPlatform where trains from this siding first join the path
	 * @param loopJoinPath  on a loop joined part of the way round, the rest of the way to its first stop, which trains run once
	 *                      before the main path, or empty if they join at the first stop
	 * @param tripTails     where services leave the main path for another route
	 */
	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed, List<PathData> loopJoinPath, List<TripTail> tripTails) {
		final List<PathData> tempPath = new ArrayList<>();
		final Map<String, TripPath> tempTripPaths = new HashMap<>();
		final int successfulSegments;
		final int tempRepeatIndex1;
		final int tempRepeatIndex2;
		final int tempLoopJoinIndex;

		if (firstPlatform == null || lastPlatform == null) {
			successfulSegments = 0;
			tempRepeatIndex1 = 0;
			tempRepeatIndex2 = 0;
			tempLoopJoinIndex = 0;
		} else {
			final List<SavedRailBase> depotAndFirstPlatform = new ArrayList<>();
			depotAndFirstPlatform.add(this);
			depotAndFirstPlatform.add(firstPlatform);
			PathFinder.findPath(tempPath, rails, graph, depotAndFirstPlatform, null, 0, cruisingAltitude, useFastSpeed);

			if (tempPath.isEmpty()) {
				successfulSegments = 1;
				tempRepeatIndex1 = 0;
				tempRepeatIndex2 = 0;
				tempLoopJoinIndex = 0;
			} else if (mainPath.isEmpty()) {
				tempPath.clear();
				successfulSegments = successfulSegmentsMain + 1;
				tempRepeatIndex1 = 0;
				tempRepeatIndex2 = 0;
				tempLoopJoinIndex = 0;
			} else {
				if (!loopJoinPath.isEmpty()) {
					PathFinder.appendPath(tempPath, loopJoinPath);
				}
				tempLoopJoinIndex = loopJoinPath.isEmpty() ? 0 : tempPath.size() - 1;
				tempRepeatIndex1 = repeatInfinitely ? tempPath.size() - (tempPath.get(tempPath.size() - 1).isOppositeRail(mainPath.get(0)) ? 0 : 1) : 0;
				final List<PathData> pathBeforeMain = new ArrayList<>(tempPath);
				PathFinder.appendPath(tempPath, mainPath);

				for (final TripTail tripTail : tripTails) {
					final List<PathData> tripPath = new ArrayList<>(pathBeforeMain);
					PathFinder.appendPath(tripPath, new ArrayList<>(mainPath.subList(0, tripTail.leaveRail + 1)));
					final int lapEnd = tripPath.size() - 1;
					PathFinder.appendPath(tripPath, tripTail.path);
					final List<PathData> pathToDepot = new ArrayList<>();
					PathFinder.findPath(pathToDepot, rails, graph, Arrays.asList(tripTail.lastPlatform, this), null, 0, cruisingAltitude, useFastSpeed);
					// A way back is needed, and without it the service stays on the main path
					if (!pathToDepot.isEmpty()) {
						PathFinder.appendPath(tripPath, pathToDepot);
						final TripPath newTripPath = new TripPath(tripTail.getKey(), tripTail.routeIds, tripTail.cutRouteId, tripTail.cutStop, tempLoopJoinIndex, lapEnd, tripPath);
						newTripPath.generateTimes(this);
						tempTripPaths.put(newTripPath.key, newTripPath);
					}
				}

				final List<SavedRailBase> lastPlatformAndDepot = new ArrayList<>();
				lastPlatformAndDepot.add(lastPlatform);
				lastPlatformAndDepot.add(this);
				final List<PathData> pathLastPlatformToDepot = new ArrayList<>();
				PathFinder.findPath(pathLastPlatformToDepot, rails, graph, lastPlatformAndDepot, null, successfulSegmentsMain, cruisingAltitude, useFastSpeed);

				if (pathLastPlatformToDepot.isEmpty()) {
					successfulSegments = successfulSegmentsMain + 1;
					tempPath.clear();
					tempRepeatIndex2 = 0;
				} else {
					tempRepeatIndex2 = repeatInfinitely ? tempPath.size() - 1 : 0;
					PathFinder.appendPath(tempPath, pathLastPlatformToDepot);
					successfulSegments = successfulSegmentsMain + 2;
				}
			}
		}

		final List<TimeSegment> tempTimeSegments = new ArrayList<>();
		final Map<Long, Map<Long, Float>> tempPlatformTimes = new HashMap<>();
		final StopTimes tempStopTimes = new StopTimes();
		generateTimeSegments(tempPath, tempTimeSegments, tempPlatformTimes, tempStopTimes, tempLoopJoinIndex, tempRepeatIndex2);

		minecraftServer.execute(() -> {
			try {
				// A failed search keeps the old path and every train, and only the failure is reported
				if (tempPath.isEmpty() && firstPlatform != null && path.size() > 1) {
					return;
				}

				final List<String> oldRails = getRailKeys(path);
				final List<Double> oldDistances = new ArrayList<>(distances);

				path.clear();
				if (tempPath.isEmpty()) {
					generateDefaultPath(rails);
				} else {
					path.addAll(tempPath);
				}

				timeSegments.clear();
				timeSegments.addAll(tempTimeSegments);
				stopTimes.copyFrom(tempStopTimes);
				platformTimes.clear();
				platformTimes.putAll(tempPlatformTimes);
				generateDistances();

				repeatIndex1 = tempRepeatIndex1;
				repeatIndex2 = tempRepeatIndex2;
				loopJoinIndex = tempLoopJoinIndex;
				tripPaths.clear();
				tripPaths.putAll(tempTripPaths);
				if (!tempPath.isEmpty()) {
					relocateTrains(oldRails, oldDistances);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return successfulSegments;
	}

	private void relocateTrains(List<String> oldRails, List<Double> oldDistances) {
		final List<String> newRails = getRailKeys(path);
		final List<Double> newDistances = new ArrayList<>(distances);
		trains.removeIf(train -> {
			// These trains finish the path they are on, which stays valid until they are back
			if (train.isOnTripPath()) {
				train.setDepotRepeatIndices(repeatIndex1, repeatIndex2);
				return false;
			}
			if (!train.getIsOnRoute()) {
				train.relocate(train.getRailProgress(), 0, repeatIndex1, repeatIndex2);
				return false;
			}
			final PathRelocator.Position position = PathRelocator.locate(oldRails, oldDistances, newRails, newDistances, train.getRailProgress());
			if (position != null) {
				train.relocate(position.railProgress, position.railIndex, repeatIndex1, repeatIndex2);
			}
			return position == null;
		});
		trainsRelocated = true;
	}

	private static List<String> getRailKeys(List<PathData> path) {
		final List<String> rails = new ArrayList<>();
		path.forEach(pathData -> rails.add(pathData.startingPos.asLong() + ">" + pathData.getLastPos().asLong()));
		return rails;
	}

	public void simulateTrain(DataCache dataCache, RailwayDataDriveTrainModule railwayDataDriveTrainModule, List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks, Map<Player, Set<TrainServer>> trainsInPlayerRange, Set<TrainServer> trainsToSync, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays) {
		if (depot == null) {
			return;
		}

		if (trainsRelocated) {
			trainsToSync.addAll(trains);
			trainsRelocated = false;
		}

		int trainsAtDepot = 0;
		boolean spawnTrain = true;

		final Set<Long> railProgressSet = new HashSet<>();
		final Set<TrainServer> trainsToRemove = new HashSet<>();
		for (final TrainServer train : trains) {
			if (train.isCurrentlyManual() && railwayDataDriveTrainModule.drive(train)) {
				trainsToSync.add(train);
			}

			train.setStopTimes(stopTimes, tripPaths);
			train.lastSchedulesForPlatform = lastSchedulesForPlatform;
			if (train.simulateTrain(world, ticksElapsed, depot, dataCache, trainPositions, trainsInPlayerRange, schedulesForPlatform, trainDelays)) {
				trainsToSync.add(train);
			}

			if (train.closeToDepot(train.spacing * trainCars)) {
				spawnTrain = false;
			}

			if (!train.getIsOnRoute()) {
				trainsAtDepot++;
				if (trainsAtDepot > 1) {
					trainsToRemove.add(train);
				}
			}

			final long roundedRailProgress = Math.round(train.getRailProgress() * 10);
			if (railProgressSet.contains(roundedRailProgress)) {
				trainsToRemove.add(train);
			}
			railProgressSet.add(roundedRailProgress);

			if (trainPositions != null && !transportMode.continuousMovement) {
				train.writeTrainPositions(trainPositions, signalBlocks);
			}
		}

		if (trainCars > 0 && (trains.isEmpty() || spawnTrain && (unlimitedTrains && trains.size() < MAX_UNLIMITED_TRAINS || trains.size() <= maxTrains))) {
			final TrainServer train = new TrainServer(unlimitedTrains || maxTrains > 0 ? new Random().nextLong() : id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, timeSegments, isManual, maxManualSpeed, dwellTime);
			trains.add(train);
		}

		if (!trainsToRemove.isEmpty()) {
			trainsToRemove.forEach(trains::remove);
		}
	}

	public boolean isValidVehicle(int spacing) {
		return Math.max(2, railLength) >= spacing;
	}

	/**
	 * True when a rail of the generated path was removed or reshaped since the path was generated.
	 */
	public boolean isPathStale(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		if (transportMode == TransportMode.AIRPLANE || path.size() < 2) {
			return false;
		}
		return path.stream().anyMatch(pathData -> {
			final Rail rail = DataCache.tryGet(rails, pathData.startingPos, pathData.getLastPos());
			return rail == null || rail.railType != pathData.rail.railType || rail.facingStart != pathData.rail.facingStart || rail.facingEnd != pathData.rail.facingEnd || Math.abs(rail.getLength() - pathData.rail.getLength()) > 0.01;
		});
	}

	public StopTimes getStopTimes() {
		return stopTimes;
	}

	/**
	 * Times a path with this siding's train, for a route no depot runs on its own.
	 */
	public StopTimes calculateStopTimes(List<PathData> newPath) {
		final StopTimes newStopTimes = new StopTimes();
		generateTimeSegments(newPath, new ArrayList<>(), new HashMap<>(), newStopTimes, 0, 0);
		return newStopTimes;
	}

	public long[] getPathNodes() {
		if (path.isEmpty()) {
			return new long[0];
		}
		final long[] nodes = new long[path.size() + 1];
		for (int i = 0; i < path.size(); i++) {
			nodes[i] = path.get(i).startingPos.asLong();
		}
		nodes[path.size()] = path.get(path.size() - 1).getLastPos().asLong();
		return nodes;
	}

	public int getMaxTrains() {
		return maxTrains;
	}

	public boolean getIsManual() {
		return isManual;
	}

	public int getMaxManualSpeed() {
		return maxManualSpeed;
	}

	public boolean getUnlimitedTrains() {
		return unlimitedTrains;
	}

	public void clearTrains() {
		trains.clear();
	}

	private void setTrainDetails() {
		for (final TrainType trainType : TrainType.values()) {
			if (TrainType.getTransportMode(trainType.baseTrainType) == transportMode && isValidVehicle(TrainType.getSpacing(trainType.baseTrainType))) {
				setTrainDetails(trainType.toString(), trainType.baseTrainType, true);
				return;
			}
		}
		setTrainDetails(TrainType.values()[0].toString(), TrainType.values()[0].baseTrainType, true);
	}

	private void setTrainDetails(String trainId, String baseTrainType, boolean force) {
		// TODO temporary code for backwards compatibility
		final String baseTrainType2 = baseTrainType.startsWith("base_") ? baseTrainType.replace("base_", "train_") : baseTrainType;
		// TODO temporary code end
		final int trainSpacing = TrainType.getSpacing(baseTrainType2);
		if (force || isValidVehicle(trainSpacing)) {
			this.baseTrainType = baseTrainType2.toLowerCase(Locale.ENGLISH);
			this.trainId = trainId.isEmpty() ? this.baseTrainType : trainId.toLowerCase(Locale.ENGLISH);
			trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / trainSpacing));
		} else {
			setTrainDetails();
		}
	}

	private void generateDefaultPath(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		trains.clear();

		final List<BlockPos> orderedPositions = getOrderedPositions(RailwayData.newBlockPos(0, 0, 0), false);
		final BlockPos pos1 = orderedPositions.get(0);
		final BlockPos pos2 = orderedPositions.get(1);
		if (RailwayData.containsRail(rails, pos1, pos2)) {
			path.add(new PathData(rails.get(pos1).get(pos2), id, 0, pos1, pos2, -1));
		}

		trains.add(new TrainServer(id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, timeSegments, isManual, maxManualSpeed, dwellTime));
	}

	private void generateDistances() {
		distances.clear();

		double distanceSum = 0;
		for (final PathData pathData : path) {
			distanceSum += pathData.rail.getLength();
			distances.add(distanceSum);
		}

		if (path.size() != 1) {
			trains.removeIf(train -> (train.id == id) == unlimitedTrains);
		}
	}

	private void generateTimeSegments(List<PathData> path, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
		timeSegments.clear();
		stopTimes.clear();
		stopTimes.joinIndex = joinIndex;

		double distanceSum1 = 0;
		final List<Double> stoppingDistances = new ArrayList<>();
		for (final PathData pathData : path) {
			distanceSum1 += pathData.rail.getLength();
			if (pathData.dwellTime > 0) {
				stoppingDistances.add(distanceSum1);
			}
		}

		final int spacing = TrainType.getSpacing(baseTrainType);
		double railProgress = (railLength + trainCars * spacing) / 2;
		double nextStoppingDistance = 0;
		float speed = 0;
		float time = 0;
		float timeOld = 0;
		long savedRailBaseIdOld = 0;
		double distanceSum2 = 0;
		boolean startNewSegment = false;
		for (int i = 0; i < path.size(); i++) {
			if (railProgress >= nextStoppingDistance) {
				if (stoppingDistances.isEmpty()) {
					nextStoppingDistance = distanceSum1;
				} else {
					nextStoppingDistance = stoppingDistances.remove(0);
				}
			}

			final PathData pathData = path.get(i);
			final float railSpeed = pathData.rail.railType.canAccelerate ? pathData.rail.railType.maxBlocksPerTick : Math.max(speed, RailType.getDefaultMaxBlocksPerTick(transportMode));
			distanceSum2 += pathData.rail.getLength();

			while (railProgress < distanceSum2) {
				final int speedChange;
				if (speed > railSpeed || nextStoppingDistance - railProgress + 1 < 0.5 * speed * speed / accelerationConstant) {
					speed = Math.max(speed - accelerationConstant, accelerationConstant);
					speedChange = -1;
				} else if (speed < railSpeed) {
					speed = Math.min(speed + accelerationConstant, railSpeed);
					speedChange = 1;
				} else {
					speedChange = 0;
				}

				if (startNewSegment || timeSegments.isEmpty() || timeSegments.get(timeSegments.size() - 1).speedChange != speedChange) {
					timeSegments.add(new TimeSegment(railProgress, speed, time, speedChange, accelerationConstant));
					startNewSegment = false;
				}

				railProgress = Math.min(railProgress + speed, distanceSum2);
				time++;

				final TimeSegment timeSegment = timeSegments.get(timeSegments.size() - 1);
				timeSegment.endRailProgress = railProgress;
				timeSegment.endTime = time;
				timeSegment.pass = railProgress == distanceSum2 && isPassingPlatform(path, i);
				timeSegment.savedRailBaseId = timeSegment.pass || nextStoppingDistance != distanceSum1 && railProgress == distanceSum2 && pathData.dwellTime > 0 ? pathData.savedRailBaseId : 0;
				// The next rail would otherwise extend this segment and clear the platform
				startNewSegment = timeSegment.pass;
			}

			final boolean isPlatformStop = pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0;
			if (pathData.dwellTime > 0 || isPassingPlatform(path, i)) {
				stopTimes.add(i, isPlatformStop ? pathData.savedRailBaseId : 0, time, time + pathData.dwellTime * 10, isPlatformStop && pathData.dwellTime > 0);
			}

			time += pathData.dwellTime * 5;

			if (pathData.savedRailBaseId != 0) {
				if (savedRailBaseIdOld != 0) {
					if (!platformTimes.containsKey(savedRailBaseIdOld)) {
						platformTimes.put(savedRailBaseIdOld, new HashMap<>());
					}
					platformTimes.get(savedRailBaseIdOld).put(pathData.savedRailBaseId, time - timeOld);
				}
				savedRailBaseIdOld = pathData.savedRailBaseId;
				timeOld = time;
			}

			time += pathData.dwellTime * 5;

			if (i + 1 < path.size() && pathData.isOppositeRail(path.get(i + 1))) {
				railProgress += spacing * trainCars;
			}
		}

		stopTimes.linkJoinStops(lapEndIndex);
	}

	/**
	 * @param path  a path through these stops in order
	 * @param stops the stops of a route
	 * @param stop  which stop to find
	 * @return the rail where a train reaches that stop, or -1 if the path doesn't have it
	 */
	public static int getStopRail(List<PathData> path, List<Route.RoutePlatform> stops, int stop) {
		int nextStop = 0;
		for (int i = 0; i < path.size() && nextStop < stops.size(); i++) {
			final PathData pathData = path.get(i);
			if (pathData.savedRailBaseId == stops.get(nextStop).platformId && (pathData.dwellTime > 0 || isPassingPlatform(path, i))) {
				if (nextStop == stop) {
					return i;
				}
				nextStop++;
			}
		}
		return -1;
	}

	/**
	 * Rails after a stop or a reversal also carry the ID of that platform, so only the first rail with the ID counts.
	 */
	public static boolean isPassingPlatform(List<PathData> path, int index) {
		final PathData pathData = path.get(index);
		return index > 0 && pathData.dwellTime == 0 && pathData.savedRailBaseId != 0 && pathData.rail.railType == RailType.PLATFORM && path.get(index - 1).savedRailBaseId != pathData.savedRailBaseId;
	}

	/**
	 * A service leaving the usual path of a depot for another route. The path up to there is the same as the usual path, so a
	 * train can change over while it runs the usual path.
	 */
	public static class TripTail {

		private final List<Long> routeIds;
		private final long cutRouteId;
		private final int cutStop;
		private final int leaveRail;
		private final List<PathData> path;
		private final SavedRailBase lastPlatform;

		/**
		 * @param routeIds     the routes of the whole trip in order
		 * @param cutRouteId   the route left part of the way along, or 0
		 * @param cutStop      the stop of that route where the train leaves it
		 * @param leaveRail    the rail of the main path where the train leaves it
		 * @param path         the rest of the way to the end of the last route
		 * @param lastPlatform the last stop of the last route
		 */
		public TripTail(List<Long> routeIds, long cutRouteId, int cutStop, int leaveRail, List<PathData> path, SavedRailBase lastPlatform) {
			this.routeIds = routeIds;
			this.cutRouteId = cutRouteId;
			this.cutStop = cutStop;
			this.leaveRail = leaveRail;
			this.path = path;
			this.lastPlatform = lastPlatform;
		}

		public String getKey() {
			return getKey(routeIds.get(routeIds.size() - 1), cutStop);
		}

		public static String getKey(long routeId, int fromStop) {
			return routeId + "@" + fromStop;
		}
	}

	/**
	 * A path of a service that isn't the usual path of the depot. Trains use it from the moment they start that service.
	 */
	public static class TripPath {

		public final String key;
		public final List<Long> routeIds = new ArrayList<>();
		public final List<PathData> path = new ArrayList<>();
		public final List<Double> distances = new ArrayList<>();
		public final List<TimeSegment> timeSegments = new ArrayList<>();
		public final StopTimes stopTimes = new StopTimes();
		private final long cutRouteId;
		private final int cutStop;
		private final int joinIndex;
		private final int lapEnd;

		private TripPath(String key, List<Long> routeIds, long cutRouteId, int cutStop, int joinIndex, int lapEnd, List<PathData> path) {
			this.key = key;
			this.routeIds.addAll(routeIds);
			this.cutRouteId = cutRouteId;
			this.cutStop = cutStop;
			this.joinIndex = joinIndex;
			this.lapEnd = lapEnd;
			this.path.addAll(path);
		}

		private TripPath(MessagePackHelper messagePackHelper) {
			key = messagePackHelper.getString(KEY_TRIP_KEY);
			messagePackHelper.iterateArrayValue(KEY_TRIP_ROUTE_IDS, value -> routeIds.add(value.asIntegerValue().asLong()));
			cutRouteId = messagePackHelper.getLong(KEY_TRIP_CUT_ROUTE);
			cutStop = messagePackHelper.getInt(KEY_TRIP_CUT_STOP);
			joinIndex = messagePackHelper.getInt(KEY_LOOP_JOIN_INDEX);
			lapEnd = messagePackHelper.getInt(KEY_TRIP_LAP_END);
			messagePackHelper.iterateArrayValue(KEY_PATH, value -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
		}

		private void generateTimes(Siding siding) {
			siding.generateTimeSegments(path, timeSegments, new HashMap<>(), stopTimes, joinIndex, lapEnd);
			stopTimes.cutRouteId = cutRouteId;
			stopTimes.cutStop = cutStop;
			double distanceSum = 0;
			distances.clear();
			for (final PathData pathData : path) {
				distanceSum += pathData.rail.getLength();
				distances.add(distanceSum);
			}
		}

		private void toMessagePack(MessagePacker messagePacker) throws IOException {
			messagePacker.packMapHeader(7);
			messagePacker.packString(KEY_TRIP_KEY).packString(key);
			messagePacker.packString(KEY_TRIP_ROUTE_IDS).packArrayHeader(routeIds.size());
			for (final long routeId : routeIds) {
				messagePacker.packLong(routeId);
			}
			messagePacker.packString(KEY_TRIP_CUT_ROUTE).packLong(cutRouteId);
			messagePacker.packString(KEY_TRIP_CUT_STOP).packInt(cutStop);
			messagePacker.packString(KEY_LOOP_JOIN_INDEX).packInt(joinIndex);
			messagePacker.packString(KEY_TRIP_LAP_END).packInt(lapEnd);
			RailwayData.writeMessagePackDataset(messagePacker, path, KEY_PATH);
		}
	}

	/**
	 * When a train reaches and leaves each stop of a path, at the fastest it can run.
	 */
	public static class StopTimes {

		private int firstIndex = -1;
		private float firstArrival;
		private long plannedKey;
		/**
		 * See {@link Siding#loopJoinIndex}.
		 */
		private int joinIndex;
		private float joinArrival;
		/**
		 * A route this path leaves part of the way along, and the last stop it runs of that route.
		 */
		private long cutRouteId;
		private int cutStop;
		private final List<Stop> stops = new ArrayList<>();
		/**
		 * The stops on the way round a loop to its first stop, which are planned like the same stops of the lap.
		 */
		private final List<Stop> joinStops = new ArrayList<>();
		private final Map<Integer, Integer> joinStopLaps = new HashMap<>();
		private final Map<Long, Map<Integer, RouteTimetable.StopPlan>> plans = new HashMap<>();

		private void clear() {
			firstIndex = -1;
			firstArrival = 0;
			plannedKey = 0;
			joinIndex = 0;
			joinArrival = 0;
			stops.clear();
			joinStops.clear();
			joinStopLaps.clear();
			plans.clear();
		}

		private void copyFrom(StopTimes stopTimes) {
			clear();
			firstIndex = stopTimes.firstIndex;
			firstArrival = stopTimes.firstArrival;
			joinIndex = stopTimes.joinIndex;
			joinArrival = stopTimes.joinArrival;
			cutRouteId = stopTimes.cutRouteId;
			cutStop = stopTimes.cutStop;
			stops.addAll(stopTimes.stops);
			joinStops.addAll(stopTimes.joinStops);
			joinStopLaps.putAll(stopTimes.joinStopLaps);
		}

		private void add(int index, long platformId, float arrival, float departure, boolean isFirstStop) {
			if (index < joinIndex) {
				if (joinStops.isEmpty()) {
					joinArrival = arrival;
				}
				joinStops.add(new Stop(index, platformId, 0, 0));
				return;
			}
			if (firstIndex < 0 && isFirstStop) {
				firstIndex = index;
				firstArrival = arrival;
			}
			if (firstIndex >= 0) {
				stops.add(new Stop(index, platformId, (long) ((arrival - firstArrival) * Depot.MILLIS_PER_TICK), (long) ((departure - firstArrival) * Depot.MILLIS_PER_TICK)));
			}
		}

		/**
		 * Goes back from the end of the first lap, as the stops of a joined loop are the last ones of the lap.
		 */
		private void linkJoinStops(int lapEndIndex) {
			int lapStop = stops.size() - 1;
			while (lapStop >= 0 && stops.get(lapStop).index >= lapEndIndex) {
				lapStop--;
			}
			for (int i = joinStops.size() - 1; i >= 0; i--) {
				final Stop joinStop = joinStops.get(i);
				while (lapStop >= 0 && stops.get(lapStop).platformId != joinStop.platformId) {
					lapStop--;
				}
				if (lapStop >= 0) {
					joinStopLaps.put(joinStop.index, stops.get(lapStop).index);
					lapStop--;
				}
			}
		}

		public boolean isEmpty() {
			return firstIndex < 0;
		}

		/**
		 * @return true if trains join the loop part of the way round
		 */
		public boolean hasJoinStops() {
			return !joinStopLaps.isEmpty();
		}

		/**
		 * @return where in a lap the first stop a train reaches after joining the loop is, for the time of that stop
		 */
		public int getFirstJoinStopLapIndex() {
			return joinStops.isEmpty() ? firstIndex : joinStopLaps.getOrDefault(joinStops.get(0).index, firstIndex);
		}

		public int getFirstIndex() {
			return firstIndex;
		}

		/**
		 * @return the time needed to reach the first platform from the siding
		 */
		public long getLeadMillis() {
			return (long) ((hasJoinStops() ? joinArrival : firstArrival) * Depot.MILLIS_PER_TICK);
		}

		/**
		 * @param routes  the routes the path runs through, in order
		 * @param routeId the route the train is on
		 * @param table   the table of that route the train is running to. Other routes use their first table until the train gets to them.
		 * @return the planned times of a stop on the path, or null if the path has no such stop
		 */
		public RouteTimetable.StopPlan getPlan(List<Route> routes, RouteTimetable timetable, int index, long routeId, int table) {
			long newKey = 1;
			for (final Route route : routes) {
				newKey = newKey * 31 + route.id + route.timetable.revision;
			}
			if (newKey != plannedKey) {
				plannedKey = newKey;
				plans.clear();
			}
			return plans.computeIfAbsent(routeId * 31 + table, key -> makePlans(routes, timetable, routeId, table)).get(joinStopLaps.getOrDefault(index, index));
		}

		private Map<Integer, RouteTimetable.StopPlan> makePlans(List<Route> routes, RouteTimetable timetable, long routeId, int table) {
			final Map<Integer, RouteTimetable.StopPlan> newPlans = new HashMap<>();
			final List<RouteTimetable.StopPlan> stopPlans = new ArrayList<>();
			final List<Boolean> isTurnBacks = new ArrayList<>();
			matchRouteStops(routes, (stop, routeStop) -> {
				final Route.RoutePlatform arrivalStop = routeStop == null ? null : routeStop.arrivalStop;
				final Route.RoutePlatform departureStop = routeStop == null ? null : routeStop.departureStop;
				final int arrivalTable = routeStop != null && routeStop.arrivalRoute.id == routeId ? table : 0;
				final int departureTable = routeStop != null && routeStop.departureRoute.id == routeId ? table : 0;
				final RouteTimetable.StopPlan stopPlan = new RouteTimetable.StopPlan(stop.arrivalMillis, stop.departureMillis, arrivalStop == null ? -1 : arrivalStop.getArrival(arrivalTable), departureStop == null ? -1 : departureStop.getDeparture(departureTable), departureStop != null && departureStop.getOvertake(departureTable));
				stopPlan.startsRoute = routeStop != null && routeStop.startsRoute;
				stopPlan.route = stopPlan.startsRoute ? routeStop.departureRoute : null;
				stopPlan.waitsForService = stopPlan.startsRoute && routeStop.departureRoute.timetable.isActive();
				stopPlans.add(stopPlan);
				isTurnBacks.add(stop.platformId == 0);
				newPlans.put(stop.index, stopPlan);
			});
			final Route currentRoute = routes.stream().filter(route -> route.id == routeId).findFirst().orElse(null);
			// The extra time of the route the train is on, which is what the running sheet of that route shows
			(currentRoute == null ? timetable : currentRoute.timetable.getTable(table)).applyPlan(stopPlans);
			RouteTimetable.linkTurnBacks(stopPlans, isTurnBacks);
			return newPlans;
		}

		/**
		 * @param routes the routes this path runs through, in order
		 * @return the fastest arrival and departure at each stop of the route counted from reaching its first stop, or -1 for stops not found
		 */
		public long[][] getFastestTimes(Route route, List<Route> routes) {
			final int stopCount = route.platformIds.size();
			final long[][] times = new long[stopCount][];
			for (int i = 0; i < stopCount; i++) {
				times[i] = new long[]{-1, -1};
			}
			matchRouteStops(routes, (stop, routeStop) -> {
				if (routeStop != null && routeStop.departureRoute == route && times[routeStop.departureIndex][0] < 0) {
					times[routeStop.departureIndex] = new long[]{stop.arrivalMillis, stop.departureMillis};
				} else if (routeStop != null && routeStop.arrivalRoute == route && times[routeStop.arrivalIndex][0] < 0) {
					times[routeStop.arrivalIndex] = new long[]{stop.arrivalMillis, stop.arrivalMillis};
				}
			});

			long start = -1;
			for (final long[] stopTime : times) {
				if (stopTime[0] >= 0) {
					start = start < 0 ? stopTime[0] : start;
					stopTime[0] -= start;
					stopTime[1] -= start;
				}
			}
			return times;
		}

		/**
		 * Goes through the stops of the path, finding each one in the routes by platform in order. A platform where one
		 * route ends and the next one starts is one stop on the path, and gets its arrival and departure from different routes.
		 */
		private void matchRouteStops(List<Route> routes, BiConsumer<Stop, RouteStop> consumer) {
			final List<RouteStop> routeStops = new ArrayList<>();
			Route lastRoute = null;
			for (final Route route : routes) {
				final int stopCount = route.id == cutRouteId ? Math.min(cutStop + 1, route.platformIds.size()) : route.platformIds.size();
				for (int i = 0; i < stopCount; i++) {
					final Route.RoutePlatform routePlatform = route.platformIds.get(i);
					final RouteStop lastStop = routeStops.isEmpty() ? null : routeStops.get(routeStops.size() - 1);
					if (i == 0 && lastStop != null && lastStop.arrivalRoute == lastRoute && lastStop.platformId == routePlatform.platformId) {
						routeStops.set(routeStops.size() - 1, new RouteStop(lastStop.arrivalRoute, lastStop.arrivalIndex, route, 0, true));
					} else {
						routeStops.add(new RouteStop(route, i, route, i, i == 0 && lastRoute != null));
					}
				}
				lastRoute = route;
			}

			int nextRouteStop = 0;
			for (final Stop stop : stops) {
				RouteStop matchedStop = null;
				for (int i = nextRouteStop; stop.platformId != 0 && i < routeStops.size(); i++) {
					if (routeStops.get(i).platformId == stop.platformId) {
						matchedStop = routeStops.get(i);
						nextRouteStop = i + 1;
						break;
					}
				}
				consumer.accept(stop, matchedStop);
			}
		}

		private static class Stop {

			private final int index;
			private final long platformId;
			private final long arrivalMillis;
			private final long departureMillis;

			private Stop(int index, long platformId, long arrivalMillis, long departureMillis) {
				this.index = index;
				this.platformId = platformId;
				this.arrivalMillis = arrivalMillis;
				this.departureMillis = departureMillis;
			}
		}

		private static class RouteStop {

			private final long platformId;
			private final Route arrivalRoute;
			private final int arrivalIndex;
			private final Route departureRoute;
			private final int departureIndex;
			private final Route.RoutePlatform arrivalStop;
			private final Route.RoutePlatform departureStop;
			private final boolean startsRoute;

			private RouteStop(Route arrivalRoute, int arrivalIndex, Route departureRoute, int departureIndex, boolean startsRoute) {
				this.arrivalRoute = arrivalRoute;
				this.arrivalIndex = arrivalIndex;
				this.departureRoute = departureRoute;
				this.departureIndex = departureIndex;
				this.startsRoute = startsRoute;
				arrivalStop = arrivalRoute.platformIds.get(arrivalIndex);
				departureStop = departureRoute.platformIds.get(departureIndex);
				platformId = arrivalStop.platformId;
			}
		}
	}

	public static class TimeSegment {

		public double endRailProgress;
		public long savedRailBaseId;
		public boolean pass;
		public long routeId;
		public int currentStationIndex;
		public float endTime;

		public final double startRailProgress;
		private final float startSpeed;
		private final float startTime;
		private final int speedChange;
		private final float accelerationConstant;

		private TimeSegment(double startRailProgress, float startSpeed, float startTime, int speedChange, float accelerationConstant) {
			this.startRailProgress = startRailProgress;
			this.startSpeed = startSpeed;
			this.startTime = startTime;
			this.speedChange = Integer.compare(speedChange, 0);
			final float tempAccelerationConstant = RailwayData.round(accelerationConstant, 3);
			this.accelerationConstant = tempAccelerationConstant <= 0 ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;
		}

		public double getTime(double railProgress) {
			final double distance = railProgress - startRailProgress;
			if (speedChange == 0) {
				return startTime + distance / startSpeed;
			} else {
				final float acceleration = speedChange * accelerationConstant;
				return startTime + (distance == 0 ? 0 : (Math.sqrt(2 * acceleration * distance + startSpeed * startSpeed) - startSpeed) / acceleration);
			}
		}
	}
}
