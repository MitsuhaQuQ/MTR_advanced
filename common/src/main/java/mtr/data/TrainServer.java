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
	private long tripRouteId;
	/**
	 * When the current service's route starts, in the same time as the stop plans. A route after a turn back has its own service.
	 */
	private long routeStartMillis;
	/**
	 * The service this train runs after the current lap, reserved up front so the depot doesn't send a train for it.
	 */
	private long nextTripMillis;
	/**
	 * With no service left for this train, it leaves the loop and returns to the depot after this lap.
	 */
	private boolean isLastLap;
	/**
	 * Still on the way round a loop to its first stop, having joined it part of the way round.
	 */
	private boolean isJoiningLoop;
	private boolean syncRepeatIndices;
	private int pathRepeatIndex1;
	private int pathRepeatIndex2;
	public Siding.StopTimes stopTimes;
	public Map<Long, List<ScheduleEntry>> lastSchedulesForPlatform = new HashMap<>();

	/**
	 * The path of the current service when it isn't the usual path of the depot, which is kept to go back to.
	 */
	private Siding.TripPath tripPath;
	private String tripPathKey = "";
	private int linkedService = -1;
	private Map<String, Siding.TripPath> tripPaths = new HashMap<>();
	private List<Siding.TimeSegment> timeSegments;
	private final List<Route> tripRoutes = new ArrayList<>();
	private final List<PathData> depotPath;
	private final List<Double> depotDistances;
	private final List<Siding.TimeSegment> depotTimeSegments;
	private int depotRepeatIndex1;
	private int depotRepeatIndex2;

	private static final int TRAIN_UPDATE_DISTANCE = 128;
	private static final int TICKS_TO_SEND_RAIL_PROGRESS = 40;
	private static final int DOOR_CLOSE_MILLIS = (DOOR_MOVE_TIME + DOOR_DELAY) * Depot.MILLIS_PER_TICK;
	private static final int MIN_DWELL_TICKS = Route.MIN_STOP_DWELL * 10;

	private static final String KEY_TRIP_MILLIS = "trip_millis";
	private static final String KEY_NEXT_TRIP_MILLIS = "next_trip_millis";
	private static final String KEY_IS_LAST_LAP = "is_last_lap";
	private static final String KEY_TRIP_ROUTE_ID = "trip_route_id";
	private static final String KEY_ROUTE_START_MILLIS = "route_start_millis";
	private static final String KEY_IS_JOINING_LOOP = "is_joining_loop";
	private static final String KEY_TRIP_PATH = "trip_path";
	private static final String KEY_LINKED_SERVICE = "linked_service";

	public TrainServer(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, List<Siding.TimeSegment> timeSegments, boolean isManual, int maxManualSpeed, int manualToAutomaticTime) {
		super(id, sidingId, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, isManual, maxManualSpeed, manualToAutomaticTime);
		this.timeSegments = timeSegments;
		pathRepeatIndex1 = repeatIndex1;
		pathRepeatIndex2 = repeatIndex2;
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
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
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		tripPathKey = messagePackHelper.getString(KEY_TRIP_PATH);
		linkedService = messagePackHelper.getInt(KEY_LINKED_SERVICE, -1);
		tripMillis = messagePackHelper.getLong(KEY_TRIP_MILLIS);
		tripRouteId = messagePackHelper.getLong(KEY_TRIP_ROUTE_ID);
		routeStartMillis = messagePackHelper.getLong(KEY_ROUTE_START_MILLIS);
		nextTripMillis = messagePackHelper.getLong(KEY_NEXT_TRIP_MILLIS);
		isJoiningLoop = messagePackHelper.getBoolean(KEY_IS_JOINING_LOOP);
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
		depotPath = path;
		depotDistances = distances;
		depotTimeSegments = timeSegments;
		depotRepeatIndex1 = repeatIndex1;
		depotRepeatIndex2 = repeatIndex2;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);
		messagePacker.packString(KEY_TRIP_MILLIS).packLong(tripMillis);
		messagePacker.packString(KEY_NEXT_TRIP_MILLIS).packLong(nextTripMillis);
		messagePacker.packString(KEY_IS_LAST_LAP).packBoolean(isLastLap);
		messagePacker.packString(KEY_TRIP_ROUTE_ID).packLong(tripRouteId);
		messagePacker.packString(KEY_ROUTE_START_MILLIS).packLong(routeStartMillis);
		messagePacker.packString(KEY_IS_JOINING_LOOP).packBoolean(isJoiningLoop);
		messagePacker.packString(KEY_TRIP_PATH).packString(tripPathKey);
		messagePacker.packString(KEY_LINKED_SERVICE).packInt(linkedService);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 8;
	}

	@Override
	public int getTotalDwellTicks() {
		final int dwellTicks = super.getTotalDwellTicks();
		final RouteTimetable.StopPlan stopPlan = isCurrentlyManual ? null : getStopPlan(getPlanIndex(nextStoppingIndex));
		if (stopPlan == null) {
			return dwellTicks;
		} else {
			// A late train shortens its stop to catch up, without going below the time doors need
			final long ticksAtStop = (long) elapsedDwellTicks + (getPlannedMillis(stopPlan.departureMillis) - System.currentTimeMillis()) / Depot.MILLIS_PER_TICK;
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
		final long departureMillis = getPlannedMillis(stopPlan.departureMillis);
		return currentMillis < departureMillis - DOOR_CLOSE_MILLIS || stopPlan.overtake && currentMillis < departureMillis + RouteTimetable.MAX_OVERTAKE_WAIT_MILLIS && isOvertakingTrainComing(getPlannedMillis(stopPlan.arrivalMillis), departureMillis, currentMillis);
	}

	@Override
	protected void onLapComplete() {
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		routeStartMillis = 0;
		if (timetable == null) {
			tripMillis = 0;
			nextTripMillis = 0;
			setLastLap(false);
		} else {
			// A train looping since before the timetable was added joins it here
			tripMillis = nextTripMillis == 0 ? depot.findNextTrip(trainId, System.currentTimeMillis()) : nextTripMillis;
			tripRouteId = depot.getTrainRoutes(trainId).get(0).id;
			nextTripMillis = 0;
			updateTripPath();
			reserveNextTrip();
		}
	}

	/**
	 * Reaching the start of the next route on the path, or the turn back rail just before it, moves the train onto that route's
	 * next service. The train then waits for it here, so a train with a turn back rail doesn't hold up the platform. Without a
	 * service it carries on with the time it has.
	 */
	@Override
	protected void onStopReached() {
		// The first stop of a joined loop ends the service the train joined, like the end of any lap
		if (isJoiningLoop && stopTimes != null && nextStoppingIndex >= stopTimes.getFirstIndex()) {
			isJoiningLoop = false;
			onLapComplete();
		}

		final RouteTimetable.StopPlan stopPlan = getStopPlan(getPlanIndex(nextStoppingIndex));
		final RouteTimetable.StopPlan startPlan = stopPlan == null || stopPlan.startsRoute ? stopPlan : stopPlan.nextRouteStart;
		if (startPlan != null && startPlan.startsRoute && startPlan.route.id != tripRouteId) {
			final long plannedStartMillis = getPlannedMillis(startPlan.routeStartMillis);
			final long readyMillis = System.currentTimeMillis() + startPlan.arrivalMillis - stopPlan.arrivalMillis;
			final boolean isLinked = tripPath != null && linkedService >= 0 && startPlan.route.id == tripPath.routeIds.get(tripPath.routeIds.size() - 1);
			final long serviceMillis = !startPlan.waitsForService ? 0 : isLinked ? RouteTimetable.getClosestService(plannedStartMillis, linkedService) : depot.findNextService(startPlan.route, Math.max(plannedStartMillis, readyMillis));
			tripMillis = serviceMillis == 0 ? plannedStartMillis : serviceMillis;
			tripRouteId = startPlan.route.id;
			updateTripPath();
			// The plan follows the route and table the train is on now, which can move where this route starts in it
			final RouteTimetable.StopPlan newStopPlan = getStopPlan(getPlanIndex(nextStoppingIndex));
			final RouteTimetable.StopPlan newStartPlan = newStopPlan == null || newStopPlan.startsRoute ? newStopPlan : newStopPlan.nextRouteStart;
			routeStartMillis = (newStartPlan == null ? startPlan : newStartPlan).routeStartMillis;
			// How long this route waited changes when the lap ends
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
			depot.reserveTrip(tripRouteId, tripMillis);
			if (nextTripMillis != 0) {
				final List<Route> routes = getTripRoutes();
				if (!routes.isEmpty()) {
					depot.reserveTrip(routes.get(0).id, nextTripMillis);
				}
			}
		} else if (tripMillis != 0 || isLastLap || tripPath != null) {
			tripMillis = 0;
			nextTripMillis = 0;
			routeStartMillis = 0;
			isJoiningLoop = false;
			leaveTripPath();
			setLastLap(false);
		}

		final int nextDepartureTicks = isOnRoute ? 0 : stopTimes == null ? depot.getNextDepartureMillis() : depot.getNextDepartureMillis(trainId, getLeadMillis());
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
						RailwayData.useRoutesAndStationsFromIndex(path.get(getIndex(timeSegment.endRailProgress, true)).stopIndex - 1, tripRouteIds.isEmpty() ? depot.getRouteIds(trainId, dataCache) : tripRouteIds, dataCache, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
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
		depotRepeatIndex1 = newRepeatIndex1;
		depotRepeatIndex2 = newRepeatIndex2;
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
	public void deployTrain(long newTripMillis, long routeId) {
		canDeploy = true;
		tripMillis = newTripMillis;
		tripRouteId = routeId;
		routeStartMillis = 0;
		nextTripMillis = 0;
		isJoiningLoop = stopTimes != null && stopTimes.hasJoinStops();
		updateTripPath();
		reserveNextTrip();
	}

	/**
	 * @param depotStopTimes the stop times of the usual path of the siding
	 * @param newTripPaths   the paths of services leaving it
	 */
	public void setStopTimes(Siding.StopTimes depotStopTimes, Map<String, Siding.TripPath> newTripPaths) {
		stopTimes = tripPath == null ? depotStopTimes : tripPath.stopTimes;
		tripPaths = newTripPaths;
	}

	public boolean isOnTripPath() {
		return tripPath != null;
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
	public boolean restoreTripPath(Map<String, Siding.TripPath> newTripPaths) {
		if (tripPathKey.isEmpty()) {
			return true;
		}
		final Siding.TripPath newTripPath = newTripPaths.get(tripPathKey);
		if (newTripPath == null) {
			return false;
		}
		useTripPath(newTripPath);
		return true;
	}

	/**
	 * Moves the train onto the path of a service running through to another route. The path is the same up to where the train
	 * leaves the usual one, so this works wherever the train is. A loop joined part of the way round changes over at its first stop.
	 */
	private void updateTripPath() {
		if (tripPath != null || isJoiningLoop || depot == null || tripMillis == 0) {
			return;
		}
		final List<Route> routes = depot.getTrainRoutes(trainId);
		final Route lastRoute = routes.isEmpty() ? null : routes.get(routes.size() - 1);
		if (lastRoute != null && lastRoute.id == tripRouteId) {
			final RouteTimetable.ServiceLink link = lastRoute.timetable.getLink(tripMillis);
			final Siding.TripPath newTripPath = link == null ? null : tripPaths.get(Siding.TripTail.getKey(link.routeId, link.fromStop));
			if (newTripPath != null) {
				linkedService = link.service;
				useTripPath(newTripPath);
			}
		}
	}

	private void useTripPath(Siding.TripPath newTripPath) {
		tripPath = newTripPath;
		tripPathKey = newTripPath.key;
		path = newTripPath.path;
		distances = newTripPath.distances;
		timeSegments = newTripPath.timeSegments;
		stopTimes = newTripPath.stopTimes;
		tripRouteIds.clear();
		tripRouteIds.addAll(newTripPath.routeIds);
		tripRoutes.clear();
		pathRepeatIndex1 = 0;
		pathRepeatIndex2 = 0;
		nextTripMillis = 0;
		setLastLap(false);
		syncRepeatIndices = true;
	}

	private void leaveTripPath() {
		if (tripPath != null) {
			tripPath = null;
			tripPathKey = "";
			linkedService = -1;
			path = depotPath;
			distances = depotDistances;
			timeSegments = depotTimeSegments;
			tripRouteIds.clear();
			tripRoutes.clear();
			pathRepeatIndex1 = depotRepeatIndex1;
			pathRepeatIndex2 = depotRepeatIndex2;
			syncRepeatIndices = true;
		}
	}

	/**
	 * @return the routes of the path the train is on
	 */
	private List<Route> getTripRoutes() {
		if (tripPath == null) {
			return depot.getTrainRoutes(trainId);
		}
		if (tripRoutes.isEmpty() && dataCache != null) {
			tripPath.routeIds.forEach(routeId -> {
				final Route route = dataCache.routeIdMap.get(routeId);
				if (route != null) {
					tripRoutes.add(route);
				}
			});
		}
		return tripRoutes;
	}

	/**
	 * @return the time from leaving the siding until the planned arrival at the first stop of a service. A train joining a
	 * loop part of the way round reaches its first stop after that time, so this can be negative.
	 */
	public long getLeadMillis() {
		if (stopTimes == null) {
			return 0;
		}
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		if (stopTimes.hasJoinStops() && timetable != null) {
			final List<Route> routes = depot.getTrainRoutes(trainId);
			final RouteTimetable.StopPlan stopPlan = stopTimes.getPlan(routes, timetable, stopTimes.getFirstJoinStopLapIndex(), routes.get(0).id, 0);
			return stopTimes.getLeadMillis() - (stopPlan == null ? 0 : stopPlan.arrivalMillis);
		}
		return stopTimes.getLeadMillis();
	}

	private void reserveNextTrip() {
		if (pathRepeatIndex1 > 0 && pathRepeatIndex2 > 0) {
			final RouteTimetable.StopPlan lastStopPlan = getStopPlan(pathRepeatIndex2);
			nextTripMillis = lastStopPlan == null ? 0 : depot.findNextTrip(trainId, getPlannedMillis(lastStopPlan.arrivalMillis));
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
		final RouteTimetable timetable = depot == null ? null : depot.getTimetable(trainId);
		if (tripMillis == 0 || timetable == null || stopTimes == null) {
			return null;
		}
		final List<Route> routes = getTripRoutes();
		return stopTimes.getPlan(routes, timetable, stopIndex, tripRouteId, getTripTable(routes));
	}

	/**
	 * @return the table of the current route that the current service belongs to
	 */
	private int getTripTable(List<Route> routes) {
		for (final Route route : routes) {
			if (route.id == tripRouteId) {
				return route.timetable.getTableIndex(tripMillis);
			}
		}
		return 0;
	}

	/**
	 * @param planMillis a time from a stop plan
	 * @return the same time on the current service as a full timestamp
	 */
	private long getPlannedMillis(long planMillis) {
		return tripMillis + planMillis - routeStartMillis;
	}

	private long getPlannedArrivalMillis(int stopIndex) {
		if (isRepeat() && stopIndex >= repeatIndex2 || isJoiningLoop && stopTimes != null && stopIndex == stopTimes.getFirstIndex()) {
			return nextTripMillis;
		} else {
			final RouteTimetable.StopPlan stopPlan = getStopPlan(stopIndex);
			return stopPlan == null ? 0 : getPlannedMillis(stopPlan.arrivalMillis);
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
