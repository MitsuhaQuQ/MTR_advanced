package mtr.advanced;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.compat.Compat;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.RailwayData;
import mtr.data.Rail;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import transitsim.network.RailGraph;
import transitsim.plan.TripPath;
import mtr.packet.IPacket;
import mtr.packet.PacketGuard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.sim.DayRun;
import transitsim.sim.SimDataset;
import transitsim.sim.SimScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Days simulated for the advanced editor (S5): the world's data with a player's drafts put in, run by the component in a
 * world of its own on a background thread, so the game's trains aren't touched. Each player has one run at a time; a new
 * request stops the one before.
 */
public final class SandboxRuns implements IPacket {

	public static final int STATUS_RESULT = 0;
	/**
	 * Too many runs are waiting, or the run took too long.
	 */
	public static final int STATUS_BUSY = 1;
	public static final int STATUS_NO_PERMISSION = 2;
	public static final int STATUS_ERROR = 3;
	/**
	 * The server doesn't simulate days for the editor ({@code editorSimulation} is off).
	 */
	public static final int STATUS_DISABLED = 4;

	public static final int MAX_DRAFTS = 256;
	public static final int MAX_REPORT_ROUTES = 256;
	private static final int MAX_WAITING = 8;
	private static final long TIME_LIMIT_NANOS = 60_000_000_000L;
	private static final int PART_BYTES = 512 * 1024;
	private static final long HOUR = 60 * 60 * 1000;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR-Editor-Simulation");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static final Map<UUID, AtomicBoolean> RUNS = new ConcurrentHashMap<>();
	private static final AtomicInteger WAITING = new AtomicInteger();

	private SandboxRuns() {
	}

	/**
	 * Request number, weekday, routes to report on, draft routes ({@link SimDataset.RouteData#toBytes}), draft duties by
	 * depot ({@link DepotDuties#toBytes}) and whether to simulate on from the trains as they are now, if the weekday is
	 * today's (G0).
	 */
	public static void receiveSimulateC2S(MinecraftServer minecraftServer, ServerPlayer player, FriendlyByteBuf packet) {
		final int requestId = packet.readInt();
		final int weekday = packet.readInt();
		final Set<Long> reportRoutes = new HashSet<>();
		for (int i = Math.min(packet.readInt(), MAX_REPORT_ROUTES); i > 0; i--) {
			reportRoutes.add(packet.readLong());
		}
		final List<byte[]> draftRoutes = new ArrayList<>();
		for (int i = Math.min(packet.readInt(), MAX_DRAFTS); i > 0; i--) {
			draftRoutes.add(packet.readByteArray());
		}
		final Map<Long, byte[]> draftDuties = new HashMap<>();
		for (int i = Math.min(packet.readInt(), MAX_DRAFTS); i > 0; i--) {
			draftDuties.put(packet.readLong(), packet.readByteArray());
		}
		final boolean fromNow = packet.readBoolean();
		PacketGuard.execute(minecraftServer, () -> start(minecraftServer, player, requestId, weekday, reportRoutes, draftRoutes, draftDuties, fromNow));
	}

	private static void start(MinecraftServer minecraftServer, ServerPlayer player, int requestId, int weekday, Set<Long> reportRoutes, List<byte[]> draftRoutes, Map<Long, byte[]> draftDuties, boolean fromNow) {
		if (!SecurityConfig.editorSimulation) {
			sendStatus(player, requestId, STATUS_DISABLED, 0);
			return;
		}
		if (RailwayData.hasNoPermission(player)) {
			sendStatus(player, requestId, STATUS_NO_PERMISSION, 0);
			return;
		}
		final RailwayData railwayData = RailwayData.getInstance(Compat.getLevel(player));
		if (railwayData == null) {
			sendStatus(player, requestId, STATUS_ERROR, 0);
			return;
		}
		if (WAITING.get() >= MAX_WAITING) {
			sendStatus(player, requestId, STATUS_BUSY, 0);
			return;
		}

		// The world is read here on the server's thread; the run only has its own copy
		final SimDataset dataset = SimDataset.fromBytes(SimExport.build(railwayData));
		final int ignoredDrafts = putDrafts(dataset, railwayData, draftRoutes, draftDuties);
		final long dayStart = getDayStart(System.currentTimeMillis(), weekday);
		// The trains are as they are now only on today; another day starts with every train in its depot
		final long fromMillis = fromNow && dataset.trains != null && dataset.trains.time > dayStart && dataset.trains.time < dayStart + OperatingDay.MILLIS_PER_DAY ? dataset.trains.time : dayStart;
		final List<DutyPathSearch> dutyPathSearches = prepareDutyPaths(railwayData, player, draftDuties);

		final AtomicBoolean stopped = new AtomicBoolean();
		final AtomicBoolean previous = RUNS.put(player.getUUID(), stopped);
		if (previous != null) {
			previous.set(true);
		}
		WAITING.incrementAndGet();
		EXECUTOR.execute(() -> {
			try {
				final long deadline = System.nanoTime() + TIME_LIMIT_NANOS;
				final int missingDutyPaths = findDutyPaths(dataset, dutyPathSearches);
				// Only the routes' own part of the world: their depots and those whose trains pass where theirs do
				final SimScope scope = SimScope.of(dataset, reportRoutes);
				final DayRun dayRun = DayRun.run(scope.dataset, dayStart, fromMillis, reportRoutes, Collections.emptyList(), () -> stopped.get() || System.nanoTime() > deadline);
				if (!stopped.get()) {
					final byte[] bytes = dayRun.toBytes();
					minecraftServer.execute(() -> {
						if (dayRun.cancelled) {
							sendStatus(player, requestId, STATUS_BUSY, ignoredDrafts, scope.depots, scope.allDepots);
						} else {
							sendResult(player, requestId, ignoredDrafts, missingDutyPaths, scope.depots, scope.allDepots, bytes);
						}
					});
				}
			} catch (Exception e) {
				e.printStackTrace();
				minecraftServer.execute(() -> sendStatus(player, requestId, STATUS_ERROR, ignoredDrafts));
			} finally {
				WAITING.decrementAndGet();
				RUNS.remove(player.getUUID(), stopped);
			}
		});
	}

