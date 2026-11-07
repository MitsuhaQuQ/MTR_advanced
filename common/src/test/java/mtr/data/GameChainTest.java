package mtr.data;

import mtr.advanced.SimExport;
import mtr.path.DeterministicPathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.sim.EventSimulator;
import transitsim.sim.RunInvariants;
import transitsim.sim.SimDataset;
import transitsim.sim.SimEvent;
import transitsim.sim.TickRunner;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The end-to-end check of the chain the game runs (2026-11-07): the game finds the paths of a depot's duties on its own
 * rails, as path generation does, they are handed to the component as the game hands them, and the component runs the
 * trains through a morning. Sixteen duties, each a round trip from the depot, run every quarter of an hour on a double
 * track line with a terminal turning back beyond its two platforms; every train keeps to the rules all morning, every
 * duty runs, and every train is back.
 */
class GameChainTest {

	private static final long DOWN = 10, UP = 11;
	private static final long S_DOWN = 1, M_DOWN = 2, A = 3, B = 4, M_UP = 5, S_UP = 6;
	private static final long MINUTE = 60000, HOUR = 60 * MINUTE;
	/**
	 * Monday the 5th of January 2026 at midnight in UTC.
	 */
	private static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	private static final int DUTIES = 16;

	@org.junit.jupiter.api.BeforeAll
	static void bootstrap() {
		// The siding makes trains, which have their cargo, which needs the game's registries
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void theGamesPathsRunAMorningInTheComponent() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());

		// The siding, and the lead north from it to the down track and from the up track back into it
		addRail(rails, pos(0, -160), pos(0, -40), RailType.SIDING);
		addRail(rails, pos(0, -40), pos(0, 0), RailType.IRON);
		addRail(rails, pos(8, 0), pos(0, -40), RailType.IRON);
		// The down track: the south station, the middle one, then the terminal's two platforms joined at both ends, and the
		// turn back rail beyond them
		final Platform southDown = platform(dataCache, rails, S_DOWN, pos(0, 0), pos(0, 160));
		addRail(rails, pos(0, 160), pos(0, 800), RailType.IRON);
		final Platform middleDown = platform(dataCache, rails, M_DOWN, pos(0, 800), pos(0, 960));
		addRail(rails, pos(0, 960), pos(0, 1600), RailType.IRON);
		addRail(rails, pos(0, 1600), pos(-4, 1640), RailType.IRON);
		addRail(rails, pos(0, 1600), pos(4, 1640), RailType.IRON);
		final Platform a = platform(dataCache, rails, A, pos(-4, 1640), pos(-4, 1800));
		final Platform b = platform(dataCache, rails, B, pos(4, 1640), pos(4, 1800));
		addRail(rails, pos(-4, 1800), pos(0, 1840), RailType.IRON);
		addRail(rails, pos(4, 1800), pos(0, 1840), RailType.IRON);
		addRail(rails, pos(0, 1840), pos(0, 2100), RailType.TURN_BACK);
		// The up track, from the terminal's south turnout back to the siding
		addRail(rails, pos(0, 1600), pos(8, 1560), RailType.IRON);
		addRail(rails, pos(8, 1560), pos(8, 960), RailType.IRON);
		final Platform middleUp = platform(dataCache, rails, M_UP, pos(8, 960), pos(8, 800));
		addRail(rails, pos(8, 800), pos(8, 160), RailType.IRON);
		final Platform southUp = platform(dataCache, rails, S_UP, pos(8, 160), pos(8, 0));

		final Route down = route(dataCache, DOWN, southDown, middleDown, a);
		final Route up = route(dataCache, UP, b, middleUp, southUp);
		for (int i = 0; i < 16; i++) {
			down.timetable.services.add((int) (6 * HOUR + i * 15 * MINUTE));
			up.timetable.services.add((int) (6 * HOUR + 20 * MINUTE + i * 15 * MINUTE));
		}
		down.timetable.enabled = true;
		up.timetable.enabled = true;
		down.timetable.sanitize();
		up.timetable.sanitize();

		final Siding siding = new Siding(100, TransportMode.TRAIN, pos(0, -160), pos(0, -40), 120);
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.corner1 = new Tuple<>(-50, -200);
		depot.corner2 = new Tuple<>(50, -20);
		depot.routeIds.add(DOWN);
		depot.routeIds.add(UP);
		dataCache.depotIdMap.put(depot.id, depot);

