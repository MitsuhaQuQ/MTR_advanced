package mtr.data;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTimetableSaveTest {

	private static Route saveAndLoad(Route route) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(route.messagePackLength());
		route.toMessagePack(packer);
		packer.close();

		final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Route newRoute = new Route(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext());
		return newRoute;
	}

	@Test
	void timetableSurvivesSaving() throws IOException {
		final Route route = new Route(1, TransportMode.TRAIN);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 12;
		route.timetable.services.addAll(Arrays.asList(1000, 2000, 3000));

		final Route newRoute = saveAndLoad(route);

		assertTrue(newRoute.timetable.enabled);
		assertEquals(12, newRoute.timetable.marginPercent);
		assertEquals(route.timetable.services, newRoute.timetable.services);
	}

	@Test
	void stopTimesSurviveSaving() throws IOException {
		final Route route = new Route(1, TransportMode.TRAIN);
		final Route.RoutePlatform stop1 = new Route.RoutePlatform(10);
		final Route.RoutePlatform stop2 = new Route.RoutePlatform(20);
		stop2.setArrival(120);
		stop2.setDeparture(180);
		stop2.overtake = true;
		route.platformIds.addAll(Arrays.asList(stop1, stop2));

		final Route newRoute = saveAndLoad(route);

		assertEquals(-1, newRoute.platformIds.get(0).getArrival());
		assertEquals(-1, newRoute.platformIds.get(0).getDeparture());
		assertFalse(newRoute.platformIds.get(0).overtake);
		assertEquals(120, newRoute.platformIds.get(1).getArrival());
		assertEquals(180, newRoute.platformIds.get(1).getDeparture());
		assertTrue(newRoute.platformIds.get(1).overtake);
	}

	@Test
	void ownColorSurvivesSaving() throws IOException {
		final Route route = new Route(1, TransportMode.TRAIN);
		route.setOwnColor(0xABCDEF);
		route.color = 0x123456;

		final Route newRoute = saveAndLoad(route);
		assertEquals(0xABCDEF, newRoute.getOwnColor());
		assertEquals(0x123456, newRoute.color);
		assertEquals(0xABCDEF, route.duplicate().getOwnColor());
	}

	@Test
	void routeWithoutATimetableStaysOff() throws IOException {
		final Route newRoute = saveAndLoad(new Route(1, TransportMode.TRAIN));

		assertFalse(newRoute.timetable.isActive());
		assertEquals(RouteTimetable.DEFAULT_MARGIN_PERCENT, newRoute.timetable.marginPercent);
	}
}
