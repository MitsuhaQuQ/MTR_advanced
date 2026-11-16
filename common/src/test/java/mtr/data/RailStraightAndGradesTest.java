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
 * Straight rails at any angle between nodes facing along the line between them, and rails whose height follows the
 * grades at their ends (0.87.0).
 */
class RailStraightAndGradesTest {

	private static final BlockPos START = new BlockPos(5, 64, -3);
	private static final int[][] OFFSETS = {{70, 30}, {7, 3}, {2, 1}, {-50, 17}, {3, 100}, {100, 1}, {-31, -64}, {1000, 333}};

	private static Rail straight(BlockPos start, BlockPos end) {
		final double angle = Math.toDegrees(Math.atan2(end.getZ() - start.getZ(), end.getX() - start.getX()));
		final RailAngle facing = RailAngle.fromAngle(angle);
		return new Rail(start, facing, end, facing.getOpposite(), RailType.IRON, TransportMode.TRAIN);
	}

	@Test
	void nodesFacingAlongTheLineGiveAStraightRailAtAnyAngle() {
		for (final int[] offset : OFFSETS) {
			final BlockPos end = START.offset(offset[0], 0, offset[1]);
			final Rail rail = straight(START, end);
			final String name = offset[0] + "," + offset[1];
			assertTrue(rail.isValid(), name);
			assertTrue(rail.goodRadius(), name);
			final double distance = Math.hypot(offset[0], offset[1]);
			assertEquals(distance, rail.getLength(), 1E-6, name);
			// Every point on the line through both nodes, and the ends exactly on the nodes
			for (int i = 0; i <= 8; i++) {
				final Vec3 point = rail.getPosition(rail.getLength() * i / 8);
				final double across = ((point.x - START.getX() - 0.5) * offset[1] - (point.z - START.getZ() - 0.5) * offset[0]) / distance;
				assertEquals(0, across, 1E-6, name + " at " + i);
			}
			final Vec3 last = rail.getPosition(rail.getLength());
			assertEquals(end.getX() + 0.5, last.x, 1E-6, name);
			assertEquals(end.getZ() + 0.5, last.z, 1E-6, name);
			// The rail back is straight too
			final Rail back = straight(end, START);
			assertTrue(back.isValid(), name);
			assertEquals(distance, back.getLength(), 1E-6, name);
		}
	}

	@Test
	void railsOnTheNodesAxesStayAsBefore() {
		final Rail rail = new Rail(START, RailAngle.E, START.offset(20, 0, 0), RailAngle.W, RailType.IRON, TransportMode.TRAIN);
		assertTrue(rail.isValid());
		assertEquals(20, rail.getLength(), 1E-9);
		final Rail diagonal = new Rail(START, RailAngle.SE, START.offset(12, 0, 12), RailAngle.NW, RailType.IRON, TransportMode.TRAIN);
		assertTrue(diagonal.isValid());
		assertEquals(12 * Math.sqrt(2), diagonal.getLength(), 1E-9);
	}

	@Test
	void anEndOffTheLineStillCurves() {
		final BlockPos end = START.offset(70, 0, 31);
		final RailAngle facing = RailAngle.fromAngle(Math.toDegrees(Math.atan2(30, 70)));
		final Rail rail = new Rail(START, facing, end, facing.getOpposite(), RailType.IRON, TransportMode.TRAIN);
		assertTrue(rail.isValid());
		assertTrue(rail.getLength() > Math.hypot(70, 31));
	}

	@Test
	void gradesSetTheHeightAlongTheRail() {
		final Rail rail = straight(START, START.offset(400, 10, 0));
		assertFalse(rail.hasGrades());
		// The old shape is level at both ends
		assertEquals(64, rail.getPosition(0.5).y, 1E-3);
		rail.setGrades(0, 0, 500, 500);
		assertTrue(rail.hasGrades());
		assertEquals(64, rail.getPosition(0).y, 1E-9);
		assertEquals(74, rail.getPosition(400).y, 1E-9);
		assertEquals(69, rail.getPosition(200).y, 1E-9);
		// On a straight grade in the middle, rather than steepest in the middle
		final double middleGrade = rail.getVerticalProfile().getMiddleGrade();
		assertEquals(middleGrade, rail.getPosition(201).y - rail.getPosition(200).y, 1E-9);
		assertEquals(middleGrade, rail.getPosition(151).y - rail.getPosition(150).y, 1E-9);
	}

	@Test
	void cableCarRailsKeepTheirSag() {
		final Rail rail = new Rail(START, RailAngle.E, START.offset(40, 6, 0), RailAngle.W, RailType.CABLE_CAR, TransportMode.CABLE_CAR);
		rail.setGrades(0.1, 0.1, 500, 500);
		assertFalse(rail.hasGrades());
	}

	@Test
	void gradesAreSavedAndSent() throws IOException {
		final Rail rail = straight(START, START.offset(70, 4, 30));
		rail.setGrades(0.02, -0.01, 300, 800);

		final Rail saved = new Rail(saved(rail));
		assertTrue(saved.hasGrades());
		assertEquals(0.02, saved.getGradeStart(), 1E-12);
		assertEquals(-0.01, saved.getGradeEnd(), 1E-12);
		assertEquals(300, saved.getVerticalRadiusStart(), 1E-12);
		assertEquals(800, saved.getVerticalRadiusEnd(), 1E-12);
		assertEquals(rail.getPosition(33).y, saved.getPosition(33).y, 1E-12);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		final Rail sent = new Rail(packet);
		assertEquals(0, packet.readableBytes());
		assertTrue(sent.hasGrades());
		assertEquals(rail.getPosition(33).y, sent.getPosition(33).y, 1E-12);

		final Rail back = straight(START.offset(70, 4, 30), START);
		back.copyGrades(rail, true);
		assertEquals(0.01, back.getGradeStart(), 1E-12);
		assertEquals(-0.02, back.getGradeEnd(), 1E-12);
		assertEquals(800, back.getVerticalRadiusStart(), 1E-12);
		assertEquals(rail.getPosition(10).y, back.getPosition(back.getLength() - 10).y, 1E-9);
	}

	@Test
	void railsWithoutGradesAreSavedAsBefore() throws IOException {
		final Rail rail = straight(START, START.offset(70, 4, 30));
		final Map<String, Value> map = saved(rail);
		assertEquals(19, map.size());
		assertFalse(map.containsKey("grade_start"));
		final Rail read = new Rail(map);
		assertFalse(read.hasGrades());
		assertEquals(rail.getPosition(20).y, read.getPosition(20).y, 1E-12);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		final Rail sent = new Rail(packet);
		assertFalse(sent.hasGrades());
		assertEquals(0, packet.readableBytes());
	}

	private static Map<String, Value> saved(Rail rail) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}
}
