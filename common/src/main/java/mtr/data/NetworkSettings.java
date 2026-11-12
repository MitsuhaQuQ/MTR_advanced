package mtr.data;

import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings of a dimension's whole network (optimisation round 4), kept in its save, sent to every player and changed from
 * the web editor by players who may edit.
 */
public final class NetworkSettings extends SerializedDataBase {

	/**
	 * The default least time to change ends: the 30 seconds trains always had to turn round.
	 */
	public static final long DEFAULT_MIN_REVERSE_MILLIS = transitsim.planning.DutyGenerator.MIN_TURNAROUND_MILLIS;
	/**
	 * The default lead a train early on its way may keep before it slows down its cruise (round 4, step 5).
	 */
	public static final long DEFAULT_EARLY_THRESHOLD_MILLIS = 30000;
	/**
	 * The longest either may be set to.
	 */
	public static final long MAX_MILLIS = 600000;
	/**
	 * Its only entry in the save.
	 */
	public static final long ID = 0;

	/**
	 * The least time a train takes to change ends where it turns back (换端最短时间), which plans allow everywhere.
	 */
	public long minReverseMillis = DEFAULT_MIN_REVERSE_MILLIS;
	/**
	 * How early a train may run before it slows down its cruise rather than waiting at the platform (round 4, step 5).
	 */
	public long earlyThresholdMillis = DEFAULT_EARLY_THRESHOLD_MILLIS;
	/**
	 * From how long a wait for the next service a train waits on a turn back rail rather than at the platform (运用补完
	 * step 2), for the whole network; stations may have their own, and a depot's rule its own again.
	 */
	public long waitThresholdMillis = DEFAULT_WAIT_THRESHOLD_MILLIS;
	/**
	 * Station to its own wait threshold, only the stations set.
	 */
	public final Map<Long, Long> stationWaitThresholds = new java.util.TreeMap<>();

	/**
	 * The owners of track sections set by hand (web layers step 2, 2026-11-09), by the key of a rail of the section
	 * ({@link transitsim.network.RailKeys}): the lines that may run there, or none for a section open to every line. Laid
	 * over the owners worked out from boundaries and platforms ({@link transitsim.network.TrackOwnership}).
	 */
	public final Map<java.util.UUID, List<String>> ownerOverrides = new java.util.TreeMap<>();

	public static final int MAX_OWNER_OVERRIDES = 4096;

	/**
	 * The logical lines of the track (逻辑线路, 2026-11-09), by ID: lines of the track itself, apart from the operating lines
	 * that run on them ({@link transitsim.network.LogicalLines}). With any, they decide which operating lines may run on
	 * each section instead of the owners worked out from boundaries and platforms.
	 */
	public final Map<Long, LogicalLine> logicalLines = new java.util.TreeMap<>();
	/**
	 * The logical line of each section put in one, by the key of a rail of it.
	 */
	public final Map<java.util.UUID, Long> railLines = new java.util.TreeMap<>();
	/**
	 * The logical lines each operating line may run on, by the line's key ({@link LineDuties#getLineKey}); a line not here
	 * runs on those its platforms are on.
	 */
	public final Map<String, List<Long>> lineTracks = new java.util.TreeMap<>();
	/**
	 * The running side of double track (user, 2026-11-12, {@link transitsim.network.RunningDirections}): {@code 1} right
	 * hand, {@code -1} left hand. By {@link #SIDE_NETWORK} for the network (right hand when not set), by {@code "L" + id}
	 * for a logical line, by {@code "R" + key} for an operating line ({@link LineDuties#getLineKey}); a line takes its own,
	 * else its logical lines' if they agree, else the network's.
	 */
	public final Map<String, Integer> runningSides = new java.util.TreeMap<>();
	public static final String SIDE_NETWORK = "";
	public static final int MAX_RUNNING_SIDES = 4096;

