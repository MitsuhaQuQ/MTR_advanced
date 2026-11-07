package mtr.screen;

import mtr.compat.GuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.client.PathFailureText;
import mtr.data.*;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.IPacket;
import mtr.packet.PacketTrainDataGuiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public class EditDepotScreen extends EditNameColorScreenBase<Depot> {

	private final int sliderX;
	private final int sliderWidthWithText;
	private final int rightPanelsX;
	private final boolean showScheduleControls;
	private final boolean showCruisingAltitude;
	private final Map<Long, Siding> sidingsInDepot;

	private final Button buttonUseRealTime;
	private final Button buttonReset;
	/**
	 * Whether the sliders are the minutes between trains in each real hour, as for a depot of trains (R2).
	 */
	private final boolean byHeadway;
	/**
	 * For a depot of trains, which runs by duties only (R2): makes the frequencies or the list of departures its routes'
	 * services, as the depot's trains were sent out by them.
	 */
	private final Button buttonFillTimetable;
	private final WidgetShorterSlider[] sliders = new WidgetShorterSlider[Depot.HOURS_IN_DAY];
	private final WidgetBetterTextField textFieldDeparture;
	private final Button buttonAddDeparture;

	private final Button buttonEditInstructions;
	private final Button buttonGenerateRoute;
	private final Button buttonClearTrains;
	private final WidgetBetterCheckbox checkboxRepeatIndefinitely;
	private final WidgetBetterCheckbox checkboxDeterministicPath;
	private final WidgetBetterCheckbox checkboxCycleRoutes;
	private final WidgetBetterCheckbox checkboxSpeedCurves;
	private final WidgetBetterTextField textFieldCruisingAltitude;
	/**
	 * The shortest time in seconds between two trains sent out by duties (decoupling document, step D).
	 */
	private final WidgetBetterTextField textFieldReleaseInterval;
	private final DashboardList departuresList;

	private final Component cruisingAltitudeText = Text.translatable("gui.mtr.cruising_altitude");
	private final Component releaseIntervalText = Text.translatable("gui.mtr.release_interval");

	private static final int PANELS_START = SQUARE_SIZE * 2 + TEXT_FIELD_PADDING;
	private static final int SLIDER_WIDTH = 64;
	private static final int MAX_TRAINS_PER_HOUR = 5;
	private static final int SECONDS_PER_MC_HOUR = Depot.TICKS_PER_HOUR / 20;

	public EditDepotScreen(Depot depot, TransportMode transportMode, DashboardScreen dashboardScreen) {
		super(depot, dashboardScreen, "gui.mtr.depot_name", "gui.mtr.depot_color");

		sidingsInDepot = ClientData.DATA_CACHE.requestDepotIdToSidings(depot.id);

		font = Minecraft.getInstance().font;
		sliderX = font.width(getTimeString(0)) + TEXT_PADDING * 2;
		sliderWidthWithText = SLIDER_WIDTH + TEXT_PADDING + font.width(getSliderString(0));
		rightPanelsX = sliderX + SLIDER_WIDTH + TEXT_PADDING * 2 + font.width(getSliderString(1));
		showScheduleControls = !transportMode.continuousMovement;
		byHeadway = Depot.supportsTimetable(transportMode);
		showCruisingAltitude = transportMode == TransportMode.AIRPLANE;

		buttonUseRealTime = UtilitiesClient.newButton(Text.translatable("gui.mtr.schedule_mode_real_time_off"), button -> {
			depot.useRealTime = !depot.useRealTime;
			toggleRealTime();
			saveData();
		});
		buttonFillTimetable = UtilitiesClient.newButton(Text.translatable("gui.mtr.depot_fill_timetable"), button -> {
			saveData();
			depot.setFrequenciesToMigrate(packet -> PacketTrainDataGuiClient.sendUpdate(IPacket.PACKET_UPDATE_DEPOT, packet));
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		buttonReset = UtilitiesClient.newButton(Text.translatable("gui.mtr.reset_sign"), button -> {
			for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
				sliders[i].setValue(0);
			}
			data.departures.clear();
			updateList();
			saveData();
		});

		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			final int currentIndex = i;
			// A depot of trains is given the minutes between trains in each hour of the real day (R2); others the game's frequencies
			sliders[currentIndex] = new WidgetShorterSlider(sliderX, SLIDER_WIDTH, byHeadway ? transitsim.planning.FrequencyTimetable.MAX_HEADWAY_MINUTES : MAX_TRAINS_PER_HOUR * 2, byHeadway ? EditDepotScreen::getHeadwayString : EditDepotScreen::getSliderString, value -> {
				for (int j = 0; j < Depot.HOURS_IN_DAY; j++) {
					if (j != currentIndex) {
						sliders[j].setValue(value);
					}
				}
			});
		}
		departuresList = new DashboardList(null, null, null, null, null, this::onDeleteDeparture, null, () -> "", text -> {
		});

		textFieldDeparture = new WidgetBetterTextField("[^\\d:+* ]", "07:10:00 + 10 * 00:03:00", 25);
		buttonAddDeparture = UtilitiesClient.newButton(Text.literal("+"), button -> {
			checkDeparture(textFieldDeparture.getValue(), true, false);
			saveData();
		});

		buttonEditInstructions = UtilitiesClient.newButton(Text.translatable("gui.mtr.edit_instructions"), button -> {
			if (minecraft != null) {
				saveData();
				final List<NameColorDataBase> routes = new ArrayList<>(ClientData.getFilteredDataSet(transportMode, ClientData.ROUTES));
				Collections.sort(routes);
				// A depot running by a timetable runs its routes in no order, each once; an old one runs them in turn
				final boolean byTimetable = runsByTimetable();
				UtilitiesClient.setScreen(minecraft, new DashboardListSelectorScreen(this, routes, data.routeIds, false, !byTimetable, !byTimetable));
			}
		});
		buttonGenerateRoute = UtilitiesClient.newButton(Text.translatable("gui.mtr.refresh_path"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		buttonClearTrains = UtilitiesClient.newButton(Text.translatable("gui.mtr.clear_vehicles"), button -> {
			sidingsInDepot.values().forEach(Siding::clearTrains);
			PacketTrainDataGuiClient.clearTrainsC2S(depot.id, sidingsInDepot.values());
		});
		checkboxRepeatIndefinitely = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.repeat_indefinitely"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		checkboxDeterministicPath = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.use_deterministic_path"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		checkboxCycleRoutes = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.cycle_routes"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		checkboxSpeedCurves = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.use_speed_curves"), button -> {
			saveData();
			depot.clientPathGenerationSuccessfulSegments = -1;
			PacketTrainDataGuiClient.generatePathC2S(depot.id);
		});
		textFieldCruisingAltitude = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.INTEGER, String.valueOf(Depot.DEFAULT_CRUISING_ALTITUDE), 5);
		textFieldReleaseInterval = new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "0", 4);
	}

	@Override
	protected void init() {
		setPositionsAndInit(rightPanelsX, width / 4 * 3, width);

		final int buttonWidth = (width - rightPanelsX) / 2;
		IDrawing.setPositionAndWidth(buttonEditInstructions, rightPanelsX, PANELS_START, buttonWidth * 2);
		IDrawing.setPositionAndWidth(buttonGenerateRoute, rightPanelsX, PANELS_START + SQUARE_SIZE, buttonWidth * (showScheduleControls ? 1 : 2));
		IDrawing.setPositionAndWidth(buttonClearTrains, rightPanelsX + buttonWidth, PANELS_START + SQUARE_SIZE, buttonWidth);
		IDrawing.setPositionAndWidth(checkboxDeterministicPath, rightPanelsX, PANELS_START + SQUARE_SIZE * 2, buttonWidth * 2);
		IDrawing.setPositionAndWidth(checkboxRepeatIndefinitely, rightPanelsX, PANELS_START + SQUARE_SIZE * (showDeterministicPath() ? 3 : 2) + (showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0), buttonWidth * 2);
		// Only one of the two shows, so they share a row: a loop repeats by itself, and other routes can be made to go round
		IDrawing.setPositionAndWidth(checkboxCycleRoutes, rightPanelsX, PANELS_START + SQUARE_SIZE * (showDeterministicPath() ? 3 : 2) + (showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0), buttonWidth * 2);
		checkboxRepeatIndefinitely.setChecked(data.repeatInfinitely);
		checkboxCycleRoutes.setChecked(data.cycleRoutes);
		checkboxDeterministicPath.setChecked(data.useDeterministicPath);
		IDrawing.setPositionAndWidth(checkboxSpeedCurves, rightPanelsX, getSpeedCurvesRowY(), buttonWidth * 2);
		checkboxSpeedCurves.setChecked(data.speedCurves);

		final int cruisingAltitudeTextWidth = font.width(cruisingAltitudeText) + TEXT_PADDING * 2;
		IDrawing.setPositionAndWidth(textFieldCruisingAltitude, rightPanelsX + Math.min(cruisingAltitudeTextWidth, buttonWidth * 2 - SQUARE_SIZE * 3) + TEXT_FIELD_PADDING / 2, PANELS_START + SQUARE_SIZE * 2 + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 3 - TEXT_FIELD_PADDING);
		textFieldCruisingAltitude.setValue(String.valueOf(data.cruisingAltitude));

		final int releaseIntervalTextWidth = font.width(releaseIntervalText) + TEXT_PADDING * 2;
		IDrawing.setPositionAndWidth(textFieldReleaseInterval, rightPanelsX + Math.min(releaseIntervalTextWidth, buttonWidth * 2 - SQUARE_SIZE * 3) + TEXT_FIELD_PADDING / 2, getReleaseIntervalRowY() + TEXT_FIELD_PADDING / 2, SQUARE_SIZE * 3 - TEXT_FIELD_PADDING);
		textFieldReleaseInterval.setValue(String.valueOf(data.releaseInterval));

		if (showScheduleControls) {
			for (WidgetShorterSlider slider : sliders) {
				addDrawableChild(slider);
			}
		}
		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			sliders[i].setValue(byHeadway ? data.getHeadway(i) : data.getFrequency(i));
		}

		final int leftWidth = rightPanelsX - 1;
		IDrawing.setPositionAndWidth(buttonUseRealTime, 0, 0, leftWidth - SQUARE_SIZE * 3);
		IDrawing.setPositionAndWidth(buttonReset, leftWidth - SQUARE_SIZE * 3, 0, SQUARE_SIZE * 3);
		IDrawing.setPositionAndWidth(buttonFillTimetable, 0, SQUARE_SIZE, leftWidth);

		departuresList.y = SQUARE_SIZE;
		departuresList.height = height - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING;
		departuresList.width = leftWidth;
		departuresList.init(this::addDrawableChild);

		IDrawing.setPositionAndWidth(textFieldDeparture, TEXT_FIELD_PADDING / 2, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, leftWidth - TEXT_FIELD_PADDING - SQUARE_SIZE);
		addDrawableChild(textFieldDeparture);
		textFieldDeparture.setResponder(text -> buttonAddDeparture.active = checkDeparture(text, false, false));
		IDrawing.setPositionAndWidth(buttonAddDeparture, leftWidth - SQUARE_SIZE, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, SQUARE_SIZE);
		addDrawableChild(buttonAddDeparture);
		buttonAddDeparture.active = false;

		addDrawableChild(buttonEditInstructions);
		addDrawableChild(buttonGenerateRoute);
		if (showScheduleControls) {
			addDrawableChild(buttonUseRealTime);
			addDrawableChild(buttonReset);
			// A depot of trains sends them out by duties only (R2), so the frequencies only fill the timetable
			if (Depot.supportsTimetable(data.transportMode)) {
				addDrawableChild(buttonFillTimetable);
			}
			addDrawableChild(buttonClearTrains);
			addDrawableChild(checkboxRepeatIndefinitely);
			addDrawableChild(checkboxCycleRoutes);
			if (showSpeedCurves()) {
				// A depot of trains always runs by speed curves (R4)
				if (!Depot.supportsTimetable(data.transportMode)) {
					addDrawableChild(checkboxSpeedCurves);
				}
				addDrawableChild(textFieldReleaseInterval);
			}
		}
		if (showDeterministicPath()) {
			addDrawableChild(checkboxDeterministicPath);
		}
		if (showCruisingAltitude) {
			addDrawableChild(textFieldCruisingAltitude);
		}

		toggleRealTime();
	}

	@Override
	public void tick() {
		super.tick();
		buttonGenerateRoute.active = data.clientPathGenerationSuccessfulSegments >= 0;
		departuresList.tick();
		textFieldDeparture.tick();
		textFieldCruisingAltitude.tick();
		textFieldReleaseInterval.tick();

		for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
			if (byHeadway) {
				data.setHeadway(sliders[i].getIntValue(), i);
			} else {
				data.setFrequency(sliders[i].getIntValue(), i);
			}
		}

		if (data.routeIds.isEmpty()) {
			checkboxRepeatIndefinitely.visible = false;
			checkboxCycleRoutes.visible = false;
		} else if (runsByTimetable()) {
			// Running by duties, the routes have no order: staying on the line only lets trains wait there between services
			checkboxRepeatIndefinitely.visible = false;
			checkboxCycleRoutes.visible = true;
			checkboxCycleRoutes.setMessage(Text.translatable("gui.mtr.stay_on_line"));
		} else {
			checkboxCycleRoutes.setMessage(Text.translatable("gui.mtr.cycle_routes"));
			final Route firstRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(0));
			final Route lastRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(data.routeIds.size() - 1));
			checkboxRepeatIndefinitely.visible = firstRoute != null && lastRoute != null && !firstRoute.platformIds.isEmpty() && !lastRoute.platformIds.isEmpty() && firstRoute.getFirstPlatformId() == lastRoute.getLastPlatformId();
			checkboxCycleRoutes.visible = !checkboxRepeatIndefinitely.visible;
		}
		UtilitiesClient.setWidgetY(checkboxSpeedCurves, getSpeedCurvesRowY());
		UtilitiesClient.setWidgetY(textFieldReleaseInterval, getReleaseIntervalRowY() + TEXT_FIELD_PADDING / 2);
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.vLine(matrices, rightPanelsX - 1, -1, height, ARGB_WHITE_TRANSLUCENT);
			renderTextFields(matrices);

			if (showScheduleControls && data.useRealTime) {
				departuresList.render(matrices, font);
			}

			final int lineHeight = Math.min(SQUARE_SIZE, (height - SQUARE_SIZE * 2) / Depot.HOURS_IN_DAY);
			for (int i = 0; i < Depot.HOURS_IN_DAY; i++) {
				if (showScheduleControls && !data.useRealTime) {
					GuiCompat.drawString(matrices, font, getTimeString(i), TEXT_PADDING, SQUARE_SIZE * 2 + lineHeight * i + (int) ((lineHeight - TEXT_HEIGHT) / 2F), ARGB_WHITE);
				}
				UtilitiesClient.setWidgetY(sliders[i], SQUARE_SIZE * 2 + lineHeight * i);
				sliders[i].setHeight(lineHeight);
			}

			super.render(matrices, mouseX, mouseY, delta);

			final int yStartRightPane = getSpeedCurvesRowY() + (showSpeedCurves() ? SQUARE_SIZE * 2 + TEXT_FIELD_PADDING : 0) + TEXT_PADDING;
			if (showSpeedCurves()) {
				GuiCompat.draw(matrices, font, releaseIntervalText, rightPanelsX + TEXT_PADDING, getReleaseIntervalRowY() + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);
			}
			if (showCruisingAltitude) {
				GuiCompat.draw(matrices, font, cruisingAltitudeText, rightPanelsX + TEXT_PADDING, PANELS_START + SQUARE_SIZE * 2 + TEXT_PADDING + TEXT_FIELD_PADDING / 2F, ARGB_WHITE);
			}
			GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.sidings_in_depot", sidingsInDepot.size()), rightPanelsX + TEXT_PADDING, yStartRightPane, ARGB_WHITE);

			final Component text;
			data.generateTempDepartures(Minecraft.getInstance().level);
			final int nextDepartureMillis = data.getMillisUntilDeploy(1);
			if (data.findTimetable(ClientData.DATA_CACHE) != null) {
				text = Text.translatable("gui.mtr.timetable_depot_active");
			} else if (nextDepartureMillis >= 0) {
				final long hour = TimeUnit.MILLISECONDS.toHours(nextDepartureMillis);
				final long minute = TimeUnit.MILLISECONDS.toMinutes(nextDepartureMillis) % 60;
				final long second = TimeUnit.MILLISECONDS.toSeconds(nextDepartureMillis) % 60;
				text = Text.translatable("gui.mtr.next_departure", String.format("%2s:%2s:%2s", hour, minute, second).replace(' ', '0'));
			} else {
				text = Text.translatable("gui.mtr.next_departure_none");
			}
			GuiCompat.draw(matrices, font, text, rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE, ARGB_WHITE);

			final String[] stringSplit = getSuccessfulSegmentsText().getString().split("\\|");
			for (int i = 0; i < stringSplit.length; i++) {
				GuiCompat.draw(matrices, font, stringSplit[i], rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE * 2 + (TEXT_HEIGHT + TEXT_PADDING) * i, ARGB_WHITE);
			}
			final String pathStatus = getPathStatusText();
			if (!pathStatus.isEmpty()) {
				GuiCompat.draw(matrices, font, Text.literal(pathStatus), rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE * 2 + (TEXT_HEIGHT + TEXT_PADDING) * stringSplit.length, ARGB_LIGHT_GRAY);
			}
			// The lines planned with this depot are short of trains (LineDuties)
			final Depot.Shortfall shortfall = data.lineShortfall;
			if (shortfall != null && shortfall.uncoveredPerDay > 0 && !data.duties.diagramMode) {
				final String shortfallText = (shortfall.needed < 0 ? Text.translatable("gui.mtr.line_shortfall_unsettled", shortfall.available, shortfall.uncoveredPerDay) : Text.translatable("gui.mtr.line_shortfall", shortfall.needed, shortfall.available, shortfall.uncoveredPerDay)).getString() + (shortfall.suggestedSiding.isEmpty() ? "" : Text.translatable("gui.mtr.line_shortfall_suggest", shortfall.suggestedSiding).getString());
				GuiCompat.draw(matrices, font, Text.literal(font.plainSubstrByWidth(shortfallText, width - rightPanelsX - TEXT_PADDING * 2)), rightPanelsX + TEXT_PADDING, yStartRightPane + SQUARE_SIZE * 2 + (TEXT_HEIGHT + TEXT_PADDING) * (stringSplit.length + (pathStatus.isEmpty() ? 0 : 1)), 0xFFFF8080);
			}

			if (showScheduleControls && !data.useRealTime) {
				GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.game_time"), sliderX / 2, SQUARE_SIZE + TEXT_PADDING, ARGB_LIGHT_GRAY);
				GuiCompat.drawCenteredString(matrices, font, Text.translatable("gui.mtr.vehicles_per_hour"), sliderX + sliderWidthWithText / 2, SQUARE_SIZE + TEXT_PADDING, ARGB_LIGHT_GRAY);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		departuresList.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		departuresList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	/**
	 * @return whether the depot runs by duties, set or from a timetable of one of its routes, where its routes have no order
	 */
	private boolean runsByTimetable() {
		return data.runsByDutiesWith(data.getRoutes(ClientData.DATA_CACHE));
	}

	private String getPathStatusText() {
		final String text = PathFailureText.get(data.clientPathFailure);
		return text.isEmpty() && ClientData.STALE_DEPOTS.contains(data.id) ? Text.translatable("gui.mtr.path_stale").getString() : text;
	}

	/**
	 * Cable cars and lifts move steadily, so speed curves don't apply to them.
	 */
	private boolean showSpeedCurves() {
		return showScheduleControls && !data.transportMode.continuousMovement;
	}

	private int getReleaseIntervalRowY() {
		return getSpeedCurvesRowY() + SQUARE_SIZE;
	}

	private int getSpeedCurvesRowY() {
		return PANELS_START + SQUARE_SIZE * (2 + (showDeterministicPath() ? 1 : 0) + (checkboxRepeatIndefinitely.visible || checkboxCycleRoutes.visible ? 1 : 0)) + (showCruisingAltitude ? SQUARE_SIZE + TEXT_FIELD_PADDING : 0);
	}

	/**
	 * Train depots always find their paths the deterministic way, as route paths, fastest times and duties do (2026-11-07
	 * review, L1): the choice is left only to the other kinds that still have it.
	 */
	private boolean showDeterministicPath() {
		return showScheduleControls && !showCruisingAltitude && !Depot.supportsTimetable(data.transportMode);
	}

	@Override
	protected void saveData() {
		super.saveData();
		data.repeatInfinitely = checkboxRepeatIndefinitely.visible && checkboxRepeatIndefinitely.selected();
		data.cycleRoutes = checkboxCycleRoutes.visible && checkboxCycleRoutes.selected();
		data.useDeterministicPath = !showDeterministicPath() || checkboxDeterministicPath.selected();
		data.speedCurves = showSpeedCurves() ? checkboxSpeedCurves.selected() : data.speedCurves;
		try {
			data.cruisingAltitude = Integer.parseInt(textFieldCruisingAltitude.getValue());
		} catch (Exception e) {
			e.printStackTrace();
			data.cruisingAltitude = Depot.DEFAULT_CRUISING_ALTITUDE;
		}
		try {
			data.releaseInterval = Math.max(0, Math.min(Depot.MAX_RELEASE_INTERVAL, Integer.parseInt(textFieldReleaseInterval.getValue())));
		} catch (Exception ignored) {
			data.releaseInterval = 0;
		}
		data.setData(packet -> PacketTrainDataGuiClient.sendUpdate(PACKET_UPDATE_DEPOT, packet));
	}

	private void toggleRealTime() {
		for (final WidgetShorterSlider slider : sliders) {
			slider.visible = !data.useRealTime;
		}
		departuresList.x = data.useRealTime ? 0 : width;
		UtilitiesClient.setWidgetX(textFieldDeparture, data.useRealTime ? TEXT_FIELD_PADDING / 2 : width);
		buttonAddDeparture.visible = data.useRealTime;
		buttonUseRealTime.setMessage(Text.translatable(data.useRealTime ? "gui.mtr.schedule_mode_real_time_on" : "gui.mtr.schedule_mode_real_time_off"));
		updateList();
	}

	private void onDeleteDeparture(NameColorDataBase data, int index) {
		checkDeparture(data.name, false, true);
		saveData();
	}

	private void updateList() {
		departuresList.setData(getDepartureList(data.departures), false, false, false, false, false, true);
		data.generateTempDepartures(Minecraft.getInstance().level);
	}

	private boolean checkDeparture(String text, boolean addToList, boolean removeFromList) {
		final boolean isValid = applyDepartures(text, departure -> {
			if (addToList) {
				if (!data.departures.contains(departure)) {
					data.departures.add(departure);
				}
			} else if (removeFromList) {
				data.departures.remove((Integer) departure);
			}
		});
		if (isValid && (addToList || removeFromList)) {
			updateList();
		}
		return isValid;
	}

	public static List<NameColorDataBase> getDepartureList(List<Integer> departures) {
		final List<NameColorDataBase> departureData = new ArrayList<>();
		final long offset = System.currentTimeMillis() / Depot.MILLISECONDS_PER_DAY * Depot.MILLISECONDS_PER_DAY;
		// In the order trains run them, so last trains after midnight come at the end
		departures.stream().sorted(Comparator.comparingLong((Integer service) -> TimetableConfig.getTimeInDay(service))).map(departure -> {
			final Calendar calendar = Calendar.getInstance(TimetableConfig.getTimeZone());
			calendar.setTimeInMillis(departure + offset);
			return calendar;
		}).forEach(calendar -> {
			final int hour = calendar.get(Calendar.HOUR_OF_DAY);
			final int minute = calendar.get(Calendar.MINUTE);
			final int second = calendar.get(Calendar.SECOND);
			departureData.add(new DataConverter(String.format("%2s:%2s:%2s", hour, minute, second).replace(' ', '0'), 0));
		});
		return departureData;
	}

	/**
	 * @return a time of day saved in UTC as text in the configured time zone
	 */
	public static String formatTime(long millis) {
		final Calendar calendar = Calendar.getInstance(TimetableConfig.getTimeZone());
		calendar.setTimeInMillis(System.currentTimeMillis() / Depot.MILLISECONDS_PER_DAY * Depot.MILLISECONDS_PER_DAY + Math.floorMod(millis, (long) Depot.MILLISECONDS_PER_DAY));
		return String.format("%02d:%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND));
	}

	/**
	 * Reads text like {@code 07:10:00} or {@code 07:10:00 + 10 * 00:03:00}, giving each time to the consumer.
	 *
	 * @return false if the text is not valid
	 */
	public static boolean applyDepartures(String text, IntConsumer consumer) {
		try {
			final String[] departureSplit = text.replace(" ", "").split("\\+");
			final String[] timeSplit1 = departureSplit[0].split(":");
			final Calendar calendar = Calendar.getInstance(TimetableConfig.getTimeZone());
			calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeSplit1[0]) % 24);
			calendar.set(Calendar.MINUTE, Integer.parseInt(timeSplit1[1]) % 60);
			calendar.set(Calendar.SECOND, Integer.parseInt(timeSplit1[2]) % 60);
			calendar.set(Calendar.MILLISECOND, 0);
			final int departure = (int) (calendar.getTimeInMillis() % Depot.MILLISECONDS_PER_DAY);
			final int multiple;
			final int interval;

			if (departureSplit.length > 1) {
				final String[] intervalSplit = departureSplit[1].split("\\*");
				multiple = Integer.parseInt(intervalSplit[0]) + 1;
				final String[] timeSplit2 = intervalSplit[1].split(":");
				interval = (Integer.parseInt(timeSplit2[0]) * 3600 + Integer.parseInt(timeSplit2[1]) * 60 + Integer.parseInt(timeSplit2[2])) * 1000;
			} else {
				multiple = 1;
				interval = 0;
			}

			for (int i = 0; i < multiple; i++) {
				consumer.accept((departure + i * interval) % Depot.MILLISECONDS_PER_DAY);
			}

			return true;
		} catch (Exception ignored) {
		}

		return false;
	}

	private Component getSuccessfulSegmentsText() {
		final int successfulSegments = data.clientPathGenerationSuccessfulSegments;

		if (successfulSegments < 0) {
			return Text.translatable("gui.mtr.generating_path");
		} else if (successfulSegments == 0) {
			return Text.translatable("gui.mtr.path_not_generated");
		} else {
			final List<String> stationNames = new ArrayList<>();
			final List<String> routeNames = new ArrayList<>();
			final String depotName = IGui.textOrUntitled(IGui.formatStationName(data.name));

			if (successfulSegments == 1) {
				RailwayData.useRoutesAndStationsFromIndex(0, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
					stationNames.add(IGui.textOrUntitled(thisStation == null ? "" : IGui.formatStationName(thisStation.name)));
					routeNames.add(IGui.textOrUntitled(thisRoute == null ? "" : IGui.formatStationName(thisRoute.name)));
				});
				stationNames.add("-");
				routeNames.add("-");

				return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), depotName, stationNames.get(0));
			} else {
				int sum = 0;
				for (int i = 0; i < data.routeIds.size(); i++) {
					final Route thisRoute = ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i));
					final Route nextRoute = i < data.routeIds.size() - 1 ? ClientData.DATA_CACHE.routeIdMap.get(data.routeIds.get(i + 1)) : null;
					if (thisRoute != null) {
						sum += thisRoute.platformIds.size();
						if (!thisRoute.platformIds.isEmpty() && nextRoute != null && !nextRoute.platformIds.isEmpty() && thisRoute.getLastPlatformId() == nextRoute.getFirstPlatformId()) {
							sum--;
						}
					}
				}

				if (successfulSegments >= sum + 2) {
					return Text.translatable("gui.mtr.path_found");
				} else {
					RailwayData.useRoutesAndStationsFromIndex(successfulSegments - 2, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
						stationNames.add(IGui.textOrUntitled(thisStation == null ? "" : IGui.formatStationName(thisStation.name)));
						if (nextStation == null) {
							RailwayData.useRoutesAndStationsFromIndex(successfulSegments - 1, data.routeIds, ClientData.DATA_CACHE, (currentStationIndex1, thisRoute1, nextRoute1, thisStation1, nextStation1, lastStation1) -> stationNames.add(IGui.textOrUntitled(thisStation1 == null ? "" : IGui.formatStationName(thisStation1.name))));
						} else {
							stationNames.add(IGui.textOrUntitled(IGui.formatStationName(nextStation.name)));
						}
						routeNames.add(IGui.textOrUntitled(IGui.formatStationName(thisRoute.name)));
					});
					stationNames.add("-");
					stationNames.add("-");
					routeNames.add("-");

					if (successfulSegments < sum + 1) {
						return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), stationNames.get(0), stationNames.get(1));
					} else {
						return Text.translatable("gui.mtr.path_not_found_between", routeNames.get(0), stationNames.get(0), depotName);
					}
				}
			}
		}
	}

	private static String getSliderString(int value) {
		final String headwayText;
		if (value == 0) {
			headwayText = "";
		} else {
			headwayText = " (" + RailwayData.round((float) Depot.TRAIN_FREQUENCY_MULTIPLIER * SECONDS_PER_MC_HOUR / value, 1) + Text.translatable("gui.mtr.s").getString() + ")";
		}
		return value / (float) Depot.TRAIN_FREQUENCY_MULTIPLIER + Text.translatable("gui.mtr.tph").getString() + headwayText;
	}

	/**
	 * @return the minutes between trains in an hour of the real day, for a depot of trains (R2)
	 */
	private static String getHeadwayString(int minutes) {
		return minutes == 0 ? Text.translatable("gui.mtr.depot_headway_none").getString() : Text.translatable("gui.mtr.depot_headway", minutes).getString();
	}

	private static String getTimeString(int hour) {
		final String hourString = StringUtils.leftPad(String.valueOf(hour), 2, "0");
		return String.format("%s:00-%s:59", hourString, hourString);
	}
}
