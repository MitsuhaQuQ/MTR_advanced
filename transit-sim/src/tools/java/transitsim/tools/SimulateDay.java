package transitsim.tools;

import transitsim.plan.OperatingDay;
import transitsim.sim.EventSimulator;
import transitsim.sim.SimDataset;
import transitsim.sim.SimEvent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

		final byte[] data = read(args[0]);
		if (data == null) {
			System.out.println("The database has no simulation data. Save the world with this version of the mod first.");
			return;
		}
		final SimDataset dataset = SimDataset.fromBytes(data);
		final long dayStart = LocalDateTime.of(day, java.time.LocalTime.of(dayStartHour, 0)).atZone(ZoneId.of(timeZone)).toInstant().toEpochMilli();
		System.out.printf("%d routes, %d depots, %d sidings, operating day of %s from %02d:00 %s%n", dataset.routes.size(), dataset.depots.size(), dataset.sidings.size(), day, dayStartHour, timeZone);

		final long buildStart = System.nanoTime();
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(data, dayStart));
		final long runStart = System.nanoTime();
		simulator.advanceTo(dayStart + 24 * HOUR);
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
		events.stream().filter(event -> event.kind == SimEvent.Kind.DEADLOCK || event.kind == SimEvent.Kind.CONFLICT).forEach(event -> System.out.println("  " + clock(event.time) + " " + event.kind + " " + event.reason));
		final long ticks = 24 * HOUR / 50;
		System.out.printf("Built in %d ms, simulated in %d ms; %.1f%% of %d ticks skipped, trains moved in %.2f%% of ticks%n", (runStart - buildStart) / 1000000, (runEnd - runStart) / 1000000, 100.0 * simulator.getRunner().getSkippedTicks() / ticks, ticks, trains.isEmpty() ? 0 : 100.0 * simulator.getRunner().getTrainTicks() / ticks / trains.size());
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
