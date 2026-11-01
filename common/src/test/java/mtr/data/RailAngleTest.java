package mtr.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class RailAngleTest {

	@Test
	void railsStayOnOppositeSidesAtEveryNewAngle() {
		for (int degrees = 0; degrees < 360; degrees += 5) {
			final RailAngle angle = RailAngle.fromAngle(degrees);
			for (int oldDegrees = 0; oldDegrees < 360; oldDegrees += 5) {
				final RailAngle oldFacing = RailAngle.fromAngle(oldDegrees);
				final RailAngle first = angle.getRotatedFacing(oldFacing, oldFacing);
				final RailAngle second = angle.getRotatedFacing(oldFacing, oldFacing.getOpposite());
				assertSame(first.getOpposite(), second, degrees + " from " + oldDegrees);
			}
		}
	}

	@Test
	void floatAndDoubleAnglesGiveTheSameInstance() {
		for (float degrees = -720; degrees < 720; degrees += 0.25F) {
			assertSame(RailAngle.fromAngle((double) degrees), RailAngle.fromAngle(degrees), String.valueOf(degrees));
		}
	}

	@Test
	void facingStaysOnTheNearSideWhenTurningSlightly() {
		assertSame(RailAngle.fromAngle(10), RailAngle.fromAngle(10).getRotatedFacing(RailAngle.E, RailAngle.E));
		assertSame(RailAngle.fromAngle(190), RailAngle.fromAngle(10).getRotatedFacing(RailAngle.E, RailAngle.W));
	}
}
