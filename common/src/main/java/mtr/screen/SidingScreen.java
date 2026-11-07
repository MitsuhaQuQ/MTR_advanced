package mtr.screen;

import mtr.compat.GuiCompat;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.Icons;
import mtr.Patreon;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A siding in the game: its number, colour and vehicle. How many trains it holds and how its trains run (speeding up,
 * braking, highest speed, driving by hand, service types) are only shown here, as they run, and set in the web editor's
 * depot dialog (5c), so what the web sets isn't put back when this screen closes.
 */
public class SidingScreen extends SavedRailScreenBase<Siding> implements Icons {

	private boolean isSelectingTrain;

	private final TransportMode transportMode;
	private final Button buttonSelectTrain;
	private final DashboardList availableTrainsList;
	private final Depot depot;

	private static final Component SELECTED_TRAIN_TEXT = Text.translatable("gui.mtr.selected_vehicle");
	private static final Component MAX_TRAINS_TEXT = Text.translatable("gui.mtr.max_vehicles");
	/**
	 * For kinds running by timetables, the same number as the web editor's "trains it holds" (2026-11-07 review): what the
	 * siding holds, its pool's trains being shared.
	 */
	private static final Component CAPACITY_TEXT = Text.translatable("gui.mtr.siding_capacity");
	private static final Component ACCELERATION_CONSTANT_TEXT = Text.translatable("gui.mtr.acceleration");
	private static final Component MANUAL_TO_AUTOMATIC_TIME = Text.translatable("gui.mtr.manual_to_automatic_time");
	private static final Component MAX_MANUAL_SPEED = Text.translatable("gui.mtr.max_manual_speed");
	private static final Component SERVICE_TYPES_TEXT = Text.translatable("gui.mtr.vehicle_service_types");
	private static final int DESCRIPTION_WIDTH = 160;
	private static final float ACCELERATION_UNIT_CONVERSION_1 = 20 * 20; // m/tick^2 to m/s^2
	private static final float ACCELERATION_UNIT_CONVERSION_2 = ACCELERATION_UNIT_CONVERSION_1 * 3.6F; // m/tick^2 to km/h/s
	private static final Map<String, String> WIKIPEDIA_ARTICLES = new HashMap<>();

