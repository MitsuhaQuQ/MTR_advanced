package mtr.screen;

import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
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

import java.util.List;

public class PlatformScreen extends SavedRailScreenBase<Platform> {

	private final List<Station> stationsHere;
	private final Button buttonStation;
	private long selectedStationId;

	private static final Component DWELL_TIME_TEXT = Text.translatable("gui.mtr.dwell_time");

	public PlatformScreen(Platform savedRailBase, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(savedRailBase, transportMode, dashboardScreen, DWELL_TIME_TEXT);
		stationsHere = ClientData.DATA_CACHE.getStationsAt(savedRailBase.getMidPos());
		selectedStationId = savedRailBase.stationId;
		buttonStation = UtilitiesClient.newButton(Text.literal(""), button -> cycleStation());
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
		}
	}

	@Override
	public void onClose() {
		final int minutes = sliderDwellTimeMin.getIntValue();
		final float second = sliderDwellTimeSec.getIntValue() / 2F;
		savedRailBase.setDwellTime((int) ((second + minutes * SECONDS_PER_MINUTE) * 2), packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
		if (selectedStationId != savedRailBase.stationId) {
			savedRailBase.setStationId(selectedStationId, packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_PLATFORM, packet));
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
