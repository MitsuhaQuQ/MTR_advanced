package mtr.path;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.Route;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StopSettingsTest {

	private static final long PLATFORM_ID = 1;

	@Test
	void shortStopIsRaisedSoDoorsCanOpen() {
		assertEquals(Route.MIN_STOP_DWELL, applyTo(1, TransportMode.TRAIN));
		assertEquals(40, applyTo(40, TransportMode.TRAIN));
	}

	@Test
	void cableCarKeepsItsStopTime() {
		assertEquals(1, applyTo(1, TransportMode.CABLE_CAR));
	}

	private static int applyTo(int dwellTime, TransportMode transportMode) {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(0, 64, 60);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), RailType.PLATFORM, transportMode);
		final List<PathData> path = new ArrayList<>();
		path.add(new PathData(rail, PLATFORM_ID, dwellTime, start, end, 0));
		PathFinder.applyStopSettings(path, Collections.singletonList(new Route.RoutePlatform(PLATFORM_ID)));
		return path.get(0).dwellTime;
	}
}
