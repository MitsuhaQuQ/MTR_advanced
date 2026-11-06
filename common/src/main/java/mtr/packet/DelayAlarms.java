package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.client.ClientData;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Siding;
import mtr.data.TrainServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import transitsim.plan.Contingency;
import transitsim.sim.DelayRules;
import transitsim.sim.TrainService;

import java.util.*;

/**
 * A depot's trains on duties that are late or stuck with no contingency plan on for them (D2): worked out on the server
 * by the component's {@link DelayRules}, written to the log when a train's reason changes, and sent to the clients for the
 * advanced editor, where a dispatcher starts a plan or sets what is done with the train.
 */
public final class DelayAlarms implements IPacket {

	private static final int MAX_ENTRIES = 64;
	private static final int MAX_LENGTH = 256;

	private DelayAlarms() {
	}

	/**
	 * What one pass over the depots looks up once for each route, as many trains run the same routes.
	 */
	public static final class Lookups {

		private final Map<Long, List<Long>> anchorsByRoute = new HashMap<>();
		private final Map<Long, Long> anchorByRoute = new HashMap<>();
		private Map<Long, List<Long>> stationPlatforms;
		/**
		 * The trains to be told other platforms to pass than they pass now (D3), found by the pass, for the host to tell them.
		 */
		public final Map<TrainServer, Set<Long>> skips = new LinkedHashMap<>();

		private Collection<Long> getStationPlatforms(DataCache dataCache, long stationId) {
			if (stationPlatforms == null) {
				stationPlatforms = new HashMap<>();
				dataCache.platformIdToStation.forEach((platformId, station) -> stationPlatforms.computeIfAbsent(station.id, key -> new ArrayList<>()).add(platformId));
			}
			return stationPlatforms.get(stationId);
		}
	}

	/**
	 * @param depotSidings the depot's sidings
	 * @param deadlocked   the trains in a circle of trains waiting on each other
	 * @return each train reported, as {@code <train> <siding> <slot> <route> <reason> <late since> <waiting since> <plan>
	 * <system>}: the moments it has been late and waiting since, or 0, which stay the same while nothing changes, so the
	 * list is only sent again when something does; the plan one waiting for a dispatcher, or 0; the system the anchor of
	 * its route's rail system; then 1 if it passes stations now (D3), else 0. After a {@code |}, each train a plan or a
	 * dispatcher's setting is on for, the same, the plan 0 for what a dispatcher set. The trains to be told which platforms
	 * to pass are put in {@link Lookups#skips}.
	 */
	public static List<String> collect(Collection<Siding> depotSidings, DataCache dataCache, Set<Long> deadlocked, mtr.data.ContingencySystems systems, transitsim.network.PlatformGraph platformGraph, Lookups lookups) {
		final List<String> alarms = new ArrayList<>();
		final List<String> handled = new ArrayList<>();
		depotSidings.forEach(siding -> siding.forEachTrain(train -> {
			final TrainService<?> service = train.getService();
			final Set<Long> skipping = service.getSkipPlatforms();
			if (!service.isOnDuty()) {
				// What it was told to pass ends with its duty
				if (!skipping.isEmpty()) {
					lookups.skips.put(train, Collections.emptySet());
				}
				return;
			}
			final long lateSince = service.getLateSinceMillis();
			final long waitingSince = train.getWaitingSinceMillis();
			final long lateMillis = service.getLateMillis();
			final long waitingMillis = train.getWaitingMillis();
			// The plans of the rail system its route is in, whichever depot it comes from; all the systems rails joined into
			// its network, none of them lost, the lowest anchor first
			final long routeId = service.getTripRouteId();
			final mtr.data.Route route = dataCache.routeIdMap.get(routeId);
			final List<Long> anchors = lookups.anchorsByRoute.computeIfAbsent(routeId, key -> systems.anchorsForRoute(route, platformGraph));
			final List<transitsim.plan.Contingencies> contingencyList = new ArrayList<>();
			anchors.forEach(eachAnchor -> contingencyList.add(systems.systems.get(eachAnchor)));
			final boolean isDeadlocked = deadlocked.contains(train.id) && waitingMillis > 0;
			final DelayRules.Outcome outcome = DelayRules.evaluate(contingencyList, train.id, routeId, lateMillis, waitingMillis, isDeadlocked);
			// The stations its plan passes, while it is late enough (D3)
			final Set<Long> skips = DelayRules.getSkipPlatforms(contingencyList, outcome, train.id, routeId, lateMillis, waitingMillis, isDeadlocked, !skipping.isEmpty(), stationId -> lookups.getStationPlatforms(dataCache, stationId));
			if (!skips.equals(skipping)) {
				lookups.skips.put(train, skips);
			}
			if (outcome.alarm == null && outcome.contingency == null) {
				return;
			}
			// Only for the trains listed, and once for each route
			final long anchor = outcome.systemIndex >= 0 ? anchors.get(outcome.systemIndex) : lookups.anchorByRoute.computeIfAbsent(routeId, key -> route == null || route.platformIds.isEmpty() ? 0L : systems.anchorOf(platformGraph.getNetworkOf(route.getFirstPlatformId()), platformGraph));
			final String entry = train.id + " " + siding.id + " " + service.getDutySlot() + " " + routeId + " ";
			if (outcome.alarm != null) {
				alarms.add(entry + outcome.alarm + " " + lateSince + " " + waitingSince + " " + outcome.waitingPlanId + " " + anchor + " " + (skipping.isEmpty() ? 0 : 1));
			} else {
				handled.add(entry + (outcome.reason == null ? "-" : outcome.reason.toString()) + " " + lateSince + " " + waitingSince + " " + (contingencyList.get(outcome.systemIndex).adhoc.containsKey(train.id) ? 0 : outcome.contingency.id) + " " + anchor + " " + (skipping.isEmpty() ? 0 : 1));
			}
		}));
		alarms.sort(String::compareTo);
		handled.sort(String::compareTo);
		alarms.add("|");
		alarms.addAll(handled);
		return alarms;
	}

