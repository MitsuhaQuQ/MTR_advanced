package mtr.data;

import mtr.advanced.PacketBinary;
import transitsim.plan.PlanRoute;
import transitsim.plan.PlanStop;
import transitsim.plan.RouteTimetable;
import transitsim.plan.ServiceExceptions;
import io.netty.buffer.Unpooled;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A route: the platforms a service stops at in order, with its type, colour, name, waypoints and the timetable
 * settings of the route (dwell times per station, stations passed without stopping, reversing sidings at its end).
 * It implements the component's {@code PlanRoute}, so timetables are made from it directly.
 */
public final class Route extends NameColorDataBase implements IGui, PlanRoute {

	private static final int MAX_PLATFORMS = 1024;
	public static final int MAX_WAYPOINTS = 64;
	public static final int MIN_STOP_DWELL = 17;
	private static final int MAX_DWELL_OVERRIDE = 7200;
	private static final double DOOR_CYCLE_SECONDS = 8.4;

	public RouteType routeType;
	public boolean isLightRailRoute;
	public boolean isHidden;
	public boolean disableNextStationAnnouncements;
	/**
	 * A connecting line (联络线): an ordinary route, which can carry passengers through to another line, but whose rails
	 * between its first and last platform other routes' paths don't run over (decoupling document 5.4, L1).
	 */
	public boolean connectingLine;
	/**
	 * The rails of this connecting line that other routes may still run over, each as its two nodes in either order: track
	 * it shares with another line, such as a crossover at a station throat, released by hand in the editor.
	 */
	public final List<long[]> connectingReleased = new ArrayList<>();
	/**
	 * The other platforms of the last stop's station trains can turn back at (站前折返), in order; see
	 * {@code PlanRoute.getTerminalPlatforms}.
	 */
	public final List<Long> terminalPlatforms = new ArrayList<>();
	/**
	 * The platforms of the first stop's station this route would rather start from, best first (种别优先).
	 */
	public final List<Long> startPlatforms = new ArrayList<>();
	public static final int MAX_TURN_PLATFORMS = 16;
	public static final int MAX_CONNECTING_RELEASED = 512;
	public CircularState circularState;
	public String lightRailRouteNumber;
	public final List<RoutePlatform> platformIds;
	/**
	 * A path drawn by hand, which replaces the path search for this route when not empty.
	 */
	private List<PathData> pathData = new ArrayList<>();
	public final RouteTimetable timetable = new RouteTimetable();
	/**
	 * The line this route belongs to, or empty to work it out from the name.
	 */
	public String lineName = "";
	/**
	 * This route's own colour, for a future running diagram. {@link #color} is the colour of its whole line, which is what
	 * signs and everything else show, including addons reading that field.
	 */
	private int ownColor = -1;
	/**
	 * The type of service, such as local or express, or empty to work it out from the name.
	 */
	public String serviceType = "";
	/**
	 * The route's speed limit in km/h, or 0 for none: its trains run between its stops no faster than this nor than the
	 * rails allow, which the editor keeps it no higher than.
	 */
	public int speedLimit;
	/**
	 * Set up in the advanced editor, so the game's own timetable controls only show it. See {@link mtr.client.editor.EditorServer}.
	 */
	public boolean editorManaged;

	private static final String KEY_PLATFORM_IDS = "platform_ids";
	private static final String KEY_CUSTOM_DESTINATIONS = "custom_destinations";
	private static final String KEY_WAYPOINTS = "waypoints";
	private static final String KEY_PASS_STATIONS = "pass_stations";
	private static final String KEY_DWELL_OVERRIDES = "dwell_overrides";
	private static final String KEY_ROUTE_TYPE = "route_type";
	private static final String KEY_IS_LIGHT_RAIL_ROUTE = "is_light_rail_route";
	private static final String KEY_LIGHT_RAIL_ROUTE_NUMBER = "light_rail_route_number";
	private static final String KEY_IS_ROUTE_HIDDEN = "is_route_hidden";
	private static final String KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS = "disable_next_station_announcements";
	private static final String KEY_CIRCULAR_STATE = "circular_state";
	private static final String KEY_CUSTOM_PATH = "ante_path";
	private static final String KEY_TIMETABLE_ENABLED = "timetable_enabled";
	private static final String KEY_TIMETABLE_MARGIN = "timetable_margin";
	/**
	 * The least performance a vehicle running a table must manage: acceleration, deceleration, highest speed.
	 */
	private static final String KEY_TIMETABLE_MIN_PERFORMANCE = "timetable_min_performance";
	private static final String KEY_TIMETABLE_SERVICES = "timetable_services";
	private static final String KEY_TIMETABLE_NAME = "timetable_name";
	private static final String KEY_TIMETABLE_WEEKDAYS = "timetable_weekdays";
	private static final String KEY_TIMETABLE_TABLES = "timetable_tables";
	private static final String KEY_ENTRY_PLATFORM = "entry_platform";
	private static final String KEY_TIMETABLE_LINKS = "timetable_links";
	private static final long TIMETABLE_LINKS_MARKER = -5;
	private static final String KEY_TIMETABLE_RETURNS = "timetable_returns";
	private static final String KEY_TIMETABLE_SUSPENDED = "timetable_suspended";
	/**
	 * Services joined or left part of the way by a train from or to a depot (MTM_Logic 0.8.0): service, kind (0 deploy, 1
	 * return), stop and depot, four longs each; older saves have none.
	 */
	private static final String KEY_TIMETABLE_ORDERS = "timetable_orders";
	private static final String KEY_TIMETABLE_EXCEPTIONS = "timetable_exceptions";
	private static final String KEY_PLAN_ARRIVALS = "plan_arrivals";
	private static final String KEY_PLAN_DEPARTURES = "plan_departures";
	private static final String KEY_OVERTAKES = "overtakes";
	private static final String KEY_ALLOW_LONGER = "allow_longer";
	private static final String KEY_LINE_NAME = "line_name";
	private static final String KEY_DIAGRAM_COLOR = "diagram_color";
	private static final String KEY_SERVICE_TYPE = "service_type";
	private static final String KEY_EDITOR_MANAGED = "editor_managed";
	private static final String KEY_CONNECTING_LINE = "connecting_line";
	private static final String KEY_CONNECTING_RELEASED = "connecting_released";
	private static final String KEY_TERMINAL_PLATFORMS = "terminal_platforms";
	private static final String KEY_START_PLATFORMS = "start_platforms";
	private static final String KEY_SPEED_LIMIT = "speed_limit";
	private static final String DUPLICATE_SUFFIX = "_duplicate";
	private static final Pattern DUPLICATE_PATTERN = Pattern.compile("(" + DUPLICATE_SUFFIX + ")+$");

	public Route(TransportMode transportMode) {
		this(0, transportMode);
	}

	public Route(long id, TransportMode transportMode) {
		super(id, transportMode);
		platformIds = new ArrayList<>();
		routeType = RouteType.NORMAL;
		isLightRailRoute = false;
		circularState = CircularState.NONE;
		lightRailRouteNumber = "";
		isHidden = false;
		disableNextStationAnnouncements = false;
	}

	public Route(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);

