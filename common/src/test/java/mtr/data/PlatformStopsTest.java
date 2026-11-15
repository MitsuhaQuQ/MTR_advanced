package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import transitsim.plan.StopPositions;
import transitsim.plan.StopTimes;
import transitsim.sim.SimDataset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where trains stop on a platform (optimisation round 4, stop points), as the game has it: saved, sent and updated with
 * the platform, looked up by paths, and timed by both ways of timing a path the same way the component runs it.
 */
class PlatformStopsTest {

	private static final Object SOURCE = new Object();
	private static final Platform FIRST = new Platform(1, TransportMode.TRAIN, pos(100), pos(300));
	private static final Platform SECOND = new Platform(2, TransportMode.TRAIN, pos(1000), pos(1200));

	@AfterEach
	void clear() {
		FIRST.stopPosition = StopPositions.EXIT_END;
		SECOND.stopPosition = StopPositions.EXIT_END;
		PlatformStops.update(SOURCE, Collections.emptySet());
	}

	@Test
	void aPlatformsSettingGivesItsRailAStopOffset() {
		final List<PathData> path = path();
		final PathData platform = path.get(2);
		assertEquals(0, platform.getStopOffset(60), 1E-9);

		setAll(StopPositions.CENTRED);
		// The 60 long train in the middle of the 200 long platform
		assertEquals(70, platform.getStopOffset(60), 1E-9);
		setAll(10);
		assertEquals(10, platform.getStopOffset(60), 1E-9);
		// Plain track never has one
		assertEquals(0, path.get(1).getStopOffset(60), 1E-9);

		// The component works it out the same from the setting the game hands it
		final SimDataset.DataTrack track = new SimDataset.DataTrack(1, 2, platform.getTrackLength(), true, false, 1, 20, 0, 1, false, true, false);
		assertEquals(platform.getStopOffset(60), track.withStopPosition(10).getStopOffset(60), 1E-9);
		setAll(StopPositions.CENTRED);
		assertEquals(platform.getStopOffset(60), track.withStopPosition(StopPositions.CENTRED).getStopOffset(60), 1E-9);
	}

	@Test
	void theSettingIsSavedSentAndUpdated() throws IOException {
		final Platform platform = new Platform(7, TransportMode.TRAIN, pos(0), pos(200));
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		platform.setStopPosition(StopPositions.CENTRED, sent::add);

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(platform.messagePackLength());
		platform.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertEquals(StopPositions.CENTRED, new Platform(map).stopPosition);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		platform.writePacket(packet);
		assertEquals(StopPositions.CENTRED, new Platform(packet).stopPosition);

		final Platform updated = new Platform(7, TransportMode.TRAIN, pos(0), pos(200));
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertEquals(StopPositions.CENTRED, updated.stopPosition);

		// An old save has none: the exit end, as always
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("stop_position");
		assertEquals(StopPositions.EXIT_END, new Platform(old).stopPosition);
	}

	@Test
	void bothWaysOfTimingAPathStopWhereThePlatformHasIt() {
		for (final boolean useSpeedCurve : new boolean[]{true, false}) {
			final PathTiming.Vehicle vehicle = new PathTiming.Vehicle(100, 6, 10, 0.01F, 0.02F, useSpeedCurve, TransportMode.TRAIN);
			final StopTimes atEnd = PathTiming.calculateStopTimes(vehicle, path());
			setAll(StopPositions.CENTRED);
			final StopTimes centred = PathTiming.calculateStopTimes(vehicle, path());
			clear();
			// Stopping 70 short of the end of the first platform, the train gets there sooner
			assertTrue(centred.getLeadMillis() < atEnd.getLeadMillis(), useSpeedCurve + ": " + centred.getLeadMillis() + " " + atEnd.getLeadMillis());
			// From one centred stop to the next is as far as from one end to the next: about as long
			final long runAtEnd = atEnd.getRunMillis(2, 4);
			final long runCentred = centred.getRunMillis(2, 4);
			assertTrue(runCentred > 0 && Math.abs(runCentred - runAtEnd) < 3000, useSpeedCurve + ": " + runCentred + " " + runAtEnd);
		}
	}

	@Test
	void turningBackAtACentredStopPutsTheHeadWhereTheTailWas() {
		final List<PathData> path = new ArrayList<>(path().subList(0, 3));
		// Back along the same platform, without a stop, then back along the track before it
		path.add(pathData(300, 100, RailType.PLATFORM, FIRST.id, 0));
		path.add(pathData(100, 0, RailType.IRON, 0, 20));
		setAll(StopPositions.CENTRED);
		for (final boolean useSpeedCurve : new boolean[]{true, false}) {
			final PathTiming.Vehicle vehicle = new PathTiming.Vehicle(100, 6, 10, 0.01F, 0.02F, useSpeedCurve, TransportMode.TRAIN);
			final List<transitsim.plan.TimeSegment> segments = new ArrayList<>();
			PathTiming.generateTimeSegments(vehicle, path, segments, new HashMap<>(), new StopTimes(), 0, 0);
			// After the 100 long siding and lead, the platform ends 400 along the path: the first run ends 70 short of it,
			// and the next starts at 400 + 70 + 60 along the path back, where the old tail was
			final double stoppedAt = segments.stream().mapToDouble(segment -> segment.endRailProgress).filter(end -> end <= 400).max().orElse(0);
			final double setOff = segments.stream().mapToDouble(segment -> segment.startRailProgress).filter(start -> start > 400).min().orElse(0);
			assertEquals(330, stoppedAt, 1E-6, String.valueOf(useSpeedCurve));
			assertEquals(530, setOff, 1E-6, String.valueOf(useSpeedCurve));
		}
	}

	private static void setAll(int setting) {
		FIRST.stopPosition = setting;
		SECOND.stopPosition = setting;
		PlatformStops.update(SOURCE, Arrays.asList(FIRST, SECOND));
	}

	/**
	 * A siding, a lead, the first platform, the line and the second platform, all along the z axis.
	 */
	private static List<PathData> path() {
		final List<PathData> path = new ArrayList<>();
		path.add(pathData(-100, 0, RailType.SIDING, 0, 0));
		path.add(pathData(0, 100, RailType.IRON, 0, 0));
		path.add(pathData(100, 300, RailType.PLATFORM, FIRST.id, 20));
		path.add(pathData(300, 1000, RailType.IRON, 0, 0));
		path.add(pathData(1000, 1200, RailType.PLATFORM, SECOND.id, 20));
		path.add(pathData(1200, 1300, RailType.IRON, 0, 0));
		return path;
	}

	private static PathData pathData(int from, int to, RailType railType, long stopId, int dwellTime) {
		final boolean north = to < from;
		return new PathData(new Rail(pos(from), north ? RailAngle.N : RailAngle.S, pos(to), north ? RailAngle.S : RailAngle.N, railType, TransportMode.TRAIN), stopId, dwellTime, pos(from), pos(to), 0);
	}

	private static BlockPos pos(int z) {
		return new BlockPos(0, 64, z);
	}
}
