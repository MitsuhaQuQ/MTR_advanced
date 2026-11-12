package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network's settings (optimisation round 4): saved and sent whole, an old save having the defaults, and kept within
 * bounds however they come in.
 */
class NetworkSettingsTest {

	@Test
	void theSettingsAreSavedAndSent() throws IOException {
		final NetworkSettings settings = new NetworkSettings();
		assertEquals(30000, settings.minReverseMillis);
		settings.minReverseMillis = 45000;
		settings.earlyThresholdMillis = 60000;
		// The wait thresholds of the network and of one station (运用补完 step 2)
		settings.waitThresholdMillis = 420000;
		settings.stationWaitThresholds.put(77L, 900000L);
		assertEquals(900000, settings.getWaitThreshold(77));
		assertEquals(420000, settings.getWaitThreshold(78));

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertEquals(settings, new NetworkSettings(map));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);

		// An old save has none: the defaults
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("min_reverse_millis");
		assertEquals(NetworkSettings.DEFAULT_MIN_REVERSE_MILLIS, new NetworkSettings(old).minReverseMillis);
	}

	@Test
	void theSettingsStayWithinBounds() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(-5);
		packet.writeLong(Long.MAX_VALUE);
		packet.writeLong(Long.MAX_VALUE);
		packet.writeVarInt(1);
		packet.writeLong(77);
		packet.writeLong(-1);
		// No owners set by hand, no logical lines, no sections put in them, no lines given them
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		// No fares, no routes put in other fare groups, no stations given groups (0.68.0)
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		packet.writeVarInt(0);
		// No running sides (0.69.0)
		packet.writeVarInt(0);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(0, read.minReverseMillis);
		assertEquals(NetworkSettings.MAX_MILLIS, read.earlyThresholdMillis);
		assertEquals(NetworkSettings.MAX_WAIT_THRESHOLD_MILLIS, read.waitThresholdMillis);
		assertEquals(0, read.getWaitThreshold(77));
	}

	@Test
	void faresAreSavedSentAndCopied() throws IOException {
		// Fares by distance (0.68.0): a group's fares with tiers set by hand, a route put in another group, a station's groups
		final NetworkSettings settings = new NetworkSettings();
		final transitsim.fare.FareLevels levels = new transitsim.fare.FareLevels();
		levels.startFare = 3;
		levels.initialBlocks = 6000;
		levels.cap = 9;
		levels.overtime = true;
		levels.overtimeMinutes = 90;
		levels.setTiers(new long[]{8000, 12000}, new int[]{3, 4, 6});
		settings.fareLevels.put("line:TRAIN:line 1", levels);
		settings.fareLevels.put("line:TRAIN:line 2", new transitsim.fare.FareLevels());
		settings.fareRouteGroups.put(5L, "Company A");
		settings.fareStationGroups.put(7L, new java.util.ArrayList<>(java.util.Arrays.asList("Company A", "line:TRAIN:line 1")));

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		final NetworkSettings saved = new NetworkSettings(map);
		assertEquals(settings, saved);
		assertEquals(6, saved.fareLevels.get("line:TRAIN:line 1").getFare(20000, 0));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);
		assertEquals(0, packet.readableBytes());

		final NetworkSettings copy = new NetworkSettings();
		copy.copyFrom(settings);
		copy.fareLevels.get("line:TRAIN:line 1").startFare = 5;
		copy.fareStationGroups.get(7L).clear();
		assertEquals(3, settings.fareLevels.get("line:TRAIN:line 1").startFare);
		assertEquals(2, settings.fareStationGroups.get(7L).size());

		// A save from before has none
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("fare_levels");
		old.remove("fare_route_groups");
		old.remove("fare_station_groups");
		final NetworkSettings oldSettings = new NetworkSettings(old);
		assertTrue(oldSettings.fareLevels.isEmpty() && oldSettings.fareRouteGroups.isEmpty() && oldSettings.fareStationGroups.isEmpty());
	}

	@Test
	void runningSidesAreSavedSentCopiedAndLookedUpLineFirst() throws IOException {
		// Running sides (0.69.0): the network's, a logical line's and an operating line's
		final NetworkSettings settings = new NetworkSettings();
		assertEquals(transitsim.network.RunningDirections.RIGHT, settings.getRunningSide("TRAIN|line 1"), "right hand when nothing is set");
		settings.logicalLines.put(4L, new NetworkSettings.LogicalLine("Trunk", 0, false));
		settings.lineTracks.put("TRAIN|line 2", new java.util.ArrayList<>(java.util.Collections.singletonList(4L)));
		settings.runningSides.put("L4", -1);
		settings.runningSides.put("RTRAIN|line 3", -1);
		settings.runningSides.put("RTRAIN|line 2", 1);
		settings.runningSides.put(NetworkSettings.SIDE_NETWORK, -1);
		assertEquals(1, settings.getRunningSide("TRAIN|line 2"), "its own before its logical line's");
		assertEquals(-1, settings.getRunningSide("TRAIN|line 3"));
		assertEquals(-1, settings.getRunningSide("TRAIN|line 9"), "the network's");
		settings.runningSides.remove("RTRAIN|line 2");
		settings.runningSides.remove(NetworkSettings.SIDE_NETWORK);
		assertEquals(-1, settings.getRunningSide("TRAIN|line 2"), "its logical line's");

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertEquals(settings, new NetworkSettings(map));
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);
		assertEquals(0, packet.readableBytes());
		final NetworkSettings copy = new NetworkSettings();
		copy.copyFrom(settings);
		assertEquals(settings, copy);
		// A save from before has none
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("running_sides");
		assertTrue(new NetworkSettings(old).runningSides.isEmpty());
	}

	@Test
	void ownersSetByHandAreSavedSentAndKeptByRail() throws IOException {
		// Web layers step 2: the owners of a section set by hand, by a rail's two nodes in either order
		final NetworkSettings settings = new NetworkSettings();
		final java.util.UUID railKey = NetworkSettings.parseRailKey("200,100");
		assertEquals(railKey, NetworkSettings.parseRailKey("100, 200"));
		assertEquals("100,200", NetworkSettings.railKeyText(railKey));
		assertNull(NetworkSettings.parseRailKey("100"));
		assertNull(NetworkSettings.parseRailKey("a,b"));
		settings.ownerOverrides.put(railKey, java.util.Arrays.asList("TRAIN|Line 1", "TRAIN|Line 2"));
		// Open to every line
		settings.ownerOverrides.put(NetworkSettings.parseRailKey("300,400"), new java.util.ArrayList<>());

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		final NetworkSettings saved = new NetworkSettings(map);
		assertEquals(settings, saved);
		assertEquals(java.util.Arrays.asList("TRAIN|Line 1", "TRAIN|Line 2"), saved.ownerOverrides.get(railKey));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);
		assertEquals(0, packet.readableBytes());

		// Copied, not shared
		final NetworkSettings copy = new NetworkSettings();
		copy.copyFrom(settings);
		assertEquals(settings, copy);
		copy.ownerOverrides.get(railKey).set(0, "TRAIN|Line 3");
		assertEquals("TRAIN|Line 1", settings.ownerOverrides.get(railKey).get(0));
		assertNotEquals(settings, copy);

		// A save from before has none
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("owner_overrides");
		assertTrue(new NetworkSettings(old).ownerOverrides.isEmpty());
	}

	@Test
	void logicalLinesAreSavedSentAndCopied() throws IOException {
		// The logical lines of the track (2026-11-09): lines, the sections put in them by a rail, the lines each operating line runs on
		final NetworkSettings settings = new NetworkSettings();
		assertFalse(settings.usesLogicalLines());
		settings.logicalLines.put(11L, new NetworkSettings.LogicalLine("京沪线", 0xFF0000, false));
		settings.logicalLines.put(12L, new NetworkSettings.LogicalLine("联络线 A", 0x00FF00, true));
		settings.railLines.put(NetworkSettings.parseRailKey("200,100"), 11L);
		settings.railLines.put(NetworkSettings.parseRailKey("300,400"), 12L);
		settings.lineTracks.put("TRAIN|Line 1", new java.util.ArrayList<>(java.util.Arrays.asList(11L, 12L)));
		assertTrue(settings.usesLogicalLines());

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		final NetworkSettings saved = new NetworkSettings(map);
		assertEquals(settings, saved);
		assertEquals("京沪线", saved.logicalLines.get(11L).name);
		assertTrue(saved.logicalLines.get(12L).connecting);
		assertEquals(11L, (long) saved.railLines.get(NetworkSettings.parseRailKey("100,200")));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);
		assertEquals(0, packet.readableBytes());

		final NetworkSettings copy = new NetworkSettings();
		copy.copyFrom(settings);
		copy.lineTracks.get("TRAIN|Line 1").remove(0);
		assertEquals(2, settings.lineTracks.get("TRAIN|Line 1").size());

		// A save from before has none
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("logical_lines");
		old.remove("rail_lines");
		old.remove("line_tracks");
		assertFalse(new NetworkSettings(old).usesLogicalLines());
	}
}
