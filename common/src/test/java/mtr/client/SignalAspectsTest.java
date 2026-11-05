package mtr.client;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a signal shows, counted in block sections ahead: a long rail is several sections, and track the client doesn't
 * know counts as taken (decoupling document 5.4, 2b).
 */
class SignalAspectsTest {

	/**
	 * A signal at z 0 facing along z: a short rail to z 20, then 1000 blocks of 80 km/h rail, cut into 5 sections.
	 */
	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final BlockPos MIDDLE = new BlockPos(0, 64, 20);
	private static final BlockPos END = new BlockPos(0, 64, 1020);
	private static final float FACING = 90;

	private static void addRail(BlockPos start, BlockPos end, RailType railType) {
		ClientData.RAILS.computeIfAbsent(start, pos -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		ClientData.RAILS.computeIfAbsent(end, pos -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}

	private static void line() {
		ClientData.RAILS.clear();
		ClientData.MAP_RAILS.clear();
		ClientData.OCCUPIED_SECTIONS = new HashMap<>();
		addRail(START, MIDDLE, RailType.IRON);
		addRail(MIDDLE, END, RailType.IRON);
	}

	@AfterEach
	void clear() {
		ClientData.RAILS.clear();
		ClientData.MAP_RAILS.clear();
		ClientData.OCCUPIED_SECTIONS = null;
	}

	@Test
	void aSignalCountsTheSectionsOfALongRail() {
		line();
		assertEquals(0, SignalAspects.getAspect(START, FACING, 4));
		// The second piece of the long rail is the third section past the signal
		ClientData.OCCUPIED_SECTIONS.put(PathData.getRailProduct(MIDDLE, END), new HashSet<>(Collections.singletonList(1)));
		assertEquals(3, SignalAspects.getAspect(START, FACING, 4));
		// A three aspect signal sees only two sections, so it shows clear
		assertEquals(0, SignalAspects.getAspect(START, FACING, 3));
		// The short rail taken is the first section
		ClientData.OCCUPIED_SECTIONS.put(PathData.getRailProduct(START, MIDDLE), new HashSet<>(Collections.singletonList(0)));
		assertEquals(1, SignalAspects.getAspect(START, FACING, 4));
	}

	@Test
	void trackKnownOnlyFromTheMapCountsAsTaken() {
		line();
		// Past the end of the rails the client has, the map shows more track
		ClientData.MAP_RAILS.add(new ClientData.MapRail(END, new BlockPos(0, 64, 2000), TransportMode.TRAIN));
		// 1 short rail and 5 pieces clear, so the track beyond is the seventh section; a 4 aspect signal still shows clear
		assertEquals(0, SignalAspects.getAspect(START, FACING, 4));
		ClientData.RAILS.remove(END);
		ClientData.RAILS.get(MIDDLE).remove(END);
		ClientData.MAP_RAILS.add(new ClientData.MapRail(MIDDLE, END, TransportMode.TRAIN));
		// Without the long rail, what is beyond the short one isn't known
		assertEquals(2, SignalAspects.getAspect(START, FACING, 4));
	}

	@Test
	void noRailThatWayShowsNothing() {
		line();
		assertEquals(-1, SignalAspects.getAspect(START, FACING + 180, 3));
	}
}