	/**
	 * A draft route only counts if it is still the route the server has, stop for stop.
	 *
	 * @return how many drafts were left out
	 */
	static int putDrafts(SimDataset dataset, RailwayData railwayData, List<byte[]> draftRoutes, Map<Long, byte[]> draftDuties) {
		int ignored = 0;
		for (final byte[] bytes : draftRoutes) {
			try {
				final SimDataset.RouteData draft = SimDataset.RouteData.fromBytes(bytes);
				final Route route = railwayData.dataCache.routeIdMap.get(draft.id);
				if (route != null && Arrays.equals(route.platformIds.stream().mapToLong(routePlatform -> routePlatform.platformId).toArray(), draft.platformIds)) {
					final mtr.data.RoutePaths.RoutePath path = railwayData.routePaths.getFound(route, railwayData.dataCache, railwayData.getRails());
					if (path != null) {
						final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
						final Route copy;
						try {
							route.writePacket(packet);
							copy = new Route(packet);
						} finally {
							packet.release();
						}
						for (int i = 0; i < draft.passes.length; i++) copy.platformIds.get(i).pass = draft.passes[i];
						final transitsim.plan.RouteTimetable timetable = transitsim.plan.TimetableBinary.read(new transitsim.io.ByteArrayBinary.Reader(draft.timetable));
						final java.util.Collection<mtr.data.PathTiming.Vehicle> vehicles = mtr.data.RoutePaths.getVehicles(mtr.data.RoutePaths.getVehicles(railwayData), route);
						final List<long[][]> tables = new ArrayList<>();
						for (transitsim.plan.RouteTimetable table : timetable.getTables()) tables.add(path.getFastestTimes(copy, vehicles, table));
						draft.fastestTimes = transitsim.plan.TimetableTimes.pack(tables);
					}
				}
				if (route == null || !Arrays.equals(route.platformIds.stream().mapToLong(routePlatform -> routePlatform.platformId).toArray(), draft.platformIds) || !dataset.replaceRoute(draft)) {
					ignored++;
				}
			} catch (Exception e) {
				ignored++;
			}
		}
		for (final Map.Entry<Long, byte[]> entry : draftDuties.entrySet()) {
			final DepotDuties duties = new DepotDuties();
			duties.fromBytes(entry.getValue());
			if (railwayData.dataCache.depotIdMap.get(entry.getKey()) == null || !dataset.replaceDuties(entry.getKey(), duties.diagramMode ? duties.toBytes() : new byte[0])) {
				ignored++;
			}
		}
		return ignored;
	}

	/**
	 * What finding the paths of a depot's draft duties needs from the server's thread (S6): trains running by duties only
	 * run a duty whose path the depot found along the rails, and a draft's new shapes of duty have none yet.
	 */
	private static List<DutyPathSearch> prepareDutyPaths(RailwayData railwayData, ServerPlayer player, Map<Long, byte[]> draftDuties) {
		final List<DutyPathSearch> searches = new ArrayList<>();
		draftDuties.forEach((depotId, bytes) -> {
			final Depot depot = railwayData.dataCache.depotIdMap.get(depotId);
			final DepotDuties duties = new DepotDuties();
			duties.fromBytes(bytes);
			if (depot != null && duties.diagramMode) {
				searches.add(new DutyPathSearch(depot, duties, railwayData, depot.getPathGraph(railwayData.getRails(), railwayData.pathBoundaries), depot.usesFastSpeed(Compat.getLevel(player))));
			}
		});
		return searches;
	}

