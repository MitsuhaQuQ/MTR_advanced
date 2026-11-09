package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.AreaBase;
import mtr.data.Depot;
import mtr.data.IGui;
import mtr.data.Station;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gives the box made with the station area tool to a station or a depot: first the station or depot, those whose areas
 * take its middle in first, then the nearest others; for a station then which of its rectangles it becomes, its main area,
 * one of its others or a new one, with the heights of the box. A depot's area becomes the box with its heights, so depots
 * one above another each take in their own sidings (2026-11-09).
 */
public class StationAreaToolScreen extends ScreenBase implements IGui, IPacket {

	private final int[] rectangle;
	private final int[] height;
	private final List<Station> stations = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	private Station chosen;

	private final List<Depot> depots = new ArrayList<>();
	private static final int MAX_STATIONS = 10;
	private static final int MAX_DEPOTS = 5;

	public StationAreaToolScreen(BlockPos pos1, BlockPos pos2) {
		super(Text.literal(""));
		rectangle = new int[]{Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getZ(), pos2.getZ()), Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getZ(), pos2.getZ())};
		height = new int[]{Math.min(pos1.getY(), pos2.getY()), Math.max(pos1.getY(), pos2.getY())};
		final int middleX = (rectangle[0] + rectangle[2]) / 2, middleZ = (rectangle[1] + rectangle[3]) / 2;
		final List<Station> all = new ArrayList<>(ClientData.STATIONS);
		all.removeIf(station -> !AreaBase.nonNullCorners(station));
		all.sort(Comparator.comparing((Station station) -> !station.inArea(middleX, middleZ)).thenComparingDouble(station -> {
			final double dx = (station.corner1.getA() + station.corner2.getA()) / 2D - middleX, dz = (station.corner1.getB() + station.corner2.getB()) / 2D - middleZ;
			return dx * dx + dz * dz;
		}).thenComparingLong(station -> station.id));
		stations.addAll(all.subList(0, Math.min(MAX_STATIONS, all.size())));
		final List<Depot> allDepots = new ArrayList<>(ClientData.DEPOTS);
		allDepots.removeIf(depot -> !AreaBase.nonNullCorners(depot));
		allDepots.sort(Comparator.comparing((Depot depot) -> !depot.inArea(middleX, middleZ)).thenComparingDouble(depot -> {
			final double dx = (depot.corner1.getA() + depot.corner2.getA()) / 2D - middleX, dz = (depot.corner1.getB() + depot.corner2.getB()) / 2D - middleZ;
			return dx * dx + dz * dz;
		}).thenComparingLong(depot -> depot.id));
		depots.addAll(allDepots.subList(0, Math.min(MAX_DEPOTS, allDepots.size())));
	}

	@Override
	protected void init() {
		super.init();
		buttons.clear();
		if (chosen == null) {
			stations.forEach(station -> buttons.add(UtilitiesClient.newButton(Text.literal(IGui.formatStationName(station.name)), button -> {
				chosen = station;
				rebuildWidgets();
			})));
			depots.forEach(depot -> buttons.add(UtilitiesClient.newButton(Text.translatable("gui.mtr.station_area_tool_depot", IGui.formatStationName(depot.name)), button -> applyDepot(depot))));
		} else {
			buttons.add(UtilitiesClient.newButton(Text.translatable("gui.mtr.station_area_tool_main"), button -> apply(0)));
			for (int i = 0; i < chosen.extraAreas.size(); i++) {
				final int index = i + 1;
				buttons.add(UtilitiesClient.newButton(Text.translatable("gui.mtr.station_area_tool_extra", index + 1), button -> apply(index)));
			}
			if (chosen.extraAreas.size() < Station.MAX_EXTRA_AREAS) {
				buttons.add(UtilitiesClient.newButton(Text.translatable("gui.mtr.station_area_tool_new"), button -> apply(chosen.extraAreas.size() + 1)));
			}
		}
		buttons.add(UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose()));
		for (int i = 0; i < buttons.size(); i++) {
			IDrawing.setPositionAndWidth(buttons.get(i), width / 4, SQUARE_SIZE * (3 + i), width / 2);
			addDrawableChild(buttons.get(i));
		}
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			final String title = chosen == null ? Text.translatable("gui.mtr.station_area_tool_choose_station").getString() : Text.translatable("gui.mtr.station_area_tool_choose_area", IGui.formatStationName(chosen.name)).getString();
			GuiCompat.drawCenteredString(matrices, font, Text.literal(title), width / 2, SQUARE_SIZE, ARGB_WHITE);
			GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.station_area_tool_box", rectangle[0], rectangle[1], rectangle[2], rectangle[3], height[0], height[1]), width / 2, SQUARE_SIZE * 2, ARGB_LIGHT_GRAY);
			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param index 0 for the main area, then each other rectangle from 1, one past them for a new one
	 */
	private void apply(int index) {
		final Station station = chosen;
		if (index == 0) {
			if (AreaBase.nonNullCorners(station)) {
				station.setHeight(new int[]{station.corner1.getA(), station.corner1.getB(), station.corner2.getA(), station.corner2.getB()}, null);
			}
			station.corner1 = new Tuple<>(rectangle[0], rectangle[1]);
			station.corner2 = new Tuple<>(rectangle[2], rectangle[3]);
			station.setCorners(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		} else {
			if (index <= station.extraAreas.size()) {
				station.setHeight(station.extraAreas.get(index - 1), null);
				station.extraAreas.set(index - 1, rectangle.clone());
			} else {
				station.extraAreas.add(rectangle.clone());
			}
			station.setExtraAreas(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		}
		station.setHeight(rectangle, height);
		station.setAreaHeights(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		ClientData.DATA_CACHE.sync();
		onClose();
	}

	/**
	 * The depot's area becomes the box, taking in its heights only.
	 */
	private void applyDepot(Depot depot) {
		depot.corner1 = new Tuple<>(rectangle[0], rectangle[1]);
		depot.corner2 = new Tuple<>(rectangle[2], rectangle[3]);
		depot.setCorners(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
		depot.setAreaHeights(height[0], height[1]);
		depot.sendAreaHeights(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
		ClientData.DATA_CACHE.sync();
		onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