	public SidingScreen(Siding siding, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(siding, transportMode, dashboardScreen, SELECTED_TRAIN_TEXT, capacityText(transportMode), ACCELERATION_CONSTANT_TEXT, MANUAL_TO_AUTOMATIC_TIME, MAX_MANUAL_SPEED, SERVICE_TYPES_TEXT);
		this.transportMode = transportMode;
		depot = ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id);
		buttonSelectTrain = UtilitiesClient.newButton(button -> onSelectingTrain());
		availableTrainsList = new DashboardList(null, null, null, null, this::onAdd, null, null, () -> ClientData.TRAINS_SEARCH, text -> ClientData.TRAINS_SEARCH = text);
	}

	@Override
	protected void init() {
		super.init();

		IDrawing.setPositionAndWidth(buttonSelectTrain, SQUARE_SIZE + textWidth, SQUARE_SIZE * 2 + TEXT_FIELD_PADDING, width - textWidth - SQUARE_SIZE * 2);
		addDrawableChild(buttonSelectTrain);

		availableTrainsList.y = SQUARE_SIZE;
		availableTrainsList.height = height - SQUARE_SIZE * 2;
		availableTrainsList.width = width - DESCRIPTION_WIDTH - SQUARE_SIZE * 3;
		availableTrainsList.init(this::addDrawableChild);

		setIsSelectingTrain(false);
	}

	private static Component capacityText(TransportMode transportMode) {
		return mtr.data.Depot.supportsTimetable(transportMode) ? CAPACITY_TEXT : MAX_TRAINS_TEXT;
	}

	/**
	 * @return how its trains run: its pool's as the depot sets it, or as its pool's first siding has it
	 */
	private VehicleSettings getVehicle() {
		return depot == null ? savedRailBase.getOwnVehicleSettings() : depot.getVehicleSettings(savedRailBase);
	}

	@Override
	public void tick() {
		super.tick();
		availableTrainsList.tick();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.render(matrices, mouseX, mouseY, delta);
		if (!isSelectingTrain) {
			GuiCompat.draw(matrices, font, SELECTED_TRAIN_TEXT, SQUARE_SIZE, SQUARE_SIZE * 2 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE);
			if (showScheduleControls) {
				renderSettings(matrices);
			}
		} else {
			final int index = availableTrainsList.getHoverItemIndex();
			if (index >= 0) {
				final TrainProperties properties = TrainClientRegistry.getTrainProperties(transportMode, index);
				final int spacing = TrainType.getSpacing(properties.baseTrainType);
				final int cars = (int) Math.floor(savedRailBase.railLength / spacing);
				int y = SQUARE_SIZE;
				y = drawWrappedText(matrices, properties.name, y, ARGB_WHITE);
				y = drawWrappedText(matrices, Text.translatable("gui.mtr.vehicle_length", spacing - 1), y, ARGB_WHITE);
				y = drawWrappedText(matrices, Text.translatable("gui.mtr.cars_to_spawn", (cars == 0 ? WARNING + " " : "") + Math.min(cars, savedRailBase.transportMode.maxLength)), y, ARGB_WHITE);
				if (properties.description != null) {
					for (final String text : properties.description.split("[|\n]")) {
						y = drawWrappedText(matrices, Text.literal(text), y, ARGB_LIGHT_GRAY);
					}
				}
				if (properties.wikipediaArticle != null) {
					final String fullText = fetchWikipediaArticle(properties.wikipediaArticle);
					for (final String text : fullText.split("\n")) {
						y = drawWrappedText(matrices, Text.literal(text), y, ARGB_LIGHT_GRAY);
					}
				}
			}
		}
	}

	/**
	 * What the web editor sets for the siding and its pool, as its trains run with it.
	 */
	private void renderSettings(PoseStack matrices) {
		final VehicleSettings vehicle = getVehicle();
		final int valueX = SQUARE_SIZE + textWidth + TEXT_FIELD_PADDING / 2;
		int row = 3;
		final int limit = savedRailBase.getFleetSize();
		drawSetting(matrices, capacityText(transportMode), limit < 0 ? Text.translatable("gui.mtr.unlimited").getString() : String.valueOf(limit), row++, valueX);
		drawSetting(matrices, ACCELERATION_CONSTANT_TEXT, accelerationText(vehicle.acceleration) + "   " + Text.translatable("gui.mtr.deceleration", RailwayData.round(vehicle.getDeceleration() * ACCELERATION_UNIT_CONVERSION_1, 1)).getString(), row++, valueX);
		if (depot != null && Depot.supportsTimetable(transportMode)) {
			final String serviceTypes = depot.vehicleServiceTypes.getOrDefault(savedRailBase.getTrainId(), "");
			drawSetting(matrices, SERVICE_TYPES_TEXT, serviceTypes.isEmpty() ? "-" : serviceTypes, row++, valueX);
		}
		final String maxSpeed = vehicle.maxSpeed == 0 ? Text.translatable("gui.mtr.siding_max_speed_none").getString() : Text.translatable("gui.mtr.siding_max_speed", vehicle.maxSpeed).getString();
		GuiCompat.draw(matrices, font, Text.translatable(vehicle.manual ? "gui.mtr.siding_manual_allowed" : "gui.mtr.siding_manual_not_allowed").getString() + "   " + maxSpeed, SQUARE_SIZE, rowY(row++), ARGB_WHITE);
		if (vehicle.manual) {
			drawSetting(matrices, MAX_MANUAL_SPEED, speedText(vehicle.maxManualSpeed), row++, valueX);
			drawSetting(matrices, MANUAL_TO_AUTOMATIC_TIME, Text.translatable("gui.mtr.arrival_sec", vehicle.manualToAutomaticTime / 2F).getString(), row, valueX);
		}
		GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.siding_set_on_web").getString(), SQUARE_SIZE, height - SQUARE_SIZE * 2, ARGB_LIGHT_GRAY);
	}

	private void drawSetting(PoseStack matrices, Component label, String value, int row, int valueX) {
		GuiCompat.draw(matrices, font, label, SQUARE_SIZE, rowY(row), ARGB_WHITE);
		GuiCompat.draw(matrices, font, value, valueX, rowY(row), ARGB_LIGHT_GRAY);
	}

	private static int rowY(int row) {
		return SQUARE_SIZE * row + TEXT_FIELD_PADDING * 2 + TEXT_PADDING;
	}

	@Override
	public void onClose() {
		// Only its number and colour are set here
		savedRailBase.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(getPacketIdentifier(), packet));
		super.onClose();
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		availableTrainsList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		availableTrainsList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	protected boolean shouldRenderExtra() {
		return isSelectingTrain;
	}

	@Override
	protected void renderExtra(PoseStack matrices, int mouseX, int mouseY, float delta) {
		availableTrainsList.render(matrices, font);
	}

	@Override
	protected String getNumberStringKey() {
		return "gui.mtr.siding_number";
	}

	@Override
	protected ResourceLocation getPacketIdentifier() {
		return PACKET_UPDATE_SIDING;
	}

	private void onSelectingTrain() {
		final List<TrainForList> trainsForListTemp = new ArrayList<>();
		final List<TrainForList> trainsForListUnavailable = new ArrayList<>();

		TrainClientRegistry.forEach(transportMode, (id, trainProperties) -> {
			final TrainForList trainForList = new TrainForList(savedRailBase, id, trainProperties);
			(trainForList.isAvailable ? trainsForListTemp : trainsForListUnavailable).add(trainForList);
		});

		trainsForListTemp.addAll(trainsForListUnavailable);
		availableTrainsList.setData(trainsForListTemp, false, false, false, false, true, false);
		setIsSelectingTrain(true);
	}

	private void setIsSelectingTrain(boolean isSelectingTrain) {
		this.isSelectingTrain = isSelectingTrain;
		buttonSelectTrain.visible = !isSelectingTrain;
		// How long until driving by hand goes back to automatic is set in the web editor too
		sliderDwellTimeMin.visible = false;
		sliderDwellTimeSec.visible = false;
		buttonSelectTrain.setMessage(TrainClientRegistry.getTrainProperties(savedRailBase.getTrainId()).name);
		availableTrainsList.x = isSelectingTrain ? SQUARE_SIZE : width;
	}

	private void onAdd(NameColorDataBase data, int index) {
		if (data instanceof TrainForList) {
			final String baseTrainType = ((TrainForList) data).trainProperties.baseTrainType;
			if (savedRailBase.isValidVehicle(TrainType.getSpacing(baseTrainType))) {
				savedRailBase.setTrainIdAndBaseType(((TrainForList) data).trainId, baseTrainType, packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_SIDING, packet));
				setIsSelectingTrain(false);
			}
		}
	}

	private static String accelerationText(float valueMeterPerTickSquared) {
		return String.format("%s m/s² (%s km/h/s)", RailwayData.round(valueMeterPerTickSquared * ACCELERATION_UNIT_CONVERSION_1, 1), RailwayData.round(valueMeterPerTickSquared * ACCELERATION_UNIT_CONVERSION_2, 1));
	}

	private static String speedText(int value) {
		final RailType railType = Train.convertMaxManualSpeed(value);
		return railType == null ? Text.translatable("gui.mtr.unlimited").getString() : String.format("%s km/h", railType.speedLimit);
	}

	private int drawWrappedText(PoseStack matrices, Component component, int y, int color) {
		final List<FormattedCharSequence> splitText = font.split(component, DESCRIPTION_WIDTH);
		int newY = y;
		for (final FormattedCharSequence formattedCharSequence : splitText) {
			final int nextY = newY + TEXT_HEIGHT + 2;
			if (nextY > height - SQUARE_SIZE - TEXT_HEIGHT) {
				GuiCompat.draw(matrices, font, "...", width - DESCRIPTION_WIDTH - SQUARE_SIZE, newY, color);
				return height;
			} else {
				GuiCompat.draw(matrices, font, formattedCharSequence, width - DESCRIPTION_WIDTH - SQUARE_SIZE, newY, color);
			}
			newY = nextY;
		}
		return newY + TEXT_PADDING;
	}

	private static String fetchWikipediaArticle(String wikipediaArticle) {
		final String result = WIKIPEDIA_ARTICLES.get(wikipediaArticle);
		if (result == null) {
			CompletableFuture.runAsync(() -> Patreon.openConnectionSafeJson("https://en.wikipedia.org/w/api.php?format=json&action=query&prop=extracts&explaintext&exintro&titles=" + wikipediaArticle, jsonElement -> {
				final JsonObject pagesObject = jsonElement.getAsJsonObject().getAsJsonObject("query").getAsJsonObject("pages");
				pagesObject.entrySet().stream().findFirst().ifPresent(entry -> WIKIPEDIA_ARTICLES.put(wikipediaArticle, pagesObject.getAsJsonObject(entry.getKey()).get("extract").getAsString()));
			}));
			WIKIPEDIA_ARTICLES.put(wikipediaArticle, "");
			return "";
		} else {
			return result;
		}
	}

	private static class TrainForList extends NameColorDataBase {

		private final String trainId;
		private final TrainProperties trainProperties;
		private final boolean isAvailable;

		private TrainForList(Siding savedRailBase, String trainId, TrainProperties trainProperties) {
			this.trainId = trainId;
			this.trainProperties = trainProperties;
			isAvailable = savedRailBase.isValidVehicle(TrainType.getSpacing(trainProperties.baseTrainType));
			this.name = (isAvailable ? "" : WARNING + " ") + trainProperties.name.getString();
			this.color = isAvailable ? trainProperties.color : 0;
		}

		@Override
		protected boolean hasTransportMode() {
			return false;
		}
	}
}
