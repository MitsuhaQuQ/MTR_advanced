package mtr.render;

import mtr.block.BlockEscalatorBase.EnumEscalatorOrientation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The moving steps of the original escalator (0.99.0): a step that crosses into the next block has the same height on
 * both. A block reads its front line only from just past its back to half a step past its front, so only the block
 * ahead has to agree.
 */
class RenderEscalatorStepTest {

	/** Each pair: this block, the block ahead, and how much higher the block ahead stands, as the original escalator places them */
	private static final Object[][] NEIGHBOURS = {
			{EnumEscalatorOrientation.FLAT, EnumEscalatorOrientation.FLAT, 0},
			{EnumEscalatorOrientation.FLAT, EnumEscalatorOrientation.TRANSITION_BOTTOM, 0},
			{EnumEscalatorOrientation.TRANSITION_BOTTOM, EnumEscalatorOrientation.SLOPE, 1},
			{EnumEscalatorOrientation.SLOPE, EnumEscalatorOrientation.SLOPE, 1},
			{EnumEscalatorOrientation.SLOPE, EnumEscalatorOrientation.TRANSITION_TOP, 1},
			{EnumEscalatorOrientation.TRANSITION_TOP, EnumEscalatorOrientation.FLAT, 0},
	};

	@Test
	void stepsMeetAcrossBlocks() {
		for (final Object[] pair : NEIGHBOURS) {
			final EnumEscalatorOrientation here = (EnumEscalatorOrientation) pair[0];
			final EnumEscalatorOrientation ahead = (EnumEscalatorOrientation) pair[1];
			final int rise = (Integer) pair[2];
			for (float u = 1; u <= 1 + RenderEscalatorStep.PITCH; u += 0.05F) {
				assertEquals(RenderEscalatorStep.frontLine(here, u), RenderEscalatorStep.frontLine(ahead, u - 1) + rise, 1E-5, here + " -> " + ahead + " at " + u);
			}
		}
	}

	@Test
	void stepsMoveUpOrDown() {
		final float up1 = RenderEscalatorStep.phase(100, 0, true), up2 = RenderEscalatorStep.phase(104, 0, true);
		assertEquals(0.25F, (up2 - up1 + 1) % 1, 1E-5);
		final float down1 = RenderEscalatorStep.phase(100, 0, false), down2 = RenderEscalatorStep.phase(104, 0, false);
		assertEquals(0.75F, (down2 - down1 + 1) % 1, 1E-5);
		assertEquals(0, RenderEscalatorStep.phase(160, 0, true), 1E-5);
	}
}
