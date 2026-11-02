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
	void everyTableSurvivesSaving() throws IOException {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		route.timetable.enabled = true;
		route.timetable.name = "weekdays";
		route.timetable.weekdays = 0b0011111;
		route.timetable.services.add(1000);
		route.platformIds.get(1).setArrival(100);

		assertEquals(1, route.addTable(0));
		final RouteTimetable weekend = route.timetable.getTable(1);
		weekend.name = "weekends";
		weekend.weekdays = 0b1100000;
		weekend.marginPercent = 20;
		weekend.services.addAll(Arrays.asList(2000, 3000));
		route.platformIds.get(1).setArrival(1, 200);
		route.platformIds.get(1).setDeparture(1, 260);
		route.platformIds.get(1).setOvertake(1, true);

		final Route newRoute = saveAndLoad(route);
		assertEquals(2, newRoute.timetable.getTableCount());
		assertEquals("weekdays", newRoute.timetable.name);
		assertEquals(0b0011111, newRoute.timetable.weekdays);
		assertEquals(100, newRoute.platformIds.get(1).getArrival());
		assertEquals(-1, newRoute.platformIds.get(1).getDeparture());
		assertFalse(newRoute.platformIds.get(1).overtake);

		final RouteTimetable newWeekend = newRoute.timetable.getTable(1);
		assertEquals("weekends", newWeekend.name);
		assertEquals(0b1100000, newWeekend.weekdays);
		assertEquals(20, newWeekend.marginPercent);
		assertEquals(weekend.services, newWeekend.services);
		assertEquals(200, newRoute.platformIds.get(1).getArrival(1));
		assertEquals(260, newRoute.platformIds.get(1).getDeparture(1));
		assertTrue(newRoute.platformIds.get(1).getOvertake(1));
	}

	@Test
	void aNewTableStartsFromTheTimesOfAnotherOne() {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.get(0).setArrival(100);
		route.timetable.marginPercent = 15;
		route.timetable.services.add(1000);

		final int table = route.addTable(0);
		assertEquals(100, route.platformIds.get(0).getArrival(table));
		assertEquals(15, route.timetable.getTable(table).marginPercent);
		assertTrue(route.timetable.getTable(table).services.isEmpty());

		route.platformIds.get(0).setArrival(table, 200);
		assertEquals(100, route.platformIds.get(0).getArrival());
	}

	@Test
	void removingATableRemovesItsTimes() {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.addTable(0);
		route.addTable(0);
		route.platformIds.get(0).setArrival(1, 100);
		route.platformIds.get(0).setArrival(2, 200);

		route.removeTable(1);
		assertEquals(2, route.timetable.getTableCount());
		assertEquals(200, route.platformIds.get(0).getArrival(1));

		route.removeTable(0);
		assertEquals(2, route.timetable.getTableCount());
	}

	@Test
	void duplicatingARouteCopiesEveryTable() {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.addTable(0);
		route.timetable.getTable(1).services.add(2000);
		route.platformIds.get(0).setArrival(1, 100);

		final Route copy = route.duplicate();
		assertEquals(2, copy.timetable.getTableCount());
		assertEquals(2000, (int) copy.timetable.getTable(1).services.get(0));
		assertEquals(100, copy.platformIds.get(0).getArrival(1));

		copy.platformIds.get(0).setArrival(1, 300);
		assertEquals(100, route.platformIds.get(0).getArrival(1));
	}

	@Test
	void servicesCarryingOnSurviveSaving() throws IOException {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.timetable.services.addAll(Arrays.asList(1000, 2000));
		route.timetable.links.put(1000, new RouteTimetable.ServiceLink(55, 9000, 3));
		route.addTable(0);
		route.timetable.getTable(1).services.add(4000);
		route.timetable.getTable(1).links.put(4000, new RouteTimetable.ServiceLink(66, 8000, -1));

		final Route newRoute = saveAndLoad(route);
		final RouteTimetable.ServiceLink link = newRoute.timetable.links.get(1000);
		assertEquals(55, link.routeId);
		assertEquals(9000, link.service);
		assertEquals(3, link.fromStop);
		assertEquals(66, newRoute.timetable.getTable(1).links.get(4000).routeId);
		assertEquals(-1, newRoute.timetable.getTable(1).links.get(4000).fromStop);

		final Route copy = route.duplicate();
		assertEquals(9000, copy.timetable.links.get(1000).service);
		assertEquals(8000, copy.timetable.getTable(1).links.get(4000).service);
	}

	@Test
	void theStopJoiningALoopIsSaved() throws IOException {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.timetable.services.add(1000);
		route.timetable.links.put(1000, new RouteTimetable.ServiceLink(55, 9000, -1, 4));

		assertEquals(4, saveAndLoad(route).timetable.links.get(1000).toStop);
	}

	@Test
	void linksOfRemovedServicesGoAway() {
		final RouteTimetable timetable = new RouteTimetable();
		timetable.services.add(1000);
		timetable.links.put(1000, new RouteTimetable.ServiceLink(55, 9000, -1));
		timetable.links.put(2000, new RouteTimetable.ServiceLink(55, 9000, -1));
		timetable.sanitize();

		assertEquals(1, timetable.links.size());
		assertTrue(timetable.links.containsKey(1000));
	}

	@Test
	void theJoinStopOfALoopSurvivesSaving() throws IOException {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		route.platformIds.add(new Route.RoutePlatform(3));
		route.platformIds.add(new Route.RoutePlatform(1));
		route.timetable.entryPlatformId = 3;

		final Route newRoute = saveAndLoad(route);
		assertEquals(2, newRoute.getLoopEntryStop());
	}

	@Test
	void onlyALoopCanBeJoinedPartOfTheWayRound() {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		route.platformIds.add(new Route.RoutePlatform(3));
		route.timetable.entryPlatformId = 2;
		assertEquals(0, route.getLoopEntryStop());

		route.platformIds.add(new Route.RoutePlatform(1));
		assertEquals(1, route.getLoopEntryStop());

		route.platformIds.get(1).pass = true;
		assertEquals(0, route.getLoopEntryStop());
		route.platformIds.get(1).pass = false;

		route.timetable.entryPlatformId = 99;
		assertEquals(0, route.getLoopEntryStop());
	}

	@Test
	void timesGoWithTheirPlatformWhenStopsChange() {
		final Route route = new Route(TransportMode.TRAIN);
		route.platformIds.add(new Route.RoutePlatform(1));
		route.platformIds.add(new Route.RoutePlatform(2));
		route.platformIds.add(new Route.RoutePlatform(3));
		route.addTable(0);
		route.platformIds.get(1).setArrival(100);
		route.platformIds.get(1).setDeparture(1, 200);
		route.platformIds.get(1).setOvertake(1, true);
		route.platformIds.remove(0);
		route.platformIds.add(new Route.RoutePlatform(4));

		route.setPlatformIds(packet -> {
			packet.readLong();
			packet.readUtf();
			route.update(packet.readUtf(), packet);
		});

		assertEquals(3, route.platformIds.size());
		assertEquals(2, route.platformIds.get(0).platformId);
		assertEquals(100, route.platformIds.get(0).getArrival());
		assertEquals(200, route.platformIds.get(0).getDeparture(1));
		assertTrue(route.platformIds.get(0).getOvertake(1));
		assertEquals(-1, route.platformIds.get(2).getArrival());
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
