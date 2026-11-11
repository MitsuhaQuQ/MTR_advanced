package mtr.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The debug display's lines of a train (0.65.31) are read back as written, cut to the most a packet carries.
 */
class TrainDebugPacketsTest {

	@Test
	void linesAreReadBackAsWritten() {
		final List<String[]> lines = Arrays.asList(new String[]{"route", "S1 down"}, new String[]{"hold", "#hold_timetable"});
		final FriendlyByteBuf packet = TrainDebugPackets.write(42, lines);
		assertEquals(42, packet.readLong());
		final List<String[]> read = TrainDebugPackets.read(packet);
		assertEquals(2, read.size());
		assertArrayEquals(lines.get(0), read.get(0));
		assertArrayEquals(lines.get(1), read.get(1));
	}

	@Test
	void tooManyLinesAreCutAndAForgedCountIsRefused() {
		final List<String[]> lines = new ArrayList<>();
		for (int i = 0; i < TrainDebugPackets.MAX_LINES + 5; i++) {
			lines.add(new String[]{"k" + i, "v"});
		}
		final FriendlyByteBuf packet = TrainDebugPackets.write(1, lines);
		packet.readLong();
		assertEquals(TrainDebugPackets.MAX_LINES, TrainDebugPackets.read(packet).size());
		final FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
		forged.writeVarInt(TrainDebugPackets.MAX_LINES + 1);
		assertThrows(IllegalArgumentException.class, () -> TrainDebugPackets.read(forged));
	}
}
