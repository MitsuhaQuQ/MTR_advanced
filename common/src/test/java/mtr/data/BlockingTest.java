package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingTest {

	private static final List<Double> DISTANCES = Arrays.asList(10D, 20D, 30D, 40D, 50D);

	@Test
	void findIndexMatchesLinearSearch() {
		final Random random = new Random(1);
		for (int round = 0; round < 200; round++) {
			final List<Double> distances = new ArrayList<>();
			double sum = 0;
			final int size = random.nextInt(20) + 1;
			for (int i = 0; i < size; i++) {
				sum += random.nextInt(3);
				distances.add(sum);
			}
			for (double progress = -1; progress <= sum + 1; progress += 0.5) {
				assertEquals(linearIndex(distances, progress, false), Train.findIndex(distances, size, progress, false));
				assertEquals(linearIndex(distances, progress, true), Train.findIndex(distances, size, progress, true));
			}
		}
	}

	@Test
	void blockedRailBeyondBrakingDistanceIsIgnored() {
		assertEquals(-1, Train.findBlockedIndex(DISTANCES, 0, 4, 5, 10, index -> index == 3));
	}

	@Test
	void blockedRailWithinBrakingDistanceIsFound() {
		assertEquals(3, Train.findBlockedIndex(DISTANCES, 0, 4, 5, 30, index -> index == 3));
	}

	@Test
	void railsAfterTheNextStopAreIgnored() {
		assertEquals(-1, Train.findBlockedIndex(DISTANCES, 0, 2, 5, 100, index -> index == 3));
	}

	@Test
	void nextRailIsAlwaysChecked() {
		assertEquals(1, Train.findBlockedIndex(DISTANCES, 0, 0, 5, 0, index -> index == 1));
	}

	@Test
	void trainInATakenSignalBlockStillRegistersItsRail() {
		final UUID rail1 = rail(0, 1);
		final UUID rail2 = rail(1, 2);
		final SignalBlocks signalBlocks = new SignalBlocks();
		signalBlocks.add(1, DyeColor.RED, rail1);
		signalBlocks.add(0, DyeColor.RED, rail2);

		final List<Map<UUID, Long>> trainPositions = new ArrayList<>();
		trainPositions.add(new HashMap<>());
		trainPositions.add(new HashMap<>());

		signalBlocks.occupy(rail1, trainPositions, 1);
		assertEquals(1L, trainPositions.get(1).get(rail2));

		final UUID rail3 = rail(5, 6);
		signalBlocks.add(0, DyeColor.RED, rail3);
		signalBlocks.occupy(rail3, trainPositions, 2);
		assertEquals(2L, trainPositions.get(1).get(rail3));
		assertFalse(trainPositions.get(1).containsValue(3L));
		assertTrue(trainPositions.get(1).containsKey(rail1));
	}

	@Test
	void normalTicksStayAtOneStepDespiteJitter() {
		final TickCompensator tickCompensator = new TickCompensator();
		long millis = 1000;
		float sum = tickCompensator.nextTick(millis);
		for (int i = 0; i < 100; i++) {
			millis += i % 2 == 0 ? 40 : 60;
			sum += tickCompensator.nextTick(millis);
		}
		assertEquals(101, sum, 1);
	}

	@Test
	void slowServerCatchesUpWithRealTime() {
		final TickCompensator tickCompensator = new TickCompensator();
		long millis = 1000;
		tickCompensator.nextTick(millis);
		float sum = 0;
		for (int i = 0; i < 100; i++) {
			millis += 100;
			sum += tickCompensator.nextTick(millis);
		}
		assertEquals(200, sum, 1);
	}

	@Test
	void longPauseIsNotCaughtUpAtOnce() {
		final TickCompensator tickCompensator = new TickCompensator();
		tickCompensator.nextTick(1000);
		assertEquals(TickCompensator.MAX_TICKS_ELAPSED, tickCompensator.nextTick(61000));
	}

	private static UUID rail(int x1, int x2) {
		return new UUID(new BlockPos(x1, 0, 0).asLong(), new BlockPos(x2, 0, 0).asLong());
	}

	private static int linearIndex(List<Double> distances, double progress, boolean roundDown) {
		for (int i = 0; i < distances.size(); i++) {
			if (progress < distances.get(i) || roundDown && progress == distances.get(i)) {
				return i;
			}
		}
		return distances.size() - 1;
	}
}