	/**
	 * Each route group's fares (design document 6.13, 0.68.0), by its group key ({@link FareData#getGroupKey}); a group not
	 * here is charged by the defaults.
	 */
	public final Map<String, transitsim.fare.FareLevels> fareLevels = new java.util.TreeMap<>();
	/**
	 * Routes put in another fare group by hand, by route ID: the group's key, an automatic one ({@link FareData#getAutoGroupKey})
	 * or a name of its own. A route not here is in the group of its line.
	 */
	public final Map<Long, String> fareRouteGroups = new java.util.TreeMap<>();
	/**
	 * The fare groups of stations set by hand, by station ID; with several, the station's gates are shared by them. A
	 * station not here belongs to the groups of the routes calling at it. Its gates go by the station they are in.
	 */
	public final Map<Long, List<String>> fareStationGroups = new java.util.TreeMap<>();
	public static final int MAX_FARE_GROUPS = 1024;
	public static final int MAX_FARE_ASSIGNMENTS = 65536;
	private static final int MAX_FARE_TIERS = transitsim.fare.FareLevels.MAX_TIERS;

	public static final int MAX_LOGICAL_LINES = 1024;
	public static final int MAX_RAIL_LINES = 65536;
	public static final int MAX_LINE_TRACKS = 4096;
	private static final int MAX_NAME_LENGTH = 256;

	public static final class LogicalLine {

		public final String name;
		public final int color;
		/**
		 * Every operating line may run on a connecting line.
		 */
		public final boolean connecting;

		public LogicalLine(String name, int color, boolean connecting) {
			this.name = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
			this.color = color;
			this.connecting = connecting;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof LogicalLine && ((LogicalLine) object).name.equals(name) && ((LogicalLine) object).color == color && ((LogicalLine) object).connecting == connecting;
		}

		@Override
		public int hashCode() {
			return (name.hashCode() * 31 + color) * 2 + (connecting ? 1 : 0);
		}
	}
	public static final int MAX_OVERRIDE_LINES = 32;
	private static final int MAX_LINE_KEY_LENGTH = 256;

	public static final long DEFAULT_WAIT_THRESHOLD_MILLIS = transitsim.sim.TrainService.LONG_TURN_BACK_WAIT_MILLIS;
	public static final long MAX_WAIT_THRESHOLD_MILLIS = 3600000;
	private static final int MAX_STATION_THRESHOLDS = 4096;

	private static final String KEY_MIN_REVERSE_MILLIS = "min_reverse_millis";
	private static final String KEY_EARLY_THRESHOLD_MILLIS = "early_threshold_millis";
	private static final String KEY_WAIT_THRESHOLD_MILLIS = "wait_threshold_millis";
	private static final String KEY_STATION_WAIT_THRESHOLDS = "station_wait_thresholds";
	private static final String KEY_OWNER_OVERRIDES = "owner_overrides";
	private static final String KEY_LOGICAL_LINES = "logical_lines";
	private static final String KEY_RAIL_LINES = "rail_lines";
	private static final String KEY_LINE_TRACKS = "line_tracks";
	private static final String KEY_FARE_LEVELS = "fare_levels";
	private static final String KEY_FARE_ROUTE_GROUPS = "fare_route_groups";
	private static final String KEY_FARE_STATION_GROUPS = "fare_station_groups";
	private static final String KEY_RUNNING_SIDES = "running_sides";

	public NetworkSettings() {
	}

