package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties planned by duty groups (duty groups, step 2): each timetable's days of each group of sidings on their own, so a
 * change to the weekend's timetable plans the weekend again and keeps the weekdays' duties as they were, and the other way
 * round.
 */
class DutyGroupsKeepTest {

	private static final int MINUTE = 60000;
	private static final int WEEKDAYS = 0x1F;
	private static final int WEEKEND = 0x60;
	private static final Function<Route, long[][]> TIMES = route -> new long[][]{{0, 0}, {10 * MINUTE, 10 * MINUTE}};

	/**
	 * A route with a table for the weekdays, every half hour from 06:00 to 09:00, and one for the weekend.
	 */
	private static Route route(long id, long from, long to, int... weekendMinutes) {
		final Route route = new Route(id, TransportMode.TRAIN);
		route.lineName = "Line";
		route.platformIds.add(new Route.RoutePlatform(from));
		route.platformIds.add(new Route.RoutePlatform(to));
		route.timetable.enabled = true;
		route.timetable.weekdays = WEEKDAYS;
		for (int minutes = 6 * 60; minutes <= 9 * 60; minutes += 30) {
			route.timetable.services.add(minutes * MINUTE + (id == 11 ? 15 * MINUTE : 0));
		}
		final RouteTimetable weekend = new RouteTimetable();
		weekend.weekdays = WEEKEND;
		for (final int minutes : weekendMinutes) {
			weekend.services.add(minutes * MINUTE + (id == 11 ? 15 * MINUTE : 0));
		}
		route.timetable.addTable(weekend);
		return route;
	}

	/**
	 * The same siding before and after a change, as in a world.
	 */
	private static final Siding SIDING = new Siding(TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);

	private static final class World {

		private final DataCache dataCache;
		private final Set<Siding> sidings = new HashSet<>();

		private World(Route up, Route down) {
			final Depot depot = new Depot(1, TransportMode.TRAIN);
			depot.routeIds.add(up.id);
			depot.routeIds.add(down.id);
			depot.corner1 = new Tuple<>(-100, -100);
			depot.corner2 = new Tuple<>(100, 100);
			sidings.add(SIDING);
			// The two ends of the line, near the siding, to estimate the ways out and back
			final Set<Platform> platforms = new HashSet<>();
			platforms.add(new Platform(1, TransportMode.TRAIN, new BlockPos(0, 64, 20), new BlockPos(40, 64, 20)));
			platforms.add(new Platform(2, TransportMode.TRAIN, new BlockPos(0, 64, 60), new BlockPos(40, 64, 60)));
			dataCache = new DataCache(new HashSet<>(), platforms, sidings, new HashSet<>(Arrays.asList(up, down)), new HashSet<>(Collections.singleton(depot)), new HashSet<>());
			platforms.forEach(platform -> dataCache.platformIdMap.put(platform.id, platform));
			dataCache.routeIdMap.put(up.id, up);
			dataCache.routeIdMap.put(down.id, down);
			dataCache.depotIdMap.put(depot.id, depot);
		}

		private LineDuties.Unit unit() {
			return LineDuties.of(dataCache.depotIdMap.get(1L), dataCache, sidings);
		}
	}

	/**
	 * @return the duties of the days of these weekdays, by siding and slot
	 */
	private static List<String> dutiesOn(LineDuties.Plan plan, int mask) {
		final List<String> duties = new ArrayList<>();
		plan.sidings.forEach((sidingId, slots) -> {
			for (int i = 0; i < slots.size(); i++) {
				for (final DepotDuties.DayDuty day : slots.get(i).days) {
					if ((day.weekdays & mask) != 0) {
						duties.add(sidingId + "/" + i + ":" + day.toText());
					}
				}
			}
		});
		return duties;
	}

