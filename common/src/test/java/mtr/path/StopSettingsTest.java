package mtr.path;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.TransportMode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
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
	void turnBackWaitBelongsToTheRail() {
		final Rail rail = new Rail(START, RailAngle.fromAngle(90), END, RailAngle.fromAngle(270), RailType.TURN_BACK, TransportMode.TRAIN);
		assertEquals(1, rail.getTurnBackDwellTime());

		rail.turnBackDwell = 120;
		assertEquals(120, rail.getTurnBackDwellTime());

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		assertEquals(120, new Rail(packet).turnBackDwell);
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

	@Test
	void stopDwellFollowsTheRouteSettings() {
		final Route.RoutePlatform routePlatform = new Route.RoutePlatform(PLATFORM_ID);
		assertEquals(40, routePlatform.getStopDwell(true, 40, false));
		routePlatform.dwellOverride = 60;
		assertEquals(60, routePlatform.getStopDwell(true, 40, false));
		routePlatform.pass = true;
		assertEquals(0, routePlatform.getStopDwell(true, 40, false));
		assertEquals(60, routePlatform.getStopDwell(false, 40, false));
	}

	private static int applyTo(int dwellTime, TransportMode transportMode) {
		final Rail rail = new Rail(START, RailAngle.fromAngle(90), END, RailAngle.fromAngle(270), RailType.PLATFORM, transportMode);
		final List<PathData> path = new ArrayList<>();
		path.add(new PathData(rail, PLATFORM_ID, dwellTime, START, END, 0));
		PathFinder.applyStopSettings(path, Collections.singletonList(new Route.RoutePlatform(PLATFORM_ID)));
		return path.get(0).dwellTime;
	}
}
