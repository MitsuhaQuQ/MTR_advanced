package mtr.advanced;

import transitsim.plan.StopRules;
import transitsim.plan.ServiceExceptions;
import io.netty.buffer.Unpooled;
import mtr.data.RailwayData;
import mtr.data.Route;
import transitsim.plan.RouteTimetable;
import mtr.data.TransportMode;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceExceptionsTest {

	private static final int SERVICE = 7 * 60 * 60 * 1000;

	/**
	 * Four stops two minutes apart, stopping 30 seconds at the middle two, with no extra time.
	 */
	private static Route makeRoute() {
		final Route route = new Route(TransportMode.TRAIN);
		for (int i = 1; i <= 4; i++) {
			route.platformIds.add(new Route.RoutePlatform(i));
		}
		route.timetable.services.add(SERVICE);
		route.timetable.marginPercent = 0;
		return route;
	}

	private static long[][] getTimes() {
		return new long[][]{{0, 0}, {120000, 150000}, {270000, 300000}, {420000, 420000}};
	}

	private static ServiceExceptions.StopException exception(int run, int dwell, int pass, int overtake) {
		final ServiceExceptions.StopException stopException = new ServiceExceptions.StopException();
		stopException.runSeconds = run;
		stopException.dwellSeconds = dwell;
		stopException.pass = pass;
		stopException.overtake = overtake;
		return stopException;
	}

	private static List<RouteTimetable.StopPlan> plan(Route route, Map<Integer, ServiceExceptions.StopException> stopExceptions) {
		return StopRules.plan(route.platformIds, getTimes(), 0, 0, stopExceptions);
	}

	@Test
	void withoutExceptionsTheTableTimesStay() {
		final List<RouteTimetable.StopPlan> plans = plan(makeRoute(), null);
		assertEquals(120000, plans.get(1).arrivalMillis);
		assertEquals(150000, plans.get(1).departureMillis);
		assertEquals(420000, plans.get(3).arrivalMillis);
	}

	@Test
	void aRunningTimeCountsFromTheStopBeforeAndMovesTheRest() {
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(180, -1, -1, -1));
		final List<RouteTimetable.StopPlan> plans = plan(makeRoute(), stopExceptions);
		assertEquals(180000, plans.get(1).arrivalMillis);
		assertEquals(210000, plans.get(1).departureMillis);
		assertEquals(330000, plans.get(2).arrivalMillis);
		assertEquals(480000, plans.get(3).arrivalMillis);
	}

	@Test
	void aStopTimeKeepsTheTrainToItsDeparture() {
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(2, exception(-1, 90, -1, -1));
		final List<RouteTimetable.StopPlan> plans = plan(makeRoute(), stopExceptions);
		assertEquals(270000, plans.get(2).arrivalMillis);
		assertEquals(360000, plans.get(2).departureMillis);
		assertTrue(plans.get(2).isTimed);
		assertEquals(480000, plans.get(3).arrivalMillis);
	}

	@Test
	void passingSkipsTheStopTime() {
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(-1, -1, 1, -1));
		final List<RouteTimetable.StopPlan> plans = plan(makeRoute(), stopExceptions);
		assertEquals(120000, plans.get(1).departureMillis);
		assertEquals(240000, plans.get(2).arrivalMillis);
	}

	@Test
	void stoppingWhereTheRoutePassesAddsAStop() {
		final Route route = makeRoute();
		route.platformIds.get(1).pass = true;
		final long[][] times = {{0, 0}, {120000, 120000}, {240000, 270000}, {390000, 390000}};
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(-1, -1, 0, -1));
		final List<RouteTimetable.StopPlan> plans = StopRules.plan(route.platformIds, times, 0, 0, stopExceptions);
		assertEquals(120000 + ServiceExceptions.ADDED_STOP_MILLIS, plans.get(1).departureMillis);
		assertEquals(240000 + ServiceExceptions.ADDED_STOP_MILLIS, plans.get(2).arrivalMillis);
	}

	@Test
	void waitingCanBeSetOrClearedForOneService() {
		final Route route = makeRoute();
		route.platformIds.get(2).setOvertake(0, true);
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(-1, -1, -1, 1));
		stopExceptions.put(2, exception(-1, -1, -1, 0));
		final List<RouteTimetable.StopPlan> plans = plan(route, stopExceptions);
		assertTrue(plans.get(1).overtake);
		assertFalse(plans.get(2).overtake);
	}

	@Test
	void exceptionsSurviveSavingAndSending() throws IOException {
		final Route route = makeRoute();
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(1, exception(180, 45, -1, 1));
		route.timetable.exceptions.put(SERVICE, stopExceptions);
		route.addTable(0);
		route.timetable.getTable(1).services.add(SERVICE + 60000);
		final Map<Integer, ServiceExceptions.StopException> otherExceptions = new HashMap<>();
		otherExceptions.put(2, exception(-1, -1, 1, -1));
		route.timetable.getTable(1).exceptions.put(SERVICE + 60000, otherExceptions);

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(route.messagePackLength());
		route.toMessagePack(packer);
		packer.close();
		final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Route saved = new Route(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext());
		checkExceptions(saved);

		final List<FriendlyByteBuf> packets = new ArrayList<>();
		route.setTimetable(packets::add);
		final FriendlyByteBuf packet = packets.get(0);
		packet.readLong();
		packet.readUtf();
		final Route received = makeRoute();
		received.update(packet.readUtf(), packet);
		checkExceptions(received);

		final FriendlyByteBuf fullPacket = new FriendlyByteBuf(Unpooled.buffer());
		route.writePacket(fullPacket);
		checkExceptions(new Route(fullPacket));
	}

	private static void checkExceptions(Route route) {
		final ServiceExceptions.StopException stopException = route.timetable.exceptions.get(SERVICE).get(1);
		assertEquals(180, stopException.runSeconds);
		assertEquals(45, stopException.dwellSeconds);
		assertEquals(-1, stopException.pass);
		assertEquals(1, stopException.overtake);
		assertEquals(1, route.timetable.getTable(1).exceptions.get(SERVICE + 60000).get(2).pass);
	}

	@Test
	void exceptionsGoWithTheirServicesAndStops() {
		final Route route = makeRoute();
		final Map<Integer, ServiceExceptions.StopException> stopExceptions = new HashMap<>();
		stopExceptions.put(2, exception(-1, 60, -1, -1));
		stopExceptions.put(3, exception(-1, -1, -1, -1));
		route.timetable.exceptions.put(SERVICE, stopExceptions);
		route.timetable.exceptions.put(SERVICE + 1, new HashMap<>(stopExceptions));
		route.timetable.sanitize();
		assertNull(route.timetable.exceptions.get(SERVICE + 1));
		assertFalse(route.timetable.exceptions.get(SERVICE).containsKey(3));

		// The second stop is taken out, so the exception at the third stop moves to the second
		final List<Route.RoutePlatform> oldStops = new ArrayList<>(route.platformIds);
		route.platformIds.remove(1);
		ServiceExceptions.remapStops(route.timetable, route.platformIds, oldStops);
		assertEquals(60, route.timetable.exceptions.get(SERVICE).get(1).dwellSeconds);
	}
}
