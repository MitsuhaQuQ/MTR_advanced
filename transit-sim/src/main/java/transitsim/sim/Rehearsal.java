package transitsim.sim;

import java.util.*;

/**
 * The rehearsal of a plan (2026-11-15, user: rehearse whether the timetable and duties can run as planned and warn while
 * planning, without forcing them to be on time): a {@link DayRun} of the lines concerned summed up as warnings, each a
 * route's late services, trains held on the way, deadlocks or services no train ran, with what held the trains up most.
 * Nothing in the plan changes. Plain Java; the game runs it in the background after a depot's duties change and sends the
 * warnings to the editor.
 */
public final class Rehearsal {

	/**
	 * At most this many warnings are kept, the worst first.
	 */
	public static final int MAX_WARNINGS = 40;
	private static final String SEPARATOR = "|";

	public enum Kind {
		/**
		 * Services of a route later than planned by more than {@link DayRun#REPORT_MILLIS}.
		 */
		LATE,
		/**
		 * Trains of a route held on the line or short of a platform for longer than that.
		 */
		HELD,
		/**
		 * Trains waiting for each other in a ring.
		 */
		DEADLOCK,
		/**
		 * Services of a route no train ran.
		 */
		NOT_RUN
	}

	public static final class Warning {

		public final Kind kind;
		/**
		 * The route, or 0 for a deadlock.
		 */
		public final long routeId;
		/**
		 * How many services (or holds, or deadlocks) of it.
		 */
		public final int count;
		/**
		 * The most late or held, in milliseconds.
		 */
		public final long maxMillis;
		/**
		 * When the worst one happened, milliseconds after the start of the operating day.
		 */
		public final long timeOfDay;
		/**
		 * The service of the worst one, milliseconds after the start of the operating day, or -1.
		 */
		public final long serviceOfDay;
		/**
		 * Where the worst one was: the stop of the route at or before it, or -1, and the platform, or 0.
		 */
		public final int stopIndex;
		public final long platformId;
		/**
		 * What held the trains up most: the route of the holding train's service (0 if it ran no service, as a train
		 * going out of or back to its depot), its service as above or -1, the kind of thing it held (empty if no train
		 * held it), and the platform that was, or 0; {@link #causeKnown} false if nothing held them up long enough.
		 */
		public final boolean causeKnown;
		public final long causeRouteId;
		public final long causeServiceOfDay;
		public final boolean causeByTrain;
		public final String causeKind;
		public final long causePlatformId;
		/**
		 * For a deadlock, the trains in it.
		 */
		public final String detail;

		public Warning(Kind kind, long routeId, int count, long maxMillis, long timeOfDay, long serviceOfDay, int stopIndex, long platformId, boolean causeKnown, long causeRouteId, long causeServiceOfDay, boolean causeByTrain, String causeKind, long causePlatformId, String detail) {
			this.kind = kind;
			this.routeId = routeId;
			this.count = count;
			this.maxMillis = maxMillis;
			this.timeOfDay = timeOfDay;
			this.serviceOfDay = serviceOfDay;
			this.stopIndex = stopIndex;
			this.platformId = platformId;
			this.causeKnown = causeKnown;
			this.causeRouteId = causeRouteId;
			this.causeServiceOfDay = causeServiceOfDay;
			this.causeByTrain = causeByTrain;
			this.causeKind = causeKind;
			this.causePlatformId = causePlatformId;
			this.detail = detail;
		}

		/**
		 * @return the warning as one line, as the game sends it
		 */
		public String encode() {
			return String.join(SEPARATOR, kind.name(), String.valueOf(routeId), String.valueOf(count), String.valueOf(maxMillis), String.valueOf(timeOfDay), String.valueOf(serviceOfDay), String.valueOf(stopIndex), String.valueOf(platformId),
					causeKnown ? "1" : "0", String.valueOf(causeRouteId), String.valueOf(causeServiceOfDay), causeByTrain ? "1" : "0", causeKind, String.valueOf(causePlatformId), detail.replace(SEPARATOR, " "));
		}

