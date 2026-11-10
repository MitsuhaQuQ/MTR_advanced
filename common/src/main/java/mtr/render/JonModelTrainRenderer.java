package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import mtr.client.CustomBogie;
import mtr.client.DynamicTrainModel;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.IGui;
import mtr.data.Train;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import mtr.mappings.UtilitiesClient;
import mtr.model.ModelBogie;
import mtr.model.ModelCableCarGrip;
import mtr.model.ModelTrainBase;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Draws trains with models of MTR itself: the built-in Java models and the JSON models of resource packs. It also
 * draws the bogies, placing them on the track when the car is near, and for vehicles with custom bogies places the car
 * body on its bogies as MTR 4 does (see section 10 of {@code MTR-加载MTR4资源包设计.md}).
 */
public class JonModelTrainRenderer extends TrainRendererBase implements IGui {

	private final TrainClient train;

	public final ModelTrainBase model;
	public final String textureId;
	public final String gangwayConnectionId;
	public final String trainBarrierId;

	private static final EntityModel<Minecart> MODEL_MINECART = UtilitiesClient.getMinecartModel();
	private static final EntityModel<Boat> MODEL_BOAT = UtilitiesClient.getBoatModel();
	private static final Map<Long, FakeBoat> BOATS = new HashMap<>();
	private static final ModelCableCarGrip MODEL_CABLE_CAR_GRIP = new ModelCableCarGrip();
	private static final ModelBogie MODEL_BOGIE = new ModelBogie();
	// A bogie's heading comes from two track points this far either side of its centre
	private static final double BOGIE_HALF_LENGTH = 1;

	// Client setting: built-in bogies of near cars also turn along the track, as in MTR 4 (custom bogies always do)
	public static boolean builtInBogiesFollowTrack = true;

