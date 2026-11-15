package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Looking for turn back rails beyond a platform's far end doesn't come back round to the platform's other end through a
 * crossover and on to the rails in front of the station (0.84.0; 0.83.15 only left out the platform rail itself).
 */
class TurnBackBeyondTest {

	private static final BlockPos NEAR = new BlockPos(0, 64, 0);
	private static final BlockPos FAR = new BlockPos(0, 64, 20);
	private static final BlockPos BEYOND = new BlockPos(0, 64, 60);
	private static final BlockPos BEYOND_END = new BlockPos(0, 64, 100);
	private static final BlockPos CROSSOVER = new BlockPos(4, 64, 60);
	private static final BlockPos OTHER_TRACK = new BlockPos(4, 64, -40);
	private static final BlockPos IN_FRONT = new BlockPos(0, 64, -40);
	private static final BlockPos IN_FRONT_END = new BlockPos(0, 64, -80);

	@Test
	void theSearchBeyondTheFarEndDoesNotComeRoundToTheStationsFront() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		// The platform, the line on beyond it to a turn back rail, and a crossover beyond it back past the station on the
		// other track, which joins the line in front of the platform
		add(rails, NEAR, FAR, RailType.PLATFORM);
		add(rails, FAR, BEYOND, RailType.IRON);
		add(rails, BEYOND, BEYOND_END, RailType.TURN_BACK);
		add(rails, BEYOND, CROSSOVER, RailType.IRON);
		add(rails, CROSSOVER, OTHER_TRACK, RailType.IRON);
		add(rails, OTHER_TRACK, NEAR, RailType.IRON);
		add(rails, NEAR, IN_FRONT, RailType.IRON);
		add(rails, IN_FRONT, IN_FRONT_END, RailType.TURN_BACK);

		final Set<BlockPos> reached = DutyPathBuilder.reachedFrom(FAR, Collections.singleton(NEAR), rails);
		assertTrue(reached.contains(BEYOND_END), "the turn back rail beyond the station");
		assertTrue(reached.contains(OTHER_TRACK));
		assertFalse(reached.contains(NEAR), "back to the platform's other end");
		assertFalse(reached.contains(IN_FRONT_END), "the turn back rail in front of the station");

		// Another platform of the station on the way round is never passed either
		final Set<BlockPos> blocked = new HashSet<>();
		blocked.add(NEAR);
		blocked.add(OTHER_TRACK);
		assertFalse(DutyPathBuilder.reachedFrom(FAR, blocked, rails).contains(OTHER_TRACK));
	}

	private static void add(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos from, BlockPos to, RailType railType) {
		final boolean forward = to.getZ() > from.getZ();
		rails.computeIfAbsent(from, key -> new HashMap<>()).put(to, new Rail(from, RailAngle.fromAngle(forward ? 90 : 270), to, RailAngle.fromAngle(forward ? 270 : 90), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(to, key -> new HashMap<>()).put(from, new Rail(to, RailAngle.fromAngle(forward ? 270 : 90), from, RailAngle.fromAngle(forward ? 90 : 270), railType, TransportMode.TRAIN));
	}
}
