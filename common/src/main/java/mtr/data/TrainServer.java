package mtr.data;

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

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

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
	/**
	 * The planned arrival at the first stop of the current service as a full timestamp, or 0 without a timetable.
	 */
	private long tripMillis;
	/**
	 * The service this train runs after the current lap, reserved up front so the depot doesn't send a train for it.
	 */
	private long nextTripMillis;
	/**
	 * With no service left for this train, it leaves the loop and returns to the depot after this lap.
	 */
	private boolean isLastLap;
	private boolean syncRepeatIndices;
	private int pathRepeatIndex1;
	private int pathRepeatIndex2;
	public Siding.StopTimes stopTimes;
	public Map<Long, List<ScheduleEntry>> lastSchedulesForPlatform = new HashMap<>();

	private final List<Siding.TimeSegment> timeSegments;

	private static final int TRAIN_UPDATE_DISTANCE = 128;
	private static final int TICKS_TO_SEND_RAIL_PROGRESS = 40;
	private static final int DOOR_CLOSE_MILLIS = (DOOR_MOVE_TIME + DOOR_DELAY) * Depot.MILLIS_PER_TICK;
	private static final int MIN_DWELL_TICKS = Route.MIN_STOP_DWELL * 10;

	private static final String KEY_TRIP_MILLIS = "trip_millis";
	private static final String KEY_NEXT_TRIP_MILLIS = "next_trip_millis";
	private static final String KEY_IS_LAST_LAP = "is_last_lap";

	public TrainServer(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<Siding.TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		super(id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
	}

	public TrainServer(
			long sidingId, float railLength, List<Siding.TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, map);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		tripMillis = messagePackHelper.getLong(KEY_TRIP_MILLIS);
		nextTripMillis = messagePackHelper.getLong(KEY_NEXT_TRIP_MILLIS);
		setLastLap(messagePackHelper.getBoolean(KEY_IS_LAST_LAP));
	}

	@Deprecated
	public TrainServer(
			long sidingId, float railLength, List<Siding.TimeSegment> timeSegments,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManual, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag
	) {
		super(sidingId, railLength, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime, compoundTag);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);
		messagePacker.packString(KEY_TRIP_MILLIS).packLong(tripMillis);
		messagePacker.packString(KEY_NEXT_TRIP_MILLIS).packLong(nextTripMillis);
		messagePacker.packString(KEY_IS_LAST_LAP).packBoolean(isLastLap);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 3;
	}

	@Override
	public int getTotalDwellTicks() {
		final int dwellTicks = super.getTotalDwellTicks();
		final RouteTimetable.StopPlan stopPlan = isCurrentlyManual ? null : getStopPlan(getPlanIndex(nextStoppingIndex));
		if (stopPlan == null) {
			return dwellTicks;
		} else {
			// A late train shortens its stop to catch up, without going below the time doors need
			final long ticksAtStop = (long) elapsedDwellTicks + (tripMillis + stopPlan.departureMillis - System.currentTimeMillis()) / Depot.MILLIS_PER_TICK;
			return (int) Math.max(Math.min(dwellTicks, ticksAtStop), Math.min(dwellTicks, MIN_DWELL_TICKS));
		}
	}

	@Override
	protected boolean isHeldAtStop() {
		final RouteTimetable.StopPlan stopPlan = isCurrentlyManual ? null : getStopPlan(getPlanIndex(nextStoppingIndex));
		if (stopPlan == null || !stopPlan.isTimed) {
			return false;
		}
		final long currentMillis = System.currentTimeMillis();
		final long departureMillis = tripMillis + stopPlan.departureMillis;
		return currentMillis < departureMillis - DOOR_CLOSE_MILLIS || stopPlan.overtake && currentMillis < departureMillis + RouteTimetable.MAX_OVERTAKE_WAIT_MILLIS && isOvertakingTrainComing(tripMillis + stopPlan.arrivalMillis, departureMillis, currentMillis);
	}

	@Override
	protected void onLapComplete() {
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable();
		if (timetable == null) {
			tripMillis = 0;
			nextTripMillis = 0;
			setLastLap(false);
		} else {
			// A train looping since before the timetable was added joins it here
			tripMillis = nextTripMillis == 0 ? depot.findNextTrip(System.currentTimeMillis()) : nextTripMillis;
			nextTripMillis = 0;
			reserveNextTrip();
		}
	}

	@Override
	protected void startUp(Level world, int trainCars, int trainSpacing, boolean isOppositeRail) {
		canDeploy = false;
		isOnRoute = true;
		elapsedDwellTicks = 0;
		speed = Train.ACCELERATION_DEFAULT;
		if (isOppositeRail) {
			railProgress += trainCars * trainSpacing;
			reversed = !reversed;
		}
		nextStoppingIndex = getNextStoppingIndex();
		super.startUp(world, trainCars, trainSpacing, isOppositeRail);
	}

	@Override
	protected boolean openDoors() {
		if (isCurrentlyManual) {
			return doorTarget;
		} else {
			if (transportMode.continuousMovement) {
				final int index = getIndex(railProgress, false);
				if (path.get(index).dwellTime > 0 && index > 0) {
					final double doorValue1 = (railProgress - distances.get(index - 1)) * 0.5;
					final double doorValue2 = (distances.get(index) - railProgress) * 0.5;
					return doorValue1 > 0 && (doorValue2 > doorValue1 || doorValue2 > 1);
				} else {
					return false;
				}
			} else {
				// Turn backs have no platform, so a long wait there must not open the doors
				if (path.get(nextStoppingIndex).savedRailBaseId == 0) {
					return false;
				}
				final int dwellTicks = getTotalDwellTicks();
				final float maxDoorMoveTime = Math.min(DOOR_MOVE_TIME, dwellTicks / 2 - DOOR_DELAY);
				return elapsedDwellTicks >= DOOR_DELAY && elapsedDwellTicks < dwellTicks - DOOR_DELAY - maxDoorMoveTime;
			}
		}
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
		if (RailwayData.chunkLoaded(world, frontPos)) {
			checkBlock(frontPos, checkPos -> {
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
			checkBlock(frontPos, checkPos -> {
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
		// A train that can't leave skips this departure instead of queueing, so the timetable doesn't drift
		if (path.size() > 1 && depot != null && !isRailOccupied(1)) {
			depot.requestDeploy(sidingId, this);
		}
		return canDeploy;
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		if (isRailOccupied(checkIndex)) {
			if (routeId != 0) {
				final BlockPos startingPos = path.get(checkIndex).startingPos;
				if (!trainDelays.containsKey(routeId)) {
					trainDelays.put(routeId, new HashMap<>());
				}
				if (!trainDelays.get(routeId).containsKey(startingPos)) {
					trainDelays.get(routeId).put(startingPos, new TrainDelay());
				}
				trainDelays.get(routeId).get(startingPos).delaying();
			}
			return true;
		}
		return false;
	}

	private boolean isRailOccupied(int checkIndex) {
		if (!transportMode.continuousMovement && trainPositions != null && checkIndex < path.size()) {
			final UUID railProduct = path.get(checkIndex).getRailProduct();
			for (final Map<UUID, Long> trainPositionsMap : trainPositions) {
				if (trainPositionsMap.containsKey(railProduct) && trainPositionsMap.get(railProduct) != id) {
					return true;
				}
			}
		}
		return false;
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
		final int oldStoppingIndex = nextStoppingIndex;
		final int oldPassengerCount = ridingEntities.size();
		final boolean oldIsCurrentlyManual = isCurrentlyManual;
		final boolean oldStopped = speed == 0;
		final boolean oldDoorOpen = doorTarget;

		simulateTrain(world, ticksElapsed, depot);

		if (isOnRoute) {
			depot.reserveTrip(tripMillis);
			depot.reserveTrip(nextTripMillis);
		} else if (tripMillis != 0 || isLastLap) {
			tripMillis = 0;
			nextTripMillis = 0;
			setLastLap(false);
		}

		final int nextDepartureTicks = isOnRoute ? 0 : stopTimes == null ? depot.getNextDepartureMillis() : depot.getNextDepartureMillis(stopTimes.getLeadMillis());
		final long currentMillis = System.currentTimeMillis() - (long) (elapsedDwellTicks * Depot.MILLIS_PER_TICK) + (long) Math.max(0, nextDepartureTicks);

		double currentTime = -1;
		int startingIndex = 0;
		for (final Siding.TimeSegment timeSegment : timeSegments) {
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
			for (int i = startingIndex; i < timeSegments.size() + (isRepeat() ? timeSegments.size() : 0); i++) {
				final Siding.TimeSegment timeSegment = timeSegments.get(i % timeSegments.size());

				if (timeSegment.savedRailBaseId != 0) {
					if (timeSegment.routeId == 0) {
						RailwayData.useRoutesAndStationsFromIndex(path.get(getIndex(timeSegment.endRailProgress, true)).stopIndex - 1, depot.routeIds, dataCache, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
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
						final long scheduledMillis = i < timeSegments.size() ? getPlannedArrivalMillis(getIndex(timeSegment.endRailProgress, true)) : 0;
						// The estimate doesn't know about waiting for the timetable at earlier stops
						final long arrivalMillis = Math.max(estimatedMillis, scheduledMillis);
						addSchedule = () -> schedulesForPlatform.get(platformId).add(new ScheduleEntry(arrivalMillis, trainCars, timeSegment.routeId, timeSegment.currentStationIndex, id, timeSegment.pass, scheduledMillis));
						if (!isRepeat()) {
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
		return oldPassengerCount > ridingEntities.size() || oldStoppingIndex != nextStoppingIndex || oldIsCurrentlyManual != isCurrentlyManual || oldStopped && speed != 0 || oldDoorOpen != doorTarget || oldSyncRepeatIndices;
	}

	public void writeTrainPositions(List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks) {
		if (!path.isEmpty()) {
			final int headIndex = getIndex(0, spacing, true);
			final int tailIndex = getIndex(trainCars, spacing, false);
			for (int i = tailIndex; i <= headIndex; i++) {
				final PathData pathData = path.get(i);
				if (i > 0 && pathData.savedRailBaseId != sidingId && pathData.rail.railType.hasSignal) {
					signalBlocks.occupy(pathData.getRailProduct(), trainPositions, id);
				}
			}
		}
	}

	/**
	 * Moves this train onto a new path without replacing it, so riders, cargo and door state all stay.
	 */
	public void relocate(double newRailProgress, int railIndex, int newRepeatIndex1, int newRepeatIndex2) {
		railProgress = newRailProgress;
		pathRepeatIndex1 = newRepeatIndex1;
		pathRepeatIndex2 = newRepeatIndex2;
		setLastLap(isLastLap);
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
	 * Sends this train out for a service of the timetable.
	 */
	public void deployTrain(long newTripMillis) {
		canDeploy = true;
		tripMillis = newTripMillis;
		nextTripMillis = 0;
		reserveNextTrip();
	}

	public long getLeadMillis() {
		return stopTimes == null ? 0 : stopTimes.getLeadMillis();
	}

	private void reserveNextTrip() {
		if (pathRepeatIndex1 > 0 && pathRepeatIndex2 > 0) {
			final RouteTimetable.StopPlan lastStopPlan = getStopPlan(pathRepeatIndex2);
			nextTripMillis = lastStopPlan == null ? 0 : depot.findNextTrip(tripMillis + lastStopPlan.arrivalMillis);
			setLastLap(nextTripMillis == 0);
		}
	}

	private void setLastLap(boolean newIsLastLap) {
		isLastLap = newIsLastLap;
		final int newRepeatIndex1 = isLastLap ? 0 : pathRepeatIndex1;
		final int newRepeatIndex2 = isLastLap ? 0 : pathRepeatIndex2;
		syncRepeatIndices = syncRepeatIndices || repeatIndex1 != newRepeatIndex1 || repeatIndex2 != newRepeatIndex2;
		repeatIndex1 = newRepeatIndex1;
		repeatIndex2 = newRepeatIndex2;
	}

	/**
	 * The stop at the end of a lap is also the first stop of the next one, which is the service the train is on by then.
	 */
	private int getPlanIndex(int stopIndex) {
		return isRepeat() && stopIndex >= repeatIndex2 && stopTimes != null ? stopTimes.getFirstIndex() : stopIndex;
	}

	private RouteTimetable.StopPlan getStopPlan(int stopIndex) {
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable();
		return tripMillis == 0 || timetable == null || stopTimes == null ? null : stopTimes.getPlan(depot.getTimetableRoutes(), timetable, stopIndex);
	}

	private long getPlannedArrivalMillis(int stopIndex) {
		if (isRepeat() && stopIndex >= repeatIndex2) {
			return nextTripMillis;
		} else {
			final RouteTimetable.StopPlan stopPlan = getStopPlan(stopIndex);
			return stopPlan == null ? 0 : tripMillis + stopPlan.arrivalMillis;
		}
	}

	/**
	 * A train planned to pass this station while this one is stopped has not gone yet, on another platform of the station.
	 */
	private boolean isOvertakingTrainComing(long arrivalMillis, long departureMillis, long currentMillis) {
		final long platformId = path.get(nextStoppingIndex).savedRailBaseId;
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
		final int headIndex = getIndex(0, 0, false);
		for (int i = headIndex; i < path.size(); i++) {
			if (path.get(i).dwellTime > 0) {
				return i;
			}
		}
		return path.size() - 1;
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
}
