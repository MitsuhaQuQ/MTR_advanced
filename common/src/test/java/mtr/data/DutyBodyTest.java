package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.plan.DutyPlan;
import transitsim.plan.TurnBackSection;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A duty's body the usual way round a turn back point and another way, as a siding makes the paths of its duties.
 */
class DutyBodyTest {

	/**
	 * Platform 1, then platform 3 at the end of the first route, a turn back rail, and platform 4 where the second route
	 * starts, then platform 5. The other way round has two rails in place of the turn back rail.
	 */
	private static final PathData P1 = rail(0, 20, RailType.PLATFORM, 1, 40);
	private static final PathData RUN_1 = rail(20, 200, RailType.DIAMOND, 0, 0);
	private static final PathData P3 = rail(200, 220, RailType.PLATFORM, 3, 40);
	private static final PathData TURN = rail(220, 260, RailType.TURN_BACK, 0, 1);
	private static final PathData P4 = rail(260, 280, RailType.PLATFORM, 4, 40);
	private static final PathData RUN_2 = rail(280, 400, RailType.DIAMOND, 0, 0);
	private static final PathData P5 = rail(400, 420, RailType.PLATFORM, 5, 40);

	@Test
	void theOtherWayRoundReplacesTheUsualOneAndMovesWhatComesAfter() {
		final List<PathData> body = Arrays.asList(P1, RUN_1, P3, TURN, P4, RUN_2, P5);
		// The way round starts on the stop and ends on the next first stop, which the search found with stop times of its own
		final List<PathData> usualWay = Arrays.asList(rail(200, 220, RailType.PLATFORM, 3, 0), TURN, rail(260, 280, RailType.PLATFORM, 4, 0));
		final List<PathData> otherWay = Arrays.asList(rail(200, 220, RailType.PLATFORM, 3, 0), rail(220, 240, RailType.DIAMOND, 0, 0), rail(240, 300, RailType.TURN_BACK, 0, 1), rail(260, 280, RailType.PLATFORM, 4, 0));
		final DutyPlan<PathData, SavedRailBase> dutyPlan = new DutyPlan<>("duty", Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), body, new int[]{0, 4}, null, null, false, false);
		final List<TurnBackSection<PathData>> sections = Collections.singletonList(new TurnBackSection<>(2, Arrays.asList(usualWay, otherWay), 0));
		final List<PathData> wayOut = Arrays.asList(rail(-40, 0, RailType.SIDING, 0, 0), rail(0, 20, RailType.PLATFORM, 1, 0));
		final int[] points = {3};

		final Siding.DutyBody usual = new Siding.DutyBody(dutyPlan, sections, -1, -1);
		final List<PathData> usualPath = usual.join(wayOut);
		assertEquals(8, usualPath.size());
		assertArrayEquals(new int[]{1, 5}, usual.getStarts(wayOut));
		assertArrayEquals(new int[]{-1, -1, -1, 3}, usual.getTurnBackStops(points, 4, wayOut));
		// The first stop keeps the body's stop time, not the one of the way out
		assertEquals(40, usualPath.get(1).dwellTime);

		final Siding.DutyBody other = new Siding.DutyBody(dutyPlan, sections, 0, 1);
		final List<PathData> otherPath = other.join(wayOut);
		assertEquals(9, otherPath.size());
		// The stop before the point and the next route's first stop are the body's own, with their stop times
		assertEquals(40, otherPath.get(3).dwellTime);
		assertTrue(otherPath.get(4).isSameRail(otherWay.get(1)));
		assertTrue(otherPath.get(5).isSameRail(otherWay.get(2)));
		assertEquals(40, otherPath.get(6).dwellTime);
		assertTrue(otherPath.get(6).isSameRail(P4));
		assertTrue(otherPath.get(8).isSameRail(P5));
		assertArrayEquals(new int[]{1, 6}, other.getStarts(wayOut));
		assertArrayEquals(new int[]{-1, -1, -1, 3}, other.getTurnBackStops(points, 4, wayOut));
		// Builders share templates; final indexed paths own their numbered rail views.
		List<PathData> indexedUsual = PathPieces.indexDuty(usualPath, usual.getIndexBoundaries(wayOut), 1);
		List<PathData> indexedOther = PathPieces.indexDuty(otherPath, other.getIndexBoundaries(wayOut), 1);
		assertNotSame(indexedUsual.get(3), indexedOther.get(3));
		int otherStop = indexedOther.get(3).stopIndex;
		indexedUsual.get(3).stopIndex = 12345;
		assertEquals(otherStop, indexedOther.get(3).stopIndex);
		assertEquals(0, P3.stopIndex);
	}

	private static PathData rail(int startZ, int endZ, RailType railType, long savedRailBaseId, int dwellTime) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, 0);
	}
}
