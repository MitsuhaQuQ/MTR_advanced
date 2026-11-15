package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import transitsim.plan.DutyPlan;
import transitsim.plan.DutyShapes;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBackSection;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A siding whose duty turns back in front of its terminus many times a day (站前折返, the user's 明珠线 at 田端, platforms
 * 6 and 5 in turn) keeps the other way round once, with which turn takes it, in its save; the path of a train taking it at
 * a turn is made then and is the whole copy {@link Siding.DutyBody} made for every turn before (0.84.0).
 */
class SidingTurnBackWaysTest {

	private static final int TURNS = 160;

	@org.junit.jupiter.api.BeforeAll
	static void bootstrap() {
		// The siding makes its default train, which has its cargo, which needs the game's registries
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void theDutyKeepsOnePathAndTheOtherWayOnce() throws IOException {
		final Duty duty = new Duty(TURNS);
		final Siding siding = newSiding();
		// Warm up, then measure
		duty.generate(newSiding());
		final long start = System.nanoTime();
		duty.generate(siding);
		final long newNanos = System.nanoTime() - start;
		assertEquals(Collections.singleton(duty.plan.key), siding.getTripPaths().keySet());
		assertEquals(1, siding.getTurnBackWays().size());

		// As before: a whole copy of the day for each turn
		final long oldStart = System.nanoTime();
		final List<TripPath<PathData>> wholeCopies = new ArrayList<>();
		for (int point = 0; point < TURNS; point++) {
			wholeCopies.add(duty.wholeCopy(siding, point));
		}
		final long oldNanos = System.nanoTime() - oldStart + newNanos;

		final int newBytes = saved(siding).length;
		int oldBytes = newBytes;
		for (final TripPath<PathData> wholeCopy : wholeCopies) {
			oldBytes += written(wholeCopy);
		}
		final TripPath<PathData> base = siding.getTripPaths().get(duty.plan.key);
		final long spliceStart = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			assertNotNull(TripPath.withTurnBackWay(base, i * 7, 1, siding.getPathVariants()));
		}
		final double spliceMillis = (System.nanoTime() - spliceStart) / 20 / 1e6;
		System.out.printf("Siding with a duty of %d front turn-backs (%d rails): path generation before %d ms (%d paths), now %d ms (1 path, 1 way); siding saved before about %d KB, now %d KB; one other way made on demand %.2f ms%n",
				TURNS, base.path.size(), oldNanos / 1000000, TURNS + 1, newNanos / 1000000, oldBytes / 1024, newBytes / 1024, spliceMillis);
		assertTrue(newBytes * 20 < oldBytes, "the save no longer holds a copy of the day per turn");
	}

	@Test
	void aTrainTakingTheOtherWayRunsTheWholeCopyMadeBefore() {
		final Duty duty = new Duty(12);
		final Siding siding = newSiding();
		duty.generate(siding);
		final TripPath<PathData> base = siding.getTripPaths().get(duty.plan.key);
		for (final int point : new int[]{0, 1, 6, 11}) {
			assertSamePath(duty.wholeCopy(siding, point), TripPath.withTurnBackWay(base, point, 1, siding.getPathVariants()));
		}
	}

