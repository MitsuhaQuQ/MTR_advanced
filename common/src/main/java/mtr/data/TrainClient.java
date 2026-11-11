package mtr.data;

import cn.zbx1425.mtrsteamloco.block.BlockEyeCandy.BlockEntityEyeCandy;
import mtr.MTRClient;
import mtr.client.*;
import mtr.render.RenderDrivingOverlay;
import mtr.render.JonModelTrainRenderer;
import mtr.render.TrainRendererBase;
import mtr.render.WorldRenderVisibility;
import mtr.sound.TrainSoundBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import transitsim.motion.PlatformDwell;
import transitsim.motion.RunSegment;

import java.util.*;

/**
 * A train on the client, made from what the server sends ({@code ClientData.updateTrains}). It runs the same motion
 * rules as the server between the server's updates, moves the players riding it, and draws each car with its
 * {@link #trainRenderer}, chosen from its {@link mtr.client.TrainProperties}.
 * <p>
 * With ANTE in the build, {@code TrainClientMixin} replaces {@code simulateTrain} as a whole (current station,
 * announcements, sounds), so changes to it here don't take effect there.
 */
public class TrainClient extends Train implements IGui {

	public boolean isRemoved = false;
	private boolean justMounted;
	private float oldSpeed;
	private double oldRailProgress;
	private float oldDoorValue;
	private boolean doorOpening;
	private boolean isSitting;
	private boolean previousShifting;

	private int currentStationIndex;
	private Route thisRoute;
	private Route nextRoute;
	private Station thisStation;
	private Station nextStation;
	private Station lastStation;

	private SpeedCallback speedCallback;
	private AnnouncementCallback announcementCallback;
	private AnnouncementCallback lightRailAnnouncementCallback;
	private Depot depot;
	private List<Long> routeIds = new ArrayList<>();
	private int announcementHead = -1;
	private mtr.path.PathData announcementRail;
	private long announcementPlatform;

	public final TrainRendererBase trainRenderer;
	public final TrainSoundBase trainSound;
	public final VehicleRidingClient vehicleRidingClient = new VehicleRidingClient(ridingEntities, PACKET_UPDATE_TRAIN_PASSENGER_POSITION);
	public final List<ScrollingText> scrollingTexts = new ArrayList<>();

	private final Set<Runnable> trainTranslucentRenders = new HashSet<>();
	private final Set<BlockEntityEyeCandy> activePlatformDoors = new HashSet<>();

	private static final float CONNECTION_HEIGHT = 2.25F;
	private static final float CONNECTION_Z_OFFSET = 0.5F;
	private static final float CONNECTION_X_OFFSET = 0.25F;

	public TrainClient(FriendlyByteBuf packet) {
		super(packet);
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
		trainRenderer = trainProperties.renderer.createTrainInstance(this);
		trainSound = trainProperties.sound.createTrainInstance(this);
	}

