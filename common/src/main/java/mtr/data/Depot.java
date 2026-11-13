package mtr.data;

import transitsim.sim.DepotBoard;
import transitsim.sim.DepotDispatcher;
import transitsim.sim.RunRecord;
import transitsim.sim.TrainService;
import transitsim.plan.PlanRoute;
import transitsim.plan.DepotReturn;
import transitsim.plan.DutyPlan;
import transitsim.plan.TripPath;
import transitsim.plan.TripTail;
import transitsim.plan.TurnBackSection;
import transitsim.plan.TimeSegment;
import transitsim.plan.StopTimes;
import mtr.advanced.PacketBinary;
import transitsim.plan.RouteTimetable;
import io.netty.buffer.Unpooled;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.planning.DutyGenerator;
import transitsim.planning.AutoDuties;
import mtr.advanced.PathBoundaries;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import transitsim.network.RailGraph;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * A depot: an area holding sidings, the routes their trains run and how trains are sent out (by frequency, by
 * timetable or by duties).
 * <p>
 * {@link #generateMainRoute} finds the paths of all its sidings on a thread of its own, working on a copy of the rails and
 * data. {@link #deployTrain(RailwayData, Level)} runs every tick and sends trains out when it is time.
 * <p>
 * Depots running a route in common are linked when the data is synced ({@link #shareServicesWith}), so a service either
 * one's trains take counts as taken in both and runs once.
 */
public class Depot extends AreaBase implements IReducedSaveData, TrainService.Board {

	public int clientPathGenerationSuccessfulSegments;
	public PathFailure clientPathFailure = PathFailure.NONE;
	/**
	 * The keys of the duties whose paths the server's last path generation couldn't find.
	 */
	public List<String> clientFailedDuties = Collections.emptyList();
	public int clientFailedDutyCount;
	public long lastDeployedMillis;
	public boolean useRealTime;
	public boolean repeatInfinitely;
	public boolean useDeterministicPath = true;
	/**
	 * Trains turn back from the last route onto the first one and stay on the line, only going back to the depot after a service
	 * marked for it. Without a next service soon, they wait on the line for it, however long that is.
	 */
	public boolean cycleRoutes;
	/**
	 * Trains run on speed curves (design document section 8), braking ahead of slower rails and cruising slower to keep
	 * time. Depots made before this keep the old way of running until it is turned on.
	 */
	public boolean speedCurves;
	/**
	 * The shortest time in seconds between two trains the depot sends out by its duties, or 0 for none (decoupling
	 * document, step D, depots sending trains out 2).
	 */
	public int releaseInterval;
	public int cruisingAltitude = DEFAULT_CRUISING_ALTITUDE;
	private int deployIndex;
	private int departureOffset;
	private boolean isDirty = true;

	public final List<Long> routeIds = new ArrayList<>();
	public final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	public final List<Integer> departures = new ArrayList<>();
	public final List<Integer> tempDepartures = new ArrayList<>();
	/**
	 * The types of service each vehicle may run by vehicle ID, as text like {@code local, express}. Without one it runs everything.
	 */
	public final Map<String, String> vehicleServiceTypes = new HashMap<>();
	/**
	 * The trains the depot gives each pool of its sidings (P2-C), by the pool's key ({@link Siding#getPoolKey}); a pool
	 * left out has its sidings' fleets added up, as before, and -1 is as many as its duties need.
	 */
	public final Map<String, Integer> poolFleets = new TreeMap<>();
	/**
	 * On the server, how much longer than planned its trains take out, round and back, measured as they run (D1).
	 */
	public final transitsim.sim.BufferRecorder bufferRecorder = new transitsim.sim.BufferRecorder();
	/**
	 * On the server, the ways out corrected by how late their trains reach the first stop (user 2026-11-13), fed by
	 * {@link #bufferRecorder}; the times it asks to adopt wait in {@link #pendingOutLeads} for the next tick. Not saved: after
	 * a restart a way out found ineffective is tried once more.
	 */
	private final transitsim.sim.OutLeads outLeads = new transitsim.sim.OutLeads();
	private final Map<String, Long> pendingOutLeads = new TreeMap<>();
	private Map<String, Long> sentIneffective = Collections.emptyMap();

	{
		bufferRecorder.outListener = (key, lateness) -> {
			final long adopt = outLeads.add(key, lateness, this.addedBuffers.getMillis().getOrDefault(key, 0L));
			if (adopt >= 0) {
				synchronized (pendingOutLeads) {
					pendingOutLeads.put(key, adopt);
				}
			}
		};
	}
	/**
	 * On the clients, its trains reported late or stuck and those a contingency plan is on for
	 * ({@link mtr.packet.DelayAlarms}); on the server, as last sent.
	 */
	public volatile List<String> clientAlarms = Collections.emptyList();
	/**
	 * On the clients, the server's clock less theirs when the alarms came, to work out how late from the moments in them.
	 */
	public volatile long clientAlarmClockOffset;
	/**
	 * On the server, train → reason as last written to the log.
	 */
	public final Map<Long, String> loggedAlarms = new HashMap<>();
	/**
	 * How much longer than planned its trains were measured to take: the recorder's on the server, as sent on the clients.
	 * Suggestions only since optimisation round 4, step 6; the duties are planned with {@link #addedBuffers}.
	 */
	private transitsim.plan.Buffers buffers = transitsim.plan.Buffers.NONE;
	/**
	 * The time added to the ways out, turns round and ways back, as a player adopted it from what was measured or set it
	 * (round 4, step 6, user 2026-11-08: kept on the depot); what the duties are planned with.
	 */
	private transitsim.plan.Buffers addedBuffers = transitsim.plan.Buffers.NONE;
	/**
	 * How the trains of each pool of its sidings run (R1), by the pool's key; a pool left out runs as its first siding has
	 * it set.
	 */
	public final Map<String, VehicleSettings> vehicleSettings = new TreeMap<>();
	/**
	 * Whether its frequencies or list of departures were made its routes' services (R2), once, when its path was first
	 * found after; they are kept, but no longer send trains out, except for vehicles moving all the time and aircraft.
	 */
	public boolean frequenciesMigrated;
	/**
	 * Whether a player asked for the frequencies or the list of departures to be added to the timetable again, which adds
	 * them to routes already running by a timetable too; not saved.
	 */
	private boolean fillRequested;
	/**
	 * Which services each train runs, set in the advanced editor. See {@link DepotDuties}.
	 */
	public final DepotDuties duties = new DepotDuties();
	/**
	 * The duties worked out from the timetables when the depot runs by a timetable and has none set, made again with its
	 * paths (decoupling document, step D). The depot then only sends trains out when their duties start.
	 */
	public final DepotDuties autoDuties = new DepotDuties();
	/**
	 * How the depot's trains go out and back, by line and weekday, and its named deadhead paths (出入库规则, 运用补完 step 2).
	 */
	public final transitsim.plan.DepotRules rules = new transitsim.plan.DepotRules();
	/**
	 * The lines whose trains a siding of the depot reaching the line at more than one station has no rule saying which it
	 * goes by, as the last path generation found (运用补完 step 2): not planned until one does.
	 */
	public final Set<String> entrancesUnset = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/**
	 * What the auto duties were last worked out from, so they are worked out again when it changes; not saved, so they
	 * are worked out again once after the world is loaded.
	 */
	private String autoDutiesKey;
	/**
	 * The plan the auto duties were given from, as {@link LineDuties.Plan#toBytes} wrote it, or empty; server only.
	 */
	byte[] autoPlanRecord = new byte[0];
	/**
	 * The duty groups players asked to have planned again, by key, until the next path generation takes them.
	 */
	private final Set<String> replanGroups = new TreeSet<>();
	/**
	 * {@link #autoDutiesKey} once the depot no longer plans by line and its paths were found again for that.
	 */
	private static final String NOT_BY_LINE = "";
	/**
	 * What path generation gives for a depot running by duties whose routes were all found: more than any count of
	 * segments, so it reads as found.
	 */
	public static final int DUTIES_ONLY_FOUND = Integer.MAX_VALUE / 2;
	/**
	 * How far from a platform a turn back rail is still taken as that station's, in blocks, and how many are tried.
	 */
	static final int NEARBY_TURN_BACK_BLOCKS = 200;
	static final int MAX_NEARBY_TURN_BACKS = 6;
	/**
	 * A way between two services counts as a detour when longer than this many blocks and this many times as far as the
	 * two platforms are apart.
	 */
	static final int DETOUR_MIN_BLOCKS = 300;
	static final int DETOUR_FACTOR = 5;
	/**
	 * A way round for turning back early longer than this isn't taken (D4), as a train would run a long way empty.
	 */
	static final int MAX_TURN_LINK_BLOCKS = 1000;
	/**
	 * How far from its platform a change of platform may go, in blocks (2026-11-09).
	 */
	static final int MAX_PLATFORM_CHANGE_REACH = 300;
	private static final int MAX_RUN_PROBLEMS = 16;

	static double pathLength(List<PathData> path) {
		return path.stream().mapToDouble(pathData -> pathData.rail.getLength()).sum();
	}

	/**
	 * The ways between services the last path generation found to be detours, as "from-platform to-platform length apart".
	 */
	final Set<String> pathDetours = Collections.synchronizedSet(new TreeSet<>());
	public List<String> clientDetours = Collections.emptyList();
	/**
	 * How long each change of platform within a station of the depot's lines takes on the rails, from the last path
	 * generation: from platform → to platform → milliseconds; a pair left out has no way between. On the server for the
	 * plan, on the clients as sent, for the advanced editor.
	 */
	public volatile Map<Long, Map<Long, Long>> clientTurnTimes = Collections.emptyMap();
	/**
	 * Whether {@link #clientTurnTimes} were measured, so an empty table means no way between any of the platforms rather
	 * than not known yet.
	 */
	public volatile boolean turnTimesMeasured;
	/**
	 * The last few services set to go back to a depot whose train couldn't, see {@link #noteReturnOrderMissed}; on the
	 * clients as sent.
	 */
	public final List<String> returnOrderMisses = new ArrayList<>();
	/**
	 * Whether the problems trains met as they ran changed since they were last sent to the clients.
	 */
	public volatile boolean runProblemsChanged;
	/**
	 * The depot's trains waiting on the line with no duty, as last sent ({@link mtr.packet.HeldTrains}): on the server
	 * what the clients have, on the clients as sent.
	 */
	public volatile List<String> clientHeldTrains = Collections.emptyList();
	/**
	 * The depots of the depot's planning unit ({@link LineDuties}), itself among them, and the unit's key, or empty if it
	 * doesn't plan by line; worked out on the server thread with the auto duties and the paths, not saved. Its trains go back
	 * to sidings of these depots with the same vehicle too.
	 */
	private volatile Set<Long> lineDepotIds = Collections.emptySet();
	private volatile String lineUnitKey = "";
	/**
	 * The depot's sidings in their order, kept until the sidings, the data or the depot's area change, as sending trains
	 * out by frequency asks for them every tick.
	 */
	private List<Siding> sidingsInDepotCache;
	private String sidingsInDepotKey;
	/**
	 * Whether the lines of the depot's planning unit have trains enough, from the last time its duties were worked out,
	 * sent to the players with the auto duties; null if not planned by line.
	 */
	public Shortfall lineShortfall;
	/**
	 * The operating day each slot last ran its duty, as "siding:slot", so a duty runs once a day however its train gets to it.
	 */

	private final int[] frequencies = new int[HOURS_IN_DAY];
	/**
	 * For a depot of trains (R2): the minutes between trains in each hour of the real day, from midnight, 0 for none, which
	 * the depot screen fills the timetable by. An old depot's are its frequencies as close as whole minutes go.
	 */
	private final int[] headways = new int[HOURS_IN_DAY];
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();
	private DataCache dataCache;
	private final Map<String, List<Route>> trainRoutes = new HashMap<>();
	/**
	 * Which services trains have taken and which duties have run; saved here, decided in the component.
	 */
	private final DepotDispatcher dispatcher = new DepotDispatcher();

	{
		// What has run is kept by the duty's number, so a duty moved to another siding or place keeps it (P2-D)
		dispatcher.slotsOf = this::getDutySlots;
	}
	/**
	 * Whether the component's simulator sends out the depot's trains (G1).
	 */
	public boolean runBySimulator;

	public static final int HOURS_IN_DAY = 24;
	public static final int TRAIN_FREQUENCY_MULTIPLIER = 4;
	public static final int TICKS_PER_HOUR = 1000;
	public static final int MILLIS_PER_TICK = 50;
	public static final int MILLISECONDS_PER_DAY = HOURS_IN_DAY * 60 * 60 * 1000;
	public static final int DEFAULT_CRUISING_ALTITUDE = 256;
	private static final int TICKS_PER_DAY = HOURS_IN_DAY * TICKS_PER_HOUR;
	private static final int CONTINUOUS_MOVEMENT_FREQUENCY = 8000;
	private static final int THRESHOLD_ABOVE_MAX_BUILD_HEIGHT = 64;

	private static final String KEY_ROUTE_IDS = "route_ids";
	private static final int MAX_DEPARTURES = 4096;
	private static final int MAX_ROUTES = 1024;
	private static final int MAX_POOL_FLEET = 999;
	private static final String KEY_USE_REAL_TIME = "use_real_time";
	private static final String KEY_FREQUENCIES = "frequencies";
	private static final String KEY_HEADWAYS = "headway_minutes";
	private static final String KEY_DEPARTURES = "departures";
	private static final String KEY_LAST_DEPLOYED = "last_deployed";
	private static final String KEY_LAST_DEPLOYED_TRIPS = "last_deployed_trips";
	private static final String KEY_VEHICLE_SERVICE_TYPES = "vehicle_service_types";
	private static final String KEY_POOL_FLEETS = "pool_fleets";
	private static final String KEY_MEASURED_BUFFERS = "measured_buffers";
	private static final String KEY_ADDED_BUFFERS = "added_buffers";
	private static final String KEY_DEPOT_RULES = "depot_rules";
	private static final String KEY_AREA_HEIGHTS = "area_heights";
	/**
	 * What the auto duties were planned from and what the plan found ({@link LineDuties.Plan#toBytes}), so they are kept
	 * while they still hold (round 4, step 4).
	 */
	private static final String KEY_AUTO_PLAN = "auto_duties_basis";
	private static final String KEY_VEHICLE_SETTINGS = "vehicle_settings";
	private static final String KEY_FREQUENCIES_MIGRATED = "frequencies_migrated";
	private static final String KEY_DEPLOY_INDEX = "deploy_index";
	private static final String KEY_REPEAT_INFINITELY = "repeat_infinitely";
	private static final String KEY_CRUISING_ALTITUDE = "cruising_altitude";
	private static final String KEY_USE_DETERMINISTIC_PATH = "use_deterministic_path";
	private static final String KEY_CYCLE_ROUTES = "cycle_routes";
	private static final String KEY_SPEED_CURVES = "speed_curves";
	private static final String KEY_RELEASE_INTERVAL = "release_interval";
	public static final int MAX_RELEASE_INTERVAL = 3600;
	private static final String KEY_DUTIES = "duties";
	private static final String KEY_AUTO_DUTIES = "auto_duties";
	private static final String KEY_DUTY_MODE = "duty_mode";
	private static final String KEY_DUTY_SLOT = "duty_slot";
	private static final String KEY_DUTY_RUN_DAYS = "duty_run_days";
	/**
	 * The duties a spare takes over (D5), as {@link DepotDispatcher#takeovers}.
	 */
	private static final String KEY_DUTY_TAKEOVERS = "duty_takeovers";
	/**
	 * The services late trains gave up, are expected to miss, or took over (0.78.0), as {@link transitsim.sim.LateServices#toList()}.
	 */
	private static final String KEY_LATE_SERVICES = "late_services";
	private static final String DUTY_MODE_DIAGRAM = "diagram";
	private static final String DUTY_MODE_ONLINE = "online";

	public Depot(TransportMode transportMode) {
		super(transportMode);
		useRealTime = !transportMode.continuousMovement;
		speedCurves = true;
	}

	public Depot(long id, TransportMode transportMode) {
		super(id, transportMode);
		speedCurves = true;
	}

	public Depot(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		messagePackHelper.iterateArrayValue(KEY_ROUTE_IDS, routeId -> routeIds.add(routeId.asIntegerValue().asLong()));
		useRealTime = messagePackHelper.getBoolean(KEY_USE_REAL_TIME);

		try {
			final ArrayValue frequenciesArray = map.get(KEY_FREQUENCIES).asArrayValue();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = frequenciesArray.get(i).asIntegerValue().asInt();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		messagePackHelper.iterateArrayValue(KEY_DEPARTURES, departure -> departures.add(departure.asIntegerValue().asInt()));

		deployIndex = Math.max(0, messagePackHelper.getInt(KEY_DEPLOY_INDEX));
		repeatInfinitely = messagePackHelper.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = messagePackHelper.getInt(KEY_CRUISING_ALTITUDE);
		useDeterministicPath = messagePackHelper.getBoolean(KEY_USE_DETERMINISTIC_PATH);
		cycleRoutes = messagePackHelper.getBoolean(KEY_CYCLE_ROUTES);
		speedCurves = messagePackHelper.getBoolean(KEY_SPEED_CURVES) || supportsTimetable(transportMode);
		releaseInterval = Mth.clamp(messagePackHelper.getInt(KEY_RELEASE_INTERVAL, 0), 0, MAX_RELEASE_INTERVAL);
		lastDeployedMillis = System.currentTimeMillis() - messagePackHelper.getLong(KEY_LAST_DEPLOYED);
		messagePackHelper.iterateMapValue(KEY_LAST_DEPLOYED_TRIPS, entry -> dispatcher.lastDeployedTrips.put(Long.parseLong(entry.getKey().asStringValue().asString()), entry.getValue().asIntegerValue().asLong()));
		messagePackHelper.iterateMapValue(KEY_VEHICLE_SERVICE_TYPES, entry -> vehicleServiceTypes.put(entry.getKey().asStringValue().asString(), entry.getValue().asStringValue().asString()));
		messagePackHelper.iterateMapValue(KEY_POOL_FLEETS, entry -> poolFleets.put(entry.getKey().asStringValue().asString(), Mth.clamp(entry.getValue().asIntegerValue().asInt(), -1, MAX_POOL_FLEET)));
		frequenciesMigrated = messagePackHelper.getBoolean(KEY_FREQUENCIES_MIGRATED);
		if (map.containsKey(KEY_HEADWAYS)) {
			final List<Integer> savedHeadways = new ArrayList<>();
			messagePackHelper.iterateArrayValue(KEY_HEADWAYS, value -> savedHeadways.add(value.asIntegerValue().asInt()));
			for (int i = 0; i < Math.min(HOURS_IN_DAY, savedHeadways.size()); i++) {
				headways[i] = Mth.clamp(savedHeadways.get(i), 0, transitsim.planning.FrequencyTimetable.MAX_HEADWAY_MINUTES);
			}
		} else {
			headwaysFromFrequencies();
		}
		messagePackHelper.iterateMapValue(KEY_VEHICLE_SETTINGS, entry -> {
			try {
				final org.msgpack.value.ArrayValue values = entry.getValue().asArrayValue();
				// The highest speed is saved from 0.34.0
				vehicleSettings.put(entry.getKey().asStringValue().asString(), new VehicleSettings(values.get(0).asFloatValue().toFloat(), values.get(1).asFloatValue().toFloat(), values.get(2).asBooleanValue().getBoolean(), values.get(3).asIntegerValue().asInt(), values.get(4).asIntegerValue().asInt(), values.size() > 5 ? values.get(5).asIntegerValue().asInt() : 0));
			} catch (Exception ignored) {
			}
		});
		final Value dutiesValue = map.get(KEY_DUTIES);
		if (dutiesValue != null && dutiesValue.isBinaryValue()) {
			duties.fromBytes(dutiesValue.asBinaryValue().asByteArray());
		}
		duties.diagramMode = DUTY_MODE_DIAGRAM.equals(messagePackHelper.getString(KEY_DUTY_MODE));
		final Value autoDutiesValue = map.get(KEY_AUTO_DUTIES);
		if (autoDutiesValue != null && autoDutiesValue.isBinaryValue()) {
			autoDuties.fromBytes(autoDutiesValue.asBinaryValue().asByteArray());
		}
		final Value rulesValue = map.get(KEY_DEPOT_RULES);
		if (rulesValue != null && rulesValue.isBinaryValue()) {
			rules.fromBytes(rulesValue.asBinaryValue().asByteArray());
		}
		final Value heightsValue = map.get(KEY_AREA_HEIGHTS);
		if (heightsValue != null && heightsValue.isArrayValue() && heightsValue.asArrayValue().size() == 2) {
			setAreaHeights(heightsValue.asArrayValue().get(0).asIntegerValue().asInt(), heightsValue.asArrayValue().get(1).asIntegerValue().asInt());
		}
		final Value autoPlanValue = map.get(KEY_AUTO_PLAN);
		if (autoPlanValue != null && autoPlanValue.isBinaryValue()) {
			autoPlanRecord = autoPlanValue.asBinaryValue().asByteArray();
		}
		messagePackHelper.iterateMapValue(KEY_DUTY_RUN_DAYS, entry -> dispatcher.dutyRunDays.put(entry.getKey().asStringValue().asString(), entry.getValue().asIntegerValue().asLong()));
		messagePackHelper.iterateArrayValue(KEY_DUTY_TAKEOVERS, value -> dispatcher.takeovers.add(value.asStringValue().asString()));
		final List<String> lateServices = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_LATE_SERVICES, value -> lateServices.add(value.asStringValue().asString()));
		dispatcher.lateServices.addAll(lateServices);
		final List<Long> bufferSamples = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_MEASURED_BUFFERS, value -> bufferSamples.add(value.asIntegerValue().asLong()));
		bufferRecorder.readList(bufferSamples);
		buffers = bufferRecorder.getBuffers();
		final List<Long> added = new ArrayList<>();
		messagePackHelper.iterateArrayValue(KEY_ADDED_BUFFERS, value -> added.add(value.asIntegerValue().asLong()));
		addedBuffers = limitAdded(transitsim.plan.Buffers.fromList(added));
		dispatcher.addedBuffers = addedBuffers;
	}

	@Deprecated
	public Depot(CompoundTag compoundTag) {
		super(compoundTag);

		final long[] routeIdsArray = compoundTag.getLongArray(KEY_ROUTE_IDS);
		for (final long routeId : routeIdsArray) {
			routeIds.add(routeId);
		}

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = compoundTag.getInt(KEY_FREQUENCIES + i);
		}
		headwaysFromFrequencies();

		lastDeployedMillis = System.currentTimeMillis() - compoundTag.getLong(KEY_LAST_DEPLOYED);
		deployIndex = Math.max(0, compoundTag.getInt(KEY_DEPLOY_INDEX));
		repeatInfinitely = compoundTag.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = compoundTag.getInt(KEY_CRUISING_ALTITUDE);
		useDeterministicPath = false;
	}

	public Depot(FriendlyByteBuf packet) {
		super(packet);

		final int routeIdCount = packet.readInt();
		for (int i = 0; i < routeIdCount; i++) {
			routeIds.add(packet.readLong());
		}

		useRealTime = packet.readBoolean();

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = packet.readInt();
		}

		final int departuresCount = packet.readInt();
		for (int i = 0; i < departuresCount; i++) {
			departures.add(packet.readInt());
		}

		lastDeployedMillis = packet.readLong();
		deployIndex = packet.readInt();
		repeatInfinitely = packet.readBoolean();
		cruisingAltitude = packet.readInt();
		useDeterministicPath = packet.readBoolean();
		cycleRoutes = packet.readBoolean();
		readVehicleServiceTypes(packet);
		duties.read(PacketBinary.of(packet));
		speedCurves = packet.readBoolean() || supportsTimetable(transportMode);
		autoDuties.read(PacketBinary.of(packet));
		releaseInterval = packet.readInt();
		lineShortfall = Shortfall.read(packet);
		readPoolFleets(packet);
		readVehicleSettings(packet);
		frequenciesMigrated = packet.readBoolean();
		readHeadways(packet);
		readBuffers(packet);
		addedBuffers = readBufferList(packet);
		dispatcher.addedBuffers = addedBuffers;
		rules.fromBytes(packet.readByteArray(1 << 20));
		readAreaHeights(packet);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		messagePacker.packString(KEY_DEPLOY_INDEX).packInt(deployIndex);
		messagePacker.packString(KEY_LAST_DEPLOYED).packLong(System.currentTimeMillis() - lastDeployedMillis);
		messagePacker.packString(KEY_LAST_DEPLOYED_TRIPS).packMapHeader(dispatcher.lastDeployedTrips.size());
		for (final Map.Entry<Long, Long> entry : dispatcher.lastDeployedTrips.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packLong(entry.getValue());
		}
		messagePacker.packString(KEY_DUTY_RUN_DAYS).packMapHeader(dispatcher.dutyRunDays.size());
		for (final Map.Entry<String, Long> entry : dispatcher.dutyRunDays.entrySet()) {
			messagePacker.packString(entry.getKey()).packLong(entry.getValue());
		}
		messagePacker.packString(KEY_DUTY_TAKEOVERS).packArrayHeader(dispatcher.takeovers.size());
		for (final String takeover : dispatcher.takeovers) {
			messagePacker.packString(takeover);
		}
		final List<String> lateServices = dispatcher.lateServices.toList();
		messagePacker.packString(KEY_LATE_SERVICES).packArrayHeader(lateServices.size());
		for (final String line : lateServices) {
			messagePacker.packString(line);
		}
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_ROUTE_IDS).packArrayHeader(routeIds.size());
		for (final long routeId : routeIds) {
			messagePacker.packLong(routeId);
		}

		messagePacker.packString(KEY_USE_REAL_TIME).packBoolean(useRealTime);
		messagePacker.packString(KEY_REPEAT_INFINITELY).packBoolean(repeatInfinitely);
		messagePacker.packString(KEY_CRUISING_ALTITUDE).packInt(cruisingAltitude);
		messagePacker.packString(KEY_USE_DETERMINISTIC_PATH).packBoolean(useDeterministicPath);
		messagePacker.packString(KEY_CYCLE_ROUTES).packBoolean(cycleRoutes);
		messagePacker.packString(KEY_SPEED_CURVES).packBoolean(speedCurves);
		messagePacker.packString(KEY_RELEASE_INTERVAL).packInt(releaseInterval);

		messagePacker.packString(KEY_FREQUENCIES).packArrayHeader(HOURS_IN_DAY);
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			messagePacker.packInt(frequencies[i]);
		}

		messagePacker.packString(KEY_DEPARTURES).packArrayHeader(departures.size());
		for (final int departure : departures) {
			messagePacker.packInt(departure);
		}

		messagePacker.packString(KEY_VEHICLE_SERVICE_TYPES).packMapHeader(vehicleServiceTypes.size());
		for (final Map.Entry<String, String> entry : vehicleServiceTypes.entrySet()) {
			messagePacker.packString(entry.getKey()).packString(entry.getValue());
		}

		messagePacker.packString(KEY_POOL_FLEETS).packMapHeader(poolFleets.size());
		for (final Map.Entry<String, Integer> entry : poolFleets.entrySet()) {
			messagePacker.packString(entry.getKey()).packInt(entry.getValue());
		}

		messagePacker.packString(KEY_FREQUENCIES_MIGRATED).packBoolean(frequenciesMigrated);
		messagePacker.packString(KEY_HEADWAYS).packArrayHeader(HOURS_IN_DAY);
		for (final int headway : headways) {
			messagePacker.packInt(headway);
		}

		messagePacker.packString(KEY_VEHICLE_SETTINGS).packMapHeader(vehicleSettings.size());
		for (final Map.Entry<String, VehicleSettings> entry : vehicleSettings.entrySet()) {
			final VehicleSettings settings = entry.getValue();
			messagePacker.packString(entry.getKey()).packArrayHeader(6).packFloat(settings.acceleration).packFloat(settings.deceleration).packBoolean(settings.manual).packInt(settings.maxManualSpeed).packInt(settings.manualToAutomaticTime).packInt(settings.maxSpeed);
		}

		final byte[] dutyBytes = duties.toBytes();
		messagePacker.packString(KEY_DUTIES).packBinaryHeader(dutyBytes.length);
		messagePacker.writePayload(dutyBytes);
		messagePacker.packString(KEY_DUTY_MODE).packString(duties.diagramMode ? DUTY_MODE_DIAGRAM : DUTY_MODE_ONLINE);
		final byte[] autoDutyBytes = autoDuties.toBytes();
		messagePacker.packString(KEY_AUTO_DUTIES).packBinaryHeader(autoDutyBytes.length);
		messagePacker.writePayload(autoDutyBytes);
		messagePacker.packString(KEY_AUTO_PLAN).packBinaryHeader(autoPlanRecord.length);
		messagePacker.writePayload(autoPlanRecord);
		final byte[] rulesBytes = rules.toBytes();
		messagePacker.packString(KEY_DEPOT_RULES).packBinaryHeader(rulesBytes.length);
		messagePacker.writePayload(rulesBytes);
		// The heights the area takes in, none for every height (2026-11-09)
		messagePacker.packString(KEY_AREA_HEIGHTS).packArrayHeader(areaHeights == null ? 0 : 2);
		if (areaHeights != null) {
			messagePacker.packInt(areaHeights[0]).packInt(areaHeights[1]);
		}
		final List<Long> bufferSamples = bufferRecorder.toList();
		messagePacker.packString(KEY_MEASURED_BUFFERS).packArrayHeader(bufferSamples.size());
		for (final long value : bufferSamples) {
			messagePacker.packLong(value);
		}
		final List<Long> added = addedBuffers.toList();
		messagePacker.packString(KEY_ADDED_BUFFERS).packArrayHeader(added.size());
		for (final long value : added) {
			messagePacker.packLong(value);
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 29;
	}

	/**
	 * Without the six entries only {@link #toMessagePack} writes: where it left off sending, when, the trips sent, the
	 * duties run, the takeovers (the takeovers were left out of the count, so a save's header stopped short of them and
	 * they were never read back, until 2026-11-09) and the services late trains gave up or took over (0.78.0).
	 */
	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 6;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);

		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);

		packet.writeBoolean(useRealTime);

		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}

		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);

		packet.writeLong(lastDeployedMillis);
		packet.writeInt(deployIndex);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
		packet.writeBoolean(useDeterministicPath);
		packet.writeBoolean(cycleRoutes);
		writeVehicleServiceTypes(packet);
		duties.write(PacketBinary.of(packet));
		packet.writeBoolean(speedCurves);
		autoDuties.write(PacketBinary.of(packet));
		packet.writeInt(releaseInterval);
		Shortfall.write(lineShortfall, packet);
		writePoolFleets(packet);
		writeVehicleSettings(packet);
		packet.writeBoolean(frequenciesMigrated);
		writeHeadways(packet);
		writeBuffers(packet);
		writeBufferList(packet, addedBuffers);
		packet.writeByteArray(rules.toBytes());
		writeAreaHeights(packet);
	}

	/**
	 * The heights the depot's area takes in, {lowest, highest}, or null for every height, as before (2026-11-09): set with
	 * the station area tool, so depots one above another each take in their own sidings.
	 */
	private int[] areaHeights;

	@Override
	public boolean inArea(int x, int y, int z) {
		return inArea(x, z) && (areaHeights == null || y >= areaHeights[0] && y <= areaHeights[1]);
	}

	@Override
	public int[] getHeight(int[] rectangle) {
		return areaHeights == null ? null : areaHeights.clone();
	}

	public void setAreaHeights(int y1, int y2) {
		areaHeights = new int[]{Math.min(y1, y2), Math.max(y1, y2)};
	}

	public void clearAreaHeights() {
		areaHeights = null;
	}

	/**
	 * Sends the heights the area takes in, as set with the station area tool.
	 */
	public void sendAreaHeights(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_AREA_HEIGHTS);
		writeAreaHeights(packet);
		sendPacket.accept(packet);
		isDirty = true;
	}

	private void writeAreaHeights(FriendlyByteBuf packet) {
		packet.writeBoolean(areaHeights != null);
		if (areaHeights != null) {
			packet.writeInt(areaHeights[0]);
			packet.writeInt(areaHeights[1]);
		}
	}

	private void readAreaHeights(FriendlyByteBuf packet) {
		if (packet.isReadable() && packet.readBoolean()) {
			setAreaHeights(packet.readInt(), packet.readInt());
		} else {
			areaHeights = null;
		}
	}

	private void writeBuffers(FriendlyByteBuf packet) {
		writeBufferList(packet, buffers);
	}

	private void readBuffers(FriendlyByteBuf packet) {
		buffers = readBufferList(packet);
	}

	private static void writeBufferList(FriendlyByteBuf packet, transitsim.plan.Buffers bufferList) {
		final List<Long> list = bufferList.toList();
		packet.writeVarInt(list.size());
		list.forEach(packet::writeLong);
	}

	private static transitsim.plan.Buffers readBufferList(FriendlyByteBuf packet) {
		final int size = packet.readVarInt();
		if (size < 0 || size > 1 + 4 * MAX_ADDED_BUFFERS) {
			throw new IllegalArgumentException("Too many buffers: " + size);
		}
		final List<Long> list = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			list.add(packet.readLong());
		}
		return transitsim.plan.Buffers.fromList(list);
	}

	/**
	 * At most as many as a recorder keeps, each from nothing to the most a recorder takes.
	 */
	private static final int MAX_ADDED_BUFFERS = 1024;

	private static transitsim.plan.Buffers limitAdded(transitsim.plan.Buffers added) {
		final Map<String, Long> millis = new TreeMap<>();
		added.getMillis().forEach((key, value) -> {
			if (millis.size() < MAX_ADDED_BUFFERS && value > 0) {
				millis.put(key, Math.min(value, transitsim.sim.BufferRecorder.MAX_EXCESS_MILLIS));
			}
		});
		return millis.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(millis);
	}

	/**
	 * @return how much longer than planned its trains were measured to take out, round and back (D1): suggestions, which
	 * a player may adopt ({@link #getAddedBuffers})
	 */
	public transitsim.plan.Buffers getBuffers() {
		return buffers;
	}

	/**
	 * @return the time added to the ways out, turns round and ways back that its duties are planned with, as adopted (round
	 * 4, step 6)
	 */
	public transitsim.plan.Buffers getAddedBuffers() {
		return addedBuffers;
	}

	/**
	 * Sets the time added to the ways out, turns round and ways back, from the web editor (round 4, step 6).
	 */
	public void setAddedBuffers(transitsim.plan.Buffers newAddedBuffers, Consumer<FriendlyByteBuf> sendPacket) {
		addedBuffers = limitAdded(newAddedBuffers);
		dispatcher.addedBuffers = addedBuffers;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_ADDED_BUFFERS);
		writeBufferList(packet, addedBuffers);
		sendPacket.accept(packet);
		isDirty = true;
	}

	/**
	 * Sends the players the buffers if they changed by more than a little since they were last sent (D1).
	 */
	public void sendBuffersIfChanged(Consumer<FriendlyByteBuf> sendPacket) {
		adoptOutLeads(sendPacket);
		final Map<String, Long> ineffective = outLeads.getIneffective();
		if (bufferRecorder.takeChanged() || !ineffective.equals(sentIneffective)) {
			buffers = withIneffective(bufferRecorder.getBuffers(), ineffective);
			sentIneffective = ineffective;
			sendBuffers(sendPacket);
			isDirty = true;
		}
	}

	/**
	 * @return what was measured, with each way out that leaving earlier didn't help as how late its trains still were, made
	 * negative so the web editor tells it apart (2026-11-13)
	 */
	private static transitsim.plan.Buffers withIneffective(transitsim.plan.Buffers measured, Map<String, Long> ineffective) {
		if (ineffective.isEmpty()) {
			return measured;
		}
		final Map<String, Long> millis = new TreeMap<>(measured.getMillis());
		ineffective.forEach((key, lateness) -> millis.put(key, -Math.max(1, lateness)));
		return new transitsim.plan.Buffers(millis);
	}

	/**
	 * The ways out measured longer than adopted are adopted as measured (user 2026-11-13: 出库提前量按实际跑的记下来自动校正),
	 * so the duties' trains leave the siding that much earlier from the next plan on; turning round and the ways back stay
	 * suggestions (round 4, step 6). Only ever raised: lowering one is left to a player, in the web editor.
	 */
	private void adoptOutLeads(Consumer<FriendlyByteBuf> sendPacket) {
		final Map<String, Long> pending;
		synchronized (pendingOutLeads) {
			if (pendingOutLeads.isEmpty()) {
				return;
			}
			pending = new TreeMap<>(pendingOutLeads);
			pendingOutLeads.clear();
		}
		final Map<String, Long> adopted = new TreeMap<>(addedBuffers.getMillis());
		final Map<String, Long> ineffective = outLeads.getIneffective();
		final List<String> changes = new ArrayList<>();
		pending.forEach((key, millis) -> {
			final long before = adopted.getOrDefault(key, 0L);
			if (millis > 0) {
				adopted.put(key, millis);
			} else {
				adopted.remove(key);
			}
			changes.add(key + " " + before / 1000 + "s -> " + millis / 1000 + "s" + (ineffective.containsKey(key) ? " (leaving earlier didn't help: still " + ineffective.get(key) / 1000 + "s late, waiting on the way for something on the clock, such as a service train on a turn back track; change the timetable or the way)" : ""));
		});
		setAddedBuffers(adopted.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(adopted), sendPacket);
		System.out.println("Minecraft Transit Railway: depot " + (name.isEmpty() ? String.valueOf(id) : name) + " corrected the ways out by how late their trains reached the first stop (out:siding:platform): " + String.join("; ", changes));
	}

	/**
	 * Forgets what was measured, from the web editor (D1).
	 */
	public void clearBuffers(Consumer<FriendlyByteBuf> sendPacket) {
		bufferRecorder.clear();
		bufferRecorder.takeChanged();
		outLeads.clear();
		sentIneffective = Collections.emptyMap();
		synchronized (pendingOutLeads) {
			pendingOutLeads.clear();
		}
		buffers = transitsim.plan.Buffers.NONE;
		sendBuffers(sendPacket);
		isDirty = true;
	}

	/**
	 * Hands the duties set for the depot on to their pools where a siding is gone or now holds another vehicle (运用补完
	 * step 5, {@link DepotDuties#rehome}), after the pools are gathered each tick; looked at again only when the sidings,
	 * their pools or the duties' sidings changed. Duties worked out from the timetables are planned again for new sidings
	 * anyway.
	 *
	 * @param sendPacket where the duties go to the players when slots moved
	 */
	public void rehomeDuties(Consumer<FriendlyByteBuf> sendPacket) {
		if (duties.sidings.isEmpty() && duties.unplaced.isEmpty()) {
			return;
		}
		long signature = duties.sidings.keySet().hashCode() * 31L + duties.unplaced.keySet().hashCode();
		for (final Map.Entry<String, List<Siding>> entry : pools.entrySet()) {
			for (final Siding siding : entry.getValue()) {
				signature = signature * 31 + Long.hashCode(siding.id) * 17L + entry.getKey().hashCode();
			}
		}
		if (signature == rehomeSignature) {
			return;
		}
		rehomeSignature = signature;
		final Map<Long, String> livePools = new TreeMap<>();
		pools.forEach((poolKey, poolSidings) -> poolSidings.forEach(siding -> livePools.put(siding.id, poolKey)));
		final Map<Long, String> recordedBefore = new TreeMap<>(duties.sidingPools);
		final boolean moved = duties.rehome(livePools);
		if (moved) {
			final int unplacedCount = duties.unplaced.values().stream().mapToInt(List::size).sum();
			System.out.println("Minecraft Transit Railway: depot " + (name.isEmpty() ? String.valueOf(id) : name) + " handed duties of sidings gone or changed to another vehicle on to their pools" + (unplacedCount > 0 ? "; " + unplacedCount + " left with no siding of their vehicle, not run" : ""));
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeLong(id);
			packet.writeUtf(transportMode.toString());
			packet.writeUtf(KEY_DUTIES);
			duties.write(PacketBinary.of(packet));
			sendPacket.accept(packet);
		}
		if (moved || !recordedBefore.equals(duties.sidingPools)) {
			isDirty = true;
		}
	}

	private long rehomeSignature;

	private void sendBuffers(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_MEASURED_BUFFERS);
		writeBuffers(packet);
		sendPacket.accept(packet);
	}

	/**
	 * Tells the players the depot's auto duties, worked out again with its paths, for the advanced editor to show.
	 */
	public void sendAutoDuties(MinecraftServer minecraftServer) {
		minecraftServer.getPlayerList().getPlayers().forEach(player -> {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeLong(id);
			autoDuties.write(PacketBinary.of(packet));
			Shortfall.write(lineShortfall, packet);
			mtr.Registry.sendToPlayer(player, mtr.packet.IPacket.PACKET_AUTO_DUTIES, packet);
		});
	}

	@Override
	protected boolean hasTransportMode() {
		return true;
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		if (KEY_FREQUENCIES.equals(key)) {
			name = packet.readUtf(PACKET_STRING_READ_LENGTH);
			color = packet.readInt();
			useRealTime = packet.readBoolean();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = packet.readInt();
			}
			departures.clear();
			final int departuresCount = packet.readInt();
			for (int i = 0; i < departuresCount; i++) {
				final int departure = packet.readInt();
				if (i < MAX_DEPARTURES) {
					departures.add(departure);
				}
			}
			routeIds.clear();
			final int routeIdCount = packet.readInt();
			for (int i = 0; i < routeIdCount; i++) {
				final long routeId = packet.readLong();
				if (i < MAX_ROUTES) {
					routeIds.add(routeId);
				}
			}
			repeatInfinitely = packet.readBoolean();
			cruisingAltitude = packet.readInt();
			useDeterministicPath = packet.readBoolean();
			cycleRoutes = packet.readBoolean();
			readVehicleServiceTypes(packet);
			speedCurves = packet.readBoolean() || supportsTimetable(transportMode);
			releaseInterval = Mth.clamp(packet.readInt(), 0, MAX_RELEASE_INTERVAL);
			readHeadways(packet);
		} else if (KEY_POOL_FLEETS.equals(key)) {
			readPoolFleets(packet);
		} else if (KEY_AREA_HEIGHTS.equals(key)) {
			readAreaHeights(packet);
		} else if (KEY_DEPOT_RULES.equals(key)) {
			rules.fromBytes(packet.readByteArray(1 << 20));
		} else if (KEY_MEASURED_BUFFERS.equals(key)) {
			readBuffers(packet);
			// Sent with none from the web editor: what was measured is forgotten. The server's are its recorder's whatever a
			// player sends; the clients' are what the server sends
			if (buffers.isEmpty()) {
				bufferRecorder.clear();
				bufferRecorder.takeChanged();
				outLeads.clear();
				sentIneffective = Collections.emptyMap();
			} else if (bufferRecorder.hasSamples()) {
				buffers = withIneffective(bufferRecorder.getBuffers(), sentIneffective);
			}
		} else if (KEY_ADDED_BUFFERS.equals(key)) {
			addedBuffers = limitAdded(readBufferList(packet));
			dispatcher.addedBuffers = addedBuffers;
		} else if (KEY_VEHICLE_SETTINGS.equals(key)) {
			readVehicleSettings(packet);
		} else if (KEY_FREQUENCIES_MIGRATED.equals(key)) {
			frequenciesMigrated = packet.readBoolean();
			fillRequested = !frequenciesMigrated;
		} else if (KEY_DUTY_MODE.equals(key)) {
			duties.diagramMode = packet.readBoolean();
		} else if (KEY_DUTIES.equals(key)) {
			// All of the duties, as the server sends them after handing a siding's on to its pool (运用补完 step 5)
			final DepotDuties read = new DepotDuties();
			read.read(PacketBinary.of(packet));
			final boolean diagramMode = duties.diagramMode;
			duties.fromBytes(read.toBytes());
			duties.diagramMode = diagramMode;
		} else if (KEY_DUTY_SLOT.equals(key)) {
			final long sidingId = packet.readLong();
			final int slotCount = packet.readVarInt();
			final int index = packet.readVarInt() - 1;
			duties.setSlot(sidingId, slotCount, index, index >= 0 ? DepotDuties.Slot.read(PacketBinary.of(packet)) : null);
		} else {
			super.update(key, packet);
		}
		isDirty = true;
	}

	public int getFrequency(int index) {
		if (index >= 0 && index < frequencies.length) {
			return frequencies[index];
		} else {
			return 0;
		}
	}

	public void setFrequency(int newFrequency, int index) {
		if (index >= 0 && index < frequencies.length) {
			frequencies[index] = newFrequency;
		}
		isDirty = true;
	}

	public void setData(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_FREQUENCIES);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeBoolean(useRealTime);
		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}
		departures.replaceAll(departure -> departure % MILLISECONDS_PER_DAY);
		departures.removeIf(departure -> departure % 1000 != 0);
		departures.sort(Integer::compareTo);
		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);
		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
		packet.writeBoolean(useDeterministicPath);
		packet.writeBoolean(cycleRoutes);
		writeVehicleServiceTypes(packet);
		packet.writeBoolean(speedCurves);
		packet.writeInt(releaseInterval);
		writeHeadways(packet);
		sendPacket.accept(packet);
	}

	/**
	 * @return the minutes between trains in an hour of the real day, for a depot of trains (R2), 0 for none
	 */
	public int getHeadway(int hour) {
		return hour >= 0 && hour < HOURS_IN_DAY ? headways[hour] : 0;
	}

	public void setHeadway(int minutes, int hour) {
		if (hour >= 0 && hour < HOURS_IN_DAY) {
			headways[hour] = Mth.clamp(minutes, 0, transitsim.planning.FrequencyTimetable.MAX_HEADWAY_MINUTES);
		}
		isDirty = true;
	}

	private void headwaysFromFrequencies() {
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			headways[i] = transitsim.planning.FrequencyTimetable.headwayOf(frequencies[i]);
		}
	}

	private void readHeadways(FriendlyByteBuf packet) {
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			headways[i] = Mth.clamp(packet.readInt(), 0, transitsim.planning.FrequencyTimetable.MAX_HEADWAY_MINUTES);
		}
	}

	private void writeHeadways(FriendlyByteBuf packet) {
		for (final int headway : headways) {
			packet.writeInt(headway);
		}
	}

	public void setDutyMode(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DUTY_MODE);
		packet.writeBoolean(duties.diagramMode);
		sendPacket.accept(packet);
	}

	/**
	 * Sends how many slots a siding has and one of them, or none when {@code index} is -1. A slot at a time keeps each
	 * packet well under the size a client may send.
	 */
	public void setDutySlot(long sidingId, int index, Consumer<FriendlyByteBuf> sendPacket) {
		final List<DepotDuties.Slot> slots = duties.getSlots(sidingId);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DUTY_SLOT);
		packet.writeLong(sidingId);
		packet.writeVarInt(slots.size());
		final boolean hasSlot = index >= 0 && index < slots.size();
		packet.writeVarInt(hasSlot ? index + 1 : 0);
		if (hasSlot) {
			slots.get(index).write(PacketBinary.of(packet));
		}
		sendPacket.accept(packet);
	}

	/**
	 * Has the frequencies or the list of departures made its routes' services again when its path is next found (R2), as
	 * the depot screen's button to fill the timetable by them asks.
	 */
	public void setFrequenciesToMigrate(Consumer<FriendlyByteBuf> sendPacket) {
		frequenciesMigrated = false;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_FREQUENCIES_MIGRATED);
		packet.writeBoolean(false);
		sendPacket.accept(packet);
	}

	/**
	 * Makes the frequencies, or the list of departures, of a depot sending trains out by them its routes' services (R2),
	 * so it runs by duties: the first route's services as the trains were sent out, and each next route's a turn after the
	 * one before arrives, as a train ran them one after another. Done once; a route running by a timetable already keeps it,
	 * and so does a depot one of whose routes does. Vehicles moving all the time and aircraft go on as before.
	 */
	private void migrateFrequencies(Level world, DataCache liveDataCache, Map<BlockPos, Map<BlockPos, Rail>> liveRails) {
		if (frequenciesMigrated || !supportsTimetable(transportMode)) {
			return;
		}
		frequenciesMigrated = true;
		isDirty = true;
		final boolean asked = fillRequested;
		fillRequested = false;
		final List<Route> routes = getRoutes(liveDataCache);
		if (routes.isEmpty() || !asked && (findTimetabledRoute(routes) != null || duties.diagramMode)) {
			return;
		}
		// By the real clock: the list of departures, or the minutes between trains in each hour
		final List<Integer> departures = useRealTime ? transitsim.planning.FrequencyTimetable.cleaned(this.departures, RouteTimetable.MAX_SERVICES) : transitsim.planning.FrequencyTimetable.departuresOfHeadways(headways, RouteTimetable.MAX_SERVICES);
		if (departures.isEmpty()) {
			return;
		}
		final RailwayData railwayData = RailwayData.getInstance(world);
		final RoutePaths.Search search = railwayData == null ? null : new RoutePaths.Search(railwayData.routePaths, liveRails, getPathGraph(liveRails, railwayData.pathBoundaries), railwayData.pathBoundaries, RoutePaths.getVehicles(railwayData));
		final List<long[][]> routeTimes = new ArrayList<>();
		routes.forEach(route -> routeTimes.add(search == null ? null : search.getTimetableTimes(route, liveDataCache)));
		final List<List<Integer>> services = transitsim.planning.FrequencyTimetable.chain(departures, routeTimes);
		for (int i = 0; i < services.size(); i++) {
			final Route route = routes.get(i);
			if (route.timetable.isActive() && !asked) {
				continue;
			}
			route.timetable.enabled = true;
			route.timetable.services.addAll(services.get(i));
			route.timetable.sanitize();
			route.timetable.revision++;
			route.setTimetable(packet -> world.players().forEach(player -> mtr.Registry.sendToPlayer((net.minecraft.server.level.ServerPlayer) player, mtr.packet.IPacket.PACKET_UPDATE_ROUTE, packet)));
		}
		System.out.println("Minecraft Transit Railway: depot " + name + " sends its trains out by a timetable from now on, " + departures.size() + " services a day" + (departures.size() >= RouteTimetable.MAX_SERVICES ? " (as many as a timetable takes; the rest left out)" : "") + (services.size() < routes.size() ? ", only " + services.size() + " of its " + routes.size() + " routes as the others' times aren't known" : ""));
	}

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache liveDataCache, RailwayDataPathGenerationModule.Snapshot snapshot, Set<Siding> unorderedSidings, Consumer<java.util.concurrent.Future<?>> callback) {
		// By ID: the world's sidings are a hash set of objects hashed by identity, so taken as they come the sidings were
		// grouped and given their duties in another order each time the server started, and the same plan gave a depot's
		// sidings different duties (found 2026-11-13 comparing the yel world's duty paths across starts)
		final Set<Siding> sidings = new LinkedHashSet<>();
		unorderedSidings.stream().sorted(Comparator.comparingLong(siding -> siding.id)).forEach(sidings::add);
		final RailwayData railwayData = RailwayData.getInstance(world);
		// By frequency or a list of departures, the depot runs by a timetable from now on (R2)
		migrateFrequencies(world, liveDataCache, railwayData == null ? snapshot.rails : railwayData.getRails());
		// The thread works on the rails and data as they were when it was started, so a player laying track meanwhile can't
		// break it part of the way through or have it find a path on a network changed half way; depots started in the same
		// tick share the copy, which nothing writes
		final DataCache dataCache = snapshot.dataCache;
		final Map<BlockPos, Map<BlockPos, Rail>> rails = snapshot.rails;
		final Map<BlockPos, Map<BlockPos, Rail>> liveRails = railwayData == null ? rails : railwayData.getRails();
		// The planning unit of the depot's lines, read here on the server thread, as the other depots and their duties may
		// change while the path is found
		final LineDuties.Unit lineUnit = LineDuties.of(this, liveDataCache, sidings);
		setLineUnit(lineUnit);
		// The sidings of the unit's other depots, which the duties' trains may go back to as well, and of the depots the
		// routes' services are set on the running diagram to go back to
		final Set<Long> returnDepotIds = new HashSet<>();
		if (lineUnit != null) {
			returnDepotIds.addAll(lineUnit.depotIds);
		}
		getRoutes(liveDataCache).forEach(route -> route.timetable.getTables().forEach(table -> table.returnOrders.values().forEach(order -> returnDepotIds.add(order.depotId))));
		returnDepotIds.remove(id);
		final List<Siding> lineSidings = new ArrayList<>();
		sidings.forEach(siding -> {
			final Depot sidingDepot = liveDataCache.sidingIdToDepot.get(siding.id);
			if (sidingDepot != null && sidingDepot != this && returnDepotIds.contains(sidingDepot.id)) {
				lineSidings.add(siding);
			}
		});
		// Vehicles only run some types of service, so each group of them gets a path of its own routes
		final Map<String, List<Siding>> sidingGroups = new LinkedHashMap<>();
		sidings.forEach(siding -> {
			final BlockPos sidingMidPos = siding.getMidPos();
			if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
				sidingGroups.computeIfAbsent(getSidingGroupKey(getServiceTypes(siding.getTrainId()), siding), key -> new ArrayList<>()).add(siding);
			}
		});
		if (sidingGroups.isEmpty()) {
			sidingGroups.put("", new ArrayList<>());
		}

		final boolean useFastSpeed = usesFastSpeed(world);
		final List<DutyPathBuilder> mainPaths = new ArrayList<>();
		sidingGroups.forEach((groupKey, sidingGroup) -> {
			final DutyPathBuilder mainPath = new DutyPathBuilder(this, sidingGroup.isEmpty() ? getRoutes(dataCache) : getSidingRoutes(sidingGroup.get(0), dataCache), sidingGroup, dataCache);
			mainPath.lineSidings = lineSidings;
			mainPaths.add(mainPath);
		});

		final RailGraph<Rail> graph = usesPathGraph() ? snapshot.getGraph() : null;
		// Depots running by a timetable take their routes' paths from the routes (decoupling document 5.4, L1)
		final RoutePaths.Search routeSearch = railwayData == null ? null : new RoutePaths.Search(railwayData.routePaths, rails, graph, railwayData.pathBoundaries, snapshot.vehicles);
		mainPaths.forEach(mainPath -> mainPath.routeSearch = routeSearch);

		pathDetours.clear();
		// Ways out and back kept as measured along rails since changed no longer hold: planned by estimates until measured
		// again (P2-A)
		sidings.forEach(siding -> {
			if (liveDataCache.sidingIdToDepot.get(siding.id) == this && siding.getRecordedLegs() != DepotLegs.NONE && siding.areTripPathsStale(liveRails)) {
				System.out.println("Minecraft Transit Railway: the rails of siding " + siding.id + "'s duties changed, so its ways out and back are measured again");
				siding.setRecordedLegs(DepotLegs.NONE, packet -> world.players().forEach(player -> mtr.Registry.sendToPlayer((net.minecraft.server.level.ServerPlayer) player, mtr.packet.IPacket.PACKET_UPDATE_SIDING, packet)));
			}
		});
		// The unit's duties as planned before and what they were planned from, read here on the server thread: kept if they
		// still hold (round 4, step 4)
		final Map<Long, byte[]> keptRecords = new HashMap<>();
		final Map<Long, List<DepotDuties.Slot>> keptSlots = new TreeMap<>();
		// The duty groups players asked to have planned again (duty groups, step 4), taken now
		final Set<String> forcedGroups = new TreeSet<>();
		if (lineUnit != null) {
			lineUnit.depotIds.forEach(depotId -> {
				final Depot depot = liveDataCache.depotIdMap.get(depotId);
				// A depot the plan gave no duties has none, and its record all the same
				if (depot != null) {
					forcedGroups.addAll(depot.replanGroups);
					depot.replanGroups.clear();
				}
				if (depot != null && !depot.duties.diagramMode) {
					keptRecords.put(depotId, depot.autoPlanRecord);
					depot.autoDuties.sidings.forEach((sidingId, slots) -> keptSlots.put(sidingId, new ArrayList<>(slots)));
				}
			});
		}
		// On the threads shared by the whole server (optimisation round 5, step 5)
		final Runnable task = () -> {
			try {
				int successfulSegments = Integer.MAX_VALUE;
				PathFailure failure = PathFailure.NONE;
				// How long each part takes, told with the end of path generation (2026-11-13, measured before splitting the
				// depot's path building out): turn times, ways out and back, the plan of the unit, the duties' paths
				final long timeStart = System.nanoTime();
				final String[] planHow = {"shared"};
				// The duties of the whole unit, planned at once by operating line, of which this depot keeps its own sidings'
				// How long each change of platform within a station takes on the rails, for the plan and the clients
				final Map<Long, Map<Long, Long>> turnTimes = mainPaths.isEmpty() ? new TreeMap<>() : mainPaths.get(0).turnTimesFor(lineUnit == null ? getRoutes(dataCache) : lineUnit.getRoutes(), rails, graph, useFastSpeed);
				clientTurnTimes = turnTimes;
				turnTimesMeasured = !mainPaths.isEmpty();
				final long timeTurns = System.nanoTime();
				// The ways out of the sidings to their first stops and back from their last, found on the track sections the way
				// the line runs the platforms and timed (round 4, step 3): planned with at once, kept for the clients and the
				// next plans, and the duties' paths take the same ways
				final Map<Long, DepotLegs> measuredWays = new TreeMap<>();
				if (railwayData != null && routeSearch != null) {
					final Map<Long, List<List<Long>>> sidingStops = lineUnit == null ? ownSidingStops(dataCache) : lineUnit.getSidingStops();
					measuredWays.putAll(measureDepotWays(Depot.this, sidingStops, railwayData.routePaths, routeSearch, lineUnit == null ? getRoutes(dataCache) : lineUnit.getRoutes(), dataCache, rails));
					measuredWays.forEach((sidingId, legs) -> {
						final Siding siding = dataCache.sidingIdMap.get(sidingId);
						if (siding != null && !sameWays(siding.getRecordedLegs(), legs)) {
							siding.setRecordedLegs(legs, packet -> minecraftServer.execute(() -> world.players().forEach(player -> mtr.Registry.sendToPlayer((net.minecraft.server.level.ServerPlayer) player, mtr.packet.IPacket.PACKET_UPDATE_SIDING, packet))));
						}
					});
				}
				final long timeWays = System.nanoTime();
				// The depots of the unit found again together plan the same duties: one plans them, the others take its plan
				final int dayStartMillis = TimetableConfig.getDayStartMillis();
				final LineDuties.Plan linePlan = lineUnit == null || routeSearch == null || railwayData == null ? null : railwayData.linePlans.get(lineUnit.key + "|" + dayStartMillis + "|" + turnTimes + "|" + describeWays(measuredWays) + "|" + forcedGroups, () -> {
					final java.util.function.Function<Route, long[][]> fastestTimes = route -> {
						final long[][] times = routeSearch.getTimetableTimes(route, dataCache);
						return times == null || times.length == 0 ? null : times;
					};
					// Planned before from the same timetables and sidings: kept while every rule still holds with these times,
					// planned again once one breaks (round 4, step 4)
					final long checkStart = System.nanoTime();
					final List<String> why = new ArrayList<>();
					final LineDuties.Plan kept = lineUnit.keep(keptRecords, keptSlots, fastestTimes, dayStartMillis, turnTimes, measuredWays, forcedGroups, why::add);
					final long checkMillis = (System.nanoTime() - checkStart) / 1000000;
					planHow[0] = kept == null ? "planned" : kept.kept ? "kept" : "partly planned";
					if (kept != null && !kept.kept) {
						// Some duty groups kept, the others planned again (duty groups, step 2)
						System.out.println("Minecraft Transit Railway: planned the duties of " + kept.describe() + " (" + String.join("; ", why) + ")");
						return kept;
					}
					if (kept != null) {
						System.out.println("Minecraft Transit Railway: kept the duties of " + kept.describe() + ", as they still hold (checked in " + checkMillis + " ms)" + (kept.replanHint.isEmpty() ? "" : "; settings changed since they were planned, " + kept.replanHint + ", so planning them again is suggested"));
						return kept;
					}
					final LineDuties.Plan plan = lineUnit.plan(fastestTimes, dayStartMillis, turnTimes, measuredWays);
					System.out.println("Minecraft Transit Railway: planned the duties of " + plan.describe() + (why.isEmpty() ? "" : " (planned again: " + why.get(0) + ")"));
					return plan;
				});
				final long timePlan = System.nanoTime();
				// The duties' ways out and back are the ones measured: found on the track sections the same way (round 4)
				if (railwayData != null) {
					// By the station nearest the siding, then along the line, for a depot anywhere along the line (2026-11-09)
					final RoutePaths.DepotLine depotLine = new RoutePaths.DepotLine(lineUnit == null ? getRoutes(dataCache) : lineUnit.getRoutes(), dataCache, rules, entrancesUnset);
					Siding.DEPOT_WAYS.set(new Siding.DepotWays() {
						@Override
						public List<PathData> find(SavedRailBase from, SavedRailBase to, boolean out, long platformEnd) {
							return railwayData.routePaths.findDepotWay(from, to, rails, out, platformEnd, depotLine);
						}

						@Override
						public long startAt(Siding siding, long firstPlatformId) {
							final DepotLegs legs = measuredWays.get(siding.id);
							return legs == null ? firstPlatformId : legs.startVia.getOrDefault(firstPlatformId, firstPlatformId);
						}

						@Override
						public List<PathData> find(SavedRailBase from, SavedRailBase to, boolean out, long platformEnd, long pathId) {
							final transitsim.plan.DepotRules.DeadheadPath path = pathId == 0 ? null : rules.getPath(pathId);
							return path == null ? find(from, to, out, platformEnd) : railwayData.routePaths.findDepotWay(from, to, rails, out, platformEnd, depotLine, path.waypoints);
						}
					});
				}
				try {
					for (final DutyPathBuilder mainPath : mainPaths) {
						successfulSegments = Math.min(successfulSegments, mainPath.generate(minecraftServer, rails, graph, useFastSpeed, linePlan));
						failure = mainPath.failure == PathFailure.NONE ? failure : mainPath.failure;
					}
				} finally {
					Siding.DEPOT_WAYS.remove();
				}
				final List<String> detours = new ArrayList<>(pathDetours);
				final List<String> failedDuties = new ArrayList<>();
				mainPaths.forEach(mainPath -> mainPath.sidings.forEach(siding -> failedDuties.addAll(siding.getLastFailedDuties())));
				// How each siding's trains get out to their first stops and back, for the advanced editor's running diagram
				final Map<Long, DepotLegs> depotLegs = new TreeMap<>();
				mainPaths.forEach(mainPath -> mainPath.sidings.forEach(siding -> depotLegs.put(siding.id, siding.getDepotLegs())));
				// With -Dmtr.debugDepotLegs=true, the ways out and back as measured on the depot's paths against the ways found on
				// the track sections (optimisation round 4, step 3, measured before one replaces the other)
				if (Boolean.getBoolean("mtr.debugDepotLegs") && railwayData != null) {
					mainPaths.forEach(mainPath -> mainPath.sidings.forEach(siding -> debugDepotLegs(siding, railwayData.routePaths, rails, dataCache)));
				}
				PacketTrainDataGuiServer.generatePathS2C(world, id, successfulSegments, failure, failedDuties, depotLegs, detours, turnTimes);
				final long timeEnd = System.nanoTime();
				final int pathCount = mainPaths.stream().mapToInt(mainPath -> mainPath.sidings.stream().mapToInt(siding -> siding.getTripPaths().size()).sum()).sum();
				System.out.println("Finished path generation" + (name.isEmpty() ? "" : " for " + name) + String.format(" in %d ms: turn times %d, ways out and back %d, plan of the unit %d (%s), duties' paths %d (%d paths)", (timeEnd - timeStart) / 1000000, (timeTurns - timeStart) / 1000000, (timeWays - timeTurns) / 1000000, (timePlan - timeWays) / 1000000, linePlan == null ? "none" : planHow[0], (timeEnd - timePlan) / 1000000, pathCount));
				if (ConnectionStats.ON) {
					ConnectionStats.print();
				}
				// On the server thread after the sidings' new paths, which are applied there: read before, a depot's dump could
				// hold the paths of the generation before, depending on how long this one took (2026-11-13)
				if (minecraftServer == null) {
					dumpDutyPaths(mainPaths, turnTimes, measuredWays);
				} else {
					minecraftServer.execute(() -> dumpDutyPaths(mainPaths, turnTimes, measuredWays));
				}
			} catch (CancellationException e) {
				// A newer request restarted path generation, and that thread reports the result
				System.out.println("Stopped path generation" + (name.isEmpty() ? "" : " for " + name));
			} catch (Exception e) {
				e.printStackTrace();
				PacketTrainDataGuiServer.generatePathS2C(world, id, 0);
				System.out.println("Failed to generate path" + (name.isEmpty() ? "" : " for " + name));
			}
		};
		callback.accept(transitsim.sim.WorkPool.submit(task));
	}

	/**
	 * With {@code -Dmtr.dumpDutyPaths=<folder>}, writes what path generation found to {@code <folder>/depot-<id>.txt}: the
	 * turn times, the ways out and back, and every path of every siding with its rails, stops and times, sorted, so two
	 * builds can be compared line by line (2026-11-13, the check for moving the path building out of the depot).
	 */
	private void dumpDutyPaths(List<DutyPathBuilder> mainPaths, Map<Long, Map<Long, Long>> turnTimes, Map<Long, DepotLegs> measuredWays) {
		final String folder = System.getProperty("mtr.dumpDutyPaths", "");
		if (folder.isEmpty()) {
			return;
		}
		final List<String> lines = new ArrayList<>();
		lines.add("depot " + id + " " + name);
		new TreeMap<>(turnTimes).forEach((from, tos) -> new TreeMap<>(tos).forEach((to, millis) -> lines.add("turn " + from + " " + to + " " + millis)));
		new TreeMap<>(measuredWays).forEach((sidingId, legs) -> lines.add("ways " + sidingId + " out " + new TreeMap<>(legs.outMillis) + " back " + new TreeMap<>(legs.backMillis) + " noOut " + new TreeSet<>(legs.unreachableOut) + " noBack " + new TreeSet<>(legs.unreachableBack)));
		final List<Siding> sorted = new ArrayList<>();
		mainPaths.forEach(mainPath -> sorted.addAll(mainPath.sidings));
		sorted.sort(Comparator.comparingLong(siding -> siding.id));
		for (final Siding siding : sorted) {
			lines.add("siding " + siding.id + " failed " + new TreeSet<>(siding.getLastFailedDuties()));
			new TreeMap<>(siding.getTripPaths()).forEach((key, tripPath) -> {
				lines.add("path " + key + " starts " + Arrays.toString(tripPath.dutyStarts) + " lead " + (tripPath.stopTimes == null ? -1 : tripPath.stopTimes.getLeadMillis()) + " duration " + (tripPath.stopTimes == null ? -1 : tripPath.stopTimes.getDurationMillis()));
				final StringBuilder rails = new StringBuilder();
				for (final mtr.path.PathData pathData : tripPath.path) {
					rails.append(' ').append(pathData.startingPos.toShortString().replace(" ", "")).append('>').append(pathData.getLastPos().toShortString().replace(" ", ""));
					if (pathData.getDwellTime() > 0) {
						rails.append('@').append(pathData.getStopId()).append('/').append(pathData.getDwellTime());
					}
				}
				lines.add(" rails" + rails);
			});
		}
		try {
			final java.nio.file.Path file = java.nio.file.Paths.get(folder, "depot-" + id + ".txt");
			java.nio.file.Files.createDirectories(file.getParent());
			java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.out.println("Minecraft Transit Railway: couldn't write the duty paths of depot " + id + ": " + e);
		}
	}

	/**
	 * @return this depot's sidings with the first and last stops of their routes, for a depot not planning by line
	 */
	private Map<Long, List<List<Long>>> ownSidingStops(DataCache dataCache) {
		final Map<Long, List<List<Long>>> stops = new TreeMap<>();
		dataCache.sidingIdMap.values().forEach(siding -> {
			if (dataCache.sidingIdToDepot.get(siding.id) == this) {
				final List<Long> firsts = new ArrayList<>();
				final List<Long> lasts = new ArrayList<>();
				getSidingRoutes(siding, dataCache).forEach(route -> {
					if (!route.platformIds.isEmpty()) {
						firsts.add(route.getFirstPlatformId());
						lasts.add(route.getLastPlatformId());
					}
				});
				stops.put(siding.id, Arrays.asList(firsts, lasts));
			}
		});
		return stops;
	}

	/**
	 * The ways out of the sidings to their first stops and back from their last (round 4, step 3), found on the track
	 * sections running the platforms the way the routes do, and timed for each siding's vehicle. Back is to the nearest
	 * siding of the same pool of the depot, as the trains go back to (user 2026-11-08). A stop with no way is told so; one
	 * not measured, before the track sections are there, is left to what was kept.
	 *
	 * @return by siding
	 */
	/**
	 * @param self the depot measuring, whose rules a siding of no depot found goes by (a siding of the unit outside every
	 *             depot's area is the measuring depot's, as path generation has it)
	 */
	/**
	 * @return why a siding has no way out or back ({@link DepotLegs#outReasons}), in a few words for the server log
	 */
	static String describeWayReason(long[] reason) {
		switch (reason.length == 0 ? 0 : (int) reason[0]) {
			case DepotLegs.REASON_NO_TRACK:
				return "the rails don't connect them";
			case DepotLegs.REASON_AGAINST:
				return "only by running the line's platforms " + Arrays.toString(Arrays.copyOfRange(reason, 1, reason.length)) + " against the line";
			case DepotLegs.REASON_ENTRANCE_UNSET:
				return "the siding reaches the line at more than one station and no rule says which to go by";
			case DepotLegs.REASON_RULE:
				return "the station or path the depot's rule gives leads nowhere";
			case DepotLegs.REASON_WRONG_WAY:
				return "only reached the wrong way round, with nowhere to turn back";
			default:
				return "not known";
		}
	}

	/**
	 * @return the platforms of the first stop's station that the routes starting at it list to start at instead, in order
	 */
	private static List<Long> startPlatformsOf(Collection<Route> routes, long firstPlatformId, DataCache dataCache) {
		final Station station = dataCache.platformIdToStation.get(firstPlatformId);
		final List<Long> starts = new ArrayList<>();
		routes.forEach(route -> {
			if (!route.platformIds.isEmpty() && route.getFirstPlatformId() == firstPlatformId) {
				route.startPlatforms.forEach(platformId -> {
					if (platformId != firstPlatformId && !starts.contains(platformId) && station != null && dataCache.platformIdToStation.get(platformId) == station) {
						starts.add(platformId);
					}
				});
			}
		});
		return starts;
	}

	private static Map<Long, DepotLegs> measureDepotWays(Depot self, Map<Long, List<List<Long>>> sidingStops, RoutePaths routePaths, RoutePaths.Search routeSearch, Collection<Route> routes, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		// Each siding's ways by its own depot's rules (运用补完 step 2)
		final Map<Depot, RoutePaths.DepotLine> depotLines = new HashMap<>();
		// The node each route runs its first stop and its last towards
		final Map<Long, Long> firstEnds = new HashMap<>();
		final Map<Long, Long> lastEnds = new HashMap<>();
		routes.forEach(route -> {
			if (route.platformIds.isEmpty()) {
				return;
			}
			final RoutePaths.RoutePath routePath = routeSearch.get(route, dataCache);
			if (routePath == null || !routePath.isFound()) {
				return;
			}
			final List<PathData> path = routePath.getPath(route.platformIds);
			for (final PathData pathData : path) {
				if (pathData.savedRailBaseId == route.getFirstPlatformId()) {
					firstEnds.putIfAbsent(route.getFirstPlatformId(), pathData.getLastPos().asLong());
					break;
				}
			}
			for (int i = path.size() - 1; i >= 0; i--) {
				if (path.get(i).savedRailBaseId == route.getLastPlatformId()) {
					lastEnds.putIfAbsent(route.getLastPlatformId(), path.get(i).getLastPos().asLong());
					break;
				}
			}
		});
		final Map<Long, DepotLegs> measured = new TreeMap<>();
		sidingStops.forEach((sidingId, stops) -> {
			final Siding siding = dataCache.sidingIdMap.get(sidingId);
			if (siding == null) {
				return;
			}
			final PathTiming.Vehicle vehicle = siding.getTimingVehicle();
			final Depot sidingDepot = dataCache.sidingIdToDepot.getOrDefault(sidingId, self);
			final RoutePaths.DepotLine depotLine = depotLines.computeIfAbsent(sidingDepot, depot -> {
				depot.entrancesUnset.clear();
				return new RoutePaths.DepotLine(routes, dataCache, depot.rules, depot.entrancesUnset);
			});
			final Map<Long, Long> out = new TreeMap<>();
			final Map<Long, Long> back = new TreeMap<>();
			final Set<Long> noWayOut = new TreeSet<>();
			final Set<Long> noWayBack = new TreeSet<>();
			final Map<Long, long[]> outReasons = new TreeMap<>();
			final Map<Long, long[]> backReasons = new TreeMap<>();
			// The platform each way out runs through just before turning back into its platform (first come, first out there)
			final Map<Long, Long> passes = new TreeMap<>();
			final Map<Long, Long> startVia = new TreeMap<>();
			boolean any = false;
			for (final long platformId : stops.get(0)) {
				final Platform platform = dataCache.platformIdMap.get(platformId);
				final long[][] why = new long[1][];
				final List<PathData> way = platform == null || out.containsKey(platformId) ? null : routePaths.findDepotWay(siding, platform, rails, true, firstEnds.getOrDefault(platformId, 0L), depotLine, why);
				if (way != null) {
					any = true;
					if (way.isEmpty()) {
						noWayOut.add(platformId);
						if (why[0] != null) {
							outReasons.put(platformId, why[0]);
						}
					} else {
						out.put(platformId, RoutePaths.timeDepotWay(way, vehicle, true));
						final PathData passed = DepotLegs.passedBeforeTurningInto(way);
						if (passed != null) {
							final long passedId = passed.savedRailBaseId != 0 ? passed.savedRailBaseId : dataCache.platformIdMap.values().stream().filter(each -> each.containsPos(passed.startingPos) || each.containsPos(passed.getLastPos())).mapToLong(each -> each.id).findFirst().orElse(0);
							if (passedId != 0 && passedId != platformId) {
								passes.put(platformId, passedId);
							}
						}
					}
				}
			}
			final Depot depot = dataCache.sidingIdToDepot.get(sidingId);
			for (final long platformId : stops.get(1)) {
				final Platform platform = dataCache.platformIdMap.get(platformId);
				if (platform == null || back.containsKey(platformId) || noWayBack.contains(platformId)) {
					continue;
				}
				long best = -1;
				boolean measuredAny = false;
				long[] backWhy = null;
				for (final Siding target : dataCache.sidingIdMap.values()) {
					if (target != siding && (depot == null || dataCache.sidingIdToDepot.get(target.id) != depot || !siding.getPoolKey().equals(target.getPoolKey()))) {
						continue;
					}
					final long[][] why = new long[1][];
					final List<PathData> way = routePaths.findDepotWay(platform, target, rails, false, lastEnds.getOrDefault(platformId, 0L), depotLine, why);
					if (way != null && way.isEmpty() && backWhy == null) {
						// Why for the siding itself, or the first of the pool tried
						backWhy = why[0];
					}
					if (way != null) {
						measuredAny = true;
						final long millis = RoutePaths.timeDepotWay(way, vehicle, false);
						if (millis >= 0 && (best < 0 || millis < best)) {
							best = millis;
						}
					}
				}
				if (measuredAny) {
					any = true;
					if (best < 0) {
						noWayBack.add(platformId);
						if (backWhy != null) {
							backReasons.put(platformId, backWhy);
						}
					} else {
						// The depot's paths count the last stop's stop time in the way back
						back.put(platformId, best + platform.getDwellTime() * 500L);
					}
				}
			}
			if (any) {
				// No way out to a first stop: to another platform of its station the routes starting there list to start at
				// instead, the first with a way (2026-11-13, 单独出库站台)
				for (final long platformId : new ArrayList<>(noWayOut)) {
					for (final long startId : startPlatformsOf(routes, platformId, dataCache)) {
						final Platform start = dataCache.platformIdMap.get(startId);
						final List<PathData> way = start == null ? null : routePaths.findDepotWay(siding, start, rails, true, 0, depotLine, new long[1][]);
						if (way != null && !way.isEmpty()) {
							noWayOut.remove(platformId);
							outReasons.remove(platformId);
							out.put(platformId, RoutePaths.timeDepotWay(way, vehicle, true));
							startVia.put(platformId, startId);
							break;
						}
					}
				}
				final DepotLegs legs = new DepotLegs(siding.getDepotLegs().entrancePlatform, out, back).withUnreachable(noWayOut, noWayBack).withReasons(outReasons, backReasons);
				legs.outPasses.putAll(passes);
				legs.startVia.putAll(startVia);
				measured.put(sidingId, legs);
				// Told in the log too, each platform with no way and why
				outReasons.forEach((platformId, reason) -> System.out.println("Minecraft Transit Railway: siding " + sidingId + " has no way out to platform " + platformId + ": " + describeWayReason(reason)));
				backReasons.forEach((platformId, reason) -> System.out.println("Minecraft Transit Railway: siding " + sidingId + " has no way back from platform " + platformId + ": " + describeWayReason(reason)));
				// A siding trains can't leave for any first stop: trains taken back into it stay there, unless the depot has track
				// from it to a siding they can leave from (2026-11-13, 车厂内调车)
				if (out.isEmpty() && !noWayOut.isEmpty()) {
					System.out.println("Minecraft Transit Railway: siding " + sidingId + " has no way out to any first stop; trains taken back into it can't leave, add track in the depot from it to a siding they can leave from");
				}
			}
		});
		return measured;
	}

	private static boolean sameWays(DepotLegs legs1, DepotLegs legs2) {
		return legs1.outMillis.equals(legs2.outMillis) && legs1.backMillis.equals(legs2.backMillis) && legs1.unreachableOut.equals(legs2.unreachableOut) && legs1.unreachableBack.equals(legs2.unreachableBack) && sameReasons(legs1.outReasons, legs2.outReasons) && sameReasons(legs1.backReasons, legs2.backReasons) && legs1.outPasses.equals(legs2.outPasses);
	}

	private static boolean sameReasons(Map<Long, long[]> reasons1, Map<Long, long[]> reasons2) {
		if (!reasons1.keySet().equals(reasons2.keySet())) {
			return false;
		}
		for (final Map.Entry<Long, long[]> entry : reasons1.entrySet()) {
			if (!Arrays.equals(entry.getValue(), reasons2.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static String describeWays(Map<Long, DepotLegs> ways) {
		final StringBuilder builder = new StringBuilder();
		ways.forEach((sidingId, legs) -> builder.append(sidingId).append(legs.outMillis).append(legs.backMillis).append(legs.unreachableOut).append(legs.unreachableBack).append(legs.outPasses).append(';'));
		return builder.toString();
	}

	/**
	 * @return the node a route runs the platform towards, where it is the route's first stop or its last, or 0 if no route
	 * found on the rails does
	 */
	private static long runningEnd(long platformId, boolean first, RoutePaths routePaths, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		for (final Route route : dataCache.routeIdMap.values()) {
			if (route.platformIds.isEmpty() || (first ? route.getFirstPlatformId() : route.getLastPlatformId()) != platformId) {
				continue;
			}
			final RoutePaths.RoutePath routePath = routePaths.getFound(route, dataCache, rails);
			if (routePath == null || !routePath.isFound()) {
				continue;
			}
			final List<PathData> path = routePath.getPath(route.platformIds);
			for (int i = first ? 0 : path.size() - 1; first ? i < path.size() : i >= 0; i += first ? 1 : -1) {
				if (path.get(i).savedRailBaseId == platformId) {
					return path.get(i).getLastPos().asLong();
				}
			}
		}
		return 0;
	}

	private static void debugDepotLegs(Siding siding, RoutePaths routePaths, Map<BlockPos, Map<BlockPos, Rail>> rails, DataCache dataCache) {
		final DepotLegs measured = siding.getDepotLegs();
		final PathTiming.Vehicle vehicle = siding.getTimingVehicle();
		measured.outMillis.forEach((platformId, millis) -> {
			final Platform platform = dataCache.platformIdMap.get(platformId);
			final long byLegs = platform == null ? -1 : routePaths.measureDepotLeg(siding, platform, rails, vehicle, true, runningEnd(platformId, true, routePaths, dataCache, rails));
			System.out.println("Minecraft Transit Railway depot leg: out of siding " + siding.name + " (" + siding.id + ") to " + (platform == null ? "?" : platform.name) + " (" + platformId + "): paths " + millis + " ms, legs " + byLegs + " ms, difference " + (byLegs < 0 ? "no way" : (byLegs - millis) + " ms"));
		});
		measured.backMillis.forEach((platformId, millis) -> {
			final Platform platform = dataCache.platformIdMap.get(platformId);
			// Back to the siding or any other of its pool, as the depot's paths go back to any of them, the nearest counting
			long moving = -1;
			if (platform != null) {
				for (final Siding target : dataCache.sidingIdMap.values()) {
					if (target == siding || dataCache.sidingIdToDepot.get(target.id) == dataCache.sidingIdToDepot.get(siding.id) && siding.getPoolKey().equals(target.getPoolKey())) {
						final long toTarget = routePaths.measureDepotLeg(platform, target, rails, vehicle, false, runningEnd(platformId, false, routePaths, dataCache, rails));
						if (toTarget >= 0 && (moving < 0 || toTarget < moving)) {
							moving = toTarget;
						}
					}
				}
			}
			// The depot's paths count the last stop's dwell in the way back
			final long byLegs = moving < 0 ? -1 : moving + platform.getDwellTime() * 500L;
			System.out.println("Minecraft Transit Railway depot leg: back from " + (platform == null ? "?" : platform.name) + " (" + platformId + ") to siding " + siding.name + " (" + siding.id + "): paths " + millis + " ms, legs " + byLegs + " ms, difference " + (byLegs < 0 ? "no way" : (byLegs - millis) + " ms"));
		});
	}

	/**
	 * Adds the services the depot sent out last, and the days its duties ran, to a record of the trains (G0), under one of
	 * its sidings.
	 */
	/**
	 * Takes what the simulator's depot sent out last and the days its duties ran (G1), so they are saved, and carried on
	 * from when the simulator starts again.
	 */
	public void copyDispatcher(DepotBoard simulated) {
		dispatcher.lastDeployedTrips.clear();
		dispatcher.lastDeployedTrips.putAll(simulated.dispatcher.lastDeployedTrips);
		dispatcher.dutyRunDays.clear();
		dispatcher.dutyRunDays.putAll(simulated.dispatcher.dutyRunDays);
		dispatcher.takeovers.clear();
		dispatcher.takeovers.addAll(simulated.dispatcher.takeovers);
		dispatcher.lateServices.clear();
		dispatcher.lateServices.addAll(simulated.dispatcher.lateServices.toList());
		lastDeployedMillis = simulated.lastDeployedMillis;
		deployIndex = Math.max(0, simulated.deployIndex);
	}

	public void recordDispatcher(RunRecord record, long sidingId) {
		record.lastDeployedTrips.put(sidingId, new TreeMap<>(dispatcher.lastDeployedTrips));
		record.dutyRunDays.put(sidingId, new TreeMap<>(dispatcher.dutyRunDays));
		record.lastDeployedMillis.put(sidingId, lastDeployedMillis);
		record.deployIndices.put(sidingId, deployIndex);
		if (!dispatcher.takeovers.isEmpty()) {
			record.takeovers.put(sidingId, new ArrayList<>(dispatcher.takeovers));
		}
	}

	/**
	 * @return the departures of trains sent out by frequency, as times of the day in order, as the component's simulator
	 * takes them (G1), worked out first if the depot changed, as after the world is loaded
	 */
	public int[] getDeployTimes(Level world) {
		if (isDirty) {
			generateTempDepartures(world);
		}
		return tempDepartures.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/**
	 * Whether the depot's paths are found for flying high above the world.
	 */
	public boolean usesFastSpeed(Level world) {
		return cruisingAltitude >= world.getMaxBuildHeight() + THRESHOLD_ABOVE_MAX_BUILD_HEIGHT;
	}

	/**
	 * How long each change of platform within a station takes between these routes, measured along the rails the way a
	 * duty runs it, as path generation measures it for the plan; for tests.
	 *
	 * @return from platform → to platform → milliseconds, a pair left out having no way between
	 */
	public Map<Long, Map<Long, Long>> measureTurnTimes(List<Route> routes, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
		return new DutyPathBuilder(this, routes, Collections.emptyList(), dataCache).turnTimesFor(routes, rails, graph, useFastSpeed);
	}

	/**
	 * Finds the paths of these duties from each of the depot's sidings along the rails, the way path generation does, for a
	 * draft of them, without changing the depot or its sidings (S6). Like path generation it runs away from the server
	 * thread; the graph comes from {@link #getPathGraph} on the server thread.
	 *
	 * @param failed told about the key of each duty shape whose path can't be found
	 * @return the paths by siding, then by the key of the duty's shape
	 */
	public Map<Long, Map<String, TripPath<PathData>>> findDutyPaths(DepotDuties draft, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, Set<Siding> sidings, boolean useFastSpeed, Consumer<String> failed) {
		final Map<Long, Map<String, TripPath<PathData>>> dutyPaths = new HashMap<>();
		final Map<String, List<Siding>> sidingGroups = new LinkedHashMap<>();
		sidings.forEach(siding -> {
			final BlockPos sidingMidPos = siding.getMidPos();
			if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
				sidingGroups.computeIfAbsent(getSidingGroupKey(getServiceTypes(siding.getTrainId()), siding), key -> new ArrayList<>()).add(siding);
			}
		});
		sidingGroups.forEach((groupKey, sidingGroup) -> {
			final DutyPathBuilder mainPath = new DutyPathBuilder(this, getSidingRoutes(sidingGroup.get(0), dataCache), sidingGroup, dataCache);
			sidingGroup.forEach(siding -> {
				final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = mainPath.getDutyPlans(draft, siding, rails, graph, useFastSpeed, failed);
				dutyPaths.put(siding.id, siding.findDutyPaths(dutyPlans, rails, graph, cruisingAltitude, useFastSpeed, failed));
			});
		});
		return dutyPaths;
	}

	/**
	 * Gives each siding the paths of its duties as path generation does for a depot running by duties, at once and without
	 * a server: the end-to-end check of "the game finds the paths, the component runs the trains".
	 */
	public void generateDutyPathsNow(DepotDuties runDuties, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, Set<Siding> sidings, boolean useFastSpeed, Consumer<String> failed) {
		final Map<String, List<Siding>> sidingGroups = new LinkedHashMap<>();
		sidings.forEach(siding -> {
			final BlockPos sidingMidPos = siding.getMidPos();
			if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
				sidingGroups.computeIfAbsent(getSidingGroupKey(getServiceTypes(siding.getTrainId()), siding), key -> new ArrayList<>()).add(siding);
			}
		});
		sidingGroups.forEach((groupKey, sidingGroup) -> {
			final DutyPathBuilder mainPath = new DutyPathBuilder(this, getSidingRoutes(sidingGroup.get(0), dataCache), sidingGroup, dataCache);
			final Map<String, List<PathData>> turnLinkCache = new HashMap<>();
			sidingGroup.forEach(siding -> {
				final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = mainPath.getDutyPlans(runDuties, siding, rails, graph, useFastSpeed, failed);
				mainPath.setTurnLinks(siding, dutyPlans, turnLinkCache, rails, graph, useFastSpeed);
				siding.generateDutyRoute(Runnable::run, rails, graph, cruisingAltitude, useFastSpeed, dutyPlans, mainPath.getPoolSidings(siding));
				siding.getLastFailedDuties().forEach(failed);
			});
		});
	}

	/**
	 * Where a siding's duties can turn back early (D4), as path generation finds it, for the tests: the ways round by
	 * {@code from>to}.
	 */
	public Map<String, List<PathData>> findTurnLinks(DepotDuties draft, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, Siding siding, boolean useFastSpeed) {
		final DutyPathBuilder mainPath = new DutyPathBuilder(this, getSidingRoutes(siding, dataCache), Collections.singletonList(siding), dataCache);
		final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = mainPath.getDutyPlans(draft, siding, rails, graph, useFastSpeed, key -> {
		});
		return mainPath.setTurnLinks(siding, dutyPlans, new HashMap<>(), rails, graph, useFastSpeed);
	}

	/**
	 * Must be called on the server thread. Null selects the old search, which airplanes and cable cars always use.
	 */
	public RailGraph<Rail> getPathGraph(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		return getPathGraph(rails, null);
	}

	/**
	 * Like {@link #getPathGraph(Map)}, kept to the regions of the pathfinding boundaries.
	 */
	public RailGraph<Rail> getPathGraph(Map<BlockPos, Map<BlockPos, Rail>> rails, PathBoundaries boundaries) {
		return usesPathGraph() ? DeterministicPathFinder.compile(rails, boundaries) : null;
	}

	/**
	 * Train depots always search deterministically, the same way as their routes' paths (2026-11-07 review, L1), whatever an
	 * old save has.
	 *
	 * @return whether the depot's paths are found on the network to search ({@link DeterministicPathFinder}) rather than by
	 * the old search
	 */
	public boolean usesPathGraph() {
		return (useDeterministicPath || supportsTimetable(transportMode)) && transportMode != TransportMode.AIRPLANE && !transportMode.continuousMovement;
	}

	/**
	 * The depot's sidings by their pool (see {@link Siding#getPoolKey}), gathered again at the start of every tick.
	 */
	private final Map<String, List<Siding>> pools = new HashMap<>();

	public void clearPools() {
		pools.clear();
	}

	public void addToPool(Siding siding) {
		pools.computeIfAbsent(siding.getPoolKey(), key -> new ArrayList<>()).add(siding);
	}

	private List<Siding> getPool(Siding siding) {
		return pools.getOrDefault(siding.getPoolKey(), Collections.singletonList(siding));
	}

	/**
	 * @return the depot's pool of the siding, then the sidings of the other depots of its planning unit with the same
	 * vehicle, which its trains go back to as well (decoupling document 5.4, after L1)
	 */
	private List<Siding> getLinePool(Siding siding) {
		// The candidates the game has at hand: the depot's pool, and the same pool of the other depots of its planning unit;
		// which of them are the pool is the component's rule (FleetRules, S7)
		final List<Siding> candidates = new ArrayList<>(getPool(siding));
		final boolean byLine = dataCache != null && lineDepotIds.size() > 1;
		if (byLine) {
			new TreeSet<>(lineDepotIds).forEach(depotId -> {
				final Depot other = dataCache.depotIdMap.get(depotId);
				if (other != null && other != this) {
					candidates.addAll(other.pools.getOrDefault(siding.getPoolKey(), Collections.emptyList()));
				}
			});
		}
		final String linePool = byLine ? "line|" + siding.getPoolKey() : "";
		return transitsim.sim.FleetRules.linePool(siding, candidates, poolSiding -> {
			final Depot depot = dataCache == null ? null : dataCache.sidingIdToDepot.get(poolSiding.id);
			return depot == null ? id : depot.id;
		}, Siding::getPoolKey, poolSiding -> {
			final Depot depot = dataCache == null ? null : dataCache.sidingIdToDepot.get(poolSiding.id);
			return byLine && (depot == null ? this : depot) != this && lineDepotIds.contains((depot == null ? this : depot).id) || poolSiding == siding ? linePool : "";
		});
	}

	/**
	 * @return the duties a siding of the depot's line pool runs by, its own depot's
	 */
	private List<DepotDuties.Slot> getSlotsOf(long sidingId) {
		final Depot depot = dataCache == null ? null : dataCache.sidingIdToDepot.get(sidingId);
		final DepotDuties runDuties = depot == null ? null : depot.getRunDuties();
		return runDuties == null ? Collections.emptyList() : runDuties.getSlots(sidingId);
	}

	/**
	 * @return how many duty slots the sidings of the siding's pool have together, across its planning unit
	 */
	public int getPoolSlots(Siding siding) {
		return getRunDuties() == null ? 0 : getLinePool(siding).stream().mapToInt(poolSiding -> getSlotsOf(poolSiding.id).size()).sum();
	}

	/**
	 * @return how many trains the sidings of the siding's pool have together, out, standing and coming back from another
	 * siding, across its planning unit
	 */
	public int getPoolTrains(Siding siding) {
		return getLinePool(siding).stream().mapToInt(poolSiding -> poolSiding.getTrainCount() + poolSiding.getShedCount()).sum();
	}

	private Map<String, TripPath<PathData>> getSidingPaths(long sidingId) {
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		return siding == null ? null : siding.getTripPaths();
	}

	private Object getSidingPool(long sidingId) {
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		return siding == null ? sidingId : siding.getPoolKey();
	}

	private transitsim.plan.PathVariants<PathData> getSidingVariants(long sidingId) {
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		return siding == null ? null : siding.getPathVariants();
	}

	/**
	 * A train of the game asks for a spare to take its duty over (D5). See {@link DepotDispatcher#requestTakeover}.
	 */
	@Override
	public int requestTakeover(long dutySidingId, int slot, long dayStart, int fromSegment, String fromKey, long serviceMillis, Set<UUID> affected) {
		return dispatcher.requestTakeover(System.currentTimeMillis(), dutySidingId, slot, dayStart, fromSegment, fromKey, serviceMillis, affected, this::getSidingPaths, this::getSidingVariants, this::getSidingPool);
	}

	@Override
	public long chooseReturnSiding(long sidingId, long depotId, LongPredicate canGoBack) {
		return depotId == 0 ? choosePoolReturnSiding(sidingId, canGoBack) : chooseOrderedReturnSiding(sidingId, depotId, canGoBack);
	}

	private long choosePoolReturnSiding(long sidingId, LongPredicate canGoBack) {
		final DepotDuties runDuties = getRunDuties();
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		if (runDuties == null || siding == null) {
			return sidingId;
		}
		final List<Siding> pool = getLinePool(siding);
		if (pool.size() < 2) {
			return sidingId;
		}
		final Map<Long, Siding> poolById = new HashMap<>();
		pool.forEach(poolSiding -> poolById.put(poolSiding.id, poolSiding));
		// Each siding's slots and what has run today are its own depot's
		return DepotDispatcher.chooseReturnSiding(sidingId, poolById.keySet(), this::getSlotsOf, (eachSidingId, slot, day) -> {
			final Depot depot = dataCache.sidingIdToDepot.get(eachSidingId);
			return depot != null && depot.hasDutyRun(eachSidingId, slot, day);
		}, System.currentTimeMillis(), otherSidingId -> {
			final Siding other = poolById.get(otherSidingId);
			// Only a siding the duty has a way back to
			if (!canGoBack.test(otherSidingId)) {
				return false;
			}
			// Running by duties, a siding holds its fleet, however long its rails: room for the trains it holds and the ones
			// already on their way to it (FleetRules, S7)
			return otherSidingId == sidingId || transitsim.sim.FleetRules.hasRoom(other.getHeldCount(), other.getFleetSize() < 0 ? 0 : pool.stream().mapToInt(poolSiding -> poolSiding.countReturningTo(otherSidingId)).sum(), other.getFleetSize());
		});
	}

	private long chooseOrderedReturnSiding(long sidingId, long depotId, LongPredicate canGoBack) {
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		final Depot orderDepot = dataCache == null ? null : dataCache.depotIdMap.get(depotId);
		if (siding == null || orderDepot == null) {
			noteReturnOrderMissed(sidingId, depotId, orderDepot == null ? "the depot no longer exists" : "the siding no longer exists");
			return choosePoolReturnSiding(sidingId, canGoBack);
		}
		final Map<Long, Siding> candidates = new TreeMap<>();
		orderDepot.pools.getOrDefault(siding.getPoolKey(), Collections.emptyList()).forEach(other -> {
			if (canGoBack.test(other.id) && (other == siding || transitsim.sim.FleetRules.hasRoom(other.getHeldCount(), other.getFleetSize() < 0 ? 0 : countReturningTo(other.id), other.getFleetSize()))) {
				candidates.put(other.id, other);
			}
		});
		if (candidates.isEmpty()) {
			// Told rather than silently going back elsewhere (P1-B)
			noteReturnOrderMissed(sidingId, depotId, "no siding of the same vehicle with room and a way back");
			return choosePoolReturnSiding(sidingId, canGoBack);
		}
		final long first = candidates.containsKey(sidingId) ? sidingId : candidates.keySet().iterator().next();
		return DepotDispatcher.chooseReturnSiding(first, candidates.keySet(), this::getSlotsOf, (eachSidingId, slot, day) -> {
			final Depot depot = dataCache.sidingIdToDepot.get(eachSidingId);
			return depot != null && depot.hasDutyRun(eachSidingId, slot, day);
		}, System.currentTimeMillis(), eachSidingId -> true);
	}

	/**
	 * A service set to go back to a depot whose train couldn't: in the log, and kept for the advanced editor's sync panel
	 * (the last few, as {@code <siding> <depot> <reason>}).
	 */
	private void noteReturnOrderMissed(long sidingId, long depotId, String reason) {
		System.out.println("Minecraft Transit Railway: a train of siding " + sidingId + " set to go back to depot " + depotId + " couldn't (" + reason + ") and goes back to its own pool instead");
		synchronized (returnOrderMisses) {
			returnOrderMisses.add(sidingId + " " + depotId + " " + reason);
			while (returnOrderMisses.size() > MAX_RUN_PROBLEMS) {
				returnOrderMisses.remove(0);
			}
		}
		runProblemsChanged = true;
	}

	/**
	 * @return how many trains anywhere are on their way back to this siding, not their own
	 */
	private int countReturningTo(long sidingId) {
		if (dataCache == null) {
			return 0;
		}
		return dataCache.sidingIdMap.values().stream().mapToInt(other -> other.countReturningTo(sidingId)).sum();
	}

	public void requestDeploy(long sidingId, TrainServer train) {
		deployableSidings.put(sidingId, train);
	}

	public void deployTrain(RailwayData railwayData, Level world) {
		if (isDirty) {
			generateTempDepartures(world);
		}

		// The simulator sends out the trains of its depots (G1)
		if (runBySimulator) {
			departureOffset = 0;
			deployableSidings.clear();
			return;
		}

		dispatcher.startTick();

		dataCache = railwayData.dataCache;
		trainRoutes.clear();

		if (getRunDuties() != null) {
			deployDutyTrain(railwayData);
			departureOffset = 0;
			deployableSidings.clear();
			return;
		}

		// A depot of trains runs by duties only (R2): until its duties are worked out, none are sent out; vehicles moving all
		// the time and aircraft go on by frequency, a list of departures or a timetable as before
		if (supportsTimetable(transportMode)) {
			departureOffset = 0;
			deployableSidings.clear();
			return;
		}

		if (!deployableSidings.isEmpty()) {
			final List<Siding> sidingsInDepot = getSidingsInDepot(railwayData);

			final int sidingsInDepotSize = sidingsInDepot.size();
			for (int i = deployIndex; i < deployIndex + sidingsInDepotSize; i++) {
				final TrainServer train = deployableSidings.get(sidingsInDepot.get(i % sidingsInDepotSize).id);
				if (train == null) {
					continue;
				}
				final RouteTimetable timetable = getTimetable(train.trainId);
				final Route firstRoute = timetable == null ? null : getTrainRoutes(train.trainId).get(0);
				final long currentMillis = System.currentTimeMillis();
				final long tripMillis = timetable == null ? 0 : train.findDeployService(dispatcher.getLastDeployedTrip(firstRoute.id));
				if (timetable == null ? getMillisUntilDeploy(1) == 0 : tripMillis != 0) {
					lastDeployedMillis = currentMillis;
					deployIndex++;
					if (deployIndex >= sidingsInDepotSize) {
						deployIndex = 0;
					}
					if (timetable == null) {
						train.deployTrain();
					} else {
						dispatcher.lastDeployedTrips.put(firstRoute.id, tripMillis);
						reserveTrip(firstRoute.id, tripMillis);
						train.deployTrain(tripMillis, firstRoute.id);
					}
					break;
				}
			}
		}

		departureOffset = 0;
		deployableSidings.clear();
	}

	/**
	 * See {@link DepotDispatcher#findDutyDeploy}.
	 */
	private void deployDutyTrain(RailwayData railwayData) {
		final long currentMillis = System.currentTimeMillis();
		dispatcher.releaseIntervalMillis = releaseInterval * 1000;
		final DepotDispatcher.DutyDeploy<PathData> deploy = dispatcher.findDutyDeploy(currentMillis, new TreeSet<>(deployableSidings.keySet()), getRunDuties(), this::getSidingPaths, railwayData.dataCache.routeIdMap::get, this::getSidingPool, this::getSidingVariants);
		if (deploy != null) {
			lastDeployedMillis = currentMillis;
			deployableSidings.get(deploy.sidingId).deployDuty(deploy.slot, deploy.dutySidingId, deploy.dayStart, deploy.path, deploy.startSegment);
			if (deploy.late) {
				deployableSidings.get(deploy.sidingId).joinDutyLate();
			}
		}
	}

	@Override
	public long[] findDuty(long dutyId) {
		final DepotDuties runDuties = getRunDuties();
		return runDuties == null ? null : runDuties.findSlot(dutyId);
	}

	@Override
	public void noteReturnMissed(long sidingId, long targetSidingId, String reason) {
		noteReturnOrderMissed(sidingId, targetSidingId, reason);
	}

	@Override
	public List<DepotDuties.Slot> getDutySlots(long sidingId) {
		final DepotDuties runDuties = getRunDuties();
		return runDuties == null ? Collections.emptyList() : runDuties.getSlots(sidingId);
	}

	/**
	 * @return what the depot's auto duties are worked out from: its routes' timetables and stops, and its sidings and the
	 * trains each holds; null if it doesn't run by a timetable or has duties set
	 */
	/**
	 * Another depot running a route of this one already runs some of its services: all it has in its own duties, and the
	 * ones in its auto duties when it comes first by its ID. This depot's auto duties leave them to it, so no service of a
	 * route two depots run is run twice (as when trains reserved their services, the depots sharing a route share them).
	 * Auto duties of depots sharing a route are worked out in the order of their IDs, each leaving what the earlier ones
	 * took, so working them out again in any order ends the same.
	 *
	 * @return the services as {@code route@service}
	 */
	public Set<String> getSharedTaken(DataCache dataCache) {
		final Set<String> taken = new TreeSet<>();
		final Set<Long> ownRoutes = new HashSet<>(routeIds);
		dataCache.depotIdMap.values().forEach(other -> {
			if (other == this || other.transportMode != transportMode || other.routeIds.stream().noneMatch(ownRoutes::contains)) {
				return;
			}
			final DepotDuties otherDuties = other.duties.diagramMode ? other.duties : other.id < id && other.autoDuties.diagramMode ? other.autoDuties : null;
			if (otherDuties != null) {
				otherDuties.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> {
					if (ownRoutes.contains(segment.routeId)) {
						taken.add(segment.routeId + "@" + segment.service);
					}
				}))));
			}
		});
		return taken;
	}

	/**
	 * @return what the depot's auto duties are worked out from: those of its whole planning unit ({@link LineDuties}), so
	 * a change on any line or depot of the unit works out every depot of it again; null if it doesn't plan by line
	 */
	public String getAutoDutiesKey(DataCache dataCache, Set<Siding> sidings) {
		final LineDuties.Unit unit = LineDuties.of(this, dataCache, sidings);
		return unit == null ? null : unit.key;
	}

	/**
	 * The key of the depot's auto duties planned on its own, before planning by line; kept for reference by add-ons.
	 */
	@Deprecated
	public String getDepotAutoDutiesKey(DataCache dataCache, Set<Siding> sidings) {
		if (duties.diagramMode || transportMode.continuousMovement) {
			return null;
		}
		final List<Route> routes = getRoutes(dataCache);
		if (findTimetabledRoute(routes) == null) {
			return null;
		}
		final StringBuilder key = new StringBuilder();
		routes.forEach(route -> {
			final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
			transitsim.plan.TimetableBinary.write(writer, route.timetable);
			key.append(route.id).append(':').append(Arrays.hashCode(writer.toBytes())).append(':');
			route.platformIds.forEach(platform -> key.append(platform.platformId).append(','));
			key.append(';');
		});
		sidings.stream().filter(siding -> dataCache.sidingIdToDepot.get(siding.id) == this).sorted(Comparator.comparingLong(siding -> siding.id)).forEach(siding -> key.append(siding.id).append('/').append(siding.getTrainLimit()).append(siding.getTrainId()).append(';'));
		// What depots sharing a route run changes what is left for this one
		key.append("shared:").append(getSharedTaken(dataCache).hashCode());
		// The routes' paths keep off the connecting lines, so making one or no longer finds them again
		key.append("connecting:");
		dataCache.routeIdMap.values().stream().filter(route -> route.connectingLine && route.transportMode == transportMode).sorted(Comparator.comparingLong(route -> route.id)).forEach(route -> {
			key.append(route.id).append('/');
			route.connectingReleased.forEach(rail -> key.append(transitsim.network.RailKeys.of(rail[0], rail[1]).hashCode()).append('.'));
			key.append(',');
		});
		return key.toString();
	}

	private void setLineUnit(LineDuties.Unit unit) {
		lineDepotIds = unit == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(unit.depotIds));
		lineUnitKey = unit == null ? "" : unit.key;
	}

	/**
	 * @return the key of the depot's planning unit, or empty if it doesn't plan by line
	 */
	/**
	 * Forgets what the auto duties were planned from, so the next path generation plans them again rather than keep them:
	 * a player asked for it (round 4, step 4).
	 */
	public void forgetAutoPlan() {
		autoPlanRecord = new byte[0];
	}

	/**
	 * Has a duty group of the depot's planning unit planned again with the next path generation, the others kept while
	 * they hold (duty groups, step 4). Not saved.
	 */
	public void replanGroup(String key) {
		replanGroups.add(key);
	}

	public String getLineUnitKey() {
		return lineUnitKey;
	}

	/**
	 * @return whether the auto duties need working out again, which is then taken as done
	 */
	public boolean takeAutoDutiesChange(DataCache dataCache, Set<Siding> sidings) {
		return takeAutoDutiesChange(LineDuties.of(this, dataCache, sidings));
	}

	/**
	 * @param unit the depot's planning unit, as {@link LineDuties#unitsOf} gives it, or null if it doesn't plan by line
	 * @return whether the auto duties need working out again, which is then taken as done
	 */
	public boolean takeAutoDutiesChange(LineDuties.Unit unit) {
		setLineUnit(unit);
		final String key = unit == null ? null : unit.key;
		if (key == null) {
			// No longer planned by line, as when its routes were deleted or lost their timetables: its paths are found once
			// more, which drops the auto duties of the routes it no longer runs, then not again until it plans by line
			if (!NOT_BY_LINE.equals(autoDutiesKey) && autoDuties.diagramMode && !autoDuties.sidings.isEmpty()) {
				autoDutiesKey = NOT_BY_LINE;
				return true;
			}
			autoDutiesKey = NOT_BY_LINE;
			return false;
		}
		if (key.equals(autoDutiesKey)) {
			return false;
		}
		autoDutiesKey = key;
		return true;
	}

	/**
	 * @return the duties the depot runs by: the ones set for it, or for a depot running by a timetable with none set the
	 * ones worked out from its timetables; null for a depot sending trains out by frequency
	 */
	public DepotDuties getRunDuties() {
		if (duties.diagramMode) {
			return duties;
		}
		return autoDuties.diagramMode && !transportMode.continuousMovement ? autoDuties : null;
	}

	@Override
	public boolean hasDutyRun(long sidingId, int slot, long day) {
		return dispatcher.hasDutyRun(sidingId, slot, day);
	}

	@Override
	public void markDutyRun(long sidingId, int slot, long day) {
		dispatcher.markDutyRun(sidingId, slot, day);
	}

	@Override
	public void giveUpService(long routeId, int fromStop, long serviceMillis) {
		dispatcher.giveUpService(routeId, fromStop, serviceMillis);
	}

	@Override
	public void expectLate(String trainId, long routeId, int fromStop, long serviceMillis) {
		dispatcher.expectLate(trainId, routeId, fromStop, serviceMillis);
	}

	@Override
	public boolean isServiceTaken(long routeId, int fromStop, long serviceMillis) {
		return dispatcher.isServiceTaken(routeId, fromStop, serviceMillis);
	}

	@Override
	public long takeFreeService(long routeId, int fromStop, long afterMillis, long fromMillis, long untilMillis, long currentMillis) {
		return dispatcher.takeFreeService(routeId, fromStop, afterMillis, fromMillis, untilMillis, currentMillis);
	}

	/**
	 * @return the timetable of one of the depot's routes running by one, for showing that departures follow a timetable
	 */
	public RouteTimetable findTimetable(DataCache dataCache) {
		final Route route = findTimetabledRoute(getRoutes(dataCache));
		return route == null ? null : route.timetable;
	}

	/**
	 * @return the timetable sending out this vehicle, which belongs to the first route it runs
	 */
	public RouteTimetable getTimetable(String trainId) {
		final Route route = findTimetabledRoute(getTrainRoutes(trainId));
		return route == null ? null : route.timetable;
	}

	RouteTimetable getActiveTimetable(Route route) {
		return supportsTimetable(transportMode) && route.timetable.isActive() ? route.timetable : null;
	}

	/**
	 * @return the first of the routes running by a timetable, or null: a depot's routes are the lines it runs, in no
	 * order, so any of them having a timetable makes the depot run by a timetable
	 */
	Route findTimetabledRoute(List<Route> routes) {
		return routes.stream().filter(route -> getActiveTimetable(route) != null).findFirst().orElse(null);
	}

	/**
	 * @return whether the depot's trains run by duties, set or worked out from its timetables, whatever the order of its
	 * routes; then they run only on their duties' paths, and the routes aren't joined into one path
	 */
	public boolean runsByDutiesWith(List<Route> routes) {
		return duties.diagramMode || !transportMode.continuousMovement && findTimetabledRoute(routes) != null;
	}

	/**
	 * @return the routes this vehicle runs through, in order, kept for one tick as this is needed for every train
	 */
	public List<Route> getTrainRoutes(String trainId) {
		final List<Route> routes = trainRoutes.get(trainId);
		if (routes != null) {
			return routes;
		}
		final List<Route> newRoutes = dataCache == null ? new ArrayList<>() : getRoutes(trainId, dataCache);
		trainRoutes.put(trainId, newRoutes);
		return newRoutes;
	}

	public List<Route> getRoutes(DataCache dataCache) {
		final List<Route> routes = new ArrayList<>();
		routeIds.forEach(routeId -> {
			final Route route = dataCache.routeIdMap.get(routeId);
			if (route != null) {
				routes.add(route);
			}
		});
		return routes;
	}

	/**
	 * @return the routes a siding's trains run: the depot's, those the siding is given if any, then those its vehicle's
	 * types of service run
	 */
	public List<Route> getSidingRoutes(Siding siding, DataCache dataCache) {
		final Set<Long> sidingRouteIds = siding.getRouteIds();
		final List<Route> routes = getRoutes(dataCache).stream().filter(route -> sidingRouteIds.isEmpty() || sidingRouteIds.contains(route.id)).collect(Collectors.toList());
		return filterRoutes(routes, getServiceTypes(siding.getTrainId()));
	}

	/**
	 * @return the IDs of {@link #getSidingRoutes}
	 */
	public List<Long> getSidingRouteIds(Siding siding, DataCache dataCache) {
		if (siding.getRouteIds().isEmpty()) {
			return getRouteIds(siding.getTrainId(), dataCache);
		}
		final List<Long> ids = new ArrayList<>();
		getSidingRoutes(siding, dataCache).forEach(route -> ids.add(route.id));
		return ids;
	}

	/**
	 * @return what sidings planned together have in common: their vehicles' types of service and the routes they're given
	 */
	public static String getSidingGroupKey(Set<String> serviceTypes, Siding siding) {
		return serviceTypes + "|" + siding.getRouteIds();
	}

	public List<Route> getRoutes(String trainId, DataCache dataCache) {
		return filterRoutes(getRoutes(dataCache), getServiceTypes(trainId));
	}

	/**
	 * @return the IDs of the routes a vehicle runs, which is what stop indices on its path count through
	 */
	public List<Long> getRouteIds(String trainId, DataCache dataCache) {
		if (getServiceTypes(trainId).isEmpty()) {
			return routeIds;
		}
		final List<Long> newRouteIds = new ArrayList<>();
		getRoutes(trainId, dataCache).forEach(route -> newRouteIds.add(route.id));
		return newRouteIds;
	}

	public Set<String> getServiceTypes(String trainId) {
		return parseServiceTypes(vehicleServiceTypes.getOrDefault(trainId, ""));
	}

	public static Set<String> parseServiceTypes(String text) {
		final Set<String> serviceTypes = new TreeSet<>();
		for (final String serviceType : text.split("[,，、;；\\s]+")) {
			if (!serviceType.isEmpty()) {
				serviceTypes.add(serviceType.toLowerCase(Locale.ENGLISH));
			}
		}
		return serviceTypes;
	}

	/**
	 * A route with no type of service can be run by every vehicle, so a line doesn't stop working by leaving it out.
	 */
	public static List<Route> filterRoutes(List<Route> routes, Set<String> serviceTypes) {
		return serviceTypes.isEmpty() ? routes : routes.stream().filter(route -> route.getServiceType().isEmpty() || serviceTypes.contains(route.getServiceType())).collect(Collectors.toList());
	}

	public void setVehicleServiceTypes(String trainId, String text, Consumer<FriendlyByteBuf> sendPacket) {
		if (parseServiceTypes(text).isEmpty()) {
			vehicleServiceTypes.remove(trainId);
		} else {
			vehicleServiceTypes.put(trainId, text.trim());
		}
		setData(sendPacket);
	}

	public static boolean supportsTimetable(TransportMode transportMode) {
		return !transportMode.continuousMovement && transportMode != TransportMode.AIRPLANE;
	}

	/**
	 * The two depots run a route in common, so a service either one's trains take isn't taken by the other's too.
	 */
	public void shareServicesWith(Depot other) {
		dispatcher.shareServicesWith(other.dispatcher);
	}

	public void clearSharedServices() {
		dispatcher.clearPeers();
	}

	public void reserveTrip(long routeId, long tripMillis) {
		dispatcher.reserveTrip(routeId, tripMillis);
	}

	/**
	 * @return the first service of this vehicle's first route from this time on that no train has taken, or 0 if a train would wait too long for it
	 */
	public long findNextTrip(String trainId, long readyMillis) {
		final List<Route> routes = getTrainRoutes(trainId);
		return routes.isEmpty() ? 0 : findNextService(routes.get(0), readyMillis);
	}

	/**
	 * @return the first service of this route from this time on that no train has taken, or 0 if a train would wait too long for
	 * it. A train that stays on the line waits for its next service however long that is.
	 */
	public long findNextService(PlanRoute route, long readyMillis) {
		return dispatcher.findNextService(route, readyMillis, cycleRoutes);
	}

	@Override
	public boolean keepsTrainsOnLine() {
		return cycleRoutes;
	}

	public boolean isTripReserved(long routeId, long tripMillis) {
		return dispatcher.isTripReserved(routeId, tripMillis);
	}

	/**
	 * Like {@link #getNextDepartureMillis()}, using the vehicle's timetable if it has one, which skips services already taken.
	 */
	public int getNextDepartureMillis(String trainId, long leadMillis) {
		final RouteTimetable timetable = getTimetable(trainId);
		if (timetable == null) {
			return getNextDepartureMillis();
		}

		departureOffset++;
		return dispatcher.getNextDepartureMillis(timetable, getTrainRoutes(trainId).get(0).id, System.currentTimeMillis(), leadMillis, departureOffset);
	}

	public int getNextDepartureMillis() {
		departureOffset++;
		final int millisUntilDeploy = getMillisUntilDeploy(departureOffset);
		return millisUntilDeploy >= 0 ? millisUntilDeploy : -1;
	}

	public int getMillisUntilDeploy(int offset) {
		return getMillisUntilDeploy(offset, 0);
	}

	public int getMillisUntilDeploy(int offset, int currentTimeOffset) {
		return DepotDispatcher.getMillisUntilDeploy(tempDepartures, lastDeployedMillis + currentTimeOffset, System.currentTimeMillis() + currentTimeOffset, offset);
	}

	public void generateTempDepartures(Level world) {
		tempDepartures.clear();
		if (useRealTime && !transportMode.continuousMovement) {
			tempDepartures.addAll(departures);
		} else if (world != null) {
			int millisOffset = 0;
			while (millisOffset < MILLISECONDS_PER_DAY) {
				final int tempFrequency = getFrequency(getHour(world, millisOffset));
				if (tempFrequency == 0 && !transportMode.continuousMovement) {
					millisOffset = (int) (Math.floor((float) millisOffset / MILLIS_PER_TICK / TICKS_PER_HOUR) + 1) * TICKS_PER_HOUR * MILLIS_PER_TICK;
				} else {
					tempDepartures.add((int) ((lastDeployedMillis + millisOffset) % MILLISECONDS_PER_DAY));
					millisOffset += transportMode.continuousMovement ? CONTINUOUS_MOVEMENT_FREQUENCY : TICKS_PER_HOUR * MILLIS_PER_TICK * TRAIN_FREQUENCY_MULTIPLIER / tempFrequency;
				}
			}
			tempDepartures.sort(Integer::compareTo);
		}
		isDirty = false;
	}

	private static int getHour(Level world, int offsetMillis) {
		return (int) wrapTime(world.getDayTime() + (float) offsetMillis / MILLIS_PER_TICK) / TICKS_PER_HOUR;
	}

	private static float wrapTime(float time) {
		return (time + 6000 + TICKS_PER_DAY) % TICKS_PER_DAY;
	}

	/**
	 * Sets the trains the depot gives each pool of its sidings (P2-C), a pool left out keeping its sidings' fleets added up.
	 */
	/**
	 * Applies the depot's rules for going out and back to duties just planned (运用补完 step 2): by the rule the first
	 * service of each day matches, how much earlier than its way out the train leaves, the stations it goes out and back
	 * by, the named deadhead paths and the siding it goes back to (one of its pool); then keeps what the duties planned
	 * before set as exceptions, for the same duty number and weekdays.
	 *
	 * @param before the depot's duties as they were
	 */
	void applyRules(DepotDuties planned, DepotDuties before, DataCache dataCache) {
		planned.sidings.forEach((sidingId, slots) -> {
			final Siding siding = dataCache.sidingIdMap.get(sidingId);
			final List<DepotDuties.Slot> beforeSlots = before.sidings.getOrDefault(sidingId, Collections.emptyList());
			for (final DepotDuties.Slot slot : slots) {
				for (final DepotDuties.DayDuty day : slot.days) {
					if (day.segments.isEmpty()) {
						continue;
					}
					final Route route = dataCache.routeIdMap.get(day.segments.get(0).routeId);
					final transitsim.plan.DepotRules.Rule rule = route == null ? null : rules.match(LineDuties.getLineKey(route), Integer.numberOfTrailingZeros(day.weekdays));
					if (rule != null) {
						if (day.outLeadMillis >= 0) {
							day.outLeadMillis += rule.extraLeadMillis;
						}
						day.outEntrance = rule.outEntrance;
						day.backEntrance = rule.backEntrance;
						day.outPathId = rule.outPath;
						day.backPathId = rule.backPath;
						final Siding backSiding = rule.backSiding == 0 || day.stayOnLine ? null : dataCache.sidingIdMap.get(rule.backSiding);
						if (backSiding != null && siding != null && backSiding.getPoolKey().equals(siding.getPoolKey())) {
							day.returnSidingId = backSiding.id;
						}
					}
					for (final DepotDuties.Slot beforeSlot : beforeSlots) {
						if (beforeSlot.id == slot.id) {
							beforeSlot.days.stream().filter(beforeDay -> (beforeDay.weekdays & day.weekdays) != 0 && beforeDay.exceptions != 0).findFirst().ifPresent(day::keepExceptions);
						}
					}
				}
			}
		});
	}

	/**
	 * Replaces the depot's rules for going out and back and its named deadhead paths (运用补完 step 2).
	 */
	public void setRules(transitsim.plan.DepotRules newRules, Consumer<FriendlyByteBuf> sendPacket) {
		rules.fromBytes(newRules.toBytes());
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DEPOT_RULES);
		packet.writeByteArray(rules.toBytes());
		sendPacket.accept(packet);
		isDirty = true;
	}

	public void setPoolFleets(Map<String, Integer> newPoolFleets, Consumer<FriendlyByteBuf> sendPacket) {
		poolFleets.clear();
		newPoolFleets.forEach((poolKey, fleet) -> {
			if (poolFleets.size() < MAX_ROUTES) {
				poolFleets.put(poolKey, Mth.clamp(fleet, -1, MAX_POOL_FLEET));
			}
		});
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_POOL_FLEETS);
		writePoolFleets(packet);
		sendPacket.accept(packet);
		isDirty = true;
	}

	/**
	 * @return the trains the depot gives a pool of its sidings: as set, or its sidings' fleets added up, -1 if one of them
	 * has as many as needed or for a pool set to that
	 */
	public int getPoolFleet(String poolKey, Collection<Siding> poolSidings) {
		final Integer fleet = poolFleets.get(poolKey);
		if (fleet != null) {
			return fleet;
		}
		final List<Integer> fleets = new ArrayList<>();
		poolSidings.forEach(siding -> fleets.add(siding.getFleetSize()));
		return transitsim.sim.FleetRules.sumFleets(fleets);
	}

	/**
	 * @return the trains the siding's pool has in all across its line (P2-C): what each depot of the line gives it, added
	 * up, or -1 for as many as its duties need
	 */
	public int getLinePoolFleet(Siding siding) {
		final Map<Depot, List<Siding>> byDepot = new LinkedHashMap<>();
		for (final Siding poolSiding : getLinePool(siding)) {
			final Depot depot = dataCache == null ? this : dataCache.sidingIdToDepot.getOrDefault(poolSiding.id, this);
			byDepot.computeIfAbsent(depot, key -> new ArrayList<>()).add(poolSiding);
		}
		final List<Integer> fleets = new ArrayList<>();
		byDepot.forEach((depot, depotSidings) -> fleets.add(depot.getPoolFleet(siding.getPoolKey(), depotSidings)));
		return transitsim.sim.FleetRules.sumFleets(fleets);
	}

	/**
	 * Sets how the trains of each pool of its sidings run (R1); a pool left out runs as its first siding has it set.
	 */
	public void setVehicleSettings(Map<String, VehicleSettings> newSettings, Consumer<FriendlyByteBuf> sendPacket) {
		vehicleSettings.clear();
		newSettings.forEach((poolKey, settings) -> {
			if (vehicleSettings.size() < MAX_ROUTES) {
				vehicleSettings.put(poolKey, settings);
			}
		});
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_VEHICLE_SETTINGS);
		writeVehicleSettings(packet);
		sendPacket.accept(packet);
		isDirty = true;
	}

	/**
	 * @return how the trains of the siding's pool run: as set for the pool, or as its first siding, by ID, has it set
	 */
	public VehicleSettings getVehicleSettings(Siding siding) {
		final VehicleSettings set = vehicleSettings.get(siding.getPoolKey());
		if (set != null) {
			return set;
		}
		final Siding first = getPool(siding).stream().min(Comparator.comparingLong(poolSiding -> poolSiding.id)).orElse(siding);
		return first.getOwnVehicleSettings();
	}

	private void readVehicleSettings(FriendlyByteBuf packet) {
		vehicleSettings.clear();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			final String poolKey = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final VehicleSettings settings = VehicleSettings.read(packet);
			if (i < MAX_ROUTES) {
				vehicleSettings.put(poolKey, settings);
			}
		}
	}

	private void writeVehicleSettings(FriendlyByteBuf packet) {
		packet.writeInt(vehicleSettings.size());
		vehicleSettings.forEach((poolKey, settings) -> {
			packet.writeUtf(poolKey);
			settings.write(packet);
		});
	}

	private void readPoolFleets(FriendlyByteBuf packet) {
		poolFleets.clear();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			final String poolKey = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final int fleet = packet.readInt();
			if (i < MAX_ROUTES) {
				poolFleets.put(poolKey, Mth.clamp(fleet, -1, MAX_POOL_FLEET));
			}
		}
	}

	private void writePoolFleets(FriendlyByteBuf packet) {
		packet.writeInt(poolFleets.size());
		poolFleets.forEach((poolKey, fleet) -> {
			packet.writeUtf(poolKey);
			packet.writeInt(fleet);
		});
	}

	private void readVehicleServiceTypes(FriendlyByteBuf packet) {
		vehicleServiceTypes.clear();
		final int count = packet.readInt();
		for (int i = 0; i < count; i++) {
			final String trainId = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final String serviceTypes = packet.readUtf(PACKET_STRING_READ_LENGTH);
			if (i < MAX_ROUTES) {
				vehicleServiceTypes.put(trainId, serviceTypes);
			}
		}
	}

	private void writeVehicleServiceTypes(FriendlyByteBuf packet) {
		packet.writeInt(vehicleServiceTypes.size());
		vehicleServiceTypes.forEach((trainId, serviceTypes) -> {
			packet.writeUtf(trainId);
			packet.writeUtf(serviceTypes);
		});
	}

	/**
	 * The path through some of the depot's routes, shared by the sidings whose vehicles run those routes.
	 */
	private List<Siding> getSidingsInDepot(RailwayData railwayData) {
		// Also worked out again every few seconds, as a siding renamed changes the order without changing the data's version
		final String key = railwayData.sidings.size() + "/" + railwayData.dataCache.getVersion() + "/" + corner1 + corner2 + transportMode + "/" + System.currentTimeMillis() / 5000;
		if (sidingsInDepotCache == null || !key.equals(sidingsInDepotKey)) {
			sidingsInDepotCache = railwayData.sidings.stream().filter(siding -> {
				final BlockPos sidingPos = siding.getMidPos();
				return siding.isTransportMode(transportMode) && inArea(sidingPos.getX(), sidingPos.getZ());
			}).sorted().collect(Collectors.toList());
			sidingsInDepotKey = key;
		}
		return sidingsInDepotCache;
	}

	/**
	 * Whether a planning unit's lines have trains enough ({@link LineDuties.Plan}): the trains needed to run every service,
	 * those the sidings hold, the services a day with no train and where to add trains.
	 */
	/**
	 * A duty group as the clients see it (duty groups, step 4): its key ({@link LineDuties.Part#key}: the vehicles, a
	 * digest of the group of sidings and the weekdays), the trains it runs at most and needs, the services a day with no
	 * train, and whether the last plan kept it as it was.
	 */
	public static final class DutyGroup {

		public final String key;
		public final int trains;
		public final int needed;
		public final int uncoveredPerDay;
		public final boolean kept;

		public DutyGroup(String key, int trains, int needed, int uncoveredPerDay, boolean kept) {
			this.key = key;
			this.trains = trains;
			this.needed = needed;
			this.uncoveredPerDay = uncoveredPerDay;
			this.kept = kept;
		}

		/**
		 * @return the vehicles of its sidings
		 */
		public String getLabel() {
			final int end = key.indexOf('#');
			return end < 0 ? key : key.substring(0, end);
		}

		/**
		 * @return its weekdays, bit 0 Monday
		 */
		public int getWeekdays() {
			try {
				return Integer.parseInt(key.substring(key.lastIndexOf('|') + 1));
			} catch (RuntimeException e) {
				return 0;
			}
		}
	}

	public static final class Shortfall {

		public final String lineNames;
		public final int needed;
		public final int available;
		public final int uncoveredPerDay;
		public final String suggestedSiding;
		/**
		 * The settings changed since the duties kept were planned ({@link LineDuties.Plan#replanHint}), or empty.
		 */
		public final String replanHint;
		/**
		 * The same for each group of sidings planned apart, by its vehicles, with services a day: trains aren't lent from
		 * one group to another, so the totals alone can show enough trains while one group is short.
		 */
		public final List<LineDuties.Pool> pools;
		/**
		 * The duty groups of the unit (duty groups, step 4): each group of sidings on one day type, for the advanced editor.
		 */
		public final List<DutyGroup> groups = new ArrayList<>();
		/**
		 * Services a day no number of trains could run, as no siding reaches them and no train can turn to them: not
		 * trains short, but a way out of the sidings or round missing.
		 */
		public int unrunnablePerDay;
		/**
		 * The lines the depot reaches at more than one station with no rule saying which its trains go by (运用补完 step 2).
		 */
		public final List<String> entrancesUnset = new ArrayList<>();

		private Shortfall(String lineNames, int needed, int available, int uncoveredPerDay, String suggestedSiding, String replanHint, List<LineDuties.Pool> pools) {
			this.lineNames = lineNames;
			this.needed = needed;
			this.available = available;
			this.uncoveredPerDay = uncoveredPerDay;
			this.suggestedSiding = suggestedSiding;
			this.replanHint = replanHint;
			this.pools = pools;
		}

		Shortfall(LineDuties.Plan plan) {
			this(plan.lineNames, plan.needed, plan.available, plan.uncovered / 7, plan.suggestedSiding, plan.replanHint, perDay(plan.pools));
			plan.parts.forEach(part -> groups.add(new DutyGroup(part.key, part.trains, part.needed, part.uncovered / 7, plan.kept || plan.keptParts.contains(part.key))));
			unrunnablePerDay = plan.getUnrunnable() / 7;
		}

		private static List<LineDuties.Pool> perDay(List<LineDuties.Pool> pools) {
			final List<LineDuties.Pool> perDay = new ArrayList<>();
			pools.forEach(pool -> perDay.add(new LineDuties.Pool(pool.label, pool.needed, pool.available, pool.uncovered / 7)));
			return perDay;
		}

		/**
		 * @return how many trains short the groups are, each counted on its own, or -1 if one's need isn't known
		 */
		public int getMissingTrains() {
			if (pools.isEmpty()) {
				return needed < 0 ? -1 : Math.max(0, needed - available);
			}
			int missing = 0;
			for (final LineDuties.Pool pool : pools) {
				if (pool.uncovered > 0) {
					if (pool.needed < 0) {
						return -1;
					}
					missing += Math.max(0, pool.needed - pool.available);
				}
			}
			return missing;
		}

		public static void write(Shortfall shortfall, FriendlyByteBuf packet) {
			packet.writeBoolean(shortfall != null);
			if (shortfall != null) {
				packet.writeUtf(shortfall.lineNames);
				packet.writeInt(shortfall.needed);
				packet.writeInt(shortfall.available);
				packet.writeInt(shortfall.uncoveredPerDay);
				packet.writeUtf(shortfall.suggestedSiding);
				packet.writeUtf(shortfall.replanHint);
				packet.writeVarInt(shortfall.pools.size());
				shortfall.pools.forEach(pool -> {
					packet.writeUtf(pool.label);
					packet.writeInt(pool.needed);
					packet.writeInt(pool.available);
					packet.writeInt(pool.uncovered);
				});
				packet.writeVarInt(shortfall.groups.size());
				shortfall.groups.forEach(group -> {
					packet.writeUtf(group.key);
					packet.writeInt(group.trains);
					packet.writeInt(group.needed);
					packet.writeInt(group.uncoveredPerDay);
					packet.writeBoolean(group.kept);
				});
				packet.writeVarInt(shortfall.unrunnablePerDay);
				packet.writeVarInt(shortfall.entrancesUnset.size());
				shortfall.entrancesUnset.forEach(packet::writeUtf);
			}
		}

		public static Shortfall read(FriendlyByteBuf packet) {
			if (!packet.readBoolean()) {
				return null;
			}
			final String lineNames = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final int needed = packet.readInt();
			final int available = packet.readInt();
			final int uncoveredPerDay = packet.readInt();
			final String suggestedSiding = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final String replanHint = packet.readUtf(PACKET_STRING_READ_LENGTH);
			final List<LineDuties.Pool> pools = new ArrayList<>();
			for (int i = Math.min(packet.readVarInt(), 256); i > 0; i--) {
				pools.add(new LineDuties.Pool(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readInt(), packet.readInt(), packet.readInt()));
			}
			final Shortfall shortfall = new Shortfall(lineNames, needed, available, uncoveredPerDay, suggestedSiding, replanHint, pools);
			for (int i = Math.min(packet.readVarInt(), 4096); i > 0; i--) {
				shortfall.groups.add(new DutyGroup(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readInt(), packet.readInt(), packet.readInt(), packet.readBoolean()));
			}
			shortfall.unrunnablePerDay = packet.readVarInt();
			for (int i = Math.min(packet.readVarInt(), 4096); i > 0; i--) {
				shortfall.entrancesUnset.add(packet.readUtf(PACKET_STRING_READ_LENGTH));
			}
			return shortfall;
		}
	}

}
