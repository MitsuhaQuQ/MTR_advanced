package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.PathData;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathFailure;
import mtr.path.PathFinder;
import mtr.path.RailGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Depot extends AreaBase implements IReducedSaveData {

	public int clientPathGenerationSuccessfulSegments;
	public PathFailure clientPathFailure = PathFailure.NONE;
	public long lastDeployedMillis;
	private long lastDeployedTripMillis;
	public boolean useRealTime;
	public boolean repeatInfinitely;
	public boolean useDeterministicPath = true;
	public int cruisingAltitude = DEFAULT_CRUISING_ALTITUDE;
	private int deployIndex;
	private int departureOffset;
	private boolean isDirty = true;

	public final List<Long> routeIds = new ArrayList<>();
	public final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	public final List<Integer> departures = new ArrayList<>();
	public final List<Integer> tempDepartures = new ArrayList<>();

	private final int[] frequencies = new int[HOURS_IN_DAY];
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();
	private RouteTimetable timetable;
	private Route timetableRoute;
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
	/**
	 * How early a train aims to reach the first stop, so a small delay on the way doesn't make it late.
	 */
	private static final int DEPLOY_MARGIN_MILLIS = 15000;

	private static final String KEY_ROUTE_IDS = "route_ids";
	private static final int MAX_DEPARTURES = 4096;
	private static final int MAX_ROUTES = 1024;
	private static final String KEY_USE_REAL_TIME = "use_real_time";
	private static final String KEY_FREQUENCIES = "frequencies";
	private static final String KEY_DEPARTURES = "departures";
	private static final String KEY_LAST_DEPLOYED = "last_deployed";
	private static final String KEY_LAST_DEPLOYED_TRIP = "last_deployed_trip";
	private static final String KEY_DEPLOY_INDEX = "deploy_index";
	private static final String KEY_REPEAT_INFINITELY = "repeat_infinitely";
	private static final String KEY_CRUISING_ALTITUDE = "cruising_altitude";
	private static final String KEY_USE_DETERMINISTIC_PATH = "use_deterministic_path";

	public Depot(TransportMode transportMode) {
		super(transportMode);
		useRealTime = !transportMode.continuousMovement;
	}

	public Depot(long id, TransportMode transportMode) {
		super(id, transportMode);
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
		lastDeployedMillis = System.currentTimeMillis() - messagePackHelper.getLong(KEY_LAST_DEPLOYED);
		lastDeployedTripMillis = messagePackHelper.getLong(KEY_LAST_DEPLOYED_TRIP);
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
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		messagePacker.packString(KEY_DEPLOY_INDEX).packInt(deployIndex);
		messagePacker.packString(KEY_LAST_DEPLOYED).packLong(System.currentTimeMillis() - lastDeployedMillis);
		messagePacker.packString(KEY_LAST_DEPLOYED_TRIP).packLong(lastDeployedTripMillis);
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

		messagePacker.packString(KEY_FREQUENCIES).packArrayHeader(HOURS_IN_DAY);
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			messagePacker.packInt(frequencies[i]);
		}

		messagePacker.packString(KEY_DEPARTURES).packArrayHeader(departures.size());
		for (final int departure : departures) {
			messagePacker.packInt(departure);
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 10;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 3;
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
		sendPacket.accept(packet);
	}

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Siding> sidings, Consumer<Thread> callback) {
		final List<SavedRailBase> platformsMerged = new ArrayList<>();
		final List<List<SavedRailBase>> platformsInRoute = new ArrayList<>();
		final List<List<PathData>> pathsInRoute = new ArrayList<>();
		final List<List<List<BlockPos>>> waypointsInRoute = new ArrayList<>();
		final List<List<Route.RoutePlatform>> stopSettingsInRoute = new ArrayList<>();

		routeIds.forEach(routeId -> {
			final Route route = dataCache.routeIdMap.get(routeId);
			if (route != null) {
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
			}
		});

		final boolean useFastSpeed = cruisingAltitude >= world.getMaxBuildHeight() + THRESHOLD_ABOVE_MAX_BUILD_HEIGHT;
		// A timetabled line that ends where it starts runs lap after lap, and only returns to the depot when service ends
		final boolean repeatPath = repeatInfinitely || findTimetable(dataCache) != null && platformsMerged.size() > 1 && platformsMerged.get(0).id == platformsMerged.get(platformsMerged.size() - 1).id;

		final RailGraph<Rail> graph = getPathGraph(rails);

		final Thread thread = new Thread(() -> {
			try {
				final List<PathData> tempPath = new ArrayList<>();
				PathFailure failure = PathFailure.NONE;
				SavedRailBase lastPlatform = null;

				for (int i = 0; i < pathsInRoute.size(); i++) {
					final List<SavedRailBase> platforms = platformsInRoute.get(i);
					if (lastPlatform != null && !platforms.isEmpty() && platforms.get(0) != lastPlatform) {
						final List<PathData> connectingPath = new ArrayList<>();
						PathFinder.findPath(connectingPath, rails, graph, Arrays.asList(lastPlatform, platforms.get(0)), null, 1, cruisingAltitude, useFastSpeed);
						tempPath.addAll(connectingPath);
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

				int stopIndex = 1;
				for (int i = 1; i < tempPath.size(); i++) {
					final PathData pathData = tempPath.get(i);
					// Passed platforms have no stop time but still count, as route indices include them
					if (pathData.rail.railType == RailType.PLATFORM && pathData.savedRailBaseId != 0 && tempPath.get(i - 1).rail.railType != RailType.PLATFORM) {
						stopIndex++;
					}
					pathData.stopIndex = stopIndex;
				}

				final int[] successfulSegments = new int[]{Integer.MAX_VALUE};
				final int successfulSegmentsMain = platformsMerged.size();

				sidings.forEach(siding -> {
					final BlockPos sidingMidPos = siding.getMidPos();
					if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
						final SavedRailBase firstPlatform = platformsMerged.isEmpty() ? null : platformsMerged.get(0);
						final SavedRailBase finalPlatform = platformsMerged.isEmpty() ? null : platformsMerged.get(platformsMerged.size() - 1);
						final int result = siding.generateRoute(minecraftServer, tempPath, successfulSegmentsMain, rails, graph, firstPlatform, finalPlatform, repeatPath, cruisingAltitude, useFastSpeed);
						if (result < successfulSegments[0]) {
							successfulSegments[0] = result;
						}
					}
				});

				PacketTrainDataGuiServer.generatePathS2C(world, id, successfulSegments[0], failure);
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
		return useDeterministicPath && transportMode != TransportMode.AIRPLANE && !transportMode.continuousMovement ? DeterministicPathFinder.compile(rails) : null;
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

		timetable = findTimetable(railwayData.dataCache);
		timetableRoute = timetable == null ? null : railwayData.dataCache.routeIdMap.get(routeIds.get(0));

		if (!deployableSidings.isEmpty() && (timetable != null || getMillisUntilDeploy(1) == 0)) {
			final List<Siding> sidingsInDepot = railwayData.sidings.stream().filter(siding -> {
				final BlockPos sidingPos = siding.getMidPos();
				return siding.isTransportMode(transportMode) && inArea(sidingPos.getX(), sidingPos.getZ());
			}).sorted().collect(Collectors.toList());

			final int sidingsInDepotSize = sidingsInDepot.size();
			for (int i = deployIndex; i < deployIndex + sidingsInDepotSize; i++) {
				final TrainServer train = deployableSidings.get(sidingsInDepot.get(i % sidingsInDepotSize).id);
				final long currentMillis = System.currentTimeMillis();
				final long tripMillis = train == null || timetable == null ? 0 : timetable.getLatestService(Math.max(lastDeployedTripMillis, currentMillis - RouteTimetable.MAX_DEPLOY_DELAY_MILLIS), currentMillis + train.getLeadMillis() + DEPLOY_MARGIN_MILLIS, this::isTripReserved);
				if (train != null && (timetable == null || tripMillis != 0)) {
					lastDeployedMillis = currentMillis;
					deployIndex++;
					if (deployIndex >= sidingsInDepotSize) {
						deployIndex = 0;
					}
					if (timetable == null) {
						train.deployTrain();
					} else {
						lastDeployedTripMillis = tripMillis;
						reserveTrip(tripMillis);
						train.deployTrain(tripMillis);
					}
					break;
				}
			}
		}

		departureOffset = 0;
		deployableSidings.clear();
	}

	public RouteTimetable findTimetable(DataCache dataCache) {
		final Route firstRoute = routeIds.isEmpty() ? null : dataCache.routeIdMap.get(routeIds.get(0));
		return firstRoute != null && supportsTimetable(transportMode) && firstRoute.timetable.isActive() ? firstRoute.timetable : null;
	}

	public static boolean supportsTimetable(TransportMode transportMode) {
		return !transportMode.continuousMovement && transportMode != TransportMode.AIRPLANE;
	}

	/**
	 * @return the timetable sending out this depot's trains, which belongs to its first route
	 */
	public RouteTimetable getTimetable() {
		return timetable;
	}

	public Route getTimetableRoute() {
		return timetableRoute;
	}

	public void reserveTrip(long tripMillis) {
		if (tripMillis != 0) {
			newReservedTrips.add(tripMillis);
		}
	}

	/**
	 * @return the first service from this time on that no train has taken, or 0 if a train would wait too long for it
	 */
	public long findNextTrip(long readyMillis) {
		final long tripMillis = timetable == null ? 0 : timetable.getNextService(readyMillis, readyMillis + RouteTimetable.MAX_LAYOVER_MILLIS, this::isTripReserved);
		reserveTrip(tripMillis);
		return tripMillis;
	}

	private boolean isTripReserved(long tripMillis) {
		return reservedTrips.contains(tripMillis) || newReservedTrips.contains(tripMillis);
	}

	/**
	 * Like {@link #getNextDepartureMillis()}, using the timetable if there is one, which skips services already taken.
	 */
	public int getNextDepartureMillis(long leadMillis) {
		if (timetable == null) {
			return getNextDepartureMillis();
		}

		departureOffset++;
		final long currentMillis = System.currentTimeMillis();
		long fromMillis = currentMillis + leadMillis;
		long tripMillis = 0;
		for (int i = 0; i < departureOffset; i++) {
			tripMillis = timetable.getNextService(fromMillis, currentMillis + MILLISECONDS_PER_DAY, this::isTripReserved);
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
}
