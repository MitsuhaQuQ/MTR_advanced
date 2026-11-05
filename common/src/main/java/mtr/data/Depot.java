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
	 * Which services each train runs, set in the advanced editor. See {@link DepotDuties}.
	 */
	public final DepotDuties duties = new DepotDuties();
	/**
	 * The duties worked out from the timetables when the depot runs by a timetable and has none set, made again with its
	 * paths (decoupling document, step D). The depot then only sends trains out when their duties start.
	 */
	public final DepotDuties autoDuties = new DepotDuties();
	/**
	 * What the auto duties were last worked out from, so they are worked out again when it changes; not saved, so they
	 * are worked out again once after the world is loaded.
	 */
	private String autoDutiesKey;
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
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();
	private DataCache dataCache;
	private final Map<String, List<Route>> trainRoutes = new HashMap<>();
	/**
	 * Which services trains have taken and which duties have run; saved here, decided in the component.
	 */
	private final DepotDispatcher dispatcher = new DepotDispatcher();
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
	private static final String KEY_USE_REAL_TIME = "use_real_time";
	private static final String KEY_FREQUENCIES = "frequencies";
	private static final String KEY_DEPARTURES = "departures";
	private static final String KEY_LAST_DEPLOYED = "last_deployed";
	private static final String KEY_LAST_DEPLOYED_TRIPS = "last_deployed_trips";
	private static final String KEY_VEHICLE_SERVICE_TYPES = "vehicle_service_types";
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
		speedCurves = messagePackHelper.getBoolean(KEY_SPEED_CURVES);
		releaseInterval = Mth.clamp(messagePackHelper.getInt(KEY_RELEASE_INTERVAL, 0), 0, MAX_RELEASE_INTERVAL);
		lastDeployedMillis = System.currentTimeMillis() - messagePackHelper.getLong(KEY_LAST_DEPLOYED);
		messagePackHelper.iterateMapValue(KEY_LAST_DEPLOYED_TRIPS, entry -> dispatcher.lastDeployedTrips.put(Long.parseLong(entry.getKey().asStringValue().asString()), entry.getValue().asIntegerValue().asLong()));
		messagePackHelper.iterateMapValue(KEY_VEHICLE_SERVICE_TYPES, entry -> vehicleServiceTypes.put(entry.getKey().asStringValue().asString(), entry.getValue().asStringValue().asString()));
		final Value dutiesValue = map.get(KEY_DUTIES);
		if (dutiesValue != null && dutiesValue.isBinaryValue()) {
			duties.fromBytes(dutiesValue.asBinaryValue().asByteArray());
		}
		duties.diagramMode = DUTY_MODE_DIAGRAM.equals(messagePackHelper.getString(KEY_DUTY_MODE));
		final Value autoDutiesValue = map.get(KEY_AUTO_DUTIES);
		if (autoDutiesValue != null && autoDutiesValue.isBinaryValue()) {
			autoDuties.fromBytes(autoDutiesValue.asBinaryValue().asByteArray());
		}
		messagePackHelper.iterateMapValue(KEY_DUTY_RUN_DAYS, entry -> dispatcher.dutyRunDays.put(entry.getKey().asStringValue().asString(), entry.getValue().asIntegerValue().asLong()));
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
		speedCurves = packet.readBoolean();
		autoDuties.read(PacketBinary.of(packet));
		releaseInterval = packet.readInt();
		lineShortfall = Shortfall.read(packet);
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

		final byte[] dutyBytes = duties.toBytes();
		messagePacker.packString(KEY_DUTIES).packBinaryHeader(dutyBytes.length);
		messagePacker.writePayload(dutyBytes);
		messagePacker.packString(KEY_DUTY_MODE).packString(duties.diagramMode ? DUTY_MODE_DIAGRAM : DUTY_MODE_ONLINE);
		final byte[] autoDutyBytes = autoDuties.toBytes();
		messagePacker.packString(KEY_AUTO_DUTIES).packBinaryHeader(autoDutyBytes.length);
		messagePacker.writePayload(autoDutyBytes);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 17;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 4;
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
			speedCurves = packet.readBoolean();
			releaseInterval = Mth.clamp(packet.readInt(), 0, MAX_RELEASE_INTERVAL);
		} else if (KEY_DUTY_MODE.equals(key)) {
			duties.diagramMode = packet.readBoolean();
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
		sendPacket.accept(packet);
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

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache liveDataCache, Map<BlockPos, Map<BlockPos, Rail>> liveRails, Set<Siding> sidings, Consumer<Thread> callback) {
		// The thread works on the rails and data as they are now, so a player laying track meanwhile can't break it part of
		// the way through or have it find a path on a network changed half way
		final DataCache dataCache = liveDataCache.copyIdMaps();
		final Map<BlockPos, Map<BlockPos, Rail>> rails = DataCache.copyRails(liveRails);
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
		final List<MainPath> mainPaths = new ArrayList<>();
		sidingGroups.forEach((groupKey, sidingGroup) -> {
			final MainPath mainPath = new MainPath(sidingGroup.isEmpty() ? getRoutes(dataCache) : getSidingRoutes(sidingGroup.get(0), dataCache), sidingGroup, dataCache);
			mainPath.lineSidings = lineSidings;
			mainPaths.add(mainPath);
		});

		final RailwayData railwayData = RailwayData.getInstance(world);
		final RailGraph<Rail> graph = getPathGraph(rails, railwayData == null ? null : railwayData.pathBoundaries);
		// Depots running by a timetable take their routes' paths from the routes (decoupling document 5.4, L1)
		final RoutePaths.Search routeSearch = railwayData == null ? null : new RoutePaths.Search(railwayData.routePaths, rails, graph, railwayData.pathBoundaries, RoutePaths.getVehicles(railwayData));
		mainPaths.forEach(mainPath -> mainPath.routeSearch = routeSearch);

		final Thread thread = new Thread(() -> {
			try {
				int successfulSegments = Integer.MAX_VALUE;
				PathFailure failure = PathFailure.NONE;
				// The duties of the whole unit, planned at once by operating line, of which this depot keeps its own sidings'
				final LineDuties.Plan linePlan = lineUnit == null || routeSearch == null ? null : lineUnit.plan(route -> {
					final long[][] times = routeSearch.getFastestTimes(route, dataCache);
					return times == null || times.length == 0 ? null : times;
				}, TimetableConfig.getDayStartMillis());
				if (linePlan != null) {
					System.out.println("Minecraft Transit Railway: planned the duties of " + linePlan.describe());
				}
				for (final MainPath mainPath : mainPaths) {
					successfulSegments = Math.min(successfulSegments, mainPath.generate(minecraftServer, rails, graph, useFastSpeed, linePlan));
					failure = mainPath.failure == PathFailure.NONE ? failure : mainPath.failure;
				}
				// Duties whose paths weren't found, which only the server's log told of before
				final List<String> failedDuties = new ArrayList<>();
				mainPaths.forEach(mainPath -> mainPath.sidings.forEach(siding -> failedDuties.addAll(siding.getLastFailedDuties())));
				PacketTrainDataGuiServer.generatePathS2C(world, id, successfulSegments, failure, failedDuties);
				System.out.println("Finished path generation" + (name.isEmpty() ? "" : " for " + name));
			} catch (CancellationException e) {
				// A newer request restarted path generation, and that thread reports the result
				System.out.println("Stopped path generation" + (name.isEmpty() ? "" : " for " + name));
			} catch (Exception e) {
				e.printStackTrace();
				PacketTrainDataGuiServer.generatePathS2C(world, id, 0);
				System.out.println("Failed to generate path" + (name.isEmpty() ? "" : " for " + name));
			}
		});
		callback.accept(thread);
		thread.start();
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
		lastDeployedMillis = simulated.lastDeployedMillis;
		deployIndex = Math.max(0, simulated.deployIndex);
	}

	public void recordDispatcher(RunRecord record, long sidingId) {
		record.lastDeployedTrips.put(sidingId, new TreeMap<>(dispatcher.lastDeployedTrips));
		record.dutyRunDays.put(sidingId, new TreeMap<>(dispatcher.dutyRunDays));
		record.lastDeployedMillis.put(sidingId, lastDeployedMillis);
		record.deployIndices.put(sidingId, deployIndex);
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
			final MainPath mainPath = new MainPath(getSidingRoutes(sidingGroup.get(0), dataCache), sidingGroup, dataCache);
			sidingGroup.forEach(siding -> {
				final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = mainPath.getDutyPlans(draft, siding, rails, graph, useFastSpeed, failed);
				dutyPaths.put(siding.id, siding.findDutyPaths(dutyPlans, rails, graph, cruisingAltitude, useFastSpeed, failed));
			});
		});
		return dutyPaths;
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
		return useDeterministicPath && transportMode != TransportMode.AIRPLANE && !transportMode.continuousMovement ? DeterministicPathFinder.compile(rails, boundaries) : null;
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
		final List<Siding> pool = new ArrayList<>(getPool(siding));
		if (dataCache != null && lineDepotIds.size() > 1) {
			new TreeSet<>(lineDepotIds).forEach(depotId -> {
				final Depot other = dataCache.depotIdMap.get(depotId);
				if (other != null && other != this) {
					pool.addAll(other.pools.getOrDefault(siding.getPoolKey(), Collections.emptyList()));
				}
			});
		}
		return pool;
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
		return getLinePool(siding).stream().mapToInt(poolSiding -> poolSiding.getTrainCount() + poolSiding.getPendingArrivals()).sum();
	}

	@Override
	public long chooseReturnSiding(long sidingId) {
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
			// Running by duties, a siding holds its fleet, however long its rails
			if (otherSidingId == sidingId || other.getFleetSize() < 0) {
				return true;
			}
			// Room for the trains it holds and the ones already on their way to it
			final int coming = pool.stream().mapToInt(poolSiding -> poolSiding.countReturningTo(otherSidingId)).sum();
			return other.getTrainCount() + coming + other.getPendingArrivals() < other.getFleetSize();
		});
	}

	@Override
	public long chooseReturnSiding(long sidingId, long depotId) {
		final Siding siding = dataCache == null ? null : dataCache.sidingIdMap.get(sidingId);
		final Depot orderDepot = dataCache == null ? null : dataCache.depotIdMap.get(depotId);
		if (siding == null || orderDepot == null) {
			return chooseReturnSiding(sidingId);
		}
		final Map<Long, Siding> candidates = new TreeMap<>();
		orderDepot.pools.getOrDefault(siding.getPoolKey(), Collections.emptyList()).forEach(other -> {
			if (other == siding || other.getFleetSize() < 0 || other.getTrainCount() + other.getPendingArrivals() + countReturningTo(other.id) < other.getFleetSize()) {
				candidates.put(other.id, other);
			}
		});
		if (candidates.isEmpty()) {
			return chooseReturnSiding(sidingId);
		}
		final long first = candidates.containsKey(sidingId) ? sidingId : candidates.keySet().iterator().next();
		return DepotDispatcher.chooseReturnSiding(first, candidates.keySet(), this::getSlotsOf, (eachSidingId, slot, day) -> {
			final Depot depot = dataCache.sidingIdToDepot.get(eachSidingId);
			return depot != null && depot.hasDutyRun(eachSidingId, slot, day);
		}, System.currentTimeMillis(), eachSidingId -> true);
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
		final DepotDispatcher.DutyDeploy<PathData> deploy = dispatcher.findDutyDeploy(currentMillis, new TreeSet<>(deployableSidings.keySet()), getRunDuties(), sidingId -> {
			final Siding siding = railwayData.dataCache.sidingIdMap.get(sidingId);
			return siding == null ? null : siding.getTripPaths();
		}, railwayData.dataCache.routeIdMap::get, sidingId -> {
			final Siding siding = railwayData.dataCache.sidingIdMap.get(sidingId);
			return siding == null ? sidingId : siding.getPoolKey();
		});
		if (deploy != null) {
			lastDeployedMillis = currentMillis;
			deployableSidings.get(deploy.sidingId).deployDuty(deploy.slot, deploy.dutySidingId, deploy.dayStart, deploy.path);
			if (deploy.late) {
				deployableSidings.get(deploy.sidingId).joinDutyLate();
			}
		}
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
	public String getLineUnitKey() {
		return lineUnitKey;
	}

	/**
	 * @return whether the auto duties need working out again, which is then taken as done
	 */
	public boolean takeAutoDutiesChange(DataCache dataCache, Set<Siding> sidings) {
		final LineDuties.Unit unit = LineDuties.of(this, dataCache, sidings);
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

	private RouteTimetable getActiveTimetable(Route route) {
		return supportsTimetable(transportMode) && route.timetable.isActive() ? route.timetable : null;
	}

	/**
	 * @return the first of the routes running by a timetable, or null: a depot's routes are the lines it runs, in no
	 * order, so any of them having a timetable makes the depot run by a timetable
	 */
	private Route findTimetabledRoute(List<Route> routes) {
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
	public static final class Shortfall {

		public final String lineNames;
		public final int needed;
		public final int available;
		public final int uncoveredPerDay;
		public final String suggestedSiding;

		private Shortfall(String lineNames, int needed, int available, int uncoveredPerDay, String suggestedSiding) {
			this.lineNames = lineNames;
			this.needed = needed;
			this.available = available;
			this.uncoveredPerDay = uncoveredPerDay;
			this.suggestedSiding = suggestedSiding;
		}

		Shortfall(LineDuties.Plan plan) {
			this(plan.lineNames, plan.needed, plan.available, plan.uncovered / 7, plan.suggestedSiding);
		}

		public static void write(Shortfall shortfall, FriendlyByteBuf packet) {
			packet.writeBoolean(shortfall != null);
			if (shortfall != null) {
				packet.writeUtf(shortfall.lineNames);
				packet.writeInt(shortfall.needed);
				packet.writeInt(shortfall.available);
				packet.writeInt(shortfall.uncoveredPerDay);
				packet.writeUtf(shortfall.suggestedSiding);
			}
		}

		public static Shortfall read(FriendlyByteBuf packet) {
			return packet.readBoolean() ? new Shortfall(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readInt(), packet.readInt(), packet.readInt(), packet.readUtf(PACKET_STRING_READ_LENGTH)) : null;
		}
	}

	private class MainPath {

		/**
		 * Works out the duties of these sidings from the timetables of their routes when the depot runs by a timetable
		 * and has none set, and gives them to the depot on the server thread (decoupling document, step D). The routes'
		 * fastest times are the routes' own ({@link RoutePaths.RoutePath#getFastestTimes}), for the slowest vehicle
		 * running each, as the editor shows them; a route with a path drawn by hand is timed along this depot's path.
		 *
		 * @return the duties, or null if the depot doesn't run by a timetable
		 */
		private DepotDuties generateAutoDuties(MinecraftServer minecraftServer, LineDuties.Plan linePlan) {
			final Set<Long> sidingIds = new HashSet<>();
			sidings.forEach(siding -> sidingIds.add(siding.id));
			final boolean byTimetable = findTimetabledRoute(mainRoutes) != null && !transportMode.continuousMovement && !sidings.isEmpty();
			if (!byTimetable) {
				minecraftServer.execute(() -> {
					autoDuties.sidings.keySet().removeAll(sidingIds);
					autoDuties.diagramMode = !autoDuties.sidings.isEmpty();
					// The clients drop them too, or the editor goes on showing duties of routes no longer run
					sendAutoDuties(minecraftServer);
				});
				return null;
			}
			if (linePlan != null) {
				// Planned with the depot's whole planning unit: these sidings' slots of it
				final DepotDuties planned = new DepotDuties();
				planned.diagramMode = true;
				sidingIds.forEach(sidingId -> {
					final List<DepotDuties.Slot> slots = linePlan.sidings.get(sidingId);
					if (slots != null && !slots.isEmpty()) {
						planned.sidings.put(sidingId, slots);
					}
				});
				minecraftServer.execute(() -> {
					autoDuties.sidings.keySet().removeAll(sidingIds);
					autoDuties.sidings.putAll(planned.sidings);
					autoDuties.diagramMode = true;
					lineShortfall = new Shortfall(linePlan);
					sendAutoDuties(minecraftServer);
				});
				return planned;
			}
			final Set<String> autoTaken = new HashSet<>();
			final Map<Long, long[][]> fastestTimes = new HashMap<>();
			for (int i = 0; i < mainRoutes.size(); i++) {
				final List<PathData> routePath = pathsInRoute.get(i);
				// The route's own times, the same as the editor's, for the slowest vehicle running it
				final long[][] routeTimes = usesRoutePaths() ? routeSearch.getFastestTimes(mainRoutes.get(i), dataCache) : null;
				if (routeTimes != null) {
					if (routeTimes.length > 0) {
						fastestTimes.put(mainRoutes.get(i).id, routeTimes);
					}
				} else if (routePath.size() > 1) {
					fastestTimes.put(mainRoutes.get(i).id, sidings.get(0).calculateStopTimes(routePath).getFastestTimesFromFirstStop(mainRoutes.get(i)));
				}
			}
			final List<DutyGenerator.SidingInput> sidingInputs = new ArrayList<>();
			sidings.stream().sorted(Comparator.comparing((Siding siding) -> siding.name).thenComparingLong(siding -> siding.id)).forEach(siding -> sidingInputs.add(new DutyGenerator.SidingInput(siding.id, siding.getFleetSize(), null, id)));
			final Map<Long, Long> stationOf = new HashMap<>();
			dataCache.platformIdToStation.forEach((platformId, station) -> stationOf.put(platformId, station.id));
			final Set<Long> mainRouteIds = new HashSet<>();
			mainRoutes.forEach(route -> mainRouteIds.add(route.id));
			final long leftToOthers = autoTaken.stream().filter(service -> mainRouteIds.contains(Long.parseLong(service.substring(0, service.indexOf('@'))))).count();
			final AutoDuties generated = AutoDuties.generate(mainRoutes, route -> fastestTimes.get(route.getRouteId()), TimetableConfig.getDayStartMillis(), sidingInputs, stationOf, cycleRoutes, autoTaken);
			System.out.println("Minecraft Transit Railway: worked out the duties of depot " + (name.isEmpty() ? String.valueOf(id) : name) + " from its timetables: " + generated.trains + " trains, " + generated.uncovered / 7 + " services a day with no train" + (leftToOthers == 0 ? "" : ", " + leftToOthers + " services left to other sidings or depots running its routes") + (generated.missingTimes.isEmpty() ? "" : ", " + generated.missingTimes.size() + " routes left out as their times aren't known"));
			minecraftServer.execute(() -> {
				autoDuties.sidings.keySet().removeAll(sidingIds);
				autoDuties.sidings.putAll(generated.duties.sidings);
				autoDuties.diagramMode = true;
				sendAutoDuties(minecraftServer);
			});
			return generated.duties;
		}

		private PathFailure failure = PathFailure.NONE;

		private final List<Siding> sidings;
		/**
		 * The sidings of the other depots of the depot's planning unit, or none.
		 */
		private List<Siding> lineSidings = Collections.emptyList();
		private final boolean repeatPath;
		/**
		 * The path turns back from the last route to the first stop of the first one, so trains go round without the depot.
		 */
		private final boolean closesPath;
		/**
		 * The depot runs by duties ({@link #runsByDutiesWith}): each route is only checked, and the trains run on their
		 * duties' paths, so the order of the routes doesn't matter and they needn't join one to the next.
		 */
		private final boolean dutiesOnly;
		/**
		 * The loop that trains join part of the way round, with the stop where they join it.
		 */
		private final Route loopRoute;
		private final int loopEntryStop;
		private final List<Route> mainRoutes;
		private final DataCache dataCache;
		/**
		 * The routes' own paths, for a depot running by a timetable, or null to search each route here as before.
		 */
		private RoutePaths.Search routeSearch;
		private final List<SavedRailBase> platformsMerged = new ArrayList<>();
		private final List<List<SavedRailBase>> platformsInRoute = new ArrayList<>();
		private final List<List<PathData>> pathsInRoute = new ArrayList<>();
		private final List<List<List<BlockPos>>> waypointsInRoute = new ArrayList<>();
		private final List<List<Route.RoutePlatform>> stopSettingsInRoute = new ArrayList<>();

		private MainPath(List<Route> routes, List<Siding> sidings, DataCache dataCache) {
			this.sidings = sidings;
			mainRoutes = routes;
			this.dataCache = dataCache;

			routes.forEach(route -> {
				final List<PathData> routePath = new ArrayList<>();
				route.getPathData().forEach(pathData -> routePath.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex)));
				final List<SavedRailBase> routePlatforms = new ArrayList<>();
				final List<List<BlockPos>> routeWaypoints = new ArrayList<>();
				final List<Route.RoutePlatform> routeStopSettings = new ArrayList<>();

				if (routePath.isEmpty()) {
					route.platformIds.forEach(platformId -> {
						final Platform platform = dataCache.platformIdMap.get(platformId.platformId);
						if (platform != null) {
							routePlatforms.add(platform);
							routeWaypoints.add(platformId.waypoints);
							routeStopSettings.add(platformId);
							if (platformsMerged.isEmpty() || platform.id != platformsMerged.get(platformsMerged.size() - 1).id) {
								platformsMerged.add(platform);
							}
						}
					});
				} else {
					for (int i = 0; i < route.platformIds.size(); i++) {
						final Platform platform = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
						if (platform != null) {
							routePlatforms.add(platform);
							if (i != 0 || platformsMerged.isEmpty() || platform.id != platformsMerged.get(platformsMerged.size() - 1).id) {
								platformsMerged.add(platform);
							}
						}
					}
				}

				platformsInRoute.add(routePlatforms);
				pathsInRoute.add(routePath);
				waypointsInRoute.add(routeWaypoints);
				stopSettingsInRoute.add(routeStopSettings);
			});

			// A timetabled line that ends where it starts runs lap after lap, and only returns to the depot when service ends
			final boolean hasTimetable = findTimetabledRoute(routes) != null;
			dutiesOnly = runsByDutiesWith(routes);
			// Trains running by duties go round on their duties' paths, so the routes aren't joined up into a lap
			closesPath = !dutiesOnly && cycleRoutes && platformsMerged.size() > 1 && platformsMerged.get(0).id != platformsMerged.get(platformsMerged.size() - 1).id;
			if (closesPath) {
				platformsMerged.add(platformsMerged.get(0));
			}
			repeatPath = repeatInfinitely || closesPath || hasTimetable && platformsMerged.size() > 1 && platformsMerged.get(0).id == platformsMerged.get(platformsMerged.size() - 1).id;
			final boolean canJoinLoop = routes.size() == 1 && repeatPath && hasTimetable && routes.get(0).getPathData().isEmpty();
			loopRoute = canJoinLoop ? routes.get(0) : null;
			loopEntryStop = canJoinLoop ? routes.get(0).getLoopEntryStop() : 0;
		}

		/**
		 * Finds where services of the last route carry on onto other routes. Each way through only needs one path, however
		 * many services use it. A route that can't be reached is left out, and its services stay on the main path.
		 */
		private List<TripTail<PathData, SavedRailBase>> getTripTails(List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<TripTail<PathData, SavedRailBase>> tripTails = new ArrayList<>();
			final Route lastRoute = mainRoutes.isEmpty() ? null : mainRoutes.get(mainRoutes.size() - 1);
			if (lastRoute == null || mainPath.isEmpty() || getActiveTimetable(lastRoute) == null) {
				return tripTails;
			}

			final Set<String> keys = new HashSet<>();
			lastRoute.timetable.getTables().forEach(table -> table.links.values().forEach(link -> {
				final List<RouteTimetable.ServiceLink> links = RouteTimetable.getLinkChain(link, routeId -> {
					final Route route = dataCache.routeIdMap.get(routeId);
					return route == null ? null : route.timetable;
				});
				if (keys.add(TripTail.getKey(links))) {
					final TripTail<PathData, SavedRailBase> tripTail = getTripTail(lastRoute, links, mainPath, rails, graph, useFastSpeed);
					if (tripTail != null) {
						tripTails.add(tripTail);
					}
				}
			}));
			return tripTails;
		}

		private TripTail<PathData, SavedRailBase> getTripTail(Route lastRoute, List<RouteTimetable.ServiceLink> links, List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final int leaveRail = isCut(lastRoute, links.get(0)) ? Siding.getStopRail(mainPath, lastRoute.platformIds, links.get(0).fromStop) : mainPath.size() - 1;
			if (leaveRail < 0) {
				return null;
			}

			final List<Route> tripRoutes = new ArrayList<>(mainRoutes);
			final Map<Long, Integer> cutStops = new HashMap<>();
			final Map<Long, Integer> entryStops = new HashMap<>();
			final Map<Long, List<PathData>> entryLeads = new HashMap<>();
			final List<PathData> tailPath = new ArrayList<>();
			Route previousRoute = lastRoute;
			SavedRailBase lastPlatform = null;
			int lapStart = TripTail.NO_RAIL;
			int entryRail = TripTail.NO_RAIL;
			boolean isLapReversed = false;

			for (int i = 0; i < links.size(); i++) {
				final RouteTimetable.ServiceLink link = links.get(i);
				final RouteTimetable.ServiceLink nextLink = i + 1 < links.size() ? links.get(i + 1) : null;
				final Route nextRoute = dataCache.routeIdMap.get(link.routeId);
				final boolean isCut = isCut(previousRoute, link);
				final int leaveStop = isCut ? link.fromStop : previousRoute.platformIds.size() - 1;
				final Platform leavePlatform = previousRoute.platformIds.isEmpty() ? null : dataCache.platformIdMap.get(previousRoute.platformIds.get(leaveStop).platformId);
				if (nextRoute == null || nextRoute.platformIds.isEmpty() || nextRoute.transportMode != transportMode || leavePlatform == null) {
					return null;
				}
				if (isCut) {
					cutStops.put(previousRoute.id, leaveStop);
				}

				final List<SavedRailBase> nextPlatforms = new ArrayList<>();
				final List<List<BlockPos>> nextWaypoints = new ArrayList<>();
				final List<Route.RoutePlatform> nextStopSettings = new ArrayList<>();
				nextRoute.platformIds.forEach(routePlatform -> {
					final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
					if (platform != null) {
						nextPlatforms.add(platform);
						nextWaypoints.add(routePlatform.waypoints);
						nextStopSettings.add(routePlatform);
					}
				});
				if (nextPlatforms.size() < 2) {
					return null;
				}

				// A train on a loop at the end of its trip stays on it, and can join it at any stop. Other routes can be joined
				// part of the way along at a timetable boundary, which the links were checked against when they were saved.
				final boolean isLap = nextLink == null && nextRoute.isLoop() && getActiveTimetable(nextRoute) != null;
				final int entryStop = nextPlatforms.size() == nextRoute.platformIds.size() && link.toStop > 0 && link.toStop < nextPlatforms.size() - 1 ? link.toStop : 0;
				final SavedRailBase entryPlatform = nextPlatforms.get(entryStop);

				if (entryPlatform.id != leavePlatform.id) {
					final List<PathData> connectingPath = new ArrayList<>();
					// A through service turns back on the first turn back rail the route lists, without choosing between them
					final List<BlockPos> turnBackNodes = isCut ? Collections.emptyList() : getTurnBackNodes(previousRoute, graph);
					if (turnBackNodes.isEmpty()) {
						PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(leavePlatform, entryPlatform), null, 1, cruisingAltitude, useFastSpeed);
					} else {
						final TurnBackSection<PathData> turnBackSection = findTurnBackSection(leavePlatform, entryPlatform, turnBackNodes, 0, rails, graph, useFastSpeed);
						if (turnBackSection != null) {
							connectingPath.addAll(turnBackSection.getDefaultWay());
						}
					}
					if (connectingPath.isEmpty()) {
						return null;
					}
					PathFinder.appendPath(tailPath, connectingPath);
				}
				final List<PathData> routePath = new ArrayList<>();
				// The route's own path where the depot takes its routes' paths from the routes
				final RoutePaths.RoutePath ownPath = usesRoutePaths() && nextPlatforms.size() == nextRoute.platformIds.size() ? routeSearch.get(nextRoute, dataCache) : null;
				if (ownPath != null) {
					if (ownPath.isFound()) {
						routePath.addAll(ownPath.getPath(nextStopSettings));
					}
				} else {
					PathFinder.findPath(routePath, rails, graph, nextPlatforms, nextWaypoints, 1, cruisingAltitude, useFastSpeed);
					PathFinder.applyStopSettings(routePath, nextStopSettings);
				}
				if (routePath.size() <= 2) {
					return null;
				}

				// Stops are counted through every route before this one in full, so this route starts after all of them
				tripRoutes.add(nextRoute);
				int stopIndex = getStopCount(tripRoutes) + 1;
				for (int j = 0; j < routePath.size(); j++) {
					final PathData pathData = routePath.get(j);
					if (j > 0 && pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && routePath.get(j - 1).rail.railType != RailType.PLATFORM) {
						stopIndex++;
					}
					pathData.stopIndex = stopIndex;
				}

				final int joinRail = entryStop > 0 ? Siding.getStopRail(routePath, nextRoute.platformIds, entryStop) : 0;
				if (joinRail < 0) {
					return null;
				}
				if (entryStop > 0 && isLap) {
					final List<PathData> joinPath = new ArrayList<>();
					for (int j = joinRail; j < routePath.size(); j++) {
						final PathData pathData = routePath.get(j);
						joinPath.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex));
					}
					entryRail = tailPath.size() - 1;
					addToTail(tailPath, mainPath.get(leaveRail), joinPath);
				} else if (entryStop > 0) {
					// The train runs from the stop where it joins, and plans by the way from the first stop to there
					entryStops.put(nextRoute.id, entryStop);
					entryLeads.put(nextRoute.id, new ArrayList<>(routePath.subList(0, joinRail + 1)));
				}

				final List<PathData> usedPath;
				final int usedStart = isLap ? 0 : joinRail;
				if (nextLink != null && isCut(nextRoute, nextLink)) {
					final int cutRail = Siding.getStopRail(routePath, nextRoute.platformIds, nextLink.fromStop);
					if (cutRail <= usedStart) {
						return null;
					}
					usedPath = new ArrayList<>(routePath.subList(usedStart, cutRail + 1));
				} else {
					usedPath = new ArrayList<>(routePath.subList(usedStart, routePath.size()));
				}

				if (isLap) {
					final PathData lastRail = tailPath.isEmpty() ? mainPath.get(leaveRail) : tailPath.get(tailPath.size() - 1);
					lapStart = tailPath.size() - 1;
					isLapReversed = usedPath.get(0).isOppositeRail(lastRail);
				}
				addToTail(tailPath, mainPath.get(leaveRail), usedPath);

				previousRoute = nextRoute;
				lastPlatform = nextPlatforms.get(nextPlatforms.size() - 1);
			}

			final List<Long> tripRouteIds = new ArrayList<>();
			tripRoutes.forEach(route -> tripRouteIds.add(route.id));
			return new TripTail<>(TripTail.getKey(links), tripRouteIds, cutStops, leaveRail, tailPath, lastPlatform, lapStart, isLapReversed, entryRail, entryStops, entryLeads);
		}

		/**
		 * Each shape of duty a siding's slots run, once (design document 6.12, block E2).
		 */
		/**
		 * @param dutiesToPlan the depot's duties, or a draft of them
		 * @param failed       told about the key of each duty shape whose plan can't be found
		 */
		private List<DutyPlan<PathData, SavedRailBase>> getDutyPlans(DepotDuties dutiesToPlan, Siding siding, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, Consumer<String> failed) {
			final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = new ArrayList<>();
			final Set<String> keys = new HashSet<>();
			// The duties of every siding of its pool, as its trains may run them in place of the other sidings' trains
			for (final Siding poolSiding : getPoolSidings(siding)) {
				final List<DepotDuties.Slot> slots = dutiesToPlan.getSlots(poolSiding.id);
				for (int i = 0; i < slots.size(); i++) {
					for (int weekday = 0; weekday < 7; weekday++) {
						final String key = DutyShapes.getSlotKey(slots, i, weekday, dataCache.routeIdMap::get);
						if (key == null || !keys.add(key)) {
							continue;
						}
						final DepotDuties.DayDuty day = slots.get(i).getDuty(weekday);
						final DutyPlan<PathData, SavedRailBase> dutyPlan = getDutyPlan(key, day.segments, weekday, DutyShapes.getOvernightSlot(slots, i, weekday) >= 0, day.stayOnLine, rails, graph, useFastSpeed);
						if (dutyPlan == null) {
							failure = new PathFailure(PathFailure.Type.DUTY, 0);
							failed.accept(key);
						} else {
							dutyPlans.add(dutyPlan);
						}
					}
				}
			}
			return dutyPlans;
		}

		/**
		 * @return the sidings of the group with the same vehicle as this one, itself first, then those of the other depots of
		 * its planning unit with the same vehicle, which its duties may go back to
		 */
		private List<Siding> getPoolSidings(Siding siding) {
			final List<Siding> poolSidings = new ArrayList<>();
			poolSidings.add(siding);
			sidings.stream().filter(other -> other != siding && other.getPoolKey().equals(siding.getPoolKey())).sorted(Comparator.comparingLong(other -> other.id)).forEach(poolSidings::add);
			lineSidings.stream().filter(other -> other.getPoolKey().equals(siding.getPoolKey())).sorted(Comparator.comparingLong(other -> other.id)).forEach(poolSidings::add);
			return poolSidings;
		}

		/**
		 * Joins up a duty's segments: each service from where it joins its route to where it leaves, with the stops it passes
		 * passed, which is how a single service's passing or stopping becomes real, then on to the next segment the way the
		 * depot's own path would turn back.
		 *
		 * @return the plan, or null if a part of it can't be found
		 */
		private DutyPlan<PathData, SavedRailBase> getDutyPlan(String key, List<DepotDuties.Segment> segments, int weekday, boolean startsOnLine, boolean staysOnLine, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<PathData> body = new ArrayList<>();
			final int[] segmentStarts = new int[segments.size()];
			final List<Long> routeIds = new ArrayList<>();
			final Map<Long, Integer> cutStops = new HashMap<>();
			final Map<Long, Integer> entryStops = new HashMap<>();
			final Map<Long, List<PathData>> entryLeads = new HashMap<>();
			SavedRailBase firstPlatform = null;
			SavedRailBase lastPlatform = null;
			Route previousRoute = null;
			boolean previousRanToEnd = false;
			final List<TurnBackSection<PathData>> turnBackSections = new ArrayList<>();
			List<SavedRailBase> previousPlatforms = Collections.emptyList();
			List<List<BlockPos>> previousWaypoints = Collections.emptyList();
			int previousStart = 0;

			for (int k = 0; k < segments.size(); k++) {
				final DepotDuties.Segment segment = segments.get(k);
				final Route route = dataCache.routeIdMap.get(segment.routeId);
				if (route == null || route.transportMode != transportMode || route.platformIds.size() < 2) {
					return null;
				}
				final int firstStop = DutyShapes.getFirstStop(route, segment);
				final int lastStop = DutyShapes.getLastStop(route, segment);
				final boolean[] pass = DutyShapes.getPassFlags(route, segment, weekday);
				final List<SavedRailBase> platforms = new ArrayList<>();
				final List<List<BlockPos>> waypoints = new ArrayList<>();
				final List<Route.RoutePlatform> stopSettings = new ArrayList<>();
				for (int i = firstStop; i <= lastStop; i++) {
					final Route.RoutePlatform routePlatform = route.platformIds.get(i);
					final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);
					if (platform == null) {
						return null;
					}
					platforms.add(platform);
					waypoints.add(routePlatform.waypoints);
					final Route.RoutePlatform stopSetting = new Route.RoutePlatform(routePlatform.platformId);
					stopSetting.pass = pass[i - firstStop];
					stopSetting.dwellOverride = routePlatform.dwellOverride;
					stopSettings.add(stopSetting);
				}
				final List<PathData> segmentPath = new ArrayList<>();
				// Cut from the route's own path rather than searched again
				final RoutePaths.RoutePath ownPath = usesRoutePaths() ? routeSearch.get(route, dataCache) : null;
				if (ownPath != null) {
					segmentPath.addAll(ownPath.getSlice(firstStop, lastStop));
				} else {
					PathFinder.findPath(segmentPath, rails, graph, platforms, waypoints, 1, cruisingAltitude, useFastSpeed);
				}
				if (segmentPath.size() < 2) {
					return null;
				}
				PathFinder.applyStopSettings(segmentPath, stopSettings);

				if (lastPlatform != null && lastPlatform.id != platforms.get(0).id) {
					final List<PathData> connectingPath = new ArrayList<>();
					// After running a route to its end the train turns back the way the route lists, like the depot's own path
					final List<BlockPos> turnBackNodes = previousRanToEnd ? getTurnBackNodes(previousRoute, graph) : Collections.emptyList();
					TurnBackSection<PathData> turnBackSection = null;
					if (turnBackNodes.isEmpty()) {
						PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(lastPlatform, platforms.get(0)), null, 1, cruisingAltitude, useFastSpeed);
					} else {
						turnBackSection = findTurnBackSection(lastPlatform, platforms.get(0), turnBackNodes, 0, rails, graph, useFastSpeed);
						if (turnBackSection != null) {
							connectingPath.addAll(turnBackSection.getDefaultWay());
						}
					}
					if (connectingPath.isEmpty()) {
						return null;
					}
					// More than one way round, each from the last stop, which is already the last rail of the body
					if (turnBackSection != null && turnBackSection.ways.size() > 1 && !body.isEmpty() && body.get(body.size() - 1).isSameRail(connectingPath.get(0))) {
						turnBackSections.add(new TurnBackSection<>(body.size() - 1, turnBackSection.ways, turnBackSection.defaultWay));
					}
					PathFinder.appendPath(body, connectingPath);
				}

				final PathData first = segmentPath.get(0);
				final PathData last = body.isEmpty() ? null : body.get(body.size() - 1);
				if (last != null && first.isSameRail(last)) {
					segmentStarts[k] = body.size() - 1;
					body.set(body.size() - 1, first);
					body.addAll(segmentPath.subList(1, segmentPath.size()));
				} else if (last != null && first.isOppositeRail(last)) {
					// Turning back on the platform: the stop is the arrival there, and the platform reversed is only the way out
					first.dwellTime = 0;
					first.savedRailBaseId = 0;
					segmentStarts[k] = body.size() - 1;
					body.addAll(segmentPath);
				} else {
					segmentStarts[k] = body.size();
					body.addAll(segmentPath);
				}

				// The route before may turn back at other platforms of its last station (站前折返)
				if (k > 0 && previousRanToEnd && firstStop == 0) {
					addFrontTurnBack(body, previousRoute, previousPlatforms, previousWaypoints, previousStart, route, platforms, waypoints, segmentStarts[k], turnBackSections, rails, graph, useFastSpeed);
				}

				if (firstPlatform == null) {
					firstPlatform = platforms.get(0);
				}
				lastPlatform = platforms.get(platforms.size() - 1);
				routeIds.add(route.id);
				if (lastStop < route.platformIds.size() - 1) {
					cutStops.putIfAbsent(route.id, lastStop);
				}
				if (firstStop > 0 && !entryStops.containsKey(route.id)) {
					// The plan of a route joined part of the way along counts from its first stop, so the way there is timed too
					final List<SavedRailBase> leadPlatforms = new ArrayList<>();
					final List<List<BlockPos>> leadWaypoints = new ArrayList<>();
					final List<Route.RoutePlatform> leadSettings = new ArrayList<>();
					for (int i = 0; i <= firstStop; i++) {
						final Platform platform = dataCache.platformIdMap.get(route.platformIds.get(i).platformId);
						if (platform != null) {
							leadPlatforms.add(platform);
							leadWaypoints.add(route.platformIds.get(i).waypoints);
							leadSettings.add(route.platformIds.get(i));
						}
					}
					final List<PathData> leadPath = new ArrayList<>();
					if (ownPath != null) {
						leadPath.addAll(ownPath.getSlice(0, firstStop));
					} else {
						PathFinder.findPath(leadPath, rails, graph, leadPlatforms, leadWaypoints, 1, cruisingAltitude, useFastSpeed);
					}
					if (leadPath.size() >= 2 && leadPlatforms.size() == firstStop + 1) {
						PathFinder.applyStopSettings(leadPath, leadSettings);
						entryStops.put(route.id, firstStop);
						entryLeads.put(route.id, leadPath);
					}
				}
				previousRoute = route;
				previousRanToEnd = lastStop == route.platformIds.size() - 1;
				previousPlatforms = platforms;
				previousWaypoints = waypoints;
				previousStart = segmentStarts[k];
			}
			final DutyPlan<PathData, SavedRailBase> dutyPlan = new DutyPlan<>(key, routeIds, cutStops, entryStops, entryLeads, body, segmentStarts, firstPlatform, lastPlatform, startsOnLine, staysOnLine);
			dutyPlan.turnBackSections.addAll(turnBackSections);
			return dutyPlan;
		}

		/**
		 * Where a route ends at a station whose other platforms it lists to turn back at (站前折返), and the next segment's
		 * route starts in that station, adds the ways from the stop before the last to the next route's second stop, one at
		 * each of those platforms, in the order the next route would rather start from (种别优先). The usual way is one of
		 * them when the train usually turns back at its last platform, and the last when it turns back after the station
		 * (站后), so the platforms come first and the way after the station is taken only when none of them does.
		 *
		 * @param previousStart the rail of the body where the route before starts
		 * @param nextStart     the rail of the body where the next route starts
		 */
		private void addFrontTurnBack(List<PathData> body, Route previousRoute, List<SavedRailBase> previousPlatforms, List<List<BlockPos>> previousWaypoints, int previousStart, Route route, List<SavedRailBase> platforms, List<List<BlockPos>> waypoints, int nextStart, List<TurnBackSection<PathData>> turnBackSections, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			if (previousRoute.terminalPlatforms.isEmpty() || previousPlatforms.size() < 2 || platforms.size() < 2 || graph == null) {
				return;
			}
			final long lastPlatformId = previousRoute.getLastPlatformId();
			final Station station = dataCache.platformIdToStation.get(lastPlatformId);
			if (station == null || dataCache.platformIdToStation.get(platforms.get(0).id) != station) {
				return;
			}
			final int stopRail = findStopRail(body, previousStart, previousPlatforms.get(previousPlatforms.size() - 2).id);
			final int lastRail = stopRail < 0 ? -1 : findStopRail(body, stopRail + 1, lastPlatformId);
			final int secondRail = lastRail < 0 ? -1 : findStopRail(body, Math.max(nextStart, lastRail) + 1, platforms.get(1).id);
			if (secondRail < 0) {
				return;
			}
			final List<PathData> usualWay = new ArrayList<>(body.subList(stopRail, secondRail + 1));
			final boolean usualAtPlatform = lastPlatformId == platforms.get(0).id;

			final List<Long> candidates = new ArrayList<>();
			if (usualAtPlatform) {
				candidates.add(lastPlatformId);
			}
			previousRoute.terminalPlatforms.forEach(platformId -> {
				if (platformId != lastPlatformId && !candidates.contains(platformId) && dataCache.platformIdToStation.get(platformId) == station && dataCache.platformIdMap.containsKey(platformId)) {
					candidates.add(platformId);
				}
			});
			final List<Long> ordered = new ArrayList<>();
			route.startPlatforms.forEach(platformId -> {
				if (candidates.contains(platformId) && !ordered.contains(platformId)) {
					ordered.add(platformId);
				}
			});
			candidates.forEach(platformId -> {
				if (!ordered.contains(platformId)) {
					ordered.add(platformId);
				}
			});

			final List<List<PathData>> ways = new ArrayList<>();
			int defaultWay = -1;
			for (final long platformId : ordered) {
				if (platformId == lastPlatformId) {
					defaultWay = ways.size();
					ways.add(usualWay);
				} else {
					final List<PathData> way = findFrontWay(previousPlatforms.get(previousPlatforms.size() - 2), dataCache.platformIdMap.get(platformId), platforms.get(1), previousWaypoints.get(previousWaypoints.size() - 2), waypoints.get(0), body.get(lastRail).dwellTime, usualWay, rails, graph, useFastSpeed);
					ways.add(way);
					if (way == null) {
						failure = new PathFailure(PathFailure.Type.TURN_BACK_UNUSED, dataCache.platformIdMap.get(platformId).getMidPos().asLong());
					}
				}
			}
			if (!usualAtPlatform) {
				defaultWay = ways.size();
				ways.add(usualWay);
			}
			if (ways.stream().filter(Objects::nonNull).count() >= 2) {
				turnBackSections.add(new TurnBackSection<>(stopRail, ways, defaultWay));
			}
		}

		/**
		 * @return the way from a stop into a platform and back out of it to the next route's second stop, turning back on the
		 * platform, or null if there is none that leaves and reaches the stops along the same rails as the usual way
		 */
		private List<PathData> findFrontWay(SavedRailBase from, Platform platform, SavedRailBase to, List<BlockPos> fromWaypoints, List<BlockPos> toWaypoints, int dwellTime, List<PathData> usualWay, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			if (platform == null) {
				return null;
			}
			final List<PathData> wayIn = new ArrayList<>();
			PathFinder.findPath(wayIn, rails, graph, Arrays.asList(from, platform), Collections.singletonList(fromWaypoints), 1, cruisingAltitude, useFastSpeed);
			final List<PathData> wayOut = new ArrayList<>();
			PathFinder.findPath(wayOut, rails, graph, Arrays.asList(platform, to), Collections.singletonList(toWaypoints), 1, cruisingAltitude, useFastSpeed);
			if (wayIn.size() < 2 || wayOut.size() < 2) {
				return null;
			}
			// The stop is the arrival at the platform, with the stop time of the route's last stop; the platform reversed is
			// only the way out
			final PathData arrival = wayIn.get(wayIn.size() - 1);
			arrival.dwellTime = dwellTime;
			final PathData leaving = wayOut.get(0);
			if (!leaving.isOppositeRail(arrival)) {
				return null;
			}
			leaving.dwellTime = 0;
			leaving.savedRailBaseId = 0;
			wayIn.addAll(wayOut);
			return wayIn.get(0).isSameRail(usualWay.get(0)) && wayIn.get(wayIn.size() - 1).isSameRail(usualWay.get(usualWay.size() - 1)) ? wayIn : null;
		}

		/**
		 * @return the first rail from this one on that is this platform, or -1
		 */
		private static int findStopRail(List<PathData> body, int from, long platformId) {
			for (int i = Math.max(from, 0); i < body.size(); i++) {
				if (body.get(i).savedRailBaseId == platformId && body.get(i).rail.railType == RailType.PLATFORM) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * @return whether the routes' paths are taken from the routes: for a depot running by a timetable, not moving
		 * continuously and not of airplanes, which search their whole way as before
		 */
		private boolean usesRoutePaths() {
			return routeSearch != null && findTimetabledRoute(mainRoutes) != null && !transportMode.continuousMovement && transportMode != TransportMode.AIRPLANE;
		}

		private boolean isCut(Route route, RouteTimetable.ServiceLink link) {
			return link.fromStop >= 0 && link.fromStop < route.platformIds.size() - 1;
		}

		/**
		 * @return the number of stops before the last route of these routes
		 */
		private int getStopCount(List<Route> routes) {
			int stopCount = 0;
			Route previousRoute = null;
			for (int i = 0; i < routes.size(); i++) {
				final Route route = routes.get(i);
				if (i < routes.size() - 1) {
					stopCount += route.platformIds.size();
				}
				if (previousRoute != null && previousRoute.getLastPlatformId() == route.getFirstPlatformId()) {
					stopCount--;
				}
				previousRoute = route;
			}
			return stopCount;
		}

		private void addToTail(List<PathData> tailPath, PathData lastMainRail, List<PathData> newPath) {
			final PathData first = newPath.get(0);
			final PathData last = tailPath.isEmpty() ? lastMainRail : tailPath.get(tailPath.size() - 1);
			if (first.isOppositeRail(last)) {
				first.dwellTime = 0;
				first.savedRailBaseId = 0;
			} else if (first.isSameRail(last)) {
				newPath.remove(0);
			}
			tailPath.addAll(newPath);
		}

		/**
		 * Adds the way from the last stop of a route to the first stop of the next one, round the turn back rails the route lists
		 * after its last stop if it has any.
		 *
		 * @param routeBefore the route whose last stop this is, or -1 if it has no stops
		 * @return false if none of the turn back rails can be reached, which fails the path
		 */
		/**
		 * For a depot running by duties: checks each route's own path, reporting the first that can't be found, then gives
		 * each siding its duties' paths and the siding alone as its path.
		 *
		 * @return {@link #DUTIES_ONLY_FOUND} if every route was found, otherwise 0
		 */
		private int generateDutiesOnly(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, LineDuties.Plan linePlan) {
			for (int i = 0; i < pathsInRoute.size(); i++) {
				final List<SavedRailBase> platforms = platformsInRoute.get(i);
				if (platforms.size() < 2 || !pathsInRoute.get(i).isEmpty()) {
					continue;
				}
				final RoutePaths.RoutePath ownPath = usesRoutePaths() ? routeSearch.get(mainRoutes.get(i), dataCache) : null;
				if (ownPath != null) {
					if (!ownPath.isFound() && failure == PathFailure.NONE) {
						failure = ownPath.failure == PathFailure.NONE ? new PathFailure(PathFailure.Type.NO_ROUTE, 0) : ownPath.failure;
					}
				} else {
					final List<PathData> routePath = new ArrayList<>();
					final int reached = PathFinder.findPath(routePath, rails, graph, platforms, waypointsInRoute.get(i), 1, cruisingAltitude, useFastSpeed);
					if (routePath.isEmpty() && failure == PathFailure.NONE) {
						failure = PathFailure.diagnose(graph, platforms, waypointsInRoute.get(i), reached);
					}
				}
			}

			final DepotDuties runDuties = duties.diagramMode ? duties : generateAutoDuties(minecraftServer, linePlan);
			for (final Siding siding : sidings) {
				final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = runDuties != null ? getDutyPlans(runDuties, siding, rails, graph, useFastSpeed, key -> {
				}) : new ArrayList<>();
				siding.generateDutyRoute(minecraftServer, rails, graph, cruisingAltitude, useFastSpeed, dutyPlans, getPoolSidings(siding));
			}
			return failure == PathFailure.NONE ? DUTIES_ONLY_FOUND : 0;
		}

		private boolean addConnection(List<PathData> tempPath, SavedRailBase from, SavedRailBase to, int routeBefore, List<TurnBackSection<PathData>> turnBackSections, List<DepotReturn<PathData, SavedRailBase>> depotReturns, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final int start = tempPath.size();
			final List<BlockPos> turnBackNodes = routeBefore >= 0 ? getTurnBackNodes(mainRoutes.get(routeBefore), graph) : Collections.emptyList();
			final List<PathData> connectingPath = new ArrayList<>();
			if (turnBackNodes.isEmpty()) {
				PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(from, to), null, 1, cruisingAltitude, useFastSpeed);
			} else {
				final TurnBackSection<PathData> turnBackSection = findTurnBackSection(from, to, turnBackNodes, start, rails, graph, useFastSpeed);
				if (turnBackSection == null) {
					failure = new PathFailure(PathFailure.Type.TURN_BACK, turnBackNodes.get(0).asLong());
					tempPath.clear();
					return false;
				}
				connectingPath.addAll(turnBackSection.getDefaultWay());
				turnBackSections.add(turnBackSection);
			}
			tempPath.addAll(connectingPath);
			// Only trains staying on the line need a way back to the depot after a route
			if (cycleRoutes && routeBefore >= 0 && !connectingPath.isEmpty()) {
				depotReturns.add(new DepotReturn<>(routeBefore, start, connectingPath, from));
			}
			return true;
		}

		/**
		 * Nodes a route lists after its last stop are the ends of the turn back rails it can use there, in the order trains try them.
		 */
		private List<BlockPos> getTurnBackNodes(Route route, RailGraph<Rail> graph) {
			// The old search can't follow nodes
			return graph == null || route.platformIds.isEmpty() ? Collections.emptyList() : route.platformIds.get(route.platformIds.size() - 1).waypoints;
		}

		/**
		 * Finds a way from one stop to the next round each turn back node. Every way round has to leave the first stop and reach
		 * the next one along the same rails as the first way that can be used, so a train can take any of them from the stop.
		 *
		 * @param start where the ways round start on the main path
		 * @return the ways round, or null if none of them can be used
		 */
		private TurnBackSection<PathData> findTurnBackSection(SavedRailBase from, SavedRailBase to, List<BlockPos> nodes, int start, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<List<PathData>> ways = new ArrayList<>();
			int defaultWay = -1;
			for (final BlockPos node : nodes) {
				final List<PathData> way = new ArrayList<>();
				PathFinder.findPath(way, rails, graph, Arrays.asList(from, to), Collections.singletonList(Collections.singletonList(node)), 1, cruisingAltitude, useFastSpeed);
				final List<PathData> firstWay = defaultWay < 0 ? null : ways.get(defaultWay);
				final boolean canUse = way.size() >= 2 && (firstWay == null || way.get(0).isSameRail(firstWay.get(0)) && way.get(way.size() - 1).isSameRail(firstWay.get(firstWay.size() - 1)));
				ways.add(canUse ? way : null);
				if (!canUse) {
					failure = new PathFailure(PathFailure.Type.TURN_BACK_UNUSED, node.asLong());
				} else if (defaultWay < 0) {
					defaultWay = ways.size() - 1;
				}
			}
			return defaultWay < 0 ? null : new TurnBackSection<>(start, ways, defaultWay);
		}

		/**
		 * @return the part of the loop from the stop where trains join it to the end of the path, or empty if they join at the first stop
		 */
		private List<PathData> getLoopJoinPath(List<PathData> mainPath) {
			final List<PathData> loopJoinPath = new ArrayList<>();
			final int joinRail = loopEntryStop > 0 ? Siding.getStopRail(mainPath, loopRoute.platformIds, loopEntryStop) : -1;
			if (joinRail >= 0) {
				for (int i = joinRail; i < mainPath.size(); i++) {
					final PathData pathData = mainPath.get(i);
					loopJoinPath.add(new PathData(pathData.rail, pathData.savedRailBaseId, pathData.dwellTime, pathData.startingPos, pathData.getLastPos(), pathData.stopIndex));
				}
			}
			return loopJoinPath;
		}

		/**
		 * @param linePlan the duties of the depot's planning unit, or null if there are none
		 */
		private int generate(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed, LineDuties.Plan linePlan) {
			if (dutiesOnly) {
				return generateDutiesOnly(minecraftServer, rails, graph, useFastSpeed, linePlan);
			}
			final List<PathData> tempPath = new ArrayList<>();
			final List<TurnBackSection<PathData>> turnBackSections = new ArrayList<>();
			final List<DepotReturn<PathData, SavedRailBase>> depotReturns = new ArrayList<>();
			SavedRailBase lastPlatform = null;

			for (int i = 0; i < pathsInRoute.size(); i++) {
				final List<SavedRailBase> platforms = platformsInRoute.get(i);
				if (lastPlatform != null && !platforms.isEmpty() && platforms.get(0) != lastPlatform) {
					final int routeBefore = i > 0 && !platformsInRoute.get(i - 1).isEmpty() ? i - 1 : -1;
					if (!addConnection(tempPath, lastPlatform, platforms.get(0), routeBefore, turnBackSections, depotReturns, rails, graph, useFastSpeed)) {
						break;
					}
				}
				if (!platforms.isEmpty()) {
					lastPlatform = platforms.get(platforms.size() - 1);
				}

				final List<PathData> routePath = pathsInRoute.get(i);
				final RoutePaths.RoutePath ownPath = routePath.isEmpty() && usesRoutePaths() ? routeSearch.get(mainRoutes.get(i), dataCache) : null;
				if (ownPath != null) {
					// The route's own path, with the route's stops
					if (!ownPath.isFound() && platforms.size() >= 2) {
						failure = ownPath.failure == PathFailure.NONE ? new PathFailure(PathFailure.Type.NO_ROUTE, 0) : ownPath.failure;
						tempPath.clear();
						break;
					}
					if (ownPath.isFound()) {
						routePath.addAll(ownPath.getPath(stopSettingsInRoute.get(i)));
					}
				} else if (routePath.isEmpty()) {
					final int reached = PathFinder.findPath(routePath, rails, graph, platforms, waypointsInRoute.get(i), 1, cruisingAltitude, useFastSpeed);
					if (routePath.isEmpty() && platforms.size() >= 2) {
						failure = PathFailure.diagnose(graph, platforms, waypointsInRoute.get(i), reached);
						tempPath.clear();
						break;
					}
					PathFinder.applyStopSettings(routePath, stopSettingsInRoute.get(i));
				}

				if (routePath.size() > 2) {
					final PathData first = routePath.get(0);
					final PathData last = tempPath.isEmpty() ? null : tempPath.get(tempPath.size() - 1);
					if (last != null) {
						if (first.isOppositeRail(last)) {
							first.dwellTime = 0;
							first.savedRailBaseId = 0;
						} else if (first.isSameRail(last)) {
							routePath.remove(0);
						}
					}
					tempPath.addAll(routePath);
				}
			}

			// Trains staying on the line turn back from the last route onto the first one, and a lap ends at its first stop
			if (closesPath && !tempPath.isEmpty() && lastPlatform != null) {
				final int lengthBefore = tempPath.size();
				if (!addConnection(tempPath, lastPlatform, platformsMerged.get(0), pathsInRoute.size() - 1, turnBackSections, depotReturns, rails, graph, useFastSpeed)) {
					tempPath.clear();
				} else if (tempPath.size() == lengthBefore) {
					// No way back round: said as such, rather than only that the path wasn't found
					failure = new PathFailure(PathFailure.Type.CYCLE_BACK, lastPlatform.id, platformsMerged.get(0).id, 0);
					tempPath.clear();
				}
			}

			int stopIndex = 1;
			for (int i = 1; i < tempPath.size(); i++) {
				final PathData pathData = tempPath.get(i);
				// Passed platforms have no stop time but still count, as route indices include them
				if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && tempPath.get(i - 1).rail.railType != RailType.PLATFORM) {
					stopIndex++;
				}
				pathData.stopIndex = stopIndex;
			}
			turnBackSections.forEach(turnBackSection -> turnBackSection.numberStops(tempPath));
			if (closesPath && !tempPath.isEmpty()) {
				// The end of a lap is the first stop again, which is where the stations are counted from
				final int firstStopIndex = tempPath.get(0).stopIndex;
				tempPath.get(tempPath.size() - 1).stopIndex = firstStopIndex;
				if (!turnBackSections.isEmpty()) {
					turnBackSections.get(turnBackSections.size() - 1).setEndStopIndex(tempPath.size(), firstStopIndex);
				}
			}
			depotReturns.forEach(depotReturn -> depotReturn.findStop(tempPath));

			final List<PathData> loopJoinPath = getLoopJoinPath(tempPath);
			final SavedRailBase entryPlatform = loopJoinPath.isEmpty() ? null : platformsInRoute.get(0).get(loopEntryStop);
			final List<TripTail<PathData, SavedRailBase>> tripTails = getTripTails(tempPath, rails, graph, useFastSpeed);

			final DepotDuties runDuties = duties.diagramMode ? duties : generateAutoDuties(minecraftServer, linePlan);

			int successfulSegments = Integer.MAX_VALUE;
			for (final Siding siding : sidings) {
				final SavedRailBase firstPlatform = entryPlatform != null ? entryPlatform : platformsMerged.isEmpty() ? null : platformsMerged.get(0);
				final SavedRailBase finalPlatform = platformsMerged.isEmpty() ? null : platformsMerged.get(platformsMerged.size() - 1);
				final List<DutyPlan<PathData, SavedRailBase>> dutyPlans = runDuties != null ? getDutyPlans(runDuties, siding, rails, graph, useFastSpeed, key -> {
				}) : new ArrayList<>();
				successfulSegments = Math.min(successfulSegments, siding.generateRoute(minecraftServer, tempPath, platformsMerged.size(), rails, graph, firstPlatform, finalPlatform, repeatPath, cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, turnBackSections, depotReturns, dutyPlans, getPoolSidings(siding)));
			}
			return successfulSegments;
		}
	}
}
