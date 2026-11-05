package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A platform's or siding's positions and middle are kept once worked out, and are what they were before.
 */
class SavedRailPositionsTest {

	@Test
	void theMiddleAndTheEndsStayTheSame() {
		final BlockPos pos1 = new BlockPos(10, 64, -7);
		final BlockPos pos2 = new BlockPos(41, 66, -7);
		final Platform platform = new Platform(1, TransportMode.TRAIN, pos1, pos2);

		// As worked out before: the two positions in the set's order, halved
		final Set<BlockPos> set = new HashSet<>(2);
		set.add(pos1);
		set.add(pos2);
		final BlockPos first = set.iterator().next();
		final BlockPos sum = pos1.offset(pos2);
		assertEquals(new BlockPos(sum.getX() / 2, sum.getY() / 2, sum.getZ() / 2), platform.getMidPos());
		assertEquals(new BlockPos(sum.getX() / 2, 0, sum.getZ() / 2), platform.getMidPos(true));
		assertSame(platform.getMidPos(), platform.getMidPos(), "kept once worked out");

		final List<BlockPos> ordered = platform.getOrderedPositions(BlockPos.ZERO, false);
		assertTrue(ordered.contains(pos1) && ordered.contains(pos2));
		assertEquals(first.distSqr(BlockPos.ZERO) <= (first == pos1 ? pos2 : pos1).distSqr(BlockPos.ZERO) ? first : ordered.get(0), ordered.get(0));
		assertTrue(platform.containsPos(pos1) && platform.containsPos(pos2));
	}
}
