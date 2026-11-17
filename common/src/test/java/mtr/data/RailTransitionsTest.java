package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rails with transition curves at their ends (0.95.0, design 9.3).
 */
class RailTransitionsTest {

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
	void transitionsKeepTheEndsAndTheirAngles() {
		final Rail before = curve();
		final Rail rail = curve();
		rail.setTransitions(20, 20);
		assertNotNull(rail.getTransitionPath());
		assertClose(before.getPosition(0), rail.getPosition(0), 1E-6);
		assertClose(before.getPosition(before.getLength()), rail.getPosition(rail.getLength()), 1E-4);
		assertEquals(before.getLeavingFacing(), rail.getLeavingFacing());
		assertEquals(before.getArrivingFacing(), rail.getArrivingFacing());
		assertNotEquals(before.getLength(), rail.getLength(), 1E-3);
		assertEquals(0, rail.getTransitionPath().getCurvature(0), 1E-12);
		assertEquals(0, rail.getTransitionPath().getCurvature(rail.getLength()), 1E-12);
		// Leaves the start along the old straight part
		assertEquals(START.getZ() + 0.5, rail.getPosition(1).z, 1E-6);
		assertTrue(rail.goodRadius());
		assertTrue(rail.isValid());
	}

	@Test
	void noTransitionsKeepTheOldShape() {
		final Rail before = curve();
		final Rail rail = curve();
		rail.setTransitions(20, 20);
		rail.setTransitions(0, 0);
		assertNull(rail.getTransitionPath());
		assertEquals(before.getLength(), rail.getLength(), 1E-12);
		assertClose(before.getPosition(33), rail.getPosition(33), 1E-12);

		final Rail straight = new Rail(START, RailAngle.E, START.offset(50, 0, 0), RailAngle.W, RailType.IRON, TransportMode.TRAIN);
		straight.setTransitions(30, 30);
		assertNull(straight.getTransitionPath());
		assertEquals(50, straight.getLength(), 1E-9);
	}

	@Test
	void theRailBackHasTheSameShape() {
		final Rail rail = curve();
		final Rail back = curveBack();
		rail.setTransitions(25, 10);
		back.setTransitions(10, 25);
		assertEquals(rail.getLength(), back.getLength(), 1E-6);
		for (double s = 0; s <= rail.getLength(); s += 0.5) {
			assertClose(rail.getPosition(s), back.getPosition(back.getLength() - s), 1E-4);
		}
		final Rail copied = curveBack();
		copied.copyGrades(rail, true);
		assertEquals(10, copied.getTransitionStart(), 1E-12);
		assertEquals(25, copied.getTransitionEnd(), 1E-12);
		assertEquals(back.getLength(), copied.getLength(), 1E-12);
	}

	@Test
	void anSCurveChangesDirectionWithoutAJump() {
		final BlockPos end = START.offset(60, 0, 12);
		final Rail rail = new Rail(START, RailAngle.E, end, RailAngle.W, RailType.IRON, TransportMode.TRAIN);
		final Rail before = new Rail(START, RailAngle.E, end, RailAngle.W, RailType.IRON, TransportMode.TRAIN);
		rail.setTransitions(10, 10);
		assertNotNull(rail.getTransitionPath());
		assertClose(before.getPosition(before.getLength()), rail.getPosition(rail.getLength()), 1E-4);
		double previous = rail.getTransitionPath().getCurvature(0);
		for (double s = 0.1; s <= rail.getLength(); s += 0.1) {
			final double curvature = rail.getTransitionPath().getCurvature(s);
			assertTrue(Math.abs(curvature - previous) < 0.01, "jump at " + s);
			previous = curvature;
		}
		// Halfway it goes straight for a moment, turning from one side to the other
		assertEquals(0, rail.getTransitionPath().getCurvature(rail.getLength() / 2), 0.01);
	}

	@Test
	void theProfileFollowsTheNewLength() {
		final BlockPos end = START.offset(60, 3, 40);
		final Rail rail = new Rail(START, RailAngle.E, end, RailAngle.N, RailType.IRON, TransportMode.TRAIN);
		rail.setGrades(0, 0, 500, 500);
		rail.setTransitions(20, 20);
		assertEquals(START.getY(), rail.getPosition(0).y, 1E-9);
		assertEquals(end.getY(), rail.getPosition(rail.getLength()).y, 1E-9);
	}

	@Test
	void transitionsAreSavedAndSent() throws IOException {
		final Rail rail = curve();
		rail.setGrades(0, 0, 500, 500);
		rail.setTransitions(20, 15);

		final Map<String, Value> map = saved(rail);
		assertEquals(25, map.size());
		final Rail saved = new Rail(map);
		assertEquals(20, saved.getTransitionStart(), 1E-12);
		assertEquals(15, saved.getTransitionEnd(), 1E-12);
		assertEquals(rail.getLength(), saved.getLength(), 1E-12);
		assertClose(rail.getPosition(37), saved.getPosition(37), 1E-12);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		final Rail sent = new Rail(packet);
		assertEquals(0, packet.readableBytes());
		assertEquals(rail.getLength(), sent.getLength(), 1E-12);
		assertClose(rail.getPosition(37), sent.getPosition(37), 1E-12);

		// Without transitions nothing more is saved
		assertFalse(saved(curve()).containsKey("transition_start"));
	}

	@Test
	void platformsAndSidingsStayLevel() {
		final BlockPos end = START.offset(40, 0, 0);
		final Rail platform = new Rail(START, RailAngle.E, end, RailAngle.W, RailType.PLATFORM, TransportMode.TRAIN);
		platform.setGrades(0, 0, 500, 500);
		assertNull(RailEdit.checkNewRail(platform, START));
		assertEquals(0, RailEdit.slope(platform)[0], 1E-12);

		// 2 per thousand at one end: fine for a platform, too steep for a siding
		platform.setGrades(0.002, 0, 500, 500);
		assertNull(RailEdit.checkNewRail(platform, START));
		final Rail siding = new Rail(START, RailAngle.E, end, RailAngle.W, RailType.SIDING, TransportMode.TRAIN);
		siding.setGrades(0.002, 0, 500, 500);
		assertEquals("gui.mtr.siding_too_steep", RailEdit.checkNewRail(siding, START)[0]);

		platform.setGrades(0.01, 0, 500, 500);
		assertEquals("gui.mtr.platform_too_steep", RailEdit.checkNewRail(platform, START)[0]);

		// Other rails have no limit
		final Rail rail = new Rail(START, RailAngle.E, end, RailAngle.W, RailType.IRON, TransportMode.TRAIN);
		rail.setGrades(0.05, 0, 500, 500);
		assertNull(RailEdit.checkNewRail(rail, START));
	}

	private static void assertClose(Vec3 expected, Vec3 actual, double tolerance) {
		assertEquals(expected.x, actual.x, tolerance);
		assertEquals(expected.y, actual.y, tolerance);
		assertEquals(expected.z, actual.z, tolerance);
	}

	private static Map<String, Value> saved(Rail rail) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}
}