	/**
	 * Writes a line to the log for each train whose reason changed since last time.
	 *
	 * @param before train → reason, as last written, updated
	 */
	public static void log(Depot depot, List<String> alarms, Map<Long, String> before) {
		final Map<Long, String> now = new HashMap<>();
		boolean handled = false;
		for (final String alarm : alarms) {
			if ("|".equals(alarm)) {
				handled = true;
				continue;
			}
			final String[] parts = alarm.split(" ");
			now.put(Long.parseLong(parts[0]), (handled ? "plan " + parts[7] + " " : "") + parts[4]);
		}
		now.forEach((trainId, reason) -> {
			if (!reason.equals(before.get(trainId))) {
				System.out.println("Minecraft Transit Railway: depot " + depot.id + " train " + trainId + (reason.startsWith("plan ") ? " falls under " : " reported, ") + reason);
			}
		});
		before.clear();
		before.putAll(now);
	}

	public static void sendS2C(Level world, long depotId, List<String> alarms) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(depotId);
		// The server's clock, so the clients work out how late from the moments in the list whatever their own clock says
		packet.writeLong(System.currentTimeMillis());
		final int count = Math.min(alarms.size(), MAX_ENTRIES);
		packet.writeInt(count);
		alarms.stream().limit(count).forEach(entry -> packet.writeUtf(entry.length() > MAX_LENGTH ? entry.substring(0, MAX_LENGTH) : entry));
		world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_DELAY_ALARMS, packet));
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
		final long clockOffset = packet.readLong() - System.currentTimeMillis();
		final int count = packet.readInt();
		if (count < 0 || count > MAX_ENTRIES) {
			throw new IllegalArgumentException("Too many entries: " + count);
		}
		final List<String> alarms = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			alarms.add(packet.readUtf(MAX_LENGTH));
		}
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			final Depot depot = ClientData.DATA_CACHE.depotIdMap.get(depotId);
			if (depot != null) {
				depot.clientAlarms = alarms;
				depot.clientAlarmClockOffset = clockOffset;
			}
		});
	}

	/**
	 * @return the reason's name for a reason written in an alarm, or null
	 */
	public static Contingency.Reason parseReason(String text) {
		try {
			return Contingency.Reason.valueOf(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
