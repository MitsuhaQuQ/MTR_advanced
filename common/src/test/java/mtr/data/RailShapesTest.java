package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maps draw a long curved rail through a few points along it rather than straight between its nodes.
 */
class RailShapesTest {

	@Test
	void aStraightRailHasNoShape() {
		final Rail rail = new Rail(new BlockPos(0, 64, 0), RailAngle.fromAngle(90), new BlockPos(0, 64, 300), RailAngle.fromAngle(270), RailType.DIAMOND, TransportMode.TRAIN);
		assertNull(rail.getMapShape());
	}

	@Test
	void aLongCurveIsDrawnCloseToTheRail() {
		// A quarter turn of about 200 blocks' radius
		final Rail rail = new Rail(new BlockPos(0, 64, 0), RailAngle.fromAngle(0), new BlockPos(200, 64, 200), RailAngle.fromAngle(90), RailType.DIAMOND, TransportMode.TRAIN);
		final float[] shape = rail.getMapShape();
		assertTrue(shape != null && shape.length >= 4, "a long curve has points along it");
		assertTrue(shape.length / 2 <= RailShapes.MAX_POINTS);

		// Every point along the rail is close to the line drawn through the nodes and the shape
		final Vec3 first = rail.getPosition(0);
		final double offsetX = Math.floor(first.x) + 0.5 - first.x;
		final double offsetZ = Math.floor(first.z) + 0.5 - first.z;
		final double[] xs = new double[shape.length / 2 + 2];
		final double[] zs = new double[shape.length / 2 + 2];
		final Vec3 last = rail.getPosition(rail.getLength());
		xs[0] = first.x + offsetX;
		zs[0] = first.z + offsetZ;
		for (int i = 0; i < shape.length / 2; i++) {
			xs[i + 1] = shape[i * 2];
			zs[i + 1] = shape[i * 2 + 1];
		}
		xs[xs.length - 1] = last.x + offsetX;
		zs[zs.length - 1] = last.z + offsetZ;
		for (int step = 0; step <= 200; step++) {
			final Vec3 position = rail.getPosition(rail.getLength() * step / 200);
			double nearest = Double.MAX_VALUE;
			for (int i = 0; i + 1 < xs.length; i++) {
				nearest = Math.min(nearest, RailShapes.distanceToLine(position.x + offsetX, position.z + offsetZ, xs[i], zs[i], xs[i + 1], zs[i + 1]));
			}
			assertTrue(nearest <= RailShapes.TOLERANCE + 0.01, "point " + step + " is " + nearest + " blocks off");
		}
	}

	@Test
	void pointsPackToQuarterBlocks() {
		final long packed = RailShapes.pack(-1234.25F, 98765.5F);
		assertEquals(-1234.25F, RailShapes.unpackX(packed));
		assertEquals(98765.5F, RailShapes.unpackZ(packed));
	}
}
