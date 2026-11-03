package mtr.advanced;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.compat.Compat;
import mtr.data.RailwayData;
import mtr.data.Route;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
	 * Request number, weekday, routes to report on, draft routes ({@link SimDataset.RouteData#toBytes}) and draft duties by
	 * depot ({@link DepotDuties#toBytes}).
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
		PacketGuard.execute(minecraftServer, () -> start(minecraftServer, player, requestId, weekday, reportRoutes, draftRoutes, draftDuties));
	}

	private static void start(MinecraftServer minecraftServer, ServerPlayer player, int requestId, int weekday, Set<Long> reportRoutes, List<byte[]> draftRoutes, Map<Long, byte[]> draftDuties) {
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

		final AtomicBoolean stopped = new AtomicBoolean();
		final AtomicBoolean previous = RUNS.put(player.getUUID(), stopped);
		if (previous != null) {
			previous.set(true);
		}
		WAITING.incrementAndGet();
		EXECUTOR.execute(() -> {
			try {
				final long deadline = System.nanoTime() + TIME_LIMIT_NANOS;
				final DayRun dayRun = DayRun.run(dataset, dayStart, reportRoutes, Collections.emptyList(), () -> stopped.get() || System.nanoTime() > deadline);
				if (!stopped.get()) {
					final byte[] bytes = dayRun.toBytes();
					minecraftServer.execute(() -> {
						if (dayRun.cancelled) {
							sendStatus(player, requestId, STATUS_BUSY, ignoredDrafts);
						} else {
							sendResult(player, requestId, ignoredDrafts, bytes);
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
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeInt(requestId);
		packet.writeInt(status);
		packet.writeInt(ignoredDrafts);
		packet.writeInt(0);
		packet.writeInt(0);
		packet.writeByteArray(new byte[0]);
		Registry.sendToPlayer(player, PACKET_SIMULATE_RESULT, packet);
	}

	/**
	 * The report in parts small enough for a packet, which the client puts back together.
	 */
	private static void sendResult(ServerPlayer player, int requestId, int ignoredDrafts, byte[] bytes) {
		final List<byte[]> parts = split(bytes, PART_BYTES);
		for (int i = 0; i < parts.size(); i++) {
			final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			packet.writeInt(requestId);
			packet.writeInt(STATUS_RESULT);
			packet.writeInt(ignoredDrafts);
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
