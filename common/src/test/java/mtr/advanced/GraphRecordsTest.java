package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.network.RailKeys;
import transitsim.network.SectionType;
import transitsim.network.TrackSections;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MTR's rails as the track section layer takes them (stages document round 1).
 */
class GraphRecordsTest {

	private static BlockPos pos(int x, int z) {
		return new BlockPos(x, 64, z);
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, int x1, int x2, int z, RailType forwards, RailType backwards) {
		final BlockPos start = pos(x1, z);
		final BlockPos end = pos(x2, z);
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.E, end, RailAngle.W, forwards, TransportMode.TRAIN));
		if (backwards != null) {
			rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.W, start, RailAngle.E, backwards, TransportMode.TRAIN));
		}
	}

	@Test
	void eachRailOnceWithTheWaysItCanBeRun() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		addRail(rails, 0, 20, 0, RailType.IRON, RailType.IRON);
		// One way, laid from the higher node: the way back is NONE
		addRail(rails, 40, 20, 0, RailType.OBSIDIAN, RailType.NONE);
		addRail(rails, 40, 60, 0, RailType.PLATFORM, RailType.PLATFORM);
		final UUID platformKey = RailKeys.of(pos(40, 0).asLong(), pos(60, 0).asLong());
		final Map<UUID, TrackSections.Rail> trackRails = GraphRecords.toTrackRails(rails, Collections.singletonMap(platformKey, 7L));
		assertEquals(3, trackRails.size());

		final TrackSections.Rail twoWay = trackRails.get(RailKeys.of(pos(0, 0).asLong(), pos(20, 0).asLong()));
		assertTrue(twoWay.forwards && twoWay.backwards);
		assertEquals(80, twoWay.speedKmh);
		assertEquals(85, twoWay.getPhysicalKmh());

		final TrackSections.Rail oneWay = trackRails.get(RailKeys.of(pos(20, 0).asLong(), pos(40, 0).asLong()));
		// Typed by the way it can be run, not the way back
		assertEquals(120, oneWay.speedKmh);
		assertTrue(oneWay.canAccelerate);
		final boolean fortyLower = pos(40, 0).asLong() < pos(20, 0).asLong();
		assertEquals(fortyLower, oneWay.forwards);
		assertEquals(!fortyLower, oneWay.backwards);

		final TrackSections.Rail platform = trackRails.get(platformKey);
		assertEquals(SectionType.PLATFORM, platform.type);
		assertEquals(7, platform.savedRailId);
		assertFalse(platform.canAccelerate);

		// The same rails kept the other way round give the same shape
		final Map<BlockPos, Map<BlockPos, Rail>> reversed = new HashMap<>();
		addRail(reversed, 20, 0, 0, RailType.IRON, RailType.IRON);
		final Rail rail = reversed.get(pos(20, 0)).get(pos(0, 0));
		reversed.clear();
		reversed.computeIfAbsent(pos(20, 0), key -> new HashMap<>()).put(pos(0, 0), new Rail(pos(20, 0), RailAngle.W, pos(0, 0), RailAngle.E, RailType.IRON, TransportMode.TRAIN));
		reversed.computeIfAbsent(pos(0, 0), key -> new HashMap<>()).put(pos(20, 0), new Rail(pos(0, 0), RailAngle.E, pos(20, 0), RailAngle.W, RailType.IRON, TransportMode.TRAIN));
		assertNotNull(rail);
		assertEquals(twoWay, GraphRecords.toTrackRails(reversed, Collections.emptyMap()).get(RailKeys.of(pos(0, 0).asLong(), pos(20, 0).asLong())));
	}

	@Test
	void checkingTheRailsOfALargeNetworkForAChangeIsQuick() {
		// 200 lines of 100 rails, about as many as 64 copies of a line
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		for (int z = 0; z < 200; z++) {
			for (int x = 0; x < 100; x++) {
				addRail(rails, x * 20, x * 20 + 20, z * 10, RailType.IRON, RailType.IRON);
			}
		}
		final TrackSections sections = new TrackSections();
		sections.sync(GraphRecords.toTrackRails(rails, Collections.emptyMap()), Collections.emptyMap());
		final long[] best = {Long.MAX_VALUE};
		for (int i = 0; i < 20; i++) {
			// One rail made faster and back
			final RailType railType = i % 2 == 0 ? RailType.DIAMOND : RailType.IRON;
			addRail(rails, 1000, 1020, 50, railType, railType);
			final long start = System.nanoTime();
			final TrackSections.Change change = sections.sync(GraphRecords.toTrackRails(rails, Collections.emptyMap()), Collections.emptyMap());
			best[0] = Math.min(best[0], System.nanoTime() - start);
			assertEquals(1, change.added.size());
			assertEquals(1, change.removed.size());
		}
		System.out.println("Checking " + rails.size() + " nodes of rails for one change: " + best[0] / 1000 + " us at best");
		assertTrue(best[0] < 200_000_000L);
	}
}