	@Test
	void eachTimetablesDaysAreADutyGroupOfTheirOwn() {
		final World world = new World(route(10, 1, 2, 7 * 60, 8 * 60), route(11, 2, 1, 7 * 60, 8 * 60));
		final LineDuties.Plan plan = world.unit().plan(TIMES, 0);
		assertFalse(plan.sidings.isEmpty(), plan.describe());
		assertEquals(2, plan.parts.size());
		final LineDuties.Plan read = LineDuties.Plan.fromBytes(plan.toBytes(), plan.lineNames);
		assertNotNull(read);
		assertEquals(2, read.parts.size());
		assertEquals(plan.parts.get(0).key, read.parts.get(0).key);
		assertEquals(plan.parts.get(1).basis, read.parts.get(1).basis);
		// Every duty lies within one timetable's days
		plan.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> assertTrue((day.weekdays & ~WEEKDAYS) == 0 || (day.weekdays & ~WEEKEND) == 0, day.toText()))));
	}

	@Test
	void aChangeToTheWeekendPlansOnlyTheWeekendAgain() {
		final World before = new World(route(10, 1, 2, 7 * 60, 8 * 60), route(11, 2, 1, 7 * 60, 8 * 60));
		final LineDuties.Plan planned = before.unit().plan(TIMES, 0);
		final Map<Long, byte[]> records = Collections.singletonMap(1L, planned.toBytes());

		// The weekend gets a service more
		final World after = new World(route(10, 1, 2, 7 * 60, 8 * 60, 9 * 60), route(11, 2, 1, 7 * 60, 8 * 60, 9 * 60));
		final LineDuties.Unit unit = after.unit();
		assertNotEquals(before.unit().basis, unit.basis);
		final List<String> why = new ArrayList<>();
		final LineDuties.Plan plan = unit.keep(records, planned.sidings, TIMES, 0, null, null, why::add);
		assertNotNull(plan, why.toString());
		assertFalse(plan.kept);
		assertEquals(1, plan.keptParts.size(), why.toString());
		assertEquals(1, plan.plannedParts.size());
		assertTrue(plan.keptParts.get(0).endsWith("|" + WEEKDAYS));
		assertEquals(dutiesOn(planned, WEEKDAYS), dutiesOn(plan, WEEKDAYS), "the weekdays' duties are kept as they were");
		assertNotEquals(dutiesOn(planned, WEEKEND), dutiesOn(plan, WEEKEND), "the weekend's are planned again");
		assertTrue(dutiesOn(plan, WEEKEND).stream().anyMatch(duty -> duty.contains("," + 9 * 60 * MINUTE + ",")), "with its new service");
		// What it found is kept for the next time
		final LineDuties.Plan read = LineDuties.Plan.fromBytes(plan.toBytes(), plan.lineNames);
		assertNotNull(read);
		assertEquals(2, read.parts.size());
	}

	@Test
	void aChangeToTheWeekdaysKeepsTheWeekend() {
		final World before = new World(route(10, 1, 2, 7 * 60, 8 * 60), route(11, 2, 1, 7 * 60, 8 * 60));
		final LineDuties.Plan planned = before.unit().plan(TIMES, 0);
		final Map<Long, byte[]> records = Collections.singletonMap(1L, planned.toBytes());
		final Route up = route(10, 1, 2, 7 * 60, 8 * 60), down = route(11, 2, 1, 7 * 60, 8 * 60);
		up.timetable.services.add(10 * 60 * MINUTE);
		down.timetable.services.add(10 * 60 * MINUTE + 15 * MINUTE);
		final World after = new World(up, down);
		final List<String> why = new ArrayList<>();
		final LineDuties.Plan plan = after.unit().keep(records, planned.sidings, TIMES, 0, null, null, why::add);
		assertNotNull(plan, why.toString());
		assertEquals(Collections.singletonList(planned.parts.get(1).key), plan.keptParts, why.toString());
		assertEquals(dutiesOn(planned, WEEKEND), dutiesOn(plan, WEEKEND));
	}

	@Test
	void aGroupAskedForIsPlannedAgainAloneAndTheGroupsGoToTheClients() {
		final World world = new World(route(10, 1, 2, 7 * 60, 8 * 60), route(11, 2, 1, 7 * 60, 8 * 60));
		final LineDuties.Plan planned = world.unit().plan(TIMES, 0);
		final String weekend = planned.parts.get(1).key;
		final List<String> why = new ArrayList<>();
		final LineDuties.Plan plan = world.unit().keep(Collections.singletonMap(1L, planned.toBytes()), planned.sidings, TIMES, 0, null, null, Collections.singleton(weekend), why::add);
		assertNotNull(plan, why.toString());
		assertFalse(plan.kept);
		assertEquals(Collections.singletonList(planned.parts.get(0).key), plan.keptParts, why.toString());
		assertEquals(Collections.singletonList(weekend), plan.plannedParts);
		assertEquals(dutiesOn(planned, WEEKDAYS), dutiesOn(plan, WEEKDAYS));

		// The clients get the groups with the depot's shortfall
		final Depot.Shortfall shortfall = new Depot.Shortfall(plan);
		final net.minecraft.network.FriendlyByteBuf packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		Depot.Shortfall.write(shortfall, packet);
		final Depot.Shortfall read = Depot.Shortfall.read(packet);
		assertNotNull(read);
		assertEquals(2, read.groups.size());
		assertTrue(read.groups.get(0).kept);
		assertFalse(read.groups.get(1).kept);
		assertEquals(WEEKEND, read.groups.get(1).getWeekdays());
		assertEquals(weekend, read.groups.get(1).key);
		assertFalse(read.groups.get(1).getLabel().contains("|"));
	}

	@Test
	void unchangedTheDutiesAreKeptWhole() {
		final World world = new World(route(10, 1, 2, 7 * 60, 8 * 60), route(11, 2, 1, 7 * 60, 8 * 60));
		final LineDuties.Plan planned = world.unit().plan(TIMES, 0);
		final List<String> why = new ArrayList<>();
		final LineDuties.Plan plan = world.unit().keep(Collections.singletonMap(1L, planned.toBytes()), planned.sidings, TIMES, 0, null, null, why::add);
		assertNotNull(plan, why.toString());
		assertTrue(plan.kept);
	}
}
