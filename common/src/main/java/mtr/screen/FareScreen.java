package mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.compat.GuiCompat;
import mtr.compat.ScreenBase;
import mtr.data.FareData;
import mtr.data.IGui;
import mtr.data.NetworkSettings;
import mtr.data.Route;
import mtr.data.Station;
import mtr.mappings.Text;
import mtr.mappings.UtilitiesClient;
import mtr.packet.FarePackets;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import transitsim.fare.FareLevels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Fares by distance (design document 6.13, 0.68.0), from the dashboard. Three tabs:
 * <ul>
 * <li>fares: the fare rules (0.73.0), made, named and removed here (a removed rule's lines and stations go to the rule
 * chosen), each with a reference line whose length its tiers go up to, its formula, longest distance, cap and overtime
 * fine, and its tiers on a bar of distance, whose bounds can be dragged, added and removed and whose fares can be set for
 * each tier;</li>
 * <li>lines: the rule of each line, all its directions and service types together (0.73.0);</li>
 * <li>stations: the rules a station belongs to, whose gates are shared by them if there are several.</li>
 * </ul>
 * Everything is edited on a copy and sent with "save"; the server checks the player may edit.
 */
public class FareScreen extends ScreenBase implements IGui {

	private static final int LIST_WIDTH = 150;
	private static final int ROW_HEIGHT = 12;
	private static final int FIELD_WIDTH = 50;
	private static final long SNAP_BLOCKS = 100;

	private enum Tab {FARES, ROUTES, STATIONS}

	private Tab tab = Tab.FARES;
	private final NetworkSettings edited = new NetworkSettings();
	/**
	 * Group key to {name, longest distance in blocks}, as the server has them.
	 */
	private Map<String, Object[]> groups = new LinkedHashMap<>();
	private final List<Object> listItems = new ArrayList<>();
	private int listScroll;
	private Object selected;

	private final Button buttonTabFares;
	private final Button buttonTabRoutes;
	private final Button buttonTabStations;
	private final Button buttonSave;
	private final Button buttonCancel;

	private final WidgetBetterTextField fieldStartFare = numberField();
	private final WidgetBetterTextField fieldUnitFare = numberField();
	private final WidgetBetterTextField fieldInitial = decimalField();
	private final WidgetBetterTextField fieldUnit = decimalField();
	private final WidgetBetterTextField fieldMax = decimalField();
	private final WidgetBetterTextField fieldCap = numberField();
	private final WidgetBetterTextField fieldOvertimeHours = decimalField();
	private final WidgetBetterTextField fieldOvertimeFine = numberField();
	private final WidgetBetterCheckbox checkboxOvertime;
	private final Button buttonRecalculate;
	private final Button buttonKeepTiers;
	private final WidgetBetterTextField fieldTierPrice = numberField();
	private final Button buttonAddBound;
	private final Button buttonRemoveBound;
	private final WidgetBetterTextField fieldRuleName = new WidgetBetterTextField("", 64);
	private final Button buttonNewRule;
	private final Button buttonReference;
	private final Button buttonDeleteTarget;
	private final Button buttonDelete;
	/** The rule a rule's lines and stations go to when it is removed */
	private String deleteTarget;
	/** The lines by key ({@link FareData#getAutoGroupKey}) with their names */
	private final Map<String, String> lines = FareData.getLines(ClientData.DATA_CACHE);

	/** A line in the lines tab: all its routes go by one rule */
	private static final class LineItem {
		private final String key;
		private final String name;

		private LineItem(String key, String name) {
			this.key = key;
			this.name = name;
		}
	}
	private final List<AbstractWidget> dynamicWidgets = new ArrayList<>();

	/**
	 * Whether the server sent the longest distances: until then the formula's tiers can't be made, so the bar can't be
	 * changed (changing it would keep tiers made up to no distance).
	 */
	private boolean groupsReceived;
	private boolean pendingChoice;
	private boolean loadingFields;
	private int selectedTier = -1;
	private int draggingBound = -1;

