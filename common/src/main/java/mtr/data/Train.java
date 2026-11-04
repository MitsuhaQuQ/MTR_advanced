package mtr.data;

import mtr.Items;
import mtr.Keys;
import mtr.advanced.TrackViews;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPlatform;
import mtr.packet.IPacket;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.motion.*;
import transitsim.motion.legacy.LegacyTrainMotion;

import java.io.*;
import java.util.*;
import java.util.function.IntPredicate;

public abstract class Train extends NameColorDataBase implements IPacket {

	protected float speed;
	protected double railProgress;
	protected boolean doorTarget;
	protected float doorValue;
	protected float elapsedDwellTicks;
	protected int nextStoppingIndex;
	protected int nextPlatformIndex;
	protected boolean reversed;
	protected boolean isOnRoute = false;
	protected boolean isCurrentlyManual;
	protected int manualNotch;
	/**
	 * Whether the train follows speed curves (design document section 8), set by its depot. See {@link SpeedCurves}.
	 */
	protected boolean useSpeedCurve;
	protected float decelerationConstant = ACCELERATION_DEFAULT;
	/**
	 * The cruising speed for the run to the next stop, lowered so the train arrives on time, or infinity for none.
	 */
	protected float cruiseCap = Float.POSITIVE_INFINITY;
	/**
	 * Whether the component's simulator moves the train (G1): its state is then copied in each tick, and the train only
	 * does what needs the world, such as its cars, riders and doors.
	 */
	protected boolean movedBySimulator;
	private SpeedCurves speedCurves;
	private List<PathData> speedCurvesPath;
	private int speedCurvesSize;
	private double speedCurvesLength;
	private float speedCurvesDeceleration;
	private SpeedProfile cappedProfile;
	private SpeedCurves.Run cappedRun;
	private float cappedCap;
	private final Motion motion = new Motion();
	private RunSegment runSegment;
	private final List<MotionEvent> motionEvents = new ArrayList<>();
	private long motionEventSequence;
	private Level motionWorld;
	private Depot motionDepot;

	public final long sidingId;
	public final String trainId;
	public final String baseTrainType;
	public final TransportMode transportMode;
	public final int spacing;
	public final int width;
	public final int trainCars;
	public final float accelerationConstant;
	public final boolean isManualAllowed;
	public final int maxManualSpeed;
	public final int manualToAutomaticTime;
	public List<PathData> path;

	protected List<Double> distances;
	/**
	 * The routes of the path, when it isn't the usual path of the depot, such as a service running through to another line.
	 */
	protected final List<Long> tripRouteIds = new ArrayList<>();
	protected int repeatIndex1;
	protected int repeatIndex2;
	protected final Set<UUID> ridingEntities = new HashSet<>();
	protected final SimpleContainer inventory;

	private final float railLength;

	public static final float ACCELERATION_DEFAULT = 0.01F; // m/tick^2
	public static final float MAX_ACCELERATION = 0.05F; // m/tick^2
	public static final float MIN_ACCELERATION = 0.001F; // m/tick^2
	public static final int DOOR_MOVE_TIME = 64;
	private static final int MAX_MOTION_EVENTS = 256;
	protected static final int MAX_CHECK_DISTANCE = 32;
	protected static final int DOOR_DELAY = 20;

	private static final String KEY_SPEED = "speed";
	private static final String KEY_RAIL_PROGRESS = "rail_progress";
	private static final String KEY_ELAPSED_DWELL_TICKS = "stop_counter";
	private static final String KEY_NEXT_STOPPING_INDEX = "next_stopping_index";
	private static final String KEY_NEXT_PLATFORM_INDEX = "next_platform_index";
	private static final String KEY_REVERSED = "reversed";
	private static final String KEY_IS_CURRENTLY_MANUAL = "is_currently_manual";
	private static final String KEY_IS_ON_ROUTE = "is_on_route";
	private static final String KEY_TRAIN_TYPE = "train_type";
	private static final String KEY_TRAIN_CUSTOM_ID = "train_custom_id";
	private static final String KEY_RIDING_ENTITIES = "riding_entities";
	private static final String KEY_CARGO = "cargo";

