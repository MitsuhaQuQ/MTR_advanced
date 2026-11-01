package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.Depot;
import mtr.data.IGui;
import mtr.data.NameColorDataBase;
import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.RouteTimetable;
import mtr.data.Station;
import mtr.data.TimetableConflicts;
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

	private Route route;
	private final ScreenMapper previousScreen;
	private final Button buttonDirection;
	private final WidgetBetterCheckbox checkboxEnabled;
	private final WidgetBetterTextField textFieldMargin;
	private final WidgetBetterTextField textFieldService;
	private final Button buttonAddService;
	private final Button buttonClear;
	private final Button buttonCheckConflicts;
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
	private static final int ROWS_Y = SQUARE_SIZE * 2 + TEXT_PADDING;
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
				if (route.timetable.services.size() < RouteTimetable.MAX_SERVICES && !route.timetable.services.contains(service)) {
					route.timetable.services.add(service);
				}
			});
			updateList();
		});
		buttonClear = UtilitiesClient.newButton(Text.translatable("gui.mtr.reset_sign"), button -> {
			route.timetable.services.clear();
			updateList();
		});
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
						// A train running through can't keep to a departure time or wait
						routePlatform.setDeparture(-1);
						routePlatform.overtake = false;
					}
					bindRows();
				}
			});
			checkboxesOvertake[i] = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.literal(""), checked -> {
				final int index = scroll + row;
				if (!isBinding && index < route.platformIds.size()) {
					route.platformIds.get(index).overtake = checked;
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
		textFieldService.setResponder(text -> buttonAddService.active = EditDepotScreen.applyDepartures(text, service -> {
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
		textFieldMargin.setValue(String.valueOf(route.timetable.marginPercent));
		updateDirectionText();

		addDrawableChild(buttonDirection);
		addDrawableChild(buttonClear);
		addDrawableChild(textFieldService);
		addDrawableChild(buttonAddService);
		addDrawableChild(checkboxEnabled);
		addDrawableChild(textFieldMargin);

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

		updateList();
		requestConflicts();
	}

	@Override
	public void tick() {
		servicesList.tick();
		textFieldService.tick();
		textFieldMargin.tick();
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
			final int headerY = SQUARE_SIZE + TEXT_PADDING;
			GuiCompat.draw(matrices, font, marginText, rightX, getMarginY() + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);
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

			final List<FormattedCharSequence> hintLines = getHintLines(hasTimes ? hintText : Text.translatable("gui.mtr.timetable_no_path"));
			for (int i = 0; i < hintLines.size(); i++) {
				GuiCompat.draw(matrices, font, hintLines.get(i), rightX, height - (TEXT_HEIGHT + 1) * (hintLines.size() - i), hasTimes ? ARGB_LIGHT_GRAY : ARGB_WARNING);
			}
			drawConflictText(matrices, rightX, getMarginY() + SQUARE_SIZE + TEXT_PADDING / 2);

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
		return height - SQUARE_SIZE - (TEXT_HEIGHT + 1) * (getHintLines(hintText).size() + 1) - TEXT_PADDING;
	}

	private void saveRoute() {
		route.timetable.enabled = checkboxEnabled.selected();
		try {
			route.timetable.marginPercent = Integer.parseInt(textFieldMargin.getValue());
		} catch (NumberFormatException e) {
			route.timetable.marginPercent = RouteTimetable.DEFAULT_MARGIN_PERCENT;
		}
		route.timetable.sanitize();
		route.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
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
		checkboxEnabled.setChecked(route.timetable.enabled);
		textFieldMargin.setValue(String.valueOf(route.timetable.marginPercent));
		PacketTrainDataGuiClient.requestTimetableTimesC2S(route.id);
		updateDirectionText();
		updateList();
		requestConflicts();
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
		return RouteTimetable.getStopPlans(route.platformIds, hasFastestTimes() ? ClientData.TIMETABLE_TIMES.get(route.id) : null, parseMargin());
	}

	private void requestConflicts() {
		checkedKey = getCheckKey();
		PacketTrainDataGuiClient.requestTimetableConflictsC2S(route.id);
	}

	/**
	 * Changes with anything that moves the times, so a result that no longer applies doesn't stay on screen.
	 */
	private int getCheckKey() {
		int key = Objects.hash(route.id, parseMargin(), route.timetable.services);
		for (final Route.RoutePlatform routePlatform : route.platformIds) {
			key = key * 31 + Objects.hash(routePlatform.getArrival(), routePlatform.getDeparture(), routePlatform.pass);
		}
		return key;
	}

	private TimetableConflicts.Result getConflictResult() {
		return checkedKey == getCheckKey() ? ClientData.TIMETABLE_CONFLICTS.get(route.id) : null;
	}

	private List<TimetableConflicts.Conflict> getServiceConflicts(TimetableConflicts.Result result) {
		return result.conflicts.stream().filter(conflict -> conflict.service == baseMillis || conflict.otherRouteId == route.id && conflict.otherService == baseMillis).collect(Collectors.toList());
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
				conflictServices.add(EditDepotScreen.formatTime(conflict.service));
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
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(conflict.platformId);
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(conflict.platformId);
		final Route otherRoute = ClientData.DATA_CACHE.routeIdMap.get(conflict.otherRouteId);
		final int otherService = conflict.otherRouteId == route.id && conflict.otherService == baseMillis ? conflict.service : conflict.otherService;
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

			textFieldsArrival[i].setValue(!hasStop || routePlatform.getArrival() < 0 ? "" : EditDepotScreen.formatTime(baseMillis + routePlatform.getArrival() * 1000L));
			textFieldsDeparture[i].setValue(!hasStop || routePlatform.getDeparture() < 0 ? "" : EditDepotScreen.formatTime(baseMillis + routePlatform.getDeparture() * 1000L));
			textFieldsArrival[i].setTextColor(ARGB_FIELD_TEXT);
			textFieldsDeparture[i].setTextColor(ARGB_FIELD_TEXT);
			checkboxesPass[i].setChecked(hasStop && routePlatform.pass);
			checkboxesOvertake[i].setChecked(hasStop && routePlatform.overtake);
		}
		isBinding = false;
		updateSuggestions();
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

	private static void setTime(Route.RoutePlatform routePlatform, boolean isArrival, int seconds) {
		if (isArrival) {
			routePlatform.setArrival(seconds);
		} else {
			routePlatform.setDeparture(seconds);
		}
	}

	private void onSelectService(NameColorDataBase data, int index) {
		EditDepotScreen.applyDepartures(data.name, service -> baseMillis = service);
		bindRows();
	}

	private void onDeleteService(NameColorDataBase data, int index) {
		EditDepotScreen.applyDepartures(data.name, service -> route.timetable.services.remove((Integer) service));
		updateList();
	}

	private void updateList() {
		servicesList.setData(EditDepotScreen.getDepartureList(route.timetable.services), true, false, false, false, false, true);
		if (!route.timetable.services.contains(baseMillis)) {
			baseMillis = route.timetable.services.isEmpty() ? 0 : route.timetable.services.stream().min(Integer::compareTo).orElse(0);
		}
		bindRows();
	}

	private static String formatDuration(long millis) {
		final long seconds = millis / 1000;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
