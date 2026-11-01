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
import mtr.mappings.ScreenMapper;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Laid out like a Japanese driver's duty sheet, with one row for each stop and the times of the chosen service.
 */
public class TimetableScreen extends ScreenBase implements IGui, IPacket {

	private int scroll;
	private int rowCount;
	private int baseMillis;
	private boolean isBinding;
	private int columnPlatform;
	private int columnFastest;
	private int columnArrival;
	private int columnDeparture;
	private int columnPass;
	private int columnOvertake;

	private Route route;
	private final ScreenMapper previousScreen;
	private final Button buttonDirection;
	private final WidgetBetterCheckbox checkboxEnabled;
	private final WidgetBetterTextField textFieldMargin;
	private final WidgetBetterTextField textFieldDiagramColor;
	private final WidgetBetterTextField textFieldService;
	private final Button buttonAddService;
	private final Button buttonClear;
	private final DashboardList servicesList;
	private final WidgetBetterTextField[] textFieldsArrival = new WidgetBetterTextField[MAX_ROWS];
	private final WidgetBetterTextField[] textFieldsDeparture = new WidgetBetterTextField[MAX_ROWS];
	private final WidgetBetterCheckbox[] checkboxesPass = new WidgetBetterCheckbox[MAX_ROWS];
	private final WidgetBetterCheckbox[] checkboxesOvertake = new WidgetBetterCheckbox[MAX_ROWS];

	private final Component marginText = Text.translatable("gui.mtr.timetable_margin");
	private final Component diagramColorText = Text.translatable("gui.mtr.timetable_diagram_color");
	private final Component hintText = Text.translatable("gui.mtr.timetable_hint");