	public NetworkSettings(Map<String, Value> map) {
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		minReverseMillis = clamp(messagePackHelper.getLong(KEY_MIN_REVERSE_MILLIS, DEFAULT_MIN_REVERSE_MILLIS));
		earlyThresholdMillis = clamp(messagePackHelper.getLong(KEY_EARLY_THRESHOLD_MILLIS, DEFAULT_EARLY_THRESHOLD_MILLIS));
		waitThresholdMillis = clampWait(messagePackHelper.getLong(KEY_WAIT_THRESHOLD_MILLIS, DEFAULT_WAIT_THRESHOLD_MILLIS));
		messagePackHelper.iterateMapValue(KEY_STATION_WAIT_THRESHOLDS, entry -> {
			if (stationWaitThresholds.size() < MAX_STATION_THRESHOLDS) {
				stationWaitThresholds.put(Long.parseLong(entry.getKey().asStringValue().asString()), clampWait(entry.getValue().asIntegerValue().asLong()));
			}
		});
		messagePackHelper.iterateMapValue(KEY_OWNER_OVERRIDES, entry -> {
			final java.util.UUID railKey = parseRailKey(entry.getKey().asStringValue().asString());
			if (railKey != null && ownerOverrides.size() < MAX_OWNER_OVERRIDES && entry.getValue().isArrayValue()) {
				final List<String> lines = new ArrayList<>();
				entry.getValue().asArrayValue().forEach(line -> {
					if (lines.size() < MAX_OVERRIDE_LINES && line.isStringValue()) {
						lines.add(line.asStringValue().asString());
					}
				});
				ownerOverrides.put(railKey, lines);
			}
		});
		messagePackHelper.iterateMapValue(KEY_LOGICAL_LINES, entry -> {
			if (logicalLines.size() < MAX_LOGICAL_LINES && entry.getValue().isMapValue()) {
				final Map<String, Value> line = RailwayData.castMessagePackValueToSKMap(entry.getValue());
				final MessagePackHelper lineHelper = new MessagePackHelper(line);
				try {
					logicalLines.put(Long.parseLong(entry.getKey().asStringValue().asString()), new LogicalLine(lineHelper.getString("name", ""), lineHelper.getInt("color", 0), lineHelper.getBoolean("connecting", false)));
				} catch (NumberFormatException ignored) {
				}
			}
		});
		messagePackHelper.iterateMapValue(KEY_RAIL_LINES, entry -> {
			final java.util.UUID railKey = parseRailKey(entry.getKey().asStringValue().asString());
			if (railKey != null && railLines.size() < MAX_RAIL_LINES && entry.getValue().isIntegerValue()) {
				railLines.put(railKey, entry.getValue().asIntegerValue().asLong());
			}
		});
		messagePackHelper.iterateMapValue(KEY_LINE_TRACKS, entry -> {
			if (lineTracks.size() < MAX_LINE_TRACKS && entry.getValue().isArrayValue()) {
				final List<Long> ids = new ArrayList<>();
				entry.getValue().asArrayValue().forEach(id -> {
					if (ids.size() < MAX_LOGICAL_LINES && id.isIntegerValue()) {
						ids.add(id.asIntegerValue().asLong());
					}
				});
				lineTracks.put(entry.getKey().asStringValue().asString(), ids);
			}
		});
		messagePackHelper.iterateMapValue(KEY_FARE_LEVELS, entry -> {
			if (fareLevels.size() < MAX_FARE_GROUPS && entry.getValue().isMapValue()) {
				fareLevels.put(entry.getKey().asStringValue().asString(), readFareLevels(new MessagePackHelper(RailwayData.castMessagePackValueToSKMap(entry.getValue()))));
			}
		});
		messagePackHelper.iterateMapValue(KEY_FARE_ROUTE_GROUPS, entry -> {
			if (fareRouteGroups.size() < MAX_FARE_ASSIGNMENTS && entry.getValue().isStringValue()) {
				try {
					fareRouteGroups.put(Long.parseLong(entry.getKey().asStringValue().asString()), entry.getValue().asStringValue().asString());
				} catch (NumberFormatException ignored) {
				}
			}
		});
		messagePackHelper.iterateMapValue(KEY_FARE_STATION_GROUPS, entry -> {
			if (fareStationGroups.size() < MAX_FARE_ASSIGNMENTS && entry.getValue().isArrayValue()) {
				final List<String> groups = new ArrayList<>();
				entry.getValue().asArrayValue().forEach(group -> {
					if (groups.size() < MAX_OVERRIDE_LINES && group.isStringValue()) {
						groups.add(group.asStringValue().asString());
					}
				});
				try {
					fareStationGroups.put(Long.parseLong(entry.getKey().asStringValue().asString()), groups);
				} catch (NumberFormatException ignored) {
				}
			}
		});
		messagePackHelper.iterateMapValue(KEY_RUNNING_SIDES, entry -> {
			if (runningSides.size() < MAX_RUNNING_SIDES && entry.getValue().isIntegerValue()) {
				final int side = entry.getValue().asIntegerValue().asInt();
				if (side == 1 || side == -1) {
					runningSides.put(entry.getKey().asStringValue().asString(), side);
				}
			}
		});
	}

