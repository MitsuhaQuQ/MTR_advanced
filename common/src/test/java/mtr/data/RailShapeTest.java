package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailShapeTest {

	@Test
	void nodeTurnedSlightlyAwayFromACollinearNodeStaysDrivable() {
		for (final int angle : new int[]{85, 89, 91, 95}) {
			final Rail rail = new Rail(new BlockPos(0, 64, 0), RailAngle.fromAngle(angle), new BlockPos(0, 64, 60), RailAngle.fromAngle(270), RailType.IRON, TransportMode.TRAIN);
			assertTrue(rail.isValid(), angle + " valid");
			assertTrue(rail.goodRadius(), angle + " radius");
			assertEquals(60, rail.getLength(), 1, angle + " length");
		}
	}

	@Test
	void aSavedFacingPointingTheOtherWayStillGivesTheRealDirection() {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(60, 64, 0);
		final Rail rail = new Rail(start, RailAngle.W, end, RailAngle.E, RailType.IRON, TransportMode.TRAIN);

		assertSame(RailAngle.E, rail.getLeavingFacing());
		assertSame(RailAngle.W, rail.getArrivingFacing());
	}

	@Test
	void aNodeShowsTheWayItsLowestRailLeaves() {
		final Map<BlockPos, Rail> connections = new HashMap<>();
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos west = new BlockPos(-60, 64, 0);
		final BlockPos east = new BlockPos(60, 64, 0);
		connections.put(east, new Rail(start, RailAngle.E, east, RailAngle.W, RailType.IRON, TransportMode.TRAIN));
		connections.put(west, new Rail(start, RailAngle.E, west, RailAngle.E, RailType.IRON, TransportMode.TRAIN));

		assertSame(RailAngle.W, Rail.getNodeFacing(connections));
		assertNull(Rail.getNodeFacing(new HashMap<>()));
		assertNull(Rail.getNodeFacing(null));
	}

	@Test
	void aTurnoutKeepsBothRailsForwardWhenItsNodeTurns() {
		final BlockPos node = new BlockPos(0, 64, 0);
		final BlockPos straight = new BlockPos(40, 64, 0);
		final BlockPos branch = new BlockPos(24, 64, 6);
		for (int degrees = -20; degrees <= 20; degrees++) {
			final RailAngle angle = RailAngle.fromAngle(degrees);
			final RailAngle facing = angle.getRotatedFacing(RailAngle.E, RailAngle.E);
			assertTrue(!new Rail(node, facing, straight, RailAngle.W, RailType.IRON, TransportMode.TRAIN).turnsBack(node, straight), degrees + " straight");
			assertTrue(!new Rail(node, facing, branch, RailAngle.W, RailType.IRON, TransportMode.TRAIN).turnsBack(node, branch), degrees + " branch");
		}
	}

	@Test
	void aCurveAimedAtTheOtherNodeDoesNotGoPastIt() {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(80, 64, 7);
		for (final int endAngle : new int[]{-15, -10, -5, 10, 15, 20}) {
			final Rail rail = new Rail(start, RailAngle.fromAngle(5), end, RailAngle.fromAngle(endAngle + 180), RailType.IRON, TransportMode.TRAIN);
			assertTrue(rail.isValid(), endAngle + " valid");
			assertTrue(!rail.turnsBack(start, end), endAngle + " turns back");
		}
	}

	@Test
	void turningBackIsDetected() {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(0, 64, 60);
		assertTrue(!new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), RailType.IRON, TransportMode.TRAIN).turnsBack(start, end));
		assertTrue(new Rail(start, RailAngle.fromAngle(0), new BlockPos(-20, 64, 2), RailAngle.fromAngle(0), RailType.IRON, TransportMode.TRAIN).turnsBack(start, new BlockPos(-20, 64, 2)));
	}

	@Test
	void doorOpenTimeAndStopTimeConvert() {
		assertEquals(17, Route.getDwellFromDoorOpenSeconds(0));
		assertEquals(0, Route.getDoorOpenSeconds(17));
		assertEquals(77, Route.getDwellFromDoorOpenSeconds(30));
		assertEquals(30, Route.getDoorOpenSeconds(77));
	}
}
