package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.advanced.SharedTrack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The track run on by depots planned apart, sent to the client for the web editor's check list (0.85.0).
 */
class SharedTrackPacketsTest {

	@Test
	void areasComeOverAsSent() {
		final SharedTrack.Area area = new SharedTrack.Area();
		area.key = "[[1], [2]]@3-4+5";
		final SharedTrack.Unit first = new SharedTrack.Unit();
		first.depotIds.add(1L);
		first.lines.add("TRAIN|line 1");
		final SharedTrack.Unit second = new SharedTrack.Unit();
		second.depotIds.addAll(Arrays.asList(2L, 7L));
		second.lines.addAll(Arrays.asList("TRAIN|line 2", "TRAIN|line 3"));
		area.units.addAll(Arrays.asList(first, second));
		area.stationIds.addAll(Arrays.asList(10L, 11L));
		area.length = 1234.5;
		area.start = new BlockPos(-100, 64, 2000);

		final FriendlyByteBuf packet = SharedTrackPackets.write(Collections.singletonList(area));
		final List<SharedTrack.Area> read = SharedTrackPackets.read(packet);
		assertNotNull(read);
		assertEquals(1, read.size());
		assertEquals(area.key, read.get(0).key);
		assertEquals(2, read.get(0).units.size());
		assertEquals(second.depotIds, read.get(0).units.get(1).depotIds);
		assertEquals(second.lines, read.get(0).units.get(1).lines);
		assertEquals(area.stationIds, read.get(0).stationIds);
		assertEquals(area.length, read.get(0).length);
		assertEquals(area.start, read.get(0).start);
		assertEquals(0, packet.readableBytes());
	}

	@Test
	void aMalformedPacketIsDropped() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeVarInt(1);
		packet.writeUtf("key");
		packet.writeVarInt(1 << 20);
		assertNull(SharedTrackPackets.read(packet));
	}
}
