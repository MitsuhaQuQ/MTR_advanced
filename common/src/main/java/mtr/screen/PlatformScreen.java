package mtr.screen;

import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.data.IGui;
import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PlatformScreen extends SavedRailScreenBase<Platform> {

	private final List<Station> stationsHere;
	private final Button buttonStation;
	private final Button buttonBoundary;
	private long selectedStationId;
	private boolean timetableBoundary;
	private int firstVisibleRow;
	private final List<Route> rowRoutes = new ArrayList<>();
	private final List<Integer> rowIndices = new ArrayList<>();

	private static final Component DWELL_TIME_TEXT = Text.translatable("gui.mtr.dwell_time");
	private static final int ROW_HEIGHT = TEXT_HEIGHT + TEXT_PADDING;

	public PlatformScreen(Platform savedRailBase, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(savedRailBase, transportMode, dashboardScreen, DWELL_TIME_TEXT);
		stationsHere = ClientData.DATA_CACHE.getStationsAt(savedRailBase.getMidPos());
		selectedStationId = savedRailBase.stationId;
		buttonStation = UtilitiesClient.newButton(Text.literal(""), button -> cycleStation());
		timetableBoundary = savedRailBase.timetableBoundary;
		buttonBoundary = UtilitiesClient.newButton(Text.literal(""), button -> {
			timetableBoundary = !timetableBoundary;
			updateBoundaryButton();
		});

		final List<Route> routes = new ArrayList<>(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES));
		routes.sort(Comparator.comparing(route -> route.name));
		routes.forEach(route -> {
			for (int i = 0; i < route.platformIds.size(); i++) {
				if (route.platformIds.get(i).platformId == savedRailBase.id) {
					rowRoutes.add(route);
					rowIndices.add(i);
				}
			}
		});
	}

	private void cycleStation() {
		int index = -1;
		for (int i = 0; i < stationsHere.size(); i++) {
			if (stationsHere.get(i).id == selectedStationId) {
				index = i;
			}
		}
		selectedStationId = index + 1 < stationsHere.size() ? stationsHere.get(index + 1).id : 0;
		updateStationButton();
	}

	private void updateBoundaryButton() {
		buttonBoundary.setMessage(Text.translatable(timetableBoundary ? "gui.mtr.platform_timetable_boundary_on" : "gui.mtr.platform_timetable_boundary_off"));
	}

	private void updateStationButton() {
		final Station station = ClientData.DATA_CACHE.stationIdMap.get(selectedStationId);
		buttonStation.setMessage(station == null ? Text.translatable("gui.mtr.platform_station_auto") : Text.translatable("gui.mtr.platform_station", station.name));
	}

	@Override
	protected void init() {
		super.init();
		UtilitiesClient.setWidgetY(sliderDwellTimeMin, SQUARE_SIZE * 2 + TEXT_FIELD_PADDING);
		UtilitiesClient.setWidgetY(sliderDwellTimeSec, SQUARE_SIZE * 5 / 2 + TEXT_FIELD_PADDING);
		if (stationsHere.size() > 1) {
			IDrawing.setPositionAndWidth(buttonStation, SQUARE_SIZE, SQUARE_SIZE * 7 / 2 + TEXT_FIELD_PADDING, width - SQUARE_SIZE * 2);
			updateStationButton();
			addDrawableChild(buttonStation);
		}
		if (showScheduleControls) {
			// Where services can leave or join a route part of the way along, see TimetableBoundaries
			IDrawing.setPositionAndWidth(buttonBoundary, SQUARE_SIZE, (stationsHere.size() > 1 ? SQUARE_SIZE * 9 / 2 : SQUARE_SIZE * 7 / 2) + TEXT_FIELD_PADDING, width - SQUARE_SIZE * 2);
			updateBoundaryButton();
			addDrawableChild(buttonBoundary);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!savedRailBase.transportMode.continuousMovement && sliderDwellTimeMin.getIntValue() == 0 && sliderDwellTimeSec.getIntValue() < Route.MIN_STOP_DWELL) {
			sliderDwellTimeSec.setValue(Route.MIN_STOP_DWELL);
		}
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		super.render(matrices, mouseX, mouseY, delta);
		if (showScheduleControls) {
			GuiCompat.draw(matrices, font, DWELL_TIME_TEXT, SQUARE_SIZE, SQUARE_SIZE * 2 + TEXT_FIELD_PADDING + TEXT_PADDING, ARGB_WHITE);
			renderRows(matrices);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		firstVisibleRow = Math.max(0, Math.min(firstVisibleRow - (int) Math.signum(amount), rowRoutes.size() - getVisibleRows()));
		return true;
	}

	private int getRowsTop() {
		return SQUARE_SIZE * (stationsHere.size() > 1 ? 5 : 7 / 2) + (showScheduleControls ? SQUARE_SIZE : 0) + TEXT_FIELD_PADDING + SQUARE_SIZE / 2;
	}

	private int getVisibleRows() {
		return Math.max(1, (height - getRowsTop() - ROW_HEIGHT - SQUARE_SIZE) / ROW_HEIGHT);
	}

	private void renderRows(PoseStack matrices) {
		final int top = getRowsTop();
		final int visibleRows = getVisibleRows();
		final String title = Text.translatable("gui.mtr.platform_schedule_title").getString();
		GuiCompat.draw(matrices, font, rowRoutes.size() > visibleRows ? String.format("%s (%s/%s)", title, firstVisibleRow + 1, rowRoutes.size()) : title, SQUARE_SIZE, top, ARGB_WHITE);
		if (rowRoutes.isEmpty()) {
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.platform_schedule_empty").getString(), SQUARE_SIZE, top + ROW_HEIGHT, ARGB_LIGHT_GRAY);
		}

		final int platformDwell = sliderDwellTimeMin.getIntValue() * SECONDS_PER_MINUTE * 2 + sliderDwellTimeSec.getIntValue();
		for (int i = 0; i < visibleRows && i + firstVisibleRow < rowRoutes.size(); i++) {
			final String row = getRowText(rowRoutes.get(i + firstVisibleRow), rowIndices.get(i + firstVisibleRow), platformDwell);
			final int rowWidth = font.width(row);
			final int availableWidth = width - SQUARE_SIZE * 2;
			matrices.pushPose();
			matrices.translate(SQUARE_SIZE, top + ROW_HEIGHT * (i + 1), 0);
			if (rowWidth > availableWidth) {
				matrices.scale((float) availableWidth / rowWidth, 1, 1);
			}
			GuiCompat.draw(matrices, font, row, 0, 0, ARGB_LIGHT_GRAY);
			matrices.popPose();
		}
	}

	private String getRowText(Route route, int index, int platformDwell) {
		final Route.RoutePlatform routePlatform = route.platformIds.get(index);
		final int stopCount = route.platformIds.size();
		final String routeName = IGui.formatStationName(route.name);
		final int dwell = routePlatform.getStopDwell(index > 0 && index < stopCount - 1, platformDwell, false);
		if (dwell == 0) {
			return Text.translatable("gui.mtr.platform_schedule_pass", routeName, index + 1, stopCount).getString();
		}
		final String source = Text.translatable(routePlatform.dwellOverride > 0 ? "gui.mtr.dwell_source_route" : "gui.mtr.dwell_source_platform").getString();
		return Text.translatable("gui.mtr.platform_schedule_stop", routeName, index + 1, stopCount, Route.getDoorOpenSeconds(dwell), dwell / 2F, source).getString();
	}

	@Override
	public void onClose() {
		final int minutes = sliderDwellTimeMin.getIntValue();
		final float second = sliderDwellTimeSec.getIntValue() / 2F;
		savedRailBase.setDwellTime((int) ((second + minutes * SECONDS_PER_MINUTE) * 2), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
		if (selectedStationId != savedRailBase.stationId) {
			savedRailBase.setStationId(selectedStationId, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
		}
		if (timetableBoundary != savedRailBase.timetableBoundary) {
			savedRailBase.setTimetableBoundary(timetableBoundary, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
		}
		super.onClose();
	}

	@Override
	protected String getNumberStringKey() {
		return "gui.mtr.platform_number";
	}

	@Override
	protected ResourceLocation getPacketIdentifier() {
		return PACKET_UPDATE_PLATFORM;
	}
}
