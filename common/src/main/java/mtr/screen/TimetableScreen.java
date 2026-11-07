package mtr.screen;

import transitsim.plan.StopRules;
import mtr.advanced.PlanAdapters;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.advanced.AdvancedEditing;
import transitsim.plan.TimetableBoundaries;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.Depot;
import net.minecraft.core.BlockPos;
import mtr.data.IGui;
import mtr.data.NameColorDataBase;
import mtr.data.Platform;
import mtr.data.Route;
import transitsim.plan.RouteTimetable;
import mtr.data.Station;
import mtr.data.TimetableConfig;
import transitsim.plan.TimetableConflicts;
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Laid out like a Japanese driver's duty sheet, with one row for each stop and the times of the chosen service.
 */
public class TimetableScreen extends ScreenBase implements IGui, IPacket {

	private int scroll;
	private int rowCount;
	private int baseMillis;
	private boolean isBinding;
	private int directionWidth;
	private int columnPlatform;
	private int columnFastest;
	private int columnArrival;
	private int columnDeparture;
	private int columnPass;
	private int columnOvertake;
	private int checkedKey;
	private int tableIndex;
	private boolean hasInitialized;

	private Route route;
	private final ScreenMapper previousScreen;
	private final Button buttonDirection;
	private final WidgetBetterCheckbox checkboxEnabled;
	private final WidgetBetterTextField textFieldMargin;
	private final WidgetBetterTextField textFieldService;
	private final Button buttonAddService;
	private final Button buttonClear;
	private final Button buttonCheckConflicts;
	private final Button buttonEntryStop;
	private final Button buttonLinkRoute;
	private final Button buttonLinkStop;
	private final Button buttonLinkEntry;
	private final Button buttonClearLink;
	private final Button buttonReturnToDepot;
	private final WidgetBetterTextField textFieldLinkService;
	private final List<Long> linkRouteIds = new ArrayList<>();
	private final Button buttonTable;
	private final Button buttonAddTable;
	private final Button buttonDeleteTable;
	private final Button[] buttonsWeekday = new Button[DAYS_PER_WEEK];
	private final WidgetBetterTextField textFieldTableName;
	private final DashboardList servicesList;
	private final Set<String> conflictServices = new HashSet<>();
	private final WidgetBetterTextField[] textFieldsArrival = new WidgetBetterTextField[MAX_ROWS];
	private final WidgetBetterTextField[] textFieldsDeparture = new WidgetBetterTextField[MAX_ROWS];
	private final WidgetBetterCheckbox[] checkboxesPass = new WidgetBetterCheckbox[MAX_ROWS];
	private final WidgetBetterCheckbox[] checkboxesOvertake = new WidgetBetterCheckbox[MAX_ROWS];

	private final Component marginText = Text.translatable("gui.mtr.timetable_margin");
	private final Component hintText = Text.translatable("gui.mtr.timetable_hint");

	private static final int MAX_ROWS = 32;
	private static final int LIST_WIDTH = 130;
	private static final int TIME_WIDTH = 58;
	private static final int FASTEST_WIDTH = 44;
	private static final int PLATFORM_WIDTH = 30;
	private static final int CHECKBOX_COLUMN_WIDTH = SQUARE_SIZE + TEXT_PADDING * 2;
	private static final int MAX_DIRECTION_WIDTH = 120;
	private static final int MARGIN_FIELD_WIDTH = SQUARE_SIZE * 2;
	private static final int CHECK_BUTTON_WIDTH = 100;
	private static final int DAYS_PER_WEEK = 7;
	private static final int TABLE_BUTTON_WIDTH = SQUARE_SIZE * 2;
	private static final int ROWS_Y = SQUARE_SIZE * 3 + TEXT_PADDING;
	private static final int ARGB_WARNING = 0xFFFF5555;
	private static final int ARGB_FIELD_TEXT = 0xFFE0E0E0;