	public FareScreen() {
		super(Text.literal(""));
		edited.copyFrom(ClientData.DATA_CACHE.networkSettings);
		FareData.getAllGroups(ClientData.DATA_CACHE, edited).forEach((group, name) -> groups.put(group, new Object[]{name, 0L}));

		buttonTabFares = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_tab_fares"), button -> setTab(Tab.FARES));
		buttonTabRoutes = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_tab_routes"), button -> setTab(Tab.ROUTES));
		buttonTabStations = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_tab_stations"), button -> setTab(Tab.STATIONS));
		buttonSave = UtilitiesClient.newButton(Text.translatable("gui.done"), button -> {
			FarePackets.setC2S(edited);
			onClose();
		});
		buttonCancel = UtilitiesClient.newButton(Text.translatable("gui.cancel"), button -> onClose());
		checkboxOvertime = new WidgetBetterCheckbox(0, 0, 0, SQUARE_SIZE, Text.translatable("gui.mtr.fare_overtime"), checked -> withLevels(levels -> levels.overtime = checked));
		buttonRecalculate = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_recalculate"), button -> withLevels(levels -> {
			levels.manualTiers = false;
			pendingChoice = false;
			selectedTier = -1;
		}));
		buttonKeepTiers = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_keep_tiers"), button -> pendingChoice = false);
		buttonAddBound = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_add_bound"), button -> addBound());
		buttonRemoveBound = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_remove_bound"), button -> removeBound());
		buttonNewRule = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_new_rule"), button -> newRule());
		buttonReference = UtilitiesClient.newButton(Text.literal(""), button -> nextReference());
		buttonDeleteTarget = UtilitiesClient.newButton(Text.literal(""), button -> {
			final List<String> others = otherRules();
			if (!others.isEmpty()) {
				deleteTarget = others.get((others.indexOf(deleteTarget) + 1) % others.size());
			}
			loadFields();
		});
		buttonDelete = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_delete_rule"), button -> deleteRule());
		fieldRuleName.setResponder(text -> {
			if (!loadingFields && selected instanceof String && edited.fareRules.containsKey(selected)) {
				final NetworkSettings.FareRule rule = edited.fareRules.get(selected);
				edited.fareRules.put((String) selected, new NetworkSettings.FareRule(text.trim(), rule.referenceLine));
				final Object[] value = groups.get(selected);
				if (value != null) {
					value[0] = text.trim().isEmpty() ? selected : text.trim();
				}
			}
		});

		fieldStartFare.setResponder(text -> formulaChanged(levels -> levels.startFare = parseInt(text, levels.startFare)));
		fieldUnitFare.setResponder(text -> formulaChanged(levels -> levels.unitFare = parseInt(text, levels.unitFare)));
		fieldInitial.setResponder(text -> formulaChanged(levels -> levels.initialBlocks = parseBlocks(text, levels.initialBlocks)));
		fieldUnit.setResponder(text -> formulaChanged(levels -> levels.unitBlocks = Math.max(1, parseBlocks(text, levels.unitBlocks))));
		fieldMax.setResponder(text -> withLevels(levels -> levels.maxBlocks = text.trim().isEmpty() ? 0 : parseBlocks(text, levels.maxBlocks)));
		fieldCap.setResponder(text -> withLevels(levels -> levels.cap = text.trim().isEmpty() ? -1 : parseInt(text, levels.cap)));
		fieldOvertimeHours.setResponder(text -> withLevels(levels -> levels.overtimeMinutes = (int) Math.round(parseDouble(text, levels.overtimeMinutes / 60D) * 60)));
		fieldOvertimeFine.setResponder(text -> withLevels(levels -> levels.overtimeFine = parseInt(text, levels.overtimeFine)));
		fieldTierPrice.setResponder(text -> {
			if (loadingFields || selectedTier < 0) {
				return;
			}
			withLevels(levels -> {
				final FareLevels.Tiers tiers = levels.getTiers(getLongest());
				if (selectedTier < tiers.prices.length) {
					final int[] prices = tiers.prices.clone();
					prices[selectedTier] = parseInt(text, prices[selectedTier]);
					levels.setTiers(tiers.bounds, prices);
				}
			});
		});
		FarePackets.requestGroupsC2S();
	}

	/**
	 * The groups as the server has them, with the longest distance of each.
	 */
	public void setGroups(Map<String, Object[]> serverGroups) {
		// Only the longest distances: the rules and their names are as edited here
		serverGroups.forEach((group, value) -> {
			final Object[] own = groups.get(group);
			if (own != null) {
				own[1] = value[1];
			}
		});
		groupsReceived = true;
		updateList();
	}

	@Override
	protected void init() {
		super.init();
		IDrawing.setPositionAndWidth(buttonTabFares, 0, 0, LIST_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonTabRoutes, LIST_WIDTH / 3, 0, LIST_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonTabStations, LIST_WIDTH * 2 / 3, 0, LIST_WIDTH / 3);
		IDrawing.setPositionAndWidth(buttonSave, 0, height - SQUARE_SIZE, LIST_WIDTH / 2);
		IDrawing.setPositionAndWidth(buttonCancel, LIST_WIDTH / 2, height - SQUARE_SIZE, LIST_WIDTH / 2);

		final int x0 = LIST_WIDTH + SQUARE_SIZE;
		final int half = (width - x0 - SQUARE_SIZE) / 2;
		final int labelWidth = half - FIELD_WIDTH - TEXT_FIELD_PADDING;
		placeField(fieldStartFare, x0 + labelWidth, row(1));
		placeField(fieldUnitFare, x0 + half + labelWidth, row(1));
		placeField(fieldInitial, x0 + labelWidth, row(2));
		placeField(fieldUnit, x0 + half + labelWidth, row(2));
		placeField(fieldMax, x0 + labelWidth, row(3));
		placeField(fieldCap, x0 + half + labelWidth, row(3));
		IDrawing.setPositionAndWidth(checkboxOvertime, x0, row(4), labelWidth);
		placeField(fieldOvertimeHours, x0 + labelWidth, row(4));
		placeField(fieldOvertimeFine, x0 + half + labelWidth, row(4));
		IDrawing.setPositionAndWidth(buttonRecalculate, x0, row(5), half);
		IDrawing.setPositionAndWidth(buttonKeepTiers, x0 + half, row(5), half);
		placeField(fieldTierPrice, x0 + labelWidth, row(8));
		IDrawing.setPositionAndWidth(buttonAddBound, x0 + half, row(8), half / 2);
		IDrawing.setPositionAndWidth(buttonRemoveBound, x0 + half + half / 2, row(8), half / 2);
		placeField(fieldRuleName, x0 + labelWidth - FIELD_WIDTH, row(10), FIELD_WIDTH * 2);
		IDrawing.setPositionAndWidth(buttonReference, x0 + half, row(10), half);
		IDrawing.setPositionAndWidth(buttonDeleteTarget, x0, row(11), half + half / 2);
		IDrawing.setPositionAndWidth(buttonDelete, x0 + half + half / 2, row(11), half / 2);
		IDrawing.setPositionAndWidth(buttonNewRule, 0, height - SQUARE_SIZE * 2, LIST_WIDTH);

		for (final AbstractWidget widget : new AbstractWidget[]{buttonTabFares, buttonTabRoutes, buttonTabStations, buttonSave, buttonCancel, fieldStartFare, fieldUnitFare, fieldInitial, fieldUnit, fieldMax, fieldCap, checkboxOvertime, fieldOvertimeHours, fieldOvertimeFine, buttonRecalculate, buttonKeepTiers, fieldTierPrice, buttonAddBound, buttonRemoveBound, fieldRuleName, buttonReference, buttonDeleteTarget, buttonDelete, buttonNewRule}) {
			addDrawableChild(widget);
		}
		updateList();
	}

	private static void placeField(WidgetBetterTextField field, int x, int y) {
		placeField(field, x, y, FIELD_WIDTH);
	}

	private static void placeField(WidgetBetterTextField field, int x, int y, int fieldWidth) {
		IDrawing.setPositionAndWidth(field, x + TEXT_FIELD_PADDING / 2, y + TEXT_FIELD_PADDING / 2, fieldWidth - TEXT_FIELD_PADDING);
	}

	private static int row(int row) {
		return SQUARE_SIZE * row;
	}

	private void setTab(Tab newTab) {
		tab = newTab;
		selected = null;
		listScroll = 0;
		updateList();
	}

	private void updateList() {
		listItems.clear();
		switch (tab) {
			case FARES:
				listItems.addAll(groups.keySet());
				break;
			case ROUTES:
				// A line once, not each of its directions and service types (0.73.0)
				lines.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(line -> listItems.add(new LineItem(line.getKey(), line.getValue())));
				break;
			case STATIONS:
				listItems.addAll(ClientData.DATA_CACHE.stationIdMap.values().stream().sorted(Comparator.comparing(station -> station.name)).collect(Collectors.toList()));
				break;
		}
		if (selected == null && !listItems.isEmpty()) {
			selected = listItems.get(0);
		}
		loadFields();
		updateDynamicWidgets();
	}

	private void loadFields() {
		final boolean fares = tab == Tab.FARES && selected instanceof String;
		for (final AbstractWidget widget : new AbstractWidget[]{fieldStartFare, fieldUnitFare, fieldInitial, fieldUnit, fieldMax, fieldCap, checkboxOvertime, fieldOvertimeHours, fieldOvertimeFine, buttonRecalculate, fieldTierPrice, buttonAddBound, buttonRemoveBound}) {
			widget.visible = fares;
		}
		buttonKeepTiers.visible = fares && pendingChoice;
		for (final AbstractWidget widget : new AbstractWidget[]{fieldRuleName, buttonReference, buttonDeleteTarget, buttonDelete}) {
			widget.visible = fares;
		}
		buttonNewRule.visible = tab == Tab.FARES;
		buttonNewRule.active = edited.fareRules.size() < NetworkSettings.MAX_FARE_GROUPS;
		buttonTabFares.active = tab != Tab.FARES;
		buttonTabRoutes.active = tab != Tab.ROUTES;
		buttonTabStations.active = tab != Tab.STATIONS;
		if (!fares) {
			return;
		}
		loadingFields = true;
		final FareLevels levels = getLevels();
		fieldStartFare.setValue(String.valueOf(levels.startFare));
		fieldUnitFare.setValue(String.valueOf(levels.unitFare));
		fieldInitial.setValue(kilometres(levels.initialBlocks));
		fieldUnit.setValue(kilometres(levels.unitBlocks));
		fieldMax.setValue(levels.maxBlocks > 0 ? kilometres(levels.maxBlocks) : "");
		fieldCap.setValue(levels.cap >= 0 ? String.valueOf(levels.cap) : "");
		checkboxOvertime.setChecked(levels.overtime);
		fieldOvertimeHours.setValue(String.format(Locale.ENGLISH, "%.1f", levels.overtimeMinutes / 60D));
		fieldOvertimeFine.setValue(String.valueOf(levels.overtimeFine));
		final NetworkSettings.FareRule rule = edited.fareRules.get(selected);
		fieldRuleName.setValue(rule == null ? "" : rule.name);
		final String reference = rule == null ? "" : rule.referenceLine;
		buttonReference.setMessage(Text.translatable("gui.mtr.fare_reference_line", reference.isEmpty() ? Text.translatable("gui.mtr.fare_reference_none").getString() : lines.getOrDefault(reference, reference)));
		final List<String> others = otherRules();
		if (!others.contains(deleteTarget)) {
			deleteTarget = others.isEmpty() ? null : others.get(0);
		}
		buttonDeleteTarget.setMessage(Text.translatable("gui.mtr.fare_delete_into", deleteTarget == null ? "-" : groupName(deleteTarget)));
		buttonDeleteTarget.active = others.size() > 1;
		buttonDelete.active = deleteTarget != null;
		loadTierPrice();
		loadingFields = false;
	}

	private void loadTierPrice() {
		final boolean wasLoading = loadingFields;
		loadingFields = true;
		final FareLevels.Tiers tiers = getLevels().getTiers(getLongest());
		fieldTierPrice.setValue(selectedTier >= 0 && selectedTier < tiers.prices.length ? String.valueOf(tiers.prices[selectedTier]) : "");
		fieldTierPrice.active = groupsReceived && selectedTier >= 0;
		buttonAddBound.active = groupsReceived && selectedTier >= 0 && tiers.bounds.length < FareLevels.MAX_TIERS - 1;
		buttonRemoveBound.active = groupsReceived && selectedTier >= 0 && selectedTier < tiers.bounds.length;
		loadingFields = wasLoading;
	}

	/**
	 * The buttons that change with what is chosen: the groups a route may be put in, the groups a station may belong to.
	 */
	private void updateDynamicWidgets() {
		dynamicWidgets.forEach(this::removeWidget);
		dynamicWidgets.clear();
		final int x0 = LIST_WIDTH + SQUARE_SIZE;
		final int columnWidth = (width - x0 - SQUARE_SIZE) / 2;
		final List<String> groupKeys = new ArrayList<>(groups.keySet());
		final int maxRows = Math.max(1, (height - SQUARE_SIZE * 5) / SQUARE_SIZE);
		if (tab == Tab.ROUTES && selected instanceof LineItem) {
			final LineItem line = (LineItem) selected;
			final String current = lineRule(line.key);
			for (int i = 0; i < groupKeys.size() && i < maxRows * 2; i++) {
				final String group = groupKeys.get(i);
				final Button button = UtilitiesClient.newButton(Text.literal(groupName(group)), clicked -> {
					routesOf(line.key).forEach(route -> edited.fareRouteGroups.put(route.id, group));
					updateDynamicWidgets();
				});
				button.active = !group.equals(current);
				IDrawing.setPositionAndWidth(button, x0 + (i % 2) * columnWidth, row(2 + i / 2), columnWidth);
				dynamicWidgets.add(button);
			}
		} else if (tab == Tab.STATIONS && selected instanceof Station) {
			final Station station = (Station) selected;
			final Set<String> routeGroups = FareData.getRouteGroups(ClientData.DATA_CACHE, edited, station.id);
			final List<String> set = edited.fareStationGroups.get(station.id);
			final Button auto = UtilitiesClient.newButton(Text.translatable("gui.mtr.fare_station_auto", routeGroups.stream().map(this::groupName).collect(Collectors.joining(", "))), button -> {
				edited.fareStationGroups.remove(station.id);
				updateDynamicWidgets();
			});
			auto.active = set != null;
			IDrawing.setPositionAndWidth(auto, x0, row(2), columnWidth * 2);
			dynamicWidgets.add(auto);
			final Set<String> current = set == null ? routeGroups : new TreeSet<>(set);
			for (int i = 0; i < groupKeys.size() && i < maxRows * 2; i++) {
				final String group = groupKeys.get(i);
				final WidgetBetterCheckbox checkbox = new WidgetBetterCheckbox(x0 + (i % 2) * columnWidth, row(3 + i / 2), columnWidth, SQUARE_SIZE, Text.literal(groupName(group)), checked -> {
					final List<String> setNow = edited.fareStationGroups.get(station.id);
					final Set<String> now = new TreeSet<>(setNow == null ? FareData.getRouteGroups(ClientData.DATA_CACHE, edited, station.id) : setNow);
					if (checked) {
						now.add(group);
					} else {
						now.remove(group);
					}
					if (now.isEmpty()) {
						edited.fareStationGroups.remove(station.id);
					} else {
						edited.fareStationGroups.put(station.id, new ArrayList<>(now));
					}
					updateDynamicWidgets();
				});
				checkbox.setChecked(current.contains(group));
				dynamicWidgets.add(checkbox);
			}
		}
		dynamicWidgets.forEach(this::addDrawableChild);
		loadFields();
	}

	private static List<Route> routesOf(String lineKey) {
		return ClientData.DATA_CACHE.routeIdMap.values().stream().filter(route -> !route.isHidden && route.platformIds.size() >= 2 && FareData.getAutoGroupKey(route).equals(lineKey)).collect(Collectors.toList());
	}

	/**
	 * @return the rule a line goes by, as its first route does
	 */
	private String lineRule(String lineKey) {
		final List<Route> routes = routesOf(lineKey);
		return routes.isEmpty() ? edited.getDefaultFareRule() : FareData.getGroupKey(routes.get(0), ClientData.DATA_CACHE, edited);
	}

	private List<String> otherRules() {
		return edited.fareRules.keySet().stream().filter(rule -> !rule.equals(selected)).collect(Collectors.toList());
	}

	/**
	 * A new rule with the default fares, named "规则 N" to be renamed
	 */
	private void newRule() {
		int number = edited.fareRules.size() + 1;
		while (edited.fareRules.containsKey("rule:" + number)) {
			number++;
		}
		final String id = "rule:" + number;
		final String name = Text.translatable("gui.mtr.fare_rule_new_name", number).getString();
		edited.fareRules.put(id, new NetworkSettings.FareRule(name, ""));
		groups.put(id, new Object[]{name, 0L});
		selected = id;
		selectedTier = -1;
		pendingChoice = false;
		updateList();
	}

	/**
	 * The next line as the chosen rule's reference, after the last none
	 */
	private void nextReference() {
		if (!(selected instanceof String) || !edited.fareRules.containsKey(selected)) {
			return;
		}
		final NetworkSettings.FareRule rule = edited.fareRules.get(selected);
		final List<String> keys = new ArrayList<>();
		keys.add("");
		lines.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(line -> keys.add(line.getKey()));
		final String next = keys.get((keys.indexOf(rule.referenceLine) + 1) % keys.size());
		edited.fareRules.put((String) selected, new NetworkSettings.FareRule(rule.name, next));
		loadFields();
	}

	/**
	 * Removes the chosen rule: the lines going by it and the stations set to it go by the rule chosen instead; there is
	 * always one left
	 */
	private void deleteRule() {
		if (!(selected instanceof String) || deleteTarget == null || edited.fareRules.size() < 2) {
			return;
		}
		final String removed = (String) selected;
		ClientData.DATA_CACHE.routeIdMap.values().forEach(route -> {
			if (FareData.getGroupKey(route, ClientData.DATA_CACHE, edited).equals(removed)) {
				edited.fareRouteGroups.put(route.id, deleteTarget);
			}
		});
		edited.fareStationGroups.values().forEach(rules -> {
			if (rules.remove(removed) && !rules.contains(deleteTarget)) {
				rules.add(deleteTarget);
			}
		});
		edited.fareRules.remove(removed);
		edited.keepFareRulesKnown();
		groups.remove(removed);
		selected = deleteTarget;
		selectedTier = -1;
		pendingChoice = false;
		updateList();
	}

	private String groupName(String group) {
		final Object[] value = groups.get(group);
		return value == null ? group : (String) value[0];
	}

	/**
	 * @return the chosen group's fares to look at: the defaults if it has none, which are not kept, so a group only looked
	 * at is not saved
	 */
	private FareLevels getLevels() {
		final FareLevels levels = selected instanceof String ? edited.fareLevels.get(selected) : null;
		return levels == null ? new FareLevels() : levels;
	}

	/**
	 * @return the chosen group's fares to change, kept from now on
	 */
	private FareLevels editLevels() {
		return edited.fareLevels.computeIfAbsent((String) selected, key -> new FareLevels());
	}

	private long getLongest() {
		final Object[] value = selected instanceof String ? groups.get(selected) : null;
		return value == null ? 0 : (Long) value[1];
	}

	private void withLevels(Consumer<FareLevels> change) {
		if (!loadingFields && selected instanceof String) {
			change.accept(editLevels());
			loadTierPrice();
		}
	}

	/**
	 * A formula changed after the tiers were set by hand: the tiers stay as they are until the player chooses to work
	 * them out again or keep them.
	 */
	private void formulaChanged(Consumer<FareLevels> change) {
		withLevels(levels -> {
			change.accept(levels);
			if (levels.manualTiers) {
				pendingChoice = true;
				buttonKeepTiers.visible = true;
			}
		});
	}

	private void addBound() {
		withLevels(levels -> {
			final FareLevels.Tiers tiers = levels.getTiers(getLongest());
			final long start = selectedTier == 0 ? 0 : tiers.bounds[selectedTier - 1];
			final long end = selectedTier < tiers.bounds.length ? tiers.bounds[selectedTier] : getBarBlocks(tiers);
			final long bound = snap((start + end) / 2);
			if (bound <= start || bound >= end) {
				return;
			}
			final long[] bounds = Arrays.copyOf(tiers.bounds, tiers.bounds.length + 1);
			bounds[bounds.length - 1] = bound;
			Arrays.sort(bounds);
			final int[] prices = new int[tiers.prices.length + 1];
			for (int i = 0, j = 0; i < prices.length; i++) {
				prices[i] = tiers.prices[Math.min(j, tiers.prices.length - 1)];
				if (i != selectedTier) {
					j++;
				}
			}
			levels.setTiers(bounds, prices);
		});
	}

	private void removeBound() {
		withLevels(levels -> {
			final FareLevels.Tiers tiers = levels.getTiers(getLongest());
			if (selectedTier < 0 || selectedTier >= tiers.bounds.length) {
				return;
			}
			final long[] bounds = new long[tiers.bounds.length - 1];
			final int[] prices = new int[tiers.prices.length - 1];
			for (int i = 0, j = 0; i < tiers.bounds.length; i++) {
				if (i != selectedTier) {
					bounds[j++] = tiers.bounds[i];
				}
			}
			for (int i = 0, j = 0; i < tiers.prices.length; i++) {
				if (i != selectedTier + 1) {
					prices[j++] = tiers.prices[i];
				}
			}
			levels.setTiers(bounds, prices);
		});
	}

	@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		try {
			renderBackground(matrices);
			GuiCompat.fill(matrices, 0, SQUARE_SIZE, LIST_WIDTH, height - SQUARE_SIZE, ARGB_BACKGROUND);
			final int listTop = SQUARE_SIZE + TEXT_PADDING / 2;
			final int rows = (height - SQUARE_SIZE * 2 - TEXT_PADDING) / ROW_HEIGHT;
			for (int i = 0; i < rows && i + listScroll < listItems.size(); i++) {
				final Object item = listItems.get(i + listScroll);
				final int y = listTop + i * ROW_HEIGHT;
				if (item == selected) {
					GuiCompat.fill(matrices, 0, y - 2, LIST_WIDTH, y + ROW_HEIGHT - 2, 0x40FFFFFF);
				}
				GuiCompat.draw(matrices, font, font.plainSubstrByWidth(listText(item), LIST_WIDTH - TEXT_PADDING * 2), TEXT_PADDING, y, ARGB_WHITE);
			}

			final int x0 = LIST_WIDTH + SQUARE_SIZE;
			final int half = (width - x0 - SQUARE_SIZE) / 2;
			if (tab == Tab.FARES && selected instanceof String) {
				final long longest = getLongest();
				GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.fare_group_title", groupName((String) selected), kilometres(longest)), x0, TEXT_PADDING, ARGB_WHITE);
				drawLabel(matrices, "gui.mtr.fare_start_fare", x0, row(1));
				drawLabel(matrices, "gui.mtr.fare_unit_fare", x0 + half, row(1));
				drawLabel(matrices, "gui.mtr.fare_initial_km", x0, row(2));
				drawLabel(matrices, "gui.mtr.fare_unit_km", x0 + half, row(2));
				drawLabel(matrices, "gui.mtr.fare_max_km", x0, row(3));
				drawLabel(matrices, "gui.mtr.fare_cap", x0 + half, row(3));
				drawLabel(matrices, "gui.mtr.fare_overtime_fine", x0 + half, row(4));
				if (pendingChoice) {
					GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.fare_formula_changed"), x0, row(6) - TEXT_HEIGHT - 2, 0xFFFFDD55);
				}
				drawTiers(matrices, x0, width - SQUARE_SIZE);
				drawLabel(matrices, "gui.mtr.fare_tier_price", x0, row(8));
				drawLabel(matrices, "gui.mtr.fare_rule_name", x0, row(10));
			} else if (tab == Tab.ROUTES && selected instanceof LineItem) {
				final LineItem line = (LineItem) selected;
				GuiCompat.draw(matrices, font, Text.translatable("gui.mtr.fare_route_title", line.name, groupName(lineRule(line.key))), x0, TEXT_PADDING, ARGB_WHITE);
			} else if (tab == Tab.STATIONS && selected instanceof Station) {
				final Station station = (Station) selected;
				final Set<String> stationGroups = new TreeSet<>(edited.fareStationGroups.containsKey(station.id) ? edited.fareStationGroups.get(station.id) : FareData.getRouteGroups(ClientData.DATA_CACHE, edited, station.id));
				GuiCompat.draw(matrices, font, Text.translatable(stationGroups.size() > 1 ? "gui.mtr.fare_station_shared" : "gui.mtr.fare_station_title", IGui.formatStationName(station.name)), x0, TEXT_PADDING, ARGB_WHITE);
			}
			super.render(matrices, mouseX, mouseY, delta);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void drawLabel(PoseStack matrices, String key, int x, int y) {
		GuiCompat.draw(matrices, font, Text.translatable(key), x, y + TEXT_PADDING, ARGB_WHITE);
	}

	private String listText(Object item) {
		if (item instanceof String) {
			return groupName((String) item);
		} else if (item instanceof LineItem) {
			return ((LineItem) item).name + " → " + groupName(lineRule(((LineItem) item).key));
		} else if (item instanceof Station) {
			return IGui.formatStationName(((Station) item).name);
		}
		return "";
	}

	private long getBarBlocks(FareLevels.Tiers tiers) {
		final FareLevels levels = getLevels();
		final long longest = levels.maxBlocks > 0 ? levels.maxBlocks : getLongest();
		final long lastBound = tiers.bounds.length == 0 ? 0 : tiers.bounds[tiers.bounds.length - 1];
		return Math.max(Math.max(longest, lastBound + lastBound / 10), FareLevels.BLOCKS_PER_KILOMETRE);
	}

	private int barX(long blocks, long barBlocks, int left, int right) {
		return left + (int) Math.round((right - left) * (double) blocks / barBlocks);
	}

	/**
	 * The tiers on a bar from 0 to the longest distance: a handle at each bound with its distance above, each tier's fare
	 * in it, written once over tiers next to each other with the same fare.
	 */
	private void drawTiers(PoseStack matrices, int left, int right) {
		final FareLevels.Tiers tiers = getLevels().getTiers(getLongest());
		final long barBlocks = getBarBlocks(tiers);
		final int top = row(6) + TEXT_HEIGHT + 4;
		final int bottom = top + SQUARE_SIZE - 4;
		GuiCompat.fill(matrices, left, top, right, bottom, 0xFF303030);
		for (int i = 0; i < tiers.prices.length; i++) {
			final int start = barX(i == 0 ? 0 : tiers.bounds[i - 1], barBlocks, left, right);
			final int end = i < tiers.bounds.length ? barX(tiers.bounds[i], barBlocks, left, right) : right;
			if (i == selectedTier) {
				GuiCompat.fill(matrices, start, top, end, bottom, 0xFF505070);
			}
			final boolean samePrice = i > 0 && tiers.prices[i] == tiers.prices[i - 1];
			final String price = String.valueOf(tiers.prices[i]);
			if (!samePrice && end - start > font.width(price) + 2) {
				GuiCompat.draw(matrices, font, price, (start + end - font.width(price)) / 2F, top + (bottom - top - TEXT_HEIGHT) / 2F, ARGB_WHITE);
			}
		}
		int lastLabelEnd = Integer.MIN_VALUE;
		for (int i = 0; i < tiers.bounds.length; i++) {
			final int x = barX(tiers.bounds[i], barBlocks, left, right);
			final boolean faint = i + 1 < tiers.prices.length && tiers.prices[i] == tiers.prices[i + 1];
			GuiCompat.fill(matrices, x - 1, top - 2, x + 1, bottom + 2, faint ? 0xFF707070 : 0xFFFFDD55);
			final String label = kilometres(tiers.bounds[i]);
			final int labelX = x - font.width(label) / 2;
			if (labelX > lastLabelEnd + 2) {
				GuiCompat.draw(matrices, font, label, labelX, top - TEXT_HEIGHT - 3, ARGB_LIGHT_GRAY);
				lastLabelEnd = labelX + font.width(label);
			}
		}
		final String end = kilometres(barBlocks) + " km";
		GuiCompat.draw(matrices, font, end, right - font.width(end), bottom + 3, ARGB_LIGHT_GRAY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		final int listTop = SQUARE_SIZE + TEXT_PADDING / 2;
		if (mouseX < LIST_WIDTH && mouseY >= listTop && mouseY < height - SQUARE_SIZE) {
			final int index = (int) ((mouseY - listTop + 2) / ROW_HEIGHT) + listScroll;
			if (index >= 0 && index < listItems.size()) {
				selected = listItems.get(index);
				selectedTier = -1;
				pendingChoice = false;
				updateDynamicWidgets();
				return true;
			}
		}
		if (tab == Tab.FARES && selected instanceof String && groupsReceived) {
			final int left = LIST_WIDTH + SQUARE_SIZE;
			final int right = width - SQUARE_SIZE;
			final int top = row(6) + TEXT_HEIGHT + 4;
			final int bottom = top + SQUARE_SIZE - 4;
			if (mouseX >= left && mouseX <= right && mouseY >= top - 3 && mouseY <= bottom + 3) {
				final FareLevels.Tiers tiers = getLevels().getTiers(getLongest());
				final long barBlocks = getBarBlocks(tiers);
				for (int i = 0; i < tiers.bounds.length; i++) {
					if (Math.abs(mouseX - barX(tiers.bounds[i], barBlocks, left, right)) <= 3) {
						draggingBound = i;
						return true;
					}
				}
				final long blocks = (long) ((mouseX - left) / (right - left) * barBlocks);
				int tier = 0;
				while (tier < tiers.bounds.length && blocks > tiers.bounds[tier]) {
					tier++;
				}
				selectedTier = tier;
				loadTierPrice();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (draggingBound >= 0) {
			final int left = LIST_WIDTH + SQUARE_SIZE;
			final int right = width - SQUARE_SIZE;
			withLevels(levels -> {
				final FareLevels.Tiers tiers = levels.getTiers(getLongest());
				if (draggingBound >= tiers.bounds.length) {
					return;
				}
				final long barBlocks = getBarBlocks(tiers);
				final long low = draggingBound == 0 ? SNAP_BLOCKS : tiers.bounds[draggingBound - 1] + SNAP_BLOCKS;
				final long high = draggingBound + 1 < tiers.bounds.length ? tiers.bounds[draggingBound + 1] - SNAP_BLOCKS : barBlocks;
				final long[] bounds = tiers.bounds.clone();
				bounds[draggingBound] = Math.max(low, Math.min(high, snap((long) ((mouseX - left) / (right - left) * barBlocks))));
				levels.setTiers(bounds, tiers.prices);
			});
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingBound = -1;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < LIST_WIDTH) {
			final int rows = (height - SQUARE_SIZE * 2 - TEXT_PADDING) / ROW_HEIGHT;
			listScroll = Math.max(0, Math.min(Math.max(0, listItems.size() - rows), listScroll - (int) Math.signum(amount) * 3));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static WidgetBetterTextField numberField() {
		// No minus sign: fares, caps and fines are never negative (the server keeps them so too)
		return new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.POSITIVE_INTEGER, "", 7);
	}

	private static WidgetBetterTextField decimalField() {
		return new WidgetBetterTextField(WidgetBetterTextField.TextFieldFilter.DECIMAL, "", 8);
	}

	private static long snap(long blocks) {
		return Math.round(blocks / (double) SNAP_BLOCKS) * SNAP_BLOCKS;
	}

	private static String kilometres(long blocks) {
		return String.format(Locale.ENGLISH, "%.1f", blocks / (double) FareLevels.BLOCKS_PER_KILOMETRE);
	}

	private static int parseInt(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double parseDouble(String text, double fallback) {
		try {
			return Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static long parseBlocks(String text, long fallback) {
		final double kilometres = parseDouble(text, -1);
		return kilometres < 0 ? fallback : Math.round(kilometres * FareLevels.BLOCKS_PER_KILOMETRE);
	}
}