	@Test
	void theWaysAreSavedAndReadBackAndOldWholeCopiesDropped() throws IOException {
		final Duty duty = new Duty(12);
		final Siding siding = newSiding();
		duty.generate(siding);
		final Map<String, Value> map = savedMap(siding);
		final Siding read = new Siding(map);
		assertEquals(1, read.getTurnBackWays().size());
		assertEquals(siding.getTurnBackWays().get(0).size(), read.getTurnBackWays().get(0).size());
		assertEquals(siding.getTripPaths().keySet(), read.getTripPaths().keySet());
		// The path of a train taking the other way at a turn is made from what was read back
		final TripPath<PathData> readBase = read.getTripPaths().get(duty.plan.key);
		final TripPath<PathData> made = TripPath.withTurnBackWay(readBase, 4, 1, read.getPathVariants());
		assertNotNull(made);
		assertEquals(describe(duty.wholeCopy(siding, 4).path), describe(made.path));

		// A save from before 0.84.0 has no ways but a whole copy for each turn, which are left out when read
		map.remove("turn_back_ways");
		map.remove("turn_back_way_points");
		final List<Value> savedPaths = new ArrayList<>(map.get("trip_paths").asArrayValue().list());
		final TripPath<PathData> wholeCopy = duty.wholeCopy(siding, 4);
		map.put("trip_paths", value(packer -> {
			packer.packArrayHeader(savedPaths.size() + 1);
			for (final Value savedPath : savedPaths) {
				packer.packValue(savedPath);
			}
			packer.packMapHeader(TripPath.WRITTEN_VALUES + 1);
			wholeCopy.write(new mtr.advanced.MessagePackKeyed.Writer(packer));
			PathPieces.writePath(packer, wholeCopy.path, "path");
		}));
		final Siding old = new Siding(map);
		assertTrue(old.getTurnBackWays().isEmpty());
		assertEquals(Collections.singleton(duty.plan.key), old.getTripPaths().keySet());
		assertNull(TripPath.withTurnBackWay(old.getTripPaths().get(duty.plan.key), 4, 1, old.getPathVariants()));
	}

	private interface PackerWrite {
		void accept(MessageBufferPacker packer) throws IOException;
	}

