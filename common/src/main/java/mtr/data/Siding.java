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
	private int ticksSinceLoad;
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
	private TurnBacks turnBacks = TurnBacks.NONE;

	private static final String KEY_RAIL_LENGTH = "rail_length";
	private static final String KEY_TRIP_PATHS = "trip_paths";
	private static final String KEY_TRIP_KEY = "trip_key";
	private static final String KEY_TRIP_ROUTE_IDS = "trip_route_ids";
	private static final String KEY_TRIP_CUT_ROUTE = "trip_cut_route";
	private static final String KEY_TRIP_CUT_STOP = "trip_cut_stop";
	private static final String KEY_TRIP_LAP_END = "trip_lap_end";
	private static final String KEY_TRIP_CUTS = "trip_cuts";
	private static final String KEY_TRIP_REPEAT_1 = "trip_repeat_1";
	private static final String KEY_TRIP_REPEAT_2 = "trip_repeat_2";
	private static final String KEY_TRIP_LAP_STOP = "trip_lap_stop";
	private static final String KEY_TRIP_ENTRY_RAIL = "trip_entry_rail";
	private static final String KEY_TRIP_BASE = "trip_base";
	private static final String KEY_TRIP_TURN_BACK = "trip_turn_back";
	private static final String KEY_TRIP_TURN_BACK_STOPS = "trip_turn_back_stops";
	private static final String KEY_TRIP_CHANGE_STOP = "trip_change_stop";
	private static final String KEY_TURN_BACK_POINTS = "turn_back_points";
	private static final String KEY_TURN_BACK_STOPS = "turn_back_stops";
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
		turnBacks = TurnBacks.read(messagePackHelper);

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
		turnBacks.write(messagePacker);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 16;
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
		return generateRoute(minecraftServer, mainPath, successfulSegmentsMain, rails, graph, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, new ArrayList<>(), new ArrayList<>());
	}

	/**
	 * @param turnBackSections where the main path can turn back on more than one rail, with its stops numbered
	 * @param depotReturns     for trains staying on the line, where they can go back to the depot after a route
	 */
	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed, List<PathData> loopJoinPath, List<TripTail> tripTails, List<TurnBackSection> turnBackSections, List<DepotReturn> depotReturns) {
		final List<PathData> tempPath = new ArrayList<>();
		final Map<String, TripPath> tempTripPaths = new HashMap<>();
		final int successfulSegments;
		final int tempRepeatIndex1;
		final int tempRepeatIndex2;
		final int tempLoopJoinIndex;
		TurnBacks tempTurnBacks = TurnBacks.NONE;

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
				// Rails of the main path are this far along the whole path, which starts the same way for every service
				final int mainOffset = tempPath.size() - mainPath.size();

				final Map<String, List<PathData>> tripPathsToDepot = new HashMap<>();
				for (final TripTail tripTail : tripTails) {
					final List<PathData> pathToDepot = new ArrayList<>();
					PathFinder.findPath(pathToDepot, rails, graph, Arrays.asList(tripTail.lastPlatform, this), null, 0, cruisingAltitude, useFastSpeed);
					// A way back is needed, and without it the service stays on the main path
					if (!pathToDepot.isEmpty()) {
						tripPathsToDepot.put(tripTail.key, pathToDepot);
						addTripPath(tempTripPaths, pathBeforeMain, mainPath, tripTail, tripTail.leaveRail, pathToDepot, tempLoopJoinIndex, tripTail.key, -1, -1, getTurnBackStops(turnBackSections, mainOffset, tripTail.leaveRail, -1, 0));
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
					if (!turnBackSections.isEmpty()) {
						addTurnBackPaths(tempTripPaths, pathBeforeMain, mainPath, pathLastPlatformToDepot, mainOffset, turnBackSections, tripTails, tripPathsToDepot, tempLoopJoinIndex, tempRepeatIndex1, tempRepeatIndex2);
						tempTurnBacks = TurnBacks.fromSections(turnBackSections, getTurnBackStops(turnBackSections, mainOffset, Integer.MAX_VALUE, -1, 0));
					}
					addDepotReturnPaths(tempTripPaths, pathBeforeMain, mainPath, mainOffset, turnBackSections, depotReturns, tempLoopJoinIndex, rails, graph, cruisingAltitude, useFastSpeed);
				}
			}
		}

		final List<TimeSegment> tempTimeSegments = new ArrayList<>();
		final Map<Long, Map<Long, Float>> tempPlatformTimes = new HashMap<>();
		final StopTimes tempStopTimes = new StopTimes();
		generateTimeSegments(tempPath, tempTimeSegments, tempPlatformTimes, tempStopTimes, tempLoopJoinIndex, tempRepeatIndex2);
		final TurnBacks newTurnBacks = tempTurnBacks;

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
				turnBacks = newTurnBacks;
				if (!tempPath.isEmpty()) {
					relocateTrains(oldRails, oldDistances);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return successfulSegments;
	}

	private void addTripPath(Map<String, TripPath> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, TripTail tripTail, int leaveRail, List<PathData> pathToDepot, int joinIndex, String key, int turnBackPoint, int turnBackWay, int[] turnBackStops) {
		final List<PathData> tripPath = new ArrayList<>(pathBeforeMain);
		PathFinder.appendPath(tripPath, new ArrayList<>(mainPath.subList(0, leaveRail + 1)));
		final int lapEnd = tripPath.size() - 1;
		final int tailStart = tripPath.size() - (tripPath.get(lapEnd).isSameRail(tripTail.path.get(0)) ? 1 : 0);
		PathFinder.appendPath(tripPath, tripTail.path);
		final int repeatEnd = tripPath.size() - 1;
		PathFinder.appendPath(tripPath, pathToDepot);
		final TripPath newTripPath = new TripPath(key, turnBackPoint, turnBackWay, turnBackStops, tripTail, joinIndex, lapEnd, tailStart, repeatEnd, tripPath);
		newTripPath.generateTimes(this);
		tempTripPaths.put(key, newTripPath);
	}

	/**
	 * Each way round a turn back point that the main path doesn't take gets a path of its own, for the usual path and for
	 * every service running through to another route after the point. Only the rails of that way round are different, so a
	 * train can change onto one of these paths at the stop before the point.
	 */
	private void addTurnBackPaths(Map<String, TripPath> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, List<PathData> pathToDepot, int mainOffset, List<TurnBackSection> turnBackSections, List<TripTail> tripTails, Map<String, List<PathData>> tripPathsToDepot, int joinIndex, int newRepeatIndex1, int newRepeatIndex2) {
		for (int point = 0; point < turnBackSections.size(); point++) {
			final TurnBackSection section = turnBackSections.get(point);
			for (int way = 0; way < section.ways.size(); way++) {
				if (way != section.defaultWay && section.ways.get(way) != null) {
					final List<PathData> newMainPath = section.getMainPath(mainPath, way);
					final int extraRails = section.ways.get(way).size() - section.getLength();
					final List<PathData> newPath = new ArrayList<>(pathBeforeMain);
					PathFinder.appendPath(newPath, newMainPath);
					PathFinder.appendPath(newPath, pathToDepot);
					final String key = TripPath.getTurnBackKey("", point, way);
					final TripPath turnBackPath = new TripPath(key, point, way, getTurnBackStops(turnBackSections, mainOffset, Integer.MAX_VALUE, point, extraRails), joinIndex, newRepeatIndex1, newRepeatIndex2 == 0 ? 0 : newRepeatIndex2 + extraRails, -1, newPath);
					turnBackPath.generateTimes(this);
					tempTripPaths.put(key, turnBackPath);

					for (final TripTail tripTail : tripTails) {
						final List<PathData> tripPathToDepot = tripPathsToDepot.get(tripTail.key);
						if (tripPathToDepot != null && tripTail.leaveRail >= section.getEnd()) {
							addTripPath(tempTripPaths, pathBeforeMain, newMainPath, tripTail, tripTail.leaveRail + extraRails, tripPathToDepot, joinIndex, TripPath.getTurnBackKey(tripTail.key, point, way), point, way, getTurnBackStops(turnBackSections, mainOffset, tripTail.leaveRail, point, extraRails));
						}
					}
				}
			}
		}
	}

	/**
	 * A train staying on the line goes back to the depot after a service marked for it. It turns back after the last stop of
	 * that route as it always does, and heads for the depot from there instead of the next route.
	 */
	private void addDepotReturnPaths(Map<String, TripPath> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, int mainOffset, List<TurnBackSection> turnBackSections, List<DepotReturn> depotReturns, int joinIndex, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed) {
		for (final DepotReturn depotReturn : depotReturns) {
			final BlockPos turnBackNode = depotReturn.getTurnBackNode();
			final List<PathData> wayToDepot = new ArrayList<>();
			PathFinder.findPath(wayToDepot, rails, graph, Arrays.asList(depotReturn.lastPlatform, this), turnBackNode == null ? null : Collections.singletonList(Collections.singletonList(turnBackNode)), mainPath.get(depotReturn.stopRail).stopIndex, cruisingAltitude, useFastSpeed);
			if (!wayToDepot.isEmpty()) {
				final List<PathData> newPath = new ArrayList<>(pathBeforeMain);
				PathFinder.appendPath(newPath, new ArrayList<>(mainPath.subList(0, depotReturn.stopRail + 1)));
				final int changeStop = newPath.size() - 1;
				PathFinder.appendPath(newPath, wayToDepot);
				final String key = TripPath.getDepotReturnKey(depotReturn.routeIndex);
				final TripPath returnPath = new TripPath(key, -1, -1, getTurnBackStops(turnBackSections, mainOffset, depotReturn.stopRail, -1, 0), joinIndex, 0, 0, changeStop, newPath);
				returnPath.generateTimes(this);
				tempTripPaths.put(key, returnPath);
			}
		}
	}

	/**
	 * @param leaveRail     where the path leaves the main path, as a rail of the main path
	 * @param changedPoint  the point where the path takes another way round, or -1
	 * @param extraRails    how many more rails that way round has
	 * @return the rail of the stop before each turn back point on the path, or -1 for points it doesn't pass
	 */
	private static int[] getTurnBackStops(List<TurnBackSection> turnBackSections, int mainOffset, int leaveRail, int changedPoint, int extraRails) {
		final int[] stops = new int[turnBackSections.size()];
		for (int i = 0; i < turnBackSections.size(); i++) {
			final TurnBackSection section = turnBackSections.get(i);
			stops[i] = section.getEnd() <= leaveRail ? mainOffset + section.stopRail + (changedPoint >= 0 && i > changedPoint ? extraRails : 0) : -1;
		}
		return stops;
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

		// Train positions and the routes of the depot are known once every siding has run for a tick
		if (ticksSinceLoad < 2 && ++ticksSinceLoad == 2) {
			trains.stream().filter(TrainServer::needsAlignment).sorted(Comparator.comparingDouble(train -> -train.getRailProgress())).forEach(train -> train.alignToTimetable(depot, dataCache, trainPositions));
			trainsToSync.addAll(trains);
		}

		int trainsAtDepot = 0;
		boolean spawnTrain = true;

		final Set<Long> railProgressSet = new HashSet<>();
		final Set<TrainServer> trainsToRemove = new HashSet<>();
		for (final TrainServer train : trains) {
			if (train.isCurrentlyManual() && railwayDataDriveTrainModule.drive(train)) {
				trainsToSync.add(train);
			}

			train.setStopTimes(stopTimes, tripPaths, turnBacks);
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
	 * @return the turn back rails a train can use instead of this one of the usual path, in the order trains try them
	 */
	public List<UUID> getTurnBackCandidates(UUID rail) {
		return turnBacks.getCandidates(rail);
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
				final boolean isTurnBack = !isPlatformStop && i + 1 < path.size() && pathData.isOppositeRail(path.get(i + 1));
				stopTimes.add(i, isPlatformStop ? pathData.savedRailBaseId : 0, isTurnBack ? pathData.getRailProduct() : null, time, time + pathData.dwellTime * 10, isPlatformStop && pathData.dwellTime > 0);
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

		public static final int NO_RAIL = Integer.MIN_VALUE;

		private final String key;
		private final List<Long> routeIds;
		private final Map<Long, Integer> cutStops;
		private final int leaveRail;
		private final List<PathData> path;
		private final SavedRailBase lastPlatform;
		private final int lapStart;
		private final boolean isLapReversed;
		private final int entryRail;

		/**
		 * @param key           the key of this way through, see {@link #getKey(List)}
		 * @param routeIds      the routes of the whole trip in order
		 * @param cutStops      for routes left part of the way along, the stop where the train leaves them
		 * @param leaveRail     the rail of the main path where the train leaves it
		 * @param path          the rest of the way to the end of the last route
		 * @param lastPlatform  the last stop of the last route
		 * @param lapStart      for a loop at the end, the rail of {@code path} where its first stop is, or {@link #NO_RAIL}
		 * @param isLapReversed true if a train at the end of a lap goes back along the rail it came in on
		 * @param entryRail     the rail of {@code path} where a train joins that loop part of the way round, or {@link #NO_RAIL}
		 */
		public TripTail(String key, List<Long> routeIds, Map<Long, Integer> cutStops, int leaveRail, List<PathData> path, SavedRailBase lastPlatform, int lapStart, boolean isLapReversed, int entryRail) {
			this.key = key;
			this.routeIds = routeIds;
			this.cutStops = cutStops;
			this.leaveRail = leaveRail;
			this.path = path;
			this.lastPlatform = lastPlatform;
			this.lapStart = lapStart;
			this.isLapReversed = isLapReversed;
			this.entryRail = entryRail;
		}

		/**
		 * @return a key for the way a train goes with these links, which doesn't depend on the times of the services
		 */
		public static String getKey(List<RouteTimetable.ServiceLink> links) {
			final StringBuilder key = new StringBuilder();
			for (final RouteTimetable.ServiceLink link : links) {
				key.append(key.length() == 0 ? "" : ">").append(link.routeId).append("@").append(link.fromStop);
				if (link.toStop > 0) {
					key.append("+").append(link.toStop);
				}
			}
			return key.toString();
		}
	}

	/**
	 * The way from the last stop of a route on the main path to the first stop of the next route, while paths are generated,
	 * with a way round each turn back rail the route lists after its last stop.
	 */
	public static class TurnBackSection {

		private final int start;
		private final List<List<PathData>> ways;
		private final int defaultWay;
		private int stopRail;

		/**
		 * @param start      the rail of the main path where the way from the stop starts
		 * @param ways       for each turn back rail in order, the way from the stop to the next one, or null if it can't be used
		 * @param defaultWay the way the main path takes
		 */
		public TurnBackSection(int start, List<List<PathData>> ways, int defaultWay) {
			this.start = start;
			this.ways = ways;
			this.defaultWay = defaultWay;
		}

		public List<PathData> getDefaultWay() {
			return ways.get(defaultWay);
		}

		/**
		 * Numbers the stops of the other ways round like the main path, which must be numbered already.
		 */
		public void numberStops(List<PathData> mainPath) {
			stopRail = Math.max(start - 1, 0);
			while (stopRail > 0 && mainPath.get(stopRail).dwellTime == 0) {
				stopRail--;
			}
			for (int i = 0; i < ways.size(); i++) {
				final List<PathData> way = ways.get(i);
				if (way != null && i != defaultWay) {
					PathData previous = start > 0 ? mainPath.get(start - 1) : way.get(0);
					int stopIndex = previous.stopIndex;
					for (final PathData pathData : way) {
						if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && previous.rail.railType != RailType.PLATFORM) {
							stopIndex++;
						}
						pathData.stopIndex = stopIndex;
						previous = pathData;
					}
				}
			}
		}

		/**
		 * The way that ends a lap ends at the first stop, so its last rail counts as that stop.
		 *
		 * @param mainSize the number of rails of the main path
		 */
		public void setEndStopIndex(int mainSize, int stopIndex) {
			if (getEnd() == mainSize - 1) {
				ways.forEach(way -> {
					if (way != null) {
						way.get(way.size() - 1).stopIndex = stopIndex;
					}
				});
			}
		}

		private int getLength() {
			return getDefaultWay().size();
		}

		/**
		 * @return the last rail of this section on the main path
		 */
		private int getEnd() {
			return start + getLength() - 1;
		}

		private List<PathData> getMainPath(List<PathData> mainPath, int way) {
			final List<PathData> newMainPath = new ArrayList<>(mainPath.subList(0, start));
			newMainPath.addAll(ways.get(way));
			newMainPath.addAll(mainPath.subList(start + getLength(), mainPath.size()));
			return newMainPath;
		}
	}

	/**
	 * The way from the last stop of a route on the main path to the next route, while paths are generated. A train going back
	 * to the depot after this route turns back on it as usual, and heads for the depot from there.
	 */
	public static class DepotReturn {

		private final int routeIndex;
		private final int start;
		private final List<PathData> way;
		private final SavedRailBase lastPlatform;
		private int stopRail;

		/**
		 * @param routeIndex   the route of the depot this is after
		 * @param start        the rail of the main path where the way starts
		 * @param way          the way the main path takes from the last stop of the route
		 * @param lastPlatform the last stop of the route
		 */
		public DepotReturn(int routeIndex, int start, List<PathData> way, SavedRailBase lastPlatform) {
			this.routeIndex = routeIndex;
			this.start = start;
			this.way = way;
			this.lastPlatform = lastPlatform;
		}

		public void findStop(List<PathData> mainPath) {
			stopRail = Math.max(start - 1, 0);
			while (stopRail > 0 && mainPath.get(stopRail).dwellTime == 0) {
				stopRail--;
			}
		}

		/**
		 * @return the node where a train on this way turns back, or null if it doesn't
		 */
		private BlockPos getTurnBackNode() {
			for (int i = 1; i < way.size() - 1; i++) {
				if (way.get(i).isOppositeRail(way.get(i + 1))) {
					return way.get(i).getLastPos();
				}
			}
			return null;
		}
	}

	/**
	 * Where trains of a siding can turn back on more than one rail after the last stop of a route. At each of these points
	 * a train takes the first turn back rail that is free as it leaves the stop. The paths of the ways round that the usual
	 * path doesn't take are kept with the paths of services, by {@link TripPath#getTurnBackKey}.
	 */
	public static class TurnBacks {

		public static final TurnBacks NONE = new TurnBacks(new ArrayList<>(), new int[0]);

		private final List<TurnBackPoint> points;
		/**
		 * The rail of the stop before each point on the usual path.
		 */
		private final int[] depotStops;

		private TurnBacks(List<TurnBackPoint> points, int[] depotStops) {
			this.points = points;
			this.depotStops = depotStops;
		}

		private static TurnBacks fromSections(List<TurnBackSection> turnBackSections, int[] depotStops) {
			final List<TurnBackPoint> points = new ArrayList<>();
			turnBackSections.forEach(section -> points.add(new TurnBackPoint(section)));
			return new TurnBacks(points, depotStops);
		}

		/**
		 * Turn back points worked out elsewhere, for running services without a world.
		 */
		static TurnBacks of(int[] depotStops, TurnBackPoint... points) {
			return new TurnBacks(Arrays.asList(points), depotStops);
		}

		private static TurnBacks read(MessagePackHelper messagePackHelper) {
			final List<TurnBackPoint> points = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_TURN_BACK_POINTS, value -> {
				final List<Long> values = new ArrayList<>();
				value.asArrayValue().forEach(pointValue -> values.add(pointValue.asIntegerValue().asLong()));
				points.add(TurnBackPoint.read(values));
			});
			final List<Integer> stops = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_TURN_BACK_STOPS, value -> stops.add(value.asIntegerValue().asInt()));
			return points.size() == stops.size() && !points.isEmpty() ? new TurnBacks(points, stops.stream().mapToInt(Integer::intValue).toArray()) : NONE;
		}

		private void write(MessagePacker messagePacker) throws IOException {
			messagePacker.packString(KEY_TURN_BACK_POINTS).packArrayHeader(points.size());
			for (final TurnBackPoint point : points) {
				final List<Long> values = point.write();
				messagePacker.packArrayHeader(values.size());
				for (final long value : values) {
					messagePacker.packLong(value);
				}
			}
			messagePacker.packString(KEY_TURN_BACK_STOPS).packArrayHeader(depotStops.length);
			for (final int stop : depotStops) {
				messagePacker.packInt(stop);
			}
		}

		/**
		 * @param stops the stops of the path the train is on, or null for the usual path
		 * @return the point after this stop, or -1 if there is none
		 */
		public int getPoint(int[] stops, int stopIndex) {
			final int[] pathStops = stops == null ? depotStops : stops;
			for (int i = 0; i < pathStops.length && i < points.size(); i++) {
				if (pathStops[i] == stopIndex) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * @param stops the stops of a path, or null for the usual path
		 * @return the rail of the stop before this point on that path, or -1 if the path doesn't pass it
		 */
		public int getStop(int[] stops, int point) {
			final int[] pathStops = stops == null ? depotStops : stops;
			return point < pathStops.length ? pathStops[point] : -1;
		}

		public TurnBackPoint get(int point) {
			return points.get(point);
		}

		private List<UUID> getCandidates(UUID rail) {
			for (final TurnBackPoint point : points) {
				final List<UUID> defaultRails = point.rails.get(point.defaultWay);
				if (defaultRails != null && defaultRails.contains(rail)) {
					final List<UUID> candidates = new ArrayList<>();
					point.rails.forEach(rails -> {
						if (rails != null && !rails.isEmpty()) {
							candidates.add(rails.get(0));
						}
					});
					return candidates;
				}
			}
			return Collections.singletonList(rail);
		}
	}

	public static class TurnBackPoint {

		/**
		 * For each way round in order, the rails where a train turns back, or null if it can't be used.
		 */
		private final List<List<UUID>> rails;
		/**
		 * For each way round, the number of rails from the stop to the first stop of the next route.
		 */
		private final int[] lengths;
		public final int defaultWay;

		/**
		 * @param rails   for each way round in order, the rails where a train turns back, or null if it can't be used
		 * @param lengths for each way round, the number of rails from the stop to the first stop of the next route
		 */
		TurnBackPoint(List<List<UUID>> rails, int[] lengths, int defaultWay) {
			this.rails = rails;
			this.lengths = lengths;
			this.defaultWay = defaultWay;
		}

		private TurnBackPoint(TurnBackSection section) {
			rails = new ArrayList<>();
			lengths = new int[section.ways.size()];
			for (int i = 0; i < section.ways.size(); i++) {
				final List<PathData> way = section.ways.get(i);
				if (way == null) {
					rails.add(null);
				} else {
					final List<UUID> wayRails = new ArrayList<>();
					for (int j = 1; j < way.size() - 1; j++) {
						if (way.get(j).isOppositeRail(way.get(j + 1))) {
							wayRails.add(way.get(j).getRailProduct());
						}
					}
					// A way round without turning back, like a loop, is kept clear as a whole
					if (wayRails.isEmpty()) {
						for (int j = 1; j < way.size() - 1; j++) {
							wayRails.add(way.get(j).getRailProduct());
						}
					}
					rails.add(wayRails);
					lengths[i] = section.start - section.stopRail + way.size() - 1;
				}
			}
			defaultWay = section.defaultWay;
		}

		private static TurnBackPoint read(List<Long> values) {
			final int defaultWay = values.get(0).intValue();
			final int wayCount = values.get(1).intValue();
			final List<List<UUID>> rails = new ArrayList<>();
			final int[] lengths = new int[wayCount];
			int index = 2;
			for (int i = 0; i < wayCount; i++) {
				lengths[i] = values.get(index++).intValue();
				final int railCount = values.get(index++).intValue();
				if (railCount < 0) {
					rails.add(null);
				} else {
					final List<UUID> wayRails = new ArrayList<>();
					for (int j = 0; j < railCount; j++) {
						wayRails.add(new UUID(values.get(index++), values.get(index++)));
					}
					rails.add(wayRails);
				}
			}
			return new TurnBackPoint(rails, lengths, defaultWay);
		}

		private List<Long> write() {
			final List<Long> values = new ArrayList<>();
			values.add((long) defaultWay);
			values.add((long) rails.size());
			for (int i = 0; i < rails.size(); i++) {
				final List<UUID> wayRails = rails.get(i);
				values.add((long) lengths[i]);
				values.add(wayRails == null ? -1L : wayRails.size());
				if (wayRails != null) {
					wayRails.forEach(rail -> {
						values.add(rail.getMostSignificantBits());
						values.add(rail.getLeastSignificantBits());
					});
				}
			}
			return values;
		}

		public int getWayCount() {
			return rails.size();
		}

		/**
		 * @return the rails where a train taking this way round turns back, or null if it can't be used
		 */
		public List<UUID> getRails(int way) {
			return rails.get(way);
		}

		/**
		 * @return the number of rails from the stop to the first stop of the next route this way round
		 */
		public int getLength(int way) {
			return lengths[way];
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
		/**
		 * The rails a train laps on when the trip ends with a loop, or 0.
		 */
		public final int repeatIndex1;
		public final int repeatIndex2;
		/**
		 * The rail of the first stop of that loop.
		 */
		public final int lapStop;
		/**
		 * The rail where the train joins that loop part of the way round, or -1 if it joins at the first stop.
		 */
		public final int entryRail;
		/**
		 * The key of the service running through to another route, or empty for the usual path of the depot.
		 */
		public final String tripKey;
		/**
		 * The rail of the stop before each {@link TurnBacks} point, or -1 for a point this path doesn't pass.
		 */
		public final int[] turnBackStops;
		/**
		 * The point where this path takes another way round than the usual one, and which way, or -1.
		 */
		public final int turnBackPoint;
		public final int turnBackWay;
		/**
		 * For a way back to the depot, the rail of the stop where trains change onto it, or -1.
		 */
		public final int changeStop;
		private final Map<Long, Integer> cutStops = new HashMap<>();
		private final int joinIndex;
		private final int lapEnd;

		private TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, TripTail tripTail, int joinIndex, int lapEnd, int tailStart, int repeatEnd, List<PathData> path) {
			this.key = key;
			tripKey = tripTail.key;
			this.turnBackStops = turnBackStops;
			this.turnBackPoint = turnBackPoint;
			this.turnBackWay = turnBackWay;
			changeStop = -1;
			routeIds.addAll(tripTail.routeIds);
			cutStops.putAll(tripTail.cutStops);
			this.joinIndex = joinIndex;
			this.lapEnd = lapEnd;
			this.path.addAll(path);
			final boolean hasLap = tripTail.lapStart != TripTail.NO_RAIL;
			lapStop = hasLap ? tripTail.lapStart + tailStart : 0;
			repeatIndex1 = hasLap ? lapStop + (tripTail.isLapReversed ? 1 : 0) : 0;
			repeatIndex2 = hasLap ? repeatEnd : 0;
			entryRail = tripTail.entryRail == TripTail.NO_RAIL ? -1 : tripTail.entryRail + tailStart;
		}

		/**
		 * The usual path of the depot with another way round a turn back point.
		 */
		private TripPath(String key, int turnBackPoint, int turnBackWay, int[] turnBackStops, int joinIndex, int repeatIndex1, int repeatIndex2, int changeStop, List<PathData> path) {
			this.key = key;
			tripKey = "";
			this.turnBackStops = turnBackStops;
			this.turnBackPoint = turnBackPoint;
			this.turnBackWay = turnBackWay;
			this.changeStop = changeStop;
			this.joinIndex = joinIndex;
			lapEnd = repeatIndex2;
			this.path.addAll(path);
			this.repeatIndex1 = repeatIndex1;
			this.repeatIndex2 = repeatIndex2;
			lapStop = 0;
			entryRail = -1;
		}

		private TripPath(MessagePackHelper messagePackHelper) {
			key = messagePackHelper.getString(KEY_TRIP_KEY);
			tripKey = messagePackHelper.getString(KEY_TRIP_BASE, key);
			final List<Integer> stops = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_TRIP_TURN_BACK_STOPS, value -> stops.add(value.asIntegerValue().asInt()));
			turnBackStops = stops.stream().mapToInt(Integer::intValue).toArray();
			final List<Integer> turnBack = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_TRIP_TURN_BACK, value -> turnBack.add(value.asIntegerValue().asInt()));
			turnBackPoint = turnBack.size() == 2 ? turnBack.get(0) : -1;
			turnBackWay = turnBack.size() == 2 ? turnBack.get(1) : -1;
			changeStop = messagePackHelper.getInt(KEY_TRIP_CHANGE_STOP, -1);
			messagePackHelper.iterateArrayValue(KEY_TRIP_ROUTE_IDS, value -> routeIds.add(value.asIntegerValue().asLong()));
			final long oldCutRouteId = messagePackHelper.getLong(KEY_TRIP_CUT_ROUTE);
			if (oldCutRouteId != 0) {
				cutStops.put(oldCutRouteId, messagePackHelper.getInt(KEY_TRIP_CUT_STOP));
			}
			final List<Long> cutValues = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_TRIP_CUTS, value -> cutValues.add(value.asIntegerValue().asLong()));
			for (int i = 0; i + 1 < cutValues.size(); i += 2) {
				cutStops.put(cutValues.get(i), cutValues.get(i + 1).intValue());
			}
			joinIndex = messagePackHelper.getInt(KEY_LOOP_JOIN_INDEX);
			lapEnd = messagePackHelper.getInt(KEY_TRIP_LAP_END);
			repeatIndex1 = messagePackHelper.getInt(KEY_TRIP_REPEAT_1);
			repeatIndex2 = messagePackHelper.getInt(KEY_TRIP_REPEAT_2);
			lapStop = messagePackHelper.getInt(KEY_TRIP_LAP_STOP);
			entryRail = messagePackHelper.getInt(KEY_TRIP_ENTRY_RAIL, -1);
			messagePackHelper.iterateArrayValue(KEY_PATH, value -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
		}

		/**
		 * A path worked out elsewhere, for running services without a world.
		 *
		 * @param tailStart the rail of {@code path} where the tail of the trip starts
		 * @param repeatEnd for a loop at the end, the rail where a lap ends, or 0
		 */
		static TripPath of(TripTail tripTail, List<PathData> path, int tailStart, int repeatEnd, StopTimes newStopTimes) {
			final TripPath tripPath = new TripPath(tripTail.key, -1, -1, new int[0], tripTail, 0, 0, tailStart, repeatEnd, path);
			tripPath.stopTimes.copyFrom(newStopTimes);
			tripPath.stopTimes.cutStops.clear();
			tripPath.stopTimes.cutStops.putAll(tripPath.cutStops);
			return tripPath;
		}

		/**
		 * The usual path with another way round a turn back point, worked out elsewhere, for running services without a world.
		 */
		static TripPath ofTurnBack(int point, int way, int[] turnBackStops, List<PathData> path, StopTimes newStopTimes) {
			final TripPath tripPath = new TripPath(getTurnBackKey("", point, way), point, way, turnBackStops, 0, 0, 0, -1, path);
			tripPath.stopTimes.copyFrom(newStopTimes);
			return tripPath;
		}

		/**
		 * A way back to the depot after a route, worked out elsewhere, for running services without a world.
		 */
		static TripPath ofDepotReturn(int routeIndex, int changeStop, List<PathData> path, StopTimes newStopTimes) {
			final TripPath tripPath = new TripPath(getDepotReturnKey(routeIndex), -1, -1, new int[0], 0, 0, 0, changeStop, path);
			tripPath.stopTimes.copyFrom(newStopTimes);
			return tripPath;
		}

		/**
		 * @param tripKey the key of the service running through to another route, or empty for the usual path of the depot
		 * @return the key of the path that takes this way round this turn back point
		 */
		public static String getTurnBackKey(String tripKey, int point, int way) {
			return tripKey + "|" + point + ":" + way;
		}

		/**
		 * @param routeIndex the route of the depot after which the train goes back to it
		 * @return the key of the path back to the depot after that route
		 */
		public static String getDepotReturnKey(int routeIndex) {
			return "depot|" + routeIndex;
		}

		private void generateTimes(Siding siding) {
			stopTimes.cutStops.clear();
			stopTimes.cutStops.putAll(cutStops);
			stopTimes.tailJoinStart = entryRail;
			stopTimes.tailJoinEnd = entryRail < 0 ? 0 : lapStop;
			stopTimes.tailLapEnd = repeatIndex2;
			siding.generateTimeSegments(path, timeSegments, new HashMap<>(), stopTimes, joinIndex, lapEnd);
			double distanceSum = 0;
			distances.clear();
			for (final PathData pathData : path) {
				distanceSum += pathData.rail.getLength();
				distances.add(distanceSum);
			}
		}

		private void toMessagePack(MessagePacker messagePacker) throws IOException {
			messagePacker.packMapHeader(14);
			messagePacker.packString(KEY_TRIP_KEY).packString(key);
			messagePacker.packString(KEY_TRIP_CHANGE_STOP).packInt(changeStop);
			messagePacker.packString(KEY_TRIP_BASE).packString(tripKey);
			messagePacker.packString(KEY_TRIP_TURN_BACK).packArrayHeader(2).packInt(turnBackPoint).packInt(turnBackWay);
			messagePacker.packString(KEY_TRIP_TURN_BACK_STOPS).packArrayHeader(turnBackStops.length);
			for (final int stop : turnBackStops) {
				messagePacker.packInt(stop);
			}
			messagePacker.packString(KEY_TRIP_ROUTE_IDS).packArrayHeader(routeIds.size());
			for (final long routeId : routeIds) {
				messagePacker.packLong(routeId);
			}
			messagePacker.packString(KEY_TRIP_CUTS).packArrayHeader(cutStops.size() * 2);
			for (final Map.Entry<Long, Integer> entry : cutStops.entrySet()) {
				messagePacker.packLong(entry.getKey());
				messagePacker.packLong(entry.getValue());
			}
			messagePacker.packString(KEY_LOOP_JOIN_INDEX).packInt(joinIndex);
			messagePacker.packString(KEY_TRIP_LAP_END).packInt(lapEnd);
			messagePacker.packString(KEY_TRIP_REPEAT_1).packInt(repeatIndex1);
			messagePacker.packString(KEY_TRIP_REPEAT_2).packInt(repeatIndex2);
			messagePacker.packString(KEY_TRIP_LAP_STOP).packInt(lapStop);
			messagePacker.packString(KEY_TRIP_ENTRY_RAIL).packInt(entryRail);
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
		 * The routes this path leaves part of the way along, and the last stop it runs of each one.
		 */
		private final Map<Long, Integer> cutStops = new HashMap<>();
		/**
		 * Where a train joins a loop at the end of a trip, from the stop where it joins to the rail of the first stop of the loop.
		 */
		private int tailJoinStart = -1;
		private int tailJoinEnd;
		private int tailLapEnd;
		private final List<Stop> stops = new ArrayList<>();
		/**
		 * The stops on the way round a loop to its first stop, which are planned like the same stops of the lap.
		 */
		private final List<Stop> joinStops = new ArrayList<>();
		private final List<Stop> tailJoinStops = new ArrayList<>();
		private final Map<Integer, Integer> joinStopLaps = new HashMap<>();
		private final Map<Long, Map<Integer, RouteTimetable.StopPlan>> plans = new HashMap<>();

		/**
		 * Stop times of a path worked out elsewhere, for running services without a world.
		 *
		 * @param stops for each stop in order, its rail on the path, its platform, and its arrival and departure in ticks from leaving the siding
		 */
		static StopTimes of(long[]... stops) {
			final StopTimes stopTimes = new StopTimes();
			for (int i = 0; i < stops.length; i++) {
				stopTimes.add((int) stops[i][0], stops[i][1], null, stops[i][2], stops[i][3], i == 0);
			}
			return stopTimes;
		}

		private void clear() {
			firstIndex = -1;
			firstArrival = 0;
			plannedKey = 0;
			joinIndex = 0;
			joinArrival = 0;
			stops.clear();
			joinStops.clear();
			tailJoinStops.clear();
			joinStopLaps.clear();
			plans.clear();
		}

		private void copyFrom(StopTimes stopTimes) {
			clear();
			firstIndex = stopTimes.firstIndex;
			firstArrival = stopTimes.firstArrival;
			joinIndex = stopTimes.joinIndex;
			joinArrival = stopTimes.joinArrival;
			cutStops.clear();
			cutStops.putAll(stopTimes.cutStops);
			tailJoinStart = stopTimes.tailJoinStart;
			tailJoinEnd = stopTimes.tailJoinEnd;
			tailLapEnd = stopTimes.tailLapEnd;
			stops.addAll(stopTimes.stops);
			tailJoinStops.addAll(stopTimes.tailJoinStops);
			joinStops.addAll(stopTimes.joinStops);
			joinStopLaps.putAll(stopTimes.joinStopLaps);
		}

		private void add(int index, long platformId, UUID turnBackRail, float arrival, float departure, boolean isFirstStop) {
			if (index < joinIndex) {
				if (joinStops.isEmpty()) {
					joinArrival = arrival;
				}
				joinStops.add(new Stop(index, platformId, null, 0, 0));
				return;
			}
			if (index >= tailJoinStart && tailJoinStart >= 0 && index < tailJoinEnd) {
				tailJoinStops.add(new Stop(index, platformId, null, 0, 0));
				return;
			}
			if (firstIndex < 0 && isFirstStop) {
				firstIndex = index;
				firstArrival = arrival;
			}
			if (firstIndex >= 0) {
				stops.add(new Stop(index, platformId, turnBackRail, (long) ((arrival - firstArrival) * Depot.MILLIS_PER_TICK), (long) ((departure - firstArrival) * Depot.MILLIS_PER_TICK)));
			}
		}

		/**
		 * Goes back from the end of the first lap, as the stops of a joined loop are the last ones of the lap.
		 */
		private void linkJoinStops(int lapEndIndex) {
			linkJoinStops(joinStops, lapEndIndex);
			linkJoinStops(tailJoinStops, tailLapEnd);
		}

		private void linkJoinStops(List<Stop> newJoinStops, int lapEndIndex) {
			int lapStop = stops.size() - 1;
			while (lapStop >= 0 && stops.get(lapStop).index >= lapEndIndex) {
				lapStop--;
			}
			for (int i = newJoinStops.size() - 1; i >= 0; i--) {
				final Stop joinStop = newJoinStops.get(i);
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
			return !joinStops.isEmpty() && joinStopLaps.containsKey(joinStops.get(0).index);
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
		 * @return the time from the first stop to the last one
		 */
		public long getDurationMillis() {
			return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalMillis;
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
		 * @param routes the routes this path runs through, in order
		 * @return the turn back rails on this path, each after the last stop before it
		 */
		public List<TimetableConflicts.TurnBack> getTurnBacks(List<Route> routes) {
			final List<Stop> pathStops = new ArrayList<>();
			final List<RouteStop> pathRouteStops = new ArrayList<>();
			matchRouteStops(routes, (stop, routeStop) -> {
				pathStops.add(stop);
				pathRouteStops.add(routeStop);
			});

			final List<TimetableConflicts.TurnBack> turnBacks = new ArrayList<>();
			for (int i = 1; i < pathStops.size(); i++) {
				final Stop stop = pathStops.get(i);
				final RouteStop lastRouteStop = pathRouteStops.get(i - 1);
				if (stop.turnBackRail != null && lastRouteStop != null) {
					final boolean startsRoute = i + 1 < pathStops.size() && pathRouteStops.get(i + 1) != null && pathRouteStops.get(i + 1).startsRoute;
					turnBacks.add(new TimetableConflicts.TurnBack(
							lastRouteStop.departureRoute.id, stop.turnBackRail, lastRouteStop.departureIndex, startsRoute ? pathRouteStops.get(i + 1).departureRoute.id : 0,
							stop.arrivalMillis - pathStops.get(i - 1).departureMillis, stop.departureMillis - stop.arrivalMillis, startsRoute ? pathStops.get(i + 1).arrivalMillis - stop.departureMillis : 0
					));
				}
			}
			return turnBacks;
		}

		/**
		 * Goes through the stops of the path, finding each one in the routes by platform in order. A platform where one
		 * route ends and the next one starts is one stop on the path, and gets its arrival and departure from different routes.
		 */
		private void matchRouteStops(List<Route> routes, BiConsumer<Stop, RouteStop> consumer) {
			final List<RouteStop> routeStops = new ArrayList<>();
			Route lastRoute = null;
			for (final Route route : routes) {
				final int stopCount = cutStops.containsKey(route.id) ? Math.min(cutStops.get(route.id) + 1, route.platformIds.size()) : route.platformIds.size();
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
			private final UUID turnBackRail;
			private final long arrivalMillis;
			private final long departureMillis;

			private Stop(int index, long platformId, UUID turnBackRail, long arrivalMillis, long departureMillis) {
				this.index = index;
				this.platformId = platformId;
				this.turnBackRail = turnBackRail;
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