	private static transitsim.fare.FareLevels readFareLevels(MessagePackHelper helper) {
		final transitsim.fare.FareLevels levels = new transitsim.fare.FareLevels();
		levels.startFare = helper.getInt("start_fare", levels.startFare);
		levels.initialBlocks = helper.getLong("initial_blocks", levels.initialBlocks);
		levels.unitBlocks = Math.max(1, helper.getLong("unit_blocks", levels.unitBlocks));
		levels.unitFare = helper.getInt("unit_fare", levels.unitFare);
		levels.maxBlocks = helper.getLong("max_blocks", levels.maxBlocks);
		levels.cap = helper.getInt("cap", levels.cap);
		levels.overtime = helper.getBoolean("overtime", levels.overtime);
		levels.overtimeMinutes = helper.getInt("overtime_minutes", levels.overtimeMinutes);
		levels.overtimeFine = helper.getInt("overtime_fine", levels.overtimeFine);
		final List<Long> bounds = new ArrayList<>();
		final List<Integer> prices = new ArrayList<>();
		helper.iterateArrayValue("bounds", value -> {
			if (bounds.size() < MAX_FARE_TIERS && value.isIntegerValue()) {
				bounds.add(value.asIntegerValue().asLong());
			}
		});
		helper.iterateArrayValue("prices", value -> {
			if (prices.size() < MAX_FARE_TIERS + 1 && value.isIntegerValue()) {
				prices.add(value.asIntegerValue().asInt());
			}
		});
		if (helper.getBoolean("manual_tiers", false) && prices.size() == bounds.size() + 1) {
			levels.setTiers(bounds.stream().mapToLong(Long::longValue).toArray(), prices.stream().mapToInt(Integer::intValue).toArray());
		}
		// No negative fares from a save edited by hand
		return levels.clamp();
	}

	/**
	 * @return whether the logical lines decide which operating lines may run where, as there are any
	 */
	public boolean usesLogicalLines() {
		return !logicalLines.isEmpty();
	}

