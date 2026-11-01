package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
