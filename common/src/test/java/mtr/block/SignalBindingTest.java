package mtr.block;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which node a signal is bound to (0.99.0, design 7.6).
 */
class SignalBindingTest {

	private static void connect(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos from, RailAngle facingFrom, BlockPos to, RailAngle facingTo) {
		rails.computeIfAbsent(from, key -> new HashMap<>()).put(to, new Rail(from, facingFrom, to, facingTo, RailType.IRON, TransportMode.TRAIN));
		rails.computeIfAbsent(to, key -> new HashMap<>()).put(from, new Rail(to, facingTo, from, facingFrom, RailType.IRON, TransportMode.TRAIN));
	}

	@Test
	void theNearestNodeAheadWithARailLeavingTheWayTrainsRun() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		// A straight line west to east along z = 0, nodes at x = -30, -6, 3, 7 and 30
		connect(rails, new BlockPos(-30, 64, 0), RailAngle.E, new BlockPos(-6, 64, 0), RailAngle.W);
		connect(rails, new BlockPos(-6, 64, 0), RailAngle.E, new BlockPos(3, 64, 0), RailAngle.W);
		connect(rails, new BlockPos(3, 64, 0), RailAngle.E, new BlockPos(7, 64, 0), RailAngle.W);
		connect(rails, new BlockPos(7, 64, 0), RailAngle.E, new BlockPos(30, 64, 0), RailAngle.W);
		final BlockPos signal = new BlockPos(0, 65, 2);

		// Facing east: trains run east past it, so the node ahead at x = 3, not the nearer-by-nothing one behind at -6
		final float east = SignalBinding.angleOf(Direction.EAST, 0);
		assertEquals(new BlockPos(3, 64, 0), SignalBinding.findNode(rails, signal, east));
		// The way back: the node at -6, behind the signal the way trains run east, is ahead of it going west
		final float west = SignalBinding.angleOf(Direction.EAST, 1);
		assertEquals(new BlockPos(-6, 64, 0), SignalBinding.findNode(rails, signal, west));
		// Too far: nothing within reach
		assertNull(SignalBinding.findNode(rails, new BlockPos(18, 65, 20), east));
	}

	@Test
	void aNodeWithNoRailLeavingThatWayIsNotTaken() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		// The line ends at x = 3: no rail leaves it east
		connect(rails, new BlockPos(-20, 64, 0), RailAngle.E, new BlockPos(3, 64, 0), RailAngle.W);
		final float east = SignalBinding.angleOf(Direction.EAST, 0);
		assertNull(SignalBinding.findNode(rails, new BlockPos(0, 65, 2), east));
		assertFalse(SignalBinding.leadsOn(rails, new BlockPos(3, 64, 0), east));
		assertTrue(SignalBinding.leadsOn(rails, new BlockPos(3, 64, 0), SignalBinding.angleOf(Direction.EAST, 1)));
	}

	@Test
	void tiesGoTheSameWayEveryTime() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		// Two parallel tracks the same distance from the signal
		connect(rails, new BlockPos(2, 64, -2), RailAngle.E, new BlockPos(20, 64, -2), RailAngle.W);
		connect(rails, new BlockPos(2, 64, 2), RailAngle.E, new BlockPos(20, 64, 2), RailAngle.W);
		final float east = SignalBinding.angleOf(Direction.EAST, 0);
		final BlockPos first = SignalBinding.findNode(rails, new BlockPos(0, 64, 0), east);
		assertNotNull(first);
		for (int i = 0; i < 5; i++) {
			assertEquals(first, SignalBinding.findNode(new HashMap<>(rails), new BlockPos(0, 64, 0), east));
		}
	}
}