		// Each duty down and back once, from the depot and back to it, a quarter of an hour after the one before; between the
		// two the train waits on the turn back rail
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final List<DepotDuties.Slot> slots = new ArrayList<>();
		for (int duty = 0; duty < DUTIES; duty++) {
			final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
			day.weekdays = DepotDuties.ALL_WEEKDAYS;
			final int offset = (int) (duty * 15 * MINUTE);
			day.segments.add(new DepotDuties.Segment(DOWN, (int) (6 * HOUR) + offset, 0, -1));
			day.segments.add(new DepotDuties.Segment(UP, (int) (6 * HOUR + 20 * MINUTE) + offset, 0, -1));
			final DepotDuties.Slot slot = new DepotDuties.Slot();
			slot.days.add(day);
			slots.add(slot);
		}
		duties.sidings.put(siding.id, slots);

		// The game finds the paths
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		final List<String> failed = new ArrayList<>();
		depot.generateDutyPathsNow(duties, dataCache, rails, graph, Collections.singleton(siding), false, failed::add);
		assertEquals(Collections.emptyList(), failed);

		// Handed over as the game hands them
		final SimDataset dataset = new SimDataset();
		dataset.routes.add(new SimDataset.RouteData(down));
		dataset.routes.add(new SimDataset.RouteData(up));
		final SimDataset.DepotData depotData = new SimDataset.DepotData(depot.id, new long[]{DOWN, UP}, false, duties.toBytes());
		depotData.dutiesOnly = true;
		depotData.sidingOrder = new long[]{siding.id};
		dataset.depots.add(depotData);
		final SimDataset.SidingData sidingData = siding.toSimulation(depot.id, new long[]{DOWN, UP}, path -> SimExport.toTracks(path, rails), true);
		assertNotNull(sidingData, "the siding has a vehicle and a path");
		dataset.sidings.add(sidingData);
		final byte[] bytes = dataset.toBytes();

		// The component runs the morning
		final long start = MONDAY + 5 * HOUR + 30 * MINUTE;
		final long end = MONDAY + 11 * HOUR;
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, start));
		final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(simulator.getRunner()).setStuckMillis(10 * MINUTE);
		for (long time = start; time < end; time += 1000) {
			simulator.advanceTo(time);
			invariants.check();
		}
		assertEquals(Collections.emptyList(), invariants.getViolations());
		assertEquals(Collections.emptyMap(), invariants.getStuck());
		final List<SimEvent> events = simulator.events(0);
		assertTrue(events.stream().noneMatch(event -> event.kind == SimEvent.Kind.DEADLOCK), "no deadlock");
		// Every duty run, out and back, stopping at the six platforms of its round trip
		final Map<Long, Long> arrivals = events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE).collect(Collectors.groupingBy(event -> event.trainId, TreeMap::new, Collectors.counting()));
		final long arrived = events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE).count();
		final long returned = events.stream().filter(event -> event.kind == SimEvent.Kind.RETURNED).count();
		assertEquals(DUTIES, returned, "duties run: " + arrivals + "; trains " + simulator.getRunner().getTrains().stream().map(train -> train.id + " on " + train.isOnRoute() + " head " + train.getHeadRail() + "/" + train.getPath().size() + " progress " + Math.round(train.getRailProgress()) + " duty " + train.service.isOnDuty() + " deploy " + train.canDeploy()).collect(Collectors.joining("; ")) + "; shed " + simulator.getRunner().getShed(siding.id) + "; events " + events.stream().filter(event -> event.kind == SimEvent.Kind.RETURNED || event.kind == SimEvent.Kind.HELD || event.kind == SimEvent.Kind.CONFLICT).map(Object::toString).collect(Collectors.joining(", ")));
		assertTrue(arrived >= DUTIES * 6L, arrived + " stops: " + arrivals);
		final List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> trains = simulator.getRunner().getTrains();
		assertTrue(trains.stream().noneMatch(TickRunner.HeadlessTrain::isOnRoute), "all back");
	}

	private static BlockPos pos(int x, int z) {
		return new BlockPos(x, 64, z);
	}

	private static Platform platform(DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, long id, BlockPos start, BlockPos end) {
		addRail(rails, start, end, RailType.PLATFORM);
		final Platform platform = new Platform(id, TransportMode.TRAIN, start, end);
		dataCache.platformIdMap.put(id, platform);
		return platform;
	}

	private static Route route(DataCache dataCache, long id, Platform... platforms) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final Platform platform : platforms) {
			route.platformIds.add(new Route.RoutePlatform(platform.id));
		}
		dataCache.routeIdMap.put(id, route);
		return route;
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, RailType railType) {
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), railType, TransportMode.TRAIN));
	}
}
