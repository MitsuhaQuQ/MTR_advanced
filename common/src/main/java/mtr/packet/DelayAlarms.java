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
	 * @param deadlocked the trains in a circle of trains waiting on each other
	 * @return each train reported, as {@code <train> <siding> <slot> <route> <reason> <late seconds> <waiting seconds> <plan>
	 * <system>}, the plan being one waiting for a dispatcher, or 0, and the system the anchor of its route's rail system;
	 * and, after a {@code |}, each train a plan or a dispatcher's setting is on for, the same, the plan 0 for what a
	 * dispatcher set
	 */
	public static List<String> collect(Depot depot, Set<Siding> sidings, DataCache dataCache, Set<Long> deadlocked, mtr.data.ContingencySystems systems, transitsim.network.PlatformGraph platformGraph) {
		final List<String> alarms = new ArrayList<>();
		final List<String> handled = new ArrayList<>();
		sidings.forEach(siding -> {
			if (dataCache.sidingIdToDepot.get(siding.id) != depot) {
				return;
			}
			siding.forEachTrain(train -> {
				final TrainService<?> service = train.getService();
				if (!service.isOnDuty()) {
					return;
				}
				final long lateMillis = service.getLateMillis();
				final long waitingMillis = train.getWaitingMillis();
				// The plans of the rail system its route is in, whichever depot it comes from
				final mtr.data.Route route = dataCache.routeIdMap.get(service.getTripRouteId());
				// All the systems rails joined into its network, none of them lost, the lowest anchor first
				final List<Long> anchors = systems.anchorsForRoute(route, platformGraph);
				final List<transitsim.plan.Contingencies> contingencyList = new ArrayList<>();
				anchors.forEach(eachAnchor -> contingencyList.add(systems.systems.get(eachAnchor)));
				final DelayRules.Outcome outcome = DelayRules.evaluate(contingencyList, train.id, service.getTripRouteId(), lateMillis, waitingMillis, deadlocked.contains(train.id) && waitingMillis > 0);
				final long anchor = outcome.systemIndex >= 0 ? anchors.get(outcome.systemIndex) : route == null || route.platformIds.isEmpty() ? 0 : systems.anchorOf(platformGraph.getNetworkOf(route.getFirstPlatformId()), platformGraph);
				final transitsim.plan.Contingencies contingencies = outcome.systemIndex >= 0 ? contingencyList.get(outcome.systemIndex) : null;
				final String entry = train.id + " " + siding.id + " " + service.getDutySlot() + " " + service.getTripRouteId() + " ";
				if (outcome.alarm != null) {
					alarms.add(entry + outcome.alarm + " " + lateMillis / 1000 + " " + waitingMillis / 1000 + " " + outcome.waitingPlanId + " " + anchor);
				} else if (outcome.contingency != null) {
					handled.add(entry + (outcome.reason == null ? "-" : outcome.reason.toString()) + " " + lateMillis / 1000 + " " + waitingMillis / 1000 + " " + (contingencies.adhoc.containsKey(train.id) ? 0 : outcome.contingency.id) + " " + anchor);
				}
			});
		});
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
		final int count = Math.min(alarms.size(), MAX_ENTRIES);
		packet.writeInt(count);
		alarms.stream().limit(count).forEach(entry -> packet.writeUtf(entry.length() > MAX_LENGTH ? entry.substring(0, MAX_LENGTH) : entry));
		world.players().forEach(player -> Registry.sendToPlayer((ServerPlayer) player, PACKET_DELAY_ALARMS, packet));
	}

	public static void receiveS2C(FriendlyByteBuf packet) {
		final long depotId = packet.readLong();
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
