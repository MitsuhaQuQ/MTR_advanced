package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A siding keeps the ways for turning back early its depot found (D4) in its save, and an old save has none.
 */
class SidingTurnLinksTest {

	private static final PathData UP = rail(0, 20, RailType.PLATFORM, 2, 40);
	private static final PathData ROUND = rail(20, 60, RailType.TURN_BACK, 0, 1);
	private static final PathData DOWN = rail(60, 80, RailType.PLATFORM, 5, 40);
	private static final PathData BACK = rail(80, 120, RailType.SIDING, 9, 0);

	private static PathData rail(int startZ, int endZ, RailType railType, long savedRailBaseId, int dwellTime) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, 0);
	}

	private static Map<String, Value> saved(Siding siding) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(siding.messagePackLength());
		siding.toMessagePack(packer);
		packer.close();
		// Nothing is left over for what comes after it
		final org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Map<String, Value> map = new HashMap<>(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext());
		return map;
	}

	private static Value value(MessagePackWrite write) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		write.accept(packer);
		packer.close();
		return MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue();
	}

	@Test
	void theWaysForTurningBackEarlyAreSavedAndReadBack() throws IOException {
		final Siding siding = new Siding(TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);
		final Map<String, Value> map = saved(siding);
		// An old save has none
		assertTrue(new Siding(map).getTurnLinks().isEmpty());

		map.put("turn_links", value(packer -> {
			packer.packArrayHeader(1);
			packer.packMapHeader(3);
			packer.packString("from").packLong(2);
			packer.packString("to").packLong(5);
			RailwayData.writeMessagePackDataset(packer, Arrays.asList(UP, ROUND, DOWN), "path");
		}));
		map.put("way_outs", value(packer -> {
			packer.packArrayHeader(1);
			packer.packMapHeader(2);
			packer.packString("platform").packLong(5);
			RailwayData.writeMessagePackDataset(packer, Arrays.asList(BACK, DOWN), "path");
		}));
		map.put("turn_returns", value(packer -> {
			packer.packArrayHeader(1);
			packer.packMapHeader(2);
			packer.packString("platform").packLong(5);
			RailwayData.writeMessagePackDataset(packer, Arrays.asList(DOWN, BACK), "path");
		}));
		final Siding read = new Siding(map);
		assertEquals(Collections.singleton("2>5"), read.getTurnLinks().keySet());
		assertEquals(3, read.getTurnLinks().get("2>5").size());
		assertEquals(2, read.getTurnReturns().get(5L).size());

		// And saved again the same
		final Siding again = new Siding(saved(read));
		assertEquals(3, again.getTurnLinks().get("2>5").size());
		assertTrue(again.getTurnLinks().get("2>5").get(1).isSameRail(ROUND));
		assertEquals(2, again.getTurnReturns().get(5L).size());
		// And the ways out for spares (D5)
		assertEquals(2, again.getWayOuts().get(5L).size());
	}

	private interface MessagePackWrite {
		void accept(MessageBufferPacker packer) throws IOException;
	}
}
