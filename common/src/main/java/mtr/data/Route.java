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
import java.util.Map;
import java.util.function.Consumer;

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
		messagePackHelper.iterateArrayValue(KEY_CUSTOM_PATH, value -> pathData.add(new PathData(RailwayData.castMessagePackValueToSKMap(value))));
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

		final int pathSize = packet.readInt();
		for (int i = 0; i < pathSize; i++) {
			pathData.add(new PathData(packet));
		}
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
		return super.messagePackLength() + 11 + (pathData.isEmpty() ? 0 : 1);
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

		packet.writeInt(pathData.size());
		pathData.forEach(data -> data.writePacket(packet));
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
		}

		private void writeWaypoints(FriendlyByteBuf packet) {
			packet.writeInt(waypoints.size());
			waypoints.forEach(waypoint -> packet.writeLong(waypoint.asLong()));
			packet.writeBoolean(pass);
			packet.writeInt(dwellOverride);
		}
	}

	public enum CircularState {NONE, CLOCKWISE, ANTICLOCKWISE}
}
