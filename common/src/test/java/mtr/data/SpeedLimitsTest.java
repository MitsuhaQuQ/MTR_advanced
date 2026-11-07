package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.path.DeterministicPathFinder;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import transitsim.network.RailGraph;
import transitsim.plan.DepotDuties;
import transitsim.plan.TripPath;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Speed limits: the 45 km/h rails, the routes' speed limits written into the paths of their duties, and the vehicles'
 * highest speeds, saved, sent and updated.
 */
class SpeedLimitsTest {

	@Test
	void theNewRailTypeLeavesTheOthersAsTheyWere() {
		// Manual driving speeds are saved by these numbers
		assertEquals(0, RailType.WOODEN.ordinal());
		assertEquals(1, RailType.STONE.ordinal());
		assertEquals(3, RailType.IRON.ordinal());
		assertEquals(7, RailType.DIAMOND.ordinal());
		assertEquals(15, RailType.NONE.ordinal());
		assertEquals(RailType.STONE, Train.convertMaxManualSpeed(1));
		assertNull(Train.convertMaxManualSpeed(RailType.COPPER.ordinal()));
		assertEquals(45, RailType.COPPER.speedLimit);
		assertTrue(RailType.COPPER.canAccelerate);
	}

	@Test
	void aCopperRailIsSavedAndSentAsOne() throws IOException {
		final Rail rail = new Rail(new BlockPos(0, 64, 0), RailAngle.fromAngle(90), new BlockPos(0, 64, 40), RailAngle.fromAngle(270), RailType.COPPER, TransportMode.TRAIN);
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(packer);
		packer.close();
		assertEquals(RailType.COPPER, new Rail(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue())).railType);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		assertEquals(RailType.COPPER, new Rail(packet).railType);
	}

	@Test
	void aPathKeepsItsRoutesSpeedLimit() throws IOException {
		final Rail rail = new Rail(new BlockPos(0, 64, 0), RailAngle.fromAngle(90), new BlockPos(0, 64, 40), RailAngle.fromAngle(270), RailType.IRON, TransportMode.TRAIN);
		final PathData pathData = new PathData(rail, 5, 20, new BlockPos(0, 64, 0), new BlockPos(0, 64, 40), 1);
		// None, as old saves have it
		assertEquals(RailType.IRON.maxBlocksPerTick, pathData.getMaxSpeed());
		assertEquals(0, saved(pathData).speedCap);
		PathData.capSpeed(Collections.singletonList(pathData), 60);
		assertEquals(60 / 3.6F / 20, pathData.getMaxSpeed(), 1e-6);
		assertEquals(pathData.speedCap, saved(pathData).speedCap);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		pathData.writePacket(packet);
		assertEquals(pathData.speedCap, new PathData(packet).speedCap);
		// Copies keep it, and a limit above the rail's doesn't raise it
		assertEquals(pathData.speedCap, pathData.copy(5, 0).speedCap);
		PathData.capSpeed(Collections.singletonList(pathData), 200);
		assertEquals(RailType.IRON.maxBlocksPerTick, pathData.getMaxSpeed());
	}

	private static PathData saved(PathData pathData) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(pathData.messagePackLength());
		pathData.toMessagePack(packer);
		packer.close();
		final org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final PathData read = new PathData(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext());
		return read;
	}

	@Test
	void aRoutesSpeedLimitIsSavedSentAndUpdated() throws IOException {
		final Route route = new Route(9, TransportMode.TRAIN);
		route.speedLimit = 70;
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(route.messagePackLength());
		route.toMessagePack(packer);
		packer.close();
		final org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		assertEquals(70, new Route(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue())).speedLimit);
		assertFalse(unpacker.hasNext());
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		route.writePacket(packet);
		assertEquals(70, new Route(packet).speedLimit);

		// The update the editor sends, which times the route again
		final Route other = new Route(9, TransportMode.TRAIN);
		final long revision = other.timetable.revision;
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		route.setExtraData(sent::add);
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH);
		other.update(update.readUtf(SerializedDataBase.PACKET_STRING_READ_LENGTH), update);
		assertEquals(70, other.speedLimit);
		assertTrue(other.timetable.revision > revision);
	}

	@Test
	void aRoutesSpeedLimitIsOnItsOwnRailsOfADutyAndNotOnTheWayRound() {
		// The terminal of DutyTurnBackTest: down to platform A, round the turn back rail, up from platform B
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		addRail(rails, pos(0, -60), pos(0, -40), RailType.SIDING);
		addRail(rails, pos(0, -40), pos(0, 0), RailType.IRON);
		final Platform south = platform(dataCache, rails, 1, pos(0, 0), pos(0, 20));
		addRail(rails, pos(0, 20), pos(0, 200), RailType.IRON);
		addRail(rails, pos(0, 200), pos(-4, 240), RailType.IRON);
		addRail(rails, pos(0, 200), pos(4, 240), RailType.IRON);
		final Platform a = platform(dataCache, rails, 2, pos(-4, 240), pos(-4, 260));
		final Platform b = platform(dataCache, rails, 3, pos(4, 240), pos(4, 260));
		addRail(rails, pos(-4, 260), pos(0, 300), RailType.IRON);
		addRail(rails, pos(4, 260), pos(0, 300), RailType.IRON);
		addRail(rails, pos(0, 300), pos(0, 500), RailType.TURN_BACK);
		final Route down = route(dataCache, 10, south, a);
		route(dataCache, 11, b, south);
		down.speedLimit = 60;
		final Siding siding = new Siding(100, TransportMode.TRAIN, pos(0, -60), pos(0, -40), 20);
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.corner1 = new Tuple<>(-50, -100);
		depot.corner2 = new Tuple<>(50, -20);
		depot.routeIds.add(10L);
		depot.routeIds.add(11L);
		dataCache.depotIdMap.put(depot.id, depot);
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.segments.add(new DepotDuties.Segment(10, 8 * 3600 * 1000, 0, -1));
		day.segments.add(new DepotDuties.Segment(11, 8 * 3600 * 1000 + 600000, 0, -1));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		duties.sidings.put(siding.id, Collections.singletonList(slot));

		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		final List<String> failed = new ArrayList<>();
		final Map<Long, Map<String, TripPath<PathData>>> paths = depot.findDutyPaths(duties, dataCache, rails, graph, Collections.singleton(siding), false, failed::add);
		assertTrue(failed.isEmpty(), failed.toString());
		final List<PathData> path = paths.get(siding.id).values().iterator().next().path;
		final float cap = 60 / 3.6F / 20;
		boolean capped = false;
		for (final PathData pathData : path) {
			if (pathData.rail.railType == RailType.TURN_BACK || pathData.rail.railType == RailType.SIDING) {
				assertEquals(0, pathData.speedCap, "not on the way round or in the siding: " + pathData.startingPos + " " + pathData.getLastPos());
			}
			// The down route runs north from the south platform to platform A
			final boolean northwards = pathData.startingPos.getZ() < pathData.getLastPos().getZ();
			if (northwards && pathData.startingPos.getZ() >= 20 && pathData.getLastPos().getZ() <= 260 && pathData.getLastPos().getX() <= 0) {
				assertEquals(cap, pathData.speedCap, 1e-6, "on the down route: " + pathData.startingPos + " " + pathData.getLastPos());
				capped = true;
			}
		}
		assertTrue(capped);
	}

	private static BlockPos pos(int x, int z) {
		return new BlockPos(x, 64, z);
	}

	private static Platform platform(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, long id, BlockPos start, BlockPos end) {
		addRail(rails, start, end, RailType.PLATFORM);
		final Platform platform = new Platform(id, TransportMode.TRAIN, start, end);
		dataCache.platformIdMap.put(id, platform);
		return platform;
	}

	private static Route route(DataCache dataCache, long id, Platform... platforms) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final Platform platform : platforms) {
			route.platformIds.add(new Route.RoutePlatform(platform.id));
		}
		dataCache.routeIdMap.put(id, route);
		return route;
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, RailType railType) {
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}
}
