package cn.zbx1425.mtrsteamloco.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailCalculatorTest {

	private static RailCalculator.Group calculate(double endX, double endZ, double startDegrees, double endDegrees) {
		return RailCalculator.calculate(0, 0, endX, endZ, Math.toRadians(startDegrees), Math.toRadians(endDegrees));
	}

	@Test
	void aNodeOnTheOtherNodesAxisIsLeftToTheBiarcInBothDirections() {
		for (int degrees = 1; degrees <= 20; degrees++) {
			assertNull(calculate(40, 0, degrees, 180), "+" + degrees);
			assertNull(calculate(40, 0, -degrees, 180), "-" + degrees);
		}
	}

	@Test
	void anOrdinaryCurveIsStillCalculated() {
		assertNotNull(calculate(40, 20, 0, 225));
	}

	@Test
	void cornersTooSmallToRunOnAreLeftToTheBiarc() {
		final double[][] shapes = {{8, -1, -8, 195}, {43, 29, 34, 200}, {97, -56, -25, 150}};
		for (final double[] shape : shapes) {
			final RailCalculator.Group group = calculate(shape[0], shape[1], shape[2], shape[3]);
			if (group != null) {
				assertTrue(group.first.isStraight || group.first.r >= 2);
				assertTrue(group.second.isStraight || group.second.r >= 2);
			}
		}
	}
}