	/**
	 * @return a rail key written as its two nodes, {@code "node1,node2"}, or null if it isn't one
	 */
	public static java.util.UUID parseRailKey(String text) {
		final String[] parts = text.split(",");
		try {
			return parts.length == 2 ? transitsim.network.RailKeys.of(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim())) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String railKeyText(java.util.UUID railKey) {
		return railKey.getMostSignificantBits() + "," + railKey.getLeastSignificantBits();
	}

	/**
	 * @return the wait threshold of a station: its own, or the network one
	 */
	public long getWaitThreshold(long stationId) {
		return stationWaitThresholds.getOrDefault(stationId, waitThresholdMillis);
	}

	public void read(FriendlyByteBuf packet) {
		minReverseMillis = clamp(packet.readLong());
		earlyThresholdMillis = clamp(packet.readLong());
		waitThresholdMillis = clampWait(packet.readLong());
		stationWaitThresholds.clear();
		final int count = packet.readVarInt();
		for (int i = 0; i < count; i++) {
			final long stationId = packet.readLong();
			final long millis = clampWait(packet.readLong());
			if (i < MAX_STATION_THRESHOLDS) {
				stationWaitThresholds.put(stationId, millis);
			}
		}
		ownerOverrides.clear();
		final int overrideCount = packet.readVarInt();
		if (overrideCount < 0 || overrideCount > MAX_OWNER_OVERRIDES) {
			throw new IllegalArgumentException("Too many owners set by hand: " + overrideCount);
		}
		for (int i = 0; i < overrideCount; i++) {
			final java.util.UUID railKey = transitsim.network.RailKeys.of(packet.readLong(), packet.readLong());
			final int lineCount = packet.readVarInt();
			if (lineCount < 0 || lineCount > MAX_OVERRIDE_LINES) {
				throw new IllegalArgumentException("Too many lines for a section: " + lineCount);
			}
			final List<String> lines = new ArrayList<>();
			for (int j = 0; j < lineCount; j++) {
				lines.add(packet.readUtf(MAX_LINE_KEY_LENGTH));
			}
			ownerOverrides.put(railKey, lines);
		}
		logicalLines.clear();
		final int lineCount = readCount(packet, MAX_LOGICAL_LINES);
		for (int i = 0; i < lineCount; i++) {
			final long id = packet.readLong();
			logicalLines.put(id, new LogicalLine(packet.readUtf(MAX_NAME_LENGTH), packet.readInt(), packet.readBoolean()));
		}
		railLines.clear();
		final int railCount = readCount(packet, MAX_RAIL_LINES);
		for (int i = 0; i < railCount; i++) {
			railLines.put(transitsim.network.RailKeys.of(packet.readLong(), packet.readLong()), packet.readLong());
		}
		lineTracks.clear();
		final int trackCount = readCount(packet, MAX_LINE_TRACKS);
		for (int i = 0; i < trackCount; i++) {
			final String lineKey = packet.readUtf(MAX_LINE_KEY_LENGTH);
			final int idCount = readCount(packet, MAX_LOGICAL_LINES);
			final List<Long> ids = new ArrayList<>();
			for (int j = 0; j < idCount; j++) {
				ids.add(packet.readLong());
			}
			lineTracks.put(lineKey, ids);
		}
		fareLevels.clear();
		final int groupCount = readCount(packet, MAX_FARE_GROUPS);
		for (int i = 0; i < groupCount; i++) {
			final String group = packet.readUtf(MAX_LINE_KEY_LENGTH);
			final transitsim.fare.FareLevels levels = new transitsim.fare.FareLevels();
			levels.startFare = packet.readVarInt();
			levels.initialBlocks = packet.readVarLong();
			levels.unitBlocks = Math.max(1, packet.readVarLong());
			levels.unitFare = packet.readVarInt();
			levels.maxBlocks = packet.readVarLong();
			levels.cap = packet.readInt();
			levels.overtime = packet.readBoolean();
			levels.overtimeMinutes = packet.readVarInt();
			levels.overtimeFine = packet.readVarInt();
			final boolean manual = packet.readBoolean();
			final int tierCount = readCount(packet, MAX_FARE_TIERS);
			final long[] bounds = new long[tierCount];
			for (int j = 0; j < tierCount; j++) {
				bounds[j] = packet.readVarLong();
			}
			final int[] prices = new int[tierCount + 1];
			for (int j = 0; j <= tierCount; j++) {
				prices[j] = packet.readVarInt();
			}
			if (manual) {
				levels.setTiers(bounds, prices);
			}
			// No negative fares from a player's packet (FarePackets.receiveSetC2S reads with this): they would pay for trips
			fareLevels.put(group, levels.clamp());
		}
		fareRouteGroups.clear();
		final int routeCount = readCount(packet, MAX_FARE_ASSIGNMENTS);
		for (int i = 0; i < routeCount; i++) {
			fareRouteGroups.put(packet.readLong(), packet.readUtf(MAX_LINE_KEY_LENGTH));
		}
		fareStationGroups.clear();
		final int stationCount = readCount(packet, MAX_FARE_ASSIGNMENTS);
		for (int i = 0; i < stationCount; i++) {
			final long stationId = packet.readLong();
			final int groupCount2 = readCount(packet, MAX_OVERRIDE_LINES);
			final List<String> groups = new ArrayList<>();
			for (int j = 0; j < groupCount2; j++) {
				groups.add(packet.readUtf(MAX_LINE_KEY_LENGTH));
			}
			fareStationGroups.put(stationId, groups);
		}
		runningSides.clear();
		final int sideCount = readCount(packet, MAX_RUNNING_SIDES);
		for (int i = 0; i < sideCount; i++) {
			final String key = packet.readUtf(MAX_LINE_KEY_LENGTH + 1);
			final int side = packet.readByte();
			if (side == 1 || side == -1) {
				runningSides.put(key, side);
			}
		}
	}

	private static int readCount(FriendlyByteBuf packet, int max) {
		final int count = packet.readVarInt();
		if (count < 0 || count > max) {
			throw new IllegalArgumentException("Too many: " + count);
		}
		return count;
	}

	public void copyFrom(NetworkSettings other) {
		minReverseMillis = other.minReverseMillis;
		earlyThresholdMillis = other.earlyThresholdMillis;
		waitThresholdMillis = other.waitThresholdMillis;
		stationWaitThresholds.clear();
		stationWaitThresholds.putAll(other.stationWaitThresholds);
		ownerOverrides.clear();
		other.ownerOverrides.forEach((railKey, lines) -> ownerOverrides.put(railKey, new ArrayList<>(lines)));
		logicalLines.clear();
		logicalLines.putAll(other.logicalLines);
		railLines.clear();
		railLines.putAll(other.railLines);
		lineTracks.clear();
		other.lineTracks.forEach((lineKey, ids) -> lineTracks.put(lineKey, new ArrayList<>(ids)));
		fareLevels.clear();
		other.fareLevels.forEach((group, levels) -> fareLevels.put(group, levels.copy()));
		fareRouteGroups.clear();
		fareRouteGroups.putAll(other.fareRouteGroups);
		fareStationGroups.clear();
		other.fareStationGroups.forEach((stationId, groups) -> fareStationGroups.put(stationId, new ArrayList<>(groups)));
		runningSides.clear();
		runningSides.putAll(other.runningSides);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_MIN_REVERSE_MILLIS).packLong(minReverseMillis);
		messagePacker.packString(KEY_EARLY_THRESHOLD_MILLIS).packLong(earlyThresholdMillis);
		messagePacker.packString(KEY_WAIT_THRESHOLD_MILLIS).packLong(waitThresholdMillis);
		messagePacker.packString(KEY_STATION_WAIT_THRESHOLDS).packMapHeader(stationWaitThresholds.size());
		for (final Map.Entry<Long, Long> entry : stationWaitThresholds.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packLong(entry.getValue());
		}
		messagePacker.packString(KEY_OWNER_OVERRIDES).packMapHeader(ownerOverrides.size());
		for (final Map.Entry<java.util.UUID, List<String>> entry : ownerOverrides.entrySet()) {
			messagePacker.packString(railKeyText(entry.getKey())).packArrayHeader(entry.getValue().size());
			for (final String line : entry.getValue()) {
				messagePacker.packString(line);
			}
		}
		messagePacker.packString(KEY_LOGICAL_LINES).packMapHeader(logicalLines.size());
		for (final Map.Entry<Long, LogicalLine> entry : logicalLines.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packMapHeader(3);
			messagePacker.packString("name").packString(entry.getValue().name);
			messagePacker.packString("color").packInt(entry.getValue().color);
			messagePacker.packString("connecting").packBoolean(entry.getValue().connecting);
		}
		messagePacker.packString(KEY_RAIL_LINES).packMapHeader(railLines.size());
		for (final Map.Entry<java.util.UUID, Long> entry : railLines.entrySet()) {
			messagePacker.packString(railKeyText(entry.getKey())).packLong(entry.getValue());
		}
		messagePacker.packString(KEY_LINE_TRACKS).packMapHeader(lineTracks.size());
		for (final Map.Entry<String, List<Long>> entry : lineTracks.entrySet()) {
			messagePacker.packString(entry.getKey()).packArrayHeader(entry.getValue().size());
			for (final long id : entry.getValue()) {
				messagePacker.packLong(id);
			}
		}
		messagePacker.packString(KEY_FARE_LEVELS).packMapHeader(fareLevels.size());
		for (final Map.Entry<String, transitsim.fare.FareLevels> entry : fareLevels.entrySet()) {
			final transitsim.fare.FareLevels levels = entry.getValue();
			messagePacker.packString(entry.getKey()).packMapHeader(12);
			messagePacker.packString("start_fare").packInt(levels.startFare);
			messagePacker.packString("initial_blocks").packLong(levels.initialBlocks);
			messagePacker.packString("unit_blocks").packLong(levels.unitBlocks);
			messagePacker.packString("unit_fare").packInt(levels.unitFare);
			messagePacker.packString("max_blocks").packLong(levels.maxBlocks);
			messagePacker.packString("cap").packInt(levels.cap);
			messagePacker.packString("overtime").packBoolean(levels.overtime);
			messagePacker.packString("overtime_minutes").packInt(levels.overtimeMinutes);
			messagePacker.packString("overtime_fine").packInt(levels.overtimeFine);
			messagePacker.packString("manual_tiers").packBoolean(levels.manualTiers);
			messagePacker.packString("bounds").packArrayHeader(levels.bounds.length);
			for (final long bound : levels.bounds) {
				messagePacker.packLong(bound);
			}
			messagePacker.packString("prices").packArrayHeader(levels.prices.length);
			for (final int price : levels.prices) {
				messagePacker.packInt(price);
			}
		}
		messagePacker.packString(KEY_FARE_ROUTE_GROUPS).packMapHeader(fareRouteGroups.size());
		for (final Map.Entry<Long, String> entry : fareRouteGroups.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packString(entry.getValue());
		}
		messagePacker.packString(KEY_FARE_STATION_GROUPS).packMapHeader(fareStationGroups.size());
		for (final Map.Entry<Long, List<String>> entry : fareStationGroups.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packArrayHeader(entry.getValue().size());
			for (final String group : entry.getValue()) {
				messagePacker.packString(group);
			}
		}
		messagePacker.packString(KEY_RUNNING_SIDES).packMapHeader(runningSides.size());
		for (final Map.Entry<String, Integer> entry : runningSides.entrySet()) {
			messagePacker.packString(entry.getKey()).packInt(entry.getValue());
		}
	}

