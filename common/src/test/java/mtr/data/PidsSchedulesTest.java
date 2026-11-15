package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.DisplayArrivals;
import transitsim.plan.DutyPlan;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PidsSchedulesTest {

	@Test
	void pendingDutyUsesItsPathAndTimetableAndExcludesPassingAndSuspendedServices() {
		final TransportMode mode = TransportMode.TRAIN;
		final Depot depot = new Depot(1, mode);
		final Route route = new Route(2, mode);
		route.platformIds.add(new Route.RoutePlatform(10));
		route.platformIds.add(new Route.RoutePlatform(11));
		route.platformIds.add(new Route.RoutePlatform(12));
		route.platformIds.get(1).pass = true;

		final long now = DutyShapes.getDayStart(System.currentTimeMillis()) + 60000;
		final long service = now + 120000;
		final int timeOfDay = (int) Math.floorMod(service, (long) OperatingDay.MILLIS_PER_DAY);
		route.timetable.services.add(timeOfDay);

		final Map<String, TripPath<PathData>> paths = new HashMap<>();
		final Siding siding = new Siding(3, mode, BlockPos.ZERO, new BlockPos(20, 0, 0), 20) {
			@Override
			public int getTrainCars() {
				return 8;
			}

			@Override
			public Map<String, TripPath<PathData>> getTripPaths() {
				return paths;
			}
		};
		final DataCache cache = new DataCache(Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
		cache.routeIdMap.put(route.id, route);
		cache.sidingIdMap.put(siding.id, siding);
		cache.sidingIdToDepot.put(siding.id, depot);
		for (long platform = 10; platform <= 12; platform++) {
			cache.platformIdMap.put(platform, new Platform(platform, mode, BlockPos.ZERO, new BlockPos(20, 0, 0)));
		}

		final DepotDuties.DayDuty duty = new DepotDuties.DayDuty();
		duty.weekdays = 1 << OperatingDay.getWeekday(service);
		duty.segments.add(new DepotDuties.Segment(route.id, timeOfDay, 0, -1));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(duty);
		depot.duties.diagramMode = true;
		depot.duties.sidings.put(siding.id, Collections.singletonList(slot));
		final String key = DutyShapes.getSlotKey(depot.duties.getSlots(siding.id), 0, OperatingDay.getWeekday(service), cache.routeIdMap::get);
		final List<PathData> rails = Arrays.asList(
				new PathData(null, 10, 10, BlockPos.ZERO, BlockPos.ZERO, 1),
				new PathData(null, 11, 0, BlockPos.ZERO, BlockPos.ZERO, 2),
				new PathData(null, 12, 10, BlockPos.ZERO, BlockPos.ZERO, 3));
		final int[] starts = {0};
		final DutyPlan<PathData, SavedRailBase> plan = new DutyPlan<>(key, Collections.singletonList(route.id), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), rails, starts, null, null, false, false);
		paths.put(key, TripPath.ofDuty(plan, starts, rails, StopTimes.of(new long[]{0, 10, 0, 20}, new long[]{1, 11, 100, 100}, new long[]{2, 12, 200, 220})));

		final Map<Long, List<DisplayArrivals.Arrival>> arrivals = planned(depot, cache, now, 1);
		assertEquals(service, arrivals.get(10L).get(0).estimateMillis);
		// The pass told as a pass, at the platform the path has for it
		assertTrue(arrivals.get(11L).get(0).stop.pass);
		assertEquals(service + 10000, arrivals.get(12L).get(0).estimateMillis);
		// No train in the depot for it: not told
		assertTrue(planned(depot, cache, now, 0).isEmpty());
		// Suspended as an edit does it, as a new revision
		route.timetable.suspended.add(timeOfDay);
		route.timetable.revision++;
		assertTrue(planned(depot, cache, now, 1).isEmpty());
	}

	private static Map<Long, List<DisplayArrivals.Arrival>> planned(Depot depot, DataCache cache, long now, int idleTrains) {
		final List<PidsSchedules.Built> built = PidsSchedules.build(PidsSchedules.gather(Collections.singleton(depot), cache, now));
		final List<DisplayArrivals.Duty> duties = new ArrayList<>();
		final Map<Object, Integer> idle = new HashMap<>();
		built.forEach(each -> {
			duties.add(each.duty);
			idle.put(each.duty.pool, idleTrains);
		});
		return DisplayArrivals.compute(duties, Collections.emptyList(), idle, duty -> false, now, now + OperatingDay.MILLIS_PER_DAY).arrivals;
	}

	@Test
	void plannedArrivalsFillTheGapsAndTheTrainsOwnReplaceThem() {
		final ScheduleEntry plan = new ScheduleEntry(120000, 8, 2, 0, 0, false, 120000, true, false);
		final List<ScheduleEntry> planned = Collections.singletonList(plan);
		final ScheduleEntry standby = new ScheduleEntry(70000, 8, 2, 0, 9, false, 0, false, true);
		final List<ScheduleEntry> standbyEntries = Collections.singletonList(standby);
		assertEquals(Collections.singletonList(plan), PidsSchedules.forDisplay(standbyEntries, planned, 60000));
		assertEquals(Collections.singletonList(standby), standbyEntries);
		assertTrue(PidsSchedules.forDisplay(null, planned, 120000).isEmpty());

		// The train's own estimate of the same service, by route, stop and planned time, in place of the plan's
		final ScheduleEntry live = new ScheduleEntry(130000, 8, 2, 0, 9, false, 120000);
		assertEquals(Collections.singletonList(live), PidsSchedules.forDisplay(Collections.singletonList(live), planned, 60000));
		// Another service of the plan stays
		final ScheduleEntry later = new ScheduleEntry(720000, 8, 2, 0, 9, false, 720000, false, false);
		assertEquals(Arrays.asList(live, later), PidsSchedules.forDisplay(Collections.singletonList(live), Arrays.asList(plan, later), 60000));
	}

	@Test
	void thePacketPreservesWhetherAnArrivalIsPlanned() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		try {
			new ScheduleEntry(120000, 8, 2, 0, 0, false, 120000, true, false).writePacket(packet);
			new ScheduleEntry(130000, 8, 2, 0, 9, false, 120000).writePacket(packet);
			assertTrue(new ScheduleEntry(packet).planned);
			final ScheduleEntry live = new ScheduleEntry(packet);
			assertFalse(live.planned);
			assertEquals(130000, live.arrivalMillis);
			assertEquals(0, packet.readableBytes());
		} finally {
			packet.release();
		}
	}
}
