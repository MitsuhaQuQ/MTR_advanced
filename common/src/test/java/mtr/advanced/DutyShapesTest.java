package mtr.advanced;

import transitsim.plan.DutyShapes;
import transitsim.plan.DepotDuties;
import transitsim.plan.ServiceExceptions;
import io.netty.buffer.Unpooled;
import mtr.data.Route;
import mtr.data.TimetableConfig;
import mtr.data.TransportMode;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

class DutyShapesTest {

	private static final int HOUR = 3600000;
	private final Map<Long, Route> routes = new HashMap<>();
	private final LongFunction<Route> routeLookup = routes::get;

	@BeforeEach
	void setUp() {
		TimetableConfig.set("UTC", 0);
		routes.put(1L, makeRoute(1, 11, 12, 13, 14));
		routes.put(2L, makeRoute(2, 14, 13, 12, 11));
	}

	private static Route makeRoute(long id, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final long platformId : platformIds) {
			route.platformIds.add(new Route.RoutePlatform(platformId));
		}
		route.timetable.enabled = true;
		route.timetable.services.add(8 * HOUR);
		route.timetable.services.add(9 * HOUR);
		route.timetable.sanitize();
		return route;
	}

	private static DepotDuties.DayDuty day(boolean stay, int nextSlot, DepotDuties.Segment... segments) {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.stayOnLine = stay;
		day.nextSlot = nextSlot;
		day.segments.addAll(Arrays.asList(segments));
		return day;
	}

	private static DepotDuties.Slot slot(DepotDuties.DayDuty day) {
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		return slot;
	}

	@Test
	void sharesTheShapeOfDutiesRunningTheSameWay() {
		final List<DepotDuties.Segment> morning = Arrays.asList(new DepotDuties.Segment(1, 8 * HOUR, 0, -1), new DepotDuties.Segment(2, 9 * HOUR, 0, -1));
		final List<DepotDuties.Segment> later = Arrays.asList(new DepotDuties.Segment(1, 9 * HOUR, 0, -1), new DepotDuties.Segment(2, 8 * HOUR, 0, -1));
		assertEquals(DutyShapes.getKey(morning, routeLookup, 0, 0, false), DutyShapes.getKey(later, routeLookup, 0, 0, false));
		assertNotEquals(DutyShapes.getKey(morning, routeLookup, 0, 0, false), DutyShapes.getKey(morning, routeLookup, 0, 0, true));
		assertNotEquals(DutyShapes.getKey(morning, routeLookup, 0, 0, false), DutyShapes.getKey(morning, routeLookup, 0, 14, false));
		assertNull(DutyShapes.getKey(Collections.singletonList(new DepotDuties.Segment(9, 0, 0, -1)), routeLookup, 0, 0, false));
	}

	@Test
	void passesWhereTheServiceItselfPasses() {
		final Route route = routes.get(1L);
		route.platformIds.get(1).pass = true;
		final ServiceExceptions.StopException stopHere = new ServiceExceptions.StopException();
		stopHere.pass = 0;
		final ServiceExceptions.StopException passHere = new ServiceExceptions.StopException();
		passHere.pass = 1;
		final Map<Integer, ServiceExceptions.StopException> exceptions = new HashMap<>();
		exceptions.put(1, stopHere);
		exceptions.put(2, passHere);
		route.timetable.exceptions.put(9 * HOUR, exceptions);

		assertArrayEquals(new boolean[]{false, true, false, false}, DutyShapes.getPassFlags(route, new DepotDuties.Segment(1, 8 * HOUR, 0, -1), 0));
		assertArrayEquals(new boolean[]{false, false, true, false}, DutyShapes.getPassFlags(route, new DepotDuties.Segment(1, 9 * HOUR, 0, -1), 0));
		// Where the train joins or leaves it always stops
		assertArrayEquals(new boolean[]{false, false}, DutyShapes.getPassFlags(route, new DepotDuties.Segment(1, 9 * HOUR, 1, 2), 0));
		assertNotEquals(DutyShapes.getKey(Collections.singletonList(new DepotDuties.Segment(1, 8 * HOUR, 0, -1)), routeLookup, 0, 0, false), DutyShapes.getKey(Collections.singletonList(new DepotDuties.Segment(1, 9 * HOUR, 0, -1)), routeLookup, 0, 0, false));
	}

	@Test
	void findsTheTrainStayingOvernightForASlot() {
		final List<DepotDuties.Slot> slots = Arrays.asList(
				slot(day(true, 1, new DepotDuties.Segment(1, 8 * HOUR, 0, -1))),
				slot(day(false, -1, new DepotDuties.Segment(2, 9 * HOUR, 0, -1))),
				slot(day(true, -1, new DepotDuties.Segment(1, 9 * HOUR, 0, -1)))
		);
		assertEquals(0, DutyShapes.getOvernightSlot(slots, 1, 2));
		assertEquals(2, DutyShapes.getOvernightSlot(slots, 2, 2));
		assertEquals(-1, DutyShapes.getOvernightSlot(slots, 0, 2));
		// The slot a train takes over starts where that train is, the last stop of route 1
		assertTrue(DutyShapes.getSlotKey(slots, 1, 2, routeLookup).startsWith(DutyShapes.KEY_PREFIX + "14|"));
		assertTrue(DutyShapes.getSlotKey(slots, 0, 2, routeLookup).startsWith(DutyShapes.KEY_PREFIX + "0|"));
	}

	@Test
	void readsDutiesSavedBeforeOvernightChoices() {
		// The first format: no version, the mode first, and no next slot
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBoolean(true);
		packet.writeVarInt(1);
		packet.writeLong(100);
		packet.writeVarInt(1);
		packet.writeVarInt(1);
		packet.writeByte(DepotDuties.ALL_WEEKDAYS);
		packet.writeBoolean(true);
		packet.writeVarInt(1);
		packet.writeLong(1);
		packet.writeVarInt(8 * HOUR);
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		final byte[] bytes = new byte[packet.readableBytes()];
		packet.readBytes(bytes);

		final DepotDuties duties = new DepotDuties();
		duties.fromBytes(bytes);
		assertTrue(duties.diagramMode);
		final DepotDuties.DayDuty day = duties.getSlots(100).get(0).getDuty(3);
		assertTrue(day.stayOnLine);
		assertEquals(-1, day.nextSlot);
		assertEquals(8 * HOUR, day.segments.get(0).service);

		day.nextSlot = 0;
		final DepotDuties read = new DepotDuties();
		read.fromBytes(duties.toBytes());
		assertEquals(0, read.getSlots(100).get(0).getDuty(3).nextSlot);
	}
}
