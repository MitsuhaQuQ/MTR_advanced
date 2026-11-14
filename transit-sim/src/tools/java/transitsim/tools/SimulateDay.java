package transitsim.tools;

import transitsim.plan.OperatingDay;
import transitsim.sim.DayRun;
import transitsim.sim.EventSimulator;
import transitsim.sim.RunInvariants;
import transitsim.sim.SimDataset;
import transitsim.sim.SimEvent;
import transitsim.sim.SimScope;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Simulates one operating day of a saved world without the game (S4e), from what the game writes into its database
 * ({@link SimDataset}), and prints what the trains did and how long it took.
 * <p>
 * Arguments: the database, then optionally the day ({@code yyyy-mm-dd}, today if left out), the time zone ({@code UTC}
 * if left out), the hour the operating day starts at (3 if left out), and the flags {@code --events} to print every event
 * and {@code --check} for the end-to-end check: the rules no train may break ({@link RunInvariants}) looked at every
 * second, and how the day went against the timetables ({@link DayRun}); it exits with 1 if a rule was broken or the
 * trains deadlocked.
 */
public final class SimulateDay {

	private static final long HOUR = 60 * 60 * 1000;

	private SimulateDay() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("Usage: SimulateDay <mtr.db> [yyyy-mm-dd] [time zone] [day start hour] [--events] [--check] [--origins]");
			return;
		}
		final List<String> flags = new ArrayList<>();
		final List<String> values = new ArrayList<>();
		for (final String arg : args) {
			(arg.startsWith("--") ? flags : values).add(arg);
		}
		final boolean printEvents = flags.contains("--events");
		final boolean check = flags.contains("--check");
		final int count = values.size();
		final LocalDate day = count > 1 ? LocalDate.parse(values.get(1)) : LocalDate.now();
		final String timeZone = count > 2 ? values.get(2) : "UTC";
		final int dayStartHour = count > 3 ? Integer.parseInt(values.get(3)) : OperatingDay.DEFAULT_DAY_START_HOUR;
		OperatingDay.set(timeZone, dayStartHour);

		byte[] data = read(values.get(0));
		if (data == null) {
			System.out.println("The database has no simulation data. Save the world with this version of the mod first.");
			return;
		}
		SimDataset dataset;
		try {
			dataset = SimDataset.fromBytes(data);
		} catch (Exception e) {
			// Saved by an older version: the world needs saving with this one, as upgrading resets what isn't its map
			System.out.println("UNREADABLE simulation data, saved by another version: " + e);
			return;
		}
		// -Dmtr.noLinePools=true runs without trains going back to other depots of their line, to compare
		if (Boolean.getBoolean("mtr.noLinePools")) {
			dataset.sidings.forEach(siding -> siding.linePool = "");
			data = dataset.toBytes();
		}
		// -Dmtr.earlyThreshold=millis runs the day with another early threshold (round 4, step 5), to compare
		final String earlyThreshold = System.getProperty("mtr.earlyThreshold", "");
		if (!earlyThreshold.isEmpty()) {
			dataset.earlyThresholdMillis = Long.parseLong(earlyThreshold);
			data = dataset.toBytes();
		}
		System.out.printf("Early threshold %d ms%n", dataset.earlyThresholdMillis);
		// -Dmtr.buffersFile=path: the ways out adopted as a depot does on its own (2026-11-13), read from the file before the
		// day and, with --outs, written back with what was measured longer, so running again is the next day after adopting
		final String buffersFile = System.getProperty("mtr.buffersFile", "");
		final Map<String, Long> adoptedOuts = new TreeMap<>();
		if (!buffersFile.isEmpty() && new java.io.File(buffersFile).isFile()) {
			for (final String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(buffersFile))) {
				final String[] parts = line.trim().split("=");
				if (parts.length == 2) {
					adoptedOuts.put(parts[0], Long.parseLong(parts[1]));
				}
			}
			final Map<Long, Long> sidingDepots = new HashMap<>();
			dataset.sidings.forEach(siding -> sidingDepots.put(siding.id, siding.depotId));
			for (final SimDataset.DepotData depot : dataset.depots) {
				final Map<String, Long> millis = new TreeMap<>(depot.addedBuffers.getMillis());
				adoptedOuts.forEach((key, value) -> {
					if (Objects.equals(sidingDepots.get(transitsim.plan.Buffers.parse(key)[1]), depot.id)) {
						millis.merge(key, value, Math::max);
					}
				});
				depot.addedBuffers = millis.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(millis);
			}
			data = dataset.toBytes();
			System.out.println("Adopted ways out: " + adoptedOuts);
		}
		// -Dmtr.simRoutes=id,id simulates only those routes' part of the world, as the editor does (SimScope);
		// -Dmtr.simRoutes=scopes lists how big that part is for each depot's routes, without simulating
		final String simRoutes = System.getProperty("mtr.simRoutes", "");
		if (simRoutes.equals("scopes")) {
			printScopes(dataset);
			return;
		}
		if (!simRoutes.isEmpty()) {
			final Set<Long> routeIds = new TreeSet<>();
			for (final String id : simRoutes.split(",")) {
				routeIds.add(Long.parseLong(id.trim()));
			}
			final SimScope scope = SimScope.of(dataset, routeIds);
			System.out.printf("Routes %s: %d of %d depots, %d of %d sidings%n", routeIds, scope.depots, scope.allDepots, scope.dataset.sidings.size(), dataset.sidings.size());
			dataset = scope.dataset;
			data = dataset.toBytes();
		}
		final long dayStart = LocalDateTime.of(day, java.time.LocalTime.of(dayStartHour, 0)).atZone(ZoneId.of(timeZone)).toInstant().toEpochMilli();
		System.out.printf("%d routes, %d depots, %d sidings, operating day of %s from %02d:00 %s%n", dataset.routes.size(), dataset.depots.size(), dataset.sidings.size(), day, dayStartHour, timeZone);

		// Throat locks only where the depots sharing track all run by duties (LockNetworks)
		final transitsim.network.LockNetworks.Result lockResult = dataset.getLockNetworks().result();
		System.out.printf("%d track networks, %d shared with depots not running by duties: throat locks off for %d of %d depots%n", lockResult.networks, lockResult.mixed.size(), lockResult.offDepots.size(), dataset.depots.size());
		final long buildStart = System.nanoTime();
		// With the trains that stayed on the line overnight where they stood (2026-11-09)
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(transitsim.sim.DayRun.startOfDay(data, dayStart));
		final long runStart = System.nanoTime();
		// -Dmtr.simHours=N simulates only the first N hours, to compare runs of a big world quickly
		final long hours = Long.getLong("mtr.simHours", 24L);
		// --outs: each way out of a depot, from leaving the siding to leaving the first stop, against the plan (2026-11-13)
		final OutLegs outLegs = flags.contains("--outs") ? new OutLegs() : null;
		// With --outs, what the depots' recorders measure too (D1), for comparing with the legs; with -Dmtr.buffersFile, the
		// ways out also correct themselves as the day goes, as the game's depots do (OutLeads, 2026-11-13)
		final transitsim.sim.BufferRecorder outBuffers = new transitsim.sim.BufferRecorder();
		final transitsim.sim.OutLeads outLeads = new transitsim.sim.OutLeads();
		final Map<Long, transitsim.sim.DepotBoard> sidingBoards = new HashMap<>();
		final List<String> outChanges = new ArrayList<>();
		if (outLegs != null) {
			simulator.getRunner().setTrainListener(outLegs::onEvent);
			simulator.getRunner().addTrainListener((event, train) -> sidingBoards.putIfAbsent(train.getSidingId(), train.getDepot()));
			simulator.getRunner().recordBuffers(outBuffers);
			if (!buffersFile.isEmpty()) {
				outBuffers.outListener = (key, lateness) -> {
					final transitsim.sim.DepotBoard board = sidingBoards.get(transitsim.plan.Buffers.parse(key)[1]);
					if (board == null) {
						return;
					}
					final Map<String, Long> millis = new TreeMap<>(board.dispatcher.addedBuffers.getMillis());
					final long before = millis.getOrDefault(key, 0L);
					final long adopt = outLeads.add(key, lateness, before);
					if (adopt >= 0) {
						if (adopt > 0) {
							millis.put(key, adopt);
						} else {
							millis.remove(key);
						}
						board.dispatcher.addedBuffers = millis.isEmpty() ? transitsim.plan.Buffers.NONE : new transitsim.plan.Buffers(millis);
						outChanges.add(clock(simulator.getRunner().getMillis()) + " " + key + " " + before / 1000 + "s -> " + adopt / 1000 + "s");
					}
				};
			}
		}
		// --late: each departure from a stop against the plan of the service the train is on then, and the latest ones (0.81.0)
		final List<String> lateDepartures = new ArrayList<>();
		final Map<Long, Long> latestByRoute = new TreeMap<>();
		if (flags.contains("--late")) {
			simulator.getRunner().addTrainListener((event, train) -> {
				if (event.type != transitsim.motion.MotionEvent.Type.DEPARTED || event.rail < 0 || event.rail >= train.getPath().size()) {
					return;
				}
				final SimDataset.DataTrack track = train.getPath().get(event.rail);
				if (track.getStopId() == 0 || track.getDwellTime() <= 0 || track.isSidingTrack()) {
					return;
				}
				final transitsim.plan.RouteTimetable.StopPlan plan = train.service.getPlanAt(event.rail);
				if (plan == null) {
					return;
				}
				final long late = event.millis - train.service.getPlannedMillis(plan.departureMillis);
				latestByRoute.merge(train.service.getTripRouteId(), late, Math::max);
				if (late > 60000) {
					lateDepartures.add(clock(event.millis) + " train " + train.id + " route " + train.service.getTripRouteId() + " rail " + event.rail + " stop " + track.getStopId() + " +" + late / 1000 + " s");
				}
			});
		}
		final RunInvariants<SimDataset.DataTrack> invariants = check ? new RunInvariants<>(simulator.getRunner()) : null;
		if (invariants == null) {
			simulator.advanceTo(dayStart + hours * HOUR);
		} else {
			for (long time = dayStart; time < dayStart + hours * HOUR; time += 1000) {
				simulator.advanceTo(time);
				invariants.check();
			}
		}
		final long runEnd = System.nanoTime();
		if (flags.contains("--late")) {
			latestByRoute.forEach((routeId, late) -> System.out.println("LATE route " + routeId + " at most +" + late / 1000 + " s"));
			lateDepartures.stream().limit(40).forEach(line -> System.out.println("LATE " + line));
		}

		final List<SimEvent> events = simulator.events(0);
		final Map<SimEvent.Kind, Integer> kinds = new TreeMap<>();
		final Set<Long> trains = new TreeSet<>();
		for (final SimEvent event : events) {
			kinds.merge(event.kind, 1, Integer::sum);
			trains.add(event.trainId);
			if (printEvents) {
				System.out.println(clock(event.time) + " " + event.kind + " train " + event.trainId + " rail " + event.rail + (event.reason.isEmpty() ? "" : " " + event.reason));
			}
		}
		System.out.printf("%d trains ran; %s%n", trains.size(), kinds);
		// How long trains run between stops and stand at them: with an early threshold they run faster and stand longer
		final Map<Long, Long> lastDepart = new java.util.HashMap<>();
		final Map<Long, Long> lastArrive = new java.util.HashMap<>();
		long runMillis = 0, runs = 0, standMillis = 0, stands = 0;
		for (final SimEvent event : events) {
			if (event.kind == SimEvent.Kind.ARRIVE) {
				final Long departed = lastDepart.remove(event.trainId);
				if (departed != null) {
					runMillis += event.time - departed;
					runs++;
				}
				lastArrive.put(event.trainId, event.time);
			} else if (event.kind == SimEvent.Kind.DEPART) {
				final Long arrived = lastArrive.remove(event.trainId);
				if (arrived != null) {
					standMillis += event.time - arrived;
					stands++;
				}
				lastDepart.put(event.trainId, event.time);
			}
		}
		System.out.printf("Between stops: %d runs, %.1f s on average; at stops: %d stops, %.1f s on average%n", runs, runs == 0 ? 0 : runMillis / 1000.0 / runs, stands, stands == 0 ? 0 : standMillis / 1000.0 / stands);
		// -Pmtr.debugPaths=true lists every rail of each train's path at the end, with where it runs (2026-11-14, looking for
		// where line 6's short routes of the test lines turn back)
		if (Boolean.getBoolean("mtr.debugPaths")) {
			for (final transitsim.sim.TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				final java.util.List<SimDataset.DataTrack> path = train.getPath();
				System.out.printf("path of train %d: %d rails%n", train.id, path.size());
				for (int i = 0; i < path.size(); i++) {
					final SimDataset.DataTrack each = path.get(i);
					System.out.printf("    %d %s -> %s%s%n", i, pos(each.getStartNode()), pos(each.getEndNode()), each.getStopId() != 0 ? " stop " + each.getStopId() + " dwell " + each.getDwellTime() : each.getDwellTime() > 0 ? " turn, dwell " + each.getDwellTime() : "");
				}
			}
		}
		// -Pmtr.debugStuck=true lists trains standing between stations at the end, and what is ahead of them
		// =all lists those standing at platforms too, as a train waiting at one may hold up one between stations
		final boolean stuckAll = "all".equals(System.getProperty("mtr.debugStuck"));
		if (Boolean.getBoolean("mtr.debugStuck") || stuckAll) {
			int listed = 0;
			for (final transitsim.sim.TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				if (listed >= 400 || !train.isOnRoute() || train.getSpeed() > 0) {
					continue;
				}
				final int head = train.getHeadRail();
				final java.util.List<SimDataset.DataTrack> path = train.getPath();
				if (head < 0 || head >= path.size() || !stuckAll && (path.get(head).isPlatformTrack() || path.get(head).isSidingTrack())) {
					continue;
				}
				listed++;
				final SimDataset.DataTrack track = path.get(head);
				final SimDataset.DataTrack next = head + 1 < path.size() ? path.get(head + 1) : null;
				// Who has what is ahead: the first taken unit of the next rail, or of its own rail ahead
				long blocker = 0;
				if (next != null) {
					for (final java.util.UUID unit : transitsim.network.BlockSections.getPieces(next.getTrackKey(), next.getTrackLength(), next.canAccelerate() ? next.getMaxSpeed() : -1, next.getStartNode() < next.getEndNode(), 0, next.getTrackLength())) {
						final long occupant = simulator.getRunner().getOccupant(unit);
						if (occupant != 0 && occupant != train.id) {
							blocker = occupant;
							break;
						}
					}
				}
				for (final java.util.UUID unit : transitsim.network.BlockSections.getPieces(track.getTrackKey(), track.getTrackLength(), track.canAccelerate() ? track.getMaxSpeed() : -1, track.getStartNode() < track.getEndNode(), 0, track.getTrackLength())) {
					final long occupant = simulator.getRunner().getOccupant(unit);
					if (blocker == 0 && occupant != 0 && occupant != train.id) {
						blocker = occupant;
					}
				}
				System.out.printf("blocker of %d is %d%n", train.id, blocker);
				// Where it stands and what is next, as block positions (x, y, z), to find the place in the game
				System.out.printf("  at %s -> %s, next %s -> %s%n", pos(track.getStartNode()), pos(track.getEndNode()), next == null ? "-" : pos(next.getStartNode()), next == null ? "-" : pos(next.getEndNode()));
				System.out.printf("  route %d, duty %d of siding %d%n", train.service.getTripRouteId(), train.service.getDutySlot(), train.getSidingId());
				// What it waits for at a stop or a turn back rail: its duty's departure, when to leave the rail for its next segment
				System.out.printf("  duty departure %s, leave turn back rail %s, now %s%n", clock(train.service.getDutyDepartureMillis()), clock(train.service.getTurnBackLeaveMillis()), clock(train.getMillis()));
				// =all also gives what the arbiter granted it, each by the rail of its path it is on, or the kind of resource
				if (stuckAll) {
					final StringBuilder held = new StringBuilder("  holds");
					for (final transitsim.sim.Arbiter.Claim<java.util.UUID> claim : simulator.getRunner().getReservations().getHeldClaims(train.id)) {
						String where = claim.kind + ":" + claim.resource + (claim.way != 0 ? "/" + claim.way : "");
						for (int i = 0; i < path.size(); i++) {
							final SimDataset.DataTrack each = path.get(i);
							if (transitsim.run.PathClaims.directionOf(each.getTrackKey()).equals(claim.resource)) {
								where = "dir@" + i + (claim.way > 0 ? "+" : claim.way < 0 ? "-" : "both");
								break;
							}
							if (transitsim.network.BlockSections.getPieces(each.getTrackKey(), each.getTrackLength(), each.canAccelerate() ? each.getMaxSpeed() : -1, each.getStartNode() < each.getEndNode(), 0, each.getTrackLength()).contains(claim.resource)) {
								where = "rail@" + i;
								break;
							}
						}
						held.append(' ').append(where);
					}
					System.out.println(held);
				}
				// =all also gives the way it came and goes, a few rails each side, with the stops marked
				if (stuckAll) {
					for (int i = Math.max(0, head - 14); i <= Math.min(path.size() - 1, head + 6); i++) {
						final SimDataset.DataTrack each = path.get(i);
						// The painted signal block the rail is in, by the first of its units, or - for none
						final java.util.List<java.util.UUID> block = simulator.getRunner().getBlockSections().getSection(each.getTrackKey());
						System.out.printf("    %s%d %s -> %s%s%s%s%n", i == head ? "*" : " ", i, pos(each.getStartNode()), pos(each.getEndNode()), each.getStopId() != 0 ? " stop " + each.getStopId() + " dwell " + each.getDwellTime() : each.getDwellTime() > 0 ? " turn, dwell " + each.getDwellTime() : "", block == null ? "" : " block " + block.get(0).toString().substring(0, 8) + "/" + block.size(), each.isTwoWay() ? " two-way " + each.getConvention() : " one-way");
					}
				}
				System.out.printf("stuck %d head rail %d/%d len %.0f speed %.2f accel %b pieces %d progress %.1f own %.1f nextBlocked %b nextLen %.0f nextPieces %d nextPlatform %b blockedRail %d nextStop %d%n",
						train.id, head, path.size(), track.getTrackLength(), track.getMaxSpeed(), track.canAccelerate(), transitsim.network.BlockSections.getPieceCount(track.getTrackLength(), track.canAccelerate() ? track.getMaxSpeed() : -1), train.getRailProgress(),
						train.getSectionBlockedPosition(head, train.getRailProgress()), next != null && train.isRailBlocked(head + 1), next == null ? 0 : next.getTrackLength(), next == null ? 0 : transitsim.network.BlockSections.getPieceCount(next.getTrackLength(), next.canAccelerate() ? next.getMaxSpeed() : -1), next != null && next.isPlatformTrack(), train.getBlockedRail(), train.getNextStoppingIndex());
			}
		}
		if (outLegs != null) {
			outLegs.print();
			System.out.println("Measured buffers (D1, kind:from:to = ms): " + outBuffers.getBuffers().getMillis());
			if (!buffersFile.isEmpty()) {
				outChanges.forEach(change -> System.out.println("Way out corrected: " + change));
				System.out.println("Ways out leaving earlier didn't help (key = still late ms): " + outLeads.getIneffective());
				// What the depots ended the day with, for the next run
				adoptedOuts.clear();
				new HashSet<>(sidingBoards.values()).forEach(board -> board.dispatcher.addedBuffers.getMillis().forEach((key, millis) -> {
					if (transitsim.plan.Buffers.parse(key)[0] == transitsim.plan.Buffers.OUT) {
						adoptedOuts.put(key, millis);
					}
				}));
				final StringBuilder text = new StringBuilder();
				adoptedOuts.forEach((key, millis) -> text.append(key).append('=').append(millis).append(System.lineSeparator()));
				java.nio.file.Files.write(java.nio.file.Paths.get(buffersFile), text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		// --origins: how each service left its first stop against its service time and its plan (0.66.4), to find trains
		// leaving early
		if (flags.contains("--origins")) {
			final DayRun originRun = DayRun.run(dataset, dayStart, Collections.emptySet(), Collections.emptyList(), () -> false);
			final Map<String, Integer> buckets = new TreeMap<>();
			int listed = 0;
			for (final DayRun.Trip trip : originRun.trips) {
				if (trip.stops.isEmpty() || trip.stops.get(0).departureMillis < 0) {
					continue;
				}
				final DayRun.Stop first = trip.stops.get(0);
				final long againstService = (first.departureMillis - trip.serviceMillis) / 1000;
				final long againstPlan = first.plannedDepartureMillis < 0 ? Long.MIN_VALUE : (first.departureMillis - first.plannedDepartureMillis) / 1000;
				final String bucket = againstPlan == Long.MIN_VALUE ? "no plan" : againstPlan < -2 ? "early" : againstPlan > 30 ? "late" : "on time";
				buckets.merge(bucket + (againstService < -2 ? ", before service" : ""), 1, Integer::sum);
				if ((againstService < -2 || againstPlan != Long.MIN_VALUE && againstPlan < -2) && listed++ < 60) {
					System.out.printf("ORIGIN route %d service %s train %d stop %d platform %d: arrived %s, left %s, planned %s (%+d s), service %+d s%n", trip.routeId, clock(trip.serviceMillis), trip.trainId, first.stopIndex, first.platformId, clock(first.arrivalMillis), clock(first.departureMillis), first.plannedDepartureMillis < 0 ? "-" : clock(first.plannedDepartureMillis), againstPlan == Long.MIN_VALUE ? 0 : againstPlan, againstService);
				}
			}
			System.out.printf("ORIGINS %d trips: %s%n", originRun.trips.size(), buckets);
		}
		events.stream().filter(event -> event.kind == SimEvent.Kind.DEADLOCK || event.kind == SimEvent.Kind.CONFLICT).forEach(event -> System.out.println("  " + clock(event.time) + " " + event.kind + " " + event.reason));
		final long ticks = hours * HOUR / 50;
		System.out.printf("Built in %d ms, simulated in %d ms; %.1f%% of %d ticks skipped, trains moved in %.2f%% of ticks%n", (runStart - buildStart) / 1000000, (runEnd - runStart) / 1000000, 100.0 * simulator.getRunner().getSkippedTicks() / ticks, ticks, trains.isEmpty() ? 0 : 100.0 * simulator.getRunner().getTrainTicks() / ticks / trains.size());
		if (invariants != null) {
			final long deadlocks = kinds.getOrDefault(SimEvent.Kind.DEADLOCK, 0);
			System.out.printf("CHECK %d rules broken, %d trains stuck over 10 minutes, %d deadlocks%n", invariants.getViolations().size(), invariants.getStuck().size(), deadlocks);
			invariants.getViolations().stream().limit(30).forEach(line -> System.out.println("  broken: " + line));
			invariants.getStuck().values().stream().limit(30).forEach(line -> System.out.println("  stuck: " + line));
			// How the day went against the timetables, run again on its own
			final DayRun dayRun = DayRun.run(dataset, dayStart, Collections.emptySet(), Collections.emptyList(), () -> false);
			final Map<DayRun.ProblemKind, Integer> problems = new TreeMap<>();
			dayRun.problems.forEach(problem -> problems.merge(problem.kind, 1, Integer::sum));
			System.out.printf("DAY %d trips, problems %s%n", dayRun.trips.size(), problems);
			if (!invariants.getViolations().isEmpty() || deadlocks > 0) {
				System.exit(1);
			}
		}
	}

	/**
	 * For each depot's routes, how many depots and sidings the editor's simulated day of them runs, smallest first.
	 */
	private static void printScopes(SimDataset dataset) {
		final List<String> lines = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		for (final SimDataset.DepotData depot : dataset.depots) {
			final Set<Long> routeIds = new TreeSet<>();
			for (final long routeId : depot.routeIds) {
				routeIds.add(routeId);
			}
			if (routeIds.isEmpty() || !seen.add(routeIds.toString())) {
				continue;
			}
			final SimScope scope = SimScope.of(dataset, routeIds);
			final Set<Long> own = new HashSet<>();
			for (final SimDataset.DepotData each : dataset.depots) {
				if (Arrays.stream(each.routeIds).anyMatch(routeIds::contains)) {
					own.add(each.id);
				}
			}
			final long ownSidings = dataset.sidings.stream().filter(siding -> own.contains(siding.depotId)).count();
			final long ownTrains = dataset.trains == null ? -1 : dataset.trains.trains.stream().filter(train -> scope.dataset.sidings.stream().anyMatch(siding -> siding.id == train.sidingId && own.contains(siding.depotId))).count();
			final long trains = dataset.trains == null ? -1 : dataset.trains.trains.stream().filter(train -> scope.dataset.sidings.stream().anyMatch(siding -> siding.id == train.sidingId)).count();
			lines.add(String.format("%5d depots %5d sidings %5d trains | own %d (direct %d) depots %d sidings %d trains | depot %d routes %s", scope.depots, scope.dataset.sidings.size(), trains, scope.ownDepots, own.size(), ownSidings, ownTrains, depot.id, routeIds.toString().replace(" ", "")));
		}
		Collections.sort(lines);
		lines.forEach(System.out::println);
	}

	private static byte[] read(String path) throws Exception {
		try (final Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path); final PreparedStatement statement = connection.prepareStatement("SELECT data FROM sim_data WHERE key = 'world'")) {
			try (final ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getBytes(1) : null;
			}
		} catch (Exception e) {
			if (e.getMessage() != null && e.getMessage().contains("no such table")) {
				return null;
			}
			throw e;
		}
	}

	/**
	 * @return a node as the block position it is packed from, as Minecraft packs one into a long
	 */
	private static String pos(long node) {
		final long x = node >> 38;
		final long y = node << 52 >> 52;
		final long z = node << 26 >> 38;
		return "(" + x + ", " + y + ", " + z + ")";
	}

	private static String clock(long millis) {
		return java.time.Instant.ofEpochMilli(millis).atZone(OperatingDay.getTimeZone()).toLocalTime().toString();
	}

	/**
	 * Ways out of depots as they ran: from the train leaving its siding to it leaving the first stop of its duty, the time
	 * the duty allowed (its departure there less the time it left), how long it stood short of taken track on the way, and
	 * how often it stopped to turn back. Grouped by siding, worst first.
	 */
	private static final class OutLegs {

		private final java.util.Map<Long, Leg> open = new java.util.HashMap<>();
		private final List<Leg> done = new java.util.ArrayList<>();

		private void onEvent(transitsim.motion.MotionEvent event, transitsim.sim.TickRunner<SimDataset.DataTrack>.HeadlessTrain train) {
			final List<SimDataset.DataTrack> path = train.getPath();
			final int rail = event.rail;
			if (rail < 0 || rail >= path.size()) {
				return;
			}
			final Leg leg = open.get(train.id);
			switch (event.type) {
				case DEPARTED:
					if (rail == 0 && path.get(0).isSidingTrack()) {
						final Leg started = new Leg(train.id, train.getSidingId(), event.millis, train.service.getLeadMillis());
						open.put(train.id, started);
					} else if (leg != null) {
						if (leg.holdStart >= 0) {
							leg.holdMillis += event.millis - leg.holdStart;
							leg.holdStart = -1;
						} else if (leg.arrived >= 0) {
							leg.departed = event.millis;
							open.remove(train.id);
							done.add(leg);
						}
					}
					break;
				case STOPPED:
					if (leg == null) {
						break;
					}
					final SimDataset.DataTrack track = path.get(rail);
					if (track.getDwellTime() <= 0) {
						leg.holdStart = event.millis;
						leg.holds++;
					} else if (track.getStopId() == 0) {
						leg.turns++;
					} else if (leg.arrived < 0) {
						leg.arrived = event.millis;
						leg.stopRail = rail;
						// The first stop's planned departure, as the trip's report takes it
						final transitsim.plan.RouteTimetable.StopPlan plan = train.service.getPlanAt(rail);
						leg.planned = plan == null ? 0 : train.service.getPlannedMillis(plan.departureMillis);
						leg.routeId = train.service.getTripRouteId();
					}
					break;
				case RETURNED:
					open.remove(train.id);
					break;
				default:
					break;
			}
		}

		private void print() {
			System.out.printf("OUTS %d ways out ran (%d not finished)%n", done.size(), open.size());
			final java.util.Map<Long, List<Leg>> bySiding = new java.util.TreeMap<>();
			done.forEach(leg -> bySiding.computeIfAbsent(leg.sidingId, key -> new java.util.ArrayList<>()).add(leg));
			final List<java.util.Map.Entry<Long, List<Leg>>> entries = new java.util.ArrayList<>(bySiding.entrySet());
			entries.sort((a, b) -> Long.compare(b.getValue().stream().mapToLong(Leg::late).max().orElse(0), a.getValue().stream().mapToLong(Leg::late).max().orElse(0)));
			for (final java.util.Map.Entry<Long, List<Leg>> entry : entries) {
				final List<Leg> legs = entry.getValue();
				final java.util.LongSummaryStatistics late = legs.stream().mapToLong(Leg::late).summaryStatistics();
				System.out.printf("OUTS siding %d (route %s): %d legs, late at first stop avg %+.0f s max %+.0f s; allowed avg %.0f s, planned lead to arrive %.0f s, ran avg %.0f s (to arrive %.0f s), held avg %.0f s in %.1f stops, turned back %.1f times%n",
						entry.getKey(), legs.stream().map(leg -> String.valueOf(leg.routeId)).distinct().collect(java.util.stream.Collectors.joining(",")), legs.size(), late.getAverage() / 1000, late.getMax() / 1000.0,
						legs.stream().mapToLong(Leg::allowed).average().orElse(0) / 1000, legs.stream().mapToLong(leg -> leg.lead).average().orElse(0) / 1000, legs.stream().mapToLong(leg -> leg.departed - leg.left).average().orElse(0) / 1000,
						legs.stream().mapToLong(leg -> leg.arrived - leg.left).average().orElse(0) / 1000,
						legs.stream().mapToLong(leg -> leg.holdMillis).average().orElse(0) / 1000, legs.stream().mapToInt(leg -> leg.holds).average().orElse(0), legs.stream().mapToInt(leg -> leg.turns).average().orElse(0));
				legs.stream().filter(leg -> leg.late() > 30000).limit(5).forEach(leg -> System.out.printf("OUTS   train %d left %s, planned at first stop %s, arrived %s, left %s (%+d s), held %d s in %d stops, turned %d%n",
						leg.trainId, clock(leg.left), clock(leg.planned), clock(leg.arrived), clock(leg.departed), leg.late() / 1000, leg.holdMillis / 1000, leg.holds, leg.turns));
			}
		}

		private static final class Leg {
			private final long trainId, sidingId, left, lead;
			private long planned, routeId, arrived = -1, departed = -1, holdStart = -1, holdMillis;
			private int holds, turns, stopRail;

			private Leg(long trainId, long sidingId, long left, long lead) {
				this.trainId = trainId;
				this.sidingId = sidingId;
				this.left = left;
				this.lead = lead;
			}

			private long late() {
				return planned <= 0 ? 0 : departed - planned;
			}

			private long allowed() {
				return planned <= 0 ? 0 : planned - left;
			}
		}
	}
}
