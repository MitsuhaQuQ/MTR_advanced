package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import transitsim.geometry.Superelevation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rails with superelevation set on their nodes (0.98.0).
 */
class RailCantTest {

	private static final BlockPos START = new BlockPos(5, 64, -3);
	// A straight part of 20 and a quarter circle of radius 40
	private static final BlockPos END = START.offset(60, 0, 40);

	private static Rail curve() {
		return new Rail(START, RailAngle.E, END, RailAngle.N, RailType.IRON, TransportMode.TRAIN);
	}

	private static Rail curveBack() {
		return new Rail(END, RailAngle.N, START, RailAngle.E, RailType.IRON, TransportMode.TRAIN);
	}

	@Test
	void theStraightIsLevelTheArcLeansByTheCant() {
		final Rail rail = curve();
		rail.setTransitions(20, 20);
		rail.setCants(150, 0, 150, 0);
		assertTrue(rail.hasCant());
		assertEquals(0, rail.getCantRoll(5), 1E-6, "on the straight");
		final float middle = rail.getCantRoll(rail.getLength() * 0.6);
		assertEquals(Math.asin(150 / Superelevation.RAIL_HEADS), Math.abs(middle), 2E-3, "the whole cant on the arc");
		// Along the transition it rises
		assertTrue(Math.abs(rail.getCantRoll(30)) > Math.abs(rail.getCantRoll(23)));
	}

	@Test
	void theRailBackLeansTheSameWaySeenFromItsOwnWay() {
		final Rail rail = curve();
		final Rail back = curveBack();
		rail.setTransitions(20, 20);
		back.setTransitions(20, 20);
		rail.setCants(100, 0, 0, 80);
		back.setCants(0, 80, 100, 0);
		for (double s = 1; s < rail.getLength() - 1; s += 3) {
			// The same place, seen going the other way: the curve turns the other way, so the sign flips
			assertEquals(-rail.getCantRoll(s), back.getCantRoll(back.getLength() - s), 2E-3, "at " + s);
		}
		final Rail copied = curveBack();
		copied.setTransitions(20, 20);
		copied.copyGrades(rail, true);
		assertEquals(back.getCantStart(), copied.getCantStart());
		assertEquals(back.getCantSpeedStart(), copied.getCantSpeedStart());
		assertEquals(back.getCantEnd(), copied.getCantEnd());
	}

	@Test
	void noCantNoRollAndCableCarsHaveNone() {
		final Rail rail = curve();
		assertFalse(rail.hasCant());
		assertEquals(0, rail.getCantRoll(30));
		rail.setCants(120, 0, 120, 0);
		rail.setCants(0, 0, 0, 0);
		assertFalse(rail.hasCant());
		final Rail cable = new Rail(START, RailAngle.E, END, RailAngle.N, RailType.CABLE_CAR, TransportMode.CABLE_CAR);
		cable.setCants(120, 60, 120, 60);
		assertFalse(cable.hasCant());
	}

	@Test
	void keptInTheSaveAndSentInPackets() throws IOException {
		final Rail rail = curve();
		rail.setTransitions(10, 10);
		rail.setCants(90, 0, 0, 120);
		final Rail saved = new Rail(save(rail));
		assertEquals(90, saved.getCantStart());
		assertEquals(120, saved.getCantSpeedEnd());
		assertEquals(rail.getCantRoll(40), saved.getCantRoll(40), 1E-6);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		final Rail sent = new Rail(packet);
		assertEquals(0, packet.readableBytes());
		assertEquals(rail.getCantRoll(40), sent.getCantRoll(40), 1E-6);

		// A rail without saves no keys for it
		final Rail none = curve();
		assertFalse(save(none).containsKey("cant_start"));
	}

	private static Map<String, Value> save(Rail rail) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}
}