	public TimetableScreen(Route initialRoute, ScreenMapper previousScreen) {
		super(Text.literal(""));
		route = initialRoute;
		this.previousScreen = previousScreen;
		buttonDirection = UtilitiesClient.newButton(Text.literal(""), button -> onSelectNextDirection());

		checkboxEnabled = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.timetable_enabled"), checked -> {
		});
		textFieldMargin = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, String.valueOf(RouteTimetable.DEFAULT_MARGIN_PERCENT), 3);
		textFieldService = new WidgetBetterTextField("[^\\d:+* ]", "07:10:00 + 10 * 00:03:00", 25);
		buttonAddService = UtilitiesClient.newButton(Text.literal("+"), button -> {
			EditDepotScreen.applyDepartures(textFieldService.getValue(), service -> {
				if (getTable().services.size() < RouteTimetable.MAX_SERVICES && !getTable().services.contains(service)) {
					getTable().services.add(service);
				}
			});
			updateList();
		});
		buttonClear = UtilitiesClient.newButton(Text.translatable("gui.mtr.reset_sign"), button -> {
			getTable().services.clear();
			updateList();
		});
		buttonEntryStop = UtilitiesClient.newButton(Text.literal(""), button -> {
			// Trains can join at any stop that isn't passed, or at the first stop like any other route
			final int stopCount = route.platformIds.size();
			int nextStop = route.getLoopEntryStop() + 1;
			while (nextStop < stopCount - 1 && route.platformIds.get(nextStop).pass) {
				nextStop++;
			}
			route.timetable.entryPlatformId = nextStop < stopCount - 1 ? route.platformIds.get(nextStop).platformId : 0;
			updateEntryStopText();
		});
		buttonLinkRoute = UtilitiesClient.newButton(Text.literal(""), button -> {
			if (minecraft != null) {
				final List<NameColorDataBase> routes = new ArrayList<>(ClientData.getFilteredDataSet(route.transportMode, ClientData.ROUTES));
				routes.remove(route);
				final RouteTimetable.ServiceLink link = getTable().links.get(baseMillis);
				linkRouteIds.clear();
				if (link != null) {
					linkRouteIds.add(link.routeId);
				}
				UtilitiesClient.setScreen(minecraft, new DashboardListSelectorScreen(() -> {
					UtilitiesClient.setScreen(minecraft, this);
					onLinkRouteSelected();
				}, routes, linkRouteIds, true, false));
			}
		});
		textFieldLinkService = new WidgetBetterTextField("[^\\d:]", null, 8);
		textFieldLinkService.setResponder(this::onLinkServiceChanged);
		buttonLinkStop = UtilitiesClient.newButton(Text.literal(""), button -> {
			final RouteTimetable.ServiceLink link = getTable().links.get(baseMillis);
			if (link != null) {
				// A route can be left at its timetable boundaries, or at the end
				int nextStop = link.fromStop < 0 ? 1 : link.fromStop + 1;
				while (nextStop < route.platformIds.size() - 1 && !TimetableBoundaries.isBoundaryStop(route, nextStop, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap))) {
					nextStop++;
				}
				getTable().links.put(baseMillis, new RouteTimetable.ServiceLink(link.routeId, link.service, nextStop < route.platformIds.size() - 1 ? nextStop : -1, link.toStop));
				updateLinkWidgets();
			}
		});
		buttonLinkEntry = UtilitiesClient.newButton(Text.literal(""), button -> {
			final RouteTimetable.ServiceLink link = getTable().links.get(baseMillis);
			final Route linkRoute = link == null ? null : ClientData.DATA_CACHE.routeIdMap.get(link.routeId);
			if (linkRoute != null) {
				// A route can be joined at its timetable boundaries, or at the first stop
				int nextStop = link.toStop + 1;
				while (nextStop < linkRoute.platformIds.size() - 1 && !TimetableBoundaries.isBoundaryStop(linkRoute, nextStop, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap))) {
					nextStop++;
				}
				getTable().links.put(baseMillis, new RouteTimetable.ServiceLink(link.routeId, link.service, link.fromStop, nextStop < linkRoute.platformIds.size() - 1 ? nextStop : 0));
				updateLinkWidgets();
			}
		});
		buttonClearLink = UtilitiesClient.newButton(Text.literal("X"), button -> {
			getTable().links.remove(baseMillis);
			updateLinkWidgets();
		});
		buttonReturnToDepot = UtilitiesClient.newButton(Text.literal(""), button -> {
			if (!getTable().returnServices.remove(baseMillis)) {
				getTable().returnServices.add(baseMillis);
			}
			updateLinkWidgets();
		});
		buttonTable = UtilitiesClient.newButton(Text.literal(""), button -> selectTable((tableIndex + 1) % route.timetable.getTableCount()));
		buttonAddTable = UtilitiesClient.newButton(Text.literal("+"), button -> {
			saveTableFields();
			final int newTable = route.addTable(tableIndex);
			if (newTable >= 0) {
				tableIndex = newTable;
				onTableChanged();
			}
		});
		buttonDeleteTable = UtilitiesClient.newButton(Text.literal("-"), button -> {
			route.removeTable(tableIndex);
			tableIndex--;
			onTableChanged();
		});
		textFieldTableName = new WidgetBetterTextField(Text.translatable("gui.mtr.timetable_table_name").getString());
		for (int i = 0; i < DAYS_PER_WEEK; i++) {
			final int day = i;
			buttonsWeekday[i] = UtilitiesClient.newButton(Text.literal(""), button -> {
				getTable().weekdays ^= 1 << day;
				updateTableWidgets();
			});
		}
		buttonCheckConflicts = UtilitiesClient.newButton(Text.translatable("gui.mtr.timetable_check_conflicts"), button -> {
			saveRoute();
			requestConflicts();
		});
		servicesList = new DashboardList(this::onSelectService, null, null, null, null, this::onDeleteService, null, () -> "", text -> {
		});
		servicesList.setRowStyle(data -> IGui.formatStationName(data.name), data -> false, data -> conflictServices.contains(data.name) ? ARGB_WARNING & 0xFFFFFF : data.color, data -> {
		});

		for (int i = 0; i < MAX_ROWS; i++) {
			final int row = i;
			textFieldsArrival[i] = new WidgetBetterTextField("[^\\d:]", null, 8);
			textFieldsArrival[i].setResponder(text -> onTimeChanged(row, true, text));
			textFieldsDeparture[i] = new WidgetBetterTextField("[^\\d:]", null, 8);
			textFieldsDeparture[i].setResponder(text -> onTimeChanged(row, false, text));
			checkboxesPass[i] = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal(""), checked -> {
				final int index = scroll + row;
				if (!isBinding && index < route.platformIds.size()) {
					final Route.RoutePlatform routePlatform = route.platformIds.get(index);
					routePlatform.pass = checked;
					if (checked) {
						// A train running through can't keep to a departure time or wait, whichever table it runs to
						for (int j = 0; j < route.timetable.getTableCount(); j++) {
							routePlatform.setDeparture(j, -1);
							routePlatform.setOvertake(j, false);
						}
					}
					bindRows();
				}
			});
			checkboxesOvertake[i] = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal(""), checked -> {
				final int index = scroll + row;
				if (!isBinding && index < route.platformIds.size()) {
					route.platformIds.get(index).setOvertake(tableIndex, checked);
				}
			});
		}

		PacketTrainDataGuiClient.requestTimetableTimesC2S(route.id);
	}

	@Override
	protected void init() {
		super.init();
		final int rightX = LIST_WIDTH + TEXT_PADDING;

		columnOvertake = width - CHECKBOX_COLUMN_WIDTH;
		columnPass = columnOvertake - CHECKBOX_COLUMN_WIDTH;
		columnDeparture = columnPass - TIME_WIDTH;
		columnArrival = columnDeparture - TIME_WIDTH;
		columnFastest = columnArrival - FASTEST_WIDTH;
		columnPlatform = columnFastest - PLATFORM_WIDTH;
		rowCount = Math.max(1, Math.min(MAX_ROWS, (getMarginY() - ROWS_Y) / SQUARE_SIZE));
		scroll = 0;

		servicesList.x = 0;
		servicesList.y = SQUARE_SIZE;
		servicesList.height = height - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING;
		servicesList.width = LIST_WIDTH;
		servicesList.init(this::addDrawableChild);

		IDrawing.setPositionAndWidth(buttonClear, 0, 0, LIST_WIDTH);
		IDrawing.setPositionAndWidth(textFieldService, TEXT_FIELD_PADDING / 2, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, LIST_WIDTH - TEXT_FIELD_PADDING - SQUARE_SIZE);
		textFieldService.setResponder(text -> buttonAddService.active = !isLocked() && EditDepotScreen.applyDepartures(text, service -> {
		}));
		IDrawing.setPositionAndWidth(buttonAddService, LIST_WIDTH - SQUARE_SIZE, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, SQUARE_SIZE);
		buttonAddService.active = false;

		final boolean hasDirections = getLineRoutes().size() > 1;
		directionWidth = hasDirections ? Math.min(MAX_DIRECTION_WIDTH, (width - rightX) / 2 - TEXT_PADDING) : 0;
		IDrawing.setPositionAndWidth(buttonDirection, rightX, 0, directionWidth);
		buttonDirection.visible = hasDirections;
		IDrawing.setPositionAndWidth(checkboxEnabled, rightX + directionWidth + TEXT_PADDING, 0, width - rightX - directionWidth - TEXT_PADDING);
		checkboxEnabled.setChecked(route.timetable.enabled);
		IDrawing.setPositionAndWidth(textFieldMargin, rightX + font.width(marginText) + TEXT_PADDING * 2 + TEXT_FIELD_PADDING / 2, getMarginY() + TEXT_FIELD_PADDING / 2, MARGIN_FIELD_WIDTH - TEXT_FIELD_PADDING);
		updateDirectionText();

		final int weekdaysX = width - SQUARE_SIZE * DAYS_PER_WEEK;
		IDrawing.setPositionAndWidth(buttonTable, rightX, SQUARE_SIZE, TABLE_BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(textFieldTableName, rightX + TABLE_BUTTON_WIDTH + TEXT_FIELD_PADDING / 2, SQUARE_SIZE + TEXT_FIELD_PADDING / 2, weekdaysX - rightX - TABLE_BUTTON_WIDTH - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(buttonAddTable, weekdaysX - SQUARE_SIZE * 2, SQUARE_SIZE, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonDeleteTable, weekdaysX - SQUARE_SIZE, SQUARE_SIZE, SQUARE_SIZE);
		for (int i = 0; i < DAYS_PER_WEEK; i++) {
			IDrawing.setPositionAndWidth(buttonsWeekday[i], weekdaysX + SQUARE_SIZE * i, SQUARE_SIZE, SQUARE_SIZE);
			addDrawableChild(buttonsWeekday[i]);
		}
		// Resizing the window comes through here too, and shouldn't lose what was typed
		if (hasInitialized) {
			saveTableFields();
		}
		hasInitialized = true;
		textFieldMargin.setValue(String.valueOf(getTable().marginPercent));
		textFieldTableName.setValue(getTable().name);
		updateTableWidgets();

		addDrawableChild(buttonTable);
		addDrawableChild(textFieldTableName);
		addDrawableChild(buttonAddTable);
		addDrawableChild(buttonDeleteTable);
		addDrawableChild(buttonDirection);
		addDrawableChild(buttonClear);
		addDrawableChild(textFieldService);
		addDrawableChild(buttonAddService);
		addDrawableChild(checkboxEnabled);
		// The margin is no longer set: running times are planned at the planned speeds (optimisation round 3)

		for (int i = 0; i < rowCount; i++) {
			final int y = ROWS_Y + SQUARE_SIZE * i;
			IDrawing.setPositionAndWidth(textFieldsArrival[i], columnArrival + TEXT_FIELD_PADDING / 2, y + TEXT_FIELD_PADDING / 2, TIME_WIDTH - TEXT_FIELD_PADDING);
			IDrawing.setPositionAndWidth(textFieldsDeparture[i], columnDeparture + TEXT_FIELD_PADDING / 2, y + TEXT_FIELD_PADDING / 2, TIME_WIDTH - TEXT_FIELD_PADDING);
			IDrawing.setPositionAndWidth(checkboxesPass[i], columnPass + TEXT_PADDING, y, SQUARE_SIZE);
			IDrawing.setPositionAndWidth(checkboxesOvertake[i], columnOvertake + TEXT_PADDING, y, SQUARE_SIZE);
			addDrawableChild(textFieldsArrival[i]);
			addDrawableChild(textFieldsDeparture[i]);
			addDrawableChild(checkboxesPass[i]);
			addDrawableChild(checkboxesOvertake[i]);
		}

		IDrawing.setPositionAndWidth(buttonCheckConflicts, width - CHECK_BUTTON_WIDTH, getMarginY(), CHECK_BUTTON_WIDTH);
		addDrawableChild(buttonCheckConflicts);
		IDrawing.setPositionAndWidth(buttonEntryStop, width - CHECK_BUTTON_WIDTH * 2, getMarginY(), CHECK_BUTTON_WIDTH);
		addDrawableChild(buttonEntryStop);
		updateEntryStopText();

		final int linkY = getMarginY() + SQUARE_SIZE;
		IDrawing.setPositionAndWidth(buttonLinkRoute, rightX, linkY, CHECK_BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(textFieldLinkService, rightX + CHECK_BUTTON_WIDTH + TEXT_FIELD_PADDING / 2, linkY + TEXT_FIELD_PADDING / 2, TIME_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(buttonLinkStop, rightX + CHECK_BUTTON_WIDTH + TIME_WIDTH, linkY, CHECK_BUTTON_WIDTH);
		IDrawing.setPositionAndWidth(buttonClearLink, rightX + CHECK_BUTTON_WIDTH * 2 + TIME_WIDTH, linkY, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonLinkEntry, rightX + CHECK_BUTTON_WIDTH * 2 + TIME_WIDTH + SQUARE_SIZE, linkY, CHECK_BUTTON_WIDTH);
		// In the place of the next service's time, as a service running through to another route doesn't go back to the depot
		IDrawing.setPositionAndWidth(buttonReturnToDepot, rightX + CHECK_BUTTON_WIDTH, linkY, CHECK_BUTTON_WIDTH);
		addDrawableChild(buttonLinkRoute);
		addDrawableChild(textFieldLinkService);
		addDrawableChild(buttonLinkStop);
		addDrawableChild(buttonClearLink);
		addDrawableChild(buttonLinkEntry);
		addDrawableChild(buttonReturnToDepot);

		updateList();
		requestConflicts();
		updateLocked();
	}

	@Override
	public void tick() {
		servicesList.tick();
		textFieldService.tick();
		textFieldMargin.tick();
		textFieldTableName.tick();
		textFieldLinkService.tick();
		for (int i = 0; i < rowCount; i++) {
			textFieldsArrival[i].tick();
			textFieldsDeparture[i].tick();
		}
		updateSuggestions();
		updateConflictServices();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.vLine(matrices, LIST_WIDTH, -1, height, ARGB_WHITE_TRANSLUCENT);
			servicesList.render(matrices, font);

			final int rightX = LIST_WIDTH + TEXT_PADDING;
			final int headerY = SQUARE_SIZE * 2 + TEXT_PADDING;
			// No margin to show (optimisation round 3)
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_station"), rightX, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_platform"), columnPlatform, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_fastest"), columnFastest, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_arrival"), columnArrival + TEXT_PADDING, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_departure"), columnDeparture + TEXT_PADDING, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_pass"), columnPass, headerY, ARGB_LIGHT_GRAY);
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.timetable_overtake"), columnOvertake, headerY, ARGB_LIGHT_GRAY);

			final List<RouteTimetable.StopPlan> plans = getPlans();
			final boolean hasTimes = hasFastestTimes();
			long lastFastestDeparture = 0;
			long lastDeparture = 0;
			for (int i = 0; i < plans.size(); i++) {
				final RouteTimetable.StopPlan plan = plans.get(i);
				final boolean tooFast = i > 0 && hasTimes && plan.arrivalMillis < lastDeparture + plan.fastestArrivalMillis - lastFastestDeparture;
				lastFastestDeparture = plan.fastestDepartureMillis;
				lastDeparture = plan.departureMillis;

				final int row = i - scroll;
				if (row >= 0 && row < rowCount) {
					drawRow(matrices, i, plan, ROWS_Y + SQUARE_SIZE * row + TEXT_PADDING + TEXT_FIELD_PADDING / 2, hasTimes, tooFast);
				}
			}

			final boolean isLocked = isLocked();
			final List<FormattedCharSequence> hintLines = getHintLines(isLocked ? Text.translatable("gui.mtr.timetable_editor_managed") : hasTimes ? hintText : Text.translatable("gui.mtr.timetable_no_path"));
			for (int i = 0; i < hintLines.size(); i++) {
				GuiCompat.draw(matrices, font, hintLines.get(i), rightX, height - (TEXT_HEIGHT + 1) * (hintLines.size() - i), hasTimes && !isLocked ? ARGB_LIGHT_GRAY : ARGB_WARNING);
			}
			drawConflictText(matrices, rightX, getMarginY() + SQUARE_SIZE * 2 + TEXT_PADDING / 2);

			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		servicesList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX > LIST_WIDTH) {
			final int newScroll = Math.max(0, Math.min(scroll + (amount > 0 ? -1 : 1), route.platformIds.size() - rowCount));
			if (newScroll != scroll) {
				scroll = newScroll;
				bindRows();
			}
		} else {
			servicesList.mouseScrolled(mouseX, mouseY, amount);
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void onClose() {
		super.onClose();
		saveRoute();
		if (minecraft != null) {
			UtilitiesClient.setScreen(minecraft, previousScreen);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * @return the route's name with where it goes, which tells apart directions that share a name
	 */
	public static String getDirectionText(Route route) {
		final Station lastStation = ClientData.DATA_CACHE.platformIdToStation.get(route.getLastPlatformId());
		return IGui.formatStationName(route.name) + (lastStation == null ? "" : " \u2192 " + IGui.formatStationName(lastStation.name));
	}

	/**
	 * The hints go below the table, and wrap to the width of the window, so the table gets whatever height is left.
	 */
	private List<FormattedCharSequence> getHintLines(Component text) {
		final List<FormattedCharSequence> lines = new ArrayList<>();
		for (final String line : text.getString().split("\\|")) {
			lines.addAll(font.split(Text.literal(line), width - LIST_WIDTH - TEXT_PADDING * 2));
		}
		return lines;
	}

	private int getMarginY() {
		return height - SQUARE_SIZE * 2 - (TEXT_HEIGHT + 1) * (getHintLines(hintText).size() + 1) - TEXT_PADDING;
	}

	private void onLinkRouteSelected() {
		final Route linkRoute = linkRouteIds.isEmpty() ? null : ClientData.DATA_CACHE.routeIdMap.get(linkRouteIds.get(0));
		if (linkRoute == null) {
			getTable().links.remove(baseMillis);
		} else {
			// The closest service after this one is the likely choice, and it can be changed afterwards
			final RouteTimetable.ServiceLink link = getTable().links.get(baseMillis);
			final int service = linkRoute.timetable.services.stream().filter(testService -> testService >= baseMillis).min(Integer::compareTo).orElse(linkRoute.timetable.services.stream().min(Integer::compareTo).orElse(baseMillis));
			final boolean isSameRoute = link != null && link.routeId == linkRoute.id;
			getTable().links.put(baseMillis, new RouteTimetable.ServiceLink(linkRoute.id, service, link == null ? -1 : link.fromStop, isSameRoute ? link.toStop : 0));
		}
		updateLinkWidgets();
	}

	private void onLinkServiceChanged(String text) {
		final RouteTimetable.ServiceLink link = getTable().links.get(baseMillis);
		if (link != null && !isBinding) {
			final boolean isValid = text.length() == 8 && EditDepotScreen.applyDepartures(text, service -> getTable().links.put(baseMillis, new RouteTimetable.ServiceLink(link.routeId, service, link.fromStop, link.toStop)));
			textFieldLinkService.setTextColor(isValid ? ARGB_FIELD_TEXT : ARGB_WARNING);
		}
	}

	private String getOrderStopName(RouteTimetable.ServiceOrder order) {
		final Station station = route.platformIds.isEmpty() ? null : ClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(Math.min(order.stop, route.platformIds.size() - 1)).platformId);
		return IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
	}

	private static String getOrderDepotName(RouteTimetable.ServiceOrder order) {
		final Depot depot = order.depotId == 0 ? null : ClientData.DATA_CACHE.depotIdMap.get(order.depotId);
		return depot == null ? Text.translatable("gui.mtr.timetable_order_any_depot").getString() : IGui.textOrUntitled(IGui.formatStationName(depot.name));
	}

	private void updateLinkWidgets() {
		final boolean hasService = getTable().services.contains(baseMillis);
		final RouteTimetable.ServiceLink link = hasService ? getTable().links.get(baseMillis) : null;
		final Route linkRoute = link == null ? null : ClientData.DATA_CACHE.routeIdMap.get(link.routeId);
		final String routeName = link == null ? Text.translatable("gui.mtr.timetable_link_none").getString() : IGui.textOrUntitled(linkRoute == null ? "" : IGui.formatStationName(linkRoute.name));

		buttonLinkRoute.visible = hasService;
		buttonLinkRoute.setMessage(Text.literal(fitButtonText(Text.translatable("gui.mtr.timetable_link_route", routeName).getString())));
		textFieldLinkService.visible = link != null;
		buttonLinkStop.visible = link != null && TimetableBoundaries.hasBoundaryStops(route, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap));
		buttonClearLink.visible = link != null;
		buttonLinkEntry.visible = linkRoute != null && TimetableBoundaries.hasBoundaryStops(linkRoute, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap));
		buttonReturnToDepot.visible = hasService && link == null;
		// Set on the running diagram in the advanced editor, only shown here
		final List<String> orders = new ArrayList<>();
		if (hasService && getTable().suspended.contains(baseMillis)) {
			orders.add(Text.translatable("gui.mtr.timetable_suspended").getString());
		}
		final RouteTimetable.ServiceOrder deployOrder = hasService ? getTable().deployOrders.get(baseMillis) : null;
		final RouteTimetable.ServiceOrder returnOrder = hasService ? getTable().returnOrders.get(baseMillis) : null;
		if (deployOrder != null) {
			orders.add(Text.translatable("gui.mtr.timetable_deploy_order", getOrderStopName(deployOrder), getOrderDepotName(deployOrder)).getString());
		}
		if (returnOrder != null) {
			orders.add(Text.translatable("gui.mtr.timetable_return_order", getOrderStopName(returnOrder), getOrderDepotName(returnOrder)).getString());
		}
		buttonReturnToDepot.setMessage(Text.literal(fitButtonText(orders.isEmpty() ? Text.translatable(getTable().returnServices.contains(baseMillis) ? "gui.mtr.timetable_return_depot" : "gui.mtr.timetable_stay_on_line").getString() : String.join(" / ", orders))));
		updateLocked();

		final boolean wasBinding = isBinding;
		isBinding = true;
		textFieldLinkService.setValue(link == null ? "" : EditDepotScreen.formatTime(link.service));
		textFieldLinkService.setTextColor(ARGB_FIELD_TEXT);
		isBinding = wasBinding;

		if (link != null) {
			final Station station = link.fromStop < 0 ? null : ClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(Math.min(link.fromStop, route.platformIds.size() - 1)).platformId);
			final String stopName = link.fromStop < 0 ? Text.translatable("gui.mtr.timetable_link_last_stop").getString() : IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
			buttonLinkStop.setMessage(Text.literal(fitButtonText(Text.translatable("gui.mtr.timetable_link_stop", stopName).getString())));
		}
		if (linkRoute != null && TimetableBoundaries.hasBoundaryStops(linkRoute, PlanAdapters.boundaryPlatforms(ClientData.DATA_CACHE.platformIdMap))) {
			final Station station = ClientData.DATA_CACHE.platformIdToStation.get(linkRoute.platformIds.get(Math.min(link.toStop, linkRoute.platformIds.size() - 1)).platformId);
			final String stopName = link.toStop == 0 ? Text.translatable("gui.mtr.timetable_entry_first_stop").getString() : IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
			buttonLinkEntry.setMessage(Text.literal(fitButtonText(Text.translatable("gui.mtr.timetable_link_entry", stopName).getString())));
		}
	}

	private String fitButtonText(String text) {
		final int availableWidth = CHECK_BUTTON_WIDTH - TEXT_PADDING * 2;
		return font == null || font.width(text) <= availableWidth ? text : font.plainSubstrByWidth(text, availableWidth - font.width("...")) + "...";
	}

	private void saveRoute() {
		if (isLocked()) {
			return;
		}
		route.timetable.enabled = checkboxEnabled.selected();
		saveTableFields();
		route.timetable.sanitize();
		route.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
	}

	private RouteTimetable getTable() {
		return route.timetable.getTable(tableIndex);
	}

	private void saveTableFields() {
		// The margin is kept as saved, unused (optimisation round 3)
		getTable().name = textFieldTableName.getValue();
	}

	private void selectTable(int newTableIndex) {
		saveTableFields();
		tableIndex = newTableIndex;
		onTableChanged();
	}

	private void onTableChanged() {
		textFieldMargin.setValue(String.valueOf(getTable().marginPercent));
		textFieldTableName.setValue(getTable().name);
		updateTableWidgets();
		updateList();
	}

	private void updateTableWidgets() {
		final int tableCount = route.timetable.getTableCount();
		buttonTable.setMessage(Text.literal((tableIndex + 1) + "/" + tableCount));
		buttonTable.active = tableCount > 1;
		buttonAddTable.active = tableCount < RouteTimetable.MAX_TABLES;
		buttonDeleteTable.active = tableIndex > 0;
		for (int i = 0; i < DAYS_PER_WEEK; i++) {
			buttonsWeekday[i].setMessage(Text.literal((getTable().runsOn(i) ? "§a" : "§8") + Text.translatable("gui.mtr.timetable_weekday_" + i).getString()));
		}
		updateLocked();
	}

	private boolean isLocked() {
		return AdvancedEditing.isLockedInGame(route);
	}

	/**
	 * A route the advanced editor manages can still be looked through here, by table, direction and service, but not changed.
	 * Controls that are sometimes unavailable anyway are only switched off, so they come back the way their own update left
	 * them when the next route isn't locked.
	 */
	private void updateLocked() {
		final boolean canEdit = !isLocked();
		checkboxEnabled.active = canEdit;
		buttonAddService.active &= canEdit;
		buttonClear.active = canEdit;
		buttonEntryStop.active = canEdit;
		buttonLinkRoute.active = canEdit;
		buttonLinkStop.active = canEdit;
		buttonLinkEntry.active = canEdit;
		buttonClearLink.active = canEdit;
		buttonReturnToDepot.active = canEdit;
		buttonAddTable.active &= canEdit;
		buttonDeleteTable.active &= canEdit;
		for (final Button button : buttonsWeekday) {
			button.active = canEdit;
		}
		for (final WidgetBetterTextField textField : new WidgetBetterTextField[]{textFieldMargin, textFieldService, textFieldLinkService, textFieldTableName}) {
			textField.setEditable(canEdit);
		}
		for (int i = 0; i < MAX_ROWS; i++) {
			textFieldsArrival[i].setEditable(canEdit);
			textFieldsDeparture[i].setEditable(canEdit);
			checkboxesPass[i].active = canEdit;
			checkboxesOvertake[i].active = canEdit;
		}
	}

	/**
	 * The same grouping as the folded rows of the route list.
	 */
	private List<Route> getLineRoutes() {
		final List<Route> routes = ClientData.getFilteredDataSet(route.transportMode, ClientData.ROUTES).stream().filter(route::isSameLine).sorted().collect(Collectors.toList());
		return routes.isEmpty() ? Collections.singletonList(route) : routes;
	}

	private void onSelectNextDirection() {
		saveRoute();
		final List<Route> routes = getLineRoutes();
		route = routes.get((routes.indexOf(route) + 1) % routes.size());
		scroll = 0;
		tableIndex = 0;
		checkboxEnabled.setChecked(route.timetable.enabled);
		PacketTrainDataGuiClient.requestTimetableTimesC2S(route.id);
		updateDirectionText();
		onTableChanged();
		updateEntryStopText();
		requestConflicts();
	}

	/**
	 * Only a loop can be joined part of the way round, so other routes don't show this.
	 */
	private void updateEntryStopText() {
		buttonEntryStop.visible = route.isLoop();
		final int entryStop = route.getLoopEntryStop();
		final Station station = entryStop == 0 ? null : ClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(entryStop).platformId);
		final String stationName = entryStop == 0 ? Text.translatable("gui.mtr.timetable_entry_first_stop").getString() : IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
		buttonEntryStop.setMessage(Text.literal(fitButtonText(Text.translatable("gui.mtr.timetable_entry_stop", stationName).getString())));
	}

	private void updateDirectionText() {
		// The line is the same for every direction, so the button only needs what tells them apart
		final Station lastStation = ClientData.DATA_CACHE.platformIdToStation.get(route.getLastPlatformId());
		final String text = lastStation == null ? IGui.formatStationName(route.name) : (route.getServiceType() + " → " + lastStation.name.split("\\|")[0]).trim();
		final int availableWidth = directionWidth - TEXT_PADDING * 4;
		final boolean isTooLong = font.width(text) > availableWidth;
		buttonDirection.setMessage(Text.literal(isTooLong ? font.plainSubstrByWidth(text, availableWidth - font.width("...")) + "..." : text));
	}

	private void drawRow(PoseStack matrices, int index, RouteTimetable.StopPlan plan, int y, boolean hasTimes, boolean tooFast) {
		final Route.RoutePlatform routePlatform = route.platformIds.get(index);
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
		final String stationName = IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
		final int rightX = LIST_WIDTH + TEXT_PADDING;

		GuiCompat.draw(matrices, font, font.plainSubstrByWidth(stationName, columnPlatform - rightX - TEXT_PADDING), rightX, y, hasConflict(index) ? ARGB_WARNING : ARGB_WHITE);
		GuiCompat.draw(matrices, font, platform == null ? "" : font.plainSubstrByWidth(platform.name, PLATFORM_WIDTH - TEXT_PADDING), columnPlatform, y, ARGB_LIGHT_GRAY);
		GuiCompat.draw(matrices, font, hasTimes ? formatDuration(plan.fastestArrivalMillis) : "-", columnFastest, y, tooFast ? ARGB_WARNING : ARGB_LIGHT_GRAY);

		if (index == 0) {
			GuiCompat.draw(matrices, font, EditDepotScreen.formatTime(baseMillis), columnArrival + TEXT_PADDING, y, ARGB_WHITE);
		}
		if (isPass(index)) {
			GuiCompat.draw(matrices, font, "\u2193", columnDeparture + TIME_WIDTH / 2F, y, ARGB_LIGHT_GRAY);
		}
	}

	private boolean isPass(int index) {
		return route.platformIds.get(index).pass && index > 0 && index < route.platformIds.size() - 1;
	}

	private boolean hasFastestTimes() {
		final long[][] times = ClientData.TIMETABLE_TIMES.get(route.id);
		return times != null && times.length == route.platformIds.size();
	}

	private List<RouteTimetable.StopPlan> getPlans() {
		// The selected service's own times, where it runs differently from the table
		return StopRules.plan(route.platformIds, hasFastestTimes() ? ClientData.TIMETABLE_TIMES.get(route.id) : null, parseMargin(), tableIndex, getTable().exceptions.get(baseMillis));
	}

	private void requestConflicts() {
		checkedKey = getCheckKey();
		PacketTrainDataGuiClient.requestTimetableConflictsC2S(route.id);
	}

	/**
	 * Changes with anything that moves the times, so a result that no longer applies doesn't stay on screen.
	 */
	private int getCheckKey() {
		int key = Long.hashCode(route.id);
		for (int i = 0; i < route.timetable.getTableCount(); i++) {
			final RouteTimetable table = route.timetable.getTable(i);
			key = key * 31 + Objects.hash(i == tableIndex ? parseMargin() : table.marginPercent, table.weekdays, table.services);
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				key = key * 31 + Objects.hash(routePlatform.getArrival(i), routePlatform.getDeparture(i), routePlatform.pass);
			}
		}
		return key;
	}

	private TimetableConflicts.Result getConflictResult() {
		return checkedKey == getCheckKey() ? ClientData.TIMETABLE_CONFLICTS.get(route.id) : null;
	}

	private List<TimetableConflicts.Conflict> getServiceConflicts(TimetableConflicts.Result result) {
		return result.conflicts.stream().filter(conflict -> conflict.table == tableIndex && conflict.service == baseMillis || conflict.otherRouteId == route.id && conflict.otherService == baseMillis).collect(Collectors.toList());
	}

	private boolean hasConflict(int index) {
		final TimetableConflicts.Result result = getConflictResult();
		return result != null && getServiceConflicts(result).stream().anyMatch(conflict -> conflict.stopIndex == index);
	}

	private void updateConflictServices() {
		conflictServices.clear();
		final TimetableConflicts.Result result = getConflictResult();
		if (result != null) {
			result.conflicts.forEach(conflict -> {
				if (conflict.table == tableIndex) {
					conflictServices.add(EditDepotScreen.formatTime(conflict.service));
				}
				if (conflict.otherRouteId == route.id) {
					conflictServices.add(EditDepotScreen.formatTime(conflict.otherService));
				}
			});
		}
	}

	private void drawConflictText(PoseStack matrices, int x, int y) {
		final TimetableConflicts.Result result = getConflictResult();
		final Component text;
		if (checkedKey != getCheckKey()) {
			text = Text.translatable("gui.mtr.timetable_conflicts_outdated");
		} else if (result == null) {
			text = Text.translatable("gui.mtr.timetable_conflicts_checking");
		} else if (result.conflicts.isEmpty()) {
			text = result.uncheckedRoutes > 0 ? Text.translatable("gui.mtr.timetable_conflicts_unchecked", result.uncheckedRoutes) : Text.translatable("gui.mtr.timetable_conflicts_none");
		} else {
			text = getConflictSummary(result);
		}
		final boolean isWarning = result != null && (!result.conflicts.isEmpty() || result.uncheckedRoutes > 0);
		GuiCompat.draw(matrices, font, font.plainSubstrByWidth(text.getString(), width - x - TEXT_PADDING), x, y, isWarning ? ARGB_WARNING : ARGB_LIGHT_GRAY);
	}

	private Component getConflictSummary(TimetableConflicts.Result result) {
		final String count = result.conflicts.size() >= TimetableConflicts.MAX_CONFLICTS ? result.conflicts.size() + "+" : String.valueOf(result.conflicts.size());
		final List<TimetableConflicts.Conflict> serviceConflicts = getServiceConflicts(result);
		if (serviceConflicts.isEmpty()) {
			return Text.translatable("gui.mtr.timetable_conflicts_other_services", count);
		}

		final TimetableConflicts.Conflict conflict = serviceConflicts.get(0);
		final Route otherRoute = ClientData.DATA_CACHE.routeIdMap.get(conflict.otherRouteId);
		final int otherService = conflict.otherRouteId == route.id && conflict.otherService == baseMillis ? conflict.service : conflict.otherService;
		if (conflict.turnoutNode != 0) {
			// Both trains through a turnout of the throat by different ways at once (L2)
			final Station station = conflict.stopIndex < route.platformIds.size() ? ClientData.DATA_CACHE.platformIdToStation.get(route.platformIds.get(conflict.stopIndex).platformId) : null;
			final BlockPos turnout = BlockPos.of(conflict.turnoutNode);
			return Text.translatable("gui.mtr.timetable_conflicts_found_throat", count, IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name)), turnout.getX() + ", " + turnout.getZ(), IGui.textOrUntitled(otherRoute == null ? "" : IGui.formatStationName(otherRoute.name)), EditDepotScreen.formatTime(otherService));
		}
		if (conflict.platformId == 0) {
			return Text.translatable("gui.mtr.timetable_conflicts_found_turn_back", count, IGui.textOrUntitled(otherRoute == null ? "" : IGui.formatStationName(otherRoute.name)), EditDepotScreen.formatTime(otherService));
		}

		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(conflict.platformId);
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(conflict.platformId);
		return Text.translatable(
				"gui.mtr.timetable_conflicts_found",
				count,
				IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name)),
				platform == null ? "" : platform.name,
				IGui.textOrUntitled(otherRoute == null ? "" : IGui.formatStationName(otherRoute.name)),
				EditDepotScreen.formatTime(otherService)
		);
	}

	private int parseMargin() {
		try {
			return Math.min(Integer.parseInt(textFieldMargin.getValue()), RouteTimetable.MAX_MARGIN_PERCENT);
		} catch (NumberFormatException e) {
			return RouteTimetable.DEFAULT_MARGIN_PERCENT;
		}
	}

	private void bindRows() {
		isBinding = true;
		final int stopCount = route.platformIds.size();
		for (int i = 0; i < rowCount; i++) {
			final int index = scroll + i;
			final boolean hasStop = index < stopCount;
			final Route.RoutePlatform routePlatform = hasStop ? route.platformIds.get(index) : null;

			textFieldsArrival[i].visible = hasStop && index > 0;
			textFieldsDeparture[i].visible = hasStop && index < stopCount - 1 && !isPass(index);
			checkboxesPass[i].visible = hasStop && index > 0 && index < stopCount - 1;
			checkboxesOvertake[i].visible = hasStop && index > 0 && index < stopCount - 1 && !isPass(index);

			textFieldsArrival[i].setValue(!hasStop || routePlatform.getArrival(tableIndex) < 0 ? "" : EditDepotScreen.formatTime(baseMillis + routePlatform.getArrival(tableIndex) * 1000L));
			textFieldsDeparture[i].setValue(!hasStop || routePlatform.getDeparture(tableIndex) < 0 ? "" : EditDepotScreen.formatTime(baseMillis + routePlatform.getDeparture(tableIndex) * 1000L));
			textFieldsArrival[i].setTextColor(ARGB_FIELD_TEXT);
			textFieldsDeparture[i].setTextColor(ARGB_FIELD_TEXT);
			checkboxesPass[i].setChecked(hasStop && routePlatform.pass);
			checkboxesOvertake[i].setChecked(hasStop && routePlatform.getOvertake(tableIndex));
		}
		isBinding = false;
		updateSuggestions();
		updateLinkWidgets();
	}

	private void updateSuggestions() {
		final List<RouteTimetable.StopPlan> plans = getPlans();
		for (int i = 0; i < rowCount; i++) {
			final int index = scroll + i;
			if (index < plans.size()) {
				textFieldsArrival[i].setSuggestion(textFieldsArrival[i].getValue().isEmpty() ? EditDepotScreen.formatTime(baseMillis + plans.get(index).arrivalMillis) : "");
				textFieldsDeparture[i].setSuggestion(textFieldsDeparture[i].getValue().isEmpty() ? EditDepotScreen.formatTime(baseMillis + plans.get(index).departureMillis) : "");
			}
		}
	}

	private void onTimeChanged(int row, boolean isArrival, String text) {
		final int index = scroll + row;
		if (isBinding || index >= route.platformIds.size()) {
			return;
		}

		final Route.RoutePlatform routePlatform = route.platformIds.get(index);
		final boolean isValid;
		if (text.isEmpty()) {
			isValid = true;
			setTime(routePlatform, isArrival, -1);
		} else {
			isValid = text.length() == 8 && EditDepotScreen.applyDepartures(text, time -> setTime(routePlatform, isArrival, Math.floorMod(time - baseMillis, Depot.MILLISECONDS_PER_DAY) / 1000));
		}
		(isArrival ? textFieldsArrival : textFieldsDeparture)[row].setTextColor(isValid ? ARGB_FIELD_TEXT : ARGB_WARNING);
		updateSuggestions();
	}

	private void setTime(Route.RoutePlatform routePlatform, boolean isArrival, int seconds) {
		if (isArrival) {
			routePlatform.setArrival(tableIndex, seconds);
		} else {
			routePlatform.setDeparture(tableIndex, seconds);
		}
	}

	private void onSelectService(NameColorDataBase data, int index) {
		EditDepotScreen.applyDepartures(data.name, service -> baseMillis = service);
		bindRows();
	}

	private void onDeleteService(NameColorDataBase data, int index) {
		if (isLocked()) {
			return;
		}
		EditDepotScreen.applyDepartures(data.name, service -> getTable().services.remove((Integer) service));
		updateList();
	}

	private void updateList() {
		servicesList.setData(EditDepotScreen.getDepartureList(getTable().services), true, false, false, false, false, true);
		if (!getTable().services.contains(baseMillis)) {
			// The first train of the operating day, as times are saved in UTC and the smallest one can be any time of the local day
			baseMillis = getTable().services.stream().min(Comparator.comparingLong((Integer service) -> TimetableConfig.getTimeInDay(service))).orElse(TimetableConfig.getDayStartMillis());
		}
		bindRows();
	}

	private static String formatDuration(long millis) {
		final long seconds = millis / 1000;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
