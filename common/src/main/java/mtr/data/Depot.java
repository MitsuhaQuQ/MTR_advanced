package mtr.data;

import mtr.advanced.PacketBinary;
import transitsim.plan.RouteTimetable;
import io.netty.buffer.Unpooled;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
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
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import transitsim.network.RailGraph;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Depot extends AreaBase implements IReducedSaveData, TrainService.Board {

	public int clientPathGenerationSuccessfulSegments;
	public PathFailure clientPathFailure = PathFailure.NONE;
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
	 * The operating day each slot last ran its duty, as "siding:slot", so a duty runs once a day however its train gets to it.
	 */
	private final Map<String, Long> dutyRunDays = new HashMap<>();

	private final int[] frequencies = new int[HOURS_IN_DAY];
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();
	private DataCache dataCache;
	private final Map<String, List<Route>> trainRoutes = new HashMap<>();
	private final Map<Long, Long> lastDeployedTrips = new HashMap<>();
	private Set<Long> reservedTrips = new HashSet<>();
	private Set<Long> newReservedTrips = new HashSet<>();

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
	private static final String KEY_DUTIES = "duties";
	private static final String KEY_DUTY_MODE = "duty_mode";
	private static final String KEY_DUTY_SLOT = "duty_slot";
	private static final String KEY_DUTY_RUN_DAYS = "duty_run_days";
	private static final int DUTY_DEPLOY_MARGIN_MILLIS = 15000;
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

		deployIndex = messagePackHelper.getInt(KEY_DEPLOY_INDEX);
		repeatInfinitely = messagePackHelper.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = messagePackHelper.getInt(KEY_CRUISING_ALTITUDE);
		useDeterministicPath = messagePackHelper.getBoolean(KEY_USE_DETERMINISTIC_PATH);
		cycleRoutes = messagePackHelper.getBoolean(KEY_CYCLE_ROUTES);
		speedCurves = messagePackHelper.getBoolean(KEY_SPEED_CURVES);
		lastDeployedMillis = System.currentTimeMillis() - messagePackHelper.getLong(KEY_LAST_DEPLOYED);
		messagePackHelper.iterateMapValue(KEY_LAST_DEPLOYED_TRIPS, entry -> lastDeployedTrips.put(Long.parseLong(entry.getKey().asStringValue().asString()), entry.getValue().asIntegerValue().asLong()));
		messagePackHelper.iterateMapValue(KEY_VEHICLE_SERVICE_TYPES, entry -> vehicleServiceTypes.put(entry.getKey().asStringValue().asString(), entry.getValue().asStringValue().asString()));
		final Value dutiesValue = map.get(KEY_DUTIES);
		if (dutiesValue != null && dutiesValue.isBinaryValue()) {
			duties.fromBytes(dutiesValue.asBinaryValue().asByteArray());
		}
		duties.diagramMode = DUTY_MODE_DIAGRAM.equals(messagePackHelper.getString(KEY_DUTY_MODE));
		messagePackHelper.iterateMapValue(KEY_DUTY_RUN_DAYS, entry -> dutyRunDays.put(entry.getKey().asStringValue().asString(), entry.getValue().asIntegerValue().asLong()));
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
		deployIndex = compoundTag.getInt(KEY_DEPLOY_INDEX);
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
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		messagePacker.packString(KEY_DEPLOY_INDEX).packInt(deployIndex);
		messagePacker.packString(KEY_LAST_DEPLOYED).packLong(System.currentTimeMillis() - lastDeployedMillis);
		messagePacker.packString(KEY_LAST_DEPLOYED_TRIPS).packMapHeader(lastDeployedTrips.size());
		for (final Map.Entry<Long, Long> entry : lastDeployedTrips.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packLong(entry.getValue());
		}
		messagePacker.packString(KEY_DUTY_RUN_DAYS).packMapHeader(dutyRunDays.size());
		for (final Map.Entry<String, Long> entry : dutyRunDays.entrySet()) {
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
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 16;
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

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Siding> sidings, Consumer<Thread> callback) {
		// Vehicles only run some types of service, so each group of them gets a path of its own routes
		final Map<Set<String>, List<Siding>> sidingGroups = new LinkedHashMap<>();
		sidings.forEach(siding -> {
			final BlockPos sidingMidPos = siding.getMidPos();
			if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
				sidingGroups.computeIfAbsent(getServiceTypes(siding.getTrainId()), key -> new ArrayList<>()).add(siding);
			}
		});
		if (sidingGroups.isEmpty()) {
			sidingGroups.put(Collections.emptySet(), new ArrayList<>());
		}

		final boolean useFastSpeed = cruisingAltitude >= world.getMaxBuildHeight() + THRESHOLD_ABOVE_MAX_BUILD_HEIGHT;
		final List<MainPath> mainPaths = new ArrayList<>();
		sidingGroups.forEach((serviceTypes, sidingGroup) -> mainPaths.add(new MainPath(filterRoutes(getRoutes(dataCache), serviceTypes), sidingGroup, dataCache)));

		final RailwayData railwayData = RailwayData.getInstance(world);
		final RailGraph<Rail> graph = getPathGraph(rails, railwayData == null ? null : railwayData.pathBoundaries);

		final Thread thread = new Thread(() -> {
			try {
				int successfulSegments = Integer.MAX_VALUE;
				PathFailure failure = PathFailure.NONE;
				for (final MainPath mainPath : mainPaths) {
					successfulSegments = Math.min(successfulSegments, mainPath.generate(minecraftServer, rails, graph, useFastSpeed));
					failure = mainPath.failure == PathFailure.NONE ? failure : mainPath.failure;
				}
				PacketTrainDataGuiServer.generatePathS2C(world, id, successfulSegments, failure);
				System.out.println("Finished path generation" + (name.isEmpty() ? "" : " for " + name));
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

	public void requestDeploy(long sidingId, TrainServer train) {
		deployableSidings.put(sidingId, train);
	}

	public void deployTrain(RailwayData railwayData, Level world) {
		if (isDirty) {
			generateTempDepartures(world);
		}

		final Set<Long> oldReservedTrips = reservedTrips;
		reservedTrips = newReservedTrips;
		newReservedTrips = oldReservedTrips;
		newReservedTrips.clear();

		dataCache = railwayData.dataCache;
		trainRoutes.clear();

		if (duties.diagramMode) {
			deployDutyTrain(railwayData);
			departureOffset = 0;
			deployableSidings.clear();
			return;
		}

		if (!deployableSidings.isEmpty()) {
			final List<Siding> sidingsInDepot = railwayData.sidings.stream().filter(siding -> {
				final BlockPos sidingPos = siding.getMidPos();
				return siding.isTransportMode(transportMode) && inArea(sidingPos.getX(), sidingPos.getZ());
			}).sorted().collect(Collectors.toList());

			final int sidingsInDepotSize = sidingsInDepot.size();
			for (int i = deployIndex; i < deployIndex + sidingsInDepotSize; i++) {
				final TrainServer train = deployableSidings.get(sidingsInDepot.get(i % sidingsInDepotSize).id);
				if (train == null) {
					continue;
				}
				final RouteTimetable timetable = getTimetable(train.trainId);
				final Route firstRoute = timetable == null ? null : getTrainRoutes(train.trainId).get(0);
				final long currentMillis = System.currentTimeMillis();
				final long tripMillis = timetable == null ? 0 : train.findDeployService(lastDeployedTrips.getOrDefault(firstRoute.id, 0L));
				if (timetable == null ? getMillisUntilDeploy(1) == 0 : tripMillis != 0) {
					lastDeployedMillis = currentMillis;
					deployIndex++;
					if (deployIndex >= sidingsInDepotSize) {
						deployIndex = 0;
					}
					if (timetable == null) {
						train.deployTrain();
					} else {
						lastDeployedTrips.put(firstRoute.id, tripMillis);
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
	 * A depot running by its duties sends out a train for each slot whose duty starts from the siding today, when its first
	 * service is due, once a day (design document 6.12, block E2). Slots whose duty a train staying overnight takes over are
	 * left to that train. One train leaves a tick, the one due first.
	 */
	private void deployDutyTrain(RailwayData railwayData) {
		final long currentMillis = System.currentTimeMillis();
		final long dayStart = DutyShapes.getDayStart(currentMillis);
		final long day = DutyShapes.getDayNumber(dayStart);
		final int weekday = TimetableConfig.getWeekday(currentMillis);
		TrainServer bestTrain = null;
		Siding.TripPath bestPath = null;
		long bestSidingId = 0;
		int bestSlot = -1;
		long bestDepartureMillis = Long.MAX_VALUE;
		for (final Map.Entry<Long, TrainServer> entry : new TreeMap<>(deployableSidings).entrySet()) {
			final Siding siding = railwayData.dataCache.sidingIdMap.get(entry.getKey());
			final List<DepotDuties.Slot> slots = duties.getSlots(entry.getKey());
			if (siding == null) {
				continue;
			}
			for (int i = 0; i < slots.size(); i++) {
				final DepotDuties.DayDuty duty = slots.get(i).getDuty(weekday);
				if (duty == null || duty.segments.isEmpty() || hasDutyRun(siding.id, i, day) || DutyShapes.getOvernightSlot(slots, i, weekday) >= 0) {
					continue;
				}
				final String key = DutyShapes.getSlotKey(slots, i, weekday, railwayData.dataCache.routeIdMap::get);
				final Siding.TripPath dutyPath = key == null ? null : siding.getTripPaths().get(key);
				if (dutyPath == null) {
					continue;
				}
				final long departureMillis = DutyShapes.getServiceMillis(dayStart, duty.segments.get(0).service) - dutyPath.stopTimes.getLeadMillis() - DUTY_DEPLOY_MARGIN_MILLIS;
				if (currentMillis >= departureMillis && currentMillis <= departureMillis + RouteTimetable.MAX_DEPLOY_DELAY_MILLIS && departureMillis < bestDepartureMillis) {
					bestTrain = entry.getValue();
					bestPath = dutyPath;
					bestSidingId = siding.id;
					bestSlot = i;
					bestDepartureMillis = departureMillis;
				}
			}
		}
		if (bestTrain != null) {
			markDutyRun(bestSidingId, bestSlot, day);
			lastDeployedMillis = currentMillis;
			bestTrain.deployDuty(bestSlot, dayStart, bestPath);
		}
	}

	@Override
	public List<DepotDuties.Slot> getDutySlots(long sidingId) {
		return duties.getSlots(sidingId);
	}

	@Override
	public boolean hasDutyRun(long sidingId, int slot, long day) {
		final Long runDay = dutyRunDays.get(sidingId + ":" + slot);
		return runDay != null && runDay == day;
	}

	@Override
	public void markDutyRun(long sidingId, int slot, long day) {
		dutyRunDays.put(sidingId + ":" + slot, day);
		// Days long gone don't need remembering
		dutyRunDays.values().removeIf(runDay -> runDay < day - 2);
	}

	/**
	 * @return the timetable of the depot's first route, for showing that departures follow a timetable
	 */
	public RouteTimetable findTimetable(DataCache dataCache) {
		final List<Route> routes = getRoutes(dataCache);
		return routes.isEmpty() ? null : getActiveTimetable(routes.get(0));
	}

	/**
	 * @return the timetable sending out this vehicle, which belongs to the first route it runs
	 */
	public RouteTimetable getTimetable(String trainId) {
		final List<Route> routes = getTrainRoutes(trainId);
		return routes.isEmpty() ? null : getActiveTimetable(routes.get(0));
	}

	private RouteTimetable getActiveTimetable(Route route) {
		return supportsTimetable(transportMode) && route.timetable.isActive() ? route.timetable : null;
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

	public void reserveTrip(long routeId, long tripMillis) {
		if (tripMillis != 0) {
			newReservedTrips.add(getTripKey(routeId, tripMillis));
		}
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
	public long findNextService(Route route, long readyMillis) {
		final long tripMillis = route.timetable.getNextService(readyMillis, readyMillis + (cycleRoutes ? RouteTimetable.MAX_STAY_MILLIS : RouteTimetable.MAX_LAYOVER_MILLIS), millis -> isTripReserved(route.id, millis));
		reserveTrip(route.id, tripMillis);
		return tripMillis;
	}

	@Override
	public boolean keepsTrainsOnLine() {
		return cycleRoutes;
	}

	public boolean isTripReserved(long routeId, long tripMillis) {
		final long tripKey = getTripKey(routeId, tripMillis);
		return reservedTrips.contains(tripKey) || newReservedTrips.contains(tripKey);
	}

	private static long getTripKey(long routeId, long tripMillis) {
		return routeId * 31 + tripMillis;
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
		final long firstRouteId = getTrainRoutes(trainId).get(0).id;
		final long currentMillis = System.currentTimeMillis();
		long fromMillis = currentMillis + leadMillis;
		long tripMillis = 0;
		for (int i = 0; i < departureOffset; i++) {
			tripMillis = timetable.getNextService(fromMillis, currentMillis + MILLISECONDS_PER_DAY, millis -> isTripReserved(firstRouteId, millis));
			if (tripMillis == 0) {
				return -1;
			}
			fromMillis = tripMillis + 1;
		}
		return (int) (tripMillis - leadMillis - currentMillis);
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
		final long millis = (System.currentTimeMillis() + currentTimeOffset) % MILLISECONDS_PER_DAY;
		for (int i = 0; i < tempDepartures.size(); i++) {
			final long thisDeparture = tempDepartures.get(i);
			final long nextDeparture = wrapTime(tempDepartures.get((i + 1) % tempDepartures.size()), thisDeparture);
			final long newMillis = wrapTime(millis, thisDeparture);
			if (newMillis > thisDeparture && newMillis <= nextDeparture) {
				if (offset > 1) {
					if (offset <= tempDepartures.size()) {
						return (int) (wrapTime(tempDepartures.get((i + offset) % tempDepartures.size()), millis) - millis);
					}
				} else {
					return wrapTime(lastDeployedMillis + currentTimeOffset, newMillis) - MILLISECONDS_PER_DAY >= thisDeparture ? (int) (nextDeparture - newMillis) : 0;
				}
			}
		}
		return -1;
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

	private static long wrapTime(long time, long mustBeGreaterThan) {
		long newTime = time % MILLISECONDS_PER_DAY;
		while (newTime <= mustBeGreaterThan) {
			newTime += MILLISECONDS_PER_DAY;
		}
		return newTime;
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
	private class MainPath {

		private PathFailure failure = PathFailure.NONE;

		private final List<Siding> sidings;
		private final boolean repeatPath;
		/**
		 * The path turns back from the last route to the first stop of the first one, so trains go round without the depot.
		 */
		private final boolean closesPath;
		/**
		 * The loop that trains join part of the way round, with the stop where they join it.
		 */
		private final Route loopRoute;
		private final int loopEntryStop;
		private final List<Route> mainRoutes;
		private final DataCache dataCache;
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
			final boolean hasTimetable = !routes.isEmpty() && getActiveTimetable(routes.get(0)) != null;
			closesPath = cycleRoutes && platformsMerged.size() > 1 && platformsMerged.get(0).id != platformsMerged.get(platformsMerged.size() - 1).id;
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
		private List<Siding.TripTail> getTripTails(List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<Siding.TripTail> tripTails = new ArrayList<>();
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
				if (keys.add(Siding.TripTail.getKey(links))) {
					final Siding.TripTail tripTail = getTripTail(lastRoute, links, mainPath, rails, graph, useFastSpeed);
					if (tripTail != null) {
						tripTails.add(tripTail);
					}
				}
			}));
			return tripTails;
		}

		private Siding.TripTail getTripTail(Route lastRoute, List<RouteTimetable.ServiceLink> links, List<PathData> mainPath, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
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
			int lapStart = Siding.TripTail.NO_RAIL;
			int entryRail = Siding.TripTail.NO_RAIL;
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
						final Siding.TurnBackSection turnBackSection = findTurnBackSection(leavePlatform, entryPlatform, turnBackNodes, 0, rails, graph, useFastSpeed);
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
				PathFinder.findPath(routePath, rails, graph, nextPlatforms, nextWaypoints, 1, cruisingAltitude, useFastSpeed);
				if (routePath.size() <= 2) {
					return null;
				}
				PathFinder.applyStopSettings(routePath, nextStopSettings);

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
			return new Siding.TripTail(Siding.TripTail.getKey(links), tripRouteIds, cutStops, leaveRail, tailPath, lastPlatform, lapStart, isLapReversed, entryRail, entryStops, entryLeads);
		}

		/**
		 * Each shape of duty a siding's slots run, once (design document 6.12, block E2).
		 */
		private List<Siding.DutyPlan> getDutyPlans(Siding siding, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<Siding.DutyPlan> dutyPlans = new ArrayList<>();
			final Set<String> keys = new HashSet<>();
			final List<DepotDuties.Slot> slots = duties.getSlots(siding.id);
			for (int i = 0; i < slots.size(); i++) {
				for (int weekday = 0; weekday < 7; weekday++) {
					final String key = DutyShapes.getSlotKey(slots, i, weekday, dataCache.routeIdMap::get);
					if (key == null || !keys.add(key)) {
						continue;
					}
					final DepotDuties.DayDuty day = slots.get(i).getDuty(weekday);
					final Siding.DutyPlan dutyPlan = getDutyPlan(key, day.segments, weekday, DutyShapes.getOvernightSlot(slots, i, weekday) >= 0, day.stayOnLine, rails, graph, useFastSpeed);
					if (dutyPlan == null) {
						failure = new PathFailure(PathFailure.Type.DUTY, 0);
					} else {
						dutyPlans.add(dutyPlan);
					}
				}
			}
			return dutyPlans;
		}

		/**
		 * Joins up a duty's segments: each service from where it joins its route to where it leaves, with the stops it passes
		 * passed, which is how a single service's passing or stopping becomes real, then on to the next segment the way the
		 * depot's own path would turn back.
		 *
		 * @return the plan, or null if a part of it can't be found
		 */
		private Siding.DutyPlan getDutyPlan(String key, List<DepotDuties.Segment> segments, int weekday, boolean startsOnLine, boolean staysOnLine, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
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
				PathFinder.findPath(segmentPath, rails, graph, platforms, waypoints, 1, cruisingAltitude, useFastSpeed);
				if (segmentPath.size() < 2) {
					return null;
				}
				PathFinder.applyStopSettings(segmentPath, stopSettings);

				if (lastPlatform != null && lastPlatform.id != platforms.get(0).id) {
					final List<PathData> connectingPath = new ArrayList<>();
					// After running a route to its end the train turns back the way the route lists, like the depot's own path
					final List<BlockPos> turnBackNodes = previousRanToEnd ? getTurnBackNodes(previousRoute, graph) : Collections.emptyList();
					if (turnBackNodes.isEmpty()) {
						PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(lastPlatform, platforms.get(0)), null, 1, cruisingAltitude, useFastSpeed);
					} else {
						final Siding.TurnBackSection turnBackSection = findTurnBackSection(lastPlatform, platforms.get(0), turnBackNodes, 0, rails, graph, useFastSpeed);
						if (turnBackSection != null) {
							connectingPath.addAll(turnBackSection.getDefaultWay());
						}
					}
					if (connectingPath.isEmpty()) {
						return null;
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
					PathFinder.findPath(leadPath, rails, graph, leadPlatforms, leadWaypoints, 1, cruisingAltitude, useFastSpeed);
					if (leadPath.size() >= 2 && leadPlatforms.size() == firstStop + 1) {
						PathFinder.applyStopSettings(leadPath, leadSettings);
						entryStops.put(route.id, firstStop);
						entryLeads.put(route.id, leadPath);
					}
				}
				previousRoute = route;
				previousRanToEnd = lastStop == route.platformIds.size() - 1;
			}
			return new Siding.DutyPlan(key, routeIds, cutStops, entryStops, entryLeads, body, segmentStarts, firstPlatform, lastPlatform, startsOnLine, staysOnLine);
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
		private boolean addConnection(List<PathData> tempPath, SavedRailBase from, SavedRailBase to, int routeBefore, List<Siding.TurnBackSection> turnBackSections, List<Siding.DepotReturn> depotReturns, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final int start = tempPath.size();
			final List<BlockPos> turnBackNodes = routeBefore >= 0 ? getTurnBackNodes(mainRoutes.get(routeBefore), graph) : Collections.emptyList();
			final List<PathData> connectingPath = new ArrayList<>();
			if (turnBackNodes.isEmpty()) {
				PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(from, to), null, 1, cruisingAltitude, useFastSpeed);
			} else {
				final Siding.TurnBackSection turnBackSection = findTurnBackSection(from, to, turnBackNodes, start, rails, graph, useFastSpeed);
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
				depotReturns.add(new Siding.DepotReturn(routeBefore, start, connectingPath, from));
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
		private Siding.TurnBackSection findTurnBackSection(SavedRailBase from, SavedRailBase to, List<BlockPos> nodes, int start, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
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
			return defaultWay < 0 ? null : new Siding.TurnBackSection(start, ways, defaultWay);
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

		private int generate(MinecraftServer minecraftServer, Map<BlockPos, Map<BlockPos, Rail>> rails, RailGraph<Rail> graph, boolean useFastSpeed) {
			final List<PathData> tempPath = new ArrayList<>();
			final List<Siding.TurnBackSection> turnBackSections = new ArrayList<>();
			final List<Siding.DepotReturn> depotReturns = new ArrayList<>();
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
				if (routePath.isEmpty()) {
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
			if (closesPath && !tempPath.isEmpty() && lastPlatform != null && !addConnection(tempPath, lastPlatform, platformsMerged.get(0), pathsInRoute.size() - 1, turnBackSections, depotReturns, rails, graph, useFastSpeed)) {
				tempPath.clear();
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
			final List<Siding.TripTail> tripTails = getTripTails(tempPath, rails, graph, useFastSpeed);

			int successfulSegments = Integer.MAX_VALUE;
			for (final Siding siding : sidings) {
				final SavedRailBase firstPlatform = entryPlatform != null ? entryPlatform : platformsMerged.isEmpty() ? null : platformsMerged.get(0);
				final SavedRailBase finalPlatform = platformsMerged.isEmpty() ? null : platformsMerged.get(platformsMerged.size() - 1);
				final List<Siding.DutyPlan> dutyPlans = duties.diagramMode ? getDutyPlans(siding, rails, graph, useFastSpeed) : new ArrayList<>();
				successfulSegments = Math.min(successfulSegments, siding.generateRoute(minecraftServer, tempPath, platformsMerged.size(), rails, graph, firstPlatform, finalPlatform, repeatPath, cruisingAltitude, useFastSpeed, loopJoinPath, tripTails, turnBackSections, depotReturns, dutyPlans));
			}
			return successfulSegments;
		}
	}
}
