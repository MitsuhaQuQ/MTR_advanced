package transitsim.tools;

import transitsim.plan.OperatingDay;
import transitsim.sim.EventSimulator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Simulates one operating day of a saved world without the game (S4e), from what the game writes into its database
 * ({@link SimDataset}), and prints what the trains did and how long it took.
 * <p>
 * Arguments: the database, then optionally the day ({@code yyyy-mm-dd}, today if left out), the time zone ({@code UTC}
 * if left out), the hour the operating day starts at (3 if left out) and {@code --events} to print every event.
 */
public final class SimulateDay {

	private static final long HOUR = 60 * 60 * 1000;

	private SimulateDay() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("Usage: SimulateDay <mtr.db> [yyyy-mm-dd] [time zone] [day start hour] [--events]");
			return;
		}
		final boolean printEvents = args[args.length - 1].equals("--events");
		final int count = printEvents ? args.length - 1 : args.length;
		final LocalDate day = count > 1 ? LocalDate.parse(args[1]) : LocalDate.now();
		final String timeZone = count > 2 ? args[2] : "UTC";
		final int dayStartHour = count > 3 ? Integer.parseInt(args[3]) : OperatingDay.DEFAULT_DAY_START_HOUR;
		OperatingDay.set(timeZone, dayStartHour);

		byte[] data = read(args[0]);
		if (data == null) {
			System.out.println("The database has no simulation data. Save the world with this version of the mod first.");
			return;
		}
		SimDataset dataset = SimDataset.fromBytes(data);
		// -Dmtr.noLinePools=true runs without trains going back to other depots of their line, to compare
		if (Boolean.getBoolean("mtr.noLinePools")) {
			dataset.sidings.forEach(siding -> siding.linePool = "");
			data = dataset.toBytes();
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
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(data, dayStart));
		final long runStart = System.nanoTime();
		// -Dmtr.simHours=N simulates only the first N hours, to compare runs of a big world quickly
		final long hours = Long.getLong("mtr.simHours", 24L);
		simulator.advanceTo(dayStart + hours * HOUR);
		final long runEnd = System.nanoTime();

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
		// -Pmtr.debugStuck=true lists trains standing between stations at the end, and what is ahead of them
		if (Boolean.getBoolean("mtr.debugStuck")) {
			int listed = 0;
			for (final transitsim.sim.TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				if (listed >= 400 || !train.isOnRoute() || train.getSpeed() > 0) {
					continue;
				}
				final int head = train.getHeadRail();
				final java.util.List<SimDataset.DataTrack> path = train.getPath();
				if (head < 0 || head >= path.size() || path.get(head).isPlatformTrack() || path.get(head).isSidingTrack()) {
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
				System.out.printf("stuck %d head rail %d/%d len %.0f speed %.2f accel %b pieces %d progress %.1f own %.1f nextBlocked %b nextLen %.0f nextPieces %d nextPlatform %b blockedRail %d nextStop %d%n",
						train.id, head, path.size(), track.getTrackLength(), track.getMaxSpeed(), track.canAccelerate(), transitsim.network.BlockSections.getPieceCount(track.getTrackLength(), track.canAccelerate() ? track.getMaxSpeed() : -1), train.getRailProgress(),
						train.getSectionBlockedPosition(head, train.getRailProgress()), next != null && train.isRailBlocked(head + 1), next == null ? 0 : next.getTrackLength(), next == null ? 0 : transitsim.network.BlockSections.getPieceCount(next.getTrackLength(), next.canAccelerate() ? next.getMaxSpeed() : -1), next != null && next.isPlatformTrack(), train.getBlockedRail(), train.getNextStoppingIndex());
			}
		}
		events.stream().filter(event -> event.kind == SimEvent.Kind.DEADLOCK || event.kind == SimEvent.Kind.CONFLICT).forEach(event -> System.out.println("  " + clock(event.time) + " " + event.kind + " " + event.reason));
		final long ticks = hours * HOUR / 50;
		System.out.printf("Built in %d ms, simulated in %d ms; %.1f%% of %d ticks skipped, trains moved in %.2f%% of ticks%n", (runStart - buildStart) / 1000000, (runEnd - runStart) / 1000000, 100.0 * simulator.getRunner().getSkippedTicks() / ticks, ticks, trains.isEmpty() ? 0 : 100.0 * simulator.getRunner().getTrainTicks() / ticks / trains.size());
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

	private static String clock(long millis) {
		return java.time.Instant.ofEpochMilli(millis).atZone(OperatingDay.getTimeZone()).toLocalTime().toString();
	}
}