		/**
		 * @return the warning in a line {@link #encode} wrote, or null if it isn't one
		 */
		public static Warning decode(String line) {
			final String[] parts = line.split("\\|", -1);
			if (parts.length < 15) {
				return null;
			}
			try {
				return new Warning(Kind.valueOf(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]), Long.parseLong(parts[4]), Long.parseLong(parts[5]), Integer.parseInt(parts[6]), Long.parseLong(parts[7]),
						"1".equals(parts[8]), Long.parseLong(parts[9]), Long.parseLong(parts[10]), "1".equals(parts[11]), parts[12], Long.parseLong(parts[13]), parts[14]);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	private Rehearsal() {
	}

	/**
	 * Rehearses a day of the lines concerned.
	 *
	 * @param routes the routes to warn of, those of the depots whose plan changed
	 */
	public static List<Warning> run(SimDataset dataset, long dayStart, Set<Long> routes, java.util.function.BooleanSupplier cancelled) {
		final SimScope scope = SimScope.of(dataset, routes);
		final DayRun dayRun = DayRun.run(scope.dataset, dayStart, routes, Collections.emptyList(), cancelled);
		return dayRun.cancelled ? null : summarize(dayRun);
	}

	/**
	 * @return the day's problems as warnings, by route and kind, the worst first, at most {@link #MAX_WARNINGS}
	 */
	public static List<Warning> summarize(DayRun dayRun) {
		final Map<String, List<DayRun.Problem>> groups = new LinkedHashMap<>();
		final List<Warning> warnings = new ArrayList<>();
		for (final DayRun.Problem problem : dayRun.problems) {
			switch (problem.kind) {
				case LATE:
					groups.computeIfAbsent(Kind.LATE + "@" + problem.routeId, key -> new ArrayList<>()).add(problem);
					break;
				case WAITING_FOR_PLATFORM:
				case HELD_ON_LINE:
					groups.computeIfAbsent(Kind.HELD + "@" + problem.routeId, key -> new ArrayList<>()).add(problem);
					break;
				case NOT_RUN:
					groups.computeIfAbsent(Kind.NOT_RUN + "@" + problem.routeId, key -> new ArrayList<>()).add(problem);
					break;
				case DEADLOCK:
					warnings.add(new Warning(Kind.DEADLOCK, 0, 1, 0, problem.time - dayRun.dayStart, -1, -1, 0, false, 0, -1, false, "", 0, problem.detail));
					break;
				default:
					break;
			}
		}
		groups.forEach((key, problems) -> {
			final Kind kind = Kind.valueOf(key.substring(0, key.indexOf('@')));
			DayRun.Problem worst = problems.get(0);
			for (final DayRun.Problem problem : problems) {
				if (problem.durationMillis > worst.durationMillis) {
					worst = problem;
				}
			}
			// The cause most of them had, by how long it held them up altogether
			final Map<String, Long> causeMillis = new HashMap<>();
			final Map<String, DayRun.Cause> causes = new HashMap<>();
			for (final DayRun.Problem problem : problems) {
				if (problem.cause != null) {
					final String causeKey = problem.cause.routeId + "@" + (problem.cause.trainId != 0) + "@" + problem.cause.kind + "@" + problem.cause.platformId;
					causeMillis.merge(causeKey, problem.cause.millis, Long::sum);
					causes.putIfAbsent(causeKey, problem.cause);
					// The worst one's own holder's service, if it is this one
					if (problem == worst) {
						causes.put(causeKey, problem.cause);
					}
				}
			}
			final String causeKey = causeMillis.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
			final DayRun.Cause cause = causeKey == null ? null : causes.get(causeKey);
			// The holder's own service only when it is the worst one's: another service of the same route held the others
			final boolean worstsOwn = cause != null && cause == worst.cause;
			final long dayStart = dayRun.dayStart;
			warnings.add(new Warning(kind, worst.routeId, problems.size(), kind == Kind.NOT_RUN ? 0 : worst.durationMillis, worst.time - dayStart, worst.serviceMillis > 0 ? worst.serviceMillis - dayStart : -1, worst.stopIndex, worst.platformId,
					cause != null, cause == null ? 0 : cause.routeId, !worstsOwn || cause.serviceMillis <= 0 ? -1 : cause.serviceMillis - dayStart, cause != null && cause.trainId != 0, cause == null ? "" : cause.kind, cause == null ? 0 : cause.platformId, ""));
		});
		// Deadlocks first, then services no train ran, then the most late or held
		warnings.sort(Comparator.comparingInt((Warning warning) -> warning.kind == Kind.DEADLOCK ? 0 : warning.kind == Kind.NOT_RUN ? 1 : 2).thenComparing(Comparator.comparingLong((Warning warning) -> warning.maxMillis).reversed()));
		return warnings.size() > MAX_WARNINGS ? new ArrayList<>(warnings.subList(0, MAX_WARNINGS)) : warnings;
	}
}
