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
import transitsim.plan.TurnBackPoint;
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
	/**
	 * The routes this siding's trains run, of its depot's, in no order; empty for all of them.
	 */
	private final Set<Long> routeIds = new TreeSet<>();
	/**
	 * The keys of the duties whose paths the last path generation couldn't find, for the depot to report.
	 */
	private volatile List<String> lastFailedDuties = Collections.emptyList();
	/**
	 * How the siding's trains get out to their first stops and back from their last, from the last path generation, for
	 * the advanced editor's running diagram: on the server for the depot to send, on the client as it was sent.
	 */
	private volatile DepotLegs lastDepotLegs = DepotLegs.NONE;
	/**
	 * The ways out and back the duties are planned by where measuring found the estimate off (DepotLegs.record): saved,
	 * sent to the clients, and kept across path generations, unlike {@link #lastDepotLegs}.
	 */
	private volatile DepotLegs recordedLegs = DepotLegs.NONE;
	/**
	 * First and last stop platforms the path generation running now found no way out to or back from (P2-A).
	 */
	private final Set<Long> foundNoWayOut = new TreeSet<>();
	private final Set<Long> foundNoWayBack = new TreeSet<>();
	/**
	 * How many trains run from the siding for a depot running by duties, as a depot's fleet, which needn't fit on the
	 * rails here; -1 for as many as the duties need.
	 */
	private int fleetSize = -1;
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
	private static final String KEY_ROUTE_IDS = "route_ids";
	private static final String KEY_FLEET_SIZE = "fleet_size";
	private static final String KEY_RECORDED_LEGS = "recorded_depot_legs";
	/**
	 * The update setting the routes the siding's trains run and how many trains it has, together.
	 */
	private static final String KEY_OPERATION = "siding_operation";
	private static final int MAX_ROUTE_IDS = 1024;
	private static final int MAX_TRAINS = 999;
	private static final int MAX_UNLIMITED_TRAINS = 4096;
	private static final String KEY_IS_MANUAL = "is_manual";
	private static final String KEY_MAX_MANUAL_SPEED = "max_manual_speed";
	private static final String KEY_PATH = "path";
	private static final String KEY_REPEAT_INDEX_1 = "repeat_index_1";
	private static final String KEY_REPEAT_INDEX_2 = "repeat_index_2";
	private static final String KEY_LOOP_JOIN_INDEX = "loop_join_index";
	private static final String KEY_TRAINS = "trains";
	private static final String KEY_SHED = "shed";
	private static final String KEY_ACCELERATION_CONSTANT = "acceleration_constant";
	private static final String KEY_DECELERATION_CONSTANT = "deceleration_constant";

	public Siding(long id, TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(id, transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		// New sidings hold any number of trains: how many run is the fleet, set in the advanced editor
		unlimitedTrains = true;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
	}

	public Siding(TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		// New sidings hold any number of trains: how many run is the fleet, set in the advanced editor
		unlimitedTrains = true;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
	}

	public Siding(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		railLength = RailwayData.round(messagePackHelper.getFloat(KEY_RAIL_LENGTH), 3);
		setTrainDetails(messagePackHelper.getString(KEY_TRAIN_ID), messagePackHelper.getString(KEY_BASE_TRAIN_TYPE), false);
		unlimitedTrains = transportMode.continuousMovement || messagePackHelper.getBoolean(KEY_UNLIMITED_TRAINS);
		maxTrains = messagePackHelper.getInt(KEY_MAX_TRAINS);
		messagePackHelper.iterateArrayValue(KEY_ROUTE_IDS, value -> {
			if (routeIds.size() < MAX_ROUTE_IDS) {
				routeIds.add(value.asIntegerValue().asLong());
			}
		});
		// Saved before the fleet: as many as the siding held
		fleetSize = Math.max(-1, messagePackHelper.getInt(KEY_FLEET_SIZE, getTrainLimit()));
		// An old save has none, and plans by estimates until measuring finds them off
		final List<Long> recordedList = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_RECORDED_LEGS, value -> recordedList.add(value.asIntegerValue().asLong()));
		recordedLegs = recordedList.isEmpty() ? DepotLegs.NONE : DepotLegs.fromList(recordedList);
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
		messagePackHelper.iterateArrayValue(KEY_SHED, value -> shed.add(new TrainServer(id, railLength, timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, dwellTime, RailwayData.castMessagePackValueToSKMap(value))));
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
		readOperation(packet);
		recordedLegs = DepotLegs.readPacket(packet);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		RailwayData.writeMessagePackDataset(messagePacker, trains, KEY_TRAINS);
		final List<TrainServer> kept = new ArrayList<>(shed);
		kept.addAll(arriving);
		RailwayData.writeMessagePackDataset(messagePacker, kept, KEY_SHED);
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_RAIL_LENGTH).packFloat(railLength);
		messagePacker.packString(KEY_TRAIN_ID).packString(trainId);
		messagePacker.packString(KEY_BASE_TRAIN_TYPE).packString(baseTrainType);
		messagePacker.packString(KEY_UNLIMITED_TRAINS).packBoolean(unlimitedTrains);
		messagePacker.packString(KEY_MAX_TRAINS).packInt(maxTrains);
		messagePacker.packString(KEY_ROUTE_IDS).packArrayHeader(routeIds.size());
		for (final long routeId : routeIds) {
			messagePacker.packLong(routeId);
		}
		messagePacker.packString(KEY_FLEET_SIZE).packInt(fleetSize);
		final List<Long> recordedList = recordedLegs.toList();
		messagePacker.packString(KEY_RECORDED_LEGS).packArrayHeader(recordedList.size());
		for (final long value : recordedList) {
			messagePacker.packLong(value);
		}
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
		return super.messagePackLength() + 21;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 2;
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
		writeOperation(packet);
		recordedLegs.writePacket(packet);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_DECELERATION_CONSTANT:
				decelerationConstant = clampDeceleration(packet.readFloat());
				break;
			case KEY_OPERATION:
				readOperation(packet);
				break;
			case KEY_RECORDED_LEGS:
				recordedLegs = DepotLegs.readPacket(packet);
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
				// Set in the game's siding screen, the limit is the fleet too
				fleetSize = getTrainLimit();
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
		fleetSize = getTrainLimit();
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
	 * @return the routes this siding's trains run, of its depot's; empty for all of them
	 */
	public Set<Long> getRouteIds() {
		return Collections.unmodifiableSet(routeIds);
	}

	/**
	 * @return how many trains run from the siding for a depot running by duties, or -1 for as many as needed
	 */
	public int getFleetSize() {
		return fleetSize;
	}

	/**
	 * @return the keys of the duties whose paths the last path generation couldn't find
	 */
	public List<String> getLastFailedDuties() {
		return lastFailedDuties;
	}

	public DepotLegs getDepotLegs() {
		return lastDepotLegs;
	}

	public void setDepotLegs(DepotLegs depotLegs) {
		lastDepotLegs = depotLegs;
	}

	/**
	 * Each train of the siding waiting on the line with no duty to go on with: its slot, the stop it waits at and why.
	 */
	public void forEachHeldTrain(HeldTrainConsumer consumer) {
		trains.forEach(train -> {
			final transitsim.sim.TrainService<?> service = train.getService();
			if (service.getOvernightHold() != null) {
				consumer.accept(service.getDutySlot(), service.getHeldStopId(), service.getOvernightHold().name());
			}
		});
	}

	/**
	 * Takes the train of the slot waiting on the line with no duty straight back to the siding.
	 *
	 * @return whether there was one
	 */
	public boolean recallHeldTrain(int slot) {
		for (final TrainServer train : trains) {
			final transitsim.sim.TrainService<?> service = train.getService();
			if (service.getOvernightHold() != null && service.getDutySlot() == slot && service.recallHeld()) {
				return true;
			}
		}
		return false;
	}

	@FunctionalInterface
	public interface HeldTrainConsumer {
		void accept(int slot, long stopId, String reason);
	}

	public DepotLegs getRecordedLegs() {
		return recordedLegs;
	}

	/**
	 * Keeps the ways out and back the duties are planned by, and sends them to the clients.
	 */
	public void setRecordedLegs(DepotLegs legs, Consumer<FriendlyByteBuf> sendPacket) {
		recordedLegs = legs;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_RECORDED_LEGS);
		legs.writePacket(packet);
		sendPacket.accept(packet);
	}

	/**
	 * Sets the routes the siding's trains run (empty for all of its depot's) and its fleet (-1 for as many as needed).
	 */
	public void setOperation(Collection<Long> newRouteIds, int newFleetSize, Consumer<FriendlyByteBuf> sendPacket) {
		routeIds.clear();
		newRouteIds.stream().limit(MAX_ROUTE_IDS).forEach(routeIds::add);
		fleetSize = Mth.clamp(newFleetSize, -1, MAX_TRAINS + 1);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_OPERATION);
		writeOperation(packet);
		sendPacket.accept(packet);
	}

	private void writeOperation(FriendlyByteBuf packet) {
		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);
		packet.writeInt(fleetSize);
	}

	private void readOperation(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		if (count < 0 || count > MAX_ROUTE_IDS) {
			throw new IllegalArgumentException("Too many routes for a siding: " + count);
		}
		routeIds.clear();
		for (int i = 0; i < count; i++) {
			routeIds.add(packet.readLong());
		}
		fleetSize = Mth.clamp(packet.readInt(), -1, MAX_TRAINS + 1);
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
	 * Running by duties, the trains of its pool kept in the siding beyond the one standing there, oldest first (P2-C), and
	 * the ones back in this tick, kept from the next ({@link #keepArrived}). Saved as its trains are.
	 */
	private final List<TrainServer> shed = new ArrayList<>();
	private final List<TrainServer> arriving = new ArrayList<>();

	/**
	 * @return the trains kept in its shed, the ones back in this tick among them
	 */
	public int getShedCount() {
		return shed.size() + arriving.size();
	}

	/**
	 * @return the trains standing in the siding and kept in its shed, for whether it has room for one more
	 */
	public int getHeldCount() {
		return (int) trains.stream().filter(train -> !train.getIsOnRoute()).count() + getShedCount();
	}

	/**
	 * The trains back in its shed in this tick are kept there from the next, as in the component's runner.
	 */
	public void keepArrived() {
		shed.addAll(arriving);
		arriving.clear();
	}

	/**
	 * @return the same train going on as a train of this siding (P2-C, R1): a new train of the siding, with its run state
	 * made afresh, holding the train's identity, so its ID, cargo, riders and ANTE's settings are the same
	 */
	private TrainServer adopt(TrainServer train) {
		return train.sidingId == id ? train : new TrainServer(train, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime());
	}

	/**
	 * Has the siding keep the trains the component's simulator keeps in its shed (P2-C), each the game's train with its ID
	 * wherever it was.
	 */
	public void showSimulatedShed(List<Long> shedIds, Map<Long, TrainServer> known) {
		arriving.clear();
		final Map<Long, TrainServer> own = new HashMap<>();
		shed.forEach(train -> own.put(train.id, train));
		shed.clear();
		shedIds.forEach(trainId -> {
			final TrainServer train = own.containsKey(trainId) ? own.get(trainId) : known.get(trainId);
			shed.add(train == null ? new TrainServer(trainId, id, railLength, this.trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime()) : adopt(train));
		});
	}

	/**
	 * Adds its trains, those standing and those kept in its shed, to an index by ID.
	 */
	public void indexTrains(Map<Long, TrainServer> index) {
		trains.forEach(train -> index.put(train.id, train));
		shed.forEach(train -> index.put(train.id, train));
		arriving.forEach(train -> index.put(train.id, train));
	}

	/**
	 * @return how many of its trains are on their way back to another siding
	 */
	public int countReturningTo(long otherSidingId) {
		return (int) trains.stream().filter(train -> train.isReturningTo(otherSidingId)).count();
	}

	/**
	 * How its trains run on the server: its pool's vehicle (R1), set at the start of each tick; null until then, and on the
	 * client, where the siding's own settings show.
	 */
	private VehicleSettings vehicle;

	/**
	 * @return how its trains run as set for the siding itself, which its pool takes when it is its first siding and the
	 * depot has none set for it
	 */
	public VehicleSettings getOwnVehicleSettings() {
		return new VehicleSettings(accelerationConstant, decelerationConstant, isManual, maxManualSpeed, dwellTime);
	}

	public float getAccelerationConstant() {
		return vehicle == null || transportMode.continuousMovement ? accelerationConstant : vehicle.acceleration;
	}

	/**
	 * @return how fast trains brake, which is how fast they speed up unless set
	 */
	public float getDecelerationConstant() {
		return vehicle == null || transportMode.continuousMovement ? decelerationConstant <= 0 ? accelerationConstant : decelerationConstant : vehicle.getDeceleration();
	}

	public int getManualToAutomaticTime() {
		return vehicle == null || transportMode.continuousMovement ? dwellTime : vehicle.manualToAutomaticTime;
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
			// Turning speed curves on or off, or how the pool's vehicle speeds up and brakes (R1), changes how long the path takes
			final VehicleSettings newVehicle = depot.getVehicleSettings(this);
			// Made with its own settings, the siding's times change only once its pool's differ
			final boolean vehicleChanged = !transportMode.continuousMovement && !newVehicle.equals(vehicle == null ? getOwnVehicleSettings() : vehicle);
			vehicle = newVehicle;
			if (depot.speedCurves != useSpeedCurve || vehicleChanged) {
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
		foundNoWayOut.clear();
		foundNoWayBack.clear();
		final Map<String, List<PathData>> returnTails = new HashMap<>();
		// The duties' turn back points come after the usual path's
		final List<TurnBackPoint> dutyPoints = new ArrayList<>();
		final int firstDutyPoint = tempTurnBacks.size();
		dutyPlans.forEach(dutyPlan -> {
			if (!addDutyPath(tempTripPaths, dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, poolSidings, returnTails, dutyPoints, firstDutyPoint)) {
				failedDuties.add(dutyPlan.key);
			}
		});
		failedDuties.forEach(key -> System.out.println("The path of a duty can't be found: " + key));
		lastFailedDuties = Collections.unmodifiableList(new ArrayList<>(failedDuties));
		lastDepotLegs = DepotLegs.of(tempTripPaths.values()).withUnreachable(new TreeSet<>(foundNoWayOut), new TreeSet<>(foundNoWayBack));
		final TurnBacks newTurnBacks = tempTurnBacks.with(dutyPoints);

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
	 * For a depot running by duties, where the routes aren't joined into one path: the siding alone is the path, and the
	 * trains run on their duties' paths. The duties' paths are always taken, so one route that can't be reached from
	 * another doesn't keep the old ones.
	 */
	public void generateDutyRoute(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed, List<DutyPlan<PathData, SavedRailBase>> dutyPlans, List<Siding> poolSidings) {
		final Map<String, TripPath<PathData>> tempTripPaths = new HashMap<>();
		final List<String> failedDuties = new ArrayList<>();
		foundNoWayOut.clear();
		foundNoWayBack.clear();
		final Map<String, List<PathData>> returnTails = new HashMap<>();
		final List<TurnBackPoint> dutyPoints = new ArrayList<>();
		dutyPlans.forEach(dutyPlan -> {
			if (!addDutyPath(tempTripPaths, dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, poolSidings, returnTails, dutyPoints, 0)) {
				failedDuties.add(dutyPlan.key);
			}
		});
		failedDuties.forEach(key -> System.out.println("The path of a duty can't be found: " + key));
		lastFailedDuties = Collections.unmodifiableList(new ArrayList<>(failedDuties));
		lastDepotLegs = DepotLegs.of(tempTripPaths.values()).withUnreachable(new TreeSet<>(foundNoWayOut), new TreeSet<>(foundNoWayBack));
		final TurnBacks newTurnBacks = TurnBacks.NONE.with(dutyPoints);

		minecraftServer.execute(() -> {
			try {
				path.clear();
				generateDefaultPath(rails);
				timeSegments.clear();
				stopTimes.copyFrom(new StopTimes());
				platformTimes.clear();
				generateDistances();
				repeatIndex1 = 0;
				repeatIndex2 = 0;
				loopJoinIndex = 0;
				tripPaths.clear();
				tripPaths.putAll(tempTripPaths);
				turnBacks = newTurnBacks;
				recordPaths();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * The path of a duty from this siding (design document 6.12, block E2): out to its first stop, unless the train starts
	 * where it stayed overnight, its segments, and back to the siding unless it stays on the line.
	 *
	 * @return false if a part of it can't be found
	 */
	/**
	 * @param dutyPoints     the turn back points of the duties so far, to which this duty's are added, or null to give the
	 *                       duty only its usual way round each
	 * @param firstDutyPoint the number the first of the duties' points has among the siding's points
	 */
	private boolean addDutyPath(Map<String, TripPath<PathData>> tempTripPaths, DutyPlan<PathData, SavedRailBase> dutyPlan, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, int cruisingAltitude, boolean useFastSpeed, List<Siding> poolSidings, Map<String, List<PathData>> returnTails, List<TurnBackPoint> dutyPoints, int firstDutyPoint) {
		final List<PathData> wayOut = new ArrayList<>();
		if (!dutyPlan.startsOnLine) {
			PathFinder.findPath(wayOut, rails, graph, Arrays.asList(this, dutyPlan.firstPlatform), null, 0, cruisingAltitude, useFastSpeed);
			if (wayOut.isEmpty()) {
				// No way out to the first stop (P2-A), told only by path generation itself, not a draft's
				if (dutyPoints != null && dutyPlan.firstPlatform != null) {
					foundNoWayOut.add(dutyPlan.firstPlatform.id);
				}
				return false;
			}
		}
		final List<PathData> pathToDepot = dutyPlan.staysOnLine ? null : getReturnTail(dutyPlan, this, rails, graph, cruisingAltitude, useFastSpeed, returnTails);
		if (!dutyPlan.staysOnLine && pathToDepot == null) {
			if (dutyPoints != null && dutyPlan.lastPlatform != null) {
				foundNoWayBack.add(dutyPlan.lastPlatform.id);
			}
			return false;
		}

		// Each place the duty can turn back more than one way is a point of the siding's own
		final List<TurnBackSection<PathData>> sections = dutyPoints == null ? Collections.emptyList() : dutyPlan.turnBackSections;
		final int[] points = new int[sections.size()];
		for (int i = 0; i < sections.size(); i++) {
			points[i] = firstDutyPoint + dutyPoints.size();
			dutyPoints.add(new TurnBackPoint(sections.get(i).ways, sections.get(i).defaultWay, 0));
		}
		final int pointCount = sections.isEmpty() ? 0 : points[points.length - 1] + 1;

		final DutyBody usual = new DutyBody(dutyPlan, sections, -1, -1);
		final List<PathData> dutyPath = usual.join(wayOut);
		final int[] usualStops = usual.getTurnBackStops(points, pointCount, wayOut);
		if (dutyPlan.staysOnLine) {
			addDutyTripPath(tempTripPaths, dutyPlan, dutyPath, usual.getStarts(wayOut), dutyPlan.key, -1, -1, usualStops);
		} else {
			final List<PathData> ownPath = new ArrayList<>(dutyPath);
			PathFinder.appendPath(ownPath, pathToDepot);
			addDutyTripPath(tempTripPaths, dutyPlan, ownPath, usual.getStarts(wayOut), dutyPlan.key, -1, -1, usualStops);
			// The same duty going back to each other siding of the pool, for a train going where a train is needed next
			for (final Siding poolSiding : poolSidings) {
				final List<PathData> pathToOther = poolSiding == this ? null : getReturnTail(dutyPlan, poolSiding, rails, graph, cruisingAltitude, useFastSpeed, returnTails);
				if (pathToOther != null) {
					final List<PathData> otherPath = new ArrayList<>(dutyPath);
					PathFinder.appendPath(otherPath, pathToOther);
					addDutyTripPath(tempTripPaths, dutyPlan, otherPath, usual.getStarts(wayOut), TripPath.getReturnKey(dutyPlan.key, poolSiding.id), -1, -1, usualStops);
				}
			}
		}

		// The other ways round, one point at a time, each going back to this siding; a train going back to another siding
		// takes the way back of the usual path from its last stop
		for (int i = 0; i < sections.size(); i++) {
			final TurnBackSection<PathData> section = sections.get(i);
			for (int way = 0; way < section.ways.size(); way++) {
				if (way == section.defaultWay || section.ways.get(way) == null) {
					continue;
				}
				final DutyBody other = new DutyBody(dutyPlan, sections, i, way);
				final List<PathData> otherPath = other.join(copyPath(wayOut));
				if (!dutyPlan.staysOnLine) {
					PathFinder.appendPath(otherPath, copyPath(pathToDepot));
				}
				addDutyTripPath(tempTripPaths, dutyPlan, otherPath, other.getStarts(wayOut), TripPath.getTurnBackKey(dutyPlan.key, points[i], way), points[i], way, other.getTurnBackStops(points, pointCount, wayOut));
			}
		}
		return true;
	}

	/**
	 * A duty's body as a train runs it, the usual way round every turn back point or another way round one of them.
	 */
	static final class DutyBody {

		private final List<PathData> body = new ArrayList<>();
		private final int[] segmentStarts;
		/**
		 * The rail of the body of the stop before each of the duty's turn back points.
		 */
		private final int[] stopRails;

		/**
		 * @param changedSection the section taken another way round, or -1
		 */
		DutyBody(DutyPlan<PathData, SavedRailBase> dutyPlan, List<TurnBackSection<PathData>> sections, int changedSection, int way) {
			final List<PathData> planBody;
			final int changedStart;
			final int changedEnd;
			final int extraRails;
			if (changedSection < 0) {
				planBody = dutyPlan.body;
				changedStart = Integer.MAX_VALUE;
				changedEnd = Integer.MAX_VALUE;
				extraRails = 0;
			} else {
				// The stop and the first stop of the next route stay the body's own, with their stop settings
				final TurnBackSection<PathData> section = sections.get(changedSection);
				final List<PathData> newWay = section.ways.get(way);
				final int length = section.getLength();
				planBody = new ArrayList<>(dutyPlan.body.subList(0, section.start + 1));
				planBody.addAll(newWay.subList(1, newWay.size() - 1));
				planBody.addAll(dutyPlan.body.subList(section.start + length - 1, dutyPlan.body.size()));
				changedStart = section.start;
				changedEnd = section.start + length - 1;
				extraRails = newWay.size() - length;
			}
			planBody.forEach(pathData -> body.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex)));
			segmentStarts = new int[dutyPlan.segmentStarts.length];
			for (int i = 0; i < segmentStarts.length; i++) {
				segmentStarts[i] = dutyPlan.segmentStarts[i] + (dutyPlan.segmentStarts[i] > changedStart ? extraRails : 0);
			}
			stopRails = new int[sections.size()];
			for (int i = 0; i < stopRails.length; i++) {
				final int start = sections.get(i).start;
				// A point within the way taken instead, like one after the station inside a way round its platforms, isn't passed
				stopRails[i] = i != changedSection && start > changedStart && start < changedEnd ? -1 : start + (start > changedStart ? extraRails : 0);
			}
		}

		/**
		 * Puts the body after the way out of the siding, whose last rail, when it is the first stop, keeps the body's stop time.
		 */
		List<PathData> join(List<PathData> wayOut) {
			final List<PathData> dutyPath = new ArrayList<>(wayOut);
			if (!dutyPath.isEmpty() && dutyPath.get(dutyPath.size() - 1).isSameRail(body.get(0))) {
				dutyPath.set(dutyPath.size() - 1, body.get(0));
				dutyPath.addAll(body.subList(1, body.size()));
			} else {
				dutyPath.addAll(body);
			}
			return dutyPath;
		}

		private int getBodyStart(List<PathData> wayOut) {
			return !wayOut.isEmpty() && wayOut.get(wayOut.size() - 1).isSameRail(body.get(0)) ? wayOut.size() - 1 : wayOut.size();
		}

		int[] getStarts(List<PathData> wayOut) {
			final int bodyStart = getBodyStart(wayOut);
			final int[] starts = new int[segmentStarts.length];
			for (int i = 0; i < starts.length; i++) {
				starts[i] = segmentStarts[i] + bodyStart;
			}
			return starts;
		}

		/**
		 * @return the rail of the stop before each of the siding's points, or -1 for those of other paths
		 */
		int[] getTurnBackStops(int[] points, int pointCount, List<PathData> wayOut) {
			final int[] stops = new int[pointCount];
			Arrays.fill(stops, -1);
			final int bodyStart = getBodyStart(wayOut);
			for (int i = 0; i < points.length; i++) {
				stops[points[i]] = stopRails[i] < 0 ? -1 : stopRails[i] + bodyStart;
			}
			return stops;
		}
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

	private void addDutyTripPath(Map<String, TripPath<PathData>> tempTripPaths, DutyPlan<PathData, SavedRailBase> dutyPlan, List<PathData> dutyPath, int[] dutyStarts, String key, int turnBackPoint, int turnBackWay, int[] turnBackStops) {
		int stopIndex = 1;
		for (int i = 1; i < dutyPath.size(); i++) {
			final PathData pathData = dutyPath.get(i);
			if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && dutyPath.get(i - 1).rail.railType != RailType.PLATFORM) {
				stopIndex++;
			}
			pathData.stopIndex = stopIndex;
		}
		final TripPath<PathData> newTripPath = new TripPath<>(dutyPlan, dutyStarts, dutyPath, key, turnBackPoint, turnBackWay, turnBackStops);
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
		// A depot running by duties has a train for each slot of the siding's pool, the sidings with the same vehicle,
		// within each siding's limit
		// A depot of trains has its sidings by duties only (R3), even before it has duties; vehicles moving all the time and
		// aircraft as before
		final boolean runsByDuties = depot.getRunDuties() != null || Depot.supportsTimetable(transportMode) && !transportMode.continuousMovement;

		final Set<Long> railProgressSet = new HashSet<>();
		final Set<TrainServer> trainsToRemove = new HashSet<>();
		for (final TrainServer train : trains) {
			if (train.isCurrentlyManual() && railwayDataDriveTrainModule.drive(train)) {
				trainsToSync.add(train);
			}

			train.setStopTimes(stopTimes, tripPaths, turnBacks);
			train.setSpeedCurve(useSpeedCurve, getDecelerationConstant());
			// As the pool's vehicle runs now (R1), for trains made before it was set or changed
			train.setVehicle(getAccelerationConstant(), getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime());
			train.lastSchedulesForPlatform = lastSchedulesForPlatform;
			if (train.simulateTrain(world, ticksElapsed, depot, dataCache, trainPositions, trainsInPlayerRange, schedulesForPlatform, trainDelays)) {
				trainsToSync.add(train);
			}
			// Back in another siding of the pool, maybe of another depot of the line, the same train is kept in its shed (P2-C)
			final long returnedTo = train.takeReturnedElsewhereSiding();
			if (returnedTo != 0) {
				trainsToRemove.add(train);
				final Siding target = dataCache == null ? null : dataCache.sidingIdMap.get(returnedTo);
				if (target != null && target != this) {
					target.arriving.add(target.adopt(train));
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
					// By duties, a second train standing here is kept in the shed rather than taken away
					if (runsByDuties) {
						arriving.add(train);
						continue;
					}
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

		if (runsByDuties) {
			// By duties, the trains are the pool's, across the sidings of the other depots of its line too (P2-C): a siding
			// with none standing takes the one kept longest in its shed, and a new train is made only while the pool has fewer
			// than it has in all, or than its duties need when that is not set; the siding's fleet is how many it holds
			if (trainCars > 0 && (trains.isEmpty() || spawnTrain)) {
				if (!shed.isEmpty()) {
					trains.add(shed.remove(0));
				} else {
					final int poolFleet = depot.getLinePoolFleet(this);
					final int poolTrains = depot.getPoolTrains(this);
					final boolean roomHere = fleetSize < 0 || getHeldCount() < fleetSize;
					final int poolSlots = poolFleet < 0 ? depot.getPoolSlots(this) : 0;
					// As many as needed: as many as its duties, each siding with one standing at least
					if (poolFleet >= 0 ? poolTrains < poolFleet && roomHere : trains.isEmpty() || poolTrains < poolSlots && (fleetSize < 0 || trains.size() < fleetSize)) {
						trains.add(new TrainServer(unlimitedTrains || maxTrains > 0 ? new Random().nextLong() : id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime()));
					}
				}
			}
		} else if (trainCars > 0 && (trains.isEmpty() || spawnTrain && (unlimitedTrains && trains.size() < MAX_UNLIMITED_TRAINS || trains.size() <= maxTrains))) {
			final TrainServer train = new TrainServer(unlimitedTrains || maxTrains > 0 ? new Random().nextLong() : id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime());
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
	/**
	 * @return whether a rail of any duty's path, which its ways out and back were measured along, is gone or changed, so
	 * the ways kept as measured no longer hold
	 */
	public boolean areTripPathsStale(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		return tripPaths.values().stream().anyMatch(tripPath -> RoutePaths.isStale(tripPath.path, rails));
	}

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
		return toSimulation(depotId, routeIds, toTracks, false);
	}

	/**
	 * @param byDuties whether the depot runs by duties, when the siding has its fleet rather than what its rails hold
	 */
	public SimDataset.SidingData toSimulation(long depotId, long[] routeIds, Function<List<PathData>, List<SimDataset.DataTrack>> toTracks, boolean byDuties) {
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
				id, depotId, routeIds, trainCars, TrainType.getSpacing(baseTrainType), railLength, getAccelerationConstant(), getDecelerationConstant(),
				byDuties ? fleetSize < 0 ? SimDataset.UNLIMITED_TRAINS : fleetSize - 1 : unlimitedTrains ? SimDataset.UNLIMITED_TRAINS : maxTrains, useSpeedCurve && !transportMode.continuousMovement, RailType.getDefaultMaxBlocksPerTick(transportMode),
				repeatIndex1, repeatIndex2, loopJoinIndex, toTracks.apply(path), turnBacksWriter.toBytes()
		);
		sidingData.manual = getIsManual();
		sidingData.poolKey = getPoolKey();
		// The pool across the depots of its operating line (SimDataset 7)
		final String lineUnitKey = depot == null ? "" : depot.getLineUnitKey();
		sidingData.linePool = lineUnitKey.isEmpty() ? "" : lineUnitKey + "|" + getPoolKey();
		// The trains its pool has in all (SimDataset 11, P2-C)
		sidingData.poolFleet = byDuties && depot != null ? depot.getLinePoolFleet(this) : -1;
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
		showSimulatedTrains(simulatedTrains, takenOver, Collections.emptyMap());
	}

	/**
	 * @param known the game's trains of the sidings the simulator runs, by ID, before any of them was shown, so a train the
	 *              simulator moved to this siding is the same train here (P2-C)
	 */
	public void showSimulatedTrains(List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> simulatedTrains, Set<Long> takenOver, Map<Long, TrainServer> known) {
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
				final TrainServer knownTrain = known.get(simulatedId);
				final TrainServer train = knownTrain != null ? adopt(knownTrain) : new TrainServer(simulatedId, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime());
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
		if (getShedCount() > 0) {
			final List<Long> ids = new ArrayList<>();
			shed.forEach(train -> ids.add(train.id));
			arriving.forEach(train -> ids.add(train.id));
			record.sheds.put(id, ids);
		}
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
			if (!addDutyPath(dutyPaths, dutyPlan, rails, graph, cruisingAltitude, useFastSpeed, Collections.emptyList(), returnTails, null, 0)) {
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
		return vehicle == null || transportMode.continuousMovement ? isManual : vehicle.manual;
	}

	public int getMaxManualSpeed() {
		return vehicle == null || transportMode.continuousMovement ? maxManualSpeed : vehicle.maxManualSpeed;
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

		trains.add(new TrainServer(id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, getAccelerationConstant(), timeSegments, getIsManual(), getMaxManualSpeed(), getManualToAutomaticTime()));
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
		return new PathTiming.Vehicle(railLength, trainCars, TrainType.getSpacing(baseTrainType), getAccelerationConstant(), getDecelerationConstant(), speedCurves, transportMode);
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