	@Override
	public int messagePackLength() {
		return 12;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(minReverseMillis);
		packet.writeLong(earlyThresholdMillis);
		packet.writeLong(waitThresholdMillis);
		packet.writeVarInt(stationWaitThresholds.size());
		stationWaitThresholds.forEach((stationId, millis) -> {
			packet.writeLong(stationId);
			packet.writeLong(millis);
		});
		packet.writeVarInt(ownerOverrides.size());
		ownerOverrides.forEach((railKey, lines) -> {
			packet.writeLong(railKey.getMostSignificantBits());
			packet.writeLong(railKey.getLeastSignificantBits());
			packet.writeVarInt(lines.size());
			lines.forEach(line -> packet.writeUtf(line, MAX_LINE_KEY_LENGTH));
		});
		packet.writeVarInt(logicalLines.size());
		logicalLines.forEach((id, line) -> {
			packet.writeLong(id);
			packet.writeUtf(line.name, MAX_NAME_LENGTH);
			packet.writeInt(line.color);
			packet.writeBoolean(line.connecting);
		});
		packet.writeVarInt(railLines.size());
		railLines.forEach((railKey, id) -> {
			packet.writeLong(railKey.getMostSignificantBits());
			packet.writeLong(railKey.getLeastSignificantBits());
			packet.writeLong(id);
		});
		packet.writeVarInt(lineTracks.size());
		lineTracks.forEach((lineKey, ids) -> {
			packet.writeUtf(lineKey, MAX_LINE_KEY_LENGTH);
			packet.writeVarInt(ids.size());
			ids.forEach(packet::writeLong);
		});
		packet.writeVarInt(fareLevels.size());
		fareLevels.forEach((group, levels) -> {
			packet.writeUtf(group, MAX_LINE_KEY_LENGTH);
			packet.writeVarInt(levels.startFare);
			packet.writeVarLong(levels.initialBlocks);
			packet.writeVarLong(levels.unitBlocks);
			packet.writeVarInt(levels.unitFare);
			packet.writeVarLong(levels.maxBlocks);
			packet.writeInt(levels.cap);
			packet.writeBoolean(levels.overtime);
			packet.writeVarInt(levels.overtimeMinutes);
			packet.writeVarInt(levels.overtimeFine);
			final boolean manual = levels.manualTiers && levels.prices.length == levels.bounds.length + 1;
			packet.writeBoolean(manual);
			final long[] bounds = manual ? levels.bounds : new long[0];
			packet.writeVarInt(bounds.length);
			for (final long bound : bounds) {
				packet.writeVarLong(bound);
			}
			final int[] prices = manual ? levels.prices : new int[]{levels.startFare};
			for (final int price : prices) {
				packet.writeVarInt(price);
			}
		});
		packet.writeVarInt(fareRouteGroups.size());
		fareRouteGroups.forEach((routeId, group) -> {
			packet.writeLong(routeId);
			packet.writeUtf(group, MAX_LINE_KEY_LENGTH);
		});
		packet.writeVarInt(fareStationGroups.size());
		fareStationGroups.forEach((stationId, groups) -> {
			packet.writeLong(stationId);
			packet.writeVarInt(groups.size());
			groups.forEach(group -> packet.writeUtf(group, MAX_LINE_KEY_LENGTH));
		});
		packet.writeVarInt(runningSides.size());
		runningSides.forEach((key, side) -> {
			packet.writeUtf(key, MAX_LINE_KEY_LENGTH + 1);
			packet.writeByte(side);
		});
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof NetworkSettings && ((NetworkSettings) object).minReverseMillis == minReverseMillis && ((NetworkSettings) object).earlyThresholdMillis == earlyThresholdMillis && ((NetworkSettings) object).waitThresholdMillis == waitThresholdMillis && ((NetworkSettings) object).stationWaitThresholds.equals(stationWaitThresholds) && ((NetworkSettings) object).ownerOverrides.equals(ownerOverrides) && ((NetworkSettings) object).logicalLines.equals(logicalLines) && ((NetworkSettings) object).railLines.equals(railLines) && ((NetworkSettings) object).lineTracks.equals(lineTracks) && ((NetworkSettings) object).fareLevels.equals(fareLevels) && ((NetworkSettings) object).fareRouteGroups.equals(fareRouteGroups) && ((NetworkSettings) object).fareStationGroups.equals(fareStationGroups) && ((NetworkSettings) object).runningSides.equals(runningSides);
	}

