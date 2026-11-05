package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rail ANTE saved as a Bézier curve, with its ends and their angles in keys of its own and a shape that isn't MTR's,
 * takes MTR's shape for the same ends and angles when read again.
 */
class AnteBezierRailTest {

	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final BlockPos END = new BlockPos(60, 64, 30);

	private static Map<String, Value> saved(Rail rail) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}

	/**
	 * As ANTE saved a Bézier rail: its path mode, its ends and their angles in radians, and a shape of MTR's that is nothing.
	 */
	private static Map<String, Value> asAnteBezier(Map<String, Value> map, RailAngle facingStart, RailAngle facingEnd) {
		map.put("path_mode", ValueFactory.newInteger(1));
		map.put("pos_start", ValueFactory.newInteger(START.asLong()));
		map.put("facing_start", ValueFactory.newFloat(facingStart.angleRadians));
		map.put("pos_end", ValueFactory.newInteger(END.asLong()));
		map.put("facing_end", ValueFactory.newFloat(facingEnd.angleRadians));
		for (final String key : new String[]{"h_1", "k_1", "h_2", "k_2", "r_1", "r_2", "t_start_1", "t_end_1", "t_start_2", "t_end_2"}) {
			map.put(key, ValueFactory.newFloat(0));
		}
		return map;
	}

	@Test
	void aBezierRailTakesTheShapeOfItsEnds() throws IOException {
		final RailAngle facingStart = RailAngle.fromAngle(0F);
		final RailAngle facingEnd = RailAngle.fromAngle(180F);
		final Rail expected = new Rail(START, facingStart, END, facingEnd, RailType.IRON, TransportMode.TRAIN);
		assertTrue(expected.isValid());

		final Rail read = new Rail(asAnteBezier(saved(expected), facingStart, facingEnd));
		assertTrue(read.isValid());
		assertEquals(expected.getLength(), read.getLength(), 1E-6);
		assertEquals(expected.getPosition(expected.getLength() / 2).x, read.getPosition(read.getLength() / 2).x, 1E-6);
		assertEquals(facingStart.angleDegrees, read.facingStart.angleDegrees, 0.1);
	}

	@Test
	void anOrdinaryRailIsReadAsItWasSaved() throws IOException {
		final Rail rail = new Rail(START, RailAngle.fromAngle(0F), END, RailAngle.fromAngle(180F), RailType.IRON, TransportMode.TRAIN);
		final Map<String, Value> map = saved(rail);
		map.put("path_mode", ValueFactory.newInteger(0));
		final Rail read = new Rail(map);
		assertEquals(rail.getLength(), read.getLength(), 1E-6);
	}
}