	private static Value value(PackerWrite write) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		write.accept(packer);
		packer.close();
		return MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue();
	}

	private static Map<String, Value> savedMap(Siding siding) throws IOException {
		final org.msgpack.core.MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(saved(siding));
		final Map<String, Value> map = new HashMap<>(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext());
		return map;
	}

	private static byte[] saved(Siding siding) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(siding.messagePackLength());
		siding.toMessagePack(packer);
		packer.close();
		return packer.toByteArray();
	}

	/**
	 * @return how many bytes the siding's save gave a trip path before 0.84.0 kept in full
	 */
	private static int written(TripPath<PathData> tripPath) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(TripPath.WRITTEN_VALUES + 1);
		tripPath.write(new mtr.advanced.MessagePackKeyed.Writer(packer));
		PathPieces.writePath(packer, tripPath.path, "path");
		packer.close();
		return packer.toByteArray().length;
	}

	private static Siding newSiding() {
		return new Siding(100, TransportMode.TRAIN, new BlockPos(0, 64, -100), new BlockPos(0, 64, -60), 40);
	}

	private static void assertSamePath(TripPath<PathData> expected, TripPath<PathData> actual) {
		assertNotNull(actual);
		assertEquals(expected.key, actual.key);
		assertEquals(expected.turnBackPoint, actual.turnBackPoint);
		assertEquals(expected.turnBackWay, actual.turnBackWay);
		assertArrayEquals(expected.dutyStarts, actual.dutyStarts);
		assertArrayEquals(expected.turnBackStops, actual.turnBackStops);
		assertEquals(describe(expected.path), describe(actual.path));
		assertEquals(expected.timeSegments.size(), actual.timeSegments.size());
		for (int i = 0; i < expected.timeSegments.size(); i++) {
			final TimeSegment expectedSegment = expected.timeSegments.get(i);
			assertEquals(expectedSegment.endTime, actual.timeSegments.get(i).endTime, "time segment " + i);
		}
		assertEquals(expected.stopTimes.getDurationMillis(), actual.stopTimes.getDurationMillis());
	}

	private static List<String> describe(List<PathData> path) {
		final List<String> lines = new ArrayList<>();
		path.forEach(pathData -> lines.add(pathData.startingPos.toShortString() + ">" + pathData.getLastPos().toShortString() + "@" + pathData.savedRailBaseId + "/" + pathData.dwellTime + "#" + pathData.stopIndex));
		return lines;
	}

	private static PathData rail(int x, int startZ, int endZ, RailType railType, long savedRailBaseId, int dwellTime) {
		final BlockPos start = new BlockPos(x, 64, startZ);
		final BlockPos end = new BlockPos(x, 64, endZ);
		final boolean forward = endZ > startZ;
		final Rail rail = new Rail(start, RailAngle.fromAngle(forward ? 90 : 270), end, RailAngle.fromAngle(forward ? 270 : 90), railType, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, 0);
	}

	/**
	 * Up from platform 1 by 2 to 3, turning back on it, and down by 5 to 4, again and again. The other way round at each
	 * turn runs from platform 2 into platform 9 of the same station and back out to platform 5, a rail longer.
	 */
	private static final class Duty {

		final List<PathData> body = new ArrayList<>();
		final List<TurnBackSection<PathData>> sections = new ArrayList<>();
		final DutyPlan<PathData, SavedRailBase> plan;

		Duty(int turns) {
			final List<Integer> starts = new ArrayList<>();
			final List<Long> routeIds = new ArrayList<>();
			for (int turn = 0; turn < turns; turn++) {
				starts.add(body.size());
				routeIds.add(1L);
				body.add(rail(0, 0, 20, RailType.PLATFORM, 1, 40));
				body.add(rail(0, 20, 200, RailType.IRON, 0, 0));
				final int stop = body.size();
				body.add(rail(0, 200, 220, RailType.PLATFORM, 2, 40));
				body.add(rail(0, 220, 400, RailType.IRON, 0, 0));
				body.add(rail(0, 400, 420, RailType.PLATFORM, 3, 40));
				starts.add(body.size());
				routeIds.add(2L);
				body.add(rail(0, 420, 400, RailType.PLATFORM, 0, 0));
				body.add(rail(4, 400, 220, RailType.IRON, 0, 0));
				body.add(rail(4, 220, 200, RailType.PLATFORM, 5, 40));
				final List<PathData> usual = new ArrayList<>(body.subList(stop, body.size()));
				// Found again for each turn, the same rails each time
				final List<PathData> other = Arrays.asList(rail(0, 200, 220, RailType.PLATFORM, 2, 0), rail(8, 220, 300, RailType.IRON, 0, 0), rail(8, 300, 400, RailType.IRON, 0, 0),
						rail(8, 400, 420, RailType.PLATFORM, 9, 40), rail(8, 420, 400, RailType.PLATFORM, 0, 0), rail(8, 400, 220, RailType.IRON, 0, 0), rail(4, 220, 200, RailType.PLATFORM, 5, 0));
				sections.add(new TurnBackSection<>(stop, Arrays.asList(usual, other), 0));
				body.add(rail(4, 200, 20, RailType.IRON, 0, 0));
				body.add(rail(4, 20, 0, RailType.PLATFORM, 4, 40));
				body.add(rail(4, 0, -20, RailType.IRON, 0, 0));
			}
			plan = new DutyPlan<>(DutyShapes.KEY_PREFIX + "0|test>stay", routeIds, new HashMap<>(), new HashMap<>(), new HashMap<>(), body, starts.stream().mapToInt(Integer::intValue).toArray(), null, null, true, true);
			plan.segmentsKnown = true;
			plan.turnBackSections.addAll(sections);
		}

		void generate(Siding siding) {
			siding.generateDutyRoute(Runnable::run, new HashMap<>(), null, 0, false, Collections.singletonList(plan), Collections.emptyList());
		}

		/**
		 * The whole path for taking the other way at a turn, as the siding kept it for every turn before 0.84.0.
		 */
		TripPath<PathData> wholeCopy(Siding siding, int point) {
			final List<PathData> wayOut = Collections.emptyList();
			final int[] points = new int[sections.size()];
			for (int i = 0; i < points.length; i++) {
				points[i] = i;
			}
			final Siding.DutyBody other = new Siding.DutyBody(plan, sections, point, 1);
			final List<PathData> otherPath = other.join(wayOut);
			final int[] starts = other.getStarts(wayOut);
			final TripPath<PathData> tripPath = new TripPath<>(plan, starts, PathPieces.indexDuty(otherPath, other.getIndexBoundaries(wayOut), starts.length == 0 ? 0 : starts[0]), TripPath.getTurnBackKey(plan.key, point, 1), point, 1, other.getTurnBackStops(points, points.length, wayOut));
			tripPath.generateTimes(siding.getPathVariants().getTimeGenerator());
			return tripPath;
		}
	}
}