	@Override
	public int hashCode() {
		return ((Long.hashCode(minReverseMillis) * 31 + Long.hashCode(earlyThresholdMillis)) * 31 + Long.hashCode(waitThresholdMillis)) * 31 + stationWaitThresholds.hashCode() + 17 * ownerOverrides.hashCode() + 19 * logicalLines.hashCode() + 23 * railLines.hashCode() + 29 * lineTracks.hashCode() + 37 * fareLevels.hashCode() + 41 * fareRouteGroups.hashCode() + 43 * fareStationGroups.hashCode() + 47 * runningSides.hashCode();
	}

	/**
	 * @return the running side of an operating line ({@link #runningSides}): 1 right hand, -1 left hand
	 */
	public int getRunningSide(String lineKey) {
		final Integer own = runningSides.get("R" + lineKey);
		if (own != null) {
			return own;
		}
		int logical = 0;
		for (final long id : lineTracks.getOrDefault(lineKey, java.util.Collections.emptyList())) {
			final Integer side = runningSides.get("L" + id);
			if (side != null) {
				if (logical != 0 && logical != side) {
					logical = 0;
					break;
				}
				logical = side;
			}
		}
		return logical != 0 ? logical : runningSides.getOrDefault(SIDE_NETWORK, transitsim.network.RunningDirections.RIGHT);
	}

	public static long clampWait(long millis) {
		return Math.max(0, Math.min(MAX_WAIT_THRESHOLD_MILLIS, millis));
	}

	public static long clamp(long millis) {
		return Math.max(0, Math.min(MAX_MILLIS, millis));
	}
}
