package mtr.data;

import transitsim.plan.DepotReturn;
import transitsim.sim.SimDataset;
import transitsim.sim.RunRecord;
import transitsim.sim.TickRunner;
import transitsim.io.BinaryKeyed;
import transitsim.io.ByteArrayBinary;
import java.util.function.Function;
import transitsim.plan.PathTimes;
import transitsim.plan.DutyPlan;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeGenerator;
import mtr.advanced.MessagePackKeyed;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;
import transitsim.plan.TripTail;
import transitsim.plan.TurnBackSection;
import transitsim.plan.TurnBacks;
import transitsim.plan.TimetableConflicts;
import transitsim.plan.RouteTimetable;
import io.netty.buffer.Unpooled;
import transitsim.plan.DutyShapes;
import mtr.advanced.GraphRecords;
import transitsim.plan.ServiceExceptions;
import mtr.advanced.TrackViews;
import mtr.packet.IPacket;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.motion.MotionEvent;
import transitsim.motion.PathRelocator;
import transitsim.motion.SpeedCurves;
import transitsim.motion.SpeedProfile;
import transitsim.network.RailGraph;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A siding: a stretch of track in a depot where its trains are kept, and the trains it owns ({@link TrainServer}).
 * <p>
 * It holds the paths its trains run, made by {@code generateRoute} when its depot generates paths: the main path along
 * the depot's routes, and the paths out of the siding, turning back and back to the depot ({@link TripPath}). Each tick
 * {@code simulateTrain} moves its trains, unless the component's simulator runs them ({@link #isRunBySimulator()}).
 */
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
	/**
	 * Whether the component's simulator runs the siding's trains (G1): it sends them out and takes them back, and the
	 * siding's trains only show its trains.
	 */
	private boolean runBySimulator;
	private int ticksSinceLoad;
	private float accelerationConstant;
	/**
	 * How fast trains brake, or 0 to brake as fast as they speed up.
	 */
	private float decelerationConstant;
	/**
	 * Whether the depot runs this siding's trains on speed curves, which the times of the paths follow too.
	 */
	private boolean useSpeedCurve;

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
	/**
	 * Where the trains' motion events go, collected by the world at the end of the tick, or null to drop them. A field for
	 * the same reason as {@link #ticksElapsed}.
	 */
	public List<MotionEvent> motionEvents;
	private final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	private final Set<TrainServer> trains = new HashSet<>();
	/**
	 * The paths of services leaving the usual path for another route, by {@link TripTail#getKey()}.
	 */
	private final Map<String, TripPath<PathData>> tripPaths = new HashMap<>();
	private TurnBacks turnBacks = TurnBacks.NONE;
	private final TimeGenerator<PathData> timeGenerator = new TimeGenerator<PathData>() {
		@Override
		public StopTimes calculateStopTimes(List<PathData> path) {
			return Siding.this.calculateStopTimes(path);
		}

		@Override
		public void generateTimeSegments(List<PathData> path, List<TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
			Siding.this.generateTimeSegments(path, timeSegments, new HashMap<>(), stopTimes, joinIndex, lapEndIndex);
		}
	};

	private static final String KEY_RAIL_LENGTH = "rail_length";
	private static final String KEY_TRIP_PATHS = "trip_paths";
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
	private static final String KEY_DECELERATION_CONSTANT = "deceleration_constant";

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
		decelerationConstant = clampDeceleration(messagePackHelper.getFloat(KEY_DECELERATION_CONSTANT, 0));

		messagePackHelper.iterateArrayValue(KEY_PATH, pathSection -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(pathSection))));

		generateTimeSegments(path, timeSegments, platformTimes, stopTimes, loopJoinIndex, repeatIndex2);
		turnBacks = TurnBacks.read(new MessagePackKeyed.Reader(messagePackHelper));

		messagePackHelper.iterateArrayValue(KEY_TRIP_PATHS, value -> {
			final MessagePackHelper tripHelper = new MessagePackHelper(RailwayData.castMessagePackValueToSKMap(value));
			final List<PathData> tripPathData = new ArrayList<>();
			tripHelper.iterateArrayValue(KEY_PATH, pathValue -> tripPathData.add(new PathData(RailwayData.castMessagePackValueToSKMap(pathValue))));
			final TripPath<PathData> tripPath = new TripPath<>(new MessagePackKeyed.Reader(tripHelper), tripPathData);
			tripPath.generateTimes(timeGenerator);
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
		decelerationConstant = clampDeceleration(packet.readFloat());
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
		messagePacker.packString(KEY_DECELERATION_CONSTANT).packFloat(decelerationConstant);
		RailwayData.writeMessagePackDataset(messagePacker, path, KEY_PATH);
		messagePacker.packString(KEY_TRIP_PATHS).packArrayHeader(tripPaths.size());
		for (final TripPath<PathData> tripPath : tripPaths.values()) {
			messagePacker.packMapHeader(TripPath.WRITTEN_VALUES + 1);
			tripPath.write(new MessagePackKeyed.Writer(messagePacker));
			RailwayData.writeMessagePackDataset(messagePacker, tripPath.path, KEY_PATH);
		}
		turnBacks.write(new MessagePackKeyed.Writer(messagePacker));
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 17;
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
		packet.writeFloat(decelerationConstant);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_DECELERATION_CONSTANT:
				decelerationConstant = clampDeceleration(packet.readFloat());
				break;
			case KEY_BASE_TRAIN_TYPE:
				setTrainDetails(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readUtf(PACKET_STRING_READ_LENGTH), false);
				trains.clear();
				trainsRelocated = true;
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
					trainsRelocated = true;
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

	/**
	 * @return what the sidings of a depot whose trains can run each other's duties have in common: the vehicle and its
	 * length (decoupling document, step D, depots sending trains out 4 and 5)
	 */
	public String getPoolKey() {
		return trainId + "/" + trainCars;
	}

	public int getTrainCount() {
		return trains.size();
	}

	/**
	 * Gives the nodes of the siding's path and of each of its duties' paths, in order, for telling which depots' trains
	 * share track ({@link transitsim.network.LockNetworks}).
	 */
	public void forEachPathNodes(Consumer<long[]> consumer) {
		consumer.accept(nodesOf(path));
		tripPaths.values().forEach(tripPath -> consumer.accept(nodesOf(tripPath.path)));
	}

	private static long[] nodesOf(List<PathData> pathDataList) {
		if (pathDataList.isEmpty()) {
			return new long[0];
		}
		final long[] nodes = new long[pathDataList.size() + 1];
		for (int i = 0; i < pathDataList.size(); i++) {
			nodes[i] = pathDataList.get(i).startingPos.asLong();
		}
		nodes[pathDataList.size()] = pathDataList.get(pathDataList.size() - 1).getLastPos().asLong();
		return nodes;
	}

	/**
	 * Trains of other sidings that came back here, each of which this siding makes standing in its place; not saved, as
	 * that happens within a tick or two.
	 */
	private int pendingArrivals;

	public int getPendingArrivals() {
		return pendingArrivals;
	}

	/**
	 * @return how many of its trains are on their way back to another siding
	 */
	public int countReturningTo(long otherSidingId) {
		return (int) trains.stream().filter(train -> train.isReturningTo(otherSidingId)).count();
	}

	public float getAccelerationConstant() {
		return accelerationConstant;
	}

	/**
	 * @return how fast trains brake, which is how fast they speed up unless set
	 */
	public float getDecelerationConstant() {
		return decelerationConstant <= 0 ? accelerationConstant : decelerationConstant;
	}

	public void setDecelerationConstant(float newDecelerationConstant, Consumer<FriendlyByteBuf> sendPacket) {
		decelerationConstant = clampDeceleration(newDecelerationConstant);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DECELERATION_CONSTANT);
		packet.writeFloat(decelerationConstant);
		sendPacket.accept(packet);
	}

	private static float clampDeceleration(float value) {
		return Float.isNaN(value) || value <= 0 ? 0 : Mth.clamp(RailwayData.round(value, 3), Train.MIN_ACCELERATION, Train.MAX_ACCELERATION);
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
			// Turning speed curves on or off changes how long the path takes
			if (depot.speedCurves != useSpeedCurve) {
				useSpeedCurve = depot.speedCurves;
				generateTimeSegments(path, timeSegments, platformTimes, stopTimes, loopJoinIndex, repeatIndex2);
				tripPaths.values().forEach(tripPath -> tripPath.generateTimes(timeGenerator));
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
	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed, List<PathData> loopJoinPath, List<TripTail<PathData, SavedRailBase>> tripTails) {
		return generateRoute(minecraftServer, mainPath, successfulSegmentsMain, rails, graph, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, new ArrayList<>(), new ArrayList<>());
	}

	/**
	 * @param turnBackSections where the main path can turn back on more than one rail, with its stops numbered
	 * @param depotReturns     for trains staying on the line, where they can go back to the depot after a route
	 */
	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed, List<PathData> loopJoinPath, List<TripTail<PathData, SavedRailBase>> tripTails, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns) {
		return generateRoute(minecraftServer, mainPath, successfulSegmentsMain, rails, graph, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, turnBackSections, depotReturns, new ArrayList<>(), Collections.emptyList());
	}

	/**
	 * @param dutyPlans    the paths of the duties of this siding's trains, for a depot running by its duties
	 * @param poolSidings the other sidings of its pool, which its trains may go back to after a duty
	 */
	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed, List<PathData> loopJoinPath, List<TripTail<PathData, SavedRailBase>> tripTails, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, List<DutyPlan<PathData, SavedRailBase>> dutyPlans, List<Siding> poolSidings) {
		final List<PathData> tempPath = new ArrayList<>();
		final Map<String, TripPath<PathData>> tempTripPaths = new HashMap<>();
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
				for (final TripTail<PathData, SavedRailBase> tripTail : tripTails) {
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
		final List<String> failedDuties = new ArrayList<>();
		final Map<String, List<PathData>> returnTails = new HashMap<>();
		dutyPlans.forEach(dutyPlan -> {
			if (!addDutyPath(tempTripPaths, dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, poolSidings, returnTails)) {
				failedDuties.add(dutyPlan.key);
			}
		});
		failedDuties.forEach(key -> System.out.println("The path of a duty can't be found: " + key));
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
				recordPaths();
				if (!tempPath.isEmpty()) {
					relocateTrains(oldRails, oldDistances);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return successfulSegments;
	}

	/**
	 * The path of a duty from this siding (design document 6.12, block E2): out to its first stop, unless the train starts
	 * where it stayed overnight, its segments, and back to the siding unless it stays on the line.
	 *
	 * @return false if a part of it can't be found
	 */
	private boolean addDutyPath(Map<String, TripPath<PathData>> tempTripPaths, DutyPlan<PathData, SavedRailBase> dutyPlan, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed, List<Siding> poolSidings, Map<String, List<PathData>> returnTails) {
		final List<PathData> dutyPath = new ArrayList<>();
		if (!dutyPlan.startsOnLine) {
			PathFinder.findPath(dutyPath, rails, graph, Arrays.asList(this, dutyPlan.firstPlatform), null, 0, cruisingAltitude, useFastSpeed);
			if (dutyPath.isEmpty()) {
				return false;
			}
		}
		final List<PathData> body = new ArrayList<>();
		dutyPlan.body.forEach(pathData -> body.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex)));
		// The first stop of the duty keeps its own stop time, not the one the way out of the siding found
		final int bodyStart;
		if (!dutyPath.isEmpty() && dutyPath.get(dutyPath.size() - 1).isSameRail(body.get(0))) {
			bodyStart = dutyPath.size() - 1;
			dutyPath.set(bodyStart, body.get(0));
			dutyPath.addAll(body.subList(1, body.size()));
		} else {
			bodyStart = dutyPath.size();
			dutyPath.addAll(body);
		}
		if (dutyPlan.staysOnLine) {
			addDutyTripPath(tempTripPaths, dutyPlan, dutyPath, bodyStart, dutyPlan.key);
			return true;
		}
		final List<PathData> pathToDepot = getReturnTail(dutyPlan, this, rails, graph, cruisingAltitude, useFastSpeed, returnTails);
		if (pathToDepot == null) {
			return false;
		}
		final List<PathData> ownPath = new ArrayList<>(dutyPath);
		PathFinder.appendPath(ownPath, pathToDepot);
		addDutyTripPath(tempTripPaths, dutyPlan, ownPath, bodyStart, dutyPlan.key);
		// The same duty going back to each other siding of the pool, for a train going where a train is needed next
		for (final Siding poolSiding : poolSidings) {
			final List<PathData> pathToOther = poolSiding == this ? null : getReturnTail(dutyPlan, poolSiding, rails, graph, cruisingAltitude, useFastSpeed, returnTails);
			if (pathToOther != null) {
				final List<PathData> otherPath = new ArrayList<>(dutyPath);
				PathFinder.appendPath(otherPath, pathToOther);
				addDutyTripPath(tempTripPaths, dutyPlan, otherPath, bodyStart, TripPath.getReturnKey(dutyPlan.key, poolSiding.id));
			}
		}
		return true;
	}

	/**
	 * @return the way from the last stop of the duty into a siding, found once for each stop and siding, or null if there
	 * is none
	 */
	private static List<PathData> getReturnTail(DutyPlan<PathData, SavedRailBase> dutyPlan, Siding siding, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed, Map<String, List<PathData>> returnTails) {
		final List<PathData> tail = returnTails.computeIfAbsent(dutyPlan.lastPlatform.id + ":" + siding.id, key -> {
			final List<PathData> pathToDepot = new ArrayList<>();
			PathFinder.findPath(pathToDepot, rails, graph, Arrays.asList(dutyPlan.lastPlatform, siding), null, 0, cruisingAltitude, useFastSpeed);
			return pathToDepot;
		});
		return tail.isEmpty() ? null : copyPath(tail);
	}

	private static List<PathData> copyPath(List<PathData> path) {
		final List<PathData> copy = new ArrayList<>();
		path.forEach(pathData -> copy.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex)));
		return copy;
	}

	private void addDutyTripPath(Map<String, TripPath<PathData>> tempTripPaths, DutyPlan<PathData, SavedRailBase> dutyPlan, List<PathData> dutyPath, int bodyStart, String key) {
		int stopIndex = 1;
		for (int i = 1; i < dutyPath.size(); i++) {
			final PathData pathData = dutyPath.get(i);
			if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && dutyPath.get(i - 1).rail.railType != RailType.PLATFORM) {
				stopIndex++;
			}
			pathData.stopIndex = stopIndex;
		}
		final int[] dutyStarts = new int[dutyPlan.segmentStarts.length];
		for (int i = 0; i < dutyStarts.length; i++) {
			dutyStarts[i] = dutyPlan.segmentStarts[i] + bodyStart;
		}
		final TripPath<PathData> newTripPath = new TripPath<>(dutyPlan, dutyStarts, dutyPath, key);
		newTripPath.generateTimes(timeGenerator);
		tempTripPaths.put(key, newTripPath);
	}

	/**
	 * Hands the paths of this siding to the graph records, which write them into the database. See {@link GraphRecords}.
	 */
	private void recordPaths() {
		final RailwayData railwayData = world == null ? null : RailwayData.getInstance(world);
		if (railwayData != null) {
			railwayData.graphRecords.addPath("siding:" + id, GraphRecords.getMoves(path));
			tripPaths.forEach((key, tripPath) -> railwayData.graphRecords.addPath("siding:" + id + ":" + key, GraphRecords.getMoves(tripPath.path)));
		}
	}

	public Map<String, TripPath<PathData>> getTripPaths() {
		return Collections.unmodifiableMap(tripPaths);
	}

	private void addTripPath(Map<String, TripPath<PathData>> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, TripTail<PathData, SavedRailBase> tripTail, int leaveRail, List<PathData> pathToDepot, int joinIndex, String key, int turnBackPoint, int turnBackWay, int[] turnBackStops) {
		final List<PathData> tripPath = new ArrayList<>(pathBeforeMain);
		PathFinder.appendPath(tripPath, new ArrayList<>(mainPath.subList(0, leaveRail + 1)));
		final int lapEnd = tripPath.size() - 1;
		final int tailStart = tripPath.size() - (tripPath.get(lapEnd).isSameRail(tripTail.path.get(0)) ? 1 : 0);
		PathFinder.appendPath(tripPath, tripTail.path);
		final int repeatEnd = tripPath.size() - 1;
		PathFinder.appendPath(tripPath, pathToDepot);
		final TripPath<PathData> newTripPath = new TripPath<>(key, turnBackPoint, turnBackWay, turnBackStops, tripTail, joinIndex, lapEnd, tailStart, repeatEnd, tripPath);
		newTripPath.generateTimes(timeGenerator);
		tempTripPaths.put(key, newTripPath);
	}

	/**
	 * Each way round a turn back point that the main path doesn't take gets a path of its own, for the usual path and for
	 * every service running through to another route after the point. Only the rails of that way round are different, so a
	 * train can change onto one of these paths at the stop before the point.
	 */
	private void addTurnBackPaths(Map<String, TripPath<PathData>> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, List<PathData> pathToDepot, int mainOffset, List<TurnBackSection<PathData>> turnBackSections, List<TripTail<PathData, SavedRailBase>> tripTails, Map<String, List<PathData>> tripPathsToDepot, int joinIndex, int newRepeatIndex1, int newRepeatIndex2) {
		for (int point = 0; point < turnBackSections.size(); point++) {
			final TurnBackSection<PathData> section = turnBackSections.get(point);
			for (int way = 0; way < section.ways.size(); way++) {
				if (way != section.defaultWay && section.ways.get(way) != null) {
					final List<PathData> newMainPath = section.getMainPath(mainPath, way);
					final int extraRails = section.ways.get(way).size() - section.getLength();
					final List<PathData> newPath = new ArrayList<>(pathBeforeMain);
					PathFinder.appendPath(newPath, newMainPath);
					PathFinder.appendPath(newPath, pathToDepot);
					final String key = TripPath.getTurnBackKey("", point, way);
					final TripPath<PathData> turnBackPath = new TripPath<>(key, point, way, getTurnBackStops(turnBackSections, mainOffset, Integer.MAX_VALUE, point, extraRails), joinIndex, newRepeatIndex1, newRepeatIndex2 == 0 ? 0 : newRepeatIndex2 + extraRails, -1, newPath);
					turnBackPath.generateTimes(timeGenerator);
					tempTripPaths.put(key, turnBackPath);

					for (final TripTail<PathData, SavedRailBase> tripTail : tripTails) {
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
	private void addDepotReturnPaths(Map<String, TripPath<PathData>> tempTripPaths, List<PathData> pathBeforeMain, List<PathData> mainPath, int mainOffset, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, int joinIndex, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed) {
		for (final DepotReturn<PathData, SavedRailBase> depotReturn : depotReturns) {
			final int turnBackRail = depotReturn.getTurnBackRail();
			final BlockPos turnBackNode = turnBackRail < 0 ? null : depotReturn.way.get(turnBackRail).getLastPos();
			final List<PathData> wayToDepot = new ArrayList<>();
			PathFinder.findPath(wayToDepot, rails, graph, Arrays.asList(depotReturn.lastPlatform, this), turnBackNode == null ? null : Collections.singletonList(Collections.singletonList(turnBackNode)), mainPath.get(depotReturn.getStopRail()).stopIndex, cruisingAltitude, useFastSpeed);
			if (!wayToDepot.isEmpty()) {
				final List<PathData> newPath = new ArrayList<>(pathBeforeMain);
				PathFinder.appendPath(newPath, new ArrayList<>(mainPath.subList(0, depotReturn.getStopRail() + 1)));
				final int changeStop = newPath.size() - 1;
				PathFinder.appendPath(newPath, wayToDepot);
				final String key = TripPath.getDepotReturnKey(depotReturn.routeIndex);
				final TripPath<PathData> returnPath = new TripPath<>(key, -1, -1, getTurnBackStops(turnBackSections, mainOffset, depotReturn.getStopRail(), -1, 0), joinIndex, 0, 0, changeStop, newPath);
				returnPath.generateTimes(timeGenerator);
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
	private static int[] getTurnBackStops(List<TurnBackSection<PathData>> turnBackSections, int mainOffset, int leaveRail, int changedPoint, int extraRails) {
		final int[] stops = new int[turnBackSections.size()];
		for (int i = 0; i < turnBackSections.size(); i++) {
			final TurnBackSection<PathData> section = turnBackSections.get(i);
			stops[i] = section.getEnd() <= leaveRail ? mainOffset + section.getStopRail() + (changedPoint >= 0 && i > changedPoint ? extraRails : 0) : -1;
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

		// Train positions and the routes of the depot are known once every siding has run for a tick; the simulator puts
		// its trains where the plan has them itself
		if (ticksSinceLoad < 2 && ++ticksSinceLoad == 2 && !runBySimulator) {
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
			train.setSpeedCurve(useSpeedCurve, getDecelerationConstant());
			train.lastSchedulesForPlatform = lastSchedulesForPlatform;
			if (train.simulateTrain(world, ticksElapsed, depot, dataCache, trainPositions, trainsInPlayerRange, schedulesForPlatform, trainDelays)) {
				trainsToSync.add(train);
			}
			// Back in another siding of the pool, which makes a train standing there in its place, maybe of another depot of
			// the line
			final long returnedTo = train.takeReturnedElsewhereSiding();
			if (returnedTo != 0) {
				trainsToRemove.add(train);
				final Siding target = dataCache == null ? null : dataCache.sidingIdMap.get(returnedTo);
				if (target != null && target != this) {
					target.pendingArrivals++;
				}
				continue;
			}
			if (motionEvents != null) {
				train.drainMotionEvents(motionEvents);
			}

			if (runBySimulator) {
				if (trainPositions != null && !transportMode.continuousMovement) {
					train.writeTrainPositions(trainPositions, signalBlocks);
				}
				continue;
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

		if (runBySimulator) {
			return;
		}

		// A depot running by its duties has a train for each slot of the siding's pool, the sidings with the same vehicle,
		// within each siding's limit
		final boolean runsByDuties = depot.getRunDuties() != null;
		final int poolSlots = runsByDuties ? depot.getPoolSlots(this) : 0;
		final int trainLimit = getTrainLimit();
		// A train that came back here from another siding stands here in its place
		final boolean arrived = pendingArrivals > 0;
		if (arrived) {
			pendingArrivals--;
		}
		if (trainCars > 0 && (arrived ? trains.isEmpty() || trainLimit < 0 || trains.size() < trainLimit : runsByDuties ? poolSlots > 0 && (trains.isEmpty() || spawnTrain && depot.getPoolTrains(this) < poolSlots && (trainLimit < 0 || trains.size() < trainLimit)) : trains.isEmpty() || spawnTrain && (unlimitedTrains && trains.size() < MAX_UNLIMITED_TRAINS || trains.size() <= maxTrains))) {
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

	/**
	 * @param routeIds        the routes the siding's trains run, in order
	 * @param toTracks        turns a path into the component's tracks
	 * @return the siding as the component's simulator needs it (S4e), or null without a path or trains
	 */
	public SimDataset.SidingData toSimulation(long depotId, long[] routeIds, Function<List<PathData>, List<SimDataset.DataTrack>> toTracks) {
		if (path.isEmpty() || trainCars <= 0) {
			return null;
		}
		final ByteArrayBinary.Writer turnBacksWriter = new ByteArrayBinary.Writer();
		final BinaryKeyed.Writer turnBacksValues = new BinaryKeyed.Writer();
		try {
			turnBacks.write(turnBacksValues);
		} catch (IOException ignored) {
		}
		turnBacksValues.writeTo(turnBacksWriter);
		final SimDataset.SidingData sidingData = new SimDataset.SidingData(
				id, depotId, routeIds, trainCars, TrainType.getSpacing(baseTrainType), railLength, accelerationConstant, getDecelerationConstant(),
				unlimitedTrains ? SimDataset.UNLIMITED_TRAINS : maxTrains, useSpeedCurve && !transportMode.continuousMovement, RailType.getDefaultMaxBlocksPerTick(transportMode),
				repeatIndex1, repeatIndex2, loopJoinIndex, toTracks.apply(path), turnBacksWriter.toBytes()
		);
		sidingData.manual = isManual;
		sidingData.poolKey = getPoolKey();
		// The pool across the depots of its operating line (SimDataset 7)
		final String lineUnitKey = depot == null ? "" : depot.getLineUnitKey();
		sidingData.linePool = lineUnitKey.isEmpty() ? "" : lineUnitKey + "|" + getPoolKey();
		tripPaths.values().stream().sorted(Comparator.comparing(tripPath -> tripPath.key)).forEach(tripPath -> sidingData.tripPaths.add(toSimulation(tripPath, toTracks)));
		return sidingData;
	}

	public boolean isRunBySimulator() {
		return runBySimulator;
	}

	/**
	 * @return whether the siding's path was found again or its trains were cleared away since its last tick, so the
	 * component's simulator starts again from its trains as they are now
	 */
	public boolean hasTrainsRelocated() {
		return trainsRelocated;
	}

	/**
	 * @return whether the siding has run the ticks after the world is loaded in which its trains are put where the plan has
	 * them
	 */
	public boolean hasSettled() {
		return ticksSinceLoad >= 2;
	}

	/**
	 * @return whether the component's simulator can run the siding, which needs a path and a vehicle that fits (G1)
	 */
	public boolean canBeSimulated() {
		return !path.isEmpty() && trainCars > 0;
	}

	/**
	 * Has the siding's trains show the simulator's trains of the siding (G1): a train for each of them, with the same ID,
	 * and none other, apart from the trains the game moves itself as they are driven by hand.
	 *
	 * @param takenOver the trains the game moves itself, which stay as they are
	 */
	public void showSimulatedTrains(List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> simulatedTrains, Set<Long> takenOver) {
		runBySimulator = true;
		final Map<Long, TickRunner<SimDataset.DataTrack>.HeadlessTrain> byId = new HashMap<>();
		simulatedTrains.forEach(train -> byId.put(train.id, train));
		trains.removeIf(train -> !byId.containsKey(train.id) && !takenOver.contains(train.id));
		final Set<Long> shown = new HashSet<>();
		trains.forEach(train -> {
			shown.add(train.id);
			train.setSimulated(byId.get(train.id));
		});
		byId.forEach((simulatedId, simulatedTrain) -> {
			if (!shown.contains(simulatedId)) {
				final TrainServer train = new TrainServer(simulatedId, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, timeSegments, isManual, maxManualSpeed, dwellTime);
				train.setSimulated(simulatedTrain);
				trains.add(train);
			}
		});
	}

	/**
	 * The game moves the siding's trains again, from where the simulator had them.
	 */
	public void stopSimulated() {
		runBySimulator = false;
		trains.forEach(train -> train.setSimulated(null));
	}

	public void forEachTrain(Consumer<TrainServer> consumer) {
		trains.stream().sorted(Comparator.comparingLong(train -> train.id)).forEach(consumer);
	}

	/**
	 * Adds the siding's trains to a record of them (G0), leaving out the ones driven by hand, which the simulator doesn't
	 * move.
	 */
	public void recordTrains(RunRecord record) {
		trains.stream().filter(train -> !train.isCurrentlyManual()).sorted(Comparator.comparingLong(train -> train.id)).forEach(train -> record.trains.add(train.toRecord()));
	}

	/**
	 * @return a path of a service leaving the siding as the component's simulator has it (S4e)
	 */
	public static SimDataset.TripPathData toSimulation(TripPath<PathData> tripPath, Function<List<PathData>, List<SimDataset.DataTrack>> toTracks) {
		final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
		try {
			tripPath.write(values);
		} catch (IOException ignored) {
		}
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		values.writeTo(writer);
		return new SimDataset.TripPathData(writer.toBytes(), toTracks.apply(tripPath.path));
	}

	/**
	 * The paths of these duties from this siding, found the way path generation finds them but kept apart from the siding,
	 * as for a draft of the duties (S6).
	 *
	 * @param failed told about the key of each duty whose path can't be found
	 * @return the paths by the key of the duty's shape
	 */
	public Map<String, TripPath<PathData>> findDutyPaths(List<DutyPlan<PathData, SavedRailBase>> dutyPlans, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed, Consumer<String> failed) {
		final Map<String, TripPath<PathData>> dutyPaths = new HashMap<>();
		final Map<String, List<PathData>> returnTails = new HashMap<>();
		dutyPlans.forEach(dutyPlan -> {
			// Only the paths back to this siding, for showing the duties in the editor
			if (!addDutyPath(dutyPaths, dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, Collections.emptyList(), returnTails)) {
				failed.accept(dutyPlan.key);
			}
		});
		return dutyPaths;
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

	/**
	 * @return how many trains the siding holds at most, or -1 for no limit; {@link #getMaxTrains} is one less, as the
	 * siding makes a train while it has no more than that many
	 */
	public int getTrainLimit() {
		return unlimitedTrains ? -1 : maxTrains + 1;
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
		trainsRelocated = true;
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
		PathTiming.generateTimeSegments(getTimingVehicle(), path, timeSegments, platformTimes, stopTimes, joinIndex, lapEndIndex);
	}

	/**
	 * @return what the times along a path depend on of this siding's trains (see {@link PathTiming})
	 */
	public PathTiming.Vehicle getTimingVehicle() {
		return getTimingVehicle(useSpeedCurve);
	}

	/**
	 * @param speedCurves whether the train runs on speed curves, as its depot is set
	 */
	public PathTiming.Vehicle getTimingVehicle(boolean speedCurves) {
		return new PathTiming.Vehicle(railLength, trainCars, TrainType.getSpacing(baseTrainType), accelerationConstant, getDecelerationConstant(), speedCurves, transportMode);
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
		return PathTimes.isPassingPlatform(path, index);
	}
}