	private JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId, TrainClient train) {
		this.model = model;
		this.textureId = resolvePath(textureId);
		this.gangwayConnectionId = resolvePath(gangwayConnectionId);
		this.trainBarrierId = resolvePath(trainBarrierId);
		this.train = train;
	}

	public JonModelTrainRenderer(ModelTrainBase model, String textureId, String gangwayConnectionId, String trainBarrierId) {
		this(model, textureId, gangwayConnectionId, trainBarrierId, null);
	}

	@Override
	public TrainRendererBase createTrainInstance(TrainClient train) {
		return new JonModelTrainRenderer(model, textureId, gangwayConnectionId, trainBarrierId, train);
	}

	public double getCarCullRadius() {
		if (model == null || model.getClass() != DynamicTrainModel.class || train.transportMode != TransportMode.TRAIN) {
			return Double.POSITIVE_INFINITY;
		}
		final TrainProperties properties = TrainClientRegistry.getTrainProperties(train.trainId);
		if (properties.hasCustomBogies()) {
			return Double.POSITIVE_INFINITY;
		}
		// The built-in bogies may follow the track away from the car's chord on curves.
		final double bogieRadius = train.spacing + Math.abs(properties.bogiePosition) + 4;
		return Math.max(bogieRadius, ((DynamicTrainModel) model).getCullRadius());
	}

	@Override
	public void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen) {
		final String trainId = train.trainId;
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);

		// The car's centre between its two ends, which bogies on the track are placed from
		final double endsX = x;
		final double endsY = y;
		final double endsZ = z;

		// Cars with bogies from the resource pack stand on their bogies, as in MTR 4; the rest stay placed by their two ends
		if (trainProperties.hasCustomBogies() && !trainProperties.isJacobsBogie) {
			final double[] placement = getPlacementOnBogies(trainProperties, carIndex);
			if (placement != null) {
				x += placement[0];
				y += placement[1];
				z += placement[2];
				yaw = (float) placement[3];
				pitch = (float) placement[4];
			}
		}

		final float doorLeftValue = doorLeftOpen ? train.getDoorValue() : 0;
		final float doorRightValue = doorRightOpen ? train.getDoorValue() : 0;
		final boolean atPlatform = train.path.get(train.getIndex(0, train.spacing, true)).dwellTime > 0;
		final boolean hasPitch = pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending;

		if (model == null && isTranslucentBatch) {
			return;
		}

		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		matrices.pushPose();
		translateToWorld(train.getViewOffset(), x, y, z);
		UtilitiesClient.rotateY(matrices, (float) Math.PI + yaw);
		UtilitiesClient.rotateX(matrices, (float) Math.PI + (hasPitch ? pitch : 0));

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));
		boolean renderDetails = false;
		boolean bogiesOnTrack = false;

		if (model == null || textureId == null) {
			final boolean isBoat = train.transportMode == TransportMode.BOAT;

			matrices.translate(0, isBoat ? 0.875 : 0.5, 0);
			UtilitiesClient.rotateYDegrees(matrices, 90);

			final EntityModel<? extends Entity> model = isBoat ? MODEL_BOAT : MODEL_MINECART;
			final VertexConsumer vertexConsumer = vertexConsumers.getBuffer(model.renderType(resolveTexture(textureId, textureId -> textureId + ".png")));

			if (isBoat) {
				if (!BOATS.containsKey(train.id)) {
					BOATS.put(train.id, new FakeBoat());
				}
				MODEL_BOAT.setupAnim(BOATS.get(train.id), (train.getSpeed() + Train.ACCELERATION_DEFAULT) * (doorLeftValue == 0 && doorRightValue == 0 ? lastFrameDuration : 0), 0, -0.1F, 0, 0);
			} else {
				model.setupAnim(null, 0, 0, -0.1F, 0, 0);
			}

			model.renderToBuffer(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
		} else if (!textureId.isEmpty()) {
			renderDetails = MTRClient.isReplayMod() || posAverage.distSqr(camera.getBlockPosition()) <= RenderTrains.DETAIL_RADIUS_SQUARED;
			model.render(matrices, vertexConsumers, train, resolveTexture(textureId, textureId -> textureId + ".png"), light, doorLeftValue, doorRightValue, train.isDoorOpening(), carIndex, train.trainCars, !train.isReversed(), train.getIsOnRoute(), isTranslucentBatch, renderDetails, atPlatform);

			if (trainProperties.hasCustomBogies() && !trainProperties.isJacobsBogie) {
				// Near cars turn their bogies along the track after the car is drawn; far ones keep them fixed to the body
				if (renderDetails) {
					bogiesOnTrack = true;
				} else {
					renderBogieOnBody(trainProperties.bogie1, carIndex, light, false, atPlatform);
					renderBogieOnBody(trainProperties.bogie2, carIndex, light, false, atPlatform);
				}
			} else if (trainProperties.bogiePosition != 0 && !isTranslucentBatch) {
				if (builtInBogiesFollowTrack && renderDetails && !trainProperties.isJacobsBogie) {
					bogiesOnTrack = true;
				} else if (trainProperties.isJacobsBogie) {
					if (carIndex == 0) {
						MODEL_BOGIE.render(matrices, vertexConsumers, light, train.trainCars == 1 ? 0 : -(int) (trainProperties.bogiePosition * 16));
					} else if (carIndex == train.trainCars - 1) {
						MODEL_BOGIE.render(matrices, vertexConsumers, light, (int) (trainProperties.bogiePosition * 16));
					}
				} else {
					MODEL_BOGIE.render(matrices, vertexConsumers, light, (int) (trainProperties.bogiePosition * 16));
					MODEL_BOGIE.render(matrices, vertexConsumers, light, -(int) (trainProperties.bogiePosition * 16));
				}
			}
		}

		if (train.transportMode == TransportMode.CABLE_CAR && !isTranslucentBatch) {
			matrices.translate(0, TransportMode.CABLE_CAR.railOffset + 0.5, 0);
			if (!hasPitch) {
				UtilitiesClient.rotateX(matrices, pitch);
			}
			if (trainId.endsWith("_rht")) {
				UtilitiesClient.rotateYDegrees(matrices, 180);
			}
			MODEL_CABLE_CAR_GRIP.render(matrices, vertexConsumers, light);
		}

		matrices.popPose();

		if (bogiesOnTrack) {
			if (trainProperties.hasCustomBogies()) {
				renderBogieOnTrack(trainProperties.bogie1, carIndex, endsX, endsY, endsZ, light, atPlatform);
				renderBogieOnTrack(trainProperties.bogie2, carIndex, endsX, endsY, endsZ, light, atPlatform);
			} else {
				renderBogieOnTrack(new CustomBogie(null, null, trainProperties.bogiePosition), carIndex, endsX, endsY, endsZ, light, atPlatform);
				renderBogieOnTrack(new CustomBogie(null, null, -trainProperties.bogiePosition), carIndex, endsX, endsY, endsZ, light, atPlatform);
			}
		}

		matrices.popPose();
	}

	/**
	 * MTR 4's car placement: the body runs through the centres of its two bogies on the track and is shifted along itself
	 * by the bogies' average position. Returns the shift from the car's centre between its ends, then yaw and pitch.
	 */
	private double[] getPlacementOnBogies(TrainProperties trainProperties, int carIndex) {
		final float position1 = trainProperties.bogie1 == null ? trainProperties.bogie2.position : trainProperties.bogie1.position;
		final float position2 = trainProperties.bogie2 == null ? position1 : trainProperties.bogie2.position;
		final float positionLow = Math.min(position1, position2);
		final float positionHigh = Math.max(position1, position2);

		final Vec3 carEnd1 = train.getTrackPosition(carIndex, -train.spacing / 2D);
		final Vec3 carEnd2 = train.getTrackPosition(carIndex, train.spacing / 2D);
		final Vec3 bogieLowEnd1 = train.getTrackPosition(carIndex, positionLow - BOGIE_HALF_LENGTH);
		final Vec3 bogieLowEnd2 = train.getTrackPosition(carIndex, positionLow + BOGIE_HALF_LENGTH);
		final Vec3 bogieHighEnd1 = train.getTrackPosition(carIndex, positionHigh - BOGIE_HALF_LENGTH);
		final Vec3 bogieHighEnd2 = train.getTrackPosition(carIndex, positionHigh + BOGIE_HALF_LENGTH);
		if (carEnd1 == null || carEnd2 == null || bogieLowEnd1 == null || bogieLowEnd2 == null || bogieHighEnd1 == null || bogieHighEnd2 == null) {
			return null;
		}

		final Vec3 bogieLow = bogieLowEnd1.add(bogieLowEnd2).scale(0.5);
		final Vec3 bogieHigh = bogieHighEnd1.add(bogieHighEnd2).scale(0.5);
		// With one bogie, or both at the same place, the body takes that bogie's heading
		final Vec3 direction = positionLow == positionHigh ? bogieLowEnd2.subtract(bogieLowEnd1) : bogieHigh.subtract(bogieLow);
		if (direction.lengthSqr() == 0) {
			return null;
		}

		final Vec3 unit = direction.normalize();
		final Vec3 center = bogieLow.add(bogieHigh).scale(0.5).subtract(unit.scale((positionLow + positionHigh) / 2D));
		return new double[]{
				center.x - (carEnd1.x + carEnd2.x) / 2,
				center.y - (carEnd1.y + carEnd2.y) / 2,
				center.z - (carEnd1.z + carEnd2.z) / 2,
				Math.atan2(unit.x, unit.z),
				Math.asin(Math.max(-1, Math.min(1, unit.y)))
		};
	}

	// Level 1: the bogie moves with the car body, like the built-in bogies
	private void renderBogieOnBody(CustomBogie bogie, int carIndex, int light, boolean renderDetails, boolean atPlatform) {
		if (bogie == null) {
			return;
		}
		matrices.pushPose();
		matrices.translate(0, 0, bogie.position);
		renderBogieModel(bogie, carIndex, light, renderDetails, atPlatform);
		matrices.popPose();
	}

	// Level 2: the bogie stands on the track and turns with it; the car body stays placed by its two ends as before
	private void renderBogieOnTrack(CustomBogie bogie, int carIndex, double x, double y, double z, int light, boolean atPlatform) {
		if (bogie == null) {
			return;
		}

		final Vec3 carEnd1 = train.getTrackPosition(carIndex, -train.spacing / 2D);
		final Vec3 carEnd2 = train.getTrackPosition(carIndex, train.spacing / 2D);
		final Vec3 bogieEnd1 = train.getTrackPosition(carIndex, bogie.position - BOGIE_HALF_LENGTH);
		final Vec3 bogieEnd2 = train.getTrackPosition(carIndex, bogie.position + BOGIE_HALF_LENGTH);
		if (carEnd1 == null || carEnd2 == null || bogieEnd1 == null || bogieEnd2 == null) {
			return;
		}

		final double bogieLength = bogieEnd2.distanceTo(bogieEnd1);
		final float yaw = (float) Math.atan2(bogieEnd2.x - bogieEnd1.x, bogieEnd2.z - bogieEnd1.z);
		final float pitch = bogieLength == 0 ? 0 : (float) Math.asin((bogieEnd2.y - bogieEnd1.y) / bogieLength);
		final boolean hasPitch = pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending;

		// x, y and z are the car's centre as drawn (riding offset included), so move by the bogie's distance from the car's centre
		matrices.pushPose();
		translateToWorld(
				train.getViewOffset(),
				x + (bogieEnd1.x + bogieEnd2.x - carEnd1.x - carEnd2.x) / 2,
				y + (bogieEnd1.y + bogieEnd2.y - carEnd1.y - carEnd2.y) / 2,
				z + (bogieEnd1.z + bogieEnd2.z - carEnd1.z - carEnd2.z) / 2
		);
		UtilitiesClient.rotateY(matrices, (float) Math.PI + yaw);
		UtilitiesClient.rotateX(matrices, (float) Math.PI + (hasPitch ? pitch : 0));
		renderBogieModel(bogie, carIndex, light, true, atPlatform);
		matrices.popPose();
	}

	private void renderBogieModel(CustomBogie bogie, int carIndex, int light, boolean renderDetails, boolean atPlatform) {
		if (bogie.model == null) {
			if (!isTranslucentBatch) {
				MODEL_BOGIE.render(matrices, vertexConsumers, light, 0);
			}
		} else {
			bogie.model.render(matrices, vertexConsumers, train, resolveTexture(bogie.textureId, textureId -> textureId + ".png"), light, 0, 0, false, carIndex, train.trainCars, !train.isReversed(), train.getIsOnRoute(), isTranslucentBatch, renderDetails, atPlatform);
		}
	}

	@Override
	public void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		final String trainId = train.trainId;
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));

		if (!gangwayConnectionId.isEmpty()) {
			final VertexConsumer vertexConsumerExterior = vertexConsumers.getBuffer(MoreRenderLayers.getExterior(getConnectorTextureString(true, "exterior")));
			drawTexture(matrices, vertexConsumerExterior, thisPos2, prevPos3, prevPos4, thisPos1, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos2, thisPos3, thisPos4, prevPos1, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos3, thisPos2, thisPos3, prevPos2, light);
			drawTexture(matrices, vertexConsumerExterior, prevPos1, thisPos4, thisPos1, prevPos4, light);

			final int lightOnLevel = train.getIsOnRoute() ? RenderTrains.MAX_LIGHT_INTERIOR : light;
			final VertexConsumer vertexConsumerSide = vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "side")));
			drawTexture(matrices, vertexConsumerSide, thisPos3, prevPos2, prevPos1, thisPos4, lightOnLevel);
			drawTexture(matrices, vertexConsumerSide, prevPos3, thisPos2, thisPos1, prevPos4, lightOnLevel);
			drawTexture(matrices, vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "roof"))), prevPos2, thisPos3, thisPos2, prevPos3, lightOnLevel);
			drawTexture(matrices, vertexConsumers.getBuffer(MoreRenderLayers.getInterior(getConnectorTextureString(true, "floor"))), prevPos4, thisPos1, thisPos4, prevPos1, lightOnLevel);
		}

		if (trainProperties.isJacobsBogie) {
			matrices.pushPose();
			translateToWorld(train.getViewOffset(), x, y, z);
			UtilitiesClient.rotateY(matrices, (float) Math.PI + yaw);
			UtilitiesClient.rotateX(matrices, (float) Math.PI + ((pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending) ? pitch : 0));
			MODEL_BOGIE.render(matrices, vertexConsumers, light, 0);
			matrices.popPose();
		}

		matrices.popPose();
	}

	@Override
	public void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
		if (StringUtils.isEmpty(trainBarrierId)) {
			return;
		}

		final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
		if (posAverage == null) {
			return;
		}

		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));

		final VertexConsumer vertexConsumerExterior = vertexConsumers.getBuffer(MoreRenderLayers.getExterior(getConnectorTextureString(false, "exterior")));
		drawTexture(matrices, vertexConsumerExterior, thisPos2, prevPos3, prevPos4, thisPos1, light);
		drawTexture(matrices, vertexConsumerExterior, prevPos2, thisPos3, thisPos4, prevPos1, light);
		drawTexture(matrices, vertexConsumerExterior, thisPos3, prevPos2, prevPos1, thisPos4, light);
		drawTexture(matrices, vertexConsumerExterior, prevPos3, thisPos2, thisPos1, prevPos4, light);

		matrices.popPose();
	}

	public ResourceLocation resolveTexture(String textureId, Function<String, String> formatter) {
		final String textureString = formatter.apply(textureId);
		final ResourceLocation id = new ResourceLocation(textureString);
		final boolean available;

		if (!RenderTrains.AVAILABLE_TEXTURES.contains(textureString) && !RenderTrains.UNAVAILABLE_TEXTURES.contains(textureString)) {
			available = UtilitiesClient.hasResource(id);
			(available ? RenderTrains.AVAILABLE_TEXTURES : RenderTrains.UNAVAILABLE_TEXTURES).add(textureString);
			if (!available) {
				System.out.println("Texture " + textureString + " not found, using default");
			}
		} else {
			available = RenderTrains.AVAILABLE_TEXTURES.contains(textureString);
		}

		if (available) {
			return id;
		} else {
			final TrainRendererBase baseRenderer = TrainClientRegistry.getTrainProperties(train.baseTrainType).renderer;
			return new ResourceLocation((!(baseRenderer instanceof JonModelTrainRenderer) ? "mtr:textures/block/transparent.png" : formatter.apply(((JonModelTrainRenderer) baseRenderer).textureId)));
		}
	}

	private ResourceLocation getConnectorTextureString(boolean isConnector, String partName) {
		return resolveTexture(isConnector ? gangwayConnectionId : trainBarrierId, textureId -> String.format("%s_%s_%s.png", textureId, isConnector ? "connector" : "barrier", partName));
	}

	private void drawTexture(PoseStack matrices, VertexConsumer vertexConsumer, Vec3 pos1, Vec3 pos2, Vec3 pos3, Vec3 pos4, int light) {
		if (train != null && train.getViewOffset() == null) {
			mtr.client.IDrawing.drawTextureWorld(matrices, vertexConsumer, pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z, pos3.x, pos3.y, pos3.z, pos4.x, pos4.y, pos4.z, 0, 0, 1, 1, Direction.UP, -1, light);
		} else {
			mtr.client.IDrawing.drawTexture(matrices, vertexConsumer, (float) pos1.x, (float) pos1.y, (float) pos1.z, (float) pos2.x, (float) pos2.y, (float) pos2.z, (float) pos3.x, (float) pos3.y, (float) pos3.z, (float) pos4.x, (float) pos4.y, (float) pos4.z, 0, 0, 1, 1, Direction.UP, -1, light);
		}
	}

	private static String resolvePath(String path) {
		return path == null ? null : path.toLowerCase(Locale.ENGLISH).split("\\.png")[0];
	}

	private static class FakeBoat extends Boat {

		private float progress;

		public FakeBoat() {
			super(EntityType.BOAT, null);
		}

		@Override
		public float getRowingTime(int paddle, float newProgress) {
			progress += newProgress;
			return progress;
		}
	}
}