	@Override
	protected void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	) {
		final LocalPlayer clientPlayer = Minecraft.getInstance().player;
		if (clientPlayer == null) {
			return;
		}

		final BlockPos soundPos = RailwayData.newBlockPos(carX, carY, carZ);
		trainSound.playAllCars(world, soundPos, ridingCar);
		if (doorLeftOpen || doorRightOpen) {
			trainSound.playAllCarsDoorOpening(world, soundPos, ridingCar);
		}

		final Vec3 offset = vehicleRidingClient.renderPlayerAndGetOffset();
		final double newX = carX - offset.x;
		final double newY = carY - offset.y;
		final double newZ = carZ - offset.z;

		renderCarModels(ridingCar, newX, newY, newZ, carYaw, carPitch, doorLeftOpen, doorRightOpen);

		if (ridingCar > 0 && shouldRenderCarConnection(prevCarX, prevCarY, prevCarZ, carX, carY, carZ)) {
			final double newPrevCarX = prevCarX - offset.x;
			final double newPrevCarY = prevCarY - offset.y;
			final double newPrevCarZ = prevCarZ - offset.z;

			final Vec3 prevPos0 = new Vec3(0, 0, spacing / 2D - 1).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
			final Vec3 thisPos0 = new Vec3(0, 0, -(spacing / 2D - 1)).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
			final Vec3 connectPos = prevPos0.add(thisPos0).scale(0.5);
			final float connectYaw = (float) Mth.atan2(thisPos0.x - prevPos0.x, thisPos0.z - prevPos0.z);
			final float connectPitch = realSpacing == 0 ? 0 : (float) asin((thisPos0.y - prevPos0.y) / realSpacing);

			for (int i = 0; i < 2; i++) {
				final double xStart = width / 2D + (i == 0 ? -1 : 0.5) * CONNECTION_X_OFFSET;
				final double zStart = spacing / 2D - (i == 0 ? 1 : 2) * CONNECTION_Z_OFFSET;

				final Vec3 prevPos1 = new Vec3(xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos2 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos3 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos4 = new Vec3(-xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);

				final Vec3 thisPos1 = new Vec3(-xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos2 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos3 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos4 = new Vec3(xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);

				if (i == 0) {
					trainRenderer.renderConnection(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				} else {
					trainRenderer.renderBarrier(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				}
			}
		}
	}

	protected void renderCarModels(int car, double x, double y, double z, float yaw, float pitch, boolean leftOpen, boolean rightOpen) {
		doorOpening = doorValue > oldDoorValue;
		if (WorldRenderVisibility.hasFrustum() && trainRenderer.getClass() == JonModelTrainRenderer.class && getViewOffset() == null
				&& !WorldRenderVisibility.isVisible(x, y, z, ((JonModelTrainRenderer) trainRenderer).getCarCullRadius())) {
			return;
		}
		trainRenderer.renderCar(car, x, y, z, yaw, pitch, leftOpen, rightOpen);
		trainTranslucentRenders.add(() -> trainRenderer.renderCar(car, x, y, z, yaw, pitch, leftOpen, rightOpen));
	}

	protected boolean shouldRenderCarConnection(double x, double y, double z, double endX, double endY, double endZ) {
		return !WorldRenderVisibility.hasFrustum() || trainRenderer.getClass() != JonModelTrainRenderer.class || getViewOffset() != null
				|| WorldRenderVisibility.isVisible(x, y, z, endX, endY, endZ, spacing / 2D + width / 2D + 5);
	}

	@Override
	protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer clientPlayer = client.player;
		if (clientPlayer == null) {
			return false;
		}

		vehicleRidingClient.begin();

		if (ticksElapsed > 0) {
			if (isPlayerRiding(clientPlayer)) {
				final int headIndex = getIndex(0, spacing, false);
				final int stopIndex = path.get(headIndex).stopIndex - 1;

				if (speedCallback != null) {
					speedCallback.speedCallback(speed * 20, stopIndex, routeIds);
				}

				if (announcementCallback != null) {
					final double targetProgress = distances.get(getPreviousStoppingIndex(headIndex)) + (trainCars + 1) * spacing;
					if (oldRailProgress < targetProgress && railProgress >= targetProgress) {
						announcementCallback.announcementCallback(stopIndex, routeIds);
					}
				}

				if (lightRailAnnouncementCallback != null && (justOpening() || justMounted)) {
					lightRailAnnouncementCallback.announcementCallback(stopIndex, routeIds);
				}
			}

			final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
			vehicleRidingClient.movePlayer(uuid -> {
				final CalculateCarCallback calculateCarCallback = (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> vehicleRidingClient.setOffsets(uuid, x, y, z, yaw, pitch, transportMode.maxLength == 1 ? spacing : realSpacingRender, width, doorLeftOpenRender, doorRightOpenRender, transportMode.hasPitchAscending, transportMode.hasPitchDescending, trainProperties.riderOffset, trainProperties.riderOffsetDismounting, speed > 0, doorValue == 0, () -> {
					final boolean isShifting = clientPlayer.isShiftKeyDown();
					if (Config.shiftToToggleSitting() && !MTRClient.isVivecraft()) {
						if (isShifting && !previousShifting) {
							isSitting = !isSitting;
						}
						clientPlayer.setPose(isSitting && !client.gameRenderer.getMainCamera().isDetached() ? Pose.CROUCHING : Pose.STANDING);
					}
					previousShifting = isShifting;
				});

				final int currentRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
				calculateCar(world, positions, currentRidingCar, 0, (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> {
					vehicleRidingClient.moveSelf(id, uuid, realSpacingRender, width, yaw, currentRidingCar, trainCars, doorLeftOpenRender, doorRightOpenRender, !trainProperties.hasGangwayConnection, ticksElapsed);

					final int newRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
					if (currentRidingCar == newRidingCar) {
						calculateCarCallback.calculateCarCallback(x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender);
					} else {
						calculateCar(world, positions, newRidingCar, 0, calculateCarCallback);
					}
				});
			});
		}

		vehicleRidingClient.end();
		justMounted = false;

		final Entity camera = client.cameraEntity;
		final Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
		double nearestDistance = Double.POSITIVE_INFINITY;
		int nearestCar = 0;
		for (int i = 0; i < trainCars; i++) {
			final double checkDistance = cameraPos.distanceToSqr(positions[i]);
			if (checkDistance < nearestDistance) {
				nearestCar = i;
				nearestDistance = checkDistance;
			}
		}
		final BlockPos soundPos = RailwayData.newBlockPos(positions[nearestCar].x, positions[nearestCar].y, positions[nearestCar].z);
		trainSound.playNearestCar(world, soundPos, nearestCar);

		return true;
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		return false;
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		return false;
	}

	@Override
	protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
		if (!Config.optimizeTrainRendering() || shouldScanPlatformDoors()) {
			return false;
		}
		clearPlatformDoors();
		return true;
	}

	private boolean shouldScanPlatformDoors() {
		if (!isOnRoute) {
			return false;
		}
		if (transportMode.continuousMovement) {
			return true;
		}
		if (speed != 0) {
			return false;
		}
		if (PlatformDwell.isAtStop(getMotionState(), getMotionTrack()) && path.get(nextStoppingIndex).savedRailBaseId != 0) {
			return true;
		}
		// A driver can stop and operate the doors away from the scheduled stopping point.
		if (isCurrentlyManual && (doorTarget || doorValue > 0)) {
			final int head = getIndex(railProgress, true);
			for (int i = getIndex(railProgress - trainCars * spacing, false); i >= 0 && i <= head; i++) {
				if (path.get(i).rail.railType == RailType.PLATFORM) {
					return true;
				}
			}
		}
		return false;
	}

	public void syncPlatformDoor(BlockEntityEyeCandy door) {
		door.syncTrainDoors(id, doorTarget, doorValue);
		if (!transportMode.continuousMovement && (doorTarget || doorValue > 0)) {
			activePlatformDoors.add(door);
		} else {
			activePlatformDoors.remove(door);
		}
	}

	public void clearPlatformDoors() {
		if (!activePlatformDoors.isEmpty()) {
			activePlatformDoors.forEach(door -> door.clearTrainDoors(id));
			activePlatformDoors.clear();
		}
	}

	@Override
	protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
		return true;
	}

	@Override
	protected float getModelZOffset() {
		return baseTrainType.startsWith("london_underground_199")
				|| trainId.startsWith("london_underground_199")
				|| baseTrainType.equals("mpl_85")
				|| trainId.equals("mpl_85")
				|| baseTrainType.equals("br_423")
				|| trainId.equals("br_423") ?
				reversed ? -0.5F : 0.5F : 0; // TODO integrate this into TrainClientRegistry
	}

	@Override
	protected double asin(double value) {
		return Math.asin(value);
	}

	public void simulateTrain(Level world, float ticksElapsed, SpeedCallback speedCallback, AnnouncementCallback announcementCallback, AnnouncementCallback lightRailAnnouncementCallback) {
		trainTranslucentRenders.clear();
		this.speedCallback = speedCallback;
		this.announcementCallback = announcementCallback;
		this.lightRailAnnouncementCallback = lightRailAnnouncementCallback;
		oldSpeed = speed;
		oldRailProgress = railProgress;
		oldDoorValue = doorValue;

		if (ticksElapsed != 0) {
			final int head = getIndex(0, spacing, false);
			final int stopIndex = path.get(head).stopIndex - 1;
			if (head != announcementHead || announcementRail != path.get(head)) {
				announcementHead = head; announcementRail = path.get(head); announcementPlatform = 0;
				for (int i=head;i>=0;i--) if (path.get(i).isPlatformTrack() && path.get(i).getStopId() != 0) { announcementPlatform = path.get(i).getStopId(); break; }
			}
			// The next station is where this train stops next on its own path: a stop it passes, by its service's exception
			// or a dispatcher's skip, isn't announced, which the route's own list of stops can't tell (0.65.27)
			final long nextStopId = transitsim.plan.AnnouncementStops.nextStop(path, head);
			final Station pathNextStation = nextStopId == 0 ? null : ClientData.DATA_CACHE.platformIdToStation.get(nextStopId);
			if (!RailwayData.useRoutesAndStationsFromIndex(stopIndex, announcementPlatform, routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute1, nextRoute1, thisStation1, nextStation1, lastStation1) -> {
				this.currentStationIndex = currentStationIndex;
				thisRoute = thisRoute1;
				nextRoute = nextRoute1;
				thisStation = thisStation1;
				nextStation = nextStation1 != null && pathNextStation != null ? pathNextStation : nextStation1;
				lastStation = lastStation1;
			})) {
				currentStationIndex = 0;
				thisRoute = null;
				nextRoute = null;
				thisStation = null;
				nextStation = null;
				lastStation = null;
			}
		}

		simulateTrain(world, ticksElapsed, null);

		if (depot == null || routeIds.isEmpty()) {
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
			depot = siding == null ? null : ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id);
			routeIds = !tripRouteIds.isEmpty() ? new ArrayList<>(tripRouteIds) : depot == null ? new ArrayList<>() : depot.getRouteIds(trainId, ClientData.DATA_CACHE);
			if (depot != null) {
				depot.lastDeployedMillis = System.currentTimeMillis();
			}
		}

		final LocalPlayer player = Minecraft.getInstance().player;
		if (isManualAllowed && Train.isHoldingKey(player) && isPlayerRiding(player)) {
			RenderDrivingOverlay.setData(manualNotch, this);
		}
	}

	public void renderTranslucent() {
		trainTranslucentRenders.forEach(Runnable::run);
		trainTranslucentRenders.clear();
	}

	public Vec3 getViewOffset() {
		return vehicleRidingClient.getViewOffset();
	}

	public int getCurrentStationIndex() {
		return currentStationIndex;
	}

	public Route getThisRoute() {
		return thisRoute;
	}

	public Route getNextRoute() {
		return nextRoute;
	}

	public Station getThisStation() {
		return thisStation;
	}

	public Station getNextStation() {
		return nextStation;
	}

	public Station getLastStation() {
		return lastStation;
	}

	public void startRidingClient(UUID uuid, float percentageX, float percentageZ) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getUUID().equals(uuid)) {
			justMounted = true;
			isSitting = false;
		}
		vehicleRidingClient.startRiding(uuid, percentageX, percentageZ);
	}

	public void updateRiderPercentages(UUID uuid, float percentageX, float percentageZ) {
		vehicleRidingClient.updateRiderPercentages(uuid, percentageX, percentageZ);
	}

	public void copyFromTrain(Train train) {
		// The same train gone on as a train of another siding of its pool stays the same train here (R1), its siding's
		// depot and routes looked up again
		if (sidingId != train.sidingId) {
			sidingId = train.sidingId;
			depot = null;
			routeIds = new ArrayList<>();
		}
		path.clear();
		distances.clear();
		ridingEntities.clear();

		path.addAll(train.path);
		distances.addAll(train.distances);
		ridingEntities.addAll(train.ridingEntities);

		// A train on a run segment is where the segment has it by the clock, which the train shown eases into (G2)
		if (train.segmentSpec == null) {
			speed = train.speed;
			railProgress = train.railProgress;
		}
		segmentSpec = train.segmentSpec;

		// Going onto or off a service running through to another line changes the routes the train announces
		if (!tripRouteIds.equals(train.tripRouteIds)) {
			tripRouteIds.clear();
			tripRouteIds.addAll(train.tripRouteIds);
			routeIds = new ArrayList<>();
		}
		repeatIndex1 = train.repeatIndex1;
		repeatIndex2 = train.repeatIndex2;

		doorTarget = train.doorTarget;
		elapsedDwellTicks = train.elapsedDwellTicks;
		nextStoppingIndex = train.nextStoppingIndex;
		nextPlatformIndex = train.nextPlatformIndex;
		reversed = train.reversed;
		isOnRoute = train.isOnRoute;
		isCurrentlyManual = train.isCurrentlyManual;
		manualNotch = train.manualNotch;
		useSpeedCurve = train.useSpeedCurve;
		decelerationConstant = train.decelerationConstant;
		cruiseCap = train.cruiseCap;
		// The path is refilled in place, so the curves can't tell it changed
		invalidateSpeedCurves();
	}

	/**
	 * What changes while a train runs, as {@link Train#writeStatePacket} writes it (G2), read on the network thread and put
	 * into the train on the client's.
	 */
	public static final class State {

		public final long id;
		public final int syncKey;
		private final int repeatIndex1;
		private final int repeatIndex2;
		private final List<Long> tripRouteIds = new ArrayList<>();
		private final float speed;
		private final double railProgress;
		private final float elapsedDwellTicks;
		private final int nextStoppingIndex;
		private final int nextPlatformIndex;
		private final boolean reversed;
		private final boolean isCurrentlyManual;
		private final boolean isOnRoute;
		private final int manualNotch;
		private final boolean doorTarget;
		private final List<UUID> ridingEntities = new ArrayList<>();
		private final boolean useSpeedCurve;
		private final float decelerationConstant;
		private final float cruiseCap;
		private final RunSegment.Spec segmentSpec;

		public State(FriendlyByteBuf packet) {
			id = packet.readLong();
			syncKey = packet.readInt();
			repeatIndex1 = packet.readInt();
			repeatIndex2 = packet.readInt();
			for (int i = packet.readInt(); i > 0; i--) {
				tripRouteIds.add(packet.readLong());
			}
			speed = packet.readFloat();
			railProgress = packet.readDouble();
			elapsedDwellTicks = packet.readFloat();
			nextStoppingIndex = packet.readInt();
			nextPlatformIndex = packet.readInt();
			reversed = packet.readBoolean();
			isCurrentlyManual = packet.readBoolean();
			isOnRoute = packet.readBoolean();
			manualNotch = packet.readInt();
			doorTarget = packet.readBoolean();
			for (int i = packet.readInt(); i > 0; i--) {
				ridingEntities.add(packet.readUUID());
			}
			useSpeedCurve = packet.readBoolean();
			decelerationConstant = packet.readFloat();
			cruiseCap = packet.readFloat();
			segmentSpec = readSegmentSpec(packet);
		}
	}

	/**
	 * Takes in what changed while the train runs (G2), as {@link #copyFromTrain} does without the path.
	 *
	 * @return false if the train's path isn't the one the state goes with, so it is left as it is
	 */
	public boolean applyState(State state) {
		if (state.syncKey != getSyncKey()) {
			return false;
		}
		ridingEntities.clear();
		ridingEntities.addAll(state.ridingEntities);
		if (!tripRouteIds.equals(state.tripRouteIds)) {
			tripRouteIds.clear();
			tripRouteIds.addAll(state.tripRouteIds);
			routeIds = new ArrayList<>();
		}
		repeatIndex1 = state.repeatIndex1;
		repeatIndex2 = state.repeatIndex2;
		if (state.segmentSpec == null) {
			speed = state.speed;
			railProgress = state.railProgress;
		}
		segmentSpec = state.segmentSpec;
		doorTarget = state.doorTarget;
		elapsedDwellTicks = state.elapsedDwellTicks;
		nextStoppingIndex = state.nextStoppingIndex;
		nextPlatformIndex = state.nextPlatformIndex;
		reversed = state.reversed;
		isOnRoute = state.isOnRoute;
		isCurrentlyManual = state.isCurrentlyManual;
		manualNotch = state.manualNotch;
		if (useSpeedCurve != state.useSpeedCurve || decelerationConstant != state.decelerationConstant) {
			invalidateSpeedCurves();
		}
		useSpeedCurve = state.useSpeedCurve;
		decelerationConstant = state.decelerationConstant;
		cruiseCap = state.cruiseCap;
		return true;
	}

	public final float speedChange() {
		return speed - oldSpeed;
	}

	public boolean justOpening() {
		return oldDoorValue == 0 && doorValue > 0;
	}

	public boolean justClosing(float doorCloseTime) {
		return oldDoorValue >= doorCloseTime && doorValue < doorCloseTime;
	}

	public final boolean isDoorOpening() {
		return doorOpening;
	}

	public final List<Long> getRouteIds() {
		return routeIds;
	}

	private int getPreviousStoppingIndex(int headIndex) {
		for (int i = headIndex; i >= 0; i--) {
			if (path.get(i).dwellTime > 0 && path.get(i).rail.railType == RailType.PLATFORM) {
				return i;
			}
		}
		return 0;
	}

	@FunctionalInterface
	public interface SpeedCallback {
		void speedCallback(float speed, int stopIndex, List<Long> routeIds);
	}

	@FunctionalInterface
	public interface AnnouncementCallback {
		void announcementCallback(int stopIndex, List<Long> routeIds);
	}
}
