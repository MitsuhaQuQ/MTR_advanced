package mtr.screen;

import mtr.compat.GuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public class EditRouteScreen extends EditNameColorScreenBase<Route> implements IGui, IPacket {

	private RouteType routeType;

	private final Component lightRailRouteNumberText = Text.translatable("gui.mtr.light_rail_route_number");
	private final Component lineNameText = Text.translatable("gui.mtr.route_line_name");
	private final Component serviceTypeText = Text.translatable("gui.mtr.route_service_type");

	private final WidgetBetterTextField textFieldLightRailRouteNumber;
	private final Button buttonRouteType;
	private final WidgetBetterCheckbox buttonIsLightRailRoute;
	private final WidgetBetterCheckbox buttonIsRouteHidden;
	private final WidgetBetterCheckbox buttonDisableNextStationAnnouncements;
	private final WidgetBetterCheckbox buttonIsClockwiseRoute;
	private final WidgetBetterCheckbox buttonIsAntiClockwiseRoute;
	private final Button buttonTimetable;
	private final Button buttonDuplicate;
	private final WidgetBetterTextField textFieldLineName;
	private final WidgetBetterTextField textFieldServiceType;

	private final boolean isCircular;

	private static final int CHECKBOX_WIDTH = 160;
	private static final int RIGHT_COLUMN_X = SQUARE_SIZE * 2 + CHECKBOX_WIDTH;

	public EditRouteScreen(Route route, DashboardScreen dashboardScreen) {
		super(route, dashboardScreen, "gui.mtr.route_name", "gui.mtr.route_color");

		textFieldLightRailRouteNumber = new WidgetBetterTextField("");
		buttonRouteType = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_value"), button -> setRouteTypeText(data.transportMode, routeType.next()));
		buttonIsLightRailRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_light_rail_route"), this::setIsLightRailRoute);
		buttonIsRouteHidden = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_route_hidden"), this::setIsRouteHidden);
		buttonDisableNextStationAnnouncements = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.disable_next_station_announcements"), this::setDisableNextStationAnnouncements);
		buttonIsClockwiseRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_clockwise_route"), this::setIsClockwise);
		buttonIsAntiClockwiseRoute = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.is_anticlockwise_route"), this::setIsAntiClockwise);
		buttonTimetable = UtilitiesClient.newButton(Text.translatable("gui.mtr.timetable"), button -> {
			saveData();
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new TimetableScreen(data, this));
			}
		});

		buttonDuplicate = UtilitiesClient.newButton(Text.translatable("gui.mtr.duplicate_route"), button -> {
			saveData();
			final Route newRoute = data.duplicate();
			ClientData.ROUTES.add(newRoute);
			newRoute.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
			newRoute.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
			newRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
			newRoute.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
			if (!newRoute.getPathData().isEmpty()) {
				newRoute.setCustomPath(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
			}
			PacketTrainDataGuiClient.requestMapRouteC2S(newRoute.id);
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new EditRouteScreen(newRoute, dashboardScreen));
			}
		});
		textFieldLineName = new WidgetBetterTextField(Route.getAutoLineName(route.name));
		textFieldServiceType = new WidgetBetterTextField(LineWords.findServiceWord(route.name));

		if (route.platformIds.size() > 0) {
			final Station firstStation = ClientData.DATA_CACHE.platformIdToStation.get(route.getFirstPlatformId());
			final Station lastStation = ClientData.DATA_CACHE.platformIdToStation.get(route.getLastPlatformId());
			isCircular = firstStation != null && lastStation != null && firstStation.id == lastStation.id;
		} else {
			isCircular = false;
		}
	}

	@Override
	protected void init() {
		setPositionsAndInit(SQUARE_SIZE, width / 4 * 3 - SQUARE_SIZE, width - SQUARE_SIZE);

		IDrawing.setPositionAndWidth(buttonRouteType, SQUARE_SIZE, SQUARE_SIZE * 3, CHECKBOX_WIDTH);
		setRouteTypeText(data.transportMode, data.routeType);

		IDrawing.setPositionAndWidth(buttonIsRouteHidden, SQUARE_SIZE, SQUARE_SIZE * 4, CHECKBOX_WIDTH);
		IDrawing.setPositionAndWidth(buttonDisableNextStationAnnouncements, SQUARE_SIZE, SQUARE_SIZE * 5, CHECKBOX_WIDTH);
		IDrawing.setPositionAndWidth(buttonIsLightRailRoute, SQUARE_SIZE, SQUARE_SIZE * 6, CHECKBOX_WIDTH);
		IDrawing.setPositionAndWidth(textFieldLightRailRouteNumber, SQUARE_SIZE + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 8 + TEXT_FIELD_PADDING / 2, CHECKBOX_WIDTH - TEXT_FIELD_PADDING);
		textFieldLightRailRouteNumber.setValue(data.lightRailRouteNumber);

		IDrawing.setPositionAndWidth(buttonIsClockwiseRoute, SQUARE_SIZE, SQUARE_SIZE * 9 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
		IDrawing.setPositionAndWidth(buttonIsAntiClockwiseRoute, SQUARE_SIZE, SQUARE_SIZE * 10 + TEXT_FIELD_PADDING, CHECKBOX_WIDTH);
		// These sit beside the other options, as the left column already runs off the bottom of a small window
		final int rightColumnWidth = getRightColumnWidth();
		IDrawing.setPositionAndWidth(textFieldLineName, RIGHT_COLUMN_X + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 4 + TEXT_FIELD_PADDING / 2, rightColumnWidth - TEXT_FIELD_PADDING);
		textFieldLineName.setValue(data.lineName);
		IDrawing.setPositionAndWidth(textFieldServiceType, RIGHT_COLUMN_X + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 6 + TEXT_FIELD_PADDING / 2, rightColumnWidth - TEXT_FIELD_PADDING);
		textFieldServiceType.setValue(data.serviceType);
		IDrawing.setPositionAndWidth(buttonTimetable, RIGHT_COLUMN_X, SQUARE_SIZE * 7 + TEXT_FIELD_PADDING, rightColumnWidth);
		IDrawing.setPositionAndWidth(buttonDuplicate, RIGHT_COLUMN_X, SQUARE_SIZE * 8 + TEXT_FIELD_PADDING, rightColumnWidth);

		if (data.transportMode.hasRouteTypeVariation) {
			addDrawableChild(buttonRouteType);
		}
		addDrawableChild(textFieldLightRailRouteNumber);
		addDrawableChild(buttonIsLightRailRoute);
		addDrawableChild(buttonIsRouteHidden);
		addDrawableChild(buttonDisableNextStationAnnouncements);
		if (isCircular) {
			addDrawableChild(buttonIsClockwiseRoute);
			addDrawableChild(buttonIsAntiClockwiseRoute);
		}
		if (Depot.supportsTimetable(data.transportMode)) {
			addDrawableChild(buttonTimetable);
		}
		addDrawableChild(buttonDuplicate);
		addDrawableChild(textFieldLineName);
		addDrawableChild(textFieldServiceType);

		setIsLightRailRoute(data.isLightRailRoute);
		setIsRouteHidden(data.isHidden);
		setDisableNextStationAnnouncements(data.disableNextStationAnnouncements);
		setIsClockwise(data.circularState == Route.CircularState.CLOCKWISE);
		setIsAntiClockwise(data.circularState == Route.CircularState.ANTICLOCKWISE);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			renderTextFields(matrices);

			GuiCompat.drawString(matrices, font, font.plainSubstrByWidth(lineNameText.getString(), getRightColumnWidth()), RIGHT_COLUMN_X, SQUARE_SIZE * 3 + TEXT_PADDING, ARGB_WHITE);
			GuiCompat.drawString(matrices, font, font.plainSubstrByWidth(serviceTypeText.getString(), getRightColumnWidth()), RIGHT_COLUMN_X, SQUARE_SIZE * 5 + TEXT_PADDING, ARGB_WHITE);
			if (textFieldLightRailRouteNumber.visible) {
				GuiCompat.drawString(matrices, font, lightRailRouteNumberText, SQUARE_SIZE, SQUARE_SIZE * 7 + TEXT_PADDING, ARGB_WHITE);
			}

			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void saveData() {
		super.saveData();

		data.routeType = routeType;
		data.isLightRailRoute = buttonIsLightRailRoute.selected();
		data.lightRailRouteNumber = textFieldLightRailRouteNumber.getValue();
		data.isHidden = buttonIsRouteHidden.selected();
		data.disableNextStationAnnouncements = buttonDisableNextStationAnnouncements.selected();
		data.lineName = textFieldLineName.getValue().trim();
		data.serviceType = textFieldServiceType.getValue().trim();

		if (isCircular) {
			data.circularState = buttonIsClockwiseRoute.selected() ? Route.CircularState.CLOCKWISE : buttonIsAntiClockwiseRoute.selected() ? Route.CircularState.ANTICLOCKWISE : Route.CircularState.NONE;
		} else {
			data.circularState = Route.CircularState.NONE;
		}

		data.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		ClientData.DATA_CACHE.sync();
	}

	@Override
	protected int getEditedColor() {
		return data.getOwnColor();
	}

	@Override
	protected void setEditedColor(int color) {
		data.setOwnColor(color);
	}

	private int getRightColumnWidth() {
		return Math.max(SQUARE_SIZE * 2, Math.min(CHECKBOX_WIDTH, width - RIGHT_COLUMN_X - SQUARE_SIZE));
	}

	private void setRouteTypeText(TransportMode transportMode, RouteType newRouteType) {
		routeType = newRouteType;
		buttonRouteType.setMessage(Text.translatable(String.format("gui.mtr.route_type_%s_%s", transportMode, routeType).toLowerCase(Locale.ENGLISH)));
	}

	private void setIsLightRailRoute(boolean isLightRailRoute) {
		buttonIsLightRailRoute.setChecked(isLightRailRoute);
		textFieldLightRailRouteNumber.visible = isLightRailRoute;
	}

	private void setIsRouteHidden(boolean isRouteHidden) {
		buttonIsRouteHidden.setChecked(isRouteHidden);
	}

	private void setDisableNextStationAnnouncements(boolean hasNextStationAnnouncements) {
		buttonDisableNextStationAnnouncements.setChecked(hasNextStationAnnouncements);
	}

	private void setIsClockwise(boolean isClockwise) {
		buttonIsClockwiseRoute.setChecked(isClockwise);
		if (isClockwise) {
			buttonIsAntiClockwiseRoute.setChecked(false);
		}
	}

	private void setIsAntiClockwise(boolean isAntiClockwise) {
		buttonIsAntiClockwiseRoute.setChecked(isAntiClockwise);
		if (isAntiClockwise) {
			buttonIsClockwiseRoute.setChecked(false);
		}
	}
}