		platformIds = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_PLATFORM_IDS, platformId -> platformIds.add(new RoutePlatform(platformId.asIntegerValue().asLong())));

		final List<String> customDestinations = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_CUSTOM_DESTINATIONS, customDestination -> customDestinations.add(customDestination.asStringValue().asString()));

		for (int i = 0; i < Math.min(platformIds.size(), customDestinations.size()); i++) {
			platformIds.get(i).customDestination = customDestinations.get(i);
		}

		final int[] passIndex = {0};
		messagePackHelper.iterateArrayValue(KEY_PASS_STATIONS, pass -> {
			if (passIndex[0] < platformIds.size()) {
				platformIds.get(passIndex[0]).pass = pass.asBooleanValue().getBoolean();
			}
			passIndex[0]++;
		});
		final int[] dwellIndex = {0};
		messagePackHelper.iterateArrayValue(KEY_DWELL_OVERRIDES, dwellOverride -> {
			if (dwellIndex[0] < platformIds.size()) {
				platformIds.get(dwellIndex[0]).dwellOverride = dwellOverride.asIntegerValue().asInt();
			}
			dwellIndex[0]++;
		});

		final int[] platformIndex = {0};
		messagePackHelper.iterateArrayValue(KEY_WAYPOINTS, waypoints -> {
			if (platformIndex[0] < platformIds.size()) {
				waypoints.asArrayValue().forEach(waypoint -> platformIds.get(platformIndex[0]).waypoints.add(BlockPos.of(waypoint.asIntegerValue().asLong())));
			}
			platformIndex[0]++;
		});

		routeType = EnumHelper.valueOf(RouteType.NORMAL, messagePackHelper.getString(KEY_ROUTE_TYPE));
		isLightRailRoute = messagePackHelper.getBoolean(KEY_IS_LIGHT_RAIL_ROUTE);
		isHidden = messagePackHelper.getBoolean(KEY_IS_ROUTE_HIDDEN);
		disableNextStationAnnouncements = messagePackHelper.getBoolean(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS);
		lightRailRouteNumber = messagePackHelper.getString(KEY_LIGHT_RAIL_ROUTE_NUMBER);
		circularState = EnumHelper.valueOf(CircularState.NONE, messagePackHelper.getString(KEY_CIRCULAR_STATE));
		lineName = messagePackHelper.getString(KEY_LINE_NAME);
		ownColor = messagePackHelper.getInt(KEY_DIAGRAM_COLOR, -1);
		serviceType = messagePackHelper.getString(KEY_SERVICE_TYPE);
		speedLimit = Math.max(0, messagePackHelper.getInt(KEY_SPEED_LIMIT, 0));
		editorManaged = messagePackHelper.getBoolean(KEY_EDITOR_MANAGED);
		connectingLine = messagePackHelper.getBoolean(KEY_CONNECTING_LINE);
		final List<Long> releasedNodes = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_CONNECTING_RELEASED, node -> releasedNodes.add(node.asIntegerValue().asLong()));
		for (int i = 0; i + 1 < releasedNodes.size() && connectingReleased.size() < MAX_CONNECTING_RELEASED; i += 2) {
			connectingReleased.add(new long[]{releasedNodes.get(i), releasedNodes.get(i + 1)});
		}
		messagePackHelper.iterateArrayValue(KEY_TERMINAL_PLATFORMS, value -> addTurnPlatform(terminalPlatforms, value.asIntegerValue().asLong()));
		messagePackHelper.iterateArrayValue(KEY_START_PLATFORMS, value -> addTurnPlatform(startPlatforms, value.asIntegerValue().asLong()));
		messagePackHelper.iterateArrayValue(KEY_CUSTOM_PATH, value -> pathData.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
		timetable.enabled = messagePackHelper.getBoolean(KEY_TIMETABLE_ENABLED);
		timetable.marginPercent = messagePackHelper.getInt(KEY_TIMETABLE_MARGIN, RouteTimetable.DEFAULT_MARGIN_PERCENT);
		readMinPerformance(messagePackHelper, timetable);
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_SERVICES, service -> timetable.services.add(service.asIntegerValue().asInt()));
		timetable.name = messagePackHelper.getString(KEY_TIMETABLE_NAME);
		timetable.weekdays = messagePackHelper.getInt(KEY_TIMETABLE_WEEKDAYS, RouteTimetable.ALL_WEEKDAYS);
		timetable.entryPlatformId = messagePackHelper.getLong(KEY_ENTRY_PLATFORM);
		readLinks(messagePackHelper, timetable);
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_RETURNS, value -> timetable.returnServices.add(value.asIntegerValue().asInt()));
		// Services that don't run; an older save has none
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_SUSPENDED, value -> timetable.suspended.add(value.asIntegerValue().asInt()));
		readOrders(messagePackHelper, timetable);
		readExceptions(messagePackHelper, timetable);

		final int[] planIndex = {0, 0, 0};
		messagePackHelper.iterateArrayValue(KEY_PLAN_ARRIVALS, value -> {
			if (planIndex[0] < platformIds.size()) {
				platformIds.get(planIndex[0]).setArrival(value.asIntegerValue().asInt());
			}
			planIndex[0]++;
		});
		messagePackHelper.iterateArrayValue(KEY_PLAN_DEPARTURES, value -> {
			if (planIndex[1] < platformIds.size()) {
				platformIds.get(planIndex[1]).setDeparture(value.asIntegerValue().asInt());
			}
			planIndex[1]++;
		});
		messagePackHelper.iterateArrayValue(KEY_OVERTAKES, value -> {
			if (planIndex[2] < platformIds.size()) {
				platformIds.get(planIndex[2]).overtake = value.asBooleanValue().getBoolean();
			}
			planIndex[2]++;
		});
		final int[] longerIndex = {0};
		messagePackHelper.iterateArrayValue(KEY_ALLOW_LONGER, value -> {
			if (longerIndex[0] < platformIds.size()) {
				platformIds.get(longerIndex[0]).allowLonger = value.asBooleanValue().getBoolean();
			}
			longerIndex[0]++;
		});

		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_TABLES, value -> readTable(new MessagePackHelper(RailwayData.castMessagePackValueToSKMap(value))));
		timetable.getTables().forEach(table -> ServiceExceptions.limitStops(table, platformIds.size()));
		timetable.sanitize();
	}

	@Deprecated
	public Route(CompoundTag compoundTag) {
		super(compoundTag);

		platformIds = new ArrayList<>();
		final long[] platformIdsArray = compoundTag.getLongArray(KEY_PLATFORM_IDS);
		for (final long platformId : platformIdsArray) {
			platformIds.add(new RoutePlatform(platformId));
		}

		routeType = EnumHelper.valueOf(RouteType.NORMAL, compoundTag.getString(KEY_ROUTE_TYPE));
		isLightRailRoute = compoundTag.getBoolean(KEY_IS_LIGHT_RAIL_ROUTE);
		isHidden = compoundTag.getBoolean(KEY_IS_ROUTE_HIDDEN);
		disableNextStationAnnouncements = compoundTag.getBoolean(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS);
		lightRailRouteNumber = compoundTag.getString(KEY_LIGHT_RAIL_ROUTE_NUMBER);
		circularState = EnumHelper.valueOf(CircularState.NONE, compoundTag.getString(KEY_CIRCULAR_STATE));
	}

	public Route(FriendlyByteBuf packet) {
		super(packet);

		platformIds = new ArrayList<>();
		final int platformCount = packet.readInt();
		for (int i = 0; i < platformCount; i++) {
			final RoutePlatform routePlatform = new RoutePlatform(packet.readLong());
			routePlatform.customDestination = packet.readUtf(PACKET_STRING_READ_LENGTH);
			routePlatform.readWaypoints(packet);
			platformIds.add(routePlatform);
		}

		routeType = EnumHelper.valueOf(RouteType.NORMAL, packet.readUtf(PACKET_STRING_READ_LENGTH));
		isLightRailRoute = packet.readBoolean();
		isHidden = packet.readBoolean();
		disableNextStationAnnouncements = packet.readBoolean();
		lightRailRouteNumber = packet.readUtf(PACKET_STRING_READ_LENGTH);
		circularState = EnumHelper.valueOf(CircularState.NONE, packet.readUtf(PACKET_STRING_READ_LENGTH));
		lineName = packet.readUtf(PACKET_STRING_READ_LENGTH);
		serviceType = packet.readUtf(PACKET_STRING_READ_LENGTH);
		ownColor = packet.readInt();
		editorManaged = packet.readBoolean();
		connectingLine = packet.readBoolean();
		readConnectingReleased(packet);
		readTurnPlatforms(packet);
		speedLimit = packet.readInt();

		final int pathSize = packet.readInt();
		for (int i = 0; i < pathSize; i++) {
			pathData.add(new PathData(packet));
		}
		readTimetable(packet);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_PLATFORM_IDS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packLong(routePlatform.platformId);
		}

		messagePacker.packString(KEY_CUSTOM_DESTINATIONS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packString(routePlatform.customDestination);
		}

		messagePacker.packString(KEY_PASS_STATIONS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.pass);
		}

		messagePacker.packString(KEY_DWELL_OVERRIDES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.dwellOverride);
		}

		messagePacker.packString(KEY_WAYPOINTS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packArrayHeader(routePlatform.waypoints.size());
			for (final BlockPos waypoint : routePlatform.waypoints) {
				messagePacker.packLong(waypoint.asLong());
			}
		}

		messagePacker.packString(KEY_ROUTE_TYPE).packString(routeType.toString());
		messagePacker.packString(KEY_IS_LIGHT_RAIL_ROUTE).packBoolean(isLightRailRoute);
		messagePacker.packString(KEY_IS_ROUTE_HIDDEN).packBoolean(isHidden);
		messagePacker.packString(KEY_DISABLE_NEXT_STATION_ANNOUNCEMENTS).packBoolean(disableNextStationAnnouncements);
		messagePacker.packString(KEY_LIGHT_RAIL_ROUTE_NUMBER).packString(lightRailRouteNumber);
		messagePacker.packString(KEY_CIRCULAR_STATE).packString(circularState.toString());
		messagePacker.packString(KEY_LINE_NAME).packString(lineName);
		messagePacker.packString(KEY_DIAGRAM_COLOR).packInt(getOwnColor());
		messagePacker.packString(KEY_SERVICE_TYPE).packString(serviceType);
		messagePacker.packString(KEY_SPEED_LIMIT).packInt(speedLimit);
		messagePacker.packString(KEY_EDITOR_MANAGED).packBoolean(editorManaged);
		messagePacker.packString(KEY_CONNECTING_LINE).packBoolean(connectingLine);
		messagePacker.packString(KEY_CONNECTING_RELEASED).packArrayHeader(connectingReleased.size() * 2);
		for (final long[] rail : connectingReleased) {
			messagePacker.packLong(rail[0]);
			messagePacker.packLong(rail[1]);
		}
		messagePacker.packString(KEY_TERMINAL_PLATFORMS).packArrayHeader(terminalPlatforms.size());
		for (final long platformId : terminalPlatforms) {
			messagePacker.packLong(platformId);
		}
		messagePacker.packString(KEY_START_PLATFORMS).packArrayHeader(startPlatforms.size());
		for (final long platformId : startPlatforms) {
			messagePacker.packLong(platformId);
		}

		messagePacker.packString(KEY_TIMETABLE_ENABLED).packBoolean(timetable.enabled);
		messagePacker.packString(KEY_TIMETABLE_MARGIN).packInt(timetable.marginPercent);
		writeMinPerformance(messagePacker, timetable);
		messagePacker.packString(KEY_TIMETABLE_SERVICES).packArrayHeader(timetable.services.size());
		for (final int service : timetable.services) {
			messagePacker.packInt(service);
		}

		messagePacker.packString(KEY_PLAN_ARRIVALS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.getArrival());
		}
		messagePacker.packString(KEY_PLAN_DEPARTURES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.getDeparture());
		}
		messagePacker.packString(KEY_OVERTAKES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.overtake);
		}
		messagePacker.packString(KEY_ALLOW_LONGER).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.allowLonger);
		}

		messagePacker.packString(KEY_TIMETABLE_NAME).packString(timetable.name);
		messagePacker.packString(KEY_TIMETABLE_WEEKDAYS).packInt(timetable.weekdays);
		messagePacker.packString(KEY_ENTRY_PLATFORM).packLong(timetable.entryPlatformId);
		writeLinks(messagePacker, timetable);
		writeReturns(messagePacker, timetable);
		writeOrders(messagePacker, timetable);
		writeExceptions(messagePacker, timetable);
		messagePacker.packString(KEY_TIMETABLE_TABLES).packArrayHeader(timetable.getTableCount() - 1);
		for (int i = 1; i < timetable.getTableCount(); i++) {
			writeTable(messagePacker, i);
		}

		if (!pathData.isEmpty()) {
			messagePacker.packString(KEY_CUSTOM_PATH).packArrayHeader(pathData.size());
			for (final PathData data : pathData) {
				messagePacker.packMapHeader(data.messagePackLength());
				data.toMessagePack(messagePacker);
			}
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 37 + (pathData.isEmpty() ? 0 : 1);
	}

	private static void readLinks(MessagePackHelper messagePackHelper, RouteTimetable table) {
		final List<Long> values = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_LINKS, value -> values.add(value.asIntegerValue().asLong()));
		// Older saves have no marker and no joining stop. A service is never negative, so the marker can't be mistaken for one.
		final boolean hasToStop = !values.isEmpty() && values.get(0) == TIMETABLE_LINKS_MARKER;
		final int size = hasToStop ? 5 : 4;
		for (int i = hasToStop ? 1 : 0; i + size - 1 < values.size(); i += size) {
			table.links.put(values.get(i).intValue(), new RouteTimetable.ServiceLink(values.get(i + 1), values.get(i + 2).intValue(), values.get(i + 3).intValue(), hasToStop ? values.get(i + 4).intValue() : 0));
		}
	}

	private static void writeLinks(MessagePacker messagePacker, RouteTimetable table) throws IOException {
		messagePacker.packString(KEY_TIMETABLE_LINKS).packArrayHeader(table.links.isEmpty() ? 0 : table.links.size() * 5 + 1);
		if (!table.links.isEmpty()) {
			messagePacker.packLong(TIMETABLE_LINKS_MARKER);
		}
		for (final Map.Entry<Integer, RouteTimetable.ServiceLink> entry : table.links.entrySet()) {
			messagePacker.packLong(entry.getKey());
			messagePacker.packLong(entry.getValue().routeId);
			messagePacker.packLong(entry.getValue().service);
			messagePacker.packLong(entry.getValue().fromStop);
			messagePacker.packLong(entry.getValue().toStop);
		}
	}

	private static void readExceptions(MessagePackHelper messagePackHelper, RouteTimetable table) {
		final List<Integer> values = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_EXCEPTIONS, value -> values.add(value.asIntegerValue().asInt()));
		ServiceExceptions.fromInts(values, table);
	}

	private static void writeExceptions(MessagePacker messagePacker, RouteTimetable table) throws IOException {
		final int[] values = ServiceExceptions.toInts(table);
		messagePacker.packString(KEY_TIMETABLE_EXCEPTIONS).packArrayHeader(values.length);
		for (final int value : values) {
			messagePacker.packInt(value);
		}
	}

	private static void readOrders(MessagePackHelper messagePackHelper, RouteTimetable table) {
		final List<Long> values = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_ORDERS, value -> values.add(value.asIntegerValue().asLong()));
		for (int i = 0; i + 3 < values.size(); i += 4) {
			final RouteTimetable.ServiceOrder order = new RouteTimetable.ServiceOrder(values.get(i + 2).intValue(), values.get(i + 3));
			(values.get(i + 1) == 0 ? table.deployOrders : table.returnOrders).put(values.get(i).intValue(), order);
		}
	}

	private static void writeOrders(MessagePacker messagePacker, RouteTimetable table) throws IOException {
		messagePacker.packString(KEY_TIMETABLE_ORDERS).packArrayHeader((table.deployOrders.size() + table.returnOrders.size()) * 4);
		for (final Map.Entry<Integer, RouteTimetable.ServiceOrder> entry : new TreeMap<>(table.deployOrders).entrySet()) {
			messagePacker.packLong(entry.getKey()).packLong(0).packLong(entry.getValue().stop).packLong(entry.getValue().depotId);
		}
		for (final Map.Entry<Integer, RouteTimetable.ServiceOrder> entry : new TreeMap<>(table.returnOrders).entrySet()) {
			messagePacker.packLong(entry.getKey()).packLong(1).packLong(entry.getValue().stop).packLong(entry.getValue().depotId);
		}
	}

	/**
	 * Writes the services after which the train goes back, and the services that don't run: two keys.
	 */
	private static void writeReturns(MessagePacker messagePacker, RouteTimetable table) throws IOException {
		messagePacker.packString(KEY_TIMETABLE_RETURNS).packArrayHeader(table.returnServices.size());
		for (final int service : table.returnServices) {
			messagePacker.packInt(service);
		}
		messagePacker.packString(KEY_TIMETABLE_SUSPENDED).packArrayHeader(table.suspended.size());
		for (final int service : new TreeSet<>(table.suspended)) {
			messagePacker.packInt(service);
		}
	}

	/**
	 * Trains from the depot can join a timetabled loop part of the way round, keeping to the timetable from that stop. This
	 * only works on a loop where the first and last stops are the same, and at a stop where trains actually stop.
	 *
	 * @return the stop where trains join this route, or 0 for the first stop
	 */
	public int getLoopEntryStop() {
		if (timetable.entryPlatformId != 0 && isLoop()) {
			for (int i = 1; i < platformIds.size() - 1; i++) {
				if (platformIds.get(i).platformId == timetable.entryPlatformId) {
					return platformIds.get(i).pass ? 0 : i;
				}
			}
		}
		return 0;
	}

	public boolean isLoop() {
		return platformIds.size() > 2 && getFirstPlatformId() == getLastPlatformId();
	}

	private void readTable(MessagePackHelper messagePackHelper) {
		final RouteTimetable table = new RouteTimetable();
		table.name = messagePackHelper.getString(KEY_TIMETABLE_NAME);
		table.weekdays = messagePackHelper.getInt(KEY_TIMETABLE_WEEKDAYS, RouteTimetable.ALL_WEEKDAYS);
		table.marginPercent = messagePackHelper.getInt(KEY_TIMETABLE_MARGIN, RouteTimetable.DEFAULT_MARGIN_PERCENT);
		readMinPerformance(messagePackHelper, table);
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_SERVICES, service -> table.services.add(service.asIntegerValue().asInt()));
		readLinks(messagePackHelper, table);
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_RETURNS, value -> table.returnServices.add(value.asIntegerValue().asInt()));
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_SUSPENDED, value -> table.suspended.add(value.asIntegerValue().asInt()));
		readOrders(messagePackHelper, table);
		readExceptions(messagePackHelper, table);
		timetable.addTable(table);

		final int tableIndex = timetable.getTableCount() - 1;
		final int[] planIndex = {0, 0, 0};
		messagePackHelper.iterateArrayValue(KEY_PLAN_ARRIVALS, value -> {
			if (planIndex[0] < platformIds.size()) {
				platformIds.get(planIndex[0]).setArrival(tableIndex, value.asIntegerValue().asInt());
			}
			planIndex[0]++;
		});
		messagePackHelper.iterateArrayValue(KEY_PLAN_DEPARTURES, value -> {
			if (planIndex[1] < platformIds.size()) {
				platformIds.get(planIndex[1]).setDeparture(tableIndex, value.asIntegerValue().asInt());
			}
			planIndex[1]++;
		});
		messagePackHelper.iterateArrayValue(KEY_OVERTAKES, value -> {
			if (planIndex[2] < platformIds.size()) {
				platformIds.get(planIndex[2]).setOvertake(tableIndex, value.asBooleanValue().getBoolean());
			}
			planIndex[2]++;
		});
	}

	private static void writeMinPerformance(MessagePacker messagePacker, RouteTimetable table) throws IOException {
		messagePacker.packString(KEY_TIMETABLE_MIN_PERFORMANCE).packArrayHeader(3);
		messagePacker.packFloat(table.minAcceleration);
		messagePacker.packFloat(table.minDeceleration);
		messagePacker.packFloat(table.minMaxSpeed);
	}

	private static void readMinPerformance(MessagePackHelper messagePackHelper, RouteTimetable table) {
		final List<Float> values = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_MIN_PERFORMANCE, value -> values.add(value.asFloatValue().toFloat()));
		if (values.size() == 3) {
			table.minAcceleration = values.get(0);
			table.minDeceleration = values.get(1);
			table.minMaxSpeed = values.get(2);
		}
	}

	private static void copyMinPerformance(RouteTimetable from, RouteTimetable to) {
		to.minAcceleration = from.minAcceleration;
		to.minDeceleration = from.minDeceleration;
		to.minMaxSpeed = from.minMaxSpeed;
	}

	private void writeTable(MessagePacker messagePacker, int tableIndex) throws IOException {
		final RouteTimetable table = timetable.getTable(tableIndex);
		messagePacker.packMapHeader(13);
		writeLinks(messagePacker, table);
		writeReturns(messagePacker, table);
		writeOrders(messagePacker, table);
		writeExceptions(messagePacker, table);
		messagePacker.packString(KEY_TIMETABLE_NAME).packString(table.name);
		messagePacker.packString(KEY_TIMETABLE_WEEKDAYS).packInt(table.weekdays);
		messagePacker.packString(KEY_TIMETABLE_MARGIN).packInt(table.marginPercent);
		writeMinPerformance(messagePacker, table);
		messagePacker.packString(KEY_TIMETABLE_SERVICES).packArrayHeader(table.services.size());
		for (final int service : table.services) {
			messagePacker.packInt(service);
		}
		messagePacker.packString(KEY_PLAN_ARRIVALS).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.getArrival(tableIndex));
		}
		messagePacker.packString(KEY_PLAN_DEPARTURES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packInt(routePlatform.getDeparture(tableIndex));
		}
		messagePacker.packString(KEY_OVERTAKES).packArrayHeader(platformIds.size());
		for (final RoutePlatform routePlatform : platformIds) {
			messagePacker.packBoolean(routePlatform.getOvertake(tableIndex));
		}
	}

	/**
	 * Adds a table with no services, starting from the stop times of another one.
	 *
	 * @return the index of the new table, or -1 if the route has as many as it can
	 */
	public int addTable(int copiedTable) {
		if (timetable.getTableCount() >= RouteTimetable.MAX_TABLES) {
			return -1;
		}
		final RouteTimetable table = new RouteTimetable();
		table.marginPercent = timetable.getTable(copiedTable).marginPercent;
		copyMinPerformance(timetable.getTable(copiedTable), table);
		timetable.addTable(table);
		final int tableIndex = timetable.getTableCount() - 1;
		platformIds.forEach(routePlatform -> {
			routePlatform.setArrival(tableIndex, routePlatform.getArrival(copiedTable));
			routePlatform.setDeparture(tableIndex, routePlatform.getDeparture(copiedTable));
			routePlatform.setOvertake(tableIndex, routePlatform.getOvertake(copiedTable));
		});
		return tableIndex;
	}

	/**
	 * Removes a table other than the first one, along with its times at each stop.
	 */
	public void removeTable(int tableIndex) {
		if (tableIndex >= 1 && tableIndex < timetable.getTableCount()) {
			timetable.removeTable(tableIndex);
			platformIds.forEach(routePlatform -> routePlatform.removeTable(tableIndex));
		}
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeInt(platformIds.size());
		platformIds.forEach(routePlatform -> {
			packet.writeLong(routePlatform.platformId);
			packet.writeUtf(routePlatform.customDestination);
			routePlatform.writeWaypoints(packet);
		});

		packet.writeUtf(routeType.toString());
		packet.writeBoolean(isLightRailRoute);
		packet.writeBoolean(isHidden);
		packet.writeBoolean(disableNextStationAnnouncements);
		packet.writeUtf(lightRailRouteNumber);
		packet.writeUtf(circularState.toString());
		packet.writeUtf(lineName);
		packet.writeUtf(serviceType);
		packet.writeInt(ownColor);
		packet.writeBoolean(editorManaged);
		packet.writeBoolean(connectingLine);
		writeConnectingReleased(packet);
		writeTurnPlatforms(packet);
		packet.writeInt(speedLimit);

		packet.writeInt(pathData.size());
		pathData.forEach(data -> data.writePacket(packet));
		writeTimetable(packet);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_PLATFORM_IDS:
				final List<RoutePlatform> oldPlatforms = new ArrayList<>(platformIds);
				platformIds.clear();
				final int platformCount = packet.readInt();
				for (int i = 0; i < platformCount; i++) {
					final RoutePlatform routePlatform = new RoutePlatform(packet.readLong());
					routePlatform.customDestination = packet.readUtf(PACKET_STRING_READ_LENGTH);
					routePlatform.readWaypoints(packet);
					routePlatform.takeTimes(oldPlatforms);
					if (i < MAX_PLATFORMS) {
						platformIds.add(routePlatform);
					}
				}
				ServiceExceptions.remapStops(timetable, platformIds, oldPlatforms);
				timetable.revision++;
				break;
			case KEY_IS_LIGHT_RAIL_ROUTE:
				name = packet.readUtf(PACKET_STRING_READ_LENGTH);
				color = packet.readInt();
				routeType = EnumHelper.valueOf(RouteType.NORMAL, packet.readUtf(PACKET_STRING_READ_LENGTH));
				isLightRailRoute = packet.readBoolean();
				lightRailRouteNumber = packet.readUtf(PACKET_STRING_READ_LENGTH);
				isHidden = packet.readBoolean();
				disableNextStationAnnouncements = packet.readBoolean();
				circularState = EnumHelper.valueOf(CircularState.NONE, packet.readUtf(PACKET_STRING_READ_LENGTH));
				lineName = packet.readUtf(PACKET_STRING_READ_LENGTH);
				serviceType = packet.readUtf(PACKET_STRING_READ_LENGTH);
				ownColor = packet.readInt();
				editorManaged = packet.readBoolean();
				connectingLine = packet.readBoolean();
				readConnectingReleased(packet);
				readTurnPlatforms(packet);
				// A new speed limit times the route and its duties again
				final int newSpeedLimit = Math.max(0, packet.readInt());
				if (newSpeedLimit != speedLimit) {
					speedLimit = newSpeedLimit;
					timetable.revision++;
				}
				break;
			case KEY_TIMETABLE_SERVICES:
				readTimetable(packet);
				break;
			case KEY_CUSTOM_PATH:
				final List<PathData> newPathData = new ArrayList<>();
				final int newPathSize = packet.readInt();
				for (int i = 0; i < newPathSize; i++) {
					newPathData.add(new PathData(packet));
				}
				pathData = newPathData;
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	@Override
	protected boolean hasTransportMode() {
		return true;
	}

	@Override
	public long getRouteId() {
		return id;
	}

	@Override
	public RouteTimetable getTimetable() {
		return timetable;
	}

	@Override
	public List<RoutePlatform> getStops() {
		return platformIds;
	}

	@Override
	public int getTransportModeId() {
		return transportMode.ordinal();
	}

	public void setPlatformIds(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_PLATFORM_IDS);
		packet.writeInt(platformIds.size());
		platformIds.forEach(routePlatform -> {
			packet.writeLong(routePlatform.platformId);
			packet.writeUtf(routePlatform.customDestination);
			routePlatform.writeWaypoints(packet);
		});
		sendPacket.accept(packet);
	}

	public void setExtraData(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_IS_LIGHT_RAIL_ROUTE);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeUtf(routeType.toString());
		packet.writeBoolean(isLightRailRoute);
		packet.writeUtf(lightRailRouteNumber);
		packet.writeBoolean(isHidden);
		packet.writeBoolean(disableNextStationAnnouncements);
		packet.writeUtf(circularState.toString());
		packet.writeUtf(lineName);
		packet.writeUtf(serviceType);
		packet.writeInt(ownColor);
		packet.writeBoolean(editorManaged);
		packet.writeBoolean(connectingLine);
		writeConnectingReleased(packet);
		writeTurnPlatforms(packet);
		packet.writeInt(speedLimit);
		sendPacket.accept(packet);
	}

	private void writeTurnPlatforms(FriendlyByteBuf packet) {
		packet.writeInt(terminalPlatforms.size());
		terminalPlatforms.forEach(packet::writeLong);
		packet.writeInt(startPlatforms.size());
		startPlatforms.forEach(packet::writeLong);
	}

	private void readTurnPlatforms(FriendlyByteBuf packet) {
		terminalPlatforms.clear();
		for (int i = packet.readInt(); i > 0; i--) {
			addTurnPlatform(terminalPlatforms, packet.readLong());
		}
		startPlatforms.clear();
		for (int i = packet.readInt(); i > 0; i--) {
			addTurnPlatform(startPlatforms, packet.readLong());
		}
	}

	private static void addTurnPlatform(List<Long> platforms, long platformId) {
		if (platforms.size() < MAX_TURN_PLATFORMS && platformId != 0 && !platforms.contains(platformId)) {
			platforms.add(platformId);
		}
	}

	@Override
	public List<Long> getTerminalPlatforms() {
		return terminalPlatforms;
	}

	@Override
	public List<Long> getStartPlatforms() {
		return startPlatforms;
	}

	private void writeConnectingReleased(FriendlyByteBuf packet) {
		packet.writeInt(connectingReleased.size());
		connectingReleased.forEach(rail -> {
			packet.writeLong(rail[0]);
			packet.writeLong(rail[1]);
		});
	}

	private void readConnectingReleased(FriendlyByteBuf packet) {
		connectingReleased.clear();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			final long[] rail = {packet.readLong(), packet.readLong()};
			if (i < MAX_CONNECTING_RELEASED) {
				connectingReleased.add(rail);
			}
		}
	}

	public void setCustomPath(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_CUSTOM_PATH);
		packet.writeInt(pathData.size());
		pathData.forEach(data -> data.writePacket(packet));
		sendPacket.accept(packet);
	}

	/**
	 * @return a new route with everything this one has, so it can be given a different timetable
	 */
	public Route duplicate() {
		final Route route = new Route(transportMode);
		route.name = name + DUPLICATE_SUFFIX;
		route.color = color;
		route.routeType = routeType;
		route.isLightRailRoute = isLightRailRoute;
		route.isHidden = isHidden;
		route.disableNextStationAnnouncements = disableNextStationAnnouncements;
		route.connectingLine = connectingLine;
		connectingReleased.forEach(rail -> route.connectingReleased.add(rail.clone()));
		route.terminalPlatforms.addAll(terminalPlatforms);
		route.startPlatforms.addAll(startPlatforms);
		route.circularState = circularState;
		route.lightRailRouteNumber = lightRailRouteNumber;
		route.lineName = lineName;
		route.ownColor = ownColor;
		route.serviceType = serviceType;
		route.speedLimit = speedLimit;
		route.pathData = new ArrayList<>(pathData);
		platformIds.forEach(routePlatform -> {
			final RoutePlatform newRoutePlatform = new RoutePlatform(routePlatform.platformId);
			newRoutePlatform.customDestination = routePlatform.customDestination;
			newRoutePlatform.waypoints.addAll(routePlatform.waypoints);
			newRoutePlatform.pass = routePlatform.pass;
			newRoutePlatform.allowLonger = routePlatform.allowLonger;
			newRoutePlatform.dwellOverride = routePlatform.dwellOverride;
			for (int i = 0; i < timetable.getTableCount(); i++) {
				newRoutePlatform.setArrival(i, routePlatform.getArrival(i));
				newRoutePlatform.setDeparture(i, routePlatform.getDeparture(i));
				newRoutePlatform.setOvertake(i, routePlatform.getOvertake(i));
			}
			route.platformIds.add(newRoutePlatform);
		});
		route.timetable.enabled = timetable.enabled;
		route.timetable.entryPlatformId = timetable.entryPlatformId;
		timetable.getTables().forEach(table -> {
			final RouteTimetable newTable = table == timetable ? route.timetable : new RouteTimetable();
			newTable.name = table.name;
			newTable.weekdays = table.weekdays;
			newTable.marginPercent = table.marginPercent;
			copyMinPerformance(table, newTable);
			newTable.services.addAll(table.services);
			newTable.links.putAll(table.links);
			newTable.returnServices.addAll(table.returnServices);
			newTable.suspended.addAll(table.suspended);
			newTable.returnOrders.putAll(table.returnOrders);
			newTable.deployOrders.putAll(table.deployOrders);
			ServiceExceptions.copy(table, newTable);
			if (table != timetable) {
				route.timetable.addTable(newTable);
			}
		});
		route.timetable.sanitize();
		return route;
	}

	public String getLineName() {
		return lineName.trim().isEmpty() ? getAutoLineName(name) : lineName.trim();
	}

	/**
	 * Compare these to tell if routes are the same line, such as the two directions of a train turning back.
	 */
	public String getLineKey() {
		return getLineName().toLowerCase(Locale.ENGLISH);
	}

	/**
	 * @return the type of service in lower case, or empty if the route doesn't have one
	 */
	public String getServiceType() {
		final String setServiceType = serviceType.trim().toLowerCase(Locale.ENGLISH);
		return setServiceType.isEmpty() ? LineWords.findServiceWord(name.split("\\|\\|", -1)[0]) : setServiceType;
	}

	/**
	 * Routes saved before they had their own colour use the colour they had.
	 */
	public int getOwnColor() {
		return ownColor < 0 ? color : ownColor;
	}

	public void setOwnColor(int ownColor) {
		this.ownColor = ownColor;
		color = ownColor;
	}

	/**
	 * Gives each route the colour of its line, which is the own colour of the line's first route by name.
	 */
	public static void applyLineColors(Collection<Route> routes) {
		final List<Route> sortedRoutes = new ArrayList<>(routes);
		sortedRoutes.sort(Comparator.comparing((Route route) -> route.name.toLowerCase(Locale.ENGLISH)).thenComparingLong(route -> route.id));
		sortedRoutes.forEach(route -> {
			if (route.ownColor < 0) {
				route.ownColor = route.color;
			}
		});
		final Map<String, Integer> lineColors = new HashMap<>();
		sortedRoutes.forEach(route -> route.color = lineColors.computeIfAbsent(route.transportMode + route.getLineKey(), key -> route.ownColor));
	}

	public boolean isSameLine(Route route) {
		return route != null && getLineKey().equals(route.getLineKey());
	}

	/**
	 * Removes what only tells routes of one line apart: anything after {@code ||}, the copy suffix, and the {@link LineWords}
	 * at either end of the name.
	 */
	public static String getAutoLineName(String name) {
		final String[] languages = name.split("\\|\\|", -1)[0].split("\\|", -1);
		for (int i = 0; i < languages.length; i++) {
			final String withoutSuffix = DUPLICATE_PATTERN.matcher(languages[i].trim()).replaceAll("");
			final String lineName = LineWords.removeFromEnds(withoutSuffix);
			languages[i] = lineName.isEmpty() ? withoutSuffix : lineName;
		}
		return String.join("|", languages);
	}

	public void setTimetable(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_TIMETABLE_SERVICES);
		writeTimetable(packet);
		sendPacket.accept(packet);
	}

	/**
	 * Converts the time doors stay fully open into a stop time in half seconds. Opening, closing and the delays around
	 * them take 8.4 seconds, which is rounded up to the 0.5 second steps of a stop time.
	 */
	public static int getDwellFromDoorOpenSeconds(int doorOpenSeconds) {
		return (int) Math.ceil((Math.max(doorOpenSeconds, 0) + DOOR_CYCLE_SECONDS) * 2);
	}

	public static int getDoorOpenSeconds(int dwell) {
		return Math.max((int) Math.floor(dwell / 2D - DOOR_CYCLE_SECONDS), 0);
	}

	/**
	 * @return the stops of this route in order, as {@link RoutePlatform}, with the key nodes between them as {@link BlockPos}
	 */
	public List<Object> getEntries() {
		final List<Object> entries = new ArrayList<>();
		platformIds.forEach(routePlatform -> {
			entries.add(routePlatform);
			entries.addAll(routePlatform.waypoints);
		});
		return entries;
	}

	/**
	 * Key nodes belong to the section after a stop, so any placed before the first stop move to just after it.
	 */
	public void setEntries(List<Object> entries) {
		final List<RoutePlatform> newPlatforms = new ArrayList<>();
		final List<BlockPos> beforeFirstStop = new ArrayList<>();
		for (final Object entry : entries) {
			if (entry instanceof RoutePlatform) {
				final RoutePlatform routePlatform = (RoutePlatform) entry;
				routePlatform.waypoints.clear();
				if (newPlatforms.isEmpty()) {
					routePlatform.waypoints.addAll(beforeFirstStop);
				}
				newPlatforms.add(routePlatform);
			} else if (newPlatforms.isEmpty()) {
				beforeFirstStop.add((BlockPos) entry);
			} else if (newPlatforms.get(newPlatforms.size() - 1).waypoints.size() < MAX_WAYPOINTS) {
				newPlatforms.get(newPlatforms.size() - 1).waypoints.add((BlockPos) entry);
			}
		}
		platformIds.clear();
		platformIds.addAll(newPlatforms);
	}

	public List<PathData> getPathData() {
		return pathData;
	}

	/**
	 * Adds a stop for every platform the path enters, apart from turning back over the same platform.
	 */
	public void setPathData(List<PathData> pathData) {
		PathData last = null;
		for (final PathData data : pathData) {
			if (data.rail.railType == RailType.PLATFORM && data.savedRailBaseId != 0 && (last == null || !last.isOppositeRail(data))) {
				platformIds.add(new RoutePlatform(data.savedRailBaseId));
			}
			last = data;
		}
		this.pathData = pathData;
	}

	public int getPlatformIdIndex(long platformId) {
		for (int i = 0; i < platformIds.size(); i++) {
			if (platformIds.get(i).platformId == platformId) {
				return i;
			}
		}
		return -1;
	}

	public boolean containsPlatformId(long platformId) {
		return getPlatformIdIndex(platformId) >= 0;
	}

	public long getFirstPlatformId() {
		return platformIds.isEmpty() ? 0 : platformIds.get(0).platformId;
	}

	public long getLastPlatformId() {
		return platformIds.isEmpty() ? 0 : platformIds.get(platformIds.size() - 1).platformId;
	}

	public String getDestination(int index) {
		for (int i = Math.min(platformIds.size() - 1, index); i >= 0; i--) {
			final String customDestination = platformIds.get(i).customDestination;
			if (Route.destinationIsReset(customDestination)) {
				return null;
			} else if (!customDestination.isEmpty()) {
				return customDestination;
			}
		}
		return null;
	}

	public static boolean destinationIsReset(String destination) {
		return destination.equals("\\r") || destination.equals("\\reset");
	}

	private void readTimetable(FriendlyByteBuf packet) {
		timetable.enabled = packet.readBoolean();
		timetable.entryPlatformId = packet.readLong();
		timetable.clearOtherTables();
		platformIds.forEach(RoutePlatform::clearOtherTables);

		final int tableCount = packet.readInt();
		for (int i = 0; i < tableCount; i++) {
			final RouteTimetable table = i == 0 ? timetable : new RouteTimetable();
			readTableHeader(packet, table);
			if (i > 0) {
				timetable.addTable(table);
			}

			final int stopCount = packet.readInt();
			for (int j = 0; j < stopCount; j++) {
				final int arrival = packet.readInt();
				final int departure = packet.readInt();
				final boolean overtake = packet.readBoolean();
				if (j < platformIds.size() && i < RouteTimetable.MAX_TABLES) {
					platformIds.get(j).setArrival(i, arrival);
					platformIds.get(j).setDeparture(i, departure);
					platformIds.get(j).setOvertake(i, overtake);
				}
			}
		}
		timetable.getTables().forEach(table -> ServiceExceptions.limitStops(table, platformIds.size()));
		timetable.sanitize();

		final int stopCount = packet.readInt();
		for (int i = 0; i < stopCount; i++) {
			final boolean pass = packet.readBoolean();
			if (i < platformIds.size()) {
				platformIds.get(i).pass = pass;
			}
		}
		timetable.revision++;
	}

	private static void readTableHeader(FriendlyByteBuf packet, RouteTimetable table) {
		table.name = packet.readUtf(PACKET_STRING_READ_LENGTH);
		table.weekdays = packet.readInt();
		table.marginPercent = packet.readInt();
		table.minAcceleration = packet.readFloat();
		table.minDeceleration = packet.readFloat();
		table.minMaxSpeed = packet.readFloat();
		table.services.clear();
		final int serviceCount = packet.readInt();
		for (int i = 0; i < serviceCount; i++) {
			final int service = packet.readInt();
			if (i < RouteTimetable.MAX_SERVICES) {
				table.services.add(service);
			}
		}
		table.links.clear();
		final int linkCount = packet.readInt();
		for (int i = 0; i < linkCount; i++) {
			final int service = packet.readInt();
			final RouteTimetable.ServiceLink link = new RouteTimetable.ServiceLink(packet.readLong(), packet.readInt(), packet.readInt(), packet.readInt());
			if (i < RouteTimetable.MAX_SERVICES) {
				table.links.put(service, link);
			}
		}
		table.returnServices.clear();
		final int returnCount = packet.readInt();
		for (int i = 0; i < returnCount; i++) {
			final int service = packet.readInt();
			if (i < RouteTimetable.MAX_SERVICES) {
				table.returnServices.add(service);
			}
		}
		table.suspended.clear();
		final int suspendedCount = packet.readInt();
		for (int i = 0; i < suspendedCount; i++) {
			final int service = packet.readInt();
			if (i < RouteTimetable.MAX_SERVICES) {
				table.suspended.add(service);
			}
		}
		table.returnOrders.clear();
		table.deployOrders.clear();
		for (int kind = 0; kind < 2; kind++) {
			final int orderCount = packet.readInt();
			for (int i = 0; i < orderCount; i++) {
				final int service = packet.readInt();
				final RouteTimetable.ServiceOrder order = new RouteTimetable.ServiceOrder(packet.readInt(), packet.readLong());
				if (i < RouteTimetable.MAX_SERVICES) {
					(kind == 0 ? table.deployOrders : table.returnOrders).put(service, order);
				}
			}
		}
		ServiceExceptions.read(PacketBinary.of(packet), table);
	}

	private void writeTimetable(FriendlyByteBuf packet) {
		packet.writeBoolean(timetable.enabled);
		packet.writeLong(timetable.entryPlatformId);
		packet.writeInt(timetable.getTableCount());
		for (int i = 0; i < timetable.getTableCount(); i++) {
			final RouteTimetable table = timetable.getTable(i);
			packet.writeUtf(table.name);
			packet.writeInt(table.weekdays);
			packet.writeInt(table.marginPercent);
			packet.writeFloat(table.minAcceleration);
			packet.writeFloat(table.minDeceleration);
			packet.writeFloat(table.minMaxSpeed);
			packet.writeInt(table.services.size());
			table.services.forEach(packet::writeInt);
			packet.writeInt(table.links.size());
			table.links.forEach((service, link) -> {
				packet.writeInt(service);
				packet.writeLong(link.routeId);
				packet.writeInt(link.service);
				packet.writeInt(link.fromStop);
				packet.writeInt(link.toStop);
			});
			packet.writeInt(table.returnServices.size());
			table.returnServices.forEach(packet::writeInt);
			packet.writeInt(table.suspended.size());
			new TreeSet<>(table.suspended).forEach(packet::writeInt);
			for (final Map<Integer, RouteTimetable.ServiceOrder> orders : Arrays.asList(table.deployOrders, table.returnOrders)) {
				packet.writeInt(orders.size());
				new TreeMap<>(orders).forEach((service, order) -> {
					packet.writeInt(service);
					packet.writeInt(order.stop);
					packet.writeLong(order.depotId);
				});
			}
			ServiceExceptions.write(PacketBinary.of(packet), table);

			packet.writeInt(platformIds.size());
			for (final RoutePlatform routePlatform : platformIds) {
				packet.writeInt(routePlatform.getArrival(i));
				packet.writeInt(routePlatform.getDeparture(i));
				packet.writeBoolean(routePlatform.getOvertake(i));
			}
		}

		packet.writeInt(platformIds.size());
		platformIds.forEach(routePlatform -> packet.writeBoolean(routePlatform.pass));
	}

	public static class RoutePlatform implements PlanStop {

		public String customDestination;
		public final long platformId;
		/**
		 * Rail nodes a train must pass between this platform and the next one, without stopping.
		 */
		public final List<BlockPos> waypoints = new ArrayList<>();
		/**
		 * Trains run through without stopping, while the station stays on the route.
		 */
		public boolean pass;
		/**
		 * The stop time for this route in half seconds, or 0 to use the platform's own.
		 */
		public int dwellOverride;
		/**
		 * The train waits here for trains that should pass it before it leaves, on another platform of this station.
		 */
		public boolean overtake;
		/**
		 * Trains longer than the platform may stop here all the same (optimisation round 3b, step 3): set by hand where
		 * the check of lengths would otherwise point it out.
		 */
		public boolean allowLonger;
		private int arrival = -1;
		private int departure = -1;
		/**
		 * The times of this stop in the tables after the first one.
		 */
		private final List<RoutePlatform> otherTimes = new ArrayList<>();

		public RoutePlatform(long platformId) {
			this.platformId = platformId;
			customDestination = "";
		}

		/**
		 * @param canPass false for the first and last stops of a route
		 * @return the stop time trains actually use here in half seconds, or 0 when they pass
		 */
		public int getStopDwell(boolean canPass, int platformDwell, boolean continuousMovement) {
			if (pass && canPass) {
				return 0;
			}
			final int stopTime = dwellOverride > 0 ? dwellOverride : platformDwell;
			// Doors have no time to open during a shorter stop. Cable cars keep moving through platforms.
			return continuousMovement ? stopTime : Math.max(stopTime, MIN_STOP_DWELL);
		}

		/**
		 * @return the timetable's arrival in seconds from reaching the first stop, or -1 to work it out
		 */
		@Override
		public long getPlatformId() {
			return platformId;
		}

		@Override
		public boolean isPass() {
			return pass;
		}

		public int getArrival() {
			return arrival;
		}

		public void setArrival(int arrival) {
			this.arrival = Math.max(-1, Math.min(arrival, RouteTimetable.MAX_STOP_SECONDS));
		}

		/**
		 * @return the timetable's departure in seconds from reaching the first stop, or -1 to let the stop time vary
		 */
		public int getDeparture() {
			return departure;
		}

		public void setDeparture(int departure) {
			this.departure = Math.max(-1, Math.min(departure, RouteTimetable.MAX_STOP_SECONDS));
		}

		public int getArrival(int table) {
			return table == 0 ? arrival : getOtherTimes(table).getArrival();
		}

		public void setArrival(int table, int arrival) {
			if (table == 0) {
				setArrival(arrival);
			} else {
				getOtherTimes(table).setArrival(arrival);
			}
		}

		public int getDeparture(int table) {
			return table == 0 ? departure : getOtherTimes(table).getDeparture();
		}

		public void setDeparture(int table, int departure) {
			if (table == 0) {
				setDeparture(departure);
			} else {
				getOtherTimes(table).setDeparture(departure);
			}
		}

		public boolean getOvertake(int table) {
			return table == 0 ? overtake : getOtherTimes(table).overtake;
		}

		public void setOvertake(int table, boolean overtake) {
			if (table == 0) {
				this.overtake = overtake;
			} else {
				getOtherTimes(table).overtake = overtake;
			}
		}

		/**
		 * Keeps the times of a stop when the stops of a route change, which go with the platform and not its place in the route.
		 *
		 * @param stops the old stops, with the one taken from removed so a platform used twice keeps both sets of times
		 */
		private void takeTimes(List<RoutePlatform> stops) {
			for (int i = 0; i < stops.size(); i++) {
				final RoutePlatform stop = stops.get(i);
				if (stop.platformId == platformId) {
					arrival = stop.arrival;
					departure = stop.departure;
					overtake = stop.overtake;
					otherTimes.addAll(stop.otherTimes);
					stops.remove(i);
					return;
				}
			}
		}

		private void removeTable(int table) {
			if (table >= 1 && table <= otherTimes.size()) {
				otherTimes.remove(table - 1);
			}
		}

		private void clearOtherTables() {
			otherTimes.clear();
		}

		/**
		 * Stops added after a table have no times for it yet, so they start out blank like any other new stop.
		 */
		private RoutePlatform getOtherTimes(int table) {
			while (otherTimes.size() < table) {
				otherTimes.add(new RoutePlatform(platformId));
			}
			return otherTimes.get(table - 1);
		}

		private void readWaypoints(FriendlyByteBuf packet) {
			final int waypointCount = packet.readInt();
			for (int i = 0; i < waypointCount; i++) {
				final long waypoint = packet.readLong();
				if (i < MAX_WAYPOINTS) {
					waypoints.add(BlockPos.of(waypoint));
				}
			}
			pass = packet.readBoolean();
			dwellOverride = Math.max(0, Math.min(packet.readInt(), MAX_DWELL_OVERRIDE));
			setArrival(packet.readInt());
			setDeparture(packet.readInt());
			overtake = packet.readBoolean();
			allowLonger = packet.readBoolean();
		}

		private void writeWaypoints(FriendlyByteBuf packet) {
			packet.writeInt(waypoints.size());
			waypoints.forEach(waypoint -> packet.writeLong(waypoint.asLong()));
			packet.writeBoolean(pass);
			packet.writeInt(dwellOverride);
			packet.writeInt(arrival);
			packet.writeInt(departure);
			packet.writeBoolean(overtake);
			packet.writeBoolean(allowLonger);
		}
	}

	public enum CircularState {NONE, CLOCKWISE, ANTICLOCKWISE}

	/**
	 * @return a short text that changes when the route's timetable, stops or whether the editor manages it change, which
	 * the web editor bases its changes on and the server checks them against
	 */
	public String getEditorRevision() {
		final int[] hash = {Boolean.hashCode(editorManaged)};
		setTimetable(packet -> hash[0] = hash[0] * 31 + packet.nioBuffer().hashCode());
		setPlatformIds(packet -> hash[0] = hash[0] * 31 + packet.nioBuffer().hashCode());
		return Integer.toHexString(hash[0]);
	}
}
