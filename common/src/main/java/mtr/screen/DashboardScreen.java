package mtr.screen;

import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.MapDataCache;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DashboardScreen extends ScreenBase implements IGui, IPacket {

	private SelectedTab selectedTab;
	private AreaBase editingArea;
	private Route editingRoute;
	private int editingRoutePlatformIndex;
	private List<Object> routeEntries = new ArrayList<>();
	private boolean isNew;

	private final TransportMode transportMode;
	private final WidgetMap widgetMap;

	private final Button buttonTabStations;
	private final Button buttonTabRoutes;
	private final Button buttonTabDepots;
	private final Button buttonAddStation;
	private final Button buttonAddRoute;
	private final Button buttonAddDepot;
	private final Button buttonDoneEditingStation;
	private final Button buttonDoneEditingRoute;
	private final Button buttonDoneEditingRouteDestination;
	private final Button buttonZoomIn;
	private final Button buttonZoomOut;
	private final Button buttonRailActions;
	private final Button buttonOptions;

	private final WidgetBetterTextField textFieldName;
	private final WidgetBetterTextField textFieldCustomDestination;
	private final Button buttonStationArea;
	private final Button buttonAddStationArea;
	private final Button buttonRemoveStationArea;
	private int editingAreaIndex;
	private final WidgetBetterTextField textFieldDwellOverride;
	private final WidgetBetterTextField textFieldTurnBackDwell;
	private final WidgetBetterCheckbox checkboxPassStation;
	private final WidgetColorSelector colorSelector;

	private final DashboardList dashboardList;

	public static final int MAX_COLOR_ZONE_LENGTH = 6;
	private static final int COLOR_WIDTH = 48;
	private static final int ARGB_KEY_NODE = 0xFFFFFF00;

	public DashboardScreen(TransportMode transportMode, boolean useTimeAndWindSync) {
		super(Text.literal(""));
		this.transportMode = transportMode;

		textFieldName = new WidgetBetterTextField(Text.translatable("gui.mtr.name").getString());
		textFieldCustomDestination = new WidgetBetterTextField(Text.translatable("gui.mtr.custom_destination_suggestion").getString());
		textFieldDwellOverride = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, Text.translatable("gui.mtr.dwell_override").getString(), 4);
		textFieldTurnBackDwell = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, Text.translatable("gui.mtr.turn_back_dwell").getString(), 4);
		checkboxPassStation = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.pass_station"), checked -> {
		});
		colorSelector = new WidgetColorSelector(this, true, this::toggleButtons);
		widgetMap = new WidgetMap(transportMode, this::onDrawCorners, this::onDrawCornersMouseRelease, this::onClickAddPlatformToRoute, this::onClickAddKeyNodeToRoute, this::onClickEditSavedRail, colorSelector::isMouseOver);
		widgetMap.setRoutesToDraw(this::getRoutesToDraw);
		MapDataCache.open();

		buttonTabStations = UtilitiesClient.newButton(Text.translatable("gui.mtr.stations"), button -> onSelectTab(SelectedTab.STATIONS));
		buttonTabRoutes = UtilitiesClient.newButton(Text.translatable("gui.mtr.routes"), button -> onSelectTab(SelectedTab.ROUTES));
		buttonTabDepots = UtilitiesClient.newButton(Text.translatable("gui.mtr.depots"), button -> onSelectTab(SelectedTab.DEPOTS));

		buttonAddStation = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_station"), button -> startEditingArea(new Station(), true));
		buttonAddRoute = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_route"), button -> startEditingRoute(new Route(transportMode), true));
		buttonAddDepot = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_depot"), button -> startEditingArea(new Depot(transportMode), true));
		buttonDoneEditingStation = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDoneEditingArea());
		buttonDoneEditingRoute = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDoneEditingRoute());
		buttonDoneEditingRouteDestination = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> onDoneEditingRouteDestination());
		buttonZoomIn = UtilitiesClient.newButton(Text.literal("+"), button -> widgetMap.scale(1));
		buttonZoomOut = UtilitiesClient.newButton(Text.literal("-"), button -> widgetMap.scale(-1));
		buttonStationArea = UtilitiesClient.newButton(Text.literal(""), button -> selectStationArea((editingAreaIndex + 1) % (((Station) editingArea).extraAreas.size() + 1)));
		buttonAddStationArea = UtilitiesClient.newButton(Text.translatable("gui.mtr.add_station_area"), button -> selectStationArea(((Station) editingArea).extraAreas.size() + 1));
		buttonRemoveStationArea = UtilitiesClient.newButton(Text.translatable("gui.mtr.remove_station_area"), button -> removeStationArea());
		buttonRailActions = UtilitiesClient.newButton(Text.translatable("gui.mtr.rail_actions_button"), button -> {
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new RailActionsScreen());
			}
		});
		buttonOptions = UtilitiesClient.newButton(Text.translatable("menu.options"), button -> {
			if (minecraft != null) {
				UtilitiesClient.setScreen(minecraft, new ConfigScreen(useTimeAndWindSync));
			}
		});

		dashboardList = new DashboardList(this::onFind, this::onDrawArea, this::onEdit, this::onSort, null, this::onDelete, this::getList, () -> ClientData.DASHBOARD_SEARCH, text -> ClientData.DASHBOARD_SEARCH = text);

		onSelectTab(SelectedTab.STATIONS);
	}

	@Override
	protected void init() {
		super.init();

		final int tabCount = 3;
		final int bottomRowY = height - SQUARE_SIZE;

		widgetMap.setPositionAndSize(PANEL_WIDTH, 0, width - PANEL_WIDTH, height);

		IDrawing.setPositionAndWidth(buttonTabStations, 0, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonTabRoutes, PANEL_WIDTH / tabCount, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonTabDepots, 2 * PANEL_WIDTH / tabCount, 0, PANEL_WIDTH / tabCount);
		IDrawing.setPositionAndWidth(buttonAddStation, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonAddRoute, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonAddDepot, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonDoneEditingStation, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonDoneEditingRoute, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonDoneEditingRouteDestination, 0, bottomRowY, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(buttonZoomIn, width - SQUARE_SIZE * 2, bottomRowY, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonZoomOut, width - SQUARE_SIZE, bottomRowY, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonRailActions, width - SQUARE_SIZE * 10, bottomRowY, SQUARE_SIZE * 5);
		IDrawing.setPositionAndWidth(buttonOptions, width - SQUARE_SIZE * 5, bottomRowY, SQUARE_SIZE * 3);

		IDrawing.setPositionAndWidth(buttonStationArea, 0, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonAddStationArea, PANEL_WIDTH / 3, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonRemoveStationArea, PANEL_WIDTH * 2 / 3, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(textFieldName, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH - COLOR_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldCustomDestination, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldDwellOverride, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING * 3 / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldTurnBackDwell, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE * 3 - TEXT_FIELD_PADDING * 5 / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(checkboxPassStation, 0, bottomRowY - SQUARE_SIZE * 4 - TEXT_FIELD_PADDING * 3, PANEL_WIDTH);
		IDrawing.setPositionAndWidth(colorSelector, PANEL_WIDTH - COLOR_WIDTH + TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, COLOR_WIDTH - TEXT_FIELD_PADDING);

		dashboardList.x = 0;
		dashboardList.y = SQUARE_SIZE;
		dashboardList.width = PANEL_WIDTH;

		toggleButtons();
		dashboardList.init(this::addDrawableChild);
		addWidget(widgetMap);

		addDrawableChild(buttonTabStations);
		addDrawableChild(buttonTabRoutes);
		addDrawableChild(buttonTabDepots);
		addDrawableChild(buttonAddStation);
		addDrawableChild(buttonAddRoute);
		addDrawableChild(buttonAddDepot);
		addDrawableChild(buttonDoneEditingStation);
		addDrawableChild(buttonDoneEditingRoute);
		addDrawableChild(buttonDoneEditingRouteDestination);
		addDrawableChild(buttonZoomIn);
		addDrawableChild(buttonZoomOut);
		addDrawableChild(buttonRailActions);
		addDrawableChild(buttonStationArea);
		addDrawableChild(buttonAddStationArea);
		addDrawableChild(buttonRemoveStationArea);
		addDrawableChild(buttonOptions);

		addDrawableChild(textFieldName);
		addDrawableChild(textFieldCustomDestination);
		addDrawableChild(textFieldDwellOverride);
		addDrawableChild(textFieldTurnBackDwell);
		addDrawableChild(checkboxPassStation);
		addDrawableChild(colorSelector);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			widgetMap.render(matrices, mouseX, mouseY, delta);
			matrices.pushPose();
			matrices.translate(0, 0, 500);
			GuiCompat.fill(matrices, 0, 0, PANEL_WIDTH, height, ARGB_BACKGROUND);
			dashboardList.render(matrices, font);
			super.render(matrices, mouseX, mouseY, delta);
			matrices.popPose();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		dashboardList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		dashboardList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void tick() {
		textFieldName.tick();
		textFieldCustomDestination.tick();
		textFieldDwellOverride.tick();
		textFieldTurnBackDwell.tick();
		dashboardList.tick();

		try {
			switch (selectedTab) {
				case STATIONS:
					if (editingArea == null) {
						dashboardList.setData(ClientData.STATIONS, true, true, true, false, false, true);
					} else {
						final Map<Long, Platform> platformData = editingArea instanceof Station ? ClientData.DATA_CACHE.getPlatformsInArea((Station) editingArea) : ClientData.DATA_CACHE.requestStationIdToPlatforms(editingArea.id);
						dashboardList.setData(platformData == null ? new ArrayList<>() : new ArrayList<>(platformData.values()), true, false, true, false, false, false);
						if (platformData != null) {
							final long unassigned = platformData.values().stream().filter(platform -> platform.stationId == 0 && ClientData.DATA_CACHE.getStationsAt(platform.getMidPos()).size() > 1).count();
							widgetMap.setAreaNotice(unassigned > 0 ? Text.translatable("gui.mtr.station_overlap_notice", unassigned).getString() : "");
						}
					}
					break;
				case ROUTES:
					if (editingRoute == null) {
						dashboardList.setData(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES), false, true, true, false, false, true);
					} else {
						routeEntries = editingRoute.getEntries();
						final List<DataConverter> routeData = new ArrayList<>();
						routeEntries.forEach(entry -> routeData.add(getRouteEntryRow(entry)));
						dashboardList.setData(routeData, false, false, true, true, false, true);
					}
					break;
				case DEPOTS:
					if (editingArea == null) {
						dashboardList.setData(ClientData.getFilteredDataSet(transportMode, ClientData.DEPOTS), true, true, true, false, false, true);
					} else {
						final Map<Long, Siding> sidingData = ClientData.DATA_CACHE.requestDepotIdToSidings(editingArea.id);
						dashboardList.setData(sidingData == null ? new ArrayList<>() : new ArrayList<>(sidingData.values()), true, false, true, false, false, false);
					}
					break;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void onSelectTab(SelectedTab tab) {
		selectedTab = tab;
		buttonTabStations.active = tab != SelectedTab.STATIONS;
		buttonTabRoutes.active = tab != SelectedTab.ROUTES;
		buttonTabDepots.active = tab != SelectedTab.DEPOTS;
		stopEditing();
		widgetMap.setShowStations(selectedTab != SelectedTab.DEPOTS);
	}

	private void onFind(NameColorDataBase data, int index) {
		if (selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) {
			if (editingArea == null && data instanceof AreaBase) {
				final AreaBase area = (AreaBase) data;
				if (AreaBase.nonNullCorners(area)) {
					widgetMap.find(area.corner1.getA(), area.corner1.getB(), area.corner2.getA(), area.corner2.getB());
				}
			} else if (selectedTab == SelectedTab.STATIONS) {
				final Platform platform = (Platform) data;
				widgetMap.find(platform.getMidPos());
			}
		}
	}

	private void onDrawArea(NameColorDataBase data, int index) {
		switch (selectedTab) {
			case STATIONS:
			case DEPOTS:
				if (editingArea == null && data instanceof AreaBase) {
					startEditingArea((AreaBase) data, false);
				}
				break;
			case ROUTES:
				if (data instanceof Route) {
					startEditingRoute((Route) data, false);
				}
				break;
		}
		dashboardList.clearSearch();
	}

	private void onEdit(NameColorDataBase data, int index) {
		if (minecraft != null) {
			switch (selectedTab) {
				case STATIONS:
					if (editingArea == null) {
						if (data instanceof Station) {
							UtilitiesClient.setScreen(minecraft, new EditStationScreen((Station) data, this));
						}
					} else {
						if (data instanceof Platform) {
							UtilitiesClient.setScreen(minecraft, new PlatformScreen((Platform) data, transportMode, this));
						}
					}
					break;
				case ROUTES:
					if (editingRoute == null && data instanceof Route) {
						UtilitiesClient.setScreen(minecraft, new EditRouteScreen((Route) data, this));
					} else {
						final Object entry = routeEntries.get(index);
						if (entry instanceof BlockPos) {
							widgetMap.find((BlockPos) entry);
						} else {
							startEditingRouteDestination(editingRoute.platformIds.indexOf(entry));
						}
					}
					break;
				case DEPOTS:
					if (editingArea == null) {
						if (data instanceof Depot) {
							UtilitiesClient.setScreen(minecraft, new EditDepotScreen((Depot) data, transportMode, this));
						}
					} else {
						if (data instanceof Siding) {
							UtilitiesClient.setScreen(minecraft, new SidingScreen((Siding) data, transportMode, this));
						}
					}
					break;
			}
		}
	}

	private void onSort() {
		if (selectedTab == SelectedTab.ROUTES && editingRoute != null) {
			saveRouteEntries();
		}
	}

	private void onDelete(NameColorDataBase data, int index) {
		try {
			switch (selectedTab) {
				case STATIONS:
					if (minecraft != null) {
						final Station station = (Station) data;
						UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
							PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_STATION, station.id);
							ClientData.STATIONS.remove(station);
						}, IGui.formatStationName(station.name), this));
					}
					break;
				case ROUTES:
					if (editingRoute == null) {
						if (minecraft != null && data instanceof Route) {
							final Route route = (Route) data;
							UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
								PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_ROUTE, route.id);
								ClientData.ROUTES.remove(route);
							}, IGui.formatStationName(route.name), this));
						}
					} else {
						routeEntries.remove(index);
						saveRouteEntries();
					}
					break;
				case DEPOTS:
					if (minecraft != null && data instanceof Depot) {
						final Depot depot = (Depot) data;
						UtilitiesClient.setScreen(minecraft, new DeleteConfirmationScreen(() -> {
							PacketTrainDataGuiClient.sendDeleteData(PACKET_DELETE_DEPOT, depot.id);
							ClientData.DEPOTS.remove(depot);
						}, IGui.formatStationName(depot.name), this));
					}
					break;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private List<Object> getList() {
		return editingRoute == null ? new ArrayList<>() : routeEntries;
	}

	private DataConverter getRouteEntryRow(Object entry) {
		if (entry instanceof BlockPos) {
			final BlockPos pos = (BlockPos) entry;
			return new DataConverter(Text.translatable("gui.mtr.key_node_entry", pos.getX(), pos.getY(), pos.getZ()).getString(), ARGB_KEY_NODE & 0xFFFFFF);
		}
		final Route.RoutePlatform routePlatform = (Route.RoutePlatform) entry;
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
		if (platform == null) {
			return new DataConverter("", 0);
		}
		final String customDestinationPrefix = routePlatform.customDestination.isEmpty() ? "" : Route.destinationIsReset(routePlatform.customDestination) ? "\"" : "*";
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(platform.id);
		if (station != null) {
			return new DataConverter(String.format("%s%s (%s)", customDestinationPrefix, station.name, platform.name), station.color);
		} else {
			return new DataConverter(String.format("%s(%s)", customDestinationPrefix, platform.name), 0);
		}
	}

	private void saveRouteEntries() {
		editingRoute.setEntries(routeEntries);
		editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		PacketTrainDataGuiClient.requestMapRouteC2S(editingRoute.id);
	}

	private void startEditingArea(AreaBase editingArea, boolean isNew) {
		this.editingArea = editingArea;
		editingAreaIndex = 0;
		editingRoute = null;
		this.isNew = isNew;

		textFieldName.setValue(editingArea.name);
		colorSelector.setColor(editingArea.color);

		widgetMap.startEditingArea(editingArea);
		toggleButtons();
	}

	private void startEditingRoute(Route editingRoute, boolean isNew) {
		editingArea = null;
		this.editingRoute = editingRoute;
		this.isNew = isNew;
		editingRoutePlatformIndex = -1;

		textFieldName.setValue(editingRoute.name);
		colorSelector.setColor(editingRoute.color);

		widgetMap.startEditingRoute();
		toggleButtons();
	}

	private void startEditingRouteDestination(int index) {
		editingRoutePlatformIndex = index;
		if (isValidRoutePlatformIndex()) {
			textFieldCustomDestination.setValue(editingRoute.platformIds.get(index).customDestination);
			final int dwellOverride = editingRoute.platformIds.get(index).dwellOverride;
			textFieldDwellOverride.setValue(dwellOverride > 0 ? String.valueOf(Route.getDoorOpenSeconds(dwellOverride)) : "");
			final int turnBackDwell = editingRoute.platformIds.get(index).turnBackDwell;
			textFieldTurnBackDwell.setValue(turnBackDwell > 0 ? String.valueOf(turnBackDwell / 2) : "");
			checkboxPassStation.setChecked(editingRoute.platformIds.get(index).pass);
		}
		toggleButtons();
	}

	private void onDrawCorners(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2) {
		if (editingAreaIndex > 0) {
			final List<int[]> extraAreas = ((Station) editingArea).extraAreas;
			final int[] extraArea = {corner1.getA(), corner1.getB(), corner2.getA(), corner2.getB()};
			if (editingAreaIndex > extraAreas.size()) {
				extraAreas.add(extraArea);
			} else {
				extraAreas.set(editingAreaIndex - 1, extraArea);
			}
		} else {
			editingArea.corner1 = corner1;
			editingArea.corner2 = corner2;
		}
		toggleButtons();
	}

	private void onDrawCornersMouseRelease() {
		if (editingAreaIndex > 0) {
			((Station) editingArea).setExtraAreas(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		} else {
			editingArea.setCorners(packet -> PacketTrainDataGuiClient.sendUpdate(editingArea instanceof Station ? PACKET_UPDATE_STATION : PACKET_UPDATE_DEPOT, packet));
		}
	}

	private void selectStationArea(int index) {
		final Station station = (Station) editingArea;
		editingAreaIndex = index;
		if (index == 0) {
			widgetMap.setDrawArea(station.corner1, station.corner2);
		} else if (index > station.extraAreas.size()) {
			widgetMap.setDrawArea(null, null);
		} else {
			final int[] extraArea = station.extraAreas.get(index - 1);
			widgetMap.setDrawArea(new Tuple<>(extraArea[0], extraArea[1]), new Tuple<>(extraArea[2], extraArea[3]));
		}
		toggleButtons();
	}

	private void removeStationArea() {
		final Station station = (Station) editingArea;
		if (editingAreaIndex > 0 && editingAreaIndex <= station.extraAreas.size()) {
			station.extraAreas.remove(editingAreaIndex - 1);
			station.setExtraAreas(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		}
		selectStationArea(editingAreaIndex - 1);
	}

	private void onClickAddPlatformToRoute(long platformId) {
		editingRoute.platformIds.add(new Route.RoutePlatform(platformId));
		editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		PacketTrainDataGuiClient.requestMapRouteC2S(editingRoute.id);
	}

	private void onClickEditSavedRail(SavedRailBase savedRail) {
		if (savedRail instanceof Platform) {
			UtilitiesClient.setScreen(Minecraft.getInstance(), new PlatformScreen((Platform) savedRail, transportMode, this));
		} else if (savedRail instanceof Siding) {
			UtilitiesClient.setScreen(Minecraft.getInstance(), new SidingScreen((Siding) savedRail, transportMode, this));
		}
	}

	private void onDoneEditingArea() {
		if (editingArea instanceof Station || editingArea instanceof Depot) {
			final boolean isStation = editingArea instanceof Station;
			if (isNew) {
				if (isStation) {
					ClientData.STATIONS.add((Station) editingArea);
				} else {
					ClientData.DEPOTS.add((Depot) editingArea);
				}
			}
			editingArea.name = IGui.textOrUntitled(textFieldName.getValue());
			editingArea.color = colorSelector.getColor();
			editingArea.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(isStation ? PACKET_UPDATE_STATION : PACKET_UPDATE_DEPOT, packet));
		}
		stopEditing();
	}

	private void onDoneEditingRoute() {
		if (isNew) {
			try {
				ClientData.ROUTES.add(editingRoute);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		editingRoute.name = IGui.textOrUntitled(textFieldName.getValue());
		editingRoute.color = colorSelector.getColor();
		editingRoute.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		stopEditing();
	}

	private Collection<Route> getRoutesToDraw() {
		if (selectedTab == SelectedTab.DEPOTS) {
			return Collections.emptyList();
		}
		return editingRoute == null ? ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES) : Collections.singletonList(editingRoute);
	}

	private void onClickAddKeyNodeToRoute(BlockPos pos) {
		if (!editingRoute.platformIds.isEmpty()) {
			routeEntries = editingRoute.getEntries();
			routeEntries.add(pos);
			saveRouteEntries();
		}
	}

	private void onDoneEditingRouteDestination() {
		if (isValidRoutePlatformIndex()) {
			final Route.RoutePlatform routePlatform = editingRoute.platformIds.get(editingRoutePlatformIndex);
			routePlatform.customDestination = textFieldCustomDestination.getValue();
			routePlatform.pass = checkboxPassStation.visible && checkboxPassStation.selected();
			try {
				final String dwellText = textFieldDwellOverride.getValue();
				routePlatform.dwellOverride = dwellText.isEmpty() ? 0 : Route.getDwellFromDoorOpenSeconds(Integer.parseInt(dwellText));
				final String turnBackText = textFieldTurnBackDwell.getValue();
				routePlatform.turnBackDwell = turnBackText.isEmpty() ? 0 : Integer.parseInt(turnBackText) * 2;
			} catch (NumberFormatException ignored) {
			}
			editingRoute.setPlatformIds(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		}
		startEditingRoute(editingRoute, isNew);
	}

	private void stopEditing() {
		widgetMap.setAreaNotice("");
		editingArea = null;
		editingRoute = null;
		widgetMap.stopEditing();
		toggleButtons();
	}

	private boolean isValidRoutePlatformIndex() {
		return editingRoute != null && editingRoutePlatformIndex >= 0 && editingRoutePlatformIndex < editingRoute.platformIds.size();
	}

	private void toggleButtons() {
		final boolean hasPermission = ClientData.hasPermission();
		final boolean showRouteDestinationFields = isValidRoutePlatformIndex();

		buttonAddStation.visible = selectedTab == SelectedTab.STATIONS && editingArea == null && hasPermission;
		buttonAddRoute.visible = selectedTab == SelectedTab.ROUTES && editingRoute == null && hasPermission;
		buttonAddDepot.visible = selectedTab == SelectedTab.DEPOTS && editingArea == null && hasPermission;
		buttonDoneEditingStation.visible = (selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) && editingArea != null;
		buttonDoneEditingStation.active = AreaBase.nonNullCorners(editingArea);
		buttonDoneEditingRoute.visible = selectedTab == SelectedTab.ROUTES && editingRoute != null && !showRouteDestinationFields;
		buttonDoneEditingRouteDestination.visible = selectedTab == SelectedTab.ROUTES && editingRoute != null && showRouteDestinationFields;

		final boolean showTextFields = ((selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) && editingArea != null) || (selectedTab == SelectedTab.ROUTES && editingRoute != null && !showRouteDestinationFields);
		textFieldName.visible = showTextFields;
		final boolean showStationAreas = selectedTab == SelectedTab.STATIONS && editingArea instanceof Station;
		buttonStationArea.visible = showStationAreas;
		buttonAddStationArea.visible = showStationAreas;
		buttonRemoveStationArea.visible = showStationAreas;
		if (showStationAreas) {
			final int extraAreaCount = ((Station) editingArea).extraAreas.size();
			buttonStationArea.setMessage(Text.translatable("gui.mtr.station_area", editingAreaIndex + 1, Math.max(extraAreaCount + 1, editingAreaIndex + 1)));
			buttonAddStationArea.active = AreaBase.nonNullCorners(editingArea) && extraAreaCount < Station.MAX_EXTRA_AREAS && editingAreaIndex <= extraAreaCount;
			buttonRemoveStationArea.active = editingAreaIndex > 0;
		}
		textFieldCustomDestination.visible = showRouteDestinationFields;
		textFieldDwellOverride.visible = showRouteDestinationFields;
		textFieldTurnBackDwell.visible = showRouteDestinationFields;
		checkboxPassStation.visible = showRouteDestinationFields && editingRoutePlatformIndex > 0 && editingRoutePlatformIndex < editingRoute.platformIds.size() - 1;
		colorSelector.visible = showTextFields;
		dashboardList.height = height - SQUARE_SIZE * 2 - (showTextFields || showRouteDestinationFields ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0) - (showRouteDestinationFields ? (SQUARE_SIZE + TEXT_FIELD_PADDING) * 3 : 0) - (showStationAreas ? SQUARE_SIZE + TEXT_FIELD_PADDING / 2 : 0);
	}

	private enum SelectedTab {STATIONS, ROUTES, DEPOTS}
}