	/**
	 * Finds the paths of the draft duties as the depot would and gives them to the sidings in the data, after the ones they
	 * have, so a new path of a shape takes the place of an old one.
	 *
	 * @return how many shapes of duty have no path
	 */
	static int findDutyPaths(SimDataset dataset, List<DutyPathSearch> searches) {
		final Set<String> missing = new HashSet<>();
		for (final DutyPathSearch search : searches) {
			final Map<BlockPos, Map<BlockPos, Rail>> rails = search.rails;
			final Map<Long, Map<String, TripPath<PathData>>> dutyPaths = search.depot.findDutyPaths(search.duties, search.dataCache, rails, search.graph, search.sidings, search.useFastSpeed, key -> missing.add(search.depot.id + ":" + key));
			dutyPaths.forEach((sidingId, paths) -> dataset.sidings.stream().filter(siding -> siding.id == sidingId).findFirst().ifPresent(siding -> paths.values().stream().sorted(Comparator.comparing(tripPath -> tripPath.key)).forEach(tripPath -> siding.tripPaths.add(Siding.toSimulation(tripPath, path -> SimExport.toTracks(path, rails))))));
		}
		return missing.size();
	}

	private static final class DutyPathSearch {

		private final Depot depot;
		private final DepotDuties duties;
		private final RailwayData railwayData;
		private final RailGraph<Rail> graph;
		private final boolean useFastSpeed;
		/**
		 * The sidings as they were on the server's thread, which goes on changing its own set.
		 */
		private final Set<Siding> sidings;
		/**
		 * The rails and data as they were on the server's thread, for the same reason.
		 */
		private final Map<BlockPos, Map<BlockPos, Rail>> rails;
		private final DataCache dataCache;

		private DutyPathSearch(Depot depot, DepotDuties duties, RailwayData railwayData, RailGraph<Rail> graph, boolean useFastSpeed) {
			this.depot = depot;
			this.duties = duties;
			this.railwayData = railwayData;
			this.graph = graph;
			this.useFastSpeed = useFastSpeed;
			sidings = new HashSet<>(railwayData.sidings);
			rails = DataCache.copyRails(railwayData.getRails());
			dataCache = railwayData.dataCache.copyIdMaps();
		}
	}

	/**
	 * @return the start of the next operating day on this weekday, or of today's if it is that weekday
	 */
	public static long getDayStart(long millis, int weekday) {
		long dayStart = DutyShapes.getDayStart(millis);
		for (int i = 0; i < 7 && OperatingDay.getWeekday(dayStart + HOUR) != weekday; i++) {
			dayStart = DutyShapes.getDayStart(dayStart + OperatingDay.MILLIS_PER_DAY + HOUR);
		}
		return dayStart;
	}

	private static void sendStatus(ServerPlayer player, int requestId, int status, int ignoredDrafts) {
		sendStatus(player, requestId, status, ignoredDrafts, 0, 0);
	}

	/**
	 * @param depots    how many depots the run simulated, and of how many the world has, or 0 if it didn't run
	 */
	private static void sendStatus(ServerPlayer player, int requestId, int status, int ignoredDrafts, int depots, int allDepots) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeInt(status);
		packet.writeInt(ignoredDrafts);
		packet.writeInt(0);
		packet.writeInt(depots);
		packet.writeInt(allDepots);
		packet.writeInt(0);
		packet.writeInt(0);
		packet.writeByteArray(new byte[0]);
		Registry.sendToPlayer(player, PACKET_SIMULATE_RESULT, packet);
	}

	/**
	 * The report in parts small enough for a packet, which the client puts back together.
	 */
	private static void sendResult(ServerPlayer player, int requestId, int ignoredDrafts, int missingDutyPaths, int depots, int allDepots, byte[] bytes) {
		final List<byte[]> parts = split(bytes, PART_BYTES);
		for (int i = 0; i < parts.size(); i++) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(requestId);
			packet.writeInt(STATUS_RESULT);
			packet.writeInt(ignoredDrafts);
			packet.writeInt(missingDutyPaths);
			packet.writeInt(depots);
			packet.writeInt(allDepots);
			packet.writeInt(i);
			packet.writeInt(parts.size());
			packet.writeByteArray(parts.get(i));
			Registry.sendToPlayer(player, PACKET_SIMULATE_RESULT, packet);
		}
	}

	public static List<byte[]> split(byte[] bytes, int partBytes) {
		final List<byte[]> parts = new ArrayList<>();
		for (int start = 0; start < bytes.length || parts.isEmpty(); start += partBytes) {
			parts.add(Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + partBytes)));
		}
		return parts;
	}

	public static byte[] join(byte[][] parts) {
		int length = 0;
		for (final byte[] part : parts) {
			length += part.length;
		}
		final byte[] bytes = new byte[length];
		int start = 0;
		for (final byte[] part : parts) {
			System.arraycopy(part, 0, bytes, start, part.length);
			start += part.length;
		}
		return bytes;
	}
}