	private static final int MAX_ROWS = 32;
	private static final int LIST_WIDTH = 110;
	private static final int TIME_WIDTH = 58;
	private static final int FASTEST_WIDTH = 44;
	private static final int PLATFORM_WIDTH = 30;
	private static final int CHECKBOX_COLUMN_WIDTH = SQUARE_SIZE + TEXT_PADDING * 2;
	private static final int DIRECTION_WIDTH = 120;
	private static final int DIAGRAM_COLOR_FIELD_WIDTH = SQUARE_SIZE * 3;
	private static final int MARGIN_FIELD_WIDTH = SQUARE_SIZE * 2;
	private static final int ROWS_Y = SQUARE_SIZE * 2 + TEXT_PADDING;
	private static final int HINT_LINES = 3;
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
		textFieldDiagramColor = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.HEX, "", 6);
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
		servicesList = new DashboardList(this::onSelectService, null, null, null, null, this::onDeleteService, null, () -> "", text -> {
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
		rowCount = Math.max(1, Math.min(MAX_ROWS, (height - ROWS_Y - SQUARE_SIZE * 2 - (TEXT_HEIGHT + TEXT_PADDING) * HINT_LINES) / SQUARE_SIZE));
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
		final int directionWidth = hasDirections ? DIRECTION_WIDTH : 0;
		IDrawing.setPositionAndWidth(buttonDirection, rightX, 0, directionWidth);
		buttonDirection.visible = hasDirections;
		IDrawing.setPositionAndWidth(checkboxEnabled, rightX + directionWidth + TEXT_PADDING, 0, columnFastest - rightX - directionWidth);
		checkboxEnabled.setChecked(route.timetable.enabled);
		IDrawing.setPositionAndWidth(textFieldMargin, width - MARGIN_FIELD_WIDTH + TEXT_FIELD_PADDING / 2, TEXT_FIELD_PADDING / 2, MARGIN_FIELD_WIDTH - TEXT_FIELD_PADDING);
		textFieldMargin.setValue(String.valueOf(route.timetable.marginPercent));
		IDrawing.setPositionAndWidth(textFieldDiagramColor, rightX + font.width(diagramColorText) + TEXT_PADDING * 2 + TEXT_FIELD_PADDING / 2, getDiagramColorY() + TEXT_FIELD_PADDING / 2, DIAGRAM_COLOR_FIELD_WIDTH - TEXT_FIELD_PADDING);
		updateDiagramColorField();
		updateDirectionText();

		addDrawableChild(textFieldDiagramColor);
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

		updateList();
	}

	@Override
	public void tick() {
		servicesList.tick();
		textFieldService.tick();
		textFieldMargin.tick();
		textFieldDiagramColor.tick();
		for (int i = 0; i < rowCount; i++) {
			textFieldsArrival[i].tick();
			textFieldsDeparture[i].tick();
		}
		updateSuggestions();
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.vLine(matrices, LIST_WIDTH, -1, height, ARGB_WHITE_TRANSLUCENT);
			servicesList.render(matrices, font);

			final int rightX = LIST_WIDTH + TEXT_PADDING;
			final int headerY = SQUARE_SIZE + TEXT_PADDING;
			GuiCompat.draw(matrices, font, marginText, width - MARGIN_FIELD_WIDTH - font.width(marginText) - TEXT_PADDING, TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);
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

			GuiCompat.draw(matrices, font, diagramColorText, rightX, getDiagramColorY() + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);

			final String[] hintLines = (hasTimes ? hintText : Text.translatable("gui.mtr.timetable_no_path")).getString().split("\\|");
			for (int i = 0; i < hintLines.length; i++) {
				GuiCompat.draw(matrices, font, hintLines[i], rightX, height - (TEXT_HEIGHT + TEXT_PADDING) * (HINT_LINES - i), hasTimes ? ARGB_LIGHT_GRAY : ARGB_WARNING);
			}

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

	private void saveRoute() {
		route.timetable.enabled = checkboxEnabled.selected();
		try {
			route.timetable.marginPercent = Integer.parseInt(textFieldMargin.getValue());
		} catch (NumberFormatException e) {
			route.timetable.marginPercent = RouteTimetable.DEFAULT_MARGIN_PERCENT;
		}
		route.timetable.sanitize();
		try {
			route.diagramColor = textFieldDiagramColor.getValue().isEmpty() ? -1 : Integer.parseInt(textFieldDiagramColor.getValue(), 16);
		} catch (NumberFormatException e) {
			route.diagramColor = -1;
		}
		route.setTimetable(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_ROUTE, packet));
	}

	private int getDiagramColorY() {
		return height - SQUARE_SIZE - (TEXT_HEIGHT + TEXT_PADDING) * HINT_LINES;
	}

	private void updateDiagramColorField() {
		textFieldDiagramColor.setValue(route.diagramColor < 0 ? "" : String.format("%06X", route.diagramColor));
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
		updateDiagramColorField();
		PacketTrainDataGuiClient.requestTimetableTimesC2S(route.id);
		updateDirectionText();
		updateList();
	}

	private void updateDirectionText() {
		final String text = getDirectionText(route);
		buttonDirection.setMessage(Text.literal(font.plainSubstrByWidth(text, DIRECTION_WIDTH - TEXT_PADDING * 2)));
	}

	private void drawRow(PoseStack matrices, int index, RouteTimetable.StopPlan plan, int y, boolean hasTimes, boolean tooFast) {
		final Route.RoutePlatform routePlatform = route.platformIds.get(index);
		final Station station = ClientData.DATA_CACHE.platformIdToStation.get(routePlatform.platformId);
		final Platform platform = ClientData.DATA_CACHE.platformIdMap.get(routePlatform.platformId);
		final String stationName = IGui.textOrUntitled(station == null ? "" : IGui.formatStationName(station.name));
		final int rightX = LIST_WIDTH + TEXT_PADDING;

		GuiCompat.draw(matrices, font, font.plainSubstrByWidth(stationName, columnPlatform - rightX - TEXT_PADDING), rightX, y, ARGB_WHITE);
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
		final long[][] times = hasFastestTimes() ? ClientData.TIMETABLE_TIMES.get(route.id) : null;
		final List<RouteTimetable.StopPlan> plans = new ArrayList<>();
		long lastTime = 0;
		for (int i = 0; i < route.platformIds.size(); i++) {
			final Route.RoutePlatform routePlatform = route.platformIds.get(i);
			final boolean hasTime = times != null && times[i][0] >= 0;
			final long arrival = hasTime ? times[i][0] : lastTime;
			// The times from the server stay as they were until the path is refreshed
			final long departure = !hasTime ? lastTime : isPass(i) ? arrival : times[i][1];
			lastTime = departure;
			plans.add(new RouteTimetable.StopPlan(arrival, departure, routePlatform.getArrival(), routePlatform.getDeparture(), routePlatform.overtake));
		}
		final RouteTimetable timetable = new RouteTimetable();
		timetable.marginPercent = parseMargin();
		timetable.applyPlan(plans);
		return plans;
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
