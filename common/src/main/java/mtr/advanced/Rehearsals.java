package mtr.advanced;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.SecurityConfig;
import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.packet.IPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import transitsim.plan.DutyShapes;
import transitsim.sim.Rehearsal;
import transitsim.sim.SimDataset;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The rehearsal of each depot's plan (2026-11-15, user: rehearse whether the timetable and duties can run, warn while
 * planning, don't force them to be on time): when a depot's duties or the timetables of its routes change and then stay so
 * for a little while, its routes' part of the world is simulated for today in the background ({@link Rehearsal}), and the
 * warnings go to every player for the advanced editor's sync panel. Nothing in the plan or the game changes. Switched by
 * {@code rehearsal} in {@code mtr_security.json}.
 */
public final class Rehearsals implements IPacket {

	/**
	 * A depot's plan is looked at no more often than this.
	 */
	private static final long CHECK_MILLIS = 15000;
	/**
	 * A plan changed is rehearsed once it has stayed so this long, so a player's run of edits is rehearsed once.
	 */
	private static final long SETTLE_MILLIS = 10000;
	private static final long TIME_LIMIT_NANOS = 120L * 1000000000L;
	private static final int MAX_LENGTH = 512;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR-Rehearsal");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private static final Map<Long, State> STATES = new HashMap<>();

	private Rehearsals() {
	}

	/**
	 * On the server's thread, now and then for each depot: rehearses its plan if it changed and settled, and sends the
	 * warnings again now and then for players who joined since.
	 */
	public static void check(RailwayData railwayData, Level world, Depot depot, boolean resend) {
		final MinecraftServer server = world.getServer();
		if (server == null) {
			return;
		}
		final State state = STATES.computeIfAbsent(depot.id, key -> new State());
		if (!SecurityConfig.rehearsal) {
			if (!depot.rehearsal.isEmpty()) {
				depot.rehearsal = Collections.emptyList();
				sendS2C(world, depot.id, depot.rehearsal);
			}
			state.ranSignature = 0;
			return;
		}
		if (resend && !depot.rehearsal.isEmpty()) {
			sendS2C(world, depot.id, depot.rehearsal);
		}
		final long now = System.currentTimeMillis();
		if (state.running || now - state.checkedMillis < CHECK_MILLIS) {
			return;
		}
		state.checkedMillis = now;
		final long signature = signature(railwayData, depot);
		if (signature != state.signature) {
			state.signature = signature;
			state.changedMillis = now;
			return;
		}
		if (signature == state.ranSignature || now - state.changedMillis < SETTLE_MILLIS) {
			return;
		}
		final Set<Long> routes = new TreeSet<>(depot.routeIds);
		if (depot.duties.isEmpty() || routes.isEmpty()) {
			state.ranSignature = signature;
			if (!depot.rehearsal.isEmpty()) {
				depot.rehearsal = Collections.emptyList();
				sendS2C(world, depot.id, depot.rehearsal);
			}
			return;
		}
		// The world is read here on the server's thread; the rehearsal only has its own copy
		final SimDataset dataset = SimDataset.fromBytes(SimExport.build(railwayData));
		final long dayStart = DutyShapes.getDayStart(now);
		state.running = true;
		final AtomicBoolean stopped = new AtomicBoolean();
		state.stopped = stopped;
		EXECUTOR.execute(() -> {
			List<String> lines = null;
			try {
				final long deadline = System.nanoTime() + TIME_LIMIT_NANOS;
				final List<Rehearsal.Warning> warnings = Rehearsal.run(dataset, dayStart, routes, () -> stopped.get() || System.nanoTime() > deadline);
				if (warnings != null) {
					lines = new ArrayList<>();
					for (final Rehearsal.Warning warning : warnings) {
						lines.add(warning.encode());
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			final List<String> result = lines;
			server.execute(() -> {
				state.running = false;
				// Rehearsed whether it finished or not, so a plan too big for the time isn't tried again and again
				state.ranSignature = signature;
				if (result == null) {
					System.out.println("Minecraft Transit Railway: the rehearsal of depot " + depot.id + " didn't finish in time");
					return;
				}
				if (!result.equals(depot.rehearsal)) {
					depot.rehearsal = result;
					sendS2C(world, depot.id, result);
					System.out.println("Minecraft Transit Railway: the rehearsal of depot " + depot.id + " gives " + result.size() + " warnings");
				}
			});
		});
	}

	/**
	 * @return what the rehearsal of a depot depends on: its duties, and its routes' stops and timetables
	 */
	private static long signature(RailwayData railwayData, Depot depot) {
		long signature = Arrays.hashCode(depot.duties.toBytes());
		for (final long routeId : depot.routeIds) {
			final Route route = railwayData.dataCache.routeIdMap.get(routeId);
			signature = signature * 31 + routeId;
			if (route != null) {
				signature = signature * 31 + route.timetable.revision;
				signature = signature * 31 + route.platformIds.size();
			}
		}
		return signature == 0 ? 1 : signature;
	}

	public static void sendS2C(Level world, long depotId, List<String> warnings) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		final int count = Math.min(warnings.size(), Rehearsal.MAX_WARNINGS);
		packet.writeInt(count);
		warnings.stream().limit(count).forEach(line -> packet.writeUtf(line.length() > MAX_LENGTH ? line.substring(0, MAX_LENGTH) : line));
		world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_REHEARSAL, packet));
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		final int count = packet.readInt();
		if (count < 0 || count > Rehearsal.MAX_WARNINGS) {
			throw new IllegalArgumentException("Too many warnings: " + count);
		}
		final List<String> warnings = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			warnings.add(packet.readUtf(MAX_LENGTH));
		}
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(depotId);
			if (depot != null) {
				depot.rehearsal = warnings;
			}
		});
	}

	private static final class State {
		private long signature;
		private long ranSignature;
		private long changedMillis;
		private long checkedMillis;
		private boolean running;
		private AtomicBoolean stopped;
	}
}
