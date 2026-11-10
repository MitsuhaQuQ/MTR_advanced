package mtr.data;

import mtr.SecurityConfig;
import transitsim.sim.TrainService;
import transitsim.io.BinaryKeyed;
import transitsim.io.ByteArrayBinary;
import transitsim.sim.RunRecord;
import transitsim.sim.SimDataset;
import transitsim.sim.TickRunner;
import mtr.advanced.MessagePackKeyed;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBacks;
import transitsim.plan.RouteTimetable;
import mtr.TrigCache;
import mtr.block.*;
import mtr.mappings.Utilities;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.motion.RunSegment;
import transitsim.motion.PlatformDwell;
import transitsim.motion.SpeedCurves;
import transitsim.sim.Occupancy;
import transitsim.sim.RunReservations;
import transitsim.sim.ThroatLocks;
import transitsim.sim.TrainRules;
import transitsim.motion.MotionState;
import transitsim.motion.MotionTrack;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * A train on the server. On top of {@link Train} it checks whether the rails ahead are taken
 * ({@link #isRailBlocked}), records delays, opens doors and platform screen doors, carries passengers on and off, and holds
 * and asks for runs in the run reservations so trains wait for each other on single track.
 * <p>
 * When the component's simulator runs its siding, {@link #setSimulated} hands it the simulator's train, and this train only
 * shows where that one is.
 */
public class TrainServer extends Train {

	private boolean canDeploy;
	private List<Map<UUID, Long>> trainPositions;
	private Map<Player, Set<TrainServer>> trainsInPlayerRange = new HashMap<>();
	private Map<Long, Map<BlockPos, TrainDelay>> trainDelays = new HashMap<>();
	private long routeId;
	private int updateRailProgressCounter;
	private int manualCoolDown;
	private Depot depot;
	private DataCache dataCache;
	private final TrainService<PathData> service;
	private final TrainRules.Train rules = new RulesTrain();
	private Level world;
	private boolean syncRepeatIndices;
	private int pathRepeatIndex1;
	private int pathRepeatIndex2;
	/**
	 * The turn back rails kept for this train, until it reaches {@link #turnBackUntil} on its path.
	 */
	private List<UUID> turnBackRails = Collections.emptyList();
	private int turnBackUntil;
	/**
	 * The end of the run this train of a duty was given, or -1, and the end of the one it has asked for.
	 */
	private int grantedRunEnd = -1;
	/**
	 * The station throats on the path the train locks its way through (L2), made for the path it has now.
	 */
	private ThroatLocks throatLocks;
	/**
	 * The interlocking zones {@link #throatLocks} was made with.
	 */
	private Map<UUID, Long> throatZones;
	private int requestedRunEnd = -1;
	/**
	 * Since when it has waited for the run it asked for, or 0 (D2).
	 */
	private long waitingSinceMillis;
	public Map<Long, List<ScheduleEntry>> lastSchedulesForPlatform = new HashMap<>();

	private List<TimeSegment> timeSegments;
	private PlatformDwell platformDwell;
	private StationCall stationCall;
	private final List<PathData> depotPath;
	private final List<Double> depotDistances;
	private final List<TimeSegment> depotTimeSegments;
	private int depotRepeatIndex1;
	private int depotRepeatIndex2;
	/**
	 * Read from a save made before the cruising cap was saved, so it is worked out again from where the train is on its
	 * first tick, as the simulation does (see {@link transitsim.sim.RunRecord.TrainRecord#cruiseCap}).
	 */
	private boolean cruiseCapMissing;
	/**
	 * The component's train this one shows while the simulator runs its siding (G1), or null while the game moves it, and
	 * its service as last copied in.
	 */
	private TickRunner<SimDataset.DataTrack>.HeadlessTrain simulated;
	/**
	 * The version of the run segment the clients were last sent, or 0 for none.
	 */
	private int sentSegmentVersion;
	private byte[] simulatedService = new byte[0];
	private Map<String, TripPath<PathData>> lastTripPaths = Collections.emptyMap();

	/**
	 * The cruising cap the train set off with (G0, contracts document 6.4): without it, a train carrying on from a save gets
	 * to its next stop at another time than it would have.
	 */
	private static final String KEY_CRUISE_CAP = "cruise_cap";
	/**
	 * The run layer's movement authority and run segment of the train (5.5), from the component, so a train carries on
	 * after a restart as it was going (0.41.0).
	 */
	private static final String KEY_AUTHORITY_END = "authority_end";
	private static final String KEY_RUN_SEGMENT = "run_segment";
	private double authorityEnd = Double.NaN;
	private transitsim.motion.RunSegment.Spec runSegmentSpec;
	private static final int TRAIN_UPDATE_DISTANCE = 128;
	private static final int TICKS_TO_SEND_RAIL_PROGRESS = 40;

	/**
	 * The rail progress of the front when the blocks under it were last checked (see {@link #getCheckPositions}).
	 */
	private double lastCheckedRailProgress = Double.NaN;
	private double lastCheckedSpeed;
	private long lastCheckedMillis;
	private static final double SWEEP_STEP = 0.5;

	public TrainServer(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		this(new TrainIdentity(), id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, timeSegments, isManual, maxManualSpeed, manualToAutomaticTime);
	}

	/**
	 * A train of a siding that is the same train as another, holding its identity (R1): as it goes on as a train of
	 * another siding of its pool, with its run state made afresh.
	 */
	public TrainServer(TrainServer same, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		this(same.identity, same.id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, timeSegments, isManual, maxManualSpeed, manualToAutomaticTime);
	}

	private TrainServer(TrainIdentity identity, long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		super(identity, id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
		service = new TrainService<>(trainId, new ServiceVehicle(), System::currentTimeMillis);
		service.setSiding(sidingId);
	}

	public TrainServer(
			long sidingId, float railLength, List<TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, map);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
		service = new TrainService<>(trainId, new ServiceVehicle(), System::currentTimeMillis);
		service.setSiding(sidingId);
		service.read(new MessagePackKeyed.Reader(new MessagePackHelper(map)));
		if (map.containsKey(KEY_CRUISE_CAP)) {
			cruiseCap = new MessagePackHelper(map).getFloat(KEY_CRUISE_CAP, Float.POSITIVE_INFINITY);
		} else {
			cruiseCapMissing = true;
		}
		final MessagePackHelper helper = new MessagePackHelper(map);
		authorityEnd = map.containsKey(KEY_AUTHORITY_END) ? helper.getDouble(KEY_AUTHORITY_END) : Double.NaN;
		runSegmentSpec = map.containsKey(KEY_RUN_SEGMENT) ? parseSegment(helper.getString(KEY_RUN_SEGMENT)) : null;
		repairLoaded();
	}

	private static transitsim.motion.RunSegment.Spec parseSegment(String text) {
		final String[] parts = text.split(",");
		if (parts.length != 6) {
			return null;
		}
		try {
			return new transitsim.motion.RunSegment.Spec(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), Double.parseDouble(parts[5]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Deprecated
	public TrainServer(
			long sidingId, float railLength, List<TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, compoundTag);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
		service = new TrainService<>(trainId, new ServiceVehicle(), System::currentTimeMillis);
		service.setSiding(sidingId);
		cruiseCapMissing = true;
		repairLoaded();
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);
		service.write(new MessagePackKeyed.Writer(messagePacker));
		messagePacker.packString(KEY_CRUISE_CAP).packFloat(cruiseCap);
		messagePacker.packString(KEY_AUTHORITY_END).packDouble(authorityEnd);
		final transitsim.motion.RunSegment.Spec spec = runSegmentSpec;
		messagePacker.packString(KEY_RUN_SEGMENT).packString(spec == null ? "" : spec.version + "," + spec.startMillis + "," + spec.startPosition + "," + spec.startSpeed + "," + spec.cap + "," + spec.target);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + TrainService.SAVED_KEYS + 3;
	}

	@Override
	public int getTotalDwellTicks() {
		return TrainRules.getTotalDwellTicks(rules, super.getTotalDwellTicks());
	}

	@Override
	protected boolean isHeldAtStop() {
		return TrainRules.isHeldAtStop(rules);
	}

	@Override
	protected void onLapComplete() {
		service.onLapComplete();
	}

	@Override
	protected void onStopReached() {
		service.onStopReached();
	}

	@Override
	protected void onDwell(int rail, float departureTicks) {
		final long platformId = rail >= 0 && path.get(rail).isPlatformTrack() && !service.isOutOfService() ? path.get(rail).getStopId() : 0;
		platformDwell = PlatformDwell.update(platformDwell, platformId, System.currentTimeMillis(), departureTicks);
	}

	void forEachRider(Consumer<Player> consumer) {
		ridingEntities.forEach(uuid -> {
			final Player player = world.getPlayerByUUID(uuid);
			if (player != null) {
				consumer.accept(player);
			}
		});
	}

	@Override
	protected void startUp(Level world, int trainCars, int trainSpacing, boolean isOppositeRail) {
		canDeploy = false;
		isOnRoute = true;
		elapsedDwellTicks = 0;
		speed = Train.ACCELERATION_DEFAULT;
		if (isOppositeRail) {
			railProgress += transitsim.motion.MotionConstants.getReversalAdvance(getMotionTrack(), nextStoppingIndex, trainCars * trainSpacing);
			reversed = !reversed;
		}
		nextStoppingIndex = getNextStoppingIndex();
		cruiseCap = getCruiseCap();
		super.startUp(world, trainCars, trainSpacing, isOppositeRail);
	}

	/**
	 * See {@link TrainRules#getCruiseCap}.
	 */
	private float getCruiseCap() {
		return TrainRules.getCruiseCap(rules);
	}

	@Override
	protected boolean openDoors() {
		return isCurrentlyManual ? doorTarget : TrainRules.openDoors(rules);
	}

	@Override
	protected void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	) {
		VehicleRidingServer.mountRider(world, ridingEntities, id, routeId, carX, carY, carZ, realSpacing, width, carYaw, carPitch, doorLeftOpen || doorRightOpen, isManualAllowed || doorLeftOpen || doorRightOpen, ridingCar, PACKET_UPDATE_TRAIN_PASSENGERS, player -> !isManualAllowed || doorLeftOpen || doorRightOpen || Train.isHoldingKey(player), player -> {
			if (isHoldingKey(player)) {
				manualCoolDown = 0;
			}
		});
	}

	@Override
	protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
		final AABB trainAABB = new AABB(positions[0], positions[positions.length - 1]).inflate(TRAIN_UPDATE_DISTANCE);
		final boolean[] playerNearby = {false};
		world.players().forEach(player -> {
			if (isPlayerRiding(player) || trainAABB.contains(player.position())) {
				if (!trainsInPlayerRange.containsKey(player)) {
					trainsInPlayerRange.put(player, new HashSet<>());
				}
				trainsInPlayerRange.get(player).add(this);
				playerNearby[0] = true;
			}
		});

		final BlockPos frontPos = RailwayData.newBlockPos(positions[reversed ? positions.length - 1 : 0]);
		final Set<BlockPos> checkPositions = getCheckPositions(frontPos);
		if (RailwayData.chunkLoaded(world, frontPos)) {
			checkPositions.forEach(checkPos -> {
				if (RailwayData.chunkLoaded(world, checkPos)) {
					final BlockState state = world.getBlockState(checkPos);
					final Block block = state.getBlock();

					if (block instanceof BlockTrainRedstoneSensor && BlockTrainSensorBase.matchesFilter(world, checkPos, routeId, speed)) {
						((BlockTrainRedstoneSensor) block).power(world, state, checkPos);
					}

					if ((block instanceof BlockTrainCargoLoader || block instanceof BlockTrainCargoUnloader) && BlockTrainSensorBase.matchesFilter(world, checkPos, routeId, speed)) {
						for (final Direction direction : Direction.values()) {
							final Container nearbyInventory = HopperBlockEntity.getContainerAt(world, checkPos.relative(direction));
							if (nearbyInventory != null) {
								if (block instanceof BlockTrainCargoLoader) {
									transferItems(nearbyInventory, inventory);
								} else {
									transferItems(inventory, nearbyInventory);
								}
							}
						}
					}
				}
			});
		}

		if (!ridingEntities.isEmpty() && RailwayData.chunkLoaded(world, frontPos)) {
			checkPositions.forEach(checkPos -> {
				if (RailwayData.chunkLoaded(world, checkPos) && world.getBlockState(checkPos).getBlock() instanceof BlockTrainAnnouncer) {
					final BlockEntity entity = world.getBlockEntity(checkPos);
					if (entity instanceof BlockTrainAnnouncer.TileEntityTrainAnnouncer && ((BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity).matchesFilter(routeId, speed)) {
						ridingEntities.forEach(uuid -> ((BlockTrainAnnouncer.TileEntityTrainAnnouncer) entity).announce(world.getPlayerByUUID(uuid)));
					}
				}
			});
		}

		return playerNearby[0];
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		// A train that can't leave skips this departure instead of queueing, so the timetable doesn't drift. By duties the
		// siding alone is its path, and the train leaves along its duty's path once the duty is due (end-to-end check)
		if (depot != null && (path.size() > 1 ? !isRailOccupied(1) : depot.getRunDuties() != null)) {
			depot.requestDeploy(sidingId, this);
		}
		// A train sent out for a duty also waits for the run out of the siding, like any other run
		return canDeploy && (!service.isOnDuty() || isRunGranted(1));
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		// The rail onto the next station's throat, until the train is given its way through (L2)
		final ThroatLocks locks = getThroatLocks();
		if (locks != null && locks.isBlocked(checkIndex, Math.max(getIndex(0, spacing, true), 0))) {
			markDelay(checkIndex);
			return true;
		}
		if (isRailOccupied(checkIndex)) {
			markDelay(checkIndex);
			return true;
		}
		return false;
	}

	/**
	 * Marks the train held up before this rail, for the delays shown on the route.
	 */
	private void markDelay(int checkIndex) {
		if (routeId != 0 && checkIndex < path.size()) {
			final BlockPos startingPos = path.get(checkIndex).startingPos;
			if (!trainDelays.containsKey(routeId)) {
				trainDelays.put(routeId, new HashMap<>());
			}
			if (!trainDelays.get(routeId).containsKey(startingPos)) {
				trainDelays.get(routeId).put(startingPos, new TrainDelay());
			}
			trainDelays.get(routeId).get(startingPos).delaying();
		}
	}

	/**
	 * Shows the component's train instead of moving this one (G1), or with null goes back to moving it, carrying on from
	 * where the component had it.
	 */
	@Override
	protected RunSegment.Spec getSegmentSpecToSend() {
		return simulated == null ? null : simulated.getSegmentSpec();
	}

	/**
	 * @return what the game shows of the train, for -Dmtr.debugPlace
	 */
	public String describeShown() {
		final TickRunner<SimDataset.DataTrack>.HeadlessTrain train = simulated;
		final net.minecraft.network.FriendlyByteBuf packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		writePacket(packet);
		final int[] window = getSendWindow();
		return "packet " + packet.readableBytes() + " bytes, sent pieces " + window[0] + "-" + window[1] + ", onRoute " + isOnRoute + ", path " + path.size() + (path == depotPath ? " (depot path)" : "") + ", progress " + Math.round(railProgress) + ", speed " + speed + ", key '" + service.getTripPathKey() + "'" + (train == null ? ", not simulated" : ", simulator: onRoute " + train.isOnRoute() + ", progress " + Math.round(train.getRailProgress()) + ", path " + train.getPath().size());
	}

	private boolean missingPathTold;

	public void setSimulated(TickRunner<SimDataset.DataTrack>.HeadlessTrain newSimulated) {
		simulated = newSimulated;
		movedBySimulator = newSimulated != null;
		if (newSimulated == null) {
			simulatedService = new byte[0];
		}
	}

	/**
	 * Copies in the state of the component's train: its service and path when they changed, and where it is, how fast it
	 * goes, its stops and doors. What the simulator has as the train's is only shown here (A06).
	 */
	private void showSimulated() {
		final TickRunner<SimDataset.DataTrack>.HeadlessTrain train = simulated;
		final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
		try {
			train.service.write(values);
		} catch (IOException ignored) {
		}
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		values.writeTo(writer);
		final byte[] serviceBytes = writer.toBytes();
		if (!Arrays.equals(serviceBytes, simulatedService)) {
			simulatedService = serviceBytes;
			service.read(new BinaryKeyed.Reader(new ByteArrayBinary.Reader(serviceBytes)));
			final String key = service.getTripPathKey();
			// A path passing stops (D3) isn't among the siding's, and is made from its key
			final TripPath<PathData> tripPath = key.isEmpty() ? null : service.findPath(lastTripPaths, key);
			if (!key.isEmpty() && tripPath == null) {
				// The siding's paths were found again since the simulator took its data, as after a timetable changed, and
				// this one isn't among them: where the simulator has the train means nothing on the paths here, so the train
				// stays as it is until the simulator is started again on the new paths, and the service is read again then
				if (!missingPathTold) {
					missingPathTold = true;
					System.out.println("Minecraft Transit Railway: train " + id + " of siding " + sidingId + " can't be shown where the simulator has it, as the siding has no path " + key);
				}
				simulatedService = new byte[0];
				platformDwell = null;
				return;
			}
			if ((tripPath == null ? depotPath : tripPath.path) != path) {
				new ServiceVehicle().followPath(tripPath);
			}
		}
		final boolean wasOnRoute = isOnRoute;
		speed = train.getSpeed();
		railProgress = train.getRailProgress();
		elapsedDwellTicks = train.getElapsedDwellTicks();
		nextStoppingIndex = train.getNextStoppingIndex();
		nextPlatformIndex = train.getNextPlatformIndex();
		platformDwell = train.getPlatformDwell();
		reversed = train.isReversed();
		isOnRoute = train.isOnRoute();
		doorTarget = train.getDoorTarget();
		doorValue = train.getDoorValue();
		cruiseCap = (float) train.getCruiseCap();
		cruiseCapMissing = false;
		authorityEnd = train.getRecordAuthorityEnd();
		runSegmentSpec = train.getRecordSegment();
		if (repeatIndex1 != train.getRepeatIndex1() || repeatIndex2 != train.getRepeatIndex2()) {
			repeatIndex1 = train.getRepeatIndex1();
			repeatIndex2 = train.getRepeatIndex2();
			syncRepeatIndices = true;
		}
		turnBackRails = train.getTurnBackRails();
		turnBackUntil = train.getTurnBackUntil();
		if (wasOnRoute && !isOnRoute) {
			ridingEntities.clear();
		}
		if (train.getBlockedRail() >= 0) {
			markDelay(train.getBlockedRail());
		}
	}

	private boolean isRailOccupied(int checkIndex) {
		if (transportMode.continuousMovement || trainPositions == null || checkIndex >= path.size()) {
			return false;
		}
		for (final UUID unit : getCoveredUnits(checkIndex, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
			if (Occupancy.isTakenByOther(trainPositions, unit, id)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the block sections of a rail of the path a part of the path covers: the rail's own key, or the pieces of a
	 * rail cut into block sections, as the component's runner has them
	 */
	private List<UUID> getCoveredUnits(int index, double fromPosition, double toPosition) {
		final PathData pathData = path.get(index);
		final double start = index == 0 ? 0 : distances.get(index - 1);
		final double length = pathData.getTrackLength();
		return transitsim.network.BlockSections.getPieces(pathData.getRailProduct(), length, cutSpeed(pathData.rail.railType), pathData.startingPos.asLong() < pathData.getLastPos().asLong(), Math.max(0, fromPosition - start), Math.min(length, toPosition - start));
	}

	/**
	 * @return the speed limit a rail is cut into block sections by, or -1 for one never cut, as the component's runner has it
	 */
	public static float cutSpeed(RailType railType) {
		return railType.canAccelerate ? railType.maxBlocksPerTick : -1;
	}

		@Override
	protected double getSectionBlockedPosition(int rail, double fromPosition) {
		if (transportMode.continuousMovement || trainPositions == null || rail <= 0 || rail >= path.size() || !path.get(rail).rail.railType.hasSignal) {
			return Double.NaN;
		}
		final PathData pathData = path.get(rail);
		final double start = distances.get(rail - 1);
		for (final Map.Entry<Double, UUID> piece : transitsim.network.BlockSections.getPieceStarts(pathData.getRailProduct(), pathData.getTrackLength(), cutSpeed(pathData.rail.railType), pathData.startingPos.asLong() < pathData.getLastPos().asLong())) {
			final double pieceStart = start + piece.getKey();
			if (pieceStart >= fromPosition - 1E-6 && Occupancy.isTakenByOther(trainPositions, piece.getValue(), id)) {
				return pieceStart;
			}
		}
		return Double.NaN;
	}

	@Override
	protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
		return world.getNearestPlayer(trainX, trainY, trainZ, MAX_CHECK_DISTANCE, entity -> true) == null;
	}

	@Override
	protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
		if (block instanceof BlockPSDAPGDoorBase) {
			for (int i = -1; i <= 1; i++) {
				final BlockPos doorPos = checkPos.above(i);
				final BlockState state = world.getBlockState(doorPos);
				final Block doorBlock = state.getBlock();
				final BlockEntity entity = world.getBlockEntity(doorPos);

				if (doorBlock instanceof BlockPSDAPGDoorBase && entity instanceof BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase && IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.UNLOCKED)) {
					final int doorStateValue = (int) Mth.clamp(doorValue * DOOR_MOVE_TIME, 0, BlockPSDAPGDoorBase.MAX_OPEN_VALUE);
					((BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase) entity).setOpen(doorStateValue);

					if (doorStateValue > 0 && !world.getBlockTicks().hasScheduledTick(doorPos, doorBlock)) {
						/* This schedules the block tick to the door (Ensures the door will be closed when the train passes by) */
						Utilities.scheduleBlockTick(world, doorPos, doorBlock, dwellTicks);
					}
				}
			}
		}

		return false;
	}

	@Override
	protected double asin(double value) {
		return TrigCache.asin(value);
	}

	public boolean simulateTrain(Level world, float ticksElapsed, Depot depot, DataCache dataCache, List<Map<UUID, Long>> trainPositions, Map<Player, Set<TrainServer>> trainsInPlayerRange, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays) {
		this.trainPositions = trainPositions;
		this.trainsInPlayerRange = trainsInPlayerRange;
		this.trainDelays = trainDelays;
		this.depot = depot;
		this.dataCache = dataCache;
		setNetwork();
		final int oldStoppingIndex = nextStoppingIndex;
		final int oldPassengerCount = ridingEntities.size();
		final boolean oldIsCurrentlyManual = isCurrentlyManual;
		final boolean oldStopped = speed == 0;
		final boolean oldDoorOpen = doorTarget;
		// Turned back early, it runs empty from where everyone got off to where it takes riders again (D4)
		if (service.isOutOfService() && !ridingEntities.isEmpty()) {
			ridingEntities.clear();
		}

		this.world = world;
		if (simulated != null) {
			showSimulated();
			simulateTrain(world, ticksElapsed, depot);
		} else {
			service.updateOvernight();
			if (cruiseCapMissing) {
				cruiseCapMissing = false;
				if (isOnRoute && speed > 0) {
					cruiseCap = getCruiseCap();
				}
			}
			// Driven by hand, the train goes no further than its movement authority (run layer, 5.5): an automatic brake
			final double manualEnd = updateManualAuthority();
			simulateTrain(world, ticksElapsed, depot);
			limitToAuthority(manualEnd);
			holdRun();
			service.update(isOnRoute);
		}

		final int platformRail = platformDwell == null ? nextPlatformIndex : nextStoppingIndex;
		final RailwayData railwayData = prepareStationCall(platformRail);
		final int nextDepartureTicks = isOnRoute ? 0 : service.getStopTimes() == null ? depot.getNextDepartureMillis() : depot.getNextDepartureMillis(trainId, getLeadMillis());
		final boolean standingForDuty = !isOnRoute && depot.getRunDuties() != null;
		final long currentMillis = System.currentTimeMillis() - (long) (elapsedDwellTicks * Depot.MILLIS_PER_TICK) + (long) Math.max(0, nextDepartureTicks);

		double currentTime = -1;
		int startingIndex = 0;
		for (final TimeSegment timeSegment : timeSegments) {
			if (RailwayData.isBetween(railProgress, timeSegment.startRailProgress, timeSegment.endRailProgress)) {
				currentTime = timeSegment.getTime(railProgress);
				break;
			}
			startingIndex++;
		}

		if (currentTime >= 0) {
			float offsetTime = 0;
			float offsetTimeTemp = 0;
			boolean secondRound = false;
			Runnable addSchedule = null;
			routeId = 0;
			// A trip ending with a loop only comes round again on the loop, so the rest of the trip must not be counted twice
			final boolean startsOver = isRepeat() && !service.isOnTrip();
			for (int i = startingIndex; i < timeSegments.size() + (startsOver ? timeSegments.size() : 0); i++) {
				final TimeSegment timeSegment = timeSegments.get(i % timeSegments.size());

				if (timeSegment.savedRailBaseId != 0) {
					if (timeSegment.routeId == 0) {
						RailwayData.useRoutesAndStationsFromIndex(path.get(getSegmentIndex(timeSegment)).stopIndex - 1, tripRouteIds.isEmpty() ? depot.getRouteIds(trainId, dataCache) : tripRouteIds, dataCache, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
							timeSegment.routeId = thisRoute == null ? 0 : thisRoute.id;
							timeSegment.currentStationIndex = currentStationIndex;
						});
					}

					final long platformId = timeSegment.savedRailBaseId;
					if (!schedulesForPlatform.containsKey(platformId)) {
						schedulesForPlatform.put(platformId, new ArrayList<>());
					}

					if (secondRound) {
						offsetTime = offsetTimeTemp - timeSegment.endTime;
						secondRound = false;
					} else if (addSchedule != null) {
						addSchedule.run();
					}

					if (isOnRoute || nextDepartureTicks >= 0) {
						final long estimatedMillis = currentMillis + (long) ((timeSegment.endTime + offsetTime - currentTime) * Depot.MILLIS_PER_TICK);
						final long scheduledMillis = i < timeSegments.size() ? service.getPlannedArrivalMillis(getSegmentIndex(timeSegment)) : 0;
						// The estimate doesn't know about waiting for the timetable at earlier stops
						final long arrivalMillis = Math.max(estimatedMillis, scheduledMillis);
						if (railwayData != null && stationCall.dwell == null && stationCall.approachMillis == 0 && !timeSegment.pass
								&& platformId == stationCall.platformId && i < timeSegments.size() && getSegmentIndex(timeSegment) == platformRail) {
							stationCall.approachMillis = arrivalMillis;
							stationCall.routeId = timeSegment.routeId;
							railwayData.addStationCall(stationCall);
						}
						addSchedule = () -> {
							schedulesForPlatform.get(platformId).add(new ScheduleEntry(arrivalMillis, trainCars, timeSegment.routeId, timeSegment.currentStationIndex, id, timeSegment.pass, scheduledMillis, false, standingForDuty));
							// With -Dmtr.debugSchedules=<platform ID>, what each train tells that platform, every 10 s
							if (DEBUG_SCHEDULES != 0 && platformId == DEBUG_SCHEDULES && System.currentTimeMillis() / 50 % 200 == 0) {
								System.out.println("Minecraft Transit Railway schedule at " + platformId + ": train " + id + " siding " + sidingId + (service.isOnDuty() ? " duty " + service.getDutySlot() : " no duty") + ", route " + timeSegment.routeId + " stop " + timeSegment.currentStationIndex + ", in " + (arrivalMillis - System.currentTimeMillis()) / 1000 + " s, trip routes " + tripRouteIds + ", path stop " + path.get(getSegmentIndex(timeSegment)).stopIndex);
							}
						};
						if (!startsOver) {
							addSchedule.run();
							addSchedule = null;
						}
					}

					offsetTimeTemp = timeSegment.endTime;
				}

				if (routeId == 0) {
					routeId = timeSegment.routeId;
				}

				if (i == timeSegments.size() - 1) {
					secondRound = true;
				}
			}
		}

		updateRailProgressCounter++;
		if (updateRailProgressCounter == TICKS_TO_SEND_RAIL_PROGRESS) {
			updateRailProgressCounter = 0;
		}

		if (isManualAllowed) {
			if (isOnRoute) {
				if (manualCoolDown >= manualToAutomaticTime * 10) {
					if (isCurrentlyManual) {
						final int dwellTicks = nextStoppingIndex >= path.size() ? 0 : path.get(nextStoppingIndex).dwellTime * 10;
						elapsedDwellTicks = doorTarget ? dwellTicks / 2F : dwellTicks;
					}
					isCurrentlyManual = false;
				} else {
					manualCoolDown++;
					isCurrentlyManual = true;
				}
			} else {
				manualCoolDown = 0;
				isCurrentlyManual = true;
			}
		} else {
			isCurrentlyManual = false;
		}

		final boolean oldSyncRepeatIndices = syncRepeatIndices;
		syncRepeatIndices = false;
		// The clients follow each new run segment of a train the component's simulator runs (G2)
		final RunSegment.Spec segmentSpec = getSegmentSpecToSend();
		final int segmentVersion = segmentSpec == null ? 0 : segmentSpec.version;
		final boolean newSegment = segmentVersion != sentSegmentVersion;
		sentSegmentVersion = segmentVersion;
		return newSegment || oldPassengerCount > ridingEntities.size() || oldStoppingIndex != nextStoppingIndex || oldIsCurrentlyManual != isCurrentlyManual || oldStopped && speed != 0 || oldDoorOpen != doorTarget || oldSyncRepeatIndices;
	}

	/**
	 * Retains one identity as the ETA changes and the train stops, then retires it when the train leaves.
	 * Only a train on its path to the next passenger stop can advertise an approach.
	 */
	private RailwayData prepareStationCall(int rail) {
		if (!isOnRoute || service.isOutOfService() || rail < 0 || rail >= path.size()) {
			stationCall = null;
			return null;
		}
		final PathData stop = path.get(rail);
		final long platformId = stop.getStopId();
		if (!stop.isPlatformTrack() || stop.dwellTime <= 0 || platformId == 0 || platformDwell != null && platformDwell.platformId != platformId) {
			stationCall = null;
			return null;
		}
		if (platformDwell == null && railProgress >= getMotionTrack().getStopDistance(rail)) {
			// A newly attached simulator may need its first tick to report a stop already in progress.
			return null;
		}
		if (stationCall == null || stationCall.platformId != platformId || platformDwell == null && stationCall.dwell != null) {
			stationCall = new StationCall(platformId, this);
		}
		stationCall.approachMillis = 0;
		stationCall.dwell = platformDwell;
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null && platformDwell != null) {
			if (stationCall.routeId == 0) {
				RailwayData.useRoutesAndStationsFromIndex(stop.stopIndex - 1, tripRouteIds.isEmpty() ? depot.getRouteIds(trainId, dataCache) : tripRouteIds, dataCache, (index, route, nextRoute, station, nextStation, lastStation) -> {
					stationCall.routeId = route == null ? 0 : route.id;
				});
			}
			railwayData.addStationCall(stationCall);
		}
		return railwayData;
	}

	public void writeTrainPositions(List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks) {
		if (!path.isEmpty()) {
			final int headIndex = getIndex(0, spacing, true);
			final int tailIndex = getIndex(trainCars, spacing, false);
			final double tailPosition = railProgress - trainCars * spacing;
			for (int i = tailIndex; i <= headIndex; i++) {
				final PathData pathData = path.get(i);
				if (i > 0 && pathData.savedRailBaseId != sidingId && pathData.rail.railType.hasSignal) {
					// The block sections of the rail it covers: the whole rail, or the pieces of a long one
					final UUID rail = pathData.getRailProduct();
					for (final UUID unit : getCoveredUnits(i, tailPosition, railProgress)) {
						signalBlocks.occupy(rail, unit, trainPositions, id);
					}
				}
			}

			if (!turnBackRails.isEmpty()) {
				if (!isOnRoute || tailIndex >= turnBackUntil) {
					turnBackRails = Collections.emptyList();
				} else if (trainPositions.size() > 1) {
					turnBackRails.forEach(rail -> trainPositions.get(1).putIfAbsent(rail, id));
				}
			}
		}
	}

	/**
	 * @return the train as the component's simulator carries on from it (G0)
	 */
	public RunRecord.TrainRecord toRecord() {
		final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
		try {
			service.write(values);
		} catch (IOException ignored) {
		}
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		values.writeTo(writer);
		return new RunRecord.TrainRecord(sidingId, id, speed, railProgress, elapsedDwellTicks, nextStoppingIndex, nextPlatformIndex, reversed, isOnRoute, writer.toBytes(), cruiseCapMissing ? Float.NaN : cruiseCap, authorityEnd, runSegmentSpec);
	}

	public boolean needsAlignment() {
		return service.needsAlignment();
	}

	/**
	 * See {@link TrainService#alignToTimetable(boolean)}.
	 */
	public void alignToTimetable(Depot depot, DataCache dataCache, List<Map<UUID, Long>> trainPositions) {
		this.depot = depot;
		this.dataCache = dataCache;
		this.trainPositions = trainPositions;
		setNetwork();
		service.alignToTimetable(isOnRoute && !isCurrentlyManual && !transportMode.continuousMovement);
	}

	/**
	 * Moves this train onto a new path without replacing it, so riders, cargo and door state all stay.
	 */
	public void relocate(double newRailProgress, int railIndex, int newRepeatIndex1, int newRepeatIndex2) {
		railProgress = newRailProgress;
		pathRepeatIndex1 = newRepeatIndex1;
		pathRepeatIndex2 = newRepeatIndex2;
		depotRepeatIndex1 = newRepeatIndex1;
		depotRepeatIndex2 = newRepeatIndex2;
		setLastLap(service.isLastLap());
		nextStoppingIndex = path.size() - 1;
		for (int i = railIndex; i < path.size(); i++) {
			if (path.get(i).dwellTime > 0) {
				nextStoppingIndex = i;
				break;
			}
		}
		nextPlatformIndex = nextStoppingIndex;
	}

	public void deployTrain() {
		canDeploy = true;
	}

	/**
	 * See {@link TrainService#findDeployService(long)}.
	 */
	public long findDeployService(long lastDeployedMillis) {
		return service.findDeployService(lastDeployedMillis);
	}

	/**
	 * Sends this train out for a service of the timetable.
	 */
	public void deployTrain(long newTripMillis, long routeId) {
		canDeploy = true;
		service.deploy(newTripMillis, routeId);
	}

	/**
	 * Sends this train out for a slot's duty. See {@link TrainService#deployDuty}.
	 */
	public void deployDuty(int slot, long dayStart, TripPath<PathData> dutyPath) {
		deployDuty(slot, 0, dayStart, dutyPath);
	}

	/**
	 * @param dutySidingId the siding whose slot it is, when this train is sent in place of that siding's
	 */
	public void deployDuty(int slot, long dutySidingId, long dayStart, TripPath<PathData> dutyPath) {
		deployDuty(slot, dutySidingId, dayStart, dutyPath, 0);
	}

	/**
	 * @param startSegment the segment it starts the duty at, out of the siding (D5)
	 */
	public void deployDuty(int slot, long dutySidingId, long dayStart, TripPath<PathData> dutyPath, int startSegment) {
		canDeploy = true;
		service.deployDuty(slot, dutySidingId, dayStart, dutyPath, startSegment);
	}

	/**
	 * See {@link TrainService#takeReturnedElsewhere}.
	 */
	public boolean takeReturnedElsewhere() {
		return service.takeReturnedElsewhere();
	}

	/**
	 * See {@link TrainService#takeReturnedElsewhereSiding}.
	 */
	public long takeReturnedElsewhereSiding() {
		return service.takeReturnedElsewhereSiding();
	}

	/**
	 * @return whether the train is on its way back to that siding of its pool, not its own
	 */
	public boolean isReturningTo(long otherSidingId) {
		return isOnRoute && TripPath.getSkipBase(service.getTripPathKey()).endsWith(TripPath.getReturnKey("", otherSidingId));
	}

	/**
	 * Puts a train sent out much later than its duty needed at the first stop of its duty it can still keep to. See
	 * {@link TrainService#joinDutyLate}.
	 */
	public void joinDutyLate() {
		isOnRoute = true;
		canDeploy = false;
		service.joinDutyLate();
	}

	@Override
	protected boolean keepsOnLineAtEnd() {
		return service.endsOnLine();
	}

	/**
	 * Sets, every tick, the two way rails a train of a duty holds with its direction: from its tail to the end of its run,
	 * whether it is moving, waiting at a stop or held by a signal (design document 6.12, block E2, and fix F1). A rail is
	 * let go once the tail has left it, and everything once the train is back in its depot.
	 */
	private void holdRun() {
		final RunReservations reservations = getRunReservations();
		if (reservations == null) {
			return;
		}
		if (isOnRoute && isCurrentlyManual && manualAuthority != null) {
			// Its movement authority holds what it needs
			grantedRunEnd = -1;
			return;
		}
		if (!isOnRoute || isCurrentlyManual || !service.isOnDuty() || path.isEmpty()) {
			reservations.release(id);
			grantedRunEnd = -1;
			return;
		}
		if (grantedRunEnd < nextPlatformIndex) {
			grantedRunEnd = -1;
		}
		final List<RunReservations.Move> moves = getRunMoves(Math.max(findRunEnd(nextPlatformIndex), grantedRunEnd));
		final ThroatLocks locks = getThroatLocks();
		if (locks != null) {
			final int headIndex = Math.max(getIndex(0, spacing, true), 0);
			final int tailIndex = Math.max(getIndex(trainCars, spacing, true), 0);
			// Not while waiting to leave a stop, whose request is the run's
			if (requestedRunEnd < 0) {
				locks.approach(reservations, id, moves, headIndex, tailIndex, index -> index <= 0 ? -railProgress : distances.get(Math.min(index, distances.size()) - 1) - railProgress, 0.5 * speed * speed / Math.max(decelerationConstant, 1E-4F), getPlannedDepartureMillis());
			}
			moves.addAll(locks.getHeld(tailIndex));
		}
		reservations.hold(id, moves);
	}

	/**
	 * @return the locks of the station throats on the train's path (L2), made again when the path changes, or null if they
	 * are off or the train isn't running a duty
	 */
	private ThroatLocks getThroatLocks() {
		if (!ThroatLocks.enabled || !isOnRoute || isCurrentlyManual || !service.isOnDuty() || path.isEmpty()) {
			return null;
		}
		// Not on track shared with a depot not running by duties (LockNetworks)
		final RailwayData lockData = RailwayData.getInstance(world);
		final Depot lockDepot = lockData == null ? null : lockData.dataCache.sidingIdToDepot.get(sidingId);
		if (lockDepot != null && lockData.isThroatLocksOff(lockDepot.id)) {
			return null;
		}
		final Map<UUID, Long> zones = lockData == null ? Collections.emptyMap() : lockData.getSignalBlocks().getZones(SecurityConfig.interlockColor);
		// Made again when the path changes, or the interlocking zones do
		if (throatLocks == null || !throatLocks.isFor(path, path.size() + 1) || throatZones != zones) {
			final RailwayData railwayData = RailwayData.getInstance(world);
			if (railwayData == null) {
				return null;
			}
			throatZones = zones;
			final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
			final long[] nodes = new long[path.size() + 1];
			final List<Integer> stopRails = new ArrayList<>();
			for (int i = 0; i < path.size(); i++) {
				final PathData pathData = path.get(i);
				nodes[i] = pathData.startingPos.asLong();
				if (pathData.savedRailBaseId != 0 && (pathData.rail.railType == RailType.SIDING || pathData.rail.railType == RailType.PLATFORM && pathData.dwellTime > 0)) {
					stopRails.add(i);
				}
			}
			nodes[path.size()] = path.get(path.size() - 1).getLastPos().asLong();
			throatLocks = new ThroatLocks(path, nodes, stopRails.stream().mapToInt(Integer::intValue).toArray(), node -> {
				final Map<BlockPos, Rail> connections = rails.get(BlockPos.of(node));
				return connections == null ? 0 : connections.size();
			}, rail -> zones.getOrDefault(rail, 0L));
		}
		return throatLocks;
	}

	/**
	 * Asks for the run from a rail of the path to the next stop after it, which is given or not once the tick is over.
	 *
	 * @return true once the train holds the run
	 */
	/**
	 * @return how long it has waited for the run it asked for, or 0 (D2)
	 */
	public long getWaitingMillis() {
		return waitingSinceMillis == 0 ? 0 : System.currentTimeMillis() - waitingSinceMillis;
	}

	/**
	 * @return since when it has waited for the run it asked for, or 0 (D2)
	 */
	/**
	 * @return whether the train is out on the line, for the dispatcher's console (D6)
	 */
	private static final long DEBUG_SCHEDULES = Long.getLong("mtr.debugSchedules", 0);

	public boolean isOnRouteNow() {
		return isOnRoute;
	}

	/**
	 * @return the rail its head is on, or null, for the dispatcher's console (D6)
	 */
	public UUID getHeadRail() {
		if (path == null || path.isEmpty()) {
			return null;
		}
		final int index = Math.max(0, Math.min(path.size() - 1, getIndex(railProgress, false)));
		return path.get(index).getRailProduct();
	}

	/**
	 * @return the platform of its next stop, or 0
	 */
	public long getNextStopId() {
		return path != null && nextStoppingIndex >= 0 && nextStoppingIndex < path.size() ? path.get(nextStoppingIndex).savedRailBaseId : 0;
	}

	/**
	 * @return the platform of the last stop before its next one, the one it left or stands at, or 0, for the run monitor
	 */
	public long getLastStopId() {
		final int index = getLastStopIndex();
		return index < 0 ? 0 : path.get(index).savedRailBaseId;
	}

	/**
	 * @return {@link #STOP_STATE_RUNNING} between stops, {@link #STOP_STATE_AT_NEXT} standing at its next stop, or
	 * {@link #STOP_STATE_AT_LAST} standing at the stop before it, for the run monitor
	 */
	public int getStopState() {
		if (path == null || path.isEmpty() || getSpeed() > 0) {
			return STOP_STATE_RUNNING;
		}
		final int head = Math.max(0, Math.min(path.size() - 1, getIndex(railProgress, false)));
		if (head == nextStoppingIndex) {
			return STOP_STATE_AT_NEXT;
		}
		return head == getLastStopIndex() ? STOP_STATE_AT_LAST : STOP_STATE_RUNNING;
	}

	private int getLastStopIndex() {
		if (path == null) {
			return -1;
		}
		for (int i = Math.min(nextStoppingIndex, path.size()) - 1; i >= 0; i--) {
			final PathData pathData = path.get(i);
			if (pathData.dwellTime > 0 && pathData.savedRailBaseId != 0) {
				return i;
			}
		}
		return -1;
	}

	public static final int STOP_STATE_RUNNING = 0;
	public static final int STOP_STATE_AT_NEXT = 1;
	public static final int STOP_STATE_AT_LAST = 2;

	public long getWaitingSinceMillis() {
		return waitingSinceMillis;
	}

	private boolean isRunGranted(int fromIndex) {
		final RunReservations reservations = getRunReservations();
		if (reservations == null) {
			return true;
		}
		final int runEnd = findRunEnd(fromIndex);
		if (grantedRunEnd == runEnd) {
			return true;
		}
		final ThroatLocks locks = getThroatLocks();
		if (requestedRunEnd == runEnd && reservations.takeGrant(id)) {
			grantedRunEnd = runEnd;
			requestedRunEnd = -1;
			waitingSinceMillis = 0;
			if (locks != null) {
				locks.onGranted();
			}
			return true;
		}
		requestedRunEnd = runEnd;
		if (waitingSinceMillis == 0) {
			waitingSinceMillis = System.currentTimeMillis();
		}
		final List<RunReservations.Move> moves = getRunMoves(runEnd);
		if (locks != null) {
			// The way out through the throat of the stop it is leaving goes with the run, all of it or none
			final List<transitsim.network.ThroatRoutes.Item> exit = locks.getExit(fromIndex - 1);
			moves.addAll(locks.getHeld(Math.max(getIndex(trainCars, spacing, true), 0)));
			moves.addAll(ThroatLocks.toMoves(exit));
			locks.setRequested(exit);
		}
		reservations.request(id, moves, getPlannedDepartureMillis());
		return false;
	}

	/**
	 * @return the two way rails from the train's tail to this rail of its path, with the direction it runs along them
	 */
	private List<RunReservations.Move> getRunMoves(int runEnd) {
		final RailwayData railwayData = RailwayData.getInstance(world);
		final List<RunReservations.Move> moves = new ArrayList<>();
		if (railwayData == null) {
			return moves;
		}
		final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRails();
		// Rounding down keeps the rail the tail is just leaving
		for (int i = Math.max(getIndex(trainCars, spacing, true), 0); i <= Math.min(runEnd, path.size() - 1); i++) {
			final PathData pathData = path.get(i);
			final Rail backwards = DataCache.tryGet(rails, pathData.getLastPos(), pathData.startingPos);
			if (backwards != null && backwards.railType != RailType.NONE) {
				moves.add(new RunReservations.Move(pathData.startingPos.asLong(), pathData.getLastPos().asLong()));
			}
		}
		// A train turning back at the stop its run ends at takes the way back out of it too (掉头车优先), as the component's
		final int turnRail = Math.min(runEnd, path.size() - 1) + 1;
		if (turnRail > 0 && turnRail < path.size() && path.get(turnRail - 1).isOppositeRail(path.get(turnRail))) {
			final PathData turnData = path.get(turnRail);
			final Rail backwards = DataCache.tryGet(rails, turnData.getLastPos(), turnData.startingPos);
			if (backwards != null && backwards.railType != RailType.NONE) {
				moves.add(new RunReservations.Move(turnData.startingPos.asLong(), turnData.getLastPos().asLong()));
			}
		}
		return moves;
	}

	private int findRunEnd(int fromIndex) {
		return TrainRules.findRunEnd(getMotionTrack(), fromIndex);
	}

	private long getPlannedDepartureMillis() {
		return TrainRules.getPlannedDepartureMillis(rules);
	}

	/**
	 * The movement authority of a train driven by hand (run layer, 5.5), asked for as far as it takes to stop and a little
	 * more, through the same arbiter as the trains running by themselves; and the path's tracks as the arbiter reads them.
	 */
	private transitsim.run.Authority manualAuthority;
	private List<transitsim.sim.SimDataset.DataTrack> manualTracks;
	private List<PathData> manualTracksFor;

	/**
	 * @return how far the train driven by hand may run, or NaN if it isn't driven by hand on the line
	 */
	private double updateManualAuthority() {
		final RailwayData railwayData = world == null ? null : RailwayData.getInstance(world);
		if (railwayData == null || !isOnRoute || !isCurrentlyManual || path.isEmpty() || transportMode.continuousMovement) {
			if (manualAuthority != null) {
				manualAuthority.release();
				manualAuthority = null;
			}
			return Double.NaN;
		}
		if (manualAuthority == null) {
			manualAuthority = new transitsim.run.Authority(id, this::getRunReservations);
		}
		if (manualTracksFor != path) {
			manualTracks = mtr.advanced.SimExport.toTracks(path, railwayData.getRails());
			manualTracksFor = path;
		}
		final List<transitsim.sim.SimDataset.DataTrack> tracks = manualTracks;
		final List<Double> ends = distances;
		final transitsim.run.PathClaims.Path<transitsim.sim.SimDataset.DataTrack> claimPath = new transitsim.run.PathClaims.Path<transitsim.sim.SimDataset.DataTrack>() {
			@Override
			public List<transitsim.sim.SimDataset.DataTrack> getTracks() {
				return tracks;
			}

			@Override
			public double getEnd(int track) {
				return ends.get(track);
			}

			@Override
			public boolean turnsBack(int track, int nextTrack) {
				return tracks.get(track).isOppositeOf(tracks.get(nextTrack));
			}
		};
		final double head = railProgress;
		final double braking = 0.5 * speed * speed / Math.max(decelerationConstant, 1E-4F) + speed + ThroatLocks.APPROACH_MARGIN;
		final double target = Math.min(distances.get(distances.size() - 1), head + braking);
		return manualAuthority.update(claimPath, path, Math.max(0, head - trainCars * spacing), head, target, null, System.currentTimeMillis());
	}

	/**
	 * Brakes a train driven by hand so it stops where its authority ends, and never runs past it.
	 */
	private void limitToAuthority(double end) {
		if (Double.isNaN(end) || !isCurrentlyManual) {
			return;
		}
		if (railProgress > end) {
			railProgress = end;
			speed = 0;
		} else {
			final float allowed = (float) Math.sqrt(2 * Math.max(decelerationConstant, 1E-4F) * Math.max(0, end - railProgress));
			if (speed > allowed) {
				speed = allowed;
			}
		}
	}

	private RunReservations getRunReservations() {
		final RailwayData railwayData = world == null ? null : RailwayData.getInstance(world);
		return railwayData == null ? null : railwayData.runReservations;
	}

	/**
	 * @param depotStopTimes the stop times of the usual path of the siding
	 * @param newTripPaths   the paths of services leaving it
	 */
	public void setStopTimes(StopTimes depotStopTimes, Map<String, TripPath<PathData>> newTripPaths) {
		lastTripPaths = newTripPaths;
		service.setStopTimes(depotStopTimes, newTripPaths);
	}

	/**
	 * @param turnBacks where trains of the siding can turn back on more than one rail
	 */
	public void setStopTimes(StopTimes depotStopTimes, Map<String, TripPath<PathData>> newTripPaths, TurnBacks turnBacks) {
		lastTripPaths = newTripPaths;
		service.setStopTimes(depotStopTimes, newTripPaths, turnBacks);
	}

	public boolean isOnTripPath() {
		return service.isOnTripPath();
	}

	public void setDepotRepeatIndices(int newRepeatIndex1, int newRepeatIndex2) {
		depotRepeatIndex1 = newRepeatIndex1;
		depotRepeatIndex2 = newRepeatIndex2;
	}

	/**
	 * Puts a train from a save back on its path.
	 *
	 * @return false if that path no longer exists
	 */
	/**
	 * How its siding makes paths passing stops (D3).
	 */
	public void setPathVariants(transitsim.plan.PathVariants<PathData> pathVariants) {
		service.setPathVariants(pathVariants);
	}

	public boolean restoreTripPath(Map<String, TripPath<PathData>> newTripPaths) {
		return service.restoreTripPath(newTripPaths);
	}

	/**
	 * See {@link TrainService#getLeadMillis()}.
	 */
	public long getLeadMillis() {
		return service.getLeadMillis();
	}

	private void setNetwork() {
		service.setNetwork(depot, dataCache == null ? null : dataCache.routeIdMap::get, dataCache == null ? null : dataCache.routeIdToOneDepot::get);
	}

	private void setLastLap(boolean isLastLap) {
		final int newRepeatIndex1 = isLastLap ? 0 : pathRepeatIndex1;
		final int newRepeatIndex2 = isLastLap ? 0 : pathRepeatIndex2;
		syncRepeatIndices = syncRepeatIndices || repeatIndex1 != newRepeatIndex1 || repeatIndex2 != newRepeatIndex2;
		repeatIndex1 = newRepeatIndex1;
		repeatIndex2 = newRepeatIndex2;
	}

	/**
	 * A train planned to pass this station while this one is stopped has not gone yet, on another platform of the station.
	 */
	private boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis) {
		final Station station = dataCache == null ? null : dataCache.platformIdToStation.get(platformId);
		if (station == null) {
			return false;
		}
		for (final Map.Entry<Long, List<ScheduleEntry>> entry : lastSchedulesForPlatform.entrySet()) {
			if (entry.getKey() != platformId && dataCache.platformIdToStation.get(entry.getKey()) == station) {
				for (final ScheduleEntry scheduleEntry : entry.getValue()) {
					if (scheduleEntry.trainId != id && scheduleEntry.arrivalMillis >= currentMillis && scheduleEntry.scheduledMillis >= arrivalMillis && scheduleEntry.scheduledMillis <= departureMillis) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private int getNextStoppingIndex() {
		return TrainRules.findNextStop(getMotionTrack(), railProgress);
	}

	/**
	 * The blocks a sensor, a cargo loader or an announcer can be in to be set off by the train in this tick: around the
	 * front of the train as before, and under each block the front passed since the last tick, so a fast train or one
	 * moving several ticks at once when the server is slow doesn't go past one without setting it off (G3). A jump much
	 * longer than the train could run, as when it is put somewhere else, sets off nothing on the way.
	 */
	private Set<BlockPos> getCheckPositions(BlockPos frontPos) {
		final Set<BlockPos> checkPositions = new LinkedHashSet<>();
		checkBlock(frontPos, checkPositions::add);
		final long millis = System.currentTimeMillis();
		// By the clock, not the ticks the game counted, which stop at a few however slow the server is, while a train the
		// component moves goes on for all the time that passed
		final float ticksPassed = lastCheckedMillis == 0 ? 1 : (float) (millis - lastCheckedMillis) / Depot.MILLIS_PER_TICK;
		lastCheckedMillis = millis;
		if (!path.isEmpty()) {
			for (final double sweptRailProgress : getSweptRailProgresses(lastCheckedRailProgress, railProgress, Math.max(Math.abs(speed), lastCheckedSpeed), ticksPassed)) {
				final BlockPos pos = RailwayData.newBlockPos(getFrontPosition(sweptRailProgress));
				for (int y = 0; y <= 3; y++) {
					checkPositions.add(pos.below(y));
				}
			}
		}
		lastCheckedRailProgress = railProgress;
		lastCheckedSpeed = Math.abs(speed);
		return checkPositions;
	}

	/**
	 * @param fromRailProgress where the front was when last checked, or NaN
	 * @param speed            the faster of the speeds then and now, in blocks a tick
	 * @param ticksPassed      the time since then, in ticks
	 * @return the rail progresses of the front to check under on the way to where it is now, every half block, or none if
	 * it moved back, hardly moved or jumped further than it could run
	 */
	public static List<Double> getSweptRailProgresses(double fromRailProgress, double toRailProgress, double speed, float ticksPassed) {
		final List<Double> sweptRailProgresses = new ArrayList<>();
		final double passed = toRailProgress - fromRailProgress;
		final double maxPassed = speed * Math.max(1, ticksPassed) * 1.5 + 2;
		if (Double.isNaN(fromRailProgress) || passed <= 1 || passed > maxPassed) {
			return sweptRailProgresses;
		}
		for (double sweptRailProgress = fromRailProgress; sweptRailProgress < toRailProgress; sweptRailProgress += SWEEP_STEP) {
			sweptRailProgresses.add(sweptRailProgress);
		}
		return sweptRailProgresses;
	}

	private void checkBlock(BlockPos pos, Consumer<BlockPos> callback) {
		final int checkRadius = (int) Math.floor(speed);
		for (int x = -checkRadius; x <= checkRadius; x++) {
			for (int z = -checkRadius; z <= checkRadius; z++) {
				for (int y = 0; y <= 3; y++) {
					callback.accept(pos.offset(x, -y, z));
				}
			}
		}
	}

	private static void transferItems(Container inventoryFrom, Container inventoryTo) {
		for (int i = 0; i < inventoryFrom.getContainerSize(); i++) {
			if (!inventoryFrom.getItem(i).isEmpty()) {
				final ItemStack insertItem = new ItemStack(inventoryFrom.getItem(i).getItem(), 1);
				insertItem.setTag(inventoryFrom.getItem(i).getOrCreateTag());

				final ItemStack remainingStack = HopperBlockEntity.addItem(null, inventoryTo, insertItem, null);
				if (remainingStack.isEmpty()) {
					inventoryFrom.removeItem(i, 1);
					return;
				}
			}
		}
	}

	/**
	 * The train as the component's stop rules see it ({@link TrainRules}).
	 */
	/**
	 * The train's running by its depot's duties and timetables.
	 */
	public TrainService<PathData> getService() {
		return service;
	}

	private class RulesTrain implements TrainRules.Train {

		@Override
		public long getMillis() {
			return System.currentTimeMillis();
		}

		@Override
		public long getEarlyThresholdMillis() {
			return dataCache == null ? TrainRules.DEFAULT_EARLY_THRESHOLD_MILLIS : dataCache.networkSettings.earlyThresholdMillis;
		}

		@Override
		public TrainService<?> getService() {
			return service;
		}

		@Override
		public MotionState getState() {
			return getMotionState();
		}

		@Override
		public MotionTrack getTrack() {
			return getMotionTrack();
		}

		@Override
		public long getStopId(int rail) {
			return path.get(rail).savedRailBaseId;
		}

		@Override
		public boolean isContinuousMovement() {
			return transportMode.continuousMovement;
		}

		@Override
		public boolean usesSpeedCurve() {
			return TrainServer.this.usesSpeedCurve();
		}

		@Override
		public SpeedCurves getSpeedCurves() {
			return TrainServer.this.getSpeedCurves();
		}

		@Override
		public int getTotalDwellTicks() {
			return TrainServer.this.getTotalDwellTicks();
		}

		@Override
		public boolean isOvertakingTrainComing(long platformId, long arrivalMillis, long departureMillis, long currentMillis) {
			return TrainServer.this.isOvertakingTrainComing(platformId, arrivalMillis, departureMillis, currentMillis);
		}

		@Override
		public boolean isRunGranted(int fromIndex) {
			return TrainServer.this.isRunGranted(fromIndex);
		}
	}

	private class ServiceVehicle implements TrainService.Vehicle<PathData> {

		@Override
		public List<PathData> getPath() {
			return path;
		}

		@Override
		public double getTrainLength() {
			return trainCars * spacing;
		}

		@Override
		public int getNextStoppingIndex() {
			return nextStoppingIndex;
		}

		@Override
		public boolean isRepeat() {
			return TrainServer.this.isRepeat();
		}

		@Override
		public int getRepeatIndex2() {
			return repeatIndex2;
		}

		@Override
		public int getPathRepeatIndex1() {
			return pathRepeatIndex1;
		}

		@Override
		public int getPathRepeatIndex2() {
			return pathRepeatIndex2;
		}

		@Override
		public void setLastLap(boolean isLastLap) {
			TrainServer.this.setLastLap(isLastLap);
		}

		@Override
		public void followPath(TripPath<PathData> tripPath) {
			path = tripPath == null ? depotPath : tripPath.path;
			distances = tripPath == null ? depotDistances : tripPath.distances;
			timeSegments = tripPath == null ? depotTimeSegments : tripPath.timeSegments;
			tripRouteIds.clear();
			if (tripPath != null) {
				tripRouteIds.addAll(tripPath.routeIds);
			}
			pathRepeatIndex1 = tripPath == null ? depotRepeatIndex1 : tripPath.repeatIndex1;
			pathRepeatIndex2 = tripPath == null ? depotRepeatIndex2 : tripPath.repeatIndex2;
			syncRepeatIndices = true;
			// Runs are rails of the path, so a run given on the old one is asked for again
			grantedRunEnd = -1;
			requestedRunEnd = -1;
			waitingSinceMillis = 0;
			// The next packet can be sent before motion runs again. Rebase the parked train along with its path.
			if (tripPath == null && !getIsOnRoute()) {
				parkAtSiding();
				runSegmentSpec = null;
			} else if (!getIsOnRoute() && nextStoppingIndex >= path.size()) {
				nextStoppingIndex = 0;
				nextPlatformIndex = 0;
			}
		}

		@Override
		public void changePath(TripPath<PathData> tripPath, int newStopIndex) {
			final double distanceToStop = getMotionTrack().getStopDistance(nextStoppingIndex) - railProgress;
			followPath(tripPath);
			railProgress = getMotionTrack().getStopDistance(newStopIndex) - distanceToStop;
			nextStoppingIndex = newStopIndex;
			nextPlatformIndex = newStopIndex;
		}

		@Override
		public boolean isTurnBackFree(List<UUID> rails) {
			if (trainPositions != null) {
				for (final UUID rail : rails) {
					if (Occupancy.isTakenByOther(trainPositions, rail, id)) {
						return false;
					}
				}
			}
			return true;
		}

		@Override
		public void reserveTurnBack(List<UUID> rails, int untilIndex) {
			turnBackRails = rails;
			turnBackUntil = untilIndex;
		}

		@Override
		public boolean isWayClear(List<UUID> rails) {
			final RunReservations reservations = getRunReservations();
			return isTurnBackFree(rails) && (reservations == null || !reservations.isHeldByOther(id, rails));
		}

		@Override
		public boolean isStopFree(int stopIndex) {
			double remainingLength = trainCars * spacing;
			for (int i = stopIndex; i >= 0 && remainingLength > 0; i--) {
				if (isRailOccupied(i)) {
					return false;
				}
				remainingLength -= path.get(i).rail.getLength();
			}
			return true;
		}

		@Override
		public void moveToStop(int stopIndex) {
			// Every turn back on the way swaps which end of the train leads
			reversed = false;
			for (int i = 0; i < stopIndex; i++) {
				if (path.get(i).isOppositeRail(path.get(i + 1))) {
					reversed = !reversed;
				}
			}
			railProgress = getMotionTrack().getStopDistance(stopIndex);
			speed = 0;
			manualNotch = -2;
			elapsedDwellTicks = 0;
			nextStoppingIndex = stopIndex;
			nextPlatformIndex = stopIndex;
			grantedRunEnd = -1;
			requestedRunEnd = -1;
			waitingSinceMillis = 0;
		}

		@Override
		public boolean moveBetweenStops(int fromStop, int toStop, double newRailProgress, float newSpeed) {
			moveToStop(fromStop);
			// Turning back on the run swaps which end leads too
			for (int i = fromStop; i + 1 < path.size() && distances.get(i) < newRailProgress; i++) {
				if (path.get(i).isOppositeRail(path.get(i + 1))) {
					reversed = !reversed;
				}
			}
			railProgress = newRailProgress;
			speed = newSpeed;
			nextStoppingIndex = toStop;
			nextPlatformIndex = toStop;
			return true;
		}

		@Override
		public void returnToDepot() {
			isOnRoute = false;
			manualNotch = -2;
			speed = 0;
			ridingEntities.clear();
		}
	}
}
