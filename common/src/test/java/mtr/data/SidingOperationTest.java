package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A siding's routes and fleet: kept in saves, packets and updates alike, an old save keeping what its siding held, and
 * the depot's routes narrowed to those of the siding.
 */
class SidingOperationTest {

	private static Siding siding() {
		return new Siding(TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);
	}

	private static Map<String, Value> saved(Siding siding) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(siding.messagePackLength());
		siding.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}

	@Test
	void aNewSidingHoldsAnyNumberOfTrains() {
		final Siding siding = siding();
		assertEquals(-1, siding.getTrainLimit());
		assertEquals(-1, siding.getFleetSize());
		assertTrue(siding.getRouteIds().isEmpty());
	}

	@Test
	void routesAndFleetAreSavedSentAndUpdated() throws IOException {
		final Siding siding = siding();
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		siding.setOperation(Arrays.asList(7L, 3L), 12, sent::add);
		assertEquals(new TreeSet<>(Arrays.asList(3L, 7L)), siding.getRouteIds());
		assertEquals(12, siding.getFleetSize());

		final Siding read = new Siding(saved(siding));
		assertEquals(siding.getRouteIds(), read.getRouteIds());
		assertEquals(12, read.getFleetSize());

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		siding.writePacket(packet);
		final Siding received = new Siding(packet);
		assertEquals(siding.getRouteIds(), received.getRouteIds());
		assertEquals(12, received.getFleetSize());

		final Siding updated = siding();
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertEquals(siding.getRouteIds(), updated.getRouteIds());
		assertEquals(12, updated.getFleetSize());
	}

	@Test
	void legsKeptAsMeasuredAreSavedSentAndUpdated() throws IOException {
		final Siding siding = siding();
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		final DepotLegs legs = new DepotLegs(11, Collections.singletonMap(11L, 90000L), Collections.singletonMap(12L, 40000L));
		siding.setRecordedLegs(legs, sent::add);

		final Siding read = new Siding(saved(siding));
		assertEquals(legs.outMillis, read.getRecordedLegs().outMillis);
		assertEquals(legs.backMillis, read.getRecordedLegs().backMillis);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		siding.writePacket(packet);
		assertEquals(legs.outMillis, new Siding(packet).getRecordedLegs().outMillis);

		final Siding updated = siding();
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertEquals(legs.backMillis, updated.getRecordedLegs().backMillis);
	}

	@Test
	void aSidingWithNoWayOutToAPlatformDoesntStartTrainsThere() {
		final Siding siding = siding();
		final Platform reachable = new Platform(21, TransportMode.TRAIN, new BlockPos(100, 64, 0), new BlockPos(120, 64, 0));
		final Platform cutOff = new Platform(22, TransportMode.TRAIN, new BlockPos(200, 64, 0), new BlockPos(220, 64, 0));
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(Arrays.asList(reachable, cutOff)), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		dataCache.platformIdMap.put(21L, reachable);
		dataCache.platformIdMap.put(22L, cutOff);
		siding.setRecordedLegs(DepotLegs.NONE.withUnreachable(Collections.singleton(22L), Collections.singleton(21L)), packet -> {
		});
		// Out: only where there is a way, by estimate as not measured
		assertEquals(Collections.singleton(21L), LineDuties.sidingLinks(siding, Arrays.asList(21L, 22L), dataCache).keySet());
		// Back: no way from 21, told to the planner
		assertEquals(Long.valueOf(transitsim.planning.OperationNetwork.UNREACHABLE), LineDuties.sidingBackLinks(siding, Arrays.asList(21L, 22L), dataCache).get(21L));
		assertTrue(LineDuties.sidingBackLinks(siding, Arrays.asList(21L, 22L), dataCache).get(22L) > 0);
	}

	@Test
	void anOldSaveKeepsWhatItsSidingHeld() throws IOException {
		final Siding siding = siding();
		siding.setUnlimitedTrains(false, 4, false, 0, Train.ACCELERATION_DEFAULT, 0, false, packet -> {
		});
		final Map<String, Value> map = saved(siding);
		map.remove("fleet_size");
		map.remove("route_ids");
		map.remove("recorded_depot_legs");
		final Siding read = new Siding(map);
		assertTrue(read.getRecordedLegs().outMillis.isEmpty(), "an old siding plans by estimates");
		assertEquals(5, read.getFleetSize(), "an old siding's fleet is what it held");
		assertTrue(read.getRouteIds().isEmpty());
	}

	@Test
	void aSidingRunsOnlyTheRoutesItIsGiven() {
		final Route up = new Route(10, TransportMode.TRAIN), down = new Route(11, TransportMode.TRAIN);
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.routeIds.add(10L);
		depot.routeIds.add(11L);
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(Arrays.asList(up, down)), new HashSet<>(Collections.singletonList(depot)), new HashSet<>());
		dataCache.routeIdMap.put(up.id, up);
		dataCache.routeIdMap.put(down.id, down);

		final Siding siding = siding();
		assertEquals(Arrays.asList(10L, 11L), depot.getSidingRouteIds(siding, dataCache));
		siding.setOperation(Collections.singletonList(11L), -1, packet -> {
		});
		assertEquals(Collections.singletonList(11L), depot.getSidingRouteIds(siding, dataCache));
	}
}
