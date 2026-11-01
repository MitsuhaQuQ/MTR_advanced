package mtr.path;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopSettingsTest {

	private static final long PLATFORM_ID = 1;
	private static final long NEXT_PLATFORM_ID = 2;
	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final BlockPos END = new BlockPos(0, 64, 60);

	@Test
	void shortStopIsRaisedSoDoorsCanOpen() {
		assertEquals(Route.MIN_STOP_DWELL, applyTo(1, TransportMode.TRAIN));
		assertEquals(40, applyTo(40, TransportMode.TRAIN));
	}

	@Test
	void cableCarKeepsItsStopTime() {
		assertEquals(1, applyTo(1, TransportMode.CABLE_CAR));
	}

	@Test
	void turnBackWaitsOnlyBeforeTheNextPlatform() {
		final Rail rail = new Rail(START, RailAngle.fromAngle(90), END, RailAngle.fromAngle(270), RailType.TURN_BACK, TransportMode.TRAIN);
		final List<PathData> path = new ArrayList<>();
		path.add(new PathData(rail, PLATFORM_ID, 40, START, END, 0));
		path.add(new PathData(rail, 0, 1, START, END, 1));
		path.add(new PathData(rail, NEXT_PLATFORM_ID, 40, START, END, 1));
		path.add(new PathData(rail, 0, 1, START, END, 2));

		final Route.RoutePlatform routePlatform = new Route.RoutePlatform(PLATFORM_ID);
		routePlatform.turnBackDwell = 120;
		final List<Route.RoutePlatform> routePlatforms = new ArrayList<>();
		routePlatforms.add(routePlatform);
		routePlatforms.add(new Route.RoutePlatform(NEXT_PLATFORM_ID));
		PathFinder.applyStopSettings(path, routePlatforms);

		assertEquals(120, path.get(1).dwellTime);
		assertEquals(1, path.get(3).dwellTime);
	}

	@Test
	void onlyPlatformsRunThroughCountAsPassing() {
		final Rail platformRail = new Rail(START, RailAngle.fromAngle(90), END, RailAngle.fromAngle(270), RailType.PLATFORM, TransportMode.TRAIN);
		final List<PathData> path = new ArrayList<>();
		path.add(new PathData(platformRail, PLATFORM_ID, 40, START, END, 0));
		path.add(new PathData(platformRail, PLATFORM_ID, 0, END, START, 0));
		path.add(new PathData(platformRail, NEXT_PLATFORM_ID, 0, START, END, 1));
		assertFalse(Siding.isPassingPlatform(path, 0));
		assertFalse(Siding.isPassingPlatform(path, 1));
		assertTrue(Siding.isPassingPlatform(path, 2));
	}

	private static int applyTo(int dwellTime, TransportMode transportMode) {
		final Rail rail = new Rail(START, RailAngle.fromAngle(90), END, RailAngle.fromAngle(270), RailType.PLATFORM, transportMode);
		final List<PathData> path = new ArrayList<>();
		path.add(new PathData(rail, PLATFORM_ID, dwellTime, START, END, 0));
		PathFinder.applyStopSettings(path, Collections.singletonList(new Route.RoutePlatform(PLATFORM_ID)));
		return path.get(0).dwellTime;
	}
}
