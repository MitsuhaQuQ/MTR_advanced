package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ways round a turn back point, as written into a siding's data and read back.
 */
class TurnBackPointTest {

	private static final List<UUID> TURN_1 = Collections.singletonList(new UUID(1, 2));
	private static final List<UUID> TURN_2 = Collections.singletonList(new UUID(3, 4));
	private static final List<UUID> WAY_1 = Arrays.asList(new UUID(5, 6), new UUID(1, 2), new UUID(7, 8));
	private static final List<UUID> WAY_2 = Arrays.asList(new UUID(5, 6), new UUID(3, 4), new UUID(9, 10), new UUID(7, 8));

	@Test
	void readsBackEveryRailOfEachWay() {
		final TurnBackPoint point = new TurnBackPoint(Arrays.asList(TURN_1, null, TURN_2), new int[]{2, 0, 3}, Arrays.asList(WAY_1, null, WAY_2), 0);
		final TurnBackPoint read = TurnBackPoint.read(point.write());
		assertEquals(0, read.defaultWay);
		assertEquals(3, read.getWayCount());
		assertEquals(TURN_1, read.getRails(0));
		assertNull(read.getRails(1));
		assertEquals(TURN_2, read.getRails(2));
		assertEquals(WAY_1, read.getWayRails(0));
		assertNull(read.getWayRails(1));
		assertEquals(WAY_2, read.getWayRails(2));
		assertEquals(3, read.getLength(2));
	}

	@Test
	void readsValuesWrittenBeforeTheWaysWereKeptWithTheTurnBackRailsAsTheWays() {
		final TurnBackPoint point = new TurnBackPoint(Arrays.asList(TURN_1, TURN_2), new int[]{2, 3}, Arrays.asList(WAY_1, WAY_2), 1);
		final long[] values = point.write();
		// Without the ways: the default way, the count, then each way's length, rail count and rails
		final long[] older = Arrays.copyOf(values, 2 + 2 * (2 + 2));
		final TurnBackPoint read = TurnBackPoint.read(older);
		assertEquals(1, read.defaultWay);
		assertEquals(TURN_2, read.getRails(1));
		assertEquals(TURN_1, read.getWayRails(0));
		assertEquals(TURN_2, read.getWayRails(1));
	}
}