	public Train(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime) {
		super(id);
		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.trainId = trainId;
		// TODO temporary code for backwards compatibility
		baseTrainType = baseTrainType.startsWith("base_") ? baseTrainType.replace("base_", "train_") : baseTrainType;
		// TODO temporary code end
		this.baseTrainType = baseTrainType;
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		this.trainCars = trainCars;
		this.isManualAllowed = isManualAllowed;
		isCurrentlyManual = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		final float tempAccelerationConstant = RailwayData.round(accelerationConstant, 3);
		this.accelerationConstant = tempAccelerationConstant <= 0 ? ACCELERATION_DEFAULT : tempAccelerationConstant;
		inventory = new SimpleContainer(trainCars);
	}

	public Train(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map
	) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);

		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		this.accelerationConstant = accelerationConstant;
		this.isManualAllowed = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;

		speed = messagePackHelper.getFloat(KEY_SPEED);
		railProgress = messagePackHelper.getDouble(KEY_RAIL_PROGRESS);
		elapsedDwellTicks = messagePackHelper.getFloat(KEY_ELAPSED_DWELL_TICKS);
		nextStoppingIndex = messagePackHelper.getInt(KEY_NEXT_STOPPING_INDEX);
		nextPlatformIndex = messagePackHelper.getInt(KEY_NEXT_PLATFORM_INDEX);
		reversed = messagePackHelper.getBoolean(KEY_REVERSED);

		final String tempTrainId = messagePackHelper.getString(KEY_TRAIN_CUSTOM_ID).toLowerCase(Locale.ENGLISH);
		// TODO temporary code for backwards compatibility
		String tempBaseTrainType = messagePackHelper.getString(KEY_TRAIN_TYPE).toLowerCase(Locale.ENGLISH);
		baseTrainType = tempBaseTrainType.startsWith("base_") ? tempBaseTrainType.replace("base_", "train_") : tempBaseTrainType;
		// TODO temporary code end
		trainId = tempTrainId.isEmpty() ? baseTrainType : tempTrainId;
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isCurrentlyManual = messagePackHelper.getBoolean(KEY_IS_CURRENTLY_MANUAL);

		isOnRoute = messagePackHelper.getBoolean(KEY_IS_ON_ROUTE);
		messagePackHelper.iterateArrayValue(KEY_RIDING_ENTITIES, value -> ridingEntities.add(UUID.fromString(value.asStringValue().asString())));

		SimpleContainer inventory1 = new SimpleContainer(trainCars);
		if (map.containsKey(KEY_CARGO) && !map.get(KEY_CARGO).isNilValue()) {
			final byte[] rawNbt = map.get(KEY_CARGO).asBinaryValue().asByteArray();
			final ByteArrayInputStream inputStream = new ByteArrayInputStream(rawNbt);
			try {
				final CompoundTag compoundTag = NbtIo.read(new DataInputStream(inputStream));
				final NonNullList<ItemStack> stacks = NonNullList.withSize(trainCars, ItemStack.EMPTY);
				ContainerHelper.loadAllItems(compoundTag.getCompound(KEY_CARGO), stacks);
				inventory1 = new SimpleContainer(stacks.toArray(new ItemStack[0]));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		inventory = inventory1;
	}

	@Deprecated
	public Train(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag
	) {
		super(compoundTag);

		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		this.accelerationConstant = accelerationConstant;
		this.isManualAllowed = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;

		speed = compoundTag.getFloat(KEY_SPEED);
		railProgress = compoundTag.getDouble(KEY_RAIL_PROGRESS);
		elapsedDwellTicks = compoundTag.getFloat(KEY_ELAPSED_DWELL_TICKS);
		nextStoppingIndex = compoundTag.getInt(KEY_NEXT_STOPPING_INDEX);
		nextPlatformIndex = compoundTag.getInt(KEY_NEXT_PLATFORM_INDEX);
		reversed = compoundTag.getBoolean(KEY_REVERSED);

		trainId = compoundTag.getString(KEY_TRAIN_CUSTOM_ID);
		baseTrainType = compoundTag.getString(KEY_TRAIN_TYPE);
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isCurrentlyManual = compoundTag.getBoolean(KEY_IS_CURRENTLY_MANUAL);

		isOnRoute = compoundTag.getBoolean(KEY_IS_ON_ROUTE);
		final CompoundTag tagRidingEntities = compoundTag.getCompound(KEY_RIDING_ENTITIES);
		tagRidingEntities.getAllKeys().forEach(key -> ridingEntities.add(tagRidingEntities.getUUID(key)));

		final NonNullList<ItemStack> stacks = NonNullList.withSize(trainCars, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(compoundTag.getCompound(KEY_CARGO), stacks);
		inventory = new SimpleContainer(stacks.toArray(new ItemStack[0]));
	}

	public Train(FriendlyByteBuf packet) {
		super(packet);

		path = new ArrayList<>();
		distances = new ArrayList<>();
		final int pathSize = packet.readInt();
		for (int i = 0; i < pathSize; i++) {
			path.add(new PathData(packet));
			distances.add(packet.readDouble());
		}
		repeatIndex1 = packet.readInt();
		repeatIndex2 = packet.readInt();
		final int tripRouteCount = packet.readInt();
		for (int i = 0; i < tripRouteCount; i++) {
			tripRouteIds.add(packet.readLong());
		}

		sidingId = packet.readLong();
		railLength = RailwayData.round(packet.readFloat(), 3);
		speed = packet.readFloat();
		final float tempAccelerationConstant = RailwayData.round(packet.readFloat(), 3);
		accelerationConstant = tempAccelerationConstant <= 0 ? ACCELERATION_DEFAULT : tempAccelerationConstant;
		railProgress = packet.readDouble();
		elapsedDwellTicks = packet.readFloat();
		nextStoppingIndex = packet.readInt();
		nextPlatformIndex = packet.readInt();
		reversed = packet.readBoolean();
		trainId = packet.readUtf(PACKET_STRING_READ_LENGTH);
		baseTrainType = packet.readUtf(PACKET_STRING_READ_LENGTH);
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isManualAllowed = packet.readBoolean();
		isCurrentlyManual = packet.readBoolean();
		maxManualSpeed = packet.readInt();
		manualToAutomaticTime = packet.readInt();
		isOnRoute = packet.readBoolean();
		manualNotch = packet.readInt();
		doorTarget = packet.readBoolean();

		final int ridingEntitiesCount = packet.readInt();
		for (int i = 0; i < ridingEntitiesCount; i++) {
			ridingEntities.add(packet.readUUID());
		}
		useSpeedCurve = packet.readBoolean();
		decelerationConstant = packet.readFloat();
		cruiseCap = packet.readFloat();

		inventory = null;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_SPEED).packFloat(speed);
		messagePacker.packString(KEY_RAIL_PROGRESS).packDouble(railProgress);
		messagePacker.packString(KEY_ELAPSED_DWELL_TICKS).packFloat(elapsedDwellTicks);
		messagePacker.packString(KEY_NEXT_STOPPING_INDEX).packLong(nextStoppingIndex);
		messagePacker.packString(KEY_NEXT_PLATFORM_INDEX).packLong(nextPlatformIndex);
		messagePacker.packString(KEY_REVERSED).packBoolean(reversed);
		messagePacker.packString(KEY_TRAIN_CUSTOM_ID).packString(trainId);
		messagePacker.packString(KEY_TRAIN_TYPE).packString(baseTrainType);
		messagePacker.packString(KEY_IS_CURRENTLY_MANUAL).packBoolean(isCurrentlyManual);
		messagePacker.packString(KEY_IS_ON_ROUTE).packBoolean(isOnRoute);

		messagePacker.packString(KEY_RIDING_ENTITIES).packArrayHeader(ridingEntities.size());
		for (final UUID uuid : ridingEntities) {
			messagePacker.packString(uuid.toString());
		}

		messagePacker.packString(KEY_CARGO);
		if (inventory != null) {
			final NonNullList<ItemStack> stacks = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
			int totalCount = 0;
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				stacks.set(i, inventory.getItem(i));
				totalCount += inventory.getItem(i).getCount();
			}
			if (totalCount > 0) {
				CompoundTag tag = ContainerHelper.saveAllItems(new CompoundTag(), stacks);
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				NbtIo.write(tag, new DataOutputStream(outputStream));
				messagePacker.packBinaryHeader(outputStream.size());
				messagePacker.writePayload(outputStream.toByteArray());
			} else {
				messagePacker.packNil();
			}
		} else {
			messagePacker.packNil();
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 12;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);

		final int pathSize = Math.min(path.size(), distances.size());
		packet.writeInt(pathSize);
		for (int i = 0; i < pathSize; i++) {
			path.get(i).writePacket(packet);
			packet.writeDouble(distances.get(i));
		}
		packet.writeInt(repeatIndex1);
		packet.writeInt(repeatIndex2);
		packet.writeInt(tripRouteIds.size());
		tripRouteIds.forEach(packet::writeLong);

		packet.writeLong(sidingId);
		packet.writeFloat(railLength);
		packet.writeFloat(speed);
		packet.writeFloat(accelerationConstant);
		packet.writeDouble(railProgress);
		packet.writeFloat(elapsedDwellTicks);
		packet.writeInt(nextStoppingIndex);
		packet.writeInt(nextPlatformIndex);
		packet.writeBoolean(reversed);
		packet.writeUtf(trainId);
		packet.writeUtf(baseTrainType);
		packet.writeBoolean(isManualAllowed);
		packet.writeBoolean(isCurrentlyManual);
		packet.writeInt(maxManualSpeed);
		packet.writeInt(manualToAutomaticTime);
		packet.writeBoolean(isOnRoute);
		packet.writeInt(manualNotch);
		packet.writeBoolean(doorTarget);
		packet.writeInt(ridingEntities.size());
		ridingEntities.forEach(packet::writeUUID);
		packet.writeBoolean(useSpeedCurve);
		packet.writeFloat(decelerationConstant);
		packet.writeFloat(cruiseCap);
	}

	@Override
	protected final boolean hasTransportMode() {
		return false;
	}

	/**
	 * Called by the siding every tick with its depot's setting and its train's braking.
	 */
	public final void setSpeedCurve(boolean useSpeedCurve, float decelerationConstant) {
		this.useSpeedCurve = useSpeedCurve;
		this.decelerationConstant = decelerationConstant <= 0 ? accelerationConstant : decelerationConstant;
	}

	public final boolean usesSpeedCurve() {
		return useSpeedCurve && !isCurrentlyManual && !transportMode.continuousMovement;
	}

	/**
	 * @return the curves of the current path, worked out again when the path or the braking changes
	 */
	protected final SpeedCurves getSpeedCurves() {
		final double length = distances.isEmpty() ? 0 : distances.get(distances.size() - 1);
		if (speedCurves == null || speedCurvesPath != path || speedCurvesSize != path.size() || speedCurvesLength != length || speedCurvesDeceleration != decelerationConstant) {
			speedCurves = SpeedCurves.of(TrackViews.of(path, transportMode), (railLength + trainCars * spacing) / 2, trainCars * spacing, accelerationConstant, decelerationConstant);
			speedCurvesPath = path;
			speedCurvesSize = path.size();
			speedCurvesLength = length;
			speedCurvesDeceleration = decelerationConstant;
			cappedProfile = null;
		}
		return speedCurves;
	}

	protected final void invalidateSpeedCurves() {
		speedCurves = null;
	}

	/**
	 * @return the curve for the run the train is on, cruising slower when it has time to spare
	 */
	private SpeedProfile getRunProfile(SpeedCurves.Run run) {
		if (Float.isInfinite(cruiseCap)) {
			return run.fastest;
		}
		if (cappedProfile == null || cappedRun != run || cappedCap != cruiseCap) {
			cappedProfile = run.withCap(cruiseCap);
			cappedRun = run;
			cappedCap = cruiseCap;
		}
		return cappedProfile;
	}

	/**
	 * @return true if the end of the path is a stop on the line rather than the way into the siding, as for a train staying
	 * there overnight
	 */
	protected boolean keepsOnLineAtEnd() {
		return false;
	}

	public final boolean getIsOnRoute() {
		return isOnRoute;
	}

	public final double getRailProgress() {
		return railProgress;
	}

	public final boolean closeToDepot(int trainDistance) {
		return !isOnRoute || railProgress < trainDistance + railLength;
	}

	public final boolean isCurrentlyManual() {
		return isCurrentlyManual;
	}

	public boolean changeManualSpeed(boolean isAccelerate) {
		return TrainMotion.applyCommand(motion, isAccelerate ? MotionCommand.ACCELERATE : MotionCommand.BRAKE);
	}

	public boolean toggleDoors() {
		return TrainMotion.applyCommand(motion, MotionCommand.TOGGLE_DOORS);
	}

	/**
	 * Hands over what happened to the train since the last time, for the host to handle at the end of the tick.
	 */
	public final void drainMotionEvents(Collection<MotionEvent> events) {
		events.addAll(motionEvents);
		motionEvents.clear();
	}

	public final int getIndex(int car, int trainSpacing, boolean roundDown) {
		return getIndex(getRailProgress(car, trainSpacing), roundDown);
	}

	public final int getIndex(double tempRailProgress, boolean roundDown) {
		return findIndex(distances, path.size(), tempRailProgress, roundDown);
	}

	/**
	 * @return the first rail ending after the progress, or the last rail. Distances never decrease along a path.
	 */
	public static int findIndex(List<Double> distances, int size, double tempRailProgress, boolean roundDown) {
		int low = 0;
		int high = size;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			final double tempDistance = distances.get(middle);
			if (tempRailProgress < tempDistance || roundDown && tempRailProgress == tempDistance) {
				high = middle;
			} else {
				low = middle + 1;
			}
		}
		return Math.min(low, size - 1);
	}

	/**
	 * Looks along the path for a blocked rail the train must start braking for. The next rail is always checked.
	 *
	 * @param lastIndex the last rail worth checking, which is the next stop
	 * @return the blocked rail, or -1
	 */
	public static int findBlockedIndex(List<Double> distances, int headIndex, int lastIndex, double railProgress, double lookAhead, IntPredicate isBlocked) {
		for (int i = headIndex + 1; i == headIndex + 1 || i <= lastIndex && distances.get(i - 1) - railProgress <= lookAhead; i++) {
			if (isBlocked.test(i)) {
				return i;
			}
		}
		return -1;
	}

	public final float getRailSpeed(int railIndex) {
		final RailType thisRail = path.get(railIndex).rail.railType;
		final float railSpeed;
		if (thisRail.canAccelerate) {
			railSpeed = thisRail.maxBlocksPerTick;
		} else {
			final RailType lastRail = railIndex > 0 ? path.get(railIndex - 1).rail.railType : thisRail;
			railSpeed = Math.max(lastRail.canAccelerate ? lastRail.maxBlocksPerTick : RailType.getDefaultMaxBlocksPerTick(transportMode), speed);
		}
		return railSpeed;
	}

	public final boolean isPlayerRiding(Player player) {
		return ridingEntities.contains(player.getUUID());
	}

	public final float getSpeed() {
		return speed;
	}

	public final float getDoorValue() {
		return doorValue;
	}

	public final float getElapsedDwellTicks() {
		return elapsedDwellTicks;
	}

	public final boolean isReversed() {
		return reversed;
	}

	public final boolean isOnRoute() {
		return isOnRoute;
	}

	public int getTotalDwellTicks() {
		return path.get(nextStoppingIndex).dwellTime * 10;
	}

	/**
	 * The train's moving state as the component's rules see it, for subclasses handing it to them.
	 */
	protected final MotionState getMotionState() {
		return motion;
	}

	protected final MotionTrack getMotionTrack() {
		return motion;
	}

	protected final void simulateTrain(Level world, float ticksElapsed, Depot depot) {
		if (world == null) {
			return;
		}

		try {
			// The rules moving the train are in the component (decoupling document 2.2); the old ones stay for comparing
			motionWorld = world;
			motionDepot = depot;
			final int totalDwellTicks = movedBySimulator ? motion.getTotalDwellTicks() : MotionConfig.useLegacyMotion(world.isClientSide()) ? LegacyTrainMotion.tick(motion, motion, motion, motion, motion, ticksElapsed) : TrainMotion.tick(motion, motion, motion, motion, motion, ticksElapsed);
			if (totalDwellTicks < 0) {
				return;
			}

			if (!path.isEmpty()) {
				final Vec3[] positions = new Vec3[trainCars + 1];
				for (int i = 0; i <= trainCars; i++) {
					positions[i] = getRoutePosition(reversed ? trainCars - i : i, spacing);
				}

				if (handlePositions(world, positions, ticksElapsed)) {
					final double[] prevX = {0};
					final double[] prevY = {0};
					final double[] prevZ = {0};
					final float[] prevYaw = {0};
					final float[] prevPitch = {0};

					for (int i = 0; i < trainCars; i++) {
						final int ridingCar = i;
						calculateCar(world, positions, i, totalDwellTicks, (x, y, z, yaw, pitch, realSpacing, doorLeftOpen, doorRightOpen) -> {
							simulateCar(
									world, ridingCar, ticksElapsed,
									x, y, z,
									yaw, pitch,
									prevX[0], prevY[0], prevZ[0],
									prevYaw[0], prevPitch[0],
									doorLeftOpen, doorRightOpen, realSpacing
							);
							prevX[0] = x;
							prevY[0] = y;
							prevZ[0] = z;
							prevYaw[0] = yaw;
							prevPitch[0] = pitch;
						});
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected final void calculateCar(Level world, Vec3[] positions, int index, int dwellTicks, CalculateCarCallback calculateCarCallback) {
		final Vec3 pos1 = positions[index];
		final Vec3 pos2 = positions[index + 1];

		if (pos1 != null && pos2 != null) {
			final double x = getAverage(pos1.x, pos2.x);
			final double y = getAverage(pos1.y, pos2.y) + 1;
			final double z = getAverage(pos1.z, pos2.z);

			final double realSpacing = pos2.distanceTo(pos1);
			final float yaw = (float) Mth.atan2(pos2.x - pos1.x, pos2.z - pos1.z);
			final float pitch = realSpacing == 0 ? 0 : (float) asin((pos2.y - pos1.y) / realSpacing);
			final boolean doorLeftOpen = scanDoors(world, x, y, z, (float) Math.PI + yaw, pitch, realSpacing / 2, dwellTicks) && doorValue > 0;
			final boolean doorRightOpen = scanDoors(world, x, y, z, yaw, pitch, realSpacing / 2, dwellTicks) && doorValue > 0;

			calculateCarCallback.calculateCarCallback(x, y, z, yaw, pitch, realSpacing, doorLeftOpen, doorRightOpen);
		}
	}

	protected void startUp(Level world, int trainCars, int trainSpacing, boolean isOppositeRail) {
		doorTarget = false;
		doorValue = 0;
		nextPlatformIndex = nextStoppingIndex;
	}

	protected boolean openDoors() {
		return doorTarget;
	}

	protected float getModelZOffset() {
		return 0;
	}

	protected boolean isRepeat() {
		return repeatIndex1 > 0 && repeatIndex2 > 0;
	}

	/**
	 * Called as a repeating train starts its next lap, before it looks for its next stop.
	 */
	protected void onLapComplete() {
	}

	/**
	 * Called while a train has just stopped, before its stop time starts counting, which can be more than once.
	 */
	protected void onStopReached() {
	}

	/**
	 * While true, a stopped train keeps its doors open and doesn't leave, the same way as when the rail ahead is blocked.
	 */
	protected boolean isHeldAtStop() {
		return false;
	}

	protected abstract void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	);

	protected abstract boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed);

	protected abstract boolean canDeploy(Depot depot);

	protected abstract boolean isRailBlocked(int checkIndex);

	protected abstract boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ);

	protected abstract boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks);

	protected abstract double asin(double value);

	private double getRailProgress(int car, int trainSpacing) {
		return railProgress - car * trainSpacing;
	}

	private Vec3 getRoutePosition(int car, int trainSpacing) {
		final double tempRailProgress = Math.max(getRailProgress(car, trainSpacing) - getModelZOffset(), 0);
		final int index = getIndex(tempRailProgress, false);
		return path.get(index).rail.getPosition(tempRailProgress - (index == 0 ? 0 : distances.get(index - 1))).add(0, transportMode.railOffset, 0);
	}

	/**
	 * Where a point of a car lies on the track, for drawing bogies. The car is counted as in {@link #calculateCar}, and a
	 * positive offset points from the first end of the car towards the second. Uses the same path and progress as the car
	 * positions and changes nothing.
	 */
	public final Vec3 getTrackPosition(int car, double offset) {
		if (path.isEmpty()) {
			return null;
		}
		final double carCenter = reversed ? trainCars - car - 0.5 : car + 0.5;
		final double tempRailProgress = Math.max(railProgress - carCenter * spacing + (reversed ? offset : -offset) - getModelZOffset(), 0);
		final int index = getIndex(tempRailProgress, false);
		return path.get(index).rail.getPosition(tempRailProgress - (index == 0 ? 0 : distances.get(index - 1))).add(0, transportMode.railOffset, 0);
	}

	private boolean scanDoors(Level world, double trainX, double trainY, double trainZ, float checkYaw, float pitch, double halfSpacing, int dwellTicks) {
		if (skipScanBlocks(world, trainX, trainY, trainZ)) {
			return false;
		}

		boolean hasPlatform = false;
		final Vec3 offsetVec = new Vec3(1, 0, 0).yRot(checkYaw).xRot(pitch);
		final Vec3 traverseVec = new Vec3(0, 0, 1).yRot(checkYaw).xRot(pitch);

		for (int checkX = 1; checkX <= 3; checkX++) {
			for (int checkY = -2; checkY <= 3; checkY++) {
				for (double checkZ = -halfSpacing; checkZ <= halfSpacing; checkZ++) {
					final BlockPos checkPos = RailwayData.newBlockPos(trainX + offsetVec.x * checkX + traverseVec.x * checkZ, trainY + checkY, trainZ + offsetVec.z * checkX + traverseVec.z * checkZ);
					final Block block = world.getBlockState(checkPos).getBlock();

					if (block instanceof BlockPlatform || block instanceof BlockPSDAPGBase) {
						if (openDoors(world, block, checkPos, dwellTicks)) {
							return true;
						}
						hasPlatform = true;
					}
				}
			}
		}

		return hasPlatform;
	}

	public static boolean isHoldingKey(Player player) {
		return player != null && !Keys.LIFTS_ONLY && player.isHolding(Items.DRIVER_KEY.get());
	}

	public static double getAverage(double a, double b) {
		return (a + b) / 2;
	}

	public static RailType convertMaxManualSpeed(int maxManualSpeed) {
		if (maxManualSpeed >= 0 && maxManualSpeed <= RailType.DIAMOND.ordinal()) {
			return RailType.values()[maxManualSpeed];
		} else {
			return null;
		}
	}

	@FunctionalInterface
	protected interface CalculateCarCallback {
		void calculateCarCallback(double x, double y, double z, float yaw, float pitch, double realSpacing, boolean doorLeftOpen, boolean doorRightOpen);
	}

	/**
	 * The train as the component's motion rules see it. The state stays in the fields and the hooks stay overridable, so
	 * subclasses and add-ons work as before.
	 */
	private final class Motion implements MotionState, MotionTrack, MotionVehicle, MotionHooks, MotionCurves {

		@Override
		public float getSpeed() {
			return speed;
		}

		@Override
		public void setSpeed(float speed) {
			Train.this.speed = speed;
		}

		@Override
		public double getRailProgress() {
			return railProgress;
		}

		@Override
		public void setRailProgress(double railProgress) {
			Train.this.railProgress = railProgress;
		}

		@Override
		public boolean getDoorTarget() {
			return doorTarget;
		}

		@Override
		public void setDoorTarget(boolean doorTarget) {
			Train.this.doorTarget = doorTarget;
		}

		@Override
		public float getDoorValue() {
			return doorValue;
		}

		@Override
		public void setDoorValue(float doorValue) {
			Train.this.doorValue = doorValue;
		}

		@Override
		public float getElapsedDwellTicks() {
			return elapsedDwellTicks;
		}

		@Override
		public void setElapsedDwellTicks(float elapsedDwellTicks) {
			Train.this.elapsedDwellTicks = elapsedDwellTicks;
		}

		@Override
		public int getNextStoppingIndex() {
			return nextStoppingIndex;
		}

		@Override
		public void setNextStoppingIndex(int nextStoppingIndex) {
			Train.this.nextStoppingIndex = nextStoppingIndex;
		}

		@Override
		public int getNextPlatformIndex() {
			return nextPlatformIndex;
		}

		@Override
		public void setNextPlatformIndex(int nextPlatformIndex) {
			Train.this.nextPlatformIndex = nextPlatformIndex;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean reversed) {
			Train.this.reversed = reversed;
		}

		@Override
		public boolean isOnRoute() {
			return isOnRoute;
		}

		@Override
		public void setOnRoute(boolean onRoute) {
			isOnRoute = onRoute;
		}

		@Override
		public boolean isCurrentlyManual() {
			return isCurrentlyManual;
		}

		@Override
		public int getManualNotch() {
			return manualNotch;
		}

		@Override
		public void setManualNotch(int manualNotch) {
			Train.this.manualNotch = manualNotch;
		}

		@Override
		public RunSegment getRunSegment() {
			return runSegment;
		}

		@Override
		public void setRunSegment(RunSegment runSegment) {
			Train.this.runSegment = runSegment;
		}

		@Override
		public int size() {
			return path.size();
		}

		@Override
		public int getDistanceCount() {
			return distances.size();
		}

		@Override
		public double getDistance(int rail) {
			return distances.get(rail);
		}

		@Override
		public int getDwellTime(int rail) {
			return path.get(rail).dwellTime;
		}

		@Override
		public boolean canAccelerate(int rail) {
			return path.get(rail).rail.railType.canAccelerate;
		}

		@Override
		public float getMaxSpeed(int rail) {
			return path.get(rail).rail.railType.maxBlocksPerTick;
		}

		@Override
		public boolean isOppositeRail(int rail, int nextRail) {
			return path.get(rail).isOppositeRail(path.get(nextRail));
		}

		@Override
		public int getRepeatIndex1() {
			return repeatIndex1;
		}

		@Override
		public int getRepeatIndex2() {
			return repeatIndex2;
		}

		@Override
		public int getTrainCars() {
			return trainCars;
		}

		@Override
		public int getSpacing() {
			return spacing;
		}

		@Override
		public float getRailLength() {
			return railLength;
		}

		@Override
		public float getAcceleration() {
			return accelerationConstant;
		}

		@Override
		public float getDeceleration() {
			return decelerationConstant;
		}

		@Override
		public boolean usesSpeedCurve() {
			return Train.this.usesSpeedCurve();
		}

		@Override
		public boolean isContinuousMovement() {
			return transportMode.continuousMovement;
		}

		@Override
		public float getDefaultMaxSpeed() {
			return RailType.getDefaultMaxBlocksPerTick(transportMode);
		}

		@Override
		public float getManualMaxSpeed() {
			final RailType railType = convertMaxManualSpeed(maxManualSpeed);
			return railType == null ? RailType.IRON.maxBlocksPerTick : railType.maxBlocksPerTick;
		}

		@Override
		public boolean isClientSide() {
			return motionWorld.isClientSide();
		}

		@Override
		public int getTotalDwellTicks() {
			return Train.this.getTotalDwellTicks();
		}

		@Override
		public boolean canDeploy() {
			return Train.this.canDeploy(motionDepot);
		}

		@Override
		public void startUp(boolean isOppositeRail) {
			Train.this.startUp(motionWorld, trainCars, spacing, isOppositeRail);
		}

		@Override
		public boolean keepsOnLineAtEnd() {
			return Train.this.keepsOnLineAtEnd();
		}

		@Override
		public void clearRiders() {
			ridingEntities.clear();
		}

		@Override
		public boolean isRepeat() {
			return Train.this.isRepeat();
		}

		@Override
		public void onLapComplete() {
			Train.this.onLapComplete();
		}

		@Override
		public void onStopReached() {
			Train.this.onStopReached();
		}

		@Override
		public boolean isHeldAtStop() {
			return Train.this.isHeldAtStop();
		}

		@Override
		public boolean isRailBlocked(int rail) {
			return Train.this.isRailBlocked(rail);
		}

		@Override
		public boolean openDoors() {
			return Train.this.openDoors();
		}

		@Override
		public void emit(MotionEvent.Type type, int rail) {
			// Nobody collects them while there are too many, as on a host that doesn't
			if (motionEvents.size() < MAX_MOTION_EVENTS) {
				motionEvents.add(new MotionEvent(System.currentTimeMillis(), type, id, ++motionEventSequence, rail, runSegment == null ? 0 : runSegment.version));
			}
		}

		@Override
		public SpeedProfile getProfile(double position) {
			final SpeedCurves.Run run = getSpeedCurves().getRun(position);
			return run == null ? null : getRunProfile(run);
		}

		@Override
		public SpeedCurves.Run getRun(double position) {
			return getSpeedCurves().getRun(position);
		}

		@Override
		public double getCruiseCap() {
			return cruiseCap;
		}
	}
}
