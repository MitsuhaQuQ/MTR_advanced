package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class Route extends NameColorDataBase implements IGui {

	private static final int MAX_PLATFORMS = 1024;
	public static final int MAX_WAYPOINTS = 64;
	public static final int MIN_STOP_DWELL = 17;
	private static final int MAX_DWELL_OVERRIDE = 7200;
	private static final double DOOR_CYCLE_SECONDS = 8.4;

	public RouteType routeType;
	public boolean isLightRailRoute;
	public boolean isHidden;
	public boolean disableNextStationAnnouncements;
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
	private static final String KEY_TIMETABLE_SERVICES = "timetable_services";
	private static final String KEY_PLAN_ARRIVALS = "plan_arrivals";
	private static final String KEY_PLAN_DEPARTURES = "plan_departures";
	private static final String KEY_OVERTAKES = "overtakes";
	private static final String KEY_LINE_NAME = "line_name";
	private static final String DUPLICATE_SUFFIX = "_duplicate";
	private static final Pattern DUPLICATE_PATTERN = Pattern.compile("(" + DUPLICATE_SUFFIX + ")+$");
	private static final Pattern DIRECTION_PATTERN = Pattern.compile("[\\s\\-_/:：·・]*[(（\\[【]?\\s*(上行|下行|內環|内环|外環|外环|内回り?|外回り?|上り|下り|(?<![a-z])(up|down|upbound|downbound|inbound|outbound|northbound|southbound|eastbound|westbound|clockwise|anticlockwise|counterclockwise))\\s*[)）\\]】]?$", Pattern.CASE_INSENSITIVE);

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
		messagePackHelper.iterateArrayValue(KEY_CUSTOM_PATH, value -> pathData.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
		timetable.enabled = messagePackHelper.getBoolean(KEY_TIMETABLE_ENABLED);
		timetable.marginPercent = messagePackHelper.getInt(KEY_TIMETABLE_MARGIN, RouteTimetable.DEFAULT_MARGIN_PERCENT);
		messagePackHelper.iterateArrayValue(KEY_TIMETABLE_SERVICES, service -> timetable.services.add(service.asIntegerValue().asInt()));
		timetable.sanitize();

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

		messagePacker.packString(KEY_TIMETABLE_ENABLED).packBoolean(timetable.enabled);
		messagePacker.packString(KEY_TIMETABLE_MARGIN).packInt(timetable.marginPercent);
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
		return super.messagePackLength() + 18 + (pathData.isEmpty() ? 0 : 1);
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

		packet.writeInt(pathData.size());
		pathData.forEach(data -> data.writePacket(packet));
		writeTimetable(packet);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_PLATFORM_IDS:
				platformIds.clear();
				final int platformCount = packet.readInt();
				for (int i = 0; i < platformCount; i++) {
					final RoutePlatform routePlatform = new RoutePlatform(packet.readLong());
					routePlatform.customDestination = packet.readUtf(PACKET_STRING_READ_LENGTH);
					routePlatform.readWaypoints(packet);
					if (i < MAX_PLATFORMS) {
						platformIds.add(routePlatform);
					}
				}
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
		sendPacket.accept(packet);
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
		route.circularState = circularState;
		route.lightRailRouteNumber = lightRailRouteNumber;
		route.lineName = lineName;
		route.pathData = new ArrayList<>(pathData);
		platformIds.forEach(routePlatform -> {
			final RoutePlatform newRoutePlatform = new RoutePlatform(routePlatform.platformId);
			newRoutePlatform.customDestination = routePlatform.customDestination;
			newRoutePlatform.waypoints.addAll(routePlatform.waypoints);
			newRoutePlatform.pass = routePlatform.pass;
			newRoutePlatform.dwellOverride = routePlatform.dwellOverride;
			newRoutePlatform.overtake = routePlatform.overtake;
			newRoutePlatform.setArrival(routePlatform.getArrival());
			newRoutePlatform.setDeparture(routePlatform.getDeparture());
			route.platformIds.add(newRoutePlatform);
		});
		route.timetable.enabled = timetable.enabled;
		route.timetable.marginPercent = timetable.marginPercent;
		route.timetable.services.addAll(timetable.services);
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

	public boolean isSameLine(Route route) {
		return route != null && getLineKey().equals(route.getLineKey());
	}

	/**
	 * Removes what only tells routes of one line apart: anything after {@code ||}, the copy suffix and direction words.
	 */
	public static String getAutoLineName(String name) {
		final String[] languages = name.split("\\|\\|", -1)[0].split("\\|", -1);
		for (int i = 0; i < languages.length; i++) {
			final String withoutSuffix = DUPLICATE_PATTERN.matcher(languages[i].trim()).replaceAll("");
			final String withoutDirection = DIRECTION_PATTERN.matcher(withoutSuffix).replaceAll("").trim();
			languages[i] = withoutDirection.isEmpty() ? withoutSuffix : withoutDirection;
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
		timetable.marginPercent = packet.readInt();
		timetable.services.clear();
		final int serviceCount = packet.readInt();
		for (int i = 0; i < serviceCount; i++) {
			final int service = packet.readInt();
			if (i < RouteTimetable.MAX_SERVICES) {
				timetable.services.add(service);
			}
		}
		timetable.sanitize();

		final int stopCount = packet.readInt();
		for (int i = 0; i < stopCount; i++) {
			final int arrival = packet.readInt();
			final int departure = packet.readInt();
			final boolean overtake = packet.readBoolean();
			final boolean pass = packet.readBoolean();
			if (i < platformIds.size()) {
				platformIds.get(i).setArrival(arrival);
				platformIds.get(i).setDeparture(departure);
				platformIds.get(i).overtake = overtake;
				platformIds.get(i).pass = pass;
			}
		}
		timetable.revision++;
	}

	private void writeTimetable(FriendlyByteBuf packet) {
		packet.writeBoolean(timetable.enabled);
		packet.writeInt(timetable.marginPercent);
		packet.writeInt(timetable.services.size());
		timetable.services.forEach(packet::writeInt);

		packet.writeInt(platformIds.size());
		platformIds.forEach(routePlatform -> {
			packet.writeInt(routePlatform.getArrival());
			packet.writeInt(routePlatform.getDeparture());
			packet.writeBoolean(routePlatform.overtake);
			packet.writeBoolean(routePlatform.pass);
		});
	}

	public static class RoutePlatform {

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
		private int arrival = -1;
		private int departure = -1;

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
		}

		private void writeWaypoints(FriendlyByteBuf packet) {
			packet.writeInt(waypoints.size());
			waypoints.forEach(waypoint -> packet.writeLong(waypoint.asLong()));
			packet.writeBoolean(pass);
			packet.writeInt(dwellOverride);
			packet.writeInt(arrival);
			packet.writeInt(departure);
			packet.writeBoolean(overtake);
		}
	}

	public enum CircularState {NONE, CLOCKWISE, ANTICLOCKWISE}
}
