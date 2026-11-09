package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.advanced.AdvancedEditing;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.MapDataCache;
import mtr.client.editor.EditorServer;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import transitsim.network.PathRegions;
import transitsim.network.RegionData;

import java.util.*;
import java.util.function.Function;

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
	private final Button buttonAdvancedEditor;
	private final Button buttonRegionFilter;
	// The regions of the pathfinding boundaries the lists are cut down to, or null for everything
	private int[] regionFilter;
	/**
	 * Whether the region filter button is too narrow for its full text.
	 */
	private boolean compactRegionFilter;
	private long pickedStationId;

	private final WidgetBetterTextField textFieldName;
	private final WidgetBetterTextField textFieldCustomDestination;
	private final Button buttonStationArea;
	private final Button buttonAddStationArea;
	private final Button buttonRemoveStationArea;
	private int editingAreaIndex;
	private final WidgetBetterTextField textFieldDwellOverride;
	private final WidgetBetterCheckbox checkboxPassStation;
	private final WidgetColorSelector colorSelector;

	private final DashboardList dashboardList;
	private final LineRows lineRows = new LineRows();

	public static final int MAX_COLOR_ZONE_LENGTH = 6;
	private static final int COLOR_WIDTH = 48;
	private static final int ARGB_KEY_NODE = 0xFFFFFF00;

	public DashboardScreen(TransportMode transportMode, boolean useTimeAndWindSync) {
		super(Text.literal(""));
		this.transportMode = transportMode;
		// On a server these come from the server, so the local files are left out
		if (Minecraft.getInstance().hasSingleplayerServer()) {
			LineWords.reload();
			TimetableConfig.reload();
		}

		textFieldName = new WidgetBetterTextField(Text.translatable("gui.mtr.name").getString());
		textFieldCustomDestination = new WidgetBetterTextField(Text.translatable("gui.mtr.custom_destination_suggestion").getString());
		textFieldDwellOverride = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, Text.translatable("gui.mtr.dwell_override").getString(), 4);
		checkboxPassStation = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.pass_station"), checked -> {
		});
		colorSelector = new WidgetColorSelector(this, true, this::toggleButtons);
		widgetMap = new WidgetMap(transportMode, this::onDrawCorners, this::onDrawCornersMouseRelease, this::onClickAddPlatformToRoute, this::onClickAddKeyNodeToRoute, this::onClickEditSavedRail, colorSelector::isMouseOver);
		widgetMap.setRoutesToDraw(this::getRoutesToDraw);
		// A station rectangle with heights (3D) is only taken away here, and the right button takes any away (2026-11-09)
		widgetMap.setAreaEditing(() -> getEditingHeights() != null, this::removeStationAreaAt);
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

		buttonAdvancedEditor = UtilitiesClient.newButton(Text.translatable("gui.mtr.advanced_editor"), button -> {
			final String url = EditorServer.open();
			if (url != null) {
				Util.getPlatform().openUri(url);
			}
		});

		buttonRegionFilter = UtilitiesClient.newButton(Text.literal(""), button -> toggleRegionFilter());

		dashboardList = new DashboardList(this::onFind, this::onDrawArea, this::onEdit, this::onSort, null, this::onDelete, this::getList, () -> ClientData.DASHBOARD_SEARCH, text -> ClientData.DASHBOARD_SEARCH = text);

		widgetMap.setRoutePicking(() -> selectedTab == SelectedTab.ROUTES && editingRoute == null, route -> {
			final String lineKey = route.getLineKey();
			widgetMap.setSelectedLine(lineKey);
			dashboardList.clearSearch();
			ClientData.DASHBOARD_SEARCH = "";
			updateRouteRows();
			final long headerId = LineRows.getHeaderId(lineKey);
			dashboardList.selectItem(lineRows.hasHeader(headerId) ? headerId : route.id);
		});
		dashboardList.setRowStyle(this::getRowLabel, lineRows::isHeader, lineRows::getColor, this::onClickLineHeader);
		// The stops of a route the advanced editor manages are changed there
		dashboardList.setLockedRows(data -> data instanceof Route && AdvancedEditing.isLockedInGame((Route) data));
		widgetMap.setStationPicking(() -> selectedTab == SelectedTab.STATIONS && editingArea == null, station -> {
			widgetMap.setSelectedStation(station.id);
			dashboardList.selectItem(station.id);
			pickedStationId = station.id;
		});
		widgetMap.setDepotPicking(() -> selectedTab == SelectedTab.DEPOTS && editingArea == null, depot -> {
			widgetMap.setSelectedDepot(depot.id);
			dashboardList.selectItem(depot.id);
		});

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
		layoutMapButtons(bottomRowY);

		IDrawing.setPositionAndWidth(buttonStationArea, 0, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonAddStationArea, PANEL_WIDTH / 3, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonRemoveStationArea, PANEL_WIDTH * 2 / 3, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING, PANEL_WIDTH / 3);
		IDrawing.setPositionAndWidth(textFieldName, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH - COLOR_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldCustomDestination, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(textFieldDwellOverride, TEXT_FIELD_PADDING / 2, bottomRowY - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING * 3 / 2, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(checkboxPassStation, 0, bottomRowY - SQUARE_SIZE * 3 - TEXT_FIELD_PADDING * 2, PANEL_WIDTH);
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
		addDrawableChild(buttonAdvancedEditor);
		addDrawableChild(buttonRegionFilter);

		addDrawableChild(textFieldName);
		addDrawableChild(textFieldCustomDestination);
		addDrawableChild(textFieldDwellOverride);
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
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return button == 0 && dashboardList.mouseClicked(mouseX, mouseY) || super.mouseClicked(mouseX, mouseY, button);
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
		dashboardList.tick();
		updateRegionFilterButton();

		try {
			switch (selectedTab) {
				case STATIONS:
					if (editingArea == null) {
						dashboardList.setData(filterByRegion(ClientData.STATIONS, station -> ClientData.DATA_CACHE.requestStationIdToPlatforms(station.id), true), true, true, true, false, false, true);
					} else {
						final Map<Long, Platform> platformData = editingArea instanceof Station ? ClientData.DATA_CACHE.getPlatformsInArea((Station) editingArea) : ClientData.DATA_CACHE.requestStationIdToPlatforms(editingArea.id);
						dashboardList.setData(platformData == null ? new ArrayList<>() : new ArrayList<>(platformData.values()), true, false, true, false, false, false);
						if (platformData != null) {
							final long unassigned = platformData.values().stream().filter(platform -> ClientData.DATA_CACHE.overlappingPlatforms.containsKey(platform.id)).count();
							widgetMap.setAreaNotice(unassigned > 0 ? Text.translatable("gui.mtr.station_overlap_notice", unassigned).getString() : "");
						}
					}
					break;
				case ROUTES:
					if (editingRoute == null) {
						updateRouteRows();
					} else {
						routeEntries = editingRoute.getEntries();
						final List<DataConverter> routeData = new ArrayList<>();
						routeEntries.forEach(entry -> routeData.add(getRouteEntryRow(entry)));
						dashboardList.setData(routeData, false, false, true, true, false, true);
					}
					break;
				case DEPOTS:
					if (editingArea == null) {
						dashboardList.setData(filterByRegion(ClientData.getFilteredDataSet(transportMode, ClientData.DEPOTS), depot -> ClientData.DATA_CACHE.requestDepotIdToSidings(depot.id), false), true, true, true, false, false, true);
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

	/**
	 * Routes of one line are folded into a row of their own, unless searching, when every route has to be findable.
	 */
	private void updateRouteRows() {
		final List<Route> routes = new ArrayList<>(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES));
		if (regionFilter != null) {
			routes.removeIf(route -> route.platformIds.stream().noneMatch(routePlatform -> PathRegions.shares(regionFilter, ClientData.PATH_REGIONS.platformRegions.getOrDefault(routePlatform.platformId, new int[0]))));
		}
		Collections.sort(routes);

		if (ClientData.DASHBOARD_SEARCH.isEmpty()) {
			dashboardList.setData(lineRows.getRows(routes), true, true, true, false, false, true);
		} else {
			lineRows.clear();
			dashboardList.setData(routes, true, true, true, false, false, true);
		}
	}

	/**
	 * Picks the regions of the station picked last on the map or in the list, or goes back to showing everything (design
	 * document 5.13). Regions aren't named or saved, so changing the network only changes what a new pick gives.
	 */
	private void toggleRegionFilter() {
		if (regionFilter != null) {
			regionFilter = null;
		} else {
			final Map<Long, Platform> platforms = ClientData.DATA_CACHE.requestStationIdToPlatforms(pickedStationId);
			final Set<Integer> regions = new TreeSet<>();
			if (platforms != null) {
				platforms.keySet().forEach(platformId -> {
					for (final int region : ClientData.PATH_REGIONS.platformRegions.getOrDefault(platformId, new int[0])) {
						regions.add(region);
					}
				});
			}
			regionFilter = regions.isEmpty() ? null : regions.stream().mapToInt(Integer::intValue).toArray();
		}
		dashboardList.clearSelectedItem();
	}

	/**
	 * The map's buttons stay over the map, never over the panel on the left: from the right, zoom, options, rail actions, the
	 * advanced editor and the region filter. Without room for all of them at full width they narrow, the region filter
	 * first, and a button with no room at all is hidden.
	 */
	private void layoutMapButtons(int bottomRowY) {
		final Button[] buttons = {buttonOptions, buttonRailActions, buttonAdvancedEditor, buttonRegionFilter};
		final int[] widths = {SQUARE_SIZE * 3, SQUARE_SIZE * 5, SQUARE_SIZE * 5, SQUARE_SIZE * 7};
		final int minWidth = SQUARE_SIZE * 2;
		final int available = Math.max(width - PANEL_WIDTH - SQUARE_SIZE * 2, 0);
		int excess = Arrays.stream(widths).sum() - available;
		final int filterCut = Math.min(Math.max(excess, 0), widths[3] - minWidth);
		widths[3] -= filterCut;
		excess -= filterCut;
		for (int i = 0; i < 3 && excess > 0; i++) {
			final int cut = Math.min(excess, widths[i] - minWidth);
			widths[i] -= cut;
			excess -= cut;
		}
		// Still too narrow: the region filter goes first, then the others from the left
		for (int i = 3; i >= 0 && excess > 0; i--) {
			excess -= widths[i];
			widths[i] = 0;
		}
		int x = width - SQUARE_SIZE * 2;
		for (int i = 0; i < buttons.length; i++) {
			x -= widths[i];
			IDrawing.setPositionAndWidth(buttons[i], x, bottomRowY, Math.max(widths[i], 1));
			buttons[i].visible = widths[i] > 0;
		}
		compactRegionFilter = widths[3] < SQUARE_SIZE * 7;
		widgetMap.setBottomButtonsWidth(width - x);
		updateRegionFilterButton();
	}

	private void updateRegionFilterButton() {
		final RegionData regionData = ClientData.PATH_REGIONS;
		final int uncut = regionData.uncutNodes.size() + regionData.uncutSegments.size();
		final int missing = regionData.missingNodes.size() + regionData.missingSegments.size();
		final String problems = (uncut > 0 ? Text.translatable("gui.mtr.region_uncut", uncut).getString() : "") + (missing > 0 ? Text.translatable("gui.mtr.region_missing", missing).getString() : "");
		// Networks are rails joined together; the regions counted are those with a platform or siding, cut by boundaries or not
		final Set<Integer> regions = new HashSet<>();
		regionData.platformRegions.values().forEach(platformRegions -> Arrays.stream(platformRegions).forEach(regions::add));
		regionData.sidingRegions.values().forEach(sidingRegions -> Arrays.stream(sidingRegions).forEach(regions::add));
		final int networks = regionData.platformGraph.getNetworkCount();
		if (regionFilter != null) {
			buttonRegionFilter.setMessage(Text.translatable(compactRegionFilter ? "gui.mtr.region_filter_on_short" : "gui.mtr.region_filter_on", regionFilter.length));
		} else if (compactRegionFilter) {
			buttonRegionFilter.setMessage(Text.translatable("gui.mtr.region_filter_short", networks, regions.size()));
		} else {
			buttonRegionFilter.setMessage(Text.literal(Text.translatable("gui.mtr.region_filter_off", networks, regions.size()).getString() + problems));
		}
		buttonRegionFilter.active = regionFilter != null || pickedStationId != 0;
	}

	/**
	 * Stations and depots without any platform or siding yet aren't part of any region, so they are always shown.
	 */
	private <T extends NameColorDataBase, U extends SavedRailBase> Set<T> filterByRegion(Set<T> data, Function<T, Map<Long, U>> getSavedRails, boolean isPlatform) {
		if (regionFilter == null) {
			return data;
		}
		final Map<Long, int[]> regions = isPlatform ? ClientData.PATH_REGIONS.platformRegions : ClientData.PATH_REGIONS.sidingRegions;
		final Set<T> filtered = new HashSet<>();
		data.forEach(item -> {
			final Map<Long, U> savedRails = getSavedRails.apply(item);
			if (savedRails == null || savedRails.isEmpty() || savedRails.keySet().stream().anyMatch(id -> PathRegions.shares(regionFilter, regions.getOrDefault(id, new int[0])))) {
				filtered.add(item);
			}
		});
		return filtered;
	}

	private void onClickLineHeader(NameColorDataBase data) {
		if (lineRows.toggle(data)) {
			updateRouteRows();
		}
	}

	private String getRowLabel(NameColorDataBase data) {
		final boolean isChild = selectedTab == SelectedTab.ROUTES && editingRoute == null && lineRows.isChild(data);
		return isChild ? "    " + TimetableScreen.getDirectionText((Route) data) : IGui.formatStationName(data.name);
	}

	private void onSelectTab(SelectedTab tab) {
		selectedTab = tab;
		widgetMap.setSelectedLine("");
		widgetMap.setSelectedStation(0);
		widgetMap.setSelectedDepot(0);
		dashboardList.clearSelectedItem();
		buttonTabStations.active = tab != SelectedTab.STATIONS;
		buttonTabRoutes.active = tab != SelectedTab.ROUTES;
		buttonTabDepots.active = tab != SelectedTab.DEPOTS;
		stopEditing();
		widgetMap.setShowStations(selectedTab != SelectedTab.DEPOTS);
	}

	private void onFind(NameColorDataBase data, int index) {
		if (selectedTab == SelectedTab.ROUTES) {
			if (data instanceof Route) {
				final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(((Route) data).getFirstPlatformId());
				if (platform != null) {
					widgetMap.find(platform.getMidPos());
				}
			}
		} else if (selectedTab == SelectedTab.STATIONS || selectedTab == SelectedTab.DEPOTS) {
			if (editingArea == null && data instanceof AreaBase) {
				final AreaBase area = (AreaBase) data;
				if (data instanceof Station) {
					pickedStationId = data.id;
				}
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
				if (data instanceof Route && !AdvancedEditing.isLockedInGame((Route) data)) {
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
		colorSelector.setColor(editingRoute.getOwnColor());

		widgetMap.startEditingRoute();
		toggleButtons();
	}

	private void startEditingRouteDestination(int index) {
		editingRoutePlatformIndex = index;
		if (isValidRoutePlatformIndex()) {
			textFieldCustomDestination.setValue(editingRoute.platformIds.get(index).customDestination);
			final int dwellOverride = editingRoute.platformIds.get(index).dwellOverride;
			textFieldDwellOverride.setValue(dwellOverride > 0 ? String.valueOf(Route.getDoorOpenSeconds(dwellOverride)) : "");
			checkboxPassStation.setChecked(editingRoute.platformIds.get(index).pass);
		}
		toggleButtons();
	}

	private void onDrawCorners(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2) {
		// Only rectangles taking in every height (2D) are drawn here; those with heights are locked (2026-11-09)
		if (getEditingHeights() != null) {
			return;
		}
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
		if (editingArea instanceof Station) {
			// The rectangle drawn takes in every height, whatever one with the same corners had
			final Station station = (Station) editingArea;
			final int[] drawn = station.getRectangle(editingAreaIndex);
			if (drawn != null && station.getHeight(drawn) != null) {
				station.clearHeight(drawn);
				station.setAreaHeights(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
			}
		}
	}

	/**
	 * @return the heights of the station rectangle being edited, or null if it takes in every height or isn't a station's
	 */
	private int[] getEditingHeights() {
		if (editingArea instanceof Depot) {
			return AreaBase.nonNullCorners(editingArea) ? editingArea.getHeight(null) : null;
		}
		if (!(editingArea instanceof Station)) {
			return null;
		}
		final Station station = (Station) editingArea;
		final int[] rectangle = station.getRectangle(editingAreaIndex);
		return rectangle == null ? null : station.getHeight(rectangle);
	}

	/**
	 * Takes away the rectangle of the station being edited under the mouse, pressed with the right button (2026-11-09).
	 */
	private void removeStationAreaAt(double worldX, double worldZ) {
		if (!(editingArea instanceof Station) || selectedTab != SelectedTab.STATIONS) {
			return;
		}
		final int index = ((Station) editingArea).rectangleAt(worldX, worldZ);
		if (index >= 0) {
			removeStationArea(index);
		}
	}

	/**
	 * Takes a rectangle of the station being edited away, with its heights; the main area is taken over by the next one,
	 * and a station's only rectangle stays.
	 */
	private void removeStationArea(int index) {
		final Station station = (Station) editingArea;
		if (!station.removeRectangle(index)) {
			widgetMap.setAreaInfo(Text.translatable("gui.mtr.station_area_only_one").getString());
			return;
		}
		if (index == 0) {
			station.setCorners(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		}
		station.setExtraAreas(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		station.setAreaHeights(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_STATION, packet));
		ClientData.DATA_CACHE.sync();
		selectStationArea(Math.min(index == 0 ? 0 : index - 1, station.extraAreas.size()));
		widgetMap.setAreaInfo(Text.translatable("gui.mtr.station_area_removed", index + 1).getString());
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
			removeStationArea(editingAreaIndex);
		} else {
			selectStationArea(Math.max(0, editingAreaIndex - 1));
		}
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
		editingRoute.setOwnColor(colorSelector.getColor());
		editingRoute.setNameColor(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		editingRoute.setExtraData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
		ClientData.DATA_CACHE.sync();
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
			// With the heights the rectangle takes in, set with the station area tool
			final Station station = (Station) editingArea;
			final int[] rectangle = editingAreaIndex == 0 ? AreaBase.nonNullCorners(station) ? new int[]{station.corner1.getA(), station.corner1.getB(), station.corner2.getA(), station.corner2.getB()} : null : editingAreaIndex <= extraAreaCount ? station.extraAreas.get(editingAreaIndex - 1) : null;
			final int[] height = rectangle == null ? null : station.getHeight(rectangle);
			buttonStationArea.setMessage(Text.literal(Text.translatable("gui.mtr.station_area", editingAreaIndex + 1, Math.max(extraAreaCount + 1, editingAreaIndex + 1)).getString() + (height == null ? "" : " Y" + height[0] + "-" + height[1])));
			buttonAddStationArea.active = AreaBase.nonNullCorners(editingArea) && extraAreaCount < Station.MAX_EXTRA_AREAS && editingAreaIndex <= extraAreaCount;
			buttonRemoveStationArea.active = editingAreaIndex > 0;
			widgetMap.setAreaInfo(height == null ? "" : Text.translatable("gui.mtr.station_area_locked", height[0], height[1]).getString());
		} else {
			// A depot's area with heights (3D) is locked too
			final int[] depotHeights = editingArea instanceof Depot ? getEditingHeights() : null;
			widgetMap.setAreaInfo(depotHeights == null ? "" : Text.translatable("gui.mtr.depot_area_locked", depotHeights[0], depotHeights[1]).getString());
		}
		textFieldCustomDestination.visible = showRouteDestinationFields;
		textFieldDwellOverride.visible = showRouteDestinationFields;
		checkboxPassStation.visible = showRouteDestinationFields && editingRoutePlatformIndex > 0 && editingRoutePlatformIndex < editingRoute.platformIds.size() - 1;
		colorSelector.visible = showTextFields;
		dashboardList.height = height - SQUARE_SIZE * 2 - (showTextFields || showRouteDestinationFields ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0) - (showRouteDestinationFields ? (SQUARE_SIZE + TEXT_FIELD_PADDING) * 2 : 0) - (showStationAreas ? SQUARE_SIZE + TEXT_FIELD_PADDING / 2 : 0);
	}

	private enum SelectedTab {STATIONS, ROUTES, DEPOTS}
}
